package me.hletrd.telecampro.camera

import me.hletrd.telecampro.ui.controls.formatZoomMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lens rail follows the DECLARED phone's maker (owner decision 2026-10-25): each preset keeps its
 * identity, but its label and the zoom it applies are the maker's chip. These pin the pure law — the
 * PMA110 identity first, because everything else is new behaviour on unvalidated hardware.
 */
class MakerZoomPresetsTest {
    @Test
    fun `PMA110 resolves to the historical presets before and after the inventory`() {
        listOf<Float?>(null, 0f, 23.4f, 23f).forEach { home ->
            val presets = zoomPresetsFor(PhoneModel.FIND_X9_ULTRA, home)
            assertEquals("home $home", ZoomPresets.NOMINAL, presets)
            LensChoice.entries.forEach { choice ->
                assertEquals(choice.zoomPreset.toRawBits(), presets.position(choice).toRawBits())
                assertEquals(choice.zoomPreset.toRawBits(), presets.label(choice).toRawBits())
            }
        }
        // The bands are the historical thresholds: <1 UW, <3 MAIN, <10 TELE3X, else TELE10X.
        val nominal = ZoomPresets.NOMINAL
        assertEquals(LensChoice.ULTRAWIDE, LensChoice.forZoom(0.99f, nominal))
        assertEquals(LensChoice.MAIN, LensChoice.forZoom(1f, nominal))
        assertEquals(LensChoice.MAIN, LensChoice.forZoom(2.99f, nominal))
        assertEquals(LensChoice.TELE3X, LensChoice.forZoom(3f, nominal))
        assertEquals(LensChoice.TELE3X, LensChoice.forZoom(9.99f, nominal))
        assertEquals(LensChoice.TELE10X, LensChoice.forZoom(10f, nominal))
        // The rail offers every enumerated preset on PMA110.
        val pma110 = lensInventoryOf(listOf(14f, 23.4f, 69.4f, 230f), 0.6f to 20f, homeEquivMm = 23.4f)
        assertEquals(LensChoice.entries, offeredLensChoices(pma110, zoomPresetsFor(PhoneModel.FIND_X9_ULTRA, 23.4f)))
    }

    @Test
    fun `an undeclared phone keeps the generic presets whatever it measures`() {
        listOf<Float?>(null, 26f, 35f).forEach { home ->
            assertEquals(ZoomPresets.NOMINAL, zoomPresetsFor(PhoneModel.OTHER, home))
        }
    }

    @Test
    fun `a chip applies its own label on the maker's display scale`() {
        // position × the logical route's display factor = the label the chip prints, so tapping it
        // puts the pill on the same number. MAIN is the one rounding exception: it lands on the
        // logical camera's own 1.0, whose display is the maker's 1× or vivo's 35 ÷ 23 "1.5×".
        PhoneModel.entries.filter { it.makerZoom != null }.forEach { phone ->
            val maker = phone.makerZoom!!
            listOf<Float?>(null, maker.mainEquivMm).forEach { home ->
                val presets = zoomPresetsFor(phone, home)
                val factor = logicalZoomDisplayFactor(phone, home)
                LensChoice.entries.filter { it != LensChoice.MAIN }.forEach { choice ->
                    val applied = presets.position(choice) * factor
                    assertEquals("${phone.name} $choice", presets.label(choice), applied, 1e-5f)
                }
                assertEquals(phone.name, 1f, presets.position(LensChoice.MAIN), 0f)
                assertEquals(phone.name, formatZoomMark(presets.label(LensChoice.MAIN)), formatZoomMark(factor))
                // The positions ascend, so the bands are well-formed.
                assertTrue(phone.name, presets.ultraWide < presets.main)
                assertTrue(phone.name, presets.main < presets.tele)
                assertTrue(phone.name, presets.tele < presets.tenX)
            }
        }
    }

    @Test
    fun `Find X10 puts its tele chip at 2_8x and offers no 10x`() {
        val presets = zoomPresetsFor(PhoneModel.FIND_X10, 23f)
        assertEquals(2.8f, presets.position(LensChoice.TELE3X), 0f)
        assertEquals("2.8×", formatZoomMark(presets.label(LensChoice.TELE3X)))
        // 2.8 is the tele band: PMA110's threshold of 3 would have put it on the main lens.
        assertEquals(LensChoice.TELE3X, LensChoice.forZoom(2.8f, presets))
        assertEquals(LensChoice.MAIN, LensChoice.forZoom(2.79f, presets))
        // OPPO's official row is 0.6 · 1 · 2 · 2.8 · 5.6: the enumeration may reach 10x by zoom, but
        // no 10x chip is drawn.
        val x10 = lensInventoryOf(listOf(15f, 23f, 65f), 0.6f to 120f, homeEquivMm = 23f)
        assertTrue(LensChoice.TELE10X in x10.available)
        assertFalse(presets.offers(LensChoice.TELE10X))
        assertEquals(
            listOf(LensChoice.ULTRAWIDE, LensChoice.MAIN, LensChoice.TELE3X),
            offeredLensChoices(x10, presets),
        )
        // A Photo→Video trip at the 2.8x chip lands on the tele lens at its own 1x, not on a 2.8x
        // crop of the main; the reverse trip comes back to exactly 2.8.
        val optical = x10.optical
        assertEquals(LensChoice.TELE3X, opticalBaseFor(2.8f, optical, presets))
        assertEquals(1f, localZoomOf(2.8f, optical, presets), 0f)
        assertEquals(
            2.8f,
            unifiedZoomOf(LensChoice.TELE3X, 1f, standaloneRoute = true, optical = optical, presets = presets),
            0f,
        )
    }

