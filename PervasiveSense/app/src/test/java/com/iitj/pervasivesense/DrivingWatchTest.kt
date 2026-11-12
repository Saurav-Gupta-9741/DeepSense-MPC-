package com.iitj.pervasivesense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
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

/**
 * A person turning a swivel chair by hand, as a test drive asks (seat frame: x forward, z up): swings
 * left, then right, each of a random 15-60 degrees over a random 0.6-1.5 s, and (if [legs]) a push with
 * the legs at every reversal that tilts a thigh-pocket phone (3 rad/s for 0.15 s and back). [hand]: the
 * phone is held, the hand shaking it (about 1 rad/s). No sideways acceleration: a chair goes nowhere.
 */
fun chairTurning(seconds: Double, seed: Int = 170, legs: Boolean = true, hand: Boolean = false): Recording {
    val rnd = Random(seed.toLong())
    val n = (seconds * 50).toInt()
    // The swings: start time, duration, peak turn rate (signed, alternating).
    val starts = ArrayList<Double>(); val durs = ArrayList<Double>(); val peaks = ArrayList<Double>()
    var at = 0.0; var dir = 1.0
    while (at < seconds) {
        val d = 0.6 + 0.9 * rnd.nextDouble()
        val angle = Math.toRadians(15.0 + 45.0 * rnd.nextDouble())
        starts += at; durs += d; peaks += dir * 2 * angle / d // a sin^2 profile: peak = 2 x angle / duration
        at += d + 0.1 * rnd.nextDouble(); dir = -dir
    }
    return Recording(LongArray(n) { it * 20_000_000L }, Array(n) { i ->
        val ts = i * 0.02
        var yaw = 0.0; var push = 0.0; var tilt = 0.0
        for (k in starts.indices) {
            val p = ts - starts[k]
            if (p in 0.0..durs[k]) { val x = sin(PI * p / durs[k]); yaw += peaks[k] * x * x }
            if (legs && p in 0.0..0.3) {
                push += if (p < 0.15) 3.0 else -3.0
                tilt += if (p < 0.15) 3.0 * p else 3.0 * (0.3 - p)
            }
        }
        val hx = if (hand) 0.8 * sin(2 * PI * 1.1 * ts) + 0.5 * sin(2 * PI * 2.3 * ts + 1) else 0.0
        val hy = if (hand) 0.7 * sin(2 * PI * 0.9 * ts + 2) + 0.4 * sin(2 * PI * 1.9 * ts) else 0.0
        floatArrayOf(
            (rnd.nextGaussian() * 0.2).toFloat(),
            (9.81 * sin(tilt) + rnd.nextGaussian() * 0.2).toFloat(),
            (9.81 * cos(tilt) + rnd.nextGaussian() * 0.3).toFloat(),
            (push + hx + rnd.nextGaussian() * 0.01).toFloat(),
            (hy + rnd.nextGaussian() * 0.01).toFloat(),
            (yaw + rnd.nextGaussian() * 0.01).toFloat())
    })
}

/** Spinning a swivel chair round and round one way at [rate] rad/s (not weaving). */
fun chairSpinning(seconds: Double, rate: Double = 1.5, seed: Int = 175) =
    drive(seconds, seed, speed = 0.0) { ts -> rate * minOf(1.0, ts / 2.0) }

class DrivingWatchTest {

    private fun run(w: DrivingWatch, rec: Recording, t0: Long = 0L, hasGyro: Boolean = true,
                    screen: (Double) -> Boolean = { false }): List<DrivingEvent> {
        val out = ArrayList<DrivingEvent>()
        val s = FloatArray(6)
        for (i in 0 until rec.size) {
            rec.data[i].copyInto(s)
            w.processSample(t0 + rec.t[i], s, hasGyro, screen(rec.t[i] / 1e9))?.let { out += it }
        }
        return out
    }

