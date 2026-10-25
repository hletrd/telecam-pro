package me.hletrd.telecampro.stab

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure math of the RAW log route's gyro EIS ([RawEisGyro] feeds it, camera/RawLogBridge.kt warps
 * with it). Host-tested; nothing here touches the Android framework.
 *
 * The model, and why it is the core of this app: a phone rotation θ moves a distant scene across the
 * sensor by F·θ, with F the focal length in sensor pixels — and through the Hasselblad converter,
 * a Keplerian afocal telescope, the scene's angle is magnified AND inverted, so F becomes
 * −M·f (M ≈ 4.286). The stabilizer therefore needs no image analysis: given the camera's orientation
 * at every row's exposure (gyro + rolling-shutter timing) and a smoothed virtual orientation, one
 * homography per row band maps each output pixel to where that scene point actually landed.
 *
 * Orientation convention: R(t) maps CAMERA coordinates at time t to the reference frame; a fixed
 * scene direction w is seen in the camera at d = Rᵀ(t)·w. Camera axes: x along sensor columns, y
 * along sensor rows (down), z out of the lens toward the scene.
 */
internal object RawEisMath {

    /** Unit quaternion (w, x, y, z). Immutable; double precision (a 30 s take integrates ~15k steps). */
    data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
        operator fun times(o: Quat) = Quat(
            w * o.w - x * o.x - y * o.y - z * o.z,
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
        )

        fun normalized(): Quat {
            val n = sqrt(w * w + x * x + y * y + z * z)
            return Quat(w / n, x / n, y / n, z / n)
        }

