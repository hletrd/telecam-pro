package me.hletrd.telecampro.gl

/**
 * ONE authority for the front-camera mirror convention and every seam derived from it.
 *
 * The device fact is [DeviceProfile.frontStreamPreMirrored][me.hletrd.telecampro.camera.DeviceProfile]:
 * PMA110's front HAL PRE-MIRRORS its SurfaceTexture stream (device-diagnosed 2026-07-23, commit
 * 29559a8 — the delivered texture already IS the selfie-mirror view), while a spec device delivers
 * the un-mirrored scene. That single boolean fixes FOUR seams that must never disagree — the
 * preview draw's mirror role, the encoder/analysis draws' un-mirror role, the tap-mapping
 * display-vs-texture axis, and the metering display-vs-array axis. The roles have already flipped
 * once on device evidence, and the 2026-08-01 multi-device review caught the half-migrated state
 * where the GL seams read the profile while the tap seams read a hardcoded constant: on any
 * GENERIC device that flipped front tap metering to the wrong side of the array (the cycle-6
 * debugger F2 class) while the selfie preview lost its conventional mirror. So every function here
 * takes the profile truth as an ARGUMENT and there is no constant left to half-migrate; callers
 * all pass `deviceProfile.frontStreamPreMirrored`.
 */
object FrontMirrorConvention {
    /** The preview adds the selfie mirror ONLY when the stream does not already carry it. */
    fun previewDrawMirrorX(frontRoute: Boolean, streamPreMirrored: Boolean): Boolean =
        frontRoute && !streamPreMirrored

    /** Encoder/analysis write the TRUE scene: un-mirror exactly when the stream is pre-mirrored. */
    fun encoderDrawMirrorX(frontRoute: Boolean, streamPreMirrored: Boolean): Boolean =
        frontRoute && streamPreMirrored

    /**
     * Whether displayed x differs from TEXTURE x, i.e. the loupe/content mapping must un-flip.
     * Displayed == texture whenever the texture is drawn as-is (pre-mirrored stream) and differs
     * exactly when the PREVIEW added the mirror itself — so this is [previewDrawMirrorX] by
     * construction. This is NOT the metering question — see [meteringMirrorX].
     */
    fun tapDisplayMirrorX(frontRoute: Boolean, streamPreMirrored: Boolean): Boolean =
        previewDrawMirrorX(frontRoute, streamPreMirrored)

    /**
     * Whether displayed x differs from ACTIVE-ARRAY x, i.e. AE/AF metering regions must un-flip.
     *
     * Distinct from [tapDisplayMirrorX], and the distinction is the whole point: the loupe consumes
     * TEXTURE space, while metering regions are specified in the sensor's ACTIVE ARRAY, which holds
     * the TRUE, un-mirrored scene. Under EITHER convention the DISPLAYED selfie is that scene
     * mirrored (the stream carries the mirror, or the preview draw adds it), so a tap on the left
     * of the preview is on the RIGHT of the array — without this flip, front tap-AF/AE meters the
     * horizontally opposite point (cycle-6 debugger F2). Hence this is `frontRoute` regardless of
     * the stream convention; it still takes the argument so all four seams share one signature and
     * a future third convention cannot silently reuse a stale two-seam answer.
     *
     * The flip belongs in DISPLAY space, before the tap's rotation into array coordinates, because
     * that is where the device-verified encoder flip acts (`texCoordQuad` mirrors the texcoord
     * ATTRIBUTE, i.e. the output's own horizontal axis, and a pulled front still read unreversed
     * on device).
     *
     * The tap mapping's ROTATION term remains OPEN on a rotated front-camera window; this fixes only
     * the mirror half. Committed docs/FIELD_CHECKS.md A4 owns the runnable large-screen 90°/270°
     * calibration procedure and is the clean-clone status authority.
     */
    @Suppress("UNUSED_PARAMETER")
    fun meteringMirrorX(frontRoute: Boolean, streamPreMirrored: Boolean): Boolean = frontRoute
}
