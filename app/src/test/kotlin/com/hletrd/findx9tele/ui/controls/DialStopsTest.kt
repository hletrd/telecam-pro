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

    // AGG3-7/CR-12: isoStops used to round each candidate to 2 significant figures, producing
    // non-standard values (130/630/1300/5100/8100…) that contradict the "conventional stops"
    // comment. It now snaps to the standard 1/3-stop ISO ladder (STANDARD_ISO_LADDER), so a
    // representative device range must reproduce EXACTLY the real-camera ladder, not a rounded
    // approximation of it.
    @Test
    fun `isoStops pins the standard 1-3-stop ladder for a representative 100-12800 range`() {
        val expected = intArrayOf(
            100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
            2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
        )
        assertTrue(
            "expected standard ISO ladder, got ${isoStops(100, 12800, 1f / 3f).toList()}",
            isoStops(100, 12800, 1f / 3f).contentEquals(expected),
        )
    }

    // Hardware bounds that don't land on a standard stop (90 sits between the ladder's 80 and 100;
    // 9000 sits between 8000 and 10000) must still be individually reachable — the ladder gets the
    // two odd endpoints ADDED verbatim alongside the nearest standard stops between them, never
    // rounded away, so the full advertised device range stays reachable.
    @Test
    fun `isoStops keeps non-standard hardware endpoints reachable alongside standard stops`() {
        val stops = isoStops(90, 9000, 1f / 3f)
        assertEquals(90, stops.first())
        assertEquals(9000, stops.last())
        assertTrue(stops.contains(100))
        assertTrue(stops.contains(6400))
        assertTrue(stops.contains(8000))
        for (i in 1 until stops.size) {
            assertTrue("strictly increasing @$i", stops[i] > stops[i - 1])
        }
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
