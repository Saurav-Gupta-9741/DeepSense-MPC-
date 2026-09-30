package com.iitj.pervasivesense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class WellnessScoreEngineTest {

    @Test
    fun compositeFormulaAndComponents() {
        val w = WellnessScoreEngine()
        w.ensureDay("2026-09-30")
        w.onContextElapsed("STILL", 600.0, isSlouching = false)
        w.onContextElapsed("STILL", 200.0, isSlouching = true)   // 25 % of still time slouched
        w.onContextElapsed("WALKING", 300.0, isSlouching = false)
        w.onContextElapsed("RUNNING", 30.0, isSlouching = false) // below 60 s: not "sustained"
        w.onSteps(3000)
        w.onFallEvent()
        val r = w.computeWellnessReport()
        assertEquals(40, r.activityDiversityScore)  // 2 sustained contexts / 5
        assertEquals(75, r.ergonomicScore)
        assertEquals(50, r.movementScore)           // 3000 / 6000
        assertEquals(75, r.safetyScore)
        assertEquals(Math.round(0.3 * 40 + 0.3 * 75 + 0.2 * 50 + 0.2 * 75).toInt(), r.overallScore)
        assertEquals(5, r.totalWalkMinutes)
        assertEquals(13, r.totalStillMinutes)
    }

    @Test
    fun dayRolloverResetsAndStateRoundTrips() {
        val w = WellnessScoreEngine()
        assertFalse(w.ensureDay("2026-09-30"))
        w.onSteps(500); w.potholes = 2
        val saved = w.exportState()
        val restored = WellnessScoreEngine().apply { restore(saved) }
        assertEquals(500, restored.computeWellnessReport().totalSteps)
        assertEquals(2, restored.potholes)
        assertTrue(restored.ensureDay("2026-10-01"))
        assertEquals(0, restored.computeWellnessReport().totalSteps)
        assertEquals(0, restored.potholes)
    }
}

class ActivityTransitionDetectorTest {
    @Test
    fun logsOnlyChangesWithTheirOwnTimestamp() {
        val d = ActivityTransitionDetector()
        assertNull(d.onStableContext("STILL", 0.9f, 1000))
        assertNull(d.onStableContext("STILL", 0.9f, 2000))
        val e = d.onStableContext("WALKING", 0.8f, 3000)!!
        assertEquals("STILL", e.fromActivity); assertEquals("WALKING", e.toActivity); assertEquals(3000, e.wallTimeMillis)
        d.onStableContext("WALKING", 0.8f, 9000)
        assertEquals(3000, d.lastTransition!!.wallTimeMillis) // not refreshed to "now"
        assertEquals(1, d.totalTransitions)
    }
}

/**
 * End-to-end tests of the real-time pipeline, driven by REAL recordings
 * re-emitted as jittered, off-rate device events.
 *
 * The classifier here is a transparent physical stand-in (motion energy of the
 * window) so that pipeline timing can be measured independently of model
 * accuracy; the TFLite model itself is validated in tests/test_tflite_model.py.
 */
class SensingEngineTest {

    /** STILL vs WALKING from the std of |a| over the window; records every call. */
    private class MotionEnergyClassifier : ActivityClassifier {
        override val labels = listOf("STILL", "WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN")
        var calls = 0
        var lastWindowLastRow = FloatArray(6)
        var fail = false
        /** Raw (unsmoothed) decision of every call, in order: call k is inference number k+1. */
        val raw = ArrayList<String>()
        override fun classify(window: FloatArray): FloatArray? {
            calls++
            if (fail) return null
            require(window.size == 128 * 6)
            window.copyInto(lastWindowLastRow, 0, 127 * 6, 128 * 6)
            var s = 0.0; var ss = 0.0
            for (i in 0 until 128) {
                val m = sqrt((window[i * 6] * window[i * 6] + window[i * 6 + 1] * window[i * 6 + 1] + window[i * 6 + 2] * window[i * 6 + 2]).toDouble())
                s += m; ss += m * m
            }
            val std = sqrt(ss / 128 - (s / 128) * (s / 128))
            raw += if (std > 1.0) "WALKING" else "STILL"
            return if (std > 1.0) floatArrayOf(0.05f, 0.9f, 0.02f, 0.02f, 0.01f) else floatArrayOf(0.9f, 0.05f, 0.02f, 0.02f, 0.01f)
        }
    }

