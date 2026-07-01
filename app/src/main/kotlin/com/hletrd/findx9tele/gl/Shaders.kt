package com.hletrd.findx9tele.gl

/**
 * GLSL ES 2.0 shaders for the flip/preview pipeline.
 *
 * The vertex shader rotates the full-screen quad (afocal 180° + sensor orientation, baked into
 * uMvp) and transforms external-texture coordinates via the SurfaceTexture matrix (uTexMatrix).
 *
 * The fragment shader samples the camera's external OES texture and optionally applies:
 *  - a transfer OETF for the video encoder path (HLG or a flat Log-like curve),
 *  - focus peaking (edge highlight) and zebra (clipping stripes) for the preview path.
 *
 * NOTE: the camera preview signal is display-referred (already non-linear); applying HLG/Log here
 * is a look approximation for grading, not a colorimetric scene-linear transform. Verify on device.
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
        uniform int uZebra;      // 0/1  (preview only)
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

        // Flat Cineon-like log curve for grading headroom.
        vec3 logc(vec3 x) {
            return log(1.0 + 90.0 * clamp(x, 0.0, 1.0)) / log(91.0);
        }

        void main() {
            vec3 color = texture2D(uTexture, vTexCoord).rgb;

            if (uTransfer == 1) {
                color = hlg(color);
            } else if (uTransfer == 2) {
                color = logc(color);
            }

            // Focus peaking: highlight strong local gradients.
            if (uPeaking == 1) {
                float c  = luma(texture2D(uTexture, vTexCoord).rgb);
                float rx = luma(texture2D(uTexture, vTexCoord + vec2(uTexel.x, 0.0)).rgb);
                float ry = luma(texture2D(uTexture, vTexCoord + vec2(0.0, uTexel.y)).rgb);
                float edge = abs(c - rx) + abs(c - ry);
                if (edge > 0.06) {
                    color = mix(color, vec3(1.0, 0.1, 0.7), 0.85);
                }
            }

            // Zebra: diagonal stripes over near-clipped highlights.
            if (uZebra == 1) {
                if (luma(color) > 0.95) {
                    float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 16.0);
                    if (stripe < 8.0) color = vec3(0.0);
                }
            }

            gl_FragColor = vec4(color, 1.0);
        }
    """
}
