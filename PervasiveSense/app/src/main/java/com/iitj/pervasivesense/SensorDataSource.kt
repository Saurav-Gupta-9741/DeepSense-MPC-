package com.iitj.pervasivesense

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/**
 * Source of timestamped IMU events. The engine only depends on [ImuSink], so a
 * wearable (e.g. a BLE IMU) can be added by implementing this interface; it
 * must deliver hardware timestamps in nanoseconds on one monotonic clock.
 */
interface SensorDataSource {
    val sourceName: String
    val hasGyroscope: Boolean
    fun start(sink: ImuSink, handler: Handler): Boolean
    fun stop()
    /** Energy mode: true = let the sensor hub batch events (and wake the CPU itself if it can). */
    fun setBatching(enabled: Boolean)
    /** True while the source can guarantee delivery with the CPU allowed to sleep. */
    val deliversWhileCpuAsleep: Boolean
}

/**
 * Phone accelerometer + gyroscope at a 20 ms (50 Hz) sampling period.
 *
 * Energy mode uses the sensor-hub FIFO (maxReportLatencyUs) so events are
 * delivered in batches. If WAKE-UP variants of the sensors exist, the hub
 * itself wakes the CPU before the FIFO overflows, so the service can release
 * its wake lock while stationary; otherwise the non-wake-up sensors plus a
 * partial wake lock keep data continuous (required for fall detection).
 */
class PhoneImuSource(context: Context) : SensorDataSource, SensorEventListener {
    companion object {
        const val SAMPLING_PERIOD_US = 20_000
        const val MAX_BATCH_LATENCY_US = 2_000_000
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelWake: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
    private val gyroWake: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true)

    private var sink: ImuSink? = null
    private var handler: Handler? = null
    private var batching = false

    override val sourceName = "Phone IMU"
    override val hasGyroscope get() = gyro != null
    override var deliversWhileCpuAsleep = false
        private set

    override fun start(sink: ImuSink, handler: Handler): Boolean {
        if (accel == null) return false
        this.sink = sink
        this.handler = handler
        register()
        return true
    }

    override fun stop() {
        sm.unregisterListener(this)
        sink = null
    }

    override fun setBatching(enabled: Boolean) {
        if (enabled == batching || sink == null) return
        batching = enabled
        sm.unregisterListener(this)
        register()
    }

    private fun latencyFor(s: Sensor): Int {
        if (!batching) return 0
        // Never ask for more latency than the FIFO can hold (both sensors share it on many hubs).
        val events = s.fifoReservedEventCount
        if (events <= 0) return 0
        val sensors = if (gyro != null) 2 else 1
        val holdUs = events.toLong() * SAMPLING_PERIOD_US / sensors
        return minOf(MAX_BATCH_LATENCY_US.toLong(), holdUs * 8 / 10).toInt()
    }

    private fun register() {
        val h = handler
        val useWake = batching && accelWake != null && (gyro == null || gyroWake != null) &&
            accelWake.fifoReservedEventCount > 0
        val a = if (useWake) accelWake!! else accel!!
        val g = if (useWake) gyroWake else gyro
        sm.registerListener(this, a, SAMPLING_PERIOD_US, latencyFor(a), h)
        g?.let { sm.registerListener(this, it, SAMPLING_PERIOD_US, latencyFor(it), h) }
        deliversWhileCpuAsleep = useWake
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = sink ?: return
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> s.onAccelerometer(event.timestamp, v[0], v[1], v[2])
            Sensor.TYPE_GYROSCOPE -> s.onGyroscope(event.timestamp, v[0], v[1], v[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
