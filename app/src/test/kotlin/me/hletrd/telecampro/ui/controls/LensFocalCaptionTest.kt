package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.effectiveFocalMm
import me.hletrd.telecampro.camera.lensInventoryOf
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


/**
 * The exact combination the CALL SITE produces, which the first round of tests missed: on PMA110's
 * seamless photo route the caption is asked about a preset while the ACTIVE route measures ~23 mm.
 * Feeding the route's value made 3x read "23 mm"; feeding the PRESET's own value keeps "70 mm".
 */
class LensFocalCaptionRouteRegressionTest {
    private val pma110 = me.hletrd.telecampro.camera.lensInventoryOf(
        listOf(14f, 23f, 69.4f, 230f),
        0.6f to 20f,
        homeEquivMm = null,
    )

    @Test
    fun `every PMA110 preset keeps its own label on the seamless route`() {
        val expected = mapOf(
            LensChoice.ULTRAWIDE to "14 mm",
            LensChoice.MAIN to "23 mm",
            LensChoice.TELE3X to "70 mm",
            LensChoice.TELE10X to "230 mm",
        )
        for ((lens, label) in expected) {
            assertEquals(
                label,
                lensFocalCaption(
                    lens,
                    teleconverter = false,
                    teleconverterFocalMm = 300f,
                    measuredEquivMm = pma110.presetEquivMm[lens] ?: 0f,
                ),
            )
        }
    }

    @Test
    fun `the route's own equivalent would have broken it — pinned as the anti-case`() {
        // 23 mm (the logical camera) against the 3x preset: this is what the regression fed in.
        assertEquals(
            "23 mm",
            lensFocalCaption(LensChoice.TELE3X, teleconverter = false, teleconverterFocalMm = 300f, measuredEquivMm = 23f),
        )
    }
}

/**
 * The caption reads what a chip FRAMES on the declared phone's scale (`presetFramingEquivMm`): an
 * optical preset is its measured lens, a digital one the main × the chip's own position.
 */
class LensFocalCaptionMakerScaleTest {
    @Test
    fun `PMA110 and an undeclared tablet read exactly the inventory's figures`() {
        val pma110 = lensInventoryOf(listOf(14f, 23.4f, 69.4f, 230f), 0.6f to 20f, homeEquivMm = 23.4f)
        val state = CameraUiState(lensInventory = pma110)
        LensChoice.entries.forEach { lens ->
            assertEquals(
                pma110.presetEquivMm.getValue(lens).toRawBits(),
                state.presetFramingEquivMm(lens).toRawBits(),
            )
        }
        val tablet = lensInventoryOf(listOf(26f), 1f to 8f, homeEquivMm = 26f)
        val other = CameraUiState(phoneModel = PhoneModel.OTHER, lensInventory = tablet)
        assertEquals(
            tablet.presetEquivMm.getValue(LensChoice.TELE3X).toRawBits(),
            other.presetFramingEquivMm(LensChoice.TELE3X).toRawBits(),
        )
        assertEquals("78 mm", lensFocalCaption(LensChoice.TELE3X, false, 300f, other.presetFramingEquivMm(LensChoice.TELE3X)))
    }

    @Test
    fun `a vivo Ultra's digital 10x reads vivo's 230 mm, not 10 x its 35 mm main`() {
        val ultra = lensInventoryOf(listOf(14f, 35f, 85f), 0.4f to 30f, homeEquivMm = 35f)
        val state = CameraUiState(phoneModel = PhoneModel.VIVO_X300_ULTRA, lensInventory = ultra)
        assertEquals(350f, ultra.presetEquivMm.getValue(LensChoice.TELE10X), 1e-3f)
        assertEquals(230f, state.presetFramingEquivMm(LensChoice.TELE10X), 1e-3f)
        assertEquals(
            "230 mm",
            lensFocalCaption(LensChoice.TELE10X, false, 300f, state.presetFramingEquivMm(LensChoice.TELE10X)),
        )
        // Optical presets keep their measured lens.
        assertEquals(35f, state.presetFramingEquivMm(LensChoice.MAIN), 0f)
        assertEquals(85f, state.presetFramingEquivMm(LensChoice.TELE3X), 0f)
    }
}
