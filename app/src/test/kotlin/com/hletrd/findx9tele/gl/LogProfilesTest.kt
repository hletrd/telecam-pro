package com.hletrd.findx9tele.gl

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the log-profile references ([LogProfiles]) and their single-sourcing into the fragment
 * shader (same pattern as [SdrToHlgMappingTest] for the HLG chain). Anchors come from the source
 * documents, NOT from re-running our own formulas: Sony's S-Log3 technical summary (18% grey =
 * 420/1023 ≈ 0.410557, 0 → 95/1023 ≈ 0.092864, 1.0 → ≈ 0.596027) and ARRI's LogC3 EI800 VFX
 * table (18% grey ≈ 0.391007, 0 → f = 0.092809). The row-sum invariant is the wrong-primaries
 * tripwire: every matrix is D65→D65, so white MUST map to white.
 */
class LogProfilesTest {

    // ---- S-Log3 curve ----

    @Test
    fun `S-Log3 pins the documented anchors`() {
        assertEquals(0.410557, LogProfiles.slog3Oetf(0.18), 1e-6)
        assertEquals(0.092864, LogProfiles.slog3Oetf(0.0), 1e-6)
        assertEquals(0.596027, LogProfiles.slog3Oetf(1.0), 1e-6)
    }

    @Test
    fun `S-Log3 segments are continuous at the cut`() {
        // The log segment must land on the linear segment's value at the cut — SLOG3_CUT_CODE is
        // by construction the log-branch code value there, and the linear branch reaches exactly
        // CUT_CODE/CODE_SCALE at x = cut.
        val logAtCut = LogProfiles.slog3Oetf(LogProfiles.SLOG3_CUT) // >= cut -> log branch
        assertEquals(LogProfiles.SLOG3_CUT_CODE / LogProfiles.SLOG3_CODE_SCALE, logAtCut, 1e-6)
    }

    // ---- LogC3 curve ----

    @Test
    fun `LogC3 pins the documented anchors`() {
        assertEquals(0.391007, LogProfiles.logc3Oetf(0.18), 1e-6)
        // The zero anchor asserts the published LITERAL, not LOGC3_F itself — comparing the
        // constant to the constant (the linear branch is E*x + F, so oetf(0) == F by definition)
        // could never catch a transcribed F (cycle-6 test-review F-A3).
        assertEquals(0.092809, LogProfiles.logc3Oetf(0.0), 1e-9)
    }

    @Test
    fun `LogC3 segments are continuous at the cut`() {
        val linAtCut = LogProfiles.logc3Oetf(LogProfiles.LOGC3_CUT) // <= cut -> linear branch
        val logAtCut = LogProfiles.LOGC3_C *
            log10(LogProfiles.LOGC3_A * LogProfiles.LOGC3_CUT + LogProfiles.LOGC3_B) +
            LogProfiles.LOGC3_D
        assertEquals(logAtCut, linAtCut, 1e-6)
    }

    @Test
    fun `both OETFs are strictly monotonic from the gamut floor upward`() {
        // 4.0 linear covers anything the [0,1] SDR decode times a row-sum-1 matrix can produce,
        // with headroom; the floor end exercises both linear segments below their cuts.
        for (oetf in listOf(LogProfiles::slog3Oetf, LogProfiles::logc3Oetf)) {
            var x = LogProfiles.GAMUT_LINEAR_FLOOR
            var previous = oetf(x)
            while (x < 4.0) {
                x += 1e-3
                val value = oetf(x)
                assertTrue("monotonicity broken at x=$x", value > previous)
                previous = value
            }
        }
    }

    // ---- gamut matrices ----

    @Test
    fun `matrix values pin the computed BT709 conversions`() {
        // inv(RGBtoXYZ(target)) x RGBtoXYZ(BT.709), D65 white throughout (derivation in the
        // object docs); independently recomputed values, pinned to 1e-4.
        assertMatrix(
            doubleArrayOf(0.566049, 0.342763, 0.091188, 0.076961, 0.799055, 0.123984, 0.022351, 0.108612, 0.869037),
            sGamut3Matrix(),
        )
        assertMatrix(
            doubleArrayOf(0.645679, 0.259115, 0.095206, 0.087530, 0.759700, 0.152770, 0.036957, 0.129281, 0.833762),
            sGamut3CineMatrix(),
        )
        assertMatrix(
            doubleArrayOf(0.631321, 0.270801, 0.097878, 0.036820, 0.793037, 0.170143, 0.017370, 0.148789, 0.833841),
            awg3Matrix(),
        )
    }

