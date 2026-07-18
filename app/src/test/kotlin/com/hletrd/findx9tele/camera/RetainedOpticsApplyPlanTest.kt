package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedOpticsApplyPlanTest {

    private val base = ManualControls()

    @Test
    fun `active boost always takes one full rebuild even when controls are unchanged`() {
        assertEquals(
            RetainedOpticsApplyPlan.FULL_REBUILD,
            retainedOpticsApplyPlan(base, base, smoothPreviewBoostActive = true),
        )
        assertEquals(
            RetainedOpticsApplyPlan.FULL_REBUILD,
            retainedOpticsApplyPlan(
                base,
                base.copy(zoomRatio = 3f),
                smoothPreviewBoostActive = true,
            ),
        )
    }

    @Test
    fun `idle zoom-only optics remap keeps the exact fast path`() {
        val zoomed = base.copy(zoomRatio = 3f)

        assertTrue(zoomOnlyControlsDelta(base, zoomed))
        assertEquals(
            RetainedOpticsApplyPlan.ZOOM_FAST_PATH,
            retainedOpticsApplyPlan(base, zoomed, smoothPreviewBoostActive = false),
        )
    }

    @Test
    fun `idle sensor-only packet keeps the sensor fast path`() {
        assertEquals(
            RetainedOpticsApplyPlan.SENSOR_FAST_PATH,
            retainedOpticsApplyPlan(
                base,
                base.copy(iso = base.iso + 100),
                smoothPreviewBoostActive = false,
            ),
        )
    }

    @Test
    fun `idle identical packet is a no-op and broad packet rebuilds`() {
        assertEquals(
            RetainedOpticsApplyPlan.NO_OP,
            retainedOpticsApplyPlan(base, base.copy(), smoothPreviewBoostActive = false),
        )
        val broad = base.copy(zoomRatio = 3f, wbMode = WbMode.MANUAL)
        assertFalse(zoomOnlyControlsDelta(base, broad))
        assertEquals(
            RetainedOpticsApplyPlan.FULL_REBUILD,
            retainedOpticsApplyPlan(base, broad, smoothPreviewBoostActive = false),
        )
    }
}
