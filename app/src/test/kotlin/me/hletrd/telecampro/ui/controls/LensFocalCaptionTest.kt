package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.effectiveFocalMm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Lens page caption must be truthful about the converter: lens picks are zoom presets that do
 * NOT bundle TELE, so the 3× caption may only claim "300 mm equiv." while the separate toggle is
 * actually on (an operator relying on the caption could otherwise shoot a mounted converter
 * without its afocal correction).
 */
class LensFocalCaptionTest {
    // The kit optic, spelled out: the caption now takes the SELECTED converter's focal, so the
    // historical "300 mm equiv." string is asserted against the historical magnification.
    private val kitFocal = effectiveFocalMm(TELECONVERTER_MAGNIFICATION)


    @Test
    fun `tele3x caption follows the converter state`() {
        assertEquals("70 mm", lensFocalCaption(LensChoice.TELE3X, teleconverter = false, teleconverterFocalMm = kitFocal))
        assertEquals("300 mm equiv.", lensFocalCaption(LensChoice.TELE3X, teleconverter = true, teleconverterFocalMm = kitFocal))
    }

    @Test
    fun `the caption quotes the SELECTED converter, not the kit one`() {
        // A generic 2x on the 70 mm host is 140 mm — claiming the kit's 300 would misdescribe the
        // optic the operator actually mounted.
        assertEquals(
            "140 mm equiv.",
            lensFocalCaption(
                LensChoice.TELE3X,
                teleconverter = true,
                teleconverterFocalMm = effectiveFocalMm(2f),
            ),
        )
    }

    @Test
    fun `other lenses never claim the converter`() {
        for (tc in booleanArrayOf(false, true)) {
            assertEquals("14 mm", lensFocalCaption(LensChoice.ULTRAWIDE, teleconverter = tc, teleconverterFocalMm = kitFocal))
            assertEquals("23 mm", lensFocalCaption(LensChoice.MAIN, teleconverter = tc, teleconverterFocalMm = kitFocal))
            assertEquals("230 mm", lensFocalCaption(LensChoice.TELE10X, teleconverter = tc, teleconverterFocalMm = kitFocal))
        }
    }
}
