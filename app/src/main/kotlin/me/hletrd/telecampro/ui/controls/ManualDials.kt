package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.R

import android.util.Range
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.ControlAvailability
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ExposureStep
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.controlAvailability
import me.hletrd.telecampro.camera.controlCapabilities
import me.hletrd.telecampro.camera.exposureUpperBoundForCaptureMode
import me.hletrd.telecampro.focus.FocusMapping
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.FnEntryAnchor
import me.hletrd.telecampro.ui.fnEntryAnchor
import me.hletrd.telecampro.ui.formatDisplayZoom
import me.hletrd.telecampro.ui.formatZoomMultiplier
import me.hletrd.telecampro.ui.overlays.HudPlate
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.hudGlyph
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The six quick "Fn" manual dials (focus/shutter/ISO/WB/EV/zoom) — the signature element of the
 * camera UI. A horizontal row of value chips sits at rest; tapping one opens a tick-ruler slider
 * above the row where the current value is always centered under a fixed indicator and the ruler
 * scrolls beneath it as the user drags. Only one dial is open at a time.
 */
enum class DialType { FOCUS, SHUTTER, ISO, WB, EV, ZOOM }

/**
 * Back gets first refusal from an expanded ruler; a closed cluster leaves Back to its parent.
 * Keeping this admission rule pure pins the behavior on the JVM, while the local [BackHandler]
 * owns the actual Compose state transition.
 */
internal fun manualDialConsumesBack(openDial: DialType?): Boolean = openDial != null

/** Whether a ruler's exact request mode and scalar range are admitted on the active route. */
internal fun quickManualDialEnabled(type: DialType, availability: ControlAvailability): Boolean =
    when (type) {
        DialType.FOCUS -> availability.manualFocusDialEnabled
        DialType.SHUTTER -> availability.shutterDialEnabled
        DialType.ISO -> availability.isoDialEnabled
        DialType.WB -> availability.wbDialEnabled
        DialType.EV -> availability.evDialEnabled
        DialType.ZOOM -> availability.zoomDialEnabled
    }

/** WB presets navigate to the sheet; only MANUAL enters the numeric Kelvin ruler. */
internal fun whiteBalanceFnChipEnabled(
    mode: WbMode,
    availability: ControlAvailability,
): Boolean = if (mode == WbMode.MANUAL) {
    availability.wbDialEnabled
} else {
    availability.wbModes.size > 1
}

internal fun reconcileOpenManualDial(
    openDial: DialType?,
    availability: ControlAvailability,
): DialType? = openDial?.takeIf { quickManualDialEnabled(it, availability) }

internal fun manualDialForFnSlot(slot: FnSlot): DialType? = when (slot) {
    FnSlot.FOCUS -> DialType.FOCUS
    FnSlot.SHUTTER -> DialType.SHUTTER
    FnSlot.ISO -> DialType.ISO
    FnSlot.WB -> DialType.WB
    FnSlot.EV -> DialType.EV
    FnSlot.ZOOM -> DialType.ZOOM
    else -> null
}

/**
 * One ownership-safe result for every manual-control entry point. The detailed dial strip and the
 * compact Fn tray both consume this plan, so a shortcut cannot silently skip the exposure/focus
 * mode transition that makes its ruler authoritative.
 */
internal data class ManualDialTransition(
    val openDial: DialType?,
    val exposureMode: ExposureMode? = null,
    val focusMode: FocusMode? = null,
    val openExposureSheet: Boolean = false,
)

internal fun manualDialTransition(
    requested: DialType,
    currentlyOpen: DialType?,
    exposureMode: ExposureMode,
    focusMode: FocusMode,
    wbMode: WbMode,
): ManualDialTransition = when {
    requested == DialType.WB && wbMode != WbMode.MANUAL -> ManualDialTransition(
        openDial = null,
        openExposureSheet = true,
    )
    requested == DialType.SHUTTER &&
        (exposureMode == ExposureMode.PROGRAM || exposureMode == ExposureMode.ISO) ->
        ManualDialTransition(openDial = requested, exposureMode = ExposureMode.SHUTTER)
    requested == DialType.ISO &&
        (exposureMode == ExposureMode.PROGRAM || exposureMode == ExposureMode.SHUTTER) ->
        ManualDialTransition(openDial = requested, exposureMode = ExposureMode.ISO)
    requested == DialType.FOCUS && focusMode != FocusMode.MANUAL ->
        ManualDialTransition(openDial = requested, focusMode = FocusMode.MANUAL)
    requested == DialType.EV && exposureMode == ExposureMode.MANUAL ->
        ManualDialTransition(openDial = null)
    else -> ManualDialTransition(openDial = if (currentlyOpen == requested) null else requested)
}

