package me.hletrd.telecampro

import android.media.AudioDeviceInfo
import android.media.MediaFormat
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.afTelemetryBelongsToRepeatingRequest
import me.hletrd.telecampro.camera.exifLensModel
import me.hletrd.telecampro.storage.StoredMediaCollection
import me.hletrd.telecampro.storage.StoredMediaOutputKind
import me.hletrd.telecampro.storage.storedMediaOutputKind
import me.hletrd.telecampro.ui.controls.formatFocusRelative
import me.hletrd.telecampro.ui.overlays.compactPhotoFormatLabel
import me.hletrd.telecampro.ui.overlays.photoFormatLabel
import me.hletrd.telecampro.ui.teleZoomMarkState
import me.hletrd.telecampro.video.AudioInputPortInfo
import me.hletrd.telecampro.video.ColorProfiles
import me.hletrd.telecampro.video.audioPortLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for pure seams the 2026-07-30 whole-app review flagged as having no direct host coverage,
 * plus the pure halves of that review's fixes. Each block names its seam.
 */
class ReviewSurfacedSeamsTest {

    // --- hardwareShutterAudioDrop (review C7/C8) -------------------------------------------------

    @Test
    fun `only the SHUTTER binding may drop audio`() {
        // A volume key remapped to Zoom In used to flip audio off (and, because recordAudio gates
        // the touch prompt, silently condemned every later clip to video-only).
        for (action in HardwareKeyAction.entries.filter { it != HardwareKeyAction.SHUTTER }) {
            assertFalse(
                action.name,
                hardwareShutterAudioDrop(
                    fullKeyAction = action,
                    videoMode = true,
                    recording = false,
                    recordAudio = true,
                    hasMicrophonePermission = false,
                ),
            )
        }
        assertTrue(
            hardwareShutterAudioDrop(
                fullKeyAction = HardwareKeyAction.SHUTTER,
                videoMode = true,
                recording = false,
                recordAudio = true,
                hasMicrophonePermission = false,
            ),
        )
    }

    @Test
    fun `a granted microphone or photo mode never drops audio`() {
        assertFalse(
            hardwareShutterAudioDrop(
                fullKeyAction = HardwareKeyAction.SHUTTER,
                videoMode = true,
                recording = false,
                recordAudio = true,
                hasMicrophonePermission = true,
            ),
        )
        assertFalse(
            hardwareShutterAudioDrop(
                fullKeyAction = HardwareKeyAction.SHUTTER,
                videoMode = false,
                recording = false,
                recordAudio = true,
                hasMicrophonePermission = false,
            ),
        )
        // Audio already off: nothing left to drop, and the press must not re-touch the setting.
        assertFalse(
            hardwareShutterAudioDrop(
                fullKeyAction = HardwareKeyAction.SHUTTER,
                videoMode = true,
                recording = false,
                recordAudio = false,
                hasMicrophonePermission = false,
            ),
        )
    }

    // --- hasVisualMediaAccess (reinstall gallery restore, 2026-08-01) ----------------------------

    @Test
    fun `any visual-media grant counts as access, including the partial Select-photos grant`() {
        assertTrue(hasVisualMediaAccess(imagesGranted = true, videoGranted = false, userSelectedGranted = false))
        assertTrue(hasVisualMediaAccess(imagesGranted = false, videoGranted = true, userSelectedGranted = false))
        // Android 14+ "Select photos": full permissions read DENIED while USER_SELECTED is granted
        // — a denial-shaped result map that must still count as access.
        assertTrue(hasVisualMediaAccess(imagesGranted = false, videoGranted = false, userSelectedGranted = true))
        assertFalse(hasVisualMediaAccess(imagesGranted = false, videoGranted = false, userSelectedGranted = false))
    }

    // --- exifLensModel unknown-optics omission (review L6) ---------------------------------------

    @Test
    fun `unknown focal and aperture omit their tokens instead of claiming 0mm f0`() {
        assertEquals(
            "OPPO PMA110 camera",
            exifLensModel("OPPO", "PMA110", equivMm = 0f, apertureF = 0f, frontFacing = false),
        )
    }

    @Test
    fun `known optics keep the full label with truncated aperture`() {
        assertEquals(
            "OPPO PMA110 wide camera 23mm f/1.6",
            exifLensModel("OPPO", "PMA110", equivMm = 23f, apertureF = 1.69f, frontFacing = false),
        )
    }

    // --- LensChoice.forZoom ----------------------------------------------------------------------

    @Test
    fun `forZoom band boundaries follow the logical camera's crossings`() {
        assertEquals(LensChoice.ULTRAWIDE, LensChoice.forZoom(0.6f))
        assertEquals(LensChoice.MAIN, LensChoice.forZoom(1f))
        assertEquals(LensChoice.MAIN, LensChoice.forZoom(2.99f))
        assertEquals(LensChoice.TELE3X, LensChoice.forZoom(3f))
        assertEquals(LensChoice.TELE3X, LensChoice.forZoom(9.99f))
        assertEquals(LensChoice.TELE10X, LensChoice.forZoom(10f))
        assertEquals(LensChoice.TELE10X, LensChoice.forZoom(20f))
    }

    // --- ColorProfiles.mimeFor -------------------------------------------------------------------

