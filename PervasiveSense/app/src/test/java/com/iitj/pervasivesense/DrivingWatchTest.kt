package com.iitj.pervasivesense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * A drive in the car frame (x forward, y left, z up) at 50 Hz: turn rate [yaw] (rad/s),
 * forward acceleration [fwd] (m/s²), lateral acceleration = speed × turn rate, plus seat
 * vibration, gyro noise and the driver's leg moving on the pedals (rotation about a
 * horizontal axis, which must not look like steering).
 */
fun drive(seconds: Double, seed: Int = 1, speed: Double = 8.0,
          fwd: (Double) -> Double = { 0.0 }, yaw: (Double) -> Double): Recording {
    val rnd = Random(seed.toLong())
    val n = (seconds * 50).toInt()
    val t = LongArray(n) { it * 20_000_000L }
    val data = Array(n) { i ->
        val ts = i * 0.02
        val w = yaw(ts)
        // A pedal movement every 7 s: 0.4 s of leg rotation about the car's y axis.
        val pedal = if ((ts % 7.0) < 0.4) 0.6 * sin(PI * (ts % 7.0) / 0.4) else 0.0
        floatArrayOf(
            (fwd(ts) + rnd.nextGaussian() * 0.3).toFloat(),
            (speed * w + rnd.nextGaussian() * 0.3).toFloat(),
            (9.81 + rnd.nextGaussian() * 0.4).toFloat(),
            (rnd.nextGaussian() * 0.01).toFloat(),
            (pedal + rnd.nextGaussian() * 0.01).toFloat(),
            (w + rnd.nextGaussian() * 0.01).toFloat())
    }
    return Recording(t, data)
}

/** A smooth bump from 0 to [peak] and back over [duration] s, starting at [start]. */
fun bump(ts: Double, start: Double, duration: Double, peak: Double): Double {
    if (ts < start || ts > start + duration) return 0.0
    val x = sin(PI * (ts - start) / duration)
    return peak * x * x
}

/** Ordinary city driving, 5 min: alternating 90° turns, lane changes, normal stops. */
fun cityDrive(seed: Int = 1) = drive(300.0, seed, fwd = { ts ->
    // A normal stop every 40 s: -2.5 m/s² for 3 s, then pulling away at +1.5 m/s².
    -bump(ts % 40.0, 30.0, 3.0, 2.5) + bump(ts % 40.0, 35.0, 4.0, 1.5)
}) { ts ->
    val k = (ts / 25.0).toInt()
    val phase = ts % 25.0
    // A 90° turn (≈ 0.3 rad/s peak over 10 s ≈ π/2) every 25 s, alternating left and right...
    val turn = bump(phase, 5.0, 10.5, if (k % 2 == 0) 0.3 else -0.3)
    // ...and a lane change (left, then right back) in between.
    val lane = bump(phase, 18.0, 2.0, 0.06) - bump(phase, 20.0, 2.0, 0.06)
    turn + lane
}

/** Weaving: left-right swings of [peak] rad/s at [hz] (lane to lane), for [seconds]. */
fun weavingDrive(seconds: Double = 60.0, peak: Double = 0.09, hz: Double = 0.2, seed: Int = 2) =
    drive(seconds, seed) { ts -> peak * sin(2 * PI * hz * ts) }

class DrivingWatchTest {

    private fun run(w: DrivingWatch, rec: Recording, t0: Long = 0L, hasGyro: Boolean = true): List<DrivingEvent> {
        val out = ArrayList<DrivingEvent>()
        val s = FloatArray(6)
        for (i in 0 until rec.size) {
            rec.data[i].copyInto(s)
            w.processSample(t0 + rec.t[i], s, hasGyro)?.let { out += it }
        }
        return out
    }

    private val rotations = listOf(
        Recording.rotation(0.0, 0.0, 0.0),
        Recording.rotation(40.0, 70.0, 10.0),    // phone in a trouser pocket, thigh forward
        Recording.rotation(-120.0, 15.0, 160.0), // upside down in a bag
        Recording.rotation(90.0, -80.0, 30.0)
    )

    @Test
    fun ordinaryCityDrivingIsNotErraticInAnyPhoneOrientation() {
        for ((k, r) in rotations.withIndex()) {
            val w = DrivingWatch()
            val rec = cityDrive(seed = 10 + k).rotated(r)
            val events = run(w, rec)
            assertTrue("rotation $k: $events", events.none { it.type == DrivingEventType.WEAVING })
            assertFalse("rotation $k: ${events.map { it.type }}", w.erratic(rec.t.last()))
        }
    }

    @Test
    fun weavingIsDetectedInAnyPhoneOrientation() {
        for ((k, r) in rotations.withIndex()) {
            val w = DrivingWatch()
            val rec = Recording.concat(drive(20.0, seed = 3) { 0.0 }, weavingDrive(seed = 4 + k)).rotated(r)
            val events = run(w, rec)
            assertTrue("rotation $k: ${events.map { it.type }}", events.any { it.type == DrivingEventType.WEAVING })
            assertTrue(w.erratic(rec.t.last()))
        }
    }

