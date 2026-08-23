package me.hletrd.telecampro.ui.overlays

import android.util.Size
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.FocusConfidenceSource
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoStabMode
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
        assertEquals(key, statusBarPriorityResetKey(base.copy(audioLevels = listOf(0.2f, 0.7f)), "70 mm", false))
        assertEquals(
            key,
            statusBarPriorityResetKey(
                base.copy(
                    focusConfidence = FocusConfidenceSource.FRAME_DETAIL,
                    controls = base.controls.copy(meteringMode = MeteringMode.SPOT, aeLock = true),
                    punchIn = true,
                ),
                "70 mm",
                false,
            ),
        )
    }

    @Test
    fun `lens route and every leading video output tag reset OSD to logical start`() {
        val key = statusBarPriorityResetKey(base, "70 mm", compact = false)
        assertNotEquals(key, statusBarPriorityResetKey(base, "300 mm TELE", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(facing = CameraFacing.FRONT), "70 mm", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(videoCodec = VideoCodec.AVC), "70 mm", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(transfer = ColorTransfer.SDR), "70 mm", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(openGate = true), "70 mm", false))
        assertNotEquals(key, statusBarPriorityResetKey(base.copy(recordAudio = false), "70 mm", false))
        assertNotEquals(
            key,
            statusBarPriorityResetKey(base.copy(videoStabMode = VideoStabMode.STANDARD), "70 mm", false),
        )
    }

    @Test
    fun `visible gamma assist is priority identity but an inapplicable assist is not`() {
        val log = base.copy(transfer = ColorTransfer.SLOG3_CINE)
        val logKey = statusBarPriorityResetKey(log, "70 mm", compact = false)
        assertNotEquals(logKey, statusBarPriorityResetKey(log.copy(gammaAssist = true), "70 mm", false))

        val hlg = base.copy(transfer = ColorTransfer.HLG)
        assertEquals(
            statusBarPriorityResetKey(hlg, "70 mm", compact = false),
            statusBarPriorityResetKey(hlg.copy(gammaAssist = true), "70 mm", compact = false),
        )
    }
}
