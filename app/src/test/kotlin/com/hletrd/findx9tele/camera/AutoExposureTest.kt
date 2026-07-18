package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the app-side auto-exposure decision logic. The drive functions take plain
 * Int/Long bounds (android.util.Range throws "not mocked" on the JVM), so the SHUTTER/ISO priority
 * loops AND the photo-P program line are fully testable here.
 */
class AutoExposureTest {

    /** A 256-bin luma histogram with every sample in one bin. */
    private fun histAt(bin: Int, count: Int = 1000): IntArray =
        IntArray(256).also { it[bin.coerceIn(0, 255)] = count }

    // Typical tele ranges: ISO 50..12800, exposure 100 µs .. 1 s.
    private val isoMin = 50
    private val isoMax = 12800
    private val expMin = 100_000L
    private val expMax = 1_000_000_000L

    // The "preferred" handheld shutter at ~300 mm effective focal: 1/300 s.
    private val prefNs = 3_333_333L

    @Test
    fun meanLuma_midBin_isHalf() {
        // Bin 128 of 0..255 → ~0.502.
        assertEquals(0.502f, AutoExposure.meanLuma(histAt(128)), 0.01f)
    }

    @Test
    fun meanLuma_black_isZero() {
        assertEquals(0f, AutoExposure.meanLuma(histAt(0)), 0.0001f)
        assertEquals(0f, AutoExposure.meanLuma(IntArray(256)), 0.0001f) // empty → 0, no divide-by-zero
    }

    @Test
    fun meanLuma_white_isOne() {
        assertEquals(1f, AutoExposure.meanLuma(histAt(255)), 0.0001f)
    }

    @Test
    fun correction_darkScene_brightens() {
        // Mean well below the 0.45 target → positive correction (open up).
        val c = AutoExposure.correctionStops(0.15f, 0f)
        assertTrue("expected a correction", c != null)
        assertTrue("dark scene should brighten (positive stops), was $c", c!! > 0f)
    }

    @Test
    fun correction_brightScene_darkens() {
        val c = AutoExposure.correctionStops(0.85f, 0f)
        assertTrue(c != null)
        assertTrue("bright scene should darken (negative stops), was $c", c!! < 0f)
    }

    @Test
    fun correction_onTarget_isNullDeadband() {
        // Mean == target → within the deadband → no update.
        assertNull(AutoExposure.correctionStops(AutoExposure.TARGET_LUMA, 0f))
    }

    @Test
    fun correction_isClampedToMaxStep() {
        // A pitch-dark meter must not demand more than the documented 0.30-stop per-tick clamp
        // (P7.1/TEST4-15: the old 1.0-stop assertion silently tolerated a 3x-too-fast AE swing).
        val c = AutoExposure.correctionStops(0.001f, 0f)!!
        assertTrue("per-tick step must be <= 0.30 stop, was $c", c <= 0.30f + 1e-4f)
    }

    @Test
    fun correction_evCompRaisesTarget() {
        // At a fixed mean below target, +1 EV raises the target further above the mean, so the
        // brighten correction is at least as large as with no compensation.
        val base = AutoExposure.correctionStops(0.2f, 0f)!!
        val plus = AutoExposure.correctionStops(0.2f, 1f)!!
        assertTrue("more +EV should brighten at least as much ($plus vs $base)", plus >= base)
    }

    // ---- driveIso (SHUTTER priority) ----

    @Test
    fun driveIso_darkScene_raisesIso_withinRange() {
        val next = AutoExposure.driveIso(histAt(20), currentIso = 400, isoMin = isoMin, isoMax = isoMax, evCompStops = 0f)
        assertTrue("dark scene must raise ISO", next != null && next > 400)
        assertTrue("must stay in range", next!! <= isoMax)
    }

    @Test
    fun driveIso_brightScene_lowersIso_clampedAtMin() {
        // Bright scene at base ISO: the correction wants to darken but ISO can't go lower →
        // clamped result equals the current value → null (no pointless request rebuild).
        assertNull(AutoExposure.driveIso(histAt(240), currentIso = isoMin, isoMin = isoMin, isoMax = isoMax, evCompStops = 0f))
    }

    @Test
    fun driveIso_converged_returnsNull() {
        // Mean at the target (bin ≈ 0.45*255 ≈ 115) → deadband → no update.
        assertNull(AutoExposure.driveIso(histAt(115), currentIso = 400, isoMin = isoMin, isoMax = isoMax, evCompStops = 0f))
    }

    // ---- driveShutterNs (ISO priority) ----

    @Test
    fun driveShutter_darkScene_lengthensExposure_withinRange() {
        val next = AutoExposure.driveShutterNs(histAt(20), currentNs = 8_000_000L, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f)
        assertTrue("dark scene must lengthen the shutter", next != null && next > 8_000_000L)
        assertTrue(next!! <= expMax)
    }

    @Test
    fun driveShutter_brightScene_shortensExposure() {
        val next = AutoExposure.driveShutterNs(histAt(240), currentNs = 8_000_000L, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f)
        assertTrue("bright scene must shorten the shutter", next != null && next < 8_000_000L)
        assertTrue(next!! >= expMin)
    }

    // ---- driveProgram (photo-P program line) ----

    @Test
    fun program_settledAtPreferredShutter_returnsNull() {
        // On target, shutter already at the preferred point → nothing to do.
        val r = AutoExposure.driveProgram(
            histAt(115), currentIso = 400, currentNs = prefNs, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f,
        )
        assertNull(r)
    }

