package com.hletrd.findx9tele.ui.overlays

import org.junit.Assert.assertTrue
import org.junit.Test

class HudContrastTest {

    @Test
    fun `shared HUD scrim clears small-text contrast on a white frame`() {
        val foregrounds = mapOf(
            "primary" to 0xFFFFFF,
            "secondary" to 0x9E9E9E,
            "blue status accent" to 0x4C9AFF,
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
            "chrome/review/toast/scope white" to 0xFFFFFF,
            "chrome/metadata secondary #9E9E9E" to 0x9E9E9E,
            "chrome accent blue #8AB4F8" to 0x8AB4F8,
        )
        foregrounds.forEach { (label, rgb) ->
            val ratio = contrastRatioOnWhiteScrim(rgb, HUD_TEXT_SCRIM_ALPHA)
            assertTrue("$label contrast was $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `destructive review delete icon clears the floor at the shared alpha`() {
        // The red trash glyph (#FF6B6B) was the worst interactive contrast found (1.43:1 at 0.5 alpha);
        // at the shared floor it clears 4.5, so no opaque plate is needed. A destructive action must
        // never be ambiguous over a bright review frame.
        val ratio = contrastRatioOnWhiteScrim(0xFF6B6B, HUD_TEXT_SCRIM_ALPHA)
        assertTrue("delete-red contrast was $ratio", ratio >= 4.5)
    }

    @Test
    fun `every foreground the raised HUD surfaces use clears the floor at the shared alpha`() {
        // The cycle-2 fix routed StatusInfoPill, MemoryRecallStrip, ExposureMeter, ZoomIndicator,
        // RecordingIndicator, the half-press label, and RulerReadout through HUD_TEXT_SCRIM_ALPHA.
        // Pin their actual foregrounds so a future alpha/color tweak can't quietly sink one of
        // them back under 4.5:1 on a white frame.
        val foregrounds = mapOf(
            "white (pill/meter/REC time)" to 0xFFFFFF,
            "secondary (empty MR label)" to 0x9E9E9E,
            "accent blue (zoom readout)" to 0x8AB4F8,
            "manual yellow (ruler readout, half-press label)" to 0xFFD60A,
        )
        foregrounds.forEach { (label, rgb) ->
            val ratio = contrastRatioOnWhiteScrim(rgb, HUD_TEXT_SCRIM_ALPHA)
            assertTrue("$label contrast was $ratio", ratio >= 4.5)
        }
    }
}
