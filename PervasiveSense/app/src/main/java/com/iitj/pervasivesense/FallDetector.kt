package com.iitj.pervasivesense

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class FallState {
    MONITORING,
    IMPACT_SUSPECTED,
    FALL_CONFIRMED
}

/** What the detector decided about the last drop (a free fall then an impact), so the screen can say why. */
enum class FallCheck {
    FALL,
    /** One sharp tick after a short dip (a phone put down on a table); a body lands softer. */
    HARD_LANDING,
    /** It kept moving (got up and walked, or the phone was picked up). */
    MOVED,
    /** It ended up the same way as before, upright: a jump, still standing. */
    NO_TURN,
    /** It fell (a real drop) but landed too softly to tell (impact below the threshold): a deep pillow. */
    SOFT_LANDING,
    /** Sensor data was missing: not judged. */
    GAP
}

class FallVerdict(val tNanos: Long, val peakG: Float, val check: FallCheck)

/**
 * Multi-phase fall detector (threshold + posture + inactivity model). Always on: there is no
 * test mode, because nobody knows when a person will fall.
 *
 *  1. Pre-impact: a low-g phase within [freeFallLookbackSec] before the impact - the
 *     body/phone accelerates toward the ground: at least [minLowGSamples] samples
 *     below [lowG] (≈ 0.1 s), one of them below [freeFallG]. Moving the phone by
 *     hand dips for a moment only.
 *  2. Impact: |a| > [impactG]. Two kinds of fall:
 *     - **A real drop** ([reportDropSamples] samples in a row below [lowG], 0.3 s, about 45 cm or
 *       more): a person falling, or the phone flying out of the hand of a person who falls. It counts
 *       whatever it hits (a hard floor rings once) and even while the phone was in use.
 *     - **A short dip**: must be a body's impact, not a phone's tick (at least [minImpactSamples]
 *       samples above [impactBodyG] within [impactWindowSec]; a phone put down on a table rings for
 *       one sample), and never while the phone is held and looked at ([processSample] `held`) in the
 *       [heldGraceSec] before the impact: that is the phone being put down.
 *  3. Settling: the first [settleSec] after impact contain bounces and are
 *     deliberately NOT used for the immobility test (including them made the
 *     original implementation reject genuine falls).
 *  4. Observation for [observeSec]: immobile (std of |a| < [immobileStdG]) AND lying: turned by at
 *     least [minOrientationChangeDeg] from before the fall (standing to lying), or, after a real
 *     drop, resting flat on the ground (a phone that left the hand lands flat, whichever way up it
 *     was held). A jump with the phone in a pocket, then standing still, fails both.
 *  5. Still moving at first (trying to get up): watched for up to [lateWatchSec] after the impact.
 *     Lying still later, turned at least [lateTurnDeg] (clearly lying, not just picked up and held),
 *     is a fall; still and upright again (got up) or moving all along is not.
 *
 * Once confirmed the state latches until [acknowledge] (user tapped "I'm OK")
 * or [reset]. All timing uses sensor timestamps.
 */
