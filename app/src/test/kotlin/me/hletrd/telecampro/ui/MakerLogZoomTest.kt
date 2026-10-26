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
        val tc = requested.copy(teleconverterMode = true)
        assertEquals(VideoColorMode.LOG, tc.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, tc.effectiveVideoTransfer)
        assertEquals(ColorTransfer.MAKER_LOG, tc.transfer)
        val refused = requested.copy(photoSessionOutputs = PhotoSessionOutputs())
        assertEquals(VideoColorMode.LOG, refused.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, refused.effectiveVideoTransfer)
    }
}
