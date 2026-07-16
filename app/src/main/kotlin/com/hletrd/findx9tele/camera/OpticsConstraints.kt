package com.hletrd.findx9tele.camera

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

internal enum class VideoSizeRequestSource { INTERACTIVE, RECALL }

/** Recall validates after target caps arrive; a live picker must validate immediately. */
internal fun validatesVideoSizeAgainstCurrentCaps(source: VideoSizeRequestSource): Boolean =
    source == VideoSizeRequestSource.INTERACTIVE

/** Clamps normalized optics again once the selected camera's live zoom range is authoritative. */
internal fun reconcileZoomWithCaps(
    mode: CaptureMode,
    teleconverter: Boolean,
    zoomRatio: Float,
    capsLower: Float?,
    capsUpper: Float?,
): Float {
    val contractLower = if (mode == CaptureMode.PHOTO && !teleconverter) 0.6f else 1f
    val contractUpper = when {
        teleconverter -> TELE_MAX_DISPLAY_ZOOM / TELE_DISPLAY_BASE
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
