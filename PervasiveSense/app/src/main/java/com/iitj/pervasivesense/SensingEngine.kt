package com.iitj.pervasivesense

enum class EngineStatus { WARMING_UP, ACTIVE, MODEL_ERROR }

/** Availability of vehicle (IN_VEHICLE) detection, reported by the host. */
enum class VehicleDetection { STARTING, ACTIVE, NO_PERMISSION, UNAVAILABLE }

data class SensingSnapshot(
    val status: EngineStatus,
    val warmupProgress: Float,
    /** Displayed context: IN_VEHICLE, a DeepSense class, or null while undecided. */
    val context: String?,
    val bodyActivity: String?,
    val confidence: Float,
    val probabilities: List<Pair<String, Float>>,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val ergonomics: ErgonomicReport,
    val potholes: Int,
    val speedBreakers: Int,
    val roadMonitoringActive: Boolean,
    val vehicleDetection: VehicleDetection,
    val fallState: FallState,
    val wellness: WellnessReport,
    val lastTransition: TransitionEvent?,
    val transitionCount: Int,
    val ecoMode: Boolean,
    val measuredRateHz: Float,
    val inferenceMillis: Float,
    val inferencesRun: Long,
    val stepSource: String,
    val hasGyro: Boolean,
    val sensorTimeNanos: Long
)

/**
 * Real-time sensing pipeline. Single-threaded: call every method from the same
 * (sensor) thread. Contains no Android types so it is unit-testable on the JVM.
 *
 *   raw accel/gyro events --ImuResampler--> uniform 50 Hz samples
 *     every sample : posture/fidget, fall FSM, road anomalies, step detection,
 *                    sliding window, UI publish (10 Hz while visible)
 *     every stride : DeepSense inference on the latest 2.56 s window,
 *                    smoothing + hysteresis, transitions, wellness, eco mode
 */
