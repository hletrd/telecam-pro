package com.hletrd.findx9tele.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android-free half of the ViewModel's zoom-interaction lifecycle (P2.1/P2.2):
 *
 *  - `invalidateForRemap()` is the ONE owner every optics-scale remap door and onStop route through
 *    (`invalidateZoomGlide()` in the ViewModel wraps it + cancels the matching Handler timers). Before
 *    this holder existed the invalidation was hand-duplicated across ~10 sites and forgotten at several
 *    (AGG3-10/25/26/51, VER-3, ARCH-4) — these tests fail if any field stops being cleared.
 *  - `isLeadingEdgeToWide()` is the zoom-OUT leading-edge decision (AGG3-9): the first outward gesture
 *    tick must submit immediately, while zoom-IN and every mid-gesture tick must NOT (they ride the
 *    existing coalesced/throttled wide-aim path).
 *  - `base()` is the compounding-input source of truth (`currentZoomBase()`): the coalesced pending
 *    ratio while a flush window is open, else the committed state ratio.
 */
class ZoomGlideStateTest {

    // ---- invalidateForRemap: every plain glide field is cleared (P2.1 door invariant) ----

    @Test fun `invalidateForRemap clears pending, ease target, interacting, and flush-scheduled`() {
        val g = ZoomGlideState().apply {
            pendingRatio = 7.5f
            easeTarget = 4.2f
            interacting = true
            flushScheduled = true
        }
        g.invalidateForRemap()
        assertTrue("pendingRatio must be NaN after invalidate", g.pendingRatio.isNaN())
        assertNull("easeTarget must be null after invalidate", g.easeTarget)
        assertFalse("interacting must be false after invalidate", g.interacting)
        assertFalse("flushScheduled must be false after invalidate", g.flushScheduled)
    }

    @Test fun `invalidateForRemap is idempotent on already-idle state`() {
        val g = ZoomGlideState()
        g.invalidateForRemap()
        assertTrue(g.pendingRatio.isNaN())
        assertNull(g.easeTarget)
        assertFalse(g.interacting)
        assertFalse(g.flushScheduled)
    }

    // Models the door→helper wiring: after any remap door invalidates the glide, the next gesture's
    // FIRST tick is treated as a leading edge again (interacting reset) and compounds against the
    // committed ratio, NOT a stale pre-remap pending value.
    @Test fun `after a remap the next gesture re-bases on the committed ratio and re-arms the leading edge`() {
        val g = ZoomGlideState().apply {
            pendingRatio = 9f // stale old-scale coalesced value
            interacting = true // mid-gesture when the remap fired
        }
        g.invalidateForRemap()
        assertEquals("base must ignore the invalidated pending ratio", 3f, g.base(3f), 0f)
        assertTrue("first post-remap outward tick is a fresh leading edge", g.isLeadingEdgeToWide(2f, 3f))
    }

    // ---- isLeadingEdgeToWide: P2.2 zoom-OUT leading edge ----

    @Test fun `leading-edge-out fires on the first outward tick of a gesture`() {
        val g = ZoomGlideState() // interacting = false (gesture not yet started)
        assertTrue(g.isLeadingEdgeToWide(newRatio = 4f, currentRatio = 6f))
    }

    @Test fun `leading-edge-in does not fire (zoom-IN keeps the swallow, no regression)`() {
        val g = ZoomGlideState()
        assertFalse("zoom-IN first tick must not take the leading-edge submit", g.isLeadingEdgeToWide(8f, 6f))
    }

    @Test fun `an equal-ratio first tick is not a leading edge`() {
        val g = ZoomGlideState()
        assertFalse(g.isLeadingEdgeToWide(6f, 6f))
    }

    @Test fun `mid-gesture outward ticks are not leading edges (only the first)`() {
        val g = ZoomGlideState().apply { interacting = true }
        assertFalse("subsequent outward ticks ride the throttled wide-aim path", g.isLeadingEdgeToWide(2f, 6f))
    }

    // ---- base: currentZoomBase source of truth ----

    @Test fun `base prefers the pending coalesced ratio while a flush window is open`() {
        val g = ZoomGlideState().apply { pendingRatio = 5.5f }
        assertEquals(5.5f, g.base(2f), 0f)
    }

    @Test fun `base falls back to the committed state ratio when idle`() {
        val g = ZoomGlideState() // pendingRatio = NaN
        assertEquals(2f, g.base(2f), 0f)
    }
}
