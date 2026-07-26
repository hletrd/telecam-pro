package me.hletrd.findx9tele.focus

import me.hletrd.findx9tele.camera.AfIndication
import me.hletrd.findx9tele.camera.FocusConfidenceSource
import me.hletrd.findx9tele.camera.FocusMode
import me.hletrd.findx9tele.camera.FrameDetail
import me.hletrd.findx9tele.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.findx9tele.ui.preferredProgramShutterNs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the FRAME_DETAIL admission gates, the two-proof OR, the value-latching hold, and the OSD
 * wording each proof licenses.
 *
 * The whole point of this file is the refusal side: [FrameDetail.SOFT] is necessary and nowhere
 * near sufficient, and every gate below removes a state in which unresolved detail has a
 * legitimate cause other than "the viewfinder is not resolving the subject".
 */
class FocusConfidenceTest {

    // 300 mm effective (70 mm x the teleconverter) -> the handheld rule is ~3.33 ms.
    private val handheldNs = preferredProgramShutterNs(70f, teleconverterMode = true, TELECONVERTER_MAGNIFICATION)

    private fun candidate(
        detail: FrameDetail? = FrameDetail.SOFT,
        ageMs: Long = 100L,
        mode: FocusMode = FocusMode.CONTINUOUS,
        af: AfIndication = AfIndication.FOCUSED,
        recording: Boolean = false,
        recordingStarting: Boolean = false,
        zoomInteracting: Boolean = false,
        exposureNs: Long? = 10_000_000L, // 1/100 s
    ) = frameDefocusCandidate(
        detail = detail,
        detailAgeMs = ageMs,
        focusMode = mode,
        afIndication = af,
        recording = recording,
        recordingStarting = recordingStarting,
        zoomInteracting = zoomInteracting,
        exposureNs = exposureNs,
        handheldShutterNs = handheldNs,
    )

    // ---- the verdict itself ----

    @Test
    fun `only a soft verdict can arm - unjudgeable is not weak evidence`() {
        assertTrue(candidate(detail = FrameDetail.SOFT))
        assertFalse(candidate(detail = FrameDetail.RESOLVED))
        assertFalse(candidate(detail = FrameDetail.UNJUDGEABLE))
        assertFalse(candidate(detail = null))
    }

    // ---- the gates ----

    @Test
    fun `manual focus is excluded and every AF mode is admitted`() {
        // The user owns the distance in MANUAL, and deliberate defocus is a creative state. A MACRO
        // AF mode that still cannot resolve is exactly worth saying, so it admits like the rest.
        assertFalse(candidate(mode = FocusMode.MANUAL))
        assertTrue(candidate(mode = FocusMode.AUTO))
        assertTrue(candidate(mode = FocusMode.CONTINUOUS))
        assertTrue(candidate(mode = FocusMode.MACRO))
    }

    @Test
    fun `a scanning lens is refused - it is defocused by design`() {
        // Note this INVERTS the AF_LIMIT predicate, which reads persistent scanning as evidence.
        assertFalse(candidate(af = AfIndication.SCANNING))
        assertTrue(candidate(af = AfIndication.FOCUSED))
        assertTrue(candidate(af = AfIndication.FAILED))
        assertTrue(candidate(af = AfIndication.IDLE))
    }

    @Test
    fun `recording refuses but armed video admits`() {
        assertFalse(candidate(recording = true))
        assertFalse(candidate(recordingStarting = true))
        assertTrue("armed (pre-roll) video is where this is actionable", candidate())
    }

    @Test
    fun `stale statistics cannot speak for the live preview`() {
        assertTrue(candidate(ageMs = 900L))
        assertTrue("the boundary itself still counts", candidate(ageMs = FOCUS_DETAIL_MAX_AGE_MS))
        assertFalse(candidate(ageMs = FOCUS_DETAIL_MAX_AGE_MS + 1))
        assertFalse(candidate(ageMs = 1_500L))
        assertFalse("a clock that went backwards is not fresh", candidate(ageMs = -1L))
    }

    @Test
    fun `a zoom gesture refuses - its HAL submits gap the stream`() {
        assertFalse(candidate(zoomInteracting = true))
    }

