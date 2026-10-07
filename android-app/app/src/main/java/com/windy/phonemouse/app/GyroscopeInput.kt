package com.windy.phonemouse.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/** Registered only while the visible app is actively controlling a connected computer. */
internal class GyroscopeInput(context: Context, private val onMove: (Double, Double) -> Unit,
    private val onFailure: (String) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val motion = GyroMotionController()
    private var settings = GyroSettings()
    private var rotation = 0
    private var gravityReady = false
    private val gravity = DoubleArray(3)
    val available get() = gyroscope != null
    var running = false
        private set
    var samplesReceived = 0
        private set

    fun configure(value: GyroSettings, displayRotation: Int) {
        val sanitized = value.sanitized()
        if (settings != sanitized || rotation != displayRotation) motion.reset()
        settings = sanitized; rotation = displayRotation
    }

    fun start(): Boolean {
        if (running) return true
        val sensor = gyroscope ?: return false
        motion.reset(); samplesReceived = 0; gravityReady = gravitySensor == null
        if (gravityReady) motion.gravity(0.0, 1.0, 0.0)
        running = true
        try {
            val gravityAccepted = gravitySensor?.let { manager.registerListener(this, it, 20_000, mainHandler) } ?: true
            val gyroAccepted = gravityAccepted && manager.registerListener(this, sensor, 10_000, mainHandler)
            if (gyroAccepted) return true
        } catch (_: RuntimeException) { /* Report below after releasing any partial registration. */ }
        stop()
        onFailure("体感传感器暂时不可用，请稍后重试或使用触控板。")
        return false
    }

    fun stop() {
        if (running) manager.unregisterListener(this)
        running = false; gravityReady = false; motion.setRepositioning(false); motion.reset()
    }

    fun setRepositioning(value: Boolean) { motion.setRepositioning(running && value) }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.size < 3) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) {
            for (i in 0..2) {
                val value = event.values[i].toDouble()
                gravity[i] = if (!gravityReady || event.sensor.type == Sensor.TYPE_GRAVITY) value
                    else gravity[i] * .9 + value * .1
            }
            gravityReady = motion.gravity(gravity[0], gravity[1], gravity[2])
        } else {
            samplesReceived++
            if (!gravityReady) return
            val delta = motion.sample(event.timestamp, event.values[0].toDouble(), event.values[1].toDouble(),
                event.values[2].toDouble(), rotation, settings)
            if (delta.x != 0.0 || delta.y != 0.0) onMove(delta.x, delta.y)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
