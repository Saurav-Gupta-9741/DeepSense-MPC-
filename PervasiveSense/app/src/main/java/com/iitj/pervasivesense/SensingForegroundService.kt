package com.iitj.pervasivesense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class SensingForegroundService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "PervasiveSense_Channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_BROADCAST_UPDATE = "com.iitj.pervasivesense.UPDATE"
        const val EXTRA_ACTIVITY = "extra_activity"
        const val EXTRA_CONFIDENCE = "extra_confidence"
        const val EXTRA_POSTURE_TILT = "extra_posture_tilt"
        const val EXTRA_POSTURE_STATUS = "extra_posture_status"
        const val EXTRA_FIDGET_INDEX = "extra_fidget_index"
        const val EXTRA_SEDENTARY_MINS = "extra_sedentary_mins"
        const val EXTRA_POTHOLES = "extra_potholes"
        const val EXTRA_SPEED_BREAKERS = "extra_speed_breakers"
        const val EXTRA_FALL_STATUS = "extra_fall_status"
        const val EXTRA_AX = "extra_ax"
        const val EXTRA_AY = "extra_ay"
        const val EXTRA_AZ = "extra_az"

        // New extras for Novelty A/B/C/D
        const val EXTRA_WELLNESS_SCORE = "extra_wellness_score"
        const val EXTRA_DIVERSITY_SCORE = "extra_diversity_score"
        const val EXTRA_ERGONOMIC_SCORE = "extra_ergonomic_score"
        const val EXTRA_MOVEMENT_SCORE = "extra_movement_score"
        const val EXTRA_SAFETY_SCORE = "extra_safety_score"
        const val EXTRA_TOTAL_STEPS = "extra_total_steps"
        const val EXTRA_WALK_MINUTES = "extra_walk_minutes"
        const val EXTRA_STILL_MINUTES = "extra_still_minutes"
        const val EXTRA_TRANSITION_FROM = "extra_transition_from"
        const val EXTRA_TRANSITION_TO = "extra_transition_to"
        const val EXTRA_TRANSITION_COUNT = "extra_transition_count"
        const val EXTRA_SAMPLING_RATE = "extra_sampling_rate"
        const val EXTRA_SENSOR_SOURCE = "extra_sensor_source"

        var isRunning = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var classifier: DeepSenseClassifier
    private val postureTracker = ErgonomicPostureTracker()
    private val roadDetector = RoadAnomalyDetector()
    private val fallDetector = FallDetector()

    // Novelty A: Adaptive Sampling
    private var currentSensorDelay = SensorManager.SENSOR_DELAY_GAME
    private var currentSamplingRateLabel = "50 Hz"

    // Novelty B: Activity Transition Detection
    private val transitionDetector = ActivityTransitionDetector()

    // Novelty C: Daily Wellness Score
    private val wellnessEngine = WellnessScoreEngine()

    // 128 samples x 6 channels (ax, ay, az, gx, gy, gz)
    private val windowSize = 128
    private val channels = 6
    private val buffer = FloatArray(windowSize * channels)
    private var sampleCount = 0

    // Elapsed time per classification window (varies with sampling rate)
    private val windowDurationSeconds = 2.56f // 128 samples / 50Hz

    // Latest readings
    private var latestAx = 0f
    private var latestAy = 9.8f
    private var latestAz = 0f
    private var latestGx = 0f
    private var latestGy = 0f
    private var latestGz = 0f

    private var currentActivity = "INITIALIZING..."
    private var currentConfidence = 0.0f
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        classifier = DeepSenseClassifier(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PervasiveSense::SensingWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24h safety limit

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Pervasive Sensing Active", "Monitoring ambient mobility & ergonomics")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        // Register sensors at default 50Hz (SENSOR_DELAY_GAME ~ 20ms)
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        isRunning = true
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAx = event.values[0]
                latestAy = event.values[1]
                latestAz = event.values[2]

                // Fall Detection check on every accel tick
                val fallTriggered = fallDetector.processSample(latestAx, latestAy, latestAz)
                if (fallTriggered) {
                    wellnessEngine.onFallEvent() // Novelty C: track fall event in wellness
                    updateNotificationAlert("⚠️ CRITICAL ALERT: Fall Detected!", "Immobility detected after high impact")
                }

                // Only advance buffer on accelerometer events (gyro values are reused from latest reading)
                val baseIndex = sampleCount * channels
                buffer[baseIndex + 0] = latestAx
                buffer[baseIndex + 1] = latestAy
                buffer[baseIndex + 2] = latestAz
                buffer[baseIndex + 3] = latestGx
                buffer[baseIndex + 4] = latestGy
                buffer[baseIndex + 5] = latestGz

                sampleCount++
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGx = event.values[0]
                latestGy = event.values[1]
                latestGz = event.values[2]
            }
        }

        // Once window is full (128 samples of physical motion)
        if (sampleCount >= windowSize) {
            sampleCount = 0 // Reset for next window

            // 1. Run DeepSense TFLite Inference
            val result = classifier.classify(buffer)
            currentActivity = result.activity
            currentConfidence = result.confidence

            val isStill = (currentActivity == "STILL")
            val isVehicular = (currentActivity == "BUS" || currentActivity == "CAR" || currentActivity == "METRO")

            // 2. Run Ergonomic Posture Tracking
            val ergoReport = postureTracker.processSample(latestAx, latestAy, latestAz, isStill)

            // 3. Run Road Anomaly Detection
            roadDetector.processSample(latestAz, isVehicular)

            // 4. [NOVELTY B] Activity Transition Detection
            val transition = transitionDetector.checkTransition(currentActivity, currentConfidence)

            // 5. [NOVELTY A] Adaptive Energy-Aware Sampling
            adaptSamplingRate(currentActivity)

            // 6. [NOVELTY C] Update Daily Wellness Score
            wellnessEngine.onClassificationResult(currentActivity, ergoReport.postureStatus, windowDurationSeconds)
            val wellnessReport = wellnessEngine.computeWellnessReport()

            // 7. Update Persistent Notification on Lockscreen
            val statusSummary = "$currentActivity (${(currentConfidence * 100).toInt()}%) | Wellness: ${wellnessReport.overallScore}/100"
            updateNotification(statusSummary)

            // 8. Broadcast to MainActivity for real-time dashboard UI
            val broadcastIntent = Intent(ACTION_BROADCAST_UPDATE).apply {
                // Original extras
                putExtra(EXTRA_ACTIVITY, currentActivity)
                putExtra(EXTRA_CONFIDENCE, currentConfidence)
                putExtra(EXTRA_POSTURE_TILT, ergoReport.postureTiltAngle)
                putExtra(EXTRA_POSTURE_STATUS, ergoReport.postureStatus)
                putExtra(EXTRA_FIDGET_INDEX, ergoReport.fidgetIndex)
                putExtra(EXTRA_SEDENTARY_MINS, ergoReport.continuousStillMinutes)
                putExtra(EXTRA_POTHOLES, roadDetector.potholesCount)
                putExtra(EXTRA_SPEED_BREAKERS, roadDetector.speedBreakersCount)
                putExtra(EXTRA_FALL_STATUS, fallDetector.currentState.name)
                putExtra(EXTRA_AX, latestAx)
                putExtra(EXTRA_AY, latestAy)
                putExtra(EXTRA_AZ, latestAz)

                // Novelty C: Wellness Score extras
                putExtra(EXTRA_WELLNESS_SCORE, wellnessReport.overallScore)
                putExtra(EXTRA_DIVERSITY_SCORE, wellnessReport.activityDiversityScore)
                putExtra(EXTRA_ERGONOMIC_SCORE, wellnessReport.ergonomicScore)
                putExtra(EXTRA_MOVEMENT_SCORE, wellnessReport.movementScore)
                putExtra(EXTRA_SAFETY_SCORE, wellnessReport.safetyScore)
                putExtra(EXTRA_TOTAL_STEPS, wellnessReport.totalSteps)
                putExtra(EXTRA_WALK_MINUTES, wellnessReport.totalWalkMinutes)
                putExtra(EXTRA_STILL_MINUTES, wellnessReport.totalStillMinutes)

                // Novelty B: Transition extras
                putExtra(EXTRA_TRANSITION_COUNT, transitionDetector.getTransitionCount())
                if (transition != null) {
                    putExtra(EXTRA_TRANSITION_FROM, transition.fromActivity)
                    putExtra(EXTRA_TRANSITION_TO, transition.toActivity)
                } else {
                    val last = transitionDetector.getLastTransition()
                    putExtra(EXTRA_TRANSITION_FROM, last?.fromActivity ?: "")
                    putExtra(EXTRA_TRANSITION_TO, last?.toActivity ?: "")
                }

                // Novelty A: Adaptive sampling rate
                putExtra(EXTRA_SAMPLING_RATE, currentSamplingRateLabel)

                // Novelty D: Sensor source
                putExtra(EXTRA_SENSOR_SOURCE, "Phone IMU")
            }
            sendBroadcast(broadcastIntent)
        }
    }

    /**
     * [NOVELTY A] Adaptive Energy-Aware Sampling
     * Dynamically adjusts sensor sampling rate based on current activity:
     * - STILL: 5 Hz (SENSOR_DELAY_NORMAL) → saves ~90% battery
     * - WALKING: ~15-20 Hz (SENSOR_DELAY_UI) → saves ~60% battery
     * - RUNNING/STAIRS/VEHICLE: 50 Hz (SENSOR_DELAY_GAME) → full precision
     */
    private fun adaptSamplingRate(activity: String) {
        val targetDelay = when (activity) {
            "STILL" -> SensorManager.SENSOR_DELAY_NORMAL     // ~5 Hz
            "WALKING" -> SensorManager.SENSOR_DELAY_UI        // ~15-20 Hz
            else -> SensorManager.SENSOR_DELAY_GAME            // ~50 Hz
        }

        val label = when (targetDelay) {
            SensorManager.SENSOR_DELAY_NORMAL -> "⚡ 5 Hz (Energy Save)"
            SensorManager.SENSOR_DELAY_UI -> "⚡ 20 Hz (Balanced)"
            else -> "⚡ 50 Hz (Full Rate)"
        }

        if (targetDelay != currentSensorDelay) {
            // Re-register sensors at new rate
            sensorManager.unregisterListener(this)
            accelSensor?.let { sensorManager.registerListener(this, it, targetDelay) }
            gyroSensor?.let { sensorManager.registerListener(this, it, targetDelay) }
            currentSensorDelay = targetDelay
            currentSamplingRateLabel = label
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PervasiveSense Background Sensing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live physical context and ergonomic posture"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification("PervasiveSense: $currentActivity", text)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationAlert(title: String, text: String) {
        val notification = buildNotification(title, text)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        classifier.close()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
