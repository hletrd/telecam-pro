package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.*
import org.junit.Assert.*
import org.junit.Test

class MakerLogZoomTest {
    @Test fun `DNG photo to factory video preserves the 3x lens framing across zoom scales`() {
        val video = remapModeOptics(
            CaptureMode.PHOTO, CaptureMode.VIDEO, LensChoice.TELE3X, false,
            ManualControls(zoomRatio = 1.25f), false, false,
            photoIsStandalone = true, videoIsStandalone = false,
            optical = LensChoice.entries.toSet(), presets = ZoomPresets.NOMINAL,
        )
        assertEquals(3.75f, video.controls.zoomRatio, 1e-6f)
        val photo = remapModeOptics(
            CaptureMode.VIDEO, CaptureMode.PHOTO, video.lens, false, video.controls, false, false,
            photoIsStandalone = true, videoIsStandalone = false,
            optical = LensChoice.entries.toSet(), presets = ZoomPresets.NOMINAL,
        )
        assertEquals(LensChoice.TELE3X, photo.lens)
        assertEquals(1.25f, photo.controls.zoomRatio, 1e-6f)
    }

    @Test fun `factory video restore retains unified framing instead of multiplying it by the lens`() {
        val restored = restoredOptics(
            CaptureMode.VIDEO, LensChoice.TELE3X, false, 13f, 3.75f,
            photoStandalone = false, videoStandalone = false, presets = ZoomPresets.NOMINAL,
        )
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertEquals(3.75f, restored.zoomRatio, 1e-6f)
    }

    @Test fun `an unsupported maker request reports its effective S-Log3 fallback`() {
        val requested = CameraUiState(
            mode = CaptureMode.VIDEO, transfer = ColorTransfer.MAKER_LOG,
            makerLogSupported = true, cameraReady = true,
            photoSessionOutputs = PhotoSessionOutputs(makerLog = true),
        )
        assertEquals(VideoColorMode.MAKER_LOG, requested.videoColorMode)
        assertEquals(ColorTransfer.MAKER_LOG, requested.effectiveVideoTransfer)
        // A profile without a measured TC span keeps withholding it under TC.
        val tc = requested.copy(teleconverterMode = true)
        assertEquals(VideoColorMode.LOG, tc.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, tc.effectiveVideoTransfer)
        assertEquals(ColorTransfer.MAKER_LOG, tc.transfer)
        // The measured profile offers it with the converter on (owner request 2026-10-26).
        val tcMeasured = tc.copy(makerLogTeleSupported = true)
        assertEquals(VideoColorMode.MAKER_LOG, tcMeasured.videoColorMode)
        assertEquals(ColorTransfer.MAKER_LOG, tcMeasured.effectiveVideoTransfer)
        // ...and still reads the S-Log3 the session records when the maker route was refused.
        val tcRefused = tcMeasured.copy(photoSessionOutputs = PhotoSessionOutputs())
        assertEquals(VideoColorMode.LOG, tcRefused.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, tcRefused.effectiveVideoTransfer)
        val refused = requested.copy(photoSessionOutputs = PhotoSessionOutputs())
        assertEquals(VideoColorMode.LOG, refused.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, refused.effectiveVideoTransfer)
    }

    @Test fun `maker Log under TC keeps the converter's TC-local zoom scale`() {
        val ordinary = CameraUiState(
            mode = CaptureMode.VIDEO, transfer = ColorTransfer.SLOG3, makerLogSupported = true,
            makerLogTeleSupported = true, teleconverterMode = true, lens = LensChoice.TELE3X,
            controls = ManualControls(zoomRatio = 2f),
        )
        val maker = ordinary.copy(transfer = ColorTransfer.MAKER_LOG)
        // The TC route moves the CAMERA, never the scale: no unified-scale maker route under TC...
        assertFalse(maker.makerLogRoute)
        assertTrue(maker.makerLogTeleRoute)
        assertFalse(ordinary.makerLogTeleRoute)
        // ...so every scale reader agrees with the ordinary TC route at the same TC-local ratio.
        assertEquals(ordinary.unifiedZoom, maker.unifiedZoom, 0f)
        assertEquals(ordinary.mainRelativeZoomMultiplier, maker.mainRelativeZoomMultiplier, 0f)
        assertEquals(ordinary.teleDisplayBase, maker.mainRelativeZoomMultiplier, 0f)
        // Off TC the maker route is the unified scale, as before.
        val nonTc = maker.copy(teleconverterMode = false)
        assertTrue(nonTc.makerLogRoute)
        assertFalse(nonTc.makerLogTeleRoute)
        // Photo never rides the maker pipeline, TC or not.
        assertFalse(maker.copy(mode = CaptureMode.PHOTO).makerLogTeleRoute)
    }

    @Test fun `the TC ruler and rail stop at the maker route's reachable span`() {
        // The route caps publish the TC-local window [1, 9.8/3]; effectiveZoomBounds is the seam every
        // clamp, ruler and rail mark reads, and it must keep the converter's 60x TOTAL cap out of reach.
        val base = (300f / 70f) * (70f / 23f)
        val window = makerTeleLocalZoomWindow(0.6f, 20f, 3f, MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110)!!
        val bounds = effectiveZoomBounds(window.start, window.endInclusive, teleconverter = true, teleDisplayBase = base)!!
        assertEquals(1f, bounds.lower, 0f)
        assertEquals(9.8f / 3f, bounds.upper, 1e-6f)
        val ruler = zoomRulerScale(bounds.lower, bounds.upper, base, teleconverter = true)
        assertEquals(42.6f, ruler.hi, 0.1f)
        // The ordinary TC route on standalone 4 (1-10x advertised) keeps the 60x TOTAL ceiling.
        val ordinary = effectiveZoomBounds(1f, 10f, teleconverter = true, teleDisplayBase = base)!!
        assertEquals(60f / base, ordinary.upper, 1e-5f)
    }
}
