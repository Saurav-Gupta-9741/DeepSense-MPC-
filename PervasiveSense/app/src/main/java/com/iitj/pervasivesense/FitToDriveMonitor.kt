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
enum class BalanceOutcome { PASSED, FAILED, MOVED, NO_REFERENCE, REFERENCE_SAVED, REFERENCE_REJECTED }
enum class BalanceStart { STARTED, REFUSED_WARNING, REFUSED_IN_VEHICLE }

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
    val topDeviations: List<Pair<GaitFeature, Float>>
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
    /** Walking, but the phone is not in a pocket: this walk is not used. */
    val walkingOutsidePocket: Boolean,
    /** A study session is labelled: nothing is learned. */
    val learningPaused: Boolean,
    val lastCheck: WalkCheck?,
    val balanceRunning: Boolean,
    val balancePurpose: BalancePurpose?,
    val balanceProgress: Float,
    val balanceSecondsLeft: Int,
    val balanceReferences: Int,
    val balanceReferencesRecommended: Int,
    val lastBalance: BalanceResult?,
    val advice: FitAdvice
)

sealed interface FitToDriveEvent {
    class WalkChecked(val check: WalkCheck, val advice: FitAdvice) : FitToDriveEvent
    class BalanceDone(val result: BalanceResult, val advice: FitAdvice) : FitToDriveEvent
    /** A trip is starting although the last check warned against driving (a reminder, nothing more). */
    class DrivingAfterWarning(val trigger: CheckTrigger, val advice: FitAdvice) : FitToDriveEvent
    /** Every analysed walking segment, with its distance from normal (NaN while learning): study log. */
    class SegmentMeasured(val segment: GaitSegment, val distance: Float) : FitToDriveEvent
    /** Learned data changed (persist it). */
    object StateChanged : FitToDriveEvent
}

/** Everything the check learns, for persistence across restarts. */
class FitToDriveState(val baseline: List<BaselineEntry>, val balanceReferences: List<BalanceReference>)

