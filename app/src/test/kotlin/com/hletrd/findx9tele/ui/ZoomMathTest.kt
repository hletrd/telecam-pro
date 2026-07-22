package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.reconcileZoomWithCaps
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE
import com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM
import com.hletrd.findx9tele.camera.normalizedForCaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ZoomMathTest {
    @Test fun `shared zoom formatter uses photographic glyph and stable decimal`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5×", formatZoomMultiplier(1.5f))
            assertEquals("10.0×", formatDisplayZoom(1f, false, LensChoice.TELE10X.targetEquivMm))
            assertEquals("13.0×", formatDisplayZoom(1f, true, null))
        } finally {
            Locale.setDefault(previous)
        }
    }

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

    @Test fun `video entry clamps slow shutter even when tele optics do not remap`() {
        val remapped = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(
                exposureMode = com.hletrd.findx9tele.camera.ExposureMode.ISO,
                exposureTimeNs = 500_000_000L,
                fps = 30,
                zoomRatio = 2.5f,
            ),
        )

        assertEquals(LensChoice.TELE3X, remapped.lens)
        assertEquals(2.5f, remapped.controls.zoomRatio, 0f)
        assertEquals(33_333_333L, remapped.controls.exposureTimeNs)
    }

    @Test fun `photo slow shutter survives a tele video round trip`() {
        val photoControls = ManualControls(
            exposureMode = com.hletrd.findx9tele.camera.ExposureMode.MANUAL,
            exposureTimeNs = 500_000_000L,
            fps = 30,
            zoomRatio = 2.5f,
        )
        val entering = modeExposureState(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            controls = photoControls,
            rememberedPhotoExposureTimeNs = 8_000_000L,
        )
        val video = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = entering.controls,
        )
        assertEquals(33_333_333L, video.controls.exposureTimeNs)

        val leaving = modeExposureState(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            controls = video.controls,
            rememberedPhotoExposureTimeNs = entering.photoExposureTimeNs,
        )
        val photo = remapModeOptics(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = leaving.controls,
        )

        assertEquals(500_000_000L, photo.controls.exposureTimeNs)
    }

    @Test fun `angle mode round trip retains its dormant photo speed`() {
        val remembered = modeExposureState(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            controls = ManualControls(
                exposureMode = com.hletrd.findx9tele.camera.ExposureMode.SHUTTER,
                shutterMode = com.hletrd.findx9tele.camera.ShutterMode.ANGLE,
                shutterAngle = 180f,
                exposureTimeNs = 2_000_000_000L,
                fps = 30,
            ),
            rememberedPhotoExposureTimeNs = 8_000_000L,
        )
        val video = remembered.controls.normalizedForCaptureMode(CaptureMode.VIDEO)
        val restored = modeExposureState(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            controls = video,
            rememberedPhotoExposureTimeNs = remembered.photoExposureTimeNs,
        )

        assertEquals(2_000_000_000L, restored.controls.exposureTimeNs)
        assertEquals(com.hletrd.findx9tele.camera.ShutterMode.ANGLE, restored.controls.shutterMode)
    }

    @Test fun `video MR restore never clamps the hidden photo shutter with outgoing caps`() {
        val restored = restoredExposureState(
            targetMode = CaptureMode.VIDEO,
            activeExposureTimeNs = 2_000_000_000L,
            storedPhotoExposureTimeNs = 4_000_000_000L,
            authoritativeMinNs = 10_000L,
            authoritativeMaxNs = 500_000_000L,
        )

        assertEquals(500_000_000L, restored.activeExposureTimeNs)
        assertEquals(4_000_000_000L, restored.photoExposureTimeNs)
    }

    @Test fun `cross-route photo MR waits for target caps before clamping`() {
        assertFalse(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.VIDEO,
                currentLens = LensChoice.TELE3X,
                currentTeleconverter = false,
                currentOverrideId = null,
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.MAIN,
                targetTeleconverter = false,
            ),
        )
        val restored = restoredExposureState(
            targetMode = CaptureMode.PHOTO,
            activeExposureTimeNs = 4_000_000_000L,
            storedPhotoExposureTimeNs = 500_000_000L,
            authoritativeMinNs = null,
            authoritativeMaxNs = null,
        )

        assertEquals(4_000_000_000L, restored.activeExposureTimeNs)
        assertEquals(4_000_000_000L, restored.photoExposureTimeNs)
    }

    @Test fun `same logical photo route may use its accepted caps immediately`() {
        assertTrue(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.PHOTO,
                currentLens = LensChoice.ULTRAWIDE,
                currentTeleconverter = false,
                currentOverrideId = null,
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.TELE3X,
                targetTeleconverter = false,
            ),
        )
        val restored = restoredExposureState(
            targetMode = CaptureMode.PHOTO,
            activeExposureTimeNs = 6_300_000_000L,
            storedPhotoExposureTimeNs = 500_000_000L,
            authoritativeMinNs = 10_000L,
            authoritativeMaxNs = 4_000_000_000L,
        )

        assertEquals(4_000_000_000L, restored.activeExposureTimeNs)
        assertEquals(4_000_000_000L, restored.photoExposureTimeNs)
    }

    @Test fun `debug camera override never lends caps to an automatic MR route`() {
        assertFalse(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.PHOTO,
                currentLens = LensChoice.MAIN,
                currentTeleconverter = false,
                currentOverrideId = "5",
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.MAIN,
                targetTeleconverter = false,
            ),
        )
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

    // ---- FRONT (selfie) route: display scale, mode remap, and caps authority ----

    @Test fun `front zoom displays lens-local, never main-relative`() {
        // front-equiv ÷ main-equiv would read "0.9×" at the selfie 1× — the honest front display
        // is the plain local ratio.
        assertEquals(1f, zoomDisplayMultiplier(teleconverter = false, equivalentFocalMm = 20f, frontFacing = true), 0f)
        assertEquals("2.0×", formatDisplayZoom(2f, teleconverter = false, equivalentFocalMm = 20f, frontFacing = true))
    }

    @Test fun `front mode flip keeps lens-local zoom and the retained rear band`() {
        // The unified↔local remap is a REAR concept; on the single front camera it would rewrite
        // the retained rear band (what "leave FRONT" returns to) from a front-local ratio.
        val optics = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.MAIN,
            teleconverter = false,
            controls = ManualControls(zoomRatio = 5f),
            frontFacing = true,
        )
        assertEquals(LensChoice.MAIN, optics.lens)
        assertEquals(5f, optics.controls.zoomRatio, 0f)
    }

    @Test fun `front route caps are never authoritative for a recalled rear packet`() {
        // While FRONT the current mode/lens fields can coincidentally equal the recalled target's,
        // but the live caps describe the front camera.
        assertFalse(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.PHOTO,
                currentLens = LensChoice.MAIN,
                currentTeleconverter = false,
                currentOverrideId = null,
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.MAIN,
                targetTeleconverter = false,
                currentFrontFacing = true,
            ),
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
