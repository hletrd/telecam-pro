package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.VideoCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [quickFnEnabled] (TEST4-9): the mid-REC quick-Fn gate is defense-in-depth for every caller
 * surface (Fn overlay, My Menu, Recent) — dropping one of these guards would let a quick tile
 * reconfigure the live session mid-recording, and only a device toast would show it.
 */
class QuickFnEnabledTest {

    private val idle = CameraUiState(isRecording = false, videoCodec = VideoCodec.HEVC)
    private val recording = CameraUiState(isRecording = true, videoCodec = VideoCodec.HEVC)

    @Test
    fun `session-reconfiguring slots disable while recording`() {
        for (slot in listOf(FnSlot.TRANSFER, FnSlot.TELECONVERTER, FnSlot.STABILIZATION, FnSlot.AUDIO_SCENE)) {
            assertTrue("$slot idle", quickFnEnabled(slot, idle))
            assertFalse("$slot recording", quickFnEnabled(slot, recording.copy(mode = CaptureMode.VIDEO)))
        }
    }

    @Test
    fun `transfer additionally requires HEVC`() {
        assertFalse(quickFnEnabled(FnSlot.TRANSFER, idle.copy(videoCodec = VideoCodec.AVC)))
        assertTrue(quickFnEnabled(FnSlot.TRANSFER, idle.copy(videoCodec = VideoCodec.HEVC)))
    }

    @Test
    fun `open gate requires video mode and not recording`() {
        assertFalse(quickFnEnabled(FnSlot.OPEN_GATE, idle.copy(mode = CaptureMode.PHOTO)))
        assertTrue(quickFnEnabled(FnSlot.OPEN_GATE, idle.copy(mode = CaptureMode.VIDEO)))
        assertFalse(
            quickFnEnabled(FnSlot.OPEN_GATE, recording.copy(mode = CaptureMode.VIDEO)),
        )
    }

    @Test
    fun `REC-safe slots stay enabled while recording`() {
        for (slot in listOf(
            FnSlot.EXPOSURE_MODE, FnSlot.FOCUS, FnSlot.SHUTTER, FnSlot.ISO, FnSlot.WB, FnSlot.EV,
            FnSlot.ZOOM, FnSlot.DRIVE, FnSlot.METERING, FnSlot.PEAKING, FnSlot.ZEBRA, FnSlot.GRID,
            FnSlot.LEVEL, FnSlot.PUNCH_IN, FnSlot.FRAME_LINES,
        )) {
            assertTrue("$slot must stay usable mid-REC", quickFnEnabled(slot, recording))
        }
    }
}
