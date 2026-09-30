package com.iitj.pervasivesense

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

interface SensorDataCallback {
    fun onSensorData(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float)
}

interface SensorDataSource {
    fun start(callback: SensorDataCallback)
    fun stop()
    fun getSourceName(): String
    fun getSamplingRateHz(): Int
}

class PhoneIMUSource(private val context: Context) : SensorDataSource, SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var callback: SensorDataCallback? = null
    private var samplingDelay = SensorManager.SENSOR_DELAY_GAME
    
    private var lastGx = 0f
    private var lastGy = 0f
    private var lastGz = 0f

    override fun start(callback: SensorDataCallback) {
        this.callback = callback
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        accel?.let { sensorManager?.registerListener(this, it, samplingDelay) }
        gyro?.let { sensorManager?.registerListener(this, it, samplingDelay) }
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        callback = null
    }

    override fun getSourceName(): String {
        return "Phone IMU (Built-in)"
    }

    override fun getSamplingRateHz(): Int {
        return when (samplingDelay) {
            SensorManager.SENSOR_DELAY_FASTEST -> 100
            SensorManager.SENSOR_DELAY_GAME -> 50
            SensorManager.SENSOR_DELAY_UI -> 15
            SensorManager.SENSOR_DELAY_NORMAL -> 5
            else -> 50
        }
    }
    
    fun setSamplingDelay(delay: Int) {
        this.samplingDelay = delay
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGx = event.values[0]
                lastGy = event.values[1]
                lastGz = event.values[2]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                callback?.onSensorData(ax, ay, az, lastGx, lastGy, lastGz)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}

class BLEWearableSource : SensorDataSource {
    override fun start(callback: SensorDataCallback) {
        // TODO: Implement BLE scanning and GATT connection
        // TODO: Subscribe to IMU characteristics
    }

    override fun stop() {
        // TODO: Disconnect GATT server
    }

    override fun getSourceName(): String {
        return "BLE Wearable (Not Connected)"
    }

    override fun getSamplingRateHz(): Int {
        // TODO: Return actual configured rate of BLE device
        return 0
    }
}
