package com.hletrd.findx9tele.ui.controls

import android.util.Range
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.ui.CameraActions
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------

/**
 * Manual-focus slider mapped through [FocusMapping]. Displays the resolved distance in
 * meters/centimeters, or "∞" at zero diopters. Disabled while AF is active or the lens has no
 * manual-focus range (fixed-focus, [minFocusDiopters] <= 0).
 */
@Composable
fun FocusSlider(
    focusDistanceDiopters: Float,
    minFocusDiopters: Float,
    autoFocus: Boolean,
    onFocusSlider: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderPos = remember(focusDistanceDiopters, minFocusDiopters) {
        FocusMapping.dioptersToSlider(focusDistanceDiopters, minFocusDiopters)
    }
    val distanceLabel = remember(focusDistanceDiopters) { formatFocusDistance(focusDistanceDiopters) }
    val enabled = !autoFocus && minFocusDiopters > 0f
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("초점", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(distanceLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = sliderPos,
            onValueChange = onFocusSlider,
            enabled = enabled,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** AF (continuous autofocus) / MF (manual, driven by [FocusSlider]) toggle. */
@Composable
fun AfMfToggle(
    autoFocus: Boolean,
    onToggleAutoFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AutoManualToggle(
        auto = autoFocus,
        onToggle = onToggleAutoFocus,
        modifier = modifier,
        autoLabel = "AF",
        manualLabel = "MF",
    )
}

private fun formatFocusDistance(diopters: Float): String {
    val meters = FocusMapping.dioptersToMeters(diopters)
    return when {
        meters.isInfinite() -> "∞"
        meters < 1f -> "${(meters * 100).roundToInt()}cm"
        else -> "%.2fm".format(meters)
    }
}

// ---------------------------------------------------------------------------
// Shared small building blocks
// ---------------------------------------------------------------------------

/** Small reusable Auto/Manual segmented toggle used by focus, exposure, and white-balance rows. */
@Composable
fun AutoManualToggle(
    auto: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    autoLabel: String = "자동",
    manualLabel: String = "수동",
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = auto, onClick = { onToggle(true) }, label = { Text(autoLabel) })
        FilterChip(selected = !auto, onClick = { onToggle(false) }, label = { Text(manualLabel) })
    }
}

/** Label + Auto/Manual toggle + value readout header, with a slider (or other content) beneath it. */
@Composable
private fun ControlRow(
    label: String,
    valueLabel: String,
    auto: Boolean,
    onToggleAuto: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
            AutoManualToggle(auto = auto, onToggle = onToggleAuto)
            Text(valueLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        content()
    }
}

// ---------------------------------------------------------------------------
// Exposure: ISO / Shutter / EV
// ---------------------------------------------------------------------------

/**
 * ISO (sensor sensitivity) row. Manual only while [autoExposure] is false; the toggle is shared
 * with [ShutterRow] and [EvRow] since Camera2 exposes a single manual-sensor on/off switch.
 */
@Composable
fun IsoRow(
    iso: Int,
    isoRange: Range<Int>?,
    autoExposure: Boolean,
    onIsoChange: (Int) -> Unit,
    onToggleAutoExposure: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = isoRange ?: Range(iso, iso)
    ControlRow(
        label = "ISO",
        valueLabel = iso.toString(),
        auto = autoExposure,
        onToggleAuto = onToggleAutoExposure,
        modifier = modifier,
    ) {
        Slider(
            value = iso.toFloat().coerceIn(range.lower.toFloat(), range.upper.toFloat()),
            onValueChange = { onIsoChange(it.roundToInt()) },
            enabled = !autoExposure,
            valueRange = range.lower.toFloat()..range.upper.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Shutter-speed row (nanoseconds), displayed as "1/x s", mapped log-scale onto the slider. */
@Composable
fun ShutterRow(
    exposureTimeNs: Long,
    exposureTimeRange: Range<Long>?,
    autoExposure: Boolean,
    onShutterNs: (Long) -> Unit,
    onToggleAutoExposure: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = exposureTimeRange ?: Range(exposureTimeNs, exposureTimeNs)
    val sliderPos = remember(exposureTimeNs, range) { shutterNsToSlider(exposureTimeNs, range) }
    ControlRow(
        label = "셔터",
        valueLabel = formatShutterSpeed(exposureTimeNs),
        auto = autoExposure,
        onToggleAuto = onToggleAutoExposure,
        modifier = modifier,
    ) {
        Slider(
            value = sliderPos,
            onValueChange = { onShutterNs(sliderToShutterNs(it, range)) },
            enabled = !autoExposure,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun shutterNsToSlider(ns: Long, range: Range<Long>): Float {
    if (range.lower >= range.upper) return 0f
    val lo = ln(range.lower.toDouble())
    val hi = ln(range.upper.toDouble())
    val v = ln(ns.coerceIn(range.lower, range.upper).toDouble())
    return ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToShutterNs(slider: Float, range: Range<Long>): Long {
    if (range.lower >= range.upper) return range.lower
    val lo = ln(range.lower.toDouble())
    val hi = ln(range.upper.toDouble())
    val v = exp(lo + slider.coerceIn(0f, 1f) * (hi - lo))
    return v.roundToLong().coerceIn(range.lower, range.upper)
}

private fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) {
        "%.1fs".format(seconds)
    } else {
        val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        "1/${denominator}s"
    }
}

/** Exposure-compensation row; meaningful while [autoExposure] is true (manual ISO/shutter otherwise). */
@Composable
fun EvRow(
    exposureCompensation: Int,
    evRange: Range<Int>,
    evStep: Rational,
    autoExposure: Boolean,
    onExposureCompensation: (Int) -> Unit,
    onToggleAutoExposure: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stepValue = evStep.numerator.toFloat() / evStep.denominator.toFloat()
    val evLabel = "%+.1f EV".format(exposureCompensation * stepValue)
    val lower = minOf(evRange.lower, evRange.upper).toFloat()
    val upper = maxOf(evRange.lower, evRange.upper).toFloat()
    ControlRow(
        label = "EV",
        valueLabel = evLabel,
        auto = autoExposure,
        onToggleAuto = onToggleAutoExposure,
        modifier = modifier,
    ) {
        Slider(
            value = exposureCompensation.toFloat().coerceIn(lower, upper),
            onValueChange = { onExposureCompensation(it.roundToInt()) },
            enabled = autoExposure,
            valueRange = lower..upper,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// White balance
// ---------------------------------------------------------------------------

/** White-balance Kelvin row (2000K-10000K) with an AWB auto/manual toggle. */
@Composable
fun WbRow(
    wbKelvin: Int,
    autoWhiteBalance: Boolean,
    onWbKelvin: (Int) -> Unit,
    onToggleAutoWb: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ControlRow(
        label = "WB",
        valueLabel = "${wbKelvin}K",
        auto = autoWhiteBalance,
        onToggleAuto = onToggleAutoWb,
        modifier = modifier,
    ) {
        Slider(
            value = wbKelvin.toFloat().coerceIn(2000f, 10000f),
            onValueChange = { onWbKelvin(it.roundToInt()) },
            enabled = !autoWhiteBalance,
            valueRange = 2000f..10000f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Transfer / formats / audio / overlays
// ---------------------------------------------------------------------------

/** HLG / LOG transfer-function selector. */
@Composable
fun TransferSelector(
    transfer: ColorTransfer,
    onTransfer: (ColorTransfer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = transfer == ColorTransfer.HLG,
            onClick = { onTransfer(ColorTransfer.HLG) },
            label = { Text("HLG") },
        )
        FilterChip(
            selected = transfer == ColorTransfer.LOG,
            onClick = { onTransfer(ColorTransfer.LOG) },
            label = { Text("LOG") },
        )
    }
}

/** HEIF / DNG output-format toggles; both may be enabled simultaneously. */
@Composable
fun PhotoFormatToggles(
    formats: PhotoFormats,
    onSetPhotoFormats: (PhotoFormats) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = formats.heif,
            onClick = { onSetPhotoFormats(formats.copy(heif = !formats.heif)) },
            label = { Text("HEIF") },
        )
        FilterChip(
            selected = formats.dngRaw,
            onClick = { onSetPhotoFormats(formats.copy(dngRaw = !formats.dngRaw)) },
            label = { Text("DNG") },
        )
    }
}

/** Toggle for recording audio alongside video. */
@Composable
fun RecordAudioToggle(
    enabled: Boolean,
    onToggleRecordAudio: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("오디오 녹음", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Switch(checked = enabled, onCheckedChange = onToggleRecordAudio)
    }
}

/** Overlay visibility toggles: focus peaking, zebra, grid, level, and punch-in zoom. */
@Composable
fun OverlayToggles(
    peaking: Boolean,
    zebra: Boolean,
    grid: Boolean,
    level: Boolean,
    punchIn: Boolean,
    onTogglePeaking: (Boolean) -> Unit,
    onToggleZebra: (Boolean) -> Unit,
    onToggleGrid: (Boolean) -> Unit,
    onToggleLevel: (Boolean) -> Unit,
    onTogglePunchIn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = peaking, onClick = { onTogglePeaking(!peaking) }, label = { Text("피킹") })
        FilterChip(selected = zebra, onClick = { onToggleZebra(!zebra) }, label = { Text("제브라") })
        FilterChip(selected = grid, onClick = { onToggleGrid(!grid) }, label = { Text("그리드") })
        FilterChip(selected = level, onClick = { onToggleLevel(!level) }, label = { Text("수평계") })
        FilterChip(selected = punchIn, onClick = { onTogglePunchIn(!punchIn) }, label = { Text("펀치인") })
    }
}

// ---------------------------------------------------------------------------
// Collapsible pro panel (composition root for this file's rows)
// ---------------------------------------------------------------------------

/**
 * Collapsible pro-control panel composed of the rows above. Purely presentational: every value
 * comes from the caller (mirroring [com.hletrd.findx9tele.camera.CameraUiState] fields) and every
 * interaction is forwarded straight to the matching [CameraActions] method.
 */
@Composable
fun ProPanel(
    expanded: Boolean,
    controls: ManualControls,
    caps: CameraCaps?,
    transfer: ColorTransfer,
    photoFormats: PhotoFormats,
    recordAudio: Boolean,
    focusPeaking: Boolean,
    zebra: Boolean,
    grid: Boolean,
    level: Boolean,
    punchIn: Boolean,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    if (!expanded) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IsoRow(
            iso = controls.iso,
            isoRange = caps?.isoRange,
            autoExposure = controls.autoExposure,
            onIsoChange = actions::onIso,
            onToggleAutoExposure = actions::onToggleAutoExposure,
        )
        ShutterRow(
            exposureTimeNs = controls.exposureTimeNs,
            exposureTimeRange = caps?.exposureTimeRange,
            autoExposure = controls.autoExposure,
            onShutterNs = actions::onShutterNs,
            onToggleAutoExposure = actions::onToggleAutoExposure,
        )
        EvRow(
            exposureCompensation = controls.exposureCompensation,
            evRange = caps?.evRange ?: Range(0, 0),
            evStep = caps?.evStep ?: Rational(1, 3),
            autoExposure = controls.autoExposure,
            onExposureCompensation = actions::onExposureCompensation,
            onToggleAutoExposure = actions::onToggleAutoExposure,
        )
        WbRow(
            wbKelvin = controls.wbKelvin,
            autoWhiteBalance = controls.autoWhiteBalance,
            onWbKelvin = actions::onWbKelvin,
            onToggleAutoWb = actions::onToggleAutoWb,
        )
        TransferSelector(transfer = transfer, onTransfer = actions::onTransfer)
        PhotoFormatToggles(formats = photoFormats, onSetPhotoFormats = actions::onSetPhotoFormats)
        RecordAudioToggle(enabled = recordAudio, onToggleRecordAudio = actions::onToggleRecordAudio)
        OverlayToggles(
            peaking = focusPeaking,
            zebra = zebra,
            grid = grid,
            level = level,
            punchIn = punchIn,
            onTogglePeaking = actions::onTogglePeaking,
            onToggleZebra = actions::onToggleZebra,
            onToggleGrid = actions::onToggleGrid,
            onToggleLevel = actions::onToggleLevel,
            onTogglePunchIn = actions::onTogglePunchIn,
        )
    }
}
