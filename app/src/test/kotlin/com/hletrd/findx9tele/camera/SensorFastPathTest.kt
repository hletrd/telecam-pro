package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sensor fast-path admission predicate: ONLY the high-churn sensor scalars (manual focus
 * distance, ISO, exposure time) may ride the cached-builder resubmit; any other field difference —
 * including fields added to ManualControls in the future, via data-class equality — must take the
 * full request rebuild.
 */
class SensorFastPathTest {

    private val base = ManualControls()

    @Test
    fun `identical packets do not fast-path`() {
        assertFalse(sensorOnlyControlsDelta(base, base.copy()))
    }

    @Test
    fun `each sensor scalar alone rides the fast path`() {
        assertTrue(sensorOnlyControlsDelta(base, base.copy(iso = base.iso + 100)))
        assertTrue(sensorOnlyControlsDelta(base, base.copy(exposureTimeNs = base.exposureTimeNs + 1_000L)))
        assertTrue(sensorOnlyControlsDelta(base, base.copy(focusDistanceDiopters = base.focusDistanceDiopters + 0.5f)))
    }

    @Test
    fun `the app-side AE pair rides the fast path as one packet`() {
        assertTrue(
            sensorOnlyControlsDelta(
                base,
                base.copy(iso = base.iso + 200, exposureTimeNs = base.exposureTimeNs * 2),
            ),
        )
    }

    @Test
    fun `live tap-to-focus refuses the fast path - its AF override lives outside applyManualControls`() {
        // The full rebuild sets AF_MODE_AUTO for the tapped one-shot hold AFTER applyManualControls;
        // the fast path re-runs applyFocus, whose unconditional AF_MODE write would release the
        // tapped focus within ~100 ms of app-side AE ticks (the architect-caught regression).
        val delta = base.copy(iso = base.iso + 100)
        assertTrue(sensorFastPathAdmitted(base, delta, touchAfActive = false))
        assertFalse(sensorFastPathAdmitted(base, delta, touchAfActive = true))
    }

    @Test
    fun `AF lock refuses the fast path - its frozen distance override lives outside applyManualControls`() {
        val locked = base.copy(afLock = true)
        val delta = locked.copy(exposureTimeNs = locked.exposureTimeNs * 2)
        assertFalse(sensorFastPathAdmitted(locked, delta, touchAfActive = false))
    }

    @Test
    fun `any non-sensor field difference forces the full rebuild`() {
        assertFalse(sensorOnlyControlsDelta(base, base.copy(zoomRatio = base.zoomRatio + 1f)))
        assertFalse(
            sensorOnlyControlsDelta(
                base,
                base.copy(focusMode = FocusMode.MANUAL, focusDistanceDiopters = 1f),
            ),
        )
        assertFalse(
            sensorOnlyControlsDelta(
                base,
                base.copy(iso = base.iso + 100, wbMode = WbMode.MANUAL),
            ),
        )
    }
}
