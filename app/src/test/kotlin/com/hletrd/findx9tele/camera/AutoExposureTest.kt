package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the app-side auto-exposure decision logic (the part that has no Android dependency).
 * The Range-clamped [AutoExposure.driveIso]/[driveShutterNs] wrappers are thin and exercised on device.
 */
class AutoExposureTest {

    /** A 256-bin luma histogram with every sample in one bin. */
    private fun histAt(bin: Int, count: Int = 1000): IntArray =
        IntArray(256).also { it[bin.coerceIn(0, 255)] = count }

    @Test
    fun meanLuma_midBin_isHalf() {
        // Bin 128 of 0..255 → ~0.502.
        assertEquals(0.502f, AutoExposure.meanLuma(histAt(128)), 0.01f)
    }

    @Test
    fun meanLuma_black_isZero() {
        assertEquals(0f, AutoExposure.meanLuma(histAt(0)), 0.0001f)
        assertEquals(0f, AutoExposure.meanLuma(IntArray(256)), 0.0001f) // empty → 0, no divide-by-zero
    }

    @Test
    fun meanLuma_white_isOne() {
        assertEquals(1f, AutoExposure.meanLuma(histAt(255)), 0.0001f)
    }

    @Test
    fun correction_darkScene_brightens() {
        // Mean well below the 0.45 target → positive correction (open up).
        val c = AutoExposure.correctionStops(0.15f, 0f)
        assertTrue("expected a correction", c != null)
        assertTrue("dark scene should brighten (positive stops), was $c", c!! > 0f)
    }

    @Test
    fun correction_brightScene_darkens() {
        val c = AutoExposure.correctionStops(0.85f, 0f)
        assertTrue(c != null)
        assertTrue("bright scene should darken (negative stops), was $c", c!! < 0f)
    }

    @Test
    fun correction_onTarget_isNullDeadband() {
        // Mean == target → within the deadband → no update.
        assertNull(AutoExposure.correctionStops(AutoExposure.TARGET_LUMA, 0f))
    }

    @Test
    fun correction_isClampedToOneStop() {
        // A pitch-dark meter must not demand more than one stop per tick.
        val c = AutoExposure.correctionStops(0.001f, 0f)!!
        assertTrue("per-tick step must be <= 1 stop, was $c", c <= 1.0f + 1e-4f)
    }

    @Test
    fun correction_evCompRaisesTarget() {
        // At a fixed mean below target, +1 EV raises the target further above the mean, so the
        // brighten correction is at least as large as with no compensation.
        val base = AutoExposure.correctionStops(0.2f, 0f)!!
        val plus = AutoExposure.correctionStops(0.2f, 1f)!!
        assertTrue("more +EV should brighten at least as much ($plus vs $base)", plus >= base)
    }
}