/**
 * Fit-to-drive check (opt-in).
 *
 *  1. Learn normal: while enabled, every segment of everyday walking with the
 *     phone in a pocket is added to the user's own [GaitBaseline] (up to a
 *     daily quota so one long walk cannot dominate). Segments are quarantined
 *     first: they are learned only once they leave the pre-drive window
 *     unflagged, so the walk being scored is never part of the "normal" it is
 *     compared with. A scored walk is learned only if it was normal, so
 *     slightly impaired evenings never drift into "normal". Nothing is learned
 *     while a warning or offer is active or during a labelled study session.
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
        val segmentsForProvisional: Int = 30,   // ≈ 2 min of walking (4 s per segment)
        val segmentsForEstablished: Int = 150,  // ≈ 10 min
        val daysForEstablished: Int = 7,
        val lookbackSec: Float = 300f,          // vehicle detection lags the start of a trip
        val minWalkSegments: Int = 3,           // ≈ 16 s of walking
        val maxWalkSegments: Int = 15,          // the last ≈ minute before the trip
        val manualWaitSec: Float = 180f,
        val adviceExpirySec: Float = 3600f,
        val balanceFailRatio: Float = 1.75f,
        val maxBalanceReferences: Int = 5,
        val recommendedBalanceReferences: Int = 3,
        val maxPerDay: Int = 150
    )

    private val extractor = GaitSegmentExtractor()
    private val baseline = GaitBaseline(maxPerDay = config.maxPerDay)
    private val recent = ArrayDeque<Pair<GaitSegment, Long>>() // segment + wall-clock time
    private val balanceTest = BalanceTest()
    private var balancePurpose: BalancePurpose? = null
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
    private var lastT = Long.MIN_VALUE
    private var outsidePocketSince = Long.MIN_VALUE

    private val segmentSeconds get() = extractor.hopSamples / 50f
    private val warningActive get() = advice == FitAdvice.TAKE_BALANCE_CHECK || advice == FitAdvice.DONT_DRIVE
    /** Possibly impaired right now: do not learn "normal" from it. */
    private val learningBlocked get() = learningPaused || warningActive || advice == FitAdvice.CONSIDER_BALANCE_CHECK

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        extractor.reset(); recent.clear(); waitUntil = Long.MIN_VALUE
        if (!on) { advice = FitAdvice.NONE; cancelBalanceTest() }
    }

    fun status(): BaselineStatus = when {
        baseline.size < config.segmentsForProvisional -> BaselineStatus.LEARNING
        baseline.size >= config.segmentsForEstablished && baseline.days >= config.daysForEstablished -> BaselineStatus.ESTABLISHED
        else -> BaselineStatus.PROVISIONAL
    }

    /** Sensor data is missing: never build a segment across the hole. */
    fun onGap() = extractor.reset()

    /**
     * Every uniform 50 Hz sample. [walking]: the displayed activity is WALKING.
     * [inPocket]: the proximity sensor is covered (true if the phone has none);
     * only pocket walks are learned and scored, like the baseline.
     */
    fun processSample(t: Long, s: FloatArray, walking: Boolean, hasGyro: Boolean, inPocket: Boolean = true) {
        lastT = t
        balanceTest.processSample(t, s, hasGyro)?.let { finishBalance(t, it) }
        if (advice != FitAdvice.NONE && adviceT != Long.MIN_VALUE && t - adviceT > secs(config.adviceExpirySec)) {
            advice = FitAdvice.NONE
        }
        if (!enabled) return
        outsidePocketSince = if (walking && !inPocket) (if (outsidePocketSince == Long.MIN_VALUE) t else outsidePocketSince) else Long.MIN_VALUE
        if (waitUntil != Long.MIN_VALUE && t > waitUntil) {
            waitUntil = Long.MIN_VALUE
            publishCheck(notScoredCheck(t, CheckTrigger.MANUAL, NotScoredReason.NO_RECENT_WALK))
        }
        if (recent.isNotEmpty() && t - recent.first().first.tEndNanos > secs(config.lookbackSec)) pruneRecent(t)
        val seg = extractor.processSample(t, s, walking && inPocket, hasGyro) ?: return
        recent.addLast(seg to wallClockOf(t))
        onEvent(FitToDriveEvent.SegmentMeasured(seg, baseline.deviation(seg.values)?.distance ?: Float.NaN))
        if (waitUntil != Long.MIN_VALUE && recent.size >= config.minWalkSegments) {
            waitUntil = Long.MIN_VALUE
            evaluate(t, CheckTrigger.MANUAL)
        }
    }

    /** The vehicle context just started (Activity Recognition IN_VEHICLE). */
    fun onTripStart(t: Long) { if (enabled) beforeDrive(t, CheckTrigger.TRIP_START) }

    /** A car audio / hands-free Bluetooth device connected: usually before the car moves. */
    fun onCarBluetooth(t: Long) { if (enabled) beforeDrive(t, CheckTrigger.CAR_BLUETOOTH) }

    /** "Check my walk": scores recent walking, or waits for the user to walk. */
    fun requestCheck(t: Long) {
        if (!enabled) return
        if (!evaluate(t, CheckTrigger.MANUAL)) waitUntil = t + secs(config.manualWaitSec)
    }

    /**
     * Never in a moving vehicle (the driver must not use the phone, and car
     * motion is not body sway). A reference ("my normal balance") is refused
     * while the person may be impaired.
     */
    fun startBalanceTest(t: Long, purpose: BalancePurpose, inVehicle: Boolean = false): BalanceStart {
        if (inVehicle) return BalanceStart.REFUSED_IN_VEHICLE
        if (purpose == BalancePurpose.REFERENCE && (warningActive || advice == FitAdvice.CONSIDER_BALANCE_CHECK)) {
            return BalanceStart.REFUSED_WARNING
        }
        balancePurpose = purpose
        balanceTest.start(t)
        return BalanceStart.STARTED
    }

    fun cancelBalanceTest() { balanceTest.cancel(); balancePurpose = null }

    /** "I'm not driving" or the user read the advice. */
    fun dismissAdvice() { advice = FitAdvice.NONE }

    fun resetLearning() {
        baseline.clear(); references.clear(); recent.clear()
        lastCheck = null; lastBalance = null; advice = FitAdvice.NONE
        onEvent(FitToDriveEvent.StateChanged)
    }

    fun exportState() = FitToDriveState(baseline.snapshot(), references.toList())

    fun restore(state: FitToDriveState) {
        baseline.restore(state.baseline)
        references.clear(); references.addAll(state.balanceReferences.takeLast(config.maxBalanceReferences))
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
        // A few seconds of grace: putting the phone away is not "walking with it in the hand".
        walkingOutsidePocket = outsidePocketSince != Long.MIN_VALUE && t - outsidePocketSince > 3_000_000_000L,
        learningPaused = learningPaused,
        lastCheck = lastCheck,
        balanceRunning = balanceTest.running,
        balancePurpose = balancePurpose,
        balanceProgress = balanceTest.progress(t),
        balanceSecondsLeft = balanceTest.secondsLeft(t),
        balanceReferences = references.size,
        balanceReferencesRecommended = config.recommendedBalanceReferences,
        lastBalance = lastBalance,
        advice = advice
    )

    // ------------------------------------------------------------------ internals
    private fun secs(s: Float) = (s * 1e9f).toLong()

    /** Trip start or car Bluetooth: remind if already warned, otherwise score the walk. */
    private fun beforeDrive(t: Long, trigger: CheckTrigger) {
        if (warningActive) {
            recent.clear() // possibly impaired walking: never learned
            onEvent(FitToDriveEvent.DrivingAfterWarning(trigger, advice))
            return
        }
        evaluate(t, trigger)
    }

    /** Segments leaving the pre-drive window unflagged become part of the baseline. */
    private fun pruneRecent(t: Long) {
        var learned = false
        while (recent.isNotEmpty() && t - recent.first().first.tEndNanos > secs(config.lookbackSec)) {
            val (seg, wall) = recent.removeFirst()
            learned = learn(seg, wall) || learned
        }
        if (learned) onEvent(FitToDriveEvent.StateChanged)
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

    private fun learnAll(segments: List<Pair<GaitSegment, Long>>) {
        var learned = false
        for ((seg, wall) in segments) learned = learn(seg, wall) || learned
        if (learned) onEvent(FitToDriveEvent.StateChanged)
    }

    /** Scores the recent walk; returns false if there was not enough walking to score. */
    private fun evaluate(t: Long, trigger: CheckTrigger): Boolean {
        pruneRecent(t)
        if (recent.size < config.minWalkSegments) return false
        val pending = recent.toList()
        recent.clear() // each walk is scored once
        if (status() == BaselineStatus.LEARNING) {
            learnAll(pending)
            publishCheck(notScoredCheck(t, trigger, NotScoredReason.STILL_LEARNING))
            return true
        }
        val walk = pending.takeLast(config.maxWalkSegments)
        val th = baseline.thresholds(walk.size) ?: return false
        val zSum = DoubleArray(GaitFeature.COUNT)
        val zN = IntArray(GaitFeature.COUNT)
        val distances = FloatArray(walk.size)
        for ((k, pair) in walk.withIndex()) {
            val d = baseline.deviation(pair.first.values) ?: return false
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
            // Only a normal walk becomes part of "normal".
            WalkLevel.NORMAL -> learnAll(pending)
            WalkLevel.UNUSUAL ->
                // Before driving, a slightly unusual walk is worth a balance check, as an offer.
                // Already moving (trip detected late): no prompt for a mild case.
                if (trigger != CheckTrigger.TRIP_START && (advice == FitAdvice.NONE || advice == FitAdvice.BALANCE_OK)) {
                    advice = FitAdvice.CONSIDER_BALANCE_CHECK
                    adviceT = t
                }
            WalkLevel.LARGE_GAP -> {
                advice = FitAdvice.TAKE_BALANCE_CHECK
                adviceT = t
            }
        }
        publishCheck(WalkCheck(wallClockOf(t), trigger, level, null, score, th.unusual, th.large,
            th.percentile(score), walk.size, status() != BaselineStatus.ESTABLISHED, top))
        return true
    }

    private fun notScoredCheck(t: Long, trigger: CheckTrigger, reason: NotScoredReason) =
        WalkCheck(wallClockOf(t), trigger, null, reason, 0f, 0f, 0f, 0f, 0, true, emptyList())

    private fun publishCheck(c: WalkCheck) {
        lastCheck = c
        onEvent(FitToDriveEvent.WalkChecked(c, advice))
    }

    private fun finishBalance(t: Long, m: BalanceMeasurement) {
        val purpose = balancePurpose ?: BalancePurpose.CHECK
        balancePurpose = null
        val wall = wallClockOf(t)
        var ratio = Float.NaN
        val outcome = when {
            m.moved -> BalanceOutcome.MOVED
            purpose == BalancePurpose.REFERENCE -> {
                ratio = if (references.isEmpty()) Float.NaN else swayRatio(m)
                // A "normal" far less steady than the earlier ones is probably not normal.
                if (!ratio.isNaN() && ratio >= config.balanceFailRatio) {
                    BalanceOutcome.REFERENCE_REJECTED
                } else {
                    references += BalanceReference(wall, m.swayAccel, m.swayGyro)
                    while (references.size > config.maxBalanceReferences) references.removeAt(0)
                    onEvent(FitToDriveEvent.StateChanged)
                    BalanceOutcome.REFERENCE_SAVED
                }
            }
            references.isEmpty() -> BalanceOutcome.NO_REFERENCE
            else -> {
                ratio = swayRatio(m)
                if (ratio >= config.balanceFailRatio) BalanceOutcome.FAILED else BalanceOutcome.PASSED
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
