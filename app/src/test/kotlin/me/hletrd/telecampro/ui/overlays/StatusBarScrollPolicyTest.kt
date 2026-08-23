package me.hletrd.telecampro.ui.overlays

import android.util.Size
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatusBarScrollPolicyTest {

    private val base = CameraUiState(
        mode = CaptureMode.VIDEO,
        videoResolution = Size(3840, 2160),
        videoCodec = VideoCodec.HEVC,
    )

    @Test
    fun `telemetry and trailing tag changes do not yank an inspected OSD tail`() {
        val key = statusBarPriorityResetKey(base, "70 mm", compact = false)
        assertEquals(key, statusBarPriorityResetKey(base.copy(recordElapsedMs = 42_000), "70 mm", false))
        assertEquals(key, statusBarPriorityResetKey(base.copy(gammaAssist = true), "70 mm", false))
    }

    @Test
    fun `lens route and encoder truth reset OSD to logical start`() {
        val key = statusBarPriorityResetKey(base, "70 mm", compact = false)
        assertNotEquals(key, statusBarPriorityResetKey(base, "300 mm TELE", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(facing = CameraFacing.FRONT), "70 mm", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(videoCodec = VideoCodec.AVC), "70 mm", false))
    }
}
