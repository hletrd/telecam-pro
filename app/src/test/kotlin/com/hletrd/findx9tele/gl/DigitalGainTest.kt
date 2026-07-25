package com.hletrd.findx9tele.gl

import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cycle-8 preview brightness simulation: the fluidity-capped repeating request's residual
 * exposure shortfall renders as a linear-light gain in the PREVIEW shader, and the analysis
 * readback (drawn unboosted) applies the matching display LUT CPU-side so scopes/meter/AE read the
 * same simulated still exposure — the boost counted exactly once, never in files.
 */
class DigitalGainTest {

    // ---- shader structure ----

    @Test
    fun `gain applies in linear light before base forms`() {
        val shader = Shaders.FRAGMENT
        assertTrue("uDigitalGain uniform must exist", shader.contains("uniform float uDigitalGain;"))
        // The boost wraps the very first texture sample, so `base` — and with it the display, the
        // zebra/false-color `meter`, and peaking — all read the simulated brightness.
        assertTrue(
            "base must form from the boosted sample",
            shader.contains("vec3 base = dgain(texture2D(uTexture, vTexCoord).rgb);"),
        )
        // Linear-domain multiply: BT.1886 decode -> gain -> clamp -> re-encode.
        val body = shader.substringAfter("vec3 dgain(vec3 c)").substringBefore("}")
        assertTrue("unity short-circuit", body.contains("if (uDigitalGain <= 1.001) return c;"))
        assertTrue(
            "decode then multiply",
            body.contains("pow(clamp(c, 0.0, 1.0), vec3(SDR_EOTF_GAMMA)) * uDigitalGain"),
        )
        assertTrue("clamp then re-encode", body.contains("pow(min(lin, vec3(1.0)), vec3(1.0 / SDR_EOTF_GAMMA))"))
    }

    @Test
    fun `peaking neighbors ride the same gain as the center sample`() {
        // A boosted center against raw neighbors would fabricate gradients everywhere; raw-vs-raw
        // would under-fire the threshold exactly in the low light the simulation exists for.
        val peakingBlock = Shaders.FRAGMENT.substringAfter("if (uPeaking == 1)").substringBefore("if (uZebra")
        assertTrue(peakingBlock.contains("luma(dgain(texture2D(uTexture, vTexCoord + vec2(uTexel.x, 0.0)).rgb))"))
        assertTrue(peakingBlock.contains("luma(dgain(texture2D(uTexture, vTexCoord + vec2(0.0, uTexel.y)).rgb))"))
        assertTrue("no raw neighbor sample may remain", !peakingBlock.contains("luma(texture2D"))
    }

    // ---- display LUT ----

    @Test
    fun `unity gain yields no LUT`() {
        assertNull(digitalGainDisplayLut(1f))
        assertNull(digitalGainDisplayLut(0.5f))
        assertNull(digitalGainDisplayLut(1.001f))
    }

    @Test
    fun `LUT matches the shader chain and stays monotonic`() {
        val gain = 4f
        val lut = checkNotNull(digitalGainDisplayLut(gain))
        assertEquals(256, lut.size)
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        var prev = 0
        for (v in 0..255) {
            val expected = (minOf((v / 255.0).pow(SdrToHlgMapping.SDR_EOTF_GAMMA) * gain, 1.0)
                .pow(1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA) * 255.0).roundToInt()
            assertEquals("lut[$v]", expected, lut[v])
            assertTrue("monotonic", lut[v] >= prev)
            prev = lut[v]
        }
        // A ×4 linear gain is 2 stops: mid-grey must brighten but stay unclipped.
        assertTrue(lut[64] > 64)
        assertTrue(lut[64] < 255)
    }

    // ---- analysis integration ----

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

    private fun IntArray.nonZeroBins(): List<Int> = indices.filter { this[it] != 0 }

    @Test
    fun `boosted histogram moves every channel through the LUT before luma weighting`() {
        val lut = checkNotNull(digitalGainDisplayLut(4f))
        val hist = computeHistogram(solid(96, 72, 40, 40, 40), 96, 72, lut)
        // Channels bin the LUT'd byte; luma is computed FROM the boosted channels (shader parity).
        assertEquals(listOf(lut[40]), hist.red.nonZeroBins())
        assertEquals(listOf(lut[40]), hist.green.nonZeroBins())
        assertEquals(listOf(lut[40]), hist.blue.nonZeroBins())
        val lumaBin = hist.luma.nonZeroBins().single()
        assertTrue("boosted luma bin ~lut[40]=${lut[40]}, was $lumaBin", lumaBin in (lut[40] - 1)..lut[40])
        // Null LUT keeps the sensor-true bins.
        assertEquals(listOf(40), computeHistogram(solid(96, 72, 40, 40, 40), 96, 72, null).red.nonZeroBins())
    }

    @Test
    fun `boosted waveform rises toward the bright rows`() {
        val lut = checkNotNull(digitalGainDisplayLut(8f))
        val raw = computeWaveform(solid(96, 72, 24, 24, 24), 96, 72)
        val boosted = computeWaveform(solid(96, 72, 24, 24, 24), 96, 72, lut)
        fun WaveformData.occupiedRow(): Int {
            for (row in 0 until rows) for (col in 0 until columns) {
                if (bins[col * rows + row] != 0) return row
            }
            return -1
        }
        // Row 0 = brightest: the boosted trace must sit strictly above (smaller row index).
        assertTrue(boosted.occupiedRow() < raw.occupiedRow())
    }
}
