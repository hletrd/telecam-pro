package com.hletrd.findx9tele.camera

import kotlin.math.max
import kotlin.math.min

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
