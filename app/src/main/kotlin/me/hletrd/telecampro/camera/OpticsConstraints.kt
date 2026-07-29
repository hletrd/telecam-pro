package me.hletrd.telecampro.camera

import kotlin.math.max
import kotlin.math.min

internal enum class PendingControlsDisposition { DRAIN_BEFORE_OPTICS, CANCEL_FOR_REPLACEMENT }

/** Which delayed whole-controls packet, if any, still belongs to the next camera transaction. */
internal fun pendingControlsForTransition(
    pending: ManualControls?,
    disposition: PendingControlsDisposition,
): ManualControls? = when (disposition) {
    PendingControlsDisposition.DRAIN_BEFORE_OPTICS -> pending
    PendingControlsDisposition.CANCEL_FOR_REPLACEMENT -> null
}

internal data class AcceptedOpticsAuxState(
    val preTeleUnifiedZoom: Float,
    val photoFormats: PhotoFormats,
)

/** Auxiliary UI state changes only when the desired camera transaction reaches Ready. */
internal fun acceptedOpticsAuxState(
    teleconverter: Boolean,
    photoOutputs: PhotoSessionOutputs,
    preTeleUnifiedZoom: Float,
    photoFormats: PhotoFormats,
): AcceptedOpticsAuxState = AcceptedOpticsAuxState(
    preTeleUnifiedZoom = if (teleconverter) preTeleUnifiedZoom else Float.NaN,
    // What an accepted session may edit in the operator's format request, and what it may not
    // (both halves device-reproduced 2026-07-29):
    //
    // - No still lane AT ALL is a session STATE, not an answer about formats — the 10-bit video
    //   session drops both still readers by design, and preview-only is a fallback rung. Normalising
    //   against that wrote the EMPTY set over the request, which persisted on background and came
    //   back as HEIF-only next launch.
    // - The PROCESSED axis IS a genuine session answer: a hi-res session collapses to passthrough
    //   JPEG, and a DNG-only session really has no processed reader. It still normalises.
    // - The RAW axis is NOT. RAW's presence is a CONSEQUENCE of this very request — wanting DNG is
    //   what moves the route — so letting the session clear it made the engine's `rawWanted` and the
    //   UI's `dngRaw` diverge, and `setRawWanted`'s change gate froze the divergence: the operator
    //   saw DNG off while photo stayed pinned to a standalone lens, silently losing seamless zoom for
    //   a format they no longer appeared to have chosen (seen on the front-camera trip).
    //
    // [rawSelectable] disables the chip wherever the route structurally cannot deliver RAW, and
    // capture-time normalisation in CameraEngine still refuses to shoot a missing output — so keeping
    // intent here can never produce a bogus capture.
    photoFormats = if (photoOutputs.hasStillTarget) {
        photoFormats.normalizedFor(photoOutputs).copy(dngRaw = photoFormats.dngRaw)
    } else {
        photoFormats
    },
)

/**
 * Whether choosing DNG can actually yield a RAW file.
 *
 * Two different questions used to share one flag. RAW is a DEVICE capability, but it is also a ROUTE
 * INPUT: in PHOTO, wanting DNG is exactly what moves the session off the logical camera onto a
 * standalone lens that can deliver it, so gating the chip on session truth made it unreachable
 * (disabled because RAW was absent, absent because it could not be enabled). Gating it purely on
 * device capability then over-corrected: in a 10-bit VIDEO session — which drops both still readers
 * by design — the chip stayed live and the caption read "HEIF/JPEG unavailable; DNG only" while DNG
 * was equally unavailable and no route change could bring it back.
 *
 * So: honour the capability, and require that the session either already carries RAW or belongs to a
 * mode where selecting DNG is what brings it. [hiResSession] is excluded because its one ladder rung
 * force-drops RAW (a full-sensor blob plus RAW is the over-demanding combo this HAL punishes), and
 * [frontFacing] because the session plan force-drops RAW on the front route as well — the front
 * camera advertising RAW would otherwise leave a live chip promising a DNG that never arrives.
 */
internal fun rawSelectable(
    deviceSupportsRaw: Boolean,
    rawInSession: Boolean,
    videoMode: Boolean,
    hiResSession: Boolean,
    frontFacing: Boolean,
): Boolean = deviceSupportsRaw && !frontFacing && (rawInSession || (!videoMode && !hiResSession))

/**
 * Clamps normalized optics again once the selected camera's live zoom range is authoritative.
 *
 * [teleconverterMagnification] is the SELECTED converter's magnification: TELE's contract ceiling is
 * a cap on TOTAL magnification, so the local ratio it permits scales inversely with the optic.
 */
internal fun reconcileZoomWithCaps(
    mode: CaptureMode,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    zoomRatio: Float,
    capsLower: Float?,
    capsUpper: Float?,
): Float {
    val contractLower = if (mode == CaptureMode.PHOTO && !teleconverter) 0.6f else 1f
    val contractUpper = when {
        teleconverter -> TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(teleconverterMagnification)
        mode == CaptureMode.VIDEO -> 10f
        else -> 20f
    }
    val safe = zoomRatio.takeIf { it.isFinite() }?.coerceIn(contractLower, contractUpper) ?: contractLower
    val liveLower = capsLower?.takeIf { it.isFinite() } ?: return safe
    val liveUpper = capsUpper?.takeIf { it.isFinite() } ?: return safe
    val lower = max(contractLower, liveLower)
    val upper = min(contractUpper, liveUpper)
    return if (lower <= upper) safe.coerceIn(lower, upper) else safe
}

/** One complete capability + route normalization boundary for recalled/live control packets. */
internal fun normalizeControlsForRoute(
    requested: ManualControls,
    capabilities: CameraControlCapabilities,
    mode: CaptureMode,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    capsLower: Float?,
    capsUpper: Float?,
): ManualControls {
    // Video PROGRAM normally belongs to HAL AE. Route switches happen while mode already equals
    // VIDEO, so clear ownership at this central caps seam rather than only on Photo -> Video entry;
    // an AE_OFF-only target will truthfully re-enable the app-side fallback during normalization.
    val modeIntent = if (mode == CaptureMode.VIDEO && requested.exposureMode == ExposureMode.PROGRAM) {
        requested.copy(programAppSide = false)
    } else {
        requested
    }
    val capabilityControls = modeIntent.normalizedFor(capabilities).normalizedForCaptureMode(mode)
    return capabilityControls.copy(
        zoomRatio = reconcileZoomWithCaps(
            mode = mode,
            teleconverter = teleconverter,
            teleconverterMagnification = teleconverterMagnification,
            zoomRatio = capabilityControls.zoomRatio,
            capsLower = capsLower,
            capsUpper = capsUpper,
        ),
    )
}

/**
 * Retained-session terminal normalization. Callers pass the live packet while holding the engine
 * monitor; taking a transition-time snapshot here would lose controls accepted while setup queued.
 */
internal fun normalizeRetainedControlsAtCommit(
    liveControls: ManualControls,
    capabilities: CameraControlCapabilities,
    mode: CaptureMode,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    capsLower: Float?,
    capsUpper: Float?,
): ManualControls = normalizeControlsForRoute(
    requested = liveControls,
    capabilities = capabilities,
    mode = mode,
    teleconverter = teleconverter,
    teleconverterMagnification = teleconverterMagnification,
    capsLower = capsLower,
    capsUpper = capsUpper,
)
