package com.hletrd.findx9tele.ui.controls

import android.util.Range
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.ShutterMode
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
enum class DialType { FOCUS, SHUTTER, ISO, WB, EV }

@Composable
fun ManualDialCluster(
    state: CameraUiState,
    actions: CameraActions,
    onRequestWhiteBalanceSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openDial by remember { mutableStateOf<DialType?>(null) }
    val controls = state.controls
    val caps = state.caps

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AnimatedVisibility(
            visible = openDial != null,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            when (openDial) {
                DialType.FOCUS -> FocusRuler(controls = controls, caps = caps, onFocusSlider = actions::onFocusSlider)
                DialType.SHUTTER -> ShutterRuler(controls = controls, caps = caps, actions = actions)
                DialType.ISO -> IsoRuler(controls = controls, caps = caps, onIso = actions::onIso)
                DialType.WB -> WbRuler(controls = controls, onWbKelvin = actions::onWbKelvin)
                DialType.EV -> EvRuler(controls = controls, caps = caps, onEv = actions::onExposureCompensation)
                null -> Unit
            }
        }

        DialChipRow(
            state = state,
            openDial = openDial,
            onSelect = { type ->
                if (type == DialType.WB && controls.wbMode != WbMode.MANUAL) {
                    openDial = null
                    onRequestWhiteBalanceSheet()
                } else {
                    openDial = if (openDial == type) null else type
                }
            },
        )
    }
}

@Composable
private fun DialChipRow(
    state: CameraUiState,
    openDial: DialType?,
    onSelect: (DialType) -> Unit,
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
        DialChip(
            label = "Focus",
            value = formatFocusDistance(controls.focusDistanceDiopters),
            active = openDial == DialType.FOCUS,
            enabled = controls.focusMode == FocusMode.MANUAL && (caps?.supportsManualFocus ?: false),
            onClick = { onSelect(DialType.FOCUS) },
        )
        DialChip(
            label = "Shutter",
            value = if (controls.shutterMode == ShutterMode.ANGLE) {
                "%.0f°".format(controls.shutterAngle)
            } else {
                formatShutterSpeed(controls.exposureTimeNs)
            },
            active = openDial == DialType.SHUTTER,
            enabled = !controls.autoExposure,
            onClick = { onSelect(DialType.SHUTTER) },
        )
        DialChip(
            label = "ISO",
            value = controls.iso.toString(),
            active = openDial == DialType.ISO,
            enabled = !controls.autoExposure,
            onClick = { onSelect(DialType.ISO) },
        )
        DialChip(
            label = "WB",
            value = if (controls.wbMode == WbMode.MANUAL) "${controls.wbKelvin}K" else wbModeLabel(controls.wbMode),
            active = openDial == DialType.WB,
            enabled = true,
            onClick = { onSelect(DialType.WB) },
        )
        DialChip(
            label = "EV",
            value = "%+.1f".format(controls.exposureCompensation * evStepValue),
            active = openDial == DialType.EV,
            enabled = controls.autoExposure,
            onClick = { onSelect(DialType.EV) },
        )
    }
}

@Composable
private fun DialChip(
    label: String,
    value: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
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
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (!active) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, color = fg.copy(alpha = if (active) 1f else 0.75f), style = MaterialTheme.typography.labelMedium)
    }
}

// ---------------------------------------------------------------------------
// Per-control ruler wrappers: translate the real domain (diopters/ns/ISO/K/EV) to/from the
// ruler's normalized 0..1 travel and format the live readout above it.
// ---------------------------------------------------------------------------

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
        RulerReadout(formatFocusDistance(controls.focusDistanceDiopters))
        RulerSlider(fraction = fraction, onFractionChange = onFocusSlider, enabled = enabled)
    }
}

@Composable
private fun ShutterRuler(controls: ManualControls, caps: CameraCaps?, actions: CameraActions) {
    val enabled = !controls.autoExposure
    if (controls.shutterMode == ShutterMode.ANGLE) {
        val fraction = ((controls.shutterAngle - 1f) / 359f).coerceIn(0f, 1f)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RulerReadout("%.0f°  (%s)".format(controls.shutterAngle, formatShutterSpeed(controls.effectiveExposureNsForDisplay())))
            RulerSlider(
                fraction = fraction,
                onFractionChange = { f -> actions.onShutterAngle((1f + f * 359f).coerceIn(1f, 360f)) },
                enabled = enabled,
            )
        }
    } else {
        val range = caps?.exposureTimeRange ?: Range(controls.exposureTimeNs, controls.exposureTimeNs)
        val fraction = shutterNsToSlider(controls.exposureTimeNs, range)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RulerReadout(formatShutterSpeed(controls.exposureTimeNs))
            RulerSlider(
                fraction = fraction,
                onFractionChange = { f -> actions.onShutterNs(sliderToShutterNs(f, range)) },
                enabled = enabled,
            )
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

@Composable
private fun IsoRuler(controls: ManualControls, caps: CameraCaps?, onIso: (Int) -> Unit) {
    val range = caps?.isoRange ?: Range(controls.iso, controls.iso)
    val enabled = !controls.autoExposure
    val fraction = if (range.lower >= range.upper) {
        0f
    } else {
        ((controls.iso - range.lower).toFloat() / (range.upper - range.lower).toFloat()).coerceIn(0f, 1f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RulerReadout("ISO ${controls.iso}")
        RulerSlider(
            fraction = fraction,
            onFractionChange = { f ->
                val iso = (range.lower + f * (range.upper - range.lower)).roundToInt().coerceIn(range.lower, range.upper)
                onIso(iso)
            },
            enabled = enabled,
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
            enabled = controls.autoExposure,
            totalUnits = (hi - lo).coerceAtLeast(1),
            majorEvery = majorEvery,
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
) {
    val density = LocalDensity.current
    val pxPerUnit = remember(density, tickSpacing) { with(density) { tickSpacing.toPx() } }
    var isDragging by remember { mutableStateOf(false) }
    var localUnit by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f) * totalUnits) }
    if (!isDragging) {
        localUnit = fraction.coerceIn(0f, 1f) * totalUnits
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
            .pointerInput(enabled, totalUnits, pxPerUnit) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    // Content follows the finger: dragging left (negative dx) increases the value.
                    localUnit = (localUnit - dragAmount / pxPerUnit).coerceIn(0f, totalUnits.toFloat())
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
