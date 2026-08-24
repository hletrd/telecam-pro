package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.availableTransfers
import me.hletrd.telecampro.camera.availableVideoStabModes

/**
 * The SINGLE home of the enum tap-cycle orders and the auto-exposure readout text shared by the
 * shooting-screen dials (ManualDials), the Fn overlay / My Menu (ProSheet), and the Fn bar
 * (CameraScreen). These used to exist as verbatim private copies in ProSheet and ManualDials —
 * which is exactly how the EV readout drifted (one copy hardcoded a 1/3-stop step while every
 * other EV path derived it from hardware). One copy, no drift.
 *
 * The CAPABILITY-PROJECTED enums (exposure mode, focus, WB, metering, flash) deliberately have NO
 * fixed order here: every shipping surface steps them with [nextAvailable] over the route's
 * advertised list, so the order IS the projection's order. Hardcoded `next*` twins for those five
 * were deleted 2026-07-26 — they had zero main-source callers and their documented orders had
 * already drifted from what ships (they claimed CONTINUOUS→AUTO→MANUAL→MACRO while FocusMode.entries
 * makes the shipped cycle MANUAL→AUTO→CONTINUOUS→MACRO, and claimed the WB cycle steps PAST
 * Tungsten/Fluorescent while the advertised list stops on them). A tested-but-unshipped second copy
 * is exactly the drift this header forbids. If one of those documented orders is the intent,
 * reorder the list ControlAvailability builds — that is the seam the UI actually walks.
 */

