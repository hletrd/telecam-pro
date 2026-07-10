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
}