        /** Row-major 3×3 rotation matrix. */
        fun toMatrix(): DoubleArray = doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        )

        companion object {
            val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

            /** The rotation of angular velocity [wx],[wy],[wz] (rad/s, body frame) held for [dt] s. */
            fun fromAngularVelocity(wx: Double, wy: Double, wz: Double, dt: Double): Quat {
                val rate = sqrt(wx * wx + wy * wy + wz * wz)
                val angle = rate * dt
                if (angle < 1e-12) return Quat(1.0, wx * dt / 2, wy * dt / 2, wz * dt / 2).normalized()
                val s = sin(angle / 2) / rate
                return Quat(cos(angle / 2), wx * s, wy * s, wz * s)
            }
        }
    }

    /** Spherical interpolation from [a] (t = 0) to [b] (t = 1), the short way round. */
    fun slerp(a: Quat, b0: Quat, t: Double): Quat {
        var b = b0
        var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
        if (dot < 0) {
            b = Quat(-b.w, -b.x, -b.y, -b.z)
            dot = -dot
        }
        if (dot > 0.9995) {
            return Quat(
                a.w + t * (b.w - a.w), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z),
            ).normalized()
        }
        val theta = acos(dot.coerceIn(-1.0, 1.0))
        val sa = sin((1 - t) * theta) / sin(theta)
        val sb = sin(t * theta) / sin(theta)
        return Quat(sa * a.w + sb * b.w, sa * a.x + sb * b.x, sa * a.y + sb * b.y, sa * a.z + sb * b.z)
    }

    /** Rotation angle (rad) between two orientations. */
    fun angleBetween(a: Quat, b: Quat): Double {
        val dot = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceAtMost(1.0)
        return 2 * acos(dot)
    }

    /**
     * Device (Android sensor) axes → camera axes for a REAR camera of [sensorOrientation] degrees, row
     * major: ω_cam = C·ω_dev. The sensor image is displayed upright after a clockwise rotation by the
     * orientation, so image +x lands on screen direction (cos θ, sin θ) and image +y on (−sin θ, cos θ)
     * (screen y down); screen (X, Y) is device (x, −y); the rear lens looks along device −z. At 90°:
     * x_cam = −y_dev, y_cam = −x_dev, z_cam = −z_dev (a proper rotation, det = +1).
     */
    fun rearDeviceToCamera(sensorOrientation: Int): DoubleArray {
        val t = Math.toRadians(sensorOrientation.toDouble())
        val c = cos(t)
        val s = sin(t)
        return doubleArrayOf(
            c, -s, 0.0,
            -s, -c, 0.0,
            0.0, 0.0, -1.0,
        )
    }

    /**
     * Focal length in sensor pixels as the IMAGE sees it: the lens focal over the pixel pitch, times
     * the converter's angular magnification, NEGATED for an inverting (Keplerian afocal) converter —
     * the same assumption the whole app makes when TELE rotates the picture 180°.
     */
    fun effectiveFocalPx(focalMm: Double, physicalWidthMm: Double, pixelArrayWidth: Int, converter: Double?): Double {
        val f = focalMm / physicalWidthMm * pixelArrayWidth
        return if (converter != null && converter > 1.0) -converter * f else f
    }

    // ---- 3×3 row-major helpers ----

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun transpose(m: DoubleArray) = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    fun apply(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    /** A rectangle in sensor-array pixels. */
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        val centerX get() = x + width / 2
        val centerY get() = y + height / 2
    }

    /**
     * Output (u, v) ∈ [0, 1]² → texture coordinates of the developed region, for a camera that sees
     * the output [window] (array px) through the VIRTUAL orientation while the sensor row was exposed
     * at [real]: H = T_tex · K · (Rᵀ_real · V) · K⁻¹ · T_out. [focalPx] carries the converter sign;
     * the principal point is the array centre. With real == virtual this is exactly the plain crop.
     */
    fun bandHomography(
        real: Quat,
        virtual: Quat,
        focalPx: Double,
        arrayWidth: Int,
        arrayHeight: Int,
        window: Rect,
        developed: Rect,
    ): DoubleArray {
        val cx = arrayWidth / 2.0
        val cy = arrayHeight / 2.0
        val tOut = doubleArrayOf(
            window.width, 0.0, window.x,
            0.0, window.height, window.y,
            0.0, 0.0, 1.0,
        )
        val kInv = doubleArrayOf(1 / focalPx, 0.0, -cx / focalPx, 0.0, 1 / focalPx, -cy / focalPx, 0.0, 0.0, 1.0)
        val k = doubleArrayOf(focalPx, 0.0, cx, 0.0, focalPx, cy, 0.0, 0.0, 1.0)
        val rotation = multiply(transpose(real.toMatrix()), virtual.toMatrix())
        val tTex = doubleArrayOf(
            1 / developed.width, 0.0, -developed.x / developed.width,
            0.0, 1 / developed.height, -developed.y / developed.height,
            0.0, 0.0, 1.0,
        )
        return multiply(tTex, multiply(k, multiply(rotation, multiply(kInv, tOut))))
    }

    /**
     * Whether every band keeps the output window inside the developed region: each band is checked at
     * the output rows it serves (its own row band) along the window's left/centre/right columns, with
     * an [insetTex] guard so bilinear taps and the next frame's motion never sample past the edge.
     */
    fun windowFits(bands: List<DoubleArray>, insetTex: Double): Boolean {
        val n = bands.size
        for ((k, h) in bands.withIndex()) {
            val v0 = if (n == 1) 0.0 else ((k - 0.5) / (n - 1)).coerceIn(0.0, 1.0)
            val v1 = if (n == 1) 1.0 else ((k + 0.5) / (n - 1)).coerceIn(0.0, 1.0)
            for (v in doubleArrayOf(v0, v1)) {
                for (u in doubleArrayOf(0.0, 0.5, 1.0)) {
                    val (tx, ty) = apply(h, u, v)
                    if (tx < insetTex || tx > 1 - insetTex || ty < insetTex || ty > 1 - insetTex) return false
                }
            }
        }
        return true
    }

    /** Smoothing strength per stabilization level: the per-frame catch-up fraction toward the real path. */
    fun baseFollow(strong: Boolean): Double = if (strong) STRONG_FOLLOW else STANDARD_FOLLOW

    /**
     * One causal smoothing step. The virtual orientation moves toward the real one by [follow] (small =
     * steadier), more as the correction uses up the crop margin ([usage] = correction angle over the
     * allowed angle: a soft ramp from 0.6 to 1.0 removes the visible "bump" of a hard limit), and the
     * caller then binary-searches upward if the window still does not fit.
     */
    fun followFor(baseFollow: Double, usage: Double): Double {
        val ramp = ((usage - 0.6) / 0.4).coerceIn(0.0, 1.0)
        val smooth = ramp * ramp * (3 - 2 * ramp)
        return (baseFollow + (1 - baseFollow) * smooth * 0.5).coerceIn(baseFollow, 1.0)
    }

    private const val STANDARD_FOLLOW = 0.08
    private const val STRONG_FOLLOW = 0.03
}
