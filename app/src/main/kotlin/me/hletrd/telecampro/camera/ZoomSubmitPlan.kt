package me.hletrd.telecampro.camera

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
 *   range) so the GL crop keeps field for instant zoom-out.
 * - **A MOVING gesture submits NOTHING.** Device measurement 2026-07-27: 12 zoom submits over
 *   ~4.2 s stalled the preview for ≥1794 ms total (six gaps ≥200 ms, individual gaps to 413 ms,
 *   and gaps under 200 ms are not even logged) — the "frame rate drops while zooming" report. The
 *   old ≥[throttleMs] spacing did NOT fix that: the same run's submits already landed ~400 ms
 *   apart, double the floor, and stalled just as hard, because the stall belongs to the
 *   repeating-request SWAP itself and not to how tightly swaps are packed. So the fix is to stop
 *   swapping while the finger moves, not to space the swaps out. [throttleMs] is retained as the
 *   pacing floor for the quiet-window landing that lands the exact ratio when the finger PAUSES.
 * - Outside a gesture (or the moment one ends) the EXACT ratio submits unconditionally.
 *
 * A moving gesture therefore costs exactly TWO swaps — one at each edge — instead of one per
 * ~200 ms. The cost, accepted deliberately: with no mid-gesture submit the HAL field is frozen at
 * the edge's wide-aimed target, so the preview softens progressively as the user zooms IN (GL is
 * upscaling) and runs out of field entirely if they zoom OUT past [gestureMargin]. The gesture-START
 * submit is what pre-buys that margin, which is why it must be the wide-aimed one.
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
    // `throttleMs`/`nowMs`/`lastSubmitMs` are still parameters because the QUIET-window landing
    // (CameraEngine.landExactZoom) paces off the same stamps; a moving tick simply never submits.
    val submitNow = !interactionActive
    return ZoomSubmitPlan(halTarget = halTarget, submitNow = submitNow, controlsZoomRatio = requestedZoom)
}
