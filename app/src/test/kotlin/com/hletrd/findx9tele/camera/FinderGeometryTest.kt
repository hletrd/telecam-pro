package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single geometry rule the TELE finder PIP shares between the GL scissor/viewport
 * (pixels) and the Compose border overlay (dp): fraction of the FULL box, inset by the margin of
 * the short edge from the bottom-left. The regression this protects: the original Compose chain
 * applied `padding` BEFORE `fillMaxWidth`, sizing the border from padding-reduced constraints —
 * ~6% smaller than the GL content box.
 */
class FinderGeometryTest {

    @Test
    fun `box is the fraction of the full box, inset by the short-edge margin`() {
        val r = finderRect(boxWidth = 1000f, boxHeight = 750f, fraction = 0.30f, margin = 0.03f)
        assertEquals(300f, r.width, 1e-4f)
        assertEquals(225f, r.height, 1e-4f)
        // Short edge is 750 → inset 22.5 on both axes.
        assertEquals(22.5f, r.x, 1e-4f)
        assertEquals(22.5f, r.y, 1e-4f)
    }

    @Test
    fun `portrait boxes inset by the width`() {
        val r = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        assertEquals(1080f * FINDER_FRACTION, r.width, 1e-3f)
        assertEquals(1440f * FINDER_FRACTION, r.height, 1e-3f)
        assertEquals(1080f * FINDER_MARGIN, r.x, 1e-3f)
        assertEquals(1080f * FINDER_MARGIN, r.y, 1e-3f)
    }

    @Test
    fun `gl pixel box and compose dp box are the same physical rect`() {
        // The same physical preview box expressed in px (GL surface) and in dp (Compose
        // constraints) must produce density-scaled copies of one rect — the property that keeps
        // the white border exactly on the GL content box.
        val density = 2.625f
        val px = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        val dp = finderRect(boxWidth = 1080f / density, boxHeight = 1440f / density)
        assertEquals(px.x / density, dp.x, 1e-3f)
        assertEquals(px.y / density, dp.y, 1e-3f)
        assertEquals(px.width / density, dp.width, 1e-3f)
        assertEquals(px.height / density, dp.height, 1e-3f)
    }

    @Test
    fun `size does not depend on the margin`() {
        // The regression case: the border must be fraction-of-FULL-box, not
        // fraction-of-(box minus 2 margins).
        val noMargin = finderRect(boxWidth = 1000f, boxHeight = 1500f, margin = 0f)
        val withMargin = finderRect(boxWidth = 1000f, boxHeight = 1500f, margin = 0.03f)
        assertEquals(noMargin.width, withMargin.width, 1e-4f)
        assertEquals(noMargin.height, withMargin.height, 1e-4f)
    }

    @Test
    fun `box aspect matches the preview box aspect`() {
        val r = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        assertEquals(1080f / 1440f, r.width / r.height, 1e-4f)
    }

    @Test
    fun `top-left UI hit geometry consumes only the visible finder rect`() {
        val r = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        val top = 1440f - r.y - r.height

        assertTrue(finderContainsTopLeftPoint(r.x + 1f, top + 1f, 1080f, 1440f))
        assertTrue(finderContainsTopLeftPoint(r.x + r.width, top + r.height, 1080f, 1440f))
        assertFalse(finderContainsTopLeftPoint(r.x - 1f, top + 1f, 1080f, 1440f))
        assertFalse(finderContainsTopLeftPoint(r.x + 1f, top - 1f, 1080f, 1440f))
        assertFalse(finderContainsTopLeftPoint(0f, 0f, 0f, 1440f))
    }
}