    /**
     * The driver picks the phone up at [from] s (tilting it 80° in 0.6 s), uses it in the hand until
     * [to] s (the hand turns it ±0.3 rad/s and shakes it), then puts it back as it was.
     */
    private fun pickedUp(base: Recording, from: Double, to: Double) = Recording(base.t.copyOf(), Array(base.size) { i ->
        val ts = base.t[i] / 1e9
        val lift = when {
            ts < from || ts > to + 0.6 -> 0.0
            ts < from + 0.6 -> (ts - from) / 0.6
            ts <= to -> 1.0
            else -> 1.0 - (ts - to) / 0.6
        }
        val rate = when {
            ts in from..(from + 0.6) -> Math.toRadians(80.0) / 0.6
            ts in to..(to + 0.6) -> -Math.toRadians(80.0) / 0.6
            else -> 0.0
        }
        val a = Math.toRadians(80.0 * lift)
        val c = cos(a).toFloat(); val sn = sin(a).toFloat()
        val d = base.data[i]
        val j = if (ts in from..(to + 0.6)) 1.0 else 0.0
        floatArrayOf(
            (d[0] + j * 1.5 * sin(2 * PI * 1.3 * ts)).toFloat(),
            c * d[1] - sn * d[2] + (j * 1.2 * sin(2 * PI * 0.8 * ts + 1)).toFloat(),
            sn * d[1] + c * d[2],
            (d[3] + rate + j * 0.25 * sin(2 * PI * 0.7 * ts)).toFloat(),
            c * d[4] - sn * d[5] + (j * 0.25 * sin(2 * PI * 1.1 * ts + 1)).toFloat(),
            sn * d[4] + c * d[5] + (j * 0.35 * sin(2 * PI * 0.9 * ts + 2)).toFloat())
    })

    /** The hand check switched off: how it was before v3.5. */
    private fun withoutHandCheck() = DrivingWatch(DrivingWatch.Config(handlingRadS = Float.MAX_VALUE, handlingTiltDeg = 181f))

    @Test
    fun usingThePhoneWhileDrivingCalmlyIsNotErratic() {
        // Calm straight driving; the phone is picked up and used for 25 s, three times in 5 minutes.
        var rec = drive(300.0, seed = 30) { 0.0 }
        val uses = listOf(40.0, 130.0, 220.0)
        for (from in uses) rec = pickedUp(rec, from, from + 25.0)
        val screen = { ts: Double -> uses.any { ts in it..(it + 26.0) } }

        val before = withoutHandCheck()
        val old = run(before, rec, screen = screen)
        assertTrue("the hand made driving events before: ${old.map { it.type }}", before.erratic(rec.t.last()))

        val w = DrivingWatch()
        val events = run(w, rec, screen = screen)
        assertTrue(events.map { "${it.type}@${it.tNanos / 1_000_000_000}s" }.toString(), events.isEmpty())
        assertFalse(w.erratic(rec.t.last()))
    }

    @Test
    fun movingThePhoneInThePocketWithTheScreenOffIsNotErratic() {
        // Screen off: the driver shifts in the seat and the phone turns over and back, three times.
        var rec = drive(300.0, seed = 33) { 0.0 }
        for (from in listOf(40.0, 130.0, 220.0)) rec = pickedUp(rec, from, from + 8.0)
        val w = DrivingWatch()
        val events = run(w, rec)
        assertFalse(events.map { "${it.type}@${it.tNanos / 1_000_000_000}s" }.toString(), w.erratic(rec.t.last()))
    }

    @Test
    fun aPhoneInAHolderWithTheScreenOnIsStillWatched() {
        // Navigation on all the time; the phone is taken out of the holder at 10 s and put back at 20 s.
        val rec = pickedUp(Recording.concat(drive(40.0, seed = 31) { 0.0 }, weavingDrive(seed = 32)), 10.0, 20.0)
        val w = DrivingWatch()
        val events = run(w, rec, screen = { true })
        assertTrue(events.map { it.type }.toString(), events.any { it.type == DrivingEventType.WEAVING })
        assertTrue(w.erratic(rec.t.last()))
    }

    @Test
    fun hardBrakingRightAfterThePhoneWasPutDownIsStillSeen() {
        // Used until 30 s, screen off at 31 s; three hard brakes later.
        var rec = drive(150.0, seed = 34, fwd = { ts -> -bump(ts, 50.0, 2.0, 6.5) - bump(ts, 80.0, 2.0, 6.5) - bump(ts, 110.0, 2.0, 6.5) }) { 0.0 }
        rec = pickedUp(rec, 10.0, 30.0)
        val w = DrivingWatch()
        val events = run(w, rec, screen = { it in 10.0..31.0 })
        assertEquals(events.map { it.type }.toString(), 3, events.count { it.type == DrivingEventType.HARSH_BRAKING })
        assertTrue(w.erratic(rec.t.last()))
    }

