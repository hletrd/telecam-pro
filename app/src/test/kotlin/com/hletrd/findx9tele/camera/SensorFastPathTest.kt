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
    fun `a sensor-only delta rides the fast path regardless of AF overrides`() {
        // Live tap-AF/AF-lock no longer refuses admission: the controller re-applies the override
        // keys onto the cached builder AFTER applySensorValueControls (reapplyAfOverrides), so the
        // key state equals the full rebuild's without the ~180 ms swap stall. The old wholesale
        // refusal re-created the ~5 fps dim-light preview whenever the app-side AE loop ran with a
        // held tap-AF — the exact starvation the fast path exists to remove. The device check for
        // "tapped focus HOLDS across an ISO change" remains the on-device regression gate.
        val delta = base.copy(iso = base.iso + 100)
        assertTrue(sensorFastPathAdmitted(base, delta))
        val locked = base.copy(afLock = true)
        assertTrue(sensorFastPathAdmitted(locked, locked.copy(exposureTimeNs = locked.exposureTimeNs * 2)))
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
