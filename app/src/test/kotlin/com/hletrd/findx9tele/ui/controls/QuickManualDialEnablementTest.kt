package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickManualDialEnablementTest {

    @Test
    fun `supported hardware exposes all automatic-to-manual entry points`() {
        for (type in listOf(DialType.FOCUS, DialType.SHUTTER, DialType.ISO)) {
            assertTrue(
                quickManualDialEnabled(
                    type = type,
                    supportsManualFocus = true,
                    supportsManualSensor = true,
                    hasExposureTimeRange = true,
                    hasIsoRange = true,
                ),
            )
        }
    }

    @Test
    fun `focus follows manual-focus capability only`() {
        assertTrue(enabled(DialType.FOCUS, focus = true, sensor = false, exposure = false, iso = false))
        assertFalse(enabled(DialType.FOCUS, focus = false, sensor = true, exposure = true, iso = true))
    }

    @Test
    fun `shutter requires manual sensor and exposure range`() {
        assertTrue(enabled(DialType.SHUTTER, sensor = true, exposure = true))
        assertFalse(enabled(DialType.SHUTTER, sensor = false, exposure = true))
        assertFalse(enabled(DialType.SHUTTER, sensor = true, exposure = false))
    }

    @Test
    fun `iso requires manual sensor and sensitivity range`() {
        assertTrue(enabled(DialType.ISO, sensor = true, iso = true))
        assertFalse(enabled(DialType.ISO, sensor = false, iso = true))
        assertFalse(enabled(DialType.ISO, sensor = true, iso = false))
    }

    @Test
    fun `unrelated quick dials retain their own enablement rules`() {
        for (type in listOf(DialType.WB, DialType.EV, DialType.ZOOM)) {
            assertTrue(enabled(type))
        }
    }

    private fun enabled(
        type: DialType,
        focus: Boolean = false,
        sensor: Boolean = false,
        exposure: Boolean = false,
        iso: Boolean = false,
    ): Boolean = quickManualDialEnabled(
        type = type,
        supportsManualFocus = focus,
        supportsManualSensor = sensor,
        hasExposureTimeRange = exposure,
        hasIsoRange = iso,
    )
}
