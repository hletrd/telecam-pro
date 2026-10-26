package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins still framing = preview framing (owner decision 2026-10-27, FIELD_CHECKS A23): on a profile
 * whose stills keep the session's stabilization, EVERY still request writes exactly the session's
 * mode; elsewhere a still writes nothing (the template's own value), and pseudo-ZSL may only serve
 * a ring frame framed like the real still.
 */
class StillFramingTest {
    private val off = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    private val on = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
    private val preview = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    private val sessionModes = listOf(off, on, preview)

    @Test
    fun `a following profile writes exactly the session's mode for every session mode`() {
        // Never a third value: the still is framed by the same mode as the finder and the ring.
        for (mode in sessionModes) assertEquals(mode, stillVideoStabilizationMode(true, mode))
    }

    @Test
    fun `the spec path writes nothing and keeps the still template's own value`() {
        for (mode in sessionModes) assertNull(stillVideoStabilizationMode(false, mode))
    }

    @Test
    fun `PMA110 follows the session and GENERIC keeps the spec path`() {
        // The law has no WB term any more: AUTO, presets, manual Kelvin and custom WB alike.
        assertTrue(DeviceProfile.PMA110.stillKeepsSessionStabilization)
        assertFalse(DeviceProfile.GENERIC.stillKeepsSessionStabilization)
        assertEquals(preview, stillVideoStabilizationMode(DeviceProfile.PMA110.stillKeepsSessionStabilization, preview))
        assertNull(stillVideoStabilizationMode(DeviceProfile.GENERIC.stillKeepsSessionStabilization, preview))
    }

    @Test
    fun `a ring frame matches the real still only at the same stabilization mode`() {
        for (ring in sessionModes) {
            for (still in sessionModes) {
                assertEquals("still=$still ring=$ring", still == ring, zslStillFramingMatches(still, ring))
            }
        }
    }

    @Test
    fun `a template-valued still reads as OFF, so it matches only an unstabilized ring`() {
        assertTrue(zslStillFramingMatches(null, off))
        assertFalse(zslStillFramingMatches(null, on))
        assertFalse(zslStillFramingMatches(null, preview))
    }

    @Test
    fun `a following profile always matches its own ring, the spec path only while the session is OFF`() {
        for (mode in sessionModes) {
            assertTrue(zslStillFramingMatches(stillVideoStabilizationMode(true, mode), mode))
            assertEquals(mode == off, zslStillFramingMatches(stillVideoStabilizationMode(false, mode), mode))
        }
    }
}
