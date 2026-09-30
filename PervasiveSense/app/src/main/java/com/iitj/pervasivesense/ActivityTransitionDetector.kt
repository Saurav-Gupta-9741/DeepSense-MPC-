package com.iitj.pervasivesense

data class TransitionEvent(
    val fromActivity: String,
    val toActivity: String,
    val confidence: Float,
    val wallTimeMillis: Long
)

/**
 * Logs changes of the STABLE (smoothed + hysteresis) context. Feeding it the
 * smoothed label - not raw per-window argmax - means single ambiguous windows
 * never produce spurious transitions. Each event keeps its own timestamp.
 */
class ActivityTransitionDetector(private val maxLog: Int = 100) {
    private var previous: String? = null
    private val log = ArrayDeque<TransitionEvent>()
    var totalTransitions = 0
        private set

    val transitionLog: List<TransitionEvent> get() = log.toList()
    val lastTransition: TransitionEvent? get() = log.lastOrNull()

    fun onStableContext(context: String, confidence: Float, wallTimeMillis: Long): TransitionEvent? {
        val prev = previous
        previous = context
        if (prev == null || prev == context) return null
        val e = TransitionEvent(prev, context, confidence, wallTimeMillis)
        log.addLast(e)
        if (log.size > maxLog) log.removeFirst()
        totalTransitions++
        return e
    }

    fun reset() { previous = null }
}
