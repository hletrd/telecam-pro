package com.hletrd.findx9tele.ui

import androidx.compose.ui.semantics.Role
import com.hletrd.findx9tele.camera.AfIndication
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.normalizeFnSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiPolicyTest {
    @Test
    fun `standby meter owns mic only while armed Video level is visible`() {
        fun allowed(
            started: Boolean = true,
            visible: Boolean = true,
            mode: com.hletrd.findx9tele.camera.CaptureMode =
                com.hletrd.findx9tele.camera.CaptureMode.VIDEO,
            audio: Boolean = true,
            recording: Boolean = false,
        ) = standbyAudioMeterShouldRun(started, visible, mode, audio, recording)

        assertTrue(allowed())
        assertFalse(allowed(visible = false))
        assertFalse(allowed(started = false))
        assertFalse(allowed(mode = com.hletrd.findx9tele.camera.CaptureMode.PHOTO))
        assertFalse(allowed(audio = false))
        assertFalse(allowed(recording = true))
    }

    @Test
    fun `late recording refusal after stop cannot reacquire the standby microphone`() {
        // A recorder-executor refusal is reconciled on main after onStop. Its refreshed state uses
        // the latest lifecycle/visibility snapshot, even though recording has just become false.
        assertFalse(
            standbyAudioMeterShouldRun(
                lifecycleStarted = false,
                visible = true,
                mode = com.hletrd.findx9tele.camera.CaptureMode.VIDEO,
                recordAudio = true,
                recording = false,
            ),
        )
    }

    @Test
    fun `late recording refusal cannot reset a newer optimistic attempt`() {
        fun owns(current: Long, expected: Long, recording: Boolean = true, starting: Boolean = true) =
            recordingAttemptOwnsGeneration(current, expected, recording, starting)

        assertTrue(owns(current = 7, expected = 7))
        assertFalse(owns(current = 8, expected = 7)) // stopped attempt A
        assertFalse(owns(current = 9, expected = 7)) // newer start B
        assertFalse(owns(current = 7, expected = 7, recording = false)) // already idle
        assertFalse(owns(current = 7, expected = 7, starting = false)) // already started
    }

    @Test
    fun `Fn normalization is distinct bounded and falls back from empty input`() {
        val nine = listOf(
            com.hletrd.findx9tele.camera.FnSlot.ISO,
            com.hletrd.findx9tele.camera.FnSlot.WB,
            com.hletrd.findx9tele.camera.FnSlot.EV,
            com.hletrd.findx9tele.camera.FnSlot.FOCUS,
            com.hletrd.findx9tele.camera.FnSlot.SHUTTER,
            com.hletrd.findx9tele.camera.FnSlot.EXPOSURE_MODE,
            com.hletrd.findx9tele.camera.FnSlot.TRANSFER,
            com.hletrd.findx9tele.camera.FnSlot.STABILIZATION,
            com.hletrd.findx9tele.camera.FnSlot.AUDIO_SCENE,
            com.hletrd.findx9tele.camera.FnSlot.ISO,
        )
        val fallback = com.hletrd.findx9tele.camera.FnSlot.PHOTO_DEFAULT

        assertEquals(nine.distinct().take(8), normalizeFnSlots(nine, fallback))
        assertEquals(fallback, normalizeFnSlots(emptyList(), fallback))
    }

    @Test
    fun `active TELE reveal waits for a finite measured trailing scroll edge`() {
        assertNull(topBarTeleRevealTarget(active = false, maxScroll = 120))
        assertNull(topBarTeleRevealTarget(active = true, maxScroll = 0))
        assertNull(topBarTeleRevealTarget(active = true, maxScroll = Int.MAX_VALUE))
        assertEquals(120, topBarTeleRevealTarget(active = true, maxScroll = 120))
    }

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
    fun `every actual focus mode change clears held or pending tap state`() {
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

    @Test
    fun `new submitted tap starts scanning instead of inheriting the previous verdict`() {
        for (previous in listOf(AfIndication.FOCUSED, AfIndication.FAILED)) {
            val updated = submittedTapFocusUiState(
                CameraUiState(afIndication = previous),
                0.25f to 0.75f,
            )
            assertEquals(0.25f to 0.75f, updated.tapPoint)
            assertTrue(updated.tapFocusHeld)
            assertEquals(AfIndication.SCANNING, updated.afIndication)
        }

        val locked = submittedTapFocusUiState(
            CameraUiState(
                controls = ManualControls(afLock = true),
                afIndication = AfIndication.FAILED,
            ),
            0.5f to 0.5f,
        )
        assertEquals(AfIndication.IDLE, locked.afIndication)
        assertTrue(locked.tapFocusHeld)
    }
}
