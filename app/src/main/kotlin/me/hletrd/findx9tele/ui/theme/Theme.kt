package me.hletrd.findx9tele.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.hletrd.findx9tele.R

/**
 * Sony-style pro camera design tokens. Kept as plain constants (rather than only
 * [MaterialTheme.colorScheme] entries) so Canvas-drawn glyphs, overlays and chrome scrims can
 * reference them directly without threading the color scheme through every draw call.
 *
 * TWO of these names are byte-identical white and are still NOT interchangeable. [TextPrimary] is
 * foreground INK — the thing the photographer reads or the mark the app draws. [Block],
 * [BlockDisabled], [Hairline], [AffordanceEdge] and [GuideLine] derive from a bare `Color.White`
 * BASE — a wash of N% white over whatever happens to be underneath, which is a structural fact
 * about compositing, not a statement about foreground. A call site that means the first must spell
 * [TextPrimary] even though `Color.White` renders the same pixel today; a call site that means the
 * second keeps the base. That separation is the only thing that would let a future ink change (a
 * warm white, say) land on the glyphs and leave the scrims alone, and it is why the remaining bare
 * `Color.White` literals in `ui/` are each annotated with which of the two they are.
 */
object CameraColors {
    /** True-black viewfinder background. */
    val Background = Color(0xFF000000)
    /**
     * Base color for translucent chrome scrims (top bar, bottom cluster gradient).
     *
     * It has exactly ONE consumer — [me.hletrd.findx9tele.ui.overlays.HudPlate], which bakes in
     * [me.hletrd.findx9tele.ui.overlays.HUD_TEXT_SCRIM_ALPHA]. Do NOT reference this token at a draw
     * site: a call site holding the base colour and an alpha in its hands is how the plate came to be
     * spelled three ways (27 sites split across 20 `Color.Black.copy(alpha = …)`, 3
     * `ChromeScrim.copy(…)` and 4 lighter `Pill.copy(…)`; 26 are the plate, and the 27th is the
     * GearButton knob halo, which is deliberately not one), and how this very token acquired a
     * consumer that passed a hand-picked 0.45 for that halo. The old "0.40-0.55" note here invited
     * exactly the regression HudContrastTest exists to prevent: it asserts 0.45 and 0.55 FAIL the
     * 4.5:1 floor over a white frame.
     */
    val ChromeScrim = Color(0xFF000000)
    /**
     * SOLID pill/chip background (ghost chips, sheet surface) — and solid means solid.
     *
     * Four bottom-cluster chips over the LIVE preview (DialChip idle, CompactFnButton,
     * CompactDialCloseButton, the Speed/Angle toggle) used to paint `Pill.copy(alpha = …)`, an
     * incidental ~9/255-lighter plate inherited from the first Pixel-style draft. Composited over a
     * white frame at the shared alpha it measured 3.57:1 for [TextSecondary] — under the 4.5:1 floor,
     * and under the 5.07:1 the contrast pass that touched those exact lines believed it was buying.
     * They now paint [me.hletrd.findx9tele.ui.overlays.HudPlate] like every other HUD plate. Keep this
     * token opaque; over the viewfinder, use HudPlate.
     */
    val Pill = Color(0xFF1C1C1E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9E9E9E)
    /** Primary accent: links, active non-manual highlights. */
    val Accent = Color(0xFF8AB4F8)
    /**
     * Recording state and the record affordance: the REC tally border, the REC dot, the video-mode
     * shutter disc. Plus exactly two HARDWARE alarms that are not UI state and are red on camera
     * bodies too — the ≤15% battery numeral and the audio meter's clipping bucket. Plus one indirect
     * route: this token is also the scheme's `error` slot below. Nothing reads `colorScheme.error`
     * today, and that is the point of naming it here — it is the one way a Material component could
     * re-acquire the recording red without ever spelling this token, which is exactly how ChromeScrim
     * grew a consumer. Do not introduce a `colorScheme.error` reader without deciding, at that call
     * site, that the recording red is what it means.
     *
     * NOT for "this control is unavailable on this route". Two static capability captions in the Video
     * tab used to paint this token, which made the sheet say "error" about a device fact and put the
     * recording colour in the one tab that is designed to be read mid-REC; they are [TextSecondary]
     * now, like their PhotoFormatToggles siblings (which cover an equally terminal dead-end). A
     * destructive GLYPH uses the lighter [Alert], not this.
     */
    val Record = Color(0xFFFF3B30)
    /**
     * Destructive-action glyph red: the review trash icon and its confirm button. Lighter than
     * [Record] on purpose — HudContrastTest pins this exact value at the shared scrim because at the
     * pre-fix 0.5 alpha it measured 1.43:1, the worst interactive contrast in the app.
     */
    val Alert = Color(0xFFFF6B6B)
    /**
     * Manual-control-active accent (focus reticle, open ruler dial, manual chip) AND the OSD's
     * non-default-state tag colour (AEL/AWL/AFL, MUTE, LOUPE, OVERVIEW, drive, HR, MR). One amber for
     * "the camera is not in its default state", Sony-style. It was written as a bare literal at 20
     * sites, only two of which were manual overrides; the token now owns every one of them.
     */
    val ManualActive = Color(0xFFFFD60A)
    /**
     * The ONE raised-block FILL: the white wash that lifts a small INTERACTIVE block off whatever it
     * sits on. Three sites draw it — the sheet's CloseButton pill, MiniTextButton, and FnOverlayTile —
     * and they had drifted to two spellings 0.01 apart (0.08 / 0.09), i.e. three blocks and two
     * slightly different greys, exactly the drift [Hairline] documents for the edges. 0.09 is the
     * majority spelling (two of the three), so the odd 0.08 moved rather than the pair.
     *
     * It is NOT [me.hletrd.findx9tele.ui.overlays.HudPlate]: that is a translucent BLACK slab whose
     * job is a contrast floor for text over the live image. This is a lightening wash, and it carries
     * no contrast promise of its own — every one of the three has its own foreground rule.
     *
     * NOT folded in, deliberately: the idle settings-row surface (0.05) and the SELECTED tab-rail item
     * (0.10). Those two encode different ROLES — collapsing either into this token would make a
     * selected rail item look like an idle row.
     */
    val Block = Color.White.copy(alpha = 0.09f)
    /** [Block] for a disabled block. The foreground carries the state too; this keeps the slab quieter. */
    val BlockDisabled = Color.White.copy(alpha = 0.04f)
    /**
     * The ONE edge a small INTERACTIVE control draws around itself: the settings `FilterChip` (via
     * `pixelChipBorder`), the compact dial's close pill, the FocalRail lens circle, and MediaReview's
     * ReviewActionButton. Exactly the four sites [Hairline] already named in prose as the ones it
     * must not swallow — they simply had no name of their own, so the number lived inline four times.
     *
     * Kept SEPARATE from [Hairline] rather than merged upward: Hairline edges something you cannot
     * touch, this edges a touch target. Composited on [Pill] these four already measure only ~1.8:1,
     * so 0.18 is a floor being held, not a knob — moving it toward the decorative edge would dim an
     * affordance, which is a visual decision and not a cleanup.
     */
    val AffordanceEdge = Color.White.copy(alpha = 0.18f)
    /**
     * Composition guides drawn straight onto the live image with no plate beneath them: the
     * FrameLinesOverlay delivery-aspect box and every GridOverlay rule (thirds / golden / square /
     * center). Two consumers, one job — a reference the photographer composes against and then stops
     * seeing — and they must move together or the finder shows two weights of guide at once.
     *
     * NOT the level gauge's static reference line (0.4), and NOT a scope's plot frame (0.3 for the
     * histogram, 0.35 for the waveform — a real drift, see those two sites). Each of those is part of
     * an instrument sitting on a HUD plate and carries its own number; folding any of them in here
     * would move rendered pixels, which is not something a naming pass is allowed to do.
     */
    val GuideLine = Color.White.copy(alpha = 0.55f)
    /**
     * The ONE hairline stroke for chip/tile/card/panel EDGES that carry no affordance of their own.
     * Five decorative borders drifted across 0.10-0.15 alpha, which reads as five slightly different
     * greys rather than one edge treatment.
     *
     * NOT for interactive boundaries: those four sites (the settings FilterChip via
     * `pixelChipBorder`, the dial close pill, the FocalRail circle, and MediaReview's
     * ReviewActionButton) are [AffordanceEdge] now — a token of their own at 0.18, not this one at
     * 0.14. They keep their own number because composited on Pill they already measure ~1.8:1, and
     * lowering an affordance edge is not a cleanup. (The Fn overlay's own Close carries no border.)
     */
    val Hairline = Color.White.copy(alpha = 0.14f)
}

