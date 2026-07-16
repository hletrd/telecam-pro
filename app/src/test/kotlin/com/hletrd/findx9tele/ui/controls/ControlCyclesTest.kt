package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.WbMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared enum tap-cycle orders (ControlCycles.kt). Each cycle is checked two ways:
 *  - explicit per-step transitions (documents the exact order the Fn/dial UI walks), and
 *  - a generic closed-cycle property (repeatedly applying from ANY loop member returns to that
 *    member in `members.size` steps AND visits every member on the way).
 * nextWbMode is the deliberate exception — INCANDESCENT/FLUORESCENT/CUSTOM route to AUTO instead of
 * looping — so its skip-list is pinned separately and the closed cycle covers only the loop members.
 */
class ControlCyclesTest {

    /** A tap-cycle over [members] must be a single closed permutation cycle in the given order. */
    private fun <T> assertClosedCycle(members: List<T>, next: (T) -> T) {
        for (start in members) {
            var cur = start
            val visited = mutableSetOf(cur)
            repeat(members.size) {
                cur = next(cur)
                visited.add(cur)
            }
            assertEquals("cycle from $start must return to start in ${members.size} steps", start, cur)
            assertEquals("cycle from $start must visit every loop member", members.toSet(), visited)
        }
    }

    @Test
    fun exposureModeCycle() {
        assertEquals(ExposureMode.SHUTTER, nextExposureMode(ExposureMode.PROGRAM))
        assertEquals(ExposureMode.ISO, nextExposureMode(ExposureMode.SHUTTER))
        assertEquals(ExposureMode.MANUAL, nextExposureMode(ExposureMode.ISO))
        assertEquals(ExposureMode.PROGRAM, nextExposureMode(ExposureMode.MANUAL))
        assertClosedCycle(ExposureMode.entries, ::nextExposureMode)
    }

    @Test
    fun focusModeCycle() {
        assertEquals(FocusMode.AUTO, nextFocusMode(FocusMode.CONTINUOUS))
        assertEquals(FocusMode.MANUAL, nextFocusMode(FocusMode.AUTO))
        assertEquals(FocusMode.MACRO, nextFocusMode(FocusMode.MANUAL))
        assertEquals(FocusMode.CONTINUOUS, nextFocusMode(FocusMode.MACRO))
        assertClosedCycle(FocusMode.entries, ::nextFocusMode)
    }

    @Test
    fun wbModeLoopAndSkips() {
        // The loop the Fn cycle walks.
        assertEquals(WbMode.DAYLIGHT, nextWbMode(WbMode.AUTO))
        assertEquals(WbMode.CLOUDY, nextWbMode(WbMode.DAYLIGHT))
        assertEquals(WbMode.SHADE, nextWbMode(WbMode.CLOUDY))
        assertEquals(WbMode.MANUAL, nextWbMode(WbMode.SHADE))
        assertEquals(WbMode.AUTO, nextWbMode(WbMode.MANUAL))
        // Skip-list: these are entered only by other means (presets / "Capture Custom WB"); the Fn
        // cycle steps PAST them back to AUTO rather than looping through them.
        assertEquals(WbMode.AUTO, nextWbMode(WbMode.INCANDESCENT))
        assertEquals(WbMode.AUTO, nextWbMode(WbMode.FLUORESCENT))
        assertEquals(WbMode.AUTO, nextWbMode(WbMode.CUSTOM))
        // Closed cycle over the loop members only.
        assertClosedCycle(
            listOf(WbMode.AUTO, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE, WbMode.MANUAL),
            ::nextWbMode,
        )
    }

    @Test
    fun videoStabModeCycle() {
        assertEquals(VideoStabMode.STANDARD, nextVideoStabMode(VideoStabMode.OFF))
        assertEquals(VideoStabMode.ENHANCED, nextVideoStabMode(VideoStabMode.STANDARD))
        assertEquals(VideoStabMode.OFF, nextVideoStabMode(VideoStabMode.ENHANCED))
        assertClosedCycle(VideoStabMode.entries, ::nextVideoStabMode)
    }

    @Test
    fun driveModeCycle() {
        assertEquals(DriveMode.BURST, nextDriveMode(DriveMode.SINGLE))
        assertEquals(DriveMode.AEB, nextDriveMode(DriveMode.BURST))
        assertEquals(DriveMode.TIMELAPSE, nextDriveMode(DriveMode.AEB))
        assertEquals(DriveMode.SINGLE, nextDriveMode(DriveMode.TIMELAPSE))
        assertClosedCycle(DriveMode.entries, ::nextDriveMode)
    }

    @Test
    fun meteringModeCycle() {
        assertEquals(MeteringMode.CENTER, nextMeteringMode(MeteringMode.MATRIX))
        assertEquals(MeteringMode.SPOT, nextMeteringMode(MeteringMode.CENTER))
        assertEquals(MeteringMode.MATRIX, nextMeteringMode(MeteringMode.SPOT))
        assertClosedCycle(MeteringMode.entries, ::nextMeteringMode)
    }

    @Test
    fun transferCycle() {
        assertEquals(ColorTransfer.LOG, nextTransfer(ColorTransfer.HLG))
        assertEquals(ColorTransfer.SDR, nextTransfer(ColorTransfer.LOG))
        assertEquals(ColorTransfer.HLG, nextTransfer(ColorTransfer.SDR))
        assertClosedCycle(ColorTransfer.entries, ::nextTransfer)
    }