    @Test
    fun `Find X10 Pro Max reads a 0_5x ultrawide over its 24 mm 1x`() {
        val presets = zoomPresetsFor(PhoneModel.FIND_X10_PRO_MAX, 24f)
        assertEquals(0.5f, presets.position(LensChoice.ULTRAWIDE), 0f)
        assertEquals("0.5×", formatZoomMark(presets.label(LensChoice.ULTRAWIDE)))
        assertEquals("3×", formatZoomMark(presets.label(LensChoice.TELE3X)))
        assertFalse(presets.offers(LensChoice.TELE10X))
        assertEquals(24f, oneXReferenceMm(PhoneModel.FIND_X10_PRO_MAX, 24.2f), 0f)
        // 0.5 sits below PMA110's 0.6 floor, and is still the ultrawide band.
        assertEquals(LensChoice.ULTRAWIDE, LensChoice.forZoom(0.5f, presets))
    }

    @Test
    fun `X300 Pro reads a 3_5x tele and keeps the 10x vivo shows`() {
        val presets = zoomPresetsFor(PhoneModel.VIVO_X300_PRO, 24f)
        assertEquals("3.5×", formatZoomMark(presets.label(LensChoice.TELE3X)))
        assertEquals(3.5f, presets.position(LensChoice.TELE3X), 0f)
        assertTrue(presets.offers(LensChoice.TELE10X))
        assertEquals("10×", formatZoomMark(presets.label(LensChoice.TELE10X)))
    }

    @Test
    fun `X300 Ultra's MAIN chip opens the 35 mm main and reads 1_5x`() {
        // vivo numbers zoom with 23 mm as 1x, so the main is 1.5x and the 85 mm tele 3.7x. The
        // logical camera's own 1.0 is the 35 mm main (factor 35 / 23).
        val presets = zoomPresetsFor(PhoneModel.VIVO_X300_ULTRA, 35f)
        val factor = 35f / 23f
        assertEquals("1.5×", formatZoomMark(presets.label(LensChoice.MAIN)))
        assertEquals(1f, presets.position(LensChoice.MAIN), 0f)
        assertEquals("1.5×", formatZoomMark(presets.position(LensChoice.MAIN) * factor))
        assertEquals(3.7f / factor, presets.position(LensChoice.TELE3X), 1e-6f)
        assertEquals("3.7×", formatZoomMark(presets.position(LensChoice.TELE3X) * factor))
        assertEquals(0.6f / factor, presets.position(LensChoice.ULTRAWIDE), 1e-6f)
        // On a standalone route the MAIN chip opens the main lens at its own 1x, the tele chip the
        // tele at its own 1x — the positions of the SAME lens divide to 1.
        val optical = setOf(LensChoice.ULTRAWIDE, LensChoice.MAIN, LensChoice.TELE3X)
        assertEquals(1f, localZoomOf(presets.position(LensChoice.MAIN), optical, presets), 0f)
        assertEquals(1f, localZoomOf(presets.position(LensChoice.TELE3X), optical, presets), 0f)
        assertEquals(LensChoice.TELE3X, opticalBaseFor(presets.position(LensChoice.TELE3X), optical, presets))
        // Before the enumeration publishes, the declared 35 mm main gives the same positions.
        assertEquals(zoomPresetsFor(PhoneModel.VIVO_X300_ULTRA, null), zoomPresetsFor(PhoneModel.VIVO_X300_ULTRA, 35f))
    }

    @Test
    fun `the TELE-off return and the FRONT return land on the maker's tele position`() {
        val presets = zoomPresetsFor(PhoneModel.FIND_X10, 23f)
        val optical = setOf(LensChoice.ULTRAWIDE, LensChoice.MAIN, LensChoice.TELE3X)
        // TC off with no pre-TELE framing to restore: the 3x host preset is the maker's 2.8.
        val off = resolveTeleZoomTransition(
            nonTeleStandaloneRoute = false,
            opticalPresets = optical,
            presets = presets,
            currentLens = LensChoice.TELE3X,
            currentTeleconverter = true,
            currentZoomRatio = 1f,
            currentPreTeleUnifiedZoom = Float.NaN,
            requestedLens = LensChoice.TELE3X,
            requestedTeleconverter = false,
            restorePreTele = false,
        )
        assertEquals(2.8f, off.zoomRatio, 0f)
        assertEquals(LensChoice.TELE3X, off.lens)
        // A FRONT return with no snapshot falls back to the retained lens's maker position.
        assertEquals(
            2.8f,
            rearReturnZoom(false, Float.NaN, presets.position(LensChoice.TELE3X), optical, presets),
            0f,
        )
        assertEquals(LensChoice.TELE3X, rearReturnLens(false, 2.8f, LensChoice.MAIN, presets))
    }
}
