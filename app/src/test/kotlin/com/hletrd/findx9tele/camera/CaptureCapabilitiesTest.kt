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
}
