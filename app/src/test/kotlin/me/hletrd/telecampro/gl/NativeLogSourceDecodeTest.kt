package me.hletrd.telecampro.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * The GL decode of a NATIVE-LOG camera stream (ISP-encoded S-Log3, see NativeLogCurve.kt): the
 * shader's `slog3Inverse` must be the exact inverse of the forward `slog3`, and agree with
 * [LogProfiles.slog3Inverse]. GLSL cannot run on the host, so this pins the interpolated shader
 * text and evaluates a Kotlin transliteration of THAT text (same operation order, `mix`/`step`
 * semantics) against the CPU reference — the same discipline SourceLinearHlgTest uses.
 */
class NativeLogSourceDecodeTest {

    private val shader = Shaders.FRAGMENT

    /** Literal transliteration of the shader's slog3Inverse for one channel. */
    private fun glslSlog3Inverse(y: Double): Double {
        val logX = exp(
            (y * LogProfiles.SLOG3_CODE_SCALE - LogProfiles.SLOG3_LOG_OFFSET_CODE) /
                LogProfiles.SLOG3_LOG_SLOPE / LogProfiles.INV_LN10,
        ) * LogProfiles.SLOG3_GREY_PLUS_OFFSET - LogProfiles.SLOG3_LIN_OFFSET
        val linX = (y * LogProfiles.SLOG3_CODE_SCALE - LogProfiles.SLOG3_BLACK_CODE) *
            LogProfiles.SLOG3_CUT / (LogProfiles.SLOG3_CUT_CODE - LogProfiles.SLOG3_BLACK_CODE)
        // mix(logX, linX, step(y, cut)): step(edge, x) = x < edge ? 0 : 1, so y <= cut takes linX.
        val cut = LogProfiles.SLOG3_CUT_CODE / LogProfiles.SLOG3_CODE_SCALE
        return if (cut < y) logX else linX
    }

    /** Transliteration of sampleSource's native-log branch (decode -> clamp -> BT.1886 encode). */
    private fun glslSampleSource(raw: Double): Double =
        glslSlog3Inverse(raw).coerceIn(0.0, 1.0).pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA)

    @Test
    fun `shader declares and wires the native-log source uniform`() {
        assertTrue(ShaderBindings.U_SOURCE_LOG in ShaderBindings.uniforms)
        assertTrue(shader.contains("uniform int uSourceLog;"))
        val sample = shader.substringAfter("vec3 sampleSource(vec2 uv) {").substringBefore("}")
        assertTrue(sample.contains("if (uSourceLog == 0) return c;"))
        assertTrue(sample.contains("vec3 lin = clamp(slog3Inverse(c), 0.0, 1.0);"))
        assertTrue(sample.contains("return pow(lin, vec3(1.0 / SDR_EOTF_GAMMA));"))
        // The decode happens BEFORE base forms, so display/meter/peaking/transfers all see it.
        assertTrue(shader.contains("vec3 base = dgain(sampleSource(vTexCoord));"))
    }

    @Test
    fun `shader inverse is built from the LogProfiles constants`() {
        val body = shader.substringAfter("vec3 slog3Inverse(vec3 y) {").substringBefore("\n        }")
        assertTrue(body.contains("exp((y * ${LogProfiles.SLOG3_CODE_SCALE} - ${LogProfiles.SLOG3_LOG_OFFSET_CODE})"))
        assertTrue(body.contains("/ ${LogProfiles.SLOG3_LOG_SLOPE} / ${LogProfiles.INV_LN10})"))
        assertTrue(body.contains("* ${LogProfiles.SLOG3_GREY_PLUS_OFFSET} - ${LogProfiles.SLOG3_LIN_OFFSET};"))
        assertTrue(body.contains("(y * ${LogProfiles.SLOG3_CODE_SCALE} - ${LogProfiles.SLOG3_BLACK_CODE})"))
        assertTrue(body.contains("* ${LogProfiles.SLOG3_CUT} / (${LogProfiles.SLOG3_CUT_CODE} - ${LogProfiles.SLOG3_BLACK_CODE});"))
        assertTrue(body.contains("step(y, vec3(${LogProfiles.SLOG3_CUT_CODE} / ${LogProfiles.SLOG3_CODE_SCALE}))"))
    }

    @Test
    fun `GLSL and Kotlin inverses agree across the whole code range`() {
        var y = 0.0
        while (y <= 1.0) {
            assertEquals("disagree at code $y", LogProfiles.slog3Inverse(y), glslSlog3Inverse(y), 1e-12)
            y += 0.0005
        }
    }

    @Test
    fun `inverse round-trips the forward curve`() {
        var x = 0.0
        while (x <= 1.0) {
            assertEquals("round trip at linear $x", x, LogProfiles.slog3Inverse(LogProfiles.slog3Oetf(x)), 1e-9)
            assertEquals("GLSL round trip at linear $x", x, glslSlog3Inverse(LogProfiles.slog3Oetf(x)), 1e-9)
            x += 0.001
        }
        // ...and the other direction over the curve's own code range.
        var y = LogProfiles.slog3Oetf(0.0)
        while (y <= LogProfiles.slog3Oetf(1.0)) {
            assertEquals("code round trip at $y", y, LogProfiles.slog3Oetf(LogProfiles.slog3Inverse(y)), 1e-9)
            y += 0.001
        }
    }

    @Test
    fun `segments meet at the cut on both sides`() {
        val cutCode = LogProfiles.SLOG3_CUT_CODE / LogProfiles.SLOG3_CODE_SCALE
        assertEquals(LogProfiles.SLOG3_CUT, LogProfiles.slog3Inverse(cutCode), 1e-9)
        assertEquals(glslSlog3Inverse(cutCode - 1e-12), glslSlog3Inverse(cutCode + 1e-12), 1e-9)
    }

    /**
     * End to end on the CPU: a grey card the ISP encoded at the S-Log3 anchor decodes to linear 0.18,
     * becomes the display-referred `base`, and the unchanged transfer branch (BT.1886 decode ->
     * neutral-preserving gamut -> OETF) re-encodes it to the same anchor. Proves the decode puts the
     * signal back on the scale every existing branch was written for.
     */
    @Test
    fun `native grey survives the decode and the existing S-Log3 encode`() {
        val raw = LogProfiles.slog3Oetf(0.18)
        val base = glslSampleSource(raw)
        assertEquals(0.18.pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA), base, 1e-9)
        val anchor = 420.0 / 1023.0
        val slog3 = LogProfiles.encodeSlog3(base, base, base)
        val cine = LogProfiles.encodeSlog3Cine(base, base, base)
        listOf(slog3.red, slog3.green, slog3.blue, cine.red, cine.green, cine.blue).forEach {
            assertEquals(anchor, it, 1e-6)
        }
        assertEquals(0.391007, LogProfiles.encodeLogc3(base, base, base).green, 1e-5)
    }

    @Test
    fun `decode clamps to the programmed clip range`() {
        assertEquals(0.0, glslSampleSource(0.0), 0.0)
        assertEquals(1.0, glslSampleSource(1.0), 0.0)
    }
}
