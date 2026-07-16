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
}
