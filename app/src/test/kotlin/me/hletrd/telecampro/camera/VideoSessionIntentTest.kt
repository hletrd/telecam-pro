package me.hletrd.telecampro.camera

import android.media.MediaFormat
import android.util.Range
import android.util.Size
import me.hletrd.telecampro.ui.ViewModelTestAccess
import me.hletrd.telecampro.video.EncoderFormatSupport
import me.hletrd.telecampro.video.EncoderSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoSessionIntentTest {
    private val route = TeleSelection("4", null, 70f)
    private val eightK = Size(7680, 4320)
    private val eightKOpenGate = Size(7680, 5760)
    private val uhd = Size(3840, 2160)

    @Test
    fun `rate normalization that enables RAW Log reapplies RAW raster gate`() {
        val resolved = resolveVideoSessionIntent(
            requested = request(rawLogRequested = true),
            caps = caps(rawSize = Size(4096, 3072)),
            candidates = candidates8k30,
        )

        assertNull("4K RAW must never be developed into an 8K output", resolved)
    }

    @Test
    fun `legal 8K RAW resolves once at the exact NTSC rate and rotated encoder raster`() {
        val resolved = resolveVideoSessionIntent(
            requested = request(rawLogRequested = true, frameRate = VideoFrameRate.FPS_60),
            caps = caps(rawSize = eightK),
            candidates = candidates8k30,
        )

        assertNotNull(resolved)
        resolved!!
        assertTrue(resolved.rawLog)
        assertEquals(VideoFrameRate.FPS_29_97, resolved.frameRate)
        assertEquals(30000, resolved.frameRate.rateNumerator)
        assertEquals(1001, resolved.frameRate.rateDenominator)
        assertEquals(eightK, resolved.streamSize)
        assertEquals(Size(4320, 7680), resolved.encoderSize)
    }

    @Test
    fun `an unsupported retained request keeps the legacy automatic fallback`() {
        val resolved = resolveVideoSessionIntent(
            requested = request(requestedSize = Size(1920, 1080), rawLogRequested = false),
            caps = caps(rawSize = null),
            candidates = candidates8k30,
        )

        assertNotNull(resolved)
        assertEquals(uhd, resolved!!.streamSize)
    }

    @Test
    fun `empty aspect lists retain the direct characteristics automatic answer`() {
        val directCharacteristicsAnswer = Size(2560, 1440)
        val resolved = resolveVideoSessionIntent(
            requested = request(
                requestedSize = null,
                automaticSize = directCharacteristicsAnswer,
                rawLogRequested = false,
            ),
            caps = caps(rawSize = null).copy(
                availableVideoSizes = emptyList(),
                openGateVideoSizes = emptyList(),
            ),
            candidates = candidates8k30,
        )

        assertNotNull(resolved)
        assertEquals(directCharacteristicsAnswer, resolved!!.streamSize)
        assertEquals(VideoFrameRate.FPS_60, resolved.frameRate)
    }

    @Test
    fun `legacy size keeps its requested rate when the camera advertises no legal rate`() {
        val resolved = resolveVideoSessionIntent(
            requested = request(
                requestedSize = uhd,
                automaticSize = uhd,
                rawLogRequested = false,
                frameRate = VideoFrameRate.FPS_29_97,
            ),
            caps = caps(rawSize = null).copy(availableFpsRanges = emptyArray()),
            candidates = candidates8k30,
        )

        assertNotNull(resolved)
        assertEquals(VideoFrameRate.FPS_29_97, resolved!!.frameRate)
        assertEquals(uhd, resolved.streamSize)
    }

    @Test
    fun `Open Gate with no legal fixed point is refused`() {
        val resolved = resolveVideoSessionIntent(
            requested = request(
                openGate = true,
                requestedSize = eightKOpenGate,
                automaticSize = null,
                rawLogRequested = false,
            ),
            caps = caps(rawSize = null).copy(
                availableVideoSizes = listOf(uhd),
                openGateVideoSizes = listOf(eightKOpenGate),
            ),
            candidates = candidates8k30,
        )

        assertNull(resolved)
    }

    private fun request(
        rawLogRequested: Boolean,
        openGate: Boolean = false,
        requestedSize: Size? = eightK,
        automaticSize: Size? = uhd,
        frameRate: VideoFrameRate = VideoFrameRate.FPS_60,
    ) = VideoSessionRequest(
        route = route,
        codec = VideoCodec.HEVC,
        transfer = if (rawLogRequested) ColorTransfer.SLOG3 else ColorTransfer.SDR,
        rawLogRequested = rawLogRequested,
        openGate = openGate,
        requestedSize = requestedSize,
        automaticSize = automaticSize,
        frameRate = frameRate,
        rawRequiresStandalone = true,
        contentRotation = 0,
    )

    private fun caps(rawSize: Size?): CameraCaps = ViewModelTestAccess.caps().copy(
        availableFpsRanges = arrayOf(Range(30, 30), Range(60, 60)),
        availableVideoSizes = listOf(eightK, uhd),
        openGateVideoSizes = listOf(Size(4096, 3072)),
        supportsRaw = rawSize != null,
        rawSize = rawSize,
        rawLogDevelopable = rawSize != null,
        rawMinFrameDurationNs = if (rawSize == null) 0L else 33_333_333L,
    )

    private val candidates8k30 = listOf(
        EncoderSelection(
            codec = VideoCodec.HEVC,
            codecName = "test.hevc.8k30",
            mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true,
            main10 = true,
            formatSupport = EncoderFormatSupport { format ->
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val rate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
                ((width == 7680 && height == 4320) || (width == 4320 && height == 7680)) &&
                    rate <= 30f
            },
        ),
    )
}
