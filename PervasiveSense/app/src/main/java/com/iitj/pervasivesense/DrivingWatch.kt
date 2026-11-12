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
 *    (a sudden jerk of the wheel or a swerve) and the car is thrown sideways
 *    ([Config.jerkMinLateralMs2]); the driver's body turning is not a jerk.
 *  - HARSH_BRAKING: horizontal acceleration of [Config.harshMs2] (about 0.4 g,
 *    the usual "harsh event" level): hard braking, hard acceleration or swerving.
 *
 * [erratic]: a weaving episode, or [Config.eventsForErratic] jerky/harsh events
 * within [Config.eventsWindowSec] (used after a fit-to-drive warning).
 * [clearlyErratic]: the higher bar for every other trip, where nothing else
 * suggests impairment: [Config.unwarnedPoints] points, a weaving episode counting
 * 2 and a jerky/harsh event 1 (three weaving episodes, about a minute of steady
 * weaving; or weaving plus hard brakes or jerks; or six of those).
 *
 * The phone moved by hand (picked up, put down, used while driving) moves on its own,
 * so nothing is judged then ([handled]): a hand tilts the phone fast or far, a car only
 * turns it about the vertical. Picked up with the screen on, it counts as in the hand
 * until the screen goes off or it rests still (put in a holder) for [Config.restingSec].
 *
 * Limits: weaving inside one lane at highway speed turns the car too little to
 * be seen; a phone in a trouser pocket also moves with the driver's leg; a very
 * twisty road (a curve every few seconds for minutes) can look like weaving. So
 * without a warning the driver is asked first ("Are you OK?"), and an alert always
 * has a cancel countdown. Pure Kotlin, 50 Hz samples [ax, ay, az, gx, gy, gz].
 */
class DrivingWatch(val config: Config = Config()) {
    data class Config(
        val swingRadS: Float = 0.05f,
        val weavingSwings: Int = 6,
        val weavingWindowSec: Float = 30f,
        val jerkRadS: Float = 0.4f,
        /**
         * A jerk of the wheel throws the car sideways; the driver's body turning (a phone in a shirt
         * or jacket pocket at a shoulder check) does not, even in a bend or while braking. So a
         * turn-rate jump is a jerk only if the sideways acceleration (0.5 s mean) changes by this much
         * from just before it, within [jerkLateralSec] (0: no such check). "Sideways" is across the
         * car's forward axis once that is learned (see [forwardMinMs2]); until then the whole
         * horizontal acceleration.
         */
        val jerkMinLateralMs2: Float = 1f,
        val jerkLateralSec: Float = 1f,
        /**
         * A swing this fast whose sideways acceleration changes less than [jerkMinLateralMs2] is the
         * driver's body turning, not the car: it is not a weaving swing (Float.MAX_VALUE: off).
         */
        val bodyTurnRadS: Float = 0.3f,
        /** On a straight road (turn rate below [straightRadS] for 1 s), braking or pulling away by this much shows the forward axis. */
        val forwardMinMs2: Float = 0.8f,
        val straightRadS: Float = 0.03f,
        val harshMs2: Float = 4f,
        val eventsForErratic: Int = 3,
        val eventsWindowSec: Float = 300f,
        /**
         * Without a warning, points needed within [eventsWindowSec]: a weaving episode counts 2,
         * a jerky or harsh event 1 (about a minute of steady weaving, or weaving plus several
         * hard brakes or jerks, or six of those).
         */
        val unwarnedPoints: Int = 6,
        val cooldownSec: Float = 3f,
        /** The phone tilting this fast (rad/s, 0.25 s mean) is a hand moving it: a car pitches and rolls far slower. */
        val handlingRadS: Float = 1.5f,
        /** ...or its 0.5 s mean direction this far (degrees) from the slow "up": braking at 1 g tilts it only 45°. */
        val handlingTiltDeg: Float = 55f,
        /** Nothing is judged for this long after the phone was moved (its new orientation settles first). */
        val handlingGraceSec: Float = 3f,
        /** Picked up with the screen on: in the hand until it tilts less than this (rad/s, 3 s mean)... */
        val restingRadS: Float = 0.15f,
        /** ...for this long (put in a holder), or the screen goes off. */
        val restingSec: Float = 10f
    ) {
        companion object {
            /**
             * "Test drive (no car)": turning on a swivel chair is the body turning with no sideways
             * acceleration, which a real drive ignores. So the test judges the turn rate alone.
             */
            fun forTestDrive() = Config(jerkMinLateralMs2 = 0f, bodyTurnRadS = Float.MAX_VALUE)
        }
    }

