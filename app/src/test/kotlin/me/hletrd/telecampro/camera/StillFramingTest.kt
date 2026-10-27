package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins still framing = preview framing (owner decision 2026-10-27, FIELD_CHECKS A23): on EVERY
 * profile a still request writes exactly the session's mode (PMA110 and, since 2026-10-27, the spec
 * path measured on the Galaxy Z Flip6), apart from PMA110's long HAL-AWB Active still, and
 * pseudo-ZSL may only serve a ring frame framed like the real still.
 */
class StillFramingTest {
    private val off = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    private val on = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
    private val preview = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    private val sessionModes = listOf(off, on, preview)
    private val limit = PMA110_PREVIEW_STABILIZED_STILL_MAX_EXPOSURE_NS

    /** A short (1/125 s) HAL-AWB still unless told otherwise; PMA110's measured limit. */
    private fun mode(
        session: Int,
        exposureNs: Long? = 8_000_000L,
        max: Long? = limit,
        wbAppOwned: Boolean = false,
    ) = stillVideoStabilizationMode(session, exposureNs, max, wbAppOwned)

    @Test
    fun `every still writes exactly the session's mode for every session mode`() {
        // Never a third value: the still is framed by the same mode as the finder and the ring,
        // whatever its white balance (the owner's "Auto and manual stills must match").
        for (mode in sessionModes) {
            assertEquals(mode, mode(mode))
            assertEquals(mode, mode(mode, wbAppOwned = true))
        }
    }

    @Test
    fun `PMA110 and GENERIC both follow the session at a servable exposure`() {
        // GENERIC no longer leaves the key to the still template, which carries OFF on both devices
        // read: the Flip6's stabilized finder was then 24 % narrower than its stills (A23).
        for (profile in listOf(DeviceProfile.PMA110, DeviceProfile.GENERIC)) {
            for (mode in sessionModes) {
                assertEquals(mode, mode(mode, max = profile.previewStabilizedStillMaxExposureNs))
            }
        }
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
    fun `a still at a servable exposure always matches its own session's ring`() {
        // So a stabilized spec session may serve a ring frame now; it refused on framing before.
        for (mode in sessionModes) {
            assertTrue(zslStillFramingMatches(mode(mode), mode))
            assertTrue(zslStillFramingMatches(mode(mode, max = null), mode))
        }
    }

    @Test
    fun `a long PMA110 Active still riding ON is framed unlike the ring`() {
        assertFalse(zslStillFramingMatches(mode(preview, exposureNs = limit + 1), preview))
    }

    @Test
    fun `a HAL-AWB still longer than the measured limit leaves PREVIEW_STABILIZATION for ON`() {
        // PMA110 2026-10-27: 1/2/3/3.2 s stills completed carrying PREVIEW_STABILIZATION, every 4 s
        // one was lost (onCaptureBufferLost); 4 s at ON completed. The limit itself rides Active.
        assertEquals(3_200_000_000L, limit)
        assertEquals(preview, mode(preview, exposureNs = limit))
        assertEquals(on, mode(preview, exposureNs = limit + 1))
        assertEquals(on, mode(preview, exposureNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS))
    }

    @Test
    fun `an app-owned white balance keeps the session's mode even past the limit`() {
        // A TRANSFORM_MATRIX still at ON or OFF inside an Active session renders another white
        // balance (3200 K came out deep blue / cyan, PMA110 2026-10-27), so no mode change rescues
        // it: it keeps PREVIEW_STABILIZATION and stays lost — the open owner decision, not a quiet
        // wrong-colour file.
        assertEquals(preview, mode(preview, exposureNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS, wbAppOwned = true))
        assertEquals(on, mode(on, exposureNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS, wbAppOwned = true))
    }

    @Test
    fun `the long-still exception touches only an Active session with a known exposure and limit`() {
        val long = HAL_SAFE_MAX_STILL_EXPOSURE_NS
        // Standard and Off sessions keep their own mode at any exposure (4 s at ON measured fine).
        assertEquals(on, mode(on, exposureNs = long))
        assertEquals(off, mode(off, exposureNs = long))
        // HAL AE owns the exposure (null): never seconds long, keeps the session's mode.
        assertEquals(preview, mode(preview, exposureNs = null))
        // No measured limit (GENERIC): the session's mode at any exposure, whatever the white balance.
        assertEquals(preview, mode(preview, exposureNs = long, max = null))
        assertEquals(preview, mode(preview, exposureNs = long, max = null, wbAppOwned = true))
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
                assertTrue(zslStillFramingMatches(mode(session, exposureNs = zslCeilingNs, wbAppOwned = appOwned), session))
            }
        }
    }
}