/** Cycles only inside a route's advertised choices; a stale current value enters at the first. */
internal fun <T> nextAvailable(current: T, available: List<T>): T {
    if (available.isEmpty()) return current
    val currentIndex = available.indexOf(current)
    return available[if (currentIndex < 0) 0 else (currentIndex + 1) % available.size]
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

/**
 * What the top-bar grid TOGGLE should apply. It is a two-state control (on/off) sitting next to a
 * five-state Fn cycle and an AssistsTab picker, and it used to hardcode THIRDS on the way back on —
 * so one off→on round trip silently destroyed a GOLDEN/SQUARE/CENTER choice. Restore the last
 * non-NONE type instead; THIRDS remains the first-ever default.
 */
internal fun toggledGridType(current: GridType, lastActive: GridType): GridType = when {
    current != GridType.NONE -> GridType.NONE
    lastActive != GridType.NONE -> lastActive
    else -> GridType.THIRDS
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
    val hardwareStep = state.caps?.evStep
    return exposureCompensationStops(
        index = state.controls.exposureCompensation,
        stepNumerator = hardwareStep?.numerator,
        stepDenominator = hardwareStep?.denominator,
    )
}

/** The ONE EV-step derivation: the hardware CONTROL_AE_COMPENSATION_STEP, conventional 1/3 fallback. */
internal fun exposureCompensationStep(stepNumerator: Int?, stepDenominator: Int?): Float =
    if (stepNumerator != null && stepDenominator != null && stepDenominator != 0) {
        stepNumerator.toFloat() / stepDenominator.toFloat()
    } else {
        1f / 3f
    }

/** Pure Camera2 compensation-index conversion, including malformed/missing-step fallback. */
internal fun exposureCompensationStops(
    index: Int,
    stepNumerator: Int?,
    stepDenominator: Int?,
): Float = index * exposureCompensationStep(stepNumerator, stepDenominator)

/** Final signed value used by the dedicated ±3 EV meter; [evCompStops] is already fully scaled. */
internal fun exposureMeterCompensationEv(state: CameraUiState): Float =
    evCompStops(state).coerceIn(-3f, 3f)

// The top-bar quick-tap cycles (flash / self-timer / still aspect). These lived as a second set of
// private copies in CameraScreen — exactly the split this file's header warns about — and are now
// in the one shared home with the Fn-dial cycles above.

/**
 * The flash choices the top-bar button offers in [mode], narrowed from the route's advertised list.
 * VIDEO has no AE flash metering to drive, so only the constant lamp (the video light) is
 * meaningful there — AUTO/ON are still-only. Torch itself is mode-agnostic on the wire
 * (`FLASH_MODE_TORCH` on the repeating request), which is why video had a working light and no way
 * to reach it: the button was gated to PHOTO and there is no FLASH Fn slot or menu row.
 */
internal fun flashChoicesFor(mode: CaptureMode, advertised: List<FlashMode>): List<FlashMode> =
    if (mode == CaptureMode.PHOTO) advertised
    else advertised.filter { it == FlashMode.OFF || it == FlashMode.TORCH }

/**
 * What the button REPRESENTS in [mode]. `controls.flash` survives a mode switch, so an AUTO/ON left
 * over from photo must read (and cycle) as OFF in video rather than claiming a metering mode video
 * can never use.
 */
internal fun flashDisplayMode(mode: CaptureMode, flash: FlashMode): FlashMode =
    if (mode == CaptureMode.PHOTO || flash == FlashMode.TORCH) flash else FlashMode.OFF

internal fun nextTimer(timer: ShutterTimer): ShutterTimer = when (timer) {
    ShutterTimer.OFF -> ShutterTimer.SEC3
    ShutterTimer.SEC3 -> ShutterTimer.SEC10
    ShutterTimer.SEC10 -> ShutterTimer.OFF
}

internal fun nextAspect(ratio: AspectRatio): AspectRatio = when (ratio) {
    AspectRatio.W4_3 -> AspectRatio.W16_9
    AspectRatio.W16_9 -> AspectRatio.W4_3
}

/**
 * Whether [slot] can do anything at all in [mode] — the axis the Fn slot EDITOR must filter on.
 * It offered all 20 slots to all three lists (Photo Fn / Video Fn / My Menu), which put four
 * video-only slots into a Photo Fn list where they are inert or, worse, contradictory:
 *  - OPEN_GATE renders permanently disabled ([quickFnEnabled] requires VIDEO) — a slot the user
 *    can add that can never work.
 *  - TRANSFER and AUDIO_SCENE mutate video-only settings; the chip value changes and nothing about
 *    the still does.
 *  - STABILIZATION reads `videoStabMode` (Off/Standard/Active) while the stabilization a PHOTO
 *    actually applies is `controls.oisEnabled`, which the OSD reports as `OIS OFF` — so the chip
 *    could read "Active" while the OSD read "OIS OFF" for the same frame.
 * My Menu is a settings surface rather than a shooting one, so it keeps every slot.
 */
/** Timer cycle: OFF → 3s → 10s → OFF. Fixed order, matches the sheet's chip order. */
internal fun nextShutterTimer(current: ShutterTimer): ShutterTimer =
    ShutterTimer.entries[(current.ordinal + 1) % ShutterTimer.entries.size]

/** Mic-input cycle over the declared preference order (resolution against live ports is later). */
internal fun nextAudioInput(current: AudioInputPreference): AudioInputPreference =
    AudioInputPreference.entries[(current.ordinal + 1) % AudioInputPreference.entries.size]

internal fun fnSlotAppliesTo(slot: FnSlot, mode: CaptureMode): Boolean = when (slot) {
    FnSlot.OPEN_GATE, FnSlot.TRANSFER, FnSlot.AUDIO_SCENE, FnSlot.STABILIZATION, FnSlot.AUDIO_INPUT ->
        mode == CaptureMode.VIDEO
    // Still-only axes: the self-timer counts down to a SHUTTER, and aspect is the STILL crop
    // (video framing is resolution/open-gate).
    FnSlot.TIMER, FnSlot.ASPECT -> mode == CaptureMode.PHOTO
    else -> true
}

/**
 * Per-slot availability for every quick-Fn surface (Fn overlay, My Menu, Recent rows). One shared
 * predicate: the Fn overlay dimmed-and-guarded these slots while My Menu's rows were always-hot —
 * the one path in the app that could toggle the teleconverter (the afocal 180° flip, live into
 * the recorded file) or the transfer curve mid-recording.
 *
 * Coverage is derived from `CameraViewModel`'s own `rejectIfRecording` gates, not guessed: every
 * slot below routes to an action that already refuses mid-REC there (a session-reconfiguring or
 * live-discontinuity change), so the UI-level gate here must match exactly or a row stays visually
 * hot and only silently no-ops (a transient toast) on tap — STABILIZATION (`onVideoStabMode`
 * rebuilds the repeating request with a new OIS/EIS profile, a visible discontinuity baked into the
 * file) and AUDIO_SCENE (`onAudioScene`) were the two additional gated actions this predicate had
 * not yet caught up to. Slots with no `else` branch (EXPOSURE_MODE/FOCUS/SHUTTER/ISO/WB/EV/ZOOM/
 * DRIVE/METERING and the pure-overlay toggles) are genuinely REC-safe: they only rewrite Camera2
 * request-level values or app-side overlay state, never a session/profile reopen.
 *
 * It ALSO carries [fnSlotAppliesTo], so a video-only slot dims in photo wherever it appears — the
 * Fn editor's new filter only governs what can be ADDED, and My Menu plus every already-persisted
 * photo Fn list can still contain one.
 */
internal fun quickFnEnabled(slot: FnSlot, state: CameraUiState): Boolean = fnSlotAppliesTo(slot, state.mode) && when (slot) {
    FnSlot.TRANSFER -> !state.isRecording && state.encoderInventoryLoaded &&
        availableTransfers(state.videoCodec, state.tenBitEncodeAvailable).size > 1
    // The TC toggle is a rear-only optics door: onToggleTeleconverter also refuses while FRONT
    // (backOpticsDoorRefusal), so the tile must dim on the selfie route or it renders hot and
    // only toasts on tap — exactly the drift this predicate's contract forbids.
    FnSlot.TELECONVERTER ->
        !state.isRecording && state.activeCameraRoute == CameraRoute.BACK && state.cameraRoutes.back
    FnSlot.OPEN_GATE -> state.mode == CaptureMode.VIDEO && !state.isRecording
    FnSlot.STABILIZATION -> !state.isRecording && state.caps?.let {
        availableVideoStabModes(it.videoStabModes).size > 1
    } == true
    FnSlot.AUDIO_SCENE -> !state.isRecording
    // Session-reconfiguring (still reader size is fixed at configureStreams) / mic-route handoff:
    // both actions rejectIfRecording, so the tile must dim in step (this predicate's contract).
    FnSlot.ASPECT -> !state.isRecording
    FnSlot.AUDIO_INPUT -> !state.isRecording
    else -> true
}
