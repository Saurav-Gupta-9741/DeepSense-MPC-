package com.iitj.pervasivesense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private fun median(xs: List<Float>): Float = xs.sorted().let { it[it.size / 2] }
private fun List<GaitSegment>.feature(f: GaitFeature) = map { it.values[f.ordinal] }

/** Three real walking sessions of one person (MotionSense subject 1). */
private val session1 by lazy { Recording.fixture("walking_pocket") }
private val session2 by lazy { Recording.fixture("walking_session2_pocket") }
private val session3 by lazy { Recording.fixture("walking_session3_pocket") }

class GaitSegmentExtractorTest {

    @Test
    fun realWalkingGivesPhysiologicalGait() {
        val segs = gaitSegments(session1)
        assertEquals("8 s segments every 4 s over 30 s", 6, segs.size)
        // Cadence from the leg swing agrees with the independent reference (gyro spectrum).
        val reference = Recording.referenceStepsPerSecond("walking_pocket") * 60
        val cadence = median(segs.feature(GaitFeature.CADENCE))
        assertEquals("cadence $cadence vs $reference", reference, cadence.toDouble(), 0.08 * reference)
        // Healthy stride-time variability is a few percent; strides repeat closely.
        assertTrue(median(segs.feature(GaitFeature.STRIDE_TIME_VARIABILITY)) < 0.05f)
        assertTrue(median(segs.feature(GaitFeature.STRIDE_REGULARITY)) > 0.5f)
        segs.forEach { s -> s.values.forEach { assertTrue(it.isFinite()) } }
    }

    @Test
    fun featuresDoNotDependOnHowThePhoneSitsInThePocket() {
        val reference = gaitSegments(session2)
        for (r in listOf(Recording.rotation(90.0, 0.0, 0.0), Recording.rotation(30.0, 60.0, -45.0), Recording.rotation(0.0, 180.0, 20.0))) {
            val rotated = gaitSegments(session2.rotated(r))
            assertEquals(reference.size, rotated.size)
            for ((a, b) in reference.zip(rotated)) for (f in GaitFeature.entries) {
                val x = a.values[f.ordinal]; val y = b.values[f.ordinal]
                assertEquals("$f", x, y, 2e-3f * max(1f, abs(x)))
            }
        }
    }

    @Test
    fun onlyContinuousWalkingProducesSegments() {
        assertTrue(gaitSegments(Recording.fixture("sitting_pocket")).isEmpty())
        assertTrue(gaitSegments(Recording.fixture("standing_pocket")).isEmpty())
        // The activity gate: nothing while the displayed activity is not WALKING,
        // and an interruption restarts the 8 s segment.
        fun produced(walkingAt: (Int) -> Boolean): Int {
            val ex = GaitSegmentExtractor()
            val s = FloatArray(6)
            var n = 0
            for (i in 0 until session1.size) {
                session1.data[i].copyInto(s)
                if (ex.processSample(session1.t[i], s, walkingAt(i), hasGyro = true) != null) n++
            }
            return n
        }
        assertEquals(6, produced { true })
        assertEquals(0, produced { false })
        assertEquals(0, produced { it % 350 != 0 }) // interrupted every 7 s: never 8 s in a row
    }

    @Test
    fun worksWithoutGyroscope() {
        val segs = gaitSegments(session1, hasGyro = false)
        assertTrue(segs.size >= 5)
        assertTrue(segs.all { it.values[GaitFeature.TURN_UNSTEADINESS.ordinal].isNaN() })
        val reference = Recording.referenceStepsPerSecond("walking_pocket") * 60
        assertEquals(reference, median(segs.feature(GaitFeature.CADENCE)).toDouble(), 0.08 * reference)
    }

    @Test
    fun impairmentMovesEveryTimingAndStabilityFeatureTheExpectedWay() {
        val clean = gaitSegments(session2)
        val impaired = gaitSegments(session2.impaired(1.0))
        fun m(segs: List<GaitSegment>, f: GaitFeature) = median(segs.feature(f))
        assertTrue(m(impaired, GaitFeature.STRIDE_TIME_VARIABILITY) > 2 * m(clean, GaitFeature.STRIDE_TIME_VARIABILITY))
        assertTrue(m(impaired, GaitFeature.STRIDE_REGULARITY) < m(clean, GaitFeature.STRIDE_REGULARITY) - 0.1f)
        assertTrue(m(impaired, GaitFeature.SWAY_IRREGULARITY) > 1.3f * m(clean, GaitFeature.SWAY_IRREGULARITY))
        assertTrue(m(impaired, GaitFeature.TURN_UNSTEADINESS) > 1.5f * m(clean, GaitFeature.TURN_UNSTEADINESS))
        assertTrue(m(impaired, GaitFeature.CADENCE) < m(clean, GaitFeature.CADENCE))
    }
}

class GaitBaselineTest {

    private fun baselineOf(vararg recs: Recording) = GaitBaseline(maxPerDay = 10_000).apply {
        recs.flatMap { gaitSegments(it) }.forEach { add(BaselineEntry(0, "d", it.values)) }
    }

    @Test
    fun thresholdsArePercentilesOfTheUsersOwnWalks() {
        val b = baselineOf(session1, session2)
        val th = b.thresholds(4)!!
        assertTrue(th.unusual >= 1.2f && th.large >= 1.8f && th.large >= th.unusual)
        assertEquals(1f, th.percentile(100f), 0f)
        assertEquals(0f, th.percentile(0f), 0f)
        // The user's own segments are mostly close to normal.
        val d = gaitSegments(session2).map { b.deviation(it.values)!!.distance }
        assertTrue("median own distance ${median(d)}", median(d) < 1.2f)
    }

