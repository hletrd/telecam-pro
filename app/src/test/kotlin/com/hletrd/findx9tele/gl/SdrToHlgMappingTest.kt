package com.hletrd.findx9tele.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrToHlgMappingTest {

    @Test
    fun `black stays black`() {
        assertRgb(Rgb(0.0, 0.0, 0.0), SdrToHlgMapping.encode(0.0, 0.0, 0.0))
    }

    @Test
    fun `SDR reference white maps to 75 percent HLG`() {
        val mapped = SdrToHlgMapping.encode(1.0, 1.0, 1.0)
        assertRgb(
            Rgb(0.7499904949218366, 0.7499904949218366, 0.7499904949218366),
            mapped,
        )
        assertEquals(0.75, mapped.red, 2e-5)
    }

    @Test
    fun `neutral code grey remains neutral at its pinned HLG level`() {
        assertRgb(
            Rgb(0.39758300928929846, 0.39758300928929846, 0.39758300928929846),
            SdrToHlgMapping.encode(0.5, 0.5, 0.5),
        )
    }

    @Test
    fun `RGB primaries pin the linear 709 to 2020 conversion`() {
        assertRgb(
            Rgb(0.6586913553844085, 0.243658857456799, 0.12121649393184603),
            SdrToHlgMapping.encode(1.0, 0.0, 0.0),
        )
        assertRgb(
            Rgb(0.5192735812286169, 0.7339198289363106, 0.2740030027783528),
            SdrToHlgMapping.encode(0.0, 1.0, 0.0),
        )
        assertRgb(
            Rgb(0.19419734816364886, 0.10159967119104205, 0.728848248155367),
            SdrToHlgMapping.encode(0.0, 0.0, 1.0),
        )
    }

    @Test
    fun `shader shares constants and applies the mapping in standards order`() {
        val shader = Shaders.FRAGMENT
        assertTrue(shader.contains("const float SDR_EOTF_GAMMA = ${SdrToHlgMapping.SDR_EOTF_GAMMA};"))
        assertTrue(
            shader.contains(
                "const float BT2408_HLG_SCALE = ${SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE};",
            ),
        )
        assertTrue(shader.contains("const float HLG_SYSTEM_GAMMA = ${SdrToHlgMapping.HLG_SYSTEM_GAMMA};"))
        assertTrue(
            shader.contains(
                "float a = ${SdrToHlgMapping.HLG_A}, b = ${SdrToHlgMapping.HLG_B}, " +
                    "c = ${SdrToHlgMapping.HLG_C};",
            ),
        )
        assertTrue(
            shader.contains(
                "vec3(${SdrToHlgMapping.R_FROM_R}, ${SdrToHlgMapping.R_FROM_G}, " +
                    "${SdrToHlgMapping.R_FROM_B})",
            ),
        )
        assertTrue(
            shader.contains(
                "vec3(${SdrToHlgMapping.G_FROM_R}, ${SdrToHlgMapping.G_FROM_G}, " +
                    "${SdrToHlgMapping.G_FROM_B})",
            ),
        )
        assertTrue(
            shader.contains(
                "vec3(${SdrToHlgMapping.B_FROM_R}, ${SdrToHlgMapping.B_FROM_G}, " +
                    "${SdrToHlgMapping.B_FROM_B})",
            ),
        )

        val decode = shader.indexOf("vec3 sdrDisplayLight = pow(clamp(color")
        val to2020 = shader.indexOf("vec3 bt2020DisplayLight = toRec2020(sdrDisplayLight)")
        val scaleAndInverseOotf = shader.indexOf("max(bt2020DisplayLight * BT2408_HLG_SCALE")
        val inverseOotf = shader.indexOf("vec3(1.0 / HLG_SYSTEM_GAMMA)", scaleAndInverseOotf)
        val hlgOetf = shader.indexOf("color = hlg(hlgSceneLight)")
        assertTrue("BT.1886 decode must be present", decode >= 0)
        assertTrue("709-to-2020 must follow decode", to2020 > decode)
        assertTrue("scale/inverse OOTF must follow gamut conversion", scaleAndInverseOotf > to2020)
        assertTrue("inverse OOTF exponent must follow scaling", inverseOotf > scaleAndInverseOotf)
        assertTrue("HLG OETF must be last", hlgOetf > inverseOotf)
    }

    private fun assertRgb(expected: Rgb, actual: Rgb, tolerance: Double = 1e-12) {
        assertEquals("red", expected.red, actual.red, tolerance)
        assertEquals("green", expected.green, actual.green, tolerance)
        assertEquals("blue", expected.blue, actual.blue, tolerance)
    }
}
