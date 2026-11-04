package com.iitj.pervasivesense

import kotlin.math.sqrt

enum class BaselineStatus { LEARNING, PROVISIONAL, ESTABLISHED }
enum class WalkLevel { NORMAL, UNUSUAL, LARGE_GAP }
enum class CheckTrigger { TRIP_START, CAR_BLUETOOTH, MANUAL }
enum class NotScoredReason { NO_RECENT_WALK, STILL_LEARNING }

/**
 * What the user is advised to do. CONSIDER is a gentle offer after a slightly
 * unusual walk; TAKE_BALANCE_CHECK and DONT_DRIVE are warnings.
 */
enum class FitAdvice { NONE, CONSIDER_BALANCE_CHECK, TAKE_BALANCE_CHECK, DONT_DRIVE, BALANCE_OK }
enum class BalancePurpose { CHECK, REFERENCE }
/** TOO_STILL: far steadier than the user's own recordings (phone put down, or sitting): not a valid test. */
enum class BalanceOutcome { PASSED, FAILED, MOVED, TOO_STILL, NO_REFERENCE, REFERENCE_SAVED, REFERENCE_REJECTED }
enum class BalanceStart { STARTED, REFUSED_WARNING, REFUSED_IN_VEHICLE }
/** Guided set-up walk ("Start walk recording"): spoken feedback while the phone is in the pocket. */
enum class WalkSessionStage { RECORDING, HALFWAY, DONE, NOTHING_YET, TIMED_OUT }

/** "Test it": a practice walk the user does on purpose. It is scored like a real check but never learned. */
enum class PracticeWalk { NORMAL, WOBBLY }

/** Result of scoring the walk before a (possible) drive. */
class WalkCheck(
    val wallMillis: Long,
    val trigger: CheckTrigger,
    /** null when the walk could not be scored, see [notScored]. */
    val level: WalkLevel?,
    val notScored: NotScoredReason?,
    /** Distance from normal: median over the walk's segments (see [GaitBaseline.walkDistance]). */
    val score: Float,
    val unusualAt: Float,
    val largeAt: Float,
    /** Share of the user's own normal walks that were less unusual than this one. */
    val percentile: Float,
    val segments: Int,
    val provisional: Boolean,
    /** Features furthest from normal, largest first (z in the impairment direction). */
    val topDeviations: List<Pair<GaitFeature, Float>>,
    /** Set for a "Test it" practice walk. */
    val practice: PracticeWalk? = null
)

class BalanceResult(
    val wallMillis: Long,
    val purpose: BalancePurpose,
    val outcome: BalanceOutcome,
    val swayAccel: Float,
    val swayGyro: Float,
    /** Sway relative to the user's own reference (NaN if none). */
    val ratio: Float
)

/** A balance test the user recorded at a normal (sober, rested) moment. */
class BalanceReference(val wallMillis: Long, val swayAccel: Float, val swayGyro: Float)

data class FitToDriveReport(
    val enabled: Boolean,
    val baselineStatus: BaselineStatus,
    val baselineSegments: Int,
    val baselineDays: Int,
    val baselineWalkMinutes: Float,
    val segmentsForProvisional: Int,
    val segmentsForEstablished: Int,
    val daysForEstablished: Int,
    val waitingForWalk: Boolean,
    /** "Check my walk" countdown (0 when none is running). */
    val checkSecondsLeft: Float,
    /** Walking, but the phone is not in a pocket: this walk is not used. */
    val walkingOutsidePocket: Boolean,
    /** Walking with the phone in a pocket right now: this walk is being recorded. */
    val recordingWalk: Boolean,
    /** The guided set-up walk ("Start walk recording") is running. */
    val walkSession: Boolean,
    /** Minutes of pocket walking needed before checks can run. */
    val walkMinutesNeeded: Float,
    /** A study session is labelled: nothing is learned. */
    val learningPaused: Boolean,
    /** A "Test it" practice walk is waiting for the user to walk. */
    val practice: PracticeWalk?,
    /** A real warning (large gap or failed balance) in the last hour that a passed balance check has not cleared. */
    val recentlyWarned: Boolean,
    /** In a vehicle after such a warning, not confirmed as a passenger: the driving is watched. */
    val watchingDriving: Boolean,
    val lastCheck: WalkCheck?,
    val balanceRunning: Boolean,
    val balancePurpose: BalancePurpose?,
    val balanceProgress: Float,
    val balanceSecondsLeft: Int,
    val balanceReferences: Int,
    /** Normal-balance recordings needed (and kept: newer ones replace the oldest). */
    val balanceReferencesNeeded: Int,
    val lastBalance: BalanceResult?,
    val advice: FitAdvice
)