    @Test
    fun walkingMoreSteadilyThanUsualIsNeverFar() {
        val b = baselineOf(session1, session2)
        val typical = gaitSegments(session2)[5].values.copyOf()
        typical[GaitFeature.STRIDE_TIME_VARIABILITY.ordinal] = 0f
        typical[GaitFeature.STRIDE_REGULARITY.ordinal] = 1f
        typical[GaitFeature.SWAY.ordinal] = 0f
        typical[GaitFeature.SWAY_IRREGULARITY.ordinal] = 0f
        typical[GaitFeature.TURN_UNSTEADINESS.ordinal] = 0f
        val z = b.deviation(typical)!!.z
        for (f in GaitFeature.entries) if (f.direction != 0) assertEquals("$f", 0f, z[f.ordinal], 0f)
    }

    @Test
    fun walkScoreIsTheMedianSoAFewOddSegmentsDoNotFlagAWalk() {
        assertEquals(0.6f, GaitBaseline.walkDistance(floatArrayOf(0.4f, 9f, 0.5f, 0.6f, 8f)), 0f)
    }

    @Test
    fun dailyQuotaAndRollingCapacity() {
        val b = GaitBaseline(capacity = 10, maxPerDay = 4)
        val v = FloatArray(GaitFeature.COUNT) { 1f }
        repeat(6) { b.add(BaselineEntry(it.toLong(), "day1", v)) }
        assertEquals(4, b.size)
        repeat(4) { d -> repeat(4) { b.add(BaselineEntry(0, "day${d + 2}", v)) } }
        assertEquals(10, b.size)
        assertEquals(3, b.days)
    }
}

class FitToDriveMonitorTest {

    private val start = 1_000_000_000L
    private val events = ArrayList<FitToDriveEvent>()
    private val config = FitToDriveMonitor.Config(segmentsForProvisional = 15, maxPerDay = 10_000)

    private fun monitor(cfg: FitToDriveMonitor.Config = config) =
        FitToDriveMonitor({ it / 1_000_000 }, { "2026-10-23" }, { events += it }, cfg).apply { setEnabled(true) }

    /** Feeds a recording at [t0]; returns the time just after it. */
    private fun feed(m: FitToDriveMonitor, rec: Recording, t0: Long, walking: Boolean = true, inPocket: Boolean = true,
                     inVehicle: Boolean = false): Long {
        val s = FloatArray(6)
        for (i in 0 until rec.size) {
            rec.data[i].copyInto(s)
            m.processSample(t0 + rec.t[i], s, walking, hasGyro = true, inPocket = inPocket, inVehicle = inVehicle)
        }
        return t0 + rec.t.last() + 20_000_000L
    }

    private val sixMinutes = 360_000_000_000L

