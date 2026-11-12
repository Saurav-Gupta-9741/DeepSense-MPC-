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
        fun run(d: FallDetector, held: (Int) -> Boolean = { false }): Pair<Boolean, Long> {
            var at = -1L
            rows.forEachIndexed { i, r -> if (d.processSample(i * NS, r[0], r[1], r[2], held(i))) at = i * NS }
            return (at >= 0) to at
        }
        /** Phone in the hand, screen towards the face (tilted 45°), with hand tremor. */
        fun inHand(sec: Double) = hold(sec, 0f, 0.71f * G, 0.71f * G, 0.03f)
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

    // ---- Real phone: put on a table, it said "Fall detected". None of these may ever be a fall.

    /** Let go 10 cm above the table: a real free fall (0.14 s), one sharp 4 g tick, a small rebound, then flat and still. */
    private fun droppedOnTable() = Builder().inHand(3.0).freeFall(0.14).hold(0.02, 0f, 0f, 4f * G, 0f)
        .hold(0.02, 0f, 0f, 1.7f * G, 0f).hold(12.0, 0f, 0f, G, 0.005f)

    /** Brought down quickly by hand (a 60 ms dip, not a fall) and set down hard (3 g, 3 samples). */
    private fun putDownHardOnTable() = Builder().inHand(3.0).hold(0.06, 0f, 0.35f * G, 0.35f * G, 0.01f)
        .hold(0.06, 0f, 0f, 3f * G, 0f).hold(12.0, 0f, 0f, G, 0.005f)

    @Test
    fun withTheOldRulesTheTableWasAFall() {
        // The bug, reproduced: one low-g sample + any 2.5 g spike + tilt change + stillness was enough.
        val old = { FallDetector(lowG = 0.6f, minLowGSamples = 1, minImpactSamples = 1) }
        assertTrue(droppedOnTable().run(old()).first)
        assertTrue(putDownHardOnTable().run(old()).first)
    }

    @Test
    fun phoneDroppedOntoATableIsNotAFall() {
        assertFalse(droppedOnTable().run(FallDetector()).first)
    }

    @Test
    fun phonePutDownHardOnATableIsNotAFall() {
        assertFalse(putDownHardOnTable().run(FallDetector()).first)
    }

    @Test
    fun phonePutDownWhileLookingAtItIsNotAFall() {
        // Even a fall-like put-down (a short dip, heavy contact) while the phone was held and looked at.
        val b = Builder().inHand(3.0).freeFall(0.2).impact(4f).bounces(0.8).hold(10.0, 0f, 0f, G)
        val heldUntil = 3 * 50 + 10
        assertFalse(b.run(FallDetector()) { it < heldUntil }.first)
        // The same motion with the phone on the body (not held) is a fall: the gate is only the hand.
        assertTrue(b.run(FallDetector()).first)
    }

    // ---- Always on, every real fall (there is no test mode: nobody knows when a person falls)

    private val heldUntil: (Int) -> Boolean = { it < 3 * 50 + 10 }

    @Test
    fun aRealDropFromTheHandIsAFallEvenWhileLookingAtIt() {
        // Walking while using the phone, tripping: the phone flies 1 m and lies where it lands.
        val b = Builder().inHand(3.0).freeFall(0.45).impact(4f).bounces(0.8).hold(10.0, 0f, 0f, G)
        assertTrue(b.run(FallDetector(), heldUntil).first)
    }

    @Test
    fun aPhoneHeldFlatThatLandsFlatOnAHardFloorIsAFall() {
        // Real phone: held nearly flat, dropped 1 m onto books (12 g, one tick), landed flat the same way
        // up. Was "not a fall: it landed the same way up" and, outside the old test mode, "in your hand".
        val b = Builder().hold(3.0, 0f, 0.17f * G, 0.98f * G, 0.03f).freeFall(0.45)
            .hold(0.02, 0f, 0f, 12f * G, 0f).hold(0.02, 0f, 0f, 0.6f * G, 0f).hold(12.0, 0f, 0f, G, 0.005f)
        val d = FallDetector()
        assertTrue(b.run(d, heldUntil).first)
        assertEquals(FallCheck.FALL, d.lastVerdict?.check)
        // Screen down on the floor counts the same.
        val down = Builder().hold(3.0, 0f, 0.17f * G, 0.98f * G, 0.03f).freeFall(0.45)
            .hold(0.02, 0f, 0f, 12f * G, 0f).hold(0.02, 0f, 0f, 0.6f * G, 0f).hold(12.0, 0f, 0f, -G, 0.005f)
        assertTrue(down.run(FallDetector()).first)
    }

    @Test
    fun aFallFromThePocketOntoAHardFloorIsAFall() {
        // Standing, phone in the trouser pocket, falls; the body hits the floor and lies still.
        val b = Builder().append(standing()).freeFall(0.4).hold(0.02, 0f, 3f * G, 2f * G, 0f)
            .hold(0.02, 0f, 0f, 0.8f * G, 0f).hold(12.0, G * 0.1f, 0f, G, 0.01f)
        assertTrue(b.run(FallDetector()).first)
    }

    @Test
    fun someoneWhoStrugglesAfterAFallThenLiesStillIsAFall() {
        // Fell, tried to get up for 5 s (moving, still lying), could not, lies still.
        val rnd = Random(9)
        val b = Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8)
        repeat(250) { b.rows += floatArrayOf((rnd.nextGaussian() * 3).toFloat(), (rnd.nextGaussian() * 3).toFloat(), G + (rnd.nextGaussian() * 3).toFloat()) }
        b.hold(20.0, 0f, 0f, G, 0.01f)
        val d = FallDetector()
        assertTrue(b.run(d).first)
        assertEquals(FallCheck.FALL, d.lastVerdict?.check)
    }

    @Test
    fun someoneWhoGetsUpAfterAFallAndStandsIsNotAFall() {
        val rnd = Random(9)
        val b = Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8)
        repeat(250) { b.rows += floatArrayOf((rnd.nextGaussian() * 3).toFloat(), (rnd.nextGaussian() * 3).toFloat(), G + (rnd.nextGaussian() * 3).toFloat()) }
        b.append(standing()).append(standing())
        val d = FallDetector()
        assertFalse(b.run(d).first)
        assertEquals(FallCheck.MOVED, d.lastVerdict?.check)
    }

    @Test
    fun aDroppedPhonePickedUpAgainIsNotAFall() {
        val rnd = Random(4)
        val b = Builder().inHand(3.0).freeFall(0.45).impact(4f).bounces(0.8)
        // Picked up (2 s of handling), then used in the hand for 40 s.
        repeat(100) { b.rows += floatArrayOf((rnd.nextGaussian() * 4).toFloat(), (rnd.nextGaussian() * 4).toFloat(), G + (rnd.nextGaussian() * 4).toFloat()) }
        b.inHand(40.0)
        val d = FallDetector()
        assertFalse(b.run(d, heldUntil).first)
        assertEquals(FallCheck.MOVED, d.lastVerdict?.check)
        // ...or picked up after lying 3 s, and kept in the hand.
        val late = Builder().inHand(3.0).freeFall(0.45).impact(4f).bounces(0.8).hold(3.0, 0f, 0f, G, 0.005f)
        repeat(100) { late.rows += floatArrayOf((rnd.nextGaussian() * 4).toFloat(), (rnd.nextGaussian() * 4).toFloat(), G + (rnd.nextGaussian() * 4).toFloat()) }
        late.inHand(40.0)
        assertFalse(late.run(FallDetector(), heldUntil).first)
    }

    @Test
    fun aJumpDownFromAStepThenStandingIsNotAFall() {
        // Phone in the pocket: jumping down (0.4 s in the air), landing, standing still upright.
        val b = Builder().append(standing()).freeFall(0.4).impact(4f).bounces(0.5).append(standing())
        val d = FallDetector()
        assertFalse(b.run(d).first)
        assertEquals(FallCheck.NO_TURN, d.lastVerdict?.check)
    }

    @Test
    fun phoneTossedRepeatedlyOnATableNeverAlarms() {
        val b = Builder()
        repeat(10) { b.inHand(2.0).freeFall(0.1 + 0.02 * it).hold(0.02, 0f, 0f, (3f + 0.3f * it) * G, 0f).hold(8.0, 0f, 0f, G, 0.005f) }
        assertFalse(b.run(FallDetector()).first)
    }

    // ---- Why a drop was not a fall (shown on the dashboard: a phone test said nothing at all)

    private fun verdictOf(b: Builder, held: (Int) -> Boolean = { false }): FallCheck? {
        val d = FallDetector(); b.run(d, held); return d.lastVerdict?.check
    }

    @Test
    fun everyDropGetsAReason() {
        // A body's fall: FALL.
        assertEquals(FallCheck.FALL, verdictOf(Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8).hold(10.0, 0f, 0f, G)))
        // A phone dropped 1 m onto a pile of books (one sharp tick), even from the hand in use: FALL (a
        // person who falls drops it like that). Let go 10 cm above a table is too small a drop to explain.
        assertNull(verdictOf(droppedOnTable()))
        assertEquals(FallCheck.FALL, verdictOf(Builder().inHand(3.0).freeFall(0.45)
            .hold(0.02, 0f, 0f, 6f * G, 0f).hold(0.02, 0f, 0f, 0.6f * G, 0f).hold(12.0, 0f, 0f, G, 0.005f)) { it < 3 * 50 + 10 })
        // Got up and walked away, then stood: MOVED.
        assertEquals(FallCheck.MOVED, verdictOf(Builder().append(standing()).freeFall(0.35).impact(4f).bounces(0.8)
            .append(Recording.fixture("walking_pocket")).append(standing())))
        // Landed the same way up: NO_TURN.
        val st = standing()
        val up = floatArrayOf(st.data.map { it[0] }.average().toFloat(), st.data.map { it[1] }.average().toFloat(), st.data.map { it[2] }.average().toFloat())
        assertEquals(FallCheck.NO_TURN, verdictOf(Builder().append(st).freeFall(0.35).impact(4f).bounces(0.8).hold(10.0, up[0], up[1], up[2])))
    }

    @Test
    fun aDropOntoADeepPillowIsTooSoftToFeel() {
        // 1 m drop (0.45 s of free fall), landing at only 1.8 g, then lying still.
        assertEquals(FallCheck.SOFT_LANDING, verdictOf(Builder().inHand(3.0).freeFall(0.45)
            .hold(0.1, 0f, 0f, 1.8f * G, 0f).hold(10.0, 0f, 0f, G, 0.005f)))
    }

    @Test
    fun everydayMovementNeverShowsADropReason() {
        for (name in listOf("walking_pocket", "jogging_pocket", "upstairs_pocket", "sitting_pocket", "standing_pocket")) {
            val d = FallDetector()
            val rec = Recording.fixture(name)
            for (i in 0 until rec.size) d.processSample(rec.t[i], rec.data[i][0], rec.data[i][1], rec.data[i][2])
            assertNull(name, d.lastVerdict)
        }
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
