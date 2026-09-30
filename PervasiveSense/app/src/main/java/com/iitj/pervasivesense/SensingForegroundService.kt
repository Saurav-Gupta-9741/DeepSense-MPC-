package com.iitj.pervasivesense

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Continuous sensing service. All sensor callbacks, inference and algorithm
 * work run on a dedicated high-priority HandlerThread (never the main thread);
 * results reach the UI through [SensingRepository].
 */
class SensingForegroundService : Service(), SensingEngine.Listener, SensingRepository.Controller {

    companion object {
        private const val TAG = "SensingService"
        const val STATUS_CHANNEL_ID = "PervasiveSense_Status"
        const val ALERT_CHANNEL_ID = "PervasiveSense_Alerts"
        const val NOTIFICATION_ID = 1001
        const val FALL_NOTIFICATION_ID = 1002
        const val BREAK_NOTIFICATION_ID = 1003
        const val ACTION_STOP = "com.iitj.pervasivesense.action.STOP"
        private const val PREFS = "pervasivesense_daily"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val NOTIFICATION_MIN_INTERVAL_NS = 3_000_000_000L
        private const val SAVE_INTERVAL_NS = 30_000_000_000L
        private const val WAKELOCK_RENEW_NS = 60_000_000_000L
        private const val VEHICLE_DETECTION_INTERVAL_MS = 5_000L

        fun hasActivityRecognitionPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var notificationManager: NotificationManager
    private lateinit var sensorManager: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null

    // Owned by the sensing thread.
    private var engine: SensingEngine? = null
    private var classifier: DeepSenseClassifier? = null
    private var imu: PhoneImuSource? = null
    private var vehiclePendingIntent: PendingIntent? = null
    private var wallOffsetMs = Long.MIN_VALUE
    private var lastNotificationT = Long.MIN_VALUE
    private var lastNotificationText = ""
    private var lastSaveT = Long.MIN_VALUE
    private var lastWakeRenewT = Long.MIN_VALUE
    private var started = false

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            engine?.onHardwareStepCounter(event.values[0].toLong())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        SensingRepository.setStatus(ServiceStatus.STARTING)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createChannels()
        thread = HandlerThread("PervasiveSense-Sensing", Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
        handler = Handler(thread.looper)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PervasiveSense::Sensing")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        try {
            val notification = buildStatusNotification("Starting", "Loading DeepSense model")
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // e.g. Android 14+ refuses a "health" foreground service without the
            // Physical-activity permission.
            Log.e(TAG, "startForeground failed", e)
            SensingRepository.setMessage("Cannot start sensing: ${e.message}")
            SensingRepository.setStatus(ServiceStatus.STOPPED)
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        SensingRepository.setMessage(null)
        handler.post { startSensing() }
        return START_STICKY
    }

    override fun onDestroy() {
        SensingRepository.detach(this)
        if (started) handler.post { stopSensing() }
        thread.quitSafely() // runs the pending teardown first
        notificationManager.cancel(BREAK_NOTIFICATION_ID)
        SensingRepository.setStatus(ServiceStatus.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ sensing thread
    private fun startSensing() {
        val c = DeepSenseClassifier(this)
        classifier = c
        c.initError?.let { SensingRepository.setMessage("DeepSense model failed to load: $it") }

        val source = PhoneImuSource(this)
        imu = source
        val e = SensingEngine(
            classifier = c,
            wallClockOf = { t -> (if (wallOffsetMs == Long.MIN_VALUE) System.currentTimeMillis() else wallOffsetMs + t / 1_000_000) },
            dayKeyOf = { ms -> Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString() },
            listener = this,
            hasGyroscope = source.hasGyroscope
        )
        loadDailyState()?.let { e.wellness.restore(it) }
        engine = e

        val sink = object : ImuSink {
            override fun onAccelerometer(tNanos: Long, x: Float, y: Float, z: Float) {
                // Map sensor timestamps to wall-clock time once, from the first live event.
                if (wallOffsetMs == Long.MIN_VALUE) wallOffsetMs = System.currentTimeMillis() - tNanos / 1_000_000
                e.onAccelerometer(tNanos, x, y, z)
            }
            override fun onGyroscope(tNanos: Long, x: Float, y: Float, z: Float) = e.onGyroscope(tNanos, x, y, z)
        }
        if (!source.start(sink, handler)) {
            SensingRepository.setMessage("This device has no accelerometer")
            Handler(mainLooper).post { stopSelf() }
            return
        }
        startHardwareStepCounter(e)
        startVehicleDetection(e)
        updateWakeLock()
        SensingRepository.attach(this)
        SensingRepository.setStatus(ServiceStatus.RUNNING)
    }

    private fun stopSensing() {
        imu?.stop()
        sensorManager.unregisterListener(stepListener)
        vehiclePendingIntent?.let { pi ->
            try {
                ActivityRecognition.getClient(this).removeActivityUpdates(pi)
            } catch (e: SecurityException) {
                Log.w(TAG, "removeActivityUpdates", e)
            }
            pi.cancel()
        }
        engine?.let { saveDailyState(it.wellness.exportState()) }
        classifier?.close()
        engine = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun startHardwareStepCounter(e: SensingEngine) {
        val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val usable = counter != null && hasActivityRecognitionPermission(this) &&
            sensorManager.registerListener(stepListener, counter, SensorManager.SENSOR_DELAY_NORMAL, handler)
        e.setHardwareStepCounter(usable)
    }

    @SuppressLint("MissingPermission") // checked explicitly below
    private fun startVehicleDetection(e: SensingEngine) {
        if (!hasActivityRecognitionPermission(this)) {
            e.vehicleDetection = VehicleDetection.NO_PERMISSION
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, 7, Intent(this, VehicleActivityReceiver::class.java), flags)
        vehiclePendingIntent = pi
        try {
            ActivityRecognition.getClient(this)
                .requestActivityUpdates(VEHICLE_DETECTION_INTERVAL_MS, pi)
                .addOnSuccessListener { handler.post { engine?.vehicleDetection = VehicleDetection.ACTIVE } }
                .addOnFailureListener { err ->
                    Log.w(TAG, "Activity Recognition unavailable", err)
                    handler.post { engine?.vehicleDetection = VehicleDetection.UNAVAILABLE }
                }
        } catch (se: SecurityException) {
            e.vehicleDetection = VehicleDetection.NO_PERMISSION
        }
    }

    /** Wake lock is needed unless the sensor hub can wake the CPU itself (eco + wake-up sensors). */
    private fun updateWakeLock() {
        val e = engine ?: return
        val canSleep = e.ecoMode && imu?.deliversWhileCpuAsleep == true
        if (canSleep) {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } else {
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) // non-reference-counted: renews the timeout
        }
    }

    // ------------------------------------------------------------------ engine callbacks (sensing thread)
    override fun onSnapshot(snapshot: SensingSnapshot) {
        SensingRepository.publish(snapshot)
        val t = snapshot.sensorTimeNanos
        if (lastWakeRenewT == Long.MIN_VALUE || t - lastWakeRenewT >= WAKELOCK_RENEW_NS) {
            lastWakeRenewT = t
            updateWakeLock()
        }
        if (lastSaveT == Long.MIN_VALUE || t - lastSaveT >= SAVE_INTERVAL_NS) {
            lastSaveT = t
            engine?.let { saveDailyState(it.wellness.exportState()) }
        }
        maybeUpdateStatusNotification(snapshot)
    }

    override fun onEcoModeChanged(eco: Boolean) {
        imu?.setBatching(eco)
        updateWakeLock()
    }

    override fun onFallConfirmed(snapshot: SensingSnapshot) {
        engine?.let { saveDailyState(it.wellness.exportState()) }
        val open = PendingIntent.getActivity(this, 11,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ack = PendingIntent.getActivity(this, 12,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ACK_FALL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fall detected")
            .setContentText("High impact followed by immobility. Tap \"I'm OK\" if you are fine.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .addAction(0, "I'm OK", ack)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(FALL_NOTIFICATION_ID, n)
    }

    override fun onBreakDue(snapshot: SensingSnapshot) {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time for a movement break")
            .setContentText("Stationary for ${snapshot.ergonomics.continuousStillMinutes} minutes in the same posture.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager.notify(BREAK_NOTIFICATION_ID, n)
    }

    // ------------------------------------------------------------------ controller (any thread)
    override fun setUiVisible(visible: Boolean) {
        handler.post { engine?.setUiVisible(visible) }
    }

    override fun acknowledgeFall() {
        handler.post { engine?.acknowledgeFall() }
        notificationManager.cancel(FALL_NOTIFICATION_ID)
    }

    override fun onVehicleConfidence(confidence: Int) {
        handler.post { engine?.onVehicleConfidence(confidence) }
    }

    // ------------------------------------------------------------------ notifications
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, "Live sensing status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Current physical context and wellness score"
                setShowBadge(false)
            })
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Safety & health alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fall alerts and sedentary break reminders"
                enableVibration(true)
            })
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildStatusNotification(title: String, text: String): Notification {
        val stop = PendingIntent.getService(this, 1,
            Intent(this, SensingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle("PervasiveSense: $title")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent())
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** At most one update per 3 s and only when the text changes (Android rate-limits notify()). */
    private fun maybeUpdateStatusNotification(s: SensingSnapshot) {
        val title = when (s.status) {
            EngineStatus.WARMING_UP -> "Warming up"
            EngineStatus.MODEL_ERROR -> "Model error"
            EngineStatus.ACTIVE -> Display.context(s.context)
        }
        val text = "Confidence ${(s.confidence * 100).toInt()}% | Wellness ${s.wellness.overallScore}/100 | " +
            "${s.wellness.totalSteps} steps"
        val key = "$title|$text"
        if (key == lastNotificationText) return
        if (lastNotificationT != Long.MIN_VALUE && s.sensorTimeNanos - lastNotificationT < NOTIFICATION_MIN_INTERVAL_NS) return
        lastNotificationT = s.sensorTimeNanos
        lastNotificationText = key
        notificationManager.notify(NOTIFICATION_ID, buildStatusNotification(title, text))
    }

    // ------------------------------------------------------------------ daily persistence
    private fun saveDailyState(s: WellnessState) {
        val secs = JSONObject()
        s.secondsByContext.forEach { (k, v) -> secs.put(k, v) }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("day", s.dayKey)
            .putInt("steps", s.steps)
            .putString("seconds", secs.toString())
            .putFloat("slouch", s.slouchSeconds.toFloat())
            .putInt("falls", s.fallEvents)
            .putInt("potholes", s.potholes)
            .putInt("breakers", s.speedBreakers)
            .apply()
    }

    private fun loadDailyState(): WellnessState? {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = p.getString("day", null) ?: return null
        val secs = try {
            val o = JSONObject(p.getString("seconds", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getDouble(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        // A saved state from a previous day is discarded by WellnessScoreEngine.ensureDay().
        return WellnessState(day, p.getInt("steps", 0), secs, p.getFloat("slouch", 0f).toDouble(),
            p.getInt("falls", 0), p.getInt("potholes", 0), p.getInt("breakers", 0))
    }
}

/** Shared display strings for UI and notification. */
object Display {
    fun context(c: String?): String = when (c) {
        null -> "Detecting"
        "IN_VEHICLE" -> "IN VEHICLE"
        else -> c.replace('_', ' ')
    }
}
