package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata

/*
 * Still framing = the preview's framing (owner decision 2026-10-27, "Auto and manual stills must
 * match"). CONTROL_VIDEO_STABILIZATION_MODE is the one request key that moves a processed still's
 * field of view without the operator touching zoom: under ON or PREVIEW_STABILIZATION the HAL crops
 * its EIS margin out of every processed stream the request carries (Camera2: PREVIEW_STABILIZATION
 * gives "the same FoV across all processed streams"; up to 20 %), and a still at the still
 * template's OFF gets the full field instead. PMA110, logical 1×, 2026-10-27, against the finder:
 * an OFF still read FoV 1.15–1.16 (16 % wider than what was framed) under Standard and Active alike,
 * a still carrying the session's mode 0.99–1.00, a pseudo-ZSL-served still — a repeating-request
 * frame, so it always carries the session's mode — 1.00 (FIELD_CHECKS A23).
 *
 * Which side wins is not free: on PMA110 a TRANSFORM_MATRIX (manual / preset / custom WB) still at
 * OFF inside a stabilized session renders a white balance it was not asked for, so stills follow the
 * SESSION ([DeviceProfile.stillKeepsSessionStabilization]); every still at OFF would bring that
 * colour bug back. GENERIC keeps the spec path (the template's own value) until a device is measured,
 * and pseudo-ZSL there refuses a ring frame framed unlike the real still ([zslStillFramingMatches]).
 *
 * RAW is outside all of this: stabilization never crops a RAW stream, so a DNG keeps the sensor's
 * field beside a processed sibling framed like the preview.
 */

/**
 * The CONTROL_VIDEO_STABILIZATION_MODE a STILL request writes, or null to write nothing and keep
 * the still template's own value (the spec path). [sessionMode] is the effective mode the session's
 * repeating request carries — the one value the preview and every pseudo-ZSL ring frame are framed
 * by — so a non-null answer is always exactly that mode, never a third one.
 */
internal fun stillVideoStabilizationMode(keepsSessionStabilization: Boolean, sessionMode: Int): Int? =
    if (keepsSessionStabilization) sessionMode else null

/**
 * Whether a pseudo-ZSL ring frame — a REPEATING-request frame, framed by [repeatingMode] — is framed
 * like the real still this press would otherwise capture, whose request carries [stillMode]
 * ([stillVideoStabilizationMode]; null = the still template's own value).
 *
 * A null still mode is read as OFF: the still template's value on PMA110 (every AUTO still request
 * in `cmd media.camera watch` read REQ videoStabilizationMode OFF, 2026-10-27) and the shape both
 * PMA110 still traps were measured in. So on a spec device a ring frame serves only while the
 * session itself runs OFF. A device whose still template defaulted to a stabilized mode would only
 * refuse more on a stabilized session; the one case this cannot see is such a template under an OFF
 * session — not PMA110's shape, and no other device's template has been read.
 */
internal fun zslStillFramingMatches(stillMode: Int?, repeatingMode: Int): Boolean =
    (stillMode ?: CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == repeatingMode
