package com.iitj.pervasivesense

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Impairment-like gait, built from physics on top of a REAL walk (no labelled
 * intoxicated recordings are available; the field protocol uses impairment-
 * simulation goggles, see docs/FIT_TO_DRIVE.md). Effects reported for alcohol
 * at around the legal limit, scaled by [severity] (1.0 = strong):
 *
 *  - slower, irregular stepping: the recording is replayed with a playback
 *    rate that drifts randomly from step to step (±15 % at severity 1) and is
 *    8 % slower on average, which also degrades stride-to-stride regularity;
 *  - side-to-side staggering: band-limited (0.3-1.5 Hz) lateral acceleration
 *    of [swayRms] x severity, along the walker's side-to-side axis;
 *  - unsteady heading: band-limited (0.5-2.5 Hz) rotation about gravity of
 *    [yawRms] x severity.
 *
 * The side-to-side axis is derived here independently of the app code: the
 * horizontal direction perpendicular to the dominant (forward-backward)
 * horizontal acceleration.
 */
fun Recording.impaired(severity: Double, seed: Int = 11, swayRms: Double = 0.8, yawRms: Double = 0.4): Recording {
    val rnd = Random(seed)
    val (up, side) = verticalAndSideAxes()

    // Time warp: a rate knot every 0.5 s of output time (output is at most 1/0.6 x longer).
    val knots = DoubleArray((durationSec / 0.25).toInt() + 8) {
        ((1.0 - 0.08 * severity) * (1.0 + 0.15 * severity * rnd.nextGaussian())).coerceIn(0.6, 1.4)
    }
    val tOut = ArrayList<Long>(); val out = ArrayList<FloatArray>()
    val v = FloatArray(6)
    var src = 0.0 // seconds into the source recording
    var j = 0
    val swayWave = bandNoise(rnd, 0.3, 1.5)
    val yawWave = bandNoise(rnd, 0.5, 2.5)
    while (src < durationSec - 0.02) {
        val tj = j * 0.02
        at(t[0] + (src * 1e9).toLong(), v)
        val sway = (swayRms * severity * swayWave(tj)).toFloat()
        val yaw = (yawRms * severity * yawWave(tj)).toFloat()
        val row = v.copyOf()
        for (c in 0..2) { row[c] += side[c] * sway; row[c + 3] += up[c] * yaw }
        tOut += j * 20_000_000L; out += row
        val pos = tj / 0.5
        val k = pos.toInt()
        val rate = knots[k] + (pos - k) * (knots[k + 1] - knots[k])
        src += 0.02 * rate
        j++
    }
    return Recording(tOut.toLongArray(), out.toTypedArray())
}

/** Unit gravity axis and the side-to-side axis of a walking recording (phone frame). */
fun Recording.verticalAndSideAxes(): Pair<FloatArray, FloatArray> {
    val g = DoubleArray(3)
    for (r in data) for (c in 0..2) g[c] += r[c].toDouble()
    val gn = sqrt(g.sumOf { it * it })
    val u = DoubleArray(3) { g[it] / gn }
    // Dominant horizontal direction by power iteration on the horizontal covariance.
    val cov = Array(3) { DoubleArray(3) }
    for (r in data) {
        val p = (0..2).sumOf { r[it] * u[it] }
        val h = DoubleArray(3) { r[it] - p * u[it] }
        for (a in 0..2) for (b in 0..2) cov[a][b] += h[a] * h[b]
    }
    var m = doubleArrayOf(u[1] - u[2], u[2] - u[0], u[0] - u[1]) // any vector off the gravity axis
    repeat(100) {
        val n = DoubleArray(3) { a -> (0..2).sumOf { b -> cov[a][b] * m[b] } }
        val p = (0..2).sumOf { n[it] * u[it] }
        for (a in 0..2) n[a] -= p * u[a]
        val nn = sqrt(n.sumOf { it * it })
        m = DoubleArray(3) { n[it] / nn }
    }
    val side = doubleArrayOf(u[1] * m[2] - u[2] * m[1], u[2] * m[0] - u[0] * m[2], u[0] * m[1] - u[1] * m[0])
    return FloatArray(3) { u[it].toFloat() } to FloatArray(3) { side[it].toFloat() }
}

