package me.hletrd.telecampro.ui.overlays

import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.FocusConfidenceSource
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PhotoFormats
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
            // "photo stabilization disabled" moved to its own test below: the clause is now
            // capability-gated (oisOffTagVisible), and CameraUiState's default caps == null means
            // this bare copy can no longer force the strip — that is the fix, not a regression.
            "lock" to base.copy(controls = ManualControls(aeLock = true)),
            "video transfer" to base.copy(mode = CaptureMode.VIDEO, transfer = ColorTransfer.HLG),
            "muted video" to base.copy(mode = CaptureMode.VIDEO, recordAudio = false),
            "open gate" to base.copy(mode = CaptureMode.VIDEO, openGate = true),
            // Both proofs of the focus-confidence tag: the compact strip is the DEFAULT viewfinder,
            // so a tag that only shows when another tag is up is a tag that never shows.
            "focus confidence (AF limit)" to base.copy(focusConfidence = FocusConfidenceSource.AF_LIMIT),
            "focus confidence (frame detail)" to base.copy(focusConfidence = FocusConfidenceSource.FRAME_DETAIL),
            // Facing is the one state that changes what the app is for; rear stays untagged.
            "front camera" to base.copy(facing = CameraFacing.FRONT, activeCameraRoute = CameraRoute.FRONT),
            "external camera" to base.copy(activeCameraRoute = CameraRoute.EXTERNAL),
        )
        cases.forEach { (label, state) -> assertTrue(label, compactShootingStatusVisible(state)) }
    }

    @Test
    fun `ois off forces the strip only where OIS is actually controllable`() {
        // The strip clause and the OIS OFF tag share one gate (verification S5): a persisted
        // oisEnabled=false preference on a route with NO OIS control (or with caps still null
        // mid-reopen) must not force the compact strip for a tag the render side suppresses.
        assertTrue(oisOffTagVisible(photoMode = true, oisAvailable = true, oisEnabled = false))
        assertFalse(oisOffTagVisible(photoMode = true, oisAvailable = false, oisEnabled = false))
        assertFalse(oisOffTagVisible(photoMode = false, oisAvailable = true, oisEnabled = false))
        assertFalse(oisOffTagVisible(photoMode = true, oisAvailable = true, oisEnabled = true))
        // The whole-state form with default (null) caps: no longer forced visible.
        assertFalse(
            compactShootingStatusVisible(
                CameraUiState().copy(controls = ManualControls(oisEnabled = false)),
            ),
        )
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
