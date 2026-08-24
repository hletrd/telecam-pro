package me.hletrd.telecampro.gl

import android.opengl.GLES20
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class FlipRendererResourceOwnershipTest {
    @Test
    fun `every partially acquired GL object is deleted exactly once and ownership clears`() {
        val ids = listOf(
            "shader" to 11,
            "shader" to 12,
            "program" to 21,
            "buffer" to 31,
            "texture" to 41,
        )
        for (acquiredCount in 1..ids.size) {
            val pending = PendingGlObjects(
                vertexShader = 11.takeIf { acquiredCount >= 1 } ?: 0,
                fragmentShader = 12.takeIf { acquiredCount >= 2 } ?: 0,
                program = 21.takeIf { acquiredCount >= 3 } ?: 0,
                buffer = 31.takeIf { acquiredCount >= 4 } ?: 0,
                texture = 41.takeIf { acquiredCount >= 5 } ?: 0,
            )
            val deleted = mutableListOf<Pair<String, Int>>()

            releasePendingGlObjects(
                pending = pending,
                deleteShader = { deleted += "shader" to it },
                deleteProgram = { deleted += "program" to it },
                deleteBuffer = { deleted += "buffer" to it },
                deleteTexture = { deleted += "texture" to it },
            )

            assertEquals(ids.take(acquiredCount), deleted)
            assertEquals(PendingGlObjects(), pending)
            releasePendingGlObjects(
                pending = pending,
                deleteShader = { deleted += "shader" to it },
                deleteProgram = { deleted += "program" to it },
                deleteBuffer = { deleted += "buffer" to it },
                deleteTexture = { deleted += "texture" to it },
            )
            assertEquals("cleanup is idempotent", ids.take(acquiredCount), deleted)
        }
    }

    @Test
    fun `transferred program is not deleted with temporary shaders`() {
        val pending = PendingGlObjects(vertexShader = 11, fragmentShader = 12, program = 21)
        val transferred = pending.program
        pending.program = 0
        val deleted = mutableListOf<Pair<String, Int>>()

        releasePendingGlObjects(
            pending = pending,
            deleteShader = { deleted += "shader" to it },
            deleteProgram = { deleted += "program" to it },
            deleteBuffer = { deleted += "buffer" to it },
            deleteTexture = { deleted += "texture" to it },
        )

        assertEquals(21, transferred)
        assertEquals(listOf("shader" to 11, "shader" to 12), deleted)
    }

    @Test
    fun `every non-throwing VBO and texture error cleans exact owners and permits retry`() {
        val faults = listOf(
            "generateBuffer" to "glGenBuffers",
            "bindBuffer:31" to "glBindBuffer(VBO)",
            "uploadBuffer" to "glBufferData",
            "bindBuffer:0" to "glBindBuffer(0)",
            "generateTexture" to "glGenTextures",
            "bindTexture:41" to "glBindTexture(GL_TEXTURE_EXTERNAL_OES)",
            "textureParameter:${GLES20.GL_TEXTURE_MIN_FILTER}" to
                "glTexParameteri(GL_TEXTURE_MIN_FILTER)",
            "textureParameter:${GLES20.GL_TEXTURE_MAG_FILTER}" to
                "glTexParameteri(GL_TEXTURE_MAG_FILTER)",
            "textureParameter:${GLES20.GL_TEXTURE_WRAP_S}" to
                "glTexParameteri(GL_TEXTURE_WRAP_S)",
            "textureParameter:${GLES20.GL_TEXTURE_WRAP_T}" to
                "glTexParameteri(GL_TEXTURE_WRAP_T)",
            "bindTexture:0" to "glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)",
        )

        for ((faultCall, diagnostic) in faults) {
            val gl = FakeFlipRendererGlApi(faultCall)
            val renderer = FlipRenderer(gl)

            val failure = assertThrows(IllegalStateException::class.java) { renderer.init() }
            assertTrue("$faultCall diagnostic", failure.message.orEmpty().contains(diagnostic))
            assertEquals("$faultCall program transfer", 0, rendererField(renderer, "program"))
            assertEquals("$faultCall VBO transfer", 0, rendererField(renderer, "quadVbo"))
            assertEquals("$faultCall texture transfer", 0, rendererField(renderer, "oesTextureId"))
            assertEquals("$faultCall unbind buffer", "bindBuffer:0", gl.calls.takeLast(2)[0])
            assertEquals("$faultCall unbind texture", "bindTexture:0", gl.calls.takeLast(2)[1])
            assertEquals("$faultCall program cleanup", 1, gl.deleted.count { it == "program:21" })
            assertEquals(
                "$faultCall buffer cleanup",
                if (gl.calls.contains("generateBuffer")) 1 else 0,
                gl.deleted.count { it == "buffer:31" },
            )
            assertEquals(
                "$faultCall texture cleanup",
                if (gl.calls.contains("generateTexture")) 1 else 0,
                gl.deleted.count { it == "texture:41" },
            )

            gl.faultCall = null
            gl.calls.clear()
            assertEquals("$faultCall retry texture", 41, renderer.init())
            assertEquals("$faultCall retry program", 21, rendererField(renderer, "program"))
            assertEquals("$faultCall retry VBO", 31, rendererField(renderer, "quadVbo"))
            assertEquals("$faultCall retry transfer", 41, rendererField(renderer, "oesTextureId"))
            renderer.release()
            assertEquals(0, rendererField(renderer, "program"))
            assertEquals(0, rendererField(renderer, "quadVbo"))
            assertEquals(0, rendererField(renderer, "oesTextureId"))
        }
    }

    @Test
    fun `initialization clears inherited GLES errors before attributing new operations`() {
        val gl = FakeFlipRendererGlApi(
            faultCall = null,
            inheritedErrors = ArrayDeque(listOf(GLES20.GL_INVALID_ENUM, GLES20.GL_INVALID_OPERATION)),
        )
        val renderer = FlipRenderer(gl)

        assertEquals(41, renderer.init())
        assertEquals(emptyList<Int>(), gl.inheritedErrors.toList())
        renderer.release()
    }

    @Test
    fun `error boundary refuses a GLES error source that never clears`() {
        val boundary = GlOperationErrorBoundary { GLES20.GL_INVALID_OPERATION }

        val failure = assertThrows(IllegalStateException::class.java) { boundary.begin() }

        assertTrue(failure.message.orEmpty().contains("did not clear"))
    }

    private fun rendererField(renderer: FlipRenderer, name: String): Int =
        renderer.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(renderer)

    private class FakeFlipRendererGlApi(
        var faultCall: String?,
        val inheritedErrors: ArrayDeque<Int> = ArrayDeque(),
    ) : FlipRendererGlApi {
        val calls = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        private var pendingError = GLES20.GL_NO_ERROR
        private var faultDelivered = false
        private var shaderCount = 0

        private fun call(name: String) {
            calls += name
            if (!faultDelivered && faultCall == name) {
                pendingError = GLES20.GL_OUT_OF_MEMORY
                faultDelivered = true
            }
        }

        override fun getError(): Int {
            if (inheritedErrors.isNotEmpty()) return inheritedErrors.removeFirst()
            return pendingError.also { pendingError = GLES20.GL_NO_ERROR }
        }

        override fun createShader(type: Int): Int = if (shaderCount++ == 0) 11 else 12
        override fun shaderSource(shader: Int, source: String) = Unit
        override fun compileShader(shader: Int) = Unit
        override fun shaderCompileStatus(shader: Int): Int = GLES20.GL_TRUE
        override fun shaderInfoLog(shader: Int): String = ""
        override fun createProgram(): Int = 21
        override fun attachShader(program: Int, shader: Int) = Unit
        override fun linkProgram(program: Int) = Unit
        override fun programLinkStatus(program: Int): Int = GLES20.GL_TRUE
        override fun programInfoLog(program: Int): String = ""
        override fun attributeLocation(program: Int, name: String): Int = 1
        override fun uniformLocation(program: Int, name: String): Int = 1
        override fun generateBuffer(): Int = 31.also { call("generateBuffer") }
        override fun bindBuffer(id: Int) = call("bindBuffer:$id")
        override fun uploadBuffer(data: FloatBuffer) = call("uploadBuffer")
        override fun generateTexture(): Int = 41.also { call("generateTexture") }
        override fun bindExternalTexture(id: Int) = call("bindTexture:$id")
        override fun setExternalTextureParameter(name: Int, value: Int) =
            call("textureParameter:$name")
        override fun deleteShader(id: Int) { deleted += "shader:$id" }
        override fun deleteProgram(id: Int) { deleted += "program:$id" }
        override fun deleteBuffer(id: Int) { deleted += "buffer:$id" }
        override fun deleteTexture(id: Int) { deleted += "texture:$id" }
    }
}
