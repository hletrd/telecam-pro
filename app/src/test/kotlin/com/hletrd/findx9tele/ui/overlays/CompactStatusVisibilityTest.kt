package com.hletrd.findx9tele.ui.overlays

import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.PhotoFormats
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
