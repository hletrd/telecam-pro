package com.hletrd.findx9tele.ui

/**
 * The Android-free half of the ViewModel's zoom-interaction lifecycle — the four plain fields the
 * coalescer/ease-glide machinery reads and every optics-SCALE remap door must invalidate. Extracted
 * so the invalidation (which cycle-2 hand-duplicated across ~10 sites and forgot at several — the
 * exact gap that produced AGG3-10/25/26/51/VER-3/ARCH-4) has ONE tested owner, and so the zoom-OUT
 * leading-edge decision (AGG3-9) is pinned by a host test instead of only being device-verifiable.
 *
 * The Handler-bound half (the ease ticker / quiet-landing / interaction-end / 16 ms trailing-flush
 * Runnables and the engine boost flip) stays in the ViewModel; [invalidateForRemap] clears exactly
 * the plain state and the ViewModel's `invalidateZoomGlide()` cancels the matching timers.
 */
internal class ZoomGlideState {
    /**
     * The coalesced pending ratio while a 16 ms flush window is open; NaN when idle. UI state lags
     * this by up to one flush, so every compounding zoom input (pinch factor, hardware-key step,
     * ease ticker) must base on it via [base], not on `_state`.
     */
    var pendingRatio: Float = Float.NaN

    /**
     * The hardware-key glide target — an ABSOLUTE ratio in the CURRENT zoom scale; null when idle.
     * A glide surviving a scale remap would ease toward an un-commanded framing in the new scale,
     * which is why every remap door nulls it.
     */
    var easeTarget: Float? = null

    /** True from a gesture's first flush until its 700 ms interaction-end fires (drives the boost). */
    var interacting: Boolean = false

    /** True while a 16 ms trailing coalescer flush is queued. */
    var flushScheduled: Boolean = false

    /**
     * The freshest zoom base: the pending coalesced ratio while a flush window is open (UI state
     * lags it by up to 16 ms), else the committed [stateRatio].
     */
    fun base(stateRatio: Float): Float = pendingRatio.takeUnless { it.isNaN() } ?: stateRatio

    /**
     * Zoom-OUT leading edge (AGG3-9): true when a gesture's FIRST tick ([interacting] not yet set)
     * moves toward WIDE ([newRatio] below the committed [currentRatio]). Only the first outward tick
     * qualifies — GL zoomComp magnifies the delivered frame instantly for zoom-IN but cannot widen
     * past the delivered crop, so only the OUT direction needs the leading-edge HAL submit; mid-
     * gesture reversals ride the existing wide-aim path.
     */
    fun isLeadingEdgeToWide(newRatio: Float, currentRatio: Float): Boolean =
        !interacting && newRatio < currentRatio

    /**
     * Clears every plain glide field for an optics-SCALE remap (mode/lens/TC/MR-recall/rollback/
     * camera-override) or lifecycle teardown (onStop). After this, [base] returns the caller's
     * committed ratio, no glide is in flight, and the next gesture's first flush re-arms the boost
     * edge on the FRESH controller. Deliberately does NOT touch the engine: a synchronous boost-off
     * at a remap door would rebuild the OUTGOING controller (see the ViewModel wrapper's note).
     */
    fun invalidateForRemap() {
        pendingRatio = Float.NaN
        easeTarget = null
        interacting = false
        flushScheduled = false
    }
}
