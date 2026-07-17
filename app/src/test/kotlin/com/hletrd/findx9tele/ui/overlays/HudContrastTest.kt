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
