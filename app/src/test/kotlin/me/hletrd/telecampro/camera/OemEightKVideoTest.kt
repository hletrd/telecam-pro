package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import me.hletrd.telecampro.R
import me.hletrd.telecampro.ui.ViewModelTestAccess
import me.hletrd.telecampro.ui.controls.noStillOutputCaption
import me.hletrd.telecampro.video.EncoderFormatSupport
import me.hletrd.telecampro.video.EncoderSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The OEM 8K route is offered only where measured, only as measured, and never by the camera cache. */
@RunWith(RobolectricTestRunner::class)
class OemEightKVideoTest {
    private val uhd = Size(3840, 2160)
    private val route = TeleSelection("2", null, 23f)

    // An encoder that accepts the rotated 8K raster at every standard rate up to 30.
    private val candidates8k = listOf(
        EncoderSelection(
            codec = VideoCodec.HEVC,
            codecName = "test.hevc",
            mime = "video/hevc",
            hardwareAccelerated = true,
            main10 = true,
            formatSupport = EncoderFormatSupport { format ->
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                (width == 7680 && height == 4320) || (width == 4320 && height == 7680) ||
                    (width <= 4096 && height <= 4096)
            },
        ),
    )

    private val rawCaps: CameraCaps = ViewModelTestAccess.caps().copy(
        availableFpsRanges = arrayOf(Range(24, 24), Range(30, 30), Range(60, 60)),
        availableVideoSizes = listOf(uhd),
        openGateVideoSizes = listOf(Size(4096, 3072)),
    )

    @Test
    fun `the raster joins the route only when admitted and never twice`() {
        assertSame(rawCaps, withOemEightKVideoSize(rawCaps, admitted = false))

        val injected = withOemEightKVideoSize(rawCaps, admitted = true)
        assertEquals(listOf(OEM_8K_VIDEO_SIZE, uhd), injected.availableVideoSizes)
        assertEquals(setOf(30), injected.measuredExtendedVideoFps[OEM_8K_VIDEO_SIZE])
        // 16:9 only: Open Gate never receives it.
        assertEquals(rawCaps.openGateVideoSizes, injected.openGateVideoSizes)
        assertSame(injected, withOemEightKVideoSize(injected, admitted = true))
    }

    @Test
    fun `projection keeps only the measured 30 parent rates on the injected raster`() {
        val projected = projectRecordableVideoSizes(
            raw = withOemEightKVideoSize(rawCaps, admitted = true),
            candidates = candidates8k,
            rawLog = false,
        )

        assertEquals(listOf(OEM_8K_VIDEO_SIZE, uhd), projected.availableVideoSizes)
        // The encoder also accepts 23.976/24/25 there; the camera route was measured at [30,30] only.
        assertEquals(setOf(29.97, 30.0), projected.extendedVideoSizeRates[OEM_8K_VIDEO_SIZE])
        assertEquals(
            listOf(VideoFrameRate.FPS_29_97, VideoFrameRate.FPS_30).sortedBy { it.ordinal },
            VideoFrameRate.availableFor(projected, OEM_8K_VIDEO_SIZE, VideoCodec.HEVC).sortedBy { it.ordinal },
        )
    }

    @Test
    fun `an encoder without the exact 8K format still removes the injected raster`() {
        val no8k = candidates8k.map { selection ->
            selection.copy(
                formatSupport = EncoderFormatSupport { format ->
                    format.getInteger(MediaFormat.KEY_WIDTH) <= 4096 && format.getInteger(MediaFormat.KEY_HEIGHT) <= 4096
                },
            )
        }
        val projected = projectRecordableVideoSizes(
            raw = withOemEightKVideoSize(rawCaps, admitted = true),
            candidates = no8k,
            rawLog = false,
        )

        assertEquals(listOf(uhd), projected.availableVideoSizes)
    }

