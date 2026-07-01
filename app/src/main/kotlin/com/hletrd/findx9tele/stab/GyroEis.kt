package com.hletrd.findx9tele.stab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Gyroscope-driven electronic stabilization signal.
 *
 * Integrates angular velocity into an absolute orientation and subtracts a low-pass "intended
 * motion" estimate, leaving only high-frequency shake. The residual angles (radians) are handed to
 * the GL pipeline, which multiplies them by the EFFECTIVE focal length (native × teleconverter
 * magnification) to get the pixel/normalized shift — that is what makes stabilization act at the
 * true 300 mm field of view rather than the lens's native ~70 mm.
 *
 * The low-pass time constant (allowing slow intentional pans) and the axis/sign mapping in the GL
 * transform are the parts that need on-device tuning; the magnitude scaling is exact.
 */
class GyroEis(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastTimestamp = 0L

    // Integrated absolute rotation (rad) about device x (pitch), y (yaw), z (roll).
    private var angPitch = 0f
    private var angYaw = 0f
    private var angRoll = 0f

    // Low-pass "intended orientation" that slow pans are allowed to follow.
    private var smoothPitch = 0f
    private var smoothYaw = 0f
    private var smoothRoll = 0f

    @Volatile private var corrPitch = 0f
    @Volatile private var corrYaw = 0f
    @Volatile private var corrRoll = 0f

    val isAvailable: Boolean get() = gyroscope != null

    fun start() {
        reset()
        gyroscope?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        reset()
    }

    /** Latest high-frequency shake to counter: [0]=yaw, [1]=pitch, [2]=roll, all radians. */
    fun currentCorrection(): FloatArray = floatArrayOf(corrYaw, corrPitch, corrRoll)

    override fun onSensorChanged(event: SensorEvent) {
        if (lastTimestamp == 0L) { lastTimestamp = event.timestamp; return }
        val dt = (event.timestamp - lastTimestamp) * 1e-9f
        lastTimestamp = event.timestamp
        if (dt <= 0f || dt > 0.1f) return // drop gaps/first-sample glitches

        angPitch += event.values[0] * dt
        angYaw += event.values[1] * dt
        angRoll += event.values[2] * dt

        smoothPitch += LOW_PASS_ALPHA * (angPitch - smoothPitch)
        smoothYaw += LOW_PASS_ALPHA * (angYaw - smoothYaw)
        smoothRoll += LOW_PASS_ALPHA * (angRoll - smoothRoll)

        corrPitch = angPitch - smoothPitch
        corrYaw = angYaw - smoothYaw
        corrRoll = angRoll - smoothRoll
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun reset() {
        lastTimestamp = 0L
        angPitch = 0f; angYaw = 0f; angRoll = 0f
        smoothPitch = 0f; smoothYaw = 0f; smoothRoll = 0f
        corrPitch = 0f; corrYaw = 0f; corrRoll = 0f
    }

    private companion object {
        // Per-sample low-pass coefficient. ~1 Hz cutoff at typical gyro rates; lower = steadier hold,
        // higher = pans feel freer. Tune on device.
        const val LOW_PASS_ALPHA = 0.02f
    }
}
