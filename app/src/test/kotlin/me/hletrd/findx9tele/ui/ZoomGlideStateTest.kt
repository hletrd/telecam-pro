package me.hletrd.findx9tele.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android-free half of the ViewModel's zoom-interaction lifecycle (P2.1/P2.2):
 *
 *  - `invalidateForRemap()` is the ONE owner every optics-scale remap door and onStop route through
 *    (`invalidateOpticsDerivedState()` in the ViewModel wraps it + cancels the matching Handler timers). Before
 *    this holder existed the invalidation was hand-duplicated across ~10 sites and forgotten at several
 *    (AGG3-10/25/26/51, VER-3, ARCH-4) — these tests fail if any field stops being cleared.
 *  - `isLeadingEdgeToWide()` is the zoom-OUT leading-edge decision (AGG3-9): the first outward tick
 *    after the pipeline goes QUIET must submit immediately, while zoom-IN and every mid-gesture tick
 *    must NOT (they ride the existing coalesced/throttled wide-aim path). Quiet — not `!interacting`
 *    — is the axis (AGG4-14): `interacting` is a 700 ms tail that outlives the finger, so gating on
 *    it disarmed the edge for a re-pinch that starts inside that tail.
 *  - `base()` is the compounding-input source of truth (`currentZoomBase()`): the coalesced pending
 *    ratio while a flush window is open, else the committed state ratio.
 */
class ZoomGlideStateTest {

    // ---- invalidateForRemap: every plain glide field is cleared (P2.1 door invariant) ----

    @Test fun `invalidateForRemap resets pending, ease target, interacting, leading edge, and flush-scheduled`() {
        val g = ZoomGlideState().apply {
            pendingRatio = 7.5f
            easeTarget = 4.2f
            interacting = true
            leadingEdgeArmed = false // a flush had spent the edge when the remap fired
            flushScheduled = true
        }
        g.invalidateForRemap()
        assertTrue("pendingRatio must be NaN after invalidate", g.pendingRatio.isNaN())
        assertNull("easeTarget must be null after invalidate", g.easeTarget)
        assertFalse("interacting must be false after invalidate", g.interacting)
        // Idle for this one is ARMED: the remap discards the outgoing controller, so no wide-aim
        // margin is spent and the next outward tick on the fresh route is a genuine leading edge.
        assertTrue("leadingEdgeArmed must be re-armed after invalidate", g.leadingEdgeArmed)
        assertFalse("flushScheduled must be false after invalidate", g.flushScheduled)
    }

    @Test fun `invalidateForRemap is idempotent on already-idle state`() {
        val g = ZoomGlideState()
        g.invalidateForRemap()
        assertTrue(g.pendingRatio.isNaN())
        assertNull(g.easeTarget)
        assertFalse(g.interacting)
        assertTrue(g.leadingEdgeArmed)
        assertFalse(g.flushScheduled)
    }

    // Models the door→helper wiring: after any remap door invalidates the glide, the next gesture's
    // FIRST tick is treated as a leading edge again (interacting reset) and compounds against the
    // committed ratio, NOT a stale pre-remap pending value.
    @Test fun `after a remap the next gesture re-bases on the committed ratio and re-arms the leading edge`() {
        val g = ZoomGlideState().apply {
            pendingRatio = 9f // stale old-scale coalesced value
            interacting = true // mid-gesture when the remap fired
            leadingEdgeArmed = false // …and that gesture's first flush had spent the edge
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

    // Re-expressed for AGG4-14: "mid-gesture" is no longer `interacting` alone (that flag is a
    // 700 ms TAIL that outlives the finger), it is `interacting` AND an edge already spent by this
    // gesture's first flush. A recent tick is exactly that state.
    @Test fun `mid-gesture outward ticks are not leading edges (only the first after quiet)`() {
        val g = ZoomGlideState().apply {
            interacting = true
            leadingEdgeArmed = false // this gesture's first flush spent it; no quiet window since
        }
        assertFalse("subsequent outward ticks ride the throttled wide-aim path", g.isLeadingEdgeToWide(2f, 6f))
    }

    // ---- AGG4-14: a gesture that BEGINS inside the previous gesture's 700 ms tail ----
    //
    // `interacting` is still true here (it is re-posted 700 ms past the last flush), but the
    // pipeline went quiet for a throttle window, so either the quiet-window landing or a real
    // onPinchEnd re-armed the edge. This is the case the pre-fix `!interacting` gate could not
    // express, and the worst one to miss: the landing already spent the 1.2× wide-aim margin and
    // GL zoomComp is clamped at 1, so an outward finger has NO source of new field until the HAL
    // submits.

    @Test fun `an outward re-pinch inside the interaction tail is a leading edge once re-armed`() {
        val g = ZoomGlideState().apply {
            interacting = true // the previous gesture's boost tail is still running
            leadingEdgeArmed = true // quiet-window landing (or onPinchEnd) re-armed it
        }
        assertTrue("a re-pinch to wide must take the immediate submit", g.isLeadingEdgeToWide(4f, 6f))
    }

    @Test fun `an inward re-pinch inside the interaction tail is still not a leading edge`() {
        val g = ZoomGlideState().apply {
            interacting = true
            leadingEdgeArmed = true
        }
        assertFalse(
            "zoom-IN has GL headroom in every window; the re-arm must not widen the swallow",
            g.isLeadingEdgeToWide(8f, 6f),
        )
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
