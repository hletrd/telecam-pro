package com.hletrd.findx9tele.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the selfie preview-mirror texcoord seam ([texCoordQuad]). The mirror is applied to the
 * ATTRIBUTE texcoords — before the rot chain and the SurfaceTexture matrix — so an x-per-vertex
 * inversion here is a display-horizontal mirror for ANY sensor orientation; a sign change on the
 * wrong axis would compile clean and only show on device, which is why the corners are pinned.
 */
class PreviewMirrorTest {

    @Test
    fun `unmirrored quad is the identity texcoord corners in triangle-strip order`() {
        assertArrayEquals(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            texCoordQuad(mirrorX = false),
            0f,
        )
    }

    @Test
    fun `mirrored quad inverts x per vertex and leaves y untouched`() {
        val plain = texCoordQuad(mirrorX = false)
        val mirrored = texCoordQuad(mirrorX = true)
        for (i in plain.indices step 2) {
            assertEquals("x of vertex ${i / 2}", 1f - plain[i], mirrored[i], 0f)
            assertEquals("y of vertex ${i / 2}", plain[i + 1], mirrored[i + 1], 0f)
        }
    }

    @Test
    fun `each call returns a fresh array so the mirrored build cannot corrupt the plain quad`() {
        // texCoordQuad(true) mutates its own fresh copy in place; a shared backing array would
        // silently mirror BOTH FlipRenderer buffers.
        val first = texCoordQuad(mirrorX = false)
        texCoordQuad(mirrorX = true)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), first, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), texCoordQuad(mirrorX = false), 0f)
    }
}
