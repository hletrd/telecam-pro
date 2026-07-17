package com.hletrd.findx9tele.camera

/** One HAL zoom-submit decision for a zoom tick (see [resolveHalZoomSubmit]). */
internal data class ZoomSubmitPlan(val halTarget: Float, val submitNow: Boolean)

/**
 * The HAL half of a zoom tick, as a pure decision so the throttle/wide-aim rules are unit-testable
 * (this exact logic took three rounds of on-device "pinch stutter" reports to converge — see
 * CLAUDE.md's setRepeatingRequest-stall fact):
 *
 * - Mid-gesture the target is aimed slightly WIDE (÷[gestureMargin], clamped to the advertised
 *   range) so the GL crop keeps field for instant zoom-out, and submits are throttled to
 *   ≥[throttleMs] — every repeating-request swap gaps this HAL's stream ~180 ms.
 * - Outside a gesture (or the moment one ends) the EXACT ratio submits unconditionally.
 */
internal fun resolveHalZoomSubmit(
    requestedZoom: Float,
    interactionActive: Boolean,
    nowMs: Long,
    lastSubmitMs: Long,
    gestureMargin: Float,
    throttleMs: Long,
    rangeLower: Float?,
    rangeUpper: Float?,
): ZoomSubmitPlan {
    val halTarget = if (interactionActive) {
        val wide = requestedZoom / gestureMargin
        if (rangeLower != null && rangeUpper != null) wide.coerceIn(rangeLower, rangeUpper) else wide
    } else {
        requestedZoom
    }
    val submitNow = !interactionActive || nowMs - lastSubmitMs >= throttleMs
    return ZoomSubmitPlan(halTarget = halTarget, submitNow = submitNow)
}
