package com.hletrd.findx9tele.ui.controls

import android.util.Range
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
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
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.theme.CameraColors
import kotlin.math.roundToInt

/**
 * The five quick "Fn" manual dials (focus/shutter/ISO/WB/EV) — the signature element of the
 * camera UI. A horizontal row of value chips sits at rest; tapping one opens a tick-ruler slider
 * above the row where the current value is always centered under a fixed indicator and the ruler
 * scrolls beneath it as the user drags. Only one dial is open at a time.
 */
enum class DialType { FOCUS, SHUTTER, ISO, WB, EV, ZOOM }

@Composable
fun ManualDialCluster(
    state: CameraUiState,
    actions: CameraActions,
    onRequestWhiteBalanceSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFnMenu: () -> Unit = {},
    onDialOpenChange: (Boolean) -> Unit = {},
) {
    var openDial by remember { mutableStateOf<DialType?>(null) }
    val controls = state.controls
    val caps = state.caps
    val dialOpen = openDial != null

    LaunchedEffect(dialOpen) {
        onDialOpenChange(dialOpen)
    }

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

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AnimatedVisibility(
            visible = dialOpen,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            when (openDial) {
                DialType.FOCUS -> FocusRuler(controls = controls, caps = caps, onFocusSlider = actions::onFocusSlider)
                DialType.SHUTTER -> ShutterRuler(controls = controls, caps = caps, actions = actions)
                DialType.ISO -> IsoRuler(controls = controls, caps = caps, onIso = actions::onIso)
                DialType.WB -> WbRuler(controls = controls, onWbKelvin = actions::onWbKelvin)
                DialType.EV -> EvRuler(controls = controls, caps = caps, onEv = actions::onExposureCompensation)
                DialType.ZOOM -> ZoomRuler(controls = controls, caps = caps, onZoomRatio = actions::onZoomRatio)
                null -> Unit
            }
        }

        DialChipRow(
            state = state,
            openDial = openDial,
            onSelect = { type ->
                when {
                    type == DialType.WB && controls.wbMode != WbMode.MANUAL -> {
                        openDial = null
                        onRequestWhiteBalanceSheet()
                    }
                    // Tapping the Shutter dial takes shutter control → Shutter-priority (from P or ISO);
                    // in Manual it stays Manual (shutter already user-owned).
                    type == DialType.SHUTTER &&
                        (controls.exposureMode == ExposureMode.PROGRAM || controls.exposureMode == ExposureMode.ISO) -> {
                        actions.onExposureMode(ExposureMode.SHUTTER)
                        openDial = type
                    }
                    // Tapping the ISO dial takes ISO control → ISO-priority (from P or S); Manual stays.
                    type == DialType.ISO &&
                        (controls.exposureMode == ExposureMode.PROGRAM || controls.exposureMode == ExposureMode.SHUTTER) -> {
                        actions.onExposureMode(ExposureMode.ISO)
                        openDial = type
                    }
                    // Focus: default is continuous AF, so tapping Focus enters manual.
                    type == DialType.FOCUS && controls.focusMode != FocusMode.MANUAL -> {
                        actions.onFocusMode(FocusMode.MANUAL)
                        openDial = type
                    }
                    // EV compensation applies whenever AE meters (P/S/ISO); in full Manual it's inert.
                    type == DialType.EV && controls.exposureMode == ExposureMode.MANUAL -> openDial = null
                    else -> openDial = if (openDial == type) null else type
                }
            },
            actions = actions,
            onOpenFnMenu = onOpenFnMenu,
        )
    }
}