    @Test
    fun `a 60 fps request resolves onto the measured 8K rate`() {
        val projected = projectRecordableVideoSizes(
            raw = withOemEightKVideoSize(rawCaps, admitted = true),
            candidates = candidates8k,
            rawLog = false,
        )
        val intent = resolveVideoSessionIntent(
            requested = VideoSessionRequest(
                route = route,
                codec = VideoCodec.HEVC,
                transfer = ColorTransfer.SDR,
                rawLogRequested = false,
                openGate = false,
                requestedSize = OEM_8K_VIDEO_SIZE,
                automaticSize = uhd,
                frameRate = VideoFrameRate.FPS_60,
                rawRequiresStandalone = true,
                contentRotation = 0,
            ),
            caps = projected,
            candidates = candidates8k,
        )

        assertNotNull(intent)
        intent!!
        assertEquals(OEM_8K_VIDEO_SIZE, intent.streamSize)
        assertEquals(30, intent.frameRate.fps)
        assertTrue(oemEightKSessionWanted(routeAdmitted = true, intent = intent))
        assertFalse(oemEightKSessionWanted(routeAdmitted = false, intent = intent))
        assertFalse(oemEightKSessionWanted(routeAdmitted = true, intent = intent.copy(streamSize = uhd)))
        assertFalse(oemEightKSessionWanted(routeAdmitted = true, intent = intent.copy(transfer = ColorTransfer.SLOG3)))
        assertFalse(oemEightKSessionWanted(routeAdmitted = true, intent = intent.copy(codec = VideoCodec.AVC)))
        assertFalse(oemEightKSessionWanted(routeAdmitted = true, intent = null))
    }

    @Test
    fun `the route needs the profile, the back camera, a standalone id and a host lens`() {
        assertTrue(oemEightKRouteAdmitted(profileAdmits = true, backRoute = true, standalone = true, hostLens = true))
        assertFalse(oemEightKRouteAdmitted(profileAdmits = false, backRoute = true, standalone = true, hostLens = true))
        assertFalse(oemEightKRouteAdmitted(profileAdmits = true, backRoute = false, standalone = true, hostLens = true))
        assertFalse(oemEightKRouteAdmitted(profileAdmits = true, backRoute = true, standalone = false, hostLens = true))
        assertFalse(oemEightKRouteAdmitted(profileAdmits = true, backRoute = true, standalone = true, hostLens = false))
    }

    @Test
    fun `only Rec 709 into HEVC is the measured packet`() {
        assertTrue(oemEightKPacketAdmitted(ColorTransfer.SDR, VideoCodec.HEVC))
        for (transfer in ColorTransfer.entries - ColorTransfer.SDR) {
            assertFalse("$transfer", oemEightKPacketAdmitted(transfer, VideoCodec.HEVC))
        }
        assertFalse(oemEightKPacketAdmitted(ColorTransfer.SDR, VideoCodec.AVC))
        assertFalse(oemEightKPacketAdmitted(ColorTransfer.SDR, VideoCodec.APV))
    }

    @Test
    fun `the 8K route asks for ON, the mode the HAL reports whatever is requested`() {
        assertEquals(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON, OEM_8K_VIDEO_STAB_MODE)
    }

    @Test
    fun `the OSD names the effective stabilization on an accepted 8K session only`() {
        val off = CameraUiState(videoStabMode = VideoStabMode.OFF)
        assertEquals(VideoStabMode.OFF, off.osdVideoStabMode)
        assertEquals(
            VideoStabMode.STANDARD,
            off.copy(photoSessionOutputs = PhotoSessionOutputs(oemEightK = true)).osdVideoStabMode,
        )
        val active = CameraUiState(videoStabMode = VideoStabMode.ENHANCED)
        assertEquals(VideoStabMode.ENHANCED, active.osdVideoStabMode)
    }

    @Test
    fun `the session type is the HAL's own 8K operation mode`() {
        assertEquals(0x80A9, OEM_8K_SESSION_TYPE)
        assertEquals(Size(7680, 4320), OEM_8K_VIDEO_SIZE)
    }

    @Test
    fun `an accepted 8K session names its stills trade, HLG keeps precedence`() {
        assertEquals(R.string.output_8k_video_stills_off, noStillOutputCaption(hlgSessionAccepted = false, eightKSessionAccepted = true))
        assertEquals(R.string.output_10_bit_video_stills_off, noStillOutputCaption(hlgSessionAccepted = true, eightKSessionAccepted = true))
        assertEquals(R.string.status_still_capture_unavailable, noStillOutputCaption(hlgSessionAccepted = false))
    }

    @Test
    fun `only exact PMA110 admits the route`() {
        assertTrue(DeviceProfile.resolve("PMA110").oemEightKVideo)
        for (model in listOf("CPH2841", "PMA120", "SM-S928B", null)) {
            assertFalse("$model", DeviceProfile.resolve(model).oemEightKVideo)
        }
    }
}
