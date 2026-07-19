package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSequenceOwnershipTest {
    @Test
    fun `stopped timelapse rejects its late save completion`() {
        val generations = CaptureSequenceGeneration()
        val running = generations.restart()

        generations.stop()

        assertFalse(generations.owns(running))
    }

    @Test
    fun `restarted timelapse owns only the newest run`() {
        val generations = CaptureSequenceGeneration()
        val first = generations.restart()
        val second = generations.restart()

        assertFalse(generations.owns(first))
        assertTrue(generations.owns(second))
    }

    @Test
    fun `photo to video cancels timelapse ownership before a stale callback can fire`() {
        val generations = CaptureSequenceGeneration()
        val photoRun = generations.restart()
        var staleCallbackFires = 0

        if (captureModeTransitionStopsTimelapse(currentVideo = false, targetVideo = true)) {
            generations.stop()
        }
        if (generations.owns(photoRun)) staleCallbackFires++

        assertFalse(generations.owns(photoRun))
        assertEquals(0, staleCallbackFires)
        assertFalse(captureModeTransitionStopsTimelapse(currentVideo = false, targetVideo = false))
        assertFalse(captureModeTransitionStopsTimelapse(currentVideo = true, targetVideo = false))
        assertFalse(captureModeTransitionStopsTimelapse(currentVideo = true, targetVideo = true))
    }

    @Test
    fun `manual aeb step retains newer non bracket controls`() {
        val latest = ManualControls(
            iso = 3200,
            wbKelvin = 4300,
            focusDistanceDiopters = 1.25f,
            shutterMode = ShutterMode.ANGLE,
            exposureTimeNs = 8_000_000L,
        )

        val step = manualAebStepControls(latest, exposureTimeNs = 32_000_000L)

        assertEquals(3200, step.iso)
        assertEquals(4300, step.wbKelvin)
        assertEquals(1.25f, step.focusDistanceDiopters)
        assertEquals(ShutterMode.SPEED, step.shutterMode)
        assertEquals(32_000_000L, step.exposureTimeNs)
    }

    @Test
    fun `auto aeb step retains newer non bracket controls`() {
        val latest = ManualControls(iso = 1600, wbKelvin = 5600, exposureCompensation = 0)

        val step = autoAebStepControls(latest, exposureCompensation = 6)

        assertEquals(1600, step.iso)
        assertEquals(5600, step.wbKelvin)
        assertEquals(6, step.exposureCompensation)
    }
}
