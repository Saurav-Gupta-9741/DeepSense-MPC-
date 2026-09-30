package com.iitj.pervasivesense

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A uniformly sampled 6-channel recording (m/s², rad/s) at 50 Hz. */
class Recording(val t: LongArray, val data: Array<FloatArray>) {
    val size get() = t.size
    val durationSec get() = size / 50.0

    fun rotated(r: Array<FloatArray>): Recording = Recording(t.copyOf(), Array(size) { i ->
        val s = data[i]
        floatArrayOf(
            r[0][0] * s[0] + r[0][1] * s[1] + r[0][2] * s[2],
            r[1][0] * s[0] + r[1][1] * s[1] + r[1][2] * s[2],
            r[2][0] * s[0] + r[2][1] * s[1] + r[2][2] * s[2],
            r[0][0] * s[3] + r[0][1] * s[4] + r[0][2] * s[5],
            r[1][0] * s[3] + r[1][1] * s[4] + r[1][2] * s[5],
            r[2][0] * s[3] + r[2][1] * s[4] + r[2][2] * s[5]
        )
    })

    /** Linear interpolation of channel values at an arbitrary time (clamped). */
    fun at(tNanos: Long, dst: FloatArray) {
        val pos = (tNanos - t[0]).toDouble() / 20_000_000.0
        val i = pos.toInt().coerceIn(0, size - 1)
        val j = (i + 1).coerceAtMost(size - 1)
        val f = (pos - i).toFloat().coerceIn(0f, 1f)
        for (c in 0..5) dst[c] = data[i][c] + f * (data[j][c] - data[i][c])
    }

    companion object {
        fun fixture(name: String): Recording {
            val stream = Recording::class.java.classLoader!!.getResourceAsStream("fixtures/$name.csv")
                ?: error("missing fixture $name")
            val rows = stream.bufferedReader().readLines().drop(1).filter { it.isNotBlank() }
            val t = LongArray(rows.size)
            val d = Array(rows.size) { FloatArray(6) }
            rows.forEachIndexed { i, line ->
                val p = line.split(',')
                t[i] = p[0].toLong()
                for (c in 0..5) d[i][c] = p[c + 1].toFloat()
            }
            return Recording(t, d)
        }

        fun referenceStepsPerSecond(name: String): Double {
            val json = Recording::class.java.classLoader!!.getResourceAsStream("fixtures/fixtures.json")!!
                .bufferedReader().readText()
            val block = Regex("\"$name\"\\s*:\\s*\\{([^}]*)\\}").find(json)!!.groupValues[1]
            return Regex("\"reference_steps_per_second\"\\s*:\\s*([0-9.]+)").find(block)!!.groupValues[1].toDouble()
        }

        /** Concatenates recordings into one continuous stream (timestamps re-based). */
        fun concat(vararg parts: Recording): Recording {
            val total = parts.sumOf { it.size }
            val t = LongArray(total) { it * 20_000_000L }
            val d = ArrayList<FloatArray>(total)
            parts.forEach { p -> p.data.forEach { d.add(it.copyOf()) } }
            return Recording(t, d.toTypedArray())
        }

        fun constant(seconds: Double, ax: Float, ay: Float, az: Float, noise: Float = 0f, seed: Int = 1): Recording {
            val rnd = Random(seed)
            val n = (seconds * 50).toInt()
            return Recording(LongArray(n) { it * 20_000_000L }, Array(n) {
                floatArrayOf(
                    ax + noise * rnd.nextGaussian(), ay + noise * rnd.nextGaussian(), az + noise * rnd.nextGaussian(),
                    0.01f * rnd.nextGaussian(), 0.01f * rnd.nextGaussian(), 0.01f * rnd.nextGaussian()
                )
            })
        }

        /** Rotation matrix from Z-Y-X Euler angles in degrees. */
        fun rotation(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Array<FloatArray> {
            val (y, p, r) = listOf(yawDeg, pitchDeg, rollDeg).map { Math.toRadians(it) }
            val cy = cos(y); val sy = sin(y); val cp = cos(p); val sp = sin(p); val cr = cos(r); val sr = sin(r)
            return arrayOf(
                floatArrayOf((cy * cp).toFloat(), (cy * sp * sr - sy * cr).toFloat(), (cy * sp * cr + sy * sr).toFloat()),
                floatArrayOf((sy * cp).toFloat(), (sy * sp * sr + cy * cr).toFloat(), (sy * sp * cr - cy * sr).toFloat()),
                floatArrayOf((-sp).toFloat(), (cp * sr).toFloat(), (cp * cr).toFloat())
            )
        }
    }
}

fun Random.nextGaussian(): Float {
    // Box-Muller
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)).toFloat()
}

/** A sensor event as Android would deliver it. */
data class DeviceEvent(val isAccel: Boolean, val t: Long, val x: Float, val y: Float, val z: Float)

/**
 * Re-emits a recording the way a real phone delivers it: accelerometer and
 * gyroscope as separate event streams at their own (non-50 Hz) rates, with
 * timestamp jitter, merged in delivery order.
 */
fun deviceEvents(
    rec: Recording,
    accelHz: Double = 57.3,
    gyroHz: Double = 48.7,
    jitterMs: Double = 2.0,
    gyroOffsetMs: Double = 3.0,
    seed: Int = 7,
    withGyro: Boolean = true,
    startNanos: Long = 1_000_000_000L
): List<DeviceEvent> {
    val rnd = Random(seed)
    val end = rec.t.last()
    val out = ArrayList<DeviceEvent>()
    val v = FloatArray(6)
    fun stream(hz: Double, offsetMs: Double, accel: Boolean) {
        var k = 0
        while (true) {
            val nominal = (k / hz * 1e9 + offsetMs * 1e6).toLong()
            val jitter = ((rnd.nextDouble() * 2 - 1) * jitterMs * 1e6).toLong()
            val t = (nominal + jitter).coerceAtLeast(0L)
            if (t > end) break
            rec.at(rec.t[0] + t, v)
            out += if (accel) DeviceEvent(true, startNanos + t, v[0], v[1], v[2])
                   else DeviceEvent(false, startNanos + t, v[3], v[4], v[5])
            k++
        }
    }
    stream(accelHz, 0.0, true)
    if (withGyro) stream(gyroHz, gyroOffsetMs, false)
    out.sortBy { it.t }
    // Jitter can reorder neighbouring events of one sensor; Android timestamps are monotonic per sensor.
    return out
}
