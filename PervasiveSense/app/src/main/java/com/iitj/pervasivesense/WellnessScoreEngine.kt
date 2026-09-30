package com.iitj.pervasivesense

import kotlin.math.min
import kotlin.math.max

data class WellnessReport(
    val overallScore: Int,
    val activityDiversityScore: Int,
    val ergonomicScore: Int,
    val movementScore: Int,
    val safetyScore: Int,
    val totalSteps: Int,
    val totalWalkMinutes: Int,
    val totalStillMinutes: Int,
    val slouchMinutes: Int,
    val uniqueActivities: Int,
    val transitionsCount: Int
)

class WellnessScoreEngine {
    private var totalSteps = 0
    private val activityDurations = mutableMapOf<String, Float>()
    private var slouchSeconds = 0f
    private val uniqueActivities = mutableSetOf<String>()
    private var breaksTaken = 0
    private var previousActivity = "UNKNOWN"
    private var fallEventsCount = 0

    fun onClassificationResult(activity: String, postureStatus: String, elapsedSeconds: Float) {
        activityDurations[activity] = (activityDurations[activity] ?: 0f) + elapsedSeconds
        
        if (postureStatus.contains("Slouching", ignoreCase = true)) {
            slouchSeconds += elapsedSeconds
        }
        
        uniqueActivities.add(activity)
        
        if (activity in setOf("WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN")) {
            totalSteps += (elapsedSeconds * 2.0f).toInt()
        }
        
        if (previousActivity == "STILL" && activity != "STILL") {
            breaksTaken++
        }
        
        previousActivity = activity
    }
    
    fun onFallEvent() {
        fallEventsCount++
    }
    
    fun computeWellnessReport(): WellnessReport {
        val uniqueActCount = uniqueActivities.size
        val activityDiversityScore = min(100, (uniqueActCount / 5.0 * 100).toInt())
        
        val totalStillSeconds = activityDurations["STILL"] ?: 0f
        val totalStillMinutes = (totalStillSeconds / 60).toInt()
        val slouchMinutes = (slouchSeconds / 60).toInt()
        
        var ergonomicScore = 100
        if (totalStillMinutes > 0) {
            ergonomicScore = 100 - (slouchMinutes * 100 / totalStillMinutes)
            ergonomicScore = max(0, min(100, ergonomicScore))
        }
        
        val movementScore = min(100, (totalSteps / 6000.0 * 100).toInt())
        
        var safetyScore = 100 - (fallEventsCount * 25)
        safetyScore = max(0, safetyScore)
        
        val overallScore = (0.3 * activityDiversityScore + 0.3 * ergonomicScore + 0.2 * movementScore + 0.2 * safetyScore).toInt()
        
        val totalWalkMinutes = ((activityDurations["WALKING"] ?: 0f) / 60).toInt()
        
        return WellnessReport(
            overallScore = overallScore,
            activityDiversityScore = activityDiversityScore,
            ergonomicScore = ergonomicScore,
            movementScore = movementScore,
            safetyScore = safetyScore,
            totalSteps = totalSteps,
            totalWalkMinutes = totalWalkMinutes,
            totalStillMinutes = totalStillMinutes,
            slouchMinutes = slouchMinutes,
            uniqueActivities = uniqueActCount,
            transitionsCount = breaksTaken
        )
    }
    
    fun resetDaily() {
        totalSteps = 0
        activityDurations.clear()
        slouchSeconds = 0f
        uniqueActivities.clear()
        breaksTaken = 0
        previousActivity = "UNKNOWN"
        fallEventsCount = 0
    }
}
