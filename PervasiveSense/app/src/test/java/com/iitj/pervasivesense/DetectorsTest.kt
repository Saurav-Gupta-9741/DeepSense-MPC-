package com.iitj.pervasivesense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val G = 9.80665f
private const val NS = 20_000_000L

class ErgonomicPostureTrackerTest {

    private fun feed(p: ErgonomicPostureTracker, rec: Recording, t0: Long = 0L) {
        for (i in 0 until rec.size) p.processSample(t0 + rec.t[i], rec.data[i][0], rec.data[i][1], rec.data[i][2])
    }

    @Test
    fun tiltMatchesGravityGeometry() {
        val cases = mapOf(0f to (0f to G), 90f to (G to 0f), -40f to (-G * sin(Math.toRadians(40.0)).toFloat() to G * cos(Math.toRadians(40.0)).toFloat()))
        for ((expected, yz) in cases) {
            val p = ErgonomicPostureTracker()
            feed(p, Recording.constant(1.0, 0f, yz.first, yz.second))
            assertEquals(expected, p.tiltDegrees, 0.5f)
        }
    }

    @Test
    fun gravityEstimateConvergesWithinThreeSecondsOfReorientation() {
        // The original filter updated once per 2.56 s window and needed minutes.
        val p = ErgonomicPostureTracker()
        feed(p, Recording.constant(5.0, 0f, 0f, G))
        feed(p, Recording.constant(3.0, 0f, G, 0f), t0 = 5_000_000_000L)
        assertEquals(90f, p.tiltDegrees, 1.0f)
    }

    /** Fraction of the recording (after the 4 s fill) during which the tracker reports fidgeting. */
    private fun restlessFraction(rec: Recording): Double {
        val p = ErgonomicPostureTracker()
        var flagged = 0; var total = 0
        for (i in 0 until rec.size) {
            p.processSample(rec.t[i], rec.data[i][0], rec.data[i][1], rec.data[i][2])
            if (i >= 200) { total++; if (p.isFidgeting) flagged++ }
        }
        return flagged.toDouble() / total
    }

    @Test
    fun fidgetIndexSeparatesRealSittingFromRealWalking() {
        val sit = restlessFraction(Recording.fixture("sitting_pocket"))
        val stand = restlessFraction(Recording.fixture("standing_pocket"))
        val walk = restlessFraction(Recording.fixture("walking_pocket"))
        // Calm sitting/standing is mostly calm (brief posture shifts are legitimately flagged)...
        assertTrue("sitting restless ${sit * 100}% of the time", sit < 0.25)
        assertTrue("standing restless ${stand * 100}% of the time", stand < 0.25)
        // ...while walking is continuously far above the threshold.
        assertTrue("walking restless ${walk * 100}% of the time", walk > 0.95)
    }

    @Test
    fun sedentaryTimerCountsPromptsAndResets() {
        val p = ErgonomicPostureTracker()
        p.processSample(0, 0f, 0f, G)
        assertEquals(0, p.report(0, isStill = true).continuousStillMinutes)
        val min = 60_000_000_000L
        p.processSample(46 * min, 0f, 0f, G)
        val r = p.report(46 * min, isStill = true)
        assertEquals(46, r.continuousStillMinutes)
        assertTrue(r.needsBreakPrompt)
        // Moving resets the bout.
        assertEquals(0, p.report(47 * min, isStill = false).continuousStillMinutes)
        p.report(48 * min, isStill = true)
        assertEquals(10, p.report(58 * min, isStill = true).continuousStillMinutes)
        // A posture shift > 15° also resets it.
        feed(p, Recording.constant(3.0, 0f, G, 0f), t0 = 58 * min)
        assertEquals(0, p.report(58 * min + 3_000_000_000L, isStill = true).continuousStillMinutes)
    }

    @Test
    fun postureLabelOnlyWhenStill() {
        val p = ErgonomicPostureTracker()
        feed(p, Recording.constant(2.0, 0f, -G * 0.7f, G * 0.7f))
        assertEquals(Posture.SLOUCHING, p.report(2_000_000_000L, true).posture)
        assertEquals(Posture.MOVING, p.report(2_000_000_000L, false).posture)
        assertEquals(45, p.report(2_000_000_000L, true).breakAfterMinutes)
    }
}

class StepDetectorTest {

    private fun count(rec: Recording): Int {
        val d = StepDetector()
        for (i in 0 until rec.size) d.processSample(rec.t[i], rec.data[i][0], rec.data[i][1], rec.data[i][2])
        return d.totalSteps
    }

