package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.controlAvailability
import com.hletrd.findx9tele.camera.controlCapabilities
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.formatDisplayZoom
import java.util.Locale

/**
 * Non-composable quick-Fn semantics, hoisted (behavior-locked, verbatim) out of ProSheet.kt to sit
 * beside ControlCycles.kt: the per-slot value readout ([fnSlotValue]) and tap dispatch
 * ([performQuickFn]) shared by the Fn overlay, My Menu, and Recent rows — contract-locked to
 * [quickFnEnabled] and the `next*` cycle orders in ControlCycles.kt — plus the ProSheet's pure
 * tab-selection/side-layout policy. ProSheet.kt keeps only Compose emission (and its
 * Intent-launching privacy-policy opener).
 */

internal fun proSheetTabSelection(selected: ProSheetTab): List<ProSheetTabSelection> =
    ProSheetTab.entries.map { ProSheetTabSelection(it, it == selected) }

internal fun proSheetUsesSideLayout(width: Float, height: Float): Boolean = width > height

internal fun fnSlotValue(slot: FnSlot, state: CameraUiState): String {
    val c = state.controls
    return when (slot) {
        FnSlot.EXPOSURE_MODE -> c.exposureMode.letter
        FnSlot.FOCUS -> focusModeLabel(c.focusMode)
        FnSlot.SHUTTER -> when {
            c.exposureMode == ExposureMode.PROGRAM -> "Auto ${autoShutterText(state)}"
            c.autoShutterDriven -> "Auto ${formatShutterSpeed(c.exposureTimeNs)}"
            c.shutterMode == ShutterMode.ANGLE -> "%.0f°".format(Locale.US, c.shutterAngle)
            else -> formatShutterSpeed(c.exposureTimeNs)
        }
        FnSlot.ISO -> when {
            c.exposureMode == ExposureMode.PROGRAM -> "Auto ${autoIsoText(state)}"
            c.autoIsoDriven -> "Auto ${c.iso}"
            else -> c.iso.toString()
        }
        FnSlot.WB -> if (c.wbMode == WbMode.MANUAL) "${c.wbKelvin}K" else wbModeLabel(c.wbMode)
        FnSlot.EV -> "%+.1f".format(Locale.US, evCompStops(state))
        // Same main-relative display scale and formatter as the HUD pill and persistent Fn row.
        FnSlot.ZOOM -> formatDisplayZoom(
            c.zoomRatio,
            state.teleconverterMode,
            state.caps?.equivalentFocalMm,
            frontFacing = state.facing == com.hletrd.findx9tele.camera.CameraFacing.FRONT,
        )
        FnSlot.STABILIZATION -> state.videoStabMode.label
        FnSlot.DRIVE -> driveModeLabel(state.driveMode)
        FnSlot.METERING -> meteringModeLabel(c.meteringMode)
        FnSlot.PEAKING -> if (state.focusPeaking) "On" else "Off"
        FnSlot.ZEBRA -> if (state.zebra) "On" else "Off"
        FnSlot.TRANSFER -> transferLabelShort(state.transfer)
        FnSlot.AUDIO_SCENE -> state.audioScene.label
        FnSlot.GRID -> gridTypeLabel(state.grid)
        FnSlot.LEVEL -> if (state.level) "On" else "Off"
        FnSlot.PUNCH_IN -> if (state.punchIn) "On" else "Off"
        FnSlot.TELECONVERTER -> if (state.teleconverterMode) "300 mm" else "Off"
        FnSlot.OPEN_GATE -> if (state.openGate) "4:3" else "Off"
        FnSlot.FRAME_LINES -> state.frameLines.label
    }
}

internal fun performQuickFn(slot: FnSlot, state: CameraUiState, actions: CameraActions) {
    // Defense in depth for EVERY caller surface (Fn overlay, My Menu, Recent): the visual
    // enabled/dimmed state lives at the row, but the action itself must refuse too — My Menu rows
    // used to invoke this unguarded, making them the one path that could toggle the teleconverter
    // (the afocal 180° flip) or the transfer curve mid-recording.
    if (!quickFnEnabled(slot, state)) return
    // Plain call (not remember): this runs once per quick-Fn TAP, not per recomposition.
    val availability = controlAvailability(state.caps?.controlCapabilities(), state.controls)
    when (slot) {
        FnSlot.EXPOSURE_MODE -> actions.onExposureMode(
            nextAvailable(state.controls.exposureMode, availability.exposureModes),
        )
        FnSlot.FOCUS -> actions.onFocusMode(nextAvailable(state.controls.focusMode, availability.focusModes))
        FnSlot.SHUTTER -> if (availability.shutterDialEnabled) actions.onShutterMode(
            if (state.controls.shutterMode == ShutterMode.SPEED) ShutterMode.ANGLE else ShutterMode.SPEED,
        )
        FnSlot.ISO -> actions.onExposureMode(
            if (state.controls.exposureMode == ExposureMode.ISO) ExposureMode.PROGRAM
            else if (ExposureMode.ISO in availability.exposureModes) ExposureMode.ISO
            else nextAvailable(state.controls.exposureMode, availability.exposureModes),
        )
        FnSlot.WB -> actions.onWbMode(nextAvailable(state.controls.wbMode, availability.wbModes))
        FnSlot.EV -> if (availability.evDialEnabled) actions.onExposureCompensation(0)
        FnSlot.ZOOM -> if (availability.zoomDialEnabled) actions.onZoomRatio(1f)
        FnSlot.STABILIZATION -> actions.onVideoStabMode(nextVideoStabMode(state.videoStabMode))
        FnSlot.DRIVE -> actions.onDriveMode(nextDriveMode(state.driveMode))
        FnSlot.METERING -> actions.onMeteringMode(
            nextAvailable(state.controls.meteringMode, availability.meteringModes),
        )
        FnSlot.PEAKING -> actions.onTogglePeaking(!state.focusPeaking)
        FnSlot.ZEBRA -> actions.onToggleZebra(!state.zebra)
        FnSlot.TRANSFER -> actions.onTransfer(nextTransfer(state.transfer))
        FnSlot.AUDIO_SCENE -> actions.onAudioScene(nextAudioScene(state.audioScene))
        FnSlot.GRID -> actions.onGridType(nextGridType(state.grid))
        FnSlot.LEVEL -> actions.onToggleLevel(!state.level)
        FnSlot.PUNCH_IN -> actions.onTogglePunchIn(!state.punchIn)
        FnSlot.TELECONVERTER -> actions.onToggleTeleconverter(!state.teleconverterMode)
        FnSlot.OPEN_GATE -> actions.onToggleOpenGate(!state.openGate) // gate lives in quickFnEnabled
        FnSlot.FRAME_LINES -> actions.onFrameLines(nextFrameLine(state.frameLines))
    }
}
