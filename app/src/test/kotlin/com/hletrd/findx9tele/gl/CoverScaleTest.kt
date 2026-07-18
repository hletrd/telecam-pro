package com.hletrd.findx9tele.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [coverScale] — the pure center-crop "cover" aspect math extracted from
 * [FlipRenderer.draw]. No GL. Pins matching-aspect identity, which axis is scaled, the 90/270 swap
 * boundary, and divide-by-zero safety.
 */
class CoverScaleTest {

    private val eps = 1e-4f

    @Test
    fun `matching aspect scales neither axis`() {
        val (ex, ey) = coverScale(4, 3, 0, 0, 4, 3)
        assertEquals(1f, ex, eps)
        assertEquals(1f, ey, eps)
        val (ex2, ey2) = coverScale(16, 9, 0, 0, 16, 9)
        assertEquals(1f, ex2, eps)
        assertEquals(1f, ey2, eps)
    }

    @Test
    fun `content wider than the target overscans horizontally`() {
        // 16:9 content into a 4:3 view -> overscan width (ex > 1), height untouched.
        val (ex, ey) = coverScale(16, 9, 0, 0, 4, 3)
        assertTrue("ex should exceed 1, was $ex", ex > 1f)
        assertEquals(1f, ey, eps)
    }

    @Test
    fun `content narrower than the target overscans vertically`() {
        // 3:4 content into a 16:9 view -> overscan height (ey > 1), width untouched.
        val (ex, ey) = coverScale(3, 4, 0, 0, 16, 9)
        assertEquals(1f, ex, eps)
        assertTrue("ey should exceed 1, was $ey", ey > 1f)
    }

    @Test
    fun `width-height swap triggers only when sensor plus rotation is a net 90 or 270`() {
        // 4:3 content into a square target: no swap overscans width, a swap overscans height.
        // Not swapped: net rotation 0/89/91/180 (each % 180 != 90).
        for ((s, r) in listOf(0 to 0, 89 to 0, 91 to 0, 180 to 0)) {
            val (ex, ey) = coverScale(4, 3, s, r, 1, 1)
            assertTrue("no swap at $s+$r: ex>1", ex > 1f)
            assertEquals("no swap at $s+$r: ey==1", 1f, ey, eps)
        }
        // Swapped: net rotation exactly 90 or 270 (from either the sensor or the extra rotation).
        for ((s, r) in listOf(90 to 0, 0 to 90, 270 to 0)) {
            val (ex, ey) = coverScale(4, 3, s, r, 1, 1)
            assertEquals("swap at $s+$r: ex==1", 1f, ex, eps)
            assertTrue("swap at $s+$r: ey>1", ey > 1f)
        }
    }

    @Test
    fun `degenerate target height does not divide by zero`() {
        val (ex, ey) = coverScale(4, 3, 0, 0, 4, 0)
        assertTrue("ex finite", ex.isFinite())
        assertTrue("ey finite", ey.isFinite())
    }

    // ---- Framing contract (ARCH4-1): preview and encoder must net the SAME field ----

    @Test
    fun `preview and encoder targets both net identity for the 90deg sensor (framing contract)`() {
        // Camera stream 3840x2160 landscape; sensorOrientation 90 rotates displayed content to
        // portrait. The preview surface is portrait (1440x2560 for 16:9); the encoder buffer is
        // RotationMath.encoderSurfaceSize's swapped 2160x3840. Both targets must cover with (1,1)
        // so the recorded file shows exactly the viewfinder field.
        val (pex, pey) = coverScale(3840, 2160, 90, 0, 1440, 2560)
        assertEquals(1f, pex, eps)
        assertEquals(1f, pey, eps)
        val (eex, eey) = coverScale(3840, 2160, 90, 0, 2160, 3840)
        assertEquals(1f, eex, eps)
        assertEquals(1f, eey, eps)
        // TELE adds the afocal 180 (net 270 -> still swapped): same identity must hold.
        val (tex, tey) = coverScale(3840, 2160, 90, 180, 2160, 3840)
        assertEquals(1f, tex, eps)
        assertEquals(1f, tey, eps)
    }

    @Test
    fun `stream-shaped landscape encoder target overscans 3-16x (the recorded-field bug this pins)`() {
        // The pre-fix arrangement: portrait-displayed content drawn into the LANDSCAPE stream-shaped
        // buffer. coverScale overscans ey = (3840/2160)/(2160/3840) = 3.1605 — the recorded file
        // carried only a center band of the preview field (device-measured 2026-07-18, gradient
        // ratio ~3.4). This test documents the failure mode so a regression is named, not silent.
        val (ex, ey) = coverScale(3840, 2160, 90, 0, 3840, 2160)
        assertEquals(1f, ex, eps)
        assertEquals(3840f / 2160f / (2160f / 3840f), ey, 1e-3f)
    }
}
