package me.hletrd.telecampro.ui.controls

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.ui.theme.CameraColors

/**
 * Shared row/building-block library for the pro settings menu ([me.hletrd.telecampro.ui.controls.ProSheet]).
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

/**
 * Tracked sub-heading used to group a handful of rows within a settings tab page.
 *
 * Weight and tracking, NOT size or color: a header used to render byte-identically to the captions
 * beneath the rows (same labelSmall, same TextSecondary) inside a uniform spacedBy column, so it sat
 * equidistant between the section it closed and the one it opened and read as a stray caption. An
 * eyebrow treatment costs zero vertical space; `uppercase()` would have changed the ACCESSIBLE text
 * of 27 decorative nodes to buy a purely visual effect.
 *
 * The header owns its own extra leading (here, not at ~27 call sites) so the tab reads 24 dp above a
 * header and 12 dp below it against the page's 12 dp base gap. Before, the sheet had exactly ONE gap
 * value everywhere, so a section boundary was spaced identically to a caption under its own control.
 */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(top = 12.dp),
    )
}

/**
 * The ONE dim applied to a disabled settings row's own text. Alpha, not a colour swap: it composes
 * with whatever colour the element is already drawn in, so label and value dim by the same amount
 * without either of them having to know the other's colour.
 */
internal const val DISABLED_ROW_ALPHA = 0.55f

/**
 * The ONE settings-row label treatment, shared by every row primitive in this file.
 *
 * WEIGHT carries hierarchy down the settings page, not size: SectionHeader (11 sp/600, tracked, dim)
 * -> row label (12 sp/600, white) -> value or option chip (12 sp/500). Every row LABEL is SemiBold
 * and every VALUE and chip is Medium, so nothing has to grow to outrank anything. Before this was one
 * function, three of the seven primitives were SemiBold and four were Medium, which made the weight
 * alternate inside consecutive rows of one tab — Focus read 600, 600, 500, 500, 500, 600 down the
 * page, i.e. the ladder inverted twice for reasons a reader could not see.
 *
 * `enabled` dims through [DISABLED_ROW_ALPHA]. That state also used to have three implementations in
 * this one sheet — no dim at all, a [CameraColors.TextSecondary] swap, and this alpha — on rows that
 * sit directly beside each other in the Exposure tab.
 */
