package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.WbMode

/**
 * The SINGLE home of the enum tap-cycle orders and the auto-exposure readout text shared by the
 * shooting-screen dials (ManualDials), the Fn overlay / My Menu (ProSheet), and the Fn bar
 * (CameraScreen). These used to exist as verbatim private copies in ProSheet and ManualDials —
 * which is exactly how the EV readout drifted (one copy hardcoded a 1/3-stop step while every
 * other EV path derived it from hardware). One copy, no drift.
 */

/** PASM cycle order: Program → Shutter-priority → ISO-priority → Manual → (back to Program). */
internal fun nextExposureMode(mode: ExposureMode): ExposureMode = when (mode) {
    ExposureMode.PROGRAM -> ExposureMode.SHUTTER
    ExposureMode.SHUTTER -> ExposureMode.ISO
    ExposureMode.ISO -> ExposureMode.MANUAL
    ExposureMode.MANUAL -> ExposureMode.PROGRAM
}

internal fun nextFocusMode(mode: FocusMode): FocusMode = when (mode) {
    FocusMode.CONTINUOUS -> FocusMode.AUTO
    FocusMode.AUTO -> FocusMode.MANUAL
    FocusMode.MANUAL -> FocusMode.MACRO
    FocusMode.MACRO -> FocusMode.CONTINUOUS
}

internal fun nextWbMode(mode: WbMode): WbMode = when (mode) {
    WbMode.AUTO -> WbMode.DAYLIGHT
    WbMode.DAYLIGHT -> WbMode.CLOUDY
    WbMode.CLOUDY -> WbMode.SHADE
    WbMode.SHADE -> WbMode.MANUAL
    WbMode.MANUAL -> WbMode.AUTO
    // CUSTOM is only ENTERED via "Capture Custom WB"; the Fn cycle steps past it back to AUTO.
    WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.CUSTOM -> WbMode.AUTO
}

internal fun nextVideoStabMode(mode: VideoStabMode): VideoStabMode = when (mode) {
    VideoStabMode.OFF -> VideoStabMode.STANDARD
    VideoStabMode.STANDARD -> VideoStabMode.ENHANCED
    VideoStabMode.ENHANCED -> VideoStabMode.OFF
}

internal fun nextDriveMode(mode: DriveMode): DriveMode = when (mode) {
    DriveMode.SINGLE -> DriveMode.BURST
    DriveMode.BURST -> DriveMode.AEB
    DriveMode.AEB -> DriveMode.TIMELAPSE
    DriveMode.TIMELAPSE -> DriveMode.SINGLE
}

internal fun nextMeteringMode(mode: MeteringMode): MeteringMode = when (mode) {
    MeteringMode.MATRIX -> MeteringMode.CENTER
    MeteringMode.CENTER -> MeteringMode.SPOT
    MeteringMode.SPOT -> MeteringMode.MATRIX
}

internal fun nextTransfer(transfer: ColorTransfer): ColorTransfer = when (transfer) {
    ColorTransfer.HLG -> ColorTransfer.LOG
    ColorTransfer.LOG -> ColorTransfer.SDR
    ColorTransfer.SDR -> ColorTransfer.HLG
}

internal fun nextAudioScene(scene: AudioScene): AudioScene = when (scene) {
    AudioScene.STANDARD -> AudioScene.SOUND_FOCUS
    AudioScene.SOUND_FOCUS -> AudioScene.SOUND_STAGE
    AudioScene.SOUND_STAGE -> AudioScene.STANDARD
}

internal fun nextGridType(type: GridType): GridType = when (type) {
    GridType.NONE -> GridType.THIRDS
    GridType.THIRDS -> GridType.GOLDEN
    GridType.GOLDEN -> GridType.SQUARE
    GridType.SQUARE -> GridType.CENTER
    GridType.CENTER -> GridType.NONE
}

internal fun nextFrameLine(type: FrameLineType): FrameLineType = when (type) {
    FrameLineType.OFF -> FrameLineType.CINEMA
    FrameLineType.CINEMA -> FrameLineType.SQUARE
    FrameLineType.SQUARE -> FrameLineType.VERTICAL
    FrameLineType.VERTICAL -> FrameLineType.OFF
}

/** Auto-mode shutter readout: the AE-resolved live value in P, else the (loop-driven) manual field. */
internal fun autoShutterText(state: CameraUiState): String {
    val c = state.controls
    val ns = if (c.autoExposure) state.liveExposureNs else c.exposureTimeNs
    return ns?.let { formatShutterSpeed(it) } ?: "--"
}

/** Auto-mode ISO readout companion of [autoShutterText]. */
internal fun autoIsoText(state: CameraUiState): String {
    val c = state.controls
    val iso = if (c.autoExposure) state.liveIso else c.iso
    return iso?.toString() ?: "--"
}

/**
 * EV compensation in stops, derived from the HARDWARE step (CONTROL_AE_COMPENSATION_STEP) with the
 * conventional 1/3 fallback — the same derivation as the dial chip, the exposure meter, and the EV
 * ruler. Never hardcode 0.333: a device advertising a 1/2 step would silently misreport EV.
 */
internal fun evCompStops(state: CameraUiState): Float {
    val step = state.caps?.evStep?.let {
        if (it.denominator == 0) 1f / 3f else it.numerator.toFloat() / it.denominator.toFloat()
    } ?: (1f / 3f)
    return state.controls.exposureCompensation * step
}
