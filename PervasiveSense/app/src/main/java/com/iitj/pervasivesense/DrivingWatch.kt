package com.iitj.pervasivesense

import kotlin.math.exp
import kotlin.math.sqrt

enum class DrivingEventType { WEAVING, JERKY_STEERING, HARSH_BRAKING }

class DrivingEvent(val tNanos: Long, val type: DrivingEventType, val magnitude: Float)

/**
 * How the vehicle is being driven, from the phone wherever it is (pocket, bag,
 * holder). Only orientation-free signals are used:
 *
 *  - Turn rate (yaw) = gyroscope · gravity direction: the car turning, whatever
 *    the phone's orientation. Band-passed (60 s high-pass, 0.25 s low-pass) so
 *    gyro bias, long bends, road vibration and leg/pedal movement drop out.
 *  - Horizontal acceleration from the 0.5 s mean acceleration vector m (vibration
 *    cancels): h = √(|m|² − g²), with g the long-run mean of |m|. A length, so the
 *    phone's orientation, and the phone tilting in a pocket, do not matter.
 *
 * Events:
 *  - WEAVING: [Config.weavingSwings] alternating left/right turn swings of at
 *    least [Config.swingRadS] within [Config.weavingWindowSec]. A turn is one
 *    swing, a lane change two, a roundabout about three.
 *  - JERKY_STEERING: the turn rate changes by [Config.jerkRadS] within 0.5 s
 *    (a sudden jerk of the wheel or a swerve).
 *  - HARSH_BRAKING: horizontal acceleration of [Config.harshMs2] (about 0.4 g,
 *    the usual "harsh event" level): hard braking, hard acceleration or swerving.
 *
 * [erratic]: a weaving episode, or [Config.eventsForErratic] jerky/harsh events
 * within [Config.eventsWindowSec].
 *
 * Limits: weaving inside one lane at highway speed turns the car too little to
 * be seen; a phone in a trouser pocket also moves with the driver's leg. It
 * therefore only acts after a fit-to-drive warning, and an alert always has a
 * cancel countdown. Pure Kotlin, 50 Hz samples [ax, ay, az, gx, gy, gz].
 */
class DrivingWatch(val config: Config = Config()) {
    data class Config(
        val swingRadS: Float = 0.05f,
        val weavingSwings: Int = 6,
        val weavingWindowSec: Float = 30f,
        val jerkRadS: Float = 0.4f,
        val harshMs2: Float = 4f,
        val eventsForErratic: Int = 3,
        val eventsWindowSec: Float = 300f,
        val cooldownSec: Float = 3f
    )

    private val dt = 0.02f
    private fun alpha(tauSec: Float) = (1 - exp(-dt / tauSec)).toFloat()
    private val aGravity = alpha(3f)
    private val aSlow = alpha(60f)
    private val aFast = alpha(0.25f)
    private val aMean = alpha(0.5f)
    private val aG0 = alpha(60f)

    private val g = FloatArray(3)
    private var started = false
    private var yawSlow = 0f
    private var yawFast = 0f
    private val mean = FloatArray(3)
    private var g0 = 0f
    private val jerkBuf = FloatArray(25) // 0.5 s of filtered turn rate
    private var jerkN = 0
    private var jerkI = 0
    private var samples = 0

    private var lobeSign = 0
    private val lobes = ArrayDeque<Long>()
    private val events = ArrayDeque<DrivingEvent>()
    private var lastJerkT = Long.MIN_VALUE
    private var lastHarshT = Long.MIN_VALUE

    /** True while the last [Config.eventsWindowSec] contain a weaving episode or enough jerky/harsh events. */
    fun erratic(t: Long): Boolean {
        prune(t)
        return events.any { it.type == DrivingEventType.WEAVING } ||
            events.count { it.type != DrivingEventType.WEAVING } >= config.eventsForErratic
    }

    /** Events of the last [Config.eventsWindowSec], oldest first. */
    fun recentEvents(t: Long): List<DrivingEvent> { prune(t); return events.toList() }

