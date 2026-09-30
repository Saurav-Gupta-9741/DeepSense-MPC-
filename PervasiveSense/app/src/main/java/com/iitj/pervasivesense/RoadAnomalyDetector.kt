package com.iitj.pervasivesense

data class RoadAnomalyEvent(
    val type: String, // "POTHOLE" or "SPEED_BREAKER"
    val timestamp: Long,
    val peakG: Float
)

class RoadAnomalyDetector {

    private val zAxisWindow = ArrayDeque<Float>(30)
    var potholesCount: Int = 0
        private set
    var speedBreakersCount: Int = 0
        private set

    private var lastAnomalyTime: Long = 0

    fun processSample(az: Float, isVehicular: Boolean): RoadAnomalyEvent? {
        if (!isVehicular) {
            zAxisWindow.clear()
            return null
        }

        zAxisWindow.addLast(az)
        if (zAxisWindow.size > 25) { // 0.5 sec window at 50Hz
            zAxisWindow.removeFirst()
        }

        if (zAxisWindow.size < 20) return null

        val now = System.currentTimeMillis()
        if (now - lastAnomalyTime < 1500) {
            // Debounce: don't double count within 1.5 seconds
            return null
        }

        val maxZ = zAxisWindow.maxOrNull() ?: 0f
        val minZ = zAxisWindow.minOrNull() ?: 0f
        val peakToPeak = maxZ - minZ

        // Pothole: Sharp asymmetric negative dip below -12 m/s² and violent rebound
        if (minZ < -11.0f && peakToPeak > 22.0f) {
            lastAnomalyTime = now
            potholesCount++
            return RoadAnomalyEvent("POTHOLE", now, peakToPeak)
        }

        // Speed breaker: Symmetric smooth wave with moderate peak-to-peak
        if (peakToPeak in 12.0f..21.0f && minZ > -10.0f) {
            lastAnomalyTime = now
            speedBreakersCount++
            return RoadAnomalyEvent("SPEED_BREAKER", now, peakToPeak)
        }

        return null
    }
}