    private fun assertWithin(name: String, tolerance: Double) {
        val rec = Recording.fixture(name)
        val ref = Recording.referenceStepsPerSecond(name) * rec.durationSec
        for (rot in listOf(Recording.rotation(0.0, 0.0, 0.0), Recording.rotation(40.0, -70.0, 110.0), Recording.rotation(170.0, 20.0, -60.0))) {
            val n = count(rec.rotated(rot))
            assertTrue("$name: detected $n vs gyro reference %.1f".format(ref), abs(n - ref) <= tolerance * ref)
        }
    }

    // Reference cadence is derived independently from the gyroscope (see export_test_fixtures.py).
    @Test fun realWalkingWithin10Percent() = assertWithin("walking_pocket", 0.10)
    @Test fun realJoggingWithin10Percent() = assertWithin("jogging_pocket", 0.10)
    @Test fun realStairClimbingWithin10Percent() = assertWithin("upstairs_pocket", 0.10)

    @Test
    fun noStepsWhileSittingOrStanding() {
        assertEquals(0, count(Recording.fixture("sitting_pocket")))
        assertEquals(0, count(Recording.fixture("standing_pocket")))
    }

    @Test
    fun isolatedBumpsAreNotCountedAsSteps() {
        val base = Recording.constant(10.0, 0f, 0f, G, noise = 0.05f)
        listOf(100, 260, 420).forEach { i -> base.data[i][2] += 8f } // three bumps 3.2 s apart
        assertEquals(0, count(base))
    }
}

/**
 * Fall scenarios are constructed from physics (no labelled fall recordings are
 * available offline); every false-alarm check also runs on REAL recordings.
 */
class FallDetectorTest {

    private class Builder {
        val rows = ArrayList<FloatArray>()
        private val rnd = Random(3)
        fun hold(sec: Double, x: Float, y: Float, z: Float, noiseG: Float = 0.01f) = apply {
            repeat((sec * 50).toInt()) {
                rows += floatArrayOf(x + noiseG * G * rnd.nextGaussian(), y + noiseG * G * rnd.nextGaussian(), z + noiseG * G * rnd.nextGaussian())
            }
        }
        fun freeFall(sec: Double) = hold(sec, 0f, 0.2f * G, 0f, 0.02f)
        fun impact(peakG: Float) = apply {
            rows += floatArrayOf(0f, peakG * 0.6f * G, peakG * 0.8f * G)
            rows += floatArrayOf(0f, -1.5f * G, 1.8f * G)
            rows += floatArrayOf(0.8f * G, 0.5f * G, 2.2f * G)
        }
        fun bounces(sec: Double) = apply {
            repeat((sec * 50).toInt()) { i -> rows += floatArrayOf(1.2f * G * sin(i * 0.9f), 0.3f * G, G + 1.0f * G * cos(i * 1.3f)) }
        }
        fun append(rec: Recording) = apply { rec.data.forEach { rows += floatArrayOf(it[0], it[1], it[2]) } }
        fun run(d: FallDetector): Pair<Boolean, Long> {
            var at = -1L
            rows.forEachIndexed { i, r -> if (d.processSample(i * NS, r[0], r[1], r[2])) at = i * NS }
            return (at >= 0) to at
        }
    }

    private fun standing() = Recording.fixture("standing_pocket")

    @Test
    fun genuineFallWithBouncesIsConfirmedAfterObservation() {
        val b = Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8).hold(10.0, 0f, 0f, G)
        val impactT = (standing().size + 17) * NS
        val d = FallDetector()
        val (confirmed, at) = b.run(d)
        assertTrue("fall not confirmed", confirmed)
        assertEquals(FallState.FALL_CONFIRMED, d.currentState)
        val delay = (at - impactT) / 1e9
        assertTrue("confirmed after %.2fs".format(delay), delay in 7.3..7.8)
    }

    @Test
    fun confirmedFallLatchesUntilAcknowledged() {
        val d = FallDetector()
        Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8).hold(30.0, 0f, 0f, G).run(d)
        // A motionless person reads ~1 g; the original code auto-reset here after 15 s.
        assertEquals(FallState.FALL_CONFIRMED, d.currentState)
        d.acknowledge()
        assertEquals(FallState.MONITORING, d.currentState)
    }

    @Test
    fun personWhoGetsUpAndWalksIsNotAFall() {
        val b = Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8).append(Recording.fixture("walking_pocket"))
        assertFalse(b.run(FallDetector()).first)
    }

    @Test
    fun jumpLandingUprightIsRejectedByOrientationCheck() {
        val s = standing()
        val upright = floatArrayOf(s.data.map { it[0] }.average().toFloat(), s.data.map { it[1] }.average().toFloat(), s.data.map { it[2] }.average().toFloat())
        val b = Builder().append(s).freeFall(0.35).impact(4f).bounces(0.8).hold(10.0, upright[0], upright[1], upright[2])
        assertFalse(b.run(FallDetector()).first)
    }

    @Test
    fun hardImpactWithoutFreeFallDoesNotTrigger() {
        // e.g. dropping heavily onto a chair: no low-g phase before the spike.
        val b = Builder().append(standing()).impact(3.5f).hold(10.0, 0f, 0f, G)
        val d = FallDetector()
        assertFalse(b.run(d).first)
    }

    @Test
    fun noFalseAlarmsOnRealDailyActivities() {
        for (name in listOf("walking_pocket", "jogging_pocket", "upstairs_pocket", "sitting_pocket", "standing_pocket")) {
            val d = FallDetector()
            val rec = Recording.fixture(name)
            for (i in 0 until rec.size) assertFalse(name, d.processSample(rec.t[i], rec.data[i][0], rec.data[i][1], rec.data[i][2]))
        }
    }

    @Test
    fun dataGapDuringObservationAbortsInsteadOfGuessing() {
        val d = FallDetector()
        val b = Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8)
        b.run(d)
        assertEquals(FallState.IMPACT_SUSPECTED, d.currentState)
        val t = b.rows.size * NS + 3_000_000_000L
        d.processSample(t, 0f, 0f, G)
        assertEquals(FallState.MONITORING, d.currentState)
    }
}