    @Test
    fun slowGentleWeavingAtHighwaySpeedIsBelowWhatThePhoneCanSee() {
        // ±0.02 rad/s: in-lane drift at speed. Documented limit, not detected.
        val w = DrivingWatch()
        val events = run(w, Recording.concat(drive(20.0) { 0.0 }, weavingDrive(peak = 0.02, hz = 0.1)))
        assertTrue(events.none { it.type == DrivingEventType.WEAVING })
    }

    @Test
    fun threeHardBrakingsMakeTheDrivingErraticOneDoesNot() {
        val w = DrivingWatch()
        val oneBrake = drive(60.0, fwd = { ts -> -bump(ts, 30.0, 2.0, 6.5) }) { 0.0 }
        val first = run(w, oneBrake)
        assertEquals(first.map { "${it.type}@${it.tNanos / 1_000_000_000}s=${it.magnitude}" }.toString(), listOf(DrivingEventType.HARSH_BRAKING), first.map { it.type })
        assertFalse(w.erratic(oneBrake.t.last()))
        val more = run(w, drive(120.0, seed = 5, fwd = { ts -> -bump(ts, 20.0, 2.0, 6.5) - bump(ts, 80.0, 2.0, 6.5) }) { 0.0 },
            t0 = oneBrake.t.last() + 20_000_000L)
        assertEquals(more.map { "${it.type}@${(it.tNanos - oneBrake.t.last()) / 1_000_000_000}s=${it.magnitude}" }.toString(), 2, more.count { it.type == DrivingEventType.HARSH_BRAKING })
        assertTrue(w.erratic(oneBrake.t.last() + 120_000_000_000L))
    }

    /** One full up-and-down cycle: a wheel over a speed breaker or through a pothole. */
    private fun wheelBump(ts: Double, start: Double, duration: Double, peak: Double): Double =
        if (ts < start || ts > start + duration) 0.0 else peak * sin(2 * PI * (ts - start) / duration)

    @Test
    fun speedBreakersAndPotholesAreNotHarshBraking() {
        for ((k, r) in rotations.withIndex()) {
            val base = drive(90.0, seed = 20 + k) { 0.0 }
            // A speed breaker every 10 s (front, then rear wheels), up to 8 m/s² vertical.
            val rec = Recording(base.t.copyOf(), Array(base.size) { i ->
                val ts = i * 0.02
                val p = ts % 10.0
                val peak = if ((ts / 10.0).toInt() % 2 == 0) 8.0 else 4.0
                val d = base.data[i].copyOf()
                d[2] += (wheelBump(p, 5.0, 0.6, peak) + wheelBump(p, 5.6, 0.6, peak)).toFloat()
                d
            }).rotated(r)
            val w = DrivingWatch()
            val events = run(w, rec)
            assertTrue("rotation $k: ${events.map { "${it.type}=${it.magnitude}" }}", events.none { it.type == DrivingEventType.HARSH_BRAKING })
            assertFalse(w.erratic(rec.t.last()))
        }
    }

    @Test
    fun aSuddenJerkOfTheWheelIsJerkySteering() {
        val w = DrivingWatch()
        // A swerve: 0.9 rad/s in 0.4 s and back.
        val events = run(w, drive(30.0, speed = 5.0) { ts -> bump(ts, 15.0, 0.8, 0.9) })
        assertTrue(events.map { it.type }.toString(), events.any { it.type == DrivingEventType.JERKY_STEERING })
    }

    @Test
    fun thePhoneTippingOverInThePocketIsNotHarshBraking() {
        // Straight driving; at 30 s the phone turns 60° about its x axis within 1 s and stays so.
        val base = drive(60.0, seed = 6) { 0.0 }
        val tipped = Recording(base.t.copyOf(), Array(base.size) { i ->
            val ts = i * 0.02
            val a = Math.toRadians(60.0 * ((ts - 30.0) / 1.0).coerceIn(0.0, 1.0))
            val rate = if (ts in 30.0..31.0) Math.toRadians(60.0) else 0.0
            val c = kotlin.math.cos(a).toFloat(); val sn = sin(a).toFloat()
            val d = base.data[i]
            floatArrayOf(d[0], c * d[1] - sn * d[2], sn * d[1] + c * d[2],
                (d[3] + rate).toFloat(), c * d[4] - sn * d[5], sn * d[4] + c * d[5])
        })
        val w = DrivingWatch()
        val events = run(w, tipped)
        assertTrue(events.map { it.type }.toString(), events.none { it.type == DrivingEventType.HARSH_BRAKING })
        assertFalse(w.erratic(tipped.t.last()))
    }

    @Test
    fun withoutAGyroscopeOnlyHarshBrakingIsJudged() {
        val w = DrivingWatch()
        val events = run(w, Recording.concat(drive(20.0) { 0.0 }, weavingDrive()), hasGyro = false)
        assertTrue(events.none { it.type == DrivingEventType.WEAVING || it.type == DrivingEventType.JERKY_STEERING })
    }
}
