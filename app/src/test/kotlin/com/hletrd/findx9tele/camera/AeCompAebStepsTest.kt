package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the AUTO-exposure AEB bracket's clamp + dedupe (the MANUAL branch's tested sibling). */
class AeCompAebStepsTest {

    @Test
    fun wideRange_fullBracket() {
        assertEquals(listOf(-2, 0, 2), aeCompAebSteps(-6, 6))
    }

    @Test
    fun narrowRange_clampsAndDedupes() {
        // [-1, 1]: -2 clamps to -1, +2 clamps to +1 → three DISTINCT shots, no duplicates.
        assertEquals(listOf(-1, 0, 1), aeCompAebSteps(-1, 1))
    }

    @Test
    fun zeroRange_collapsesToOneShot() {
        assertEquals(listOf(0), aeCompAebSteps(0, 0))
    }

    @Test
    fun oneSidedRange_dropsTheUnreachableSide() {
        // [0, 6]: -2 clamps to 0, duplicating the middle step → two shots.
        assertEquals(listOf(0, 2), aeCompAebSteps(0, 6))
    }
}
