package com.iitj.pervasivesense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

private val pocket1 by lazy { Recording.fixture("walking_pocket") }
private val pocket2 by lazy { Recording.fixture("walking_session2_pocket") }
private val pocket3 by lazy { Recording.fixture("walking_session3_pocket") }

/**
 * The same walk with the phone on the torso (jacket, belt): the body's motion reaches the
 * phone damped, without the leg swing (40 % of the dynamic acceleration and rotation).
 */
private fun Recording.onTorso(): Recording {
    val g = FloatArray(3)
    for (d in data) for (k in 0..2) g[k] += d[k] / size
    return Recording(t.copyOf(), Array(size) { i ->
        val d = data[i]
        FloatArray(6) { k -> if (k < 3) g[k] + 0.4f * (d[k] - g[k]) else 0.4f * d[k] }
    })
}

/** Carrying the phone anywhere on the body: one learned walk per position, each check uses the matching one. */
class FitToDrivePositionsTest {
    private val config = FitToDriveMonitor.Config(segmentsForProvisional = 15, maxPerDay = 10_000)
    private val events = ArrayList<FitToDriveEvent>()
    private fun monitor() = FitToDriveMonitor({ it / 1_000_000 }, { "2026-11-09" }, { events += it }, config).apply { setEnabled(true) }
    private val start = 1_000_000_000_000L
    private val gap = 10_000_000_000L

    private fun feed(m: FitToDriveMonitor, rec: Recording, t0: Long, walking: Boolean = true, inPocket: Boolean = true): Long {
        val s = FloatArray(6)
        for (i in 0 until rec.size) { rec.data[i].copyInto(s); m.processSample(t0 + rec.t[i], s, walking, hasGyro = true, inPocket = inPocket) }
        return t0 + rec.t[rec.size - 1]
    }

    private fun record(m: FitToDriveMonitor, p: CarryPosition, recs: List<Recording>, t0: Long, inPocket: Boolean = true): Long {
        assertTrue(m.startWalkRecording(t0, p))
        var t = t0
        for (r in recs) { t = feed(m, r, t + gap, inPocket = inPocket); m.onGap() }
        assertTrue("$p learned", p in m.report().learnedPositions)
        return t
    }

    private fun check(m: FitToDriveMonitor, rec: Recording, t0: Long, inPocket: Boolean = true): Pair<WalkCheck, Long> {
        m.requestCheck(t0)
        var t = feed(m, rec.slice(0, minOf(rec.size, 29 * 50)), t0 + 500_000_000L, inPocket = inPocket)
        // Stand still until the 30 s of the check are over (some test walks are shorter).
        t = feed(m, Recording.constant(maxOf(3.0, 31.0 - (t - t0) / 1e9), 0f, 0f, 9.81f), t, walking = false, inPocket = inPocket)
        return events.filterIsInstance<FitToDriveEvent.WalkChecked>().last().check to t + gap
    }

    private fun Recording.slice(from: Int, to: Int) = Recording(LongArray(to - from) { it * 20_000_000L }, Array(to - from) { data[from + it] })

    @Test
    fun eachPositionHasItsOwnWalkAndChecksUseTheMatchingOne() {
        val m = monitor()
        var t = record(m, CarryPosition.POCKET, listOf(pocket1, pocket2), start)
        t = record(m, CarryPosition.BODY, listOf(pocket1.onTorso(), pocket2.onTorso()), t + gap)
        assertEquals(setOf(CarryPosition.POCKET, CarryPosition.BODY), m.report().learnedPositions)

        // The held-out walk in the pocket is matched to the pocket, and is normal.
        val (a, t1) = check(m, pocket3, t + gap)
        assertEquals(CarryPosition.POCKET, a.position)
        assertEquals(WalkLevel.NORMAL, a.level)
        // The same walk on the torso is matched to the torso, and is normal too.
        val (b, t2) = check(m, pocket3.onTorso(), t1)
        assertEquals(CarryPosition.BODY, b.position)
        assertEquals(WalkLevel.NORMAL, b.level)
        assertFalse(b.unfamiliarPosition)
        // An unsteady walk is caught in either position.
        val (c, t3) = check(m, pocket3.impaired(1.0), t2)
        assertEquals(WalkLevel.LARGE_GAP, c.level)
        m.dismissAdvice()
        val (d, _) = check(m, pocket3.impaired(1.0).onTorso(), t3)
        assertEquals(WalkLevel.LARGE_GAP, d.level)
    }

    @Test
    fun aWalkInTheHandIsComparedWithAWalkRecordedInTheHandOnly() {
        val m = monitor()
        var t = record(m, CarryPosition.POCKET, listOf(pocket1, pocket2), start)
        // Held and looked at (screen on, uncovered) but never recorded that way: it says so, no guess.
        val (a, t1) = check(m, pocket3, t + gap, inPocket = false)
        assertEquals(NotScoredReason.POSITION_NOT_LEARNED, a.notScored)
        // Recorded in the hand once: now checked against it.
        t = record(m, CarryPosition.HAND, listOf(pocket1, pocket2), t1, inPocket = false)
        val (b, _) = check(m, pocket3, t + gap, inPocket = false)
        assertEquals(CarryPosition.HAND, b.position)
        assertEquals(WalkLevel.NORMAL, b.level)
    }

