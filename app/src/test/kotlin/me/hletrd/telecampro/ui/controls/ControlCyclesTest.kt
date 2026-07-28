package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoStabMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared enum tap-cycle orders (ControlCycles.kt). Each cycle is checked two ways:
 *  - explicit per-step transitions (documents the exact order the Fn/dial UI walks), and
 *  - a generic closed-cycle property (repeatedly applying from ANY loop member returns to that
 *    member in `members.size` steps AND visits every member on the way).
 * Only the FIXED-ORDER cycles live here. The capability-projected enums (exposure mode, focus, WB,
 * metering, flash) are walked by nextAvailable over the route's advertised list, and their pinned
 * order is whatever ControlAvailability builds — the hardcoded next* twins that used to be tested
 * here shipped nowhere and documented an order the app did not implement.
 */
class ControlCyclesTest {

    @Test
    fun nextAvailableNeverLeavesSparseSupport() {
        val sparse = listOf(ExposureMode.PROGRAM, ExposureMode.MANUAL)
        for (start in ExposureMode.entries) {
            assertEquals(true, nextAvailable(start, sparse) in sparse)
        }
        assertEquals(ExposureMode.MANUAL, nextAvailable(ExposureMode.PROGRAM, sparse))
        assertEquals(ExposureMode.PROGRAM, nextAvailable(ExposureMode.MANUAL, sparse))
        assertEquals(ExposureMode.PROGRAM, nextAvailable(ExposureMode.SHUTTER, sparse))
        assertEquals(ExposureMode.SHUTTER, nextAvailable(ExposureMode.SHUTTER, emptyList()))
    }

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
    fun transferCycle() {
        assertEquals(ColorTransfer.SLOG3, nextTransfer(ColorTransfer.HLG))
        assertEquals(ColorTransfer.SLOG3_CINE, nextTransfer(ColorTransfer.SLOG3))
        assertEquals(ColorTransfer.LOGC3, nextTransfer(ColorTransfer.SLOG3_CINE))
        assertEquals(ColorTransfer.SDR, nextTransfer(ColorTransfer.LOGC3))
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

    @Test
    fun `video narrows the flash button to the lamp and reads a leftover metering mode as off`() {
        val advertised = listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH)
        assertEquals(advertised, flashChoicesFor(CaptureMode.PHOTO, advertised))
        assertEquals(
            listOf(FlashMode.OFF, FlashMode.TORCH),
            flashChoicesFor(CaptureMode.VIDEO, advertised),
        )
        // A route with no lamp offers nothing to cycle in video, so the button stays disabled.
        assertEquals(
            listOf(FlashMode.OFF),
            flashChoicesFor(CaptureMode.VIDEO, listOf(FlashMode.OFF)),
        )
        // controls.flash survives the mode switch: AUTO/ON must READ as off in video, TORCH as torch.
        assertEquals(FlashMode.OFF, flashDisplayMode(CaptureMode.VIDEO, FlashMode.AUTO))
        assertEquals(FlashMode.OFF, flashDisplayMode(CaptureMode.VIDEO, FlashMode.ON))
        assertEquals(FlashMode.TORCH, flashDisplayMode(CaptureMode.VIDEO, FlashMode.TORCH))
        assertEquals(FlashMode.AUTO, flashDisplayMode(CaptureMode.PHOTO, FlashMode.AUTO))
        // One tap from the displayed value reaches the lamp rather than an unreachable AUTO.
        assertEquals(
            FlashMode.TORCH,
            nextAvailable(
                flashDisplayMode(CaptureMode.VIDEO, FlashMode.AUTO),
                flashChoicesFor(CaptureMode.VIDEO, advertised),
            ),
        )
    }

    @Test
    fun `the grid toggle restores the last non-default grid instead of collapsing to thirds`() {
        assertEquals(GridType.NONE, toggledGridType(GridType.GOLDEN, GridType.GOLDEN))
        assertEquals(GridType.GOLDEN, toggledGridType(GridType.NONE, GridType.GOLDEN))
        assertEquals(GridType.CENTER, toggledGridType(GridType.NONE, GridType.CENTER))
        // First-ever use (nothing remembered yet) still lands on the documented default.
        assertEquals(GridType.THIRDS, toggledGridType(GridType.NONE, GridType.NONE))
    }
}
