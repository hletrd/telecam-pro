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
    fun `a mark keeps a decimal that does`() {
        // A generic 2x clip-on's native field, and a lens whose digital ceiling stops mid-range.
        assertEquals("6.1×", formatZoomMark(6.0869565f))
        assertEquals("26.1×", formatZoomMark(26.086956f))
        assertEquals("1.5×", formatZoomMark(1.5f))
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
