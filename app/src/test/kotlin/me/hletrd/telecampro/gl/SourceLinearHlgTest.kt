package me.hletrd.telecampro.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * CPU mirror of the shader's `sourceLinear` HLG branch, pinned against the forward `hlg()` OETF it
 * must invert. The pair shares [SdrToHlgMapping]'s constants, so this proves the ALGEBRA rather
 * than re-asserting the numbers.
 *
 * Why it matters (device-measured 2026-07-29): GL_TEXTURE_EXTERNAL_OES returns buffer-encoded
 * values, so on a 10-bit HLG10/DV session the sampler yields HLG, not display-referred 709.
 * Decoding that with BT.1886 2.4 expanded the highlights and the log OETF then re-compressed them,
 * producing a file that looked normal instead of flat — plausible and wrong.
 */
class SourceLinearHlgTest {

    private val a = SdrToHlgMapping.HLG_A
    private val b = SdrToHlgMapping.HLG_B
    private val c = SdrToHlgMapping.HLG_C

    /** Forward BT.2100 HLG OETF: normalized scene light -> signal. Mirrors the shader's hlg(). */
    private fun oetf(x: Double): Double =
        if (x <= 1.0 / 12.0) sqrt(3.0 * x) else a * ln(12.0 * x - b) + c

    /** Pure inverse OETF: signal -> normalized HLG scene light. */
    private fun inverse(s: Double): Double =
        if (s <= 0.5) s * s / 3.0 else (exp((s - c) / a) + b) / 12.0

    /**
     * The shader's full sourceLinear() HLG branch: signal -> DISPLAY-light scale (white = 1), which
     * is what every downstream transfer branch expects. The trailing normalization is the exact
     * inverse of the forward HLG branch's reference-white scaling.
     */
    private fun sourceLinear(s: Double): Double =
        Math.pow(inverse(s), SdrToHlgMapping.HLG_SYSTEM_GAMMA) /
            SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE

    @Test
    fun theInverseUndoesTheForwardCurveAcrossTheRange() {
        var x = 0.0
        while (x <= 1.0) {
            assertEquals("round trip failed at scene light $x", x, inverse(oetf(x)), 1e-6)
            x += 0.005
        }
    }

    @Test
    fun theTwoSegmentsMeetAtTheBreakpoint() {
        // The forward curve switches at scene light 1/12, which is signal 0.5 — the inverse must
        // switch at exactly the same place or a band near mid-grey decodes on the wrong branch
        // (the class of error that made the O-Log2 inverse's boundary wrong, see Shaders.kt).
        assertEquals(0.5, oetf(1.0 / 12.0), 1e-9)
        assertEquals(inverse(0.5 - 1e-9), inverse(0.5 + 1e-9), 1e-6)
    }

    @Test
    fun anchorsHoldAtBlackAndWhite() {
        assertEquals(0.0, inverse(0.0), 1e-12)
        assertEquals(1.0, inverse(1.0), 1e-6)
    }

    @Test
    fun theCurveIsMonotonicSoNoTwoSignalsCollapse() {
        var prev = -1.0
        var s = 0.0
        while (s <= 1.0) {
            val v = inverse(s)
            assertTrue("not monotonic at $s", v > prev)
            prev = v
            s += 0.002
        }
    }

    /**
     * The whole point: HLG and BT.1886 disagree materially, so picking the wrong one is not a
     * rounding difference. At mid signal the SDR decode reads ~2.3x brighter than the HLG one.
     */
    /**
     * The round trip that matters end to end: display light -> forward HLG branch -> sourceLinear
     * must return the display light it started from. This is what makes a 10-bit HLG source usable
     * by transfer branches written for a BT.1886 decode.
     */
    @Test
    fun sourceLinearInvertsTheForwardHlgMappingOnTheDisplayLightScale() {
        var white = 0.02
        while (white <= 1.0) {
            val signal = oetf(
                Math.pow(white * SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE,
                    1.0 / SdrToHlgMapping.HLG_SYSTEM_GAMMA),
            )
            assertEquals("display-light round trip failed at $white", white, sourceLinear(signal), 1e-4)
            white += 0.01
        }
    }

    /** Diffuse white must land at 1.0, or the log OETFs downstream are under-driven. */
    @Test
    fun diffuseWhiteMapsToOne() {
        val signalForWhite = oetf(
            Math.pow(SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE,
                1.0 / SdrToHlgMapping.HLG_SYSTEM_GAMMA),
        )
        assertEquals(1.0, sourceLinear(signalForWhite), 1e-4)
    }

    @Test
    fun theSdrDecodeWouldBeMateriallyWrongForAnHlgSource() {
        val sdr = Math.pow(0.5, SdrToHlgMapping.SDR_EOTF_GAMMA)
        val hlg = inverse(0.5)
        assertEquals(0.1895, sdr, 1e-3)
        assertEquals(0.0833, hlg, 1e-3)
        assertTrue("the two decodes must not be interchangeable", sdr / hlg > 2.0)
    }
}