    private val dt = 0.02f
    private fun alpha(tauSec: Float) = (1 - exp(-dt / tauSec)).toFloat()
    private val aGravity = alpha(3f)
    private val aSlow = alpha(60f)
    private val aFast = alpha(0.25f)
    private val aMean = alpha(0.5f)
    private val aG0 = alpha(60f)
    private val aUp = alpha(20f)
    private val aRest = alpha(3f)
    private val cosHandling = kotlin.math.cos(Math.toRadians(config.handlingTiltDeg.toDouble())).toFloat()

    private val g = FloatArray(3)
    /** Slow "up" direction (20 s): splits the acceleration into vertical (bumps) and horizontal (braking). */
    private val up = FloatArray(3)
    private var started = false
    private var yawSlow = 0f
    private var yawFast = 0f
    private val mean = FloatArray(3)
    private var g0 = 0f
    private val jerkBuf = FloatArray(25) // 0.5 s of filtered turn rate
    private var jerkN = 0
    private var jerkI = 0
    private var samples = 0

    /** Sign of the swing in progress; [keptSign]: of the last swing that counts (counted swings alternate). */
    private var lobeSign = 0
    private var keptSign = 0
    private var lobeCounted = false
    private var lobePeak = 0f
    /** How far the sideways acceleration moved during the swing in progress, from its start. */
    private var lobeSideMax = 0f
    private val lobeStart = FloatArray(3)
    private val lobes = ArrayDeque<Long>()
    /** The car's forward axis in the phone's frame (an axis: braking and pulling away agree). */
    private val fwd = FloatArray(3)
    private var fwdEvidence = 0
    private var straightSince = Long.MIN_VALUE
    private val aFwd = alpha(2f)
    private val events = ArrayDeque<DrivingEvent>()
    private var lastJerkT = Long.MIN_VALUE
    /** A turn-rate jump waiting for the sideways acceleration that makes it a jerk of the wheel. */
    private var pendingJerkT = Long.MIN_VALUE
    private var pendingJerkSize = 0f
    private val pendingBase = FloatArray(3)
    /** The last 0.5 s of the horizontal acceleration vector: the "just before" of a turn-rate jump. */
    private val hBuf = FloatArray(25 * 3)
    private var hI = 0
    private var hN = 0
    private var lastHarshT = Long.MIN_VALUE

    // Phone handling: own filters, never reset while the phone is being moved.
    private val hg = FloatArray(3)
    private val hMean = FloatArray(3)
    private val hUp = FloatArray(3)
    private var hSamples = 0
    private var tiltFast = 0f
    private var tiltRest = 0f
    private var handledT = Long.MIN_VALUE
    private var inHand = false
    private var restingSince = Long.MIN_VALUE

    /** The phone is in the hand or was just moved by hand: the car's motion cannot be read from it. */
    var handledNow = false
        private set

    /** True while the last [Config.eventsWindowSec] contain a weaving episode or enough jerky/harsh events. */
    fun erratic(t: Long): Boolean {
        prune(t)
        return events.any { it.type == DrivingEventType.WEAVING } ||
            events.count { it.type != DrivingEventType.WEAVING } >= config.eventsForErratic
    }

    /**
     * The higher bar for a trip with no fit-to-drive warning (any trip of a sober driver could
     * reach the lower one, e.g. three hard stops in traffic): see the class description.
     */
    fun clearlyErratic(t: Long): Boolean {
        prune(t)
        val weaving = events.count { it.type == DrivingEventType.WEAVING }
        return 2 * weaving + (events.size - weaving) >= config.unwarnedPoints
    }

    /** Events of the last [Config.eventsWindowSec], oldest first. */
    fun recentEvents(t: Long): List<DrivingEvent> { prune(t); return events.toList() }

    fun reset() {
        started = false; samples = 0
        yawSlow = 0f; yawFast = 0f; mean.fill(0f); up.fill(0f); g0 = 0f
        jerkN = 0; jerkI = 0; pendingJerkT = Long.MIN_VALUE; hN = 0; hI = 0
        lobeSign = 0; keptSign = 0; lobeCounted = false; lobes.clear(); straightSince = Long.MIN_VALUE
    }

