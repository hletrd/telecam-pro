package me.hletrd.telecampro.gl

/** One production authority shared by GLSL declarations, GLES lookup, and the host gate. */
internal object ShaderBindings {
    const val A_POSITION = "aPosition"
    const val A_TEX_COORD = "aTexCoord"
    const val U_MVP = "uMvp"
    const val U_TEX_MATRIX = "uTexMatrix"
    const val U_TEXTURE = "uTexture"
    const val U_TRANSFER = "uTransfer"
    const val U_SOURCE_HLG = "uSourceHlg"
    const val U_SOURCE_LOG = "uSourceLog"
    const val U_SOURCE_LOG_HEADROOM = "uSourceLogHeadroom"
    const val U_PEAKING = "uPeaking"
    const val U_PEAK_THRESHOLD = "uPeakThreshold"
    const val U_PEAK_COLOR = "uPeakColor"
    const val U_ZEBRA = "uZebra"
    const val U_ZEBRA_THRESHOLD = "uZebraThreshold"
    const val U_FALSE_COLOR = "uFalseColor"
    const val U_TEXEL = "uTexel"
    const val U_DIGITAL_GAIN = "uDigitalGain"

    val attributes = setOf(A_POSITION, A_TEX_COORD)
    val uniforms = setOf(
        U_MVP,
        U_TEX_MATRIX,
        U_TEXTURE,
        U_TRANSFER,
        U_SOURCE_HLG,
        U_SOURCE_LOG,
        U_SOURCE_LOG_HEADROOM,
        U_PEAKING,
        U_PEAK_THRESHOLD,
        U_PEAK_COLOR,
        U_ZEBRA,
        U_ZEBRA_THRESHOLD,
        U_FALSE_COLOR,
        U_TEXEL,
        U_DIGITAL_GAIN,
    )
    val requiredInterface = attributes + uniforms
}

/**
 * GLSL ES 2.0 shaders for the flip/preview pipeline.
 *
 * The vertex shader rotates the full-screen quad (afocal 180° + sensor orientation, baked into
 * uMvp) and transforms external-texture coordinates via the SurfaceTexture matrix (uTexMatrix).
 *
 * The fragment shader samples the camera's external OES texture and optionally applies:
 *  - an SDR-to-HLG mapping or one of the standard log profiles (S-Log3/S-Gamut3,
 *    S-Log3/S-Gamut3.Cine, ARRI LogC3 EI800/AWG3, and the per-device phone-maker logs OPPO
 *    O-Log2 / OPPO O-Log / Xiaomi Mi-Log, all BT.2020) for the video encoder path,
 *  - focus peaking (edge highlight) and zebra (clipping stripes) for the preview path.
 *
 * NOTE: the camera signal is non-linear, in one of three accepted-session encodings. A NATIVE-LOG
 * session's stream is S-Log3 written by the ISP's own programmed tone curve (NativeLogCurve.kt): it
 * is decoded at SAMPLE time (`sampleSource`: exact S-Log3 inverse -> clamp -> BT.1886-encode), so
 * `base` is an un-tone-mapped display-referred rendition and every consumer below — display, Gamma
 * Display Assist, dgain, meter/zebra/false-color, peaking and the transfer branches' sourceLinear —
 * runs unchanged, now on the ISP's linear signal rather than its S-curve. Under a Log EI offset
 * (`uSourceLogHeadroom` k > 1) the LOG encode branches instead read `nativeSceneLinear`: the same
 * decode, UNclamped up to k, so the highlights the underexposed wire kept are encoded rather than
 * re-clipped at `base`'s 1.0; `base` itself (and so display, meter, zebra, false color, peaking,
 * Gamma Display Assist, the AE readback) stays the clamped normal-brightness rendition. At k = 1
 * the branches take the historical sourceLinear path exactly. Otherwise the stream is
 * display-referred (already tone-mapped): accepted HLG10 input is inverse-HLG decoded; standard
 * input is BT.1886 decoded. HLG then follows the simplified
 * ITU-R BT.2408-9 display-referred mapping: linear BT.709→BT.2020,
 * reference-white/inverse-OOTF adjustment, then the BT.2100 HLG OETF. Each log profile follows the
 * same chain shape: BT.1886 decode, linear BT.709→target-gamut 3×3 matrix, defensive lower clamp,
 * then the profile's OETF — constants single-sourced from [LogProfiles]. The phone-maker logs
 * (offered only on their maker's phones, DeviceProfile.nativeLogFlavor) are codes 6 (OPPO O-Log2,
 * the official two-segment white-paper curve), 7 (OPPO O-Log V1 under OPPO's DCTL exposure
 * convention) and 8 (Xiaomi Mi-Log), each BT.709→BT.2020 then its OETF; the retired de-log
 * inverse stays removed and shader code 3 stays vacant. Gamma Display Assist bypasses every log curve on preview only while
 * encoder output retains the curve. No path can recover
 * above-white highlights removed by the ISP's SDR tone mapping. Verify on device.
 */
