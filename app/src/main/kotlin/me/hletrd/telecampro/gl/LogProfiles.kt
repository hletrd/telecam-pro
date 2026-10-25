package me.hletrd.telecampro.gl

import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/**
 * Android-free reference for the GL-baked log profiles (S-Log3/S-Gamut3, S-Log3/S-Gamut3.Cine,
 * ARRI LogC3 EI800/AWG3, and the per-device phone-maker logs OPPO O-Log2, OPPO O-Log and Xiaomi
 * Mi-Log, all three in BT.2020) — the [Shaders.FRAGMENT] string interpolates THESE constants, so the
 * shader and the unit-tested reference cannot drift apart (same single-source pattern as
 * [SdrToHlgMapping]).
 *
 * Pipeline order per profile (mirrors [Shaders]): source-aware standard/HLG decode into the common
 * display-light domain, linear 3×3 BT.709→target-gamut matrix, defensive
 * lower clamp ([GAMUT_LINEAR_FLOOR]) so both curves' log/linear segments stay defined, then the
 * profile's OETF. These remain display-referred curves:
 * the ISP has already tone-mapped the stream, so clipped highlights cannot be recovered and the
 * result is NOT scene-referred camera log — a grading convenience with standard-shaped curves.
 *
 * Curve sources (constants copied verbatim, pinned by LogProfilesTest):
 *  - Sony "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3" (S-Log3 OETF).
 *  - ARRI "ALEXA Log C Curve — Usage in VFX" (LogC3 EI800 parameter row).
 *  - OPPO "O-Log2 White Paper" (2026-04-17, Cris Yan, CHN→ENG translation;
 *    https://www.oppo.com/en/o-log-technical-downloads/): a TWO-segment curve (quadratic toe +
 *    log2 segment) in BT.2020-2 primaries, D65 — the same algebraic form AND constants as Apple
 *    Log. See [OLOG2_RT] for the printed-threshold typo.
 *  - OPPO "O-Log White Paper" V1 (2025-10-22, same download page): ONE natural-log segment over
 *    R ∈ [0, 16], BT.2020-2 primaries, D65; exposure convention from OPPO's own
 *    `O-Log_to_ACES_AP0_V1.dctl` — see [OLOG_DCTL_SCALE].
 *  - Xiaomi "Xiaomi Log Profile White Paper" (December 2024), as transcribed by colour-science
 *    (`colour/models/rgb/transfer_functions/xiaomi_mi_log.py`, whose doctest pins
 *    0.18 → 0.4534596…): Apple-Log form with Xiaomi's own constants, BT.2020 D65.
 *
 * Gamut matrices are M = inv(RGBtoXYZ(target)) × RGBtoXYZ(BT.709), all primaries specified with
 * the D65 white (x=0.3127, y=0.3290) — no chromatic adaptation needed, so every row sums to 1.0
 * exactly (white maps to white; the row-sum test is the wrong-primaries tripwire).
 */
internal object LogProfiles {
    /** log10(x) = ln(x) × this — GLSL has no log10; both the shader and this file use ln·INV_LN10. */
    const val INV_LN10 = 0.4342944819032518

    /**
     * Defensive lower clamp between the gamut matrix and the OETF. All six matrices are strictly
     * positive (BT.709 sits inside each target gamut), so an in-range SDR decode cannot actually go
     * negative — this floor guards float error and any future matrix change while keeping the log
     * arguments defined: S-Log3's log input is x + 0.01 (≥ 1e-4 at the floor), LogC3's cut keeps
     * its log branch above x = 0.010591, the two Apple-Log-form curves (O-Log2, Mi-Log) are DEFINED
     * down to their own R0 (< −0.05) by the quadratic toe, and O-Log v1 clamps to its white-paper
     * domain itself ([ologOetf]). Deliberately NO upper clamp here: row-sum-1 matrices cannot
     * push [0,1] input above 1, and a silent highlight clamp here would be a gamut distortion — and
     * under a native-log Log EI offset the input legitimately reaches k (up to 8), which every OETF
     * here encodes below code 1.0 (S-Log3 ≈ 0.83, LogC3 ≈ 0.79, O-Log2 ≈ 0.950, Mi-Log ≈ 0.951)
     * EXCEPT O-Log v1, whose white-paper domain ends at R = 16 (scene-linear ≈ 7.37, code ≈ 0.9996):
     * at +3 EI the top ≈ 0.12 stop of the headroom clips there (see [ologOetf]).
     */
    const val GAMUT_LINEAR_FLOOR = -0.0099

