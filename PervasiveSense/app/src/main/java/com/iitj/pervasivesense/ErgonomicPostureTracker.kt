package com.iitj.pervasivesense

import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

data class ErgonomicReport(
    val postureTiltAngle: Float,
    val postureStatus: String,
    val fidgetIndex: Float,
    val continuousStillMinutes: Int,
    val needsBreakPrompt: Boolean
)

class ErgonomicPostureTracker {

    private val gravity = FloatArray(3) { 0f }
    private var lastPostureShiftTime = System.currentTimeMillis()
    private var baselineTiltAngle: Float = 0f
    private val dynamicAccelSamples = ArrayDeque<Float>(210)

    fun processSample(ax: Float, ay: Float, az: Float, isCurrentlyStill: Boolean): ErgonomicReport {
        // 1. Complementary Low-pass filter to isolate Gravity vector g
        val alpha = 0.85f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * ax
        gravity[1] = alpha * gravity[1] + (1 - alpha) * ay
        gravity[2] = alpha * gravity[2] + (1 - alpha) * az

        // 2. Compute Thigh Tilt Angle (Pitch) in degrees
        // When sitting with phone in pocket, Y and Z axes reflect thigh incline
        val tiltAngle = (atan2(gravity[1].toDouble(), gravity[2].toDouble()) * (180.0 / Math.PI)).toFloat()

        // 3. Dynamic acceleration magnitude (subtract static gravity)
        val dynX = ax - gravity[0]
        val dynY = ay - gravity[1]
        val dynZ = az - gravity[2]
        val dynMag = sqrt(dynX.pow(2) + dynY.pow(2) + dynZ.pow(2))

        dynamicAccelSamples.addLast(dynMag)
        if (dynamicAccelSamples.size > 200) { // Keep last ~4 seconds at 50Hz
            dynamicAccelSamples.removeFirst()
        }

        // Fidget Index = Variance of dynamic acceleration while sitting
        val mean = dynamicAccelSamples.average().toFloat()
        var variance = 0f
        for (sample in dynamicAccelSamples) {
            variance += (sample - mean).pow(2)
        }
        val fidgetIndex = if (dynamicAccelSamples.isNotEmpty()) variance / dynamicAccelSamples.size else 0f

        // 4. Posture Shift Detection
        val now = System.currentTimeMillis()
        if (Math.abs(tiltAngle - baselineTiltAngle) > 15.0f) {
            // User shifted sitting posture or adjusted seat
            baselineTiltAngle = tiltAngle
            lastPostureShiftTime = now
        }

        if (!isCurrentlyStill) {
            // User is walking or moving -> reset sitting timer
            lastPostureShiftTime = now
        }

        val continuousMinutes = ((now - lastPostureShiftTime) / 60000).toInt()
        val needsBreak = continuousMinutes >= 45

        val status = when {
            tiltAngle in -25.0f..25.0f -> "Upright / Ergonomic"
            tiltAngle < -25.0f -> "Forward Slouching"
            else -> "Reclined / Leaning"
        }

        return ErgonomicReport(
            postureTiltAngle = tiltAngle,
            postureStatus = status,
            fidgetIndex = fidgetIndex,
            continuousStillMinutes = continuousMinutes,
            needsBreakPrompt = needsBreak
        )
    }
}
