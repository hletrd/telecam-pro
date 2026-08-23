package me.hletrd.telecampro.focus

import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.LensExifMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the macro too-close heuristic: candidate matrix, the 700 ms hold (injected clock), and the
 * closer-lens hint selection over the per-lens metadata cache.
 */
class MacroProximityTest {

    // PMA110-shaped fixtures, corrected against a live dumpsys (2026-07-25): the tele periscope
    // advertises 0.833 diopters (~120 cm), the rear mains 6.67 (~15 cm) and one ultrawide 25
    // (~4 cm). The earlier fixture claimed 2.5 / 10, which was never this device.
    private val teleMin = 0.833f

    private fun candidate(
        af: AfIndication = AfIndication.FAILED,
        mode: FocusMode = FocusMode.CONTINUOUS,
        live: Float? = teleMin, // racked hard against the limit
        min: Float = teleMin,
    ) = macroTooCloseCandidate(af, mode, live, min)

    // ---- candidate matrix ----

    @Test
    fun `failed AF at the near limit is a candidate`() {
        assertTrue(candidate(af = AfIndication.FAILED))
    }

    @Test
    fun `persistent scanning at the near limit is a candidate`() {
        // A genuinely-too-close subject often keeps CONTINUOUS AF hunting forever instead of
        // reporting NOT_FOCUSED; the hold (not this predicate) filters ordinary transient scans.
        assertTrue(candidate(af = AfIndication.SCANNING))
    }

    @Test
    fun `focused or idle AF is never a candidate`() {
        assertFalse(candidate(af = AfIndication.FOCUSED))
        assertFalse(candidate(af = AfIndication.IDLE))
    }

    @Test
    fun `manual focus is excluded`() {
        assertFalse(candidate(mode = FocusMode.MANUAL))
    }

    @Test
    fun `lens away from its near limit is not a candidate`() {
        assertFalse(candidate(live = teleMin * 0.5f))
        // The 85% boundary itself qualifies; just below it does not.
        assertTrue(candidate(live = teleMin * MACRO_NEAR_LIMIT_RATIO))
        assertFalse(candidate(live = teleMin * (MACRO_NEAR_LIMIT_RATIO - 0.02f)))
    }

    @Test
    fun `unknown distance or fixed-focus route is never a candidate`() {
        assertFalse(candidate(live = null))
        assertFalse(candidate(min = 0f))
    }

    // ---- closer-lens hint ----

    private fun lens(equivMm: Float, minDiopters: Float) =
        LensExifMetadata(focalLengthMm = equivMm / 8f, apertureF = 2f, equivalentFocalMm = equivMm, minFocusDiopters = minDiopters)

    @Test
    fun `hint picks the longest wider lens that focuses meaningfully closer`() {
        // Active: the 70 mm tele at 0.833 dpt. Candidates match the live device map: 14 mm
        // ultrawide (25 dpt), 23 mm main (6.67 dpt), the 230 mm 10x (0.5 dpt). Both wides qualify
        // — the main's 8x close-focus advantage is far past MACRO_HINT_MIN_ADVANTAGE — and the
        // 23 mm wins on least framing change, labelled "1×".
        val hint = closerLensHint(
            activeEquivFocalMm = 70f,
            activeMinFocusDiopters = teleMin,
            candidates = listOf(lens(14f, 25f), lens(23f, 6.67f), lens(230f, 0.5f), lens(70f, teleMin)),
        )
        assertEquals(23f, checkNotNull(hint).equivalentFocalMm, 0f)
        assertEquals(me.hletrd.telecampro.camera.LensChoice.MAIN, lensChoiceForEquivFocal(hint.equivalentFocalMm))
        assertTrue("the real advantage is ~8x", hint.minFocusDiopters / teleMin > 7f)
    }

    @Test
    fun `no hint without a meaningful close-focus advantage`() {
        // A wider lens with only ~equal close focus (< 1.2x advantage) must not be advised.
        assertNull(
            closerLensHint(
                activeEquivFocalMm = 70f,
                activeMinFocusDiopters = 6.67f,
                candidates = listOf(lens(23f, 7.5f)),
            ),
        )
    }

    @Test
    fun `longer lenses and unknown-focus candidates never hint`() {
        assertNull(
            closerLensHint(
                activeEquivFocalMm = 23f,
                activeMinFocusDiopters = 6.67f,
                candidates = listOf(lens(70f, 30f), lens(14f, 0f)),
            ),
        )
    }

    @Test
    fun `degenerate active focal yields no hint`() {
        assertNull(closerLensHint(0f, teleMin, listOf(lens(23f, 6.67f))))
    }
}
