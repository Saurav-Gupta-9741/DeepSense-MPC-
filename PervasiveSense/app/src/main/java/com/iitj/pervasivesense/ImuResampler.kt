package com.iitj.pervasivesense

/**
 * Fuses the asynchronous accelerometer and gyroscope event streams into one
 * uniformly sampled 6-channel stream (default 50 Hz) using the hardware
 * SensorEvent timestamps.
 *
 * Android does not deliver sensors at exactly the requested rate: the actual
 * rate varies per device (often 50-60 Hz for a 20 ms request), events jitter,
 * and accelerometer/gyroscope events are not aligned. The DeepSense model was
 * trained on exactly 50 Hz data, so every grid point is produced by linear
 * interpolation of both streams at the same instant.
 *
 * A grid point is emitted only once both streams have data at or beyond it
 * (so both are interpolated, never extrapolated). If the gyroscope is absent
 * or stops reporting, the accelerometer alone drives the grid and the gyro
 * channels hold the last known value (zeros if none). When the device has a
 * gyroscope the grid waits up to [gyroStaleNanos] for its first event, so the
 * start of a session never feeds fake zero rotation into the model.
 *
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 */
class ImuResampler(
    val periodNanos: Long = 20_000_000L,
    private val maxGapNanos: Long = 250_000_000L,
    private val gyroStaleNanos: Long = 200_000_000L,
    /** True if the device has a gyroscope: the grid then waits for its first event. */
    private val expectGyro: Boolean = true,
    private val sink: (tNanos: Long, sample: FloatArray) -> Unit,
    private val onGap: (tNanos: Long) -> Unit = {}
) {
    private val acc = TimedRing(64)
    private val gyr = TimedRing(64)
    private var nextGridT = Long.MIN_VALUE
    private var streamStartT = Long.MIN_VALUE
    private val out = FloatArray(6)
    private val tmp = FloatArray(3)

    /** Exponential moving average of the device's real accelerometer rate (Hz). */
    var measuredAccelRateHz = 0f
        private set
    var hasGyroData = false
        private set

    fun onAccelerometer(tNanos: Long, x: Float, y: Float, z: Float) {
        if (!acc.isEmpty) {
            val dt = tNanos - acc.latestT
            if (dt <= 0) return // duplicate / out-of-order event
            if (dt > maxGapNanos) {
                // Sensor stall (e.g. re-registration, doze). Interpolating across it
                // would fabricate data, so restart the grid.
                acc.clear(); gyr.clear()
                nextGridT = Long.MIN_VALUE
                onGap(tNanos)
            } else {
                val hz = 1e9f / dt
                measuredAccelRateHz = if (measuredAccelRateHz == 0f) hz else 0.98f * measuredAccelRateHz + 0.02f * hz
            }
        }
        acc.add(tNanos, x, y, z)
        if (nextGridT == Long.MIN_VALUE) { nextGridT = tNanos; streamStartT = tNanos }
        drain()
    }

    fun onGyroscope(tNanos: Long, x: Float, y: Float, z: Float) {
        if (!gyr.isEmpty && tNanos <= gyr.latestT) return
        gyr.add(tNanos, x, y, z)
        hasGyroData = true
        drain()
    }

    fun reset() {
        acc.clear(); gyr.clear()
        nextGridT = Long.MIN_VALUE
        measuredAccelRateHz = 0f
    }

    private fun drain() {
        if (acc.isEmpty || nextGridT == Long.MIN_VALUE) return
        if (expectGyro && gyr.isEmpty && acc.latestT - streamStartT < gyroStaleNanos) return // gyro not started yet
        val gyroLive = !gyr.isEmpty && acc.latestT - gyr.latestT <= gyroStaleNanos
        val limit = if (gyroLive) minOf(acc.latestT, gyr.latestT) else acc.latestT
        while (nextGridT <= limit) {
            acc.interpolate(nextGridT, tmp)
            out[0] = tmp[0]; out[1] = tmp[1]; out[2] = tmp[2]
            if (gyr.isEmpty) {
                out[3] = 0f; out[4] = 0f; out[5] = 0f
            } else {
                gyr.interpolate(nextGridT, tmp)
                out[3] = tmp[0]; out[4] = tmp[1]; out[5] = tmp[2]
            }
            sink(nextGridT, out)
            nextGridT += periodNanos
        }
    }

    /** Fixed-capacity ring of timestamped 3-vectors, oldest overwritten first. */
    private class TimedRing(private val capacity: Int) {
        private val t = LongArray(capacity)
        private val v = FloatArray(capacity * 3)
        private var head = 0 // next write slot
        private var count = 0

        val isEmpty get() = count == 0
        val latestT get() = t[(head - 1 + capacity) % capacity]

        fun clear() { head = 0; count = 0 }

        fun add(time: Long, x: Float, y: Float, z: Float) {
            t[head] = time
            v[head * 3] = x; v[head * 3 + 1] = y; v[head * 3 + 2] = z
            head = (head + 1) % capacity
            if (count < capacity) count++
        }

        private fun slot(ageFromNewest: Int) = (head - 1 - ageFromNewest + 2 * capacity) % capacity

        /** Linear interpolation at [time]; clamps to the oldest/newest sample. */
        fun interpolate(time: Long, dst: FloatArray) {
            var newer = slot(0)
            if (time >= t[newer]) { copy(newer, dst); return }
            for (age in 1 until count) {
                val older = slot(age)
                if (t[older] <= time) {
                    val f = (time - t[older]).toFloat() / (t[newer] - t[older]).toFloat()
                    for (k in 0..2) dst[k] = v[older * 3 + k] + f * (v[newer * 3 + k] - v[older * 3 + k])
                    return
                }
                newer = older
            }
            copy(newer, dst) // older than everything retained
        }

        private fun copy(s: Int, dst: FloatArray) {
            dst[0] = v[s * 3]; dst[1] = v[s * 3 + 1]; dst[2] = v[s * 3 + 2]
        }
    }
}
