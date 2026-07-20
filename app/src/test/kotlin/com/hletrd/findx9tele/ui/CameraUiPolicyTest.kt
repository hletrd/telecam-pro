package com.hletrd.findx9tele.ui

import androidx.compose.ui.semantics.Role
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.FocusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiPolicyTest {
    @Test
    fun `mode carousel exposes one mutually exclusive radio choice`() {
        val selected = modeCarouselState(active = true, enabled = true)
        assertTrue(selected.selected)
        assertTrue(selected.enabled)
        assertEquals("Selected", selected.stateDescription)
        assertEquals(Role.RadioButton, selected.accessibilityRole)

        val locked = modeCarouselState(active = false, enabled = false)
        assertFalse(locked.selected)
        assertFalse(locked.enabled)
        assertEquals("Not selected", locked.stateDescription)
        assertEquals(Role.RadioButton, locked.accessibilityRole)
    }

    @Test
    fun `an actual focus mode change releases the tap owned point`() {
        assertTrue(focusModeChangeClearsTapPoint(FocusMode.CONTINUOUS, FocusMode.MANUAL))
        assertTrue(focusModeChangeClearsTapPoint(FocusMode.AUTO, FocusMode.CONTINUOUS))
        assertFalse(focusModeChangeClearsTapPoint(FocusMode.MACRO, FocusMode.MACRO))
    }

    @Test
    fun `focal rail exposes selection converter reconfiguration and REC truth`() {
        val selected = focalRailState(LensChoice.TELE3X, LensChoice.TELE3X, true, true, false)
        assertTrue(selected.selected)
        assertTrue(selected.enabled)
        assertEquals("Selected; teleconverter on", selected.stateDescription)
        assertEquals(Role.RadioButton, selected.accessibilityRole)

        val unselected = focalRailState(LensChoice.MAIN, LensChoice.TELE3X, true, true, false)
        assertFalse(unselected.selected)
        assertEquals("Not selected", unselected.stateDescription)

        val reconfiguring = focalRailState(LensChoice.MAIN, LensChoice.MAIN, false, false, false)
        assertFalse(reconfiguring.enabled)
        assertEquals("Camera reconfiguring", reconfiguring.stateDescription)

        val recording = focalRailState(LensChoice.MAIN, LensChoice.MAIN, false, true, true)
        assertFalse(recording.enabled)
        assertEquals("Unavailable while recording", recording.stateDescription)
    }

    @Test
    fun `photo shutter countdown activation dispatches only cancel`() {
        var cancelCalls = 0
        var fireCalls = 0
        var startedAt: Int? = null

        dispatchPhotoShutter(
            countdownSeconds = 2,
            // Cancellation has priority even if readiness falls during the timer.
            stillCaptureReady = false,
            configuredDelaySeconds = 3,
            cancelCountdown = { cancelCalls += 1 },
            fireShutter = { fireCalls += 1 },
            startCountdown = { startedAt = it },
        )

        assertEquals(1, cancelCalls)
        assertEquals(0, fireCalls)
        assertEquals(null, startedAt)
    }

    @Test
    fun `photo shutter dispatches immediate unavailable and delayed paths exactly once`() {
        var fireCalls = 0
        var startedAt: Int? = null

        dispatchPhotoShutter(
            countdownSeconds = 0,
            stillCaptureReady = false,
            configuredDelaySeconds = 10,
            cancelCountdown = { error("No countdown exists") },
            fireShutter = { fireCalls += 1 },
            startCountdown = { error("An unavailable still target cannot start a timer") },
        )
        assertEquals(1, fireCalls)

        dispatchPhotoShutter(
            countdownSeconds = 0,
            stillCaptureReady = true,
            configuredDelaySeconds = 0,
            cancelCountdown = { error("No countdown exists") },
            fireShutter = { fireCalls += 1 },
            startCountdown = { error("A zero-delay shutter must fire immediately") },
        )
        assertEquals(2, fireCalls)

        dispatchPhotoShutter(
            countdownSeconds = 0,
            stillCaptureReady = true,
            configuredDelaySeconds = 3,
            cancelCountdown = { error("No countdown exists") },
            fireShutter = { error("A configured timer must not fire immediately") },
            startCountdown = { startedAt = it },
        )
        assertEquals(3, startedAt)
    }

    @Test
    fun `recording snapshot ignores the Photo self timer`() {
        assertEquals(0, photoShutterDelaySeconds(configuredDelaySeconds = 10, recording = true))
        assertEquals(10, photoShutterDelaySeconds(configuredDelaySeconds = 10, recording = false))
    }
}