class FallDetector(
    private val impactG: Float = 2.5f,
    private val freeFallG: Float = 0.6f,
    private val lowG: Float = 0.75f,
    private val minLowGSamples: Int = 5,
    private val impactBodyG: Float = 1.5f,
    private val minImpactSamples: Int = 3,
    private val impactWindowSec: Float = 0.4f,
    private val heldGraceSec: Float = 2.0f,
    private val freeFallLookbackSec: Float = 1.0f,
    private val settleSec: Float = 1.5f,
    private val observeSec: Float = 6.0f,
    private val immobileStdG: Float = 0.10f,
    private val minOrientationChangeDeg: Float = 30f,
    private val maxGapSec: Float = 1.0f,
    /** A real drop: this many samples in a row below [lowG] (0.3 s, a drop of about 45 cm or more). A step in a pocket stays light for about 10 (real walking). */
    private val reportDropSamples: Int = 15,
    /** Lying flat: the screen's axis within about 25° of vertical (cos 25°). */
    private val flatCos: Float = 0.9f,
    private val lateWatchSec: Float = 30f,
    private val lateTurnDeg: Float = 60f
) {
    companion object {
        const val G = 9.80665f
        private const val HISTORY = 128 // > 2 s at 50 Hz
    }

    var currentState: FallState = FallState.MONITORING
        private set
    var lastImpactPeakG = 0f
        private set
    /**
     * The last drop that was judged: a free fall then an impact above [impactG]. Everyday impacts
     * without a free fall (steps, sitting down) are never reported here.
     */
    var lastVerdict: FallVerdict? = null
        private set

    /** The impact being observed followed a real drop (not a step): its verdict is shown. */
    private var reportable = false
    /** Samples in a row below [lowG] so far (monitoring). */
    private var lowRun = 0
    /** A real drop just ended without an impact yet: if none comes by then, it landed too softly. */
    private var softUntil = Long.MIN_VALUE
    private var softPeak = 0f

    private fun verdict(t: Long, peak: Float, check: FallCheck) {
        if (check == FallCheck.FALL || reportable) lastVerdict = FallVerdict(t, peak, check)
    }

    // Recent history for the pre-impact analysis.
    private val hT = LongArray(HISTORY)
    private val hA = FloatArray(HISTORY * 3)
    private var hHead = 0
    private var hCount = 0

    private var impactT = 0L
    private var impactSamples = 0
    /** The impact followed a real drop ([reportDropSamples]): no body-impact or hand rule. */
    private var dropped = false
    /** The current observation window started then. */
    private var obsStartT = 0L
    /** Moving in the first window: watching for lying still later ([lateWatchSec]). */
    private var lateWatch = false
    private var lastHeldT = Long.MIN_VALUE
    private var lastT = Long.MIN_VALUE
    private val preImpactDir = FloatArray(3)
    // Welford statistics over the observation phase.
    private var obsN = 0
    private var obsMean = 0.0
    private var obsM2 = 0.0
    private val obsSum = DoubleArray(3)

    /**
     * Returns true exactly once, on the sample that confirms a fall. [held]: the phone is in
     * the hand and looked at (screen on, uncovered): a short dip and an impact then is the phone
     * being put down (a real drop from the hand still counts).
     */
    fun processSample(tNanos: Long, ax: Float, ay: Float, az: Float, held: Boolean = false): Boolean {
        val gap = lastT != Long.MIN_VALUE && (tNanos - lastT) > (maxGapSec * 1e9f).toLong()
        lastT = tNanos
        if (held) lastHeldT = tNanos
        val heldRecently = lastHeldT != Long.MIN_VALUE && tNanos - lastHeldT <= (heldGraceSec * 1e9f).toLong()
        val magG = sqrt(ax * ax + ay * ay + az * az) / G
        var confirmed = false

        when (currentState) {
            FallState.MONITORING -> {
                // A drop that never hits hard: say "too soft" (a drop onto a deep pillow).
                if (magG < lowG) lowRun++ else {
                    if (lowRun >= reportDropSamples) { softUntil = tNanos + 500_000_000L; softPeak = 0f }
                    lowRun = 0
                }
                if (softUntil != Long.MIN_VALUE) {
                    softPeak = maxOf(softPeak, magG)
                    if (magG > impactG) softUntil = Long.MIN_VALUE // it did hit: judged below
                    else if (tNanos > softUntil) {
                        softUntil = Long.MIN_VALUE
                        lastVerdict = FallVerdict(tNanos, softPeak, FallCheck.SOFT_LANDING)
                    }
                }
                if (magG > impactG && hadPreImpactLowG(tNanos)) {
                    val realDrop = deepLowGSamples(tNanos) >= reportDropSamples
                    // A short dip with the phone in use: putting it down. A real drop from the hand counts.
                    if ((realDrop || !heldRecently) && estimatePreImpactDirection(tNanos)) {
                        reportable = realDrop
                        dropped = realDrop
                        currentState = FallState.IMPACT_SUSPECTED
                        impactT = tNanos
                        impactSamples = 1
                        lastImpactPeakG = magG
                        lateWatch = false
                        startObservation(tNanos + (settleSec * 1e9f).toLong())
                    }
                }
            }
            FallState.IMPACT_SUSPECTED -> {
                val sinceImpact = (tNanos - impactT) / 1e9f
                if (gap) {
                    currentState = FallState.MONITORING // cannot judge across missing data
                    verdict(impactT, lastImpactPeakG, FallCheck.GAP)
                } else if (sinceImpact < impactWindowSec) {
                    if (magG > impactBodyG) impactSamples++
                    if (magG > lastImpactPeakG) lastImpactPeakG = magG
                } else if (!dropped && impactSamples < minImpactSamples) {
                    currentState = FallState.MONITORING // a sharp tick: the phone hit something, the body did not
                    verdict(impactT, lastImpactPeakG, FallCheck.HARD_LANDING)
                } else if (tNanos < obsStartT) {
                    if (magG > lastImpactPeakG) lastImpactPeakG = magG
                } else if (tNanos - obsStartT < (observeSec * 1e9f).toLong()) {
                    obsN++
                    val d = magG - obsMean
                    obsMean += d / obsN
                    obsM2 += d * (magG - obsMean)
                    obsSum[0] += ax.toDouble(); obsSum[1] += ay.toDouble(); obsSum[2] += az.toDouble()
                } else {
                    val still = obsN >= 10 && sqrt(obsM2 / (obsN - 1)) < immobileStdG
                    val change = angleDeg(preImpactDir, obsSum)
                    val lying = if (lateWatch) change >= lateTurnDeg
                        else change >= minOrientationChangeDeg || (dropped && restingFlat())
                    when {
                        obsN < 10 -> end(FallCheck.GAP)
                        still && lying -> {
                            currentState = FallState.FALL_CONFIRMED
                            confirmed = true
                            verdict(impactT, lastImpactPeakG, FallCheck.FALL)
                        }
                        // Still and upright: never fell (a jump), or got up (or picked the phone up and holds it).
                        still && change < minOrientationChangeDeg -> end(if (lateWatch) FallCheck.MOVED else FallCheck.NO_TURN)
                        // Moving (trying to get up?), or half turned: keep watching for a while.
                        sinceImpact < settleSec + lateWatchSec -> { lateWatch = true; startObservation(tNanos) }
                        else -> end(if (still) FallCheck.NO_TURN else FallCheck.MOVED)
                    }
                }
            }
            FallState.FALL_CONFIRMED -> Unit // latched until acknowledged
        }
        remember(tNanos, ax, ay, az)
        return confirmed
    }

    private fun end(check: FallCheck) {
        currentState = FallState.MONITORING
        verdict(impactT, lastImpactPeakG, check)
    }

    private fun startObservation(from: Long) {
        obsStartT = from
        obsN = 0; obsMean = 0.0; obsM2 = 0.0; obsSum.fill(0.0)
    }

    /** Lying flat (screen up or down): gravity along the screen's axis. */
    private fun restingFlat(): Boolean {
        val n = sqrt(obsSum[0] * obsSum[0] + obsSum[1] * obsSum[1] + obsSum[2] * obsSum[2])
        return n > 1e-6 && abs(obsSum[2]) >= flatCos * n
    }

    /** User confirmed they are OK, or recovery (sustained walking) was observed. */
    fun acknowledge() {
        if (currentState == FallState.FALL_CONFIRMED) currentState = FallState.MONITORING
    }

    fun reset() {
        currentState = FallState.MONITORING
        hHead = 0; hCount = 0; lastT = Long.MIN_VALUE
        lowRun = 0; softUntil = Long.MIN_VALUE
    }

    private fun remember(t: Long, ax: Float, ay: Float, az: Float) {
        hT[hHead] = t
        hA[hHead * 3] = ax; hA[hHead * 3 + 1] = ay; hA[hHead * 3 + 2] = az
        hHead = (hHead + 1) % HISTORY
        if (hCount < HISTORY) hCount++
    }

    private inline fun forHistory(action: (t: Long, i: Int) -> Unit) {
        for (k in 0 until hCount) {
            val i = (hHead - 1 - k + HISTORY) % HISTORY
            action(hT[i], i)
        }
    }

    private fun hadPreImpactLowG(now: Long): Boolean {
        val from = now - (freeFallLookbackSec * 1e9f).toLong()
        var deep = false
        var low = 0
        forHistory { t, i ->
            if (t >= from) {
                val m = sqrt(hA[i * 3] * hA[i * 3] + hA[i * 3 + 1] * hA[i * 3 + 1] + hA[i * 3 + 2] * hA[i * 3 + 2]) / G
                if (m < freeFallG) deep = true
                if (m < lowG) low++
            }
        }
        return deep && low >= minLowGSamples
    }

    /** The longest unbroken run below [lowG] in the second before the impact: how long the phone fell. */
    private fun deepLowGSamples(now: Long): Int {
        val from = now - (freeFallLookbackSec * 1e9f).toLong()
        var run = 0; var best = 0
        forHistory { t, i ->
            if (t >= from) {
                val m = sqrt(hA[i * 3] * hA[i * 3] + hA[i * 3 + 1] * hA[i * 3 + 1] + hA[i * 3 + 2] * hA[i * 3 + 2]) / G
                run = if (m < lowG) run + 1 else 0
                if (run > best) best = run
            }
        }
        return best
    }

    /** Mean acceleration (≈ gravity direction) over the second before the fall began. */
    private fun estimatePreImpactDirection(now: Long): Boolean {
        val to = now - (freeFallLookbackSec * 1e9f).toLong()
        val from = to - 1_000_000_000L
        var n = 0
        preImpactDir.fill(0f)
        forHistory { t, i ->
            if (t in from..to) {
                preImpactDir[0] += hA[i * 3]; preImpactDir[1] += hA[i * 3 + 1]; preImpactDir[2] += hA[i * 3 + 2]
                n++
            }
        }
        return n >= 5
    }

    private fun angleDeg(a: FloatArray, b: DoubleArray): Double {
        val na = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble())
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-6 || nb < 1e-6) return 0.0
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }
}
