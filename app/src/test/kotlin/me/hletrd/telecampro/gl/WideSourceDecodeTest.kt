package me.hletrd.telecampro.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RAW log developer's wide-gamut source decode (Shaders.kt `uSourceWide`): the S-Gamut3.Cine →
 * BT.709 constant is the exact inverse of the shader's own BT.709 → S-Gamut3.Cine branch matrix, so an
 * unclamped trip through BT.709 returns the developed S-Gamut3.Cine value — the log never passes
 * through a 709 clip.
 */
class WideSourceDecodeTest {

    private val toSg3c = doubleArrayOf(
        LogProfiles.SG3C_R_FROM_R, LogProfiles.SG3C_R_FROM_G, LogProfiles.SG3C_R_FROM_B,
        LogProfiles.SG3C_G_FROM_R, LogProfiles.SG3C_G_FROM_G, LogProfiles.SG3C_G_FROM_B,
        LogProfiles.SG3C_B_FROM_R, LogProfiles.SG3C_B_FROM_G, LogProfiles.SG3C_B_FROM_B,
    )

    private fun mul(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2] }

    @Test
    fun `S-Gamut3_Cine survives an unclamped trip through BT_709`() {
        // A saturated S-Gamut3.Cine green outside BT.709: its 709 coordinates go negative.
        val sg3c = doubleArrayOf(0.05, 0.6, 0.08)
        val rec709 = mul(LogProfiles.REC709_FROM_SG3C, sg3c)
        assertTrue("outside 709", rec709.any { it < 0.0 })
        val back = mul(toSg3c, rec709)
        for (i in 0 until 3) assertEquals(sg3c[i], back[i], 1e-9)
    }

    @Test
    fun `neutrals stay neutral through the inverse`() {
        mul(LogProfiles.REC709_FROM_SG3C, doubleArrayOf(0.18, 0.18, 0.18)).forEach { assertEquals(0.18, it, 1e-9) }
    }

    @Test
    fun `the GLSL literal is column-major`() {
        val literal = Shaders.mat3Literal(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))
        assertEquals(
            "mat3(1.0000000000, 4.0000000000, 7.0000000000, 2.0000000000, 5.0000000000, 8.0000000000, " +
                "3.0000000000, 6.0000000000, 9.0000000000)",
            literal,
        )
        assertTrue(Shaders.FRAGMENT.contains("const mat3 REC709_FROM_SG3C = mat3("))
    }

    @Test
    fun `the wide source takes its own branches and leaves the native ones verbatim`() {
        val shader = Shaders.FRAGMENT
        val sample = shader.substringAfter("vec3 sampleSource(vec2 uv) {").substringBefore("}")
        assertTrue(sample.contains("if (uSourceWide == 1) return wideDisplaySample(c);"))
        // The wide decode reaches the log branches UNclamped; only the display rendition clips.
        val wide = shader.substringAfter("vec3 wideSceneLinear(vec2 uv) {").substringBefore("}")
        assertTrue(wide.contains("REC709_FROM_SG3C * (slog3Inverse(texture2D(uTexture, uv).rgb) * uSourceLogHeadroom)"))
        assertTrue(!wide.contains("clamp("))
        val input = shader.substringAfter("vec3 logInputLinear(vec3 color) {").substringBefore("}")
        assertTrue(input.contains("if (uSourceLog == 1 && uSourceWide == 1) return wideSceneLinear(vTexCoord);"))
        assertTrue(input.contains("if (uSourceLog == 0 || uSourceLogHeadroom <= 1.0) return sourceLinear(color);"))
    }
}
