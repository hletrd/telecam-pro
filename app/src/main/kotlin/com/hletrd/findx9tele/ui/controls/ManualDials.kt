package com.hletrd.findx9tele.ui.controls

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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.ControlAvailability
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.controlAvailability
import com.hletrd.findx9tele.camera.controlCapabilities
import com.hletrd.findx9tele.camera.exposureUpperBoundForCaptureMode
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.FnEntryAnchor
import com.hletrd.findx9tele.ui.fnEntryAnchor
import com.hletrd.findx9tele.ui.formatDisplayZoom
import com.hletrd.findx9tele.ui.overlays.HUD_TEXT_SCRIM_ALPHA
import com.hletrd.findx9tele.ui.theme.CameraColors
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

/** The fixed compact Fn glyph follows the shared animated overlay angle without sign/phase drift. */
internal fun fnMenuGlyphRotation(overlayRotation: Float): Float = overlayRotation

@Composable
fun ManualDialCluster(
    state: CameraUiState,
    actions: CameraActions,
    openDial: DialType?,
    onSelectDial: (DialType) -> Unit,
    onCloseDial: () -> Unit,
    glyphRotation: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenFnMenu: () -> Unit = {},
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
            actions.onTogglePunchIn(true)
        } else if (!focusOpen && loupeAutoOn) {
            loupeAutoOn = false
            if (state.punchIn) actions.onTogglePunchIn(false)
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
    val caps = state.caps
    val evStepValue = remember(caps?.evStep) {
        val step = caps?.evStep
        if (step == null || step.denominator == 0) 1f / 3f else step.numerator.toFloat() / step.denominator.toFloat()
    }
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.activeFnSlots.forEach { slot ->
                    FnDialChip(
                        slot = slot,
                        state = state,
                        openDial = openDial,
                        evStepValue = evStepValue,
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
    val activate = onClick
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = "Open function menu"
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
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(CameraColors.Pill.copy(alpha = 0.72f))
                .border(1.dp, CameraColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Fn",
                color = CameraColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.rotate(fnMenuGlyphRotation(glyphRotation)),
            )
        }
    }
}

