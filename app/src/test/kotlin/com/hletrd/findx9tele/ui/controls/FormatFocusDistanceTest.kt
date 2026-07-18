package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [formatFocusDistance] (TEST4-12): manual focus is the app's essential control and this
 * readout covers the exact zone (near-zero diopters ≈ ∞, the cm↔m crossover) where a rounding
 * slip would misread on the Focus chip.
 */
class FormatFocusDistanceTest {

    @Test
    fun `zero and negative diopters read infinity`() {
        assertEquals("∞", formatFocusDistance(0f))
        assertEquals("∞", formatFocusDistance(-0.5f))
    }

    @Test
    fun `sub-meter distances read centimeters`() {
        // 4 diopters = 0.25 m.
        assertEquals("25cm", formatFocusDistance(4f))
        // 2 diopters = 0.5 m.
        assertEquals("50cm", formatFocusDistance(2f))
    }

    @Test
    fun `metric distances read meters with two decimals`() {
        // 1 diopter = exactly 1.00 m — the crossover lands on the meter side.
        assertEquals("1.00m", formatFocusDistance(1f))
        // 0.4 diopters = 2.5 m.
        assertEquals("2.50m", formatFocusDistance(0.4f))
    }
}
