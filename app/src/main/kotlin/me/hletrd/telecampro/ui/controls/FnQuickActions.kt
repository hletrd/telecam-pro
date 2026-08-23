package me.hletrd.telecampro.ui.controls

import android.content.Context
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.controlAvailability
import me.hletrd.telecampro.camera.controlCapabilities
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.formatDisplayZoom
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

internal fun fnSlotValue(slot: FnSlot, state: CameraUiState, context: Context? = null): String {
    val c = state.controls
    fun auto(value: String) = context?.getString(R.string.a11y_auto_value, value) ?: "Auto $value"
    fun onOff(value: Boolean) = context?.getString(if (value) R.string.value_on else R.string.value_off)
        ?: if (value) "On" else "Off"
    return when (slot) {
        FnSlot.EXPOSURE_MODE -> exposureModeLetter(c.exposureMode)
        FnSlot.FOCUS -> context?.localizedLabel(c.focusMode) ?: focusModeLabel(c.focusMode)
        FnSlot.SHUTTER -> when {
            c.exposureMode == ExposureMode.PROGRAM -> auto(autoShutterText(state))
            c.autoShutterDriven -> auto(formatShutterSpeed(c.exposureTimeNs))
            c.shutterMode == ShutterMode.ANGLE -> "%.0f°".format(Locale.US, c.shutterAngle)
            else -> formatShutterSpeed(c.exposureTimeNs)
        }
        FnSlot.ISO -> when {
            c.exposureMode == ExposureMode.PROGRAM -> auto(autoIsoText(state))
            c.autoIsoDriven -> auto(c.iso.toString())
            else -> c.iso.toString()
        }
        FnSlot.WB -> if (c.wbMode == WbMode.MANUAL) "${c.wbKelvin}K" else context?.localizedLabel(c.wbMode) ?: wbModeLabel(c.wbMode)
        FnSlot.EV -> formatEvComp(evCompStops(state))
        // Same main-relative display scale and formatter as the HUD pill and persistent Fn row.
        FnSlot.ZOOM -> formatDisplayZoom(
            c.zoomRatio,
            state.teleconverterMode,
            state.teleconverterMagnification,
            state.caps?.equivalentFocalMm,
            frontFacing = state.facing == me.hletrd.telecampro.camera.CameraFacing.FRONT,
        )
        FnSlot.STABILIZATION -> context?.localizedLabel(state.videoStabMode) ?: videoStabModeLabel(state.videoStabMode)
        FnSlot.DRIVE -> context?.localizedLabel(state.driveMode) ?: driveModeLabel(state.driveMode)
        FnSlot.METERING -> context?.localizedLabel(c.meteringMode) ?: meteringModeLabel(c.meteringMode)
        FnSlot.PEAKING -> onOff(state.focusPeaking)
        FnSlot.ZEBRA -> onOff(state.zebra)
        FnSlot.TRANSFER -> transferLabelShort(state.transfer)
        FnSlot.AUDIO_SCENE -> context?.localizedLabel(state.audioScene) ?: audioSceneLabel(state.audioScene)
        FnSlot.GRID -> context?.localizedLabel(state.grid) ?: gridTypeLabel(state.grid)
        FnSlot.LEVEL -> onOff(state.level)
        FnSlot.PUNCH_IN -> onOff(state.punchIn)
        FnSlot.TELECONVERTER -> if (state.teleconverterMode) formatFocalMm(state.teleconverterFocalMm) else onOff(false)
        FnSlot.OPEN_GATE -> if (state.openGate) "4:3" else onOff(false)
        FnSlot.FRAME_LINES -> context?.localizedLabel(state.frameLines) ?: frameLineTypeLabel(state.frameLines)
        FnSlot.FLASH -> context?.localizedLabel(c.flash) ?: flashModeLabel(c.flash)
        FnSlot.TIMER -> context?.localizedLabel(state.timer) ?: shutterTimerLabel(state.timer)
        FnSlot.ASPECT -> aspectRatioLabel(state.aspectRatio)
        FnSlot.AUDIO_INPUT -> context?.localizedLabel(state.audioInputPreference) ?: audioInputPreferenceLabel(state.audioInputPreference)
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
        FnSlot.FLASH -> actions.onFlash(nextAvailable(state.controls.flash, availability.flashModes))
        FnSlot.TIMER -> actions.onTimer(nextShutterTimer(state.timer))
        FnSlot.ASPECT -> actions.onAspectRatio(
            if (state.aspectRatio == AspectRatio.W4_3) AspectRatio.W16_9 else AspectRatio.W4_3,
        )
        FnSlot.AUDIO_INPUT -> actions.onAudioInputPreference(nextAudioInput(state.audioInputPreference))
    }
}
