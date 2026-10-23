package com.iitj.pervasivesense

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A learned segment of the user's own everyday walking. */
class BaselineEntry(val wallMillis: Long, val dayKey: String, val values: FloatArray)

/** How far one segment is from the user's normal walk. */
class GaitDeviation(
    /** Root-mean-square of the one-sided robust z-scores. */
    val distance: Float,
    /** Per-feature z-scores in the impairment direction (0 = normal or better); NaN if unused. */
    val z: FloatArray
)

/**
 * The user's personal model of normal walking: no drinking data, only their
 * own everyday gait.
 *
 * Each feature is summarised by its median and MAD (robust to the odd bad
 * segment). A segment's distance is the RMS of its z-scores counted only in
 * the direction impairment moves that feature (more sway, less regularity...),
 * so walking unusually steadily is never "far".
 *
 * A walk's distance is the MEDIAN of its segments' distances: most of the
 * walk has to be unusual. A few odd segments (a sharp turn, stopping at a
 * door, a kerb) do not make a walk unusual; impairment affects every segment.
 *
 * Thresholds are not fixed constants: they are percentiles of the same
 * statistic over the user's own learned walks (runs of consecutive segments,
 * which keeps the within-walk correlation), so "unusual" means more unusual
 * than 95 % of that person's normal walks and "large gap" more than 99 %.
 */
class GaitBaseline(
    private val capacity: Int = 900,
    private val maxPerDay: Int = 150,
    private val unusualQuantile: Float = 0.95f,
    private val largeQuantile: Float = 0.99f,
    private val unusualFloor: Float = 1.2f,
    private val largeFloor: Float = 1.8f,
    private val maxZ: Float = 8f
) {
    class Thresholds(val unusual: Float, val large: Float, private val nullScores: FloatArray) {
        /** Fraction of the user's own normal walks that were less unusual than [score]. */
        fun percentile(score: Float): Float {
            if (nullScores.isEmpty()) return 0f
            var below = 0
            for (s in nullScores) if (s < score) below++
            return below.toFloat() / nullScores.size
        }
    }

    private class Stats(val median: FloatArray, val scale: FloatArray, val used: BooleanArray, val distances: FloatArray)

    private val entries = ArrayDeque<BaselineEntry>()
    private var stats: Stats? = null
    private var addedSinceStats = 0
    private var dayCount = -1

    val size get() = entries.size
    /** Distinct days with learned walking (cached: read on every dashboard refresh). */
    val days: Int get() {
        if (dayCount < 0) dayCount = entries.mapTo(HashSet()) { it.dayKey }.size
        return dayCount
    }
    fun snapshot(): List<BaselineEntry> = entries.toList()

    /** Adds a segment; returns false if today's quota is already full. */
    fun add(e: BaselineEntry): Boolean {
        if (entries.count { it.dayKey == e.dayKey } >= maxPerDay) return false
        entries.addLast(e)
        while (entries.size > capacity) entries.removeFirst()
        dayCount = -1
        // Statistics are refreshed once 5 % of the baseline is new (O(n log n) on up to
        // [capacity] entries; a few new segments cannot move a median noticeably).
        if (++addedSinceStats >= max(1, entries.size / 20)) stats = null
        return true
    }

    fun clear() { entries.clear(); stats = null; dayCount = -1 }

    fun restore(list: List<BaselineEntry>) {
        entries.clear(); list.takeLast(capacity).forEach { entries.addLast(it) }; stats = null; dayCount = -1
    }

    fun deviation(values: FloatArray): GaitDeviation? {
        val st = stats() ?: return null
        return deviation(values, st)
    }

    /** Thresholds for a walk of [k] segments, scored with [walkDistance]. */
    fun thresholds(k: Int): Thresholds? {
        val st = stats() ?: return null
        val d = st.distances
        val run = k.coerceIn(1, max(1, d.size / 4))
        val sorted = FloatArray(d.size - run + 1) { walkDistance(d.copyOfRange(it, it + run)) }.sortedArray()
        val unusual = max(quantile(sorted, unusualQuantile), unusualFloor)
        val large = max(max(quantile(sorted, largeQuantile), largeFloor), unusual)
        return Thresholds(unusual, large, sorted)
    }

    private fun deviation(values: FloatArray, st: Stats): GaitDeviation {
        val z = FloatArray(GaitFeature.COUNT) { Float.NaN }
        var sum = 0.0; var n = 0
        for (f in GaitFeature.entries) {
            val i = f.ordinal
            val x = values[i]
            if (!st.used[i] || x.isNaN()) continue
            val raw = (x - st.median[i]) / st.scale[i]
            val one = when {
                f.direction > 0 -> max(raw, 0f)
                f.direction < 0 -> max(-raw, 0f)
                else -> abs(raw)
            }.coerceAtMost(maxZ)
            z[i] = one; sum += one * one; n++
        }
        return GaitDeviation(if (n == 0) 0f else sqrt(sum / n).toFloat(), z)
    }

    private fun stats(): Stats? {
        stats?.let { return it }
        if (entries.size < 4) return null
        addedSinceStats = 0
        val median = FloatArray(GaitFeature.COUNT)
        val scale = FloatArray(GaitFeature.COUNT)
        val used = BooleanArray(GaitFeature.COUNT)
        for (f in GaitFeature.entries) {
            val i = f.ordinal
            val xs = entries.map { it.values[i] }.filter { !it.isNaN() }.sorted()
            if (xs.size < max(4, entries.size / 2)) continue
            val med = medianSorted(xs)
            val mad = medianSorted(xs.map { abs(it - med) }.sorted())
            median[i] = med
            scale[i] = maxOf(1.4826f * mad, ABS_FLOOR[i], 0.05f * abs(med))
            used[i] = true
        }
        val partial = Stats(median, scale, used, FloatArray(0))
        val distances = FloatArray(entries.size) { deviation(entries[it].values, partial).distance }
        return Stats(median, scale, used, distances).also { stats = it }
    }

    companion object {
        /** A walk's distance from normal: the median of its segments' distances. */
        fun walkDistance(segmentDistances: FloatArray): Float {
            val s = segmentDistances.sortedArray()
            val n = s.size
            return if (n == 0) 0f else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }

        /** Smallest meaningful spread per feature (measurement resolution), by ordinal. */
        private val ABS_FLOOR = floatArrayOf(2f, 0.01f, 0.03f, 0.02f, 0.02f, 0.03f)

        private fun medianSorted(xs: List<Float>): Float {
            val n = xs.size
            return if (n % 2 == 1) xs[n / 2] else (xs[n / 2 - 1] + xs[n / 2]) / 2
        }

        private fun quantile(sorted: FloatArray, q: Float): Float {
            if (sorted.isEmpty()) return 0f
            val idx = min(sorted.size - 1, max(0, ceil(q * sorted.size).toInt() - 1))
            return sorted[idx]
        }
    }
}