    @Test
    fun recordingInThePocketWhileHoldingThePhoneLearnsNothing() {
        val m = monitor()
        assertTrue(m.startWalkRecording(start, CarryPosition.POCKET))
        feed(m, pocket2, start, inPocket = false)
        assertTrue(m.exportState().baseline.isEmpty())
        assertTrue(events.filterIsInstance<FitToDriveEvent.RecordingIssueChanged>().any { it.issue == RecordingIssue.NOT_IN_POCKET })
    }

    @Test
    fun positionsSurviveARestart() {
        val m = monitor()
        val t = record(m, CarryPosition.BODY, listOf(pocket1.onTorso(), pocket2.onTorso()), start)
        val m2 = monitor()
        m2.restore(m.exportState())
        assertEquals(setOf(CarryPosition.BODY), m2.report().learnedPositions)
        assertTrue(m2.exportState().baseline.all { it.position == CarryPosition.BODY && it.signature != null })
        val (c, _) = check(m2, pocket3.onTorso(), t + gap)
        assertEquals(CarryPosition.BODY, c.position)
    }

    /**
     * Accuracy per position on 10 real people (Shoaib et al. 2014, SHOAIB_DIR; skipped without it).
     * Each person records three positions (60 % of their walk): trouser pocket (left), hand
     * (wrist: an arm swinging with the phone), elsewhere on the body (belt). The other 40 % is
     * checked as it is (sober) and with simulated impairment; the right pocket (not recorded,
     * should match the left) and the upper arm (never recorded) are checked too.
     */
    @Test
    fun accuracyPerPositionOnTenPeople() {
        val root = System.getenv("SHOAIB_DIR")
        assumeTrue("SHOAIB_DIR not set", root != null)
        val recorded = mapOf(ShoaibPosition.LEFT_POCKET to CarryPosition.POCKET, ShoaibPosition.WRIST to CarryPosition.HAND,
            ShoaibPosition.BELT to CarryPosition.BODY)
        val expect = recorded + (ShoaibPosition.RIGHT_POCKET to CarryPosition.POCKET)
        class Row { var n = 0; var posOk = 0; var soberLarge = 0; var soberUnusual = 0; var impUnusualOrLarge = 0; var impLarge = 0; var unfamiliar = 0; var impUnfamiliar = 0; var impWrongPos = 0 }
        val rows = ShoaibPosition.entries.associateWith { Row() }
        for (p in 1..10) {
            val walks = ShoaibPosition.entries.associateWith { shoaibWalk(root!!, p, it)!! }
            val trained = monitor()
            var t = start
            for ((sp, cp) in recorded) {
                val w = walks.getValue(sp)
                t = record(trained, cp, listOf(w.slice(0, w.size * 6 / 10)), t + gap)
            }
            val state = trained.exportState()
            for (sp in ShoaibPosition.entries) {
                val w = walks.getValue(sp)
                val held = w.slice(w.size * 6 / 10, w.size)
                val row = rows.getValue(sp)
                for (impaired in listOf(false, true)) {
                    val m = monitor(); m.restore(state)
                    val (c, _) = check(m, if (impaired) held.impaired(1.0, seed = 70 + p) else held, t + gap)
                    if (!impaired) {
                        row.n++
                        if (c.position == expect[sp]) row.posOk++
                        if (c.level == WalkLevel.LARGE_GAP) row.soberLarge++
                        if (c.level == WalkLevel.UNUSUAL) row.soberUnusual++
                        if (c.unfamiliarPosition) row.unfamiliar++
                    } else {
                        if (c.level == WalkLevel.UNUSUAL || c.level == WalkLevel.LARGE_GAP || c.notScored == NotScoredReason.TOO_IRREGULAR) row.impUnusualOrLarge++
                        if (c.level == WalkLevel.LARGE_GAP) row.impLarge++
                        if (c.unfamiliarPosition) row.impUnfamiliar++
                        if (c.position != expect[sp]) row.impWrongPos++
                    }
                }
            }
        }
        println("POSITIONS position            matched  sober: large  unusual  unfamiliar | impaired x1.0: caught (unusual+)  large  unfamiliar  other-position")
        for ((sp, r) in rows) println("POSITIONS %-13s %8s %12s %8s %11s | %22s %7s".format(sp, "${r.posOk}/${r.n}",
            "${r.soberLarge}/${r.n}", "${r.soberUnusual}/${r.n}", "${r.unfamiliar}/${r.n}", "${r.impUnusualOrLarge}/${r.n}", "${r.impLarge}/${r.n}") +
            "  %10s  %14s".format("${r.impUnfamiliar}/${r.n}", "${r.impWrongPos}/${r.n}"))
        for (sp in recorded.keys + ShoaibPosition.RIGHT_POCKET) {
            val r = rows.getValue(sp)
            assertTrue("$sp position matched ${r.posOk}/${r.n}", r.posOk >= 8)
            assertTrue("$sp sober large gaps ${r.soberLarge}/${r.n}", r.soberLarge <= 2)
            assertTrue("$sp impaired caught ${r.impUnusualOrLarge}/${r.n}", r.impUnusualOrLarge >= 9)
        }
        // Never recorded (upper arm): no false "very different" warnings, the balance check is offered instead.
        val arm = rows.getValue(ShoaibPosition.UPPER_ARM)
        assertTrue("upper arm sober large gaps ${arm.soberLarge}/10", arm.soberLarge <= 1)
        assertTrue(arm.impUnusualOrLarge >= 9)
    }
}