@Composable
private fun CompactDialCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = "Close adjustment"
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
                .background(CameraColors.Pill.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = CameraColors.TextPrimary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FnDialChip(
    slot: FnSlot,
    state: CameraUiState,
    openDial: DialType?,
    evStepValue: Float,
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
            label = "AE",
            value = controls.exposureMode.letter,
            active = controls.exposureMode != ExposureMode.PROGRAM,
            enabled = policyEnabled && availability.exposureModes.size > 1,
            onClick = { actions.onExposureMode(nextAvailable(controls.exposureMode, availability.exposureModes)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.FOCUS -> DialChip(
            label = focusModeLabel(controls.focusMode),
            value = formatFocusRelative(
                if (controls.focusMode == FocusMode.MANUAL) controls.focusDistanceDiopters
                else state.liveFocusDiopters ?: controls.focusDistanceDiopters,
                caps?.minFocusDistanceDiopters ?: 0f,
            ),
            active = openDial == DialType.FOCUS,
            enabled = policyEnabled && quickManualDialEnabled(DialType.FOCUS, availability),
            onClick = { onSelect(DialType.FOCUS) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.SHUTTER -> DialChip(
            label = "SS",
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
            label = "ISO",
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
            label = "WB",
            value = if (controls.wbMode == WbMode.MANUAL) "${controls.wbKelvin}K" else wbModeLabel(controls.wbMode),
            active = openDial == DialType.WB,
            enabled = policyEnabled && whiteBalanceFnChipEnabled(controls.wbMode, availability),
            onClick = { onSelect(DialType.WB) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.EV -> DialChip(
            label = "EV",
            value = "%+.1f".format(Locale.US, controls.exposureCompensation * evStepValue),
            active = openDial == DialType.EV,
            enabled = policyEnabled && quickManualDialEnabled(DialType.EV, availability),
            onClick = { onSelect(DialType.EV) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ZOOM -> DialChip(
            label = "Zoom",
            value = formatDisplayZoom(
                controls.zoomRatio,
                state.teleconverterMode,
                state.caps?.equivalentFocalMm,
                frontFacing = state.facing == com.hletrd.findx9tele.camera.CameraFacing.FRONT,
            ),
            active = openDial == DialType.ZOOM,
            enabled = policyEnabled && quickManualDialEnabled(DialType.ZOOM, availability),
            onClick = { onSelect(DialType.ZOOM) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.STABILIZATION -> DialChip(
            // Full words, not in-house abbreviations ("Stab"/"Steady" read as nonsense to camera
            // users — feedback). Values come from VideoStabMode.label (Off/Standard/Active).
            label = "Stabilization",
            value = state.videoStabMode.label,
            active = state.videoStabMode != VideoStabMode.OFF,
            enabled = policyEnabled,
            onClick = { actions.onVideoStabMode(nextVideoStabMode(state.videoStabMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.DRIVE -> DialChip(
            label = "Drive",
            value = driveModeLabel(state.driveMode),
            active = state.driveMode != com.hletrd.findx9tele.camera.DriveMode.SINGLE,
            enabled = policyEnabled,
            onClick = { actions.onDriveMode(nextDriveMode(state.driveMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.METERING -> DialChip(
            label = "Meter",
            value = meteringModeLabel(controls.meteringMode),
            active = controls.meteringMode != MeteringMode.MATRIX,
            enabled = policyEnabled && availability.meteringModes.size > 1,
            onClick = { actions.onMeteringMode(nextAvailable(controls.meteringMode, availability.meteringModes)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.PEAKING -> DialChip(
            label = "Peaking",
            value = if (state.focusPeaking) "On" else "Off",
            active = state.focusPeaking,
            enabled = policyEnabled,
            onClick = { actions.onTogglePeaking(!state.focusPeaking) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ZEBRA -> DialChip(
            label = "Zebra",
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
                label = "Gamma",
                value = transferLabelShort(state.transfer),
                active = state.transfer != ColorTransfer.SDR,
                enabled = transferMutable,
                onClick = { if (transferMutable) actions.onTransfer(nextTransfer(state.transfer)) },
                onLongClick = onOpenFnMenu,
            )
        }
        FnSlot.AUDIO_SCENE -> DialChip(
            label = "Audio",
            value = state.audioScene.label,
            active = state.audioScene != com.hletrd.findx9tele.camera.AudioScene.STANDARD,
            enabled = policyEnabled,
            onClick = { actions.onAudioScene(nextAudioScene(state.audioScene)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.GRID -> DialChip(
            label = "Grid",
            value = gridTypeLabel(state.grid),
            active = state.grid != GridType.NONE,
            enabled = policyEnabled,
            onClick = { actions.onGridType(nextGridType(state.grid)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.LEVEL -> DialChip(
            label = "Level",
            value = if (state.level) "On" else "Off",
            active = state.level,
            enabled = policyEnabled,
            onClick = { actions.onToggleLevel(!state.level) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.PUNCH_IN -> DialChip(
            label = "Loupe",
            value = if (state.punchIn) "On" else "Off",
            active = state.punchIn,
            enabled = policyEnabled,
            onClick = { actions.onTogglePunchIn(!state.punchIn) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.TELECONVERTER -> DialChip(
            label = "Tele",
            value = if (state.teleconverterMode) "300 mm" else "Off",
            active = state.teleconverterMode,
            enabled = policyEnabled,
            onClick = { if (policyEnabled) actions.onToggleTeleconverter(!state.teleconverterMode) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.OPEN_GATE -> DialChip(
            label = "Open Gate",
            value = if (state.openGate) "4:3" else "Off",
            active = state.openGate,
            enabled = policyEnabled,
            onClick = { if (policyEnabled) actions.onToggleOpenGate(!state.openGate) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.FRAME_LINES -> DialChip(
            label = "Frame",
            value = state.frameLines.label,
            active = state.frameLines != FrameLineType.OFF,
            enabled = policyEnabled,
            onClick = { actions.onFrameLines(nextFrameLine(state.frameLines)) },
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
    // Auto-driven value ("A9100" class): the qualifier renders as a smaller, dimmer A so the
    // actual value stays scannable ("A9100" read as one blob — user-reported), and accessibility
    // hears the honest word instead of a letter glued to digits.
    autoValue: Boolean = false,
) {
    val activate = onClick
    val bg = if (active) CameraColors.TextPrimary else CameraColors.Pill.copy(alpha = 0.7f)
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
                contentDescription = label
                stateDescription = if (autoValue) "Auto $value" else value
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            }
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = "Open Fn menu",
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
                    if (!active) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)) else Modifier,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = fg,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val valueColor = fg.copy(alpha = if (active) 1f else 0.75f)
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
                fontSize = 11.sp,
                lineHeight = 13.sp,
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
private fun formatFocusRelative(diopters: Float, minDiopters: Float): String {
    if (minDiopters <= 0f) return "∞"
    val f = FocusMapping.dioptersToSlider(diopters, minDiopters)
    return if (f <= 0.005f) "∞" else "∞+${(f * 100).roundToInt()}"
}

@Composable
private fun RulerReadout(value: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The one number the photographer is actively adjusting sits near the TOP of the bottom
        // cluster's gradient, where the scrim is nearly transparent — over sky/snow it competed
        // with scene luminance unprotected while every audited HUD sibling had a tested pill.
        Text(
            text = value,
            color = CameraColors.ManualActive,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                .padding(horizontal = 14.dp, vertical = 2.dp),
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
            RulerReadout(if (controls.autoShutterDriven) "A $readout" else readout)
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
            RulerReadout(if (controls.autoShutterDriven) "A $readout" else readout)
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
                        .background(if (on) CameraColors.ManualActive else CameraColors.Pill.copy(alpha = 0.7f))
                        .padding(horizontal = 14.dp, vertical = 5.dp),
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

internal fun roundToSignificant(v: Double, sig: Int): Double {
    if (v <= 0.0) return v
    val digits = Math.ceil(Math.log10(v)).toInt()
    val mag = Math.pow(10.0, (sig - digits).toDouble())
    return Math.round(v * mag) / mag
}

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
        RulerReadout(if (controls.autoIsoDriven) "A ISO ${controls.iso}" else "ISO ${controls.iso}")
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
    val stepValue = remember(caps?.evStep) {
        val step = caps?.evStep
        if (step == null || step.denominator == 0) 1f / 3f else step.numerator.toFloat() / step.denominator.toFloat()
    }
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

@Composable
private fun ZoomRuler(
    controls: ManualControls,
    caps: CameraCaps?,
    teleconverter: Boolean = false,
    onZoomRatio: (Float) -> Unit,
) {
    // TELE reads and drags on the converter-equivalent scale (13–60×); the callback still writes
    // the LENS-LOCAL ratio the engine owns. Other modes are 1:1.
    val base = if (teleconverter) com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE else 1f
    val range = caps?.zoomRatioRange ?: Range(1f, 1f)
    val lo = range.lower * base
    val hi = if (teleconverter) {
        minOf(range.upper * base, com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM)
    } else {
        range.upper
    }
    val display = controls.zoomRatio * base
    val fraction = if (hi <= lo) 0f else ((display - lo) / (hi - lo)).coerceIn(0f, 1f)
    val readout = "%.1f×".format(Locale.US, display)
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
    tickSpacing: Dp = 12.dp,
    accentColor: Color = CameraColors.ManualActive,
    snap: Boolean = false,
    semanticLabel: String = "Value",
    valueDescription: String = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()} percent",
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val pxPerUnit = remember(density, tickSpacing) { with(density) { tickSpacing.toPx() } }
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
    val minorColor = Color.White.copy(alpha = if (enabled) 0.28f else 0.12f)
    val majorColor = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f)
    val indicatorColor = if (enabled) accentColor else CameraColors.TextSecondary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // The tested HUD floor, not 0.35: the ruler band sits directly over the live preview
            // and its minor ticks (0.28 white) washed out against bright scenes — same class as
            // the 05486cb scrim sweep, which this control originally missed.
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(16.dp))
            // TalkBack: a bare Canvas is invisible to accessibility services — every manual dial
            // rides this control, so expose it as an adjustable value with a set action.
            .progressSemantics(value = fraction.coerceIn(0f, 1f), valueRange = 0f..1f)
            .semantics {
                contentDescription = semanticLabel
                stateDescription = valueDescription
                if (!enabled) disabled()
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    onFractionChange(target.coerceIn(0f, 1f))
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
                    if (emittedUnit != localUnit) onFractionChange(localUnit / totalUnits)
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
                        onFractionChange(next / totalUnits)
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
