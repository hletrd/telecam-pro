package me.hletrd.telecampro.camera

import android.util.Range
import android.util.Size
import me.hletrd.telecampro.ui.ViewModelTestAccess
import me.hletrd.telecampro.video.EncoderSelection
import me.hletrd.telecampro.video.EncoderSizeRateSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Recordability is a projection of camera truth, never a widening of it. */
@RunWith(RobolectricTestRunner::class)
class RecordableVideoSizesTest {
    private val uhd = Size(3840, 2160)
    private val openGate4K = Size(4096, 3072)
    private val eightK = Size(7680, 4320)

    private val candidatesWithoutProbe = candidates(null)
    private val candidatesWithout8k = candidates(
        EncoderSizeRateSupport { width, height, _ -> width < 7680 || height < 4320 },
    )
    private val candidatesWith8k30 = candidates(
        EncoderSizeRateSupport { width, height, rate ->
            width == 7680 && height == 4320 && rate in setOf(23.976, 24.0, 29.97, 30.0)
        },
    )

    @Test
    fun `legacy sizes through 4096 by 3072 survive without an encoder probe`() {
        assertTrue(allows(3840, 2160, candidatesWithoutProbe, rawLog = false))
        assertTrue(allows(4096, 3072, candidatesWithoutProbe, rawLog = false))
        assertFalse(requiresExtendedVideoProof(4096, 3072))
        assertTrue(requiresExtendedVideoProof(4097, 3072))
        assertTrue(requiresExtendedVideoProof(4096, 3073))
    }

    @Test
    fun `an extended size requires exact encoder size and rate proof`() {
        assertFalse(allows(7680, 4320, candidatesWithoutProbe, rawLog = false))
        assertTrue(allows(7680, 4320, candidatesWith8k30, rawLog = false))
        assertFalse(allows(7680, 4320, candidatesWithout8k, rawLog = false))
    }

    @Test
    fun `RAW Log also requires a raw source at least as large as the extended size`() {
        assertFalse(
            allows(7680, 4320, candidatesWith8k30, rawLog = true, rawSizes = listOf(Size(4096, 3072))),
        )
        assertTrue(
            allows(7680, 4320, candidatesWith8k30, rawLog = true, rawSizes = listOf(Size(7680, 4320))),
        )
    }

    @Test
    fun `debug OEM probe adds 8K only after exact encoder proof`() {
        val projected = projectDebugOem8kCandidate(
            raw = capsWith(sizes = listOf(uhd)),
            candidates = candidatesWith8k30,
            debugBuild = true,
            probeRequested = true,
            oemLogRoute = true,
            profile = DeviceProfile.PMA110,
        )
        val refused = projectDebugOem8kCandidate(
            raw = capsWith(sizes = listOf(uhd)),
            candidates = candidatesWithout8k,
            debugBuild = true,
            probeRequested = true,
            oemLogRoute = true,
            profile = DeviceProfile.PMA110,
        )

        assertEquals(listOf(eightK, uhd), projected.availableVideoSizes)
        assertEquals(setOf(23.976, 24.0, 29.97, 30.0), projected.extendedVideoSizeRates[eightK])
        assertTrue(eightK !in projected.openGateVideoSizes)
        assertTrue(eightK !in refused.availableVideoSizes)
    }

    @Test
    fun `release and GENERIC cannot receive the debug OEM 8K candidate`() {
        for ((debugBuild, profile) in listOf(
            false to DeviceProfile.PMA110,
            true to DeviceProfile.GENERIC,
        )) {
            val projected = projectDebugOem8kCandidate(
                raw = capsWith(sizes = listOf(uhd)),
                candidates = candidatesWith8k30,
                debugBuild = debugBuild,
                probeRequested = true,
                oemLogRoute = true,
                profile = profile,
            )
            assertTrue(eightK !in projected.availableVideoSizes)
            assertTrue(projected.extendedVideoSizeRates.isEmpty())
        }
    }

    @Test
    fun `an extended size keeps only its exactly supported standard rates`() {
        val projected = projectRecordableVideoSizes(
            raw = capsWith(sizes = listOf(eightK, uhd)),
            candidates = candidatesWith8k30,
            rawLog = false,
        )

        val projected8kRates = projected.extendedVideoSizeRates[eightK].orEmpty()
        assertEquals(setOf(23.976, 24.0, 29.97, 30.0), projected8kRates)
        assertFalse(59.94 in projected8kRates)
        assertEquals(listOf(eightK, uhd), projected.availableVideoSizes)
        assertTrue(uhd !in projected.extendedVideoSizeRates)
    }

    @Test
    fun `the caps rate gate consumes the projected map without narrowing legacy rates`() {
        val projected = projectRecordableVideoSizes(
            raw = capsWith(sizes = listOf(eightK, uhd)),
            candidates = candidatesWith8k30,
            rawLog = false,
        ).copy(
            availableFpsRanges = arrayOf(Range(24, 24), Range(30, 30), Range(60, 60)),
        )

        assertEquals(
            setOf(
                VideoFrameRate.FPS_23_976,
                VideoFrameRate.FPS_24,
                VideoFrameRate.FPS_29_97,
                VideoFrameRate.FPS_30,
            ),
            VideoFrameRate.availableFor(projected, eightK, VideoCodec.HEVC).toSet(),
        )
        assertTrue(VideoFrameRate.FPS_59_94 in VideoFrameRate.availableFor(projected, uhd, VideoCodec.HEVC))
    }

    private fun allows(
        width: Int,
        height: Int,
        candidates: List<EncoderSelection>,
        rawLog: Boolean,
        rawSizes: List<Size> = emptyList(),
    ): Boolean {
        val size = Size(width, height)
        val raw = capsWith(
            sizes = listOf(size),
            rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height },
        )
        return size in projectRecordableVideoSizes(raw, candidates, rawLog).availableVideoSizes
    }

    private fun capsWith(sizes: List<Size>, rawSize: Size? = null): CameraCaps =
        ViewModelTestAccess.caps().copy(
            availableVideoSizes = sizes,
            openGateVideoSizes = listOf(openGate4K),
            rawSize = rawSize,
        )

    private fun candidates(support: EncoderSizeRateSupport?): List<EncoderSelection> = listOf(
        EncoderSelection(
            codec = VideoCodec.HEVC,
            codecName = "test.hevc",
            mime = "video/hevc",
            hardwareAccelerated = true,
            main10 = true,
            sizeRateSupport = support,
        ),
    )
}
