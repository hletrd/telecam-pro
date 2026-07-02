package com.hletrd.findx9tele.ui.controls

import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.EisStrength
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.ui.theme.CameraColors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Shared row/building-block library for the pro settings menu ([com.hletrd.findx9tele.ui.controls.ProSheet]).
 * Every composable here is purely presentational — values in, callbacks out — so the tabbed pages
 * in ProSheet.kt can assemble them freely. Visibility is `internal` (not `private`) so ProSheet.kt
 * and ManualDials.kt, in the same module, can call these directly.
 */

// ---------------------------------------------------------------------------
// Small reusable building blocks shared by every settings row
// ---------------------------------------------------------------------------

/** Small caps sub-heading used to group a handful of rows within a settings tab page. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

/** Colors shared by every [FilterChip] in the settings menu: filled white when selected, ghost otherwise. */
@Composable
internal fun pixelChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = CameraColors.TextPrimary,
    selectedContainerColor = CameraColors.TextPrimary,
    selectedLabelColor = Color.Black,
)

@Composable
internal fun pixelChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = Color.White.copy(alpha = 0.18f),
    selectedBorderWidth = 0.dp,
)

/** Reusable Auto/Manual segmented toggle used by exposure and white-balance rows. */
@Composable
fun AutoManualToggle(
    auto: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    autoLabel: String = "Auto",
    manualLabel: String = "Manual",
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = auto,
            onClick = { onToggle(true) },
            label = { Text(autoLabel) },
            colors = pixelChipColors(),
            border = pixelChipBorder(auto),
        )
        FilterChip(
            selected = !auto,
            onClick = { onToggle(false) },
            label = { Text(manualLabel) },
            colors = pixelChipColors(),
            border = pixelChipBorder(!auto),
        )
    }
}

/** Exclusive segmented selector (FilterChip row) for a fixed set of enum/value options. */
@Composable
internal fun <T> SegmentedSelector(
    label: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(labelFor(option)) },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(isSelected),
                )
            }
        }
    }
}

@Composable
internal fun pixelSliderColors() = SliderDefaults.colors(
    thumbColor = CameraColors.TextPrimary,
    activeTrackColor = CameraColors.Accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.18f),
    disabledThumbColor = CameraColors.TextSecondary,
    disabledActiveTrackColor = Color.White.copy(alpha = 0.25f),
    disabledInactiveTrackColor = Color.White.copy(alpha = 0.1f),
)

