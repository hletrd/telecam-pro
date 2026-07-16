package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the AUTO-exposure AEB bracket's clamp + dedupe (the MANUAL branch's tested sibling). */
class AeCompAebStepsTest {

    @Test
    fun halfStopUnits_fullBracket() {
        assertEquals(listOf(-4, 0, 4), aeCompAebSteps(center = 0, lower = -6, upper = 6, evStepStops = 0.5f))
    }

    @Test
    fun thirdStopUnits_fullBracket() {
        assertEquals(
            listOf(-6, 0, 6),
            aeCompAebSteps(center = 0, lower = -9, upper = 9, evStepStops = 1f / 3f),
        )
    }

    @Test
    fun sixthStopUnits_fullBracket() {
        assertEquals(
            listOf(-12, 0, 12),
            aeCompAebSteps(center = 0, lower = -18, upper = 18, evStepStops = 1f / 6f),
        )
    }

    @Test
    fun bracketIsCenteredOnCurrentCompensation() {
        assertEquals(
            listOf(-4, 2, 8),
            aeCompAebSteps(center = 2, lower = -9, upper = 9, evStepStops = 1f / 3f),
        )
    }

    @Test
    fun narrowRange_clampsAndDedupes() {
        assertEquals(
            listOf(-1, 0, 1),
            aeCompAebSteps(center = 0, lower = -1, upper = 1, evStepStops = 1f / 3f),
        )
    }

    @Test
    fun oneSidedRange_dropsUnreachableSide() {
        assertEquals(
            listOf(0, 6),
            aeCompAebSteps(center = 0, lower = 0, upper = 6, evStepStops = 1f / 3f),
        )
    }
}
