package com.hletrd.findx9tele.gl

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
     * Whether displayed x differs from texture x, i.e. the tap mapping must un-flip. False while
     * the pre-mirrored stream is shown as-is: displayed x == texture x by construction, whatever
     * the sensor's own mirror relationship is. (The SENSOR-half question — whether metering
     * regions computed from texture space land on the tapped subject — is a separate
     * device-verification item; see docs/BACKLOG.md front residual checks.)
     */
    fun tapDisplayMirrorX(frontRoute: Boolean): Boolean = previewDrawMirrorX(frontRoute)
}
