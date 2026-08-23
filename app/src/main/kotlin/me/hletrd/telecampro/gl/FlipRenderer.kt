package me.hletrd.telecampro.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import me.hletrd.telecampro.camera.ColorTransfer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the camera external-OES texture to whatever GL surface is current.
 *
 * Two transforms are combined:
 *  - texture coordinates are rotated by [setRotationDegrees]'s afocal-180-only angle about their
 *    center, then multiplied by the SurfaceTexture matrix (which already carries the sensor
 *    orientation — see the field comment below), so the *content* is un-inverted;
 *  - the quad geometry is scaled (center-crop "cover") so the content aspect fills the target
 *    without distortion, for whatever target (preview view or encoder) is being drawn.
 */
class FlipRenderer {
    private var program = 0
    private var oesTextureId = 0

    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var uTransfer = 0
    private var uSourceHlg = 0
    private var uPeaking = 0
    private var uPeakThreshold = 0
    private var uPeakColor = 0
    private var uZebra = 0
    private var uZebraThreshold = 0
    private var uFalseColor = 0
    private var uTexel = 0
    private var uDigitalGain = 0

    // Static quad geometry lives in ONE VBO uploaded in [init] (perf review #15): the client-side
    // glVertexAttribPointer form re-pinned and re-copied both arrays through JNI on every draw
    // (~96 draws/s at recording+loupe+finder). Layout: position quad at byte 0, plain texcoords at
    // [TEX_COORD_OFFSET_BYTES], mirrored-x texcoords at [TEX_COORD_MIRRORED_OFFSET_BYTES].
    // Selfie preview mirror: the attribute texcoords enter the rot chain BEFORE the SurfaceTexture
    // matrix, and attr x is display x, so inverting attr x here mirrors the DISPLAYED image
    // horizontally regardless of the sensor orientation the stMatrix bakes in (no sign guesswork
    // per sensor). The x→1−x inversion lives in the pure, tested [texCoordQuad].
    private var quadVbo = 0

    private val mvp = FloatArray(16)
    private val rot = FloatArray(16)
    private val texMatrix = FloatArray(16)
    // Reused (ex, ey) receiver for the per-draw cover-scale (PERF4-4; GL-thread confined).
    private val coverScratch = FloatArray(2)

    private var previewW = 1
    private var previewH = 1
    private var rotationDeg = 0
    private var sourceHlg = false
    // Rotation the camera SurfaceTexture transform already bakes into the sampled image (the sensor
    // orientation). It is NOT re-applied to texcoords (stMatrix does that), but it DOES decide the
    // displayed aspect: a ~90° sensor rotation swaps the shown width/height. Combined with rotationDeg
    // to pick the preview aspect. See CameraEngine.previewRotationDegrees.
    private var sensorOrientationDeg = 0

    /** Compiles the program and allocates the external texture. Must run with an EGL context current. */
    fun init(): Int {
        program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uTransfer = GLES20.glGetUniformLocation(program, "uTransfer")
        uSourceHlg = GLES20.glGetUniformLocation(program, "uSourceHlg")
        uPeaking = GLES20.glGetUniformLocation(program, "uPeaking")
        uPeakThreshold = GLES20.glGetUniformLocation(program, "uPeakThreshold")
        uPeakColor = GLES20.glGetUniformLocation(program, "uPeakColor")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uZebraThreshold = GLES20.glGetUniformLocation(program, "uZebraThreshold")
        uFalseColor = GLES20.glGetUniformLocation(program, "uFalseColor")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uDigitalGain = GLES20.glGetUniformLocation(program, "uDigitalGain")

        // Fresh VBO per GL generation (same replay discipline as RendererConfigStore: init() must
        // fully re-seed everything a replacement context needs; release() deletes it).
        val vboIds = IntArray(1)
        GLES20.glGenBuffers(1, vboIds, 0)
        quadVbo = vboIds[0]
        val staging = floatBuffer(
            // x, y  (triangle strip)          | tex u,v (plain)      | tex u,v (mirrored x)
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f) +
                texCoordQuad(mirrorX = false) +
                texCoordQuad(mirrorX = true),
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            staging.capacity() * 4,
            staging,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return oesTextureId
    }

