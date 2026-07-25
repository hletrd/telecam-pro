package me.hletrd.findx9tele.ui.overlays

import me.hletrd.findx9tele.camera.CameraFacing
import me.hletrd.findx9tele.camera.CameraUiState
import me.hletrd.findx9tele.camera.CaptureMode
import me.hletrd.findx9tele.camera.ColorTransfer
import me.hletrd.findx9tele.camera.DriveMode
import me.hletrd.findx9tele.camera.FocusConfidenceSource
import me.hletrd.findx9tele.camera.ManualControls
import me.hletrd.findx9tele.camera.MemorySlot
import me.hletrd.findx9tele.camera.PhotoFormats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactStatusVisibilityTest {
    @Test
    fun `default photo state leaves the clean viewfinder empty`() {
        assertFalse(compactShootingStatusVisible(CameraUiState()))
    }

    @Test
    fun `states that can silently alter output remain visible`() {
        val base = CameraUiState()
        val cases = listOf(
            "memory recall" to base.copy(activeMemorySlot = MemorySlot.MR1),
            "non-default still formats" to base.copy(photoFormats = PhotoFormats(jpeg = true)),
            "raw" to base.copy(photoFormats = PhotoFormats(dngRaw = true)),
            "drive" to base.copy(driveMode = DriveMode.BURST),
            "photo stabilization disabled" to base.copy(controls = ManualControls(oisEnabled = false)),
            "lock" to base.copy(controls = ManualControls(aeLock = true)),
            "video transfer" to base.copy(mode = CaptureMode.VIDEO, transfer = ColorTransfer.HLG),
            "muted video" to base.copy(mode = CaptureMode.VIDEO, recordAudio = false),
            "open gate" to base.copy(mode = CaptureMode.VIDEO, openGate = true),
            // Both proofs of the focus-confidence tag: the compact strip is the DEFAULT viewfinder,
            // so a tag that only shows when another tag is up is a tag that never shows.
            "focus confidence (AF limit)" to base.copy(focusConfidence = FocusConfidenceSource.AF_LIMIT),
            "focus confidence (frame detail)" to base.copy(focusConfidence = FocusConfidenceSource.FRAME_DETAIL),
            // Facing is the one state that changes what the app is for; rear stays untagged.
            "front camera" to base.copy(facing = CameraFacing.FRONT),
        )
        cases.forEach { (label, state) -> assertTrue(label, compactShootingStatusVisible(state)) }
    }

    @Test
    fun `ordinary SDR video keeps its stabilization state visible`() {
        assertTrue(
            compactShootingStatusVisible(
                CameraUiState(mode = CaptureMode.VIDEO, transfer = ColorTransfer.SDR),
            ),
        )
    }
}
