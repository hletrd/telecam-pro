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

    /** Transliteration of sampleSource's native-log branch (decode -> ×k -> clamp -> BT.1886 encode). */
    private fun glslSampleSource(raw: Double, headroom: Double = 1.0): Double =
        (glslSlog3Inverse(raw) * headroom).coerceIn(0.0, 1.0).pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA)

    @Test
    fun `shader declares and wires the native-log source uniform`() {
        assertTrue(ShaderBindings.U_SOURCE_LOG in ShaderBindings.uniforms)
        assertTrue(shader.contains("uniform int uSourceLog;"))
        val sample = shader.substringAfter("vec3 sampleSource(vec2 uv) {").substringBefore("}")
        assertTrue(sample.contains("if (uSourceLog == 0) return c;"))
        assertTrue(sample.contains("vec3 lin = clamp(slog3Inverse(c) * uSourceLogHeadroom, 0.0, 1.0);"))
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

    // ---- Log EI offset: the unclamped scene-linear path the LOG encode branches read ----

    /** Transliteration of `nativeSceneLinear` for one channel at the encoder's unit gain. */
    private fun glslNativeSceneLinear(raw: Double, headroom: Double, gain: Double = 1.0): Double {
        var lin = (glslSlog3Inverse(raw) * headroom).coerceIn(0.0, headroom)
        if (gain > 1.001) lin = minOf(lin * gain, headroom)
        return lin
    }

    /** BT.1886 decode of a sampled value: the shader's sourceLinear for an 8-bit (non-HLG) stream. */
    private fun glslSourceLinear(c: Double): Double = c.coerceIn(0.0, 1.0).pow(SdrToHlgMapping.SDR_EOTF_GAMMA)

    /**
     * Transliteration of `logInputLinear` on a native-log stream: headroom 1 takes the historical
     * sourceLinear(base) path, anything above it the unclamped scene-linear decode.
     */
    private fun glslLogInputLinear(raw: Double, headroom: Double): Double =
        if (headroom <= 1.0) glslSourceLinear(glslSampleSource(raw)) else glslNativeSceneLinear(raw, headroom)

    /** Every log encode branch (codes 2/4/5) on a neutral scene-linear input. */
    private fun encodeAll(lin: Double): Map<String, Double> = mapOf(
        "S-Log3" to LogProfiles.encodeSlog3(lin, lin, lin, sceneLinear = true).green,
        "S-Log3.Cine" to LogProfiles.encodeSlog3Cine(lin, lin, lin, sceneLinear = true).green,
        "LogC3" to LogProfiles.encodeLogc3(lin, lin, lin, sceneLinear = true).green,
    )

    private val greyCodes = mapOf(
        "S-Log3" to LogProfiles.slog3Oetf(0.18),
        "S-Log3.Cine" to LogProfiles.slog3Oetf(0.18),
        "LogC3" to LogProfiles.logc3Oetf(0.18),
    )

    @Test
    fun `shader declares the headroom uniform and routes every log branch through logInputLinear`() {
        assertTrue(ShaderBindings.U_SOURCE_LOG_HEADROOM in ShaderBindings.uniforms)
        assertTrue(shader.contains("uniform float uSourceLogHeadroom;"))
        val scene = shader.substringAfter("vec3 nativeSceneLinear(vec2 uv) {").substringBefore("\n        }")
        assertTrue(scene.contains("slog3Inverse(texture2D(uTexture, uv).rgb) * uSourceLogHeadroom, 0.0, uSourceLogHeadroom);"))
        assertTrue(scene.contains("if (uDigitalGain > 1.001) lin = min(lin * uDigitalGain, vec3(uSourceLogHeadroom));"))
        val input = shader.substringAfter("vec3 logInputLinear(vec3 color) {").substringBefore("\n        }")
        assertTrue(input.contains("if (uSourceLog == 0 || uSourceLogHeadroom <= 1.0) return sourceLinear(color);"))
        assertTrue(input.contains("return nativeSceneLinear(vTexCoord);"))
        // The three LOG branches read it; HLG (code 1) keeps the display-referred sourceLinear.
        val main = shader.substringAfter("void main() {\n            vec3 base")
        assertEquals(3, Regex("""vec3 lin = logInputLinear\(color\);""").findAll(main).count())
        assertTrue(main.contains("vec3 sdrDisplayLight = sourceLinear(color);"))
        // `base` (display, meter, zebra, false color, peaking, AE readback) keeps the CLAMPED decode.
        assertTrue(shader.contains("vec3 base = dgain(sampleSource(vTexCoord));"))
        assertTrue(shader.contains("vec3 meter = base;"))
    }

    @Test
    fun `GLSL scene-linear decode agrees with the CPU inverse up to the headroom`() {
        for (k in listOf(1.0, 2.0, 4.0, 8.0)) {
            var y = 0.0
            while (y <= 1.0) {
                assertEquals((LogProfiles.slog3Inverse(y) * k).coerceIn(0.0, k), glslNativeSceneLinear(y, k), 1e-12)
                y += 0.0005
            }
        }
    }

    @Test
    fun `N 0 log input is exactly today's sourceLinear of base`() {
        var y = 0.0
        while (y <= 1.0) {
            // Same function, same operation order: not merely close — the identical expression.
            assertEquals(glslSourceLinear(glslSampleSource(y)), glslLogInputLinear(y, 1.0), 0.0)
            y += 0.0005
        }
    }

    @Test
    fun `grey lands on every profile's grey code for every N`() {
        for (stops in 0..3) {
            val k = (1 shl stops).toDouble()
            // The sensor is underexposed by k, so the ISP's PLAIN S-Log3 curve writes slog3(0.18 / k);
            // GL's ×k after the decode puts grey back on 0.18.
            val raw = LogProfiles.slog3Oetf(0.18 / k)
            val encoded = encodeAll(glslLogInputLinear(raw, k))
            for ((name, code) in encoded) {
                assertEquals("$name at N=$stops", greyCodes.getValue(name), code, 1e-6)
            }
        }
    }

    @Test
    fun `two stops over the old clip keeps detail at N 2 and clips at N 0`() {
        // Scene 4.0 = 2 stops above the N = 0 sensor clip. At N = 2 the ISP sees 4.0 / 4 = 1.0 and
        // its plain S-Log3 curve writes slog3(1.0); GL lifts the decode back by k = 4.
        val rawN2 = LogProfiles.slog3Oetf(1.0)
        val linN2 = glslLogInputLinear(rawN2, 4.0)
        assertEquals(4.0, linN2, 1e-9)
        val atN2 = encodeAll(linN2)
        val whiteCodes = encodeAll(1.0)
        for ((name, code) in atN2) {
            assertTrue("$name: 4.0 must encode below code 1.0, got $code", code < 1.0)
            assertTrue("$name: 4.0 must encode above its 1.0 code", code > whiteCodes.getValue(name) + 1e-3)
        }
        // At +3 EI (k = 8) every profile stays finite and below code 1.0.
        val atN3 = encodeAll(8.0)
        for ((name, code) in atN3) assertTrue("$name at k=8: $code", code.isFinite() && code < 1.0)
        // At N = 0 the same scene clipped at the sensor: the ISP saw 1.0, i.e. code slog3(1.0).
        val linN0 = glslLogInputLinear(LogProfiles.slog3Oetf(1.0), 1.0)
        assertEquals(1.0, linN0, 1e-9)
    }

    @Test
    fun `the display rendition stays clamped at white under any offset`() {
        // base = sampleSource: no matter how much headroom the wire bought, display/meter top out at 1.
        assertEquals(1.0, glslSampleSource(LogProfiles.slog3Oetf(1.0), headroom = 8.0), 1e-12)
        // Grey under any offset displays at grey: the ×k lift precedes the display clamp.
        assertEquals(
            0.18.pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA),
            glslSampleSource(LogProfiles.slog3Oetf(0.18 / 4.0), headroom = 4.0),
            1e-9,
        )
        assertEquals(0.18.pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA), glslSampleSource(LogProfiles.slog3Oetf(0.18)), 1e-9)
    }

    @Test
    fun `preview brightness simulation on the scene path clips at the headroom, files carry none`() {
        val raw = LogProfiles.slog3Oetf(1.5 / 4.0)
        assertEquals(1.5, glslNativeSceneLinear(raw, 4.0), 1e-9)
        assertEquals(3.0, glslNativeSceneLinear(raw, 4.0, gain = 2.0), 1e-9)
        assertEquals(4.0, glslNativeSceneLinear(raw, 4.0, gain = 16.0), 1e-9)
    }

    @Test
    fun `decode clamps to the programmed clip range`() {
        assertEquals(0.0, glslSampleSource(0.0), 0.0)
        assertEquals(1.0, glslSampleSource(1.0), 0.0)
    }
}
