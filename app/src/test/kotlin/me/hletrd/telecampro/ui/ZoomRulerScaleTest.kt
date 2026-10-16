package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.TELE_MAX_DISPLAY_ZOOM
import me.hletrd.telecampro.camera.teleDisplayBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGG4-73: the Quick Zoom ruler reads on the SAME main-relative scale as the ZOOM chip and HUD pill
 * ([zoomDisplayMultiplier]) and writes `display / base` back lens-local.
 */
class ZoomRulerScaleTest {
    private fun multiplier(
        equivMm: Float?,
        teleconverter: Boolean = false,
        route: CameraRoute = CameraRoute.BACK,
        standalone: Boolean = true,
        measuredMain: Float? = 23f,
    ) = zoomDisplayMultiplier(
        teleconverter = teleconverter,
        teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
        equivalentFocalMm = equivMm,
        frontFacing = route == CameraRoute.FRONT,
        activeRoute = route,
        standaloneRoute = standalone,
        measuredMainEquivMm = measuredMain,
    )

    @Test fun `standalone 70 mm lens reads 3x like the chip, not 1x`() {
        val base = multiplier(69f)
        val scale = zoomRulerScale(1f, 10f, base, teleconverter = false)
        val chip = formatDisplayZoom(1f, base)
        assertEquals(chip, formatZoomMultiplier(scale.display(1f)))
        assertEquals("3.0×", chip)
        assertEquals(3f, scale.lo, 1e-4f)
        assertEquals(30f, scale.hi, 1e-4f)
        // A drag lands on the lens-local scale the engine owns.
        assertEquals(1f, scale.localFor(0f), 1e-4f)
        assertEquals(10f, scale.localFor(1f), 1e-4f)
        assertEquals(0.5f, scale.fraction(5.5f), 1e-4f)
    }

    @Test fun `ultrawide standalone lens reads below 1x like the chip`() {
        val scale = zoomRulerScale(1f, 5f, multiplier(13.8f), teleconverter = false)
        assertEquals("0.6×", formatZoomMultiplier(scale.display(1f)))
    }

    @Test fun `logical route and FRONT stay 1 to 1`() {
        // AGG5-49: 23.4 mm is PMA110's MEASURED logical equivalent; the old 23 mm fixture could
        // not see the "3.1×" the nominal divisor produced.
        val logical = zoomRulerScale(0.6f, 20f, multiplier(23.4f, standalone = false, measuredMain = 23.4f), teleconverter = false)
        assertEquals(1f, logical.base, 0f)
        assertEquals("3.0×", formatZoomMultiplier(logical.display(3f)))
        assertEquals(0.6f, logical.lo, 1e-4f)
        assertEquals(20f, logical.hi, 1e-4f)
        assertEquals(4f, logical.localFor(logical.fraction(4f)), 1e-4f)
        val front = zoomRulerScale(1f, 4f, multiplier(24f, route = CameraRoute.FRONT), teleconverter = false)
        assertEquals(1f, front.base, 0f)
    }

    @Test fun `converter scale keeps its total-magnification cap`() {
        val base = multiplier(69f, teleconverter = true)
        assertEquals(teleDisplayBase(TELECONVERTER_MAGNIFICATION), base, 1e-4f)
        val scale = zoomRulerScale(1f, 10f, base, teleconverter = true)
        assertEquals(TELE_MAX_DISPLAY_ZOOM, scale.hi, 1e-4f)
        assertEquals(TELE_MAX_DISPLAY_ZOOM / base, scale.localFor(1f), 1e-4f)
        assertTrue(scale.enabled)
    }

    @Test fun `degenerate inputs never divide by zero`() {
        val scale = zoomRulerScale(1f, 1f, Float.NaN, teleconverter = false)
        assertEquals(1f, scale.base, 0f)
        assertFalse(scale.enabled)
        assertEquals(0f, scale.fraction(1f), 0f)
        assertEquals(1f, zoomRulerScale(1f, 2f, 0f, teleconverter = false).base, 0f)
    }

    // AGG5-12: the Shoot-tab Zoom slider is a value-domain slider on this same scale; its write
    // lands lens-local and clamps into the advertised range like the ruler's fraction write.
    @Test fun `value-domain writes land lens-local and clamp to the range`() {
        val scale = zoomRulerScale(1f, 10f, multiplier(69f), teleconverter = false)
        assertEquals(1f, scale.localForDisplay(3f), 1e-4f)
        assertEquals(2f, scale.localForDisplay(6f), 1e-4f)
        assertEquals(1f, scale.localForDisplay(0.5f), 1e-4f)
        assertEquals(10f, scale.localForDisplay(99f), 1e-4f)
        assertEquals(scale.localFor(0.25f), scale.localForDisplay(scale.lo + 0.25f * (scale.hi - scale.lo)), 1e-5f)
    }
}