/** Road-shock scenarios are physics constructions; orientation independence is the key property. */
class RoadAnomalyDetectorTest {

    /** Vehicle cabin vibration plus an optional vertical shock profile, in world frame, then rotated. */
    private fun ride(shock: (Double) -> Float, rot: Array<FloatArray>, seconds: Double = 6.0, seed: Int = 5): Recording {
        val rnd = Random(seed)
        val n = (seconds * 50).toInt()
        val rec = Recording(LongArray(n) { it * NS }, Array(n) { i ->
            val t = i / 50.0
            floatArrayOf(0.05f * G * rnd.nextGaussian(), 0.05f * G * rnd.nextGaussian(), G + 0.08f * G * rnd.nextGaussian() + shock(t) * G, 0f, 0f, 0f)
        })
        return rec.rotated(rot)
    }

    private fun events(rec: Recording, vehicular: Boolean = true): List<String> {
        val d = RoadAnomalyDetector()
        return (0 until rec.size).mapNotNull { d.processSample(rec.t[it], rec.data[it][0], rec.data[it][1], rec.data[it][2], vehicular)?.type }
    }

    private val orientations = listOf(Recording.rotation(0.0, 0.0, 0.0), Recording.rotation(30.0, 80.0, 10.0), Recording.rotation(-120.0, 35.0, 150.0))

    /** Wheel drops into the hole (≈ -0.7 g) and slams out (≈ +0.8 g) within 0.2 s. */
    private val pothole: (Double) -> Float = { t -> when {
        t in 3.00..3.08 -> -0.7f
        t in 3.08..3.20 -> 0.8f
        else -> 0f } }

    /** Vehicle rises over the hump (≈ +0.4 g) then settles (≈ -0.25 g) over ~0.4 s. */
    private val breaker: (Double) -> Float = { t -> when {
        t in 3.0..3.2 -> 0.4f * sin(((t - 3.0) / 0.2) * Math.PI).toFloat()
        t in 3.2..3.4 -> -0.25f * sin(((t - 3.2) / 0.2) * Math.PI).toFloat()
        else -> 0f } }

    @Test
    fun potholeDetectedInAnyPhoneOrientation() {
        for (r in orientations) assertEquals(listOf("POTHOLE"), events(ride(pothole, r)))
    }

    @Test
    fun speedBreakerDetectedInAnyPhoneOrientation() {
        for (r in orientations) assertEquals(listOf("SPEED_BREAKER"), events(ride(breaker, r)))
    }

    @Test
    fun normalCabinVibrationProducesNothing() {
        for (r in orientations) assertTrue(events(ride({ 0f }, r, seconds = 30.0)).isEmpty())
    }

    @Test
    fun gatedOffOutsideVehicles() {
        assertTrue(events(ride(pothole, orientations[0]), vehicular = false).isEmpty())
        // Real walking must never be tagged as road damage when not in a vehicle.
        assertTrue(events(Recording.fixture("walking_pocket"), vehicular = false).isEmpty())
    }

    @Test
    fun oneShockIsCountedOnce() {
        val d = RoadAnomalyDetector()
        val rec = ride(pothole, orientations[1])
        repeat(rec.size) { d.processSample(rec.t[it], rec.data[it][0], rec.data[it][1], rec.data[it][2], true) }
        assertEquals(1, d.potholesCount)
        assertEquals(0, d.speedBreakersCount)
    }

    @Test
    fun noNullDereferenceOnFirstSample() {
        assertNull(RoadAnomalyDetector().processSample(0, 0f, 0f, G, true))
    }
}
