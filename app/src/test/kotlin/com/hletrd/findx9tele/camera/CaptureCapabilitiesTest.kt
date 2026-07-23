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
    fun `vendor hi-res needs at least twice the active-array area`() {
        // The active array IS the binned readout: near-array sizes (the 4096×3072-vs-4080×3064
        // class) must NOT read as remosaic, while a genuine 4×-area full-sensor size must.
        val binnedW = 4080
        val binnedH = 3064
        val candidates = listOf(
            4096 to 3072, // slightly above array — ordinary still size, below 2×
            binnedW to binnedH,
            16320 to 12240, // 4× area — the remosaic size
        )
        assertEquals(16320 to 12240, pickVendorHiResSize(candidates, binnedW, binnedH))
        assertEquals(
            null,
            pickVendorHiResSize(listOf(4096 to 3072, binnedW to binnedH), binnedW, binnedH),
        )
    }

    @Test
    fun `vendor hi-res threshold is inclusive at exactly 2x`() {
        // Exactly-2× must qualify (the predicate is ≥, not >).
        val exact = pickVendorHiResSize(listOf(200 to 100), activeArrayW = 100, activeArrayH = 100)
        assertEquals(200 to 100, exact)
    }

    @Test
    fun `vendor hi-res picks the largest qualifying candidate`() {
        assertEquals(
            400 to 300,
            pickVendorHiResSize(
                listOf(200 to 150, 400 to 300, 250 to 200),
                activeArrayW = 100,
                activeArrayH = 100,
            ),
        )
    }

    @Test
    fun `vendor hi-res is null for empty candidates or an unknown active array`() {
        assertEquals(null, pickVendorHiResSize(emptyList(), 4080, 3064))
        // Without the binned baseline the 2× test is meaningless; guessing would admit a
        // session-crashing blob allocation.
        assertEquals(null, pickVendorHiResSize(listOf(16320 to 12240), 0, 0))
        assertEquals(null, pickVendorHiResSize(listOf(16320 to 12240), 4080, 0))
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
    fun `degenerate lens or sensor geometry yields a zero equivalent focal`() {
        // A zero focal length or an empty sensor diagonal cannot produce a truthful 35mm-equiv;
        // EXIF gets 0 rather than an invented number.
        assertEquals(
            0f,
            lensExifMetadataOf(
                focalLengthMm = 0f,
                apertureF = 2.26f,
                sensorWidthMm = 12.0f,
                sensorHeightMm = 9.0f,
            ).equivalentFocalMm,
            0f,
        )
        assertEquals(
            0f,
            lensExifMetadataOf(
                focalLengthMm = 20.1f,
                apertureF = 2.26f,
                sensorWidthMm = 0f,
                sensorHeightMm = 0f,
            ).equivalentFocalMm,
            0f,
        )
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
