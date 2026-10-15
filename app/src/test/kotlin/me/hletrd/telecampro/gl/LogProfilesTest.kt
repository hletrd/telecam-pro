package me.hletrd.telecampro.gl

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
 * table (18% grey ≈ 0.391007, 0 → f = 0.092809), OPPO's O-Log2 white paper (2026-04-17; 18% grey →
 * ≈ 0.4883, two segments meeting at the corrected Rt = 0.01), OPPO's O-Log V1 white paper + its
 * official ACES DCTL (grey at the paper's ≈ 0.4902 under OPPO's exposure convention), and Xiaomi's
 * Mi-Log white paper as transcribed by colour-science (0.18 → 0.45345968). The row-sum invariant
 * is the wrong-primaries tripwire: every matrix is D65→D65, so white MUST map to white.
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

    // ---- phone-maker log curves (O-Log2, O-Log V1, Mi-Log) ----

    @Test
    fun `O-Log2 pins the white paper constants and anchors`() {
        // The paper's literal constants, asserted as literals so a transcription slip cannot hide
        // behind comparing a constant to itself. Rt is the CORRECTED 0.01, not the printed 0.006.
        assertEquals(-0.05641088, LogProfiles.OLOG2_R0, 0.0)
        assertEquals(0.01, LogProfiles.OLOG2_RT, 0.0)
        assertEquals(47.28711236, LogProfiles.OLOG2_C2, 0.0)
        assertEquals(0.00964052, LogProfiles.OLOG2_B, 0.0)
        assertEquals(0.08550479, LogProfiles.OLOG2_A, 0.0)
        assertEquals(0.69336945, LogProfiles.OLOG2_C, 0.0)
        // Paper Table 1: linear 0.177773433 → 0.486829841 (OPPO's AE grey), 0.38964844 → 0.58015879.
        // The table is rounded against its own formula (the formula gives 0.4868156 / 0.5801188),
        // so it pins within 5e-5, not to the 9th digit.
        assertEquals(0.486829841, LogProfiles.olog2Oetf(0.177773433), 5e-5)
        assertEquals(0.58015879, LogProfiles.olog2Oetf(0.38964844), 5e-5)
        // Scene-linear 18 % grey, 100 % white, and black (the toe's c·R0²).
        assertEquals(0.488272458527, LogProfiles.olog2Oetf(0.18), 1e-9)
        assertEquals(0.694552983055, LogProfiles.olog2Oetf(1.0), 1e-9)
        assertEquals(47.28711236 * 0.05641088 * 0.05641088, LogProfiles.olog2Oetf(0.0), 1e-12)
        assertEquals(0.150476, LogProfiles.olog2Oetf(0.0), 1e-6)
        // Below R0 the paper defines 0.
        assertEquals(0.0, LogProfiles.olog2Oetf(-0.06), 0.0)
        assertEquals(0.0, LogProfiles.olog2Oetf(Double.NEGATIVE_INFINITY), 0.0)
    }

    @Test
    fun `O-Log2 segments join in value and slope at the corrected Rt, and the printed one would step`() {
        val rt = LogProfiles.OLOG2_RT
        val toe = { x: Double -> LogProfiles.OLOG2_C2 * (x - LogProfiles.OLOG2_R0).pow(2) }
        val log = { x: Double -> LogProfiles.OLOG2_A * kotlin.math.log2(x + LogProfiles.OLOG2_B) + LogProfiles.OLOG2_C }
        assertEquals(0.2085553, toe(rt), 1e-7)
        assertEquals(toe(rt), log(rt), 1e-8)
        val toeSlope = 2.0 * LogProfiles.OLOG2_C2 * (rt - LogProfiles.OLOG2_R0)
        val logSlope = LogProfiles.OLOG2_A / ((rt + LogProfiles.OLOG2_B) * kotlin.math.ln(2.0))
        assertEquals(6.28076, toeSlope, 1e-5)
        assertEquals(toeSlope, logSlope, 1e-6)
        // The PRINTED Rt = 0.006 is the typo: the curve would step DOWN there (non-monotonic).
        assertEquals(0.18419, toe(0.006), 1e-5)
        assertEquals(0.18047, log(0.006), 1e-5)
        assertTrue(toe(0.006) - log(0.006) > 3e-3)
        // The implemented curve straddles the join without a jump.
        assertEquals(LogProfiles.olog2Oetf(rt - 1e-9), LogProfiles.olog2Oetf(rt), 1e-8)
    }

    @Test
    fun `O-Log2 inverse is the paper's decoding function`() {
        for (x in listOf(-0.05, -0.01, 0.0, 0.005, 0.01, 0.18, 1.0, 8.0)) {
            assertEquals("x=$x", x, LogProfiles.olog2Inverse(LogProfiles.olog2Oetf(x)), 1e-9)
        }
        assertEquals(LogProfiles.OLOG2_R0, LogProfiles.olog2Inverse(-0.1), 0.0)
    }

    @Test
    fun `O-Log V1 pins the white paper formula and OPPO's DCTL exposure convention`() {
        assertEquals(0.139, LogProfiles.OLOG_GAMMA, 0.0)
        assertEquals(0.019, LogProfiles.OLOG_BETA, 0.0)
        assertEquals(0.614, LogProfiles.OLOG_DELTA, 0.0)
        assertEquals(16.0, LogProfiles.OLOG_R_MAX, 0.0)
        // The official O-Log_to_ACES_AP0_V1.dctl: lin = (exp((P − 0.614)/0.139) − 0.019)/16·7.37235.
        assertEquals(7.37235 / 16.0, LogProfiles.OLOG_DCTL_SCALE, 0.0)
        val dctl = { p: Double -> (kotlin.math.exp((p - 0.614) / 0.139) - 0.019) / 16.0 * 7.37235 }
        // Grey lands at the paper's AE grey (0.4901589 ≈ 502/1023) within OPPO's own rounding,
        // and the DCTL decodes our encode back to exactly the scene-linear we wrote.
        assertEquals(0.4901589, LogProfiles.ologOetf(0.18), 3e-4)
        assertEquals(0.489949, LogProfiles.ologOetf(0.18), 1e-6)
        for (s in listOf(0.0, 0.01, 0.18, 0.5, 1.0, 4.0, 7.0)) {
            assertEquals("s=$s", s, dctl(LogProfiles.ologOetf(s)), 1e-12)
            assertEquals("s=$s", s, LogProfiles.ologInverse(LogProfiles.ologOetf(s)), 1e-12)
        }
        // The paper's formula in its own R domain: R = 0 → 0.0631 (paper R₀ = 0.0631271), R = 16 → ≈ 1.
        assertEquals(0.0631271, LogProfiles.ologOetf(0.0), 2e-4)
        assertEquals(0.139 * kotlin.math.ln(16.019) + 0.614, LogProfiles.ologOetf(16.0 * LogProfiles.OLOG_DCTL_SCALE), 1e-12)
        // Without the convention (R = s) grey would sit ~1.2 stops dark through OPPO's LUT.
        assertTrue(0.139 * kotlin.math.ln(0.18 + 0.019) + 0.614 < 0.39)
    }

    @Test
    fun `O-Log V1 clamps to the paper domain, which is where a 3-stop EI offset clips`() {
        val top = LogProfiles.ologOetf(16.0 * LogProfiles.OLOG_DCTL_SCALE)
        assertTrue(top < 1.0)
        // k = 8 (+3 EI) is beyond R = 16 (scene-linear ≈ 7.37): flat at the domain top, never > 1.
        assertEquals(7.3724, 16.0 * LogProfiles.OLOG_DCTL_SCALE, 1e-4)
        assertEquals(top, LogProfiles.ologOetf(8.0), 0.0)
        // +2 EI (k = 4) is fully inside the domain.
        assertTrue(LogProfiles.ologOetf(4.0) < top)
        // Below 0: the domain floor (no NaN, no −∞).
        assertEquals(LogProfiles.ologOetf(0.0), LogProfiles.ologOetf(-1.0), 0.0)
        assertEquals(LogProfiles.ologOetf(0.0), LogProfiles.ologOetf(Double.NEGATIVE_INFINITY), 0.0)
    }

    @Test
    fun `Mi-Log pins the white paper constants and the colour-science anchor`() {
        assertEquals(-0.09023729, LogProfiles.MILOG_R0, 0.0)
        assertEquals(0.01974185, LogProfiles.MILOG_RT, 0.0)
        assertEquals(18.10531998, LogProfiles.MILOG_C2, 0.0)
        assertEquals(0.01384578, LogProfiles.MILOG_BETA, 0.0)
        assertEquals(0.09271529, LogProfiles.MILOG_GAMMA, 0.0)
        assertEquals(0.67291850, LogProfiles.MILOG_DELTA, 0.0)
        // colour-science doctest: log_encoding_MiLog(0.18) = 0.4534596…, decoding(0.45345968) = 0.18.
        assertEquals(0.45345968, LogProfiles.milogOetf(0.18), 1e-7)
        assertEquals(0.18, LogProfiles.milogInverse(0.45345968), 1e-7)
        assertEquals(0.674758, LogProfiles.milogOetf(1.0), 1e-6)
        assertEquals(18.10531998 * 0.09023729 * 0.09023729, LogProfiles.milogOetf(0.0), 1e-12)
        // Mi-Log's Rt is NOT a typo: the segments join in value and slope.
        val rt = LogProfiles.MILOG_RT
        val toe = LogProfiles.MILOG_C2 * (rt - LogProfiles.MILOG_R0).pow(2)
        val log = LogProfiles.MILOG_GAMMA * kotlin.math.log2(rt + LogProfiles.MILOG_BETA) + LogProfiles.MILOG_DELTA
        assertEquals(toe, log, 1e-7)
        assertEquals(
            2.0 * LogProfiles.MILOG_C2 * (rt - LogProfiles.MILOG_R0),
            LogProfiles.MILOG_GAMMA / ((rt + LogProfiles.MILOG_BETA) * kotlin.math.ln(2.0)),
            1e-6,
        )
        for (x in listOf(-0.05, 0.0, 0.01, 0.18, 1.0, 8.0)) {
            assertEquals("x=$x", x, LogProfiles.milogInverse(LogProfiles.milogOetf(x)), 1e-9)
        }
        assertEquals(0.0, LogProfiles.milogOetf(-0.1), 0.0)
        assertEquals(LogProfiles.MILOG_R0, LogProfiles.milogInverse(-0.1), 0.0)
    }

    @Test
    fun `every log OETF is finite and below code 1 up to the 3-stop EI headroom`() {
        for (oetf in listOf(
            LogProfiles::slog3Oetf, LogProfiles::logc3Oetf,
            LogProfiles::olog2Oetf, LogProfiles::ologOetf, LogProfiles::milogOetf,
        )) {
            var x = LogProfiles.GAMUT_LINEAR_FLOOR
            while (x <= 8.0) {
                val y = oetf(x)
                assertTrue("non-finite at x=$x", y.isFinite())
                assertTrue("above code 1 at x=$x: $y", y < 1.0)
                x += 1e-3
            }
        }
        assertEquals(0.950032, LogProfiles.olog2Oetf(8.0), 1e-6)
        assertEquals(0.951296, LogProfiles.milogOetf(8.0), 1e-6)
    }

    @Test
    fun `every OETF is strictly monotonic from the gamut floor upward`() {
        // 4.0 linear covers anything the [0,1] SDR decode times a row-sum-1 matrix can produce,
        // with headroom; the floor end exercises the linear/toe segments below their cuts.
        for (oetf in listOf(
            LogProfiles::slog3Oetf, LogProfiles::logc3Oetf,
            LogProfiles::olog2Oetf, LogProfiles::milogOetf,
        )) {
            var x = LogProfiles.GAMUT_LINEAR_FLOOR
            var previous = oetf(x)
            while (x < 4.0) {
                x += 1e-3
                val value = oetf(x)
                assertTrue("monotonicity broken at x=$x", value > previous)
                previous = value
            }
        }
        // O-Log V1 from its own domain floor (0) upward.
        var x = 0.0
        var previous = LogProfiles.ologOetf(x)
        while (x < 4.0) {
            x += 1e-3
            val value = LogProfiles.ologOetf(x)
            assertTrue("O-Log monotonicity broken at x=$x", value > previous)
            previous = value
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
        // The BT.2020 matrix rows sum to 1 at the 4-decimal precision it is published with.
        for (channel in LogProfiles.encodeOlog2(grey, grey, grey).toList()) {
            assertEquals(0.488272, channel, 1e-5)
        }
        for (channel in LogProfiles.encodeOlog(grey, grey, grey).toList()) {
            assertEquals(0.489949, channel, 1e-5)
        }
        for (channel in LogProfiles.encodeMilog(grey, grey, grey).toList()) {
            assertEquals(0.453460, channel, 1e-5)
        }
        // Sensor/ISP white (1.0 display light) lands at the curve's 100 % white, ≈ 0.6946 — the
        // clip: nothing above it exists in this chain to encode.
        for (channel in LogProfiles.encodeOlog2(1.0, 1.0, 1.0).toList()) {
            assertEquals(0.694553, channel, 1e-5)
        }
    }

    @Test
    fun `phone-maker log primaries go through the BT2020 matrix`() {
        // Pure BT.709 red: BT.2020 r = 0.6274, g = 0.0691, b = 0.0164 linear, then the OETF.
        for ((encode, oetf) in listOf(
            LogProfiles::encodeOlog2 to LogProfiles::olog2Oetf,
            LogProfiles::encodeOlog to LogProfiles::ologOetf,
            LogProfiles::encodeMilog to LogProfiles::milogOetf,
        )) {
            val red = encode(1.0, 0.0, 0.0, false, false)
            assertEquals(oetf(SdrToHlgMapping.R_FROM_R), red.red, 1e-12)
            assertEquals(oetf(SdrToHlgMapping.G_FROM_R), red.green, 1e-12)
            assertEquals(oetf(SdrToHlgMapping.B_FROM_R), red.blue, 1e-12)
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
        // The shared Apple-Log-form helper and the two curves that bind it (O-Log2, Mi-Log).
        assertTrue(shader.contains("vec3 logY = gamma * log2(max(x + beta, 1e-6)) + delta;"))
        assertTrue(shader.contains("vec3 q = max(x - r0, vec3(0.0));"))
        assertTrue(shader.contains("return mix(logY, toeY, 1.0 - step(vec3(rt), x));"))
        assertTrue(
            shader.contains(
                "toeLog2(x, ${LogProfiles.OLOG2_R0}, ${LogProfiles.OLOG2_RT}, ${LogProfiles.OLOG2_C2},\n" +
                    "                ${LogProfiles.OLOG2_B}, ${LogProfiles.OLOG2_A}, ${LogProfiles.OLOG2_C});",
            ),
        )
        assertTrue(
            shader.contains(
                "toeLog2(x, ${LogProfiles.MILOG_R0}, ${LogProfiles.MILOG_RT}, ${LogProfiles.MILOG_C2},\n" +
                    "                ${LogProfiles.MILOG_BETA}, ${LogProfiles.MILOG_GAMMA}, ${LogProfiles.MILOG_DELTA});",
            ),
        )
        // O-Log V1 with its domain clamp and DCTL exposure scale.
        assertTrue(shader.contains("clamp(x / ${LogProfiles.OLOG_DCTL_SCALE}, 0.0, ${LogProfiles.OLOG_R_MAX})"))
        assertTrue(
            shader.contains(
                "${LogProfiles.OLOG_GAMMA} * log(r + ${LogProfiles.OLOG_BETA}) + ${LogProfiles.OLOG_DELTA};",
            ),
        )
        // Defensive floor between matrix and OETF.
        assertTrue(shader.contains("max(c, vec3(${LogProfiles.GAMUT_LINEAR_FLOOR}))"))
    }

    @Test
    fun `each log branch applies decode then matrix then floor then OETF`() {
        val shader = Shaders.FRAGMENT
        val slog3Branch = shader.substringAfter("uTransfer == 2) {").substringBefore("} else if")
        val cineBranch = shader.substringAfter("uTransfer == 4) {").substringBefore("} else if")
        val logc3Branch = shader.substringAfter("uTransfer == 5) {").substringBefore("} else if")
        val olog2Branch = shader.substringAfter("uTransfer == 6) {").substringBefore("} else if")
        val ologBranch = shader.substringAfter("uTransfer == 7) {").substringBefore("} else if")
        val milogBranch = shader.substringAfter("uTransfer == 8) {").substringBefore("}")
        for ((branch, encode) in listOf(
            slog3Branch to "color = slog3(gamutFloor(toSGamut3(lin)));",
            cineBranch to "color = slog3(gamutFloor(toSGamut3Cine(lin)));",
            logc3Branch to "color = logc3(gamutFloor(toAwg3(lin)));",
            olog2Branch to "color = olog2(gamutFloor(toRec2020(lin)));",
            ologBranch to "color = olog(gamutFloor(toRec2020(lin)));",
            milogBranch to "color = milog(gamutFloor(toRec2020(lin)));",
        )) {
            // logInputLinear IS sourceLinear(color) without a Log EI offset (pinned, with the
            // headroom path, by NativeLogSourceDecodeTest); HLG alone keeps the direct call.
            val decode = branch.indexOf("vec3 lin = logInputLinear(color);")
            val apply = branch.indexOf(encode)
            assertTrue("source decode must open the branch", decode >= 0)
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
        // `meter` is now write-once: the only reassignment lived in the de-log branch, which was
        // removed 2026-08-04. Nothing may reintroduce one — an overlay metering a post-transfer
        // signal is the exact defect this test guards.
        assertFalse("no branch may republish `color` as the meter signal", shader.contains("meter = color;"))
    }

    @Test
    fun `the declined vendor-log path leaves no shader residue`() {
        val shader = Shaders.FRAGMENT
        // O-Log2's toe IS part of OPPO's official curve (white paper 2026-04-17): this used to
        // forbid these constants while a toe-less reverse-engineered curve shipped; it now REQUIRES
        // them, with the corrected threshold, and forbids only the paper's typo'd 0.006.
        for (toe in listOf("47.28711236", "-0.05641088")) {
            assertTrue("the official O-Log2 toe ($toe) must be in the shader", shader.contains(toe))
        }
        assertFalse("the printed Rt typo must not be the join", shader.contains("${LogProfiles.OLOG2_R0}, 0.006"))
        // The de-log inverse and its branch existed only for a scene-referred vendor stream that can
        // no longer arrive (path declined 2026-08-04). They were removed with it; re-adding either
        // means re-adding dead code, so this asserts their absence rather than their survival.
        assertFalse("the de-log inverse must not come back", shader.contains("olog2Inv"))
        assertFalse("the de-log branch must not come back", shader.contains("uTransfer == 3"))
        assertFalse("its 709 helper must not come back", shader.contains("toRec709"))
    }

    // ---- helpers ----

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
