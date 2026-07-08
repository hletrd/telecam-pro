package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure video-capability logic: per-resolution frame-rate gating
 * ([VideoFrameRate.availableFor]) and bitrate resolution ([videoBitRate]). Both are android-type-free
 * cores, so they run on the JVM without a device — mirroring the real Find X9 Ultra tele caps
 * (normal fps 24/30/60; high-speed 4K@120, 1080@240) captured from the device.
 */
class VideoCapabilitiesTest {

    // The tele (dev4) advertises these fixed AE fps; note 25/50 are NOT present on this device.
    private val teleNormalFps = setOf(10, 15, 22, 24, 30, 60)

    @Test
    fun `4K on the tele offers drop-frame + integer rates up to 60, but never 120 high-speed`() {
        val rates = VideoFrameRate.availableFor(teleNormalFps, highSpeedMaxFps = 120, width = 3840, height = 2160, codec = VideoCodec.HEVC)
        assertTrue(rates.contains(VideoFrameRate.FPS_23_976))
        assertTrue(rates.contains(VideoFrameRate.FPS_29_97))
        assertTrue(rates.contains(VideoFrameRate.FPS_59_94))
        assertTrue(rates.contains(VideoFrameRate.FPS_60))
        // High-speed 120 is gated out entirely now — the constrained high-speed session SIGABRTs the
        // HAL on this device — even when the camera advertises a matching high-speed config.
        assertFalse("120 high-speed must never be offered (HAL SIGABRT)", rates.contains(VideoFrameRate.FPS_120))
    }

    @Test
    fun `25 and 50 are dropped when the camera does not advertise them`() {
        val rates = VideoFrameRate.availableFor(teleNormalFps, highSpeedMaxFps = 0, width = 3840, height = 2160, codec = VideoCodec.HEVC)
        assertFalse(rates.contains(VideoFrameRate.FPS_25))
        assertFalse(rates.contains(VideoFrameRate.FPS_50))
    }

    @Test
    fun `120 is withheld when no high-speed config exists for the size`() {
        val rates = VideoFrameRate.availableFor(teleNormalFps, highSpeedMaxFps = 0, width = 3840, height = 2160, codec = VideoCodec.HEVC)
        assertFalse(rates.contains(VideoFrameRate.FPS_120))
    }

    @Test
    fun `8K is capped at 30fps`() {
        val rates = VideoFrameRate.availableFor(teleNormalFps, highSpeedMaxFps = 120, width = 7680, height = 4320, codec = VideoCodec.HEVC)
        assertTrue(rates.all { it.fps <= 30 })
        assertTrue(rates.contains(VideoFrameRate.FPS_30))
        assertFalse(rates.contains(VideoFrameRate.FPS_60))
    }

    @Test
    fun `gating never returns an empty list`() {
        val rates = VideoFrameRate.availableFor(emptySet(), highSpeedMaxFps = 0, width = 3840, height = 2160, codec = VideoCodec.HEVC)
        assertTrue(rates.isNotEmpty())
    }

    @Test
    fun `drop-frame rates carry the true NTSC fractional encoder rate`() {
        assertEquals(24000.0 / 1001.0, VideoFrameRate.FPS_23_976.encoderRate, 1e-9)
        assertEquals(30000.0 / 1001.0, VideoFrameRate.FPS_29_97.encoderRate, 1e-9)
        assertEquals(60000.0 / 1001.0, VideoFrameRate.FPS_59_94.encoderRate, 1e-9)
        // Rounded parents used for exposure math.
        assertEquals(24, VideoFrameRate.FPS_23_976.fps)
        assertEquals(30, VideoFrameRate.FPS_29_97.fps)
        assertEquals(60, VideoFrameRate.FPS_59_94.fps)
    }

    @Test
    fun `bitrate scales with pixels and framerate and honors the floor`() {
        // 4K30 HEVC at MEDIUM (0.10 bpp): 0.10 * 3840*2160*30 = 24,883,200 (~24.9 Mbps), within range.
        val mid = videoBitRate(3840, 2160, 30.0, BitrateLevel.MEDIUM.bpp, VideoCodec.HEVC)
        assertEquals(24_883_200, mid)
        // A huge frame×rate (8K60 HIGH ≈ 318 Mbps) exceeds the 120 Mbps HEVC HW ceiling and is clamped.
        val hi = videoBitRate(7680, 4320, 60.0, BitrateLevel.HIGH.bpp, VideoCodec.HEVC)
        assertEquals(120_000_000, hi)
        // Tiny frame hits the 8 Mbps floor.
        val lo = videoBitRate(640, 480, 24.0, BitrateLevel.LOW.bpp, VideoCodec.HEVC)
        assertEquals(8_000_000, lo)
    }

    @Test
    fun `MAX preset reaches the HEVC HW ceiling range at 4K`() {
        // 4K30 MAX (0.40 bpp): ~99.5 Mbps — the whole point of adding the higher presets.
        val max = videoBitRate(3840, 2160, 30.0, BitrateLevel.MAX.bpp, VideoCodec.HEVC)
        assertTrue("4K30 MAX should be ~99.5 Mbps, was $max", max in 99_000_000..100_000_000)
    }

    @Test
    fun `APV scales bpp up for all-intra and has a higher ceiling`() {
        // effectiveBpp ×8 for APV: 4K30 MEDIUM → 0.80 bpp → ~199 Mbps (pro-intra range), under 480M cap.
        val bpp = effectiveBpp(BitrateLevel.MEDIUM, VideoCodec.APV)
        assertEquals(0.80f, bpp, 1e-4f)
        val apv = videoBitRate(3840, 2160, 30.0, bpp, VideoCodec.APV)
        assertTrue("APV 4K30 should be ~199 Mbps, was $apv", apv in 198_000_000..200_000_000)
        // A Long-GOP codec keeps its base bpp.
        assertEquals(BitrateLevel.MEDIUM.bpp, effectiveBpp(BitrateLevel.MEDIUM, VideoCodec.HEVC), 1e-6f)
    }
}