private val TeleDarkColorScheme = darkColorScheme(
    primary = CameraColors.Accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF16324F),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = CameraColors.Accent,
    onSecondary = Color.Black,
    tertiary = CameraColors.ManualActive,
    onTertiary = Color.Black,
    background = CameraColors.Background,
    onBackground = CameraColors.TextPrimary,
    surface = Color(0xFF0B0B0B),
    onSurface = CameraColors.TextPrimary,
    surfaceVariant = CameraColors.Pill,
    onSurfaceVariant = CameraColors.TextSecondary,
    outline = Color(0xFF3A3A3A),
    error = CameraColors.Record,
    onError = Color.Black,
)

/**
 * Inter (SIL Open Font License 1.1 — bundled, license at docs/licenses/inter-OFL.txt): a
 * professional UI face with unambiguous licensing, replacing whatever sans the OEM ships (ColorOS
 * substitutes its own default, so the chrome looked different from any design reference). Three
 * weights are bundled (~1.2 MB total). Korean never renders in-app (everything user-facing is
 * English), so no CJK subset is needed; system fallback would cover it.
 *
 * The family stops at SemiBold(600) DELIBERATELY, and no call site asks for Bold(700) any more
 * (BACKLOG UI16, resolved). The 22 sites that used to were all resolving to SemiBold by font
 * matching anyway, so the collapse was pixel-identical everywhere except FocalRail, whose
 * selected/unselected step was `Bold` vs `SemiBold` — i.e. one bundled face against itself, an
 * unrenderable step. That step is now SemiBold(600) vs Medium(500), which renders with zero new
 * assets. A fourth static face would cost ~420 KB (the three bundled ones measure 412/417/420 KB,
 * not the ~110 KB the old backlog entry estimated) and would thicken Inter's stems into the
 * counters at the eleven sites that sit at or below 13 sp — worst on the inverted `TELE` pill,
 * black on white at 11 sp. So: do not bundle `inter_bold.ttf`, and do not reintroduce
 * [FontWeight.Bold] at a call site — it silently means SemiBold.
 */
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/**
 * Tabular numerals ("tnum") app-wide: the OSD, chips, and rulers show live numbers (ISO 100→12800,
 * 1/8000s, REC timecode) that CHANGE while visible — proportional digits make every tick jitter
 * the layout width, the exact wiggle pro camera bodies avoid with fixed-advance digits. Inter's
 * tnum only affects digit advance, so prose is untouched.
 */
