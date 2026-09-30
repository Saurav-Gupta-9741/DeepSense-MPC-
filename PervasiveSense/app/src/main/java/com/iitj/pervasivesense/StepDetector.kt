package com.iitj.pervasivesense

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Orientation-independent pedometer on the 50 Hz accelerometer magnitude.
 *
 *  - |a| is detrended by a slow low-pass baseline (removes gravity, any pose)
 *    and smoothed by a fast low-pass (~4 Hz) to suppress impact ringing.
 *  - A step is a local maximum above an adaptive threshold
 *    max([minPeak], [adaptiveFraction] x recent peak amplitude), re-armed only
 *    after the signal falls back below the baseline (prevents double counts on
 *    double-humped heel-strike waveforms).
 *  - Physiological cadence gate: steps must be [minIntervalSec]..[maxIntervalSec]
 *    apart.
 *  - Steps are only committed after [streakToCommit] regular steps in a row,
 *    so isolated bumps (sitting down, a car pothole) are not counted; once a
 *    streak is established each further step counts immediately.
 *
 * This is the fallback when the phone has no hardware TYPE_STEP_COUNTER.
 */
class StepDetector(
    private val baselineTauSec: Float = 1.5f,
    private val smoothTauSec: Float = 0.04f,
    private val minPeak: Float = 0.8f,           // m/s²
    private val adaptiveFraction: Float = 0.4f,
    private val minIntervalSec: Float = 0.25f,
    private val maxIntervalSec: Float = 2.0f,
    private val streakToCommit: Int = 4
) {
    private var init = false
    private var baseline = 0f
    private var smooth = 0f
    private var prev = 0f
    private var rising = false
    private var armed = true
    private var lastT = 0L
    private var lastStepT = Long.MIN_VALUE
    private var streak = 0
    private var peakAvg = 0f

    var totalSteps = 0
        private set

    /** Processes one sample; returns the number of newly committed steps. */
    fun processSample(tNanos: Long, ax: Float, ay: Float, az: Float): Int {
        val m = sqrt(ax * ax + ay * ay + az * az)
        if (!init) {
            baseline = m; smooth = 0f; prev = 0f; lastT = tNanos; init = true
            return 0
        }
        val dt = ((tNanos - lastT).coerceAtLeast(0L)) / 1e9f
        lastT = tNanos
        val ab = exp(-dt / baselineTauSec)
        baseline = ab * baseline + (1 - ab) * m
        val asm = exp(-dt / smoothTauSec)
        smooth = asm * smooth + (1 - asm) * (m - baseline)

        var committed = 0
        if (lastStepT != Long.MIN_VALUE && (tNanos - lastStepT) / 1e9f > maxIntervalSec) streak = 0
        if (smooth < 0f) armed = true

        val wasRising = rising
        rising = smooth > prev
        if (wasRising && !rising && armed) {
            val peak = prev
            val threshold = max(minPeak, adaptiveFraction * peakAvg)
            val interval = if (lastStepT == Long.MIN_VALUE) Float.MAX_VALUE else (tNanos - lastStepT) / 1e9f
            if (peak >= threshold && interval >= minIntervalSec) {
                peakAvg = if (peakAvg == 0f) peak else 0.8f * peakAvg + 0.2f * peak
                armed = false
                lastStepT = tNanos
                streak++
                committed = when {
                    streak == streakToCommit -> streakToCommit
                    streak > streakToCommit -> 1
                    else -> 0
                }
            }
        }
        prev = smooth
        totalSteps += committed
        return committed
    }
}
