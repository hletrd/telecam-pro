package me.hletrd.findx9tele.ui.overlays

import androidx.compose.ui.graphics.toArgb
import me.hletrd.findx9tele.ui.theme.CameraColors
import me.hletrd.findx9tele.ui.teleChipIdleScrimAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudContrastTest {

    @Test
    fun `idle TELE uses the shared bright-frame scrim floor`() {
        assertEquals(HUD_TEXT_SCRIM_ALPHA, teleChipIdleScrimAlpha(), 0f)
        // The chip paints HudPlate, so tie the seam to that plate's own alpha — otherwise the seam
        // could keep reporting the floor after the drawn slab drifted off it. One 8-bit step of
        // tolerance because Color quantizes the alpha channel (0.82 * 255 = 209.1 -> 209/255).
        assertEquals(teleChipIdleScrimAlpha(), HudPlate.alpha, 1f / 255f)
        val ratio = contrastRatioOnWhiteScrim(rgbOf(CameraColors.TextPrimary), teleChipIdleScrimAlpha())
        assertTrue("idle TELE contrast was $ratio", ratio >= 4.5)
    }

    // LIVE surfaces reference the REAL palette (TEST4-10): the old literal copies pinned a stale
    // duplicate of CameraColors — a palette tweak kept these green while shipping an unchecked
    // color. Historical pre-fix documentation tests below keep their literals on purpose.
    private fun rgbOf(color: androidx.compose.ui.graphics.Color): Int = color.toArgb() and 0xFFFFFF

    @Test
    fun `shared HUD scrim clears small-text contrast on a white frame`() {
        val foregrounds = mapOf(
            "primary" to rgbOf(CameraColors.TextPrimary),
            "secondary" to rgbOf(CameraColors.TextSecondary),
            "blue status accent" to rgbOf(CameraColors.Accent),
        )

        foregrounds.forEach { (label, rgb) ->
            val ratio = contrastRatioOnWhiteScrim(rgb, HUD_TEXT_SCRIM_ALPHA)
            assertTrue("$label contrast was $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `old translucent HUD scrims fail the white-frame floor`() {
        assertTrue(contrastRatioOnWhiteScrim(0xFFFFFF, 0.45f) < 4.5)
        assertTrue(contrastRatioOnWhiteScrim(0x9E9E9E, 0.36f) < 4.5)
    }

    @Test
    fun `pre-fix alphas of the P5_1 surfaces failed the white-frame floor`() {
        // Documents the exact gaps cycle-3 P5.1 (AGG3-13) closed by routing each surface through
        // HUD_TEXT_SCRIM_ALPHA, using the app's own formula:
        //  - ChromeIconButton secondary glyph  #9E9E9E @ 0.45 ≈ 1.25:1
        //  - review metadata EXIF line          #9E9E9E @ 0.55 ≈ 1.78:1
        //  - review close ✕ / audio-meter box   #FFFFFF @ 0.50/0.45 ≈ 3.98 / 3.35
        //  - review delete trash icon           #FF6B6B @ 0.50 ≈ 1.43:1 (worst interactive glyph)
        //  - zoom badge / toast / histogram box  #FFFFFF @ 0.55 ≈ 4.76 (cleared only by a hair)
        assertTrue(contrastRatioOnWhiteScrim(0x9E9E9E, 0.45f) < 4.5) // ChromeIconButton secondary
        assertTrue(contrastRatioOnWhiteScrim(0x9E9E9E, 0.55f) < 4.5) // review metadata secondary
        assertTrue(contrastRatioOnWhiteScrim(0xFFFFFF, 0.50f) < 4.5) // review close ✕
        assertTrue(contrastRatioOnWhiteScrim(0xFF6B6B, 0.50f) < 4.5) // review delete icon
        assertTrue(contrastRatioOnWhiteScrim(0xFFFFFF, 0.45f) < 4.5) // audio-meter box
    }

    @Test
    fun `P5_1 extended surfaces clear the text floor at the shared alpha`() {
        // Cycle-3 P5.1 (AGG3-13) routed these previously-missed surfaces through HUD_TEXT_SCRIM_ALPHA,
        // extending 05486cb's closed list:
        //  - top-bar ChromeIconButton glyphs (white / secondary #9E9E9E / blue accent #8AB4F8)
        //  - review metadata panel (white name + secondary EXIF/size lines)
        //  - review close ✕ (white), review zoom-scale badge (white), center status/error toast (white)
        //  - histogram / waveform / audio-meter panel scrims (judged by the near-white brightest trace)
        // Pin each foreground so a future alpha/color tweak can't sink one back under 4.5:1 on a white
        // frame the way the pre-fix 0.45/0.5/0.55/0.62 alphas did.
        val foregrounds = mapOf(
            "chrome/review/toast/scope white" to rgbOf(CameraColors.TextPrimary),
            "chrome/metadata secondary" to rgbOf(CameraColors.TextSecondary),
            "chrome accent blue" to rgbOf(CameraColors.Accent),
        )
        foregrounds.forEach { (label, rgb) ->
            val ratio = contrastRatioOnWhiteScrim(rgb, HUD_TEXT_SCRIM_ALPHA)
            assertTrue("$label contrast was $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `destructive review delete icon clears the floor at the shared alpha`() {
        // The red trash glyph (CameraColors.Alert) was the worst interactive contrast found (1.43:1
        // at 0.5 alpha); at the shared floor it clears 4.5, so no opaque plate is needed. A
        // destructive action must never be ambiguous over a bright review frame. LIVE surface, so it
        // reads the real palette (see the doctrine above); the pre-fix 0.5-alpha case above this one
        // documents history and keeps its literal on purpose.
        val ratio = contrastRatioOnWhiteScrim(rgbOf(CameraColors.Alert), HUD_TEXT_SCRIM_ALPHA)
        assertTrue("delete-red contrast was $ratio", ratio >= 4.5)
    }

    @Test
    fun `every foreground the raised HUD surfaces use clears the floor at the shared alpha`() {
        // The cycle-2 fix routed StatusInfoPill, ExposureMeter, ZoomIndicator,
        // RecordingIndicator, the half-press label, and RulerReadout through HUD_TEXT_SCRIM_ALPHA.
        // Pin their actual foregrounds so a future alpha/color tweak can't quietly sink one of
        // them back under 4.5:1 on a white frame.
        val foregrounds = mapOf(
            "white (pill/meter/REC time)" to rgbOf(CameraColors.TextPrimary),
            "secondary HUD text" to rgbOf(CameraColors.TextSecondary),
            "accent blue (zoom readout)" to rgbOf(CameraColors.Accent),
            "manual yellow (ruler readout, half-press label)" to rgbOf(CameraColors.ManualActive),
        )
        foregrounds.forEach { (label, rgb) ->
            val ratio = contrastRatioOnWhiteScrim(rgb, HUD_TEXT_SCRIM_ALPHA)
            assertTrue("$label contrast was $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `review action button scrim shares the pinned alpha (DES4-4)`() {
        // ReviewActionButton (video play/pause + still zoom control) was the one review-screen
        // surface still on a magic 0.62f; it now routes through HUD_TEXT_SCRIM_ALPHA, so its
        // white glyph is covered by the same floor as every sibling.
        val ratio = contrastRatioOnWhiteScrim(rgbOf(CameraColors.TextPrimary), HUD_TEXT_SCRIM_ALPHA)
        assertTrue("review action glyph contrast was $ratio", ratio >= 4.5)
    }

    @Test
    fun `the bottom-cluster pill surfaces share the pinned alpha`() {
        // DialChip, CompactFnButton, CompactDialCloseButton and the Speed/Angle toggle sat on magic
        // 0.70/0.72 alphas and were covered by NO case above, even though they sit directly over the
        // live preview: the bottom cluster's gradient is transparent at its TOP edge, which is
        // exactly where the dial chip row is. Every foreground they use must clear the same floor.
        //
        // These four also painted a LIGHTER base than the formula below assumes — CameraColors.Pill
        // rather than black — so until the plate was unified this case measured a slab the call sites
        // did not draw (see `the lighter Pill plate these four used to paint missed the floor`). They
        // now paint HudPlate, i.e. exactly the black-at-HUD_TEXT_SCRIM_ALPHA slab measured here.
        val foregrounds = mapOf(
            "dial chip / compact glyph white" to rgbOf(CameraColors.TextPrimary),
            "unavailable chip + disabled Speed/Angle option" to rgbOf(CameraColors.TextSecondary),
        )
        foregrounds.forEach { (label, rgb) ->
            val ratio = contrastRatioOnWhiteScrim(rgb, HUD_TEXT_SCRIM_ALPHA)
            assertTrue("$label contrast was $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `the pre-fix bottom-cluster alpha failed for its secondary foreground`() {
        // Measured with the app's own formula, not estimated: at the old 0.70 plate alpha the white
        // chip text was fine (8.5:1) but CameraColors.TextSecondary — the color an unavailable dial
        // chip and a DISABLED Speed/Angle option use (an inactive but still selectable option is
        // TextPrimary; 798006d's message called it "inactive, still-selectable", which the code does
        // not do) — measured 3.2:1. That is the gap routing these four surfaces through
        // HUD_TEXT_SCRIM_ALPHA closes.
        assertTrue(contrastRatioOnWhiteScrim(rgbOf(CameraColors.TextSecondary), 0.70f) < 4.5)
        assertTrue(contrastRatioOnWhiteScrim(rgbOf(CameraColors.TextSecondary), 0.72f) < 4.5)
        assertTrue(contrastRatioOnWhiteScrim(rgbOf(CameraColors.TextSecondary), HUD_TEXT_SCRIM_ALPHA) >= 4.5)
    }

    @Test
    fun `HudPlate is the scrim token at the pinned alpha and nothing else`() {
        // The canonical plate is a parameterless value precisely so no call site can hand-pick an
        // alpha. Pin its composition: base = the scrim token, alpha = the shared floor. If someone
        // re-spells it with a literal or a different base, this fails instead of shipping a slab that
        // every other case in this file only *assumes* is being drawn.
        assertTrue(HudPlate == CameraColors.ChromeScrim.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
        assertTrue("plate rgb was ${rgbOf(HudPlate)}", rgbOf(HudPlate) == rgbOf(CameraColors.ChromeScrim))
        assertTrue("plate rgb was ${rgbOf(HudPlate)}", rgbOf(HudPlate) == 0x000000)
        // 8-bit-quantized alpha channel: 0.82 * 255 = 209.1 -> 209/255, so compare within one step.
        assertEquals(HUD_TEXT_SCRIM_ALPHA, HudPlate.alpha, 1f / 255f)
    }

    @Test
    fun `the lighter Pill plate these four used to paint missed the floor`() {
        // Why the four bottom-cluster chips folded into the black plate rather than earning a token of
        // their own. CameraColors.Pill (#1C1C1E) at the shared alpha over a white frame composites to
        // #454547, not black's #2E2E2E — measured with the same relative-luminance formula this file
        // uses, via the real palette:
        //   white     9.57:1 (vs 13.58 on black)   secondary 3.57:1 (vs 5.07)
        //   accent    4.54:1 (vs  6.44)            manual    6.78:1 (vs 9.62)
        // The secondary foreground an unavailable chip draws therefore sat UNDER the 4.5:1 floor on
        // that plate, and 798006d — the pass that rewrote those four lines to the shared alpha — put
        // 5.1:1 in its message, a figure only the black plate reaches. So it was an inherited literal,
        // not a second treatment. Documentation case: it pins the arithmetic, not a live surface.
        val pillOverWhite = compositeOverWhite(rgbOf(CameraColors.Pill), HUD_TEXT_SCRIM_ALPHA)
        val blackOverWhite = compositeOverWhite(rgbOf(CameraColors.ChromeScrim), HUD_TEXT_SCRIM_ALPHA)
        assertTrue("pill composited to $pillOverWhite", pillOverWhite == 0x454547)
        assertTrue("black composited to $blackOverWhite", blackOverWhite == 0x2E2E2E)
        val secondary = rgbOf(CameraColors.TextSecondary)
        assertTrue(contrastOnComposited(secondary, pillOverWhite) < 4.5)
        assertTrue(contrastOnComposited(secondary, blackOverWhite) >= 4.5)
        // And the white foreground cleared the floor on BOTH, which is exactly why the shortfall went
        // unnoticed: the chip label people look at was fine either way.
        assertTrue(contrastOnComposited(rgbOf(CameraColors.TextPrimary), pillOverWhite) >= 4.5)
    }

    /** Source-over composite of an [alpha] plate of [plateRgb] onto an opaque white frame. */
    private fun compositeOverWhite(plateRgb: Int, alpha: Float): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val plate = (plateRgb shr shift) and 0xFF
            val blended = Math.round(alpha * plate + (1f - alpha) * 255f).coerceIn(0, 255)
            out = out or (blended shl shift)
        }
        return out
    }

    /**
     * [contrastRatioOnWhiteScrim] takes a scrim ALPHA and assumes a black base; this takes an
     * already-composited plate colour, which is what a non-black base needs.
     */
    private fun contrastOnComposited(foregroundRgb: Int, plateRgb: Int): Double {
        val foreground = luminance(foregroundRgb)
        val plate = luminance(plateRgb)
        val lighter = maxOf(foreground, plate)
        val darker = minOf(foreground, plate)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Same WCAG relative luminance the production formula uses (that copy is file-private). */
    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
