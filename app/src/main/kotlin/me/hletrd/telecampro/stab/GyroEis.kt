package me.hletrd.telecampro.stab

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
 * [me.hletrd.telecampro.camera.VideoStabMode]). That math is kept for a possible future consumer but
 * remains dead: nothing reads it.
 *
 * The gyroscope is still NOT registered by default — integrating a ~200 Hz stream nothing reads was
 * pure battery waste then and would be now. It is instead ARMABLE via [setRotationTracking], which
 * feeds [rotationBetween] alone. That history is deliberately separate from the EIS integrators:
 * those are high-passed to isolate shake, and a motion-vs-gyro comparison needs exactly the
 * deliberate pan that high-pass discards. Consumers arm it for the seconds they need an answer and
 * disarm immediately — see `gl/MotionInversion.kt`.
 *
 * It answers for an explicit [fromNs, toNs] window rather than "since you last asked" (2026-08-11):
 * the camera frames it is compared against left the sensor before the consumer saw them, so only an
 * interval keyed on THEIR timestamps cancels the pipeline latency.
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

    // NOTE: no isAvailable accessor. It reported `gyroscope != null` — availability of a sensor
    // this class deliberately never registers (see [start]) — so it was misleading, not just unused.

    // TIMESTAMPED cumulative rotation history, radians, in the SensorEvent clock
    // (SystemClock.elapsedRealtimeNanos). SEPARATE from the EIS integrators above on purpose: those
    // are high-passed (corr* = ang* - smooth*) to isolate shake, which is precisely the component a
    // motion-vs-gyro comparison must NOT use — it needs the deliberate pan the high-pass throws away.
    //
    // A HISTORY rather than a running total, because the consumer does not want "rotation since you
    // last asked" — it wants "rotation between these two camera frames", and those frames left the
    // sensor before the consumer ever saw them (see [rotationBetween]).
    private val rotationLock = Any()
    private val sampleTimeNs = LongArray(ROTATION_HISTORY)
    private val sampleYaw = FloatArray(ROTATION_HISTORY)
    private val samplePitch = FloatArray(ROTATION_HISTORY)
    private var sampleCount = 0
    private var sampleHead = 0
    private var cumYaw = 0f
    private var cumPitch = 0f

    /** Whether the caller currently wants [rotationBetween] to produce anything. */
    @Volatile private var rotationTracking = false

    fun start() {
        reset()
        accelerometer?.let { sensorManager?.registerListener(this, it, SAMPLING_PERIOD_US) }
        // Re-arm across a pause/resume if the consumer still wants tracking. Registration does not
        // survive [stop]'s blanket unregisterListener, and the INTENT lives here, so a resume that
        // did not re-apply it would silently strand the consumer with a dead sensor — the same
        // "works only after something else re-pushes it" failure GlPipeline's start callback exists
        // to prevent.
        if (rotationTracking) registerGyroscope()
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        reset()
    }

    /**
     * Arms or disarms gyroscope rotation tracking.
     *
     * The gyroscope is NOT registered by default and must not become so: integrating a ~200 Hz
     * stream nothing reads is pure battery waste, which is exactly why the dead EIS registration was
     * removed. Consumers arm it for the seconds they need an answer and disarm immediately after.
     *
     * Idempotent. Disarming drops the retained history so a later re-arm cannot answer for a window
     * that spans the gap when nobody was watching.
     */
    fun setRotationTracking(enabled: Boolean) {
        if (rotationTracking == enabled) return
        rotationTracking = enabled
        if (enabled) {
            registerGyroscope()
        } else {
            gyroscope?.let { sensorManager?.unregisterListener(this, it) }
            clearRotationHistory()
        }
    }

    /** Drops every retained sample. A window spanning a pause is not answerable and must not be. */
    private fun clearRotationHistory() = synchronized(rotationLock) {
        sampleCount = 0
        sampleHead = 0
        cumYaw = 0f
        cumPitch = 0f
    }

    private fun registerGyroscope() {
        gyroscope?.let { sensorManager?.registerListener(this, it, GYRO_SAMPLING_PERIOD_US) }
    }

    /**
     * Rotation between two instants on the SENSOR clock: `[0]` = yaw (about device y), `[1]` = pitch
     * (about device x), radians. Returns null when the window cannot be answered honestly.
     *
     * WHY AN INTERVAL AND NOT A DRAIN (device-diagnosed 2026-08-11). The previous API answered
     * "rotation since you last asked me", which the caller then paired with two camera frames. Those
     * frames left the sensor EARLIER than the asking — camera pipeline latency — so image motion
     * from moment T was being judged against rotation over an interval ending near T + lag. A slow
     * one-direction pan hides that completely (the rotation keeps one sign, so a shifted window
     * still points the same way); a reversing motion exposes it, because the direction flips inside
     * the lag window. Measured symptom: verdicts correlated with the SIGN of the rotation, which a
     * direction-invariant comparison cannot do. See `gl/MotionInversion.kt`.
     *
     * Answering an explicit [fromNs, toNs] removes the latency from the question entirely: the
     * caller passes the two frames' own timestamps and the lag cancels.
     *
     * NULL — not zero — when the window is unanswerable: tracking disarmed, fewer than two samples,
     * or either bound outside the retained history (the phone was asleep, the consumer stalled, or
     * the window is older than [ROTATION_HISTORY] samples). Zero would read as "no rotation", which
     * is a claim; null is the absence of one, and the caller refuses on it.
     *
     * CLOCK CONTRACT: [fromNs]/[toNs] must be in the same base as `SensorEvent.timestamp`
     * (`SystemClock.elapsedRealtimeNanos`). Camera frames satisfy that only when the device reports
     * `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`; the caller checks, because on an UNKNOWN source the
     * camera clock is `System.nanoTime` and the two bases drift apart by accumulated deep sleep.
     */
    fun rotationBetween(fromNs: Long, toNs: Long): FloatArray? {
        if (!rotationTracking) return null
        synchronized(rotationLock) {
            return rotationBetweenSamples(
                timeNs = sampleTimeNs,
                yaw = sampleYaw,
                pitch = samplePitch,
                count = sampleCount,
                head = sampleHead,
                fromNs = fromNs,
                toNs = toNs,
            )
        }
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

                // Raw (un-high-passed) accumulation for [rotationBetween]. Same axis mapping as the
                // integrators above: values[0] is rotation about device x (pitch), values[1] about
                // device y (yaw). Kept inside the same dt guard so a dropped/glitched sample cannot
                // inject a spurious pan.
                if (rotationTracking) {
                    synchronized(rotationLock) {
                        cumPitch += event.values[0] * dt
                        cumYaw += event.values[1] * dt
                        // Retain the SAMPLE TIMESTAMP, not the arrival time: the whole point is to
                        // be able to answer for a window that closed before anyone asked.
                        val slot = sampleHead
                        sampleTimeNs[slot] = event.timestamp
                        sampleYaw[slot] = cumYaw
                        samplePitch[slot] = cumPitch
                        sampleHead = (sampleHead + 1) % ROTATION_HISTORY
                        if (sampleCount < ROTATION_HISTORY) sampleCount++
                    }
                }

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
                    // Wrap-aware smoothing: atan2 lives on (-180, 180], and a naive lerp across
                    // that seam (+179 → -179) steps ~72° through the WRONG quadrants in one
                    // sample — snapToQuadrant below then hands a capture a 90°-off orientation.
                    rollDegrees = smoothedRoll(rollDegrees, rollDeg, ROLL_LOW_PASS_ALPHA)
                }

                // Only update the discrete capture orientation when the phone is clearly HELD: the
                // in-plane gravity magnitude must exceed a threshold. When flat on a desk, x/y ≈ 0 and
                // atan2 is noise, so we hold the last confident value (a flat shot keeps the last hold).
                if (shouldUpdateOrientation(x, y)) {
                    stableOrientation = snapToQuadrantHysteretic(rollDegrees, stableOrientation)
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
        // The drain accumulator IS cleared here, unlike the gravity values below: it is a DELTA over
        // an interval, and the interval does not survive a pause. Carrying it across resume would
        // hand the first frame pair after resume all the rotation that happened while the camera was
        // down, which is a fabricated pan pointing anywhere.
        clearRotationHistory()
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
        // The accelerometer is registered at a UI-rate ~16.7 Hz period (60 ms): its only live
        // consumers are the horizon level overlay and the discrete capture orientation, both read
        // at ≤10 Hz — the earlier explicit 200 Hz period (chosen for the removed GL EIS loop) fed
        // 12× more samples than anything consumed (battery). If the gyroscope is ever
        // re-registered for EIS it needs its OWN faster period; do not reuse this one.
        const val SAMPLING_PERIOD_US = 60_000

        /**
         * Retained gyro samples. At [GYRO_SAMPLING_PERIOD_US] this spans ~2.5 s, comfortably more
         * than any camera pipeline latency plus the ~166 ms analysis interval the consumer asks
         * about — a window that falls off the end is answered null rather than approximated.
         */
        const val ROTATION_HISTORY = 128

        /**
         * Gyro rate while tracking, FASTER than [SAMPLING_PERIOD_US]. The accelerometer's 60 ms is
         * ample for a gravity direction; integrating rotation across a ~166 ms window at that rate
         * gives under three samples, so the interpolation carries most of the answer. 20 ms gives
         * ~8, and it only runs during the seconds the detector is armed.
         */
        const val GYRO_SAMPLING_PERIOD_US = 20_000

        // In-plane gravity magnitude (m/s²) above which the phone is considered clearly HELD (not
        // flat), so its discrete orientation can be trusted. ~4.9 = half g ≈ tilted ≥30° from flat.
        /**
         * How close to a quadrant's centre the roll must come before that quadrant is adopted.
         * 30° means the phone must pass ~60° from the orientation it is leaving — a deliberate
         * turn, not a knife edge at 45° where a hand tremor re-reports the orientation.
         */
        const val ORIENTATION_ENTER_MARGIN_DEG = 30f

        const val FLAT_GRAVITY_THRESHOLD = 4.9f

        // In-plane gravity magnitude (m/s²) below which the roll angle is undefined (phone pointing
        // steeply up/down) — hold the last value so the horizon level doesn't spin on atan2 noise.
        const val LEVEL_GRAVITY_THRESHOLD = 2.5f

        // DEAD CODE, kept for a future EIS revival (CR4-4): the gyroscope is NOT registered (its
        // only consumer, GL shake warping, is disabled) so nothing reads this coefficient. If EIS
        // re-registers the gyro it needs its OWN sampling period — SAMPLING_PERIOD_US above is the
        // 60 ms accelerometer period, not a gyro rate — and this alpha must be retuned to it.
        const val LOW_PASS_ALPHA = 0.1f

        // Per-sample low-pass coefficient for the accelerometer-derived absolute roll. Tuned WITH
        // SAMPLING_PERIOD_US to hold the design time-constant τ ≈ 22 ms via α = 1 − exp(−T/τ):
        // at the old 5 ms period, 0.2 → τ = −5/ln(0.8) ≈ 22.4 ms; at the 60 ms UI-rate period the
        // SAME τ needs α ≈ 0.93 (the interim 0.75 actually gave τ ≈ 43 ms — a ~2× heavier smooth
        // than intended, a visibly laggier horizon level; CRIT4-8). Retune together.
        const val ROLL_LOW_PASS_ALPHA = 0.93f
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

/**
 * Quadrant snap WITH HYSTERESIS — what the reported device orientation actually uses.
 *
 * [snapToQuadrant] alone flips at exactly 45°, so a phone held near a diagonal re-reports a new
 * orientation on the slightest movement and every rotating glyph twitches with it (user-reported
 * 2026-07-29: "too sensitive compared to stock"). Android's own OrientationEventListener does not
 * behave that way: it demands a decisive turn before it commits.
 *
 * A candidate quadrant is adopted only once the roll sits within [enterMargin] of ITS centre — 30°
 * by default, so the phone must pass ~60° from the quadrant it is leaving. Below that it HOLDS, so
 * the boundary is asymmetric (easy to stay, deliberate to leave) rather than a knife edge.
 *
 * Pure so the behaviour is pinned by tests rather than by waving a handset around.
 */
internal fun snapToQuadrantHysteretic(
    rollDegrees: Float,
    current: Int,
    enterMargin: Float = GyroEis.ORIENTATION_ENTER_MARGIN_DEG,
): Int {
    val candidate = snapToQuadrant(rollDegrees)
    if (candidate == current) return current
    val offCentre = kotlin.math.abs(wrapDegrees(rollDegrees - candidate))
    return if (offCentre <= enterMargin) candidate else current
}

/** Normalize an angle in degrees into (-180, 180] — the same range atan2-derived roll lives in. */
internal fun wrapDegrees(d: Float): Float {
    var w = d % 360f
    if (w <= -180f) w += 360f
    if (w > 180f) w -= 360f
    return w
}

/**
 * One wrap-aware low-pass step for the absolute roll: the correction always takes the SHORTEST
 * angular path (a +179°→-179° sample is a 2° move, not a -358° one), and the result stays in
 * atan2's (-180, 180] range so the seam never accumulates.
 */
internal fun smoothedRoll(current: Float, sample: Float, alpha: Float): Float =
    wrapDegrees(current + alpha * wrapDegrees(sample - current))

/**
 * Rotation between two instants, from a ring of TIMESTAMPED cumulative samples. Pure so the window
 * and interpolation logic is unit-testable; [GyroEis] itself needs a live SensorManager.
 *
 * Returns null rather than zero whenever the window cannot be answered — fewer than two samples, an
 * inverted or empty window, or either bound outside the retained range. Zero would assert "the phone
 * did not rotate", which is a claim; null is the absence of one, and the consumer refuses on it
 * (a fabricated "no rotation" would make every frame trivially agree with a still image).
 */
internal fun rotationBetweenSamples(
    timeNs: LongArray,
    yaw: FloatArray,
    pitch: FloatArray,
    count: Int,
    head: Int,
    fromNs: Long,
    toNs: Long,
): FloatArray? {
    if (count < 2 || toNs <= fromNs) return null
    val capacity = timeNs.size
    fun idx(i: Int): Int {
        val start = if (count < capacity) 0 else head
        return (start + i) % capacity
    }
    if (fromNs < timeNs[idx(0)] || toNs > timeNs[idx(count - 1)]) return null

    fun cumulativeAt(t: Long): FloatArray {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timeNs[idx(mid)] <= t) lo = mid else hi = mid - 1
        }
        val i0 = idx(lo)
        if (lo >= count - 1) return floatArrayOf(yaw[i0], pitch[i0])
        val i1 = idx(lo + 1)
        val t0 = timeNs[i0]
        val t1 = timeNs[i1]
        if (t1 <= t0) return floatArrayOf(yaw[i0], pitch[i0])
        val f = ((t - t0).toDouble() / (t1 - t0).toDouble()).toFloat()
        return floatArrayOf(
            yaw[i0] + f * (yaw[i1] - yaw[i0]),
            pitch[i0] + f * (pitch[i1] - pitch[i0]),
        )
    }

    val a = cumulativeAt(fromNs)
    val b = cumulativeAt(toNs)
    return floatArrayOf(b[0] - a[0], b[1] - a[1])
}
