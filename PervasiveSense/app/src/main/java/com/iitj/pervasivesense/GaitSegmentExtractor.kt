package com.iitj.pervasivesense

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Personal gait descriptors used by the fit-to-drive check, with the direction
 * in which alcohol-like impairment moves each one: +1 = rises, -1 = falls,
 * 0 = either way.
 */
enum class GaitFeature(val label: String, val direction: Int) {
    CADENCE("cadence", 0),
    STRIDE_TIME_VARIABILITY("step timing", +1),
    STRIDE_REGULARITY("stride regularity", -1),
    SWAY("side-to-side sway", +1),
    SWAY_IRREGULARITY("irregular sway", +1),
    TURN_UNSTEADINESS("turning steadiness", +1);

    companion object {
        val COUNT = entries.size
    }
}

/** One analysed stretch of walking; [values] is indexed by [GaitFeature.ordinal]. */
class GaitSegment(val tEndNanos: Long, val values: FloatArray)

/**
 * Cuts continuous walking into overlapping segments ([segmentSamples] long, a
 * new one every [hopSamples]) and describes each with [GaitFeature]s.
 *
 * Every feature is independent of how the phone sits in the pocket.
 *
 * Gait timing comes from the leg swing, not from step peaks: in a trouser
 * pocket the thigh rotates the phone forward and back exactly once per
 * stride, so the principal axis of the (low-passed) gyroscope is a clean
 * stride-periodic signal. (Without a gyroscope the same swing tilts gravity,
 * so the low-passed accelerometer's principal axis is used.) Its
 * autocorrelation gives the stride time; its peaks, timed to a fraction of a
 * sample, give the stride-to-stride timing variability.
 *
 * The segment's mean acceleration is gravity (dynamic acceleration averages
 * out over whole strides), which splits each sample into a vertical component
 * and a horizontal plane. In that plane the dominant axis is forward-backward
 * (heel-strike braking and push-off) and the weaker, perpendicular axis is
 * side-to-side, where alcohol-related sway shows most strongly.
 *
 *  - Stride regularity: vertical autocorrelation at one stride.
 *  - Sway: side-to-side RMS relative to vertical RMS (walking-intensity free).
 *  - Irregular sway / turning steadiness: stride-to-stride change of the
 *    side-to-side acceleration and of the turning rate (gyro about gravity).
 *    A steady gait repeats every stride, so the periodic leg swing and slow,
 *    deliberate turns cancel and only aperiodic staggering remains.
 *
 * Single-threaded; per-sample work is a copy, analysis runs once per hop on
 * preallocated arrays. No Android types.
 */