    fun reset() {
        started = false; samples = 0
        yawSlow = 0f; yawFast = 0f; mean.fill(0f); g0 = 0f
        jerkN = 0; jerkI = 0
        lobeSign = 0; lobes.clear()
    }

    /** Forgets the events too (a new trip). */
    fun clear() { reset(); events.clear() }

    /** One 50 Hz sample in a vehicle; returns an event when one is detected. */
    fun processSample(t: Long, s: FloatArray, hasGyro: Boolean): DrivingEvent? {
        if (!started) {
            g[0] = s[0]; g[1] = s[1]; g[2] = s[2]
            mean[0] = s[0]; mean[1] = s[1]; mean[2] = s[2]
            started = true
        }
        for (i in 0 until 3) g[i] += aGravity * (s[i] - g[i])
        val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gn < 1f) return null
        val ux = g[0] / gn; val uy = g[1] / gn; val uz = g[2] / gn
        samples++
        // Let the gravity estimate settle before judging anything.
        val settled = samples > 150

        // Horizontal acceleration: length of the 0.5 s mean vector against gravity's length.
        for (i in 0 until 3) mean[i] += aMean * (s[i] - mean[i])
        val m2 = mean[0] * mean[0] + mean[1] * mean[1] + mean[2] * mean[2]
        val m = sqrt(m2)
        g0 = if (g0 == 0f) m else g0 + aG0 * (m - g0) // learns the sensor's own 1 g
        var event: DrivingEvent? = null
        val h = sqrt(maxOf(0f, m2 - g0 * g0))
        if (settled && h >= config.harshMs2 && cooledDown(t, lastHarshT)) {
            lastHarshT = t
            event = DrivingEvent(t, DrivingEventType.HARSH_BRAKING, h)
        }

        if (hasGyro) {
            val yaw = s[3] * ux + s[4] * uy + s[5] * uz
            yawSlow += aSlow * (yaw - yawSlow)
            yawFast += aFast * (yaw - yawFast)
            val y = yawFast - yawSlow
            jerkBuf[jerkI] = yawFast
            jerkI = (jerkI + 1) % jerkBuf.size
            if (jerkN < jerkBuf.size) jerkN++
            if (settled) {
                event = weaving(t, y) ?: jerk(t) ?: event
            }
        }
        if (event != null) events.addLast(event)
        return event
    }

    private fun cooledDown(t: Long, last: Long) =
        last == Long.MIN_VALUE || t - last >= (config.cooldownSec * 1e9f).toLong()

    /** Counts alternating turn swings; a weaving episode once enough happen within the window. */
    private fun weaving(t: Long, y: Float): DrivingEvent? {
        val sign = when {
            y >= config.swingRadS -> 1
            y <= -config.swingRadS -> -1
            else -> 0
        }
        if (sign == 0 || sign == lobeSign) return null
        lobeSign = sign
        lobes.addLast(t)
        val window = (config.weavingWindowSec * 1e9f).toLong()
        while (lobes.isNotEmpty() && t - lobes.first() > window) lobes.removeFirst()
        if (lobes.size < config.weavingSwings) return null
        val swings = lobes.size
        lobes.clear()
        return DrivingEvent(t, DrivingEventType.WEAVING, swings.toFloat())
    }

    private fun jerk(t: Long): DrivingEvent? {
        if (jerkN < jerkBuf.size || !cooledDown(t, lastJerkT)) return null
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (x in jerkBuf) { if (x < lo) lo = x; if (x > hi) hi = x }
        if (hi - lo < config.jerkRadS) return null
        lastJerkT = t
        return DrivingEvent(t, DrivingEventType.JERKY_STEERING, hi - lo)
    }

    private fun prune(t: Long) {
        val window = (config.eventsWindowSec * 1e9f).toLong()
        while (events.isNotEmpty() && t - events.first().tNanos > window) events.removeFirst()
    }
}
