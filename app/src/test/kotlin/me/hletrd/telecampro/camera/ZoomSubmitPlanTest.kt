package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the HAL zoom-submit decision (moving suppression + wide aim) from CameraEngine.setZoomRatio.
 * These rules took three rounds of on-device "pinch stutter" reports to converge; a future edit to
 * the suppression rule, margin, or interaction branch must fail here, not on the device.
 */
class ZoomSubmitPlanTest {

    private val margin = 1.2f
    private fun plan(
        z: Float,
        active: Boolean,
        lower: Float? = 1f,
        upper: Float? = 10f,
    ) = resolveHalZoomSubmit(z, active, margin, lower, upper)

    @Test
    fun `idle submit is exact and unconditional`() {
        val p = plan(3f, active = false)
        assertTrue(p.submitNow)
        assertEquals(3f, p.halTarget, 0f)
    }

    @Test
    fun `mid-gesture target aims wide by the margin`() {
        val p = plan(6f, active = true)
        assertEquals(6f / margin, p.halTarget, 1e-6f)
    }

    @Test
    fun `wide aim clamps at the range lower edge`() {
        val p = plan(1.1f, active = true)
        assertEquals(1f, p.halTarget, 0f) // 1.1/1.2 < lower → clamped
    }

    @Test
    fun `wide aim clamps at the range upper edge`() {
        val p = plan(13f, active = true)
        assertEquals(10f, p.halTarget, 0f)
    }

    @Test
    fun `unknown range leaves the wide aim unclamped`() {
        val p = plan(6f, active = true, lower = null, upper = null)
        assertEquals(6f / margin, p.halTarget, 1e-6f)
    }

    @Test
    fun `inverted or non-finite range fails open instead of throwing`() {
        assertEquals(5f, plan(6f, active = true, lower = 10f, upper = 1f).halTarget, 0f)
        assertEquals(5f, plan(6f, active = true, lower = Float.NaN, upper = 10f).halTarget, 0f)
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
    fun `a moving gesture still reports the wide aim for the edge that does submit`() {
        // halTarget stays meaningful while suppressed: the gesture-START edge submits it to pre-buy
        // the zoom-out margin the GL crop lives on for the rest of the gesture.
        assertEquals(4f / margin, plan(4f, active = true).halTarget, 1e-6f)
    }

    @Test
    fun `gesture end always submits regardless of elapsed time`() {
        val p = plan(4f, active = false)
        assertTrue(p.submitNow)
        assertEquals(4f, p.halTarget, 0f)
    }

    // ---- controlsZoomRatio: the still-request truth (8e12013 exact-ratio invariant + AGG3-27) ----
    // A still snapshots the controller's controls, so EVERY plan — submitted or suppressed, wide-
    // aimed or exact — must carry the EXACT requested ratio for the still truth. The wide aim is
    // preview-only; moving suppression may swallow the repeating submit but never framing truth.

    @Test
    fun `mid-gesture wide aim still carries the exact ratio for stills`() {
        val p = plan(4f, active = true)
        assertFalse(p.submitNow)
        assertEquals(4f / margin, p.halTarget, 1e-6f)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }

    @Test
    fun `suppressed moving tick still carries the exact ratio for stills`() {
        val p = plan(4f, active = true)
        assertFalse(p.submitNow)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }

    @Test
    fun `idle submit carries the same exact ratio in both fields`() {
        val p = plan(4f, active = false)
        assertEquals(4f, p.halTarget, 0f)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }
}
