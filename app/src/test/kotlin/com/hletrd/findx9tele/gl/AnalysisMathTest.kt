package com.hletrd.findx9tele.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure scope-analysis math ([computeHistogram]/[computeWaveform]) on synthetic
 * RGBA buffers — no GL, no device. Pins the Rec.2020 luma mapping and the "row 0 = brightest"
 * waveform convention.
 */
class AnalysisMathTest {

    // Packed RGBA8888 buffer (matches glReadPixels GL_RGBA/GL_UNSIGNED_BYTE), constant color.
    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val bytes = ByteArray(w * h * 4)
        var i = 0
        while (i < bytes.size) {
            bytes[i] = r.toByte()
            bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte()
            bytes[i + 3] = 255.toByte()
            i += 4
        }
        return bytes
    }

    private fun IntArray.argmax(): Int {
        var best = 0
        for (i in indices) if (this[i] > this[best]) best = i
        return best
    }

    private fun IntArray.nonZeroBins(): List<Int> = indices.filter { this[it] != 0 }

    @Test
    fun `solid mid-grey concentrates luma near bin 128`() {
        val hist = computeHistogram(solid(120, 90, 128, 128, 128), 120, 90)
        // Rec.2020 luma of equal RGB collapses to (near) the same value; float32 truncation lands it
        // at 127. All sampled pixels are identical, so exactly one luma bin is occupied.
        val bins = hist.luma.nonZeroBins()
        assertEquals(1, bins.size)
        assertTrue("luma peak ~128, was ${bins[0]}", bins[0] in 127..128)
        // Red/Green/Blue are the raw channel bytes -> exactly bin 128, no float rounding.
        assertEquals(listOf(128), hist.red.nonZeroBins())
        assertEquals(listOf(128), hist.green.nonZeroBins())
        assertEquals(listOf(128), hist.blue.nonZeroBins())
        // The one luma bin holds every sampled pixel (same count as any channel bin).
        assertEquals(hist.red[128], hist.luma[bins[0]])
    }

    @Test
    fun `all-black and all-white hit the extreme luma bins`() {
        val black = computeHistogram(solid(96, 72, 0, 0, 0), 96, 72)
        assertEquals(listOf(0), black.luma.nonZeroBins())
        assertEquals(0, black.luma.argmax())

        val white = computeHistogram(solid(96, 72, 255, 255, 255), 96, 72)
        assertEquals(listOf(255), white.luma.nonZeroBins())
        assertEquals(255, white.luma.argmax())
    }

    @Test
    fun `top-half-white bottom-half-black waveform puts bright samples at row 0`() {
        val w = 120
        val h = 90
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val v = if (y < h / 2) 255 else 0 // top half white, bottom half black
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                bytes[i] = v.toByte()
                bytes[i + 1] = v.toByte()
                bytes[i + 2] = v.toByte()
                bytes[i + 3] = 255.toByte()
            }
        }
        val wave = computeWaveform(bytes, w, h)
        assertEquals(64, wave.rows)
        assertEquals(128, wave.columns)
        var row0 = 0
        var rowLast = 0
        var middle = 0
        for (col in 0 until wave.columns) {
            for (row in 0 until wave.rows) {
                val c = wave.bins[col * wave.rows + row]
                when (row) {
                    0 -> row0 += c // luma 255 (white) maps here — brightest pinned to the top
                    wave.rows - 1 -> rowLast += c // luma 0 (black) maps to the bottom row
                    else -> middle += c
                }
            }
        }
        assertTrue("white samples present at row 0", row0 > 0)
        assertTrue("black samples present at the bottom row", rowLast > 0)
        assertEquals("only the two extremes are occupied", 0, middle)
    }

    @Test
    fun `dimensions not divisible by the subsample step do not crash or over-run`() {
        // step = 6; 101x77 sits off the stride so the last sampled x/y land near the edge — the index
        // math (i + 2 for blue) must still stay inside the w*h*4 buffer.
        val bytes = solid(101, 77, 128, 128, 128)
        val hist = computeHistogram(bytes, 101, 77)
        val wave = computeWaveform(bytes, 101, 77)
        // No exception = no buffer over-run; sanity-check something was actually sampled.
        assertTrue(hist.luma.sum() > 0)
        assertTrue(wave.bins.sum() > 0)
    }
}
