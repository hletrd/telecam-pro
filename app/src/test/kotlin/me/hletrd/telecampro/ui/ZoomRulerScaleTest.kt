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
    private fun multiplier(equivMm: Float?, teleconverter: Boolean = false, route: CameraRoute = CameraRoute.BACK) =
        zoomDisplayMultiplier(
            teleconverter = teleconverter,
            teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
            equivalentFocalMm = equivMm,
            frontFacing = route == CameraRoute.FRONT,
            activeRoute = route,
        )

    @Test fun `standalone 70 mm lens reads 3x like the chip, not 1x`() {
        val base = multiplier(69f)
        val scale = zoomRulerScale(1f, 10f, base, teleconverter = false)
        val chip = formatDisplayZoom(1f, false, TELECONVERTER_MAGNIFICATION, 69f, false, CameraRoute.BACK)
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
        val logical = zoomRulerScale(0.6f, 20f, multiplier(23f), teleconverter = false)
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
}
