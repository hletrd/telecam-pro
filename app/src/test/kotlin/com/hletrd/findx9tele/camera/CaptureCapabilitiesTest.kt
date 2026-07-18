package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the HAL video-stabilization mode resolution ([videoStabControlModeFor]). */
class CaptureCapabilitiesTest {

    private val off = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    private val on = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
    private val preview = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION

    @Test
    fun `the tele's advertised 0,1,2 resolves every mode directly`() {
        val modes = intArrayOf(off, on, preview)
        assertEquals(off, videoStabControlModeFor(modes, VideoStabMode.OFF))
        assertEquals(on, videoStabControlModeFor(modes, VideoStabMode.STANDARD))
        assertEquals(preview, videoStabControlModeFor(modes, VideoStabMode.ENHANCED))
    }

    @Test
    fun `ENHANCED falls back to ON when preview-stabilization is absent`() {
        assertEquals(on, videoStabControlModeFor(intArrayOf(off, on), VideoStabMode.ENHANCED))
    }

    @Test
    fun `unsupported modes fall back to OFF`() {
        assertEquals(off, videoStabControlModeFor(intArrayOf(off), VideoStabMode.STANDARD))
        assertEquals(off, videoStabControlModeFor(intArrayOf(off), VideoStabMode.ENHANCED))
        assertEquals(off, videoStabControlModeFor(IntArray(0), VideoStabMode.STANDARD))
    }

    @Test
    fun `physical lens EXIF uses the prefetched cache entry`() {
        val fallback = LensExifMetadata(6.5f, 1.5f, 23f)
        val tele = LensExifMetadata(20.1f, 2.26f, 69.4f)

        assertEquals(
            tele,
            resolveLensExifMetadata("4", mapOf("4" to tele), fallback),
        )
    }

    @Test
    fun `physical lens EXIF cache miss uses route metadata without a service resolver`() {
        val fallback = LensExifMetadata(6.5f, 1.5f, 23f)

        assertEquals(
            fallback,
            resolveLensExifMetadata("missing", emptyMap(), fallback),
        )
        assertEquals(fallback, resolveLensExifMetadata(null, emptyMap(), fallback))
    }

    @Test
    fun `lens EXIF metadata derives full-frame equivalent focal length`() {
        val metadata = lensExifMetadataOf(
            focalLengthMm = 20.1f,
            apertureF = 2.26f,
            sensorWidthMm = 12.0f,
            sensorHeightMm = 9.0f,
        )

        assertEquals(20.1f, metadata.focalLengthMm, 0.0001f)
        assertEquals(2.26f, metadata.apertureF, 0.0001f)
        assertEquals(57.977f, metadata.equivalentFocalMm, 0.001f)
    }

    @Test
    fun `still exposure caps-seam clamp holds the device-verified 4s ceiling (TEST4-17)`() {
        // The single most safety-critical constant of cycle 3: this seam is what keeps a 5s+
        // selection (CAMERA_ERROR(3) shot loss) unselectable everywhere downstream.
        // Advertised upper above the ceiling clamps down; the lower rides through.
        assertEquals(
            14_000L to 4_000_000_000L,
            clampStillExposureRange(14_000L, 20_000_000_000L),
        )
        // An in-range advertisement is untouched.
        assertEquals(
            14_000L to 1_000_000_000L,
            clampStillExposureRange(14_000L, 1_000_000_000L),
        )
        // Exactly at the ceiling is allowed (4.0s is the verified-good top stop).
        assertEquals(
            14_000L to 4_000_000_000L,
            clampStillExposureRange(14_000L, 4_000_000_000L),
        )
        // Defensive: a pathological lower above the ceiling clamps too — an inverted
        // Range(lower > upper) throws at construction.
        assertEquals(
            4_000_000_000L to 4_000_000_000L,
            clampStillExposureRange(5_000_000_000L, 20_000_000_000L),
        )
    }
}
