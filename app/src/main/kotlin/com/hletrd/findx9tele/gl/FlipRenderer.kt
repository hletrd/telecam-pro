package com.hletrd.findx9tele.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.hletrd.findx9tele.camera.ColorTransfer
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
    private var uPeaking = 0
    private var uPeakThreshold = 0
    private var uPeakColor = 0
    private var uZebra = 0
    private var uZebraThreshold = 0
    private var uFalseColor = 0
    private var uTexel = 0

    private val quad: FloatBuffer = floatBuffer(
        // x, y   (triangle strip)
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )
    private val texCoords: FloatBuffer = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
    )

    private val mvp = FloatArray(16)
    private val rot = FloatArray(16)
    private val texMatrix = FloatArray(16)
    // Reused (ex, ey) receiver for the per-draw cover-scale (PERF4-4; GL-thread confined).
    private val coverScratch = FloatArray(2)

    private var previewW = 1
    private var previewH = 1
    private var rotationDeg = 0
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
        uPeaking = GLES20.glGetUniformLocation(program, "uPeaking")
        uPeakThreshold = GLES20.glGetUniformLocation(program, "uPeakThreshold")
        uPeakColor = GLES20.glGetUniformLocation(program, "uPeakColor")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uZebraThreshold = GLES20.glGetUniformLocation(program, "uZebraThreshold")
        uFalseColor = GLES20.glGetUniformLocation(program, "uFalseColor")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")

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

    /** Extra CW rotation applied to texcoords on top of the SurfaceTexture transform (afocal flip). */
    fun setRotationDegrees(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
    }

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
        // Gamma Display Assist: de-log the (native O-Log2) stream for the monitor (uTransfer = 3).
        // Overrides [transfer]; only ever set on the preview draw.
        delogAssist: Boolean = false,
        zebraThreshold: Float = 0.95f,
        // Preview-only zoom compensation (requested ÷ HAL-applied): every setRepeatingRequest stalls
        // this HAL's stream ~180 ms, so the LIVE zoom renders here as a texture crop while the HAL
        // catches up at a throttled pace. ≥1 (can't sample beyond the frame); 1 = no-op.
        zoomComp: Float = 1f,
        // Viewport origin for sub-rect draws (the TELE finder PIP); callers scissor around the draw
        // because the internal glClear is framebuffer-wide otherwise.
        viewportX: Int = 0,
        viewportY: Int = 0,
    ) {
        GLES20.glViewport(viewportX, viewportY, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Center-crop "cover" scale so the content aspect fills the target without distortion.
        // Allocation-free form (PERF4-4): draw runs 1-4x per frame (preview + encoder + finder +
        // analysis) and the Pair-returning coverScale boxed 3 objects per call in the hottest loop.
        coverScaleInto(coverScratch, previewW, previewH, sensorOrientationDeg, rotationDeg, targetWidth, targetHeight)
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, coverScratch[0], coverScratch[1], 1f)

        // Texcoord transform about center: content rotation (afocal 180° + sensor) + EIS roll,
        // crop-zoom for stabilization headroom, then EIS translation, then the SurfaceTexture matrix.
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, centerX + stabShiftX, centerY + stabShiftY, 0f)
        Matrix.rotateM(rot, 0, rotationDeg.toFloat() + stabRollDeg, 0f, 0f, 1f)
        val comp = zoomComp.coerceAtLeast(1f)
        Matrix.scaleM(rot, 0, (1f - crop) / comp, (1f - crop) / comp, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rot, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1i(
            uTransfer,
            when {
                delogAssist -> 3
                transfer == ColorTransfer.HLG -> 1
                transfer == ColorTransfer.SLOG3 -> 2
                transfer == ColorTransfer.SLOG3_CINE -> 4
                transfer == ColorTransfer.LOGC3 -> 5
                // SDR = no OETF, same as the preview/null path (the camera frames are already SDR).
                else -> 0
            },
        )
        GLES20.glUniform1i(uPeaking, if (peaking) 1 else 0)
        GLES20.glUniform1f(uPeakThreshold, peakThreshold)
        GLES20.glUniform3f(uPeakColor, peakR, peakG, peakB)
        GLES20.glUniform1i(uZebra, if (zebra) 1 else 0)
        GLES20.glUniform1f(uZebraThreshold, zebraThreshold)
        GLES20.glUniform1i(uFalseColor, if (falseColor) 1 else 0)
        GLES20.glUniform2f(uTexel, 1f / previewW, 1f / previewH)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        program = 0
        oesTextureId = 0
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
}

/**
 * Center-crop "cover" scale factors (ex, ey) for the quad geometry, extracted from [FlipRenderer.draw]
 * as pure Float math (no GL) so the aspect logic is unit-testable. Content aspect is taken AFTER all
 * rotation: the SurfaceTexture transform's sensor orientation PLUS the extra texcoord rotation, so a
 * net 90/270 swaps the displayed width/height. Exactly one axis is scaled >1 to overscan the target;
 * matching aspects return (1, 1). [targetHeight] is floored at 1 to avoid a divide-by-zero.
 */
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