@Composable
fun ManualDialCluster(
    state: CameraUiState,
    actions: CameraActions,
    openDial: DialType?,
    onSelectDial: (DialType) -> Unit,
    onCloseDial: () -> Unit,
    glyphRotation: Float,
    // No default: an empty lambda here silently disables the compact tray's only Fn entry point,
    // and a dead button is indistinguishable from a working one until it is pressed.
    onOpenFnMenu: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val controls = state.controls
    val caps = state.caps
    // Keyed remember: see TopBar — the projection is pure in (caps, controls) and telemetry ticks
    // recompose this cluster at 5-10 Hz without changing either input.
    val availability = remember(caps, controls) {
        controlAvailability(caps?.controlCapabilities(), controls)
    }
    val dialOpen = openDial != null

    // Route changes can replace the exact OFF mode/range behind an already-open ruler. Close it on
    // the first composition with the new capability projection; normalized state remains applied.
    LaunchedEffect(openDial, availability) {
        val reconciled = reconcileOpenManualDial(openDial, availability)
        if (reconciled != openDial) onCloseDial()
    }

    // This handler is composed before the full-screen sheet/Fn/review handlers, so those later
    // topmost surfaces retain priority. With no full-screen modal, Back closes the ruler instead of
    // falling through to the Activity and backgrounding the camera.
    BackHandler(enabled = manualDialConsumesBack(openDial), onBack = onCloseDial)

    // MF assist: while the Focus ruler is open, punch in on the loupe point (last tap, else center)
    // so critical focus at 300 mm is judged on magnified pixels — the auto-magnify every MF-first
    // camera ships. Only auto-toggles when the user didn't already have punch-in on, and restores
    // the previous state when the ruler closes (manual sheet toggles mid-drag win: if the user
    // turned punch-in off while the ruler was open, closing it won't re-toggle).
    val focusOpen = openDial == DialType.FOCUS
    var loupeAutoOn by remember { mutableStateOf(false) }
    LaunchedEffect(focusOpen) {
        if (focusOpen && !state.punchIn) {
            loupeAutoOn = true
            actions.onAutoPunchIn(true)
        } else if (!focusOpen && loupeAutoOn) {
            loupeAutoOn = false
            if (state.punchIn) actions.onAutoPunchIn(false)
        }
    }

    // AGG3-34: visible/exit content must not both derive from the SAME nulled state, or the shrink
    // animation composes the empty `null -> Unit` branch and the dial blinks out instead of
    // animating shut. lastOpenDial tracks the most recent non-null selection (updated via
    // SideEffect, so it lands strictly after openDial itself already drove this composition and
    // therefore never lags a live dial switch); the exit animation renders THAT while `dialOpen`
    // (still keyed on openDial != null) drives visibility.
    var lastOpenDial by remember { mutableStateOf<DialType?>(null) }
    SideEffect { if (openDial != null) lastOpenDial = openDial }
    val displayedDial = openDial ?: lastOpenDial

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AnimatedVisibility(
            visible = dialOpen,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                when (displayedDial) {
                    DialType.FOCUS -> FocusRuler(controls = controls, caps = caps, onFocusSlider = actions::onFocusSlider)
                    DialType.SHUTTER -> ShutterRuler(
                        mode = state.mode,
                        controls = controls,
                        caps = caps,
                        actions = actions,
                    )
                    DialType.ISO -> IsoRuler(controls = controls, caps = caps, onIso = actions::onIso)
                    DialType.WB -> WbRuler(controls = controls, onWbKelvin = actions::onWbKelvin)
                    DialType.EV -> EvRuler(controls = controls, caps = caps, onEv = actions::onExposureCompensation)
                    DialType.ZOOM -> ZoomRuler(
                        controls = controls,
                        caps = caps,
                        teleconverter = state.teleconverterMode,
                        teleconverterMagnification = state.teleconverterMagnification,
                        onZoomRatio = actions::onZoomRatio,
                    )
                    null -> Unit
                }
                if (compact) {
                    CompactDialCloseButton(
                        onClick = onCloseDial,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }

        if (!compact) {
            DialChipRow(
                state = state,
                openDial = openDial,
                onSelect = onSelectDial,
                actions = actions,
                onOpenFnMenu = onOpenFnMenu,
                availability = availability,
                glyphRotation = glyphRotation,
            )
        }
    }
}

@Composable
private fun DialChipRow(
    state: CameraUiState,
    openDial: DialType?,
    onSelect: (DialType) -> Unit,
    actions: CameraActions,
    onOpenFnMenu: () -> Unit,
    availability: ControlAvailability,
    glyphRotation: Float,
    modifier: Modifier = Modifier,
) {
    val controls = state.controls
    // The chip row scrolls horizontally and its content is wider than the screen — without a hint
    // the half-cut trailing chip at the screen edge reads as a LAYOUT BUG rather than "scrollable"
    // (user-reported margin weirdness). The fade lives in the shared trailingEdgeFadeScrollHint,
    // applied to every horizontally scrolling chip row app-wide (SegmentedSelector included).
    val fnScroll = rememberScrollState()
    // This row is portrait-window camera geometry: Start/End come from the held-device policy, not
    // the locale's reading direction. Unicode bidi still shapes localized Text content correctly.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val entryAnchor = fnEntryAnchor(state.deviceOrientation)
            if (entryAnchor == FnEntryAnchor.START) {
                CompactFnButton(onClick = onOpenFnMenu, glyphRotation = glyphRotation)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .trailingEdgeFadeScrollHint(fnScroll)
                    .horizontalScroll(fnScroll),
                // 8 dp, the one bottom-cluster gap: this chip row, the focal rail above it, and the
                // mode pair below it are three stacked rows that read as one control block, and they
                // used to run 6 / 8 / 20 dp.
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.activeFnSlots.forEach { slot ->
                    FnDialChip(
                        slot = slot,
                        state = state,
                        openDial = openDial,
                        onSelect = onSelect,
                        actions = actions,
                        onOpenFnMenu = onOpenFnMenu,
                        availability = availability,
                    )
                }
            }
            if (entryAnchor == FnEntryAnchor.END) {
                CompactFnButton(onClick = onOpenFnMenu, glyphRotation = glyphRotation)
            }
        }
    }
}

/** Sony-familiar, always-visible entry point; dial long-press remains as the expert shortcut. */
@Composable
internal fun CompactFnButton(
    onClick: () -> Unit,
    glyphRotation: Float,
    modifier: Modifier = Modifier,
) {
    val a11yOpenFunctionMenu = stringResource(R.string.a11y_open_function_menu)
    val activate = onClick
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = a11yOpenFunctionMenu
                role = Role.Button
                onClick {
                    activate()
                    true
                }
            }
            .clickable(role = Role.Button, onClick = onClick),
        // CenterStart, not Center (UI review #37): the rail-mates (OSD plate, exposure meter,
        // gallery thumb) put their VISIBLE plate edge on the shared 12 dp inset, but centring the
        // 36 dp circle inside its 48 dp touch box pushed the visible edge ~6 dp further in — the
        // one element off the rail line. The extra touch slack now falls inward, where it overlaps
        // nothing interactive.
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(HudPlate)
                .border(1.dp, CameraColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.label_fn),
                color = CameraColors.Accent,
                style = hudGlyph(11.sp),
                modifier = Modifier.rotate(glyphRotation),
            )
        }
    }
}

@Composable
private fun CompactDialCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a11yCloseAdjustment = stringResource(R.string.a11y_close_adjustment)
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = a11yCloseAdjustment
                role = Role.Button
                onClick {
                    activate()
                    true
                }
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(HudPlate)
                .border(1.dp, CameraColors.AffordanceEdge, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = CameraColors.TextPrimary, style = hudGlyph(16.sp, FontWeight.Normal))
        }
    }
}

