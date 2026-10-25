package me.hletrd.telecampro.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The buffer transform the RAW log developer's EGL frames lack (SourceTransformEmulation.kt), pinned
 * against AOSP GLConsumer::computeTransformMatrix's composition: camera = flipV · crop · T, EGL =
 * flipV · crop, so camera = EGL · T.
 */
class SourceTransformEmulationTest {

    private val flipV = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    // A representative crop (GLConsumer shrinks by half a texel when filtering).
    private val crop = floatArrayOf(0.998f, 0f, 0f, 0f, 0f, 0.997f, 0f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0.0015f, 0f, 1f)

    private fun map(m: FloatArray, s: Float, t: Float): Pair<Float, Float> =
        (m[0] * s + m[4] * t + m[12]) to (m[1] * s + m[5] * t + m[13])

    @Test
    fun `rear orientations map to the camera service's transforms`() {
        assertEquals(0, SourceTransformEmulation.rearTransformFor(0))
        assertEquals(SourceTransformEmulation.ROT_90, SourceTransformEmulation.rearTransformFor(90))
        assertEquals(SourceTransformEmulation.ROT_180, SourceTransformEmulation.rearTransformFor(180))
        assertEquals(SourceTransformEmulation.ROT_270, SourceTransformEmulation.rearTransformFor(270))
        assertEquals(SourceTransformEmulation.ROT_270, SourceTransformEmulation.rearTransformFor(-90))
    }

    @Test
    fun `no transform is a no-op`() {
        assertNull(SourceTransformEmulation.matrixFor(0))
        val m = flipV.copyOf()
        SourceTransformEmulation.apply(m, 0)
        assertArrayEquals(flipV, m, 0f)
    }

    @Test
    fun `the emulated EGL matrix equals the camera's for every rotation`() {
        for (flags in listOf(SourceTransformEmulation.ROT_90, SourceTransformEmulation.ROT_180, SourceTransformEmulation.ROT_270)) {
            val t = SourceTransformEmulation.matrixFor(flags)!!
            val camera = SourceTransformEmulation.multiply(SourceTransformEmulation.multiply(flipV, crop), t)
            val egl = SourceTransformEmulation.multiply(flipV, crop)
            SourceTransformEmulation.apply(egl, flags)
            assertArrayEquals("flags $flags", camera, egl, 1e-6f)
        }
    }

    @Test
    fun `ROT_90 and ROT_180 move texture coordinates as GLConsumer's matrices do`() {
        val r90 = SourceTransformEmulation.matrixFor(SourceTransformEmulation.ROT_90)!!
        assertEquals(1f to 0f, map(r90, 0f, 0f))
        assertEquals(1f to 1f, map(r90, 1f, 0f))
        assertEquals(0f to 0f, map(r90, 0f, 1f))
        val r180 = SourceTransformEmulation.matrixFor(SourceTransformEmulation.ROT_180)!!
        assertEquals(1f to 1f, map(r180, 0f, 0f))
        assertEquals(0f to 0f, map(r180, 1f, 1f))
    }
}