    private class Recorder : SensingEngine.Listener {
        val snapshots = ArrayList<SensingSnapshot>()
        val transitions = ArrayList<TransitionEvent>()
        val ecoChanges = ArrayList<Boolean>()
        override fun onSnapshot(snapshot: SensingSnapshot) { snapshots += snapshot }
        override fun onTransition(event: TransitionEvent) { transitions += event }
        override fun onEcoModeChanged(eco: Boolean) { ecoChanges += eco }
    }

    private val start = 1_000_000_000L

    private fun engine(c: ActivityClassifier, r: Recorder, day: (Long) -> String = { "2026-09-30" }, gyro: Boolean = true) =
        SensingEngine(c, wallClockOf = { it / 1_000_000 }, dayKeyOf = day, listener = r, hasGyroscope = gyro)

    private fun play(e: SensingEngine, rec: Recording, withGyro: Boolean = true, at: Long = start) {
        for (ev in deviceEvents(rec, withGyro = withGyro, startNanos = at)) {
            if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        }
    }

    private fun firstTime(r: Recorder, pred: (SensingSnapshot) -> Boolean) =
        r.snapshots.firstOrNull(pred)?.let { (it.sensorTimeNanos - start) / 1e9 }

    @Test
    fun respondsInRealTimeToRealActivityChanges() {
        val sit = Recording.fixture("sitting_pocket")    // 0 .. 30 s
        val walk = Recording.fixture("walking_pocket")   // 30 .. 60 s
        val stand = Recording.fixture("standing_pocket") // 60 .. 80 s
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r)
        e.setUiVisible(true)
        play(e, Recording.concat(sit, walk, stand))

        // Warm-up ends as soon as the first 2.56 s window is full.
        val firstDecision = firstTime(r) { it.context != null }!!
        assertTrue("first decision at ${firstDecision}s", firstDecision <= 2.7)
        assertTrue(r.snapshots.first().status == EngineStatus.WARMING_UP)

        // End-to-end latency of real changes (old app: >= 2.56 s best case, 25.6 s in its
        // 'energy save' mode, and never with the model that failed to load).
        val toWalk = firstTime(r) { it.context == "WALKING" }!! - 30.0
        val toStill = r.snapshots.first { it.context == "STILL" && (it.sensorTimeNanos - start) / 1e9 > 60 }
            .let { (it.sensorTimeNanos - start) / 1e9 - 60.0 }
        println("latency STILL->WALKING %.2fs, WALKING->STILL %.2fs".format(toWalk, toStill))
        assertTrue("STILL->WALKING latency ${toWalk}s", toWalk in 0.0..3.0)
        assertTrue("WALKING->STILL latency ${toStill}s", toStill in 0.0..4.0)
        assertEquals(listOf("STILL" to "WALKING", "WALKING" to "STILL"), r.transitions.map { it.fromActivity to it.toActivity })

        // Delay added by the pipeline itself (smoothing + hysteresis), independent of the
        // classifier: the displayed context follows a sustained classifier flip within
        // 2 inferences (= 1.0 s at the 0.5 s stride).
        for ((from, to) in listOf("STILL" to "WALKING", "WALKING" to "STILL")) {
            val flip = (1 until c.raw.size).first { c.raw[it] == to && c.raw[it - 1] == from } + 1 // 1-based inference no.
            val shown = r.snapshots.first { it.context == to && it.inferencesRun >= flip }.inferencesRun
            assertTrue("$from->$to shown ${shown - flip} inferences after the classifier flipped", shown - flip in 0..2)
        }

        // Inference every 0.5 s after warm-up; dashboard refresh at 10 Hz while visible.
        val expectedInferences = ((80.0 - 2.56) / 0.5).toInt()
        assertTrue("calls=${c.calls}", kotlin.math.abs(c.calls - expectedInferences) <= 3)
        val rate = r.snapshots.size / 80.0
        assertTrue("snapshot rate $rate Hz", rate in 9.5..13.0)

