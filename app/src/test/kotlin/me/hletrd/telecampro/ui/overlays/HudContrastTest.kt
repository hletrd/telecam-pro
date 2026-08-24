package me.hletrd.telecampro.ui.overlays

import androidx.compose.ui.graphics.toArgb
import me.hletrd.telecampro.ui.theme.CameraColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudContrastTest {

    // TELE's idle plate had a case of its own here, asserting a `teleChipIdleScrimAlpha()` seam. Both
    // are gone: TeleChip paints HudPlate directly, so the seam returned HUD_TEXT_SCRIM_ALPHA and the
    // plate is DEFINED from HUD_TEXT_SCRIM_ALPHA — its assertions could not fail in either direction,
    // which made it a constant-equality check wearing a drift guard's comment. The two facts it meant
    // to pin are pinned for real below: `HudPlate is the scrim token at the pinned alpha and nothing
    // else` pins the drawn slab's composition, and the "primary" entry in `shared HUD scrim clears
    // small-text contrast on a white frame` pins the idle chip's white label at 4.5:1 over a white
    // frame. Deleted with the function it pinned, not weakened.

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
            "low-battery and running-timelapse alarm text" to rgbOf(CameraColors.AlarmText),
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
        //  - review close glyph (white), review zoom-scale badge (white), center status/error toast (white)
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
    fun `record shapes and critical text keep separate contrast contracts`() {
        val shapeRatio = contrastRatioOnWhiteScrim(rgbOf(CameraColors.Record), HUD_TEXT_SCRIM_ALPHA)
        val textRatio = contrastRatioOnWhiteScrim(rgbOf(CameraColors.AlarmText), HUD_TEXT_SCRIM_ALPHA)
        assertTrue("record shape contrast was $shapeRatio", shapeRatio >= 3.0)
        assertTrue("record red must not be reused as small text", shapeRatio < 4.5)
        assertTrue("alarm text contrast was $textRatio", textRatio >= 4.5)
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

    @Test
    fun `the zoom bar track survives a bright frame only once it sits on the plate`() {
        // ZoomIndicator's 180x4 dp track is a 0.25-white slab and its fill is opaque Accent. It was the
        // one chrome readout drawn BARE on the live image (the "N.N×" pill 6 dp above it has a plate),
        // so a bright sky erased the track and left the fill floating with nothing to be a fraction of.
        // Non-text graphics floor is 3:1 (WCAG 1.4.11), not 4.5. Measured with this file's own formula:
        //   over a white frame, bare:   track -> #FFFFFF (1.00:1, invisible)   fill 2.11:1
        //   over a white frame, plated: track -> #626262 (6.10:1)              fill 6.44:1
        //   fill vs the plated track    2.89:1 (the bar still reads as a scale, not a slab)
        //   over a black frame          #404040 either way — unchanged
        val track = 0xFFFFFF
        val trackAlpha = 0.25f
        val white = 0xFFFFFF
        val accent = rgbOf(CameraColors.Accent)

        // Pre-fix, over a white frame: the track composites to pure white — literally invisible — and
        // the fill misses 3:1 on its own.
        val bareTrack = compositeOn(track, trackAlpha, white)
        assertTrue("bare track composited to $bareTrack", bareTrack == white)
        assertTrue(contrastOnComposited(bareTrack, white) < 1.1)
        assertTrue("accent on white was ${contrastOnComposited(accent, white)}", contrastOnComposited(accent, white) < 3.0)

        // Post-fix: the plate goes under the track, so both the empty track and the fill are measured
        // against the same slab every sibling HUD readout uses.
        val platedFrame = compositeOverWhite(rgbOf(CameraColors.ChromeScrim), HUD_TEXT_SCRIM_ALPHA)
        val platedTrack = compositeOn(track, trackAlpha, platedFrame)
        assertTrue("plated track vs frame was ${contrastOnComposited(platedTrack, white)}", contrastOnComposited(platedTrack, white) >= 3.0)
        val fillOnPlate = contrastRatioOnWhiteScrim(accent, HUD_TEXT_SCRIM_ALPHA)
        assertTrue("accent fill on the plate was $fillOnPlate", fillOnPlate >= 3.0)
        // And the fill stays distinguishable FROM the track it fills, which is what makes the bar a scale.
        assertTrue("fill vs track was ${contrastOnComposited(accent, platedTrack)}", contrastOnComposited(accent, platedTrack) >= 1.5)

        // Over a DARK scene nothing moves: 82% black over black is black, so the track composites to the
        // exact colour it did before. This fix may only add worst-case contrast, never trade any.
        val darkBefore = compositeOn(track, trackAlpha, 0x000000)
        val darkAfter = compositeOn(track, trackAlpha, compositeOn(rgbOf(CameraColors.ChromeScrim), HUD_TEXT_SCRIM_ALPHA, 0x000000))
        assertTrue("dark-scene track moved from $darkBefore to $darkAfter", darkBefore == darkAfter)
    }

    @Test
    fun `the sheet's capability captions read better in secondary than in the recording red`() {
        // The ProSheet panel is OPAQUE CameraColors.Pill, so its captions are measured directly
        // against that surface rather than through a scrim alpha. Two Video-tab capability captions
        // ("No supported resolution" / "No supported frame rate") used to paint CameraColors.Record;
        // they are TextSecondary now, like every sibling capability caption. The point of pinning it
        // here: the louder colour was also the WEAKER one, so this was never a contrast trade.
        val sheet = rgbOf(CameraColors.Pill)
        val secondary = contrastOnComposited(rgbOf(CameraColors.TextSecondary), sheet)
        val record = contrastOnComposited(rgbOf(CameraColors.Record), sheet)
        assertTrue("caption contrast was $secondary", secondary >= 4.5)
        assertTrue("caption $secondary must not undercut the red's $record", secondary > record)
    }

    @Test
    fun `the ink token is exactly white, which is what makes naming a white site free`() {
        // Seventeen `Color.White` draws (shutter/snapshot discs, the OSD's neutral tags, the REC
        // timecode, the timer digit, the review playback glyphs, the Switch knob, the level gauge's
        // off-level line) were renamed to TextPrimary on the strength of ONE fact: TextPrimary IS
        // opaque #FFFFFF, so the rename could not move a pixel. Pin the fact rather than the renames.
        // If someone warms the ink (#FFFEF8, say), those seventeen sites shift together and this
        // fails first — which is the correct outcome, since a warm ink is a decision about glyphs.
        assertEquals(0xFFFFFF, rgbOf(CameraColors.TextPrimary))
        assertEquals(1f, CameraColors.TextPrimary.alpha, 0f)
    }

    @Test
    fun `the white-derived structural tokens stay six distinct roles`() {
        // AffordanceEdge and GuideLine were minted from repeated inline literals: four
        // interactive borders and two composition guides. Two things must hold. First, each is
        // exact role token remains distinct; AffordanceEdge was later raised for 3:1 non-text contrast.
        val white = androidx.compose.ui.graphics.Color.White
        assertTrue(CameraColors.AffordanceEdge == white.copy(alpha = 0.36f))
        // GuideLine was minted at 0.55 (the wash it replaced) and RESTYLED to 0.40 on 2026-07-28,
        // deliberately: the operator reported the grid and level reading too bright and heavy on the
        // live image. Re-pinned rather than loosened, so the next change is equally visible in a
        // diff — this assertion's job is to make a restyle explicit, not to freeze the value.
        assertTrue(CameraColors.GuideLine == white.copy(alpha = 0.40f))
        // ScopeFrame (0.3) closed a real drift rather than replacing a repeated literal: the two scope
        // plot frames were 0.3 and 0.35 for one role. It is pinned at the SURVIVING number, so a
        // future edit cannot quietly reopen the gap by nudging the token toward the value it retired.
        assertTrue(CameraColors.ScopeFrame == white.copy(alpha = 0.3f))
        // Second, the six white-derived tokens stay six NUMBERS. They are close enough to look like
        // redundancy in a diff (0.04 / 0.09 / 0.14 / 0.36 / 0.3 / 0.4), and the temptation to collapse
        // "nearly the same grey" is exactly how the Block and Hairline drifts happened in the first
        // place. Each encodes a different role; a merge must delete a token, not quietly equalize it.
        val alphas = listOf(
            CameraColors.BlockDisabled.alpha,
            CameraColors.Block.alpha,
            CameraColors.Hairline.alpha,
            CameraColors.AffordanceEdge.alpha,
            CameraColors.ScopeFrame.alpha,
            CameraColors.GuideLine.alpha,
        )
        assertEquals("white-derived tokens collapsed: $alphas", alphas.size, alphas.toSet().size)
        // And all six really are white washes, not tinted ones — a tinted "hairline" would drag a
        // hue into edges that are supposed to be neutral over arbitrary live pixels.
        listOf(
            CameraColors.BlockDisabled, CameraColors.Block, CameraColors.Hairline,
            CameraColors.AffordanceEdge, CameraColors.ScopeFrame, CameraColors.GuideLine,
        ).forEach { assertEquals(0xFFFFFF, rgbOf(it)) }
    }

    @Test
    fun `enabled affordance edge clears non-text contrast on every adjacent dark plate`() {
        val plates = mapOf(
            "sheet pill" to rgbOf(CameraColors.Pill),
            "HUD over bright frame" to compositeOn(0x000000, HUD_TEXT_SCRIM_ALPHA, 0xFFFFFF),
            "HUD over dark frame" to compositeOn(0x000000, HUD_TEXT_SCRIM_ALPHA, 0x000000),
        )
        plates.forEach { (label, plate) ->
            val edge = compositeOn(
                rgbOf(CameraColors.AffordanceEdge),
                CameraColors.AffordanceEdge.alpha,
                plate,
            )
            val ratio = contrastOnComposited(edge, plate)
            assertTrue("$label enabled affordance edge contrast was $ratio", ratio >= 3.0)
        }
        // The 18% predecessor measured below the component-boundary floor. Keep the negative
        // control so a future alpha change cannot weaken this into a vacuous formula check.
        val sheet = plates.getValue("sheet pill")
        val oldEdge = compositeOn(0xFFFFFF, 0.18f, sheet)
        assertTrue(contrastOnComposited(oldEdge, sheet) < 3.0)
    }

    /** Source-over composite of an [alpha] plate of [plateRgb] onto an opaque white frame. */
    private fun compositeOverWhite(plateRgb: Int, alpha: Float): Int =
        compositeOn(plateRgb, alpha, 0xFFFFFF)

    /** Source-over composite of an [alpha] layer of [overRgb] onto the opaque [baseRgb]. */
    private fun compositeOn(overRgb: Int, alpha: Float, baseRgb: Int): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val over = (overRgb shr shift) and 0xFF
            val base = (baseRgb shr shift) and 0xFF
            val blended = Math.round(alpha * over + (1f - alpha) * base).coerceIn(0, 255)
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
