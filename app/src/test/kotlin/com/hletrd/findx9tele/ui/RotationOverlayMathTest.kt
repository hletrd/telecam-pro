package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.AutoExposure
import com.hletrd.findx9tele.camera.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the pure overlay-rotation math in CameraScreen.kt — the documented already-shipped-wrong-once
 * zone. [shortestRotationTarget] must always take the <=180-degree way around while accumulating an
 * unwrapped target. Also pins [manualMeterEv]'s guard branches and clamp.
 */
class RotationOverlayMathTest {

    /** A 256-bin luma histogram with every sample in one bin. */
    private fun histAt(bin: Int, count: Int = 1000): IntArray =
        IntArray(256).also { it[bin.coerceIn(0, 255)] = count }

    // ---- shortestRotationTarget ----

    @Test
    fun rotationTarget_takesShortestWayEachQuadrant() {
        assertEquals(90f, shortestRotationTarget(0f, 90f), 1e-4f)   // 0 -> 90 = +90
        assertEquals(0f, shortestRotationTarget(90f, 0f), 1e-4f)    // 90 -> 0 = -90
        assertEquals(360f, shortestRotationTarget(270f, 0f), 1e-4f) // 270 -> 0 wraps +90 (to 360)
        assertEquals(-90f, shortestRotationTarget(0f, 270f), 1e-4f) // 0 -> 270 = -90 (not +270)
        assertEquals(370f, shortestRotationTarget(350f, 10f), 1e-4f) // 350 -> 10 = +20 (not -340)
    }

    @Test
    fun rotationTarget_accumulatesUnwrapped() {
        // From an already-accumulated 360, a desired 90 keeps climbing to 450 (not snapping to 90).
        assertEquals(450f, shortestRotationTarget(360f, 90f), 1e-4f)
    }

    @Test
    fun rotationTarget_neverMovesMoreThan180() {
        val quadrants = listOf(0f, 90f, 180f, 270f)
        for (current in quadrants) {
            for (desired in quadrants) {
                val delta = shortestRotationTarget(current, desired) - current
                assertTrue("delta $delta for $current->$desired must be <=180", abs(delta) <= 180f + 1e-4f)
            }
        }
    }

    // ---- manualMeterEv ----

    @Test
    fun manualMeterEv_nonManualIsNull() {
        assertNull(manualMeterEv(ExposureMode.PROGRAM, histAt(200)))
        assertNull(manualMeterEv(ExposureMode.SHUTTER, histAt(200)))
        assertNull(manualMeterEv(ExposureMode.ISO, histAt(200)))
    }

    @Test
    fun manualMeterEv_nullLumaIsNull() {
        assertNull(manualMeterEv(ExposureMode.MANUAL, null))
    }

    @Test
    fun manualMeterEv_emptyHistogramIsNull_noDivideByZero() {
        // No samples anywhere: total == 0 -> null (guards the divide-by-zero in the mean).
        assertNull(manualMeterEv(ExposureMode.MANUAL, IntArray(256)))
    }

    @Test
    fun manualMeterEv_darkClampsToMinusThree() {
        // All mass at bin 0: mean floors to 0.001, log2(0.001/TARGET) is far below -3 -> clamps to -3.
        assertEquals(-3f, manualMeterEv(ExposureMode.MANUAL, histAt(0))!!, 1e-4f)
    }

    @Test
    fun manualMeterEv_brightIsPositiveWithinBounds() {
        // Max mean is 1.0 (bin 255); log2(1.0/0.45) ~= +1.15, so a fully-bright frame reads POSITIVE
        // but does NOT reach the +3 clamp (the clamp bound, not the value, is what's pinned here).
        val ev = manualMeterEv(ExposureMode.MANUAL, histAt(255))!!
        assertTrue("bright frame reads positive, was $ev", ev > 0f)
        assertTrue("stays within the +/-3 clamp, was $ev", ev in -3f..3f)
    }

    @Test
    fun manualMeterEv_atTargetIsNearZero() {
        // A frame metered right at the mid-grey target reads ~0 EV.
        val targetBin = (AutoExposure.TARGET_LUMA * 255f).toInt()
        val ev = manualMeterEv(ExposureMode.MANUAL, histAt(targetBin))!!
        assertEquals(0f, ev, 0.05f)
    }
}
