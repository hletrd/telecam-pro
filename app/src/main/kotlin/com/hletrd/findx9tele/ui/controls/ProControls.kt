package com.hletrd.findx9tele.ui.controls

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.ui.theme.CameraColors

/**
 * Shared row/building-block library for the pro settings menu ([com.hletrd.findx9tele.ui.controls.ProSheet]).
 * Every composable here is purely presentational — values in, callbacks out — so the tabbed pages
 * in ProSheet.kt can assemble them freely. Visibility is `internal` (not `private`) so ProSheet.kt
 * and ManualDials.kt, in the same module, can call these directly.
 */

// (The enum -> label mappings, shutter/focus formatters, and SettingSemantics live in
// ControlLabels.kt — hoisted to a non-composable file so the user-facing copy stays host-testable
// apart from Compose emission. Only the android.util.Size wrapper below remains here.)

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

/**
 * Every settings-sheet [FilterChip] sits inside this: bundled Material3 (`material3-android:1.4.0`)
 * never calls `minimumInteractiveComponentSize()` on `ChipKt`, unlike Checkbox/RadioButton/Switch/
 * Slider/IconButton, so the chip's fixed ~32 dp container is under the app-wide 48 dp touch floor
 * (cycle 2 fixed this for MR slots/DialChip/TeleChip via the same outer-Box `sizeIn`/`heightIn`
 * pattern). Centering the compact chip inside a taller invisible Box grows only the tappable area —
 * the visual chip stays exactly as compact as before.
 */
@Composable
internal fun MinTouchTargetChip(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
        content()
    }
}

/**
 * The same 48 dp outer-target pattern for bare Material3 `Button`/`TextButton` sites (DES4-2):
 * bundled material3 1.4.0 gives Button/TextButton only a 40 dp `defaultMinSize`
 * (ButtonSmallTokens.ContainerHeight), NOT `minimumInteractiveComponentSize()` — 8 dp under the
 * app-wide floor on exactly the surfaces where a mis-tap is costliest (the permission-gate CTAs, a
 * review-load Retry, and the destructive delete-confirmation pair).
 */
@Composable
internal fun MinTouchTargetButton(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
        content()
    }
}

/**
 * Trailing-edge fade for horizontally scrolling chip rows: without the hint, the half-cut trailing
 * chip at the panel edge reads as a LAYOUT BUG rather than "scrollable" (user-reported on the Fn
 * dial row). The fix originally landed only there — every settings SegmentedSelector (several with
 * MORE chips than the row that triggered the report) had the identical failure shape. Apply BEFORE
 * `horizontalScroll` in the modifier chain, sharing its [scrollState].
 */
