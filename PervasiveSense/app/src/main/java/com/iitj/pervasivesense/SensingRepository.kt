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

    /** A contact alert counting down (fit to drive), or null. */
    private val _alert = MutableStateFlow<AlertCountdown?>(null)
    val alert: StateFlow<AlertCountdown?> = _alert.asStateFlow()

    /** Commands from UI / receivers to the running service (null when stopped). */
    interface Controller {
        fun setUiVisible(visible: Boolean)
        fun acknowledgeFall()
        fun onVehicleConfidence(confidence: Int)
        fun setFitToDriveEnabled(enabled: Boolean)
        fun refreshFitSettings()
        fun requestFitCheck()
        fun startBalanceTest(purpose: BalancePurpose, practice: Boolean)
        fun cancelContactAlert()
        fun driverOk()
        fun startWalkRecording(position: CarryPosition)
        fun stopWalkRecording()
        fun sendTestAlert()
        fun cancelBalanceTest()
        fun startFitPractice(kind: PracticeWalk)
        fun cancelFitPractice()
        fun dismissFitAdvice()
        fun resetFitToDrive()
    }

    @Volatile private var controller: Controller? = null
    private var visibleScreens = 0
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

    /** The contact alert that could not be texted (phone, text): the screen offers to send it from Messages. */
    @Volatile var failedSms: Pair<String, String>? = null
        private set
    internal fun setFailedSms(s: Pair<String, String>?) { failedSms = s }

    /** The user handled the failed alert (sent it from Messages): clear the banner. */
    fun clearFailedSms() { failedSms = null; _message.value = null }
    internal fun setAlert(a: AlertCountdown?) { _alert.value = a }

    /**
     * Each screen reports onStart/onStop. Counted, because when one screen opens
     * another the new one starts before the old one stops.
     */
    @Synchronized
    fun setUiVisible(visible: Boolean) {
        visibleScreens = (visibleScreens + if (visible) 1 else -1).coerceAtLeast(0)
        val now = visibleScreens > 0
        if (now == uiVisible) return
        uiVisible = now
        controller?.setUiVisible(now)
    }

    fun acknowledgeFall() { controller?.acknowledgeFall() }

    fun deliverVehicleConfidence(confidence: Int) { controller?.onVehicleConfidence(confidence) }

    // Fit-to-drive check
    fun setFitToDriveEnabled(enabled: Boolean) { controller?.setFitToDriveEnabled(enabled) }
    fun refreshFitSettings() { controller?.refreshFitSettings() }
    fun requestFitCheck() { controller?.requestFitCheck() }
    fun startBalanceTest(purpose: BalancePurpose, practice: Boolean = false) { controller?.startBalanceTest(purpose, practice) }
    /** "Cancel – I'm a passenger". */
    fun cancelContactAlert() { controller?.cancelContactAlert() }
    /** "I'm OK" when asked about unsteady driving. */
    fun driverOk() { controller?.driverOk() }
    fun sendTestAlert() { controller?.sendTestAlert() }
    fun startWalkRecording(position: CarryPosition) { controller?.startWalkRecording(position) }
    fun stopWalkRecording() { controller?.stopWalkRecording() }
    fun cancelBalanceTest() { controller?.cancelBalanceTest() }
    fun startFitPractice(kind: PracticeWalk) { controller?.startFitPractice(kind) }
    fun cancelFitPractice() { controller?.cancelFitPractice() }
    fun dismissFitAdvice() { controller?.dismissFitAdvice() }
    fun resetFitToDrive() { controller?.resetFitToDrive() }
}
