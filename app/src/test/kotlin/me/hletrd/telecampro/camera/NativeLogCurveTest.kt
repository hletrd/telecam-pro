package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import me.hletrd.telecampro.gl.LogProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native ISP log (NativeLogCurve.kt): the capability gate, the S-Log3 TONEMAP_CURVE the repeating
 * request programs, and the still tone map that keeps every still out of log.
 */
class NativeLogCurveTest {

    private val pma110ToneMapModes = intArrayOf(
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE,
        CameraMetadata.TONEMAP_MODE_FAST,
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY,
    )

    // ---- capability gate ----

    @Test
    fun `the measured PMA110 capability set qualifies`() {
        assertTrue(nativeLogCurveSupported(true, pma110ToneMapModes, maxCurvePoints = 512))
    }

    @Test
    fun `each half of the gate is required`() {
        assertFalse("no MANUAL_POST_PROCESSING", nativeLogCurveSupported(false, pma110ToneMapModes, 512))
        assertFalse(
            "CONTRAST_CURVE not advertised",
            nativeLogCurveSupported(
                true,
                intArrayOf(CameraMetadata.TONEMAP_MODE_FAST, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY),
                512,
            ),
        )
        assertFalse("point budget below the floor", nativeLogCurveSupported(true, pma110ToneMapModes, NATIVE_LOG_MIN_CURVE_POINTS - 1))
        assertTrue("floor itself qualifies", nativeLogCurveSupported(true, pma110ToneMapModes, NATIVE_LOG_MIN_CURVE_POINTS))
        assertFalse("unreported budget", nativeLogCurveSupported(true, pma110ToneMapModes, 0))
    }

    @Test
    fun `unknown caps take the fallback`() {
        assertFalse(nativeLogCurveSupported(null as CameraCaps?))
    }

    // ---- curve sampling ----

    private fun pins(curve: FloatArray) = FloatArray(curve.size / 2) { curve[2 * it] }
    private fun pouts(curve: FloatArray) = FloatArray(curve.size / 2) { curve[2 * it + 1] }

    /** Piecewise-linear evaluation — what the HAL does between control points. */
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
    fun `point count is the design count clamped to the advertised budget`() {
        assertEquals(NATIVE_LOG_CURVE_POINTS * 2, nativeLogToneCurve(512).size)
        assertEquals(64 * 2, nativeLogToneCurve(64).size)
        assertEquals(NATIVE_LOG_MIN_CURVE_POINTS * 2, nativeLogToneCurve(NATIVE_LOG_MIN_CURVE_POINTS).size)
    }

    @Test
    fun `endpoints are exactly zero and the sensor clip`() {
        val curve = nativeLogToneCurve(512)
        assertEquals(0f, curve[0], 0f)
        assertEquals((LogProfiles.SLOG3_BLACK_CODE / LogProfiles.SLOG3_CODE_SCALE).toFloat(), curve[1], 1e-7f)
        assertEquals(1f, curve[curve.size - 2], 0f)
        // The honesty limit, pinned: linear 1.0 (sensor clip) lands at S-Log3 code ~0.596, with no
        // exposure offset reaching for the latitude a real S-Log3 camera rates above it.
        assertEquals(0.596, curve[curve.size - 1].toDouble(), 1e-3)
        assertEquals(LogProfiles.slog3Oetf(1.0), curve[curve.size - 1].toDouble(), 1e-6)
    }

    @Test
    fun `both axes are strictly monotonic`() {
        for (budget in listOf(32, 64, 128, 512)) {
            val curve = nativeLogToneCurve(budget)
            val xs = pins(curve)
            val ys = pouts(curve)
            for (i in 1 until xs.size) {
                assertTrue("Pin not increasing at $i (budget $budget)", xs[i] > xs[i - 1])
                assertTrue("Pout not increasing at $i (budget $budget)", ys[i] > ys[i - 1])
            }
        }
    }

    @Test
    fun `every point lies on the S-Log3 OETF`() {
        val curve = nativeLogToneCurve(512)
        for (i in 0 until curve.size / 2) {
            assertEquals(LogProfiles.slog3Oetf(curve[2 * i].toDouble()), curve[2 * i + 1].toDouble(), 1e-6)
        }
    }

    @Test
    fun `eighteen percent grey encodes to the S-Log3 anchor`() {
        assertEquals(420.0 / 1023.0, evaluate(nativeLogToneCurve(512), 0.18), 1e-4)
        assertEquals(0.4106, evaluate(nativeLogToneCurve(512), 0.18), 1e-4)
    }

    @Test
    fun `points are densest near black and the interpolation stays under a tenth of a 10-bit code`() {
        val curve = nativeLogToneCurve(512)
        val xs = pins(curve)
        assertTrue("first interval must be the narrowest region", xs[1] - xs[0] < xs[xs.size - 1] - xs[xs.size - 2])
        var maxError = 0.0
        var x = 0.0
        while (x <= 1.0) {
            maxError = maxOf(maxError, kotlin.math.abs(evaluate(curve, x) - LogProfiles.slog3Oetf(x)))
            x += 1e-4
        }
        assertTrue("max interpolation error $maxError", maxError < 0.1 / 1023.0)
    }

    // ---- stills never log ----

    @Test
    fun `a still never carries the contrast curve`() {
        val modeSets = listOf(
            pma110ToneMapModes,
            intArrayOf(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE),
            intArrayOf(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE, CameraMetadata.TONEMAP_MODE_FAST),
            intArrayOf(),
        )
        for (modes in modeSets) for (session in listOf(false, true)) {
            assertNotEquals(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE, stillToneMapMode(session, modes))
        }
    }

    @Test
    fun `a still inside a native-log session names the stock tone map`() {
        assertEquals(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY, stillToneMapMode(true, pma110ToneMapModes))
        assertEquals(
            CameraMetadata.TONEMAP_MODE_FAST,
            stillToneMapMode(true, intArrayOf(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE, CameraMetadata.TONEMAP_MODE_FAST)),
        )
    }

    @Test
    fun `outside a native-log session the still request is untouched`() {
        // PMA110 photo stills must stay byte-identical to the measured path: no key written.
        assertNull(stillToneMapMode(false, pma110ToneMapModes))
    }
}
