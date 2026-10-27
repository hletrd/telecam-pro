package me.hletrd.telecampro.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/**
 * The TELE rail's mark typography. A mark is DERIVED from the mounted converter and the lens's own
 * zoom range, so it arrives as an awkward float (13.043478, 6.0869565) and must print in the rail's
 * own idiom — the same "decimals only where they carry information" rule the lens chips read by.
 */
class ZoomMarkFormatTest {

    @Test
    fun `a mark drops a decimal that carries no information`() {
        // The kit optic's native field is 13.043478x; "13.0×" beside "30×" and "60×" reads as noise.
        assertEquals("13×", formatZoomMark(13.043478f))
        assertEquals("30×", formatZoomMark(30f))
        // A ceiling computed as 4.6 x 13.043478 lands a hair off 60 and must still print "60×".
        assertEquals("60×", formatZoomMark(59.999996f))
    }

    @Test
    fun `a mark below 10x keeps a decimal that carries information`() {
        // A generic 2x clip-on's native field.
        assertEquals("6.1×", formatZoomMark(6.0869565f))
        assertEquals("1.5×", formatZoomMark(1.5f))
        assertEquals("9.9×", formatZoomMark(9.94f))
    }

    @Test
    fun `a mark from 10x rounds to a whole number`() {
        // A tenth is noise at two digits, and the TC + maker Log ceiling's "42.6×" wrapped inside the
        // 48 dp rail chip (owner decision 2026-10-28). 9.96 prints at the whole-number form too.
        assertEquals("26×", formatZoomMark(26.086956f))
        assertEquals("43×", formatZoomMark(42.6f))
        assertEquals("10×", formatZoomMark(9.96f))
    }

    @Test
    fun `a mark reads the same in every locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("6.1×", formatZoomMark(6.0869565f))
            assertFalse("6.1× must not read 6,1×", formatZoomMark(6.0869565f).contains(','))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