    fun setPreviewSize(width: Int, height: Int) {
        previewW = width.coerceAtLeast(1)
        previewH = height.coerceAtLeast(1)
    }

    /** How the accepted camera session encoded its buffers; selects the shader source decode. */
    fun setSourceHlg(enabled: Boolean) {
        if (sourceHlg != enabled && me.hletrd.telecampro.BuildConfig.DEBUG) {
            android.util.Log.i("FlipRenderer", "sourceHlg -> $enabled (uniformLoc=$uSourceHlg)")
        }
        sourceHlg = enabled
    }

    /** Extra CW rotation applied to texcoords on top of the SurfaceTexture transform (afocal flip). */
    fun setRotationDegrees(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
    }

    /**
     * The shared content rotation state, so a caller can build a per-call [draw] `rotationOverrideDeg`
     * RELATIVE to it (the preview/finder draws add the window term on top). Reading it is the only
     * way to extend the rotation for one draw role without mutating state every role sees.
     */
    fun contentRotationDegrees(): Int = rotationDeg

    /** Sensor orientation the SurfaceTexture transform already applies; used only for aspect choice. */
    fun setSensorOrientation(deg: Int) {
        sensorOrientationDeg = ((deg % 360) + 360) % 360
    }

    fun draw(
        stMatrix: FloatArray,
        targetWidth: Int,
        targetHeight: Int,
        transfer: ColorTransfer?,
        peaking: Boolean,
        zebra: Boolean,
        falseColor: Boolean = false,
        stabShiftX: Float = 0f,
        stabShiftY: Float = 0f,
        stabRollDeg: Float = 0f,
        crop: Float = 0f,
        // Texcoord point the crop-zoom centers on (0.5,0.5 = frame center). The movable focus loupe
        // sets this to the tapped point so punch-in magnifies an off-center subject.
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        // Adjustable focus-peaking (edge threshold + highlight color) and zebra (clipping threshold).
        peakThreshold: Float = 0.06f,
        peakR: Float = 1f,
        peakG: Float = 0.1f,
        peakB: Float = 0.7f,
        zebraThreshold: Float = 0.95f,
        // Preview-only zoom compensation (requested ÷ HAL-applied): every setRepeatingRequest stalls
        // this HAL's stream ~180 ms, so the LIVE zoom renders here as a texture crop while the HAL
        // catches up at a throttled pace. ≥1 (can't sample beyond the frame); 1 = no-op.
        zoomComp: Float = 1f,
        // Preview-only brightness simulation (cycle 8): linear-light gain for the exposure
        // shortfall the fluidity-capped repeating request cannot carry. Encoder/analysis draw
        // roles keep the default 1 so files and the scope/AE readback never contain the boost.
        digitalGain: Float = 1f,
        // Viewport origin for sub-rect draws (the TELE finder PIP); callers scissor around the draw
        // because the internal glClear is framebuffer-wide otherwise.
        viewportX: Int = 0,
        viewportY: Int = 0,
        // Selfie mirror axis (route state, not an assist). WHICH draws set it derives from
        // FrontMirrorConvention resolves profile truth: PMA110 passes its pre-mirrored preview as-is
        // and un-mirrors encoder/analysis; GENERIC adds only the selfie preview mirror. Callers pass
        // the derived role, never a literal.
        mirrorX: Boolean = false,
        // Per-draw content-rotation override, in place of the shared [rotationDeg] field.
        //
        // Exists for ONE caller: the TELE corner overview, which the operator wants UPRIGHT while
        // the magnified main view carries the afocal 180° (user-specified 2026-07-28). Rotation is
        // otherwise renderer STATE precisely so every draw role agrees, so this is deliberately an
        // explicit opt-in per call rather than a settable field — null keeps the shared value, and
        // the encoder/analysis/preview roles never pass it.
        rotationOverrideDeg: Int? = null,
    ) {
        val contentRotationDeg = rotationOverrideDeg ?: rotationDeg
        GLES20.glViewport(viewportX, viewportY, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Center-crop "cover" scale so the content aspect fills the target without distortion.
        // Allocation-free form (PERF4-4): draw runs 1-4x per frame (preview + encoder + finder +
        // analysis) and the Pair-returning coverScale boxed 3 objects per call in the hottest loop.
        coverScaleInto(coverScratch, previewW, previewH, sensorOrientationDeg, contentRotationDeg, targetWidth, targetHeight)
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, coverScratch[0], coverScratch[1], 1f)

        // Texcoord transform about center: content rotation (afocal 180° + sensor) + EIS roll,
        // crop-zoom for stabilization headroom, then EIS translation, then the SurfaceTexture matrix.
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, centerX + stabShiftX, centerY + stabShiftY, 0f)
        Matrix.rotateM(rot, 0, contentRotationDeg.toFloat() + stabRollDeg, 0f, 0f, 1f)
        val comp = zoomComp.coerceAtLeast(1f)
        Matrix.scaleM(rot, 0, (1f - crop) / comp, (1f - crop) / comp, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rot, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1i(uTransfer, shaderTransferCode(transfer))
        // Route state: describes the FRAMES, so every draw role must agree. A field, not an
        // argument — preview, encoder, analysis and finder all read the same camera buffers.
        GLES20.glUniform1i(uSourceHlg, if (sourceHlg) 1 else 0)
        GLES20.glUniform1i(uPeaking, if (peaking) 1 else 0)
        GLES20.glUniform1f(uPeakThreshold, peakThreshold)
        GLES20.glUniform3f(uPeakColor, peakR, peakG, peakB)
        GLES20.glUniform1i(uZebra, if (zebra) 1 else 0)
        GLES20.glUniform1f(uZebraThreshold, zebraThreshold)
        GLES20.glUniform1i(uFalseColor, if (falseColor) 1 else 0)
        GLES20.glUniform2f(uTexel, 1f / previewW, 1f / previewH)
        GLES20.glUniform1f(uDigitalGain, digitalGain.coerceAtLeast(1f))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(
            aTexCoord, 2, GLES20.GL_FLOAT, false, 0,
            if (mirrorX) TEX_COORD_MIRRORED_OFFSET_BYTES else TEX_COORD_OFFSET_BYTES,
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        // Leave no array-buffer binding behind: other GL code in this pipeline (FBO readback, hint
        // clears) assumes default binding state.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        if (quadVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        program = 0
        oesTextureId = 0
        quadVbo = 0
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        // Byte offsets into the one static [quadVbo]: 8 position floats (32 B), then the plain
        // texcoord quad, then the mirrored-x texcoord quad (8 floats / 32 B each).
        const val TEX_COORD_OFFSET_BYTES = 8 * 4
        const val TEX_COORD_MIRRORED_OFFSET_BYTES = 16 * 4
    }
}

/**
 * The quad's attribute texcoords (triangle-strip order, matching the −1..1 position quad), with an
 * optional horizontal mirror. Mirroring here — x→1−x per vertex, y untouched — happens BEFORE the
 * rot chain and the SurfaceTexture matrix, so it flips the DISPLAYED image about the display's
 * vertical axis for ANY sensor orientation (the selfie mirror convention). Pure and top-level so
 * the inversion is unit-testable without GL.
 */
internal fun texCoordQuad(mirrorX: Boolean): FloatArray {
    val plain = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    if (!mirrorX) return plain
    for (i in plain.indices step 2) plain[i] = 1f - plain[i]
    return plain
}

/**
 * Center-crop "cover" scale factors (ex, ey) for the quad geometry, extracted from [FlipRenderer.draw]
 * as pure Float math (no GL) so the aspect logic is unit-testable. Content aspect is taken AFTER all
 * rotation: the SurfaceTexture transform's sensor orientation PLUS the extra texcoord rotation, so a
 * net 90/270 swaps the displayed width/height. Exactly one axis is scaled >1 to overscan the target;
 * matching aspects return (1, 1). [targetHeight] is floored at 1 to avoid a divide-by-zero.
 */
/**
 * Clamps a punch-in/loupe center so the SAMPLED WINDOW stays inside the texture.
 *
 * [FlipRenderer.draw] samples `center ± halfExtent`, where the half-extent is
 * `((1 - crop) / zoomComp) / 2`. Clamping only the CENTER to 0..1 (which is all
 * `GlPipeline.setPunchInCenter` can do, since it does not know the live crop) still lets the window
 * run off the edge: at the default `PUNCH_IN_CROP` the half-extent is 0.2, so a center of 0 samples
 * from -0.2. Outside the texture the external sampler returns edge-clamped/garbage texels, which is
 * the smeared out-of-bounds artifact the operator sees after dragging the loupe repeatedly against
 * an edge (reported 2026-07-28) — the finder must show real scene or nothing.
 *
 * Returns 0.5 when the window is at least as large as the frame ([halfExtent] >= 0.5): there is then
 * no legal off-center position at all, and a naive `coerceIn(half, 1 - half)` would throw on an
 * inverted range.
 */
internal fun clampPunchInCenter(center: Float, crop: Float, zoomComp: Float): Float {
    val halfExtent = ((1f - crop.coerceIn(0f, 1f)) / zoomComp.coerceAtLeast(1f)) / 2f
    if (halfExtent >= 0.5f) return 0.5f
    return center.coerceIn(halfExtent, 1f - halfExtent)
}

internal fun coverScale(
    previewW: Int,
    previewH: Int,
    sensorOrientationDeg: Int,
    rotationDeg: Int,
    targetWidth: Int,
    targetHeight: Int,
): Pair<Float, Float> {
    val out = FloatArray(2)
    coverScaleInto(out, previewW, previewH, sensorOrientationDeg, rotationDeg, targetWidth, targetHeight)
    return out[0] to out[1]
}

/** Allocation-free form of [coverScale] for the per-frame draw loop: writes (ex, ey) into [out]. */
internal fun coverScaleInto(
    out: FloatArray,
    previewW: Int,
    previewH: Int,
    sensorOrientationDeg: Int,
    rotationDeg: Int,
    targetWidth: Int,
    targetHeight: Int,
) {
    val rotated = (sensorOrientationDeg + rotationDeg) % 180 == 90
    val displayedAspect = if (rotated) previewH.toFloat() / previewW else previewW.toFloat() / previewH
    val viewAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
    var ex = 1f
    var ey = 1f
    if (displayedAspect > viewAspect) ex = displayedAspect / viewAspect else ey = viewAspect / displayedAspect
    out[0] = ex
    out[1] = ey
}

/**
 * ColorTransfer -> fragment-shader `uTransfer` branch code, as ONE exhaustive mapping. The old
 * inline `when` ended in `else -> 0`, so a future ColorTransfer member compiled clean while
 * silently rendering as SDR — unlike the container-tag side (ColorProfiles), where the exhaustive
 * `when` breaks the build.
 *
 * Code 3 is permanently vacant: it was the de-log branch for a scene-referred vendor stream that
 * can no longer arrive (that path was declined 2026-08-04). The remaining codes keep their original
 * numbers rather than closing the gap — renumbering would buy nothing and would silently
 * re-map every value these tests pin.
 */
internal fun shaderTransferCode(transfer: ColorTransfer?): Int = when {
    transfer == null -> 0 // preview/null path: camera frames are already SDR
    else -> when (transfer) {
        ColorTransfer.HLG -> 1
        ColorTransfer.SLOG3 -> 2
        ColorTransfer.SLOG3_CINE -> 4
        ColorTransfer.LOGC3 -> 5
        // SDR = no OETF, same as the null path.
        ColorTransfer.SDR -> 0
    }
}
