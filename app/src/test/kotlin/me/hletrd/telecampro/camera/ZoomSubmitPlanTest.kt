package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins moving suppression, the actual gesture-edge aim, and complete interaction-tail ownership.
 * These rules took three rounds of on-device "pinch stutter" reports to converge; a future edit to
 * the suppression, margin, landing, or end branch must fail here, not on the device.
 */
class ZoomSubmitPlanTest {

    private val margin = 1.2f
    private fun plan(z: Float, active: Boolean) = resolveHalZoomSubmit(z, active)

    private fun edge(
        z: Float,
        lower: Float? = 1f,
        upper: Float? = 10f,
    ) = resolveZoomGestureEdgeTarget(z, margin, lower, upper)

    @Test
    fun `idle submit is exact and unconditional`() {
        val p = plan(3f, active = false)
        assertTrue(p.submitNow)
        assertEquals(3f, p.controlsZoomRatio, 0f)
    }

    @Test
    fun `actual start edge aims wide by the margin`() {
        assertEquals(6f / margin, edge(6f), 1e-6f)
    }

    @Test
    fun `wide aim clamps at the range lower edge`() {
        assertEquals(1f, edge(1.1f), 0f) // 1.1/1.2 < lower → clamped
    }

    @Test
    fun `wide aim clamps at the range upper edge`() {
        assertEquals(10f, edge(13f), 0f)
    }

    @Test
    fun `unknown range leaves the wide aim unclamped`() {
        assertEquals(6f / margin, edge(6f, lower = null, upper = null), 1e-6f)
    }

    @Test
    fun `inverted or non-finite range fails open instead of throwing`() {
        assertEquals(5f, edge(6f, lower = 10f, upper = 1f), 0f)
        assertEquals(5f, edge(6f, lower = Float.NaN, upper = 10f), 0f)
        assertEquals(5f, clampToOrderedBounds(5f, Float.NEGATIVE_INFINITY, 10f), 0f)
    }

    @Test
    fun `a moving gesture never submits`() {
        // Device-measured 2026-07-27: spacing submits did NOT help. Submits already ~400 ms apart
        // (double the old floor) still stalled the preview 210-413 ms each, because the stall is a
        // property of the repeating-request SWAP and not of how tightly swaps are packed. So the
        // Time is intentionally absent from this decision: every moving tick must stay silent or
        // the "frame rate drops while zooming" report comes straight back.
        assertFalse(plan(4f, active = true).submitNow)
    }

    @Test
    fun `idle tick submits regardless of elapsed time`() {
        val p = plan(4f, active = false)
        assertTrue(p.submitNow)
    }

    // ---- controlsZoomRatio: the still-request truth (8e12013 exact-ratio invariant + AGG3-27) ----
    // A still snapshots the controller's controls, so EVERY plan — submitted or suppressed, wide-
    // aimed or exact — must carry the EXACT requested ratio for the still truth. The wide aim is
    // preview-only; moving suppression may swallow the repeating submit but never framing truth.

    @Test
    fun `mid-gesture wide aim still carries the exact ratio for stills`() {
        val p = plan(4f, active = true)
        assertFalse(p.submitNow)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }

    @Test
    fun `suppressed moving tick still carries the exact ratio for stills`() {
        val p = plan(4f, active = true)
        assertFalse(p.submitNow)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }

    @Test
    fun `idle submit carries the exact ratio`() {
        val p = plan(4f, active = false)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }

    // ---- Complete start / quiet / re-pinch / end ownership -------------------------------

    @Test
    fun `fresh start submits an edge and end before quiet submits exact`() {
        val start = startZoomInteraction()
        assertTrue(start.next.active)
        assertFalse(start.next.exactLanded)
        assertTrue(start.submitExact)

        val end = endZoomInteraction(start.next)
        assertFalse(end.next.active)
        assertTrue(end.submitExact)
    }

    @Test
    fun `quiet landing owns exact so no-FPS end is state-only`() {
        val start = startZoomInteraction()
        val quiet = landQuietZoom(start.next)
        assertTrue(quiet.submitExact)
        assertTrue(quiet.next.exactLanded)

        val end = endZoomInteraction(quiet.next)
        assertFalse(end.submitExact)
        assertEquals(ZoomInteractionState(), end.next)
    }

    @Test
    fun `quiet timer outside an interaction is inert`() {
        val quiet = landQuietZoom(ZoomInteractionState())
        assertFalse(quiet.submitExact)
        assertEquals(ZoomInteractionState(), quiet.next)
    }

    @Test
    fun `outward repinch after quiet starts a new edge and end must submit again`() {
        val quiet = landQuietZoom(startZoomInteraction().next)
        val repinch = startZoomInteraction()
        assertTrue(quiet.next.exactLanded)
        assertFalse(repinch.next.exactLanded)
        assertTrue(endZoomInteraction(repinch.next).submitExact)
    }

    @Test
    fun `inward repinch movement after quiet earns a second landing without a new edge`() {
        val firstQuiet = landQuietZoom(startZoomInteraction().next)
        val moved = noteZoomMovement(firstQuiet.next)
        val secondQuiet = landQuietZoom(moved)

        assertFalse(moved.exactLanded)
        assertTrue(secondQuiet.submitExact)
        assertTrue(secondQuiet.next.exactLanded)
        assertFalse(endZoomInteraction(secondQuiet.next).submitExact)
    }

    @Test
    fun `duplicate quiet callback without movement is idempotent`() {
        val firstQuiet = landQuietZoom(startZoomInteraction().next)
        val duplicate = landQuietZoom(firstQuiet.next)

        assertFalse(duplicate.submitExact)
        assertEquals(firstQuiet.next, duplicate.next)
    }

    @Test
    fun `no-FPS tail skips an already-landed exact submit`() {
        assertEquals(
            ZoomBoostFlipApply.STATE_ONLY,
            resolveZoomBoostFlipApply(fpsDecisionChanges = false, submitExactWhenFpsUnchanged = false),
        )
    }

    @Test
    fun `no-FPS tail submits exact when quiet did not land`() {
        assertEquals(
            ZoomBoostFlipApply.FAST_PATH,
            resolveZoomBoostFlipApply(fpsDecisionChanges = false, submitExactWhenFpsUnchanged = true),
        )
    }

    @Test
    fun `FPS-changing and unknown routes rebuild regardless of quiet landing`() {
        assertEquals(
            ZoomBoostFlipApply.REBUILD,
            resolveZoomBoostFlipApply(fpsDecisionChanges = true, submitExactWhenFpsUnchanged = false),
        )
        assertEquals(
            ZoomBoostFlipApply.REBUILD,
            resolveZoomBoostFlipApply(fpsDecisionChanges = null, submitExactWhenFpsUnchanged = false),
        )
    }
}