    // S-Log3 OETF (Sony technical summary; x = linear scene reflection, y = normalized code value):
    //   x >= 0.01125: y = (420 + log10((x + 0.01) / (0.18 + 0.01)) * 261.5) / 1023
    //   x <  0.01125: y = (x * (171.2102946929 - 95) / 0.01125 + 95) / 1023
    // 18% grey encodes to 420/1023 ≈ 0.410557; 0 encodes to the 95/1023 code black.
    const val SLOG3_CUT = 0.01125
    const val SLOG3_LIN_OFFSET = 0.01
    const val SLOG3_GREY_PLUS_OFFSET = 0.19 // 0.18 + 0.01, the log segment's grey normalizer
    const val SLOG3_LOG_SLOPE = 261.5
    const val SLOG3_LOG_OFFSET_CODE = 420.0
    const val SLOG3_CUT_CODE = 171.2102946929 // log-segment value at the cut — continuity by construction
    const val SLOG3_BLACK_CODE = 95.0
    const val SLOG3_CODE_SCALE = 1023.0

    // ARRI LogC3 EI800 OETF (ALEXA Log C VFX parameter table):
    //   x >  cut: y = c * log10(a*x + b) + d
    //   x <= cut: y = e*x + f
    // 18% grey encodes to ≈ 0.391007; 0 encodes to f = 0.092809.
    const val LOGC3_CUT = 0.010591
    const val LOGC3_A = 5.555556
    const val LOGC3_B = 0.052272
    const val LOGC3_C = 0.247190
    const val LOGC3_D = 0.385537
    const val LOGC3_E = 5.367655
    const val LOGC3_F = 0.092809

    // OPPO O-Log2 OETF (official white paper 2026-04-17), Apple-Log form:
    //   y = γ·log2(x + β) + δ   for x >= Rt
    //   y = c·(x − R0)²         for R0 <= x < Rt   (quadratic toe)
    //   y = 0                   for x < R0
    // 18 % grey → ≈ 0.48827, 1.0 (100 % white) → ≈ 0.69455, 0 → ≈ 0.15048 (c·R0², the toe's value).
    // The single-segment form this app carried 2026-10-15 (reverse-engineered from OPPO's LUT/DCTL
    // before the paper was found) had no toe and mapped 0 → ≈ 0.1208; it is SUPERSEDED by the
    // paper. OPPO's own O-Log2→ACES DCTL decodes with the log segment only (rounded 0.693/0.0855/
    // 0.0096), which is why the toe was invisible from it — the paper is the authority.
    const val OLOG2_R0 = -0.05641088

    /**
     * The paper PRINTS `Rt = 0.006` (in both the encode and decode sections), but that value is a
     * typo: at 0.006 the toe gives c·(Rt − R0)² ≈ 0.18419 while the log segment gives ≈ 0.18047 — a
     * 0.0037 DOWNWARD step, i.e. a NON-monotonic curve no camera encodes. At Rt = 0.01 the two
     * segments meet in value (0.2085553) AND slope (6.28076, the toe's 2c(x − R0) against the log's
     * γ/((x + β)·ln 2)) — the C¹ join the quadratic toe exists to make — and every other constant
     * equals Apple Log's, whose Rt is 0.01. The paper's Table 1 "0 %" row (0.18418888) is the toe
     * evaluated AT 0.006, so it inherits the same typo and is not used as an anchor.
     */
    const val OLOG2_RT = 0.01
    const val OLOG2_C2 = 47.28711236 // the paper's c (quadratic toe gain)
    const val OLOG2_B = 0.00964052 // β
    const val OLOG2_A = 0.08550479 // γ
    const val OLOG2_C = 0.69336945 // δ

    // OPPO O-Log (V1) OETF (official white paper V1): P = γ·ln(R + β) + δ, ONE segment, 0 <= R <= 16.
    const val OLOG_GAMMA = 0.139
    const val OLOG_BETA = 0.019
    const val OLOG_DELTA = 0.614
    /** Upper end of the white paper's encoding domain (R = 16 ≙ the 10-EV-normalised sensor max). */
    const val OLOG_R_MAX = 16.0

