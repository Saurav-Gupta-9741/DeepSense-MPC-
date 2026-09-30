package com.iitj.pervasivesense

import kotlin.math.acos
import kotlin.math.sqrt

enum class FallState {
    MONITORING,
    IMPACT_SUSPECTED,
    FALL_CONFIRMED
}

/**
 * Multi-phase fall detector (threshold + posture-change + inactivity model).
 *
 *  1. Pre-impact: a low-g phase (|a| < [freeFallG]) within [freeFallLookbackSec]
 *     before the impact - the body/phone accelerates toward the ground.
 *  2. Impact: |a| > [impactG].
 *  3. Settling: the first [settleSec] after impact contain bounces and are
 *     deliberately NOT used for the immobility test (including them made the
 *     original implementation reject genuine falls).
 *  4. Observation for [observeSec]: the person must be immobile
 *     (std of |a| < [immobileStdG]) AND the device orientation must have changed
 *     by at least [minOrientationChangeDeg] compared to before the fall
 *     (upright -> lying). Stomping or jumping and then standing still fails
 *     the orientation test; getting up and moving fails the immobility test.
 *
 * Once confirmed the state latches until [acknowledge] (user tapped "I'm OK")
 * or [reset]. All timing uses sensor timestamps.
 */
class FallDetector(
    private val impactG: Float = 2.5f,
    private val freeFallG: Float = 0.6f,
    private val freeFallLookbackSec: Float = 1.0f,
    private val settleSec: Float = 1.5f,
    private val observeSec: Float = 6.0f,
    private val immobileStdG: Float = 0.10f,
    private val minOrientationChangeDeg: Float = 30f,
    private val maxGapSec: Float = 1.0f
) {
    companion object {
        const val G = 9.80665f
        private const val HISTORY = 128 // > 2 s at 50 Hz
    }

    var currentState: FallState = FallState.MONITORING
        private set
    var lastImpactPeakG = 0f
        private set

    // Recent history for the pre-impact analysis.
    private val hT = LongArray(HISTORY)
    private val hA = FloatArray(HISTORY * 3)
    private var hHead = 0
    private var hCount = 0

    private var impactT = 0L
    private var lastT = Long.MIN_VALUE
    private val preImpactDir = FloatArray(3)
    // Welford statistics over the observation phase.
    private var obsN = 0
    private var obsMean = 0.0
    private var obsM2 = 0.0
    private val obsSum = DoubleArray(3)

    /** Returns true exactly once, on the sample that confirms a fall. */
    fun processSample(tNanos: Long, ax: Float, ay: Float, az: Float): Boolean {
        val gap = lastT != Long.MIN_VALUE && (tNanos - lastT) > (maxGapSec * 1e9f).toLong()
        lastT = tNanos
        val magG = sqrt(ax * ax + ay * ay + az * az) / G
        var confirmed = false

        when (currentState) {
            FallState.MONITORING -> {
                if (magG > impactG && hadPreImpactLowG(tNanos) && estimatePreImpactDirection(tNanos)) {
                    currentState = FallState.IMPACT_SUSPECTED
                    impactT = tNanos
                    lastImpactPeakG = magG
                    obsN = 0; obsMean = 0.0; obsM2 = 0.0; obsSum.fill(0.0)
                }
            }
            FallState.IMPACT_SUSPECTED -> {
                val sinceImpact = (tNanos - impactT) / 1e9f
                if (gap) {
                    currentState = FallState.MONITORING // cannot judge across missing data
                } else if (sinceImpact < settleSec) {
                    if (magG > lastImpactPeakG) lastImpactPeakG = magG
                } else if (sinceImpact < settleSec + observeSec) {
                    obsN++
                    val d = magG - obsMean
                    obsMean += d / obsN
                    obsM2 += d * (magG - obsMean)
                    obsSum[0] += ax.toDouble(); obsSum[1] += ay.toDouble(); obsSum[2] += az.toDouble()
                } else {
                    val std = if (obsN > 1) sqrt(obsM2 / (obsN - 1)) else Double.MAX_VALUE
                    val change = angleDeg(preImpactDir, obsSum)
                    if (obsN >= 10 && std < immobileStdG && change >= minOrientationChangeDeg) {
                        currentState = FallState.FALL_CONFIRMED
                        confirmed = true
                    } else {
                        currentState = FallState.MONITORING
                    }
                }
            }
            FallState.FALL_CONFIRMED -> Unit // latched until acknowledged
        }
        remember(tNanos, ax, ay, az)
        return confirmed
    }

    /** User confirmed they are OK, or recovery (sustained walking) was observed. */
    fun acknowledge() {
        if (currentState == FallState.FALL_CONFIRMED) currentState = FallState.MONITORING
    }

    fun reset() {
        currentState = FallState.MONITORING
        hHead = 0; hCount = 0; lastT = Long.MIN_VALUE
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
        var found = false
        forHistory { t, i ->
            if (t >= from) {
                val m = sqrt(hA[i * 3] * hA[i * 3] + hA[i * 3 + 1] * hA[i * 3 + 1] + hA[i * 3 + 2] * hA[i * 3 + 2]) / G
                if (m < freeFallG) found = true
            }
        }
        return found
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