private fun TextStyle.withInter(): TextStyle = copy(fontFamily = Inter, fontFeatureSettings = "tnum")

/**
 * Compact HUD/chrome glyph metrics. [MaterialTheme] wraps its content in
 * `ProvideTextStyle(typography.bodyLarge)`, so a bare `Text(fontSize = …)` MERGES over the ambient
 * bodyLarge — `TextStyle.merge` leaves `lineHeight` and `letterSpacing` untouched when the call site
 * does not name them. Every 8-16 sp camera numeral therefore silently inherited a PROSE metric: a
 * 24 sp line box and body tracking (the zoom pill measured ~32 dp around a 15 sp digit; the 8 sp
 * flash "A" floated ~8 dp above where its 2 dp bottom padding implied inside a 36 dp disc).
 *
 * Sizes stay exactly what the on-device checks tuned — only the inherited box and tracking change.
 * Tracking is 0: Inter's own optical curve is ~0 at 11-12 sp, and these are numerals over a scrim,
 * not running text. Leading is 1.2x the glyph, the tightest box that still clears Inter's ascender.
 */
internal fun hudGlyph(size: TextUnit, weight: FontWeight = FontWeight.SemiBold): TextStyle =
    TextStyle(
        fontFamily = Inter,
        fontFeatureSettings = "tnum",
        fontSize = size,
        lineHeight = size * 1.2f,
        letterSpacing = 0.sp,
        fontWeight = weight,
    )