    @Test
    fun audioSceneCycle() {
        assertEquals(AudioScene.SOUND_FOCUS, nextAudioScene(AudioScene.STANDARD))
        assertEquals(AudioScene.SOUND_STAGE, nextAudioScene(AudioScene.SOUND_FOCUS))
        assertEquals(AudioScene.STANDARD, nextAudioScene(AudioScene.SOUND_STAGE))
        assertClosedCycle(AudioScene.entries, ::nextAudioScene)
    }

    @Test
    fun gridTypeCycle() {
        assertEquals(GridType.THIRDS, nextGridType(GridType.NONE))
        assertEquals(GridType.GOLDEN, nextGridType(GridType.THIRDS))
        assertEquals(GridType.SQUARE, nextGridType(GridType.GOLDEN))
        assertEquals(GridType.CENTER, nextGridType(GridType.SQUARE))
        assertEquals(GridType.NONE, nextGridType(GridType.CENTER))
        assertClosedCycle(GridType.entries, ::nextGridType)
    }

    @Test
    fun frameLineCycle() {
        assertEquals(FrameLineType.CINEMA, nextFrameLine(FrameLineType.OFF))
        assertEquals(FrameLineType.SQUARE, nextFrameLine(FrameLineType.CINEMA))
        assertEquals(FrameLineType.VERTICAL, nextFrameLine(FrameLineType.SQUARE))
        assertEquals(FrameLineType.OFF, nextFrameLine(FrameLineType.VERTICAL))
        assertClosedCycle(FrameLineType.entries, ::nextFrameLine)
    }

    @Test
    fun flashModeCycle() {
        assertEquals(FlashMode.AUTO, nextFlashMode(FlashMode.OFF))
        assertEquals(FlashMode.ON, nextFlashMode(FlashMode.AUTO))
        assertEquals(FlashMode.TORCH, nextFlashMode(FlashMode.ON))
        assertEquals(FlashMode.OFF, nextFlashMode(FlashMode.TORCH))
        assertClosedCycle(FlashMode.entries, ::nextFlashMode)
    }

    @Test
    fun timerCycle() {
        assertEquals(ShutterTimer.SEC3, nextTimer(ShutterTimer.OFF))
        assertEquals(ShutterTimer.SEC10, nextTimer(ShutterTimer.SEC3))
        assertEquals(ShutterTimer.OFF, nextTimer(ShutterTimer.SEC10))
        assertClosedCycle(ShutterTimer.entries, ::nextTimer)
    }

    @Test
    fun aspectCycle() {
        assertEquals(AspectRatio.W16_9, nextAspect(AspectRatio.W4_3))
        assertEquals(AspectRatio.W4_3, nextAspect(AspectRatio.W16_9))
        assertClosedCycle(AspectRatio.entries, ::nextAspect)
    }

    // ---- AE readout text + EV derivation ----

    @Test
    fun autoShutterText_livePresentInAuto_elseManualField_elseDashes() {
        // Fresh state: auto exposure (PROGRAM, not app-side) with no AE result yet → dashes.
        assertEquals("--", autoShutterText(CameraUiState()))
        // Auto with a live AE-resolved value → that value, formatted.
        val auto = CameraUiState(liveExposureNs = 4_000_000L)
        assertEquals(formatShutterSpeed(4_000_000L), autoShutterText(auto))
        // Manual mode reads the manual field, NOT the (ignored) live value.
        val manual = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.MANUAL, exposureTimeNs = 8_000_000L),
            liveExposureNs = 999_999L,
        )
        assertEquals(formatShutterSpeed(8_000_000L), autoShutterText(manual))
    }

    @Test
    fun autoIsoText_livePresentInAuto_elseManualField_elseDashes() {
        assertEquals("--", autoIsoText(CameraUiState()))
        assertEquals("800", autoIsoText(CameraUiState(liveIso = 800)))
        val manual = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.MANUAL, iso = 1600),
            liveIso = 800,
        )
        assertEquals("1600", autoIsoText(manual))
    }

    @Test
    fun evCompStops_nullCapsUsesThirdStopFallback() {
        // caps is null (default state) → the conventional 1/3-stop fallback, scaled by the comp count.
        assertEquals(1f / 3f, evCompStops(CameraUiState(controls = ManualControls(exposureCompensation = 1))), 1e-6f)
        assertEquals(1f, evCompStops(CameraUiState(controls = ManualControls(exposureCompensation = 3))), 1e-6f)
        assertEquals(0f, evCompStops(CameraUiState()), 1e-6f)
    }

    @Test
    fun exposureMeterCompensationEv_usesAlreadyScaledSignedStopsOnce() {
        assertEquals(
            1f,
            exposureMeterCompensationEv(
                CameraUiState(controls = ManualControls(exposureCompensation = 3)),
            ),
            1e-6f,
        )
        assertEquals(
            -1f / 3f,
            exposureMeterCompensationEv(
                CameraUiState(controls = ManualControls(exposureCompensation = -1)),
            ),
            1e-6f,
        )
        assertEquals(0f, exposureMeterCompensationEv(CameraUiState()), 1e-6f)
    }

    @Test
    fun exposureCompensationStops_honorsNonThirdHardwareStepAndMeterClamp() {
        assertEquals(
            1f,
            exposureCompensationStops(index = 2, stepNumerator = 1, stepDenominator = 2),
            1e-6f,
        )
        assertEquals(
            -1.5f,
            exposureCompensationStops(index = -3, stepNumerator = 1, stepDenominator = 2),
            1e-6f,
        )
        assertEquals(
            4f,
            exposureCompensationStops(index = 12, stepNumerator = null, stepDenominator = null),
            1e-6f,
        )
        assertEquals(
            3f,
            exposureMeterCompensationEv(
                CameraUiState(controls = ManualControls(exposureCompensation = 12)),
            ),
            1e-6f,
        )
    }
}