object Shaders {

    val VERTEX = """
        uniform mat4 ${ShaderBindings.U_MVP};
        uniform mat4 ${ShaderBindings.U_TEX_MATRIX};
        attribute vec4 ${ShaderBindings.A_POSITION};
        attribute vec4 ${ShaderBindings.A_TEX_COORD};
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMvp * aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    // Plain val because the GLSL string interpolates Kotlin reference constants.
    val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES ${ShaderBindings.U_TEXTURE};
        // 1 when the CAMERA stream is a 10-bit HLG-encoded buffer (HLG10 / DV session).
        uniform int ${ShaderBindings.U_SOURCE_HLG};
        // 1 when the CAMERA stream is S-Log3 (709 primaries) encoded by the ISP's programmed native
        // tone curve. Never together with uSourceHlg: native log is the 8-bit session by design.
        uniform int ${ShaderBindings.U_SOURCE_LOG};
        // Log EI offset (NativeLogCurve.kt): the ACHIEVED underexposure factor k >= 1 the ISP curve
        // lifted back (slog3(k * x)). The decode slog3Inverse(code) is already scene-linear k * x —
        // the inverse needs no k — so k is the ceiling of that decode's domain [0, k]. 1 = no offset.
        uniform float ${ShaderBindings.U_SOURCE_LOG_HEADROOM};
        uniform int ${ShaderBindings.U_TRANSFER};   // 0 = display, 1 = HLG, 2 = S-Log3/S-Gamut3, 4 = S-Log3/S-Gamut3.Cine,
                                 // 5 = LogC3/AWG3, 6 = O-Log2, 7 = O-Log (V1), 8 = Mi-Log (all BT.2020),
                                 // 3 = vacant (retired de-log branch)
        uniform int ${ShaderBindings.U_PEAKING};    // 0/1  (preview only)
        uniform float ${ShaderBindings.U_PEAK_THRESHOLD}; // edge magnitude above which peaking paints
        uniform vec3 ${ShaderBindings.U_PEAK_COLOR};      // peaking highlight color
        uniform int ${ShaderBindings.U_ZEBRA};      // 0/1  (preview only)
        uniform float ${ShaderBindings.U_ZEBRA_THRESHOLD}; // luma above which zebra stripes draw
        uniform int ${ShaderBindings.U_FALSE_COLOR}; // 0/1  (preview only) exposure false-color map
        uniform vec2 ${ShaderBindings.U_TEXEL};     // 1/width, 1/height for neighbor sampling
        uniform float ${ShaderBindings.U_DIGITAL_GAIN}; // >=1 (preview only): brightness-simulation linear gain for the
                                    // exposure shortfall the fluidity-capped repeating request
                                    // cannot carry (see previewExposureTrade)
        varying vec2 vTexCoord;

        const vec3 LUMA = vec3(0.2627, 0.6780, 0.0593); // Rec.2020 luma weights
        const float SDR_EOTF_GAMMA = ${SdrToHlgMapping.SDR_EOTF_GAMMA};
        const float BT2408_HLG_SCALE = ${SdrToHlgMapping.NORMALIZED_DISPLAY_LIGHT_SCALE};
        const float HLG_SYSTEM_GAMMA = ${SdrToHlgMapping.HLG_SYSTEM_GAMMA};

        float luma(vec3 c) { return dot(c, LUMA); }

        // Preview brightness simulation (cycle 8): the AE-OFF repeating request is capped at the
        // 1/15 s fluidity ceiling and the residual exposure shortfall is applied HERE, in linear
        // light (BT.1886 decode -> multiply -> re-encode), BEFORE `base` forms — so the display,
        // zebra, and false-color all read the STILL's simulated brightness. Encoder and analysis
        // draws always pass 1.0: files never contain the boost, and the scope/AE readback stays
        // sensor-true (its CPU-side histogram applies the matching LUT exactly once instead).
        // HONESTY: boosted linear values past 1.0 CLIP at white — the simulation cannot show the
        // highlight roll-off a true long exposure would produce (same class of note as the HLG
        // mapping: nothing above the ISP's SDR white exists to recover).
        vec3 dgain(vec3 c) {
            if (uDigitalGain <= 1.001) return c;
            vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(SDR_EOTF_GAMMA)) * uDigitalGain;
            return pow(min(lin, vec3(1.0)), vec3(1.0 / SDR_EOTF_GAMMA));
        }

        // Exact S-Log3 inverse (code value -> linear reflection), the CPU twin of
        // LogProfiles.slog3Inverse with the same constants; pinned against it and against the forward
        // slog3() below by NativeLogCurveTest. log10 inverse = exp(ln10 * v), written as / INV_LN10.
        vec3 slog3Inverse(vec3 y) {
            vec3 logX = exp((y * ${LogProfiles.SLOG3_CODE_SCALE} - ${LogProfiles.SLOG3_LOG_OFFSET_CODE})
                / ${LogProfiles.SLOG3_LOG_SLOPE} / ${LogProfiles.INV_LN10})
                * ${LogProfiles.SLOG3_GREY_PLUS_OFFSET} - ${LogProfiles.SLOG3_LIN_OFFSET};
            vec3 linX = (y * ${LogProfiles.SLOG3_CODE_SCALE} - ${LogProfiles.SLOG3_BLACK_CODE})
                * ${LogProfiles.SLOG3_CUT} / (${LogProfiles.SLOG3_CUT_CODE} - ${LogProfiles.SLOG3_BLACK_CODE});
            return mix(logX, linX, step(y, vec3(${LogProfiles.SLOG3_CUT_CODE} / ${LogProfiles.SLOG3_CODE_SCALE})));
        }

        // One camera sample in the DISPLAY-REFERRED domain every consumer below was written for. On a
        // native-log session the ISP wrote S-Log3: decode it to the ISP's linear signal, clamp to the
        // sensor-clip range the curve was programmed over (1.0 = clip), and BT.1886-encode it, so the
        // later sourceLinear() BT.1886 decode returns exactly that linear value. Peaking neighbours
        // come through here too, or a decoded centre against raw log neighbours would fabricate edges.
        vec3 sampleSource(vec2 uv) {
            vec3 c = texture2D(uTexture, uv).rgb;
            if (uSourceLog == 0) return c;
            vec3 lin = clamp(slog3Inverse(c) * uSourceLogHeadroom, 0.0, 1.0);
            return pow(lin, vec3(1.0 / SDR_EOTF_GAMMA));
        }

        // Log EI offset: the native-log sample as SCENE-linear light for the LOG encode branches,
        // NOT clamped at 1.0 — only at the headroom k the wire actually bought — so detail above the
        // display white survives into the file. The ISP curve stays the PLAIN S-Log3 (k is never
        // programmed into it: PMA110 measured 2026-10-15 — a k-scaled TONEMAP_CURVE, confirmed on
        // the wire via dumpsys "Last request sent", left mid-tones ~1.7 stops dark under −2 EV, as
        // if unscaled), so the k lift is applied HERE, after the exact S-Log3 decode, and `base`
        // (sampleSource) takes the same lift before its display clamp. Brightness simulation follows dgain's rule (linear
        // multiply, clip at the ceiling); encoder draws pass gain 1 so files never carry it.
        vec3 nativeSceneLinear(vec2 uv) {
            vec3 lin = clamp(
                slog3Inverse(texture2D(uTexture, uv).rgb) * uSourceLogHeadroom, 0.0, uSourceLogHeadroom);
            if (uDigitalGain > 1.001) lin = min(lin * uDigitalGain, vec3(uSourceLogHeadroom));
            return lin;
        }

        // BT.2100 HLG OETF. Input is normalized scene light from the BT.2408 mapping below.
        vec3 hlg(vec3 x) {
            vec3 lo = sqrt(3.0 * clamp(x, 0.0, 1.0));
            float a = ${SdrToHlgMapping.HLG_A}, b = ${SdrToHlgMapping.HLG_B}, c = ${SdrToHlgMapping.HLG_C};
            vec3 hi = a * log(max(12.0 * x - b, 1e-4)) + c;
            return mix(hi, lo, step(x, vec3(1.0 / 12.0)));
        }

        // SOURCE linearisation — the one place that knows how the CAMERA encoded what we sampled.
        //
        // GL_TEXTURE_EXTERNAL_OES returns values in the BUFFER's own encoding; it does not
        // linearise. The 8-bit stream is display-referred 709, so BT.1886 is right there. When the
        // session configures HLG10 the PREVIEW OutputConfiguration carries that profile too, so the
        // same sampler yields HLG — and decoding that with a 2.4 gamma expands highlights instead of
        // unrolling the HLG curve. The log OETF then re-compresses, so the file looks plausible
        // while being wrong log: two errors partially cancelling.
        vec3 sourceLinear(vec3 c) {
            vec3 s = clamp(c, 0.0, 1.0);
            if (uSourceHlg == 0) return pow(s, vec3(SDR_EOTF_GAMMA));
            // BT.2100 inverse HLG OETF -> normalized scene light. Exact inverse of hlg() above,
            // sharing its constants so the pair cannot drift (pinned by SourceLinearHlgTest).
            float a = ${SdrToHlgMapping.HLG_A}, b = ${SdrToHlgMapping.HLG_B}, c2 = ${SdrToHlgMapping.HLG_C};
            vec3 lo = s * s / 3.0;
            vec3 hi = (exp((s - c2) / a) + b) / 12.0;
            vec3 sceneLight = mix(hi, lo, step(s, vec3(0.5)));
            // ...then back onto the DISPLAY-LIGHT scale every branch below was written for, where
            // diffuse white is 1.0. HLG scene light puts diffuse white near BT2408_HLG_SCALE
            // (~0.25), so handing it over raw under-drives the log OETFs — device-seen as an image
            // that changed but still was not flat. This is the exact inverse of the forward HLG
            // branch's `pow(displayLight * BT2408_HLG_SCALE, 1/HLG_SYSTEM_GAMMA)`, reusing both
            // constants so forward and inverse cannot drift apart.
            return pow(sceneLight, vec3(HLG_SYSTEM_GAMMA)) / BT2408_HLG_SCALE;
        }

        // Linear input of the LOG encode branches (codes 2/4/5/6/7/8). Without an EI offset — and on
        // every non-native stream — this IS the historical sourceLinear(base) path, byte for byte;
        // with one, the unclamped native scene-linear above (`base` keeps its clamp for display).
        vec3 logInputLinear(vec3 color) {
            if (uSourceLog == 0 || uSourceLogHeadroom <= 1.0) return sourceLinear(color);
            return nativeSceneLinear(vTexCoord);
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
        // row-sum-1 positive matrices cannot push input above its own max (1, or the EI headroom k).
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

        // The Apple-Log-form OETF O-Log2 and Mi-Log share (LogProfiles.toeLog2Oetf):
        //   y = gamma * log2(x + beta) + delta   for x >= rt,
        //   y = c2 * (x - r0)^2                  for r0 <= x < rt (quadratic toe, C1 join at rt),
        //   y = 0                                below r0.
        // max(x - r0, 0) makes the toe lane 0 below r0; the log lane's max() is the same NaN guard
        // as slog3's (mix cannot discard a NaN from the unused lane).
        vec3 toeLog2(vec3 x, float r0, float rt, float c2, float beta, float gamma, float delta) {
            vec3 logY = gamma * log2(max(x + beta, 1e-6)) + delta;
            vec3 q = max(x - r0, vec3(0.0));
            vec3 toeY = c2 * q * q;
            return mix(logY, toeY, 1.0 - step(vec3(rt), x));
        }

        // OPPO O-Log2 (official white paper 2026-04-17; Rt = 0.01, NOT the printed 0.006 typo — see
        // LogProfiles.OLOG2_RT). 18% grey -> ≈ 0.4883, 1.0 -> ≈ 0.6946, 0 -> ≈ 0.1505.
        vec3 olog2(vec3 x) {
            return toeLog2(x, ${LogProfiles.OLOG2_R0}, ${LogProfiles.OLOG2_RT}, ${LogProfiles.OLOG2_C2},
                ${LogProfiles.OLOG2_B}, ${LogProfiles.OLOG2_A}, ${LogProfiles.OLOG2_C});
        }

        // OPPO O-Log V1 (official white paper): P = 0.139 * ln(R + 0.019) + 0.614 on R in [0, 16],
        // with R = scene-linear / OLOG_DCTL_SCALE so OPPO's own V1 DCTL decodes it back exactly
        // (18% grey -> ≈ 0.4900). The [0, 16] clamp is the paper's domain (R = 16 ≈ 7.37 scene-linear,
        // code ≈ 0.9996): the one profile a +3 EI headroom can clip at the top.
        vec3 olog(vec3 x) {
            vec3 r = clamp(x / ${LogProfiles.OLOG_DCTL_SCALE}, 0.0, ${LogProfiles.OLOG_R_MAX});
            return ${LogProfiles.OLOG_GAMMA} * log(r + ${LogProfiles.OLOG_BETA}) + ${LogProfiles.OLOG_DELTA};
        }

        // Xiaomi Mi-Log (Xiaomi Log Profile White Paper, Dec 2024): Apple-Log form, Xiaomi constants.
        // 18% grey -> 0.45346.
        vec3 milog(vec3 x) {
            return toeLog2(x, ${LogProfiles.MILOG_R0}, ${LogProfiles.MILOG_RT}, ${LogProfiles.MILOG_C2},
                ${LogProfiles.MILOG_BETA}, ${LogProfiles.MILOG_GAMMA}, ${LogProfiles.MILOG_DELTA});
        }

        void main() {
            vec3 base = dgain(sampleSource(vTexCoord));
            vec3 color = base;
            // Exposure signal for the zebra / false-color overlays: ALWAYS the display-referred
            // rendition, never the encode curve. The log OETFs compress display white to ~0.57-0.60
            // (S-Log3 0.596, LogC3 0.571), which sits BELOW every zebra preset (0.70-1.00) and the
            // false-color near-clip bands — metered post-transfer, the overlays go dead the moment
            // a log profile is selected. Same domain rule the analysis readback already enforces
            // (analysisReadbackTransfer: the meter must not move with the log toggle).
            vec3 meter = base;

            if (uTransfer == 1) {
                // Simplified display-referred SDR-to-HLG mapping (ITU-R BT.2408-9 §5.1.3.4):
                // BT.1886 decode -> linear 709-to-2020 -> normalized reference-white scale and
                // per-channel inverse OOTF -> BT.2100 HLG OETF. SDR white maps to 75% HLG.
                vec3 sdrDisplayLight = sourceLinear(color);
                vec3 bt2020DisplayLight = toRec2020(sdrDisplayLight);
                vec3 hlgSceneLight = pow(
                    max(bt2020DisplayLight * BT2408_HLG_SCALE, vec3(0.0)),
                    vec3(1.0 / HLG_SYSTEM_GAMMA));
                color = hlg(hlgSceneLight);
            } else if (uTransfer == 2) {
                // S-Log3 / S-Gamut3 from the display-referred SDR stream, same chain shape as the
                // HLG branch above: BT.1886 decode -> linear 709-to-S-Gamut3 -> defensive floor ->
                // S-Log3 OETF (see file docs; NOT scene-referred camera log).
                vec3 lin = logInputLinear(color);
                color = slog3(gamutFloor(toSGamut3(lin)));
            } else if (uTransfer == 4) {
                // S-Log3 / S-Gamut3.Cine: identical chain, smaller grading-friendlier gamut.
                vec3 lin = logInputLinear(color);
                color = slog3(gamutFloor(toSGamut3Cine(lin)));
            } else if (uTransfer == 5) {
                // ARRI LogC3 EI800 / ARRI Wide Gamut 3: identical chain.
                vec3 lin = logInputLinear(color);
                color = logc3(gamutFloor(toAwg3(lin)));
            } else if (uTransfer == 6) {
                // OPPO O-Log2 / BT.2020: same chain shape, reusing the HLG branch's linear
                // 709-to-2020 matrix and the shared defensive floor (inert for 6/7/8: the toe is
                // defined far below it, and O-Log clamps to its own domain).
                vec3 lin = logInputLinear(color);
                color = olog2(gamutFloor(toRec2020(lin)));
            } else if (uTransfer == 7) {
                // OPPO O-Log (V1) / BT.2020.
                vec3 lin = logInputLinear(color);
                color = olog(gamutFloor(toRec2020(lin)));
            } else if (uTransfer == 8) {
                // Xiaomi Mi-Log / BT.2020.
                vec3 lin = logInputLinear(color);
                color = milog(gamutFloor(toRec2020(lin)));
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

            // Focus peaking: highlight strong local gradients. Neighbors pass through the SAME
            // dgain as `base`: mixing a boosted center with raw neighbors would fabricate a false
            // gradient everywhere, and boosted-vs-boosted keeps the edge magnitude scaling with
            // what the user actually sees (a dark true stream would otherwise under-fire the
            // threshold exactly in the low light the simulation exists for).
            if (uPeaking == 1) {
                float c  = luma(base);
                float rx = luma(dgain(sampleSource(vTexCoord + vec2(uTexel.x, 0.0))));
                float ry = luma(dgain(sampleSource(vTexCoord + vec2(0.0, uTexel.y))));
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