/**
 * [withInter] plus this app's own metrics for one type role.
 *
 * WHY the tracking differs from Material's: Material's scale is tuned for content-first consumer
 * apps, where the label roles carry short static words inside generous layouts and a little positive
 * tracking helps them read as labels. This is a dense instrument panel, and its label roles carry
 * LIVE NUMERALS — `1/250s`, `ISO 12800`, `5600K`, `4K 30p HEVC 84M`, the REC timecode, the review
 * EXIF block. On a status strip, +0.5 sp at 12 sp is +4.2% em of body tracking spread across every
 * digit of a value the photographer reads at a glance while shooting.
 *
 * The numbers are not taste. Inter publishes its own optical tracking curve (Dynamic Metrics,
 * https://rsms.me/inter/dynmetrics/): `tracking(em) = -0.0223 + 0.185 * e^(-0.1745 * size)`,
 * converted here by `tracking_sp = tracking_em * size`. Evaluated across this scale it is tighter
 * everywhere at and above 14 sp and lands at ~0 (never negative) at 11-12 sp, which is exactly the
 * property small text needs. Every SIZE below is held or raised — nothing shrinks — so no legibility
 * floor moves; only leading, tracking, and four title/display weights change.
 *
 * Six call sites already forced a weight override that the four raised title/display roles now
 * supply, so those overrides are deleted rather than left as redundant noise. ONE consumer did not
 * force one and therefore does change: MediaReview's `"DNG"` sub-label under the `"RAW"` placard
 * goes 500 → 600. Accepted — the 36/16 sp size step is what carries that pair, not the weight.
 */
private fun TextStyle.camera(
    size: TextUnit,
    lineHeight: TextUnit,
    tracking: TextUnit,
    weight: FontWeight = fontWeight ?: FontWeight.Normal,
): TextStyle = copy(
    fontFamily = Inter,
    fontFeatureSettings = "tnum",
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = tracking,
    fontWeight = weight,
)

private val TeleTypography = Typography().run {
    Typography(
        // 57 sp base kept internally consistent for its one consumer, which overrides fontSize to
        // 120 sp for the self-timer digit (and now overrides the leading and tracking with it).
        displayLarge = displayLarge.camera(57.sp, 60.sp, (-1.25).sp),
        displayMedium = displayMedium.withInter(),
        // MediaReview's "RAW" placard.
        displaySmall = displaySmall.camera(36.sp, 44.sp, (-0.8).sp, FontWeight.SemiBold),
        headlineLarge = headlineLarge.withInter(),
        headlineMedium = headlineMedium.withInter(),
        headlineSmall = headlineSmall.withInter(),
        // The settings sheet's "Menu" title, and the review close "×". That glyph moved up from
        // titleMedium with the codepoint fix: Inter's `multiply` ink is 0.50 em where the U+2715 it
        // replaced was ~0.68 em in the face ColorOS substituted, so 22 sp is what holds the drawn size.
        titleLarge = titleLarge.camera(22.sp, 28.sp, (-0.4).sp, FontWeight.SemiBold),
        // RulerReadout — the single numeral the photographer is actively DRAGGING — plus TabTitle.
        // Prose tracking on a live value in a pill is exactly wrong.
        titleMedium = titleMedium.camera(16.sp, 20.sp, (-0.2).sp, FontWeight.SemiBold),
        // The Fn overlay header.
        titleSmall = titleSmall.camera(14.sp, 20.sp, (-0.1).sp, FontWeight.SemiBold),
        // The ambient LocalTextStyle every unstyled Text merges over — which is why the 24 sp prose
        // line box was leaking into 8-16 sp chrome. Chrome no longer relies on it (see [hudGlyph]),
        // and the one explicit consumer is the single-line camera-permission label, not a paragraph.
        bodyLarge = bodyLarge.camera(16.sp, 20.sp, (-0.2).sp),
        // Status toast + review load/error copy.
        bodyMedium = bodyMedium.camera(14.sp, 20.sp, (-0.1).sp),
        // The sheet's only real prose role: the trademark and OFL footnotes. Slightly looser leading
        // than labelSmall precisely because it is prose and not a caption.
        bodySmall = bodySmall.camera(12.sp, 17.sp, 0.sp),
        // REC timecode, review zoom control. (The review zoom READOUT is labelMedium, and the
        // settings sliders' values moved off this role — they follow their row label's labelMedium.)
        labelLarge = labelLarge.camera(14.sp, 20.sp, (-0.1).sp),
        // The OSD workhorse (`300 mm TELE`, `4K 30p HEVC 84M`, `TL 5s`) AND every settings row label
        // and option chip.
        labelMedium = labelMedium.camera(12.sp, 16.sp, 0.sp),
        // Battery %, remaining shots, the EV readout, review EXIF, the tab rail, sheet captions.
        labelSmall = labelSmall.camera(11.sp, 16.sp, 0.sp),
    )
}

/**
 * App-wide dark theme for the camera UI. Deliberately deterministic (no dynamic color) so the
 * viewfinder chrome looks identical regardless of wallpaper or OS theme.
 */
@Composable
fun FindX9TeleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TeleDarkColorScheme,
        typography = TeleTypography,
        content = content,
    )
}
