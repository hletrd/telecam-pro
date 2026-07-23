package com.hletrd.findx9tele.gl

/**
 * GLSL ES 2.0 shaders for the flip/preview pipeline.
 *
 * The vertex shader rotates the full-screen quad (afocal 180° + sensor orientation, baked into
 * uMvp) and transforms external-texture coordinates via the SurfaceTexture matrix (uTexMatrix).
 *
 * The fragment shader samples the camera's external OES texture and optionally applies:
 *  - an SDR-to-HLG mapping or one of the standard log profiles (S-Log3/S-Gamut3,
 *    S-Log3/S-Gamut3.Cine, ARRI LogC3 EI800/AWG3) for the video encoder path,
 *  - focus peaking (edge highlight) and zebra (clipping stripes) for the preview path.
 *
 * NOTE: the camera preview signal is display-referred (already non-linear). HLG follows the
 * simplified ITU-R BT.2408-9 display-referred mapping: BT.1886 decode, linear BT.709→BT.2020,
 * reference-white/inverse-OOTF adjustment, then the BT.2100 HLG OETF. Each log profile follows the
 * same chain shape: BT.1886 decode, linear BT.709→target-gamut 3×3 matrix, defensive lower clamp,
 * then the profile's OETF — constants single-sourced from [LogProfiles] (the former user-facing
 * O-Log2 forward curve was removed 2026-07-22 with the option; only its INVERSE stays, dormant,
 * for the Gamma Display Assist of a future native scene-referred stream). No path can recover
 * above-white highlights removed by the ISP's SDR tone mapping. Verify on device.
 */
object Shaders {

    // O-Log2 shadow-toe constants (official white paper, 2026-04 EN v1) — the SINGLE source for
    // the DORMANT inverse's toe and segment boundary (P6.7/CR4-8: the boundary used to be a
    // second hand-computed literal, 0.20856, whose arithmetic slipped — it squared 0.06641088
    // instead of 0.006 + 0.05641088 = 0.06241088, so the inverse used the toe branch for a band
    // of P the forward had encoded with the log segment). The forward O-Log2 OETF left with the
    // user-facing option (2026-07-22); these stay because olog2Inv below still inverts it.
    const val OLOG2_TOE_SCALE = 47.28711236
    const val OLOG2_TOE_OFFSET = 0.05641088
    const val OLOG2_TOE_MAX_R = 0.006

    /** P at the forward curve's R = [OLOG2_TOE_MAX_R] switch — the derived inverse boundary. */
    val OLOG2_INV_BOUNDARY: Double =
        OLOG2_TOE_SCALE * (OLOG2_TOE_MAX_R + OLOG2_TOE_OFFSET) * (OLOG2_TOE_MAX_R + OLOG2_TOE_OFFSET)