    /** Baseline from sessions 1 and 2, learned on different occasions; returns the next free time. */
    private fun learnNormal(m: FitToDriveMonitor): Long {
        var t = feed(m, session1, start) + sixMinutes
        m.onGap()
        t = feed(m, session2, t) + sixMinutes
        m.onGap()
        // Time passes (sitting): both walks leave the pre-drive quarantine and are learned.
        return feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t, walking = false)
    }

    private fun checks() = events.filterIsInstance<FitToDriveEvent.WalkChecked>().map { it.check }

    @Test
    fun heldOutSessionIsNormalAndImpairedWalkIsALargeGap() {
        val m = monitor()
        var t = learnNormal(m)
        t = feed(m, session3, t)
        m.onTripStart(t)
        val sober = checks().last()
        assertEquals(CheckTrigger.TRIP_START, sober.trigger)
        assertTrue("sober walk scored ${sober.level} (${sober.score})", sober.level == WalkLevel.NORMAL)
        assertTrue(sober.provisional)
        assertEquals(FitAdvice.NONE, m.report().advice)

        t = feed(m, session3.impaired(1.0), t + sixMinutes)
        m.onTripStart(t)
        val impaired = checks().last()
        assertEquals(WalkLevel.LARGE_GAP, impaired.level)
        assertTrue(impaired.percentile >= 0.99f)
        assertTrue(impaired.score > impaired.largeAt)
        // The explanation names timing/stability features, largest first.
        val top = impaired.topDeviations.map { it.first }
        assertTrue("top $top", top.isNotEmpty() && top.none { it == GaitFeature.CADENCE })
        assertTrue(impaired.topDeviations.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
    }

    @Test
    fun segmentsAreQuarantinedAndAFlaggedWalkIsNeverLearned() {
        val m = monitor()
        val t = learnNormal(m)
        val learned = m.report().baselineSegments
        assertTrue(learned >= 18)
        // A fresh walk is not learned while it can still be the walk to a car...
        val after = feed(m, session3.impaired(1.0), t)
        assertEquals(learned, m.report().baselineSegments)
        // ...and once flagged it is dropped, not learned later.
        m.onTripStart(after)
        assertEquals(WalkLevel.LARGE_GAP, checks().last().level)
        m.dismissAdvice()
        feed(m, session1, after + sixMinutes)
        m.onTripStart(after + sixMinutes + 31_000_000_000L)
        assertTrue(m.report().baselineSegments <= learned + 6) // only the new normal walk
        // A normal walk is learned right after it is scored (after the hour that follows a warning).
        val before = m.report().baselineSegments
        val t2 = feed(m, session3, after + 2 * sixMinutes + 3_600_000_000_000L)
        m.onTripStart(t2)
        assertEquals(WalkLevel.NORMAL, checks().last().level)
        assertTrue(m.report().baselineSegments > before)
    }

    @Test
    fun notScoredWhileStillLearning() {
        val m = monitor(config.copy(segmentsForProvisional = 100))
        val t = feed(m, session1, start)
        m.onTripStart(t)
        val c = checks().single()
        assertNull(c.level)
        assertEquals(NotScoredReason.STILL_LEARNING, c.notScored)
        assertEquals(BaselineStatus.LEARNING, m.report().baselineStatus)
        assertEquals(FitAdvice.NONE, m.report().advice)
    }

    @Test
    fun aTripWithoutARecentWalkIsNotScoredAndEachWalkIsScoredOnce() {
        val m = monitor()
        var t = learnNormal(m)
        val before = checks().size
        m.onTripStart(t) // last walk ended > 5 min ago
        assertEquals(before, checks().size)
        t = feed(m, session3, t)
        m.onTripStart(t)
        m.onTripStart(t + 1_000_000_000L) // vehicle detection flickers: same walk
        assertEquals(before + 1, checks().size)
    }

    @Test
    fun carBluetoothTriggersTheCheckBeforeTheCarMoves() {
        val m = monitor()
        val t = feed(m, session3, learnNormal(m))
        m.onCarBluetooth(t)
        assertEquals(CheckTrigger.CAR_BLUETOOTH, checks().last().trigger)
        assertNotNull(checks().last().level)
    }

    @Test
    fun manualCheckWaitsForAWalkThenScoresIt() {
        val m = monitor()
        val t = learnNormal(m)
        m.requestCheck(t)
        assertTrue(m.report().waitingForWalk)
        val before = checks().size
        val after = feed(m, session3.impaired(1.0), t + 1_000_000_000L)
        assertEquals(before, checks().size) // scored only when the 30 s are over
        endCheck(m, after)
        assertFalse(m.report().waitingForWalk)
        assertEquals(before + 1, checks().size)
        assertEquals(CheckTrigger.MANUAL, checks().last().trigger)
        assertEquals(WalkLevel.LARGE_GAP, checks().last().level)
    }

    @Test
    fun aCheckOnRequestScoresOnlyTheWalkAfterTheTapAndIsNeverLearned() {
        val m = monitor()
        var t = learnNormal(m)
        // A wobbly walk just before the tap is not part of the check...
        t = feed(m, session3.impaired(1.0), t)
        m.requestCheck(t)
        // ...only the normal walk after it is.
        t = endCheck(m, feed(m, session1, t + 1_000_000_000L))
        assertEquals(WalkLevel.NORMAL, checks().last().level)
        val learned = m.report().baselineSegments
        m.requestCheck(t)
        endCheck(m, feed(m, session2, t + 1_000_000_000L))
        assertEquals(learned, m.report().baselineSegments) // a checked walk is never learned
    }

    @Test
    fun manualCheckGivesUpIfNobodyWalks() {
        val m = monitor()
        val t = learnNormal(m)
        m.requestCheck(t)
        feed(m, Recording.constant(200.0, 0f, 0f, 9.81f, noise = 0.05f), t, walking = false)
        assertFalse(m.report().waitingForWalk)
        assertEquals(NotScoredReason.NO_RECENT_WALK, checks().last().notScored)
    }

    @Test
    fun disabledMeansNothingIsLearnedOrChecked() {
        val m = monitor()
        m.setEnabled(false)
        val t = feed(m, session2, start)
        m.onTripStart(t); m.requestCheck(t)
        assertEquals(0, m.report().baselineSegments)
        assertTrue(events.isEmpty())
    }

    @Test
    fun learnedStateSurvivesARestart() {
        val m = monitor()
        val t = learnNormal(m)
        val copy = monitor().apply { restore(m.exportState()) }
        assertEquals(m.report().baselineSegments, copy.report().baselineSegments)
        val t2 = feed(copy, session3.impaired(1.0), t)
        copy.onTripStart(t2)
        assertEquals(WalkLevel.LARGE_GAP, checks().last().level)
    }

    // ---------------------------------------------------------------- balance check

    private fun balance(m: FitToDriveMonitor, purpose: BalancePurpose, rec: Recording, t0: Long): BalanceResult {
        assertEquals(BalanceStart.STARTED, m.startBalanceTest(t0, purpose))
        feed(m, rec, t0, walking = false)
        return events.filterIsInstance<FitToDriveEvent.BalanceDone>().last().result
    }

    @Test
    fun balanceMeasuresOrientationFreeSway() {
        val test = BalanceTest()
        val s = FloatArray(6)
        fun run(rec: Recording): BalanceMeasurement {
            test.start(0)
            var r: BalanceMeasurement? = null
            for (i in 0 until rec.size) { rec.data[i].copyInto(s); r = test.processSample(rec.t[i], s, true) ?: r }
            return r!!
        }
        val upright = run(stance(31.0, 0.08))
        val tilted = run(stance(31.0, 0.08).rotated(Recording.rotation(40.0, 70.0, 10.0)))
        assertFalse(upright.moved)
        assertEquals(upright.swayAccel, tilted.swayAccel, 1e-3f)
        assertEquals(0.08f, upright.swayAccel, 0.02f) // band-limited sway + sensor noise
        assertTrue(run(Recording.concat(stance(15.0, 0.08), session1)).moved) // walked off
    }

    @Test
    fun balanceComparesWithTheUsersOwnReference() {
        val m = monitor()
        assertEquals(BalanceOutcome.NO_REFERENCE, balance(m, BalancePurpose.CHECK, stance(31.0, 0.08), start).outcome)
        assertEquals(BalanceOutcome.REFERENCE_SAVED, balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08, seed = 4), start + 40_000_000_000L).outcome)
        assertEquals(1, m.report().balanceReferences)

        val ok = balance(m, BalancePurpose.CHECK, stance(31.0, 0.09, seed = 5), start + 80_000_000_000L)
        assertEquals(BalanceOutcome.PASSED, ok.outcome)
        assertTrue(ok.ratio in 0.9f..1.4f)
        assertEquals(FitAdvice.BALANCE_OK, m.report().advice)

        val swaying = balance(m, BalancePurpose.CHECK, stance(31.0, 0.22, seed = 6), start + 120_000_000_000L)
        assertEquals(BalanceOutcome.FAILED, swaying.outcome)
        assertTrue(swaying.ratio >= 1.75f)
        assertEquals(FitAdvice.DONT_DRIVE, m.report().advice)
    }

    @Test
    fun afterALargeGapOnlyANormalBalanceTestClearsTheWarning() {
        val m = monitor()
        var t = learnNormal(m)
        balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08), t)
        t += 40_000_000_000L
        t = feed(m, session3.impaired(1.0), t)
        m.onTripStart(t)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        // A reference cannot be recorded while a warning is active.
        assertEquals(BalanceStart.REFUSED_WARNING, m.startBalanceTest(t, BalancePurpose.REFERENCE))
        // Walking off during the check is not a pass.
        assertEquals(BalanceOutcome.MOVED, balance(m, BalancePurpose.CHECK, Recording.concat(stance(10.0, 0.08), session1), t).outcome)
        assertEquals(FitAdvice.DONT_DRIVE, m.report().advice)
        // A normal retry gives the "balance OK" advice; dismissing clears it.
        balance(m, BalancePurpose.CHECK, stance(31.0, 0.08, seed = 9), t + 60_000_000_000L)
        assertEquals(FitAdvice.BALANCE_OK, m.report().advice)
        m.dismissAdvice()
        assertEquals(FitAdvice.NONE, m.report().advice)
    }

    @Test
    fun adviceExpiresAfterAnHour() {
        val m = monitor()
        var t = learnNormal(m)
        t = feed(m, session3.impaired(1.0), t)
        m.onTripStart(t)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        feed(m, Recording.constant(2.0, 0f, 0f, 9.81f), t + 3_601_000_000_000L, walking = false)
        assertEquals(FitAdvice.NONE, m.report().advice)
    }

    // ---------------------------------------------------------------- drunk-driving scenarios

    /** A slightly impaired walk (simulated, severity 0.35): scores UNUSUAL against this person's normal. */
    private val slightlyImpaired by lazy { session3.impaired(0.35, seed = 11) }

    @Test
    fun slightlyUnusualWalkBeforeDrivingOffersTheBalanceCheckAndIsNeverLearned() {
        val m = monitor()
        var t = learnNormal(m)
        balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08), t)
        t += 40_000_000_000L
        val learned = m.report().baselineSegments
        t = feed(m, slightlyImpaired, t)
        m.onCarBluetooth(t)
        assertEquals(WalkLevel.UNUSUAL, checks().last().level)
        assertEquals(FitAdvice.CONSIDER_BALANCE_CHECK, m.report().advice)
        // The offer is not a warning: a reminder is not raised, but "normal balance" cannot be recorded now.
        assertEquals(BalanceStart.REFUSED_WARNING, m.startBalanceTest(t, BalancePurpose.REFERENCE))
        // Unsteady balance turns the offer into "don't drive".
        balance(m, BalancePurpose.CHECK, stance(31.0, 0.22, seed = 6), t)
        assertEquals(FitAdvice.DONT_DRIVE, m.report().advice)
        // The unusual walk never becomes part of "normal", even after its quarantine.
        feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
        assertEquals(learned, m.report().baselineSegments)
    }

    @Test
    fun slightlyUnusualWalkFoundOnlyOnceAlreadyDrivingRaisesNoPrompt() {
        val m = monitor()
        val t = feed(m, slightlyImpaired, learnNormal(m))
        m.onTripStart(t)
        assertEquals(WalkLevel.UNUSUAL, checks().last().level)
        assertEquals(FitAdvice.NONE, m.report().advice)
    }

    @Test
    fun drivingAfterAWarningGivesAReminderAndTheWalkIsNeverLearned() {
        val m = monitor()
        var t = feed(m, session3.impaired(1.0), learnNormal(m))
        m.onTripStart(t)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        val learned = m.report().baselineSegments
        val checksBefore = checks().size
        // Ignores the warning, walks to the car again and the car's Bluetooth connects.
        t = feed(m, session3.impaired(1.0, seed = 5), t + 60_000_000_000L)
        m.onCarBluetooth(t)
        val reminder = events.filterIsInstance<FitToDriveEvent.DrivingAfterWarning>().single()
        assertEquals(CheckTrigger.CAR_BLUETOOTH, reminder.trigger)
        assertEquals(checksBefore, checks().size)
        m.dismissAdvice()
        feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
        assertEquals(learned, m.report().baselineSegments)
    }

    @Test
    fun theBalanceCheckIsNeverStartedInAMovingVehicle() {
        val m = monitor()
        assertEquals(BalanceStart.REFUSED_IN_VEHICLE, m.startBalanceTest(start, BalancePurpose.CHECK, inVehicle = true))
        assertFalse(m.report().balanceRunning)
    }

    @Test
    fun aNormalBalanceRecordingFarLessSteadyThanEarlierOnesIsRejected() {
        val m = monitor()
        assertEquals(BalanceOutcome.REFERENCE_SAVED, balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08), start).outcome)
        val r = balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.22, seed = 6), start + 40_000_000_000L)
        assertEquals(BalanceOutcome.REFERENCE_REJECTED, r.outcome)
        assertEquals(1, m.report().balanceReferences)
        assertEquals(BalanceOutcome.REFERENCE_SAVED, balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.09, seed = 7), start + 80_000_000_000L).outcome)
        assertEquals(2, m.report().balanceReferences)
    }

    @Test
    fun aLabelledStudySessionMeasuresEverySegmentButLearnsNothing() {
        val m = monitor()
        m.learningPaused = true
        val t = feed(m, session2, start)
        feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
        assertEquals(0, m.report().baselineSegments)
        val measured = events.filterIsInstance<FitToDriveEvent.SegmentMeasured>()
        assertTrue("segments measured ${measured.size}", measured.size >= 12)
        assertTrue(measured.all { it.distance.isNaN() }) // no baseline yet
        assertTrue(m.report().learningPaused)
    }

    @Test
    fun walksWithThePhoneInTheHandAreNotLearnedOrScored() {
        val m = monitor()
        val t = feed(m, session2, start, inPocket = false)
        assertTrue(m.report().walkingOutsidePocket)
        feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
        assertEquals(0, m.report().baselineSegments)
        assertTrue(events.none { it is FitToDriveEvent.SegmentMeasured })
        assertFalse(m.report().walkingOutsidePocket)
    }

    @Test
    fun anUncheckedUnsteadyWalkIsNeverLearnedButAnUncheckedNormalWalkIs() {
        // Established (here after 15 segments on 1 day): an unchecked large-gap walk is dropped.
        // While provisional, "normal" may be a single day, so only extreme staggering is dropped
        // (judging harder discards real day-to-day variation: see FitToDriveEvaluationTest).
        val m = monitor(config.copy(segmentsForEstablished = 15, daysForEstablished = 1))
        var t = learnNormal(m)
        assertEquals(BaselineStatus.ESTABLISHED, m.report().baselineStatus)
        val learned = m.report().baselineSegments
        // Walking home unsteady (no trip, no check): after its quarantine it is judged, not learned.
        for (rec in listOf(session3.impaired(1.0), session3.impaired(1.0, seed = 5))) {
            t = feed(m, rec, t)
            t = feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
            assertEquals(learned, m.report().baselineSegments)
        }
        assertTrue(checks().isEmpty())
        // An ordinary unchecked walk still becomes part of "normal".
        t = feed(m, session3, t)
        feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
        assertTrue(m.report().baselineSegments > learned)
    }

    @Test
    fun practiceWalksAreScoredSpokenAndNeverLearned() {
        val m = monitor()
        assertFalse(m.startPractice(start, PracticeWalk.NORMAL)) // nothing to compare with yet
        var t = learnNormal(m)
        val learned = m.report().baselineSegments

        assertTrue(m.startPractice(t, PracticeWalk.NORMAL))
        assertEquals(PracticeWalk.NORMAL, m.report().practice)
        t = feed(m, session1, t + 1_000_000_000L)
        val a = checks().last()
        assertEquals(PracticeWalk.NORMAL, a.practice)
        assertEquals(CheckTrigger.MANUAL, a.trigger)
        assertEquals(WalkLevel.NORMAL, a.level)
        assertTrue(a.segments >= 5)
        assertNull(m.report().practice)

        assertTrue(m.startPractice(t, PracticeWalk.WOBBLY))
        t = feed(m, session1.impaired(1.0, seed = 3), t + 1_000_000_000L)
        val b = checks().last()
        assertEquals(PracticeWalk.WOBBLY, b.practice)
        assertEquals(WalkLevel.LARGE_GAP, b.level)
        // The real response follows, so test C (balance check) can be tried.
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        m.dismissAdvice()

        // Neither test walk ever becomes part of "normal".
        feed(m, Recording.constant(1.0, 0f, 0f, 9.81f), t + sixMinutes, walking = false)
        assertEquals(learned, m.report().baselineSegments)
    }

    @Test
    fun aPracticeWalkWaitsThenGivesUpOrCanBeCancelled() {
        val m = monitor()
        val t = learnNormal(m)
        assertTrue(m.startPractice(t, PracticeWalk.NORMAL))
        assertTrue(m.report().waitingForWalk)
        m.cancelPractice()
        assertNull(m.report().practice)
        assertFalse(m.report().waitingForWalk)

        assertTrue(m.startPractice(t, PracticeWalk.WOBBLY))
        feed(m, Recording.constant(200.0, 0f, 0f, 9.81f, noise = 0.05f), t, walking = false)
        val c = checks().last()
        assertEquals(NotScoredReason.NO_RECENT_WALK, c.notScored)
        assertEquals(PracticeWalk.WOBBLY, c.practice)
        assertNull(m.report().practice)
    }

    // ---------------------------------------------------------------- after "I'm not driving"

    private val oneMinute = 60_000_000_000L
    private fun reminders() = events.filterIsInstance<FitToDriveEvent.DrivingAfterWarning>()
    private fun erratic() = events.filterIsInstance<FitToDriveEvent.ErraticDriving>()

    /** A real large-gap warning from "Check my walk", then "I'm not driving". Returns the time after. */
    private fun warnedThenDismissed(m: FitToDriveMonitor): Long {
        var t = learnNormal(m)
        m.requestCheck(t)
        t = feed(m, session3.impaired(1.0), t + 1_000_000_000L)
        t = endCheck(m, t)
        assertEquals(WalkLevel.LARGE_GAP, checks().last().level)
        m.dismissAdvice()
        assertEquals(FitAdvice.NONE, m.report().advice)
        assertTrue(m.report().recentlyWarned)
        return t
    }

    @Test
    fun saysNotDrivingButATripStartsWithinTheHourGivesAReminder() {
        val m = monitor()
        val t = warnedThenDismissed(m)
        m.onTripStart(t + 10 * oneMinute)
        val r = reminders().single()
        assertTrue(r.dismissed)
        assertEquals(CheckTrigger.TRIP_START, r.trigger)
        // Vehicle detection flickering or the car's Bluetooth right after: one reminder per trip.
        m.onCarBluetooth(t + 11 * oneMinute)
        assertEquals(1, reminders().size)
        // After the hour: no reminder.
        m.onTripStart(t + 70 * oneMinute)
        assertEquals(1, reminders().size)
        assertFalse(m.report(t + 70 * oneMinute).recentlyWarned)
    }

    @Test
    fun aPassedBalanceCheckEndsTheWatch() {
        val m = monitor()
        var t = learnNormal(m)
        balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08), t)
        t += 40_000_000_000L
        m.requestCheck(t)
        t = feed(m, session3.impaired(1.0), t + 1_000_000_000L)
        t = endCheck(m, t)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        balance(m, BalancePurpose.CHECK, stance(31.0, 0.08, seed = 9), t)
        assertEquals(FitAdvice.BALANCE_OK, m.report().advice)
        assertFalse(m.report().recentlyWarned)
        m.dismissAdvice()
        m.onTripStart(t + 10 * oneMinute)
        assertTrue(reminders().isEmpty())
    }

    @Test
    fun weavingAfterAWarningIsReportedOnceAndAPassengerIsNotWatched() {
        val m = monitor()
        var t = warnedThenDismissed(m) + 5 * oneMinute
        m.onTripStart(t)
        t = feed(m, drive(20.0) { 0.0 }, t, walking = false, inVehicle = true)
        assertTrue(m.report().watchingDriving)
        t = feed(m, weavingDrive(90.0), t, walking = false, inVehicle = true)
        val e = erratic().single()
        assertTrue(e.events.any { it.type == DrivingEventType.WEAVING })
        // Next trip, but "I'm a passenger" (cancelled the contact alert): not watched.
        t = feed(m, Recording.constant(5.0, 0f, 0f, 9.81f), t + oneMinute, walking = false)
        m.onTripStart(t)
        m.confirmPassenger()
        t = feed(m, weavingDrive(90.0, seed = 7), t, walking = false, inVehicle = true)
        assertFalse(m.report().watchingDriving)
        assertEquals(1, erratic().size)
    }

    @Test
    fun withoutAWarningDrivingIsNeverReported() {
        val m = monitor()
        var t = learnNormal(m)
        m.onTripStart(t)
        t = feed(m, Recording.concat(drive(20.0) { 0.0 }, weavingDrive(90.0)), t, walking = false, inVehicle = true)
        assertFalse(m.report().watchingDriving)
        assertTrue(erratic().isEmpty())
        // A practice wobbly walk ("Test it") is not a real warning either.
        assertTrue(m.startPractice(t, PracticeWalk.WOBBLY))
        t = feed(m, session1.impaired(1.0, seed = 3), t + 1_000_000_000L)
        assertEquals(WalkLevel.LARGE_GAP, checks().last().level)
        m.dismissAdvice()
        assertFalse(m.report().recentlyWarned)
        m.onTripStart(t + oneMinute)
        feed(m, Recording.concat(drive(20.0) { 0.0 }, weavingDrive(90.0, seed = 8)), t + oneMinute, walking = false, inVehicle = true)
        assertTrue(erratic().isEmpty())
        assertTrue(reminders().isEmpty())
    }

    @Test
    fun aPracticeWarningNeverRemindsOrAlertsAndARealTripChecksAsUsual() {
        val m = monitor()
        var t = learnNormal(m)
        balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08), t)
        t += 40_000_000_000L
        // Test B (wobbly walk): a large gap, as expected...
        assertTrue(m.startPractice(t, PracticeWalk.WOBBLY))
        t = feed(m, session1.impaired(1.0, seed = 3), t + 1_000_000_000L)
        assertEquals(WalkLevel.LARGE_GAP, checks().last().level)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        // ...then test C (sway on purpose): "don't drive", as expected.
        assertEquals(BalanceStart.STARTED, m.startBalanceTest(t, BalancePurpose.CHECK, practice = true))
        feed(m, stance(31.0, 0.22, seed = 6), t, walking = false)
        assertEquals(BalanceOutcome.FAILED, events.filterIsInstance<FitToDriveEvent.BalanceDone>().last().result.outcome)
        assertEquals(FitAdvice.DONT_DRIVE, m.report().advice)
        assertEquals(FitAdvice.NONE, m.exportState().advice) // never saved as a real warning
        // Not dismissed; the car's Bluetooth connects and the trip starts: no reminder, no alert.
        t += 40_000_000_000L
        m.onCarBluetooth(t)
        m.onTripStart(t + oneMinute)
        assertTrue(reminders().isEmpty())
        assertFalse(m.report().recentlyWarned)
        assertEquals(FitAdvice.NONE, m.report().advice)
    }

    @Test
    fun aWarningSurvivesARestartForTheRestOfItsHour() {
        val m = monitor()
        val t = warnedThenDismissed(m)
        val saved = m.exportState()
        assertNotNull(saved.warnedWallMillis)
        // The app is killed and restarted 10 min later: still watched.
        val m2 = monitor()
        m2.restore(saved)
        feed(m2, Recording.constant(1.0, 0f, 0f, 9.81f), t + 10 * oneMinute, walking = false)
        assertTrue(m2.report().recentlyWarned)
        m2.onTripStart(t + 11 * oneMinute)
        assertEquals(1, reminders().size)
        // Restarted after the hour: nothing left.
        val m3 = monitor()
        m3.restore(saved)
        feed(m3, Recording.constant(1.0, 0f, 0f, 9.81f), t + 70 * oneMinute, walking = false)
        assertFalse(m3.report().recentlyWarned)
    }

    @Test
    fun anActiveWarningIsSavedAndShownAgainAfterARestart() {
        val m = monitor()
        var t = learnNormal(m)
        m.requestCheck(t)
        t = feed(m, session3.impaired(1.0), t + 1_000_000_000L)
        t = endCheck(m, t)
        val saved = m.exportState()
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, saved.advice)
        val m2 = monitor()
        m2.restore(saved)
        feed(m2, Recording.constant(1.0, 0f, 0f, 9.81f), t + oneMinute, walking = false)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m2.report().advice)
    }

    @Test
    fun carBluetoothAloneNeverAlertsATripMustStartToo() {
        val m = monitor()
        var t = warnedThenDismissed(m)
        // Earbuds that call themselves "hands-free", while walking: a reminder at most, never the alert.
        m.onCarBluetooth(t + oneMinute)
        assertTrue(reminders().none { it.driving })
        // The car's Bluetooth, then the trip starts: driving.
        m.onTripStart(t + 5 * oneMinute)
        assertEquals(1, reminders().count { it.driving })
        // Bluetooth, then a bus more than 10 min later: not the car.
        m.onCarBluetooth(t + 20 * oneMinute)
        m.onTripStart(t + 31 * oneMinute)
        assertEquals(1, reminders().count { it.driving })
        // Already in the vehicle when the car's Bluetooth connects: driving.
        t = feed(m, drive(5.0) { 0.0 }, t + 40 * oneMinute, walking = false, inVehicle = true)
        m.onCarBluetooth(t)
        assertEquals(2, reminders().count { it.driving })
    }

    @Test
    fun aPhoneOnATableIsNotABalancePass() {
        val m = monitor()
        var t = learnNormal(m)
        balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08), t)
        t += 40_000_000_000L
        m.requestCheck(t)
        t = feed(m, session3.impaired(1.0), t + 1_000_000_000L)
        t = endCheck(m, t)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, m.report().advice)
        // Phone put down: only sensor noise, no body sway.
        val r = balance(m, BalancePurpose.CHECK, stance(31.0, 0.0, gyroRms = 0.0), t)
        assertEquals(BalanceOutcome.TOO_STILL, r.outcome)
        assertEquals(FitAdvice.DONT_DRIVE, m.report().advice)
        assertTrue(m.report().recentlyWarned)
        // Nor can a phone on a table become "my normal balance".
        t += 40_000_000_000L
        m.dismissAdvice()
        val ref = balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.0, gyroRms = 0.0), t + 2 * oneMinute)
        assertEquals(BalanceOutcome.REFERENCE_REJECTED, ref.outcome)
    }

    @Test
    fun aTripGoesOnThroughRedLightsAndEndsAfterThreeMinutesOut() {
        val trip = TripTracker()
        val s = 1_000_000_000L
        assertTrue(trip.update(0, true))
        assertFalse(trip.update(60 * s, false))   // red light: detection drops...
        assertFalse(trip.update(120 * s, true))   // ...and comes back: same trip
        assertFalse(trip.update(200 * s, false))
        assertFalse(trip.update(299 * s, false))  // under 3 min out
        assertFalse(trip.update(301 * s, false))  // 3 min out: trip over
        assertTrue(trip.update(400 * s, true))    // a new trip
    }

    // ---------------------------------------------------------------- set up

    /** Stands still until the 30 s of "Check my walk" are over (the test walks are shorter). */
    private fun endCheck(m: FitToDriveMonitor, t: Long): Long =
        feed(m, Recording.constant(31.0, 0f, 0f, 9.81f, noise = 0.05f), t, walking = false)

    @Test
    fun duringSetUpEveryPocketWalkCountsAtOnce() {
        val m = monitor() // 15 segments (1 min) needed
        assertEquals(1f, m.report().walkMinutesNeeded, 0.01f)
        feed(m, session1, start) // 30 s of walking, no waiting period
        assertEquals(6, m.report().baselineSegments)
        assertEquals(BaselineStatus.LEARNING, m.report().baselineStatus)
        // A trip start during set up learns nothing twice.
        m.onTripStart(start + 31_000_000_000L)
        assertEquals(6, m.report().baselineSegments)
    }

    @Test
    fun theGuidedWalkSaysWhenItRecordsIsHalfwayAndDone() {
        val m = monitor() // 15 segments (1 min) needed
        assertTrue(m.startWalkRecording(start))
        assertTrue(m.report().walkSession)
        var t = feed(m, session1, start)
        t = feed(m, session2, t + 1_000_000_000L)
        val stages = events.filterIsInstance<FitToDriveEvent.WalkSession>().map { it.stage }
        assertEquals(listOf(WalkSessionStage.RECORDING, WalkSessionStage.HALFWAY, WalkSessionStage.DONE), stages)
        assertEquals(BaselineStatus.PROVISIONAL, m.report().baselineStatus)
        assertFalse(m.report().walkSession)
        assertFalse(m.startWalkRecording(t)) // already learned
    }

    @Test
    fun theGuidedWalkSaysNothingIsRecordedThenGivesUp() {
        val m = monitor()
        assertTrue(m.startWalkRecording(start))
        feed(m, Recording.constant(500.0, 0f, 0f, 9.81f), start, walking = false)
        val stages = events.filterIsInstance<FitToDriveEvent.WalkSession>().map { it.stage }
        assertEquals(listOf(WalkSessionStage.NOTHING_YET, WalkSessionStage.TIMED_OUT), stages)
        assertFalse(m.report().walkSession)
    }

    @Test
    fun liveWalkStatusShowsWhetherTheWalkIsBeingRecorded() {
        val m = monitor()
        val s = FloatArray(6)
        session1.data[0].copyInto(s)
        m.processSample(start, s, walking = true, hasGyro = true, inPocket = true)
        assertTrue(m.report().recordingWalk)
        m.processSample(start + 20_000_000L, s, walking = true, hasGyro = true, inPocket = false)
        assertFalse(m.report().recordingWalk)
        m.processSample(start + 40_000_000L, s, walking = false, hasGyro = true, inPocket = true)
        assertFalse(m.report().recordingWalk)
    }

    @Test
    fun threeNormalBalanceRecordingsAreKeptNewestReplacingOldest() {
        val m = monitor()
        for (k in 0 until 4) balance(m, BalancePurpose.REFERENCE, stance(31.0, 0.08, seed = 20 + k), start + k * 40_000_000_000L)
        assertEquals(3, m.report().balanceReferences)
        assertEquals(3, m.report().balanceReferencesNeeded)
    }

    @Test
    fun balanceProgressIsReported() {
        val m = monitor()
        m.startBalanceTest(start, BalancePurpose.CHECK)
        feed(m, stance(15.0, 0.08), start, walking = false)
        val r = m.report()
        assertTrue(r.balanceRunning)
        assertEquals(0.5f, r.balanceProgress, 0.02f)
        assertTrue(r.balanceSecondsLeft in 15..16) // 15.02 s left, shown rounded up
        m.cancelBalanceTest()
        assertFalse(m.report().balanceRunning)
    }
}

