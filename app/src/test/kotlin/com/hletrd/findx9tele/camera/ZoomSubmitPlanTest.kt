package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the HAL zoom-submit decision (throttle + wide-aim) extracted from CameraEngine.setZoomRatio.
 * These rules took three rounds of on-device "pinch stutter" reports to converge; a future edit to
 * the throttle window, the margin, or the interaction branch must fail here, not on the device.
 */
class ZoomSubmitPlanTest {

    private val margin = 1.2f
    private val throttle = 200L

    private fun plan(
        z: Float,
        active: Boolean,
        now: Long = 1_000L,
        last: Long = 0L,
        lower: Float? = 1f,
        upper: Float? = 10f,
    ) = resolveHalZoomSubmit(z, active, now, last, margin, throttle, lower, upper)

    @Test
    fun `idle submit is exact and unconditional`() {
        val p = plan(3f, active = false, now = 10L, last = 9L) // throttle window NOT elapsed
        assertTrue(p.submitNow)
        assertEquals(3f, p.halTarget, 0f)
    }

    @Test
    fun `mid-gesture target aims wide by the margin`() {
        val p = plan(6f, active = true, now = 1_000L, last = 0L)
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
    fun `mid-gesture submit throttles inside the window`() {
        assertFalse(plan(4f, active = true, now = 199L, last = 0L).submitNow)
    }

    @Test
    fun `mid-gesture submit fires exactly at the throttle boundary`() {
        assertTrue(plan(4f, active = true, now = 200L, last = 0L).submitNow)
    }

    @Test
    fun `gesture end always submits regardless of elapsed time`() {
        val p = plan(4f, active = false, now = 1L, last = 0L)
        assertTrue(p.submitNow)
        assertEquals(4f, p.halTarget, 0f)
    }

    // ---- controlsZoomRatio: the still-request truth (8e12013 exact-ratio invariant + AGG3-27) ----
    // A still snapshots the controller's controls, so EVERY plan — submitted or throttled, wide-
    // aimed or exact — must carry the EXACT requested ratio for the still truth. The wide aim is
    // preview-only; the throttle may swallow the repeating submit but never the framing truth.

    @Test
    fun `mid-gesture wide aim still carries the exact ratio for stills`() {
        val p = plan(4f, active = true, now = 300L, last = 0L)
        assertTrue(p.submitNow)
        assertEquals(4f / margin, p.halTarget, 1e-6f)
        assertEquals(4f, p.controlsZoomRatio, 0f)
    }

    @Test
    fun `throttled tick still carries the exact ratio for stills`() {
        // The AGG3-27 window: submit swallowed, but a shutter press here must frame this ratio.
        val p = plan(4f, active = true, now = 199L, last = 0L)
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