    /** Forgets the events and the phone's place in the car too (a new trip). */
    fun clear() {
        reset(); events.clear(); fwdEvidence = 0
        hSamples = 0; tiltFast = 0f; tiltRest = 0f; handledT = Long.MIN_VALUE; inHand = false
        restingSince = Long.MIN_VALUE; handledNow = false
    }

    /**
     * One 50 Hz sample in a vehicle; returns an event when one is detected. [screenOn]: a phone
     * picked up with the screen on is being used, until the screen goes off or it rests still.
     */
    fun processSample(t: Long, s: FloatArray, hasGyro: Boolean, screenOn: Boolean = false): DrivingEvent? {
        handledNow = handled(t, s, hasGyro, screenOn)
        if (handledNow) { reset(); fwdEvidence = 0; return null } // moved: a fresh start (and a new forward axis) once it settles
        if (!started) {
            g[0] = s[0]; g[1] = s[1]; g[2] = s[2]
            mean[0] = s[0]; mean[1] = s[1]; mean[2] = s[2]
            up[0] = s[0]; up[1] = s[1]; up[2] = s[2]
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
        // "Up" starts as the plain average so far (not the first sample's vibration), then a 20 s mean.
        val aU = maxOf(aUp, 1f / samples)
        for (i in 0 until 3) { mean[i] += aMean * (s[i] - mean[i]); up[i] += aU * (s[i] - up[i]) }
        val m2 = mean[0] * mean[0] + mean[1] * mean[1] + mean[2] * mean[2]
        val m = sqrt(m2)
        g0 = if (g0 == 0f) m else g0 + aG0 * (m - g0) // learns the sensor's own 1 g
        var event: DrivingEvent? = null
        // Both must be large: the length (orientation-free: the phone tipping in a pocket changes
        // only the direction) and the part across the slow "up" direction (a speed breaker or
        // pothole is vertical, so it changes the length but not this).
        val upN = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).coerceAtLeast(1e-3f)
        val vertical = (mean[0] * up[0] + mean[1] * up[1] + mean[2] * up[2]) / upN
        val h = minOf(sqrt(maxOf(0f, m2 - g0 * g0)), sqrt(maxOf(0f, m2 - vertical * vertical)))
        // The horizontal acceleration as a vector (for the forward and sideways axes).
        val vu = vertical / upN
        val hx = mean[0] - vu * up[0]; val hy = mean[1] - vu * up[1]; val hz = mean[2] - vu * up[2]
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
            if (settled) learnForward(t, y, hx, hy, hz)
            // The horizontal acceleration 0.5 s ago (the oldest in the ring).
            val o = if (hN < 25) 0 else hI
            val bx = if (hN == 0) hx else hBuf[3 * o]
            val by = if (hN == 0) hy else hBuf[3 * o + 1]
            val bz = if (hN == 0) hz else hBuf[3 * o + 2]
            hBuf[3 * hI] = hx; hBuf[3 * hI + 1] = hy; hBuf[3 * hI + 2] = hz
            hI = (hI + 1) % 25; if (hN < 25) hN++
            if (settled) {
                event = weaving(t, y, hx, hy, hz) ?: jerk(t, hx, hy, hz, bx, by, bz) ?: event
            }
        }
        if (event != null) events.addLast(event)
        return event
    }

    private fun secs(s: Float) = (s * 1e9f).toLong()

    /**
     * Picked up, put down or used: the hand tilts the phone fast (gyroscope) or far from the
     * slow "up" (no gyroscope needed). A car only turns it about the vertical, slowly pitches and
     * rolls it, and braking at 1 g tilts the mean acceleration by 45°.
     */
    private fun handled(t: Long, s: FloatArray, hasGyro: Boolean, screenOn: Boolean): Boolean {
        if (hSamples++ == 0) {
            for (i in 0 until 3) { hg[i] = s[i]; hMean[i] = s[i]; hUp[i] = s[i] }
            return false
        }
        for (i in 0 until 3) {
            hg[i] += aGravity * (s[i] - hg[i])
            hMean[i] += aMean * (s[i] - hMean[i])
            hUp[i] += aUp * (s[i] - hUp[i])
        }
        var moved = false
        val gn = sqrt(hg[0] * hg[0] + hg[1] * hg[1] + hg[2] * hg[2])
        if (hasGyro && gn >= 1f) {
            val along = (s[3] * hg[0] + s[4] * hg[1] + s[5] * hg[2]) / gn
            val tilt = sqrt(maxOf(0f, s[3] * s[3] + s[4] * s[4] + s[5] * s[5] - along * along))
            tiltFast += aFast * (tilt - tiltFast)
            tiltRest += aRest * (tilt - tiltRest)
            if (tiltFast >= config.handlingRadS) moved = true
        }
        val upN = sqrt(hUp[0] * hUp[0] + hUp[1] * hUp[1] + hUp[2] * hUp[2])
        val mN = sqrt(hMean[0] * hMean[0] + hMean[1] * hMean[1] + hMean[2] * hMean[2])
        if (hSamples > 150 && upN > 1f && mN > 1f &&
            (hMean[0] * hUp[0] + hMean[1] * hUp[1] + hMean[2] * hUp[2]) / (upN * mN) < cosHandling) moved = true
        if (moved) {
            handledT = t
            if (screenOn) { inHand = true; restingSince = Long.MIN_VALUE }
        }
        if (inHand) {
            // Without a gyroscope: in the hand until the screen goes off.
            val resting = hasGyro && !moved && tiltRest < config.restingRadS
            restingSince = if (!resting) Long.MIN_VALUE else if (restingSince == Long.MIN_VALUE) t else restingSince
            if (!screenOn || (restingSince != Long.MIN_VALUE && t - restingSince >= secs(config.restingSec))) {
                inHand = false
                handledT = t // settles from now
            }
        }
        return inHand || (handledT != Long.MIN_VALUE && t - handledT < secs(config.handlingGraceSec))
    }

    private fun cooledDown(t: Long, last: Long) =
        last == Long.MIN_VALUE || t - last >= (config.cooldownSec * 1e9f).toLong()

    /** Known once braking or pulling away on a straight road was seen for 0.5 s. */
    private val forwardKnown get() = fwdEvidence >= 25

    /** On a straight road the horizontal acceleration (braking, pulling away) lies along the forward axis. */
    private fun learnForward(t: Long, y: Float, hx: Float, hy: Float, hz: Float) {
        if (kotlin.math.abs(y) >= config.straightRadS) { straightSince = Long.MIN_VALUE; return }
        if (straightSince == Long.MIN_VALUE) straightSince = t
        val n = sqrt(hx * hx + hy * hy + hz * hz)
        if (t - straightSince < secs(1f) || n < config.forwardMinMs2 || n > 2 * config.harshMs2) return
        var ux = hx / n; var uy = hy / n; var uz = hz / n
        if (fwdEvidence > 0 && ux * fwd[0] + uy * fwd[1] + uz * fwd[2] < 0) { ux = -ux; uy = -uy; uz = -uz }
        if (fwdEvidence == 0) { fwd[0] = ux; fwd[1] = uy; fwd[2] = uz }
        else {
            fwd[0] += aFwd * (ux - fwd[0]); fwd[1] += aFwd * (uy - fwd[1]); fwd[2] += aFwd * (uz - fwd[2])
            val f = sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2]).coerceAtLeast(1e-6f)
            fwd[0] /= f; fwd[1] /= f; fwd[2] /= f
        }
        fwdEvidence++
    }

    private val lat = FloatArray(3)

    /** The car's sideways axis (up x forward) into [lat]; false while the forward axis is unknown. */
    private fun lateralAxis(): Boolean {
        if (!forwardKnown) return false
        val lx = up[1] * fwd[2] - up[2] * fwd[1]
        val ly = up[2] * fwd[0] - up[0] * fwd[2]
        val lz = up[0] * fwd[1] - up[1] * fwd[0]
        val n = sqrt(lx * lx + ly * ly + lz * lz)
        if (n < 1e-3f) return false
        lat[0] = lx / n; lat[1] = ly / n; lat[2] = lz / n
        return true
    }

    /**
     * How much the car was pushed sideways between two horizontal accelerations: across the forward
     * axis once it is known (braking then does not count), else the whole change. A vector change,
     * smooth, unlike the difference of two lengths of about 1 g.
     */
    private fun sideChange(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        if (lateralAxis()) return kotlin.math.abs(dx * lat[0] + dy * lat[1] + dz * lat[2])
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Counts alternating turn swings; a weaving episode once enough happen within the window. A
     * fast swing with no sideways acceleration (the driver's body turning, for example a shoulder
     * check with the phone in a shirt pocket) is dropped when it ends, and does not split a swing.
     */
    private fun weaving(t: Long, y: Float, hx: Float, hy: Float, hz: Float): DrivingEvent? {
        if (lobeSign != 0) {
            lobePeak = maxOf(lobePeak, kotlin.math.abs(y))
            lobeSideMax = maxOf(lobeSideMax, sideChange(hx, hy, hz, lobeStart[0], lobeStart[1], lobeStart[2]))
        }
        val sign = when {
            y >= config.swingRadS -> 1
            y <= -config.swingRadS -> -1
            else -> 0
        }
        if (sign == 0 || sign == lobeSign) return null
        // The swing that just ended: the body, not the car?
        if (lobeCounted && lobePeak >= config.bodyTurnRadS && lobeSideMax < config.jerkMinLateralMs2) {
            lobes.removeLastOrNull()
            keptSign = if (lobes.isEmpty()) 0 else -keptSign
        }
        lobeSign = sign
        lobePeak = kotlin.math.abs(y); lobeSideMax = 0f
        lobeStart[0] = hx; lobeStart[1] = hy; lobeStart[2] = hz
        lobeCounted = sign != keptSign
        if (!lobeCounted) return null // the same swing goes on after a body turn
        keptSign = sign
        lobes.addLast(t)
        val window = (config.weavingWindowSec * 1e9f).toLong()
        while (lobes.isNotEmpty() && t - lobes.first() > window) lobes.removeFirst()
        if (lobes.size < config.weavingSwings) return null
        val swings = lobes.size
        lobes.clear()
        lobeCounted = false
        return DrivingEvent(t, DrivingEventType.WEAVING, swings.toFloat())
    }

    /** [hx, hy, hz]: horizontal acceleration now (0.5 s mean); [bx, by, bz]: 0.5 s ago. */
    private fun jerk(t: Long, hx: Float, hy: Float, hz: Float, bx: Float, by: Float, bz: Float): DrivingEvent? {
        if (pendingJerkT != Long.MIN_VALUE) {
            // The turn rate jumped: a jerk of the wheel once the car is seen thrown sideways.
            if (sideChange(hx, hy, hz, pendingBase[0], pendingBase[1], pendingBase[2]) >= config.jerkMinLateralMs2) {
                pendingJerkT = Long.MIN_VALUE
                return DrivingEvent(t, DrivingEventType.JERKY_STEERING, pendingJerkSize)
            }
            if (t - pendingJerkT > secs(config.jerkLateralSec)) pendingJerkT = Long.MIN_VALUE // the body turned, not the car
            return null
        }
        if (jerkN < jerkBuf.size || !cooledDown(t, lastJerkT)) return null
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (x in jerkBuf) { if (x < lo) lo = x; if (x > hi) hi = x }
        if (hi - lo < config.jerkRadS) return null
        lastJerkT = t
        if (sideChange(hx, hy, hz, bx, by, bz) >= config.jerkMinLateralMs2) return DrivingEvent(t, DrivingEventType.JERKY_STEERING, hi - lo)
        pendingJerkT = t
        pendingJerkSize = hi - lo
        pendingBase[0] = bx; pendingBase[1] = by; pendingBase[2] = bz
        return null
    }

    private fun prune(t: Long) {
        val window = (config.eventsWindowSec * 1e9f).toLong()
        while (events.isNotEmpty() && t - events.first().tNanos > window) events.removeFirst()
    }
}

/**
 * One trip from the first vehicle detection until [endSec] without one. Vehicle
 * detection drops out at red lights and in traffic jams; without this, each restart
 * would be a "new trip", forgetting "I'm a passenger" and the driving seen so far.
 */
class TripTracker(private val endSec: Float = 180f) {
    private var active = false
    private var lastInVehicleT = Long.MIN_VALUE

    /** Returns true when a new trip starts. */
    fun update(t: Long, inVehicle: Boolean): Boolean {
        if (inVehicle) {
            lastInVehicleT = t
            if (!active) { active = true; return true }
        } else if (active && t - lastInVehicleT > (endSec * 1e9f).toLong()) {
            active = false
        }
        return false
    }
}