/** Sum of random sinusoids in [loHz, hiHz], scaled to unit RMS. */
fun bandNoise(rnd: Random, loHz: Double, hiHz: Double, components: Int = 8): (Double) -> Double {
    val f = DoubleArray(components) { loHz + (hiHz - loHz) * rnd.nextDouble() }
    val ph = DoubleArray(components) { 2 * PI * rnd.nextDouble() }
    val amp = sqrt(2.0 / components)
    return { t -> (0 until components).sumOf { amp * sin(2 * PI * f[it] * t + ph[it]) } }
}

/**
 * Standing still with the phone held flat against the chest: gravity along the
 * phone's +y axis, postural sway as band-limited horizontal acceleration of
 * [swayRms], small body rotation, and sensor noise.
 */
fun stance(seconds: Double, swayRms: Double, seed: Int = 3, gyroRms: Double = swayRms * 0.4): Recording {
    val rnd = Random(seed)
    val sx = bandNoise(rnd, 0.1, 2.0); val sz = bandNoise(rnd, 0.1, 2.0)
    val wx = bandNoise(rnd, 0.1, 2.0); val wy = bandNoise(rnd, 0.1, 2.0); val wz = bandNoise(rnd, 0.1, 2.0)
    val n = (seconds * 50).toInt()
    val k = swayRms / sqrt(2.0)
    return Recording(LongArray(n) { it * 20_000_000L }, Array(n) { i ->
        val t = i * 0.02
        floatArrayOf(
            (k * sx(t) + 0.02 * rnd.nextGaussian()).toFloat(),
            (9.80665 + 0.02 * rnd.nextGaussian()).toFloat(),
            (k * sz(t) + 0.02 * rnd.nextGaussian()).toFloat(),
            (gyroRms / sqrt(3.0) * wx(t)).toFloat(), (gyroRms / sqrt(3.0) * wy(t)).toFloat(), (gyroRms / sqrt(3.0) * wz(t)).toFloat()
        )
    })
}

/** Runs a recording through the gait extractor as the engine does (walking throughout). */
fun gaitSegments(rec: Recording, hasGyro: Boolean = true, t0: Long = 0L): List<GaitSegment> {
    val ex = GaitSegmentExtractor()
    val out = ArrayList<GaitSegment>()
    val s = FloatArray(6)
    for (i in 0 until rec.size) {
        val t = t0 + rec.t[i]
        rec.data[i].copyInto(s)
        if (!hasGyro) { s[3] = 0f; s[4] = 0f; s[5] = 0f }
        ex.processSample(t, s, walking = true, hasGyro = hasGyro)?.let { out += it }
    }
    return out
}

/** A MotionSense trial (Android units), trimmed of the first/last 3 s, or null if the file is missing. */
fun motionSense(root: String, trial: String, subject: Int): Recording? {
    val f = File(root, "A_DeviceMotion_data/$trial/sub_$subject.csv")
    if (!f.exists()) return null
    val rows = f.readLines().drop(1).filter { it.isNotBlank() }
    val g = 9.80665f
    val data = rows.map { line ->
        val p = line.split(',').map { it.toFloat() }
        // columns: idx, attitude x3, gravity x3, rotationRate x3, userAcceleration x3 (iOS, in g)
        floatArrayOf(-g * (p[4] + p[10]), -g * (p[5] + p[11]), -g * (p[6] + p[12]), p[7], p[8], p[9])
    }
    val trimmed = data.drop(150).dropLast(150)
    return Recording(LongArray(trimmed.size) { it * 20_000_000L }, trimmed.toTypedArray())
}
