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
import me.hletrd.findx9tele.R

/**
 * Sony-style pro camera design tokens. Kept as plain constants (rather than only
 * [MaterialTheme.colorScheme] entries) so Canvas-drawn glyphs, overlays and chrome scrims can
 * reference them directly without threading the color scheme through every draw call.
 */
object CameraColors {
    /** True-black viewfinder background. */
    val Background = Color(0xFF000000)
    /**
     * Base color for translucent chrome scrims (top bar, bottom cluster gradient). Callers apply
     * [me.hletrd.findx9tele.ui.overlays.HUD_TEXT_SCRIM_ALPHA] — NOT a hand-picked value. The old
     * "0.40-0.55" note here invited exactly the regression HudContrastTest exists to prevent: it
     * asserts 0.45 and 0.55 FAIL the 4.5:1 floor over a white frame.
     */
    val ChromeScrim = Color(0xFF000000)
    /** Solid pill/chip background (ghost chips, sheet surface). */
    val Pill = Color(0xFF1C1C1E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9E9E9E)
    /** Primary accent: links, active non-manual highlights. */
    val Accent = Color(0xFF8AB4F8)
    /** Recording / destructive state. */
    val Record = Color(0xFFFF3B30)
    /** Manual-control-active accent (focus reticle, open ruler dial, manual chip). */
    val ManualActive = Color(0xFFFFD60A)
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
 * NOTE the family stops at SemiBold(600) while ~22 call sites ask for [FontWeight.Bold] (700).
 * Font matching resolves those to SemiBold, so `Bold` and `SemiBold` render IDENTICALLY today —
 * which silently flattens FocalRail's selected/unselected weight step (the filled pill still
 * carries the selection). Bundling a real Bold would change the weight of every one of those 22
 * sites at once, so it is a deliberate design call with a device check attached rather than a
 * drive-by (docs/BACKLOG.md).
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

private val TeleTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.withInter(),
        displayMedium = displayMedium.withInter(),
        displaySmall = displaySmall.withInter(),
        headlineLarge = headlineLarge.withInter(),
        headlineMedium = headlineMedium.withInter(),
        headlineSmall = headlineSmall.withInter(),
        titleLarge = titleLarge.withInter(),
        titleMedium = titleMedium.withInter(),
        titleSmall = titleSmall.withInter(),
        bodyLarge = bodyLarge.withInter(),
        bodyMedium = bodyMedium.withInter(),
        bodySmall = bodySmall.withInter(),
        labelLarge = labelLarge.withInter(),
        labelMedium = labelMedium.withInter(),
        labelSmall = labelSmall.withInter(),
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
