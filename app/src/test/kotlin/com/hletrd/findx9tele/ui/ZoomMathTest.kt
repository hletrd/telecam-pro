package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.reconcileZoomWithCaps
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
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

    @Test fun `video restore clamps legacy local zoom to 10x`() {
        val restored = restoredOptics(CaptureMode.VIDEO, LensChoice.TELE3X, false, 20f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertEquals(10f, restored.zoomRatio, 0f)
    }

    @Test fun `non-finite restore falls back to a valid mode representation`() {
        assertEquals(
            1f,
            restoredOptics(CaptureMode.VIDEO, LensChoice.TELE10X, false, Float.NaN).zoomRatio,
            0f,
        )
        assertEquals(
            LensChoice.TELE3X.zoomPreset,
            restoredOptics(CaptureMode.PHOTO, LensChoice.TELE3X, false, Float.POSITIVE_INFINITY).zoomRatio,
            0f,
        )
    }

    @Test fun `tele restore clamps local zoom to converter display ceiling`() {
        val restored = restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, true, 9f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertTrue(restored.teleconverter)
        assertEquals(60f, restored.zoomRatio * TELE_DISPLAY_BASE, 0.001f)
    }

    @Test fun `photo to video remap selects lens band and local zoom`() {
        val remapped = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.MAIN,
            teleconverter = false,
            controls = ManualControls(zoomRatio = 10f),
        )

        assertEquals(LensChoice.TELE10X, remapped.lens)
        assertEquals(1f, remapped.controls.zoomRatio, 0f)
    }

    @Test fun `video to photo remap restores unified framing`() {
        val remapped = remapModeOptics(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            lens = LensChoice.TELE10X,
            teleconverter = false,
            controls = ManualControls(zoomRatio = 2f),
        )

        assertEquals(LensChoice.TELE10X, remapped.lens)
        assertEquals(20f, remapped.controls.zoomRatio, 0f)
    }

    @Test fun `tele mode transition keeps local optics unchanged`() {
        val controls = ManualControls(zoomRatio = 2.5f)
        val remapped = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = controls,
        )

        assertEquals(LensChoice.TELE3X, remapped.lens)
        assertEquals(controls, remapped.controls)
    }

    @Test fun `live caps reconcile mode contract and narrower camera range`() {
        assertEquals(
            8f,
            reconcileZoomWithCaps(CaptureMode.VIDEO, false, 10f, 1f, 8f),
            0f,
        )
        assertEquals(
            0.8f,
            reconcileZoomWithCaps(CaptureMode.PHOTO, false, 0.6f, 0.8f, 20f),
            0f,
        )
    }

    // ---- TEST4-6: the non-teleconverter branch of effectiveZoomBounds ----

    @Test
    fun `non-TC bounds pass caps through`() {
        assertEquals(ZoomBounds(0.6f, 20f), effectiveZoomBounds(0.6f, 20f, teleconverter = false))
    }

    @Test
    fun `non-TC bounds are null on missing or inverted caps`() {
        org.junit.Assert.assertNull(effectiveZoomBounds(null, 20f, teleconverter = false))
        org.junit.Assert.assertNull(effectiveZoomBounds(0.6f, null, teleconverter = false))
        org.junit.Assert.assertNull(effectiveZoomBounds(20f, 1f, teleconverter = false))
    }

    // ---- TEST4-7: the crossingMark re-snap path (both endpoints INSIDE the band) ----

    @Test
    fun `crossing the mark inside its own band re-snaps to the mark`() {
        // 30.5x -> 29.5x display: both within the 30x band, but the request crosses the exact
        // mark — the crossingMark predicate (not enteringBand) must land it back on 30x.
        val bounds = effectiveZoomBounds(1f, 10f, teleconverter = true)
        val normalized = normalizeZoomRequest(
            requested = 29.5f / TELE_DISPLAY_BASE,
            currentApplied = 30.5f / TELE_DISPLAY_BASE,
            bounds = bounds,
            teleconverter = true,
        )
        assertEquals(30f, normalized * TELE_DISPLAY_BASE, 0.001f)
    }
}
