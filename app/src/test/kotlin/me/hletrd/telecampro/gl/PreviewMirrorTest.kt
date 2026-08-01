package me.hletrd.telecampro.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the front-mirror texcoord seam ([texCoordQuad]) and the [FrontMirrorConvention] role
 * derivations. The x-inversion is applied to the ATTRIBUTE texcoords — before the rot chain and
 * the SurfaceTexture matrix — so an x-per-vertex inversion here is a display-horizontal mirror
 * for ANY sensor orientation. WHICH draws set it is the inverted-role device fact (the PMA110
 * front HAL pre-mirrors its stream, 29559a8): the preview passes the stream through and the
 * encoder/analysis draws un-mirror — all derived from the one convention constant.
 */
class PreviewMirrorTest {

    @Test
    fun `front mirror roles derive coherently under BOTH stream conventions`() {
        for (pre in listOf(true, false)) {
            // Exactly ONE draw side carries the selfie mirror on the front route — whichever way
            // the device fact points, preview and encoder must disagree, and the tap axis must
            // follow the preview role (three independent literals drifted here before; cycle-6
            // architect F4, and the 2026-08-01 half-migration).
            assertTrue(
                "pre=$pre",
                FrontMirrorConvention.previewDrawMirrorX(true, pre) !=
                    FrontMirrorConvention.encoderDrawMirrorX(true, pre),
            )
            assertEquals(
                FrontMirrorConvention.previewDrawMirrorX(true, pre),
                FrontMirrorConvention.tapDisplayMirrorX(true, pre),
            )
            // The DISPLAYED selfie is mirrored relative to the array under either convention, so
            // metering always un-flips on the front route — convention-independent by design.
            assertTrue("pre=$pre", FrontMirrorConvention.meteringMirrorX(true, pre))
            // Rear routes never mirror on any seam.
            assertFalse(FrontMirrorConvention.previewDrawMirrorX(false, pre))
            assertFalse(FrontMirrorConvention.encoderDrawMirrorX(false, pre))
            assertFalse(FrontMirrorConvention.tapDisplayMirrorX(false, pre))
            assertFalse(FrontMirrorConvention.meteringMirrorX(false, pre))
        }
        // PMA110 (pre-mirrored stream): preview pass-through, encoder un-mirrors — the shipped,
        // device-verified role set stays byte-identical.
        assertFalse(FrontMirrorConvention.previewDrawMirrorX(true, streamPreMirrored = true))
        assertTrue(FrontMirrorConvention.encoderDrawMirrorX(true, streamPreMirrored = true))
        // GENERIC (spec stream): preview adds the conventional selfie mirror, encoder writes the
        // stream as-is, and the tap must un-flip display→texture because the preview mirrored.
        assertTrue(FrontMirrorConvention.previewDrawMirrorX(true, streamPreMirrored = false))
        assertFalse(FrontMirrorConvention.encoderDrawMirrorX(true, streamPreMirrored = false))
        assertTrue(FrontMirrorConvention.tapDisplayMirrorX(true, streamPreMirrored = false))
    }

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
