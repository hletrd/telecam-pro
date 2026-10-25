package me.hletrd.telecampro.camera

import android.util.Size
import me.hletrd.telecampro.ui.ViewModelTestAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The EXPERIMENTAL RAW log route's gates (RawLog.kt) and its accepted-session truth. Robolectric only
 * because a whole CameraCaps carries framework Size/Range values.
 */
@RunWith(RobolectricTestRunner::class)
class RawLogTest {

    // PMA110 camera 2's RAW facts (dumpsys 2026-10-25): 4080x3064 RAW16 at 33.33 ms.
    private val pma110Rear = ViewModelTestAccess.caps().copy(
        supportsRaw = true,
        rawSize = Size(4080, 3064),
        rawLogDevelopable = true,
        rawMinFrameDurationNs = 33_333_333L,
    )

    @Test
    fun `only the Sony pair is developed from RAW`() {
        assertTrue(rawLogTransfer(ColorTransfer.SLOG3))
        assertTrue(rawLogTransfer(ColorTransfer.SLOG3_CINE))
        ColorTransfer.entries.filterNot { it == ColorTransfer.SLOG3 || it == ColorTransfer.SLOG3_CINE }
            .forEach { assertFalse("$it", rawLogTransfer(it)) }
    }

    @Test
    fun `the RAW cadence admits 30 and below and refuses 50 and up`() {
        VideoFrameRate.entries.filterNot { it.highSpeed }.forEach { rate ->
            assertEquals("$rate", rate.fps <= 30, rawStreamSustains(33_333_333L, rate.encoderRate))
        }
        // PMA110 camera 4 advertises 33.3222 ms: same answer.
        assertTrue(rawStreamSustains(33_322_200L, 30.0))
        // "Not available" fails closed.
        assertFalse(rawStreamSustains(0L, 24.0))
    }

    @Test
    fun `the capability gate carries the RAW route laws`() {
        assertTrue(rawLogSupported(pma110Rear, standalone = true, rawRequiresStandalone = true, fps = 30.0))
        assertFalse("routed sub-camera", rawLogSupported(pma110Rear, standalone = false, rawRequiresStandalone = true, fps = 30.0))
        assertTrue("spec device keeps a routed RAW", rawLogSupported(pma110Rear, standalone = false, rawRequiresStandalone = false, fps = 30.0))
        assertFalse("front", rawLogSupported(pma110Rear.copy(lensFacingFront = true), true, true, 30.0))
        assertFalse("not developable", rawLogSupported(pma110Rear.copy(rawLogDevelopable = false), true, true, 30.0))
        assertFalse("no RAW", rawLogSupported(pma110Rear.copy(supportsRaw = false), true, true, 30.0))
        assertFalse("60 fps", rawLogSupported(pma110Rear, true, true, 60.0))
    }

    @Test
    fun `the session wants RAW log only in video with the toggle on`() {
        assertTrue(rawLogSessionWanted(true, ColorTransfer.SLOG3_CINE, enabled = true, supported = true))
        assertFalse(rawLogSessionWanted(false, ColorTransfer.SLOG3_CINE, enabled = true, supported = true))
        assertFalse(rawLogSessionWanted(true, ColorTransfer.SLOG3_CINE, enabled = false, supported = true))
        assertFalse(rawLogSessionWanted(true, ColorTransfer.SLOG3_CINE, enabled = true, supported = false))
        assertFalse(rawLogSessionWanted(true, ColorTransfer.LOGC3, enabled = true, supported = true))
    }

    @Test
    fun `RAW log is its own session shape and wins over native log`() {
        assertEquals(
            VideoSessionShape.RAW_LOG,
            videoSessionShape(true, ColorTransfer.SLOG3, nativeLogCurveSupported = true, rawLogWanted = true),
        )
        assertEquals(
            VideoSessionShape.NATIVE_LOG,
            videoSessionShape(true, ColorTransfer.SLOG3, nativeLogCurveSupported = true, rawLogWanted = false),
        )
    }

    @Test
    fun `the row appears only where it can act`() {
        assertTrue(rawLogSettingVisible(ColorTransfer.SLOG3_CINE, pma110Rear, 29.97))
        assertFalse(rawLogSettingVisible(ColorTransfer.OLOG2, pma110Rear, 29.97))
        assertFalse(rawLogSettingVisible(ColorTransfer.SLOG3, pma110Rear, 59.94))
        assertFalse(rawLogSettingVisible(ColorTransfer.SLOG3, pma110Rear.copy(lensFacingFront = true), 30.0))
    }

    @Test
    fun `a RAW log session is still-less and never HLG, whatever the plan asked`() {
        val hlgPlan = SessionAttemptPlan(useHlg = true, useJpeg = false, useRaw = false)
        val outputs = acceptedPhotoSessionOutputs(
            plan = hlgPlan,
            processedReaderPresent = false,
            rawReaderPresent = false,
            hiResReaderPresent = false,
            rawLogPresent = true,
        )
        assertTrue(outputs.rawLog)
        assertFalse(outputs.hlg)
        assertFalse(outputs.hasStillTarget)
        // Without RAW log the plan's HLG truth is untouched.
        assertTrue(acceptedPhotoSessionOutputs(hlgPlan, false, false, false).hlg)
    }

    @Test
    fun `a missing frame is a whole skipped interval, not jitter`() {
        val period = 33_333_333L
        assertEquals(0, missingFrames(0L, period, period))
        assertEquals(0, missingFrames(period, 2 * period + 10_000_000L, period))
        assertEquals(1, missingFrames(period, 3 * period, period))
        assertEquals(3, missingFrames(period, 5 * period + 1_000_000L, period))
        assertEquals(0, missingFrames(period, period, period))
        assertEquals(0, missingFrames(period, 3 * period, 0L))
    }
}
