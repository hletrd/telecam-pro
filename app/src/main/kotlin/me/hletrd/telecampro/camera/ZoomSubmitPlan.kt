package me.hletrd.telecampro.camera

/**
 * One HAL zoom-submit decision for a zoom tick (see [resolveHalZoomSubmit]).
 * [controlsZoomRatio] is the EXACT requested ratio the controller's still-request truth must carry
 * for this tick REGARDLESS of [submitNow] — a shutter press during a moving gesture must frame
 * what the viewfinder shows, never the previous tick's ratio.
 */
internal data class ZoomSubmitPlan(
    val submitNow: Boolean,
    val controlsZoomRatio: Float,
)

/**
 * The HAL half of a zoom tick, as a pure decision so moving suppression is unit-testable
 * (this exact logic took three rounds of on-device "pinch stutter" reports to converge — see
 * CLAUDE.md's setRepeatingRequest-stall fact):
 *
 * - **A MOVING gesture submits NOTHING.** Device measurement 2026-07-27: 12 zoom submits over
 *   ~4.2 s stalled the preview for ≥1794 ms total (six gaps ≥200 ms, individual gaps to 413 ms,
 *   and gaps under 200 ms are not even logged) — the "frame rate drops while zooming" report. The
 *   old ≥200 ms spacing did NOT fix that: the same run's submits already landed ~400 ms
 *   apart, double the floor, and stalled just as hard, because the stall belongs to the
 *   repeating-request SWAP itself and not to how tightly swaps are packed. So the fix is to stop
 *   swapping while the finger moves, not to space the swaps out. A ViewModel-owned quiet-window
 *   landing separately lands the exact ratio when the finger PAUSES.
 * - Outside a gesture the EXACT ratio submits unconditionally.
 *
 * Gesture edge, quiet-landing, and boost-tail submissions have separate owners; this function does
 * not model them. It only guarantees that periodic moving ticks never touch Camera2 while their
 * exact ratio continues to update still-request truth.
 */
internal fun resolveHalZoomSubmit(
    requestedZoom: Float,
    interactionActive: Boolean,
): ZoomSubmitPlan = ZoomSubmitPlan(
    submitNow = !interactionActive,
    controlsZoomRatio = requestedZoom,
)

/** The actual gesture-start Camera2 target: pre-buy one bounded margin of wider source field. */
internal fun resolveZoomGestureEdgeTarget(
    exactZoom: Float,
    gestureMargin: Float,
    rangeLower: Float?,
    rangeUpper: Float?,
): Float = clampToOrderedBounds(exactZoom / gestureMargin, rangeLower, rangeUpper)

internal data class ZoomInteractionState(
    val active: Boolean = false,
    val exactLanded: Boolean = false,
)

internal data class ZoomInteractionTransition(
    val next: ZoomInteractionState,
    val submitExact: Boolean,
)

/** A fresh edge, including a re-pinch during the old boost tail, spends the previous exact landing. */
internal fun startZoomInteraction(): ZoomInteractionTransition = ZoomInteractionTransition(
    next = ZoomInteractionState(active = true),
    submitExact = true,
)

/** A suppressed moving tick after a landing makes the current exact wire value stale again. */
internal fun noteZoomMovement(state: ZoomInteractionState): ZoomInteractionState =
    if (state.active && state.exactLanded) state.copy(exactLanded = false) else state

/** The quiet timer lands once per unsettled movement burst while an interaction is live. */
internal fun landQuietZoom(state: ZoomInteractionState): ZoomInteractionTransition =
    if (state.active && !state.exactLanded) {
        ZoomInteractionTransition(
            next = state.copy(exactLanded = true),
            submitExact = true,
        )
    } else {
        ZoomInteractionTransition(next = state, submitExact = false)
    }

/** End needs an exact zoom-only submit only when no quiet landing already put it on the wire. */
internal fun endZoomInteraction(state: ZoomInteractionState): ZoomInteractionTransition =
    ZoomInteractionTransition(
        next = ZoomInteractionState(),
        submitExact = state.active && !state.exactLanded,
    )

internal enum class ZoomBoostFlipApply {
    STATE_ONLY,
    FAST_PATH,
    REBUILD,
}

/** Route-specific boost-tail work after exact-framing ownership is known. */
internal fun resolveZoomBoostFlipApply(
    fpsDecisionChanges: Boolean?,
    submitExactWhenFpsUnchanged: Boolean,
): ZoomBoostFlipApply = when {
    fpsDecisionChanges != false -> ZoomBoostFlipApply.REBUILD
    submitExactWhenFpsUnchanged -> ZoomBoostFlipApply.FAST_PATH
    else -> ZoomBoostFlipApply.STATE_ONLY
}