class SensingEngine(
    private val classifier: ActivityClassifier,
    private val wallClockOf: (sensorNanos: Long) -> Long,
    private val dayKeyOf: (wallMillis: Long) -> String,
    private val listener: Listener,
    private val nanoTime: () -> Long = System::nanoTime,
    val config: Config = Config(),
    hasGyroscope: Boolean = true
) : ImuSink {
    data class Config(
        val windowSize: Int = 128,
        val liveStrideSamples: Int = 25,      // 0.5 s between inferences
        val ecoStrideSamples: Int = 64,       // 1.28 s when stationary & unobserved
        val uiPublishEverySamples: Int = 5,   // 10 Hz dashboard refresh
        val ecoAfterStillSec: Float = 60f,
        val vehicleConfidenceMin: Int = 70,
        val vehicleExpirySec: Float = 120f,
        val recoveryLocomotionSec: Float = 5f
    )

    interface Listener {
        fun onSnapshot(snapshot: SensingSnapshot) {}
        fun onFallConfirmed(snapshot: SensingSnapshot) {}
        fun onBreakDue(snapshot: SensingSnapshot) {}
        fun onEcoModeChanged(eco: Boolean) {}
        fun onRoadAnomaly(event: RoadAnomalyEvent) {}
        fun onTransition(event: TransitionEvent) {}
    }

    private val labels = classifier.labels
    private val window = SlidingWindow(config.windowSize, 6)
    private val windowBuf = FloatArray(config.windowSize * 6)
    private val smoother = ActivitySmoother(labels.size)
    private val posture = ErgonomicPostureTracker()
    private val fall = FallDetector()
    private val road = RoadAnomalyDetector()
    private val stepDetector = StepDetector()
    private val transitions = ActivityTransitionDetector()
    val wellness = WellnessScoreEngine()
    private val resampler = ImuResampler(sink = ::onUniformSample, onGap = ::onGap, expectGyro = hasGyroscope)

    private val latest = FloatArray(6)
    private var lastSampleT = Long.MIN_VALUE
    private var samplesSinceInference = 0
    private var sampleCounter = 0L
    private var lastInferenceT = Long.MIN_VALUE
    private var inferenceMillis = 0f
    private var inferencesRun = 0L
    private var modelError = false

    private var context: String? = null
    private var bodyActivity: String? = null
    private var confidence = 0f
    private var ergo = posture.report(0L, isStill = false)

    private var uiVisible = false
    var ecoMode = false
        private set
    private var stillSinceT = Long.MIN_VALUE
    private var locomotionSinceT = Long.MIN_VALUE
    private var breakNotified = false

    private var vehicleConfidence = -1
    private var vehicleUpdateT = Long.MIN_VALUE
    var vehicleDetection = VehicleDetection.STARTING

    private var hardwareSteps = false
    private var lastHardwareStepTotal = -1L

    // ---------------------------------------------------------------- inputs
    override fun onAccelerometer(tNanos: Long, x: Float, y: Float, z: Float) = resampler.onAccelerometer(tNanos, x, y, z)
    override fun onGyroscope(tNanos: Long, x: Float, y: Float, z: Float) = resampler.onGyroscope(tNanos, x, y, z)

    /** Google Activity Recognition IN_VEHICLE confidence (0-100), stamped with sensor time. */
    fun onVehicleConfidence(confidence: Int) {
        vehicleConfidence = confidence
        vehicleUpdateT = lastSampleT
    }

    fun setHardwareStepCounter(enabled: Boolean) {
        hardwareSteps = enabled
        lastHardwareStepTotal = -1L
    }

    /** TYPE_STEP_COUNTER reports steps since boot; only deltas are counted. */
    fun onHardwareStepCounter(totalSinceBoot: Long) {
        if (!hardwareSteps) return
        val prev = lastHardwareStepTotal
        lastHardwareStepTotal = totalSinceBoot
        if (prev < 0 || totalSinceBoot < prev) return // first reading or counter reset (reboot)
        wellness.onSteps((totalSinceBoot - prev).toInt())
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) setEco(false)
        if (lastSampleT != Long.MIN_VALUE) publish(lastSampleT)
    }

    fun acknowledgeFall() {
        fall.acknowledge()
        if (lastSampleT != Long.MIN_VALUE) publish(lastSampleT)
    }

    val measuredRateHz get() = resampler.measuredAccelRateHz

    // ---------------------------------------------------------------- pipeline
    private fun isInVehicle(t: Long): Boolean =
        vehicleConfidence >= config.vehicleConfidenceMin && vehicleUpdateT != Long.MIN_VALUE &&
            (t - vehicleUpdateT) <= (config.vehicleExpirySec * 1e9f).toLong()

    private fun onGap(@Suppress("UNUSED_PARAMETER") t: Long) {
        // Data is missing: never classify a window spanning the gap, and don't
        // credit the gap's duration to any activity.
        window.clear()
        samplesSinceInference = 0
        lastInferenceT = Long.MIN_VALUE
    }

    private fun onUniformSample(t: Long, s: FloatArray) {
        s.copyInto(latest)
        lastSampleT = t
        val ax = s[0]; val ay = s[1]; val az = s[2]
        window.push(s)
        posture.processSample(t, ax, ay, az)

        val inVehicle = isInVehicle(t)
        road.processSample(t, ax, ay, az, inVehicle)?.let { e ->
            if (e.type == "POTHOLE") wellness.potholes++ else wellness.speedBreakers++
            listener.onRoadAnomaly(e)
        }
        val newSteps = stepDetector.processSample(t, ax, ay, az)
        if (!hardwareSteps && !inVehicle) wellness.onSteps(newSteps)

        val fallConfirmed = fall.processSample(t, ax, ay, az)
        if (fallConfirmed) wellness.onFallEvent()

        samplesSinceInference++
        val stride = if (ecoMode) config.ecoStrideSamples else config.liveStrideSamples
        var published = false
        if (window.isFull && samplesSinceInference >= stride) {
            runInference(t)
            published = true
        }
        sampleCounter++
        if (!published && (fallConfirmed || (uiVisible && sampleCounter % config.uiPublishEverySamples == 0L))) {
            publish(t)
        }
        if (fallConfirmed) listener.onFallConfirmed(buildSnapshot(t))
    }

    private fun runInference(t: Long) {
        samplesSinceInference = 0
        window.copyChronological(windowBuf)
        val start = nanoTime()
        val probs = classifier.classify(windowBuf)
        inferenceMillis = (nanoTime() - start) / 1e6f
        if (probs == null || probs.size != labels.size) {
            modelError = true
            publish(t)
            return
        }
        modelError = false
        inferencesRun++

        val stable = smoother.update(probs)
        bodyActivity = if (stable >= 0) labels[stable] else null
        val inVehicle = isInVehicle(t)
        context = if (inVehicle) "IN_VEHICLE" else bodyActivity
        confidence = if (inVehicle) vehicleConfidence / 100f else smoother.stableConfidence
        val ctx = context

        val wall = wallClockOf(t)
        wellness.ensureDay(dayKeyOf(wall))
        val isStill = ctx == "STILL"
        ergo = posture.report(t, isStill)
        if (ctx != null && lastInferenceT != Long.MIN_VALUE) {
            wellness.onContextElapsed(ctx, (t - lastInferenceT) / 1e9, ergo.postureStatus == "Forward Slouching")
        }
        lastInferenceT = t

        if (ctx != null) transitions.onStableContext(ctx, confidence, wall)?.let(listener::onTransition)

        // Sedentary break prompt: once per bout.
        if (ergo.needsBreakPrompt && !breakNotified) {
            breakNotified = true
            listener.onBreakDue(buildSnapshot(t))
        } else if (!ergo.needsBreakPrompt) {
            breakNotified = false
        }

        // Automatic fall recovery: sustained walking/running/stairs.
        val locomotion = ctx == "WALKING" || ctx == "RUNNING" || ctx == "STAIRS_UP" || ctx == "STAIRS_DOWN"
        locomotionSinceT = if (!locomotion) Long.MIN_VALUE else if (locomotionSinceT == Long.MIN_VALUE) t else locomotionSinceT
        if (fall.currentState == FallState.FALL_CONFIRMED && locomotionSinceT != Long.MIN_VALUE &&
            (t - locomotionSinceT) >= (config.recoveryLocomotionSec * 1e9f).toLong()) {
            fall.acknowledge()
        }

        // Eco mode: long stationary periods with nobody watching the dashboard.
        stillSinceT = if (!isStill) Long.MIN_VALUE else if (stillSinceT == Long.MIN_VALUE) t else stillSinceT
        val shouldEco = !uiVisible && stillSinceT != Long.MIN_VALUE &&
            (t - stillSinceT) >= (config.ecoAfterStillSec * 1e9f).toLong()
        setEco(shouldEco)

        publish(t)
    }

    private fun setEco(eco: Boolean) {
        if (eco == ecoMode) return
        ecoMode = eco
        listener.onEcoModeChanged(eco)
    }

    private fun publish(t: Long) = listener.onSnapshot(buildSnapshot(t))

    fun buildSnapshot(t: Long = lastSampleT): SensingSnapshot {
        val status = when {
            modelError -> EngineStatus.MODEL_ERROR
            context == null -> EngineStatus.WARMING_UP
            else -> EngineStatus.ACTIVE
        }
        val probs = labels.indices.map { labels[it] to smoother.smoothed[it] }.sortedByDescending { it.second }
        return SensingSnapshot(
            status = status,
            warmupProgress = window.filled.toFloat() / config.windowSize,
            context = context,
            bodyActivity = bodyActivity,
            confidence = confidence,
            probabilities = probs,
            ax = latest[0], ay = latest[1], az = latest[2],
            gx = latest[3], gy = latest[4], gz = latest[5],
            ergonomics = ergo.copy(
                postureTiltAngle = posture.tiltDegrees,
                fidgetIndex = posture.fidgetIndex,
                isFidgeting = posture.isFidgeting
            ),
            potholes = wellness.potholes,
            speedBreakers = wellness.speedBreakers,
            roadMonitoringActive = isInVehicle(t),
            vehicleDetection = vehicleDetection,
            fallState = fall.currentState,
            wellness = wellness.computeWellnessReport(),
            lastTransition = transitions.lastTransition,
            transitionCount = transitions.totalTransitions,
            ecoMode = ecoMode,
            measuredRateHz = resampler.measuredAccelRateHz,
            inferenceMillis = inferenceMillis,
            inferencesRun = inferencesRun,
            stepSource = if (hardwareSteps) "hardware step counter" else "accelerometer peak detector",
            hasGyro = resampler.hasGyroData,
            sensorTimeNanos = t
        )
    }
}
