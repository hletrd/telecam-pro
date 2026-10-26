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
    private val limit = PMA110_PREVIEW_STABILIZED_STILL_MAX_EXPOSURE_NS

    /** A short (1/125 s) HAL-AWB still unless told otherwise; PMA110's measured limit. */
    private fun mode(
        keeps: Boolean,
        session: Int,
        exposureNs: Long? = 8_000_000L,
        max: Long? = limit,
        wbAppOwned: Boolean = false,
    ) = stillVideoStabilizationMode(keeps, session, exposureNs, max, wbAppOwned)

    @Test
    fun `a following profile writes exactly the session's mode for every session mode`() {
        // Never a third value: the still is framed by the same mode as the finder and the ring,
        // whatever its white balance (the owner's "Auto and manual stills must match").
        for (mode in sessionModes) {
            assertEquals(mode, mode(true, mode))
            assertEquals(mode, mode(true, mode, wbAppOwned = true))
        }
    }

    @Test
    fun `the spec path writes nothing and keeps the still template's own value`() {
        for (mode in sessionModes) assertNull(mode(false, mode))
    }

    @Test
    fun `PMA110 follows the session and GENERIC keeps the spec path`() {
        // The law has no WB term any more: AUTO, presets, manual Kelvin and custom WB alike.
        assertTrue(DeviceProfile.PMA110.stillKeepsSessionStabilization)
        assertFalse(DeviceProfile.GENERIC.stillKeepsSessionStabilization)
        assertEquals(preview, mode(DeviceProfile.PMA110.stillKeepsSessionStabilization, preview))
        assertNull(mode(DeviceProfile.GENERIC.stillKeepsSessionStabilization, preview))
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
            assertTrue(zslStillFramingMatches(mode(true, mode), mode))
            assertEquals(mode == off, zslStillFramingMatches(mode(false, mode), mode))
        }
    }

    @Test
    fun `a HAL-AWB still longer than the measured limit leaves PREVIEW_STABILIZATION for ON`() {
        // PMA110 2026-10-27: 1/2/3/3.2 s stills completed carrying PREVIEW_STABILIZATION, every 4 s
        // one was lost (onCaptureBufferLost); 4 s at ON completed. The limit itself rides Active.
        assertEquals(3_200_000_000L, limit)
        assertEquals(preview, mode(true, preview, exposureNs = limit))
        assertEquals(on, mode(true, preview, exposureNs = limit + 1))
        assertEquals(on, mode(true, preview, exposureNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS))
    }

    @Test
    fun `an app-owned white balance keeps the session's mode even past the limit`() {
        // A TRANSFORM_MATRIX still at ON or OFF inside an Active session renders another white
        // balance (3200 K came out deep blue / cyan, PMA110 2026-10-27), so no mode change rescues
        // it: it keeps PREVIEW_STABILIZATION and stays lost — the open owner decision, not a quiet
        // wrong-colour file.
        assertEquals(preview, mode(true, preview, exposureNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS, wbAppOwned = true))
        assertEquals(on, mode(true, on, exposureNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS, wbAppOwned = true))
    }

    @Test
    fun `the long-still exception touches only an Active session with a known exposure and limit`() {
        val long = HAL_SAFE_MAX_STILL_EXPOSURE_NS
        // Standard and Off sessions keep their own mode at any exposure (4 s at ON measured fine).
        assertEquals(on, mode(true, on, exposureNs = long))
        assertEquals(off, mode(true, off, exposureNs = long))
        // HAL AE owns the exposure (null): never seconds long, keeps the session's mode.
        assertEquals(preview, mode(true, preview, exposureNs = null))
        // No measured limit: the session's mode at any exposure.
        assertEquals(preview, mode(true, preview, exposureNs = long, max = null))
        // The spec path never writes the key, long or short, whatever the white balance.
        assertNull(mode(false, preview, exposureNs = long))
        assertNull(mode(false, preview, exposureNs = long, wbAppOwned = true))
    }

    @Test
    fun `PMA110 carries the measured limit and GENERIC none`() {
        assertEquals(limit, DeviceProfile.PMA110.previewStabilizedStillMaxExposureNs)
        assertNull(DeviceProfile.GENERIC.previewStabilizedStillMaxExposureNs)
    }

    @Test
    fun `a servable still always matches the ring on PMA110`() {
        // Pseudo-ZSL serves only sub-1/15 s frames, far below the limit, so the long-still exception
        // can never make a following profile refuse a frame it would otherwise serve.
        val zslCeilingNs = 66_666_667L
        for (session in sessionModes) {
            for (appOwned in listOf(false, true)) {
                assertTrue(zslStillFramingMatches(mode(true, session, exposureNs = zslCeilingNs, wbAppOwned = appOwned), session))
            }
        }
    }
}