    @Test
    fun program_darkScene_isoCarriesExposure_shutterHoldsPreferred() {
        // Mid-range ISO, dark scene: ISO must rise; the shutter stays pinned at the handheld point.
        val (iso, ns) = AutoExposure.driveProgram(
            histAt(20), currentIso = 400, currentNs = prefNs, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f,
        )!!
        assertTrue("ISO carries the exposure in the dark", iso > 400)
        assertEquals("shutter holds the preferred point while ISO has headroom", prefNs, ns)
    }

    @Test
    fun program_isoClamped_shutterSlides_pastPreferred() {
        // Already at max ISO in the dark: the remainder must go into a slower shutter.
        val (iso, ns) = AutoExposure.driveProgram(
            histAt(20), currentIso = isoMax, currentNs = prefNs, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f,
        )!!
        assertEquals("ISO stays clamped at max", isoMax, iso)
        assertTrue("shutter lengthens past the preferred point", ns > prefNs)
    }

    @Test
    fun program_exposureFloorAboveHandheldCap_clampsInsteadOfThrowing() {
        // Degenerate hardware report: an exposure FLOOR above the 1/10 s handheld ceiling.
        // coerceIn(min, max) would throw with min > max; the guard clamps the ceiling to the floor.
        val r = AutoExposure.driveProgram(
            histAt(115), currentIso = 400, currentNs = 200_000_000L, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax,
            expMinNs = 200_000_000L, expMaxNs = 300_000_000L, evCompStops = 0f,
        )
        // Whatever it decides, the shutter must sit exactly on the only legal point: the floor.
        r?.let { (_, ns) -> assertEquals(200_000_000L, ns) }
    }

    @Test
    fun program_invertedExposureRange_clampsInsteadOfThrowing() {
        val r = AutoExposure.driveProgram(
            histAt(20), currentIso = 400, currentNs = prefNs, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax,
            expMinNs = 8_000_000L, expMaxNs = 4_000_000L, evCompStops = 0f,
        )
        r?.let { (_, ns) -> assertEquals(8_000_000L, ns) }
    }

    @Test
    fun program_darkCeiling_neverExceedsHandheldCap() {
        // Pitch black at max ISO with a huge sensor ceiling: the shutter must respect the 1/10 s
        // handheld cap, not the sensor's 1 s upper bound.
        var ns = prefNs
        repeat(60) { // let the ≤1-stop-per-tick slide converge
            val r = AutoExposure.driveProgram(
                histAt(0), currentIso = isoMax, currentNs = ns, preferredNs = prefNs,
                isoMin = isoMin, isoMax = isoMax, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f,
            ) ?: return@repeat
            ns = r.second
        }
        assertTrue("dark ceiling is 1/10 s (100 ms), was ${ns}ns", ns <= 100_000_000L)
    }

    @Test
    fun program_brightAtBaseIso_shutterShortensBelowPreferred() {
        val (iso, ns) = AutoExposure.driveProgram(
            histAt(250), currentIso = isoMin, currentNs = prefNs, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f,
        )!!
        assertEquals("ISO stays at base", isoMin, iso)
        assertTrue("shutter shortens below the preferred point in the bright", ns < prefNs)
    }

    @Test
    fun program_shutterRecenter_isBrightnessNeutral_oneStopMax() {
        // Converged scene but shutter displaced from preferred (e.g. after a dark spell): it must
        // slide back toward preferred by at most ~1 stop per tick while ISO counter-moves.
        val displaced = prefNs * 8 // 3 stops slower than preferred
        val (iso, ns) = AutoExposure.driveProgram(
            histAt(115), currentIso = 1600, currentNs = displaced, preferredNs = prefNs,
            isoMin = isoMin, isoMax = isoMax, expMinNs = expMin, expMaxNs = expMax, evCompStops = 0f,
        )!!
        assertTrue("shutter moves toward preferred", ns < displaced)
        val stops = kotlin.math.ln(displaced.toDouble() / ns) / kotlin.math.ln(2.0)
        assertTrue("per-tick shutter slide is bounded (~0.35 stop), was $stops", stops <= 0.36)
        assertTrue("ISO counter-moves up so brightness holds", iso > 1600)
    }

    // ---- cycle-2 additions ----

    @Test
    fun correction_pitchBlackMean_returnsMaxStep() {
        // mean <= 0 (a fully-black meter) takes the "open up" branch → the per-tick cap MAX_STEP_STOPS.
        assertEquals(0.30f, AutoExposure.correctionStops(0f, 0f)!!, 1e-6f)
    }

    @Test
    fun driveIso_correctionRoundsBackToCurrent_returnsNull() {
        // A real correction (bin 110 is ~0.06 stop below target, outside the deadband) but at this
        // small ISO the rounded next value lands back on the current one → still null (no rebuild).
        assertNull(AutoExposure.driveIso(histAt(110), currentIso = 10, isoMin = 5, isoMax = isoMax, evCompStops = 0f))
    }

    @Test
    fun driveShutter_correctionRoundsBackToCurrent_returnsNull() {
        // Same idea for the ISO-priority shutter drive: outside the deadband, rounds back to current.
        assertNull(AutoExposure.driveShutterNs(histAt(110), currentNs = 10L, expMinNs = 5L, expMaxNs = expMax, evCompStops = 0f))
    }
}