    @Test
    fun `the exposure gate refuses frames whose own motion blur is ambiguous`() {
        // 16x the 3.33 ms handheld rule at 300 mm is ~53 ms; PREVIEW_FLUIDITY_MAX_EXPOSURE_NS caps
        // the finder at 1/15 s (~67 ms), so the detector is refused across the whole dark-preview
        // regime where a hand-held 300 mm frame is smeared anyway.
        val ceiling = handheldNs * FOCUS_DETAIL_MAX_SHUTTER_FACTOR
        assertTrue(ceiling in 50_000_000L..56_000_000L)
        assertTrue(candidate(exposureNs = 20_000_000L))
        assertTrue("the boundary is inclusive", candidate(exposureNs = ceiling))
        assertFalse(candidate(exposureNs = ceiling + 1))
        assertFalse(candidate(exposureNs = 60_000_000L))
        assertFalse("1/15 s, the fluidity cap itself, is refused", candidate(exposureNs = 66_666_667L))
        // Nothing to gate on is a refusal, not a pass.
        assertFalse(candidate(exposureNs = null))
        assertFalse(candidate(exposureNs = 0L))
    }

    @Test
    fun `a wider lens gets a proportionally longer exposure allowance`() {
        // The gate scales with the handheld rule, so 1x main tolerates far more than 300 mm does.
        val wide = preferredProgramShutterNs(23f, teleconverterMode = false, TELECONVERTER_MAGNIFICATION)
        assertTrue(
            frameDefocusCandidate(
                FrameDetail.SOFT, 100L, FocusMode.CONTINUOUS, AfIndication.FOCUSED,
                recording = false, recordingStarting = false, zoomInteracting = false,
                exposureNs = 400_000_000L, handheldShutterNs = wide,
            ),
        )
        assertFalse(
            frameDefocusCandidate(
                FrameDetail.SOFT, 100L, FocusMode.CONTINUOUS, AfIndication.FOCUSED,
                recording = false, recordingStarting = false, zoomInteracting = false,
                exposureNs = 400_000_000L, handheldShutterNs = handheldNs,
            ),
        )
        // A degenerate handheld figure must not divide-by-zero into an always-open gate.
        assertFalse(
            frameDefocusCandidate(
                FrameDetail.SOFT, 100L, FocusMode.CONTINUOUS, AfIndication.FOCUSED,
                recording = false, recordingStarting = false, zoomInteracting = false,
                exposureNs = 1_000L, handheldShutterNs = 0L,
            ),
        )
    }

    // ---- the two-proof OR ----

    @Test
    fun `AF_LIMIT wins when both proofs hold - it establishes strictly more`() {
        assertEquals(FocusConfidenceSource.AF_LIMIT, focusConfidenceCandidate(afLimit = true, frameDetail = true))
        assertEquals(FocusConfidenceSource.AF_LIMIT, focusConfidenceCandidate(afLimit = true, frameDetail = false))
        assertEquals(FocusConfidenceSource.FRAME_DETAIL, focusConfidenceCandidate(afLimit = false, frameDetail = true))
        assertNull(focusConfidenceCandidate(afLimit = false, frameDetail = false))
    }

    // ---- the hold ----

    @Test
    fun `the tag shows only after the hold persists and clears instantly`() {
        val hold = FocusConfidenceHold(holdMs = 700L)
        val soft = FocusConfidenceSource.FRAME_DETAIL
        assertNull("first sighting arms but does not show", hold.update(soft, nowMs = 1_000L))
        assertTrue("still pending inside the hold", hold.pending(1_400L))
        assertNull(hold.update(soft, nowMs = 1_400L))
        assertEquals(soft, hold.update(soft, nowMs = 1_700L))
        assertFalse("no longer pending once shown", hold.pending(1_700L))
        assertNull("condition cleared → hide instantly", hold.update(null, nowMs = 1_800L))
        assertFalse(hold.pending(1_800L))
        assertNull("a fresh sighting re-arms from zero", hold.update(soft, nowMs = 1_900L))
        assertNull(hold.update(soft, nowMs = 2_500L))
        assertEquals(soft, hold.update(soft, nowMs = 2_600L))
    }

