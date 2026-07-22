package com.hletrd.findx9tele.gl

import kotlin.math.ln
import kotlin.math.pow

/**
 * Android-free reference for the GL-baked log profiles (S-Log3/S-Gamut3, S-Log3/S-Gamut3.Cine,
 * ARRI LogC3 EI800/AWG3) — the [Shaders.FRAGMENT] string interpolates THESE constants, so the
 * shader and the unit-tested reference cannot drift apart (same single-source pattern as
 * [SdrToHlgMapping]).
 *
 * Pipeline order per profile (mirrors the BT.2408 HLG chain documented in [Shaders]): BT.1886 2.4
 * decode of the display-referred SDR preview, linear 3×3 BT.709→target-gamut matrix, defensive
 * lower clamp ([GAMUT_LINEAR_FLOOR]) so both curves' log/linear segments stay defined, then the
 * profile's OETF. Like the removed O-Log2 option, these are display-referred SDR-source curves:
 * the ISP has already tone-mapped the stream, so clipped highlights cannot be recovered and the
 * result is NOT scene-referred camera log — a grading convenience with standard-shaped curves.
 *
 * Curve sources (constants copied verbatim, pinned by LogProfilesTest):
 *  - Sony "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3" (S-Log3 OETF).
 *  - ARRI "ALEXA Log C Curve — Usage in VFX" (LogC3 EI800 parameter row).
 *
 * Gamut matrices are M = inv(RGBtoXYZ(target)) × RGBtoXYZ(BT.709), all primaries specified with
 * the D65 white (x=0.3127, y=0.3290) — no chromatic adaptation needed, so every row sums to 1.0
 * exactly (white maps to white; the row-sum test is the wrong-primaries tripwire).
 */
internal object LogProfiles {
    /** log10(x) = ln(x) × this — GLSL has no log10; both the shader and this file use ln·INV_LN10. */
    const val INV_LN10 = 0.4342944819032518

    /**
     * Defensive lower clamp between the gamut matrix and the OETF. All three matrices are strictly
     * positive (BT.709 sits inside each target gamut), so an in-range SDR decode cannot actually go
     * negative — this floor guards float error and any future matrix change while keeping the log
     * arguments defined: S-Log3's log input is x + 0.01 (≥ 1e-4 at the floor) and LogC3's cut keeps
     * its log branch above x = 0.010591. Deliberately NO upper clamp: row-sum-1 matrices cannot
     * push [0,1] input above 1, and a silent highlight clamp here would be a gamut distortion.
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

    /** LogC3 EI800 OETF for one component. Caller keeps x ≥ [GAMUT_LINEAR_FLOOR] (log arg defined). */
    fun logc3Oetf(x: Double): Double = if (x > LOGC3_CUT) {
        LOGC3_C * ln(LOGC3_A * x + LOGC3_B) * INV_LN10 + LOGC3_D
    } else {
        LOGC3_E * x + LOGC3_F
    }

    fun encodeSlog3(red: Double, green: Double, blue: Double): Rgb = encode(
        red, green, blue,
        SG3_R_FROM_R, SG3_R_FROM_G, SG3_R_FROM_B,
        SG3_G_FROM_R, SG3_G_FROM_G, SG3_G_FROM_B,
        SG3_B_FROM_R, SG3_B_FROM_G, SG3_B_FROM_B,
        ::slog3Oetf,
    )

    fun encodeSlog3Cine(red: Double, green: Double, blue: Double): Rgb = encode(
        red, green, blue,
        SG3C_R_FROM_R, SG3C_R_FROM_G, SG3C_R_FROM_B,
        SG3C_G_FROM_R, SG3C_G_FROM_G, SG3C_G_FROM_B,
        SG3C_B_FROM_R, SG3C_B_FROM_G, SG3C_B_FROM_B,
        ::slog3Oetf,
    )

    fun encodeLogc3(red: Double, green: Double, blue: Double): Rgb = encode(
        red, green, blue,
        AWG3_R_FROM_R, AWG3_R_FROM_G, AWG3_R_FROM_B,
        AWG3_G_FROM_R, AWG3_G_FROM_G, AWG3_G_FROM_B,
        AWG3_B_FROM_R, AWG3_B_FROM_G, AWG3_B_FROM_B,
        ::logc3Oetf,
    )

    // The one place the documented order lives on the CPU side: decode → matrix → floor → OETF.
    private fun encode(
        red: Double, green: Double, blue: Double,
        rr: Double, rg: Double, rb: Double,
        gr: Double, gg: Double, gb: Double,
        br: Double, bg: Double, bb: Double,
        oetf: (Double) -> Double,
    ): Rgb {
        val linear709 = Rgb(red, green, blue).map { it.coerceIn(0.0, 1.0).pow(SdrToHlgMapping.SDR_EOTF_GAMMA) }
        val linearTarget = Rgb(
            red = rr * linear709.red + rg * linear709.green + rb * linear709.blue,
            green = gr * linear709.red + gg * linear709.green + gb * linear709.blue,
            blue = br * linear709.red + bg * linear709.green + bb * linear709.blue,
        )
        return linearTarget.map { oetf(it.coerceAtLeast(GAMUT_LINEAR_FLOOR)) }
    }
}
