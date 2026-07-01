package com.hletrd.findx9tele.stab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

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
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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

    // Absolute roll (degrees) derived from the accelerometer's gravity direction, for the
    // horizon/level overlay. Unlike [corrRoll] (integrated gyro, high-pass only, drifts and is
    // blind to slow tilt) this tracks the device's true tilt and does not drift over time.
    @Volatile private var rollDegrees = 0f

    val isAvailable: Boolean get() = gyroscope != null

    fun start() {
        reset()
        gyroscope?.let { sensorManager?.registerListener(this, it, SAMPLING_PERIOD_US) }
        accelerometer?.let { sensorManager?.registerListener(this, it, SAMPLING_PERIOD_US) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        reset()
    }

    /** Latest high-frequency shake to counter: [0]=yaw, [1]=pitch, [2]=roll, all radians. */
    fun currentCorrection(): FloatArray = floatArrayOf(corrYaw, corrPitch, corrRoll)

    /**
     * Latest absolute device roll in degrees (0° = upright portrait), derived from gravity via
     * the accelerometer. For the horizon/level overlay — does not drift like integrated gyro.
     */
    fun currentRollDegrees(): Float = rollDegrees

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
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
            Sensor.TYPE_ACCELEROMETER -> {
                // Portrait-locked device: gravity points along -y when upright, so roll is the
                // angle of the gravity vector in the x/y plane. Lightly low-passed to kill jitter.
                val x = event.values[0]
                val y = event.values[1]
                val rollDeg = Math.toDegrees(atan2(x, y).toDouble()).toFloat()
                rollDegrees += ROLL_LOW_PASS_ALPHA * (rollDeg - rollDegrees)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun reset() {
        lastTimestamp = 0L
        angPitch = 0f; angYaw = 0f; angRoll = 0f
        smoothPitch = 0f; smoothYaw = 0f; smoothRoll = 0f
        corrPitch = 0f; corrYaw = 0f; corrRoll = 0f
        rollDegrees = 0f
    }

    private companion object {
        // Sensors are registered at an explicit ~200 Hz sampling period (5000 µs) rather than
        // SENSOR_DELAY_FASTEST (~500-1000 Hz) — the GL loop only consumes the result once per
        // rendered frame (30-60 Hz), so anything faster is wasted CPU/battery with no
        // stabilization-quality benefit.
        const val SAMPLING_PERIOD_US = 5000

        // Per-sample low-pass coefficient for the gyro's "intended orientation" estimate. This is
        // a per-SAMPLE coefficient, so it assumes the ~200 Hz gyroscope sampling period set above
        // (SAMPLING_PERIOD_US) and yields roughly a 1-2 Hz corner (previously 0.02 at the
        // uncapped ~500-1000 Hz SENSOR_DELAY_FASTEST rate — same corner, different sample rate):
        // slow intentional pans are followed, shake above the corner is left as residual to
        // correct. Retune alongside SAMPLING_PERIOD_US if the sampling rate changes.
        const val LOW_PASS_ALPHA = 0.1f

        // Per-sample low-pass coefficient for the accelerometer-derived absolute roll. Heavier
        // smoothing is unnecessary here since it only feeds a UI overlay, not EIS correction.
        const val ROLL_LOW_PASS_ALPHA = 0.2f
    }
}
