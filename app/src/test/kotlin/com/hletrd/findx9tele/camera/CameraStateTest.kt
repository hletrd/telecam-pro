package com.hletrd.findx9tele.camera

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
}
