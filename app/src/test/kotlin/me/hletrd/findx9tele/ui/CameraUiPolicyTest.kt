package me.hletrd.findx9tele.ui

import androidx.compose.ui.semantics.Role
import me.hletrd.findx9tele.camera.AfIndication
import me.hletrd.findx9tele.camera.AspectRatio
import me.hletrd.findx9tele.camera.CameraUiState
import me.hletrd.findx9tele.camera.FlashMode
import me.hletrd.findx9tele.camera.FocusConfidenceSource
import me.hletrd.findx9tele.camera.GridType
import me.hletrd.findx9tele.camera.LensChoice
import me.hletrd.findx9tele.camera.FocusMode
import me.hletrd.findx9tele.camera.ManualControls
import me.hletrd.findx9tele.camera.ShutterTimer
import me.hletrd.findx9tele.camera.normalizeFnSlots
import me.hletrd.findx9tele.focus.focusConfidenceLabel
import me.hletrd.findx9tele.ui.controls.proSheetUsesSideLayout
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
            mode: me.hletrd.findx9tele.camera.CaptureMode =
                me.hletrd.findx9tele.camera.CaptureMode.VIDEO,
            audio: Boolean = true,
            recording: Boolean = false,
        ) = standbyAudioMeterShouldRun(started, visible, mode, audio, recording)

        assertTrue(allowed())
        assertFalse(allowed(visible = false))
        assertFalse(allowed(started = false))
        assertFalse(allowed(mode = me.hletrd.findx9tele.camera.CaptureMode.PHOTO))
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
                mode = me.hletrd.findx9tele.camera.CaptureMode.VIDEO,
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
            me.hletrd.findx9tele.camera.FnSlot.ISO,
            me.hletrd.findx9tele.camera.FnSlot.WB,
            me.hletrd.findx9tele.camera.FnSlot.EV,
            me.hletrd.findx9tele.camera.FnSlot.FOCUS,
            me.hletrd.findx9tele.camera.FnSlot.SHUTTER,
            me.hletrd.findx9tele.camera.FnSlot.EXPOSURE_MODE,
            me.hletrd.findx9tele.camera.FnSlot.TRANSFER,
            me.hletrd.findx9tele.camera.FnSlot.STABILIZATION,
            me.hletrd.findx9tele.camera.FnSlot.AUDIO_SCENE,
            me.hletrd.findx9tele.camera.FnSlot.ISO,
        )
        val fallback = me.hletrd.findx9tele.camera.FnSlot.PHOTO_DEFAULT

        assertEquals(nine.distinct().take(8), normalizeFnSlots(nine, fallback))
        assertEquals(fallback, normalizeFnSlots(emptyList(), fallback))
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

    // previewTopPx: the 4:3 preview biases up just enough to clear the rest-state bottom cluster;
    // 16:9 (which can never clear it) keeps the centered placement; degenerate falls back to center.
    // Numbers are the PMA110 portrait window (1440x3168) with a ~742px cluster and ~460px top floor.

    @Test
    fun `four by three preview clears the bottom cluster`() {
        val top = previewTopPx(
            availableHeightPx = 3168,
            previewHeightPx = 1920,
            topChromeMinPx = 460,
            bottomReservePx = 742,
        )
        assertEquals(3168 - 742 - 1920, top)          // 506: preview bottom == cluster top
        assertTrue(top < (3168 - 1920) / 2)           // above center, below the chrome floor
        assertTrue(top >= 460)
    }

    @Test
    fun `sixteen by nine preview stays centered when it cannot clear`() {
        val top = previewTopPx(
            availableHeightPx = 3168,
            previewHeightPx = 2560,
            topChromeMinPx = 460,
            bottomReservePx = 742,
        )
        assertEquals((3168 - 2560) / 2, top)          // clearing would need top < chrome floor
    }

    @Test
    fun `oversized preview falls back to the centered position`() {
        val top = previewTopPx(
            availableHeightPx = 2000,
            previewHeightPx = 1990,
            topChromeMinPx = 460,
            bottomReservePx = 742,
        )
        assertEquals((2000 - 1990) / 2, top)
    }

    @Test
    fun `unmeasured cluster keeps the preview at or above center never below`() {
        val top = previewTopPx(
            availableHeightPx = 3168,
            previewHeightPx = 1920,
            topChromeMinPx = 460,
            bottomReservePx = 0,
        )
        assertEquals((3168 - 1920) / 2, top)          // no reserve yet -> centered, not sunk
    }

    @Test
    fun `frame-detail math is armed only where the tag could ever appear`() {
        // Stable, route-level refusals only. The per-frame gates (mid-scan, zoom gesture, stale
        // stats, exposure) stay in frameDefocusCandidate, because a frame failing one of those is
        // still worth computing — the next frame may pass.
        assertTrue(focusDetailAnalysisRequired(FocusMode.CONTINUOUS, recording = false, recordingStarting = false))
        assertTrue(focusDetailAnalysisRequired(FocusMode.AUTO, recording = false, recordingStarting = false))
        assertTrue(focusDetailAnalysisRequired(FocusMode.MACRO, recording = false, recordingStarting = false))
        assertFalse("the user owns focus in MANUAL and pays nothing", focusDetailAnalysisRequired(FocusMode.MANUAL, false, false))
        assertFalse(focusDetailAnalysisRequired(FocusMode.CONTINUOUS, recording = true, recordingStarting = false))
        assertFalse(focusDetailAnalysisRequired(FocusMode.CONTINUOUS, recording = false, recordingStarting = true))
    }

    @Test
    fun `the focus-confidence OSD tag renders only what its proof supports`() {
        fun tag(state: CameraUiState) = focusConfidenceLabel(state.focusConfidence, state.macroCloserLensLabel)
        assertNull("no proof, no tag", tag(CameraUiState()))
        assertEquals(
            "TOO CLOSE",
            tag(CameraUiState(focusConfidence = FocusConfidenceSource.AF_LIMIT)),
        )
        assertEquals(
            "TOO CLOSE ▸ 1×",
            tag(CameraUiState(focusConfidence = FocusConfidenceSource.AF_LIMIT, macroCloserLensLabel = "1×")),
        )
        assertEquals(
            "SOFT",
            tag(CameraUiState(focusConfidence = FocusConfidenceSource.FRAME_DETAIL)),
        )
        assertEquals(
            "the detail proof cannot recommend a distance remedy",
            "SOFT",
            tag(CameraUiState(focusConfidence = FocusConfidenceSource.FRAME_DETAIL, macroCloserLensLabel = "1×")),
        )
    }

    @Test
    fun `compact DISP keeps every top-bar toggle whose state is not the default`() {
        // Full DISP draws all four regardless; compact keeps only non-default state (UX_POLICY).
        assertTrue(chromeToggleVisible(compact = false, isDefault = true))
        assertTrue(chromeToggleVisible(compact = false, isDefault = false))
        assertFalse(chromeToggleVisible(compact = true, isDefault = true))
        assertTrue(chromeToggleVisible(compact = true, isDefault = false))

        // The miss this predicate exists to make testable: GRID shipped as a bare `!compact` while its
        // three row siblings carried the second clause, so an active grid — the one chrome state that
        // paints on the live image — had no control at all in the preview-first finder.
        assertTrue(chromeToggleVisible(compact = true, isDefault = GridType.THIRDS == GridType.NONE))
        assertTrue(chromeToggleVisible(compact = true, isDefault = GridType.CENTER == GridType.NONE))
        assertFalse(chromeToggleVisible(compact = true, isDefault = GridType.NONE == GridType.NONE))

        // Same predicate, same answers, for the three that already behaved.
        assertTrue(chromeToggleVisible(compact = true, isDefault = FlashMode.TORCH == FlashMode.OFF))
        assertFalse(chromeToggleVisible(compact = true, isDefault = FlashMode.OFF == FlashMode.OFF))
        assertTrue(chromeToggleVisible(compact = true, isDefault = ShutterTimer.SEC10 == ShutterTimer.OFF))
        assertFalse(chromeToggleVisible(compact = true, isDefault = ShutterTimer.OFF == ShutterTimer.OFF))
        assertTrue(chromeToggleVisible(compact = true, isDefault = AspectRatio.W16_9 == AspectRatio.W4_3))
        assertFalse(chromeToggleVisible(compact = true, isDefault = AspectRatio.W4_3 == AspectRatio.W4_3))
    }

    @Test
    fun `settings sheet selects a side panel only in landscape`() {
        // (Moved here from SettingSemanticsTest, which is an accessibility file — a layout-policy
        // assertion parked there is part of why that surface read as covered.)
        assertTrue(proSheetUsesSideLayout(900f, 400f))
        assertFalse(proSheetUsesSideLayout(400f, 900f))
        assertFalse(proSheetUsesSideLayout(500f, 500f))
    }

    @Test
    fun `the self-timer countdown speaks a correct plural on its final second`() {
        // The countdown node is a 1 Hz Polite live region, so the last tick of EVERY timer was spoken:
        // "1 seconds remaining". The overlay and the shutter button read the same helper, so they
        // cannot disagree about one second.
        assertEquals("1 second remaining", timerCountdownDescription(1))
        assertEquals("2 seconds remaining", timerCountdownDescription(2))
        assertEquals("3 seconds remaining", timerCountdownDescription(3))
        assertEquals("10 seconds remaining", timerCountdownDescription(10))
    }

    @Test
    fun `the status pill speaks battery and remaining media in words`() {
        // Photo: a bare integer names nothing aloud, and "9999+" is the saturation token.
        assertEquals(
            "Battery 72 percent, 1234 shots remaining",
            statusInfoDescription(batteryPct = 72, remaining = "1234", video = false),
        )
        assertEquals(
            "Battery 5 percent, Over 9999 shots remaining",
            statusInfoDescription(batteryPct = 5, remaining = "9999+", video = false),
        )
        assertEquals(
            "Battery 100 percent, 1 shot remaining",
            statusInfoDescription(batteryPct = 100, remaining = "1", video = false),
        )

        // Video: "45m" is a distance aloud, and the hour branches must not be read as glyphs.
        assertEquals(
            "Battery 72 percent, 45 minutes remaining",
            statusInfoDescription(batteryPct = 72, remaining = "45m", video = true),
        )
        assertEquals(
            "Battery 72 percent, 9 hours 30 minutes remaining",
            statusInfoDescription(batteryPct = 72, remaining = "9h30m", video = true),
        )
        assertEquals(
            "Battery 72 percent, Over 9 hours remaining",
            statusInfoDescription(batteryPct = 72, remaining = "9h+", video = true),
        )
        assertEquals(
            "Battery 72 percent, 1 minute remaining",
            statusInfoDescription(batteryPct = 72, remaining = "1m", video = true),
        )
        // A whole-hour token drops the empty minute clause rather than saying "0 minutes".
        assertEquals(
            "Battery 72 percent, 1 hour remaining",
            statusInfoDescription(batteryPct = 72, remaining = "1h0m", video = true),
        )
    }

    @Test
    fun `the status pill speaks only the facts it actually has`() {
        // The pill hides each half independently (no battery telemetry yet; unknown free space), so the
        // spoken form must not emit a dangling separator or a fact it does not hold.
        assertEquals("42 shots remaining", statusInfoDescription(batteryPct = -1, remaining = "42", video = false))
        assertEquals("Battery 42 percent", statusInfoDescription(batteryPct = 42, remaining = null, video = false))
        // An unrecognized token degrades to the battery fact instead of speaking punctuation.
        assertEquals("Battery 42 percent", statusInfoDescription(batteryPct = 42, remaining = "??", video = true))
        assertEquals("Battery 42 percent", statusInfoDescription(batteryPct = 42, remaining = "??", video = false))
    }
}
