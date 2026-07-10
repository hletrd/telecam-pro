package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the manual-WB gain math ([kelvinTintToRggbGainValues]): normalization, tint, and the cap. */
class WbGainsTest {

    @Test
    fun `gains are normalized so the smallest channel is one`() {
        for (kelvin in intArrayOf(2000, 3200, 5200, 6500, 10000)) {
            val v = kelvinTintToRggbGainValues(kelvin, 0)
            val min = v.min()
            assertEquals("min gain at ${kelvin}K", 1f, min, 1e-3f)
        }
    }

    @Test
    fun `every channel respects the gain cap even at 2000K`() {
        // Unclamped, the blackbody approximation demands ~18-19x blue at 2000 K — far outside what
        // sensor WB gains span. The cap must hold at the extreme UI-reachable presets.
        for (kelvin in intArrayOf(1000, 2000, 2500, 40000)) {
            val v = kelvinTintToRggbGainValues(kelvin, 0)
            for (g in v) {
                assertTrue("gain $g at ${kelvin}K exceeds cap", g <= MAX_WB_CHANNEL_GAIN + 1e-3f)
            }
        }
    }

    @Test
    fun `warm kelvin boosts blue, cool kelvin boosts red`() {
        val warm = kelvinTintToRggbGainValues(2800, 0) // incandescent scene → blue gain dominates
        assertTrue("warm: blue > red", warm[3] > warm[0])
        val cool = kelvinTintToRggbGainValues(10000, 0) // shade → red gain dominates
        assertTrue("cool: red > blue", cool[0] > cool[3])
    }

    @Test
    fun `positive tint reduces green gain relative to neutral`() {
        val neutral = kelvinTintToRggbGainValues(5200, 0)
        val magenta = kelvinTintToRggbGainValues(5200, 50)
        val green = kelvinTintToRggbGainValues(5200, -50)
        // Compare green relative to red (normalization can rescale everything together).
        assertTrue("magenta tint lowers G/R", magenta[1] / magenta[0] < neutral[1] / neutral[0])
        assertTrue("green tint raises G/R", green[1] / green[0] > neutral[1] / neutral[0])
    }

    @Test
    fun `both green channels are identical`() {
        val v = kelvinTintToRggbGainValues(4300, 12)
        assertEquals(v[1], v[2], 0f)
    }
}
