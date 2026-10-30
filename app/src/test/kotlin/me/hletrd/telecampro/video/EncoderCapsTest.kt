package me.hletrd.telecampro.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.projectedLegalVideoRate
import me.hletrd.telecampro.ui.ViewModelTestAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the two pure seams of the encoder scan (TEST4-19): the name heuristic that backs a throwing
 * isHardwareAccelerated, and the hardware-first tie-break. EncoderCaps itself needs a live
 * MediaCodecList and stays device-verified.
 */
@RunWith(RobolectricTestRunner::class)
class EncoderCapsTest {

    private fun component(
        name: String,
        mime: String,
        hardware: Boolean = true,
        profiles: Set<Int>? = emptySet(),
    ) = CodecComponent(
        name = name,
        encoder = true,
        supportedTypes = setOf(mime),
        hardwareAccelerated = hardware,
        hevcProfiles = profiles,
    )

    @Test
    fun `Main10 rejection removes HLG 8K but Main-only SDR answer is independent`() {
        val hlg = exactFormat(VideoCodec.HEVC, ColorTransfer.HLG, 4320, 7680, 29.97)
        val sdr = exactFormat(VideoCodec.HEVC, ColorTransfer.SDR, 4320, 7680, 29.97)
        val mainOnlyProbe = EncoderFormatSupport { format ->
            format.getInteger(MediaFormat.KEY_PROFILE) == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        }
        assertFalse(mainOnlyProbe.supports(hlg))
        assertTrue(mainOnlyProbe.supports(sdr))

        val mainOnly = EncoderSelection(
            VideoCodec.HEVC, "main-only", MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true, main10 = false, formatSupport = mainOnlyProbe,
        )
        val caps = ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(Size(7680, 4320)))
        assertNull(projectedLegalVideoRate(
            caps, Size(7680, 4320), listOf(mainOnly), ColorTransfer.HLG,
            VideoCodec.HEVC, sensorOrientation = 90, contentRotation = 180,
        ))
        assertNotNull(projectedLegalVideoRate(
            caps, Size(7680, 4320), listOf(mainOnly), ColorTransfer.SDR,
            VideoCodec.HEVC, sensorOrientation = 90, contentRotation = 180,
        ))
    }

    @Test
    fun `portrait encoder rejection removes a 90 degree 8K candidate`() {
        val landscapeOnlyProbe = EncoderFormatSupport { format ->
            format.getInteger(MediaFormat.KEY_WIDTH) >= format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        val selection = EncoderSelection(
            VideoCodec.HEVC, "landscape-only", MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true, main10 = true, formatSupport = landscapeOnlyProbe,
        )
        val caps = ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(Size(7680, 4320)))

        assertNull(projectedLegalVideoRate(
            caps, Size(7680, 4320), listOf(selection), ColorTransfer.SDR,
            VideoCodec.HEVC, sensorOrientation = 90, contentRotation = 180,
        ))
        assertNotNull(projectedLegalVideoRate(
            caps, Size(7680, 4320), listOf(selection), ColorTransfer.SDR,
            VideoCodec.HEVC, sensorOrientation = 0, contentRotation = 0,
        ))
    }

    @Test
    fun `NTSC capability probes stay nominal while the admitted recording rate stays exact`() {
        val probedRates = mutableListOf<Float>()
        val probe = EncoderFormatSupport { format ->
            probedRates += format.getFloat(MediaFormat.KEY_FRAME_RATE)
            true
        }
        val selection = EncoderSelection(
            VideoCodec.HEVC, "ntsc", MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true, main10 = true, formatSupport = probe,
        )
        val caps = ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(Size(7680, 4320)))

        val admitted = projectedLegalVideoRate(
            caps, Size(7680, 4320), listOf(selection), ColorTransfer.SDR,
            VideoCodec.HEVC, sensorOrientation = 90, contentRotation = 0,
        )

        assertTrue(29.97f in probedRates)
        assertFalse(probedRates.any { kotlin.math.abs(it - VideoFrameRate.FPS_29_97.encoderRate.toFloat()) < 0.00001f })
        assertEquals(VideoFrameRate.FPS_29_97, admitted)
        assertEquals(30000.0 / 1001.0, admitted?.encoderRate ?: 0.0, 0.0)
    }

    private fun exactFormat(
        codec: VideoCodec,
        transfer: ColorTransfer,
        width: Int,
        height: Int,
        rate: Double,
    ): MediaFormat = ColorProfiles.videoFormat(codec, width, height, rate, 0.0, 100_000_000, transfer)

    @Test
    fun `empty and throwing discovery fail closed`() {
        val empty = discoverCodecInventory { emptyList() }
        assertTrue(empty.availableVideoCodecs.isEmpty())
        assertFalse(empty.heifEncodeAvailable)
        assertFalse(empty.tenBitEncodeAvailable)

        val thrown = discoverCodecInventory { throw IllegalStateException("codec service down") }
        assertTrue(thrown.availableVideoCodecs.isEmpty())
        assertNull(thrown.selectionFor(VideoCodec.HEVC))
        assertFalse(thrown.tenBitEncodeAvailable)
    }

    // AGG5-30: one throwing walk used to latch EMPTY for the whole process (no video, no HEIF).
    @Test
    fun `only a successful walk latches and a failed load retries boundedly with one report`() {
        var walks = 0
        var failing = true
        val pauses = mutableListOf<Int>()
        val reports = mutableListOf<Throwable>()
        val loader = CodecInventoryLoader(
            scan = {
                walks++
                if (failing) throw IllegalStateException("mediaserver restarting")
                listOf(component("vendor.hevc", MediaFormat.MIMETYPE_VIDEO_HEVC))
            },
            pause = { pauses += it },
            onScanFailure = { reports += it },
        )

        // AGG6-1: an exhausted load is a FAILURE (null), never the device answer EMPTY.
        assertNull(loader.load())
        assertEquals(CODEC_SCAN_MAX_ATTEMPTS, walks)
        assertEquals(listOf(2, 3), pauses)
        assertFalse("a failed walk must not latch", loader.isLoaded())
        assertEquals(1, reports.size)

        // A second failed load walks again but does not report again.
        assertNull(loader.load())
        assertEquals(2 * CODEC_SCAN_MAX_ATTEMPTS, walks)
        assertEquals(1, reports.size)

        // The provider recovers: the next load latches the real inventory, and later loads reuse it.
        failing = false
        assertEquals(listOf(VideoCodec.HEVC), loader.load()?.availableVideoCodecs)
        assertTrue(loader.isLoaded())
        val walksAtLatch = walks
        assertEquals(listOf(VideoCodec.HEVC), loader.load()?.availableVideoCodecs)
        assertEquals(listOf(VideoCodec.HEVC), loader.currentInventory().availableVideoCodecs)
        assertEquals(walksAtLatch, walks)
    }

    @Test
    fun `a walk that succeeds on a retry latches without a failure report`() {
        var walks = 0
        val reports = mutableListOf<Throwable>()
        val loader = CodecInventoryLoader(
            scan = {
                if (++walks == 1) throw IllegalStateException("binder hiccup")
                emptyList()
            },
            onScanFailure = { reports += it },
        )
        assertEquals(emptyList<VideoCodec>(), loader.load()?.availableVideoCodecs)
        assertTrue("an empty SUCCESSFUL walk is the device's real answer and latches", loader.isLoaded())
        assertEquals(2, walks)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `capability exception never grants Main10`() {
        val inventory = buildCodecInventory(
            listOf(component("vendor.hevc", MediaFormat.MIMETYPE_VIDEO_HEVC, profiles = null)),
        )
        assertTrue(inventory.heifEncodeAvailable)
        assertFalse(inventory.tenBitEncodeAvailable)
        assertFalse(inventory.selectionFor(VideoCodec.HEVC)!!.main10)
    }

    @Test
    fun `format probe belongs to exact component`() {
        val component = CodecComponent(
            name = "c2.test.hevc",
            encoder = true,
            supportedTypes = setOf("video/hevc"),
            hardwareAccelerated = true,
            hevcProfiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
            formatSupport = mapOf(
                VideoCodec.HEVC to EncoderFormatSupport { format ->
                    format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                        format.getInteger(MediaFormat.KEY_WIDTH) == 7680 &&
                        format.getInteger(MediaFormat.KEY_HEIGHT) == 4320 &&
                        format.getFloat(MediaFormat.KEY_FRAME_RATE) == 30f
                },
            ),
        )
        val selected = buildCodecInventory(listOf(component)).selectionFor(VideoCodec.HEVC)!!
        val probe = selected.formatSupport!!
        assertTrue(probe.supports(exactFormat(VideoCodec.HEVC, ColorTransfer.SDR, 7680, 4320, 30.0)))
        assertFalse(probe.supports(exactFormat(VideoCodec.HEVC, ColorTransfer.SDR, 3840, 2160, 60.0)))
    }

    @Test
    fun `dual-codec component retains independent MIME and format probes`() {
        val component = CodecComponent(
            name = "c2.test.dual",
            encoder = true,
            supportedTypes = setOf("video/hevc", "video/avc"),
            hardwareAccelerated = true,
            hevcProfiles = emptySet(),
            formatSupport = mapOf(
                VideoCodec.HEVC to EncoderFormatSupport { format ->
                    format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                        format.getInteger(MediaFormat.KEY_WIDTH) == 7680 &&
                        format.getFloat(MediaFormat.KEY_FRAME_RATE) == 30f
                },
                VideoCodec.AVC to EncoderFormatSupport { format ->
                    format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC &&
                        format.getInteger(MediaFormat.KEY_WIDTH) == 3840 &&
                        format.getFloat(MediaFormat.KEY_FRAME_RATE) == 60f
                },
            ),
        )
        val inventory = buildCodecInventory(listOf(component))
        val hevc = inventory.selectionFor(VideoCodec.HEVC)!!.formatSupport!!
        val avc = inventory.selectionFor(VideoCodec.AVC)!!.formatSupport!!

        assertTrue(hevc.supports(exactFormat(VideoCodec.HEVC, ColorTransfer.SDR, 7680, 4320, 30.0)))
        assertFalse(hevc.supports(exactFormat(VideoCodec.AVC, ColorTransfer.SDR, 3840, 2160, 60.0)))
        assertFalse(avc.supports(exactFormat(VideoCodec.HEVC, ColorTransfer.SDR, 7680, 4320, 30.0)))
        assertTrue(avc.supports(exactFormat(VideoCodec.AVC, ColorTransfer.SDR, 3840, 2160, 60.0)))
    }

    @Test
    fun `missing capability query grants no oversize support`() {
        val componentWithoutProbe = component("c2.test.hevc", MediaFormat.MIMETYPE_VIDEO_HEVC)
        val selected = buildCodecInventory(listOf(componentWithoutProbe)).selectionFor(VideoCodec.HEVC)!!
        assertNull(selected.formatSupport)
    }

    @Test
    fun `multi-component inventory carries exact admitted hardware identity`() {
        val inventory = buildCodecInventory(
            listOf(
                component(
                    "software.hevc",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardware = false,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                component(
                    "vendor.hevc",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                component("vendor.avc", MediaFormat.MIMETYPE_VIDEO_AVC),
            ),
        )

        assertEquals(listOf(VideoCodec.HEVC, VideoCodec.AVC), inventory.availableVideoCodecs)
        assertEquals("vendor.hevc", inventory.selectionFor(VideoCodec.HEVC)?.codecName)
        assertEquals("vendor.avc", inventory.selectionFor(VideoCodec.AVC)?.codecName)
        assertTrue(inventory.tenBitEncodeAvailable)
    }

    @Test
    fun `Main10 intent selects the capable component without changing SDR registry preference`() {
        val inventory = buildCodecInventory(
            listOf(
                component(
                    "software.main10",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardware = false,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                component(
                    "vendor.main-only",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
                ),
            ),
        )

        assertEquals("vendor.main-only", inventory.selectionFor(VideoCodec.HEVC)?.codecName)
        assertTrue(inventory.tenBitEncodeAvailable)
        assertEquals(
            "software.main10",
            inventory.selectionFor(VideoCodec.HEVC, ColorTransfer.HLG)?.codecName,
        )
        assertEquals(
            listOf("vendor.main-only", "software.main10"),
            inventory.candidatesFor(VideoCodec.HEVC).map { it.codecName },
        )
        assertEquals(
            listOf("software.main10"),
            inventory.candidatesFor(VideoCodec.HEVC, ColorTransfer.SLOG3).map { it.codecName },
        )
    }

    @Test
    fun `hardware candidates remain registry-stable across input permutations`() {
        val first = buildCodecInventory(
            listOf(
                component("sw", MediaFormat.MIMETYPE_VIDEO_HEVC, hardware = false),
                component("hw-a", MediaFormat.MIMETYPE_VIDEO_HEVC),
                component("hw-b", MediaFormat.MIMETYPE_VIDEO_HEVC),
            ),
        )
        val second = buildCodecInventory(
            listOf(
                component("hw-b", MediaFormat.MIMETYPE_VIDEO_HEVC),
                component("sw", MediaFormat.MIMETYPE_VIDEO_HEVC, hardware = false),
                component("hw-a", MediaFormat.MIMETYPE_VIDEO_HEVC),
            ),
        )

        assertEquals(listOf("hw-a", "hw-b", "sw"), first.candidatesFor(VideoCodec.HEVC).map { it.codecName })
        assertEquals(listOf("hw-b", "hw-a", "sw"), second.candidatesFor(VideoCodec.HEVC).map { it.codecName })
    }

    @Test
    fun `configure attempts preserve requested size before spending resolution`() {
        val limited = EncoderSelection(
            VideoCodec.HEVC, "limited", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val full = limited.copy(codecName = "full")
        val attempts = encoderConfigureAttempts(listOf(limited, full), 1080, 1920)

        assertEquals(
            listOf("limited" to (1080 to 1920), "full" to (1080 to 1920)),
            attempts.take(2).map { it.selection.codecName to (it.width to it.height) },
        )
        assertTrue(attempts[2].width < 1080)
    }

    @Test
    fun `configure runner releases rejected component before accepting the next at requested size`() {
        val limited = EncoderSelection(
            VideoCodec.HEVC, "limited", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val full = limited.copy(codecName = "full")
        data class FakeOwner(val name: String)
        val visited = mutableListOf<Pair<String, Pair<Int, Int>>>()
        val released = mutableListOf<String>()

        val accepted = firstConfiguredEncoderAttempt(
            attempts = encoderConfigureAttempts(listOf(limited, full), 1080, 1920),
            acquire = { FakeOwner(it.codecName) },
            configure = { owner, attempt ->
                visited += owner.name to (attempt.width to attempt.height)
                if (owner.name == "limited") error("requested portrait size rejected")
            },
            releaseRejected = {
                released += it.name
                true
            },
        )

        assertEquals("full", accepted.owner.name)
        assertEquals(1080, accepted.attempt.width)
        assertEquals(1920, accepted.attempt.height)
        assertEquals(listOf("limited"), released)
        assertEquals(
            listOf("limited" to (1080 to 1920), "full" to (1080 to 1920)),
            visited,
        )
    }

    @Test
    fun `smaller fallback attempt resolves its own scaled bitrate`() {
        val selection = EncoderSelection(
            VideoCodec.HEVC, "limited", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        data class FakeOwner(val name: String)
        val attemptedRates = mutableListOf<Triple<Int, Int, Int>>()

        val accepted = firstConfiguredEncoderAttempt(
            attempts = encoderConfigureAttempts(listOf(selection), 1080, 1920),
            acquire = { FakeOwner(it.codecName) },
            configure = { _, attempt ->
                val rate = me.hletrd.telecampro.camera.videoBitRate(
                    attempt.width,
                    attempt.height,
                    30.0,
                    0.4f,
                    VideoCodec.HEVC,
                )
                attemptedRates += Triple(attempt.width, attempt.height, rate)
                if (attempt.width == 1080) error("full raster rejected")
            },
            releaseRejected = { true },
        )

        assertTrue(accepted.attempt.width < 1080)
        assertTrue(attemptedRates.last().third < attemptedRates.first().third)
    }

    @Test
    fun `revoked rejected-owner cleanup stops encoder fallback immediately`() {
        val first = EncoderSelection(
            VideoCodec.HEVC, "first", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val second = first.copy(codecName = "second")
        val acquired = mutableListOf<String>()

        runCatching {
            firstConfiguredEncoderAttempt(
                attempts = encoderConfigureAttempts(listOf(first, second), 1080, 1920),
                acquire = { selection -> selection.codecName.also(acquired::add) },
                configure = { _, _ -> error("late configure failure") },
                // False models the native-operation gate closing before rejected-owner cleanup.
                releaseRejected = { false },
            )
        }.onSuccess {
            error("revoked fallback unexpectedly returned an accepted owner")
        }.onFailure { failure ->
            assertTrue(failure is RecorderNativeOperationRevokedException)
        }

        assertEquals(listOf("first"), acquired)
    }

    @Test
    fun `acquire failure advances but acquire revocation stops immediately`() {
        val first = EncoderSelection(
            VideoCodec.HEVC, "first", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val second = first.copy(codecName = "second")
        val accepted = firstConfiguredEncoderAttempt(
            attempts = encoderConfigureAttempts(listOf(first, second), 1080, 1920),
            acquire = { selection ->
                if (selection.codecName == "first") error("create failed")
                selection.codecName
            },
            configure = { _, _ -> },
            releaseRejected = { true },
        )
        assertEquals("second", accepted.owner)

        val revoked = runCatching {
            firstConfiguredEncoderAttempt(
                attempts = encoderConfigureAttempts(listOf(first, second), 1080, 1920),
                acquire = { throw RecorderNativeOperationRevokedException() },
                configure = { _, _ -> },
                releaseRejected = { true },
            )
        }.exceptionOrNull()
        assertTrue(revoked is RecorderNativeOperationRevokedException)
    }

    @Test
    fun `configure revocation propagates without rejected-owner cleanup`() {
        val selection = EncoderSelection(
            VideoCodec.HEVC, "first", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        var cleanupCalled = false

        val failure = runCatching {
            firstConfiguredEncoderAttempt(
                attempts = encoderConfigureAttempts(listOf(selection), 1080, 1920),
                acquire = { it.codecName },
                configure = { _, _ -> throw RecorderNativeOperationRevokedException() },
                releaseRejected = {
                    cleanupCalled = true
                    true
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is RecorderNativeOperationRevokedException)
        assertFalse(cleanupCalled)
    }

    @Test
    fun `all ordinary acquisition failures preserve the terminal cause`() {
        val selection = EncoderSelection(
            VideoCodec.HEVC, "first", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val failure = runCatching {
            firstConfiguredEncoderAttempt(
                attempts = encoderConfigureAttempts(listOf(selection), 1080, 1920),
                acquire = { error("create failed") },
                configure = { _, _ -> },
                releaseRejected = { true },
            )
        }.exceptionOrNull()

        assertEquals("create failed", failure?.message)
    }

    @Test
    fun `AVC rejects every non-SDR transfer at the final recorder boundary`() {
        val avc = EncoderSelection(
            VideoCodec.AVC, "vendor.avc", MediaFormat.MIMETYPE_VIDEO_AVC, true, false,
        )
        assertTrue(encoderSelectionAdmitsTransfer(avc, ColorTransfer.SDR))
        assertFalse(encoderSelectionAdmitsTransfer(avc, ColorTransfer.HLG))
        assertFalse(encoderSelectionAdmitsTransfer(avc, ColorTransfer.SLOG3))
        val apv = avc.copy(
            codec = VideoCodec.APV,
            codecName = "vendor.apv",
            mime = "video/apv",
        )
        assertFalse(encoderSelectionAdmitsTransfer(apv, ColorTransfer.SDR))
    }

    @Test(expected = IllegalStateException::class)
    fun `empty configure attempt list fails closed`() {
        firstConfiguredEncoderAttempt<Any>(
            attempts = emptyList(),
            acquire = { error("unreachable") },
            configure = { _, _ -> error("unreachable") },
            releaseRejected = { true },
        )
    }

    @Test
    fun `known software encoder names classify as software`() {
        assertFalse(looksHardwareAccelerated("c2.android.avc.encoder"))
        assertFalse(looksHardwareAccelerated("c2.android.av1.encoder"))
        assertFalse(looksHardwareAccelerated("OMX.google.h264.encoder"))
    }

    @Test
    fun `vendor encoder names classify as hardware`() {
        assertTrue(looksHardwareAccelerated("c2.qti.hevc.encoder"))
        assertTrue(looksHardwareAccelerated("c2.qti.apv.encoder"))
        assertTrue(looksHardwareAccelerated("OMX.qcom.video.encoder.avc"))
    }

    @Test
    fun `first hardware candidate wins immediately`() {
        assertEquals(
            "hw1",
            pickBestEncoder(listOf("sw1" to false, "hw1" to true, "hw2" to true)),
        )
    }

    @Test
    fun `first software candidate is the remembered fallback`() {
        // A later software match must never displace an earlier one (the reordering bug class
        // the extraction pins).
        assertEquals("sw1", pickBestEncoder(listOf("sw1" to false, "sw2" to false)))
    }

    @Test
    fun `no candidates yields null`() {
        assertNull(pickBestEncoder(emptyList<Pair<String, Boolean>>()))
    }
}
