package com.iitj.pervasivesense

data class TransitionEvent(
    val fromActivity: String,
    val toActivity: String,
    val confidence: Float,
    val timestamp: Long
)

class ActivityTransitionDetector {
    private var previousActivity: String = "UNKNOWN"
    private var previousConfidence: Float = 0f
    
    private val _transitionLog = mutableListOf<TransitionEvent>()
    val transitionLog: List<TransitionEvent> get() = _transitionLog.toList()
    
    fun checkTransition(newActivity: String, newConfidence: Float): TransitionEvent? {
        var transition: TransitionEvent? = null
        
        if (previousActivity != "UNKNOWN" && newActivity != previousActivity && newConfidence > 0.75f) {
            transition = TransitionEvent(
                fromActivity = previousActivity,
                toActivity = newActivity,
                confidence = newConfidence,
                timestamp = System.currentTimeMillis()
            )
            
            _transitionLog.add(transition)
            if (_transitionLog.size > 100) {
                _transitionLog.removeAt(0)
            }
        }
        
        previousActivity = newActivity
        previousConfidence = newConfidence
        
        return transition
    }
    
    fun getTransitionCount(): Int {
        return _transitionLog.size
    }
    
    fun getLastTransition(): TransitionEvent? {
        return _transitionLog.lastOrNull()
    }
}
