package com.hletrd.findx9tele.camera

/**
 * Pure rotation math for the afocal-teleconverter camera — no Android framework calls, so it is
 * fully JVM-unit-testable (the same discipline as [com.hletrd.findx9tele.focus.FocusMapping]).
 *
 * Two rotation contexts, with a deliberate sign asymmetry (see CameraEngine's doc comments):
 *  - **preview** (GL texcoords): the camera SurfaceTexture transform already rotates the sampled image
 *    by the sensor orientation, so the renderer only adds the afocal 180° flip (tele mode only).
 *  - **capture** (raw JPEG/RAW pixels / EXIF tag): the raw sensor image keeps the sensor orientation,
 *    so the full sensor + afocal + physical-device-tilt rotation is applied.
 */
object RotationMath {
    const val AFOCAL_FLIP = 180

    // EXIF/TIFF orientation tags — numerically identical to android.media / androidx ExifInterface's
    // ORIENTATION_* constants, kept local so this object stays pure (no ExifInterface dependency and
    // no lint ExifInterface warning) and JVM-testable.
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_ROTATE_270 = 8

    /** Normalize any degree value to [0,360). */
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * CW degrees the GL renderer adds on top of the SurfaceTexture transform (afocal flip only).
     * FRONT needs no term of its own: the SurfaceTexture transform already carries the front
     * sensor's orientation, the afocal converter is a rear-lens accessory (the facing door forces
     * teleconverterMode off), and the selfie-mirror axis is owned entirely by the GL draw roles
     * ([com.hletrd.findx9tele.gl.FrontMirrorConvention] decides which draws mirror; rotation never
     * does), so the front preview rotation is simply 0.
     */
    fun previewRotationDegrees(teleconverterMode: Boolean): Int = if (teleconverterMode) AFOCAL_FLIP else 0

    /** Rear-camera form of [captureRotationDegrees]; kept so existing callers/tests pin the back matrix. */
    fun captureRotationDegrees(sensorOrientation: Int, teleconverterMode: Boolean, deviceOrientation: Int): Int =
        captureRotationDegrees(sensorOrientation, teleconverterMode, deviceOrientation, frontFacing = false)

    /**
     * Total CW rotation to save a still upright.
     *  - BACK: sensor + afocal(tele) + device orientation, normalized — the device-verified matrix.
     *  - FRONT: sensor − device orientation, normalized; the afocal term NEVER applies (the
     *    converter clamps onto the rear 3×). The sign flips because a front sensor faces the
     *    opposite direction, so the device's physical CW tilt is CCW relative to its image — the
     *    standard Camera2 front JPEG-orientation formula. DEVICE-VERIFICATION-PENDING: like the
     *    back signs before it, this needs a held, lit portrait/landscape output check on the
     *    PMA110 front camera; if the check flips it, this seam (and its test matrix) is the one
     *    place that changes.
     */
    fun captureRotationDegrees(
        sensorOrientation: Int,
        teleconverterMode: Boolean,
        deviceOrientation: Int,
        frontFacing: Boolean,
    ): Int {
        if (frontFacing) return normalize(sensorOrientation - deviceOrientation)
        val base = sensorOrientation + if (teleconverterMode) AFOCAL_FLIP else 0
        return normalize(base + deviceOrientation)
    }

    /** Maps a CW rotation (any int) to the matching EXIF/TIFF orientation tag (1/3/6/8). */
    fun exifOrientationFor(degrees: Int): Int = when (normalize(degrees)) {
        90 -> ORIENTATION_ROTATE_90
        180 -> ORIENTATION_ROTATE_180
        270 -> ORIENTATION_ROTATE_270
        else -> ORIENTATION_NORMAL
    }

    /**
     * MediaMuxer orientation hint for a clip started at [deviceOrientation] (0/90/180/270 from
     * gravity). The GL pipeline already bakes the afocal 180° into the recorded frames, so the hint
     * carries ONLY the physical device tilt, normalized to [0,360). NOTE: the hint's SIGN on this
     * device is an open Residual Field Check (docs/BACKLOG.md) — it may need `(360 - deg) % 360`;
     * verify a held-landscape clip in BOTH directions (external gallery, not just in-app review,
     * which re-applies the container rotation itself) before trusting it.
     */
    fun videoOrientationHint(deviceOrientation: Int): Int = normalize(deviceOrientation)

    /**
     * True when the GL content aspect is SWAPPED relative to the camera stream: the SurfaceTexture
     * transform rotates sampling by [sensorOrientation] and the renderer adds [contentRotationDeg]
     * (the afocal 180°), so a net 90/270 displays the stream's H×W. MUST mirror `coverScale`'s
     * `rotated` predicate in gl/FlipRenderer.kt — that is what decides whether a draw target of a
     * given aspect gets the full field or an overscan crop.
     */
    fun contentAspectSwapped(sensorOrientation: Int, contentRotationDeg: Int): Boolean =
        normalize(sensorOrientation + contentRotationDeg) % 180 == 90

    /**
     * The encoder buffer dimensions for a camera stream of [streamW]×[streamH] (ARCH4-1, framing
     * contract): the encoder MUST be framed to the same displayed aspect as the preview or
     * `coverScale` silently overscan-crops the recorded field. With the 90° sensor the displayed
     * content is portrait, so the encoder buffer swaps to [streamH]×[streamW]; cover then nets
     * (1,1) and the file records exactly the viewfinder field (device-measured 2026-07-18: the
     * landscape-buffer arrangement recorded a ~3.16× center band of the preview field).
     * Returns width to height.
     */
    fun encoderSurfaceSize(
        streamW: Int,
        streamH: Int,
        sensorOrientation: Int,
        contentRotationDeg: Int,
    ): Pair<Int, Int> =
        if (contentAspectSwapped(sensorOrientation, contentRotationDeg)) streamH to streamW else streamW to streamH
}
