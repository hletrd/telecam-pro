package com.hletrd.findx9tele.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the hardware-glide per-tick math extracted from the ViewModel ticker: log-space exponential
 * approach, exact landing inside the epsilon, and the non-finite/non-positive guards that once let
 * a corrupted persisted ratio keep a NaN ticker re-posting at ~30 Hz forever.
 */
class ZoomEaseStepTest {

    private fun glideToLanding(start: Float, target: Float, maxTicks: Int = 200): Pair<Float, Int> {
        var cur = start
        var ticks = 0
        while (ticks < maxTicks) {
            when (val step = zoomEaseStep(cur, target)) {
                is ZoomEaseStep.Land -> return step.target to ticks
                is ZoomEaseStep.Step -> {
                    cur = step.value
                    ticks++
                }
            }
        }
        error("glide from $start to $target did not land within $maxTicks ticks (at $cur)")
    }

    @Test
    fun `zoom-in glide is monotonic and lands exactly on the target`() {
        var cur = 1f
        var previous = cur
        repeat(200) {
            when (val step = zoomEaseStep(cur, 10f)) {
                is ZoomEaseStep.Land -> {
                    assertEquals(10f, step.target, 0f)
                    return
                }
                is ZoomEaseStep.Step -> {
                    assertTrue("step ${step.value} must increase past $previous", step.value > previous)
                    assertTrue("step ${step.value} must not overshoot the target", step.value < 10f)
                    previous = step.value
                    cur = step.value
                }
            }
        }
        error("zoom-in glide never landed")
    }

    @Test
    fun `zoom-out glide is monotonic and lands exactly on the target`() {
        var cur = 8f
        var previous = cur
        repeat(200) {
            when (val step = zoomEaseStep(cur, 1f)) {
                is ZoomEaseStep.Land -> {
                    assertEquals(1f, step.target, 0f)
                    return
                }
                is ZoomEaseStep.Step -> {
                    assertTrue("step ${step.value} must decrease past $previous", step.value < previous)
                    assertTrue("step ${step.value} must not undershoot the target", step.value > 1f)
                    previous = step.value
                    cur = step.value
                }
            }
        }
        error("zoom-out glide never landed")
    }

    @Test
    fun `glide converges within a bounded tick count from far starts`() {
        // 0.4 exponent → 60% of the log-distance is consumed per tick; even 0.6→60x lands fast.
        val (landed, ticks) = glideToLanding(0.6f, 60f)
        assertEquals(60f, landed, 0f)
        assertTrue("expected < 40 ticks, took $ticks", ticks < 40)
    }

    @Test
    fun `equal current and target lands immediately`() {
        val step = zoomEaseStep(3.5f, 3.5f)
        assertTrue(step is ZoomEaseStep.Land)
        assertEquals(3.5f, (step as ZoomEaseStep.Land).target, 0f)
    }

    @Test
    fun `non-finite or non-positive current lands on the target instead of ticking`() {
        for (bad in floatArrayOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY)) {
            val step = zoomEaseStep(bad, 4f)
            assertTrue("current=$bad must land", step is ZoomEaseStep.Land)
            assertEquals(4f, (step as ZoomEaseStep.Land).target, 0f)
        }
    }

    @Test
    fun `NaN target lands rather than looping forever`() {
        // (target/current)^0.4 is NaN → the next-value guard must land; the caller then applies the
        // target once and the apply-side clamp resolves it (matching the pre-extraction behavior).
        val step = zoomEaseStep(2f, Float.NaN)
        assertTrue(step is ZoomEaseStep.Land)
    }
}
