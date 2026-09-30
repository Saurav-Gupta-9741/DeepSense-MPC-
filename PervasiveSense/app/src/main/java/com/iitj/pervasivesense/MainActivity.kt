package com.iitj.pervasivesense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iitj.pervasivesense.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACK_FALL = "com.iitj.pervasivesense.extra.ACK_FALL"
        private const val DEFAULT_FOOTER = "Foreground service keeps sensing with the screen locked"
    }

    private lateinit var binding: ActivityMainBinding
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SensingRepository.status, SensingRepository.snapshot, SensingRepository.message) { st, snap, msg ->
                    Triple(st, snap, msg)
                }.collect { (st, snap, msg) -> render(st, snap, msg) }
            }
        }
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
                "\"Physical activity\" permission is required to run background sensing on Android 14+. " +
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

    private fun render(status: ServiceStatus, s: SensingSnapshot?, message: String?) = with(binding) {
        when (status) {
            ServiceStatus.STOPPED -> {
                btnToggleService.text = "START PERVASIVE SENSING"
                btnToggleService.backgroundTintList = ColorStateList.valueOf(color(R.color.primary_cyan))
            }
            ServiceStatus.STARTING -> {
                btnToggleService.text = "STARTING... (TAP TO CANCEL)"
                btnToggleService.backgroundTintList = ColorStateList.valueOf(color(R.color.accent_amber))
            }
            ServiceStatus.RUNNING -> {
                btnToggleService.text = "STOP PERVASIVE SENSING"
                btnToggleService.backgroundTintList = ColorStateList.valueOf(color(R.color.accent_rose))
            }
        }
        tvServiceMessage.text = message ?: DEFAULT_FOOTER
        tvServiceMessage.setTextColor(color(if (message != null) R.color.accent_amber else R.color.text_muted))

        if (status == ServiceStatus.STOPPED || s == null) {
            tvCurrentActivity.text = if (status == ServiceStatus.STOPPED) "SERVICE STOPPED" else "STARTING..."
            tvCurrentActivity.setTextColor(color(R.color.text_muted))
            tvConfidence.text = "Confidence: --"
            progressConfidence.progress = 0
            return@with
        }

        // Card 1: context
        when (s.status) {
            EngineStatus.WARMING_UP -> {
                tvCurrentActivity.text = "WARMING UP ${(s.warmupProgress * 100).toInt()}%"
                tvCurrentActivity.setTextColor(color(R.color.accent_amber))
                tvConfidence.text = "Collecting the first 2.56 s of motion"
                progressConfidence.progress = (s.warmupProgress * 100).toInt()
            }
            EngineStatus.MODEL_ERROR -> {
                tvCurrentActivity.text = "MODEL ERROR"
                tvCurrentActivity.setTextColor(color(R.color.accent_rose))
                tvConfidence.text = "See message below the button"
                progressConfidence.progress = 0
            }
            EngineStatus.ACTIVE -> {
                tvCurrentActivity.text = Display.context(s.context)
                tvCurrentActivity.setTextColor(color(R.color.accent_emerald))
                val body = if (s.context == "IN_VEHICLE" && s.bodyActivity != null) " | body: ${Display.context(s.bodyActivity)}" else ""
                tvConfidence.text = "Confidence: ${(s.confidence * 100).toInt()}%$body"
                progressConfidence.progress = (s.confidence * 100).toInt()
            }
        }
        tvLiveSensor.text = String.format(Locale.US, "acc  x%7.2f y%7.2f z%7.2f m/s²\ngyro x%7.2f y%7.2f z%7.2f rad/s",
            s.ax, s.ay, s.az, s.gx, s.gy, s.gz)
        tvProbabilities.text = if (s.inferencesRun == 0L) "Model output appears after warm-up"
            else s.probabilities.take(3).joinToString("  ") { (n, p) -> "${Display.context(n)} ${(p * 100).toInt()}%" }
        tvEngineInfo.text = String.format(Locale.US, "device %.1f Hz -> 50 Hz grid | inference %.2f ms | #%d%s",
            s.measuredRateHz, s.inferenceMillis, s.inferencesRun, if (s.hasGyro) "" else " | NO GYRO")

        // Card 2: ergonomics
        val e = s.ergonomics
        tvPostureTilt.text = String.format(Locale.US, "%.1f° (%s)", e.postureTiltAngle, e.postureStatus)
        tvFidgetIndex.text = String.format(Locale.US, "%.3f (%s)", e.fidgetIndex, if (e.isFidgeting) "Restless" else "Calm")
        tvFidgetIndex.setTextColor(color(if (e.isFidgeting) R.color.accent_amber else R.color.accent_emerald))
        tvSedentaryTimer.text = "Continuous stationary time: ${e.continuousStillMinutes} min (break goal: 45 min)"
        tvSedentaryTimer.setTextColor(color(if (e.needsBreakPrompt) R.color.accent_rose else R.color.accent_amber))

        // Card 3: road
        tvRoadStatus.text = when {
            s.vehicleDetection == VehicleDetection.NO_PERMISSION -> "Vehicle detection needs the Physical-activity permission"
            s.vehicleDetection == VehicleDetection.UNAVAILABLE -> "Vehicle detection unavailable (Google Play services)"
            s.roadMonitoringActive -> "In a vehicle: monitoring road surface"
            else -> "Not in a vehicle: road monitoring paused"
        }
        tvPotholesCount.text = "${s.potholes} detected"
        tvSpeedBreakersCount.text = "${s.speedBreakers} detected"

        // Card 4: fall
        when (s.fallState) {
            FallState.MONITORING -> {
                tvFallStatus.text = "Armed: impact + immobility + posture-change check"
                tvFallStatus.setTextColor(color(R.color.accent_emerald))
            }
            FallState.IMPACT_SUSPECTED -> {
                tvFallStatus.text = "Impact detected: checking for immobility..."
                tvFallStatus.setTextColor(color(R.color.accent_amber))
            }
            FallState.FALL_CONFIRMED -> {
                tvFallStatus.text = "FALL DETECTED"
                tvFallStatus.setTextColor(color(R.color.accent_rose))
            }
        }
        btnFallAck.visibility = if (s.fallState == FallState.FALL_CONFIRMED) android.view.View.VISIBLE else android.view.View.GONE

        // Card 5: wellness
        val w = s.wellness
        tvWellnessScore.text = "${w.overallScore}"
        val (grade, gradeColor) = when {
            w.overallScore >= 80 -> "EXCELLENT" to R.color.accent_emerald
            w.overallScore >= 60 -> "GOOD" to R.color.primary_glow
            w.overallScore >= 40 -> "FAIR" to R.color.accent_amber
            else -> "LOW" to R.color.accent_rose
        }
        tvWellnessGrade.text = grade
        tvWellnessGrade.setTextColor(color(gradeColor))
        tvDiversityScore.text = "${w.activityDiversityScore}"
        tvErgonomicScore.text = "${w.ergonomicScore}"
        tvMovementScore.text = "${w.movementScore}"
        tvSafetyScore.text = "${w.safetyScore}"
        tvStepsAndTime.text = "Steps: ${w.totalSteps} (${s.stepSource}) | Walk: ${w.totalWalkMinutes}m | Still: ${w.totalStillMinutes}m"

        // Card 6: transitions & sampling
        tvSamplingRate.text = if (s.ecoMode) "50 Hz | ECO" else "50 Hz | LIVE"
        tvLastTransition.text = s.lastTransition?.let {
            "${Display.context(it.fromActivity)} -> ${Display.context(it.toActivity)} at ${timeFormat.format(Date(it.wallTimeMillis))}"
        } ?: "No transitions detected yet"
        tvTransitionCount.text = "Total transitions today: ${s.transitionCount} | Sensor: Phone IMU"
    }
}
