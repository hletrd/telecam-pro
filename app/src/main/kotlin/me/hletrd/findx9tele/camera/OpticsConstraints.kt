package me.hletrd.findx9tele.camera

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
    photoFormats = photoFormats.normalizedFor(photoOutputs),
)

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
