package me.hletrd.telecampro.gl

import me.hletrd.telecampro.camera.VideoFrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The exact constant-rate encoder grid (ConstantRateFrameClock.kt) over the cadences PMA110 measured. */
class ConstantRateFrameClockTest {

    private fun ntsc2997() = ConstantRateFrameClock(30_000, 1_001, maxFillSlots = 30)

    /** Feeds [frames] frames [intervalNs] apart from an arbitrary boot-clock base; returns every emitted PTS. */
    private fun run(clock: ConstantRateFrameClock, frames: Int, intervalNs: Long, gapsAt: Set<Int> = emptySet()): List<Long> {
        val base = 7_123_456_789_000L
        val pts = ArrayList<Long>()
        var k = 0L
        repeat(frames) { i ->
            if (i in gapsAt) k += 2 // the camera skipped two frames here
            val ts = base + k * intervalNs
            val n = clock.place(ts)
            for (e in 0 until n) pts += clock.presentationNs(e)
            k++
        }
        return pts
    }

    private fun assertExactGrid(pts: List<Long>, num: Long, den: Long) {
        assertEquals(0L, pts.first())
        pts.forEachIndexed { slot, p ->
            // Slot n starts at n·den/num s, rounded to the ns: never drifts, whatever the take length.
            val exact = slot.toDouble() * den / num * 1e9
            assertTrue("slot $slot at $p ns, exact $exact", abs(p - exact) <= 0.5)
        }
    }

    @Test
    fun `the grid is the exact NTSC fraction, with no cumulative drift`() {
        val clock = ntsc2997()
        assertEquals(0L, clock.slotStartNs(0))
        assertEquals(33_366_667L, clock.slotStartNs(1))
        // 30000 slots of 1001/30000 s is exactly 1001 s.
        assertEquals(1_001_000_000_000L, clock.slotStartNs(30_000))
        assertEquals(36_036_000_000_000L, clock.slotStartNs(1_080_000)) // ten hours of slots
    }

    @Test
    fun `a sensor running fast drops one frame per slot it gains and keeps the grid exact`() {
        // PMA110 with AE off at "29.97": 33.320 ms (30.012 fps), measured 2026-10-26.
        val clock = ntsc2997()
        val pts = run(clock, frames = 7_200, intervalNs = 33_320_000L)
        assertExactGrid(pts, 30_000, 1_001)
        // (30.012 − 29.970) / 30.012 of the frames, about one every 714: ten in four minutes.
        assertEquals(7_200L, clock.placedFrames)
        assertEquals(10L, clock.droppedFrames)
        assertEquals(0L, clock.filledSlots)
        assertEquals(7_190, pts.size)
    }

    @Test
    fun `a sensor running slow repeats a frame into each slot it falls behind`() {
        // PMA110 with HAL AE at "30": 33.4475 ms (29.898 fps), measured 2026-10-26.
        val clock = ntsc2997()
        val pts = run(clock, frames = 4_150, intervalNs = 33_447_500L)
        assertExactGrid(pts, 30_000, 1_001)
        assertEquals(0L, clock.droppedFrames)
        assertEquals(10L, clock.filledSlots)
    }

    @Test
    fun `every frame lands within half a slot of its capture time`() {
        val clock = ntsc2997()
        val base = 5_000_000_000L
        val slot = 1_001_000_000_000.0 / 30_000
        for (k in 0 until 5_000) {
            val elapsed = k * 33_320_000L
            val n = clock.place(base + elapsed)
            if (n > 0) assertTrue(abs(clock.presentationNs(n - 1) - elapsed) <= slot / 2)
        }
    }

    @Test
    fun `a camera gap is filled with repeats so everything after it keeps its time`() {
        val clock = ntsc2997()
        val pts = run(clock, frames = 100, intervalNs = 33_366_667L, gapsAt = setOf(50))
        assertExactGrid(pts, 30_000, 1_001)
        assertEquals(2L, clock.filledSlots)
        assertEquals(102, pts.size)
    }

    @Test
    fun `a stall longer than the fill limit is left as a hole`() {
        val clock = ConstantRateFrameClock(30_000, 1_001, maxFillSlots = 3)
        val base = 1_000_000_000L
        assertEquals(1, clock.place(base))
        assertEquals(1, clock.place(base + 33_366_667L))
        // Ten slots later: past the limit, so only its own slot.
        assertEquals(1, clock.place(base + 11 * 33_366_667L))
        assertEquals(clock.slotStartNs(11), clock.presentationNs(0))
        assertEquals(0L, clock.filledSlots)
    }

    @Test
    fun `a frame no later than the last filled slot is dropped`() {
        val clock = ntsc2997()
        assertEquals(1, clock.place(2_000_000_000L))
        assertEquals(1, clock.place(2_033_366_667L))
        assertEquals(0, clock.place(2_033_366_667L))
        assertEquals(0, clock.place(2_000_000_000L))
    }

    @Test
    fun `integer rates take the same exact grid`() {
        val clock = ConstantRateFrameClock(30, 1, maxFillSlots = 30)
        val pts = run(clock, frames = 5_000, intervalNs = 33_320_000L)
        assertExactGrid(pts, 30, 1)
        // 30.012 over 30: one frame in ~2500.
        assertEquals(2L, clock.droppedFrames)
    }

    @Test
    fun `the frame-rate enum carries the exact fractions`() {
        assertEquals(30_000 to 1_001, VideoFrameRate.FPS_29_97.rateNumerator to VideoFrameRate.FPS_29_97.rateDenominator)
        assertEquals(60_000 to 1_001, VideoFrameRate.FPS_59_94.rateNumerator to VideoFrameRate.FPS_59_94.rateDenominator)
        assertEquals(24_000 to 1_001, VideoFrameRate.FPS_23_976.rateNumerator to VideoFrameRate.FPS_23_976.rateDenominator)
        assertEquals(30 to 1, VideoFrameRate.FPS_30.rateNumerator to VideoFrameRate.FPS_30.rateDenominator)
        VideoFrameRate.entries.forEach { rate ->
            assertEquals(rate.encoderRate, rate.rateNumerator.toDouble() / rate.rateDenominator, 1e-12)
        }
    }

    @Test
    fun `the encoder fills at most one second of slots, enough for a mid-take still`() {
        assertEquals(30, encoderMaxFillSlots(30_000, 1_001))
        assertEquals(60, encoderMaxFillSlots(60_000, 1_001))
        assertEquals(24, encoderMaxFillSlots(24_000, 1_001))
        assertEquals(30, encoderMaxFillSlots(30, 1))
        assertEquals(1, encoderMaxFillSlots(1, 1))
        // A ~600 ms still stall at 29.97 is 18 slots: filled, not left as a hole.
        val clock = ConstantRateFrameClock(30_000, 1_001, encoderMaxFillSlots(30_000, 1_001))
        assertEquals(1, clock.place(1_000_000_000L))
        assertEquals(18, clock.place(1_000_000_000L + 18 * 33_366_667L))
    }

    @Test
    fun `the MP4 video timescale holds every offered frame period in whole ticks`() {
        VideoFrameRate.entries.forEach { rate ->
            val ticks = me.hletrd.telecampro.video.VIDEO_TRACK_TIME_SCALE.toLong() * rate.rateDenominator
            assertEquals("$rate", 0L, ticks % rate.rateNumerator)
        }
        // The 90 kHz default could not: 59.94 is 1501.5 ticks there.
        assertEquals(1L, 90_000L * 1_001 % 60_000 / 30_000)
    }
}
