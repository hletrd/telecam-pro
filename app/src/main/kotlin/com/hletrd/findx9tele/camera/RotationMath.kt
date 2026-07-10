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

    /** CW degrees the GL renderer adds on top of the SurfaceTexture transform (afocal flip only). */
    fun previewRotationDegrees(teleconverterMode: Boolean): Int = if (teleconverterMode) AFOCAL_FLIP else 0

    /** Total CW rotation to save a still upright: sensor + afocal(tele) + device orientation, normalized. */
    fun captureRotationDegrees(sensorOrientation: Int, teleconverterMode: Boolean, deviceOrientation: Int): Int {
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
}
