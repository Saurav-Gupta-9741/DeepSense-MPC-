package com.iitj.pervasivesense

import kotlin.math.acos
import kotlin.math.sqrt

/** Postural sway measured during one balance test. */
class BalanceMeasurement(
    /** RMS horizontal acceleration about the mean, m/s². */
    val swayAccel: Float,
    /** RMS angular velocity about the mean, rad/s (NaN without gyroscope). */
    val swayGyro: Float,
    /** The person stepped, walked off or put the phone away: not a valid stance. */
    val moved: Boolean
)

/**
 * 30-second standing balance test: feet together, eyes open, phone held flat
 * against the chest with both hands. (Eyes open on purpose: closing them is
 * the more sensitive Romberg variant, but unsafe for someone who may be
 * impaired.)
 *
 * The first [settleSec] are ignored (getting into position). Over the rest,
 * sway is the RMS of the horizontal acceleration (gravity removed by
 * projection, so phone orientation does not matter) and of the angular
 * velocity. A large orientation change between the two halves, or a
 * walking-sized acceleration, marks the attempt as moved.
 */
class BalanceTest(
    val durationSec: Float = DURATION_SEC,
    private val settleSec: Float = SETTLE_SEC,
    private val maxTiltChangeDeg: Float = 25f,
    private val maxDynamicAccel: Float = 4f,
    private val maxSwayAccel: Float = 1.0f,
    private val sampleRateHz: Float = 50f
) {
    companion object {
        const val DURATION_SEC = 30f
        const val SETTLE_SEC = 5f
    }

    private val capacity = ((durationSec - settleSec) * sampleRateHz).toInt() + 64
    private val data = FloatArray(capacity * 6)
    private var count = 0
    private var startT = Long.MIN_VALUE

    val running get() = startT != Long.MIN_VALUE

    fun start(tNanos: Long) { startT = tNanos; count = 0 }

    fun cancel() { startT = Long.MIN_VALUE; count = 0 }

    fun progress(tNanos: Long): Float =
        if (!running) 0f else ((tNanos - startT) / 1e9f / durationSec).coerceIn(0f, 1f)

    fun secondsLeft(tNanos: Long): Int =
        if (!running) 0 else kotlin.math.ceil(durationSec - (tNanos - startT) / 1e9f).toInt().coerceAtLeast(0)

    /** Returns the measurement on the sample that completes the test. */
    fun processSample(tNanos: Long, s: FloatArray, hasGyro: Boolean): BalanceMeasurement? {
        if (!running) return null
        val elapsed = (tNanos - startT) / 1e9f
        if (elapsed >= settleSec && count < capacity) {
            System.arraycopy(s, 0, data, count * 6, 6)
            count++
        }
        if (elapsed < durationSec) return null
        startT = Long.MIN_VALUE
        return measure(hasGyro)
    }

    private fun measure(hasGyro: Boolean): BalanceMeasurement {
        val n = count
        if (n < sampleRateHz * 5) return BalanceMeasurement(Float.NaN, Float.NaN, moved = true) // sensor gap
        val half = n / 2
        val g1 = mean(0, half, 0); val g2 = mean(half, n, 0); val g = mean(0, n, 0)
        val tilt = angleDeg(g1, g2)
        val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        val ux = g[0] / gn; val uy = g[1] / gn; val uz = g[2] / gn

        // Horizontal acceleration (orientation-free), then RMS about its mean.
        var hx = 0.0; var hy = 0.0; var hz = 0.0
        var maxDyn = 0f
        for (i in 0 until n) {
            val ax = data[i * 6]; val ay = data[i * 6 + 1]; val az = data[i * 6 + 2]
            val p = ax * ux + ay * uy + az * uz
            hx += ax - p * ux; hy += ay - p * uy; hz += az - p * uz
            val dx = ax - g[0]; val dy = ay - g[1]; val dz = az - g[2]
            val dyn = sqrt(dx * dx + dy * dy + dz * dz)
            if (dyn > maxDyn) maxDyn = dyn
        }
        hx /= n; hy /= n; hz /= n
        var acc = 0.0
        for (i in 0 until n) {
            val ax = data[i * 6]; val ay = data[i * 6 + 1]; val az = data[i * 6 + 2]
            val p = ax * ux + ay * uy + az * uz
            val x = ax - p * ux - hx; val y = ay - p * uy - hy; val z = az - p * uz - hz
            acc += x * x + y * y + z * z
        }
        val swayAccel = sqrt(acc / n).toFloat()

        val swayGyro = if (!hasGyro) Float.NaN else {
            val w = mean(0, n, 3)
            var sw = 0.0
            for (i in 0 until n) {
                val x = data[i * 6 + 3] - w[0]; val y = data[i * 6 + 4] - w[1]; val z = data[i * 6 + 5] - w[2]
                sw += x * x + y * y + z * z
            }
            sqrt(sw / n).toFloat()
        }
        val moved = tilt > maxTiltChangeDeg || maxDyn > maxDynamicAccel || swayAccel > maxSwayAccel
        return BalanceMeasurement(swayAccel, swayGyro, moved)
    }

    private fun mean(from: Int, to: Int, ch: Int): FloatArray {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (i in from until to) { x += data[i * 6 + ch]; y += data[i * 6 + ch + 1]; z += data[i * 6 + ch + 2] }
        val n = (to - from).coerceAtLeast(1)
        return floatArrayOf((x / n).toFloat(), (y / n).toFloat(), (z / n).toFloat())
    }

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-6f || nb < 1e-6f) return 0f
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }
}
