package com.iitj.pervasivesense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iitj.pervasivesense.databinding.ActivityMainBinding
import com.iitj.pervasivesense.databinding.ItemProbabilityBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard. Observes [SensingRepository] and renders each card from the latest
 * [SensingSnapshot]; it never computes sensing results itself.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACK_FALL = "com.iitj.pervasivesense.extra.ACK_FALL"
        /** From the "sensing stopped" notification: start it again (the app is in the foreground now). */
        const val EXTRA_RESTART = "com.iitj.pervasivesense.extra.RESTART"
        private const val KEY_DIAGNOSTICS = "diagnostics_open"
    }

    private lateinit var binding: ActivityMainBinding
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val numberFormat = NumberFormat.getIntegerInstance()
    private val probabilityRows = LinkedHashMap<String, ItemProbabilityBinding>()
    private var diagnosticsOpen = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggleService.setOnClickListener {
            when (SensingRepository.status.value) {
                ServiceStatus.RUNNING, ServiceStatus.STARTING -> SensingForegroundService.stopByUser(this)
                ServiceStatus.STOPPED -> requestPermissionsThenStart()
            }
        }
        binding.btnFallAck.setOnClickListener { SensingRepository.acknowledgeFall() }
        val openFit = View.OnClickListener { startActivity(Intent(this, FitToDriveActivity::class.java)) }
        binding.cardFit.setOnClickListener(openFit)
        binding.btnFitOpen.setOnClickListener(openFit)
        binding.btnFitAction.setOnClickListener { onFitAction() }
        diagnosticsOpen = savedInstanceState?.getBoolean(KEY_DIAGNOSTICS) ?: false
        binding.tvDiagnosticsToggle.setOnClickListener {
            diagnosticsOpen = !diagnosticsOpen
            applyDiagnosticsVisibility()
        }
        applyDiagnosticsVisibility()
        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SensingRepository.status, SensingRepository.snapshot, SensingRepository.message) { st, snap, msg ->
                    Triple(st, snap, msg)
                }.collect { (st, snap, msg) -> render(st, snap, msg) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DIAGNOSTICS, diagnosticsOpen)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        SensingRepository.setUiVisible(true) // 10 Hz dashboard updates, eco mode off
    }

    override fun onStop() {
        SensingRepository.setUiVisible(false)
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ACK_FALL, false) == true) {
            SensingRepository.acknowledgeFall()
            intent.removeExtra(EXTRA_ACK_FALL)
        }
        if (intent?.getBooleanExtra(EXTRA_RESTART, false) == true) {
            intent.removeExtra(EXTRA_RESTART)
            if (SensingRepository.status.value == ServiceStatus.STOPPED) requestPermissionsThenStart()
        }
    }

    private fun applyDiagnosticsVisibility() {
        binding.diagnosticsContent.visibility = if (diagnosticsOpen) View.VISIBLE else View.GONE
        binding.tvDiagnosticsToggle.setText(if (diagnosticsOpen) R.string.diagnostics_hide else R.string.diagnostics_show)
    }

    // ------------------------------------------------------------------ permissions & start
    private fun missingPermissions(): Array<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wanted += Manifest.permission.ACTIVITY_RECOGNITION
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun requestPermissionsThenStart() {
        val missing = missingPermissions()
        if (missing.isEmpty()) startSensing() else permissionLauncher.launch(missing)
    }

    private fun onPermissionsResult() {
        val arGranted = SensingForegroundService.hasActivityRecognitionPermission(this)
        if (Build.VERSION.SDK_INT >= 34 && !arGranted) {
            // Android 14+ only allows a "health" foreground service with this permission.
            SensingRepository.setMessage(
                "\"Physical activity\" permission is required on Android 14+. " +
                    "Enable it in Settings > Apps > PervasiveSense > Permissions.")
            return
        }
        if (!arGranted) {
            SensingRepository.setMessage("Without \"Physical activity\" permission, vehicle detection and the " +
                "hardware step counter are disabled.")
        }
        startSensing()
    }

    private fun startSensing() {
        SensingRepository.setStatus(ServiceStatus.STARTING)
        ContextCompat.startForegroundService(this, Intent(this, SensingForegroundService::class.java))
    }

    // ------------------------------------------------------------------ rendering
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun tint(id: Int) = ColorStateList.valueOf(color(id))
    private fun pct(v: Float) = (v * 100).toInt().coerceIn(0, 100)

    /** "STAIRS_UP" -> "Stairs up", "IN_VEHICLE" -> "In vehicle". */
    private fun title(name: String): String =
        name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private fun render(status: ServiceStatus, s: SensingSnapshot?, message: String?) {
        renderControls(status, s, message)
        renderHero(status, s)
        renderFit(status, s?.fitToDrive, inVehicle = s?.context == "IN_VEHICLE")
        if (s == null) return
        renderProbabilities(s, active = status == ServiceStatus.RUNNING)
        renderPosture(s)
        renderWellness(s.wellness)
        renderSafety(s)
        renderRoad(s)
        renderLog(s)
        renderDiagnostics(s)
    }

    private fun renderControls(status: ServiceStatus, s: SensingSnapshot?, message: String?) = with(binding) {
        val (pill, pillColor) = when {
            status == ServiceStatus.STOPPED -> R.string.status_stopped to R.color.text_muted
            status == ServiceStatus.STARTING -> R.string.status_starting to R.color.accent_amber
            s?.status == EngineStatus.MODEL_ERROR -> R.string.status_error to R.color.accent_rose
            s?.ecoMode == true -> R.string.status_eco to R.color.primary_glow
            else -> R.string.status_live to R.color.accent_emerald
        }
        tvStatusPill.setText(pill)
        tvStatusPill.backgroundTintList = tint(pillColor)

        val (label, buttonColor) = when (status) {
            ServiceStatus.STOPPED -> R.string.action_start to R.color.primary_cyan
            ServiceStatus.STARTING -> R.string.action_cancel to R.color.accent_amber
            ServiceStatus.RUNNING -> R.string.action_stop to R.color.accent_rose
        }
        btnToggleService.setText(label)
        btnToggleService.backgroundTintList = tint(buttonColor)

        tvServiceMessage.text = message ?: ""
        tvServiceMessage.visibility = if (message != null) View.VISIBLE else View.GONE
    }

    private fun renderHero(status: ServiceStatus, s: SensingSnapshot?) = with(binding) {
        when {
            status == ServiceStatus.STOPPED -> {
                tvCurrentActivity.setText(R.string.hero_not_running)
                tvCurrentActivity.setTextColor(color(R.color.text_muted))
                tvActivityDetail.setText(R.string.hero_not_running_detail)
                progressConfidence.progress = 0
            }
            s == null -> {
                tvCurrentActivity.setText(R.string.hero_starting)
                tvCurrentActivity.setTextColor(color(R.color.accent_amber))
                tvActivityDetail.setText(R.string.hero_starting_detail)
                progressConfidence.progress = 0
            }
            s.status == EngineStatus.WARMING_UP -> {
                tvCurrentActivity.setText(R.string.hero_calibrating)
                tvCurrentActivity.setTextColor(color(R.color.accent_amber))
                tvActivityDetail.text = getString(R.string.hero_calibrating_detail, pct(s.warmupProgress))
                progressConfidence.setIndicatorColor(color(R.color.accent_amber))
                progressConfidence.progress = pct(s.warmupProgress)
            }
            s.status == EngineStatus.MODEL_ERROR -> {
                tvCurrentActivity.setText(R.string.hero_model_error)
                tvCurrentActivity.setTextColor(color(R.color.accent_rose))
                tvActivityDetail.setText(R.string.hero_model_error_detail)
                progressConfidence.progress = 0
            }
            else -> {
                tvCurrentActivity.text = title(s.context ?: "")
                tvCurrentActivity.setTextColor(color(R.color.text_main))
                val parts = mutableListOf(getString(R.string.hero_confidence, pct(s.confidence)))
                s.lastTransition?.let { parts += getString(R.string.hero_since, timeFormat.format(Date(it.wallTimeMillis))) }
                if (s.context == "IN_VEHICLE" && s.bodyActivity != null) {
                    parts += getString(R.string.hero_on_foot, title(s.bodyActivity))
                }
                tvActivityDetail.text = parts.joinToString(", ")
                progressConfidence.setIndicatorColor(color(R.color.accent_emerald))
                progressConfidence.progress = pct(s.confidence)
            }
        }
    }

    private fun renderProbabilities(s: SensingSnapshot, active: Boolean) {
        // Rows are created once from the model's labels, in a fixed order.
        if (probabilityRows.keys.toList() != s.probabilities.map { it.first }) {
            binding.probContainer.removeAllViews()
            probabilityRows.clear()
            for ((name, _) in s.probabilities) {
                val row = ItemProbabilityBinding.inflate(layoutInflater, binding.probContainer, true)
                row.tvClassName.text = title(name)
                probabilityRows[name] = row
            }
        }
        val showValues = active && s.inferencesRun > 0
        for ((name, p) in s.probabilities) {
            val row = probabilityRows[name] ?: continue
            val top = showValues && name == s.bodyActivity
            row.barClass.progress = if (showValues) pct(p) else 0
            row.barClass.setIndicatorColor(color(if (top) R.color.accent_emerald else R.color.primary_cyan))
            row.tvClassPercent.text = if (showValues) getString(R.string.percent, pct(p)) else "--"
            val textColor = color(if (top) R.color.text_main else R.color.text_muted)
            row.tvClassName.setTextColor(textColor)
            row.tvClassPercent.setTextColor(textColor)
        }
    }

    private fun renderPosture(s: SensingSnapshot) = with(binding) {
        val e = s.ergonomics
        tvTiltValue.text = String.format(Locale.getDefault(), "%.0f°", e.postureTiltAngle)
        val (state, stateColor) = when (e.posture) {
            Posture.MOVING -> R.string.posture_moving to R.color.text_muted
            Posture.UPRIGHT -> R.string.posture_upright to R.color.accent_emerald
            Posture.SLOUCHING -> R.string.posture_slouching to R.color.accent_rose
            Posture.RECLINED -> R.string.posture_reclined to R.color.accent_amber
        }
        tvTiltState.setText(state)
        tvTiltState.setTextColor(color(stateColor))

        tvFidgetValue.text = String.format(Locale.getDefault(), "%.2f", e.fidgetIndex)
        tvFidgetState.setText(if (e.isFidgeting) R.string.fidget_restless else R.string.fidget_calm)
        tvFidgetState.setTextColor(color(if (e.isFidgeting) R.color.accent_amber else R.color.accent_emerald))

        tvSittingValue.text = getString(R.string.sitting_minutes, e.continuousStillMinutes)
        if (e.needsBreakPrompt) {
            tvSittingState.setText(R.string.sitting_break_now)
            tvSittingState.setTextColor(color(R.color.accent_rose))
        } else {
            tvSittingState.text = getString(R.string.sitting_goal, e.breakAfterMinutes)
            tvSittingState.setTextColor(color(R.color.text_muted))
        }
        progressSitting.progress = (e.continuousStillMinutes * 100 / e.breakAfterMinutes.coerceAtLeast(1)).coerceIn(0, 100)
        progressSitting.setIndicatorColor(color(if (e.needsBreakPrompt) R.color.accent_rose else R.color.accent_amber))
    }

    private fun renderWellness(w: WellnessReport) = with(binding) {
        tvWellnessScore.text = w.overallScore.toString()
        progressWellness.progress = w.overallScore
        val (grade, gradeColor) = when {
            w.overallScore >= 80 -> R.string.grade_excellent to R.color.accent_emerald
            w.overallScore >= 60 -> R.string.grade_good to R.color.primary_glow
            w.overallScore >= 40 -> R.string.grade_fair to R.color.accent_amber
            else -> R.string.grade_low to R.color.accent_rose
        }
        tvWellnessGrade.setText(grade)
        tvWellnessGrade.setTextColor(color(gradeColor))
        progressWellness.setIndicatorColor(color(gradeColor))

        barDiversity.progress = w.activityDiversityScore; tvDiversityScore.text = w.activityDiversityScore.toString()
        barErgonomic.progress = w.ergonomicScore; tvErgonomicScore.text = w.ergonomicScore.toString()
        barMovement.progress = w.movementScore; tvMovementScore.text = w.movementScore.toString()
        barSafety.progress = w.safetyScore; tvSafetyScore.text = w.safetyScore.toString()
        tvWellnessSummary.text = getString(R.string.wellness_summary,
            numberFormat.format(w.totalSteps), w.totalWalkMinutes, w.totalStillMinutes)
    }

    private fun renderSafety(s: SensingSnapshot) = with(binding) {
        val (status, detail, dot) = when (s.fallState) {
            FallState.MONITORING -> Triple(R.string.fall_monitoring, R.string.fall_monitoring_detail, R.color.accent_emerald)
            FallState.IMPACT_SUSPECTED -> Triple(R.string.fall_checking, R.string.fall_checking_detail, R.color.accent_amber)
            FallState.FALL_CONFIRMED -> Triple(R.string.fall_detected, R.string.fall_detected_detail, R.color.accent_rose)
        }
        tvFallStatus.setText(status)
        tvFallStatus.setTextColor(color(dot))
        tvFallDetail.setText(detail)
        fallDot.backgroundTintList = tint(dot)
        btnFallAck.visibility = if (s.fallState == FallState.FALL_CONFIRMED) View.VISIBLE else View.GONE
    }

    /** What the big button on the Fit to drive card does right now. */
    private enum class FitAction { TURN_ON, SET_UP, CHECK, BALANCE, CAB, START, NONE }
    private var fitAction = FitAction.TURN_ON
    private val fitTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * The Fit to drive card, first on the dashboard: one headline (the state that matters now),
     * one line of detail, whether drives are watched, and the one thing to do next. The border
     * takes the state's colour. Never offers a test while in a vehicle.
     */
    private fun renderFit(status: ServiceStatus, f: FitToDriveReport?, inVehicle: Boolean) = with(binding) {
        val enabled = FitToDriveStore.isEnabled(this@MainActivity)
        val warning = f != null && (f.advice == FitAdvice.TAKE_BALANCE_CHECK || f.advice == FitAdvice.DONT_DRIVE)
        val balanceAction = if (inVehicle) FitAction.NONE else FitAction.BALANCE
        class FitView(val head: String, val detail: String, val colour: Int, val action: FitAction)
        val v = when {
            !enabled -> FitView(getString(R.string.fit_card_head_off), getString(R.string.fit_card_off), R.color.text_muted, FitAction.TURN_ON)
            status == ServiceStatus.STOPPED -> FitView(getString(R.string.fit_card_head_stopped), getString(R.string.fit_card_stopped),
                R.color.accent_rose, FitAction.START)
            f == null -> FitView(getString(R.string.hero_starting), "", R.color.text_muted, FitAction.NONE)
            warning && inVehicle -> FitView(getString(R.string.fit_advice_stop_title), getString(R.string.fit_advice_stop_detail),
                R.color.accent_rose, FitAction.NONE)
            f.advice == FitAdvice.DONT_DRIVE -> FitView(getString(R.string.fit_advice_dont_title), getString(R.string.fit_advice_dont_detail),
                R.color.accent_rose, FitAction.CAB)
            f.advice == FitAdvice.TAKE_BALANCE_CHECK -> FitView(getString(R.string.fit_advice_balance_title),
                getString(R.string.fit_advice_balance_detail, pct(f.lastCheck?.percentile ?: 0.99f)), R.color.accent_amber, balanceAction)
            f.advice == FitAdvice.CONSIDER_BALANCE_CHECK -> FitView(getString(R.string.fit_advice_consider_title),
                getString(R.string.fit_advice_consider_detail), R.color.accent_amber, balanceAction)
            f.advice == FitAdvice.BALANCE_OK -> FitView(getString(R.string.fit_advice_ok_title), getString(R.string.fit_advice_ok_detail),
                R.color.accent_emerald, FitAction.NONE)
            f.baselineStatus == BaselineStatus.LEARNING -> FitView(getString(R.string.fit_card_head_setup),
                getString(R.string.fit_card_learning, pct(f.baselineSegments.toFloat() / f.segmentsForProvisional)), R.color.accent_amber, FitAction.SET_UP)
            f.balanceReferences < f.balanceReferencesNeeded -> FitView(getString(R.string.fit_card_head_setup),
                getString(R.string.fit_card_balance_left, f.balanceReferences, f.balanceReferencesNeeded), R.color.accent_amber, FitAction.SET_UP)
            else -> {
                val c = f.lastCheck
                val (head, colour) = when (c?.level) {
                    null -> getString(R.string.fit_card_head_ready) to R.color.accent_emerald
                    WalkLevel.NORMAL -> getString(R.string.fit_level_normal) to R.color.accent_emerald
                    WalkLevel.UNUSUAL -> getString(R.string.fit_level_unusual) to R.color.accent_amber
                    WalkLevel.LARGE_GAP -> getString(R.string.fit_level_large) to R.color.accent_rose
                }
                val detail = if (c?.level == null) getString(R.string.fit_card_no_check)
                    else getString(R.string.fit_card_last, fitTime.format(Date(c.wallMillis)), getString(when (c.trigger) {
                        CheckTrigger.TRIP_START -> R.string.fit_trigger_trip
                        CheckTrigger.CAR_BLUETOOTH -> R.string.fit_trigger_bt
                        CheckTrigger.MANUAL -> R.string.fit_trigger_manual
                    }))
                FitView(head, detail, colour, if (inVehicle) FitAction.NONE else FitAction.CHECK)
            }
        }
        tvFitHeadline.text = v.head
        tvFitHeadline.setTextColor(color(if (v.colour == R.color.text_muted) R.color.text_main else v.colour))
        tvFitStatus.text = v.detail
        tvFitStatus.visibility = if (v.detail.isEmpty()) View.GONE else View.VISIBLE
        fitDot.backgroundTintList = tint(v.colour)
        cardFit.strokeColor = color(if (v.colour == R.color.text_muted) R.color.card_border else v.colour)

        // Is every drive watched? (the contact alert is the safety net)
        val watch = when {
            !enabled || status == ServiceStatus.STOPPED || f == null -> null
            f.testDriveSecondsLeft > 0 -> getString(R.string.fit_card_test_drive,
                String.format(Locale.US, "%d:%02d", f.testDriveSecondsLeft / 60, f.testDriveSecondsLeft % 60)) to R.color.primary_glow
            f.watchingDriving -> getString(R.string.fit_watching_driving) to R.color.accent_amber
            ContactAlert.ready(this@MainActivity) -> getString(R.string.fit_card_watch_all,
                FitToDriveStore.contactName(this@MainActivity).ifBlank { FitToDriveStore.contactPhone(this@MainActivity) }) to R.color.accent_emerald
            else -> getString(R.string.fit_card_watch_off) to R.color.text_muted
        }
        tvFitWatch.visibility = if (watch != null) View.VISIBLE else View.GONE
        watch?.let { (text, col) -> tvFitWatch.text = text; tvFitWatch.setTextColor(color(col)) }

        fitAction = v.action
        btnFitAction.visibility = if (v.action == FitAction.NONE) View.GONE else View.VISIBLE
        btnFitAction.setText(when (v.action) {
            FitAction.TURN_ON -> R.string.fit_card_action_on
            FitAction.SET_UP -> R.string.fit_card_action_setup
            FitAction.CHECK -> R.string.fit_check_now
            FitAction.BALANCE -> R.string.fit_action_balance
            FitAction.CAB -> R.string.fit_action_cab
            FitAction.START, FitAction.NONE -> R.string.action_start
        })
        btnFitAction.backgroundTintList = tint(if (v.colour == R.color.accent_rose && v.action != FitAction.START) R.color.accent_amber else R.color.primary_cyan)
    }

    private fun onFitAction() {
        val fit = Intent(this, FitToDriveActivity::class.java)
        when (fitAction) {
            FitAction.TURN_ON, FitAction.SET_UP -> startActivity(fit)
            FitAction.CHECK -> startActivity(fit.putExtra(FitToDriveActivity.EXTRA_CHECK_NOW, true))
            FitAction.BALANCE -> startActivity(fit.putExtra(FitToDriveActivity.EXTRA_START_BALANCE, true))
            FitAction.CAB -> try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode("taxi"))))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, R.string.fit_no_app, android.widget.Toast.LENGTH_SHORT).show()
            }
            FitAction.START -> requestPermissionsThenStart()
            FitAction.NONE -> Unit
        }
    }

    private fun renderRoad(s: SensingSnapshot) = with(binding) {
        tvRoadStatus.setText(when {
            s.vehicleDetection == VehicleDetection.NO_PERMISSION -> R.string.road_no_permission
            s.vehicleDetection == VehicleDetection.UNAVAILABLE -> R.string.road_unavailable
            s.roadMonitoringActive -> R.string.road_active
            else -> R.string.road_paused
        })
        tvRoadStatus.setTextColor(color(if (s.roadMonitoringActive) R.color.accent_emerald else R.color.text_muted))
        tvPotholesCount.text = s.potholes.toString()
        tvSpeedBreakersCount.text = s.speedBreakers.toString()
    }

    private fun renderLog(s: SensingSnapshot) = with(binding) {
        val t = s.lastTransition
        if (t == null) {
            tvLastTransition.setText(R.string.transition_none)
            tvTransitionMeta.text = ""
        } else {
            tvLastTransition.text = getString(R.string.transition_arrow, title(t.fromActivity), title(t.toActivity))
            tvTransitionMeta.text = getString(R.string.transition_at, timeFormat.format(Date(t.wallTimeMillis))) +
                ", " + getString(R.string.transition_count, s.transitionCount)
        }
    }

    private fun renderDiagnostics(s: SensingSnapshot) = with(binding) {
        if (!diagnosticsOpen) return@with
        tvLiveSensor.text = getString(R.string.diag_imu, s.ax, s.ay, s.az, s.gx, s.gy, s.gz)
        tvEngineInfo.text = getString(R.string.diag_engine,
            s.measuredRateHz, s.inferenceMillis, s.inferencesRun,
            getString(if (s.ecoMode) R.string.diag_mode_eco else R.string.diag_mode_live),
            getString(if (s.hasGyro) R.string.yes else R.string.no),
            s.stepSource)
    }
}