class GaitSegmentExtractor(
    val segmentSamples: Int = 400, // 8 s
    val hopSamples: Int = 200,     // a new segment every 4 s of walking
    private val sampleRateHz: Float = 50f,
    private val minStrideSec: Float = 0.7f,
    private val maxStrideSec: Float = 2.0f,
    private val minPeriodicity: Float = 0.2f,
    private val maxGapNanos: Long = 100_000_000L
) {
    private val buf = FloatArray(segmentSamples * 6)
    private var head = 0
    private var filled = 0
    private var sinceEmit = 0
    private var lastT = Long.MIN_VALUE

    // Work arrays for the analysis, allocated once.
    private val win = FloatArray(segmentSamples * 6)
    private val vert = FloatArray(segmentSamples)
    private val side = FloatArray(segmentSamples)
    private val yaw = FloatArray(segmentSamples)
    private val h1 = FloatArray(segmentSamples)
    private val h2 = FloatArray(segmentSamples)
    private val swing = FloatArray(segmentSamples)
    private val smooth = FloatArray(segmentSamples * 3)
    private val scratch = FloatArray(segmentSamples)
    private val acf = FloatArray(segmentSamples / 2 + 2)
    private val peaks = FloatArray(segmentSamples / 10)

    fun reset() {
        head = 0; filled = 0; sinceEmit = 0
    }

    /**
     * Feeds one uniform 50 Hz sample. [walking] gates collection (any other
     * activity restarts the segment). Returns a segment when one completes and
     * passes the quality checks.
     */
    fun processSample(tNanos: Long, s: FloatArray, walking: Boolean, hasGyro: Boolean): GaitSegment? {
        // A segment never spans missing data.
        if (lastT != Long.MIN_VALUE && tNanos - lastT > maxGapNanos) reset()
        lastT = tNanos
        if (!walking) {
            if (filled > 0) reset()
            return null
        }
        System.arraycopy(s, 0, buf, head * 6, 6)
        head = (head + 1) % segmentSamples
        if (filled < segmentSamples) filled++
        sinceEmit++
        if (filled < segmentSamples || sinceEmit < hopSamples) return null
        sinceEmit = 0
        return analyse(tNanos, hasGyro)
    }

    private fun analyse(tEnd: Long, hasGyro: Boolean): GaitSegment? {
        val n = segmentSamples
        val first = (n - head) * 6
        System.arraycopy(buf, head * 6, win, 0, first)
        System.arraycopy(buf, 0, win, first, head * 6)

        // ---- Gravity = mean acceleration; vertical / horizontal decomposition.
        var gx = 0.0; var gy = 0.0; var gz = 0.0
        for (i in 0 until n) { gx += win[i * 6]; gy += win[i * 6 + 1]; gz += win[i * 6 + 2] }
        gx /= n; gy /= n; gz /= n
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 7.0 || gn > 13.0) return null
        val ux = (gx / gn).toFloat(); val uy = (gy / gn).toFloat(); val uz = (gz / gn).toFloat()
        val ref = when {
            abs(ux) <= abs(uy) && abs(ux) <= abs(uz) -> floatArrayOf(1f, 0f, 0f)
            abs(uy) <= abs(uz) -> floatArrayOf(0f, 1f, 0f)
            else -> floatArrayOf(0f, 0f, 1f)
        }
        var e1x = uy * ref[2] - uz * ref[1]; var e1y = uz * ref[0] - ux * ref[2]; var e1z = ux * ref[1] - uy * ref[0]
        val e1n = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        e1x /= e1n; e1y /= e1n; e1z /= e1n
        val e2x = uy * e1z - uz * e1y; val e2y = uz * e1x - ux * e1z; val e2z = ux * e1y - uy * e1x

        val g = gn.toFloat()
        var vMean = 0.0; var h1Mean = 0.0; var h2Mean = 0.0
        for (i in 0 until n) {
            val ax = win[i * 6]; val ay = win[i * 6 + 1]; val az = win[i * 6 + 2]
            vert[i] = ax * ux + ay * uy + az * uz - g
            h1[i] = ax * e1x + ay * e1y + az * e1z
            h2[i] = ax * e2x + ay * e2y + az * e2z
            yaw[i] = win[i * 6 + 3] * ux + win[i * 6 + 4] * uy + win[i * 6 + 5] * uz
            vMean += vert[i]; h1Mean += h1[i]; h2Mean += h2[i]
        }
        vMean /= n; h1Mean /= n; h2Mean /= n
        var vv = 0.0; var s11 = 0.0; var s22 = 0.0; var s12 = 0.0
        for (i in 0 until n) {
            vert[i] -= vMean.toFloat(); h1[i] -= h1Mean.toFloat(); h2[i] -= h2Mean.toFloat()
            vv += vert[i] * vert[i]; s11 += h1[i] * h1[i]; s22 += h2[i] * h2[i]; s12 += h1[i] * h2[i]
        }
        val vRms = sqrt(vv / n)
        if (vRms < 0.5) return null // no real gait in this segment
        s11 /= n; s22 /= n; s12 /= n

        // Side-to-side axis = minor principal axis of the horizontal plane.
        val tr = s11 + s22
        val disc = sqrt((s11 - s22) * (s11 - s22) + 4 * s12 * s12)
        val lambdaMin = ((tr - disc) / 2).coerceAtLeast(0.0)
        var sx: Double; var sy: Double
        if (abs(s12) > 1e-12) { sx = s12; sy = lambdaMin - s11 } else if (s11 <= s22) { sx = 1.0; sy = 0.0 } else { sx = 0.0; sy = 1.0 }
        val sn = sqrt(sx * sx + sy * sy)
        sx /= sn; sy /= sn
        for (i in 0 until n) side[i] = (h1[i] * sx + h2[i] * sy).toFloat()

        // ---- Leg-swing signal: principal axis of the low-passed gyro (or accel).
        buildSwingSignal(if (hasGyro) 3 else 0)

        // Stride time = first strong autocorrelation peak of the swing signal.
        val minLag = (minStrideSec * sampleRateHz).roundToInt()
        val maxLag = (maxStrideSec * sampleRateHz).roundToInt().coerceAtMost(n / 2)
        var bestR = -1f
        val energy = energyOf(swing)
        for (l in minLag - 1..maxLag + 1) acf[l] = autocorr(swing, l, energy)
        for (l in minLag..maxLag) bestR = maxOf(bestR, acf[l])
        if (bestR < minPeriodicity) return null // not a periodic gait
        var lag = -1
        for (l in minLag..maxLag) {
            if (acf[l] >= 0.85f * bestR && acf[l] >= acf[l - 1] && acf[l] >= acf[l + 1]) { lag = l; break }
        }
        if (lag < 0) return null

        // Stride-to-stride timing from the swing's peaks AND troughs (the axis sign is
        // arbitrary); the cleaner polarity is kept, which is orientation-independent.
        val cvUp = strideCv(lag, +1f)
        val cvDown = strideCv(lag, -1f)
        val strideCv = when {
            cvUp.isNaN() -> cvDown
            cvDown.isNaN() -> cvUp
            else -> minOf(cvUp, cvDown)
        }
        if (strideCv.isNaN()) return null

        // Whole-body regularity: vertical autocorrelation at one stride (local max near the lag).
        var vReg = -1f
        val vEnergy = energyOf(vert)
        for (l in (lag * 0.9f).roundToInt()..(lag * 1.1f).roundToInt().coerceAtMost(n / 2)) vReg = maxOf(vReg, autocorr(vert, l, vEnergy))

        var sideDiff = 0.0; var yawDiff = 0.0
        for (i in lag until n) {
            val ds = side[i] - side[i - lag]; sideDiff += ds * ds
            val dy = yaw[i] - yaw[i - lag]; yawDiff += dy * dy
        }
        val m = n - lag
        val values = FloatArray(GaitFeature.COUNT)
        values[GaitFeature.CADENCE.ordinal] = (120.0 * sampleRateHz / lag).toFloat()
        values[GaitFeature.STRIDE_TIME_VARIABILITY.ordinal] = strideCv
        values[GaitFeature.STRIDE_REGULARITY.ordinal] = vReg
        values[GaitFeature.SWAY.ordinal] = (sqrt(lambdaMin) / vRms).toFloat()
        values[GaitFeature.SWAY_IRREGULARITY.ordinal] = (sqrt(sideDiff / (2 * m)) / vRms).toFloat()
        values[GaitFeature.TURN_UNSTEADINESS.ordinal] = if (hasGyro) sqrt(yawDiff / (2 * m)).toFloat() else Float.NaN
        return GaitSegment(tEnd, values)
    }

    /**
     * Coefficient of variation of the intervals between successive swing peaks
     * ([sign] = +1) or troughs (-1), timed to a fraction of a sample by
     * parabolic interpolation. NaN if fewer than 3 plausible cycles.
     */
    private fun strideCv(lag: Int, sign: Float): Float {
        val n = segmentSamples
        var np = 0
        var lastPeak = -1e9f
        var lastValue = 0f
        for (i in 1 until n - 1) {
            val a = sign * swing[i - 1]; val b = sign * swing[i]; val c = sign * swing[i + 1]
            if (b <= 0f || b < a || b <= c) continue
            val denom = a - 2 * b + c
            val pos = i + if (denom != 0f) 0.5f * (a - c) / denom else 0f
            if (pos - lastPeak >= 0.6f * lag) {
                if (np < peaks.size) peaks[np++] = pos
                lastPeak = pos; lastValue = b
            } else if (b > lastValue && np > 0) {
                peaks[np - 1] = pos; lastPeak = pos; lastValue = b // keep the higher of two close peaks
            }
        }
        var cycles = 0; var sum = 0.0; var sumSq = 0.0
        for (k in 1 until np) {
            val d = peaks[k] - peaks[k - 1]
            if (d in 0.6f * lag..1.5f * lag) { cycles++; sum += d; sumSq += d * d }
        }
        if (cycles < 3) return Float.NaN
        val mean = sum / cycles
        return (sqrt((sumSq / cycles - mean * mean).coerceAtLeast(0.0)) / mean).toFloat()
    }

    /** Low-pass (two passes of a 0.18 s moving average) channels [ch, ch+3), project on the principal axis. */
    private fun buildSwingSignal(ch: Int) {
        val n = segmentSamples
        val half = 4
        for (c in 0..2) {
            for (pass in 0..1) {
                var acc = 0.0
                var lo = 0; var hi = -1
                for (i in 0 until n) {
                    val from = (i - half).coerceAtLeast(0); val to = (i + half).coerceAtMost(n - 1)
                    while (hi < to) { hi++; acc += if (pass == 0) win[hi * 6 + ch + c] else scratch[hi] }
                    while (lo < from) { acc -= if (pass == 0) win[lo * 6 + ch + c] else scratch[lo]; lo++ }
                    swing[i] = (acc / (to - from + 1)).toFloat()
                }
                if (pass == 0) swing.copyInto(scratch)
            }
            var mean = 0.0
            for (i in 0 until n) mean += swing[i]
            mean /= n
            for (i in 0 until n) smooth[i * 3 + c] = swing[i] - mean.toFloat()
        }
        // Principal axis by power iteration on the 3x3 covariance.
        val cov = DoubleArray(9)
        for (i in 0 until n) for (a in 0..2) for (b in 0..2) cov[a * 3 + b] += (smooth[i * 3 + a] * smooth[i * 3 + b]).toDouble()
        var px = 1.0; var py = 1.0; var pz = 1.0
        repeat(30) {
            val qx = cov[0] * px + cov[1] * py + cov[2] * pz
            val qy = cov[3] * px + cov[4] * py + cov[5] * pz
            val qz = cov[6] * px + cov[7] * py + cov[8] * pz
            val qn = sqrt(qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-12)
            px = qx / qn; py = qy / qn; pz = qz / qn
        }
        for (i in 0 until n) swing[i] = (smooth[i * 3] * px + smooth[i * 3 + 1] * py + smooth[i * 3 + 2] * pz).toFloat()
    }

    private fun energyOf(x: FloatArray): Double {
        var e = 0.0
        for (i in 0 until segmentSamples) e += x[i] * x[i]
        return e
    }

    /** Normalised (unbiased) autocorrelation of a zero-mean signal with total [energy] at [lag]. */
    private fun autocorr(x: FloatArray, lag: Int, energy: Double): Float {
        val n = segmentSamples
        if (lag <= 0 || lag >= n || energy <= 0.0) return 0f
        var acc = 0.0
        for (i in 0 until n - lag) acc += x[i] * x[i + lag]
        return ((acc / (n - lag)) / (energy / n)).toFloat()
    }
}