/** Label + value header with a slider beneath it. */
@Composable
internal fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
            Text(valueLabel, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = valueRange,
            colors = pixelSliderColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Label + Switch row used by every boolean toggle in the settings menu. */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) CameraColors.TextPrimary else CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CameraColors.Accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = CameraColors.TextSecondary,
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Label + clickable value row (e.g. "Camera Override  Default"), used for one-off advanced rows. */
@Composable
internal fun LabelValueRow(
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Text(valueLabel, color = CameraColors.Accent, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------------------------------------------------------------------------
// Enum -> short-label mappings
// ---------------------------------------------------------------------------

internal fun focusModeLabel(mode: FocusMode): String = when (mode) {
    FocusMode.MANUAL -> "MF"
    FocusMode.AUTO -> "AF"
    FocusMode.CONTINUOUS -> "AF-C"
    FocusMode.MACRO -> "Macro"
}

internal fun antibandingLabel(mode: Antibanding): String = when (mode) {
    Antibanding.AUTO -> "Auto"
    Antibanding.HZ50 -> "50Hz"
    Antibanding.HZ60 -> "60Hz"
    Antibanding.OFF -> "Off"
}

internal fun processingLevelLabel(level: ProcessingLevel): String = when (level) {
    ProcessingLevel.OFF -> "Off"
    ProcessingLevel.FAST -> "Fast"
    ProcessingLevel.HIGH_QUALITY -> "High Quality"
}

internal fun colorEffectLabel(effect: ColorEffect): String = when (effect) {
    ColorEffect.NONE -> "None"
    ColorEffect.MONO -> "Mono"
    ColorEffect.NEGATIVE -> "Negative"
    ColorEffect.SEPIA -> "Sepia"
    ColorEffect.AQUA -> "Aqua"
    ColorEffect.POSTERIZE -> "Posterize"
}

internal fun flashModeLabel(mode: FlashMode): String = when (mode) {
    FlashMode.OFF -> "Off"
    FlashMode.AUTO -> "Auto"
    FlashMode.ON -> "On"
    FlashMode.TORCH -> "Torch"
}

internal fun gridTypeLabel(type: GridType): String = when (type) {
    GridType.NONE -> "None"
    GridType.THIRDS -> "Thirds"
    GridType.GOLDEN -> "Golden Ratio"
    GridType.SQUARE -> "Square"
    GridType.CENTER -> "Center"
}

internal fun shutterTimerLabel(timer: ShutterTimer): String = when (timer) {
    ShutterTimer.OFF -> "Off"
    ShutterTimer.SEC3 -> "3s"
    ShutterTimer.SEC10 -> "10s"
}

internal fun shutterModeLabel(mode: ShutterMode): String = when (mode) {
    ShutterMode.SPEED -> "Speed"
    ShutterMode.ANGLE -> "Angle"
}

internal fun eisStrengthLabel(strength: EisStrength): String = when (strength) {
    EisStrength.LOW -> "Low"
    EisStrength.MEDIUM -> "Medium"
    EisStrength.HIGH -> "High"
}

internal fun wbModeLabel(mode: WbMode): String = when (mode) {
    WbMode.AUTO -> "Auto"
    WbMode.INCANDESCENT -> "Incandescent"
    WbMode.FLUORESCENT -> "Fluorescent"
    WbMode.DAYLIGHT -> "Daylight"
    WbMode.CLOUDY -> "Cloudy"
    WbMode.SHADE -> "Shade"
    WbMode.MANUAL -> "Manual"
}

internal fun meteringModeLabel(mode: MeteringMode): String = when (mode) {
    MeteringMode.MATRIX -> "Matrix"
    MeteringMode.CENTER -> "Center"
    MeteringMode.SPOT -> "Spot"
}

internal fun driveModeLabel(mode: DriveMode): String = when (mode) {
    DriveMode.SINGLE -> "Single"
    DriveMode.BURST -> "Burst"
    DriveMode.AEB -> "AEB"
    DriveMode.TIMELAPSE -> "Timelapse"
}

internal fun aspectRatioLabel(ratio: AspectRatio): String = when (ratio) {
    AspectRatio.FULL -> "Full"
    AspectRatio.W16_9 -> "16:9"
    AspectRatio.W4_3 -> "4:3"
    AspectRatio.W1_1 -> "1:1"
}

internal fun videoCodecLabel(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
}

internal fun bitrateLevelLabel(level: BitrateLevel): String = when (level) {
    BitrateLevel.LOW -> "Low"
    BitrateLevel.MEDIUM -> "Medium"
    BitrateLevel.HIGH -> "High"
}

/** "2160" -> "4K", "1080" -> "1080p", etc.; unrecognized heights fall back to "WxH". */
internal fun videoResolutionLabel(size: Size): String = when (size.height) {
    4320 -> "8K"
    2160 -> "4K"
    1440 -> "1440p"
    1080 -> "1080p"
    else -> "${size.width}x${size.height}"
}

// ---------------------------------------------------------------------------
// Exposure: ISO / Shutter helpers (shared by the exposure/color settings tab and the manual
// shutter ruler dial)
// ---------------------------------------------------------------------------

internal fun shutterNsToSlider(ns: Long, range: android.util.Range<Long>): Float {
    if (range.lower >= range.upper) return 0f
    val lo = ln(range.lower.toDouble())
    val hi = ln(range.upper.toDouble())
    val v = ln(ns.coerceIn(range.lower, range.upper).toDouble())
    return ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

internal fun sliderToShutterNs(slider: Float, range: android.util.Range<Long>): Long {
    if (range.lower >= range.upper) return range.lower
    val lo = ln(range.lower.toDouble())
    val hi = ln(range.upper.toDouble())
    val v = exp(lo + slider.coerceIn(0f, 1f) * (hi - lo))
    return v.roundToLong().coerceIn(range.lower, range.upper)
}

internal fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) {
        "%.1fs".format(seconds)
    } else {
        val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        "1/${denominator}s"
    }
}

/** Human-readable focus distance. Infinity for diopters <= 0. Shared with the manual focus ruler. */
internal fun formatFocusDistance(diopters: Float): String {
    val meters = FocusMapping.dioptersToMeters(diopters)
    return when {
        meters.isInfinite() -> "∞"
        meters < 1f -> "${(meters * 100).roundToInt()}cm"
        else -> "%.2fm".format(meters)
    }
}

// ---------------------------------------------------------------------------
// Transfer / formats (shared standalone rows used by the shooting/video settings tabs)
// ---------------------------------------------------------------------------

/** HLG / LOG transfer-function selector. */
@Composable
fun TransferSelector(
    transfer: ColorTransfer,
    onTransfer: (ColorTransfer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Transfer Function", color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = transfer == ColorTransfer.HLG,
                onClick = { onTransfer(ColorTransfer.HLG) },
                label = { Text("HLG") },
                colors = pixelChipColors(),
                border = pixelChipBorder(transfer == ColorTransfer.HLG),
            )
            FilterChip(
                selected = transfer == ColorTransfer.LOG,
                onClick = { onTransfer(ColorTransfer.LOG) },
                label = { Text("LOG") },
                colors = pixelChipColors(),
                border = pixelChipBorder(transfer == ColorTransfer.LOG),
            )
        }
    }
}

/** HEIF / DNG output-format toggles; both may be enabled simultaneously. */
@Composable
fun PhotoFormatToggles(
    formats: PhotoFormats,
    onSetPhotoFormats: (PhotoFormats) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Output Format", color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = formats.heif,
                onClick = { onSetPhotoFormats(formats.copy(heif = !formats.heif)) },
                label = { Text("HEIF") },
                colors = pixelChipColors(),
                border = pixelChipBorder(formats.heif),
            )
            FilterChip(
                selected = formats.dngRaw,
                onClick = { onSetPhotoFormats(formats.copy(dngRaw = !formats.dngRaw)) },
                label = { Text("DNG") },
                colors = pixelChipColors(),
                border = pixelChipBorder(formats.dngRaw),
            )
        }
    }
}