@Composable
private fun DialChipRow(
    state: CameraUiState,
    openDial: DialType?,
    onSelect: (DialType) -> Unit,
    actions: CameraActions,
    onOpenFnMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controls = state.controls
    val caps = state.caps
    val evStepValue = remember(caps?.evStep) {
        val step = caps?.evStep
        if (step == null || step.denominator == 0) 1f / 3f else step.numerator.toFloat() / step.denominator.toFloat()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            )
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
) {
    val controls = state.controls
    val caps = state.caps
    when (slot) {
        FnSlot.EXPOSURE_MODE -> DialChip(
            label = "AE",
            value = controls.exposureMode.letter,
            active = controls.exposureMode != ExposureMode.PROGRAM,
            enabled = true,
            onClick = { actions.onExposureMode(nextExposureMode(controls.exposureMode)) },
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
            enabled = controls.focusMode == FocusMode.MANUAL && (caps?.supportsManualFocus ?: false),
            onClick = { onSelect(DialType.FOCUS) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.SHUTTER -> DialChip(
            label = "SS",
            value = when {
                controls.exposureMode == ExposureMode.PROGRAM -> "A${autoShutterText(state)}"
                controls.autoShutterDriven -> "A${formatShutterSpeed(controls.exposureTimeNs)}"
                controls.shutterMode == ShutterMode.ANGLE -> "%.0f°".format(controls.shutterAngle)
                else -> formatShutterSpeed(controls.exposureTimeNs)
            },
            active = openDial == DialType.SHUTTER,
            enabled = controls.exposureMode == ExposureMode.SHUTTER || controls.exposureMode == ExposureMode.MANUAL,
            onClick = { onSelect(DialType.SHUTTER) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ISO -> DialChip(
            label = "ISO",
            value = when {
                controls.exposureMode == ExposureMode.PROGRAM -> "A${autoIsoText(state)}"
                controls.autoIsoDriven -> "A${controls.iso}"
                else -> controls.iso.toString()
            },
            active = openDial == DialType.ISO,
            enabled = controls.exposureMode == ExposureMode.ISO || controls.exposureMode == ExposureMode.MANUAL,
            onClick = { onSelect(DialType.ISO) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.WB -> DialChip(
            label = "WB",
            value = if (controls.wbMode == WbMode.MANUAL) "${controls.wbKelvin}K" else wbModeLabel(controls.wbMode),
            active = openDial == DialType.WB,
            enabled = true,
            onClick = { onSelect(DialType.WB) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.EV -> DialChip(
            label = "EV",
            value = "%+.1f".format(controls.exposureCompensation * evStepValue),
            active = openDial == DialType.EV,
            enabled = controls.exposureMode != ExposureMode.MANUAL,
            onClick = { onSelect(DialType.EV) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ZOOM -> DialChip(
            label = "Zoom",
            value = "%.1fx".format(controls.zoomRatio),
            active = openDial == DialType.ZOOM,
            enabled = caps?.zoomRatioRange != null,
            onClick = { onSelect(DialType.ZOOM) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.STABILIZATION -> DialChip(
            // Full words, not in-house abbreviations ("Stab"/"Steady" read as nonsense to camera
            // users — feedback). Values come from VideoStabMode.label (Off/Standard/Active).
            label = "Stabilization",
            value = state.videoStabMode.label,
            active = state.videoStabMode != VideoStabMode.OFF,
            enabled = true,
            onClick = { actions.onVideoStabMode(nextVideoStabMode(state.videoStabMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.DRIVE -> DialChip(
            label = "Drive",
            value = driveModeLabel(state.driveMode),
            active = state.driveMode != com.hletrd.findx9tele.camera.DriveMode.SINGLE,
            enabled = true,
            onClick = { actions.onDriveMode(nextDriveMode(state.driveMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.METERING -> DialChip(
            label = "Meter",
            value = meteringModeLabel(controls.meteringMode),
            active = controls.meteringMode != MeteringMode.MATRIX,
            enabled = true,
            onClick = { actions.onMeteringMode(nextMeteringMode(controls.meteringMode)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.PEAKING -> DialChip(
            label = "Peaking",
            value = if (state.focusPeaking) "On" else "Off",
            active = state.focusPeaking,
            enabled = true,
            onClick = { actions.onTogglePeaking(!state.focusPeaking) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.ZEBRA -> DialChip(
            label = "Zebra",
            value = if (state.zebra) "On" else "Off",
            active = state.zebra,
            enabled = true,
            onClick = { actions.onToggleZebra(!state.zebra) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.TRANSFER -> {
            val transferMutable = !state.isRecording && state.videoCodec == VideoCodec.HEVC
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
            enabled = true,
            onClick = { actions.onAudioScene(nextAudioScene(state.audioScene)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.GRID -> DialChip(
            label = "Grid",
            value = gridTypeLabel(state.grid),
            active = state.grid != GridType.NONE,
            enabled = true,
            onClick = { actions.onGridType(nextGridType(state.grid)) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.LEVEL -> DialChip(
            label = "Level",
            value = if (state.level) "On" else "Off",
            active = state.level,
            enabled = true,
            onClick = { actions.onToggleLevel(!state.level) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.PUNCH_IN -> DialChip(
            label = "Loupe",
            value = if (state.punchIn) "On" else "Off",
            active = state.punchIn,
            enabled = true,
            onClick = { actions.onTogglePunchIn(!state.punchIn) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.TELECONVERTER -> DialChip(
            label = "Tele",
            value = if (state.teleconverterMode) "300" else "Off",
            active = state.teleconverterMode,
            enabled = !state.isRecording,
            onClick = { if (!state.isRecording) actions.onToggleTeleconverter(!state.teleconverterMode) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.OPEN_GATE -> DialChip(
            label = "Open Gate",
            value = if (state.openGate) "4:3" else "Off",
            active = state.openGate,
            enabled = state.mode == CaptureMode.VIDEO && !state.isRecording,
            onClick = { if (state.mode == CaptureMode.VIDEO && !state.isRecording) actions.onToggleOpenGate(!state.openGate) },
            onLongClick = onOpenFnMenu,
        )
        FnSlot.FRAME_LINES -> DialChip(
            label = "Frame",
            value = state.frameLines.label,
            active = state.frameLines != FrameLineType.OFF,
            enabled = true,
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
) {
    val bg = if (active) CameraColors.TextPrimary else CameraColors.Pill.copy(alpha = 0.7f)
    val fg = when {
        active -> Color.Black
        enabled -> CameraColors.TextPrimary
        else -> CameraColors.TextSecondary
    }
    Row(
        modifier = modifier
            // Fixed floor width + centered content so a chip's OWN value changes (e.g. "Auto" ↔
            // "1/125s", "ISO 100" ↔ "ISO 12800") never resize it and shift the whole row.
            .defaultMinSize(minWidth = 76.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (!active) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)) else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            value,
            color = fg.copy(alpha = if (active) 1f else 0.75f),
            fontSize = 12.sp,
            lineHeight = 14.sp,
        )
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
        Text(
            text = value,
            color = CameraColors.ManualActive,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FocusRuler(controls: ManualControls, caps: CameraCaps?, onFocusSlider: (Float) -> Unit) {
    val minDiopters = caps?.minFocusDistanceDiopters ?: 0f
    val enabled = controls.focusMode == FocusMode.MANUAL && minDiopters > 0f
    val fraction = FocusMapping.dioptersToSlider(controls.focusDistanceDiopters, minDiopters)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout(formatFocusRelative(controls.focusDistanceDiopters, minDiopters))
        // Relative 0..100 scale (0 = ∞); shorter travel than the default so fine focus near infinity
        // — where the afocal converter lands — is reachable without a marathon drag.
        RulerSlider(
            fraction = fraction,
            onFractionChange = onFocusSlider,
            enabled = enabled,
            totalUnits = 100,
            majorEvery = 10,
        )
    }
}

@Composable
private fun ShutterRuler(controls: ManualControls, caps: CameraCaps?, actions: CameraActions) {
    // The shutter is user-editable in Shutter-priority and Manual; in ISO priority it's app-driven, so
    // the ruler is shown but inert. ANGLE is a cine convention — same exposure, expressed as a shutter
    // angle relative to the frame rate (180° = 1/(2·fps)).
    val enabled = controls.exposureMode == ExposureMode.SHUTTER || controls.exposureMode == ExposureMode.MANUAL
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SpeedAngleToggle(mode = controls.shutterMode, enabled = enabled, onSelect = actions::onShutterMode)
        if (controls.shutterMode == ShutterMode.ANGLE) {
            val fraction = ((controls.shutterAngle - 1f) / 359f).coerceIn(0f, 1f)
            val readout = "%.0f°  (%s)".format(controls.shutterAngle, formatShutterSpeed(controls.effectiveExposureNsForDisplay()))
            RulerReadout(if (controls.autoShutterDriven) "A $readout" else readout)
            RulerSlider(
                fraction = fraction,
                onFractionChange = { f -> actions.onShutterAngle((1f + f * 359f).coerceIn(1f, 360f)) },
                enabled = enabled,
            )
        } else {
            val range = caps?.exposureTimeRange ?: Range(controls.exposureTimeNs, controls.exposureTimeNs)
            val stops = remember(range.lower, range.upper, controls.exposureStep) { shutterStops(range, controls.exposureStep.ev) }
            val n = stops.size
            val idx = remember(controls.exposureTimeNs, stops) {
                stops.indices.minByOrNull { kotlin.math.abs(stops[it] - controls.exposureTimeNs) } ?: 0
            }
            val fraction = if (n <= 1) 0f else idx.toFloat() / (n - 1)
            RulerReadout(if (controls.autoShutterDriven) "A ${formatShutterSpeed(controls.exposureTimeNs)}" else formatShutterSpeed(controls.exposureTimeNs))
            RulerSlider(
                fraction = fraction,
                onFractionChange = { f -> actions.onShutterNs(stops[(f * (n - 1)).roundToInt().coerceIn(0, n - 1)]) },
                enabled = enabled,
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ShutterMode.entries.forEach { m ->
            val on = mode == m
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) CameraColors.ManualActive else CameraColors.Pill.copy(alpha = 0.7f))
                    .clickable(enabled = enabled) { onSelect(m) }
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

private fun roundToSignificant(v: Double, sig: Int): Double {
    if (v <= 0.0) return v
    val digits = Math.ceil(Math.log10(v)).toInt()
    val mag = Math.pow(10.0, (sig - digits).toDouble())
    return Math.round(v * mag) / mag
}

/** ISO values [stepEv] EV apart across [range], anchored at 100, rounded to 2 significant figures so
 *  they read as conventional stops (100, 125, 160, 200, …). Hardware bounds always included. */
private fun isoStops(range: Range<Int>, stepEv: Float): IntArray {
    if (range.lower >= range.upper || stepEv <= 0f) return intArrayOf(range.lower)
    val set = sortedSetOf(range.lower, range.upper)
    val ln2 = Math.log(2.0)
    val kLo = Math.ceil(Math.log(range.lower / 100.0) / ln2 / stepEv).toInt()
    val kHi = Math.floor(Math.log(range.upper / 100.0) / ln2 / stepEv).toInt()
    for (k in kLo..kHi) {
        val nice = roundToSignificant(100.0 * Math.pow(2.0, k * stepEv.toDouble()), 2).roundToInt()
        if (nice > range.lower && nice < range.upper) set.add(nice)
    }
    return set.toIntArray()
}

/** Shutter times (ns) [stepEv] EV apart across [range], anchored at 1 s. Hardware bounds included. */
private fun shutterStops(range: Range<Long>, stepEv: Float): LongArray {
    if (range.lower >= range.upper || stepEv <= 0f) return longArrayOf(range.lower)
    val set = sortedSetOf(range.lower, range.upper)
    val ln2 = Math.log(2.0)
    val anchor = 1_000_000_000.0
    val kLo = Math.ceil(Math.log(range.lower / anchor) / ln2 / stepEv).toInt()
    val kHi = Math.floor(Math.log(range.upper / anchor) / ln2 / stepEv).toInt()
    for (k in kLo..kHi) {
        val ns = Math.round(anchor * Math.pow(2.0, k * stepEv.toDouble()))
        if (ns > range.lower && ns < range.upper) set.add(ns)
    }
    return set.toLongArray()
}

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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout(if (controls.autoIsoDriven) "A ISO ${controls.iso}" else "ISO ${controls.iso}")
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f -> onIso(stops[(f * (n - 1)).roundToInt().coerceIn(0, n - 1)]) },
            enabled = enabled,
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout("${controls.wbKelvin}K")
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f ->
                val kelvin = (WB_KELVIN_MIN + f * (WB_KELVIN_MAX - WB_KELVIN_MIN)).roundToInt().coerceIn(2000, 10000)
                onWbKelvin(kelvin)
            },
            enabled = controls.wbMode == WbMode.MANUAL,
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout("%+.1f EV".format(controls.exposureCompensation * stepValue))
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f ->
                val ev = (lo + f * (hi - lo)).roundToInt().coerceIn(lo, hi)
                onEv(ev)
            },
            enabled = controls.exposureMode != ExposureMode.MANUAL,
            totalUnits = (hi - lo).coerceAtLeast(1),
            majorEvery = majorEvery,
            snap = true,
        )
    }
}

@Composable
private fun ZoomRuler(controls: ManualControls, caps: CameraCaps?, onZoomRatio: (Float) -> Unit) {
    val range = caps?.zoomRatioRange ?: Range(1f, 1f)
    val lo = range.lower
    val hi = range.upper
    val fraction = if (hi <= lo) 0f else ((controls.zoomRatio - lo) / (hi - lo)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout("%.1f×".format(controls.zoomRatio))
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f -> onZoomRatio((lo + f * (hi - lo)).coerceIn(lo, hi)) },
            enabled = hi > lo,
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
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp)
            .pointerInput(enabled, totalUnits, pxPerUnit, snap) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    // Content follows the finger: dragging left (negative dx) increases the value.
                    contUnit = (contUnit - dragAmount / pxPerUnit).coerceIn(0f, totalUnits.toFloat())
                    val next = if (snap) contUnit.roundToInt().toFloat() else contUnit
                    if (snap && next != localUnit) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    localUnit = next
                    onFractionChange(localUnit / totalUnits)
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
