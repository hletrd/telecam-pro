package me.hletrd.telecampro.gl

/**
 * Re-applies a buffer transform a non-camera producer cannot carry.
 *
 * A camera stream reaches the GL input SurfaceTexture with the camera service's buffer transform
 * (rear sensor orientation 90 → ROT_90), and the renderer relies on the SurfaceTexture matrix for that
 * rotation (it adds only the afocal term itself). A frame the RAW log developer renders through EGL
 * carries transform 0, and Java EGL has no way to set one. AOSP's GLConsumer builds the matrix as
 * `flipV · crop · T`, with `T = (flipH)(flipV)(rot90)` taken per flag, so for the same buffer size and
 * crop the camera's matrix is exactly the EGL frame's matrix post-multiplied by `T`. These are
 * GLConsumer's own matrices (libs/gui GLConsumer::computeTransformMatrix), column-major.
 */
internal object SourceTransformEmulation {
    /** NATIVE_WINDOW_TRANSFORM flags (system/window.h). */
    const val FLIP_H = 0x01
    const val FLIP_V = 0x02
    const val ROT_90 = 0x04
    const val ROT_180 = FLIP_H or FLIP_V
    const val ROT_270 = ROT_180 or ROT_90

    private val MTX_FLIP_H = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
    private val MTX_FLIP_V = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private val MTX_ROT_90 = floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)

    /**
     * The transform the camera service stamps on a REAR camera's stream for [sensorOrientation]
     * (CameraUtils::getRotationTransform with no mirroring). FRONT is out of scope: the RAW log route
     * excludes it.
     */
    fun rearTransformFor(sensorOrientation: Int): Int = when (((sensorOrientation % 360) + 360) % 360) {
        90 -> ROT_90
        180 -> ROT_180
        270 -> ROT_270
        else -> 0
    }

    /** `T` for [flags], or null for 0 (nothing to re-apply). */
    fun matrixFor(flags: Int): FloatArray? {
        if (flags and (FLIP_H or FLIP_V or ROT_90) == 0) return null
        var xform = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        if (flags and FLIP_H != 0) xform = multiply(xform, MTX_FLIP_H)
        if (flags and FLIP_V != 0) xform = multiply(xform, MTX_FLIP_V)
        if (flags and ROT_90 != 0) xform = multiply(xform, MTX_ROT_90)
        return xform
    }

    /** In place: [stMatrix] = [stMatrix] · T(flags); a no-op for 0. */
    fun apply(stMatrix: FloatArray, flags: Int) {
        val t = matrixFor(flags) ?: return
        multiply(stMatrix, t).copyInto(stMatrix)
    }

    /** Column-major 4×4 product a · b. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16) { i ->
        val col = i / 4
        val row = i % 4
        var sum = 0f
        for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
        sum
    }
}
