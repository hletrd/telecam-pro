package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.effectiveFocalMm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Lens page caption must be truthful about the converter: lens picks are zoom presets that do
 * NOT bundle TELE, so the 3× caption may only claim "300 mm" while the separate toggle is
 * actually on (an operator relying on the caption could otherwise shoot a mounted converter
 * without its afocal correction).
 */
class LensFocalCaptionTest {
    // The kit optic, spelled out: the caption now takes the SELECTED converter's focal, so the
    // historical "300 mm" string is asserted against the historical magnification. The " equiv."
    // suffix was dropped 2026-07-31 (UI review #36): every rail sibling is equally a 35 mm
    // equivalent figure, so the lone suffix implied the others were physical focals.
    private val kitFocal = effectiveFocalMm(TELECONVERTER_MAGNIFICATION)


    @Test
    fun `tele3x caption follows the converter state`() {
        assertEquals("70 mm", lensFocalCaption(LensChoice.TELE3X, teleconverter = false, teleconverterFocalMm = kitFocal))
        assertEquals("300 mm", lensFocalCaption(LensChoice.TELE3X, teleconverter = true, teleconverterFocalMm = kitFocal))
    }

    @Test
    fun `the caption quotes the SELECTED converter, not the kit one`() {
        // A generic 2x on the 70 mm host is 140 mm — claiming the kit's 300 would misdescribe the
        // optic the operator actually mounted.
        assertEquals(
            "140 mm",
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

/**
 * The caption's four literals are PMA110's optics; on other hardware they were simply false
 * (a 26 mm-lens tablet read "23 mm", device-seen 2026-08-02). Measured wins only when it actually
 * disagrees, so PMA110's documented round-number readout survives.
 */
class LensFocalCaptionMeasuredTest {
    @Test
    fun `PMA110 keeps its round labels — measured is within the band`() {
        // Its 3x measures ~69.4 mm and must keep reading 70 mm.
        assertEquals(
            "70 mm",
            lensFocalCaption(LensChoice.TELE3X, teleconverter = false, teleconverterFocalMm = 300f, measuredEquivMm = 69.4f),
        )
        assertEquals(
            "23 mm",
            lensFocalCaption(LensChoice.MAIN, teleconverter = false, teleconverterFocalMm = 300f, measuredEquivMm = 23.2f),
        )
    }

    @Test
    fun `foreign hardware reports what it actually has`() {
        assertEquals(
            "26 mm",
            lensFocalCaption(LensChoice.MAIN, teleconverter = false, teleconverterFocalMm = 0f, measuredEquivMm = 26f),
        )
    }

    @Test
    fun `an unreadable measurement falls back to the preset label`() {
        assertEquals(
            "23 mm",
            lensFocalCaption(LensChoice.MAIN, teleconverter = false, teleconverterFocalMm = 0f, measuredEquivMm = 0f),
        )
    }

    @Test
    fun `the converter focal still wins on the 3x row`() {
        assertEquals(
            "300 mm",
            lensFocalCaption(LensChoice.TELE3X, teleconverter = true, teleconverterFocalMm = 300f, measuredEquivMm = 26f),
        )
    }
}
