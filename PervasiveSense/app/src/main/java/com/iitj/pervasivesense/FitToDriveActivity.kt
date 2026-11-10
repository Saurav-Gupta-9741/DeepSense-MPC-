package com.iitj.pervasivesense

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iitj.pervasivesense.databinding.ActivityFitToDriveBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fit-to-drive screen. Set up (walk, normal balance, contact) until done,
 * then "Before you drive" (check my walk, balance check), "Test it" (three
 * practice tests that are never learned) and any advice.
 * Like the dashboard it only renders [SensingSnapshot.fitToDrive]; the engine
 * decides. The balance test is spoken step by step, because the phone is held
 * against the chest and the screen cannot be read.
 */
class FitToDriveActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_BALANCE = "com.iitj.pervasivesense.extra.START_BALANCE"
        const val EXTRA_DISMISS = "com.iitj.pervasivesense.extra.DISMISS_FIT"
    }

    /** "Test it" results, kept while the app runs (the screen is often closed during a test walk). */
    private object Tests {
        class Result(val text: String, val asExpected: Boolean)
        val results = HashMap<Char, Result>()
        var seenCheck: Long? = null
        var seenBalance: Long? = null
        /** The next balance check is test C. */
        var cPending = false
    }

    private lateinit var binding: ActivityFitToDriveBinding
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var voice: FitVoice? = null
    private var bindingUi = false
    /** The last report rendered (for buttons whose action depends on it). */
    private var lastReport: FitToDriveReport? = null

    // Balance test progress, for the spoken cues.
    private var balanceWasRunning = false
    private var lastBalanceSeen: Long? = null
    private val spokenCues = HashSet<String>()

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> setCarBluetooth(granted) }

    /** SMS is needed for the contact alert; location is optional (added to the message). */
    private val alertPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val ok = ContactAlert.hasSmsPermission(this)
            if (!ok) Toast.makeText(this, R.string.fit_alert_need_sms, Toast.LENGTH_LONG).show()
            setAlertContact(ok)
        }

    /** Optional: lets the test alert try every SIM. Sent either way. */
    private val phoneStatePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> SensingRepository.sendTestAlert() }

    private val exportStudy = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = StudyLog.exportTo(this, uri)
        Toast.makeText(this, if (ok) R.string.fit_study_exported else R.string.fit_study_export_failed, Toast.LENGTH_SHORT).show()
    }

    private val conditionButtons by lazy {
        mapOf(
            StudyCondition.OFF to binding.condOff.id,
            StudyCondition.SOBER to binding.condSober.id,
            StudyCondition.GOGGLES_LOW to binding.condGogglesLow.id,
            StudyCondition.GOGGLES_HIGH to binding.condGogglesHigh.id
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFitToDriveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voice = FitVoice(this)

        bindingUi = true
        binding.switchEnabled.isChecked = FitToDriveStore.isEnabled(this)
        binding.switchCarBluetooth.isChecked = FitToDriveStore.useCarBluetooth(this) &&
            SensingForegroundService.hasBluetoothPermission(this)
        binding.switchAlertContact.isChecked = FitToDriveStore.alertContact(this) && ContactAlert.hasSmsPermission(this)
        binding.btnTestAlert.visibility = if (binding.switchAlertContact.isChecked) View.VISIBLE else View.GONE
        binding.etContactName.setText(FitToDriveStore.contactName(this))
        binding.etContactPhone.setText(FitToDriveStore.contactPhone(this))
        binding.etParticipant.setText(FitToDriveStore.studyParticipant(this))
        binding.toggleCondition.check(conditionButtons.getValue(FitToDriveStore.studyCondition(this)))
        bindingUi = false

        binding.switchEnabled.setOnCheckedChangeListener { _, on -> if (!bindingUi) onEnableToggled(on) }
        binding.switchCarBluetooth.setOnCheckedChangeListener { _, on ->
            if (bindingUi) return@setOnCheckedChangeListener
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !SensingForegroundService.hasBluetoothPermission(this)) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                setCarBluetooth(on)
            }
        }
        binding.switchAlertContact.setOnCheckedChangeListener { _, on -> if (!bindingUi) onAlertToggled(on) }
        binding.btnTestAlert.setOnClickListener {
            if (ContactAlert.hasPhoneStatePermission(this)) SensingRepository.sendTestAlert()
            else phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
        binding.btnAlertCancel.setOnClickListener { SensingRepository.cancelContactAlert() }
        binding.btnStep1.setOnClickListener {
            if (lastReport?.walkSession == true) stopRecording() else recordWalk(CarryPosition.POCKET)
        }
        binding.btnStep1Hand.setOnClickListener { recordWalk(CarryPosition.HAND) }
        binding.btnStep1Body.setOnClickListener { recordWalk(CarryPosition.BODY) }
        binding.btnAddPocket.setOnClickListener { recordWalk(CarryPosition.POCKET) }
        binding.btnAddHand.setOnClickListener { recordWalk(CarryPosition.HAND) }
        binding.btnAddBody.setOnClickListener { recordWalk(CarryPosition.BODY) }
        binding.btnStep3Skip.setOnClickListener {
            FitToDriveStore.setContactSkipped(this, true)
            render(SensingRepository.status.value, SensingRepository.snapshot.value, SensingRepository.message.value)
        }
        binding.btnStep2.setOnClickListener { startBalance(BalancePurpose.REFERENCE) }
        binding.btnRecordAgain.setOnClickListener { startBalance(BalancePurpose.REFERENCE) }
        binding.btnStep3.setOnClickListener { focusContact() }
        binding.btnCheckNow.setOnClickListener {
            if (lastReport?.walkSession == true) { stopRecording(); return@setOnClickListener }
            SensingRepository.requestFitCheck()
            say(getString(checkHow(lastReport, spoken = true)))
        }
        binding.btnSettingsToggle.setOnClickListener { showSettings(binding.cardSettings.visibility != View.VISIBLE) }
        binding.btnBalanceCheck.setOnClickListener { startBalance(BalancePurpose.CHECK) }
        binding.btnBalanceCancel.setOnClickListener { SensingRepository.cancelBalanceTest(); Tests.cPending = false; voice?.stop() }
        binding.btnTestA.setOnClickListener { startPractice(PracticeWalk.NORMAL) }
        binding.btnTestB.setOnClickListener { startPractice(PracticeWalk.WOBBLY) }
        binding.btnTestC.setOnClickListener { Tests.cPending = true; startBalance(BalancePurpose.CHECK, practice = true) }
        binding.btnTestCancel.setOnClickListener { SensingRepository.cancelFitPractice(); voice?.stop() }
        binding.btnSaveContact.setOnClickListener { saveContact() }
        binding.btnCall.setOnClickListener {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(FitToDriveStore.contactPhone(this)))))
        }
        binding.btnMessage.setOnClickListener {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(FitToDriveStore.contactPhone(this))))
                .putExtra("sms_body", getString(R.string.fit_message_text)))
        }
        binding.toggleCondition.addOnButtonCheckedListener { _, id, checked ->
            if (bindingUi || !checked) return@addOnButtonCheckedListener
            val condition = conditionButtons.entries.first { it.value == id }.key
            FitToDriveStore.setStudyParticipant(this, binding.etParticipant.text.toString())
            FitToDriveStore.setStudyCondition(this, condition)
            SensingRepository.refreshFitSettings()
        }
        binding.btnStudyExport.setOnClickListener {
            FitToDriveStore.setStudyParticipant(this, binding.etParticipant.text.toString())
            exportStudy.launch("fit_to_drive_study.csv")
        }
        binding.btnStudyClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.fit_study_clear_confirm)
                .setPositiveButton(R.string.fit_study_clear) { _, _ -> StudyLog.clear(this) }
                .setNegativeButton(R.string.fit_cancel, null)
                .show()
        }
        binding.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fit_reset_confirm_title)
                .setMessage(R.string.fit_reset_confirm_body)
                .setPositiveButton(R.string.fit_reset_ok) { _, _ -> SensingRepository.resetFitToDrive() }
                .setNegativeButton(R.string.fit_cancel, null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SensingRepository.status, SensingRepository.snapshot, SensingRepository.message) { st, snap, msg ->
                    Triple(st, snap, msg)
                }.collect { (st, snap, msg) -> render(st, snap, msg) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SensingRepository.alert.collect { renderAlert(it) }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        SensingRepository.setUiVisible(true)
    }

    override fun onStop() {
        SensingRepository.setUiVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }

    /** Notification actions. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_START_BALANCE, false)) startBalance(BalancePurpose.CHECK)
        if (intent.getBooleanExtra(EXTRA_DISMISS, false)) SensingRepository.dismissFitAdvice()
        intent.removeExtra(EXTRA_START_BALANCE)
        intent.removeExtra(EXTRA_DISMISS)
    }

    // ------------------------------------------------------------------ actions
    private fun onEnableToggled(on: Boolean) {
        if (!on) {
            FitToDriveStore.setEnabled(this, false)
            SensingRepository.setFitToDriveEnabled(false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fit_consent_title)
            .setMessage(R.string.fit_consent_body)
            .setPositiveButton(R.string.fit_consent_ok) { _, _ ->
                FitToDriveStore.setEnabled(this, true)
                SensingRepository.setFitToDriveEnabled(true)
                binding.scroll.smoothScrollTo(0, 0) // the set-up steps are at the top
            }
            .setNegativeButton(R.string.fit_cancel) { _, _ -> setSwitch(false) }
            .setOnCancelListener { setSwitch(false) }
            .show()
    }

    private fun setSwitch(on: Boolean) {
        bindingUi = true
        binding.switchEnabled.isChecked = on
        bindingUi = false
    }

    private fun setCarBluetooth(on: Boolean) {
        FitToDriveStore.setUseCarBluetooth(this, on)
        bindingUi = true
        binding.switchCarBluetooth.isChecked = on
        bindingUi = false
        SensingRepository.refreshFitSettings()
    }

    /** The contact alert needs a saved contact, a phone that can text, and the SMS permission. */
    private fun onAlertToggled(on: Boolean) {
        if (!on) { setAlertContact(false); return }
        when {
            FitToDriveStore.contactPhone(this).isBlank() -> {
                Toast.makeText(this, R.string.fit_alert_need_contact, Toast.LENGTH_LONG).show()
                setAlertContact(false)
                focusContact()
            }
            !ContactAlert.canSendSms(this) -> {
                Toast.makeText(this, R.string.fit_alert_no_telephony, Toast.LENGTH_LONG).show()
                setAlertContact(false)
            }
            else -> alertPermissions.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun setAlertContact(on: Boolean) {
        FitToDriveStore.setAlertContact(this, on)
        bindingUi = true
        binding.switchAlertContact.isChecked = on
        bindingUi = false
        binding.btnTestAlert.visibility = if (on) View.VISIBLE else View.GONE
        SensingRepository.refreshFitSettings()
    }

    /** Sensing is stopped: start it here, or send the user to the dashboard for the permissions. */
    private fun startSensing() {
        val missingActivityPermission = Build.VERSION.SDK_INT >= 34 &&
            !SensingForegroundService.hasActivityRecognitionPermission(this)
        if (missingActivityPermission) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return
        }
        SensingRepository.setStatus(ServiceStatus.STARTING)
        ContextCompat.startForegroundService(this, Intent(this, SensingForegroundService::class.java))
    }

    /** [practice]: "Test it" C, which never arms the driving watch. */
    private fun startBalance(purpose: BalancePurpose, practice: Boolean = false) {
        SensingRepository.startBalanceTest(purpose, practice)
        beep()
    }

    /** The balance check offered after test B counts as test C. */
    private fun startOfferedBalance() {
        if (Tests.results.containsKey('B') && !Tests.results.containsKey('C')) Tests.cPending = true
        startBalance(BalancePurpose.CHECK, practice = Tests.cPending)
    }

    private fun startPractice(kind: PracticeWalk) {
        SensingRepository.startFitPractice(kind)
        say(getString(if (kind == PracticeWalk.NORMAL) R.string.fit_say_test_a else R.string.fit_say_test_b))
    }

    private fun saveContact() {
        val phone = binding.etContactPhone.text.toString().trim()
        val digits = phone.count { it.isDigit() }
        if (phone.isNotEmpty() && (digits < 6 || phone.any { !it.isDigit() && it !in "+-() " })) {
            binding.tilContactPhone.error = getString(R.string.fit_contact_phone_invalid)
            return
        }
        binding.tilContactPhone.error = null
        FitToDriveStore.setContact(this, binding.etContactName.text.toString(), phone)
        hideKeyboard()
        Toast.makeText(this, R.string.fit_contact_saved, Toast.LENGTH_SHORT).show()
        render(SensingRepository.status.value, SensingRepository.snapshot.value, SensingRepository.message.value)
    }

    /** Records the normal walk for one way of carrying the phone (1 min of walking). */
    private fun recordWalk(p: CarryPosition) {
        SensingRepository.startWalkRecording(p)
        say(getString(when (p) {
            CarryPosition.POCKET -> R.string.fit_say_pos_pocket
            CarryPosition.HAND -> R.string.fit_say_pos_hand
            CarryPosition.BODY -> R.string.fit_say_pos_body
        }))
    }

    private fun stopRecording() {
        SensingRepository.stopWalkRecording()
        voice?.stop()
    }

    /** How to walk for a check: the way the walk was recorded (only hand recorded: no "pocket"). */
    private fun checkHow(f: FitToDriveReport?, spoken: Boolean): Int {
        val learned = f?.learnedPositions.orEmpty()
        val onlyHand = learned.isNotEmpty() && CarryPosition.HAND in learned && CarryPosition.POCKET !in learned
        val onlyBody = learned == setOf(CarryPosition.BODY)
        return when {
            onlyHand -> if (spoken) R.string.fit_say_check_start_hand else R.string.fit_before_drive_hint_hand
            onlyBody -> if (spoken) R.string.fit_say_check_start_body else R.string.fit_before_drive_hint_body
            learned.size > 1 -> if (spoken) R.string.fit_say_check_start_any else R.string.fit_before_drive_hint_any
            else -> if (spoken) R.string.fit_say_check_start else R.string.fit_before_drive_hint
        }
    }

    private fun positionName(p: CarryPosition) = getString(when (p) {
        CarryPosition.POCKET -> R.string.fit_pos_pocket
        CarryPosition.HAND -> R.string.fit_pos_hand
        CarryPosition.BODY -> R.string.fit_pos_body
    })

    private fun showSettings(show: Boolean) = with(binding) {
        val v = if (show) View.VISIBLE else View.GONE
        cardSettings.visibility = v; cardStudy.visibility = v; cardLimits.visibility = v
        btnSettingsToggle.setText(if (show) R.string.fit_settings_hide else R.string.fit_settings_show)
    }

    private fun focusContact() {
        showSettings(true)
        binding.scroll.post { binding.scroll.smoothScrollTo(0, binding.cardSettings.top) }
        binding.etContactName.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.etContactName, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun launch(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.fit_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun findCab() {
        // Any maps app: a plain search, no particular provider.
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("taxi"))))
    }

    // ------------------------------------------------------------------ voice and beeps
    /**
     * Speaks [text] (if a voice is available) plus a beep and vibration, which always work.
     * [interrupt] cuts off what is being said; cues during the test queue up instead.
     */
    private fun say(text: String, interrupt: Boolean = true) {
        voice?.say(text, interrupt)
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun beep() {
        voice?.beep()
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ------------------------------------------------------------------ rendering
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun clock(sec: Float): String { val s = sec.toInt(); return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60) }
    private fun issueText(f: FitToDriveReport) = getString(when (f.recordingIssue) {
        RecordingIssue.NONE -> if (f.recordingWalk) R.string.fit_rec_ok else R.string.fit_step1_waiting
        RecordingIssue.NOT_IN_POCKET -> R.string.fit_rec_not_pocket
        RecordingIssue.NOT_WHERE_CHOSEN -> R.string.fit_rec_not_where
        RecordingIssue.NOT_WALKING -> R.string.fit_rec_not_walking
        RecordingIssue.NOT_STEADY -> R.string.fit_rec_not_steady
        RecordingIssue.LEARNING_PAUSED -> R.string.fit_rec_paused
        RecordingIssue.STUDY_MODE -> R.string.fit_rec_study
    })
    private fun pct(v: Float) = (v * 100).toInt().coerceIn(0, 100)

    private fun render(status: ServiceStatus, s: SensingSnapshot?, message: String?) = with(binding) {
        val f = s?.fitToDrive
        lastReport = f
        val running = status == ServiceStatus.RUNNING && f != null
        val enabled = FitToDriveStore.isEnabled(this@FitToDriveActivity)
        val busy = f?.balanceRunning == true
        val canAct = running && enabled && !busy

        renderBanner(status, f, message, running, enabled)

        if (f == null || !enabled) {
            cardAdvice.visibility = View.GONE
            cardBalance.visibility = View.GONE
            cardSetup.visibility = View.GONE
            cardCheck.visibility = View.GONE
            cardTest.visibility = View.GONE
            btnRecordAgain.visibility = View.GONE
            if (f != null) renderBalance(f)
            return@with
        }
        renderAdvice(f, inVehicle = s.context == "IN_VEHICLE")
        renderBalance(f)
        if (busy) {
            // During the balance test only the countdown is shown.
            cardSetup.visibility = View.GONE
            cardCheck.visibility = View.GONE
            cardTest.visibility = View.GONE
        } else {
            renderSetup(f, canAct)
            renderCheck(f, canAct)
            cardTest.visibility = View.GONE // "Test it" removed: Check my walk is the test
        }

        btnRecordAgain.visibility = if (f.balanceReferences >= f.balanceReferencesNeeded) View.VISIBLE else View.GONE
        btnRecordAgain.isEnabled = canAct
        btnReset.isEnabled = running && !busy
        tvStudyRows.text = getString(R.string.fit_study_rows, StudyLog.rowCount(this@FitToDriveActivity))
    }

    /** One line at the top: a problem to fix, otherwise what the walk recorder is doing right now. */
    private fun renderBanner(status: ServiceStatus, f: FitToDriveReport?, message: String?, running: Boolean, enabled: Boolean) = with(binding) {
        var action: (() -> Unit)? = null
        var actionText = R.string.fit_start_sensing
        val failed = SensingRepository.failedSms
        val (text, col) = when {
            message != null && failed != null -> {
                actionText = R.string.fit_alert_send_messages
                action = {
                    SensingRepository.clearFailedSms()
                    launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(failed.first))).putExtra("sms_body", failed.second))
                }
                message to R.color.accent_amber
            }
            message != null -> message to R.color.accent_amber
            status == ServiceStatus.STOPPED -> {
                action = ::startSensing
                getString(R.string.fit_not_running) to R.color.accent_amber
            }
            !running -> getString(R.string.hero_starting) to R.color.text_muted
            !enabled -> getString(R.string.fit_disabled_hint) to R.color.text_muted
            f == null || f.balanceRunning -> null to 0
            f.learningPaused -> getString(R.string.fit_study_paused) to R.color.accent_amber
            f.watchingDriving || (f.recentlyWarned && f.advice == FitAdvice.NONE) ->
                getString(R.string.fit_watching_driving) to R.color.accent_amber
            f.walkingOutsidePocket -> getString(R.string.fit_walk_not_pocket) to R.color.accent_amber
            f.recordingWalk -> getString(R.string.fit_walk_recording) to R.color.accent_emerald
            f.practice != null -> getString(R.string.fit_test_walk_now) to R.color.primary_glow
            f.baselineStatus == BaselineStatus.LEARNING -> getString(R.string.fit_walk_waiting) to R.color.primary_glow
            else -> null to 0
        }
        banner.visibility = if (text != null) View.VISIBLE else View.GONE
        if (text == null) return@with
        tvBanner.text = text
        tvBanner.setTextColor(color(col))
        btnBanner.visibility = if (action != null) View.VISIBLE else View.GONE
        btnBanner.setText(actionText)
        // The SIM could not text: WhatsApp goes over the internet.
        btnBanner2.visibility = if (failed != null && message != null) View.VISIBLE else View.GONE
        btnBanner2.setOnClickListener {
            val sms = SensingRepository.failedSms ?: return@setOnClickListener
            SensingRepository.clearFailedSms()
            launch(ContactAlert.whatsAppIntent(this@FitToDriveActivity, sms.first, sms.second))
        }
        btnBanner.setOnClickListener { action?.invoke() }
    }

    /** A contact alert counting down: big cancel button for a passenger or a false alarm. */
    private fun renderAlert(a: AlertCountdown?) = with(binding) {
        cardAlert.visibility = if (a != null) View.VISIBLE else View.GONE
        if (a == null) return@with
        tvAlertTitle.text = getString(R.string.fit_alert_title, a.contactName, a.secondsLeft)
        tvAlertDetail.setText(when (a.kind) {
            AlertKind.DRIVING_AFTER_WARNING -> R.string.fit_alert_detail_driving
            AlertKind.ERRATIC_DRIVING -> R.string.fit_alert_detail_erratic
            AlertKind.TEST -> R.string.fit_alert_detail_test
        })
        btnAlertCancel.setText(if (a.kind == AlertKind.TEST) R.string.fit_cancel else R.string.fit_alert_cancel)
    }

    /** Set-up, one step after another: walk, then standing balance, then the contact (optional). */
    private fun renderSetup(f: FitToDriveReport, canAct: Boolean) = with(binding) {
        val ctx = this@FitToDriveActivity
        val walkDone = f.baselineStatus != BaselineStatus.LEARNING
        val balanceDone = f.balanceReferences >= f.balanceReferencesNeeded
        val phone = FitToDriveStore.contactPhone(ctx)
        val contactDone = phone.isNotBlank()
        val complete = walkDone && balanceDone && (contactDone || FitToDriveStore.contactSkipped(ctx))
        cardSetup.visibility = if (complete) View.GONE else View.VISIBLE
        if (complete) return@with
        tvSetupTitle.text = getString(R.string.fit_setup_progress, listOf(walkDone, balanceDone, contactDone).count { it })

        fun step(title: android.widget.TextView, text: String, done: Boolean, active: Boolean) {
            title.text = (if (done) "✓  " else if (active) "▶  " else "○  ") + text
            title.setTextColor(color(when {
                done -> R.color.accent_emerald
                active -> R.color.text_main
                else -> R.color.text_muted
            }))
        }

        // Step 1: walk (also learned from everyday pocket walks, but the button guides it).
        step(tvStep1, getString(R.string.fit_step1), walkDone, !walkDone)
        // What is recorded, in plain numbers, and why not when it is not.
        tvStep1Detail.text = when {
            walkDone -> getString(R.string.fit_step1_done)
            f.walkSession -> getString(R.string.fit_pos_recording, positionName(f.sessionPosition ?: CarryPosition.POCKET)) + "\n" +
                issueText(f) + "\n" + getString(R.string.fit_step1_recording,
                clock(f.walkRecordedSec), clock(f.walkRecordedNeededSec), f.guidedSteps)
            // The last walk gave up: say why, then how.
            f.recordingIssue != RecordingIssue.NONE -> getString(R.string.fit_step1_failed, issueText(f)) + "\n" + getString(R.string.fit_step1_how)
            else -> getString(R.string.fit_step1_how)
        }
        progressStep1.visibility = if (f.walkSession) View.VISIBLE else View.GONE
        progressStep1.progress = pct(f.walkRecordedSec / f.walkRecordedNeededSec)
        btnStep1.visibility = if (walkDone) View.GONE else View.VISIBLE
        btnStep1.setText(if (f.walkSession) R.string.fit_step1_stop else R.string.fit_pos_pocket_start)
        btnStep1.isEnabled = canAct
        val choose = if (!walkDone && !f.walkSession) View.VISIBLE else View.GONE
        btnStep1Hand.visibility = choose; btnStep1Body.visibility = choose
        btnStep1Hand.isEnabled = canAct; btnStep1Body.isEnabled = canAct

        // Step 2: standing balance, after the walk.
        val step2Active = walkDone && !balanceDone
        step(tvStep2, getString(R.string.fit_step2), balanceDone, step2Active)
        tvStep2Detail.text = when {
            balanceDone -> getString(R.string.fit_step2_done, f.balanceReferences, f.balanceReferencesNeeded)
            !walkDone -> getString(R.string.fit_step_locked, 1)
            else -> getString(R.string.fit_step2_detail, f.balanceReferences, f.balanceReferencesNeeded)
        }
        btnStep2.visibility = if (step2Active) View.VISIBLE else View.GONE
        btnStep2.isEnabled = canAct

        // Step 3: trusted contact (optional), after the balance.
        val step3Active = walkDone && balanceDone && !contactDone
        step(tvStep3, getString(R.string.fit_step3), contactDone, step3Active)
        tvStep3Detail.text = when {
            contactDone -> FitToDriveStore.contactName(ctx).ifBlank { phone }
            !(walkDone && balanceDone) -> getString(R.string.fit_step_locked, 2)
            else -> getString(R.string.fit_step3_detail)
        }
        btnStep3.visibility = if (step3Active) View.VISIBLE else View.GONE
        btnStep3Skip.visibility = if (step3Active) View.VISIBLE else View.GONE
    }

    private fun renderAdvice(f: FitToDriveReport, inVehicle: Boolean) = with(binding) {
        if (f.advice == FitAdvice.NONE || f.balanceRunning) {
            cardAdvice.visibility = View.GONE
            return@with
        }
        cardAdvice.visibility = View.VISIBLE
        val phone = FitToDriveStore.contactPhone(this@FitToDriveActivity)
        val name = FitToDriveStore.contactName(this@FitToDriveActivity).ifBlank { phone }
        val showContact = f.advice == FitAdvice.DONT_DRIVE && phone.isNotBlank()
        btnCall.visibility = if (showContact) View.VISIBLE else View.GONE
        btnMessage.visibility = if (showContact) View.VISIBLE else View.GONE
        btnCall.text = getString(R.string.fit_action_call, name)
        btnMessage.text = getString(R.string.fit_action_message, name)
        tvAdviceNote.visibility = if (f.advice == FitAdvice.DONT_DRIVE && phone.isBlank()) View.VISIBLE else View.GONE
        btnAdviceSecondary.visibility = View.VISIBLE
        btnAdviceSecondary.setText(R.string.fit_action_not_driving)
        btnAdviceSecondary.setOnClickListener { SensingRepository.dismissFitAdvice() }
        btnAdvicePrimary.visibility = View.VISIBLE

        val warning = f.advice == FitAdvice.TAKE_BALANCE_CHECK || f.advice == FitAdvice.DONT_DRIVE
        if (warning && inVehicle) {
            // Possibly impaired and in a moving vehicle: no test, no buttons to fiddle with.
            tvAdviceTitle.setText(R.string.fit_advice_stop_title)
            tvAdviceTitle.setTextColor(color(R.color.accent_rose))
            tvAdviceDetail.setText(R.string.fit_advice_stop_detail)
            btnAdvicePrimary.visibility = View.GONE
            btnCall.visibility = View.GONE
            btnMessage.visibility = View.GONE
            tvAdviceNote.visibility = View.GONE
            btnAdviceSecondary.setText(R.string.fit_action_passenger)
            return@with
        }

        when (f.advice) {
            FitAdvice.CONSIDER_BALANCE_CHECK -> {
                tvAdviceTitle.setText(R.string.fit_advice_consider_title)
                tvAdviceTitle.setTextColor(color(R.color.accent_amber))
                tvAdviceDetail.setText(R.string.fit_advice_consider_detail)
                btnAdvicePrimary.setText(R.string.fit_action_balance)
                btnAdvicePrimary.setOnClickListener { startOfferedBalance() }
                btnAdviceSecondary.setText(R.string.fit_action_not_now)
            }
            FitAdvice.TAKE_BALANCE_CHECK -> {
                tvAdviceTitle.setText(R.string.fit_advice_balance_title)
                tvAdviceTitle.setTextColor(color(R.color.accent_amber))
                tvAdviceDetail.text = getString(R.string.fit_advice_balance_detail, pct(f.lastCheck?.percentile ?: 0.99f))
                btnAdvicePrimary.setText(R.string.fit_action_balance)
                btnAdvicePrimary.setOnClickListener { startOfferedBalance() }
            }
            FitAdvice.DONT_DRIVE -> {
                tvAdviceTitle.setText(R.string.fit_advice_dont_title)
                tvAdviceTitle.setTextColor(color(R.color.accent_rose))
                tvAdviceDetail.setText(R.string.fit_advice_dont_detail)
                btnAdvicePrimary.setText(R.string.fit_action_cab)
                btnAdvicePrimary.setOnClickListener { findCab() }
            }
            FitAdvice.BALANCE_OK -> {
                tvAdviceTitle.setText(R.string.fit_advice_ok_title)
                tvAdviceTitle.setTextColor(color(R.color.accent_emerald))
                tvAdviceDetail.setText(R.string.fit_advice_ok_detail)
                btnAdvicePrimary.setText(R.string.fit_action_ok)
                btnAdvicePrimary.setOnClickListener { SensingRepository.dismissFitAdvice() }
                btnAdviceSecondary.visibility = View.GONE
            }
            FitAdvice.NONE -> Unit
        }
    }

    /** The test itself plus spoken cues: start, "hold still" after the settling time, 10 s left, the result. */
    private fun renderBalance(f: FitToDriveReport) = with(binding) {
        cardBalance.visibility = if (f.balanceRunning) View.VISIBLE else View.GONE
        if (f.balanceRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val purpose = f.balancePurpose ?: BalancePurpose.CHECK
            if (!balanceWasRunning) {
                spokenCues.clear()
                say(getString(when {
                    purpose == BalancePurpose.REFERENCE -> R.string.fit_say_start_reference
                    Tests.cPending -> R.string.fit_say_test_c
                    else -> R.string.fit_say_start_check
                }))
            }
            val settling = f.balanceProgress * BalanceTest.DURATION_SEC < BalanceTest.SETTLE_SEC
            if (!settling && spokenCues.add("hold")) say(getString(R.string.fit_say_hold), interrupt = false)
            if (f.balanceSecondsLeft in 1..10 && spokenCues.add("ten")) say(getString(R.string.fit_say_ten), interrupt = false)
            tvBalanceTitle.setText(if (purpose == BalancePurpose.REFERENCE) R.string.fit_balance_title_reference else R.string.fit_balance_title_check)
            tvBalancePhase.setText(if (settling) R.string.fit_balance_get_ready else R.string.fit_balance_hold)
            tvBalancePhase.setTextColor(color(if (settling) R.color.accent_amber else R.color.accent_emerald))
            tvBalanceCountdown.text = getString(R.string.fit_balance_seconds, f.balanceSecondsLeft)
            progressBalance.progress = pct(f.balanceProgress)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // A test just finished (not cancelled): say the result, the user is not looking at the screen.
        val result = f.lastBalance
        if (balanceWasRunning && !f.balanceRunning && result != null && result.wallMillis != lastBalanceSeen) {
            say(spokenResult(result, f))
        }
        lastBalanceSeen = result?.wallMillis
        balanceWasRunning = f.balanceRunning
    }

    private fun spokenResult(r: BalanceResult, f: FitToDriveReport): String = when (r.outcome) {
        BalanceOutcome.PASSED -> getString(R.string.fit_say_passed)
        BalanceOutcome.FAILED -> getString(R.string.fit_say_failed)
        BalanceOutcome.MOVED -> getString(R.string.fit_say_moved)
        BalanceOutcome.TOO_STILL -> getString(R.string.fit_say_too_still)
        BalanceOutcome.NO_REFERENCE -> getString(R.string.fit_say_no_reference)
        BalanceOutcome.REFERENCE_REJECTED -> getString(R.string.fit_say_rejected)
        BalanceOutcome.REFERENCE_SAVED ->
            if (f.balanceReferences >= f.balanceReferencesNeeded) getString(R.string.fit_say_saved_all)
            else getString(R.string.fit_say_saved, f.balanceReferences, f.balanceReferencesNeeded)
    }

    /** "Before you drive": only once the normal walk is learned, so a check can give an answer. */
    private fun renderCheck(f: FitToDriveReport, canAct: Boolean) = with(binding) {
        val ready = f.baselineStatus != BaselineStatus.LEARNING && f.balanceReferences >= f.balanceReferencesNeeded
        cardCheck.visibility = if (ready) View.VISIBLE else View.GONE
        if (!ready) return@with
        val checking = f.checkSecondsLeft > 0f
        tvBeforeHint.setText(checkHow(f, spoken = false))
        val adding = f.walkSession // recording another way of carrying, after set-up
        btnCheckNow.isEnabled = canAct && (adding || !f.waitingForWalk)
        btnCheckNow.setText(if (adding) R.string.fit_stop_recording else R.string.fit_check_now)
        rowAddPosition.visibility = if (checking || adding) View.GONE else View.VISIBLE
        btnAddPocket.visibility = if (CarryPosition.POCKET in f.learnedPositions) View.GONE else View.VISIBLE
        btnAddHand.visibility = if (CarryPosition.HAND in f.learnedPositions) View.GONE else View.VISIBLE
        btnAddBody.visibility = if (CarryPosition.BODY in f.learnedPositions) View.GONE else View.VISIBLE
        listOf(btnAddPocket, btnAddHand, btnAddBody).forEach { it.isEnabled = canAct }
        // The balance check is offered by the advice card, only when the walk was different.
        btnBalanceCheck.visibility = View.GONE

        val c = f.lastCheck
        val (level, detail, dot) = when {
            c == null -> Triple(getString(R.string.fit_no_check_yet), "", R.color.text_muted)
            c.level == null -> Triple(
                getString(when (c.notScored) {
                    NotScoredReason.STILL_LEARNING -> R.string.fit_not_scored_learning
                    NotScoredReason.TOO_IRREGULAR -> R.string.fit_not_scored_irregular
                    NotScoredReason.POSITION_NOT_LEARNED -> R.string.fit_not_scored_position
                    // Check my walk: the walk after the tap, not "the last few minutes".
                    else -> if (c.trigger == CheckTrigger.MANUAL) R.string.fit_not_scored_no_walk_check else R.string.fit_not_scored_no_walk
                }),
                "", if (c.notScored == NotScoredReason.TOO_IRREGULAR) R.color.accent_rose else R.color.text_muted)
            else -> {
                val (label, col) = when (c.level) {
                    WalkLevel.NORMAL -> R.string.fit_level_normal to R.color.accent_emerald
                    WalkLevel.UNUSUAL -> R.string.fit_level_unusual to R.color.accent_amber
                    WalkLevel.LARGE_GAP -> R.string.fit_level_large to R.color.accent_rose
                }
                var d = getString(R.string.fit_percentile, pct(c.percentile))
                c.position?.let { d = getString(R.string.fit_pos_compared, positionName(it)) + "\n" + d }
                if (c.unfamiliarPosition) d = getString(R.string.fit_pos_unfamiliar) + "\n" + d
                if (c.provisional) d += getString(R.string.fit_provisional)
                if (c.topDeviations.isNotEmpty()) {
                    d += "\n" + getString(R.string.fit_top, c.topDeviations.joinToString(", ") { it.first.label })
                }
                Triple(getString(label), d, col)
            }
        }
        if (adding) {
            tvLastLevel.text = getString(R.string.fit_pos_recording, positionName(f.sessionPosition ?: CarryPosition.POCKET))
            tvLastLevel.setTextColor(color(R.color.text_main))
            levelDot.backgroundTintList = ColorStateList.valueOf(color(R.color.primary_glow))
            tvLastDetail.text = issueText(f) + "\n" + getString(R.string.fit_step1_recording,
                clock(f.walkRecordedSec), clock(f.walkRecordedNeededSec), f.guidedSteps)
            tvLastDetail.visibility = View.VISIBLE
            tvLastMeta.visibility = View.GONE
            tvLastBalance.visibility = View.GONE
            tvLearned.visibility = View.GONE
            return@with
        }
        if (checking) {
            tvLastLevel.text = getString(if (f.checkExtended) R.string.fit_checking_more else R.string.fit_checking,
                kotlin.math.ceil(f.checkSecondsLeft).toInt())
            tvLastLevel.setTextColor(color(R.color.text_main))
            levelDot.backgroundTintList = ColorStateList.valueOf(color(R.color.primary_glow))
            tvLastDetail.text = issueText(f) + "\n" +
                (f.currentPosition?.let { getString(R.string.fit_pos_now, positionName(it)) + "  ·  " } ?: "") +
                getString(R.string.fit_check_recorded, clock(f.walkRecordedSec), f.guidedSteps)
            tvLastDetail.visibility = View.VISIBLE
            tvLastMeta.visibility = View.GONE
            tvLastBalance.visibility = View.GONE
            tvLearned.visibility = View.GONE
            return@with
        }
        tvLearned.visibility = View.VISIBLE
        tvLastLevel.text = level
        tvLastLevel.setTextColor(color(if (c?.level != null) dot else R.color.text_main))
        levelDot.backgroundTintList = ColorStateList.valueOf(color(dot))
        tvLastDetail.text = detail
        tvLastDetail.visibility = if (tvLastDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        tvLastMeta.text = if (c == null) "" else {
            val trigger = getString(when (c.trigger) {
                CheckTrigger.TRIP_START -> R.string.fit_trigger_trip
                CheckTrigger.CAR_BLUETOOTH -> R.string.fit_trigger_bt
                CheckTrigger.MANUAL -> if (c.practice != null) R.string.fit_trigger_test else R.string.fit_trigger_manual
            })
            val at = timeFormat.format(Date(c.wallMillis))
            // Segments are 8 s long, a new one every 4 s.
            if (c.segments == 0) getString(R.string.fit_check_meta_short, trigger, at)
            else getString(R.string.fit_check_meta, trigger, at, 8 + (c.segments - 1) * 4)
        }
        tvLastMeta.visibility = if (c == null) View.GONE else View.VISIBLE

        val b = f.lastBalance?.takeIf { false }
        tvLastBalance.visibility = View.GONE
        if (b != null) {
            tvLastBalance.text = when (b.outcome) {
                BalanceOutcome.PASSED -> getString(R.string.fit_balance_passed, b.ratio)
                BalanceOutcome.FAILED -> getString(R.string.fit_balance_failed, b.ratio)
                BalanceOutcome.MOVED -> getString(R.string.fit_balance_moved)
                BalanceOutcome.TOO_STILL -> getString(R.string.fit_balance_too_still)
                BalanceOutcome.NO_REFERENCE -> getString(R.string.fit_balance_no_reference)
                BalanceOutcome.REFERENCE_SAVED -> getString(R.string.fit_balance_reference_saved, f.balanceReferences, f.balanceReferencesNeeded)
                BalanceOutcome.REFERENCE_REJECTED -> getString(R.string.fit_balance_reference_rejected)
            }
            tvLastBalance.setTextColor(color(when (b.outcome) {
                BalanceOutcome.PASSED, BalanceOutcome.REFERENCE_SAVED -> R.color.accent_emerald
                BalanceOutcome.FAILED -> R.color.accent_rose
                else -> R.color.accent_amber
            }))
        }

        val status = if (f.baselineStatus == BaselineStatus.ESTABLISHED) getString(R.string.fit_status_established)
            else getString(R.string.fit_status_provisional, f.daysForEstablished)
        val learned = CarryPosition.entries.filter { it in f.learnedPositions }.joinToString(" · ") { "✓ " + positionName(it) }
        tvLearned.text = getString(R.string.fit_pos_learned, learned) + ". " + status +
            (if (f.learnedPositions.size < CarryPosition.entries.size) "\n" + getString(R.string.fit_pos_add_hint) else "")
    }

    /**
     * "Test it": A normal walk, B wobbly walk (both never learned), C balance check while
     * swaying. Each row shows what to expect, then the result and whether it was as expected.
     */
    private fun renderTest(f: FitToDriveReport, canAct: Boolean) = with(binding) {
        val ready = f.baselineStatus != BaselineStatus.LEARNING && f.balanceReferences >= f.balanceReferencesNeeded
        cardTest.visibility = if (ready) View.VISIBLE else View.GONE
        if (!ready) return@with

        f.lastCheck?.let { c ->
            val p = c.practice
            if (p != null && c.wallMillis != Tests.seenCheck) Tests.results[if (p == PracticeWalk.NORMAL) 'A' else 'B'] = practiceResult(p, c)
            Tests.seenCheck = c.wallMillis
        }
        f.lastBalance?.let { b ->
            if (b.wallMillis != Tests.seenBalance && Tests.cPending && b.purpose == BalancePurpose.CHECK) {
                Tests.cPending = false
                Tests.results['C'] = balanceResult(b)
            }
            Tests.seenBalance = b.wallMillis
        }

        val running = f.practice
        testRunning.visibility = if (running != null) View.VISIBLE else View.GONE
        tvTestRunning.setText(if (running == PracticeWalk.WOBBLY) R.string.fit_test_running_b else R.string.fit_test_running_a)
        val free = canAct && running == null && !f.waitingForWalk
        for ((row, hint) in listOf(
            Triple('A', tvTestAResult, btnTestA) to R.string.fit_test_a_hint,
            Triple('B', tvTestBResult, btnTestB) to R.string.fit_test_b_hint,
            Triple('C', tvTestCResult, btnTestC) to R.string.fit_test_c_hint
        )) {
            val (k, tv, btn) = row
            val r = Tests.results[k]
            tv.text = r?.text ?: getString(hint)
            tv.setTextColor(color(when {
                r == null -> R.color.text_muted
                r.asExpected -> R.color.accent_emerald
                else -> R.color.accent_amber
            }))
            btn.setText(if (r == null) R.string.fit_test_start else R.string.fit_test_again)
            btn.isEnabled = free
        }
        tvTestDone.visibility = if (listOf('A', 'B', 'C').all { Tests.results[it]?.asExpected == true }) View.VISIBLE else View.GONE
    }

    private fun practiceResult(p: PracticeWalk, c: WalkCheck): Tests.Result {
        if (c.level == null) return Tests.Result(getString(R.string.fit_test_no_walk), false)
        return if (p == PracticeWalk.NORMAL) when (c.level) {
            WalkLevel.NORMAL -> Tests.Result(getString(R.string.fit_test_a_normal), true)
            WalkLevel.UNUSUAL -> Tests.Result(getString(R.string.fit_test_a_unusual), false)
            WalkLevel.LARGE_GAP -> Tests.Result(getString(R.string.fit_test_a_large), false)
        } else when (c.level) {
            WalkLevel.LARGE_GAP -> Tests.Result(getString(R.string.fit_test_b_large), true)
            WalkLevel.UNUSUAL -> Tests.Result(getString(R.string.fit_test_b_unusual), true)
            WalkLevel.NORMAL -> Tests.Result(getString(R.string.fit_test_b_normal), false)
        }
    }

    private fun balanceResult(b: BalanceResult): Tests.Result = when (b.outcome) {
        BalanceOutcome.FAILED -> Tests.Result(getString(R.string.fit_test_c_failed), true)
        BalanceOutcome.PASSED -> Tests.Result(getString(R.string.fit_test_c_passed), false)
        BalanceOutcome.MOVED -> Tests.Result(getString(R.string.fit_test_c_moved), false)
        BalanceOutcome.TOO_STILL -> Tests.Result(getString(R.string.fit_balance_too_still), false)
        else -> Tests.Result(getString(R.string.fit_say_no_reference), false)
    }
}