    /**
     * O-Log v1's R is NOT scene reflectance: the paper normalises the sensor signal so 18 %
     * reflectance sits at R ≈ 0.39 (code 0.4902 ≈ 502/1023 under OPPO's auto exposure), and OPPO's
     * official `O-Log_to_ACES_AP0_V1.dctl` / `.ctl` decode with
     * `lin = (exp((P − 0.614)/0.139) − 0.019) / 16 · 7.37235`. Encoding scene-linear s as
     * R = s / [OLOG_DCTL_SCALE] is the EXACT inverse of that DCTL, so OPPO's own LUT/DCTL workflow
     * lands at correct exposure: s = 0.18 → R ≈ 0.3906 → P ≈ 0.48995 (paper: 0.4901589, i.e. the
     * DCTL decodes the paper's grey to s ≈ 0.1802 — the 0.1 % residual is OPPO's own rounding).
     * Without it (R = s) grey would land at P ≈ 0.383, ~1.2 stops dark through OPPO's LUT.
     */
    const val OLOG_DCTL_SCALE = 7.37235 / 16.0

    // Xiaomi Mi-Log OETF (Xiaomi Log Profile White Paper, Dec 2024, via colour-science): the same
    // Apple-Log form as O-Log2 with Xiaomi's constants. 18 % grey → 0.45345968 (colour-science
    // doctest), 1.0 → ≈ 0.67476, 0 → ≈ 0.14743. Its Rt is NOT a typo: value and slope match there.
    const val MILOG_R0 = -0.09023729
    const val MILOG_RT = 0.01974185
    const val MILOG_C2 = 18.10531998
    const val MILOG_BETA = 0.01384578
    const val MILOG_GAMMA = 0.09271529
    const val MILOG_DELTA = 0.67291850

    // Linear-light BT.709 -> S-Gamut3 (D65→D65, rows sum to 1).
    const val SG3_R_FROM_R = 0.5660490800
    const val SG3_R_FROM_G = 0.3427630874
    const val SG3_R_FROM_B = 0.0911878326
    const val SG3_G_FROM_R = 0.0769613789
    const val SG3_G_FROM_G = 0.7990545001
    const val SG3_G_FROM_B = 0.1239841211
    const val SG3_B_FROM_R = 0.0223509133
    const val SG3_B_FROM_G = 0.1086120512
    const val SG3_B_FROM_B = 0.8690370356

    // Linear-light BT.709 -> S-Gamut3.Cine (D65→D65, rows sum to 1).
    const val SG3C_R_FROM_R = 0.6456794776
    const val SG3C_R_FROM_G = 0.2591145470
    const val SG3C_R_FROM_B = 0.0952059754
    const val SG3C_G_FROM_R = 0.0875299915
    const val SG3C_G_FROM_G = 0.7596995626
    const val SG3C_G_FROM_B = 0.1527704459
    const val SG3C_B_FROM_R = 0.0369574199
    const val SG3C_B_FROM_G = 0.1292809048
    const val SG3C_B_FROM_B = 0.8337616753

    /**
     * Linear-light S-Gamut3.Cine -> BT.709 (D65→D65), row-major: the exact inverse of the SG3C_*
     * matrix above, for a source whose primaries ARE S-Gamut3.Cine (the EXPERIMENTAL RAW log
     * developer, camera/RawLogBridge.kt). Computed, not typed, so it cannot drift from its forward
     * twin; LogProfilesTest pins the round trip. Its output legitimately goes NEGATIVE for colours
     * outside BT.709 — the Shaders.kt wide path keeps those values for the gamut branches, which map
     * them back exactly, and clamps only the display rendition.
     */
    val REC709_FROM_SG3C: DoubleArray = invert3(
        doubleArrayOf(
            SG3C_R_FROM_R, SG3C_R_FROM_G, SG3C_R_FROM_B,
            SG3C_G_FROM_R, SG3C_G_FROM_G, SG3C_G_FROM_B,
            SG3C_B_FROM_R, SG3C_B_FROM_G, SG3C_B_FROM_B,
        ),
    )

