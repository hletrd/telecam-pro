package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins [CameraUiState.activeFnSlots]: the Fn bar reflects the current capture mode. */
class CameraStateTest {

    @Test
    fun `family delete intent defaults to no live still producer`() {
        val family = me.hletrd.telecampro.storage.CaptureFamilyKey(
            me.hletrd.telecampro.storage.CaptureFamilyMedia.VIDEO,
            capturedAtEpochMillis = 1_700_000_000_000L,
            sequence = 9L,
        )

        val intent = CaptureFamilyDeleteIntent(family, MediaDeleteScope.CAPTURE_FAMILY)

        assertSame(family, intent.familyKey)
        assertEquals(MediaDeleteScope.CAPTURE_FAMILY, intent.scope)
        assertEquals(null, intent.liveStillCaptureId)
    }

    @Test
    fun `other phone uses the measured converter host focal when available`() {
        val inventory = LensInventory(
            available = setOf(LensChoice.MAIN, LensChoice.TELE3X),
            optical = setOf(LensChoice.MAIN, LensChoice.TELE3X),
            teleHostEquivMm = 82f,
        )

        assertEquals(
            82f,
            CameraUiState(phoneModel = PhoneModel.OTHER, lensInventory = inventory)
                .teleconverterHostEquivMm,
            0f,
        )
        assertEquals(
            DEFAULT_PHONE_MODEL.teleEquivMm,
            CameraUiState(phoneModel = DEFAULT_PHONE_MODEL, lensInventory = inventory)
                .teleconverterHostEquivMm,
            0f,
        )
    }

    @Test
    fun `accepted encoder fallback replaces requested raster only while active`() {
        val requested = Size(3840, 2160)
        val accepted = Size(1920, 1080)
        val idle = CameraUiState(videoResolution = requested)
        assertSame(requested, idle.encodedVideoResolution)
        assertSame(
            accepted,
            idle.copy(activeEncoderResolution = accepted).encodedVideoResolution,
        )
    }

    @Test
    fun activeFnSlots_selectsByMode() {
        val photoSlots = listOf(FnSlot.WB)
        val videoSlots = listOf(FnSlot.ISO)
        assertEquals(
            photoSlots,
            CameraUiState(mode = CaptureMode.PHOTO, photoFnSlots = photoSlots, videoFnSlots = videoSlots).activeFnSlots,
        )
        assertEquals(
            videoSlots,
            CameraUiState(mode = CaptureMode.VIDEO, photoFnSlots = photoSlots, videoFnSlots = videoSlots).activeFnSlots,
        )
    }

    @Test
    fun `preview-only Ready disables photo capture but keeps video record healthy`() {
        val photo = CameraUiState(
            mode = CaptureMode.PHOTO,
            cameraReady = true,
            photoSessionOutputs = PhotoSessionOutputs(),
        )
        val video = photo.copy(mode = CaptureMode.VIDEO)

        assertFalse(photo.stillCaptureReady)
        assertFalse(photo.primaryShutterHealthy)
        assertFalse(photo.primaryShutterEnabled)
        assertTrue(video.primaryShutterHealthy)
        assertTrue(video.primaryShutterEnabled)
    }

    @Test
    fun `recording snapshot requires an accepted still target`() {
        val unavailable = CameraUiState(
            mode = CaptureMode.VIDEO,
            cameraReady = true,
            isRecording = true,
            photoSessionOutputs = PhotoSessionOutputs(),
        )
        val available = unavailable.copy(photoSessionOutputs = PhotoSessionOutputs(processed = true))

        assertFalse(unavailable.stillCaptureReady)
        assertTrue(available.stillCaptureReady)
    }

    @Test
    fun `fail closed output ownership disables a Ready photo shutter`() {
        val blocked = CameraUiState(
            mode = CaptureMode.PHOTO,
            cameraReady = true,
            photoSessionOutputs = PhotoSessionOutputs(processed = true),
            stillCaptureAdmissionAvailable = false,
        )

        assertFalse(blocked.stillCaptureReady)
        assertFalse(blocked.primaryShutterHealthy)
        assertFalse(blocked.primaryShutterEnabled)
    }

