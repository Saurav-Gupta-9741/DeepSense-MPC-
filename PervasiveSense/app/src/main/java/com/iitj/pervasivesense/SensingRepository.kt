package com.iitj.pervasivesense

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ServiceStatus { STOPPED, STARTING, RUNNING }

/**
 * In-process hub between the foreground service and the UI.
 *
 * Replaces the implicit per-window broadcasts: the dashboard observes
 * [snapshot]/[status] StateFlows, so it always renders the CURRENT state
 * immediately when opened (no stale "SERVICE STOPPED" next to a STOP button),
 * and the service learns whether anyone is watching to throttle work.
 */
object SensingRepository {
    private val _status = MutableStateFlow(ServiceStatus.STOPPED)
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _snapshot = MutableStateFlow<SensingSnapshot?>(null)
    val snapshot: StateFlow<SensingSnapshot?> = _snapshot.asStateFlow()

    /** Human-readable problem (missing permission, model load failure...), or null. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Commands from UI / receivers to the running service (null when stopped). */
    interface Controller {
        fun setUiVisible(visible: Boolean)
        fun acknowledgeFall()
        fun onVehicleConfidence(confidence: Int)
    }

    @Volatile private var controller: Controller? = null
    @Volatile var uiVisible: Boolean = false
        private set

    internal fun attach(c: Controller) {
        controller = c
        c.setUiVisible(uiVisible)
    }

    internal fun detach(c: Controller) {
        if (controller === c) controller = null
    }

    internal fun setStatus(s: ServiceStatus) { _status.value = s }
    internal fun publish(s: SensingSnapshot) { _snapshot.value = s }
    internal fun setMessage(m: String?) { _message.value = m }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        controller?.setUiVisible(visible)
    }

    fun acknowledgeFall() { controller?.acknowledgeFall() }

    fun deliverVehicleConfidence(confidence: Int) { controller?.onVehicleConfidence(confidence) }
}
