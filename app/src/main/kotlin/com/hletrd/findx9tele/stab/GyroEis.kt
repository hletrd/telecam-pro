package com.hletrd.findx9tele.stab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Gravity-derived device-orientation and roll provider (and the dormant gyro-EIS signal source).
 *
 * What SHIPS from this class is the accelerometer path: the absolute roll for the horizon level
 * ([currentRollDegrees]) and the discrete held-orientation for capture/overlay rotation
 * ([currentDeviceOrientation]), both with the flat/steep-angle hold guards.
 *
 * The gyroscope residual-shake integration ([currentCorrection]) fed the app-side GL EIS, which was
 * REMOVED (frame warping cannot de-blur at 300 mm; the HAL's OIS+EIS owns stabilization — see
 * [com.hletrd.findx9tele.camera.VideoStabMode]). The math is kept for a possible future consumer,
 * but the gyroscope is no longer registered: integrating a 200 Hz stream nothing reads was pure
 * CPU/battery waste. Re-enable by registering [gyroscope] in [start] again.
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

    // Last discrete device orientation (0/90/180/270) captured while the phone was clearly HELD
    // (strong horizontal gravity). Held here so a flat phone — where the in-plane gravity is tiny and
    // atan2(x,y) is pure noise — keeps the last confident orientation instead of snapping randomly.
    @Volatile private var stableOrientation = 0

    val isAvailable: Boolean get() = gyroscope != null

    fun start() {
        reset()
        // Gyroscope deliberately NOT registered: its only consumer (app-side GL EIS) was removed,
        // so [currentCorrection] stays zero. The accelerometer alone feeds roll + orientation.
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

    /**
     * Discrete physical device orientation (0/90/180/270), derived from gravity, for auto-rotating
     * captures while the UI stays portrait-locked. 0 = upright portrait; 90/270 = the two landscapes;
     * 180 = upside down. Updated only while the phone is clearly HELD (strong in-plane gravity); a
     * flat phone keeps the last confident value rather than snapping randomly.
     */
    fun currentDeviceOrientation(): Int = stableOrientation

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

                // Only update the roll when there's enough in-plane gravity to actually define it.
                // Pointing the phone straight down/up puts gravity along ±z, so x/y ≈ 0 and atan2(x,y)
                // is pure noise — the horizon level would spin. Below the threshold we HOLD the last
                // confident angle instead of chasing the noise (QA: "level spins when pointing down").
                if (shouldUpdateRoll(x, y)) {
                    val rollDeg = Math.toDegrees(atan2(x, y).toDouble()).toFloat()
                    rollDegrees += ROLL_LOW_PASS_ALPHA * (rollDeg - rollDegrees)
                }

                // Only update the discrete capture orientation when the phone is clearly HELD: the
                // in-plane gravity magnitude must exceed a threshold. When flat on a desk, x/y ≈ 0 and
                // atan2 is noise, so we hold the last confident value (a flat shot keeps the last hold).
                if (shouldUpdateOrientation(x, y)) {
                    stableOrientation = snapToQuadrant(rollDegrees)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun reset() {
        lastTimestamp = 0L
        angPitch = 0f; angYaw = 0f; angRoll = 0f
        smoothPitch = 0f; smoothYaw = 0f; smoothRoll = 0f
        corrPitch = 0f; corrYaw = 0f; corrRoll = 0f
        // rollDegrees and stableOrientation are deliberately NOT zeroed. reset() runs on BOTH start()
        // and stop(), and those two are gravity-derived ABSOLUTE values whose documented design is
        // "hold the last confident value" (see their field comments + CLAUDE.md). Zeroing them on
        // pause/resume made a capture in the first frames after resume — before the accelerometer
        // re-samples — use upright-portrait instead of the held orientation (the flat-desk DNG
        // wrong-orientation bug class). Only the gyro-integration fields above are cleared.
    }

    // internal (not private): the pure decision seams below and their unit tests reference the two
    // gravity thresholds by name so the boundary math stays single-sourced.
    internal companion object {
        // Sensors are registered at an explicit ~200 Hz sampling period (5000 µs) rather than
        // SENSOR_DELAY_FASTEST (~500-1000 Hz) — the GL loop only consumes the result once per
        // rendered frame (30-60 Hz), so anything faster is wasted CPU/battery with no
        // stabilization-quality benefit.
        const val SAMPLING_PERIOD_US = 5000

        // In-plane gravity magnitude (m/s²) above which the phone is considered clearly HELD (not
        // flat), so its discrete orientation can be trusted. ~4.9 = half g ≈ tilted ≥30° from flat.
        const val FLAT_GRAVITY_THRESHOLD = 4.9f

        // In-plane gravity magnitude (m/s²) below which the roll angle is undefined (phone pointing
        // steeply up/down) — hold the last value so the horizon level doesn't spin on atan2 noise.
        const val LEVEL_GRAVITY_THRESHOLD = 2.5f

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

// Pure decision seams behind [GyroEis.onSensorChanged], extracted so the gravity thresholds and the
// quadrant snap are unit-testable off-device (the class itself needs a live SensorManager). Match the
// codebase's pure-seam pattern (e.g. camera/meteringRect, camera/sessionAttemptPlan).

/** True when there is enough in-plane gravity to trust the discrete held-orientation (phone HELD). */
internal fun shouldUpdateOrientation(x: Float, y: Float): Boolean =
    hypot(x, y) > GyroEis.FLAT_GRAVITY_THRESHOLD

/** True when there is enough in-plane gravity to define the roll angle (not pointing steeply up/down). */
internal fun shouldUpdateRoll(x: Float, y: Float): Boolean =
    hypot(x, y) > GyroEis.LEVEL_GRAVITY_THRESHOLD

/** Snap an absolute roll (deg) to the nearest 0/90/180/270 quadrant, normalized into 0..359. */
internal fun snapToQuadrant(rollDegrees: Float): Int {
    val d = Math.round(rollDegrees / 90f) * 90
    return ((d % 360) + 360) % 360
}