    @Test
    fun `every matrix row sums to one`() {
        // D65 -> D65 means neutrals pass through untouched; a wrong-primaries transcription slip
        // breaks this invariant long before it breaks any single-entry pin.
        for (matrix in listOf(sGamut3Matrix(), sGamut3CineMatrix(), awg3Matrix())) {
            for (row in 0 until 3) {
                val sum = matrix[row * 3] + matrix[row * 3 + 1] + matrix[row * 3 + 2]
                assertEquals("row $row", 1.0, sum, 1e-4)
            }
        }
    }

    // ---- full display-referred chain (decode -> matrix -> floor -> OETF) ----

    @Test
    fun `neutral grey card lands on the documented grey anchors end to end`() {
        // 0.18 linear as a BT.1886-coded neutral: the row-sum-1 matrices keep it neutral, so the
        // full chain must land each channel exactly on the curve's documented 18%-grey anchor.
        val grey = 0.18.pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA)
        for (channel in LogProfiles.encodeSlog3(grey, grey, grey).toList()) {
            assertEquals(0.410557, channel, 1e-6)
        }
        for (channel in LogProfiles.encodeSlog3Cine(grey, grey, grey).toList()) {
            assertEquals(0.410557, channel, 1e-6)
        }
        for (channel in LogProfiles.encodeLogc3(grey, grey, grey).toList()) {
            assertEquals(0.391007, channel, 1e-6)
        }
    }

    @Test
    fun `RGB primaries pin the full chain per profile`() {
        assertRgb(Rgb(0.533691191881, 0.323792562590, 0.214018831934), LogProfiles.encodeSlog3(1.0, 0.0, 0.0))
        assertRgb(Rgb(0.479250022611, 0.571399924006, 0.358250849300), LogProfiles.encodeSlog3(0.0, 1.0, 0.0))
        assertRgb(Rgb(0.344935972469, 0.393385083592, 0.576062961703), LogProfiles.encodeSlog3Cine(0.0, 0.0, 1.0))
        assertRgb(Rgb(0.548065282816, 0.336525482977, 0.255382784500), LogProfiles.encodeSlog3Cine(1.0, 0.0, 0.0))
        assertRgb(Rgb(0.433049556492, 0.545998733742, 0.371676788262), LogProfiles.encodeLogc3(0.0, 1.0, 0.0))
        assertRgb(Rgb(0.329987345661, 0.385269520757, 0.551323353578), LogProfiles.encodeLogc3(0.0, 0.0, 1.0))
    }

    // ---- shader single-sourcing ----

    @Test
    fun `shader shares the matrix and curve constants`() {
        val shader = Shaders.FRAGMENT
        // Matrix rows, interpolated exactly the way Shaders.kt builds them.
        assertTrue(shader.contains("dot(vec3(${LogProfiles.SG3_R_FROM_R}, ${LogProfiles.SG3_R_FROM_G}, ${LogProfiles.SG3_R_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.SG3_G_FROM_R}, ${LogProfiles.SG3_G_FROM_G}, ${LogProfiles.SG3_G_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.SG3_B_FROM_R}, ${LogProfiles.SG3_B_FROM_G}, ${LogProfiles.SG3_B_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.SG3C_R_FROM_R}, ${LogProfiles.SG3C_R_FROM_G}, ${LogProfiles.SG3C_R_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.SG3C_G_FROM_R}, ${LogProfiles.SG3C_G_FROM_G}, ${LogProfiles.SG3C_G_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.SG3C_B_FROM_R}, ${LogProfiles.SG3C_B_FROM_G}, ${LogProfiles.SG3C_B_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.AWG3_R_FROM_R}, ${LogProfiles.AWG3_R_FROM_G}, ${LogProfiles.AWG3_R_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.AWG3_G_FROM_R}, ${LogProfiles.AWG3_G_FROM_G}, ${LogProfiles.AWG3_G_FROM_B}), c)"))
        assertTrue(shader.contains("dot(vec3(${LogProfiles.AWG3_B_FROM_R}, ${LogProfiles.AWG3_B_FROM_G}, ${LogProfiles.AWG3_B_FROM_B}), c)"))
        // S-Log3 curve (the max() guards protect the unused mix lane from NaN).
        assertTrue(shader.contains("log(max(x + ${LogProfiles.SLOG3_LIN_OFFSET}, 1e-6) / ${LogProfiles.SLOG3_GREY_PLUS_OFFSET})"))
        assertTrue(shader.contains("* ${LogProfiles.INV_LN10} * ${LogProfiles.SLOG3_LOG_SLOPE}) / ${LogProfiles.SLOG3_CODE_SCALE};"))
        assertTrue(shader.contains("vec3 linY = (x * (${LogProfiles.SLOG3_CUT_CODE} - ${LogProfiles.SLOG3_BLACK_CODE})"))
        assertTrue(shader.contains("step(x, vec3(${LogProfiles.SLOG3_CUT}))"))
        // LogC3 curve.
        assertTrue(shader.contains("log(max(${LogProfiles.LOGC3_A} * x + ${LogProfiles.LOGC3_B}, 1e-6))"))
        assertTrue(shader.contains("* ${LogProfiles.INV_LN10} + ${LogProfiles.LOGC3_D};"))
        assertTrue(shader.contains("vec3 linY = ${LogProfiles.LOGC3_E} * x + ${LogProfiles.LOGC3_F};"))
        assertTrue(shader.contains("step(x, vec3(${LogProfiles.LOGC3_CUT}))"))
        // Defensive floor between matrix and OETF.
        assertTrue(shader.contains("max(c, vec3(${LogProfiles.GAMUT_LINEAR_FLOOR}))"))
    }

    @Test
    fun `each log branch applies decode then matrix then floor then OETF`() {
        val shader = Shaders.FRAGMENT
        val slog3Branch = shader.substringAfter("uTransfer == 2) {").substringBefore("} else if")
        val cineBranch = shader.substringAfter("uTransfer == 4) {").substringBefore("} else if")
        val logc3Branch = shader.substringAfter("uTransfer == 5) {").substringBefore("} else if")
        for ((branch, encode) in listOf(
            slog3Branch to "color = slog3(gamutFloor(toSGamut3(lin)));",
            cineBranch to "color = slog3(gamutFloor(toSGamut3Cine(lin)));",
            logc3Branch to "color = logc3(gamutFloor(toAwg3(lin)));",
        )) {
            val decode = branch.indexOf("vec3 lin = pow(clamp(color, 0.0, 1.0), vec3(SDR_EOTF_GAMMA));")
            val apply = branch.indexOf(encode)
            assertTrue("BT.1886 decode must open the branch", decode >= 0)
            assertTrue("matrix→floor→OETF must follow the decode", apply > decode)
        }
    }

    @Test
    fun `zebra and false color meter the display-referred signal, not the encode curve`() {
        // The log OETFs compress display white to ~0.57-0.60 — below every zebra preset
        // (0.70-1.00) and the false-color near-clip bands. Metered on the post-transfer
        // output, both overlays go dead the moment a log profile is selected (cycle-6
        // debugger F4). The shader must meter the display-referred `meter` signal, which
        // only the dormant native-log assist branch may reassign (there the de-logged
        // monitor image IS the display-referred rendition).
        val shader = Shaders.FRAGMENT
        assertTrue("display-referred meter seam must exist", shader.contains("vec3 meter = base;"))
        assertTrue(
            "zebra must meter the display-referred signal",
            shader.contains("luma(clamp(meter, 0.0, 1.0)) > uZebraThreshold"),
        )
        assertTrue(
            "false color must meter the display-referred signal",
            shader.contains("float L = luma(clamp(meter, 0.0, 1.0));"),
        )
        // No overlay may read the post-transfer `color` as its exposure signal.
        assertFalse(shader.contains("luma(clamp(color, 0.0, 1.0)) > uZebraThreshold"))
        assertFalse(shader.contains("float L = luma(color);"))
        // The only reassignment lives inside the dormant assist branch (uTransfer == 3).
        val reassignIndex = shader.indexOf("meter = color;")
        val dormantBranch = shader.indexOf("uTransfer == 3")
        val falseColorBlock = shader.indexOf("if (uFalseColor == 1)")
        assertTrue("dormant assist must republish its de-logged image as the meter signal", reassignIndex >= 0)
        assertTrue(reassignIndex > dormantBranch && reassignIndex < falseColorBlock)
        assertTrue(shader.indexOf("meter = color;", reassignIndex + 1) == -1)
    }

    @Test
    fun `forward O-Log2 is gone while the dormant inverse survives`() {
        val shader = Shaders.FRAGMENT
        assertFalse("the removed forward O-Log2 OETF must not resurface", shader.contains("vec3 olog2("))
        assertTrue("the dormant de-log assist inverse must stay", shader.contains("vec3 olog2Inv("))
        assertTrue("the dormant de-log branch must stay", shader.contains("uTransfer == 3"))
    }

    // ---- helpers ----

    @Test
    fun `dormant O-Log2 inverse boundary is the forward toe evaluated at its switch point`() {
        // P(R = OLOG2_TOE_MAX_R) = TOE_SCALE·(R + TOE_OFFSET)². The boundary used to be a second
        // hand-computed literal (0.20856) whose arithmetic squared the wrong sum (P6.7/CR4-8);
        // pin the derived value against an independent evaluation of the forward toe.
        val toeAtSwitch = Shaders.OLOG2_TOE_SCALE *
            (Shaders.OLOG2_TOE_MAX_R + Shaders.OLOG2_TOE_OFFSET).pow(2)
        assertEquals(toeAtSwitch, Shaders.OLOG2_INV_BOUNDARY, 0.0)
        assertEquals(0.1841888797965, Shaders.OLOG2_INV_BOUNDARY, 1e-12)
    }

    private fun sGamut3Matrix() = doubleArrayOf(
        LogProfiles.SG3_R_FROM_R, LogProfiles.SG3_R_FROM_G, LogProfiles.SG3_R_FROM_B,
        LogProfiles.SG3_G_FROM_R, LogProfiles.SG3_G_FROM_G, LogProfiles.SG3_G_FROM_B,
        LogProfiles.SG3_B_FROM_R, LogProfiles.SG3_B_FROM_G, LogProfiles.SG3_B_FROM_B,
    )

    private fun sGamut3CineMatrix() = doubleArrayOf(
        LogProfiles.SG3C_R_FROM_R, LogProfiles.SG3C_R_FROM_G, LogProfiles.SG3C_R_FROM_B,
        LogProfiles.SG3C_G_FROM_R, LogProfiles.SG3C_G_FROM_G, LogProfiles.SG3C_G_FROM_B,
        LogProfiles.SG3C_B_FROM_R, LogProfiles.SG3C_B_FROM_G, LogProfiles.SG3C_B_FROM_B,
    )

    private fun awg3Matrix() = doubleArrayOf(
        LogProfiles.AWG3_R_FROM_R, LogProfiles.AWG3_R_FROM_G, LogProfiles.AWG3_R_FROM_B,
        LogProfiles.AWG3_G_FROM_R, LogProfiles.AWG3_G_FROM_G, LogProfiles.AWG3_G_FROM_B,
        LogProfiles.AWG3_B_FROM_R, LogProfiles.AWG3_B_FROM_G, LogProfiles.AWG3_B_FROM_B,
    )

    private fun assertMatrix(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("entry $i: expected ${expected[i]}, got ${actual[i]}", abs(expected[i] - actual[i]) <= 1e-4)
        }
    }

    private fun Rgb.toList(): List<Double> = listOf(red, green, blue)

    private fun assertRgb(expected: Rgb, actual: Rgb, tolerance: Double = 1e-9) {
        assertEquals("red", expected.red, actual.red, tolerance)
        assertEquals("green", expected.green, actual.green, tolerance)
        assertEquals("blue", expected.blue, actual.blue, tolerance)
    }
}
