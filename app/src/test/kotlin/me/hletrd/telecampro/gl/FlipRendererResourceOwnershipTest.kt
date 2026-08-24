package me.hletrd.telecampro.gl

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
