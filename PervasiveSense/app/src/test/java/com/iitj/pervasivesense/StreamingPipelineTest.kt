package com.iitj.pervasivesense

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ImuResamplerTest {

    private class Collector {
        val t = ArrayList<Long>()
        val v = ArrayList<FloatArray>()
        var gaps = 0
        fun resampler(expectGyro: Boolean = true) =
            ImuResampler(sink = { tn, s -> t += tn; v += s.copyOf() }, onGap = { gaps++ }, expectGyro = expectGyro)
    }

    @Test
    fun uniform50HzInputPassesThroughExactly() {
        val c = Collector(); val r = c.resampler()
        for (i in 0 until 100) {
            val t = i * 20_000_000L
            r.onGyroscope(t, i * 0.1f, 0f, 0f)
            r.onAccelerometer(t, i.toFloat(), 2f * i, 9.81f)
        }
        assertEquals(100, c.t.size)
        for (i in 0 until 100) {
            assertEquals(i * 20_000_000L, c.t[i])
            assertEquals(i.toFloat(), c.v[i][0], 1e-4f)
            assertEquals(i * 0.1f, c.v[i][3], 1e-4f)
        }
    }

    private fun resample(events: List<DeviceEvent>): Pair<Collector, ImuResampler> {
        val c = Collector(); val r = c.resampler()
        for (e in events) if (e.isAccel) r.onAccelerometer(e.t, e.x, e.y, e.z) else r.onGyroscope(e.t, e.x, e.y, e.z)
        return c to r
    }

    /** Independent reference: textbook linear interpolation of one sensor's own event list. */
    private fun reference(events: List<DeviceEvent>, t: Long): FloatArray {
        val k = events.indexOfFirst { it.t >= t }
        if (k <= 0) return events.first().let { floatArrayOf(it.x, it.y, it.z) }
        val a = events[k - 1]; val b = events[k]
        val f = (t - a.t).toFloat() / (b.t - a.t)
        return floatArrayOf(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), a.z + f * (b.z - a.z))
    }

    @Test
    fun realWalkingAtDeviceRatesMatchesIdealInterpolationOnExact50HzGrid() {
        val events = deviceEvents(Recording.fixture("walking_pocket"), accelHz = 57.3, gyroHz = 48.7, jitterMs = 2.0)
        val (c, r) = resample(events)
        // Exact 20 ms spacing, no gaps, full duration, real rate measured.
        for (i in 1 until c.t.size) assertEquals(20_000_000L, c.t[i] - c.t[i - 1])
        assertEquals(0, c.gaps)
        assertTrue("got ${c.t.size} samples", c.t.size >= 1495)
        assertEquals(57.3f, r.measuredAccelRateHz, 1.0f)
        // Both streams interpolated at the SAME instant, exactly as an ideal interpolator would.
        val acc = events.filter { it.isAccel }; val gyr = events.filter { !it.isAccel }
        for (i in c.t.indices) {
            val ra = reference(acc, c.t[i]); val rg = reference(gyr, c.t[i])
            for (k in 0..2) {
                assertEquals("accel t=${c.t[i]}", ra[k], c.v[i][k], 1e-3f)
                assertEquals("gyro t=${c.t[i]}", rg[k], c.v[i][k + 3], 1e-4f)
            }
        }
    }

    @Test
    fun sessionStartWaitsForGyroInsteadOfEmittingZeroRotation() {
        // Regression: the first grid points used to carry gyro = 0 when accel started first.
        val c = Collector(); val r = c.resampler()
        for (i in 0..4) r.onAccelerometer(i * 20_000_000L, 0f, 0f, 9.8f)
        assertEquals(0, c.t.size)
        r.onGyroscope(90_000_000L, 2f, 2f, 2f)
        assertTrue(c.t.isNotEmpty())
        assertTrue(c.v.all { it[3] == 2f })
    }

    @Test
    fun deviceWhoseGyroNeverStartsFallsBackToAccelOnlyAfter200ms() {
        val c = Collector(); val r = c.resampler()
        for (i in 0..20) r.onAccelerometer(i * 20_000_000L, 0f, 0f, 9.8f)
        assertEquals(21, c.t.size) // released once 200 ms passed with no gyro
    }

    @Test
    fun gridWaitsForGyroInsteadOfExtrapolating() {
        val c = Collector(); val r = c.resampler()
        r.onGyroscope(0, 1f, 1f, 1f)
        for (i in 0..10) r.onAccelerometer(i * 20_000_000L, 0f, 0f, 9.8f)
        // Gyro only covers t=0 but is "live" (within 200 ms): only t=0 may be emitted.
        assertEquals(1, c.t.size)
        r.onGyroscope(200_000_000L, 3f, 3f, 3f)
        assertEquals(11, c.t.size)
        assertEquals(2f, c.v[5][3], 1e-4f) // interpolated half-way at t=100 ms
    }

    @Test
    fun accelerometerOnlyDeviceStillProducesStreamWithZeroGyro() {
        val c = Collector()
        val r = ImuResampler(sink = { tn, s -> c.t += tn; c.v += s.copyOf() }, expectGyro = false)
        for (i in 0 until 50) r.onAccelerometer(i * 20_000_000L, 0f, 9.8f, 0f)
        assertEquals(50, c.t.size)
        assertTrue(c.v.all { it[3] == 0f && it[4] == 0f && it[5] == 0f })
        assertEquals(false, r.hasGyroData)
    }

    @Test
    fun sensorStallRestartsGridAndReportsGap() {
        val c = Collector(); val r = c.resampler(expectGyro = false)
        for (i in 0 until 10) r.onAccelerometer(i * 20_000_000L, 1f, 0f, 0f)
        r.onAccelerometer(10 * 20_000_000L + 1_000_000_000L, 5f, 0f, 0f)
        assertEquals(1, c.gaps)
        // Nothing interpolated across the 1 s hole.
        assertTrue(c.v.none { it[0] > 1.01f && it[0] < 4.99f })
    }

    @Test
    fun duplicateAndOutOfOrderEventsAreIgnored() {
        val c = Collector(); val r = c.resampler(expectGyro = false)
        r.onAccelerometer(0, 0f, 0f, 0f)
        r.onAccelerometer(40_000_000L, 2f, 0f, 0f)
        r.onAccelerometer(40_000_000L, 99f, 0f, 0f)
        r.onAccelerometer(30_000_000L, 99f, 0f, 0f)
        r.onAccelerometer(60_000_000L, 3f, 0f, 0f)
        assertTrue(c.v.none { it[0] > 10f })
        assertEquals(4, c.t.size)
    }
}

