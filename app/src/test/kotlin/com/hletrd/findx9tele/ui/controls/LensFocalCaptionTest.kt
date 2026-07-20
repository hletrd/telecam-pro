package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.LensChoice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Lens page caption must be truthful about the converter: lens picks are zoom presets that do
 * NOT bundle TELE, so the 3× caption may only claim "300 mm equiv." while the separate toggle is
 * actually on (an operator relying on the caption could otherwise shoot a mounted converter
 * without its afocal correction).
 */
class LensFocalCaptionTest {

    @Test
    fun `tele3x caption follows the converter state`() {
        assertEquals("70 mm", lensFocalCaption(LensChoice.TELE3X, teleconverter = false))
        assertEquals("300 mm equiv.", lensFocalCaption(LensChoice.TELE3X, teleconverter = true))
    }

    @Test
    fun `other lenses never claim the converter`() {
        for (tc in booleanArrayOf(false, true)) {
            assertEquals("14 mm", lensFocalCaption(LensChoice.ULTRAWIDE, teleconverter = tc))
            assertEquals("23 mm", lensFocalCaption(LensChoice.MAIN, teleconverter = tc))
            assertEquals("230 mm", lensFocalCaption(LensChoice.TELE10X, teleconverter = tc))
        }
    }
}
