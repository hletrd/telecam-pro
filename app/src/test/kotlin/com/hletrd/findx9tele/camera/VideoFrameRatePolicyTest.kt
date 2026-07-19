package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameRatePolicyTest {

    @Test
    fun `video control boundary clamps every app-owned speed to one frame`() {
        for (mode in listOf(ExposureMode.SHUTTER, ExposureMode.ISO, ExposureMode.MANUAL)) {
            val normalized = ManualControls(
                exposureMode = mode,
                exposureTimeNs = 500_000_000L,
                fps = 30,
            ).normalizedForCaptureMode(CaptureMode.VIDEO)

            assertEquals("$mode must preserve 30p", 33_333_333L, normalized.exposureTimeNs)
            assertFalse(normalized.programAppSide)
        }
    }

    @Test
    fun `video timing clamp preserves capability-owned app Program fallback`() {
        val fallback = ManualControls(
            exposureMode = ExposureMode.PROGRAM,
            exposureTimeNs = 500_000_000L,
            fps = 30,
            programAppSide = true,
        ).normalizedForCaptureMode(CaptureMode.VIDEO)

        assertEquals(33_333_333L, fallback.exposureTimeNs)
        assertTrue(fallback.programAppSide)
    }

    @Test
    fun `photo control boundary preserves long exposure`() {
        val controls = ManualControls(
            exposureMode = ExposureMode.MANUAL,
            exposureTimeNs = 2_000_000_000L,
            fps = 30,
            programAppSide = true,
        )

        assertEquals(controls, controls.normalizedForCaptureMode(CaptureMode.PHOTO))
    }

    @Test
    fun `video exposure ceiling follows selected frame rate`() {
        assertEquals(
            41_666_666L,
            exposureUpperBoundForCaptureMode(CaptureMode.VIDEO, fps = 24, sensorUpperNs = 4_000_000_000L),
        )
        assertEquals(
            16_666_666L,
            exposureUpperBoundForCaptureMode(CaptureMode.VIDEO, fps = 60, sensorUpperNs = 4_000_000_000L),
        )
        assertEquals(
            4_000_000_000L,
            exposureUpperBoundForCaptureMode(CaptureMode.PHOTO, fps = 60, sensorUpperNs = 4_000_000_000L),
        )
    }

    @Test
    fun `ISO priority auto shutter stops at the selected video frame interval`() {
        val capNs = exposureUpperBoundForCaptureMode(
            mode = CaptureMode.VIDEO,
            fps = 30,
            sensorUpperNs = 4_000_000_000L,
        )
        val darkFrame = IntArray(256).also { it[10] = 100 }

        assertNull(
            AutoExposure.driveShutterNs(
                luma = darkFrame,
                currentNs = capNs,
                expMinNs = 14_000L,
                expMaxNs = capNs,
                evCompStops = 0f,
            ),
        )
    }

    @Test
    fun `fixed-rate request policy cannot stretch 30p to a two-fps sensor stream`() {
        val timing = sensorRequestTiming(
            fps = 30,
            requestedExposureNs = 500_000_000L,
            maxFrameDurationNs = 4_000_000_000L,
            enforceFrameRate = true,
        )

        assertEquals(33_333_333L, timing.exposureNs)
        assertEquals(33_333_333L, timing.frameDurationNs)
    }

    @Test
    fun `still request policy retains long-exposure frame stretching`() {
        val timing = sensorRequestTiming(
            fps = 30,
            requestedExposureNs = 500_000_000L,
            maxFrameDurationNs = 4_000_000_000L,
            enforceFrameRate = false,
        )

        assertEquals(500_000_000L, timing.exposureNs)
        assertEquals(500_000_000L, timing.frameDurationNs)
    }

    @Test
    fun `invalid fps leaves request exposure unchanged instead of dividing by zero`() {
        val timing = sensorRequestTiming(
            fps = 0,
            requestedExposureNs = 500_000_000L,
            maxFrameDurationNs = 4_000_000_000L,
            enforceFrameRate = true,
        )

        assertEquals(500_000_000L, timing.exposureNs)
        assertEquals(500_000_000L, timing.frameDurationNs)
    }
}
