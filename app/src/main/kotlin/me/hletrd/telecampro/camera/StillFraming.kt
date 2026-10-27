package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata

/*
 * Still framing = the preview's framing (owner decision 2026-10-27, "Auto and manual stills must
 * match"). CONTROL_VIDEO_STABILIZATION_MODE is the one request key that moves a processed still's
 * field of view without the operator touching zoom: under PREVIEW_STABILIZATION the HAL crops its
 * EIS margin out of every processed stream the request carries (Camera2: "the same FoV across all
 * processed streams"; up to 20 %), PMA110's HAL does the same under ON, and a still at the still
 * template's OFF gets the full field instead. PMA110, logical 1×, 2026-10-27, against the finder:
 * an OFF still read FoV 1.15–1.16 (16 % wider than what was framed) under Standard and Active alike,
 * a still carrying the session's mode 0.99–1.00, a pseudo-ZSL-served still — a repeating-request
 * frame, so it always carries the session's mode — 1.00 (FIELD_CHECKS A23). Riding the session's
 * mode on matrix-WB stills only (the first fix, 24f5baa1) left exactly that 16 % gap on AUTO stills:
 * one press in bright light (ZSL-served) and the next in dim light (real capture) framed differently.
 *
 * Which side wins is not free: on PMA110 a TRANSFORM_MATRIX (manual / preset / custom WB) still at
 * OFF inside a stabilized session renders a white balance it was not asked for, so stills follow the
 * SESSION on every profile; every still at OFF would bring that colour bug back. The spec device
 * agrees (Galaxy Z Flip6, 2026-10-27, FIELD_CHECKS A23): its still template also carries OFF, and
 * a still that follows the session instead framed like the Active finder (FoV 1.000, against 1.24×
 * wider at OFF), kept its colour, and reached the file in 0.51–0.57 s instead of the 1.5–1.9 s a
 * Standard-session still took to drop to OFF. Its Standard (ON) stills stay at the full field: that
 * HAL crops only the preview under ON, which Camera2 permits (only PREVIEW_STABILIZATION promises
 * "the same FoV across all processed streams"). Pseudo-ZSL refuses a ring frame framed unlike the
 * real still ([zslStillFramingMatches]).
 *
 * RAW is outside all of this: stabilization never crops a RAW stream, so a DNG keeps the sensor's
 * field beside a processed sibling framed like the preview. Hi-res stills follow too (dormant on
 * every measured device, so that combination is unmeasured).
 */

/**
 * PMA110's longest still exposure measured to complete while carrying PREVIEW_STABILIZATION
 * ([DeviceProfile.previewStabilizedStillMaxExposureNs]).
 */
internal const val PMA110_PREVIEW_STABILIZED_STILL_MAX_EXPOSURE_NS = 3_200_000_000L

/**
 * The CONTROL_VIDEO_STABILIZATION_MODE a STILL request writes. [sessionMode] is the effective mode
 * the session's repeating request carries — the one value the preview and every pseudo-ZSL ring frame
 * are framed by — and the answer is exactly that mode, with one measured exception.
 *
 * A still longer than [previewStabilizedStillMaxExposureNs] in a PREVIEW_STABILIZATION session is
 * lost outright on PMA110 (`onCaptureBufferLost`, no image), so a HAL-white-balanced still that long
 * carries ON instead: measured to complete, framed like the Active finder (logical 1×: FoV 0.847 of
 * an unstabilized still for ON and for PREVIEW_STABILIZATION alike) and with the same AWB colour.
 * An app-owned white balance ([wbAppOwned] — AWB OFF + TRANSFORM_MATRIX) has no such way out: at ON
 * or OFF inside an Active session the HAL renders some other white balance (3200 K came out deep
 * blue at ON, cyan at OFF, also with the vendor stabilization mirror held at the session's value),
 * so it keeps the session's mode and that still is still lost — unchanged since the matrix-WB fix,
 * and an open owner decision (FIELD_CHECKS A23). [stillExposureNs] is the sensor exposure the still
 * request carries; null (HAL AE owns it — flash-metered P, video P) keeps the session's mode, since
 * a HAL-AE still is never seconds long.
 */
internal fun stillVideoStabilizationMode(
    sessionMode: Int,
    stillExposureNs: Long?,
    previewStabilizedStillMaxExposureNs: Long?,
    wbAppOwned: Boolean,
): Int {
    val tooLongForPreviewStabilization =
        sessionMode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION &&
            previewStabilizedStillMaxExposureNs != null && stillExposureNs != null &&
            stillExposureNs > previewStabilizedStillMaxExposureNs
    return if (tooLongForPreviewStabilization && !wbAppOwned) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
    } else {
        sessionMode
    }
}

/**
 * Whether a pseudo-ZSL ring frame — a REPEATING-request frame, framed by [repeatingMode] — is framed
 * like the real still this press would otherwise capture, whose request carries [stillMode]
 * ([stillVideoStabilizationMode]). They differ only for PMA110's long HAL-AWB still in an Active
 * session, which rides ON and is far too long for the ring to serve anyway.
 *
 * Writing nothing is no longer an option: both still templates read so far carry OFF (PMA110 and
 * the Galaxy Z Flip6, REQ videoStabilizationMode in `cmd media.camera watch`, 2026-10-27), so a
 * still left at the template's value framed unlike every stabilized finder and ring.
 */
internal fun zslStillFramingMatches(stillMode: Int, repeatingMode: Int): Boolean = stillMode == repeatingMode