    @Test
    fun `mimeFor maps each codec to its encoder MIME`() {
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, ColorProfiles.mimeFor(VideoCodec.HEVC))
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, ColorProfiles.mimeFor(VideoCodec.AVC))
        assertEquals("video/apv", ColorProfiles.mimeFor(VideoCodec.APV))
    }

    // --- audioPortLabel --------------------------------------------------------------------------

    @Test
    fun `a meaningful product name joins the type label`() {
        assertEquals(
            "USB mic · Yeti Stereo Microphone",
            audioPortLabel(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_USB_DEVICE, "Yeti Stereo Microphone"),
            ),
        )
    }

    @Test
    fun `blank or literal Unknown product names collapse to the type label alone`() {
        assertEquals(
            "Phone mic",
            audioPortLabel(AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, " ")),
        )
        assertEquals(
            "Phone mic",
            audioPortLabel(AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Unknown")),
        )
        assertEquals(
            "BT mic",
            audioPortLabel(AudioInputPortInfo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null)),
        )
    }

    // --- storedMediaOutputKind -------------------------------------------------------------------

    @Test
    fun `raw is recognised by any adobe mime alias or a dng extension`() {
        for (mime in listOf("image/x-adobe-dng", "image/dng", "application/x-adobe-dng")) {
            assertEquals(
                mime,
                StoredMediaOutputKind.RAW,
                storedMediaOutputKind(StoredMediaCollection.IMAGE, mime, "x.bin"),
            )
        }
        // Extension wins even when the provider mislabels the MIME; parameters after ';' are ignored.
        assertEquals(
            StoredMediaOutputKind.RAW,
            storedMediaOutputKind(StoredMediaCollection.IMAGE, "image/jpeg", "IMG.DNG"),
        )
        assertEquals(
            StoredMediaOutputKind.RAW,
            storedMediaOutputKind(StoredMediaCollection.IMAGE, "IMAGE/DNG; charset=binary", null),
        )
    }

    @Test
    fun `everything else in either collection is displayable`() {
        assertEquals(
            StoredMediaOutputKind.DISPLAYABLE,
            storedMediaOutputKind(StoredMediaCollection.IMAGE, "image/jpeg", "a.jpg"),
        )
        assertEquals(
            StoredMediaOutputKind.DISPLAYABLE,
            storedMediaOutputKind(StoredMediaCollection.VIDEO, "video/mp4", "a.mp4"),
        )
        assertEquals(
            StoredMediaOutputKind.DISPLAYABLE,
            storedMediaOutputKind(StoredMediaCollection.IMAGE, null, null),
        )
    }

    // --- teleZoomMarkState -----------------------------------------------------------------------

    @Test
    fun `tele zoom marks share the rail's availability wording`() {
        assertEquals(
            "Unavailable while recording",
            teleZoomMarkState(selected = true, cameraReady = true, recording = true).stateDescription,
        )
        assertEquals(
            "Camera reconfiguring",
            teleZoomMarkState(selected = false, cameraReady = false, recording = false).stateDescription,
        )
        val selected = teleZoomMarkState(selected = true, cameraReady = true, recording = false)
        assertEquals("Selected", selected.stateDescription)
        assertTrue(selected.enabled)
        assertFalse(
            teleZoomMarkState(selected = false, cameraReady = true, recording = true).enabled,
        )
    }

    // --- photoFormatLabel / compactPhotoFormatLabel ----------------------------------------------

    @Test
    fun `format label joins with plus and uses the app's one null token when empty`() {
        assertEquals(
            "HEIF+JPEG+DNG",
            photoFormatLabel(PhotoFormats(heif = true, jpeg = true, dngRaw = true)),
        )
        assertEquals("JPEG", photoFormatLabel(PhotoFormats(heif = false, jpeg = true, dngRaw = false)))
        assertEquals(
            "JPEG+DNG",
            photoFormatLabel(PhotoFormats(heif = false, jpeg = true, dngRaw = true)),
        )
        assertEquals("--", photoFormatLabel(PhotoFormats(heif = false, jpeg = false, dngRaw = false)))
    }

    @Test
    fun `compact strip is silent in video and for the default HEIF-only selection`() {
        val videoState = CameraUiState(mode = CaptureMode.VIDEO)
        assertNull(compactPhotoFormatLabel(videoState))
        val defaultPhoto = CameraUiState(
            mode = CaptureMode.PHOTO,
            photoFormats = PhotoFormats(heif = true, jpeg = false, dngRaw = false),
        )
        assertNull(compactPhotoFormatLabel(defaultPhoto))
        val withDng = CameraUiState(
            mode = CaptureMode.PHOTO,
            photoFormats = PhotoFormats(heif = true, jpeg = false, dngRaw = true),
        )
        assertEquals("HEIF+DNG", compactPhotoFormatLabel(withDng))
    }

    // --- afTelemetryBelongsToRepeatingRequest ----------------------------------------------------

    @Test
    fun `only the idle repeating stream owns UI AF state`() {
        assertTrue(afTelemetryBelongsToRepeatingRequest(null))
        assertTrue(afTelemetryBelongsToRepeatingRequest(0)) // CONTROL_AF_TRIGGER_IDLE
        assertFalse(afTelemetryBelongsToRepeatingRequest(1)) // CONTROL_AF_TRIGGER_START
        assertFalse(afTelemetryBelongsToRepeatingRequest(2)) // CONTROL_AF_TRIGGER_CANCEL
    }

    // --- formatFocusRelative ---------------------------------------------------------------------

    @Test
    fun `focus readout is relative to infinity with a hard infinity threshold`() {
        // No focuser range advertised -> only infinity can be claimed.
        assertEquals("∞", formatFocusRelative(diopters = 5f, minDiopters = 0f))
        // At the infinity end of the slider (f <= 0.005) the readout stays the plain symbol.
        assertEquals("∞", formatFocusRelative(diopters = 0f, minDiopters = 10f))
        // Fully racked near = the whole relative scale.
        assertEquals("∞+100", formatFocusRelative(diopters = 10f, minDiopters = 10f))
    }
}
