package com.iitj.pervasivesense

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    val fallEvents: Int
)

/** Serializable daily state so the score survives service restarts. */
data class WellnessState(
    val dayKey: String,
    val steps: Int,
    val secondsByContext: Map<String, Double>,
    val slouchSeconds: Double,
    val fallEvents: Int,
    val potholes: Int,
    val speedBreakers: Int
)

/**
 * Daily composite wellness score:  0.3·Diversity + 0.3·Ergonomic + 0.2·Movement + 0.2·Safety
 *
 *  - Time is accumulated from real elapsed sensor time between updates
 *    (not an assumed 2.56 s per window).
 *  - Diversity counts contexts sustained for at least [minContextSeconds]
 *    today, so one misclassified window cannot inflate it.
 *  - Ergonomic = share of STILL time NOT spent slouching (seconds, not
 *    truncated minutes).
 *  - Movement uses real detected steps against [dailyStepGoal].
 *  - The day rolls over automatically when [ensureDay] sees a new day key.
 */
class WellnessScoreEngine(
    private val diversityTarget: Int = 5,
    private val minContextSeconds: Double = 60.0,
    private val dailyStepGoal: Int = 6000,
    private val penaltyPerFall: Int = 25
) {
    var dayKey: String = ""
        private set
    private var steps = 0
    private val seconds = mutableMapOf<String, Double>()
    private var slouchSeconds = 0.0
    private var fallEvents = 0
    var potholes = 0
    var speedBreakers = 0

    /** Returns true if the day changed and counters were reset. */
    fun ensureDay(key: String): Boolean {
        if (key == dayKey) return false
        val hadDay = dayKey.isNotEmpty()
        dayKey = key
        steps = 0; seconds.clear(); slouchSeconds = 0.0; fallEvents = 0; potholes = 0; speedBreakers = 0
        return hadDay
    }

    fun onContextElapsed(context: String, elapsedSeconds: Double, isSlouching: Boolean) {
        if (elapsedSeconds <= 0.0) return
        seconds[context] = (seconds[context] ?: 0.0) + elapsedSeconds
        if (context == "STILL" && isSlouching) slouchSeconds += elapsedSeconds
    }

    fun onSteps(newSteps: Int) { if (newSteps > 0) steps += newSteps }

    fun onFallEvent() { fallEvents++ }

    fun computeWellnessReport(): WellnessReport {
        val sustained = seconds.count { it.value >= minContextSeconds }
        val diversity = min(100, (sustained * 100.0 / diversityTarget).roundToInt())
        val still = seconds["STILL"] ?: 0.0
        val ergonomic = if (still < minContextSeconds) 100
            else (100.0 * (1.0 - slouchSeconds / still)).roundToInt().coerceIn(0, 100)
        val movement = min(100, (steps * 100.0 / dailyStepGoal).roundToInt())
        val safety = max(0, 100 - fallEvents * penaltyPerFall)
        val overall = (0.3 * diversity + 0.3 * ergonomic + 0.2 * movement + 0.2 * safety).roundToInt()
        return WellnessReport(
            overallScore = overall,
            activityDiversityScore = diversity,
            ergonomicScore = ergonomic,
            movementScore = movement,
            safetyScore = safety,
            totalSteps = steps,
            totalWalkMinutes = ((seconds["WALKING"] ?: 0.0) / 60.0).toInt(),
            totalStillMinutes = (still / 60.0).toInt(),
            slouchMinutes = (slouchSeconds / 60.0).toInt(),
            uniqueActivities = sustained,
            fallEvents = fallEvents
        )
    }

    fun exportState() = WellnessState(dayKey, steps, seconds.toMap(), slouchSeconds, fallEvents, potholes, speedBreakers)

    fun restore(state: WellnessState) {
        dayKey = state.dayKey; steps = state.steps
        seconds.clear(); seconds.putAll(state.secondsByContext)
        slouchSeconds = state.slouchSeconds; fallEvents = state.fallEvents
        potholes = state.potholes; speedBreakers = state.speedBreakers
    }
}
