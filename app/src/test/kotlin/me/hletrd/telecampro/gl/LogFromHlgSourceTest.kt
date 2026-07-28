package me.hletrd.telecampro.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The correctness criterion for 10-bit log that scene A/Bs could not measure.
 *
 * A log encoding is defined by where known SCENE anchors land, not by how a room looked: 18% grey
 * must reach S-Log3 420/1023 and diffuse white must reach the same code, whether the camera handed
 * us an 8-bit display-referred buffer or a 10-bit HLG one. If the source decode is right, both
 * routes agree; if it is wrong, they cannot.
 *
 * This exists because three device comparisons here were invalid — twice by comparing SLOG3 against
 * SLOG3_CINE after a restart restored a different profile, and once because the app-side AE loop
 * meters GL preview luma and silently cancelled a pure-gain change. None of those can touch this.
 */
class LogFromHlgSourceTest {

    private val eps = 2e-3

    /** Forward BT.2100 HLG OETF, i.e. how a camera would ENCODE a given display-light value. */
    private fun hlgSignalForDisplayLight(displayLight: Double): Double {
        val scene = (displayLight * SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE)
            .pow(1.0 / SdrToHlgMapping.HLG_SYSTEM_GAMMA)
        return if (scene <= 1.0 / 12.0) {
            sqrt(3.0 * scene)
        } else {
            SdrToHlgMapping.HLG_A * ln(12.0 * scene - SdrToHlgMapping.HLG_B) + SdrToHlgMapping.HLG_C
        }
    }

    /** The same display light as an 8-bit display-referred signal (the SDR stream's encoding). */
    private fun sdrSignalForDisplayLight(displayLight: Double): Double =
        displayLight.pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA)

    @Test
    fun bothSourceEncodingsLinearizeToTheSameDisplayLight() {
        var light = 0.02
        while (light <= 1.0) {
            val fromSdr = LogProfiles.sourceLinear(sdrSignalForDisplayLight(light), sourceHlg = false)
            val fromHlg = LogProfiles.sourceLinear(hlgSignalForDisplayLight(light), sourceHlg = true)
            assertEquals("source decodes disagree at display light $light", fromSdr, fromHlg, 1e-4)
            light += 0.02
        }
    }

    /** 18% grey is the anchor the S-Log3 curve is specified around: 420/1023. */
    @Test
    fun eighteenPercentGreyLandsOnTheStandardCodeFromEitherSource() {
        val expected = LogProfiles.SLOG3_LOG_OFFSET_CODE / LogProfiles.SLOG3_CODE_SCALE
        val sdr = LogProfiles.encodeSlog3(
            sdrSignalForDisplayLight(0.18), sdrSignalForDisplayLight(0.18), sdrSignalForDisplayLight(0.18),
        )
        val hlg = LogProfiles.encodeSlog3(
            hlgSignalForDisplayLight(0.18), hlgSignalForDisplayLight(0.18), hlgSignalForDisplayLight(0.18),
            sourceHlg = true,
        )
        assertEquals("SDR source: 18% grey off the S-Log3 anchor", expected, sdr.green, eps)
        assertEquals("HLG source: 18% grey off the S-Log3 anchor", expected, hlg.green, eps)
    }

    @Test
    fun diffuseWhiteAgreesBetweenSources() {
        val sdr = LogProfiles.encodeSlog3(1.0, 1.0, 1.0)
        val hlgSignal = hlgSignalForDisplayLight(1.0)
        val hlg = LogProfiles.encodeSlog3(hlgSignal, hlgSignal, hlgSignal, sourceHlg = true)
        assertEquals(sdr.green, hlg.green, eps)
    }

    /**
     * The whole point of log: the output must be FLAT. Across a 0.02..1.0 display-light sweep the
     * S-Log3 codes must occupy a compressed band well inside [0,1] — the "normal contrast, deep
     * blacks" look the broken device clips showed is what failure looks like here.
     */
    @Test
    fun theHlgRouteProducesAFlatLogBandNotAContrastyOne() {
        val codes = generateSequence(0.02) { it + 0.02 }.takeWhile { it <= 1.0 }
            .map { light ->
                val s = hlgSignalForDisplayLight(light)
                LogProfiles.encodeSlog3(s, s, s, sourceHlg = true).green
            }.toList()
        val lo = codes.min()
        val hi = codes.max()
        assertTrue("log blacks must be lifted, got $lo", lo > 0.09)
        assertTrue("log highlights must be rolled off, got $hi", hi < 0.80)
        assertTrue("log must be monotonic", codes.zipWithNext().all { (a, b) -> b > a })
    }

    /** Using the WRONG decode on an HLG source must be detectable, or this suite proves nothing. */
    @Test
    fun theWrongDecodeMissesTheGreyAnchor() {
        val hlgSignal = hlgSignalForDisplayLight(0.18)
        val wrong = LogProfiles.encodeSlog3(hlgSignal, hlgSignal, hlgSignal, sourceHlg = false)
        val expected = LogProfiles.SLOG3_LOG_OFFSET_CODE / LogProfiles.SLOG3_CODE_SCALE
        assertTrue(
            "the SDR decode must NOT accidentally hit the anchor on an HLG source",
            kotlin.math.abs(wrong.green - expected) > 0.05,
        )
    }
}