sealed interface FitToDriveEvent {
    class WalkChecked(val check: WalkCheck, val advice: FitAdvice) : FitToDriveEvent
    class BalanceDone(val result: BalanceResult, val advice: FitAdvice) : FitToDriveEvent
    /**
     * A trip is starting although the last hour brought a warning. [dismissed]: the user
     * had tapped "I'm not driving" (or the advice expired), so this may mean driving anyway.
     */
    class DrivingAfterWarning(val trigger: CheckTrigger, val advice: FitAdvice, val dismissed: Boolean = false,
                              /** The car's Bluetooth connected and a trip started too: strong evidence of driving (contact alert). */
                              val driving: Boolean = false) : FitToDriveEvent
    /** Progress of the guided set-up walk, to be spoken (the phone is in the pocket). */
    class WalkSession(val stage: WalkSessionStage) : FitToDriveEvent
    /** In a vehicle after a warning, the driving looks erratic (weaving, jerky steering, harsh braking). */
    class ErraticDriving(val events: List<DrivingEvent>) : FitToDriveEvent
    /** Every analysed walking segment, with its distance from normal (NaN while learning): study log. */
    class SegmentMeasured(val segment: GaitSegment, val distance: Float) : FitToDriveEvent
    /** Learned data changed (persist it). */
    object StateChanged : FitToDriveEvent
}

/** Everything the check learns, for persistence across restarts. */
class FitToDriveState(
    val baseline: List<BaselineEntry>,
    val balanceReferences: List<BalanceReference>,
    /** Wall time of the last real warning (the 1-hour watch survives a restart); null if none. */
    val warnedWallMillis: Long? = null,
    /** A real warning still showing (TAKE_BALANCE_CHECK / DONT_DRIVE) and when it was given. */
    val advice: FitAdvice = FitAdvice.NONE,
    val adviceWallMillis: Long? = null
)

/**
 * Fit-to-drive check (opt-in).
 *
 *  1. Learn normal: while enabled, every segment of everyday walking with the
 *     phone in a pocket is added to the user's own [GaitBaseline] (up to a
 *     daily quota so one long walk cannot dominate). While the baseline is
 *     still being learned (nothing can be checked yet) segments are learned at
 *     once. After that they are quarantined first: learned only once they leave
 *     the pre-drive window unflagged, so the walk being scored is never part of
 *     the "normal" it is compared with. A scored walk is learned only if it was normal, so
 *     slightly impaired evenings never drift into "normal"; an established baseline
 *     learns an unchecked walk only if it is not a large gap. Nothing is learned while a warning or
 *     offer is active, during a labelled study session or a "Test it" practice walk.
 *  2. Watch the walk: the segments of the last [Config.lookbackSec] are kept.
 *  3. Measure the gap: when a trip starts (vehicle detected), the car's
 *     Bluetooth connects, or the user asks, the walk is scored against the
 *     baseline. Each walk is scored once.
 *  4. Respond: a large gap asks for the 30 s [BalanceTest]; a failed (or
 *     skipped/invalid) balance test after a large gap advises not to drive.
 *     A slightly unusual walk before driving only offers the balance test.
 *     Starting a trip while warned produces a reminder. The balance test is
 *     never started in a moving vehicle.
 *
 * It flags walking that is unusual for this person, not alcohol itself, and
 * only ever advises. Time comes from sensor timestamps; no Android types.
 */
