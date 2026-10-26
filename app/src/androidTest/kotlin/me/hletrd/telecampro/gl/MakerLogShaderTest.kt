package me.hletrd.telecampro.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production fragment math on a real GPU with known, uncompressed input codes. */
@RunWith(AndroidJUnit4::class)
class MakerLogShaderTest {
    @Test
    fun officialOppoShadowsDisplayCorrectlyAndRecordingKeepsSourceCodes() {
        val core = EglCore(tenBit = true)
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(8, 8) }
        val surface = Surface(consumer)
        val window = core.createWindowSurface(surface)
        var program = 0
        val texture = IntArray(1)
        try {
            core.makeCurrent(window)
            val vertex = compile(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 aPosition;
                varying vec2 vTexCoord;
                void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aPosition * 0.5 + 0.5; }
            """)
            // Only the texture transport changes: this test supplies known RGB values, not a
            // Camera2/OES producer. All production colour, assist and recording branches remain.
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.FRAGMENT
                .replace("#extension GL_OES_EGL_image_external : require", "")
                .replace("samplerExternalOES", "sampler2D"))
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            assertEquals(GLES20.glGetProgramInfoLog(program), GLES20.GL_TRUE, status[0])
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            GLES20.glUseProgram(program)
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glViewport(0, 0, 8, 8)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDigitalGain"), 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSourceLogHeadroom"), 1f)
            // Independent 8-bit display targets from OPPO's ACES V2 inverse, then BT.2020 ->
            // Rec.709 / gamma 2.4. In particular code 32 has positive light; the old Apple toe
            // made it negative and clipped it to black. Coloured samples catch matrix mistakes.
            val patches = listOf(
                intArrayOf(32, 32, 32) to intArrayOf(10, 10, 10),
                intArrayOf(40, 40, 40) to intArrayOf(24, 24, 24),
                intArrayOf(48, 48, 48) to intArrayOf(32, 32, 32),
                intArrayOf(64, 64, 64) to intArrayOf(48, 48, 48),
                intArrayOf(96, 96, 96) to intArrayOf(83, 83, 83),
                intArrayOf(124, 124, 124) to intArrayOf(124, 124, 124),
                intArrayOf(177, 177, 177) to intArrayOf(255, 255, 255),
                intArrayOf(255, 255, 255) to intArrayOf(255, 255, 255),
                intArrayOf(40, 48, 64) to intArrayOf(5, 33, 50),
                intArrayOf(64, 128, 192) to intArrayOf(0, 134, 255),
                intArrayOf(192, 128, 64) to intArrayOf(255, 58, 0),
                intArrayOf(124, 100, 75) to intArrayOf(142, 82, 52),
            )
            for ((input, display) in patches) {
                val pixel = ByteBuffer.allocateDirect(4)
                input.forEach { pixel.put(it.toByte()) }
                pixel.put(255.toByte()).position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                for ((source, transfer, expected) in listOf(
                    Triple(1, 0, display), Triple(1, 9, input), Triple(0, 0, input),
                    // HLG camera input already uses BT.2020: HLG output must retain its colours.
                    Triple(2, 1, input),
                )) {
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSourceMaker"), if (source == 1) 1 else 0)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSourceHlg"), if (source == 2) 1 else 0)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTransfer"), transfer)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    pixel.position(0)
                    GLES20.glReadPixels(4, 4, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                    assertEquals("GPU readback", GLES20.GL_NO_ERROR, GLES20.glGetError())
                    for (channel in 0..2) {
                        val actual = pixel.get(channel).toInt() and 255
                        assertTrue("input=${input.toList()} source=$source transfer=$transfer channel=$channel expected=${expected[channel]} actual=$actual",
                            kotlin.math.abs(actual - expected[channel]) <= 2)
                    }
                }
            }
        } finally {
            if (texture[0] != 0) GLES20.glDeleteTextures(1, texture, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            core.releaseSurface(window)
            core.release()
            surface.release()
            consumer.release()
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        assertEquals(GLES20.glGetShaderInfoLog(shader), GLES20.GL_TRUE, status[0])
        return shader
    }
}
