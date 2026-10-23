package com.iitj.pervasivesense

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
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
 * Fit-to-drive screen: opt-in, learning progress, the last walk check, the
 * 30 s balance test and the response (cab, trusted contact). Like the
 * dashboard it only renders [SensingSnapshot.fitToDrive]; the engine decides.
 */
class FitToDriveActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_BALANCE = "com.iitj.pervasivesense.extra.START_BALANCE"
        const val EXTRA_DISMISS = "com.iitj.pervasivesense.extra.DISMISS_FIT"
    }

    private lateinit var binding: ActivityFitToDriveBinding
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val maxReferences = FitToDriveMonitor.Config().maxBalanceReferences
    private var tone: ToneGenerator? = null
    private var lastBalanceSeen: Long? = null
    private var balanceWasRunning = false
    private var bindingUi = false

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> setCarBluetooth(granted) }

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
        tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (e: RuntimeException) { null }

        bindingUi = true
        binding.switchEnabled.isChecked = FitToDriveStore.isEnabled(this)
        binding.switchCarBluetooth.isChecked = FitToDriveStore.useCarBluetooth(this) &&
            SensingForegroundService.hasBluetoothPermission(this)
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
        binding.btnCheckNow.setOnClickListener { SensingRepository.requestFitCheck() }
        binding.btnBalanceCheck.setOnClickListener { startBalance(BalancePurpose.CHECK) }
        binding.btnRecordReference.setOnClickListener { startBalance(BalancePurpose.REFERENCE) }
        binding.btnBalanceCancel.setOnClickListener { SensingRepository.cancelBalanceTest() }
        binding.btnSaveContact.setOnClickListener {
            FitToDriveStore.setContact(this, binding.etContactName.text.toString(), binding.etContactPhone.text.toString())
            Toast.makeText(this, R.string.fit_contact_saved, Toast.LENGTH_SHORT).show()
        }
        binding.btnCall.setOnClickListener {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(FitToDriveStore.contactPhone(this)))))
        }
        binding.btnMessage.setOnClickListener {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(FitToDriveStore.contactPhone(this))))
                .putExtra("sms_body", getString(R.string.fit_message_text)))
        }
        binding.btnSetupRecord.setOnClickListener { startBalance(BalancePurpose.REFERENCE) }
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
        tone?.release()
        tone = null
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
                askForBalanceReference()
            }
            .setNegativeButton(R.string.fit_cancel) { _, _ -> setSwitch(false) }
            .setOnCancelListener { setSwitch(false) }
            .show()
    }

    /** Setup: the balance check needs the person's own normal balance to compare with. */
    private fun askForBalanceReference() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fit_setup_dialog_title)
            .setMessage(R.string.fit_setup_dialog_body)
            .setPositiveButton(R.string.fit_setup_dialog_ok) { _, _ -> startBalance(BalancePurpose.REFERENCE) }
            .setNegativeButton(R.string.fit_setup_dialog_later, null)
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

    private fun startBalance(purpose: BalancePurpose) {
        SensingRepository.startBalanceTest(purpose)
        cue()
    }

    /** Audible + haptic cue: the phone is against the chest, the screen is not visible. */
    private fun cue() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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

    // ------------------------------------------------------------------ rendering
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun pct(v: Float) = (v * 100).toInt().coerceIn(0, 100)

    private fun render(status: ServiceStatus, s: SensingSnapshot?, message: String?) = with(binding) {
        val f = s?.fitToDrive
        val running = status == ServiceStatus.RUNNING && f != null
        val enabled = FitToDriveStore.isEnabled(this@FitToDriveActivity)

        val banner = when {
            message != null -> message
            !running -> getString(R.string.fit_not_running)
            !enabled -> getString(R.string.fit_disabled_hint)
            f?.walkingOutsidePocket == true -> getString(R.string.fit_pocket_hint)
            f?.learningPaused == true -> getString(R.string.fit_study_paused)
            else -> null
        }
        tvBanner.text = banner ?: ""
        tvBanner.visibility = if (banner != null) View.VISIBLE else View.GONE

        val busy = f?.balanceRunning == true
        val canAct = running && enabled && !busy
        btnCheckNow.isEnabled = canAct && f?.waitingForWalk != true
        btnBalanceCheck.isEnabled = canAct
        btnRecordReference.isEnabled = canAct
        btnReset.isEnabled = running && !busy
        btnSetupRecord.isEnabled = canAct
        tvStudyRows.text = getString(R.string.fit_study_rows, StudyLog.rowCount(this@FitToDriveActivity))

        if (f == null) {
            cardAdvice.visibility = View.GONE
            cardBalance.visibility = View.GONE
            cardSetup.visibility = View.GONE
            renderLastCheck(null)
            return@with
        }
        renderSetup(f, enabled)
        renderAdvice(f, inVehicle = s.context == "IN_VEHICLE")
        renderBalance(f)
        renderLastCheck(f)
        renderBaseline(f)
    }

    private fun renderSetup(f: FitToDriveReport, enabled: Boolean) = with(binding) {
        val walkDone = f.baselineStatus != BaselineStatus.LEARNING
        val balanceDone = f.balanceReferences >= f.balanceReferencesRecommended
        val contactDone = FitToDriveStore.contactPhone(this@FitToDriveActivity).isNotBlank()
        cardSetup.visibility = if (enabled && !(walkDone && balanceDone && contactDone)) View.VISIBLE else View.GONE
        fun mark(done: Boolean, text: String) = (if (done) "✓  " else "•  ") + text
        tvSetupWalk.text = mark(walkDone, if (walkDone) getString(R.string.fit_setup_walk_done)
            else getString(R.string.fit_setup_walk, pct(f.baselineSegments.toFloat() / f.segmentsForProvisional)))
        tvSetupBalance.text = mark(balanceDone, getString(R.string.fit_setup_balance,
            minOf(f.balanceReferences, f.balanceReferencesRecommended), f.balanceReferencesRecommended))
        tvSetupContact.text = mark(contactDone, getString(if (contactDone) R.string.fit_setup_contact_done else R.string.fit_setup_contact))
        btnSetupRecord.visibility = if (balanceDone) View.GONE else View.VISIBLE
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
        tvAdviceNote.setText(R.string.fit_no_contact)
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
                btnAdvicePrimary.setOnClickListener { startBalance(BalancePurpose.CHECK) }
                btnAdviceSecondary.setText(R.string.fit_action_not_now)
            }
            FitAdvice.TAKE_BALANCE_CHECK -> {
                tvAdviceTitle.setText(R.string.fit_advice_balance_title)
                tvAdviceTitle.setTextColor(color(R.color.accent_amber))
                tvAdviceDetail.text = getString(R.string.fit_advice_balance_detail, pct(f.lastCheck?.percentile ?: 0.99f))
                btnAdvicePrimary.setText(R.string.fit_action_balance)
                btnAdvicePrimary.setOnClickListener { startBalance(BalancePurpose.CHECK) }
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

    private fun renderBalance(f: FitToDriveReport) = with(binding) {
        cardBalance.visibility = if (f.balanceRunning) View.VISIBLE else View.GONE
        if (f.balanceRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvBalanceTitle.setText(if (f.balancePurpose == BalancePurpose.REFERENCE) R.string.fit_balance_title_reference else R.string.fit_balance_title_check)
            tvBalanceCountdown.text = getString(R.string.fit_balance_seconds, f.balanceSecondsLeft)
            val settling = f.balanceProgress * BalanceTest.DURATION_SEC < BalanceTest.SETTLE_SEC
            tvBalancePhase.text = if (settling) getString(R.string.fit_balance_settling) else ""
            progressBalance.progress = pct(f.balanceProgress)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // A test just finished: tell the user, who is not looking at the screen.
        val resultT = f.lastBalance?.wallMillis
        if (balanceWasRunning && !f.balanceRunning && resultT != null && resultT != lastBalanceSeen) cue()
        lastBalanceSeen = resultT
        balanceWasRunning = f.balanceRunning
    }

    private fun renderLastCheck(f: FitToDriveReport?) = with(binding) {
        val c = f?.lastCheck
        val (level, detail, dot) = when {
            c == null -> Triple(getString(R.string.fit_last_title), getString(R.string.fit_last_none), R.color.text_muted)
            c.level == null -> Triple(
                getString(if (c.notScored == NotScoredReason.STILL_LEARNING) R.string.fit_not_scored_learning else R.string.fit_not_scored_no_walk),
                "", R.color.text_muted)
            else -> {
                val (label, col) = when (c.level) {
                    WalkLevel.NORMAL -> R.string.fit_level_normal to R.color.accent_emerald
                    WalkLevel.UNUSUAL -> R.string.fit_level_unusual to R.color.accent_amber
                    WalkLevel.LARGE_GAP -> R.string.fit_level_large to R.color.accent_rose
                }
                var d = getString(R.string.fit_percentile, pct(c.percentile))
                if (c.provisional) d += getString(R.string.fit_provisional)
                if (c.topDeviations.isNotEmpty()) {
                    d += "\n" + getString(R.string.fit_top, c.topDeviations.joinToString(", ") { it.first.label })
                }
                Triple(getString(label), d, col)
            }
        }
        tvLastLevel.text = level
        tvLastLevel.setTextColor(color(if (c?.level != null) dot else R.color.text_main))
        tvLastDetail.text = if (f?.waitingForWalk == true) getString(R.string.fit_waiting) else detail
        tvLastDetail.visibility = if (tvLastDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        levelDot.backgroundTintList = ColorStateList.valueOf(color(dot))
        tvLastMeta.text = if (c == null) "" else {
            val trigger = getString(when (c.trigger) {
                CheckTrigger.TRIP_START -> R.string.fit_trigger_trip
                CheckTrigger.CAR_BLUETOOTH -> R.string.fit_trigger_bt
                CheckTrigger.MANUAL -> R.string.fit_trigger_manual
            })
            val at = timeFormat.format(Date(c.wallMillis))
            // Segments are 8 s long, a new one every 4 s.
            if (c.segments == 0) getString(R.string.fit_check_meta_short, trigger, at)
            else getString(R.string.fit_check_meta, trigger, at, 8 + (c.segments - 1) * 4)
        }
        tvLastMeta.visibility = if (c == null) View.GONE else View.VISIBLE

        val b = f?.lastBalance
        tvLastBalance.visibility = if (b != null) View.VISIBLE else View.GONE
        if (b != null) {
            tvLastBalance.text = when (b.outcome) {
                BalanceOutcome.PASSED -> getString(R.string.fit_balance_passed, b.ratio)
                BalanceOutcome.FAILED -> getString(R.string.fit_balance_failed, b.ratio)
                BalanceOutcome.MOVED -> getString(R.string.fit_balance_moved)
                BalanceOutcome.NO_REFERENCE -> getString(R.string.fit_balance_no_reference)
                BalanceOutcome.REFERENCE_SAVED -> getString(R.string.fit_balance_reference_saved, f.balanceReferences, maxReferences)
                BalanceOutcome.REFERENCE_REJECTED -> getString(R.string.fit_balance_reference_rejected)
            }
            tvLastBalance.setTextColor(color(when (b.outcome) {
                BalanceOutcome.PASSED, BalanceOutcome.REFERENCE_SAVED -> R.color.accent_emerald
                BalanceOutcome.FAILED -> R.color.accent_rose
                else -> R.color.accent_amber
            }))
        }
    }

    private fun renderBaseline(f: FitToDriveReport) = with(binding) {
        tvBaselineStatus.text = when (f.baselineStatus) {
            BaselineStatus.LEARNING -> getString(R.string.fit_status_learning)
            BaselineStatus.PROVISIONAL -> getString(R.string.fit_status_provisional, f.daysForEstablished)
            BaselineStatus.ESTABLISHED -> getString(R.string.fit_status_established)
        }
        val progress = when (f.baselineStatus) {
            BaselineStatus.LEARNING -> f.baselineSegments.toFloat() / f.segmentsForProvisional
            else -> minOf(f.baselineSegments.toFloat() / f.segmentsForEstablished, f.baselineDays.toFloat() / f.daysForEstablished)
        }
        progressBaseline.progress = pct(progress)
        progressBaseline.setIndicatorColor(color(when (f.baselineStatus) {
            BaselineStatus.LEARNING -> R.color.accent_amber
            BaselineStatus.PROVISIONAL -> R.color.primary_cyan
            BaselineStatus.ESTABLISHED -> R.color.accent_emerald
        }))
        tvBaselineDetail.text = resources.getQuantityString(R.plurals.fit_learned, f.baselineDays, f.baselineWalkMinutes, f.baselineDays)
        tvBalanceRefs.text = getString(R.string.fit_balance_refs, f.balanceReferences, maxReferences)
    }
}
