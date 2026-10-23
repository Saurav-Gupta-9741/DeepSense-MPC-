package com.iitj.pervasivesense

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personal-baseline evaluation on all 24 MotionSense people (needs the dataset:
 * MOTIONSENSE_DIR=<motion-sense/data>; CI downloads it, locally it is skipped).
 *
 * For every person, the real fit-to-drive monitor learns "normal" from two of
 * their walking sessions (wlk_7, wlk_8) and then scores, as a trip starts:
 *  - their third, held-out session (wlk_15): real sober day-to-day variation,
 *    must not raise a large-gap warning;
 *  - the same held-out walk with simulated impairment (see [impaired]);
 *  - other people's held-out walks (shows the model is personal).
 */
class FitToDriveEvaluationTest {

    private val config = FitToDriveMonitor.Config(segmentsForProvisional = 20, maxPerDay = 10_000)

    private class Result(val level: WalkLevel?, val score: Float, val percentile: Float, val segments: Int)

    private fun monitorWithBaseline(sessions: List<Recording>): Pair<FitToDriveMonitor, Long> {
        val m = FitToDriveMonitor({ it / 1_000_000 }, { "2026-10-23" }, {}, config)
        m.setEnabled(true)
        var t0 = 0L
        for (rec in sessions) { feed(m, rec, t0); t0 += rec.t.last() + 20_000_000L + 400_000_000_000L; m.onGap() }
        return m to t0
    }

    private fun feed(m: FitToDriveMonitor, rec: Recording, t0: Long) {
        val s = FloatArray(6)
        for (i in 0 until rec.size) {
            val t = t0 + rec.t[i]
            rec.data[i].copyInto(s)
            m.processSample(t, s, walking = true, hasGyro = true)
        }
    }

    private fun score(m: FitToDriveMonitor, t0: Long, walk: Recording): Result {
        feed(m, walk, t0)
        val end = t0 + walk.t.last()
        m.onTripStart(end)
        val c = m.report(end).lastCheck!!
        m.dismissAdvice()
        return Result(c.level, c.score, c.percentile, c.segments)
    }

    @Test
    fun personalBaselineOnAllMotionSensePeople() {
        val root = System.getenv("MOTIONSENSE_DIR")
        assumeTrue("MOTIONSENSE_DIR not set", root != null)
        val people = (1..24).mapNotNull { s ->
            val a = motionSense(root!!, "wlk_7", s); val b = motionSense(root, "wlk_8", s); val c = motionSense(root, "wlk_15", s)
            if (a == null || b == null || c == null) null else Triple(a, b, c)
        }
        assertTrue("MotionSense walking trials not found under $root", people.size == 24)

        var soberLarge = 0; var soberUnusual = 0
        val detect = mapOf(0.5 to IntArray(2), 1.0 to IntArray(2)) // [unusual-or-large, large]
        var otherLarge = 0; var otherTotal = 0
        for ((i, p) in people.withIndex()) {
            val (a, b, held) = p
            val (m, t0) = monitorWithBaseline(listOf(a, b))
            val sober = score(m, t0, held)
            if (sober.level == WalkLevel.LARGE_GAP) soberLarge++
            if (sober.level == WalkLevel.UNUSUAL) soberUnusual++
            val line = StringBuilder("person %2d: baseline %3d segs, sober %-9s %.2f (p%.0f) [%d segs, %d possible]".format(
                i + 1, m.report().baselineSegments, sober.level, sober.score, sober.percentile * 100, sober.segments, ((held.size - 400) / 200 + 1)))
            for ((sev, counts) in detect) {
                val (m2, t2) = monitorWithBaseline(listOf(a, b))
                val r = score(m2, t2, held.impaired(sev, seed = 100 + i))
                if (r.level == WalkLevel.UNUSUAL || r.level == WalkLevel.LARGE_GAP) counts[0]++
                if (r.level == WalkLevel.LARGE_GAP) counts[1]++
                line.append(", impaired x%.1f %-9s %.2f [%d segs]".format(sev, r.level, r.score, r.segments))
            }
            println(line)
            // Two other people's sober walks against this person's baseline.
            for (o in listOf((i + 1) % 24, (i + 7) % 24)) {
                val (m3, t3) = monitorWithBaseline(listOf(a, b))
                if (score(m3, t3, people[o].third).level == WalkLevel.LARGE_GAP) otherLarge++
                otherTotal++
            }
        }
        val n = people.size
        println("Sober held-out walks: large gap $soberLarge/$n, unusual $soberUnusual/$n")
        for ((sev, c) in detect) println("Impairment x$sev: flagged ${c[0]}/$n, large gap ${c[1]}/$n")
        println("Other people's walks flagged as large gap: $otherLarge/$otherTotal")

        // Measured: sober 1/24 large gap; strong 24/24 large gap; mild 23/24 flagged (18/24 large).
        assertTrue("sober false large-gap warnings $soberLarge/$n", soberLarge <= 1)
        assertTrue("strong impairment large-gap detections ${detect.getValue(1.0)[1]}/$n", detect.getValue(1.0)[1] >= 23)
        assertTrue("mild impairment flagged ${detect.getValue(0.5)[0]}/$n", detect.getValue(0.5)[0] >= 21)
    }
}
