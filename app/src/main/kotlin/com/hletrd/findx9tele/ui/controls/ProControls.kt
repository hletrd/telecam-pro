package com.hletrd.findx9tele.ui.controls

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.ui.theme.CameraColors
import kotlin.math.roundToInt

/**
 * Shared row/building-block library for the pro settings menu ([com.hletrd.findx9tele.ui.controls.ProSheet]).
 * Every composable here is purely presentational — values in, callbacks out — so the tabbed pages
 * in ProSheet.kt can assemble them freely. Visibility is `internal` (not `private`) so ProSheet.kt
 * and ManualDials.kt, in the same module, can call these directly.
 */

/**
 * Sony-style on-demand help: LONG-PRESS a setting's label to surface a one-line description in the
 * menu's bottom strip (see ProSheet). The provider maps a row's help key to its copy; rows report
 * through this local so the shared components below stay presentational.
 */
internal val LocalSettingHelp = compositionLocalOf<(String) -> Unit> { {} }

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
    // Distinct help-map key for rows whose display label repeats across tabs (e.g. "Mode").
    helpKey: String = label,
) {
    val showHelp = LocalSettingHelp.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(helpKey) { detectTapGestures(onLongPress = { showHelp(helpKey) }) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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

/** Label + value header with a pro-camera-style tick slider beneath it. */
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
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val showHelp = LocalSettingHelp.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(label) { detectTapGestures(onLongPress = { showHelp(label) }) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = if (enabled) CameraColors.TextPrimary else CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
            // The value reads like a camera HUD readout: accent-colored and bold.
            Text(
                valueLabel,
                color = if (enabled) CameraColors.ManualActive else CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        CameraSlider(
            fraction = fraction,
            onFraction = { f -> onValueChange(valueRange.start + f * span) },
            enabled = enabled,
        )
    }
}

/**
 * A pro-camera-style slider: a tick-marked track with an accent fill and a thin needle thumb (not the
 * round Material knob), tuned to match the shooting-screen dial rulers. Tap anywhere on the track to
 * jump, or drag the needle. Operates on a normalized [fraction]; the caller maps it to its own range.
 */
@Composable
private fun CameraSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    enabled: Boolean,
    tickCount: Int = 11,
) {
    val accent = if (enabled) CameraColors.ManualActive else CameraColors.TextSecondary
    val trackColor = Color.White.copy(alpha = if (enabled) 0.16f else 0.08f)
    val tickColor = Color.White.copy(alpha = if (enabled) 0.35f else 0.15f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = 8.dp.toPx()
                    val trackSpan = (size.width - 2f * pad).coerceAtLeast(1f)
                    fun emit(x: Float) = onFraction(((x - pad) / trackSpan).coerceIn(0f, 1f))
                    emit(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        emit(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        val pad = 8.dp.toPx()
        val cy = size.height / 2f
        val trackH = 5.dp.toPx()
        val span = size.width - 2f * pad
        val radius = CornerRadius(trackH / 2f, trackH / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(pad, cy - trackH / 2f),
            size = androidx.compose.ui.geometry.Size(span, trackH),
            cornerRadius = radius,
        )
        // Tick marks; the ends and centre are taller (major) for a scale-like read.
        for (i in 0 until tickCount) {
            val x = pad + span * i / (tickCount - 1)
            val major = i == 0 || i == tickCount - 1 || i == (tickCount - 1) / 2
            val th = (if (major) 8.dp else 4.dp).toPx()
            drawLine(tickColor, Offset(x, cy - th / 2f), Offset(x, cy + th / 2f), strokeWidth = 1.5.dp.toPx())
        }
        val fillW = (span * fraction).coerceIn(0f, span)
        if (fillW > 0f) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(pad, cy - trackH / 2f),
                size = androidx.compose.ui.geometry.Size(fillW, trackH),
                cornerRadius = radius,
            )
        }
        // Needle thumb: a tall rounded bar in the accent colour.
        val thumbX = pad + span * fraction
        val thumbW = 4.dp.toPx()
        val thumbH = 22.dp.toPx()
        drawRoundRect(
            color = accent,
            topLeft = Offset(thumbX - thumbW / 2f, cy - thumbH / 2f),
            size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
            cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f),
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
    val showHelp = LocalSettingHelp.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(label) { detectTapGestures(onLongPress = { showHelp(label) }) },
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
    ProcessingLevel.HIGH_QUALITY -> "HQ"
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
    GridType.GOLDEN -> "Golden"
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

internal fun wbModeLabel(mode: WbMode): String = when (mode) {
    WbMode.AUTO -> "Auto"
    WbMode.INCANDESCENT -> "Tungsten"
    WbMode.FLUORESCENT -> "Fluor."
    WbMode.DAYLIGHT -> "Daylight"
    WbMode.CLOUDY -> "Cloudy"
    WbMode.SHADE -> "Shade"
    WbMode.CUSTOM -> "Custom"
    WbMode.MANUAL -> "Manual"
}

internal fun meteringModeLabel(mode: MeteringMode): String = when (mode) {
    MeteringMode.MATRIX -> "Matrix"
    MeteringMode.CENTER -> "Center"
    MeteringMode.SPOT -> "Spot"
}

// Magnification labels throughout (0.6×/1×/3×/10×), matching stock camera apps — "UW" was the odd
// one out (user feedback).
internal fun lensLabel(lens: com.hletrd.findx9tele.camera.LensChoice): String = when (lens) {
    com.hletrd.findx9tele.camera.LensChoice.ULTRAWIDE -> "0.6×"
    com.hletrd.findx9tele.camera.LensChoice.MAIN -> "1×"
    com.hletrd.findx9tele.camera.LensChoice.TELE3X -> "3×"
    com.hletrd.findx9tele.camera.LensChoice.TELE10X -> "10×"
}

internal fun driveModeLabel(mode: DriveMode): String = when (mode) {
    DriveMode.SINGLE -> "Single"
    DriveMode.BURST -> "Burst"
    DriveMode.AEB -> "AEB"
    DriveMode.TIMELAPSE -> "Timelapse"
}

internal fun fnSlotLabel(slot: FnSlot): String = slot.label

internal fun memorySlotLabel(slot: MemorySlot): String = slot.label

internal fun hardwareKeyActionLabel(action: HardwareKeyAction): String = action.label

internal fun aspectRatioLabel(ratio: AspectRatio): String = when (ratio) {
    AspectRatio.W16_9 -> "16:9"
    AspectRatio.W4_3 -> "4:3"
}

internal fun videoCodecLabel(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
    // All-intra professional codec (ProRes / XAVC-I class), HW-accelerated, very high bitrate.
    VideoCodec.APV -> "APV Intra"
}

internal fun videoFrameRateLabel(rate: VideoFrameRate): String = rate.label

/** Compact codec name for the encoder-summary row (no "(SW, slow)" qualifier). */
internal fun videoCodecLabelShort(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
    VideoCodec.APV -> "APV"
}

internal fun bitrateLevelLabel(level: BitrateLevel): String = when (level) {
    BitrateLevel.LOW -> "Low"
    BitrateLevel.MEDIUM -> "Medium"
    BitrateLevel.HIGH -> "High"
    BitrateLevel.ULTRA -> "Ultra"
    BitrateLevel.MAX -> "Max"
}

/**
 * "3840×2160" -> "4K", "1920×1080" -> "1080p", etc. 4:3 Open-Gate sizes are tagged by their width
 * bucket with a "4:3" suffix (e.g. 4096×3072 -> "4K 4:3"); anything unrecognized falls back to "W×H".
 */
internal fun videoResolutionLabel(size: Size): String {
    val is43 = size.height * 4 == size.width * 3
    if (is43) return when {
        size.width >= 7680 -> "8K 4:3"
        size.width >= 3840 -> "4K 4:3"
        size.width >= 2560 -> "2.5K 4:3"
        size.width >= 1920 -> "1080 4:3"
        else -> "${size.width}×${size.height}"
    }
    return when (size.height) {
        4320 -> "8K"
        2160 -> "4K"
        1440 -> "1440p"
        1080 -> "1080p"
        720 -> "720p"
        else -> "${size.width}×${size.height}"
    }
}

// ---------------------------------------------------------------------------
// Exposure: shutter/focus display helpers (shared by the exposure tab and the manual dials)
// ---------------------------------------------------------------------------

// Conventional shutter-speed denominators, so an exact 2^k time (e.g. 1/128 s) displays as the
// camera-standard value a photographer expects (1/125 s).
private val NICE_SHUTTER_DENOM = intArrayOf(
    1, 2, 3, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320,
    400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000,
)

internal fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return when {
        seconds >= 10.0 -> "%.0fs".format(seconds)
        seconds >= 1.0 -> "%.1fs".format(seconds)
        else -> {
            val denom = 1.0 / seconds
            val nice = NICE_SHUTTER_DENOM.minByOrNull { kotlin.math.abs(it - denom) } ?: denom.roundToInt().coerceAtLeast(1)
            // Times in [0.667 s, 1 s) have no conventional 1/x form — snapping produced the
            // nonsensical "1/1s" (e.g. 0.75 s). Show decimal seconds there like real bodies do.
            if (nice <= 1) "%.1fs".format(seconds) else "1/${nice}s"
        }
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

/** Transfer-function display label: what the footage IS, not just the enum name. */
internal fun transferLabel(transfer: ColorTransfer): String = when (transfer) {
    ColorTransfer.HLG -> "HLG"
    // LOG = the GL-baked official O-Log2 OETF. The native HAL log key is INERT for third-party
    // Camera2 on this device (settled 2026-07-09) — see CLAUDE.md / CameraEngine.setTransfer.
    ColorTransfer.LOG -> "O-Log"
    ColorTransfer.SDR -> "SDR"
}

/** Compact transfer name for the video-mode quick chip and the OSD. */
internal fun transferLabelShort(transfer: ColorTransfer): String = when (transfer) {
    ColorTransfer.HLG -> "HLG"
    ColorTransfer.LOG -> "O-Log"
    ColorTransfer.SDR -> "SDR"
}

/**
 * HLG / LOG / SDR transfer-function selector. Only HEVC's Main10 encoder profile carries the
 * HLG/LOG tag — the capture source stays SDR/8-bit (see CLAUDE.md; not an end-to-end 10-bit claim).
 */
@Composable
fun TransferSelector(
    transfer: ColorTransfer,
    onTransfer: (ColorTransfer) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Transfer", color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorTransfer.entries.forEach { option ->
                val isSelected = transfer == option
                FilterChip(
                    selected = isSelected,
                    onClick = { onTransfer(option) },
                    enabled = enabled,
                    label = { Text(transferLabel(option)) },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(isSelected),
                )
            }
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
        Text("Output", color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = formats.heif,
                onClick = { onSetPhotoFormats(formats.copy(heif = !formats.heif)) },
                label = { Text("HEIF") },
                colors = pixelChipColors(),
                border = pixelChipBorder(formats.heif),
            )
            FilterChip(
                selected = formats.jpeg,
                onClick = { onSetPhotoFormats(formats.copy(jpeg = !formats.jpeg)) },
                label = { Text("JPEG") },
                colors = pixelChipColors(),
                border = pixelChipBorder(formats.jpeg),
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
