package com.iitj.pervasivesense

import kotlin.math.pow
import kotlin.math.sqrt

enum class FallState {
    MONITORING,
    IMPACT_SUSPECTED,
    FALL_CONFIRMED
}

class FallDetector {

    var currentState: FallState = FallState.MONITORING
        private set

    private var impactTimestamp: Long = 0
    private val postImpactBuffer = ArrayDeque<Float>(510)
    private val MAX_BUFFER_CAPACITY = 500 // ~10 seconds at 50Hz, safety cap
    private val MIN_SAMPLES_FOR_EVALUATION = 50 // ~1 second at 50Hz, prevents 1-sample variance=0 false positive

    fun processSample(ax: Float, ay: Float, az: Float): Boolean {
        val totalMagnitudeG = (sqrt(ax.pow(2) + ay.pow(2) + az.pow(2)) / 9.81f)
        val now = System.currentTimeMillis()

        when (currentState) {
            FallState.MONITORING -> {
                // High-G impact threshold (> 3.2g)
                if (totalMagnitudeG > 3.2f) {
                    currentState = FallState.IMPACT_SUSPECTED
                    impactTimestamp = now
                    postImpactBuffer.clear()
                }
            }

            FallState.IMPACT_SUSPECTED -> {
                if (postImpactBuffer.size < MAX_BUFFER_CAPACITY) {
                    postImpactBuffer.addLast(totalMagnitudeG)
                }
                val elapsed = now - impactTimestamp

                // Safety timeout: if stuck in IMPACT_SUSPECTED for >15s, reset
                if (elapsed > 15000) {
                    currentState = FallState.MONITORING
                    postImpactBuffer.clear()
                    return false
                }

                // Evaluate after 6 seconds of observation AND minimum 50 samples (~1s of real data)
                // The minimum sample guard prevents false positives when only 1-2 samples
                // produce a misleadingly low variance of 0.0
                if (elapsed >= 6000 && postImpactBuffer.size >= MIN_SAMPLES_FOR_EVALUATION) {
                    val mean = postImpactBuffer.average().toFloat()
                    var variance = 0f
                    for (v in postImpactBuffer) {
                        variance += (v - mean).pow(2)
                    }
                    val motionVariance = variance / postImpactBuffer.size

                    if (motionVariance < 0.2f) {
                        // User is completely immobile after impact -> Real fall confirmed
                        currentState = FallState.FALL_CONFIRMED
                        return true
                    } else {
                        // User moved or phone was casually tossed -> False alarm rejected
                        currentState = FallState.MONITORING
                        postImpactBuffer.clear()
                    }
                }
            }

            FallState.FALL_CONFIRMED -> {
                // If user eventually gets up and walks for > 5 seconds, reset
                if (totalMagnitudeG in 0.8f..1.5f && (now - impactTimestamp > 15000)) {
                    currentState = FallState.MONITORING
                }
            }
        }

        return false
    }

    fun reset() {
        currentState = FallState.MONITORING
        postImpactBuffer.clear()
    }
}