    const val VERTEX = """
        uniform mat4 uMvp;
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMvp * aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    // Plain val (not const): the O-Log2 inverse boundary above is a DERIVED expression, which a
    // compile-time constant string cannot interpolate. Behavior is identical.
    val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES uTexture;
        uniform int uTransfer;   // 0 = display, 1 = HLG, 2 = S-Log3/S-Gamut3, 4 = S-Log3/S-Gamut3.Cine,
                                 // 5 = LogC3/AWG3, 3 = de-log O-Log2→709 (DORMANT Gamma Disp. Assist)
        uniform int uPeaking;    // 0/1  (preview only)
        uniform float uPeakThreshold; // edge magnitude above which peaking paints
        uniform vec3 uPeakColor;      // peaking highlight color
        uniform int uZebra;      // 0/1  (preview only)
        uniform float uZebraThreshold; // luma above which zebra stripes draw
        uniform int uFalseColor; // 0/1  (preview only) exposure false-color map
        uniform vec2 uTexel;     // 1/width, 1/height for neighbor sampling
        varying vec2 vTexCoord;

        const vec3 LUMA = vec3(0.2627, 0.6780, 0.0593); // Rec.2020 luma weights
        const float SDR_EOTF_GAMMA = ${SdrToHlgMapping.SDR_EOTF_GAMMA};
        const float BT2408_HLG_SCALE = ${SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE};
        const float HLG_SYSTEM_GAMMA = ${SdrToHlgMapping.HLG_SYSTEM_GAMMA};

        float luma(vec3 c) { return dot(c, LUMA); }

        // BT.2100 HLG OETF. Input is normalized scene light from the BT.2408 mapping below.
        vec3 hlg(vec3 x) {
            vec3 lo = sqrt(3.0 * clamp(x, 0.0, 1.0));
            float a = ${SdrToHlgMapping.HLG_A}, b = ${SdrToHlgMapping.HLG_B}, c = ${SdrToHlgMapping.HLG_C};
            vec3 hi = a * log(max(12.0 * x - b, 1e-4)) + c;
            return mix(hi, lo, step(x, vec3(1.0 / 12.0)));
        }

        // Rec.709 -> Rec.2020 primaries (linear light), for the HLG mapping above.
        vec3 toRec2020(vec3 c) {
            return vec3(
                dot(vec3(${SdrToHlgMapping.R_FROM_R}, ${SdrToHlgMapping.R_FROM_G}, ${SdrToHlgMapping.R_FROM_B}), c),
                dot(vec3(${SdrToHlgMapping.G_FROM_R}, ${SdrToHlgMapping.G_FROM_G}, ${SdrToHlgMapping.G_FROM_B}), c),
                dot(vec3(${SdrToHlgMapping.B_FROM_R}, ${SdrToHlgMapping.B_FROM_G}, ${SdrToHlgMapping.B_FROM_B}), c));
        }

        // BT.709 -> S-Gamut3 primaries (linear light), D65->D65 — every row sums to 1, so neutrals
        // pass through unchanged (constants + derivation in LogProfiles; pinned by its host test).
        vec3 toSGamut3(vec3 c) {
            return vec3(
                dot(vec3(${LogProfiles.SG3_R_FROM_R}, ${LogProfiles.SG3_R_FROM_G}, ${LogProfiles.SG3_R_FROM_B}), c),
                dot(vec3(${LogProfiles.SG3_G_FROM_R}, ${LogProfiles.SG3_G_FROM_G}, ${LogProfiles.SG3_G_FROM_B}), c),
                dot(vec3(${LogProfiles.SG3_B_FROM_R}, ${LogProfiles.SG3_B_FROM_G}, ${LogProfiles.SG3_B_FROM_B}), c));
        }

        // BT.709 -> S-Gamut3.Cine primaries (linear light), D65->D65.
        vec3 toSGamut3Cine(vec3 c) {
            return vec3(
                dot(vec3(${LogProfiles.SG3C_R_FROM_R}, ${LogProfiles.SG3C_R_FROM_G}, ${LogProfiles.SG3C_R_FROM_B}), c),
                dot(vec3(${LogProfiles.SG3C_G_FROM_R}, ${LogProfiles.SG3C_G_FROM_G}, ${LogProfiles.SG3C_G_FROM_B}), c),
                dot(vec3(${LogProfiles.SG3C_B_FROM_R}, ${LogProfiles.SG3C_B_FROM_G}, ${LogProfiles.SG3C_B_FROM_B}), c));
        }

        // BT.709 -> ARRI Wide Gamut 3 primaries (linear light), D65->D65.
        vec3 toAwg3(vec3 c) {
            return vec3(
                dot(vec3(${LogProfiles.AWG3_R_FROM_R}, ${LogProfiles.AWG3_R_FROM_G}, ${LogProfiles.AWG3_R_FROM_B}), c),
                dot(vec3(${LogProfiles.AWG3_G_FROM_R}, ${LogProfiles.AWG3_G_FROM_G}, ${LogProfiles.AWG3_G_FROM_B}), c),
                dot(vec3(${LogProfiles.AWG3_B_FROM_R}, ${LogProfiles.AWG3_B_FROM_G}, ${LogProfiles.AWG3_B_FROM_B}), c));
        }

        // Defensive floor between the gamut matrix and a log OETF (LogProfiles.GAMUT_LINEAR_FLOOR):
        // keeps both curves' log segments defined at deep negatives. Deliberately NO upper clamp —
        // row-sum-1 matrices cannot push [0,1] input above 1 (see LogProfiles).
        vec3 gamutFloor(vec3 c) { return max(c, vec3(${LogProfiles.GAMUT_LINEAR_FLOOR})); }

        // Sony S-Log3 OETF (Sony technical summary; constants single-sourced from LogProfiles):
        //   y = (420 + log10((x + 0.01) / 0.19) * 261.5) / 1023      for x >= 0.01125,
        //   y = (x * (171.2102946929 - 95) / 0.01125 + 95) / 1023    for the linear segment below.
        // 18% grey encodes to 420/1023 ≈ 0.4106. The max() only guards the UNUSED mix lane: mix
        // cannot discard a NaN operand, and log of a negative would poison the selected result.
        vec3 slog3(vec3 x) {
            vec3 logY = (${LogProfiles.SLOG3_LOG_OFFSET_CODE}
                + log(max(x + ${LogProfiles.SLOG3_LIN_OFFSET}, 1e-6) / ${LogProfiles.SLOG3_GREY_PLUS_OFFSET})
                * ${LogProfiles.INV_LN10} * ${LogProfiles.SLOG3_LOG_SLOPE}) / ${LogProfiles.SLOG3_CODE_SCALE};
            vec3 linY = (x * (${LogProfiles.SLOG3_CUT_CODE} - ${LogProfiles.SLOG3_BLACK_CODE})
                / ${LogProfiles.SLOG3_CUT} + ${LogProfiles.SLOG3_BLACK_CODE}) / ${LogProfiles.SLOG3_CODE_SCALE};
            return mix(logY, linY, step(x, vec3(${LogProfiles.SLOG3_CUT})));
        }

        // ARRI LogC3 EI800 OETF (ALEXA Log C VFX parameter table; constants from LogProfiles):
        //   y = c * log10(a*x + b) + d   for x > 0.010591,
        //   y = e*x + f                  for the linear segment below.
        // 18% grey encodes to ≈ 0.3910. Same NaN guard rationale as slog3 above.
        vec3 logc3(vec3 x) {
            vec3 logY = ${LogProfiles.LOGC3_C}
                * log(max(${LogProfiles.LOGC3_A} * x + ${LogProfiles.LOGC3_B}, 1e-6))
                * ${LogProfiles.INV_LN10} + ${LogProfiles.LOGC3_D};
            vec3 linY = ${LogProfiles.LOGC3_E} * x + ${LogProfiles.LOGC3_F};
            return mix(logY, linY, step(x, vec3(${LogProfiles.LOGC3_CUT})));
        }

        // DORMANT: exact inverse of the OPPO O-Log2 OETF (white paper 2026-04 EN v1 —
        //   P = 0.08550479 * log2(R + 0.00964052) + 0.69336945 for R >= 0.006, parabolic toe
        //   below), for the Gamma Display Assist of a future native scene-referred stream. The
        // forward curve was removed with the user-facing O-Log2 option. Main segment inverts the
        // log; the shadow toe inverts the parabola (positive root). Segment boundary is DERIVED
        // from the toe constants (Kotlin interpolation, one source): P(R = OLOG2_TOE_MAX_R) via
        // the toe polynomial.
        vec3 olog2Inv(vec3 P) {
            vec3 logR = exp2((P - 0.69336945) / 0.08550479) - 0.00964052;
            vec3 toeR = sqrt(max(P, 0.0) / $OLOG2_TOE_SCALE) - $OLOG2_TOE_OFFSET;
            return mix(logR, toeR, step(P, vec3($OLOG2_INV_BOUNDARY)));
        }

        // Rec.2020 -> Rec.709 primaries (linear light), inverse of toRec2020 — the assist shows the
        // scene-referred O-Gamut stream as an ordinary 709/γ2.2 monitor image.
        vec3 toRec709(vec3 c) {
            return vec3(
                dot(vec3( 1.6605, -0.5876, -0.0728), c),
                dot(vec3(-0.1246,  1.1329, -0.0083), c),
                dot(vec3(-0.0182, -0.1006,  1.1187), c));
        }

        void main() {
            vec3 base = texture2D(uTexture, vTexCoord).rgb;
            vec3 color = base;
            // Exposure signal for the zebra / false-color overlays: ALWAYS the display-referred
            // rendition, never the encode curve. The log OETFs compress display white to ~0.57-0.60
            // (S-Log3 0.596, LogC3 0.571), which sits BELOW every zebra preset (0.70-1.00) and the
            // false-color near-clip bands — metered post-transfer, the overlays go dead the moment
            // a log profile is selected. Same domain rule the analysis readback already enforces
            // (analysisReadbackTransfer: the meter must not move with the log toggle). Only the
            // dormant native-log assist reassigns this: there the INPUT is the log stream and the
            // de-logged monitor image is the display-referred rendition.
            vec3 meter = base;

            if (uTransfer == 1) {
                // Simplified display-referred SDR-to-HLG mapping (ITU-R BT.2408-9 §5.1.3.4):
                // BT.1886 decode -> linear 709-to-2020 -> normalized reference-white scale and
                // per-channel inverse OOTF -> BT.2100 HLG OETF. SDR white maps to 75% HLG.
                vec3 sdrDisplayLight = pow(clamp(color, 0.0, 1.0), vec3(SDR_EOTF_GAMMA));
                vec3 bt2020DisplayLight = toRec2020(sdrDisplayLight);
                vec3 hlgSceneLight = pow(
                    max(bt2020DisplayLight * BT2408_HLG_SCALE, vec3(0.0)),
                    vec3(1.0 / HLG_SYSTEM_GAMMA));
                color = hlg(hlgSceneLight);
            } else if (uTransfer == 2) {
                // S-Log3 / S-Gamut3 from the display-referred SDR stream, same chain shape as the
                // HLG branch above: BT.1886 decode -> linear 709-to-S-Gamut3 -> defensive floor ->
                // S-Log3 OETF (see file docs; NOT scene-referred camera log).
                vec3 lin = pow(clamp(color, 0.0, 1.0), vec3(SDR_EOTF_GAMMA));
                color = slog3(gamutFloor(toSGamut3(lin)));
            } else if (uTransfer == 4) {
                // S-Log3 / S-Gamut3.Cine: identical chain, smaller grading-friendlier gamut.
                vec3 lin = pow(clamp(color, 0.0, 1.0), vec3(SDR_EOTF_GAMMA));
                color = slog3(gamutFloor(toSGamut3Cine(lin)));
            } else if (uTransfer == 5) {
                // ARRI LogC3 EI800 / ARRI Wide Gamut 3: identical chain.
                vec3 lin = pow(clamp(color, 0.0, 1.0), vec3(SDR_EOTF_GAMMA));
                color = logc3(gamutFloor(toAwg3(lin)));
            } else if (uTransfer == 3) {
                // DORMANT Gamma Display Assist: the incoming stream IS native O-Log2 (scene-
                // referred, O-Gamut) — a future CameraUnit path. De-log to linear, move to 709
                // primaries, γ2.2-encode for the monitor. The RECORDED stream is untouched — this
                // branch only ever runs on the preview.
                vec3 lin = max(olog2Inv(clamp(color, 0.0, 1.0)), vec3(0.0));
                color = pow(clamp(toRec709(lin), 0.0, 1.0), vec3(1.0 / 2.2));
                meter = color;
            }

            // False color: map exposure (luma) to IRE-style bands (display-referred, see meter).
            if (uFalseColor == 1) {
                float L = luma(clamp(meter, 0.0, 1.0));
                if (L < 0.03) color = vec3(0.15, 0.0, 0.5);
                else if (L < 0.10) color = vec3(0.0, 0.4, 0.85);
                else if (L < 0.42) color = vec3(0.32, 0.32, 0.32);
                else if (L < 0.52) color = vec3(0.0, 0.6, 0.1);
                else if (L < 0.78) color = vec3(0.62, 0.62, 0.62);
                else if (L < 0.93) color = vec3(0.95, 0.8, 0.0);
                else color = vec3(1.0, 0.0, 0.0);
            }

            // Focus peaking: highlight strong local gradients.
            if (uPeaking == 1) {
                float c  = luma(base);
                float rx = luma(texture2D(uTexture, vTexCoord + vec2(uTexel.x, 0.0)).rgb);
                float ry = luma(texture2D(uTexture, vTexCoord + vec2(0.0, uTexel.y)).rgb);
                float edge = abs(c - rx) + abs(c - ry);
                if (edge > uPeakThreshold) {
                    color = mix(color, uPeakColor, 0.85);
                }
            }

            // Zebra: diagonal stripes over near-clipped highlights. The stripe phase is derived from
            // the highp texture coordinate reconstructed to pixels (vTexCoord / uTexel) rather than
            // gl_FragCoord: on some Adreno drivers gl_FragCoord is only mediump, so on this 4K preview
            // its large window coords overflowed the mantissa and mod(...) degenerated — the stripes
            // never drew (QA: "zebra toggles on but shows nothing"). Reconstructed pixel coords stay
            // highp, so the modulo is exact and the stripes render. Luma is clamped so an out-of-range
            // sample can't slip past the threshold test. Metered on the display-referred signal
            // (meter), so the thresholds keep their IRE meaning under the log profiles and the
            // stripes read the SCENE, not the false-color band map.
            if (uZebra == 1) {
                if (luma(clamp(meter, 0.0, 1.0)) > uZebraThreshold) {
                    float px = vTexCoord.x / max(uTexel.x, 1e-6);
                    float py = vTexCoord.y / max(uTexel.y, 1e-6);
                    float stripe = mod(px + py, 24.0);
                    if (stripe < 12.0) color = vec3(0.0);
                }
            }

            gl_FragColor = vec4(color, 1.0);
        }
    """
}
