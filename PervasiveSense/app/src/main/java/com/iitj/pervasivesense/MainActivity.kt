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
                ServiceStatus.RUNNING, ServiceStatus.STARTING ->
                    stopService(Intent(this, SensingForegroundService::class.java))
                ServiceStatus.STOPPED -> requestPermissionsThenStart()
            }
        }
        binding.btnFallAck.setOnClickListener { SensingRepository.acknowledgeFall() }
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