class FitToDriveMonitor(
    private val wallClockOf: (sensorNanos: Long) -> Long,
    private val dayKeyOf: (wallMillis: Long) -> String,
    private val onEvent: (FitToDriveEvent) -> Unit,
    val config: Config = Config()
) {
    data class Config(
        val segmentsForProvisional: Int = 15,   // ≈ 1 min of walking (4 s per segment)
        val segmentsForEstablished: Int = 150,  // ≈ 10 min
        val daysForEstablished: Int = 7,
        val lookbackSec: Float = 300f,          // vehicle detection lags the start of a trip
        val minWalkSegments: Int = 3,           // ≈ 16 s of walking
        val maxWalkSegments: Int = 15,          // the last ≈ minute before the trip
        val manualWaitSec: Float = 180f,
        /** "Check my walk" scores exactly the walking of this long after the tap. */
        val checkSec: Float = 30f,
        val practiceWalkSegments: Int = 5,      // ≈ 24 s: a steadier answer for a test walk
        val learnWindowSegments: Int = 15,      // an unchecked walk is judged over ≈ 1 min before it is learned
        /** Until established, "normal" is narrow (often one day): only a clearly unsteady unchecked walk is dropped. */
        val provisionalRejectAt: Float = 5f,
        val adviceExpirySec: Float = 3600f,
        val balanceFailRatio: Float = 1.75f,
        val balanceReferences: Int = 3,
        val maxPerDay: Int = 150,
        /** After a real warning, trips and driving are watched this long, even after "I'm not driving". */
        val watchAfterWarningSec: Float = 3600f,
        val reminderCooldownSec: Float = 600f,
        val erraticCooldownSec: Float = 600f,
        /** The guided set-up walk gives up after this long. */
        val walkSessionSec: Float = 420f,
        /** ...and says "nothing recorded yet" if no walking was recorded this long after the start. */
        val walkSessionNudgeSec: Float = 60f,
        /** Car Bluetooth counts as driving only if a trip starts within this time (earbuds never do). */
        val carBluetoothTripSec: Float = 600f,
        /** A balance check this much steadier than the user's own recordings is not valid. */
        val balanceTooStillRatio: Float = 0.35f
    )

    private val extractor = GaitSegmentExtractor()
    private val baseline = GaitBaseline(maxPerDay = config.maxPerDay)
    /**
     * A segment of the last [Config.lookbackSec]; [learned] if it already went into the baseline (setup);
     * not [learnable] if it was recorded while learning was blocked (warning, study session, practice walk).
     */
    private class Recent(val seg: GaitSegment, val wall: Long, val learned: Boolean, val learnable: Boolean) {
        /** Unchecked walk judged on leaving the window: normal or not (shared by the whole ≈ 1 min judged). */
        var verdict: Boolean? = null
    }
    private val recent = ArrayDeque<Recent>()
    private val balanceTest = BalanceTest()
    private var balancePurpose: BalancePurpose? = null
    /** The running balance test is "Test it" C: its result never arms the driving watch. */
    private var balancePractice = false
    val drivingWatch = DrivingWatch()
    private val references = ArrayList<BalanceReference>()

    var enabled = false
        private set
    /** Study session labelled (e.g. goggles on): measure and log, but never learn. */
    var learningPaused = false
    private var lastCheck: WalkCheck? = null
    private var lastBalance: BalanceResult? = null
    private var advice = FitAdvice.NONE
    private var adviceT = Long.MIN_VALUE
    private var waitUntil = Long.MIN_VALUE
    private var practice: PracticeWalk? = null
    /** "Check my walk" is timing its 30 s walk: scored at the end, never learned as normal. */
    private var onRequest = false
    /** Time of the last real warning (not from a practice test); cleared by a passed balance check. */
    private var warnedT = Long.MIN_VALUE
    private var lastReminderT = Long.MIN_VALUE
    private var lastErraticT = Long.MIN_VALUE
    private var passengerConfirmed = false
    private var inVehicleNow = false
    private var walkSessionStart = Long.MIN_VALUE
    /** The current advice came from a "Test it" practice test: it never reminds or alerts anyone. */
    private var practiceAdvice = false
    /** Last car-Bluetooth connection; driving only once a trip also starts. */
    private var carBluetoothT = Long.MIN_VALUE
    /** Restored warning (wall times), applied on the first sample once sensor time is known. */
    private var restoredWarning: FitToDriveState? = null
    private val walkSessionStages = HashSet<WalkSessionStage>()

    /** The guided set-up walk is running (the engine then also accepts steps as walking). */
    val walkSessionActive get() = walkSessionStart != Long.MIN_VALUE
    private var lastT = Long.MIN_VALUE
    private var outsidePocketSince = Long.MIN_VALUE
    private var walkingNow = false
    private var inPocketNow = true

    private val segmentSeconds get() = extractor.hopSamples / 50f
    private val warningActive get() = advice == FitAdvice.TAKE_BALANCE_CHECK || advice == FitAdvice.DONT_DRIVE
    /** Possibly impaired right now: do not learn "normal" from it. */
    private val learningBlocked get() =
        learningPaused || practice != null || onRequest || warningActive || advice == FitAdvice.CONSIDER_BALANCE_CHECK ||
            recentlyWarned(lastT)

    private fun recentlyWarned(t: Long) =
        enabled && warnedT != Long.MIN_VALUE && t - warnedT <= secs(config.watchAfterWarningSec)

    /** Warned within the hour and not confirmed as a passenger on this trip. */
    private fun drivingArmed(t: Long) = recentlyWarned(t) && !passengerConfirmed

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        extractor.reset(); recent.clear(); waitUntil = Long.MIN_VALUE; practice = null
        if (!on) { advice = FitAdvice.NONE; warnedT = Long.MIN_VALUE; cancelBalanceTest(); stopWalkRecording() }
    }

    fun status(): BaselineStatus = when {
        baseline.size < config.segmentsForProvisional -> BaselineStatus.LEARNING
        baseline.size >= config.segmentsForEstablished && baseline.days >= config.daysForEstablished -> BaselineStatus.ESTABLISHED
        else -> BaselineStatus.PROVISIONAL
    }

    /** Sensor data is missing: never build a segment across the hole. */
    fun onGap() { extractor.reset(); drivingWatch.reset() }

    /**
     * Every uniform 50 Hz sample. [walking]: the displayed activity is WALKING.
     * [inPocket]: the proximity sensor is covered (true if the phone has none);
     * only pocket walks are learned and scored, like the baseline.
     * [inVehicle]: a trip is in progress; after a warning the driving is watched.
     */
    fun processSample(t: Long, s: FloatArray, walking: Boolean, hasGyro: Boolean, inPocket: Boolean = true,
                      inVehicle: Boolean = false) {
        lastT = t
        restoredWarning?.let { applyRestoredWarning(t, it) }
        balanceTest.processSample(t, s, hasGyro)?.let { finishBalance(t, it) }
        if (advice != FitAdvice.NONE && adviceT != Long.MIN_VALUE && t - adviceT > secs(config.adviceExpirySec)) {
            advice = FitAdvice.NONE
        }
        if (!enabled) return
        watchDriving(t, s, hasGyro, inVehicle)
        if (walkSessionActive) walkSessionClock(t)
        walkingNow = walking
        inPocketNow = inPocket
        outsidePocketSince = if (walking && !inPocket) (if (outsidePocketSince == Long.MIN_VALUE) t else outsidePocketSince) else Long.MIN_VALUE
        if (onRequest && t >= waitUntil) {
            waitUntil = Long.MIN_VALUE
            if (!evaluate(t, CheckTrigger.MANUAL)) publishCheck(notScoredCheck(t, CheckTrigger.MANUAL, NotScoredReason.NO_RECENT_WALK))
            onRequest = false
        }
        if (waitUntil != Long.MIN_VALUE && t > waitUntil) {
            waitUntil = Long.MIN_VALUE
            val p = practice
            practice = null
            publishCheck(notScoredCheck(t, CheckTrigger.MANUAL, NotScoredReason.NO_RECENT_WALK, p))
        }
        if (recent.isNotEmpty() && t - recent.first().seg.tEndNanos > secs(config.lookbackSec)) pruneRecent(t)
        val seg = extractor.processSample(t, s, walking && inPocket, hasGyro) ?: return
        val wall = wallClockOf(t)
        // Setup: nothing can be checked yet, so there is no walk to protect; learn at once.
        val learnedNow = status() == BaselineStatus.LEARNING && learn(seg, wall)
        if (learnedNow) {
            onEvent(FitToDriveEvent.StateChanged)
            if (walkSessionActive) walkSessionProgress()
        }
        recent.addLast(Recent(seg, wall, learnedNow, learnable = !learningBlocked))
        onEvent(FitToDriveEvent.SegmentMeasured(seg, baseline.deviation(seg.values)?.distance ?: Float.NaN))
        val needed = if (practice != null) config.practiceWalkSegments else config.minWalkSegments
        if (!onRequest && waitUntil != Long.MIN_VALUE && recent.size >= needed) {
            waitUntil = Long.MIN_VALUE
            evaluate(t, CheckTrigger.MANUAL)
        }
    }

    /** The vehicle context just started (Activity Recognition IN_VEHICLE). */
    fun onTripStart(t: Long) {
        passengerConfirmed = false
        drivingWatch.clear()
        if (!enabled) return
        beforeDrive(t, CheckTrigger.TRIP_START)
        carDrivingAfterWarning(t)
    }

    /** "I'm a passenger" (e.g. cancelling a contact alert): stop watching the driving on this trip. */
    fun confirmPassenger() { passengerConfirmed = true }

    /** A car audio / hands-free Bluetooth device connected: usually before the car moves. */
    fun onCarBluetooth(t: Long) {
        if (!enabled) return
        carBluetoothT = t
        beforeDrive(t, CheckTrigger.CAR_BLUETOOTH)
        if (inVehicleNow) carDrivingAfterWarning(t) // the trip had already been detected
    }

    /**
     * Car Bluetooth plus a trip within [Config.carBluetoothTripSec], after a real warning: driving.
     * Bluetooth alone is not enough (some earbuds report themselves as hands-free kits).
     */
    private fun carDrivingAfterWarning(t: Long) {
        if (carBluetoothT == Long.MIN_VALUE || t - carBluetoothT > secs(config.carBluetoothTripSec)) return
        if (!warningActive && !recentlyWarned(t)) return
        carBluetoothT = Long.MIN_VALUE
        onEvent(FitToDriveEvent.DrivingAfterWarning(CheckTrigger.CAR_BLUETOOTH, advice, dismissed = !warningActive, driving = true))
    }

    /**
     * "Check my walk": scores only the walking of the next [Config.checkSec] (earlier walking
     * is never mixed in), at the end of that time. A walk checked on request is never learned
     * as normal (the user may walk unsteadily on purpose); normal is learned from everyday walking.
     */
    fun requestCheck(t: Long) {
        if (!enabled || balanceTest.running) return
        pruneRecent(t)        // earlier walking is judged and learned (or not) as usual...
        recent.clear()        // ...but never scored as this check
        extractor.reset()
        practice = null
        onRequest = true
        waitUntil = t + secs(config.checkSec)
    }

    /**
     * Set-up step 1 on request: walk with the phone in the pocket until the walk is
     * learned. Progress is reported as [FitToDriveEvent.WalkSession] to be spoken.
     * False if the walk is already learned or a balance test is running.
     */
    fun startWalkRecording(t: Long): Boolean {
        if (!enabled || status() != BaselineStatus.LEARNING || balanceTest.running) return false
        walkSessionStart = t
        walkSessionStages.clear()
        extractor.reset()
        return true
    }

    fun stopWalkRecording() { walkSessionStart = Long.MIN_VALUE }

    private fun walkSessionStage(stage: WalkSessionStage) {
        if (walkSessionStages.add(stage)) onEvent(FitToDriveEvent.WalkSession(stage))
    }

    private fun walkSessionProgress() {
        walkSessionStage(WalkSessionStage.RECORDING)
        when {
            status() != BaselineStatus.LEARNING -> { walkSessionStage(WalkSessionStage.DONE); stopWalkRecording() }
            baseline.size * 2 >= config.segmentsForProvisional -> walkSessionStage(WalkSessionStage.HALFWAY)
        }
    }

    private fun walkSessionClock(t: Long) {
        val elapsed = t - walkSessionStart
        if (WalkSessionStage.RECORDING !in walkSessionStages && elapsed > secs(config.walkSessionNudgeSec)) {
            walkSessionStage(WalkSessionStage.NOTHING_YET)
        }
        if (elapsed > secs(config.walkSessionSec)) {
            walkSessionStage(WalkSessionStage.TIMED_OUT)
            stopWalkRecording()
        }
    }

    /**
     * "Test it": the next ≈ 24 s of pocket walking is scored and the result shown and
     * spoken, but never learned. [PracticeWalk.WOBBLY] is walked unsteadily on purpose
     * to see the warning and the balance check. False if nothing can be checked yet.
     */
    fun startPractice(t: Long, kind: PracticeWalk): Boolean {
        if (!enabled || status() == BaselineStatus.LEARNING || balanceTest.running) return false
        pruneRecent(t)        // earlier walking is judged and learned (or not) as usual...
        recent.clear()        // ...but never mixed into the test walk
        extractor.reset()
        practice = kind
        waitUntil = t + secs(config.manualWaitSec)
        return true
    }

    fun cancelPractice() {
        if (practice == null) return
        practice = null; waitUntil = Long.MIN_VALUE; recent.clear(); extractor.reset()
    }

    /**
     * Never in a moving vehicle (the driver must not use the phone, and car
     * motion is not body sway). A reference ("my normal balance") is refused
     * while the person may be impaired.
     */
    fun startBalanceTest(t: Long, purpose: BalancePurpose, inVehicle: Boolean = false, practice: Boolean = false): BalanceStart {
        if (inVehicle) return BalanceStart.REFUSED_IN_VEHICLE
        cancelPractice()
        stopWalkRecording()
        if (purpose == BalancePurpose.REFERENCE && (warningActive || advice == FitAdvice.CONSIDER_BALANCE_CHECK)) {
            return BalanceStart.REFUSED_WARNING
        }
        balancePurpose = purpose
        balancePractice = practice && purpose == BalancePurpose.CHECK
        balanceTest.start(t)
        return BalanceStart.STARTED
    }

    fun cancelBalanceTest() { balanceTest.cancel(); balancePurpose = null }

    /** "I'm not driving" or the user read the advice. */
    fun dismissAdvice() { advice = FitAdvice.NONE; practiceAdvice = false; onEvent(FitToDriveEvent.StateChanged) }

    fun resetLearning() {
        baseline.clear(); references.clear(); recent.clear(); cancelPractice(); stopWalkRecording()
        lastCheck = null; lastBalance = null; advice = FitAdvice.NONE; warnedT = Long.MIN_VALUE
        practiceAdvice = false; restoredWarning = null
        onEvent(FitToDriveEvent.StateChanged)
    }

    fun exportState(): FitToDriveState {
        restoredWarning?.let { return FitToDriveState(baseline.snapshot(), references.toList(), it.warnedWallMillis, it.advice, it.adviceWallMillis) }
        val realAdvice = if (warningActive && !practiceAdvice && adviceT != Long.MIN_VALUE) advice else FitAdvice.NONE
        return FitToDriveState(baseline.snapshot(), references.toList(),
            warnedWallMillis = if (warnedT != Long.MIN_VALUE) wallClockOf(warnedT) else null,
            advice = realAdvice,
            adviceWallMillis = if (realAdvice != FitAdvice.NONE) wallClockOf(adviceT) else null)
    }

    fun restore(state: FitToDriveState) {
        baseline.restore(state.baseline)
        references.clear(); references.addAll(state.balanceReferences.takeLast(config.balanceReferences))
        restoredWarning = if (state.warnedWallMillis != null || state.advice != FitAdvice.NONE) state else null
    }

    /** A restart (app killed, phone rebooted) keeps a warning given before it, for the rest of its hour. */
    private fun applyRestoredWarning(t: Long, s: FitToDriveState) {
        restoredWarning = null
        val now = wallClockOf(t)
        fun sensorTimeOf(wall: Long) = t - (now - wall) * 1_000_000L
        s.warnedWallMillis?.let { w ->
            if (now - w in 0..(config.watchAfterWarningSec * 1000).toLong()) warnedT = sensorTimeOf(w)
        }
        val aw = s.adviceWallMillis
        if (s.advice != FitAdvice.NONE && aw != null && now - aw in 0..(config.adviceExpirySec * 1000).toLong()) {
            advice = s.advice; adviceT = sensorTimeOf(aw); practiceAdvice = false
        }
    }

    fun report(t: Long = lastT) = FitToDriveReport(
        enabled = enabled,
        baselineStatus = status(),
        baselineSegments = baseline.size,
        baselineDays = baseline.days,
        baselineWalkMinutes = baseline.size * segmentSeconds / 60f,
        segmentsForProvisional = config.segmentsForProvisional,
        segmentsForEstablished = config.segmentsForEstablished,
        daysForEstablished = config.daysForEstablished,
        waitingForWalk = waitUntil != Long.MIN_VALUE,
        checkSecondsLeft = if (onRequest) ((waitUntil - t) / 1e9f).coerceAtLeast(0f) else 0f,
        // A few seconds of grace: putting the phone away is not "walking with it in the hand".
        walkingOutsidePocket = outsidePocketSince != Long.MIN_VALUE && t - outsidePocketSince > 3_000_000_000L,
        recordingWalk = enabled && walkingNow && inPocketNow,
        walkSession = walkSessionActive,
        walkMinutesNeeded = config.segmentsForProvisional * segmentSeconds / 60f,
        learningPaused = learningPaused,
        practice = practice,
        recentlyWarned = recentlyWarned(t),
        watchingDriving = enabled && inVehicleNow && drivingArmed(t),
        lastCheck = lastCheck,
        balanceRunning = balanceTest.running,
        balancePurpose = balancePurpose,
        balanceProgress = balanceTest.progress(t),
        balanceSecondsLeft = balanceTest.secondsLeft(t),
        balanceReferences = references.size,
        balanceReferencesNeeded = config.balanceReferences,
        lastBalance = lastBalance,
        advice = advice
    )

    // ------------------------------------------------------------------ internals
    private fun secs(s: Float) = (s * 1e9f).toLong()

    /**
     * Trip start or car Bluetooth: remind if warned (also within the hour after "I'm not
     * driving": the person may drive anyway), otherwise score the walk.
     */
    private fun beforeDrive(t: Long, trigger: CheckTrigger) {
        // Advice from a practice test is not a warning: a real trip clears it and checks the walk as usual.
        if (practiceAdvice) { advice = FitAdvice.NONE; practiceAdvice = false }
        if (warningActive || recentlyWarned(t)) {
            recent.clear() // possibly impaired walking: never learned
            // Vehicle detection flickers and Bluetooth reconnects: one reminder per trip.
            if (lastReminderT == Long.MIN_VALUE || t - lastReminderT > secs(config.reminderCooldownSec)) {
                lastReminderT = t
                onEvent(FitToDriveEvent.DrivingAfterWarning(trigger, advice, dismissed = !warningActive))
            }
            return
        }
        evaluate(t, trigger)
    }

    /** In a vehicle: track the driving; after a warning, report erratic driving (at most every 10 min). */
    private fun watchDriving(t: Long, s: FloatArray, hasGyro: Boolean, inVehicle: Boolean) {
        if (!inVehicle) {
            if (inVehicleNow) drivingWatch.reset() // a stop within the trip: keep the events seen so far
            inVehicleNow = false
            return
        }
        inVehicleNow = true
        val e = drivingWatch.processSample(t, s, hasGyro) ?: return
        if (!drivingArmed(t) || !drivingWatch.erratic(t)) return
        if (lastErraticT != Long.MIN_VALUE && t - lastErraticT < secs(config.erraticCooldownSec)) return
        lastErraticT = t
        onEvent(FitToDriveEvent.ErraticDriving(drivingWatch.recentEvents(e.tNanos)))
    }

    /**
     * Segments leaving the pre-drive window without a check become part of the baseline
     * only if the walk around them (about 1 min) is not a large gap. So, once established,
     * an unchecked unsteady walk (say, walking home after drinking) never widens "normal".
     */
    private fun pruneRecent(t: Long) {
        var learned = false
        while (recent.isNotEmpty() && t - recent.first().seg.tEndNanos > secs(config.lookbackSec)) {
            val r = recent.first()
            val ok = !r.learned && r.learnable && (r.verdict ?: judgeOldestWalk())
            recent.removeFirst()
            if (ok) learned = learn(r.seg, r.wall) || learned
        }
        if (learned) onEvent(FitToDriveEvent.StateChanged)
    }

    /**
     * Judges the oldest recent segment and the walking that follows it within about 1 min
     * as one walk: learnable unless it scores as a large gap. While provisional, "normal" is
     * often a single day and another sober day can look far from it, so only extreme
     * staggering ([Config.provisionalRejectAt]) is dropped; judging harder discards real
     * day-to-day variation (sober false alarms 1/24 -> 3/24 on MotionSense). The verdict is kept for every
     * segment judged, so the end of a walk is not judged again on its own.
     */
    private fun judgeOldestWalk(): Boolean {
        val first = recent.first()
        val until = first.seg.tEndNanos + secs(config.learnWindowSegments * segmentSeconds)
        val window = recent.asSequence().takeWhile { it.seg.tEndNanos <= until }.take(config.learnWindowSegments).toList()
        val normal = status() == BaselineStatus.LEARNING || run {
            val th = baseline.thresholds(window.size) ?: return@run false
            val distances = FloatArray(window.size)
            for ((k, w) in window.withIndex()) distances[k] = baseline.deviation(w.seg.values)?.distance ?: return@run false
            val rejectAt = if (status() == BaselineStatus.ESTABLISHED) th.large else maxOf(th.large, config.provisionalRejectAt)
            GaitBaseline.walkDistance(distances) < rejectAt
        }
        for (w in window) if (w.verdict == null) w.verdict = normal
        return normal
    }

    private fun learn(seg: GaitSegment, wall: Long): Boolean {
        if (learningBlocked) return false
        if (status() == BaselineStatus.ESTABLISHED) {
            val d = baseline.deviation(seg.values) ?: return false
            val th = baseline.thresholds(1) ?: return false
            if (d.distance > th.large) return false
        }
        return baseline.add(BaselineEntry(wall, dayKeyOf(wall), seg.values))
    }

    private fun learnAll(segments: List<Recent>) {
        var learned = false
        for (r in segments) if (!r.learned && r.learnable) learned = learn(r.seg, r.wall) || learned
        if (learned) onEvent(FitToDriveEvent.StateChanged)
    }

    /** Scores the recent walk; returns false if there was not enough walking to score. */
    private fun evaluate(t: Long, trigger: CheckTrigger): Boolean {
        pruneRecent(t)
        if (recent.size < config.minWalkSegments) return false
        val pending = recent.toList()
        recent.clear() // each walk is scored once
        val p = practice
        practice = null
        if (status() == BaselineStatus.LEARNING) {
            learnAll(pending)
            publishCheck(notScoredCheck(t, trigger, NotScoredReason.STILL_LEARNING, p))
            return true
        }
        val walk = pending.takeLast(config.maxWalkSegments)
        val th = baseline.thresholds(walk.size) ?: return false
        val zSum = DoubleArray(GaitFeature.COUNT)
        val zN = IntArray(GaitFeature.COUNT)
        val distances = FloatArray(walk.size)
        for ((k, r) in walk.withIndex()) {
            val d = baseline.deviation(r.seg.values) ?: return false
            distances[k] = d.distance
            for (i in 0 until GaitFeature.COUNT) if (!d.z[i].isNaN()) { zSum[i] = zSum[i] + d.z[i]; zN[i]++ }
        }
        val score = GaitBaseline.walkDistance(distances)
        val level = when {
            score >= th.large -> WalkLevel.LARGE_GAP
            score >= th.unusual -> WalkLevel.UNUSUAL
            else -> WalkLevel.NORMAL
        }
        val top = GaitFeature.entries
            .filter { zN[it.ordinal] > 0 }
            .map { it to (zSum[it.ordinal] / zN[it.ordinal]).toFloat() }
            .filter { it.second >= 1f }
            .sortedByDescending { it.second }
            .take(3)
        when (level) {
            // Only a normal walk becomes part of "normal" (a practice walk never does).
            WalkLevel.NORMAL -> if (p == null && !onRequest) learnAll(pending)
            WalkLevel.UNUSUAL ->
                // Before driving, a slightly unusual walk is worth a balance check, as an offer.
                // Already moving (trip detected late): no prompt for a mild case.
                if (trigger != CheckTrigger.TRIP_START && (advice == FitAdvice.NONE || advice == FitAdvice.BALANCE_OK)) {
                    advice = FitAdvice.CONSIDER_BALANCE_CHECK
                    adviceT = t
                    practiceAdvice = p != null
                }
            WalkLevel.LARGE_GAP -> {
                advice = FitAdvice.TAKE_BALANCE_CHECK
                adviceT = t
                practiceAdvice = p != null
                if (p == null) { warnedT = t; onEvent(FitToDriveEvent.StateChanged) } // a practice walk never arms the driving watch
            }
        }
        publishCheck(WalkCheck(wallClockOf(t), trigger, level, null, score, th.unusual, th.large,
            th.percentile(score), walk.size, status() != BaselineStatus.ESTABLISHED, top, p))
        return true
    }

    private fun notScoredCheck(t: Long, trigger: CheckTrigger, reason: NotScoredReason, p: PracticeWalk? = null) =
        WalkCheck(wallClockOf(t), trigger, null, reason, 0f, 0f, 0f, 0f, 0, true, emptyList(), p)

    private fun publishCheck(c: WalkCheck) {
        lastCheck = c
        onEvent(FitToDriveEvent.WalkChecked(c, advice))
    }

    private fun finishBalance(t: Long, m: BalanceMeasurement) {
        val purpose = balancePurpose ?: BalancePurpose.CHECK
        balancePurpose = null
        val practiceBalance = balancePractice
        balancePractice = false
        val wall = wallClockOf(t)
        var ratio = Float.NaN
        val outcome = when {
            m.moved -> BalanceOutcome.MOVED
            purpose == BalancePurpose.REFERENCE -> {
                ratio = if (references.isEmpty()) Float.NaN else swayRatio(m)
                // A "normal" far less steady than the earlier ones is probably not normal;
                // one far steadier was probably not held (phone put down).
                if (!ratio.isNaN() && (ratio >= config.balanceFailRatio || ratio < config.balanceTooStillRatio)) {
                    BalanceOutcome.REFERENCE_REJECTED
                } else {
                    references += BalanceReference(wall, m.swayAccel, m.swayGyro)
                    while (references.size > config.balanceReferences) references.removeAt(0)
                    onEvent(FitToDriveEvent.StateChanged)
                    BalanceOutcome.REFERENCE_SAVED
                }
            }
            references.isEmpty() -> BalanceOutcome.NO_REFERENCE
            else -> {
                ratio = swayRatio(m)
                when {
                    ratio >= config.balanceFailRatio -> BalanceOutcome.FAILED
                    // Phone put down on a table, or sitting: no real standing balance measured.
                    ratio < config.balanceTooStillRatio -> BalanceOutcome.TOO_STILL
                    else -> BalanceOutcome.PASSED
                }
            }
        }
        if (purpose == BalancePurpose.CHECK) {
            val flagged = warningActive
            advice = when (outcome) {
                BalanceOutcome.PASSED -> FitAdvice.BALANCE_OK
                BalanceOutcome.FAILED -> FitAdvice.DONT_DRIVE
                // After a large walking gap, a check that could not show normal balance is not a pass.
                else -> if (flagged) FitAdvice.DONT_DRIVE else advice
            }
            adviceT = t
            // Still practice: the test C itself, or a check after a practice warning that did not fail.
            practiceAdvice = practiceBalance || (practiceAdvice && outcome != BalanceOutcome.FAILED)
            when {
                advice == FitAdvice.BALANCE_OK -> warnedT = Long.MIN_VALUE // normal balance: all clear
                advice == FitAdvice.DONT_DRIVE && !practiceAdvice -> warnedT = t
            }
            onEvent(FitToDriveEvent.StateChanged)
        }
        val result = BalanceResult(wall, purpose, outcome, m.swayAccel, m.swayGyro, ratio)
        lastBalance = result
        onEvent(FitToDriveEvent.BalanceDone(result, advice))
    }

    /** Sway relative to the median reference; geometric mean of accel and gyro ratios when both exist. */
    private fun swayRatio(m: BalanceMeasurement): Float {
        val refAccel = median(references.map { it.swayAccel })
        val accelRatio = m.swayAccel / refAccel.coerceAtLeast(1e-3f)
        val gyros = references.map { it.swayGyro }.filter { !it.isNaN() }
        if (m.swayGyro.isNaN() || gyros.isEmpty()) return accelRatio
        val gyroRatio = m.swayGyro / median(gyros).coerceAtLeast(1e-3f)
        return sqrt(accelRatio * gyroRatio)
    }

    private fun median(xs: List<Float>): Float {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
