package com.hletrd.findx9tele.camera

/**
 * One HAL zoom-submit decision for a zoom tick (see [resolveHalZoomSubmit]).
 * [controlsZoomRatio] is the EXACT requested ratio the controller's still-request truth must carry
 * for this tick REGARDLESS of [submitNow] — a shutter press inside the throttle window must frame
 * what the viewfinder shows, never the previous tick's ratio and never the wide-aimed [halTarget].
 */
internal data class ZoomSubmitPlan(
    val halTarget: Float,
    val submitNow: Boolean,
    val controlsZoomRatio: Float,
)

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
        clampToOrderedBounds(wide, rangeLower, rangeUpper)
    } else {
        requestedZoom
    }
    val submitNow = !interactionActive || nowMs - lastSubmitMs >= throttleMs
    return ZoomSubmitPlan(halTarget = halTarget, submitNow = submitNow, controlsZoomRatio = requestedZoom)
}
