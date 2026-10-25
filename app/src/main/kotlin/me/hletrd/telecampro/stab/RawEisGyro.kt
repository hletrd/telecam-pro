package me.hletrd.telecampro.stab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Gyro orientation history for the RAW log EIS: the camera's integrated orientation, timestamped in
 * the SensorEvent clock — the SAME base as the camera's SENSOR_TIMESTAMP on a REALTIME timestamp
 * source (PMA110 reports REALTIME), so a frame row's exposure time indexes this history directly.
 *
 * 200 Hz (5 ms): the platform cap without HIGH_SAMPLING_RATE_SENSORS, still several samples across a
 * frame's rolling-shutter readout; no extra permission. Integration is body-frame (ω mapped from
 * device to camera axes by [RawEisMath.rearDeviceToCamera]), trapezoidal over each sample interval.
 * Listener and integration run on this class's own thread; [orientationAt] is safe from any thread.
 */
internal class RawEisGyro(context: Context, sensorOrientation: Int) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val deviceToCamera = RawEisMath.rearDeviceToCamera(sensorOrientation)
    private val thread = HandlerThread("RawEisGyro")
    private val lock = Any()

    // Ring of (time, orientation quaternion, angular velocity in camera axes), all primitive: the
    // listener runs at 200 Hz and must not allocate per sample (the first, object-per-sample version
    // cost 5 % of a core, measured 2026-10-25). Guarded by [lock].
    private val times = LongArray(RING)
    private val qw = DoubleArray(RING)
    private val qx = DoubleArray(RING)
    private val qy = DoubleArray(RING)
    private val qz = DoubleArray(RING)
    private val rx = DoubleArray(RING)
    private val ry = DoubleArray(RING)
    private val rz = DoubleArray(RING)
    private var head = 0
    private var count = 0

    // Integration state, listener thread only.
    private var ow = 1.0
    private var ox = 0.0
    private var oy = 0.0
    private var oz = 0.0
    private var lastTime = 0L
    private var lastWx = 0.0
    private var lastWy = 0.0
    private var lastWz = 0.0

    val available: Boolean get() = gyroscope != null

    fun start(): Boolean {
        val sensor = gyroscope ?: return false
        thread.start()
        return sensorManager?.registerListener(this, sensor, SAMPLING_PERIOD_US, Handler(thread.looper)) == true
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
        thread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val c = deviceToCamera
        val wx = c[0] * v[0] + c[1] * v[1] + c[2] * v[2]
        val wy = c[3] * v[0] + c[4] * v[1] + c[5] * v[2]
        val wz = c[6] * v[0] + c[7] * v[1] + c[8] * v[2]
        val t = event.timestamp
        if (lastTime != 0L) {
            val dt = (t - lastTime) * 1e-9
            if (dt > 0 && dt < MAX_GAP_S) {
                // Trapezoid: the mean rate across the interval, as the body-frame increment
                // dq = (cos(θ/2), axis·sin(θ/2)); o ← normalize(o ⊗ dq) — RawEisMath.Quat's product,
                // written out so nothing is allocated.
                val mx = (wx + lastWx) / 2
                val my = (wy + lastWy) / 2
                val mz = (wz + lastWz) / 2
                val rate = kotlin.math.sqrt(mx * mx + my * my + mz * mz)
                val half = rate * dt / 2
                val s = if (rate < 1e-12) dt / 2 else kotlin.math.sin(half) / rate
                val dw = kotlin.math.cos(half)
                val dx = mx * s
                val dy = my * s
                val dz = mz * s
                val nw = ow * dw - ox * dx - oy * dy - oz * dz
                val nx = ow * dx + ox * dw + oy * dz - oz * dy
                val ny = ow * dy - ox * dz + oy * dw + oz * dx
                val nz = ow * dz + ox * dy - oy * dx + oz * dw
                val n = kotlin.math.sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
                ow = nw / n
                ox = nx / n
                oy = ny / n
                oz = nz / n
            }
        }
        lastTime = t
        lastWx = wx
        lastWy = wy
        lastWz = wz
        synchronized(lock) {
            times[head] = t
            qw[head] = ow
            qx[head] = ox
            qy[head] = oy
            qz[head] = oz
            rx[head] = wx
            ry[head] = wy
            rz[head] = wz
            head = (head + 1) % RING
            if (count < RING) count++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * The camera orientation at [timeNs] (SensorEvent clock): interpolated between the bracketing
     * samples; extrapolated with the newest rate for at most [MAX_EXTRAPOLATION_NS] past the newest
     * sample (a frame's last rows can be newer than the last delivered gyro event); clamped to the
     * oldest sample before the history. Null before the first sample.
     */
    fun orientationAt(timeNs: Long): RawEisMath.Quat? = synchronized(lock) {
        if (count == 0) return null
        val newest = (head - 1 + RING) % RING
        val oldest = (head - count + RING) % RING
        if (timeNs >= times[newest]) {
            val ahead = (timeNs - times[newest]).coerceAtMost(MAX_EXTRAPOLATION_NS) * 1e-9
            return quatAt(newest) * RawEisMath.Quat.fromAngularVelocity(rx[newest], ry[newest], rz[newest], ahead)
        }
        if (timeNs <= times[oldest]) return quatAt(oldest)
        // Binary search over the logical order oldest..newest.
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[(oldest + mid) % RING] <= timeNs) lo = mid else hi = mid
        }
        val a = (oldest + lo) % RING
        val b = (oldest + hi) % RING
        val span = (times[b] - times[a]).toDouble()
        val f = if (span <= 0) 0.0 else (timeNs - times[a]) / span
        return RawEisMath.slerp(quatAt(a), quatAt(b), f)
    }

    private fun quatAt(i: Int) = RawEisMath.Quat(qw[i], qx[i], qy[i], qz[i])

    private companion object {
        const val SAMPLING_PERIOD_US = 5_000
        const val RING = 1024 // ~5 s at 200 Hz
        const val MAX_GAP_S = 0.1
        const val MAX_EXTRAPOLATION_NS = 30_000_000L
    }
}
