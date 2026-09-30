package com.iitj.pervasivesense

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.iitj.pervasivesense.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SensingForegroundService.ACTION_BROADCAST_UPDATE) {
                // Original data
                val activity = intent.getStringExtra(SensingForegroundService.EXTRA_ACTIVITY) ?: "UNKNOWN"
                val confidence = intent.getFloatExtra(SensingForegroundService.EXTRA_CONFIDENCE, 0f)
                val postureTilt = intent.getFloatExtra(SensingForegroundService.EXTRA_POSTURE_TILT, 0f)
                val postureStatus = intent.getStringExtra(SensingForegroundService.EXTRA_POSTURE_STATUS) ?: "Unknown"
                val fidgetIndex = intent.getFloatExtra(SensingForegroundService.EXTRA_FIDGET_INDEX, 0f)
                val sedentaryMins = intent.getIntExtra(SensingForegroundService.EXTRA_SEDENTARY_MINS, 0)
                val potholes = intent.getIntExtra(SensingForegroundService.EXTRA_POTHOLES, 0)
                val speedBreakers = intent.getIntExtra(SensingForegroundService.EXTRA_SPEED_BREAKERS, 0)
                val fallStatus = intent.getStringExtra(SensingForegroundService.EXTRA_FALL_STATUS) ?: "MONITORING"
                val ax = intent.getFloatExtra(SensingForegroundService.EXTRA_AX, 0f)
                val ay = intent.getFloatExtra(SensingForegroundService.EXTRA_AY, 0f)
                val az = intent.getFloatExtra(SensingForegroundService.EXTRA_AZ, 0f)

                // Novelty C: Wellness data
                val wellnessScore = intent.getIntExtra(SensingForegroundService.EXTRA_WELLNESS_SCORE, 0)
                val diversityScore = intent.getIntExtra(SensingForegroundService.EXTRA_DIVERSITY_SCORE, 0)
                val ergonomicScore = intent.getIntExtra(SensingForegroundService.EXTRA_ERGONOMIC_SCORE, 0)
                val movementScore = intent.getIntExtra(SensingForegroundService.EXTRA_MOVEMENT_SCORE, 0)
                val safetyScore = intent.getIntExtra(SensingForegroundService.EXTRA_SAFETY_SCORE, 100)
                val totalSteps = intent.getIntExtra(SensingForegroundService.EXTRA_TOTAL_STEPS, 0)
                val walkMinutes = intent.getIntExtra(SensingForegroundService.EXTRA_WALK_MINUTES, 0)
                val stillMinutes = intent.getIntExtra(SensingForegroundService.EXTRA_STILL_MINUTES, 0)

                // Novelty B: Transition data
                val transitionFrom = intent.getStringExtra(SensingForegroundService.EXTRA_TRANSITION_FROM) ?: ""
                val transitionTo = intent.getStringExtra(SensingForegroundService.EXTRA_TRANSITION_TO) ?: ""
                val transitionCount = intent.getIntExtra(SensingForegroundService.EXTRA_TRANSITION_COUNT, 0)

                // Novelty A: Sampling rate
                val samplingRate = intent.getStringExtra(SensingForegroundService.EXTRA_SAMPLING_RATE) ?: "⚡ 50 Hz"

                // Novelty D: Sensor source
                val sensorSource = intent.getStringExtra(SensingForegroundService.EXTRA_SENSOR_SOURCE) ?: "Phone IMU"

                // ═══ UPDATE UI ═══

                // Card 1: Activity Classification
                binding.tvCurrentActivity.text = activity
                binding.tvConfidence.text = "Confidence: ${(confidence * 100).toInt()}%"
                binding.progressConfidence.progress = (confidence * 100).toInt()
                binding.tvLiveSensor.text = String.format("IMU: ax: %.2f | ay: %.2f | az: %.2f m/s²", ax, ay, az)

                // Card 2: Ergonomic Posture
                binding.tvPostureTilt.text = String.format("%.1f° (%s)", postureTilt, postureStatus)
                binding.tvFidgetIndex.text = String.format("%.2f (%s)", fidgetIndex, if (fidgetIndex > 0.15f) "Restless/Fidgeting" else "Calm/Stable")
                binding.tvSedentaryTimer.text = "Continuous Stationary Time: $sedentaryMins min (Break goal: 45m)"

                // Card 3: Road Anomalies
                binding.tvPotholesCount.text = "$potholes detected"
                binding.tvSpeedBreakersCount.text = "$speedBreakers detected"

                // Card 4: Fall Detection
                binding.tvFallStatus.text = when (fallStatus) {
                    "MONITORING" -> "Armed with Anti-False-Alarm"
                    "IMPACT_SUSPECTED" -> "⚠️ Impact Detected! Observing immobility..."
                    "FALL_CONFIRMED" -> "🚨 FALL CONFIRMED! Alerting..."
                    else -> fallStatus
                }

                // Card 5: Daily Wellness Score [NOVELTY C]
                binding.tvWellnessScore.text = "$wellnessScore"
                binding.tvDiversityScore.text = "$diversityScore"
                binding.tvErgonomicScore.text = "$ergonomicScore"
                binding.tvMovementScore.text = "$movementScore"
                binding.tvSafetyScore.text = "$safetyScore"
                binding.tvStepsAndTime.text = "Steps: $totalSteps | Walk: ${walkMinutes}m | Still: ${stillMinutes}m"

                // Wellness indicator based on score (using universal unicode symbols)
                binding.tvWellnessEmoji.text = when {
                    wellnessScore >= 80 -> "✨"
                    wellnessScore >= 60 -> "⭐"
                    wellnessScore >= 40 -> "⚡"
                    else -> "⚠️"
                }

                // Card 6: Transitions & Adaptive Sampling [NOVELTY A+B]
                binding.tvSamplingRate.text = samplingRate
                if (transitionFrom.isNotEmpty() && transitionTo.isNotEmpty()) {
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    binding.tvLastTransition.text = "$transitionFrom → $transitionTo ($timeStr)"
                }
                binding.tvTransitionCount.text = "Total transitions: $transitionCount | Sensor: $sensorSource"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRequiredPermissions()

        updateServiceButtonState()

        binding.btnToggleService.setOnClickListener {
            if (SensingForegroundService.isRunning) {
                stopSensingService()
            } else {
                startSensingService()
            }
            updateServiceButtonState()
        }
    }

    private fun startSensingService() {
        val serviceIntent = Intent(this, SensingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopSensingService() {
        val serviceIntent = Intent(this, SensingForegroundService::class.java)
        stopService(serviceIntent)
        binding.tvCurrentActivity.text = "SERVICE STOPPED"
        binding.progressConfidence.progress = 0
    }

    private fun updateServiceButtonState() {
        if (SensingForegroundService.isRunning) {
            binding.btnToggleService.text = "STOP PERVASIVE SENSING"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
        } else {
            binding.btnToggleService.text = "START PERVASIVE SENSING"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_cyan))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SensingForegroundService.ACTION_BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        updateServiceButtonState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }
}
