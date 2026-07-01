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
 *  - texture coordinates are rotated (sensor orientation + afocal 180°) about their center, then
 *    multiplied by the SurfaceTexture matrix, so the *content* is un-inverted;
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
    private var uZebra = 0
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

    private var previewW = 1
    private var previewH = 1
    private var rotationDeg = 0

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
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
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

    /** Total rotation to un-invert content = normalize(sensorOrientation + 180). */
    fun setRotationDegrees(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
    }

    fun draw(
        stMatrix: FloatArray,
        targetWidth: Int,
        targetHeight: Int,
        transfer: ColorTransfer?,
        peaking: Boolean,
        zebra: Boolean,
    ) {
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Content aspect as displayed after texcoord rotation.
        val rotated = rotationDeg % 180 == 90
        val displayedAspect = if (rotated) previewH.toFloat() / previewW else previewW.toFloat() / previewH
        val viewAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)

        var ex = 1f
        var ey = 1f
        if (displayedAspect > viewAspect) ex = displayedAspect / viewAspect else ey = viewAspect / displayedAspect
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, ex, ey, 1f)

        // Rotate texcoords about their center, then apply the SurfaceTexture matrix.
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rot, 0, rotationDeg.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rot, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1i(
            uTransfer,
            when (transfer) {
                ColorTransfer.HLG -> 1
                ColorTransfer.LOG -> 2
                null -> 0
            },
        )
        GLES20.glUniform1i(uPeaking, if (peaking) 1 else 0)
        GLES20.glUniform1i(uZebra, if (zebra) 1 else 0)
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