        // Wellness credits real elapsed time to the right context.
        val w = r.snapshots.last().wellness
        assertEquals(0, w.totalWalkMinutes) // 30 s
        val walkSec = e.wellness.exportState().secondsByContext["WALKING"] ?: 0.0
        assertTrue("walking seconds $walkSec", walkSec in 27.0..33.0)
        assertTrue("steps ${w.totalSteps}", w.totalSteps in 45..62)
    }

    @Test
    fun classifierReceivesChronologicalWindowEndingAtNewestSample() {
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r)
        play(e, Recording.fixture("walking_pocket"))
        val latest = r.snapshots.last()
        // The snapshot published with the last inference carries the newest sample.
        assertEquals(latest.ax, c.lastWindowLastRow[0], 1e-5f)
        assertEquals(latest.gz, c.lastWindowLastRow[5], 1e-5f)
    }

    @Test
    fun modelFailureIsSurfacedNotHidden() {
        // Regression: the shipped model failed to load and the UI showed a silent "UNKNOWN 0%".
        val c = MotionEnergyClassifier().apply { fail = true }; val r = Recorder(); val e = engine(c, r)
        play(e, Recording.fixture("sitting_pocket"))
        assertEquals(EngineStatus.MODEL_ERROR, r.snapshots.last().status)
        assertNull(r.snapshots.last().context)
    }

    @Test
    fun ecoModeOnlyWhenStationaryAndUnobserved() {
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r)
        e.setUiVisible(false)
        val longSit = Recording.concat(*Array(3) { Recording.fixture("sitting_pocket") }) // 90 s
        play(e, longSit)
        assertEquals(listOf(true), r.ecoChanges)
        assertTrue(e.ecoMode)
        // Stride grows to 64 samples in eco: far fewer inferences in the second minute.
        e.setUiVisible(true)
        assertFalse("opening the dashboard must leave eco immediately", e.ecoMode)
    }

    @Test
    fun sensorGapNeverCreditsTimeOrClassifiesAcrossTheHole() {
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r)
        val sit = Recording.fixture("sitting_pocket")
        for (ev in deviceEvents(sit, startNanos = start)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        val callsBefore = c.calls
        val resume = start + 30_000_000_000L + 60_000_000_000L // 60 s outage
        for (ev in deviceEvents(sit, startNanos = resume)) if (ev.isAccel) e.onAccelerometer(ev.t, ev.x, ev.y, ev.z) else e.onGyroscope(ev.t, ev.x, ev.y, ev.z)
        val still = e.wellness.exportState().secondsByContext["STILL"]!!
        assertTrue("credited $still s for 60 s of recording + 60 s outage", still in 50.0..60.0)
        // After the gap, the first new inference waits for a fresh full window.
        assertTrue(c.calls > callsBefore)
    }

    @Test
    fun vehicleContextOverridesAndEnablesRoadMonitoringThenExpires() {
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r)
        play(e, Recording.constant(5.0, 0f, 0f, 9.81f, noise = 0.05f))
        e.onVehicleConfidence(85)
        play(e, Recording.constant(5.0, 0f, 0f, 9.81f, noise = 0.05f, seed = 2), at = start + 5_000_000_000L)
        val s = r.snapshots.last()
        assertEquals("IN_VEHICLE", s.context)
        assertEquals("STILL", s.bodyActivity)
        assertTrue(s.roadMonitoringActive)
        e.onVehicleConfidence(10)
        play(e, Recording.constant(3.0, 0f, 0f, 9.81f, noise = 0.05f, seed = 3), at = start + 10_000_000_000L)
        assertEquals("STILL", r.snapshots.last().context)
        assertFalse(r.snapshots.last().roadMonitoringActive)
    }

    @Test
    fun hardwareStepCounterUsesDeltasAndIgnoresSoftwareDetector() {
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r)
        e.setHardwareStepCounter(true)
        e.onHardwareStepCounter(10_000) // since boot: baseline only
        play(e, Recording.fixture("walking_pocket"))
        e.onHardwareStepCounter(10_055)
        assertEquals(55, e.wellness.computeWellnessReport().totalSteps)
        e.onHardwareStepCounter(3) // reboot: counter restarted
        e.onHardwareStepCounter(8)
        assertEquals(60, e.wellness.computeWellnessReport().totalSteps)
    }

    @Test
    fun wellnessRollsOverAtMidnight() {
        val c = MotionEnergyClassifier(); val r = Recorder()
        val e = engine(c, r, day = { wallMs -> if (wallMs < 16_000) "2026-09-30" else "2026-10-01" })
        play(e, Recording.fixture("walking_pocket"))
        val walked = e.wellness.exportState()
        assertEquals("2026-10-01", walked.dayKey)
        assertTrue("steps after rollover should only count the new day: ${walked.steps}", walked.steps < 40)
    }

    @Test
    fun worksOnPhonesWithoutGyroscope() {
        val c = MotionEnergyClassifier(); val r = Recorder(); val e = engine(c, r, gyro = false)
        play(e, Recording.fixture("walking_pocket"), withGyro = false)
        val s = r.snapshots.last()
        assertFalse(s.hasGyro)
        assertEquals("WALKING", s.context)
        assertNotNull(s.lastTransition ?: s.context)
    }
}