    @Test
    fun emergencyBrakingAtOneGIsNotMistakenForThePhoneBeingMoved() {
        for ((k, r) in rotations.withIndex()) {
            val rec = drive(60.0, seed = 35 + k, fwd = { ts -> -bump(ts, 30.0, 2.5, 9.5) }) { 0.0 }.rotated(r)
            val w = DrivingWatch()
            val events = run(w, rec)
            assertTrue("rotation $k: ${events.map { it.type }}", events.any { it.type == DrivingEventType.HARSH_BRAKING })
        }
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

    // ---------------------------------------------------------------- the higher bar (no warning)

    @Test
    fun withoutAWarningAShortWeaveIsNotEnoughButAMinuteOfWeavingIs() {
        for ((k, r) in rotations.withIndex()) {
            val short = DrivingWatch()
            val brief = Recording.concat(drive(20.0, seed = 40 + k) { 0.0 }, weavingDrive(20.0, seed = 44 + k)).rotated(r)
            val e1 = run(short, brief)
            assertTrue("rotation $k: ${e1.map { it.type }}", short.erratic(brief.t.last()))       // enough after a warning...
            assertFalse("rotation $k: ${e1.map { it.type }}", short.clearlyErratic(brief.t.last())) // ...not without one

            val long = DrivingWatch()
            val minute = Recording.concat(drive(20.0, seed = 40 + k) { 0.0 }, weavingDrive(75.0, seed = 44 + k)).rotated(r)
            val e2 = run(long, minute)
            assertTrue("rotation $k: ${e2.map { it.type }}", long.clearlyErratic(minute.t.last()))
        }
    }

    @Test
    fun aWindingRoadIsNotClearlyErratic() {
        // A hill road: a curve every 7 s, alternating left and right, for 5 minutes.
        for ((k, r) in rotations.withIndex()) {
            val w = DrivingWatch()
            val rec = drive(300.0, seed = 50 + k, speed = 6.0) { ts ->
                val n = (ts / 7.0).toInt()
                bump(ts % 7.0, 0.0, 7.0, if (n % 2 == 0) 0.25 else -0.25)
            }.rotated(r)
            val events = run(w, rec)
            assertFalse("rotation $k: ${events.map { it.type }}", w.clearlyErratic(rec.t.last()))
        }
    }

    // ---------------------------------------------------------------- wherever the phone is

    /**
     * Where the phone is, in the car or on the driver: its orientation, what else moves it (in the
     * car frame, before turning it into the phone's frame) and how much it rattles.
     */
    private class Placement(val name: String, val r: Array<FloatArray>,
                            val body: (Double, FloatArray) -> Unit = { _, _ -> }, val rattle: Float = 0f) {
        fun apply(base: Recording, seed: Int): Recording {
            val rnd = Random(seed.toLong())
            val moved = Recording(base.t.copyOf(), Array(base.size) { i -> base.data[i].copyOf().also { body(base.t[i] / 1e9, it) } })
            val turned = moved.rotated(r)
            if (rattle == 0f) return turned
            // Rattling is fast (a phone loose in a cup holder buzzes at 10-25 Hz), with some road noise.
            return Recording(turned.t, Array(turned.size) { i ->
                val ts = turned.t[i] / 1e9
                turned.data[i].copyOf().also { d ->
                    for (k in 0..2) d[k] += (rattle * (sin(2 * PI * 13 * ts + k) + 0.6 * sin(2 * PI * 21 * ts + 2 * k)) +
                        0.05 * rnd.nextGaussian()).toFloat()
                    for (k in 3..5) d[k] += (0.3 * rattle * (sin(2 * PI * 17 * ts + k) + 0.5 * sin(2 * PI * 23 * ts + k))).toFloat()
                }
            })
        }
    }

    /** The upper body: it sways (roll) and turns to check the mirrors over the shoulder every 20 s. */
    private val torso: (Double, FloatArray) -> Unit = { ts, d ->
        d[3] += (0.12 * sin(2 * PI * 0.3 * ts)).toFloat()
        val p = ts % 20.0
        d[5] += (bump(p, 10.0, 0.7, 0.7) - bump(p, 10.7, 0.7, 0.7)).toFloat()
    }

    private val placements = listOf(
        Placement("trouser pocket", Recording.rotation(40.0, 70.0, 10.0)), // drive() moves the leg on the pedals
        Placement("shirt or jacket pocket", Recording.rotation(0.0, -85.0, 0.0), body = torso),
        Placement("belt or waist", Recording.rotation(90.0, 0.0, 85.0)),
        Placement("bag on the seat or floor", Recording.rotation(-120.0, 15.0, 160.0)),
        Placement("cup holder or door pocket", Recording.rotation(10.0, 80.0, 0.0), rattle = 1f),
        Placement("dashboard holder", Recording.rotation(0.0, -75.0, 0.0), rattle = 0.3f),
        Placement("lying on the seat", Recording.rotation(30.0, 0.0, 0.0))
    )

    @Test
    fun cityDrivingIsNeverErraticWhereverThePhoneIs() {
        for ((k, p) in placements.withIndex()) {
            val w = DrivingWatch()
            val rec = p.apply(cityDrive(seed = 80 + k), seed = 90 + k)
            val events = run(w, rec)
            assertFalse("${p.name}: ${events.map { "${it.type}@${it.tNanos / 1_000_000_000}s" }}", w.erratic(rec.t.last()))
            assertFalse(p.name, w.clearlyErratic(rec.t.last()))
        }
    }

    @Test
    fun aMinuteOfWeavingIsCaughtWhereverThePhoneIs() {
        for ((k, p) in placements.withIndex()) {
            val w = DrivingWatch()
            val rec = p.apply(Recording.concat(drive(20.0, seed = 100 + k) { 0.0 }, weavingDrive(75.0, seed = 110 + k)), seed = 120 + k)
            val events = run(w, rec)
            assertTrue("${p.name}: ${events.map { it.type }}", w.clearlyErratic(rec.t.last()))
        }
    }

    @Test
    fun shoulderChecksWithThePhoneInAShirtPocketAreNotJerkySteering() {
        // Calm driving for 5 minutes, a look over the shoulder every 20 s (the torso turns at 0.7 rad/s).
        val rec = placements[1].apply(drive(300.0, seed = 130) { 0.0 }, seed = 131)
        // The control: judged on the turn rate alone (before v3.8), the body turning made the drive erratic.
        val before = DrivingWatch(DrivingWatch.Config(jerkMinLateralMs2 = 0f))
        val old = run(before, rec)
        assertTrue(old.map { it.type }.toString(), before.erratic(rec.t.last()))
        val w = DrivingWatch()
        val events = run(w, rec)
        assertTrue(events.map { "${it.type}@${it.tNanos / 1_000_000_000}s" }.toString(), events.none { it.type == DrivingEventType.JERKY_STEERING })
        assertFalse(w.erratic(rec.t.last()))
    }

    @Test
    fun aRealSwerveIsStillJerkySteeringWhereverThePhoneIs() {
        for ((k, p) in placements.withIndex()) {
            val w = DrivingWatch()
            // Straight, then a swerve (0.9 rad/s and back at 5 m/s) at 15 s.
            val rec = p.apply(drive(30.0, seed = 140 + k, speed = 5.0) { ts -> bump(ts, 15.0, 0.8, 0.9) }, seed = 150 + k)
            val events = run(w, rec)
            assertTrue("${p.name}: ${events.map { it.type }}", events.any { it.type == DrivingEventType.JERKY_STEERING })
        }
    }

    /** A swivel chair (no car): turning left and right every [periodSec]/2 s, about ±30°; no sideways acceleration. */
    private fun swivelChair(seconds: Double, periodSec: Double = 2.0, seed: Int = 160) =
        drive(seconds, seed, speed = 0.0) { ts -> 1.0 * sin(2 * PI * ts / periodSec) }

    @Test
    fun turningOnASwivelChairLooksLikeWeavingToTheTestDrive() {
        // "Test drive (no car)": the phone in a trouser pocket, the person turns the chair for a minute.
        val rec = Recording.concat(Recording.constant(20.0, 0f, 0f, 9.81f, noise = 0.05f), swivelChair(60.0)).rotated(placements[0].r)
        val w = DrivingWatch(DrivingWatch.Config.forTestDrive())
        val events = run(w, rec)
        assertTrue(events.map { it.type }.toString(), w.clearlyErratic(rec.t.last()))
        // On a real trip the same turning is the body (no sideways force), never weaving.
        val real = DrivingWatch()
        val realEvents = run(real, rec)
        assertTrue(realEvents.map { it.type }.toString(), realEvents.none { it.type == DrivingEventType.WEAVING })
        // Sitting still for 5 minutes asks nothing.
        val still = DrivingWatch(DrivingWatch.Config.forTestDrive())
        val quiet = Recording.constant(300.0, 0f, 0f, 9.81f, noise = 0.05f).rotated(placements[0].r)
        assertTrue(run(still, quiet).isEmpty())
    }

    @Test
    fun aPersonTurningAChairByHandIsCaughtByTheTestDriveWhereverThePhoneIs() {
        val still = Recording.constant(10.0, 0f, 0f, 9.81f, noise = 0.05f)
        val places = listOf(
            Triple("trouser pocket", placements[0].r, false),
            Triple("shirt pocket", placements[1].r, false),
            Triple("held in the hand, screen on", Recording.rotation(0.0, -40.0, 0.0), true))
        for ((k, place) in places.withIndex()) {
            for (seed in 0 until 5) { // five different attempts
                val rec = Recording.concat(still, chairTurning(45.0, seed = 170 + 10 * k + seed, hand = place.third)).rotated(place.second)
                val w = DrivingWatch(DrivingWatch.Config.forTestDrive())
                run(w, rec, screen = { place.third })
                assertTrue("${place.first} #$seed: score ${w.points(rec.t.last())}", w.clearlyErratic(rec.t.last()))
            }
        }
    }

    @Test
    fun theLegsPushingTheChairWipedTheTestDriveWithTheHandCheckOn() {
        // The control (v3.8's test drive kept the hand check of a real drive): every push of the legs
        // jolted the pocket phone like a hand, and the turning seen so far was forgotten.
        val rec = Recording.concat(Recording.constant(10.0, 0f, 0f, 9.81f, noise = 0.05f), chairTurning(45.0, seed = 170)).rotated(placements[0].r)
        val old = DrivingWatch(DrivingWatch.Config.forTestDrive().copy(handlingRadS = 1.5f, handlingTiltDeg = 55f))
        run(old, rec)
        assertFalse("score ${old.points(rec.t.last())}", old.clearlyErratic(rec.t.last()))
        val now = DrivingWatch(DrivingWatch.Config.forTestDrive())
        run(now, rec)
        assertTrue(now.clearlyErratic(rec.t.last()))
    }

    @Test
    fun spinningAChairRoundOneWayIsNotWeaving() {
        val rec = Recording.concat(Recording.constant(10.0, 0f, 0f, 9.81f, noise = 0.05f), chairSpinning(60.0)).rotated(placements[0].r)
        val w = DrivingWatch(DrivingWatch.Config.forTestDrive())
        val events = run(w, rec)
        assertTrue(events.map { it.type }.toString(), events.none { it.type == DrivingEventType.WEAVING })
        assertFalse(w.clearlyErratic(rec.t.last()))
    }

    @Test
    fun threeHardBrakesAreNotClearlyErraticSixAre() {
        val brakes = { times: List<Double> -> drive(280.0, seed = 60, fwd = { ts -> -times.sumOf { bump(ts, it, 2.0, 6.5) } }) { 0.0 } }
        val three = DrivingWatch()
        val r3 = brakes(listOf(40.0, 90.0, 140.0))
        run(three, r3)
        assertTrue(three.erratic(r3.t.last()))
        assertFalse(three.clearlyErratic(r3.t.last()))

        val six = DrivingWatch()
        val r6 = brakes(listOf(30.0, 70.0, 110.0, 150.0, 190.0, 230.0))
        val events = run(six, r6)
        assertEquals(events.map { it.type }.toString(), 6, events.count { it.type == DrivingEventType.HARSH_BRAKING })
        assertTrue(six.clearlyErratic(r6.t.last()))
    }
}