internal fun Modifier.trailingEdgeFadeScrollHint(scrollState: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scrollState.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0.90f to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
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
    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        val optionScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .trailingEdgeFadeScrollHint(optionScroll)
                .horizontalScroll(optionScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                MinTouchTargetChip {
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        // Single line always: a squeezed chip must scroll into space, never wrap
                        // its label mid-word (the TransferSelector "Log/C3" break class).
                        label = { Text(labelFor(option), maxLines = 1, softWrap = false) },
                        colors = pixelChipColors(),
                        border = pixelChipBorder(isSelected),
                    )
                }
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
    val accessibility = sliderSettingSemantics(label, valueLabel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = accessibility.label
                stateDescription = accessibility.state
            },
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
            valueDescription = valueLabel,
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
    valueDescription: String,
    tickCount: Int = 11,
) {
    val accent = if (enabled) CameraColors.ManualActive else CameraColors.TextSecondary
    val trackColor = Color.White.copy(alpha = if (enabled) 0.16f else 0.08f)
    val tickColor = Color.White.copy(alpha = if (enabled) 0.35f else 0.15f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            // The track remains visually compact around the centre line, but the pointer and
            // adjustable-semantics node must meet the app-wide 48 dp interaction floor.
            .height(48.dp)
            // TalkBack: a bare Canvas is invisible to accessibility services — this backs every
            // settings slider, so expose it as an adjustable value with a set action.
            .progressSemantics(value = fraction.coerceIn(0f, 1f), valueRange = 0f..1f)
            .semantics {
                stateDescription = valueDescription
                if (!enabled) disabled()
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    onFraction(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = 8.dp.toPx()
                    val trackSpan = (size.width - 2f * pad).coerceAtLeast(1f)
                    fun fractionAt(x: Float) = ((x - pad) / trackSpan).coerceIn(0f, 1f)
                    // Publication is FRAME-GATED (~60 Hz) with an exact landing on release — the
                    // same gate RulerSlider carries: per-event emission re-normalized controls and
                    // re-published the whole CameraUiState at the panel's 120 Hz input rate on
                    // every settings slider (cycle-6 PR-2), the documented pre-coalescer jank
                    // mechanism the viewfinder rulers were already cured of twice.
                    var latest = fractionAt(down.position.x)
                    var emitted = latest
                    var lastEmitMs = android.os.SystemClock.uptimeMillis()
                    onFraction(latest)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        latest = fractionAt(change.position.x)
                        change.consume()
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastEmitMs >= 16) {
                            lastEmitMs = now
                            emitted = latest
                            onFraction(latest)
                        }
                    }
                    // Land the exact final value the gate may have swallowed.
                    if (emitted != latest) onFraction(latest)
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
    val accessibility = toggleSettingSemantics(label, checked)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibility.label
                stateDescription = accessibility.state
                role = Role.Switch
                if (!enabled) disabled()
            },
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
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
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

/** Label + clickable value row (e.g. "Camera Override  Default"), used for one-off advanced rows.
 *  [enabled] dims the row and drops the click, like every other lockable-control surface — a hot
 *  row over a locked action reads as (and previously was) an unguarded mutation path. */
@Composable
internal fun LabelValueRow(
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .sizeIn(minHeight = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.55f
        Text(
            label,
            color = CameraColors.TextPrimary.copy(alpha = alpha),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            valueLabel,
            color = CameraColors.Accent.copy(alpha = alpha),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * "3840×2160" -> "4K", "1920×1080" -> "1080p", etc. 4:3 Open-Gate sizes are tagged by their width
 * bucket with a "4:3" suffix (e.g. 4096×3072 -> "4K 4:3"); anything unrecognized falls back to "W×H".
 * The plain-int core [videoResolutionLabelFor] lives in ControlLabels.kt; this framework-typed
 * wrapper stays here (android.util.Size is not mocked on the JVM).
 */
internal fun videoResolutionLabel(size: Size): String = videoResolutionLabelFor(size.width, size.height)

// ---------------------------------------------------------------------------
// Transfer / formats (shared standalone rows used by the shooting/video settings tabs)
// ---------------------------------------------------------------------------

/**
 * HLG / S-Log3 / S-Log3.Cine / LogC3 / SDR transfer-function selector. Only HEVC's Main10 encoder
 * profile carries the HLG/log tag — the capture source stays SDR/8-bit (see CLAUDE.md; not an
 * end-to-end 10-bit claim).
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
        // Scrollable like SegmentedSelector: five entries exceed the sheet width, and a fixed Row
        // squeezed the last visible chip until its label broke mid-word ("Log/C3") while SDR fell
        // off entirely. maxLines=1 keeps any future squeeze from ever wrapping a chip label again.
        val optionScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .trailingEdgeFadeScrollHint(optionScroll)
                .horizontalScroll(optionScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ColorTransfer.entries.forEach { option ->
                val isSelected = transfer == option
                MinTouchTargetChip {
                    FilterChip(
                        selected = isSelected,
                        onClick = { onTransfer(option) },
                        enabled = enabled,
                        label = { Text(transferLabel(option), maxLines = 1, softWrap = false) },
                        colors = pixelChipColors(),
                        border = pixelChipBorder(isSelected),
                    )
                }
            }
        }
    }
}

/** HEIF / JPEG / DNG output-format toggles; supported formats may be enabled simultaneously. */
@Composable
fun PhotoFormatToggles(
    formats: PhotoFormats,
    onSetPhotoFormats: (PhotoFormats) -> Unit,
    processedAvailable: Boolean,
    rawAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val processedSelected = processedAvailable && formats.wantsProcessedStill
    val rawSelected = rawAvailable && formats.dngRaw
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Output", color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MinTouchTargetChip {
                FilterChip(
                    selected = formats.heif,
                    onClick = { onSetPhotoFormats(formats.copy(heif = !formats.heif)) },
                    enabled = processedAvailable && (!formats.heif || formats.jpeg || rawSelected),
                    label = { Text("HEIF") },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(formats.heif),
                )
            }
            MinTouchTargetChip {
                FilterChip(
                    selected = formats.jpeg,
                    onClick = { onSetPhotoFormats(formats.copy(jpeg = !formats.jpeg)) },
                    enabled = processedAvailable && (!formats.jpeg || formats.heif || rawSelected),
                    label = { Text("JPEG") },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(formats.jpeg),
                )
            }
            MinTouchTargetChip {
                FilterChip(
                    selected = formats.dngRaw,
                    onClick = { onSetPhotoFormats(formats.copy(dngRaw = !formats.dngRaw)) },
                    enabled = rawAvailable && (!formats.dngRaw || processedSelected),
                    label = { Text("DNG") },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(formats.dngRaw),
                )
            }
        }
        if (!processedAvailable && !rawAvailable) {
            Text(
                "Still capture unavailable.",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (!rawAvailable) {
            Text(
                "RAW unavailable.",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (!processedAvailable) {
            Text(
                "HEIF/JPEG unavailable; DNG only.",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
