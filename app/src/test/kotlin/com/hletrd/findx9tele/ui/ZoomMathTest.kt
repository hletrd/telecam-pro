package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE
import com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    @Test fun `tele bounds use the same 60x ceiling as application`() {
        val bounds = effectiveZoomBounds(1f, 10f, teleconverter = true)!!
        assertEquals(1f, bounds.lower, 0f)
        assertEquals(TELE_MAX_DISPLAY_ZOOM / TELE_DISPLAY_BASE, bounds.upper, 0.0001f)
    }

    @Test fun `entering 30x band snaps once`() {
        val bounds = effectiveZoomBounds(1f, 10f, teleconverter = true)
        val normalized = normalizeZoomRequest(
            requested = 29f / TELE_DISPLAY_BASE,
            currentApplied = 25f / TELE_DISPLAY_BASE,
            bounds = bounds,
            teleconverter = true,
        )
        assertEquals(30f, normalized * TELE_DISPLAY_BASE, 0.001f)
    }

    @Test fun `small increments can escape an applied 30x snap`() {
        val bounds = effectiveZoomBounds(1f, 10f, teleconverter = true)
        var current = 30f / TELE_DISPLAY_BASE
        repeat(3) {
            current = normalizeZoomRequest(current * 1.04f, current, bounds, teleconverter = true)
        }
        assertTrue(current * TELE_DISPLAY_BASE > 33f)
    }

    @Test fun `tele request saturates at 60x`() {
        val bounds = effectiveZoomBounds(1f, 10f, teleconverter = true)
        val normalized = normalizeZoomRequest(10f, 4f, bounds, teleconverter = true)
        assertEquals(60f, normalized * TELE_DISPLAY_BASE, 0.001f)
    }

    @Test fun `photo restore keeps unified framing and derives lens band`() {
        val restored = restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, false, 10.5f)
        assertEquals(LensChoice.TELE10X, restored.lens)
        assertEquals(10.5f, restored.zoomRatio, 0f)
    }

    @Test fun `video restore keeps selected lens and local zoom`() {
        val restored = restoredOptics(CaptureMode.VIDEO, LensChoice.TELE3X, false, 2.25f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertEquals(2.25f, restored.zoomRatio, 0f)
    }

    @Test fun `tele restore clamps local zoom to converter display ceiling`() {
        val restored = restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, true, 9f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertTrue(restored.teleconverter)
        assertEquals(60f, restored.zoomRatio * TELE_DISPLAY_BASE, 0.001f)
    }
}
