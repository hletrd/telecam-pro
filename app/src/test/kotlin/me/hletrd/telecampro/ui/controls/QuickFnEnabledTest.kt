package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.VideoCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [quickFnEnabled] (TEST4-9): the mid-REC quick-Fn gate is defense-in-depth for every caller
 * surface (Fn overlay, My Menu, Recent) — dropping one of these guards would let a quick tile
 * reconfigure the live session mid-recording, and only a device toast would show it.
 */
class QuickFnEnabledTest {

    // Video mode: the session-reconfiguring slots below are video-only (fnSlotAppliesTo), so an
    // idle PHOTO baseline would be testing the mode gate rather than the mid-REC gate.
    private val idle = CameraUiState(isRecording = false, videoCodec = VideoCodec.HEVC, mode = CaptureMode.VIDEO)
    private val recording = CameraUiState(isRecording = true, videoCodec = VideoCodec.HEVC)

    @Test
    fun `session-reconfiguring slots disable while recording`() {
        for (slot in listOf(FnSlot.TRANSFER, FnSlot.TELECONVERTER, FnSlot.STABILIZATION, FnSlot.AUDIO_SCENE)) {
            assertTrue("$slot idle", quickFnEnabled(slot, idle))
            assertFalse("$slot recording", quickFnEnabled(slot, recording.copy(mode = CaptureMode.VIDEO)))
        }
    }

    @Test
    fun `video-only slots dim in photo wherever they appear`() {
        // A Photo Fn list persisted before the editor filter existed — and My Menu, which keeps
        // every slot by design — can still contain these. STABILIZATION is the sharp case: it reads
        // videoStabMode (Off/Standard/Active) while a photo's stabilization is controls.oisEnabled,
        // which the OSD reports as OIS OFF, so a hot chip could contradict the OSD for one frame.
        for (slot in listOf(FnSlot.STABILIZATION, FnSlot.TRANSFER, FnSlot.AUDIO_SCENE, FnSlot.OPEN_GATE)) {
            assertFalse("$slot in photo", quickFnEnabled(slot, idle.copy(mode = CaptureMode.PHOTO)))
            assertTrue("$slot in video", quickFnEnabled(slot, idle))
        }
        // Slots that act in both modes are untouched by the new axis.
        for (slot in listOf(FnSlot.ISO, FnSlot.WB, FnSlot.FOCUS, FnSlot.TELECONVERTER)) {
            assertTrue("$slot in photo", quickFnEnabled(slot, idle.copy(mode = CaptureMode.PHOTO)))
        }
    }

    @Test
    fun `the Fn editor offers only the slots its list can act on`() {
        val videoOnly = listOf(
            FnSlot.STABILIZATION, FnSlot.TRANSFER, FnSlot.AUDIO_SCENE, FnSlot.OPEN_GATE,
            FnSlot.AUDIO_INPUT,
        )
        for (slot in videoOnly) {
            assertFalse("$slot", fnSlotAppliesTo(slot, CaptureMode.PHOTO))
            assertTrue("$slot", fnSlotAppliesTo(slot, CaptureMode.VIDEO))
        }
        // Still-only axes: the self-timer counts to a SHUTTER; aspect is the STILL crop.
        val photoOnly = listOf(FnSlot.TIMER, FnSlot.ASPECT)
        for (slot in photoOnly) {
            assertTrue("$slot", fnSlotAppliesTo(slot, CaptureMode.PHOTO))
            assertFalse("$slot", fnSlotAppliesTo(slot, CaptureMode.VIDEO))
        }
        for (slot in FnSlot.entries - videoOnly.toSet() - photoOnly.toSet()) {
            assertTrue("$slot photo", fnSlotAppliesTo(slot, CaptureMode.PHOTO))
            assertTrue("$slot video", fnSlotAppliesTo(slot, CaptureMode.VIDEO))
        }
    }

    @Test
    fun `teleconverter tile dims on the selfie route`() {
        // onToggleTeleconverter refuses while FRONT (backOpticsDoorRefusal) — the tile must not
        // render hot and merely toast on tap (the exact drift this predicate's contract forbids).
        assertFalse(quickFnEnabled(FnSlot.TELECONVERTER, idle.copy(facing = CameraFacing.FRONT)))
        assertTrue(quickFnEnabled(FnSlot.TELECONVERTER, idle.copy(facing = CameraFacing.BACK)))
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
