package me.hletrd.telecampro.camera

import me.hletrd.telecampro.gl.LogProfiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log EI offset (NativeLogCurve.kt): the setting domain, the ACHIEVED offset under every wire clamp
 * (ISO floor, minimum exposure, advertised EV-comp range), the k-lifted S-Log3 tone curve, and the
 * EI-rated live readout.
 */
class NativeLogEiOffsetTest {

    // ---- setting domain ----

    @Test
    fun `the setting is whole stops 0 to 3 and k is 2 to the N`() {
        assertEquals(0, normalizedLogEiOffsetStops(-1))
        assertEquals(3, normalizedLogEiOffsetStops(7))
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0), (0..3).map(::logEiTargetFactor))
        assertEquals(NATIVE_LOG_EI_MAX_FACTOR, logEiTargetFactor(LOG_EI_OFFSET_MAX_STOPS), 0.0)
    }

    // ---- AE-OFF: ISO first, the floor's refusal from a SHORTER exposure ----

    @Test
    fun `N 0 leaves the AE-OFF wire untouched`() {
        val wire = nativeLogEiSensorWire(0, iso = 800, isoMin = 50, exposureNs = 20_000_000L, exposureMinNs = 10_000L)
        assertEquals(800, wire.iso)
        assertEquals(20_000_000L, wire.exposureNs)
        assertSame(NativeLogEiApplied.NONE, wire.applied)
    }

    @Test
    fun `with ISO headroom the whole offset comes out of ISO and the shutter is untouched`() {
        for (stops in 1..3) {
            val wire = nativeLogEiSensorWire(stops, iso = 1600, isoMin = 50, exposureNs = 20_000_000L, exposureMinNs = 10_000L)
            val k = logEiTargetFactor(stops)
            assertEquals(1600 / k.toInt(), wire.iso)
            assertEquals("shutter must stay the operator's", 20_000_000L, wire.exposureNs)
            assertEquals(k, wire.applied.factor, 1e-12)
            assertEquals(k, wire.applied.isoFactor, 1e-12)
            assertEquals(1.0, wire.applied.exposureFactor, 0.0)
        }
    }

    @Test
    fun `a non-dividing ISO rounds UP so the ISO share never exceeds k`() {
        val wire = nativeLogEiSensorWire(1, iso = 125, isoMin = 50, exposureNs = 20_000_000L, exposureMinNs = 10_000L)
        assertEquals(63, wire.iso)
        assertTrue(wire.applied.isoFactor <= 2.0)
        assertEquals(125.0 / 63.0, wire.applied.factor, 1e-12)
        assertEquals("no floor clamp, so no shutter change", 20_000_000L, wire.exposureNs)
    }

    @Test
    fun `at the ISO floor the remainder comes from a shorter exposure, never a longer one`() {
        // ISO 100 on a 50 floor, N = 2: ISO takes ×2, the shutter takes the other ×2.
        val wire = nativeLogEiSensorWire(2, iso = 100, isoMin = 50, exposureNs = 20_000_000L, exposureMinNs = 10_000L)
        assertEquals(50, wire.iso)
        assertEquals(10_000_000L, wire.exposureNs)
        assertEquals(4.0, wire.applied.factor, 1e-9)
        assertEquals(2.0, wire.applied.isoFactor, 1e-12)
        assertEquals(2.0, wire.applied.exposureFactor, 1e-9)
        // Already at the floor: everything is shutter.
        val atFloor = nativeLogEiSensorWire(3, iso = 50, isoMin = 50, exposureNs = 16_000_000L, exposureMinNs = 10_000L)
        assertEquals(50, atFloor.iso)
        assertEquals(2_000_000L, atFloor.exposureNs)
        assertEquals(8.0, atFloor.applied.factor, 1e-9)
    }

    @Test
    fun `the minimum exposure clamp is reported as a SMALLER achieved offset`() {
        // Floor ISO and a 1/8000 s exposure on a 1/16000 s sensor minimum: only ×2 is reachable.
        val wire = nativeLogEiSensorWire(3, iso = 50, isoMin = 50, exposureNs = 125_000L, exposureMinNs = 62_500L)
        assertEquals(62_500L, wire.exposureNs)
        assertEquals(2.0, wire.applied.factor, 1e-9)
        // At the minimum already: nothing achieved, the curve must stay the historical one.
        val none = nativeLogEiSensorWire(2, iso = 50, isoMin = 50, exposureNs = 62_500L, exposureMinNs = 62_500L)
        assertEquals(1.0, none.applied.factor, 0.0)
        assertTrue(none.exposureNs <= 62_500L)
    }

    @Test
    fun `the AE-OFF wire never lengthens the exposure or raises ISO for any input`() {
        for (stops in 0..3) for (iso in listOf(25, 50, 64, 100, 400, 3200)) for (exp in listOf(50_000L, 1_000_000L, 66_666_666L)) {
            val wire = nativeLogEiSensorWire(stops, iso, isoMin = 50, exposureNs = exp, exposureMinNs = 20_000L)
            assertTrue(wire.exposureNs <= exp)
            assertTrue(wire.iso <= iso)
            assertTrue(wire.applied.factor in 1.0..logEiTargetFactor(stops) + 1e-9)
            assertEquals(wire.applied.isoFactor * wire.applied.exposureFactor, wire.applied.factor, 1e-12)
        }
    }

    // ---- HAL AE: EV compensation in the advertised step, clamped ----

    @Test
    fun `N 0 leaves the operator's EV index as the wire index`() {
        val comp = nativeLogEiCompensation(0, userIndex = 3, evStepStops = 1.0 / 6, evMin = -24, evMax = 24)
        assertEquals(3, comp.wireIndex)
        assertSame(NativeLogEiApplied.NONE, comp.applied)
        // ...clamped into range exactly as before (the historical coerceIn).
        assertEquals(24, nativeLogEiCompensation(0, 99, 1.0 / 6, -24, 24).wireIndex)
    }

    @Test
    fun `the offset converts to the advertised step`() {
        val sixths = nativeLogEiCompensation(2, userIndex = 0, evStepStops = 1.0 / 6, evMin = -24, evMax = 24)
        assertEquals(-12, sixths.wireIndex)
        assertEquals(4.0, sixths.applied.factor, 1e-9)
        val thirds = nativeLogEiCompensation(3, userIndex = 1, evStepStops = 1.0 / 3, evMin = -12, evMax = 12)
        assertEquals(-8, thirds.wireIndex)
        assertEquals(8.0, thirds.applied.factor, 1e-9)
        assertEquals("HAL AE picks how; the EI readout attributes it to ISO", 1.0, thirds.applied.exposureFactor, 0.0)
    }

    @Test
    fun `a range clamp reports the ACHIEVED offset, not N`() {
        // Operator already at −1 EV of a ±2 EV range in thirds: N = 2 only reaches −2 EV, 1 stop.
        val partial = nativeLogEiCompensation(2, userIndex = -3, evStepStops = 1.0 / 3, evMin = -6, evMax = 6)
        assertEquals(-6, partial.wireIndex)
        assertEquals(2.0, partial.applied.factor, 1e-9)
        // Operator at the bottom of the range: nothing achieved.
        val none = nativeLogEiCompensation(3, userIndex = -6, evStepStops = 1.0 / 3, evMin = -6, evMax = 6)
        assertEquals(-6, none.wireIndex)
        assertEquals(1.0, none.applied.factor, 0.0)
    }

    @Test
    fun `no advertised compensation means no offset`() {
        assertEquals(1.0, nativeLogEiCompensation(2, 0, 1.0 / 3, 0, 0).applied.factor, 0.0)
        assertEquals(1.0, nativeLogEiCompensation(2, 0, 0.0, -6, 6).applied.factor, 0.0)
    }

    // ---- the k-lifted curve ----

    private fun evaluate(curve: FloatArray, x: Double): Double {
        val n = curve.size / 2
        for (i in 0 until n - 1) {
            val x0 = curve[2 * i].toDouble()
            val x1 = curve[2 * i + 2].toDouble()
            if (x in x0..x1) {
                val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0.0
                return curve[2 * i + 1] + t * (curve[2 * i + 3] - curve[2 * i + 1])
            }
        }
        error("x=$x outside the curve")
    }

    @Test
    fun `k 1 is the historical curve bit for bit`() {
        for (budget in listOf(32, 64, 128, 512)) {
            assertArrayEquals(nativeLogToneCurve(budget), nativeLogToneCurve(budget, 1.0), 0f)
        }
    }

    @Test
    fun `every N keeps the point budget, the 0-to-1 input span and strict monotonicity`() {
        for (stops in 0..3) for (budget in listOf(32, 128, 512)) {
            val k = logEiTargetFactor(stops)
            val curve = nativeLogToneCurve(budget, k)
            assertEquals(minOf(NATIVE_LOG_CURVE_POINTS, budget) * 2, curve.size)
            assertEquals(0f, curve[0], 0f)
            assertEquals(1f, curve[curve.size - 2], 0f)
            for (i in 1 until curve.size / 2) {
                assertTrue("Pin N=$stops budget=$budget at $i", curve[2 * i] > curve[2 * i - 2])
                assertTrue("Pout N=$stops budget=$budget at $i", curve[2 * i + 1] > curve[2 * i - 1])
            }
        }
    }

    @Test
    fun `every point lies on slog3 of k x and the knee is a control point`() {
        for (stops in 0..3) {
            val k = logEiTargetFactor(stops)
            val curve = nativeLogToneCurve(512, k)
            for (i in 0 until curve.size / 2) {
                assertEquals(LogProfiles.slog3Oetf(curve[2 * i] * k), curve[2 * i + 1].toDouble(), 1e-6)
            }
            val knee = (0 until curve.size / 2).any {
                kotlin.math.abs(curve[2 * it] - (LogProfiles.SLOG3_CUT / k).toFloat()) == 0f
            }
            assertTrue("knee missing at N=$stops", knee)
        }
    }

    @Test
    fun `sensor clip encodes to slog3 of k, inside the code range up to 8`() {
        for (stops in 0..3) {
            val k = logEiTargetFactor(stops)
            val curve = nativeLogToneCurve(512, k)
            assertEquals(LogProfiles.slog3Oetf(k), curve[curve.size - 1].toDouble(), 1e-6)
            assertTrue(curve[curve.size - 1] < 1f)
        }
        assertEquals(0.826, nativeLogToneCurve(512, 8.0).last().toDouble(), 2e-3)
        // A factor past the offered range is clamped rather than encoded off the code range.
        assertArrayEquals(nativeLogToneCurve(512, 8.0), nativeLogToneCurve(512, 64.0), 0f)
    }

    @Test
    fun `an underexposed grey card still lands on the S-Log3 grey code for every N`() {
        for (stops in 0..3) {
            val k = logEiTargetFactor(stops)
            // The wire was cut by k, so the ISP sees 0.18 / k; the curve lifts it back.
            assertEquals("N=$stops", 420.0 / 1023.0, evaluate(nativeLogToneCurve(512, k), 0.18 / k), 1e-4)
        }
    }

    @Test
    fun `interpolation error stays under a tenth of a 10-bit code for every N`() {
        for (stops in 0..3) {
            val k = logEiTargetFactor(stops)
            val curve = nativeLogToneCurve(512, k)
            var maxError = 0.0
            var x = 0.0
            while (x <= 1.0) {
                maxError = maxOf(maxError, kotlin.math.abs(evaluate(curve, x) - LogProfiles.slog3Oetf(k * x)))
                x += 1e-5
            }
            assertTrue("N=$stops max interpolation error $maxError", maxError < 0.1 / 1023.0)
        }
    }

    // ---- EI-rated readout ----

    @Test
    fun `the live readout is scaled back to EI values and is the identity without an offset`() {
        assertEquals(Pair(400, 20_000_000L), eiRatedReadout(400, 20_000_000L, NativeLogEiApplied.NONE))
        val applied = NativeLogEiApplied(factor = 4.0, isoFactor = 2.0, exposureFactor = 2.0)
        assertEquals(Pair(100, 20_000_000L), eiRatedReadout(50, 10_000_000L, applied))
        assertEquals(Pair<Int?, Long?>(null, null), eiRatedReadout(null, null, applied))
    }
}