    @Test
    fun `an interrupted candidate never accumulates hold time`() {
        val hold = FocusConfidenceHold(holdMs = 700L)
        val soft = FocusConfidenceSource.FRAME_DETAIL
        assertNull(hold.update(soft, nowMs = 0L))
        assertNull(hold.update(null, nowMs = 400L))
        assertNull("the earlier 400 ms must not count", hold.update(soft, nowMs = 500L))
        assertNull(hold.update(soft, nowMs = 1_100L))
        assertEquals(soft, hold.update(soft, nowMs = 1_200L))
    }

    @Test
    fun `a mid-scan refusal restarts the window rather than shortening it`() {
        // The refusal-resets-the-hold property IS the AF settle wait: the 700 ms only starts once
        // the lens has stopped sweeping, so the tag cannot blink through an ordinary AF hunt.
        val hold = FocusConfidenceHold(holdMs = 700L)
        val soft = FocusConfidenceSource.FRAME_DETAIL
        assertFalse(candidate(af = AfIndication.SCANNING))
        assertNull(hold.update(soft, nowMs = 0L))
        assertNull(hold.update(null, nowMs = 400L)) // AF started scanning
        assertNull(hold.update(soft, nowMs = 500L))
        assertNull("still inside a FRESH 700 ms at t=1000", hold.update(soft, nowMs = 1_000L))
        assertEquals(soft, hold.update(soft, nowMs = 1_200L))
    }

    @Test
    fun `a change of source re-arms from zero`() {
        // The two proofs print different text, so elapsed time under one must not publish the other.
        val hold = FocusConfidenceHold(holdMs = 700L)
        assertNull(hold.update(FocusConfidenceSource.AF_LIMIT, nowMs = 0L))
        assertEquals(FocusConfidenceSource.AF_LIMIT, hold.update(FocusConfidenceSource.AF_LIMIT, nowMs = 700L))
        assertNull(
            "switching proof restarts the hold",
            hold.update(FocusConfidenceSource.FRAME_DETAIL, nowMs = 800L),
        )
        assertNull(hold.update(FocusConfidenceSource.FRAME_DETAIL, nowMs = 1_400L))
        assertEquals(
            FocusConfidenceSource.FRAME_DETAIL,
            hold.update(FocusConfidenceSource.FRAME_DETAIL, nowMs = 1_500L),
        )
    }

    @Test
    fun `reset drops a shown latch so an optics door cannot publish stale evidence`() {
        val hold = FocusConfidenceHold(holdMs = 700L)
        val soft = FocusConfidenceSource.FRAME_DETAIL
        assertNull(hold.update(soft, nowMs = 0L))
        assertEquals(soft, hold.update(soft, nowMs = 700L))
        hold.reset()
        assertFalse(hold.pending(700L))
        assertNull("the new route must earn its own 700 ms", hold.update(soft, nowMs = 700L))
        assertEquals(soft, hold.update(soft, nowMs = 1_400L))
    }

    // ---- the wording ----

    @Test
    fun `each proof gets only the wording it can defend`() {
        assertNull(focusConfidenceLabel(null, null))
        assertNull(focusConfidenceLabel(null, "1×"))
        assertEquals("TOO CLOSE", focusConfidenceLabel(FocusConfidenceSource.AF_LIMIT, null))
        // The separator is U+2192, and this exact-string pin is what keeps it one: the suffix
        // shipped with U+25B8, which none of the three bundled Inter faces (res/font) carries, so
        // it fell back to a system typeface inside an otherwise-Inter OSD row.
        assertEquals("TOO CLOSE → 1×", focusConfidenceLabel(FocusConfidenceSource.AF_LIMIT, "1×"))
        assertEquals("SOFT", focusConfidenceLabel(FocusConfidenceSource.FRAME_DETAIL, null))
        // The detail proof takes NO lens suffix even when a closer lens exists: "→ 1×" is a
        // distance remedy, and the metric cannot establish that distance is the cause.
        assertEquals("SOFT", focusConfidenceLabel(FocusConfidenceSource.FRAME_DETAIL, "1×"))
    }
}
