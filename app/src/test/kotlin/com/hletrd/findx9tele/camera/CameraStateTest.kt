package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins [CameraUiState.activeFnSlots]: the Fn bar reflects the current capture mode. */
class CameraStateTest {

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
        assertEquals("3×", LensChoice.TELE3X.label)
    }

    @Test
    fun `dormant vendor log plumbing keeps its HAL wire values`() {
        // CameraUnit-reserved; the enum stays truthful even while nothing selects it.
        assertEquals(0, VendorLogMode.OFF.halValue)
        assertEquals(1, VendorLogMode.ON.halValue)
    }
}