@Composable
private fun FnDialChip(
    slot: FnSlot,
    state: CameraUiState,
    openDial: DialType?,
    onSelect: (DialType) -> Unit,
    actions: CameraActions,
    onOpenFnMenu: () -> Unit,
    availability: ControlAvailability,
) {
    val controls = state.controls
    val caps = state.caps
    val policyEnabled = quickFnEnabled(slot, state)
    when (slot) {
        FnSlot.EXPOSURE_MODE -> DialChip(
            label = stringResource(R.string.label_mode), // not "AE" — "AE: M" read as auto-exposure: manual (UI review #22)
            value = controls.exposureMode.letter,
            active = controls.exposureMode != ExposureMode.PROGRAM,
            enabled = policyEnabled && availability.exposureModes.size > 1,
            onClick = { actions.onExposureMode(nextAvailable(controls.exposureMode, availability.exposureModes)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.FOCUS -> {
            val focusDistance = formatFocusRelative(
                if (controls.focusMode == FocusMode.MANUAL) controls.focusDistanceDiopters
                else state.liveFocusDiopters ?: controls.focusDistanceDiopters,
                caps?.minFocusDistanceDiopters ?: 0f,
            )
            DialChip(
                label = focusModeLabel(controls.focusMode),
                // The drawn label is the LIVE AF mode, so the node's name has to come from the slot —
                // and the mode then rides the STATE with the distance, or it is spoken nowhere on the
                // chip while a sighted user reads it in the pill.
                accessibleName = fnSlotLabel(slot),
                value = focusDistance,
                accessibleState = focusDialStateDescription(controls.focusMode, focusDistance),
                active = openDial == DialType.FOCUS,
                enabled = policyEnabled && quickManualDialEnabled(DialType.FOCUS, availability),
                onClick = { onSelect(DialType.FOCUS) },
                onLongClick = onOpenFnMenu,
            )
        }
        FnSlot.SHUTTER -> DialChip(
            label = stringResource(R.string.label_ss),
            // "SS" is a printed abbreviation with no spoken form; the slot name is "Shutter". Unlike
            // FOCUS this loses nothing to the rename: "SS" is only ever an abbreviation OF the name,
            // never a value, and the speed/angle distinction the value can carry is already in the
            // value's own glyphs ("1/250s" vs "180°"). So this chip needs no accessibleState.
            accessibleName = fnSlotLabel(slot),
            value = when {
                controls.exposureMode == ExposureMode.PROGRAM -> autoShutterText(state)
                controls.autoShutterDriven -> formatShutterSpeed(controls.exposureTimeNs)
                controls.shutterMode == ShutterMode.ANGLE -> "%.0f°".format(Locale.US, controls.shutterAngle)
                else -> formatShutterSpeed(controls.exposureTimeNs)
            },
            autoValue = controls.exposureMode == ExposureMode.PROGRAM || controls.autoShutterDriven,
            active = openDial == DialType.SHUTTER,
            enabled = policyEnabled && quickManualDialEnabled(DialType.SHUTTER, availability),
            onClick = { onSelect(DialType.SHUTTER) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ISO -> DialChip(
            label = stringResource(R.string.label_iso),
            value = if (controls.exposureMode == ExposureMode.PROGRAM) {
                autoIsoText(state)
            } else {
                controls.iso.toString()
            },
            autoValue = controls.exposureMode == ExposureMode.PROGRAM || controls.autoIsoDriven,
            active = openDial == DialType.ISO,
            enabled = policyEnabled && quickManualDialEnabled(DialType.ISO, availability),
            onClick = { onSelect(DialType.ISO) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.WB -> DialChip(
            label = stringResource(R.string.label_wb),
            value = if (controls.wbMode == WbMode.MANUAL) "${controls.wbKelvin}K" else wbModeLabel(controls.wbMode),
            active = openDial == DialType.WB,
            enabled = policyEnabled && whiteBalanceFnChipEnabled(controls.wbMode, availability),
            onClick = { onSelect(DialType.WB) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.EV -> DialChip(
            label = stringResource(R.string.label_ev),
            value = formatEvComp(evCompStops(state)),
            active = openDial == DialType.EV,
            enabled = policyEnabled && quickManualDialEnabled(DialType.EV, availability),
            onClick = { onSelect(DialType.EV) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ZOOM -> DialChip(
            label = stringResource(R.string.label_zoom),
            value = formatDisplayZoom(
                controls.zoomRatio,
                state.teleconverterMode,
                state.teleconverterMagnification,
                state.caps?.equivalentFocalMm,
                frontFacing = state.facing == me.hletrd.telecampro.camera.CameraFacing.FRONT,
            ),
            active = openDial == DialType.ZOOM,
            enabled = policyEnabled && quickManualDialEnabled(DialType.ZOOM, availability),
            onClick = { onSelect(DialType.ZOOM) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.STABILIZATION -> DialChip(
            // Full words, not in-house abbreviations ("Stab"/"Steady" read as nonsense to camera
            // users — feedback). Values come from VideoStabMode.label (Off/Standard/Active).
            // The feedback governs THIS chip and stands. The one exception is the held-landscape Fn
            // tray, where fnOverlayVisualLabel/Value (CameraScreenPolicy.kt) shortens visual copy to
            // fit a 148 dp tile — "Stab", "Gate", "Std", "TL", "Day", "Tung." — while accessibility
            // keeps the complete label. That is a width seam with its own justification, not this
            // rule being overridden.
            label = stringResource(R.string.label_stabilization),
            value = state.videoStabMode.label,
            active = state.videoStabMode != VideoStabMode.OFF,
            enabled = policyEnabled,
            onClick = { actions.onVideoStabMode(nextVideoStabMode(state.videoStabMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.DRIVE -> DialChip(
            label = stringResource(R.string.label_drive),
            value = driveModeLabel(state.driveMode),
            active = state.driveMode != me.hletrd.telecampro.camera.DriveMode.SINGLE,
            enabled = policyEnabled,
            onClick = { actions.onDriveMode(nextDriveMode(state.driveMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.METERING -> DialChip(
            label = stringResource(R.string.label_meter),
            value = meteringModeLabel(controls.meteringMode),
            active = controls.meteringMode != MeteringMode.MATRIX,
            enabled = policyEnabled && availability.meteringModes.size > 1,
            onClick = { actions.onMeteringMode(nextAvailable(controls.meteringMode, availability.meteringModes)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.PEAKING -> DialChip(
            label = stringResource(R.string.label_peaking),
            value = if (state.focusPeaking) "On" else "Off",
            active = state.focusPeaking,
            enabled = policyEnabled,
            onClick = { actions.onTogglePeaking(!state.focusPeaking) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ZEBRA -> DialChip(
            label = stringResource(R.string.label_zebra),
            value = if (state.zebra) "On" else "Off",
            active = state.zebra,
            enabled = policyEnabled,
            onClick = { actions.onToggleZebra(!state.zebra) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.TRANSFER -> {
            val transferMutable = policyEnabled
            DialChip(
                // "Gamma" is the standard camera term for the transfer curve (HLG / O-Log / SDR);
                // the old "TF" abbreviation read as nonsense (feedback).
                label = stringResource(R.string.label_gamma),
                value = transferLabelShort(state.transfer),
                active = state.transfer != ColorTransfer.SDR,
                enabled = transferMutable,
                onClick = { if (transferMutable) actions.onTransfer(nextTransfer(state.transfer)) },
                onLongClick = onOpenFnMenu,
            )
        }
        FnSlot.AUDIO_SCENE -> DialChip(
            label = stringResource(R.string.label_audio),
            value = state.audioScene.label,
            active = state.audioScene != me.hletrd.telecampro.camera.AudioScene.STANDARD,
            enabled = policyEnabled,
            onClick = { actions.onAudioScene(nextAudioScene(state.audioScene)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.GRID -> DialChip(
            label = stringResource(R.string.label_grid),
            value = gridTypeLabel(state.grid),
            active = state.grid != GridType.NONE,
            enabled = policyEnabled,
            onClick = { actions.onGridType(nextGridType(state.grid)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.LEVEL -> DialChip(
            label = stringResource(R.string.label_level),
            value = if (state.level) "On" else "Off",
            active = state.level,
            enabled = policyEnabled,
            onClick = { actions.onToggleLevel(!state.level) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.PUNCH_IN -> DialChip(
            label = stringResource(R.string.label_loupe),
            value = if (state.punchIn) "On" else "Off",
            active = state.punchIn,
            enabled = policyEnabled,
            onClick = { actions.onTogglePunchIn(!state.punchIn) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.TELECONVERTER -> DialChip(
            label = stringResource(R.string.label_tele),
            value = if (state.teleconverterMode) formatFocalMm(state.teleconverterFocalMm) else "Off",
            active = state.teleconverterMode,
            enabled = policyEnabled,
            onClick = { if (policyEnabled) actions.onToggleTeleconverter(!state.teleconverterMode) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.OPEN_GATE -> DialChip(
            label = stringResource(R.string.label_open_gate),
            value = if (state.openGate) "4:3" else "Off",
            active = state.openGate,
            enabled = policyEnabled,
            onClick = { if (policyEnabled) actions.onToggleOpenGate(!state.openGate) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.FRAME_LINES -> DialChip(
            label = stringResource(R.string.label_frame),
            value = state.frameLines.label,
            active = state.frameLines != FrameLineType.OFF,
            enabled = policyEnabled,
            onClick = { actions.onFrameLines(nextFrameLine(state.frameLines)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.FLASH -> DialChip(
            label = stringResource(R.string.label_flash),
            value = flashModeLabel(controls.flash),
            active = controls.flash != FlashMode.OFF,
            enabled = policyEnabled && availability.flashModes.size > 1,
            onClick = { actions.onFlash(nextAvailable(controls.flash, availability.flashModes)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.TIMER -> DialChip(
            label = stringResource(R.string.label_timer),
            value = shutterTimerLabel(state.timer),
            active = state.timer != ShutterTimer.OFF,
            enabled = policyEnabled,
            onClick = { actions.onTimer(nextShutterTimer(state.timer)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ASPECT -> DialChip(
            label = stringResource(R.string.label_aspect),
            value = aspectRatioLabel(state.aspectRatio),
            active = state.aspectRatio != AspectRatio.W4_3,
            enabled = policyEnabled,
            onClick = {
                if (policyEnabled) actions.onAspectRatio(
                    if (state.aspectRatio == AspectRatio.W4_3) AspectRatio.W16_9 else AspectRatio.W4_3,
                )
            },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.AUDIO_INPUT -> DialChip(
            label = stringResource(R.string.label_mic_input),
            value = state.audioInputPreference.label,
            active = state.audioInputPreference != AudioInputPreference.AUTO,
            enabled = policyEnabled,
            onClick = { if (policyEnabled) actions.onAudioInputPreference(nextAudioInput(state.audioInputPreference)) },
            onLongClick = onOpenFnMenu,
        )
    }
}

// (The next* cycle helpers and auto-exposure readout text live in ControlCycles.kt — shared with
// ProSheet/CameraScreen so the cycle orders can't drift between surfaces. The verbatim private
// copies that used to sit here were the drift hazard the review flagged.)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialChip(
    label: String,
    value: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // The SPOKEN name, when the drawn [label] is not one. Most chips draw their own slot name, so the
    // default is right for 18 of the 20 — but FOCUS draws the live AF mode ("MF" / "AF-C") and SHUTTER
    // draws "SS", so those two announced a VALUE as the node's name: the chip said "MF, ∞+42" with the
    // word "Focus" nowhere, and its name changed every time the user cycled AF. A node's name must be
    // stable (TalkBack tracks focus by it) and must match the same control elsewhere — the Fn tray
    // tile for the same slot exports fnSlotLabel(slot). The value belongs in stateDescription, which
    // is where it already goes.
    accessibleName: String = label,
    // The SPOKEN state, when the drawn [value] alone is not the whole of what the pill says. Pinning
    // [accessibleName] to the stable slot name is only half the fix for a chip that draws a value in
    // its LABEL slot: FOCUS draws the live AF mode there, so with the name pinned to "Focus" the mode
    // was spoken in neither half of the node and only the distance survived. The mode is a value, so
    // it rides here with the distance (focusDialStateDescription). Null keeps the ordinary rule —
    // the drawn value IS the state — for the eighteen chips whose label is already their name.
    accessibleState: String? = null,
    // Auto-driven value ("A9100" class): the qualifier renders as a smaller, dimmer A so the
    // actual value stays scannable ("A9100" read as one blob — user-reported), and accessibility
    // hears the honest word instead of a letter glued to digits.
    autoValue: Boolean = false,
) {
    val a11yOpenFunctionMenu = stringResource(R.string.a11y_open_function_menu)
    val activate = onClick
    val longActivate = onLongClick
    // Idle plate is the shared [HudPlate], like every sibling chip. It used to be
    // `CameraColors.Pill.copy(alpha = …)`, a ~9/255-lighter slab that measured 3.57:1 for the
    // TextSecondary an UNAVAILABLE chip uses — under the 4.5:1 floor, and under the 5.07:1 the
    // contrast pass that rewrote this exact line (798006d) reported. Its nearest sibling, FocalRail's
    // lens chip in CameraScreen, is the same construct (idle plate, white when selected, 0.18 border)
    // and was already black, which is what settled it: the lighter base was an inherited literal from
    // the first Pixel-style draft, not a second plate treatment.
    val bg = if (active) CameraColors.TextPrimary else HudPlate
    val fg = when {
        active -> Color.Black
        enabled -> CameraColors.TextPrimary
        else -> CameraColors.TextSecondary
    }
    // Outer box carries the click + a 48 dp minimum touch height. The compact visual pill remains
    // smaller than its hit area, using the same outer-box pattern as TeleChip in CameraScreen.
    Box(
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = accessibleName
                stateDescription = accessibleState ?: if (autoValue) "Auto $value" else value
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
                // clearAndSetSemantics drops combinedClickable's long-press action too, so the Fn
                // shortcut has to be re-declared here or it exists only for sighted touch (UX_POLICY:
                // preserve full merged accessibility actions).
                // "Open function menu", word for word what CompactFnButton above and the opened pane
                // ("Function menu" / "Close function menu") already say: one destination gets one
                // spoken name, and "Fn" is a printed glyph, not a word.
                onLongClick(label = a11yOpenFunctionMenu) {
                    if (!enabled) return@onLongClick false
                    longActivate()
                    true
                }
            }
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = "Open function menu",
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                // Fixed floor width + centered content so a chip's OWN value changes (e.g. "Auto" ↔
                // "1/125s", "ISO 100" ↔ "ISO 12800") never resize it and shift the whole row.
                .defaultMinSize(minWidth = 64.dp)
                .clip(RoundedCornerShape(50))
                .background(bg)
                .then(
                    if (!active) Modifier.border(1.dp, CameraColors.Hairline, RoundedCornerShape(50)) else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = fg,
                style = hudGlyph(11.sp, FontWeight.Medium),
            )
            // NOT dimmed further on the idle chip: the plate underneath is a translucent pill over
            // the LIVE preview (the bottom cluster's gradient is transparent at its top edge, where
            // this row sits), so 0.75 on top of that landed the value under the 4.5:1 floor the rest
            // of the HUD is held to. The label/value hierarchy is carried by WEIGHT alone (label
            // Medium, value SemiBold — both 11 sp): the live value is the emphasized element, the
            // static caption is not.
            val valueColor = fg
            Text(
                if (autoValue) {
                    buildAnnotatedString {
                        // Two-thirds size + extra dimming: reads as a qualifier badge on the value,
                        // like the exposure-mode letters on a Sony top plate.
                        withStyle(SpanStyle(fontSize = 8.sp, color = valueColor.copy(alpha = valueColor.alpha * 0.7f))) {
                            append("A ")
                        }
                        append(value)
                    }
                } else {
                    AnnotatedString(value)
                },
                color = valueColor,
                style = hudGlyph(11.sp, FontWeight.SemiBold),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Per-control ruler wrappers: translate the real domain (diopters/ns/ISO/K/EV) to/from the
// ruler's normalized 0..1 travel and format the live readout above it.
// ---------------------------------------------------------------------------

/**
 * Focus as a RELATIVE 0..100 scale (0 = ∞), not an absolute distance. The diopter→metres estimate
 * is unreliable through the afocal converter, so a relative "∞ + N" reads truer than a fake "3.20 m".
 */
internal fun formatFocusRelative(diopters: Float, minDiopters: Float): String {
    if (minDiopters <= 0f) return "∞"
    val f = FocusMapping.dioptersToSlider(diopters, minDiopters)
    return if (f <= 0.005f) "∞" else "∞+${(f * 100).roundToInt()}"
}

@Composable
private fun RulerReadout(value: String, modifier: Modifier = Modifier, autoValue: Boolean = false) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The one number the photographer is actively adjusting sits near the TOP of the bottom
        // cluster's gradient, where the scrim is nearly transparent — over sky/snow it competed
        // with scene luminance unprotected while every audited HUD sibling had a tested pill.
        Text(
            text = if (autoValue) {
                buildAnnotatedString {
                    // Same qualifier-badge treatment as the DialChip values (64c3d4c): smaller and
                    // dimmer than the value, so "A 1/250s" stops reading as one glued blob — the
                    // ruler readout was the last surface still gluing a full-size "A " (cycle-6
                    // D-05). Accessibility hears the honest word below, like the chips.
                    withStyle(
                        SpanStyle(fontSize = 11.sp, color = CameraColors.ManualActive.copy(alpha = 0.7f)),
                    ) {
                        append("A ")
                    }
                    append(value)
                }
            } else {
                AnnotatedString(value)
            },
            color = CameraColors.ManualActive,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clearAndSetSemantics {
                    contentDescription = if (autoValue) "Auto $value" else value
                }
                .clip(RoundedCornerShape(50))
                .background(HudPlate)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun FocusRuler(controls: ManualControls, caps: CameraCaps?, onFocusSlider: (Float) -> Unit) {
    val minDiopters = caps?.minFocusDistanceDiopters ?: 0f
    val enabled = controls.focusMode == FocusMode.MANUAL && minDiopters > 0f
    val fraction = FocusMapping.dioptersToSlider(controls.focusDistanceDiopters, minDiopters)
    val readout = formatFocusRelative(controls.focusDistanceDiopters, minDiopters)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout(readout)
        // Relative 0..100 scale (0 = ∞); shorter travel than the default so fine focus near infinity
        // — where the afocal converter lands — is reachable without a marathon drag.
        RulerSlider(
            fraction = fraction,
            onFractionChange = onFocusSlider,
            enabled = enabled,
            semanticLabel = "Focus distance",
            valueDescription = readout,
            totalUnits = 100,
            majorEvery = 10,
        )
    }
}

@Composable
private fun ShutterRuler(
    mode: CaptureMode,
    controls: ManualControls,
    caps: CameraCaps?,
    actions: CameraActions,
) {
    // The shutter is user-editable in Shutter-priority and Manual; in ISO priority it's app-driven, so
    // the ruler is shown but inert. ANGLE is a cine convention — same exposure, expressed as a shutter
    // angle relative to the frame rate (180° = 1/(2·fps)).
    val enabled = controls.exposureMode == ExposureMode.SHUTTER || controls.exposureMode == ExposureMode.MANUAL
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SpeedAngleToggle(mode = controls.shutterMode, enabled = enabled, onSelect = actions::onShutterMode)
        if (controls.shutterMode == ShutterMode.ANGLE) {
            val fraction = ((controls.shutterAngle - 1f) / 359f).coerceIn(0f, 1f)
            val readout = "%.0f°  (%s)".format(Locale.US, controls.shutterAngle, formatShutterSpeed(controls.effectiveExposureNsForDisplay()))
            val describedReadout = if (controls.autoShutterDriven) "Auto $readout" else readout
            RulerReadout(readout, autoValue = controls.autoShutterDriven)
            RulerSlider(
                fraction = fraction,
                onFractionChange = { f -> actions.onShutterAngle((1f + f * 359f).coerceIn(1f, 360f)) },
                enabled = enabled,
                semanticLabel = "Shutter angle",
                valueDescription = describedReadout,
            )
        } else {
            val sensorRange = caps?.exposureTimeRange
                ?: Range(controls.exposureTimeNs, controls.exposureTimeNs)
            val upper = exposureUpperBoundForCaptureMode(
                mode = mode,
                fps = controls.fps,
                sensorUpperNs = sensorRange.upper,
            ).coerceAtLeast(sensorRange.lower)
            val range = Range(sensorRange.lower, upper)
            val stops = remember(range.lower, range.upper, controls.exposureStep) { shutterStops(range, controls.exposureStep.ev) }
            val n = stops.size
            val idx = remember(controls.exposureTimeNs, stops) {
                stops.indices.minByOrNull { kotlin.math.abs(stops[it] - controls.exposureTimeNs) } ?: 0
            }
            val fraction = if (n <= 1) 0f else idx.toFloat() / (n - 1)
            val readout = formatShutterSpeed(controls.exposureTimeNs)
            RulerReadout(readout, autoValue = controls.autoShutterDriven)
            RulerSlider(
                fraction = fraction,
                onFractionChange = { f -> actions.onShutterNs(stops[(f * (n - 1)).roundToInt().coerceIn(0, n - 1)]) },
                enabled = enabled,
                semanticLabel = "Shutter speed",
                valueDescription = if (controls.autoShutterDriven) "Auto $readout" else readout,
                totalUnits = (n - 1).coerceAtLeast(1),
                majorEvery = stepMajorEvery(controls.exposureStep),
                snap = true,
            )
        }
    }
}

/** Small Speed⇄Angle segmented switch on the shutter ruler (also mirrored in the settings sheet). */
@Composable
private fun SpeedAngleToggle(mode: ShutterMode, enabled: Boolean, onSelect: (ShutterMode) -> Unit) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ShutterMode.entries.forEach { m ->
            val on = mode == m
            val activate = { onSelect(m) }
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .focusable()
                    .clearAndSetSemantics {
                        contentDescription = if (m == ShutterMode.SPEED) "Shutter speed" else "Shutter angle"
                        stateDescription = if (on) "Selected" else "Not selected"
                        role = Role.RadioButton
                        selected = on
                        if (!enabled) disabled()
                        onClick {
                            if (!enabled) return@onClick false
                            activate()
                            true
                        }
                    }
                    .selectable(
                        selected = on,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = activate,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) CameraColors.ManualActive else HudPlate)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (m == ShutterMode.SPEED) "Speed" else "Angle",
                        color = when {
                            on -> Color.Black
                            enabled -> CameraColors.TextPrimary
                            else -> CameraColors.TextSecondary
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** [ManualControls.effectiveExposureNs] but safe to call with fps == 0 from preview/edge states. */
private fun ManualControls.effectiveExposureNsForDisplay(): Long =
    if (shutterMode == ShutterMode.ANGLE && fps > 0) {
        ((shutterAngle.coerceIn(1f, 360f) / 360.0) / fps * 1_000_000_000.0).toLong()
    } else {
        exposureTimeNs
    }

// ---- Stop snapping ---------------------------------------------------------------------------
// ISO and shutter snap to values spaced by the selected EV increment (1/3, 1/2 or 1 stop), so a
// camera user drags in familiar stops instead of a smooth continuum. Values are generated by EV from
// an anchor and log-spaced; each ruler tick is one stop, keeping the strip short.

// (The significant-figure rounder roundToSignificant was deleted 2026-07-26 with its test: the
// ladder below deliberately replaced that snap, so main had no caller left and the repo was
// pinning math it had stopped shipping — same shape as the metre focus formatter in
// ControlLabels.kt.)

// Standard 1/3-stop ISO ladder (ISO 12232 conventional sensitivities, the values every real camera
// body shows). AGG3-7/CR-12: snapping each `100·2^(k·stepEv)` candidate to 2 significant figures
// instead of this table produced non-standard values a photographer never sees on a body — 130,
// 630, 1300, 5100, 8100 — contradicting the very comment that used to sit here. Mirrors the
// NICE_SHUTTER_DENOM nearest-match pattern in ProControls.kt.
private val STANDARD_ISO_LADDER = intArrayOf(
    50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
    2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
)

/** ISO values [stepEv] EV apart across [[lower], [upper]], anchored at 100, each snapped to the
 *  nearest [STANDARD_ISO_LADDER] entry so they read as conventional stops (100, 125, 160, 200, …)
 *  rather than a generic significant-figure round. Hardware bounds always included (kept exactly
 *  even when they fall between two standard stops, so the full advertised range stays reachable).
 *  Plain-bounds core: android.util.Range getters throw "not mocked" on the JVM, so the testable
 *  seam takes Int lower/upper directly (the sessionAttemptPlan/centerCropBox house pattern); the
 *  Range overload below is a thin wrapper. */
internal fun isoStops(lower: Int, upper: Int, stepEv: Float): IntArray {
    if (lower >= upper || stepEv <= 0f) return intArrayOf(lower)
    val set = sortedSetOf(lower, upper)
    val ln2 = Math.log(2.0)
    val kLo = Math.ceil(Math.log(lower / 100.0) / ln2 / stepEv).toInt()
    val kHi = Math.floor(Math.log(upper / 100.0) / ln2 / stepEv).toInt()
    for (k in kLo..kHi) {
        val raw = 100.0 * Math.pow(2.0, k * stepEv.toDouble())
        val nice = STANDARD_ISO_LADDER.minByOrNull { kotlin.math.abs(it - raw) } ?: raw.roundToInt()
        if (nice > lower && nice < upper) set.add(nice)
    }
    return set.toIntArray()
}

private fun isoStops(range: Range<Int>, stepEv: Float): IntArray = isoStops(range.lower, range.upper, stepEv)

/** Shutter times (ns) [stepEv] EV apart across [[lower], [upper]], anchored at 1 s. Hardware bounds
 *  included. Plain-bounds core (Long lower/upper ns) for the same JVM-mockability reason as
 *  [isoStops]; the Range overload below is a thin wrapper. */
internal fun shutterStops(lower: Long, upper: Long, stepEv: Float): LongArray {
    if (lower >= upper || stepEv <= 0f) return longArrayOf(lower)
    val set = sortedSetOf(lower, upper)
    val ln2 = Math.log(2.0)
    val anchor = 1_000_000_000.0
    val kLo = Math.ceil(Math.log(lower / anchor) / ln2 / stepEv).toInt()
    val kHi = Math.floor(Math.log(upper / anchor) / ln2 / stepEv).toInt()
    for (k in kLo..kHi) {
        val ns = Math.round(anchor * Math.pow(2.0, k * stepEv.toDouble()))
        if (ns > lower && ns < upper) set.add(ns)
    }
    return set.toLongArray()
}

private fun shutterStops(range: Range<Long>, stepEv: Float): LongArray = shutterStops(range.lower, range.upper, stepEv)

private fun stepMajorEvery(step: ExposureStep): Int = when (step) {
    ExposureStep.THIRD -> 3
    ExposureStep.HALF -> 2
    ExposureStep.FULL -> 1
}

@Composable
private fun IsoRuler(controls: ManualControls, caps: CameraCaps?, onIso: (Int) -> Unit) {
    val range = caps?.isoRange ?: Range(controls.iso, controls.iso)
    // ISO is user-editable in ISO-priority and Manual; in Shutter-priority it's app-driven (inert).
    val enabled = controls.exposureMode == ExposureMode.ISO || controls.exposureMode == ExposureMode.MANUAL
    val stops = remember(range.lower, range.upper, controls.exposureStep) { isoStops(range, controls.exposureStep.ev) }
    val n = stops.size
    val idx = remember(controls.iso, stops) {
        stops.indices.minByOrNull { kotlin.math.abs(stops[it] - controls.iso) } ?: 0
    }
    val fraction = if (n <= 1) 0f else idx.toFloat() / (n - 1)
    val readout = if (controls.autoIsoDriven) "Auto ISO ${controls.iso}" else "ISO ${controls.iso}"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout("ISO ${controls.iso}", autoValue = controls.autoIsoDriven)
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f -> onIso(stops[(f * (n - 1)).roundToInt().coerceIn(0, n - 1)]) },
            enabled = enabled,
            semanticLabel = "ISO",
            valueDescription = readout,
            totalUnits = (n - 1).coerceAtLeast(1), // one tick per stop → snappy, short strip
            majorEvery = stepMajorEvery(controls.exposureStep),
            snap = true,
        )
    }
}

private const val WB_KELVIN_MIN = 2000f
private const val WB_KELVIN_MAX = 10000f

@Composable
private fun WbRuler(controls: ManualControls, onWbKelvin: (Int) -> Unit) {
    val fraction = ((controls.wbKelvin - WB_KELVIN_MIN) / (WB_KELVIN_MAX - WB_KELVIN_MIN)).coerceIn(0f, 1f)
    val readout = "${controls.wbKelvin} kelvin"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout("${controls.wbKelvin}K")
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f ->
                val kelvin = (WB_KELVIN_MIN + f * (WB_KELVIN_MAX - WB_KELVIN_MIN)).roundToInt().coerceIn(2000, 10000)
                onWbKelvin(kelvin)
            },
            enabled = controls.wbMode == WbMode.MANUAL,
            semanticLabel = "White balance",
            valueDescription = readout,
        )
    }
}

@Composable
private fun EvRuler(controls: ManualControls, caps: CameraCaps?, onEv: (Int) -> Unit) {
    val range = caps?.evRange ?: Range(0, 0)
    // Shared derivation, not a second inline copy of it: ControlCycles owns the hardware
    // CONTROL_AE_COMPENSATION_STEP fallback. Still local because majorEvery needs the step itself.
    val stepValue = exposureCompensationStep(caps?.evStep?.numerator, caps?.evStep?.denominator)
    val lo = minOf(range.lower, range.upper)
    val hi = maxOf(range.lower, range.upper)
    val fraction = if (lo >= hi) 0f else ((controls.exposureCompensation - lo).toFloat() / (hi - lo).toFloat()).coerceIn(0f, 1f)
    val majorEvery = (1f / stepValue).roundToInt().coerceAtLeast(1)
    val readout = "%+.1f EV".format(Locale.US, controls.exposureCompensation * stepValue)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout(readout)
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f ->
                val ev = (lo + f * (hi - lo)).roundToInt().coerceIn(lo, hi)
                onEv(ev)
            },
            enabled = controls.exposureMode != ExposureMode.MANUAL,
            semanticLabel = "Exposure compensation",
            valueDescription = readout,
            totalUnits = (hi - lo).coerceAtLeast(1),
            majorEvery = majorEvery,
            snap = true,
        )
    }
}

// No defaults on the converter pair: ZoomMath states the invariant for exactly this value — the
// magnification is an explicit parameter, never a global read, "so a caller can never silently
// display the kit optic's scale while a different converter is mounted". A default reading
// TELECONVERTER_MAGNIFICATION is a ready-made way back to the bug 4328fb5/2ea5227 fixed.
@Composable
private fun ZoomRuler(
    controls: ManualControls,
    caps: CameraCaps?,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    onZoomRatio: (Float) -> Unit,
) {
    // TELE reads and drags on the converter-equivalent scale (13–60× on the kit optic); the callback
    // still writes the LENS-LOCAL ratio the engine owns. Other modes are 1:1.
    val base = if (teleconverter) {
        me.hletrd.telecampro.camera.teleDisplayBase(teleconverterMagnification)
    } else {
        1f
    }
    val range = caps?.zoomRatioRange ?: Range(1f, 1f)
    val lo = range.lower * base
    val hi = if (teleconverter) {
        minOf(range.upper * base, me.hletrd.telecampro.camera.TELE_MAX_DISPLAY_ZOOM)
    } else {
        range.upper
    }
    val display = controls.zoomRatio * base
    val fraction = if (hi <= lo) 0f else ((display - lo) / (hi - lo)).coerceIn(0f, 1f)
    val readout = formatZoomMultiplier(display)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout(readout)
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f -> onZoomRatio(((lo + f * (hi - lo)) / base).coerceIn(range.lower, hi / base)) },
            enabled = hi > lo,
            semanticLabel = "Zoom",
            valueDescription = readout,
            totalUnits = 120,
            majorEvery = 12,
        )
    }
}

// ---------------------------------------------------------------------------
// RulerSlider: the actual tick-ruler drag control. Operates on a normalized [0,1] fraction; the
// caller maps that fraction to/from its own real-world domain (diopters, ns, ISO, Kelvin, EV).
// ---------------------------------------------------------------------------

// Fixed for every ruler: no call site ever varied these, and a parameter no caller passes is a
// per-dial-inconsistency waiting to be introduced. The seven rulers are one control.
private val RULER_TICK_SPACING = 12.dp
private val RULER_ACCENT = CameraColors.ManualActive

/**
 * A horizontal tick-ruler drag control. Unlike a plain [androidx.compose.material3.Slider], the
 * "thumb" never moves — a fixed accent-colored indicator sits at the horizontal center, and the
 * ruler strip itself scrolls beneath it as the user drags (content follows the finger: dragging
 * left reveals higher values under the center, dragging right reveals lower ones).
 *
 * @param fraction current value, normalized 0..1 (source of truth, owned by the caller).
 * @param onFractionChange called continuously while dragging with the new normalized value.
 * @param totalUnits number of discrete ticks spanning the full 0..1 travel. Pass the caller's
 *   real step count (e.g. EV raw-compensation-unit count) for literal 1-tick-per-step snapping,
 *   or a large default for a dense, effectively-continuous feel (focus/shutter/ISO/WB).
 * @param majorEvery every Nth tick is drawn taller/brighter (purely a visual rhythm cue).
 * @param semanticLabel spoken name of the real camera control represented by this ruler.
 * @param valueDescription formatted camera-domain value announced instead of a percentage.
 */
@Composable
fun RulerSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    totalUnits: Int = 300,
    majorEvery: Int = 30,
    snap: Boolean = false,
    // No default: "Value" would be a meaningless TalkBack name for a real camera control, and a
    // ruler that silently fell back to it would announce nothing an operator can act on.
    semanticLabel: String,
    valueDescription: String = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()} percent",
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val pxPerUnit = remember(density) { with(density) { RULER_TICK_SPACING.toPx() } }
    // The DOMAIN can change while the dial stays open (TELE toggle from the chip row rebases the
    // zoom ruler; caps republish rescales lo/hi) without any pointerInput key changing — so the
    // running drag handler must call the CURRENT composition's callback, not the first one it
    // captured, or every subsequent drag maps through the stale scale (review C10).
    val currentOnFractionChange by rememberUpdatedState(onFractionChange)
    var isDragging by remember { mutableStateOf(false) }
    // contUnit tracks the finger continuously; localUnit is what's drawn + reported. When [snap] is
    // set it detents to whole units (each = one stop) with a haptic tick, so the bar physically
    // clicks between stops instead of scrolling smoothly.
    var contUnit by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f) * totalUnits) }
    var localUnit by remember { mutableFloatStateOf(contUnit) }
    if (!isDragging) {
        contUnit = fraction.coerceIn(0f, 1f) * totalUnits
        localUnit = if (snap) contUnit.roundToInt().toFloat() else contUnit
    }
    // One-off enabled/disabled PAIRS, like the settings slider's — and deliberately NOT the same
    // numbers as it, despite the shared visual family: this ruler is drawn over the LIVE PREVIEW
    // (hence the plate note below and the brighter 0.85 majors), the settings slider over an opaque
    // sheet. Same shape, different backdrop, so the legibility budgets are not transferable.
    val minorColor = Color.White.copy(alpha = if (enabled) 0.28f else 0.12f)
    val majorColor = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f)
    val indicatorColor = if (enabled) RULER_ACCENT else CameraColors.TextSecondary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // The tested HUD floor, not 0.35: the ruler band sits directly over the live preview
            // and its minor ticks (0.28 white) washed out against bright scenes — same class as
            // the 05486cb scrim sweep, which this control originally missed.
            .background(HudPlate, RoundedCornerShape(16.dp))
            // TalkBack: a bare Canvas is invisible to accessibility services — every manual dial
            // rides this control, so expose it as an adjustable value with a set action.
            .progressSemantics(value = fraction.coerceIn(0f, 1f), valueRange = 0f..1f)
            .semantics {
                contentDescription = semanticLabel
                stateDescription = valueDescription
                if (!enabled) disabled()
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    currentOnFractionChange(target.coerceIn(0f, 1f))
                    true
                }
            }
            .padding(horizontal = 4.dp)
            .pointerInput(enabled, totalUnits, pxPerUnit, snap) {
                if (!enabled) return@pointerInput
                // Publication is FRAME-GATED (~60 Hz): on this 120 Hz touch panel a per-event
                // onFractionChange re-normalized controls and re-published the whole CameraUiState
                // at input rate — the documented pre-coalescer pinch-jank mechanism, alive on the
                // continuous rulers. The ruler's own strip still follows the finger per event
                // (localUnit only invalidates this Canvas draw), and drag end always lands the
                // exact final value the gate may have swallowed.
                var lastEmitMs = 0L
                var emittedUnit = Float.NaN
                // AGG3-21: Compose delivers onDragCancel (not onDragEnd) whenever a competing
                // gesture detector claims the pointer mid-drag (e.g. a slightly-diagonal drag
                // reinterpreted by an ancestor as a different gesture). That must land the exact
                // final value exactly like a clean release, or the applied camera value sits up
                // to one 16 ms gate window behind the finger and visibly snaps back on the next
                // recomposition.
                val landFinalValue = {
                    isDragging = false
                    if (emittedUnit != localUnit) currentOnFractionChange(localUnit / totalUnits)
                }
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true; lastEmitMs = 0L; emittedUnit = Float.NaN },
                    onDragEnd = landFinalValue,
                    onDragCancel = landFinalValue,
                ) { change, dragAmount ->
                    change.consume()
                    // Content follows the finger: dragging left (negative dx) increases the value.
                    contUnit = (contUnit - dragAmount / pxPerUnit).coerceIn(0f, totalUnits.toFloat())
                    val next = if (snap) contUnit.roundToInt().toFloat() else contUnit
                    if (snap && next != localUnit) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    localUnit = next
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastEmitMs >= 16) {
                        lastEmitMs = now
                        emittedUnit = next
                        currentOnFractionChange(next / totalUnits)
                    }
                }
            },
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val visibleHalfUnits = (centerX / pxPerUnit).toInt() + 2
        val startUnit = (localUnit - visibleHalfUnits).toInt().coerceAtLeast(0)
        val endUnit = (localUnit + visibleHalfUnits).toInt().coerceAtMost(totalUnits)
        if (startUnit <= endUnit) {
            for (u in startUnit..endUnit) {
                val x = centerX + (u - localUnit) * pxPerUnit
                val isMajor = majorEvery > 0 && u % majorEvery == 0
                val tickHeightFraction = if (isMajor) 0.6f else 0.3f
                val halfTick = size.height * tickHeightFraction / 2f
                drawLine(
                    color = if (isMajor) majorColor else minorColor,
                    start = Offset(x, centerY - halfTick),
                    end = Offset(x, centerY + halfTick),
                    strokeWidth = if (isMajor) 2f else 1.2f,
                )
            }
        }

        // Fixed center indicator: a bright vertical needle + a small downward pointer above it.
        drawLine(
            color = indicatorColor,
            start = Offset(centerX, centerY - size.height * 0.36f),
            end = Offset(centerX, centerY + size.height * 0.36f),
            strokeWidth = 3f,
        )
        val triHalf = 4.dp.toPx()
        val triTop = centerY - size.height * 0.36f - triHalf * 1.6f
        val pointer = Path().apply {
            moveTo(centerX - triHalf, triTop)
            lineTo(centerX + triHalf, triTop)
            lineTo(centerX, triTop + triHalf * 1.6f)
            close()
        }
        drawPath(pointer, color = indicatorColor)
    }
}