class SlidingWindowTest {
    @Test
    fun copiesOldestFirstAcrossWrapAround() {
        val w = SlidingWindow(4, 2)
        for (i in 1..6) w.push(floatArrayOf(i.toFloat(), -i.toFloat()))
        val dst = FloatArray(8)
        w.copyChronological(dst)
        assertArrayEquals(floatArrayOf(3f, -3f, 4f, -4f, 5f, -5f, 6f, -6f), dst, 0f)
        assertTrue(w.isFull)
    }

    @Test
    fun clearResetsFill() {
        val w = SlidingWindow(4, 1)
        repeat(4) { w.push(floatArrayOf(1f)) }
        w.clear()
        assertEquals(0, w.filled)
        assertEquals(false, w.isFull)
    }
}

class ActivitySmootherTest {
    private val still = floatArrayOf(0.9f, 0.05f, 0.05f)
    private val walk = floatArrayOf(0.05f, 0.9f, 0.05f)
    private val unsure = floatArrayOf(0.34f, 0.33f, 0.33f)

    @Test
    fun firstConfidentDecisionIsImmediate() {
        val s = ActivitySmoother(3)
        assertEquals(0, s.update(still))
    }

    @Test
    fun singleFlickerWindowDoesNotSwitch() {
        val s = ActivitySmoother(3)
        repeat(5) { s.update(still) }
        s.update(walk)
        assertEquals(0, s.update(still))
    }

    @Test
    fun sustainedChangeSwitchesWithinThreeInferences() {
        val s = ActivitySmoother(3)
        repeat(5) { s.update(still) }
        val seq = (1..3).map { s.update(walk) }
        assertEquals(1, seq.last())
        assertEquals("switched too early", 0, seq.first())
    }

    @Test
    fun ambiguousInputNeverProducesALabel() {
        val s = ActivitySmoother(3)
        repeat(10) { assertEquals(-1, s.update(unsure)) }
    }
}
