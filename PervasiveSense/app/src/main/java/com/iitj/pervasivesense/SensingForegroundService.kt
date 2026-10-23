package com.iitj.pervasivesense

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.IntentCompat
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
        const val FIT_NOTIFICATION_ID = 1004
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

        /** Needed to see car Bluetooth connections (runtime permission on Android 12+). */
        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
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
    private var fitDirty = false
    private var carReceiverRegistered = false
    private var proximityRegistered = false
    private var studyCondition = StudyCondition.OFF
    private var studyParticipant = ""

    /** Covered proximity sensor = phone in a pocket: only pocket walks are compared with the pocket baseline. */
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            engine?.onProximity(event.values[0] < event.sensor.maximumRange)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** A car audio / hands-free unit connected: the user is probably about to drive. */
    private val carBluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // registered only with the permission
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val cls = try { device?.bluetoothClass?.deviceClass } catch (e: SecurityException) { null }
            if (cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) {
                engine?.onCarBluetoothConnected()
            }
        }
    }

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
        FitToDriveStore.load(this)?.let { e.fitToDrive.restore(it) }
        e.setFitToDriveEnabled(FitToDriveStore.isEnabled(this))
        engine = e
        loadStudySettings()

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
        updateFitSensors()
        updateWakeLock()
        SensingRepository.attach(this)
        SensingRepository.setStatus(ServiceStatus.RUNNING)
    }

    private fun stopSensing() {
        imu?.stop()
        sensorManager.unregisterListener(stepListener)
        if (carReceiverRegistered) { unregisterReceiver(carBluetoothReceiver); carReceiverRegistered = false }
        if (proximityRegistered) { sensorManager.unregisterListener(proximityListener); proximityRegistered = false }
        vehiclePendingIntent?.let { pi ->
            try {
                ActivityRecognition.getClient(this).removeActivityUpdates(pi)
            } catch (e: SecurityException) {
                Log.w(TAG, "removeActivityUpdates", e)
            }
            pi.cancel()
        }
        engine?.let {
            saveDailyState(it.wellness.exportState())
            FitToDriveStore.save(this, it.fitToDrive.exportState())
        }
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
            engine?.let {
                saveDailyState(it.wellness.exportState())
                if (fitDirty) { fitDirty = false; FitToDriveStore.save(this, it.fitToDrive.exportState()) }
            }
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

    override fun onFitToDrive(event: FitToDriveEvent, snapshot: SensingSnapshot) {
        when (event) {
            is FitToDriveEvent.StateChanged -> fitDirty = true
            is FitToDriveEvent.SegmentMeasured -> studyLogSegment(event)
            is FitToDriveEvent.WalkChecked -> {
                studyLogCheck(event.check)
                val c = event.check
                when {
                    // The trip was detected while moving: never ask a driver to use the phone.
                    c.level == WalkLevel.LARGE_GAP && c.trigger == CheckTrigger.TRIP_START ->
                        notifyFit(getString(R.string.fit_notify_moving_title), getString(R.string.fit_notify_moving_text), balanceAction = false)
                    c.level == WalkLevel.LARGE_GAP ->
                        notifyFit(getString(R.string.fit_notification_title),
                            getString(R.string.fit_notification_text, (c.percentile * 100).toInt()), balanceAction = true)
                    // Car Bluetooth (about to drive) and a slightly unusual walk: a quiet offer.
                    event.advice == FitAdvice.CONSIDER_BALANCE_CHECK && c.trigger == CheckTrigger.CAR_BLUETOOTH ->
                        notifyFit(getString(R.string.fit_notify_consider_title), getString(R.string.fit_notify_consider_text),
                            balanceAction = true, urgent = false)
                }
            }
            is FitToDriveEvent.DrivingAfterWarning ->
                notifyFit(getString(R.string.fit_notify_reminder_title), getString(R.string.fit_notify_reminder_text), balanceAction = false)
            is FitToDriveEvent.BalanceDone -> {
                studyLogBalance(event.result)
                if (event.result.outcome == BalanceOutcome.REFERENCE_SAVED) {
                    engine?.let { FitToDriveStore.save(this, it.fitToDrive.exportState()) }
                    fitDirty = false
                }
            }
        }
    }

    private fun notifyFit(title: String, text: String, balanceAction: Boolean, urgent: Boolean = true) {
        fun open(code: Int, extra: String?) = PendingIntent.getActivity(this, code,
            Intent(this, FitToDriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (extra != null) putExtra(extra, true) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open(21, null))
            .setAutoCancel(true)
        if (balanceAction) b.addAction(0, getString(R.string.fit_action_balance), open(22, FitToDriveActivity.EXTRA_START_BALANCE))
        b.addAction(0, getString(R.string.fit_action_not_driving), open(23, FitToDriveActivity.EXTRA_DISMISS))
        notificationManager.notify(FIT_NOTIFICATION_ID, b.build())
    }

    // ------------------------------------------------------------------ study log (labelled sessions only)
    private fun loadStudySettings() {
        studyCondition = FitToDriveStore.studyCondition(this)
        studyParticipant = FitToDriveStore.studyParticipant(this)
        engine?.setFitLearningPaused(studyCondition != StudyCondition.OFF)
    }

    private fun studyTime(wall: Long) = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()

    private fun studyRow(wall: Long, event: String, trigger: CheckTrigger? = null, level: String? = null,
                         score: Float = Float.NaN, percentile: Float = Float.NaN, unusualAt: Float = Float.NaN,
                         largeAt: Float = Float.NaN, features: FloatArray? = null, balance: BalanceResult? = null) {
        if (studyCondition == StudyCondition.OFF) return
        val f = features ?: FloatArray(GaitFeature.COUNT) { Float.NaN }
        StudyLog.append(this, listOf(studyTime(wall), studyParticipant, studyCondition.name, event, trigger?.name, level,
            score, percentile, unusualAt, largeAt, f[0], f[1], f[2], f[3], f[4], f[5],
            balance?.outcome?.name, balance?.swayAccel, balance?.swayGyro, balance?.ratio))
    }

    private fun studyLogSegment(e: FitToDriveEvent.SegmentMeasured) {
        val wall = if (wallOffsetMs == Long.MIN_VALUE) System.currentTimeMillis() else wallOffsetMs + e.segment.tEndNanos / 1_000_000
        studyRow(wall, "segment", score = e.distance, features = e.segment.values)
    }

    private fun studyLogCheck(c: WalkCheck) = studyRow(c.wallMillis, "walk_check", c.trigger,
        c.level?.name ?: c.notScored?.name, c.score, c.percentile, c.unusualAt, c.largeAt)

    private fun studyLogBalance(r: BalanceResult) =
        studyRow(r.wallMillis, if (r.purpose == BalancePurpose.REFERENCE) "balance_reference" else "balance_check", balance = r)

    /**
     * While the check is enabled: the proximity sensor (pocket detection) and,
     * if the user opted in, the car-Bluetooth receiver.
     */
    private fun updateFitSensors() {
        val enabled = FitToDriveStore.isEnabled(this)
        val wantBt = enabled && FitToDriveStore.useCarBluetooth(this) && hasBluetoothPermission(this)
        if (wantBt != carReceiverRegistered) {
            if (wantBt) {
                ContextCompat.registerReceiver(this, carBluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
                    null, handler, ContextCompat.RECEIVER_EXPORTED) // a protected system broadcast
            } else {
                unregisterReceiver(carBluetoothReceiver)
            }
            carReceiverRegistered = wantBt
        }
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val wantProximity = enabled && proximity != null
        if (wantProximity != proximityRegistered) {
            proximityRegistered = wantProximity &&
                sensorManager.registerListener(proximityListener, proximity, SensorManager.SENSOR_DELAY_NORMAL, handler)
            if (!wantProximity) sensorManager.unregisterListener(proximityListener)
        }
        engine?.setProximityAvailable(proximityRegistered)
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

    override fun setFitToDriveEnabled(enabled: Boolean) {
        handler.post {
            engine?.setFitToDriveEnabled(enabled)
            updateFitSensors()
        }
        if (!enabled) notificationManager.cancel(FIT_NOTIFICATION_ID)
    }

    /** Settings changed (car Bluetooth opt-in, study session label). */
    override fun refreshFitSettings() {
        handler.post {
            updateFitSensors()
            loadStudySettings()
        }
    }

    override fun requestFitCheck() { handler.post { engine?.requestFitCheck() } }

    override fun startBalanceTest(purpose: BalancePurpose) {
        handler.post {
            val refusals = listOf(getString(R.string.fit_reference_refused), getString(R.string.fit_balance_in_vehicle))
            when (engine?.startBalanceTest(purpose)) {
                BalanceStart.REFUSED_WARNING -> SensingRepository.setMessage(refusals[0])
                BalanceStart.REFUSED_IN_VEHICLE -> SensingRepository.setMessage(refusals[1])
                BalanceStart.STARTED -> if (SensingRepository.message.value in refusals) SensingRepository.setMessage(null)
                null -> Unit
            }
        }
        notificationManager.cancel(FIT_NOTIFICATION_ID)
    }

    override fun cancelBalanceTest() { handler.post { engine?.cancelBalanceTest() } }

    override fun dismissFitAdvice() {
        handler.post { engine?.dismissFitAdvice() }
        notificationManager.cancel(FIT_NOTIFICATION_ID)
    }

    override fun resetFitToDrive() { handler.post { engine?.resetFitToDrive() } }

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
                description = "Fall alerts, break reminders and fit-to-drive checks"
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
        // Eco mode only runs while the dashboard is closed, so the notification is where it is visible.
        val mode = if (s.ecoMode) ", eco mode" else ""
        val text = "${(s.confidence * 100).toInt()}% confident, wellness ${s.wellness.overallScore}/100, " +
            "${s.wellness.totalSteps} steps$mode"
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

/** Display names shared by the notification ("STAIRS_UP" -> "Stairs up", as on the dashboard). */
object Display {
    fun context(c: String?): String = when (c) {
        null -> "Detecting"
        else -> c.lowercase(java.util.Locale.ROOT).replace('_', ' ')
            .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
    }
}
