package com.hletrd.findx9tele.gl

/**
 * GLSL ES 2.0 shaders for the flip/preview pipeline.
 *
 * The vertex shader rotates the full-screen quad (afocal 180° + sensor orientation, baked into
 * uMvp) and transforms external-texture coordinates via the SurfaceTexture matrix (uTexMatrix).
 *
 * The fragment shader samples the camera's external OES texture and optionally applies:
 *  - a transfer OETF for the video encoder path (HLG, or the official OPPO O-Log2 curve),
 *  - focus peaking (edge highlight) and zebra (clipping stripes) for the preview path.
 *
 * NOTE: the camera preview signal is display-referred (already non-linear). The LOG path
 * linearizes it (γ2.2 approximation of the ISP's SDR output), converts Rec.709→BT.2020 primaries
 * (O-Gamut), and applies the official O-Log2 OETF so mid-grey lands on OPPO's published anchor and
 * footage grades with OPPO's O-Log2 LUTs. It is still an approximation because the preview stream is
 * already display-referred, so there is no above-white highlight headroom — the SDR tone mapping
 * bounds the dynamic range. Verify on device.
 */
object Shaders {

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

    const val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES uTexture;
        uniform int uTransfer;   // 0 = display (preview), 1 = HLG, 2 = LOG
        uniform int uPeaking;    // 0/1  (preview only)
        uniform float uPeakThreshold; // edge magnitude above which peaking paints
        uniform vec3 uPeakColor;      // peaking highlight color
        uniform int uZebra;      // 0/1  (preview only)
        uniform float uZebraThreshold; // luma above which zebra stripes draw
        uniform int uFalseColor; // 0/1  (preview only) exposure false-color map
        uniform vec2 uTexel;     // 1/width, 1/height for neighbor sampling
        varying vec2 vTexCoord;

        const vec3 LUMA = vec3(0.2627, 0.6780, 0.0593); // Rec.2020 luma weights

        float luma(vec3 c) { return dot(c, LUMA); }

        // HLG OETF (approx; input treated as pseudo-linear [0,1]).
        vec3 hlg(vec3 x) {
            vec3 lo = sqrt(3.0 * clamp(x, 0.0, 1.0));
            float a = 0.17883277, b = 0.28466892, c = 0.55991073;
            vec3 hi = a * log(max(12.0 * x - b, 1e-4)) + c;
            return mix(hi, lo, step(x, vec3(1.0 / 12.0)));
        }

        // Rec.709 -> Rec.2020 primaries (linear light), for the O-Log2 encode (O-Gamut = BT.2020).
        vec3 toRec2020(vec3 c) {
            return vec3(
                dot(vec3(0.6274, 0.3293, 0.0433), c),
                dot(vec3(0.0691, 0.9195, 0.0114), c),
                dot(vec3(0.0164, 0.0880, 0.8956), c));
        }

        // OPPO O-Log2 OETF, constants from the official white paper (2026-04, EN v1):
        //   P = 0.08550479 * log2(R + 0.00964052) + 0.69336945   for R >= 0.006,
        //   P = 47.28711236 * (R - (-0.05641088))^2              for the shadow toe below.
        // R is scene reflectance (1.0 = diffuse white); 18% grey encodes to the official
        // 0.4868 (499/1023) anchor, so OPPO's published O-Log2 LUTs restore it correctly.
        vec3 olog2(vec3 R) {
            vec3 logP = 0.08550479 * log2(max(R + 0.00964052, 1e-5)) + 0.69336945;
            vec3 toe = 47.28711236 * (R + 0.05641088) * (R + 0.05641088);
            return clamp(mix(logP, toe, step(R, vec3(0.006))), 0.0, 1.0);
        }

        void main() {
            vec3 base = texture2D(uTexture, vTexCoord).rgb;
            vec3 color = base;

            if (uTransfer == 1) {
                color = hlg(color);
            } else if (uTransfer == 2) {
                // O-Log2 from the display-referred SDR stream: linearize (the ISP's SDR output is
                // ~gamma 2.2), move to O-Gamut primaries, then the official OETF (see file docs).
                vec3 lin = pow(clamp(color, 0.0, 1.0), vec3(2.2));
                color = olog2(toRec2020(lin));
            }

            // False color: map exposure (luma) to IRE-style bands.
            if (uFalseColor == 1) {
                float L = luma(color);
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
            // sample can't slip past the threshold test.
            if (uZebra == 1) {
                if (luma(clamp(color, 0.0, 1.0)) > uZebraThreshold) {
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