    private fun invert3(m: DoubleArray): DoubleArray {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    // Linear-light BT.709 -> ARRI Wide Gamut 3 (D65→D65, rows sum to 1).
    const val AWG3_R_FROM_R = 0.6313210225
    const val AWG3_R_FROM_G = 0.2708007273
    const val AWG3_R_FROM_B = 0.0978782502
    const val AWG3_G_FROM_R = 0.0368199337
    const val AWG3_G_FROM_G = 0.7930369660
    const val AWG3_G_FROM_B = 0.1701431002
    const val AWG3_B_FROM_R = 0.0173697319
    const val AWG3_B_FROM_G = 0.1487891849
    const val AWG3_B_FROM_B = 0.8338410832

    /** S-Log3 OETF for one component. Caller keeps x ≥ [GAMUT_LINEAR_FLOOR] (log arg defined). */
    fun slog3Oetf(x: Double): Double = if (x >= SLOG3_CUT) {
        (SLOG3_LOG_OFFSET_CODE + ln((x + SLOG3_LIN_OFFSET) / SLOG3_GREY_PLUS_OFFSET) * INV_LN10 * SLOG3_LOG_SLOPE) / SLOG3_CODE_SCALE
    } else {
        (x * (SLOG3_CUT_CODE - SLOG3_BLACK_CODE) / SLOG3_CUT + SLOG3_BLACK_CODE) / SLOG3_CODE_SCALE
    }

    /**
     * Exact inverse of [slog3Oetf]: normalized code value -> linear reflection (Sony technical
     * summary's S-Log3 -> reflection formula, same constants). This is the decode the shader runs on
     * a NATIVE-LOG camera stream, where the ISP itself encoded S-Log3 through a programmed
     * TONEMAP_CURVE ([me.hletrd.telecampro.camera.nativeLogToneCurve]), so the GL chain can recover the
     * ISP's linear signal before re-encoding the selected profile. The segment switch sits at the
     * cut's CODE value ([SLOG3_CUT_CODE] / [SLOG3_CODE_SCALE]), which is exactly where the forward
     * curve switches — pinned by NativeLogCurveTest's round trip.
     */
    fun slog3Inverse(y: Double): Double = if (y >= SLOG3_CUT_CODE / SLOG3_CODE_SCALE) {
        kotlin.math.exp((y * SLOG3_CODE_SCALE - SLOG3_LOG_OFFSET_CODE) / SLOG3_LOG_SLOPE / INV_LN10) *
            SLOG3_GREY_PLUS_OFFSET - SLOG3_LIN_OFFSET
    } else {
        (y * SLOG3_CODE_SCALE - SLOG3_BLACK_CODE) * SLOG3_CUT / (SLOG3_CUT_CODE - SLOG3_BLACK_CODE)
    }

    /** LogC3 EI800 OETF for one component. Caller keeps x ≥ [GAMUT_LINEAR_FLOOR] (log arg defined). */
    fun logc3Oetf(x: Double): Double = if (x > LOGC3_CUT) {
        LOGC3_C * ln(LOGC3_A * x + LOGC3_B) * INV_LN10 + LOGC3_D
    } else {
        LOGC3_E * x + LOGC3_F
    }

    /**
     * The Apple-Log-form OETF shared by O-Log2 and Mi-Log: log2 segment at and above [rt], quadratic
     * toe `c2·(x − r0)²` between [r0] and [rt], 0 below [r0]. Defined for every finite input.
     */
    private fun toeLog2Oetf(x: Double, r0: Double, rt: Double, c2: Double, beta: Double, gamma: Double, delta: Double): Double =
        when {
            x >= rt -> gamma * log2(x + beta) + delta
            x >= r0 -> c2 * (x - r0) * (x - r0)
            else -> 0.0
        }

    /** Exact inverse of [toeLog2Oetf] (the white papers' decoding function); P < 0 → r0. */
    private fun toeLog2Inverse(p: Double, r0: Double, rt: Double, c2: Double, beta: Double, gamma: Double, delta: Double): Double {
        val pt = c2 * (rt - r0) * (rt - r0)
        return when {
            p >= pt -> 2.0.pow((p - delta) / gamma) - beta
            p >= 0.0 -> r0 + kotlin.math.sqrt(p / c2)
            else -> r0
        }
    }

    /** OPPO O-Log2 OETF for one component (official two-segment curve, [OLOG2_RT] = 0.01). */
    fun olog2Oetf(x: Double): Double = toeLog2Oetf(x, OLOG2_R0, OLOG2_RT, OLOG2_C2, OLOG2_B, OLOG2_A, OLOG2_C)

    /** O-Log2 decoding function (white paper §2.2, with the corrected threshold). */
    fun olog2Inverse(p: Double): Double = toeLog2Inverse(p, OLOG2_R0, OLOG2_RT, OLOG2_C2, OLOG2_B, OLOG2_A, OLOG2_C)

    /**
     * OPPO O-Log (V1) OETF for one scene-linear component, under OPPO's DCTL exposure convention
     * ([OLOG_DCTL_SCALE]). R is clamped to the paper's domain [0, [OLOG_R_MAX]]: below 0 the log
     * argument would approach 0, and above 16 the paper defines nothing (code there ≈ 0.9996). That
     * upper clamp is the one place a Log EI offset can clip: R = 16 is scene-linear ≈ 7.37, so at
     * +3 EI (k = 8) the top ≈ 0.12 stop of the bought headroom encodes flat — documented rather than
     * capping the offset for one profile, since everything below it is kept.
     */
    fun ologOetf(x: Double): Double =
        OLOG_GAMMA * ln((x / OLOG_DCTL_SCALE).coerceIn(0.0, OLOG_R_MAX) + OLOG_BETA) + OLOG_DELTA

    /** OPPO's official V1 DCTL decode (code → scene-linear), the exact inverse of [ologOetf] in-domain. */
    fun ologInverse(p: Double): Double = (kotlin.math.exp((p - OLOG_DELTA) / OLOG_GAMMA) - OLOG_BETA) * OLOG_DCTL_SCALE

    /** Xiaomi Mi-Log OETF for one component (Apple-Log form, Xiaomi constants). */
    fun milogOetf(x: Double): Double = toeLog2Oetf(x, MILOG_R0, MILOG_RT, MILOG_C2, MILOG_BETA, MILOG_GAMMA, MILOG_DELTA)

    /** Mi-Log decoding function. */
    fun milogInverse(p: Double): Double = toeLog2Inverse(p, MILOG_R0, MILOG_RT, MILOG_C2, MILOG_BETA, MILOG_GAMMA, MILOG_DELTA)

    fun encodeSlog3(red: Double, green: Double, blue: Double, sourceHlg: Boolean = false, sceneLinear: Boolean = false): Rgb = encode(
        red, green, blue,
        SG3_R_FROM_R, SG3_R_FROM_G, SG3_R_FROM_B,
        SG3_G_FROM_R, SG3_G_FROM_G, SG3_G_FROM_B,
        SG3_B_FROM_R, SG3_B_FROM_G, SG3_B_FROM_B,
        ::slog3Oetf,
        sourceHlg,
        sceneLinear,
    )

    fun encodeSlog3Cine(red: Double, green: Double, blue: Double, sourceHlg: Boolean = false, sceneLinear: Boolean = false): Rgb = encode(
        red, green, blue,
        SG3C_R_FROM_R, SG3C_R_FROM_G, SG3C_R_FROM_B,
        SG3C_G_FROM_R, SG3C_G_FROM_G, SG3C_G_FROM_B,
        SG3C_B_FROM_R, SG3C_B_FROM_G, SG3C_B_FROM_B,
        ::slog3Oetf,
        sourceHlg,
        sceneLinear,
    )

    fun encodeLogc3(red: Double, green: Double, blue: Double, sourceHlg: Boolean = false, sceneLinear: Boolean = false): Rgb = encode(
        red, green, blue,
        AWG3_R_FROM_R, AWG3_R_FROM_G, AWG3_R_FROM_B,
        AWG3_G_FROM_R, AWG3_G_FROM_G, AWG3_G_FROM_B,
        AWG3_B_FROM_R, AWG3_B_FROM_G, AWG3_B_FROM_B,
        ::logc3Oetf,
        sourceHlg,
        sceneLinear,
    )

    /**
     * O-Log2 / BT.2020: the same decode → matrix → floor → OETF chain, with the BT.709→BT.2020
     * matrix the HLG mapping already single-sources ([SdrToHlgMapping]). The shared floor is inert
     * here (the toe is defined far below it).
     */
    fun encodeOlog2(red: Double, green: Double, blue: Double, sourceHlg: Boolean = false, sceneLinear: Boolean = false): Rgb =
        encodeBt2020(red, green, blue, ::olog2Oetf, sourceHlg, sceneLinear)

    /** O-Log (V1) / BT.2020, same chain; [ologOetf] owns its domain clamp. */
    fun encodeOlog(red: Double, green: Double, blue: Double, sourceHlg: Boolean = false, sceneLinear: Boolean = false): Rgb =
        encodeBt2020(red, green, blue, ::ologOetf, sourceHlg, sceneLinear)

    /** Mi-Log / BT.2020, same chain. */
    fun encodeMilog(red: Double, green: Double, blue: Double, sourceHlg: Boolean = false, sceneLinear: Boolean = false): Rgb =
        encodeBt2020(red, green, blue, ::milogOetf, sourceHlg, sceneLinear)

    private fun encodeBt2020(
        red: Double, green: Double, blue: Double,
        oetf: (Double) -> Double,
        sourceHlg: Boolean,
        sceneLinear: Boolean,
    ): Rgb = encode(
        red, green, blue,
        SdrToHlgMapping.R_FROM_R, SdrToHlgMapping.R_FROM_G, SdrToHlgMapping.R_FROM_B,
        SdrToHlgMapping.G_FROM_R, SdrToHlgMapping.G_FROM_G, SdrToHlgMapping.G_FROM_B,
        SdrToHlgMapping.B_FROM_R, SdrToHlgMapping.B_FROM_G, SdrToHlgMapping.B_FROM_B,
        oetf,
        sourceHlg,
        sceneLinear,
    )

    // The one place the documented order lives on the CPU side: decode → matrix → floor → OETF.
    /**
     * CPU mirror of the shader's `sourceLinear`: how the CAMERA encoded what was sampled.
     *
     * [sourceHlg] false is the 8-bit display-referred stream (BT.1886). True is a 10-bit HLG10/DV
     * session, where the external sampler yields HLG-encoded values; that branch is the inverse HLG
     * OETF followed by the inverse of the forward mapping's reference-white scaling, so both source
     * kinds arrive on the SAME display-light scale (diffuse white = 1.0) the matrix and OETF below
     * were written for.
     */
    internal fun sourceLinear(v: Double, sourceHlg: Boolean): Double {
        val s = v.coerceIn(0.0, 1.0)
        if (!sourceHlg) return s.pow(SdrToHlgMapping.SDR_EOTF_GAMMA)
        val scene = if (s <= 0.5) {
            s * s / 3.0
        } else {
            (kotlin.math.exp((s - SdrToHlgMapping.HLG_C) / SdrToHlgMapping.HLG_A) + SdrToHlgMapping.HLG_B) / 12.0
        }
        return scene.pow(SdrToHlgMapping.HLG_SYSTEM_GAMMA) / SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE
    }

    private fun encode(
        red: Double, green: Double, blue: Double,
        rr: Double, rg: Double, rb: Double,
        gr: Double, gg: Double, gb: Double,
        br: Double, bg: Double, bb: Double,
        oetf: (Double) -> Double,
        sourceHlg: Boolean,
        sceneLinear: Boolean,
    ): Rgb {
        // [sceneLinear]: the inputs ARE linear light already — the native-log Log EI offset path
        // (Shaders `nativeSceneLinear`), whose values legitimately exceed 1.0 and must not meet
        // sourceLinear's [0, 1] clamp.
        val linear709 = if (sceneLinear) Rgb(red, green, blue) else Rgb(red, green, blue).map { sourceLinear(it, sourceHlg) }
        val linearTarget = Rgb(
            red = rr * linear709.red + rg * linear709.green + rb * linear709.blue,
            green = gr * linear709.red + gg * linear709.green + gb * linear709.blue,
            blue = br * linear709.red + bg * linear709.green + bb * linear709.blue,
        )
        return linearTarget.map { oetf(it.coerceAtLeast(GAMUT_LINEAR_FLOOR)) }
    }
}
