package me.hletrd.findx9tele.focus

import me.hletrd.findx9tele.camera.AfIndication
import me.hletrd.findx9tele.camera.FocusMode
import me.hletrd.findx9tele.camera.LensExifMetadata
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

    // PMA110-shaped fixtures: tele periscope focuses to ~2.5 diopters (~0.4 m); main to 10 (~0.1 m).
    private val teleMin = 2.5f

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

    // ---- hold ----

    @Test
    fun `tag shows only after the hold persists and clears instantly`() {
        val hold = MacroProximityHold(holdMs = 700L)
        assertFalse("first sighting arms but does not show", hold.update(true, nowMs = 1_000L))
        assertTrue("still pending inside the hold", hold.pending(1_400L))
        assertFalse(hold.update(true, nowMs = 1_400L))
        assertTrue("hold elapsed → show", hold.update(true, nowMs = 1_700L))
        assertFalse("no longer pending once shown", hold.pending(1_700L))
        assertFalse("condition cleared → hide instantly", hold.update(false, nowMs = 1_800L))
        assertFalse("a fresh sighting re-arms from zero", hold.update(true, nowMs = 1_900L))
        assertFalse(hold.update(true, nowMs = 2_500L))
        assertTrue(hold.update(true, nowMs = 2_600L))
    }

    @Test
    fun `an interrupted candidate never accumulates hold time`() {
        val hold = MacroProximityHold(holdMs = 700L)
        assertFalse(hold.update(true, nowMs = 0L))
        assertFalse(hold.update(false, nowMs = 400L))
        assertFalse("the earlier 400 ms must not count", hold.update(true, nowMs = 500L))
        assertFalse(hold.update(true, nowMs = 1_100L))
        assertTrue(hold.update(true, nowMs = 1_200L))
    }

    // ---- closer-lens hint ----

    private fun lens(equivMm: Float, minDiopters: Float) =
        LensExifMetadata(focalLengthMm = equivMm / 8f, apertureF = 2f, equivalentFocalMm = equivMm, minFocusDiopters = minDiopters)

    @Test
    fun `hint picks the longest wider lens that focuses meaningfully closer`() {
        // Active: 70 mm tele at 2.5 dpt. Candidates: 14 mm (8 dpt), 23 mm (10 dpt), 230 mm (2 dpt).
        // Both wides qualify; the 23 mm wins (least framing change), labelled "1×".
        val hint = closerLensHint(
            activeEquivFocalMm = 70f,
            activeMinFocusDiopters = teleMin,
            candidates = listOf(lens(14f, 8f), lens(23f, 10f), lens(230f, 2f), lens(70f, teleMin)),
        )
        assertEquals(23f, checkNotNull(hint).equivalentFocalMm, 0f)
        assertEquals("1×", lensLabelForEquivFocal(hint.equivalentFocalMm))
    }

    @Test
    fun `no hint without a meaningful close-focus advantage`() {
        // A wider lens with only ~equal close focus (< 1.2x advantage) must not be advised.
        assertNull(
            closerLensHint(
                activeEquivFocalMm = 70f,
                activeMinFocusDiopters = 8f,
                candidates = listOf(lens(23f, 9f)),
            ),
        )
    }

    @Test
    fun `longer lenses and unknown-focus candidates never hint`() {
        assertNull(
            closerLensHint(
                activeEquivFocalMm = 23f,
                activeMinFocusDiopters = 10f,
                candidates = listOf(lens(70f, 30f), lens(14f, 0f)),
            ),
        )
    }

    @Test
    fun `degenerate active focal yields no hint`() {
        assertNull(closerLensHint(0f, teleMin, listOf(lens(23f, 10f))))
    }
}
