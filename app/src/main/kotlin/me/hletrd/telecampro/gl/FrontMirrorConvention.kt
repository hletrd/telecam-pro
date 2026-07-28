package me.hletrd.telecampro.gl

/**
 * ONE authority for the front-camera mirror device fact and every seam derived from it.
 *
 * PMA110's front HAL PRE-MIRRORS its SurfaceTexture stream (device-diagnosed 2026-07-23, commit
 * 29559a8): the delivered texture already IS the selfie-mirror view. That single fact fixes three
 * seams that used to be written as independent literals — the preview draw's mirror role, the
 * encoder/analysis draws' un-mirror role, and the tap-mapping display-vs-texture axis
 * (`mapTapFocusGeometry(mirrorX = ...)`). The roles have already flipped once on device evidence;
 * with three separate literals a re-diagnosis could update one and silently leave the others
 * disagreeing (cycle-6 architect F4 / debugger F2), so the seams consume the derived values below
 * and a future flip (different device, firmware change) is a ONE-constant edit.
 *
 * On a multi-device build this constant becomes a DeviceProfile quirk flag (CLAUDE.md).
 */
object FrontMirrorConvention {
    /** Whether the front HAL's SurfaceTexture stream arrives already selfie-mirrored. */
    const val FRONT_STREAM_PRE_MIRRORED = true

    /** The preview adds the selfie mirror ONLY when the stream does not already carry it. */
    fun previewDrawMirrorX(frontRoute: Boolean): Boolean = frontRoute && !FRONT_STREAM_PRE_MIRRORED

    /** Encoder/analysis write the TRUE scene: un-mirror exactly when the stream is pre-mirrored. */
    fun encoderDrawMirrorX(frontRoute: Boolean): Boolean = frontRoute && FRONT_STREAM_PRE_MIRRORED

    /**
     * Whether displayed x differs from TEXTURE x, i.e. the loupe/content mapping must un-flip.
     * False while the pre-mirrored stream is shown as-is: displayed x == texture x by construction.
     * This is NOT the metering question — see [meteringMirrorX].
     */
    fun tapDisplayMirrorX(frontRoute: Boolean): Boolean = previewDrawMirrorX(frontRoute)

    /**
     * Whether displayed x differs from ACTIVE-ARRAY x, i.e. AE/AF metering regions must un-flip.
     *
     * Distinct from [tapDisplayMirrorX], and the distinction is the whole point: the loupe consumes
     * TEXTURE space (which the pre-mirrored stream matches 1:1, so no flip), while metering regions
     * are specified in the sensor's ACTIVE ARRAY, which holds the TRUE, un-mirrored scene. The
     * displayed selfie is that scene mirrored, so a tap on the left of the preview is on the RIGHT
     * of the array — without this flip, front tap-AF/AE meters the horizontally opposite point
     * (cycle-6 debugger F2).
     *
     * Same value as [encoderDrawMirrorX] and for the same reason — both convert from what is shown
     * to what is true — and the flip belongs in DISPLAY space, before the tap's rotation into array
     * coordinates, because that is where the device-verified encoder flip acts (`texCoordQuad`
     * mirrors the texcoord ATTRIBUTE, i.e. the output's own horizontal axis, and a pulled front
     * still read unreversed on device).
     *
     * The tap mapping's ROTATION term remains uncalibrated on the front route; this fixes only the
     * mirror half. See the front residual checks in docs/BACKLOG.md.
     */
    fun meteringMirrorX(frontRoute: Boolean): Boolean = frontRoute && FRONT_STREAM_PRE_MIRRORED
}