    @Test
    fun `viewfinder focus actions follow accepted AUTO region AF and held point truth`() {
        val auto = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO)

        assertEquals(
            ViewfinderFocusActionAvailability(focusAtCenter = true, resetFocusPoint = false),
            viewfinderFocusActionAvailability(
                cameraReady = true,
                maxAfRegions = 1,
                focusMode = FocusMode.CONTINUOUS,
                afModes = auto,
                tapFocusHeld = false,
            ),
        )
        listOf(
            viewfinderFocusActionAvailability(false, 1, FocusMode.CONTINUOUS, auto, false),
            viewfinderFocusActionAvailability(true, 0, FocusMode.CONTINUOUS, auto, false),
            viewfinderFocusActionAvailability(true, 1, FocusMode.MANUAL, auto, false),
            viewfinderFocusActionAvailability(true, 1, FocusMode.CONTINUOUS, IntArray(0), false),
        ).forEach { availability ->
            assertFalse(availability.focusAtCenter)
            assertFalse(availability.resetFocusPoint)
        }

        assertEquals(
            ViewfinderFocusActionAvailability(focusAtCenter = false, resetFocusPoint = true),
            viewfinderFocusActionAvailability(
                cameraReady = false,
                maxAfRegions = 0,
                focusMode = FocusMode.MANUAL,
                afModes = IntArray(0),
                tapFocusHeld = true,
            ),
        )
    }

    @Test
    fun `recording snapshot is single regardless of saved Photo drive`() {
        DriveMode.entries.forEach { selected ->
            assertEquals(DriveMode.SINGLE, captureDriveMode(selected, singleShot = true))
            assertEquals(selected, captureDriveMode(selected, singleShot = false))
        }
    }

    @Test
    fun `record stop remains enabled through a camera health transition`() {
        val recording = CameraUiState(
            mode = CaptureMode.VIDEO,
            cameraReady = false,
            isRecording = true,
            photoSessionOutputs = PhotoSessionOutputs(),
        )

        assertTrue(recording.primaryShutterEnabled)
    }

    @Test
    fun `photo shutter remains enabled to cancel an active countdown`() {
        val countdown = CameraUiState(
            mode = CaptureMode.PHOTO,
            cameraReady = false,
            timerCountdownSec = 2,
            photoSessionOutputs = PhotoSessionOutputs(),
        )

        assertTrue(countdown.primaryShutterEnabled)
    }

    @Test
    fun `AF indication maps every HAL state and treats unknown as idle`() {
        assertEquals(
            AfIndication.SCANNING,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN),
        )
        assertEquals(
            AfIndication.SCANNING,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN),
        )
        assertEquals(
            AfIndication.FOCUSED,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED),
        )
        assertEquals(
            AfIndication.FOCUSED,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED),
        )
        assertEquals(
            AfIndication.FAILED,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED),
        )
        assertEquals(
            AfIndication.FAILED,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED),
        )
        // INACTIVE and any future HAL value both fold to the quiet reticle state.
        assertEquals(
            AfIndication.IDLE,
            AfIndication.fromHal(CameraMetadata.CONTROL_AF_STATE_INACTIVE),
        )
        assertEquals(AfIndication.IDLE, AfIndication.fromHal(-1))
    }

    @Test
    fun `Fn bank general default is the photo bank`() {
        assertEquals(FnSlot.PHOTO_DEFAULT, FnSlot.DEFAULT)
    }

    @Test
    fun `only the 3x periscope is the teleconverter mount lens`() {
        // The Hasselblad clamp fits the 70 mm periscope only; the gate must never widen.
        assertEquals(
            listOf(LensChoice.TELE3X),
            LensChoice.entries.filter { it.isTeleconverterLens },
        )
        assertEquals(3f, LensChoice.TELE3X.zoomPreset)
    }

}
