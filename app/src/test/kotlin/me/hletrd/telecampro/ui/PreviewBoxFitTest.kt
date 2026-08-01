package me.hletrd.telecampro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview letterbox must FIT INSIDE both axes. Device-reproduced 2026-08-02 on a Lenovo TB336ZU
 * (Android 16, 1600x2560): API 36 ignores the portrait lock at sw>=600dp, and the width-bound-only
 * math asked for 3413 px of preview height inside a 1600 px landscape window — viewfinder clipped,
 * most of the window dead black. Split-screen and freeform reach the same shape on phones.
 */
class PreviewBoxFitTest {

    private val portrait4x3 = 3f / 4f // displayed 4:3 stills, portrait-held
    private val portrait16x9 = 9f / 16f

    @Test
    fun `portrait windows still bind on width — the shipped PMA110 geometry is unchanged`() {
        // 1440x3168 (PMA110) and 1600x2560 (tablet portrait): the height-bound candidate is larger,
        // so the width still binds and the value equals the old formula exactly.
        assertEquals(1440, previewBoxWidthPx(1440, 3168, portrait4x3))
        assertEquals(1440, previewBoxWidthPx(1440, 3168, portrait16x9))
        assertEquals(1600, previewBoxWidthPx(1600, 2560, portrait4x3))
    }

    @Test
    fun `a landscape window binds on height and letterboxes on the sides`() {
        val w = previewBoxWidthPx(2560, 1600, portrait4x3)
        assertEquals(1200, w) // 1600 * 3/4
        val h = (w / portrait4x3).toInt()
        assertTrue("preview must fit the window height, was $h", h <= 1600)
        assertTrue("preview must fit the window width, was $w", w <= 2560)
    }

    @Test
    fun `a square-ish foldable window and a short split pane both fit`() {
        for ((cw, ch) in listOf(1800 to 2208, 1440 to 1500, 2208 to 1800, 1080 to 700)) {
            val w = previewBoxWidthPx(cw, ch, portrait4x3)
            val h = (w / portrait4x3).toInt()
            assertTrue("w=$w > $cw", w <= cw)
            assertTrue("h=$h > $ch", h <= ch + 1)
        }
    }

    @Test
    fun `degenerate inputs degrade to the available width instead of collapsing`() {
        assertEquals(1080, previewBoxWidthPx(1080, 2400, 0f))
        assertEquals(0, previewBoxWidthPx(0, 2400, portrait4x3))
    }
}
