package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the manual-dial stop math: [roundToSignificant] and the plain-bounds cores of [isoStops] /
 * [shutterStops]. The cores take Int/Long bounds directly because android.util.Range getters throw
 * "not mocked" on the JVM (the sessionAttemptPlan/centerCropBox house pattern), so they are testable
 * off-device.
 */
class DialStopsTest {

    @Test
    fun `roundToSignificant snaps to two significant figures`() {
        assertEquals(100.0, roundToSignificant(100.0, 2), 1e-9)
        // 99.99 rounds up across the power-of-ten boundary to 100 — the ceil(log10) off-by-one trap.
        assertEquals(100.0, roundToSignificant(99.99, 2), 1e-9)
        assertEquals(1200.0, roundToSignificant(1234.0, 2), 1e-9)
    }

    // Whole-stop ISO values (exact doublings of 100) land on every EV granularity that divides 1 EV
    // into an integer number of steps — 1/3, 1/2, 1 all do — so they must appear regardless of step,
    // alongside the two always-kept hardware bounds.
    private val wholeStops = intArrayOf(100, 200, 400, 800, 1600, 3200, 6400, 12800)

    @Test
    fun `isoStops core spans conventional stops and keeps both bounds`() {
        for (step in floatArrayOf(1f / 3f, 0.5f, 1f)) {
            val stops = isoStops(100, 12800, step)
            assertEquals("lower bound kept (step=$step)", 100, stops.first())
            assertEquals("upper bound kept (step=$step)", 12800, stops.last())
            for (i in 1 until stops.size) {
                assertTrue("strictly increasing @$i (step=$step)", stops[i] > stops[i - 1])
            }
            assertTrue("all within bounds (step=$step)", stops.all { it in 100..12800 })
            for (v in wholeStops) assertTrue("contains $v (step=$step)", stops.contains(v))
        }
    }

    @Test
    fun `isoStops core collapses a degenerate range to the lower bound`() {
        assertTrue(isoStops(400, 400, 1f / 3f).contentEquals(intArrayOf(400)))
        assertTrue(isoStops(400, 100, 1f / 3f).contentEquals(intArrayOf(400))) // lower >= upper
        assertTrue(isoStops(100, 12800, 0f).contentEquals(intArrayOf(100))) // stepEv <= 0
    }

    @Test
    fun `shutterStops core keeps both bounds and stays sorted`() {
        for (step in floatArrayOf(1f / 3f, 0.5f, 1f)) {
            val stops = shutterStops(125_000L, 1_000_000_000L, step)
            assertEquals("lower bound kept (step=$step)", 125_000L, stops.first())
            assertEquals("upper bound kept (step=$step)", 1_000_000_000L, stops.last())
            for (i in 1 until stops.size) {
                assertTrue("strictly increasing @$i (step=$step)", stops[i] > stops[i - 1])
            }
            assertTrue("all within bounds (step=$step)", stops.all { it in 125_000L..1_000_000_000L })
        }
    }
}
