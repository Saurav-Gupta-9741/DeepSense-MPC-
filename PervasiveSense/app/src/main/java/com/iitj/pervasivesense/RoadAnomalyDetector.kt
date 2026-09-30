package com.iitj.pervasivesense

import kotlin.math.exp
import kotlin.math.sqrt

data class RoadAnomalyEvent(
    val type: String, // "POTHOLE" or "SPEED_BREAKER"
    val timestampNanos: Long,
    val peakToPeakG: Float
)

/**
 * Vertical-shock road anomaly detector, active only while in a vehicle.
 *
 * The raw z-axis is only vertical when the phone lies flat, so every 50 Hz
 * sample is projected onto the current gravity direction (low-pass estimate):
 *     v = a · ĝ − |g|      (vertical dynamic acceleration, m/s²)
 * which works in any pocket/mount orientation. v is then low-passed (~5 Hz)
 * because road shocks are low-frequency while engine vibration is broadband.
 *
 * Over a sliding [windowSec] window:
 *   - POTHOLE: the wheel drops first (sharp dip below −[potholeDipG]) and then
 *     rebounds; peak-to-peak ≥ [potholeP2pG].
 *   - SPEED_BREAKER: the vehicle rises first (≥ [breakerRiseG]) then settles,
 *     with a moderate peak-to-peak in [[breakerMinP2pG], [potholeP2pG]).
 * Events are debounced by [debounceSec]. Thresholds follow the magnitudes
 * reported for smartphone road sensing (Pothole Patrol, Nericell) and should
 * be field-calibrated per vehicle for publication-grade results.
 */
class RoadAnomalyDetector(
    private val windowSec: Float = 0.5f,
    private val gravityTauSec: Float = 1.0f,
    private val shockTauSec: Float = 0.03f,
    private val potholeDipG: Float = 0.4f,
    private val potholeP2pG: Float = 0.8f,
    private val breakerRiseG: Float = 0.25f,
    private val breakerMinP2pG: Float = 0.4f,
    private val debounceSec: Float = 1.5f,
    private val sampleRateHz: Int = 50
) {
    private val g0 = 9.80665f
    private val n = (windowSec * sampleRateHz).toInt()
    private val v = FloatArray(n)
    private var head = 0
    private var count = 0
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var lastT = 0L
    private var lastEventT = Long.MIN_VALUE
    private var vSmooth = 0f

    var potholesCount = 0
        private set
    var speedBreakersCount = 0
        private set

    fun restoreCounts(potholes: Int, breakers: Int) {
        potholesCount = potholes; speedBreakersCount = breakers
    }

    fun processSample(tNanos: Long, ax: Float, ay: Float, az: Float, isVehicular: Boolean): RoadAnomalyEvent? {
        if (!hasGravity) {
            gravity[0] = ax; gravity[1] = ay; gravity[2] = az; hasGravity = true
        } else {
            val a = exp(-((tNanos - lastT).coerceAtLeast(0L) / 1e9f) / gravityTauSec)
            gravity[0] = a * gravity[0] + (1 - a) * ax
            gravity[1] = a * gravity[1] + (1 - a) * ay
            gravity[2] = a * gravity[2] + (1 - a) * az
        }
        val dtSec = (tNanos - lastT).coerceAtLeast(0L) / 1e9f
        lastT = tNanos
        if (!isVehicular) { count = 0; head = 0; vSmooth = 0f; return null }

        val gn = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gn < 1e-3f) return null
        // ~5 Hz low-pass: keeps pothole/breaker shocks, rejects broadband engine vibration.
        val raw = (ax * gravity[0] + ay * gravity[1] + az * gravity[2]) / gn - gn
        val b = exp(-dtSec / shockTauSec)
        vSmooth = b * vSmooth + (1 - b) * raw
        v[head] = vSmooth
        head = (head + 1) % n
        if (count < n) { count++; return null }

        if (lastEventT != Long.MIN_VALUE && tNanos - lastEventT < (debounceSec * 1e9f).toLong()) return null

        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        var minAge = 0; var maxAge = 0 // age 0 = newest sample
        for (age in 0 until n) {
            val x = v[(head - 1 - age + n) % n]
            if (x < minV) { minV = x; minAge = age }
            if (x > maxV) { maxV = x; maxAge = age }
        }
        val p2p = (maxV - minV) / g0
        val dipFirst = minAge > maxAge   // the minimum is older -> dip happened first
        val event = when {
            dipFirst && minV < -potholeDipG * g0 && p2p >= potholeP2pG -> "POTHOLE".also { potholesCount++ }
            !dipFirst && maxV > breakerRiseG * g0 && p2p >= breakerMinP2pG && p2p < potholeP2pG ->
                "SPEED_BREAKER".also { speedBreakersCount++ }
            else -> null
        } ?: return null
        lastEventT = tNanos
        count = 0; head = 0 // do not re-detect the same shock
        return RoadAnomalyEvent(event, tNanos, p2p)
    }
}