/** The fit-to-drive check inside the full real-time pipeline (jittered, off-rate device events). */
class FitToDriveEngineTest {

    private class Energy : ActivityClassifier {
        override val labels = listOf("STILL", "WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN")
        override fun classify(window: FloatArray): FloatArray {
            var s = 0.0; var ss = 0.0
            for (i in 0 until 128) {
                val m = sqrt((window[i * 6] * window[i * 6] + window[i * 6 + 1] * window[i * 6 + 1] + window[i * 6 + 2] * window[i * 6 + 2]).toDouble())
                s += m; ss += m * m
            }
            val std = sqrt(ss / 128 - (s / 128) * (s / 128))
            return if (std > 1.0) floatArrayOf(0.05f, 0.9f, 0.02f, 0.02f, 0.01f) else floatArrayOf(0.9f, 0.05f, 0.02f, 0.02f, 0.01f)
        }
    }

    @Test
    fun walkToTheCarIsScoredWhenTheTripStarts() {
        val fit = ArrayList<FitToDriveEvent>()
        val snaps = ArrayList<SensingSnapshot>()
        val cfg = SensingEngine.Config(fitToDrive = FitToDriveMonitor.Config(segmentsForProvisional = 15))
        val e = SensingEngine(Energy(), wallClockOf = { it / 1_000_000 }, dayKeyOf = { "2026-10-23" }, config = cfg,
            listener = object : SensingEngine.Listener {
                override fun onSnapshot(snapshot: SensingSnapshot) { snaps += snapshot }
                override fun onFitToDrive(event: FitToDriveEvent, snapshot: SensingSnapshot) { fit += event }
            })
        // A baseline learned earlier (persisted state), restored as the service does.
        val learner = FitToDriveMonitor({ it / 1_000_000 }, { "2026-10-22" }, {},
            FitToDriveMonitor.Config(segmentsForProvisional = 15, maxPerDay = 10_000)).apply { setEnabled(true) }
        val s = FloatArray(6)
        for ((k, rec) in listOf(session1, session2).withIndex()) {
            for (i in 0 until rec.size) { rec.data[i].copyInto(s); learner.processSample(k * 1_000_000_000_000L + rec.t[i], s, true, true) }
        }
        learner.requestCheck(3_000_000_000_000L) // flushes the quarantine (walks are long past)
        e.fitToDrive.restore(learner.exportState())
        e.setFitToDriveEnabled(true)

        val start = 1_000_000_000L
        val walk = session3.impaired(1.0)
        for (ev in deviceEvents(walk, startNanos = start)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        val tEnd = start + walk.t.last()
        e.onVehicleConfidence(90)
        val car = Recording.constant(3.0, 0f, 0f, 9.81f, noise = 0.05f)
        for (ev in deviceEvents(car, startNanos = tEnd + 20_000_000L)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)

        val check = fit.filterIsInstance<FitToDriveEvent.WalkChecked>().single().check
        assertEquals(CheckTrigger.TRIP_START, check.trigger)
        assertEquals(WalkLevel.LARGE_GAP, check.level)
        assertTrue("walk segments ${check.segments}", check.segments >= 3)
        assertEquals(FitAdvice.TAKE_BALANCE_CHECK, snaps.last().fitToDrive.advice)
        assertEquals("IN_VEHICLE", snaps.last().context)
        // In the moving car the balance check is refused.
        assertEquals(BalanceStart.REFUSED_IN_VEHICLE, e.startBalanceTest(BalancePurpose.CHECK))
    }

    @Test
    fun proximitySensorDecidesWhetherAWalkIsAPocketWalk() {
        val fit = ArrayList<FitToDriveEvent>()
        val snaps = ArrayList<SensingSnapshot>()
        val e = SensingEngine(Energy(), wallClockOf = { it / 1_000_000 }, dayKeyOf = { "2026-10-23" },
            listener = object : SensingEngine.Listener {
                override fun onSnapshot(snapshot: SensingSnapshot) { snaps += snapshot }
                override fun onFitToDrive(event: FitToDriveEvent, snapshot: SensingSnapshot) { fit += event }
            })
        e.setFitToDriveEnabled(true)
        e.setUiVisible(true)
        e.setProximityAvailable(true)
        e.onProximity(false) // uncovered: in the hand
        for (ev in deviceEvents(session2, startNanos = 1_000_000_000L)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        assertTrue(fit.none { it is FitToDriveEvent.SegmentMeasured })
        assertTrue(snaps.last().fitToDrive.walkingOutsidePocket)
        e.onProximity(true) // covered: in the pocket
        for (ev in deviceEvents(session2, startNanos = 100_000_000_000L)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        assertTrue(fit.count { it is FitToDriveEvent.SegmentMeasured } >= 10)
        assertFalse(snaps.last().fitToDrive.walkingOutsidePocket)
    }

    @Test
    fun aWalkWithTheScreenOffIsAPocketWalkEvenIfProximityIsStuck() {
        val fit = ArrayList<FitToDriveEvent>()
        val e = SensingEngine(Energy(), wallClockOf = { it / 1_000_000 }, dayKeyOf = { "2026-10-23" },
            listener = object : SensingEngine.Listener {
                override fun onSnapshot(snapshot: SensingSnapshot) = Unit
                override fun onFitToDrive(event: FitToDriveEvent, snapshot: SensingSnapshot) { fit += event }
            })
        e.setFitToDriveEnabled(true)
        e.setProximityAvailable(true)
        e.onProximity(false) // last reading before the screen went off: uncovered, never updated again
        e.setScreenOn(false)
        for (ev in deviceEvents(session2, startNanos = 1_000_000_000L)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        assertTrue(fit.count { it is FitToDriveEvent.SegmentMeasured } >= 10)
    }

    /** An activity model that never recognises walking. */
    private class AlwaysStill : ActivityClassifier {
        override val labels = listOf("STILL", "WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN")
        override fun classify(window: FloatArray) = floatArrayOf(0.9f, 0.05f, 0.02f, 0.02f, 0.01f)
    }

    @Test
    fun theGuidedWalkAlsoCountsStepsWhenTheActivityModelMissesTheWalk() {
        fun measured(guided: Boolean): Int {
            val fit = ArrayList<FitToDriveEvent>()
            val e = SensingEngine(AlwaysStill(), wallClockOf = { it / 1_000_000 }, dayKeyOf = { "2026-10-23" },
                listener = object : SensingEngine.Listener {
                    override fun onSnapshot(snapshot: SensingSnapshot) = Unit
                    override fun onFitToDrive(event: FitToDriveEvent, snapshot: SensingSnapshot) { fit += event }
                })
            e.setFitToDriveEnabled(true)
            val evs = deviceEvents(session2, startNanos = 1_000_000_000L)
            for ((k, ev) in evs.withIndex()) {
                if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
                if (k == 10 && guided) assertTrue(e.startWalkRecording())
            }
            return fit.count { it is FitToDriveEvent.SegmentMeasured }
        }
        assertEquals(0, measured(guided = false))
        assertTrue(measured(guided = true) >= 8)
    }
}
