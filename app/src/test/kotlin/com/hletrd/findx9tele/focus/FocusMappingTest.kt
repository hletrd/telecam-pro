package com.hletrd.findx9tele.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FocusMapping], a pure Kotlin object with no Android framework dependencies.
 */
class FocusMappingTest {

    private val delta = 1e-3f

    @Test
    fun `sliderToDiopters at 0 is infinity end (0 diopters) for any positive min`() {
        val mins = listOf(1f, 5f, 10f, 25f, 100f)
        for (min in mins) {
            assertEquals("min=$min", 0f, FocusMapping.sliderToDiopters(0f, min), delta)
        }
    }

    @Test
    fun `sliderToDiopters at 1 equals minFocusDiopters (closest)`() {
        val mins = listOf(1f, 5f, 10f, 25f, 100f)
        for (min in mins) {
            assertEquals("min=$min", min, FocusMapping.sliderToDiopters(1f, min), delta)
        }
    }

    @Test
    fun `sliderToDiopters is monotonically non-decreasing over slider range`() {
        val min = 10f
        var previous = FocusMapping.sliderToDiopters(0f, min)
        var slider = 0.0f
        while (slider <= 1.0f) {
            val current = FocusMapping.sliderToDiopters(slider, min)
            assertTrue(
                "diopters should be non-decreasing at slider=$slider (prev=$previous, current=$current)",
                current >= previous - delta
            )
            previous = current
            slider += 0.01f
        }
    }

    @Test
    fun `round trip slider to diopters and back approximates original slider`() {
        val min = 10f
        val sliders = listOf(0.05f, 0.1f, 0.25f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f, 0.95f)
        for (s in sliders) {
            val diopters = FocusMapping.sliderToDiopters(s, min)
            val roundTripped = FocusMapping.dioptersToSlider(diopters, min)
            assertEquals("s=$s", s, roundTripped, 1e-3f)
        }
    }

    @Test
    fun `fixed focus lens (minFocusDiopters less than or equal to 0) always maps to 0`() {
        val nonPositiveMins = listOf(0f, -1f, -10f)
        for (min in nonPositiveMins) {
            assertEquals("min=$min", 0f, FocusMapping.sliderToDiopters(0.5f, min), delta)
            assertEquals("min=$min", 0f, FocusMapping.sliderToDiopters(1f, min), delta)
            assertEquals("min=$min", 0f, FocusMapping.dioptersToSlider(5f, min), delta)
            assertEquals("min=$min", 0f, FocusMapping.dioptersToSlider(0f, min), delta)
        }
    }

    @Test
    fun `gamma greater than 1 biases resolution toward the near-infinity end`() {
        val min = 10f
        val diopters = FocusMapping.sliderToDiopters(0.5f, min, gamma = 3f)
        // 0.5^gamma < 0.5 for gamma > 1, so diopters should be well below the midpoint.
        assertTrue(
            "expected diopters ($diopters) < 0.5 * min (${0.5f * min}) for gamma > 1",
            diopters < 0.5f * min
        )
    }

    @Test
    fun `dioptersToMeters maps 0 diopters to positive infinity`() {
        assertEquals(Float.POSITIVE_INFINITY, FocusMapping.dioptersToMeters(0f))
    }

    @Test
    fun `dioptersToMeters maps negative diopters to positive infinity`() {
        assertEquals(Float.POSITIVE_INFINITY, FocusMapping.dioptersToMeters(-1f))
    }

    @Test
    fun `dioptersToMeters converts 2 diopters to 0point5 meters`() {
        assertEquals(0.5f, FocusMapping.dioptersToMeters(2f), delta)
    }

    @Test
    fun `slider inputs below 0 are clamped to 0`() {
        val min = 10f
        assertEquals(
            FocusMapping.sliderToDiopters(0f, min),
            FocusMapping.sliderToDiopters(-1f, min),
            delta
        )
        assertEquals(
            FocusMapping.sliderToDiopters(0f, min),
            FocusMapping.sliderToDiopters(-100f, min),
            delta
        )
    }

    @Test
    fun `slider inputs above 1 are clamped to 1`() {
        val min = 10f
        assertEquals(
            FocusMapping.sliderToDiopters(1f, min),
            FocusMapping.sliderToDiopters(2f, min),
            delta
        )
        assertEquals(
            FocusMapping.sliderToDiopters(1f, min),
            FocusMapping.sliderToDiopters(100f, min),
            delta
        )
    }

    @Test
    fun `dioptersToSlider clamps diopters ratio outside 0 to 1`() {
        val min = 10f
        // diopters greater than min should clamp to slider 1
        assertEquals(1f, FocusMapping.dioptersToSlider(min * 2f, min), delta)
        // negative diopters should clamp to slider 0
        assertEquals(0f, FocusMapping.dioptersToSlider(-5f, min), delta)
    }
}
