package me.hletrd.telecampro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LandscapeLanePolicyTest {
    @Test fun `an upright or unmeasured lane is free`() {
        assertNull(landscapeLaneMaxPx(held = false, topPx = 200, limitPx = 900, gapPx = 24))
        assertNull(landscapeLaneMaxPx(held = true, topPx = 0, limitPx = 900, gapPx = 24))
        assertNull(landscapeLaneMaxPx(held = true, topPx = 200, limitPx = Int.MAX_VALUE, gapPx = 24))
    }

    @Test fun `a held lane stops one gap above its limit but never under six gaps`() {
        assertEquals(676, landscapeLaneMaxPx(held = true, topPx = 200, limitPx = 900, gapPx = 24))
        assertEquals(144, landscapeLaneMaxPx(held = true, topPx = 200, limitPx = 250, gapPx = 24))
    }

    @Test fun `a stack wraps only when the next item would pass the limit`() {
        assertEquals(listOf(listOf(0, 1, 2)), endWrapColumns(listOf(100, 100, 100), limitPx = 320, gapPx = 10))
        assertEquals(listOf(listOf(0, 1), listOf(2)), endWrapColumns(listOf(100, 100, 100), limitPx = 300, gapPx = 10))
        // An item taller than the limit still gets a column of its own, in order.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), endWrapColumns(listOf(100, 500, 100), limitPx = 300, gapPx = 10))
        assertEquals(emptyList<List<Int>>(), endWrapColumns(emptyList(), limitPx = 300, gapPx = 10))
    }
}