@Composable
internal fun SettingsRowLabel(text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(
        text,
        color = CameraColors.TextPrimary.copy(alpha = if (enabled) 1f else DISABLED_ROW_ALPHA),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/**
 * A control and the caption that explains it, bound as ONE block in the tab rhythm.
 *
 * Captions are 4 dp under their control, so the page's base gap can separate GROUPS instead of
 * floating every caption equidistant between the row it explains and the next unrelated one.
 * [caption] is nullable because several of these are route-gated (rear-optics only, non-SDR
 * transfer only) — a null renders the control alone, with no leftover empty line box.
 */
@Composable
internal fun Captioned(caption: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        if (caption != null) {
            Text(caption, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Colors shared by every [FilterChip] in the settings menu: filled white when selected, ghost otherwise. */
@Composable
internal fun pixelChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = CameraColors.TextPrimary,
    selectedContainerColor = CameraColors.TextPrimary,
    selectedLabelColor = Color.Black,
)

/**
 * The affordance edge shared by every settings [FilterChip]: [CameraColors.AffordanceEdge]
 * unselected, none when selected (the filled white container is its own edge).
 *
 * [enabled] must be passed the chip's OWN enablement, not left at `true`: a hard-coded `true` drew
 * the full-strength affordance edge around a chip that could not be tapped, while its label dimmed —
 * so the row said "disabled" and "tappable" at once. Material resolves the disabled edge through this
 * app's own scheme (`onSurface` is white here), landing at white/0.12 — close to the 0.55 the labels
 * dim by, and no new hand-picked number.
 */
@Composable
internal fun pixelChipBorder(selected: Boolean, enabled: Boolean = true) = FilterChipDefaults.filterChipBorder(
    enabled = enabled,
    selected = selected,
    borderColor = CameraColors.AffordanceEdge,
    selectedBorderWidth = 0.dp,
)

/**
 * The ONE 48 dp touch-floor wrapper. Bundled Material3 (`material3-android:1.4.0`) leaves two
 * distinct gaps under the app-wide floor, and both are closed here so a future floor change can
 * never be applied to one and missed on the other:
 * - Every settings-sheet [FilterChip]: material3 never calls `minimumInteractiveComponentSize()` on
 *   `ChipKt`, unlike Checkbox/RadioButton/Switch/Slider/IconButton, so the chip's fixed ~32 dp
 *   container is under 48 dp (cycle 2 fixed this for MR slots/DialChip/TeleChip via the same
 *   outer-Box `sizeIn`/`heightIn` pattern).
 * - Bare `Button`/`TextButton` sites (DES4-2): material3 gives them only a 40 dp `defaultMinSize`
 *   (ButtonSmallTokens.ContainerHeight), NOT `minimumInteractiveComponentSize()` — 8 dp under the
 *   floor on exactly the surfaces where a mis-tap is costliest (the permission-gate CTAs, a
 *   review-load Retry, and the destructive delete-confirmation pair).
 *
 * Centering the compact content inside a taller invisible Box grows only the tappable area — the
 * visual chip/button stays exactly as compact as before.
 */
@Composable
internal fun MinTouchTarget48(content: @Composable () -> Unit) {
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
                // NOT a colour. `DstIn` keeps the destination weighted by the SOURCE ALPHA and
                // discards the source's RGB entirely, so these two stops are an opacity ramp
                // (1 -> 0) that happens to be written as colours. `Color.White` here means "alpha =
                // 1, keep this pixel"; naming it an ink token would claim the fade paints white,
                // which it never does — swap in any opaque colour and the render is identical.
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
        SettingsRowLabel(label, enabled = enabled)
        val optionScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // selectableGroup(): this row is ONE exclusive choice, and saying so is what makes
                // TalkBack announce a chip's position within it ("2 of 3"). Every other
                // exclusive-choice row in the app already declares it (FocalRail, the mode carousel,
                // the dial ruler, the settings rail). PhotoFormatToggles must NOT get this — those
                // chips are multi-select and the group would lie about exclusivity.
                .selectableGroup()
                .trailingEdgeFadeScrollHint(optionScroll)
                .horizontalScroll(optionScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                // Each chip carries the row label in its OWN name: the label is a sibling Text, so an
                // unnamed chip announced a bare value ("Off") with nothing saying what is off — and
                // "Sharpness" and "NR" draw the SAME three values back to back in the Image tab.
                val optionName = segmentedOptionName(label, labelFor(option))
                MinTouchTarget48 {
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        // semantics(), not clearAndSetSemantics(): FilterChip's own selectable node
                        // supplies the selected / not-selected announcement, and only the NAME is
                        // ours to set here.
                        modifier = Modifier.semantics { contentDescription = optionName },
                        // labelMedium binds the chip to the SAME size as the row label naming it.
                        // Material's FilterChipTokens.LabelTextFont resolves to labelLarge (14 sp),
                        // so an unbound chip rendered LARGER than that label and larger than the
                        // section header above it — reading down the panel the size order was
                        // 11 -> 12 -> 14, i.e. the most numerous and least important elements were
                        // the biggest text on the page. [SettingsRowLabel] carries the hierarchy by
                        // weight instead; no chip container or touch target moves.
                        //
                        // Single line always: a squeezed chip must scroll into space, never wrap
                        // its label mid-word (the TransferSelector "Log/C3" break class).
                        label = {
                            Text(
                                labelFor(option),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                        colors = pixelChipColors(),
                        border = pixelChipBorder(isSelected, enabled),
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
            SettingsRowLabel(label, enabled = enabled)
            // The same right-hand value column as LabelValueRow and DropdownRow: labelMedium,
            // regular weight, Accent. ManualActive is reserved for a LIVE manual exposure override —
            // rendering "JPEG Quality 92" and "Interval 5s" in bold amber put plain settings in the
            // colour that means "the sensor is under manual control", right beside Encoder and Route
            // in regular blue. The amber stays where it earns the name: this slider's own fill and
            // needle, and the viewfinder RulerReadout.
            Text(
                valueLabel,
                color = if (enabled) CameraColors.Accent else CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
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

// Fixed tick count: the single call site never varied it, and a per-slider tick rhythm would read
// as inconsistency across the settings sheet rather than as information.
private const val CAMERA_SLIDER_TICKS = 11

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
) {
    val accent = if (enabled) CameraColors.ManualActive else CameraColors.TextSecondary
    // Two one-off PAIRS, not four numbers: each is an enabled/disabled ramp for one part of this
    // slider, and the pair is what carries the disabled state (the accent needle changes hue, the
    // structure just recedes). A token would have to be either the enabled or the disabled half,
    // which would split a pair that only means anything together.
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
        for (i in 0 until CAMERA_SLIDER_TICKS) {
            val x = pad + span * i / (CAMERA_SLIDER_TICKS - 1)
            val major = i == 0 || i == CAMERA_SLIDER_TICKS - 1 || i == (CAMERA_SLIDER_TICKS - 1) / 2
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
        SettingsRowLabel(label, enabled = enabled)
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
            colors = SwitchDefaults.colors(
                // The knob is this app's foreground mark, not Material's on-surface concept: the
                // scheme's own `onPrimary` is BLACK here, so a slot resolved from Material would
                // have come out dark on the Accent track. It is TextPrimary for the same reason its
                // unchecked sibling two lines down is TextSecondary — one knob, one palette.
                checkedThumbColor = CameraColors.TextPrimary,
                checkedTrackColor = CameraColors.Accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = CameraColors.TextSecondary,
                // One-off: the OFF track, a component fill sized to sit under a TextSecondary knob.
                // Close to Hairline (0.14) by coincidence only — that token is an EDGE on something
                // inert, this is the filled body of a live control.
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
        val alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
        SettingsRowLabel(label, enabled = enabled)
        // Accent marks an AFFORDANCE, not just a value: three rows here are pure readouts
        // ("Recording / Settings locked", "Encoder / …", "Route / …") and rendered in the same blue
        // as the tappable "Privacy Policy → View" / "Tap Focus → Reset" / "… → Add" rows. TextPrimary
        // on Pill is ~15:1, so contrast rises.
        Text(
            valueLabel,
            color = (if (onClick != null) CameraColors.Accent else CameraColors.TextPrimary).copy(alpha = alpha),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Label + selected value that opens a dropdown menu of [options].
 *
 * The alternative for an exclusive choice here is [SegmentedSelector], and it stays the default: a
 * chip row shows every option at once, which is what a photographer wants for a 2–5 way pick they
 * change while shooting. This exists for the opposite shape — a long, mostly-static list where the
 * chips become a horizontally scrolling smear (the converter catalog, user-rejected in that form).
 * Prefer the chips; reach for this only when the list is long enough to stop reading as a row.
 *
 * The trigger reuses [LabelValueRow]'s exact layout and colors so a dropdown row sits flush with the
 * toggle/value rows above and below it, and it carries a merged `contentDescription` (label +
 * current selection) so the on-device UI tests can find it by label. The menu itself is capped and
 * scrollable: [PhoneModel]-sized lists fit, but nothing here may grow past the panel.
 */
@Composable
internal fun <T> DropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    // A disabled row must not keep an already-open menu alive: enablement flips from REC start and
    // from a facing change, both of which can land while the sheet is open.
    if (!enabled && expanded) expanded = false
    val accessibility = dropdownSettingSemantics(label, labelFor(selected))
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .clickable(
                    enabled = enabled,
                    role = Role.DropdownList,
                    onClick = { expanded = true },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibility.label
                    stateDescription = accessibility.state
                    role = Role.DropdownList
                    if (!enabled) disabled()
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
            SettingsRowLabel(label, enabled = enabled)
            // The value block is the row's TRAILING column and must stay pinned to the end, like
            // every LabelValueRow. Without a weight it took its intrinsic width, so a long value
            // ("OPPO Find X9 Ultra" on the Phone row) consumed all the free space, SpaceBetween had
            // none left to distribute, and the value ran on directly after the label — reading as
            // left-aligned (user-reported 2026-07-28). Weighted + End-aligned, it fills the
            // remainder and sits right; `fill = false` keeps SHORT values hugging the edge instead
            // of stretching a transparent box across the row.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    labelFor(selected),
                    color = CameraColors.Accent.copy(alpha = alpha),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(6.dp))
                DropdownCaret(color = CameraColors.Accent.copy(alpha = alpha))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = CameraColors.Pill,
            // Long catalogs must scroll INSIDE the menu rather than run off the panel.
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            labelFor(option),
                            color = if (isSelected) CameraColors.Accent else CameraColors.TextPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * The dropdown affordance, drawn rather than imported: `androidx.compose.material.icons` is NOT on
 * this module's classpath (material3 does not pull it in here), and a whole icon artifact for one
 * 8 dp triangle is not worth the APK. Purely decorative — the row above owns the semantics.
 */
@Composable
private fun DropdownCaret(color: Color) {
    Canvas(modifier = Modifier.size(width = 10.dp, height = 6.dp)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
        }
        drawPath(path, color)
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
internal fun TransferSelector(
    transfer: ColorTransfer,
    onTransfer: (ColorTransfer) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsRowLabel("Transfer", enabled = enabled)
        // Scrollable like SegmentedSelector. Five entries USED to exceed the sheet width outright:
        // a fixed Row squeezed the last visible chip until its label broke mid-word ("Log/C3")
        // while SDR fell off entirely. Binding the chips to labelMedium (12 sp, from 14 sp) took
        // ~14% off each one, so that overflow is now headroom rather than a live constraint — but
        // the scroll and maxLines=1 stay: they are what keeps a future entry, a longer label, or a
        // larger system font scale from ever wrapping a chip label again.
        val optionScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Same exclusive-choice group and same per-chip naming as SegmentedSelector: this is
                // that row hand-rolled for one enum, and it inherited the identical gap.
                .selectableGroup()
                .trailingEdgeFadeScrollHint(optionScroll)
                .horizontalScroll(optionScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ColorTransfer.entries.forEach { option ->
                val isSelected = transfer == option
                val optionName = segmentedOptionName("Transfer", transferLabel(option))
                MinTouchTarget48 {
                    FilterChip(
                        selected = isSelected,
                        onClick = { onTransfer(option) },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = optionName },
                        label = {
                            Text(
                                transferLabel(option),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                        colors = pixelChipColors(),
                        border = pixelChipBorder(isSelected, enabled),
                    )
                }
            }
        }
    }
}

/** HEIF / JPEG / DNG output-format toggles; supported formats may be enabled simultaneously. */
@Composable
internal fun PhotoFormatToggles(
    formats: PhotoFormats,
    onSetPhotoFormats: (PhotoFormats) -> Unit,
    processedAvailable: Boolean,
    // Whether the DEVICE can produce RAW at all — what the DNG chip's enablement keys off.
    rawAvailable: Boolean,
    // Whether the CURRENT session actually carries a RAW output. Drives the caption only: on the
    // logical photo route this is false while [rawAvailable] is true, and selecting DNG is exactly
    // what switches the route so it becomes true.
    rawInSession: Boolean = rawAvailable,
    modifier: Modifier = Modifier,
    // VIDEO reframes the "no still outputs" line: there it is the 10-bit session's deliberate trade,
    // not a capability the route failed to deliver.
    videoMode: Boolean = false,
) {
    val processedSelected = processedAvailable && formats.wantsProcessedStill
    val rawSelected = rawAvailable && formats.dngRaw
    // Hoisted so the chip's border sees the SAME enablement as the chip: at least one processed
    // format must survive unless RAW is on, and RAW needs a processed sibling.
    val heifEnabled = processedAvailable && (!formats.heif || formats.jpeg || rawSelected)
    val jpegEnabled = processedAvailable && (!formats.jpeg || formats.heif || rawSelected)
    val dngEnabled = rawAvailable && (!formats.dngRaw || processedSelected)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // No `enabled` axis on this row: each format chip carries its own availability, so the label
        // itself is never the thing that is unavailable.
        SettingsRowLabel("Output")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MinTouchTarget48 {
                FilterChip(
                    selected = formats.heif,
                    onClick = { onSetPhotoFormats(formats.copy(heif = !formats.heif)) },
                    enabled = heifEnabled,
                    label = { Text("HEIF", style = MaterialTheme.typography.labelMedium) },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(formats.heif, heifEnabled),
                )
            }
            MinTouchTarget48 {
                FilterChip(
                    selected = formats.jpeg,
                    onClick = { onSetPhotoFormats(formats.copy(jpeg = !formats.jpeg)) },
                    enabled = jpegEnabled,
                    label = { Text("JPEG", style = MaterialTheme.typography.labelMedium) },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(formats.jpeg, jpegEnabled),
                )
            }
            MinTouchTarget48 {
                FilterChip(
                    selected = formats.dngRaw,
                    onClick = { onSetPhotoFormats(formats.copy(dngRaw = !formats.dngRaw)) },
                    enabled = dngEnabled,
                    label = { Text("DNG", style = MaterialTheme.typography.labelMedium) },
                    colors = pixelChipColors(),
                    border = pixelChipBorder(formats.dngRaw, dngEnabled),
                )
            }
        }
        if (!processedAvailable && !rawAvailable) {
            Text(
                // Same reasoning as the Ready-publication status: in VIDEO this is a designed trade
                // (the 10-bit session drops the still readers), not a fault, so the sheet says what
                // it BOUGHT rather than what it lost.
                if (videoMode) "10-bit video · stills off" else "Still capture unavailable",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (!rawInSession && formats.dngRaw) {
            Text(
                "Switching to a single lens for RAW",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (!rawAvailable) {
            Text(
                "RAW unavailable",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (!processedAvailable) {
            Text(
                // Word for word the status CameraEngine emits for the same accepted-output mask.
                "HEIF/JPEG unavailable; DNG only",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
