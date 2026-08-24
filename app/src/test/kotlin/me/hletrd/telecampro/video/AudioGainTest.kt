package me.hletrd.telecampro.video

import me.hletrd.telecampro.camera.normalizeAudioGain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins recording-audio gain/level math — pure java.nio, JVM-testable. */
class AudioGainTest {

    private fun pcmBuffer(vararg samples: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        buf.rewind()
        return buf
    }

    private fun samplesOf(buf: ByteBuffer, count: Int): ShortArray {
        val view = buf.duplicate().apply {
            clear()
            order(ByteOrder.LITTLE_ENDIAN)
        }.asShortBuffer()
        return ShortArray(count) { view.get(it) }
    }

    @Test
    fun `unity gain leaves samples untouched and reports RMS`() {
        val buf = pcmBuffer(1000, -1000, 1000, -1000)
        val level = applyGainAndMaybeMeasureLevel(buf, 8, 1f, measureLevel = true)!!.single()
        assertEquals(listOf<Short>(1000, -1000, 1000, -1000), samplesOf(buf, 4).toList())
        // RMS of |1000| normalized by 32768.
        assertEquals(1000f / 32768f, level, 1e-4f)
    }

    @Test
    fun `gain amplifies in place`() {
        val buf = pcmBuffer(1000, -2000)
        val level = applyGainAndMaybeMeasureLevel(buf, 4, 2f, measureLevel = true)!!.single()
        assertEquals(listOf<Short>(2000, -4000), samplesOf(buf, 2).toList())
        assertTrue(level > 0f)
    }

    @Test
    fun `gain clamps at the Short range instead of wrapping`() {
        val buf = pcmBuffer(30000, -30000)
        applyPcmGain(buf, 4, 4f)
        assertEquals(listOf(Short.MAX_VALUE, Short.MIN_VALUE), samplesOf(buf, 2).toList())
    }

    @Test
    fun `empty buffer reports zero level`() {
        assertEquals(0f, measurePcmLevels(pcmBuffer(), 0).single(), 0f)
    }

    @Test
    fun `level is normalized into 0-1`() {
        val buf = pcmBuffer(Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE)
        val level = applyGainAndMaybeMeasureLevel(buf, 8, 8f, measureLevel = true)!!.single()
        assertTrue(level in 0f..1f)
    }

    @Test
    fun `odd byteCount is frame-safe and returns a finite level`() {
        // A non-frame-aligned byteCount (5 bytes = 2 whole 16-bit frames + 1 trailing byte): the short
        // view exposes only the 2 complete frames, so the third sample is never read or written.
        val buf = pcmBuffer(1000, -1000, 2000)
        val level = applyGainAndMaybeMeasureLevel(buf, 5, 2f, measureLevel = true)!!.single()
        assertEquals(listOf<Short>(2000, -2000, 2000), samplesOf(buf, 3).toList())
        assertTrue(level.isFinite())
        assertTrue(level in 0f..1f)
    }

    @Test
    fun `partial multichannel frame is rewritten but excluded from channel meters`() {
        val buf = pcmBuffer(1_000)

        val levels = applyGainAndMaybeMeasureLevel(
            buf,
            byteCount = 2,
            gain = 2f,
            channelCount = 2,
            measureLevel = true,
        )!!

        assertEquals(listOf<Short>(2_000), samplesOf(buf, 1).toList())
        assertEquals(listOf(0f, 0f), levels.toList())
    }

    @Test
    fun `non-finite and out-of-range gain is normalized at PCM boundary`() {
        assertEquals(1f, normalizeAudioGain(Float.NaN), 0f)
        assertEquals(1f, normalizeAudioGain(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, normalizeAudioGain(-1f), 0f)
        assertEquals(2f, normalizeAudioGain(3f), 0f)

        val buf = pcmBuffer(1000, -1000)
        val level = applyGainAndMaybeMeasureLevel(buf, 4, Float.NaN, measureLevel = true)!!.single()
        assertEquals(listOf<Short>(1000, -1000), samplesOf(buf, 2).toList())
        assertTrue(level.isFinite())
    }

    @Test
    fun `non-emission path applies gain with no level result`() {
        val buf = pcmBuffer(1_000, -2_000, 3_000)

        val level = applyGainAndMaybeMeasureLevel(
            buf,
            byteCount = 6,
            gain = 2f,
            channelCount = 2,
            measureLevel = false,
        )

        assertNull(level)
        assertEquals(listOf<Short>(2_000, -4_000, 6_000), samplesOf(buf, 3).toList())
    }

    @Test
    fun `gain and measurement preserve buffer position limit and byte order`() {
        val buf = pcmBuffer(1_000, -2_000, 3_000).apply {
            position(2)
            limit(5)
            order(ByteOrder.BIG_ENDIAN)
        }

        val level = applyGainAndMaybeMeasureLevel(
            buf,
            byteCount = 5,
            gain = 2f,
            channelCount = 1,
            measureLevel = true,
        )!!.single()

        assertEquals(2, buf.position())
        assertEquals(5, buf.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buf.order())
        assertEquals(listOf<Short>(2_000, -4_000, 3_000), samplesOf(buf, 3).toList())
        assertEquals(kotlin.math.sqrt(10_000_000f) / 32768f, level, 1e-4f)
    }

    @Test
    fun `stereo measurement keeps exact per-channel post-gain RMS`() {
        val buf = pcmBuffer(1_000, 2_000, -3_000, -4_000)

        val levels = applyGainAndMaybeMeasureLevel(
            buf,
            byteCount = 8,
            gain = 2f,
            channelCount = 2,
            measureLevel = true,
        )!!

        assertEquals(kotlin.math.sqrt(20_000_000f) / 32768f, levels[0], 1e-4f)
        assertEquals(kotlin.math.sqrt(40_000_000f) / 32768f, levels[1], 1e-4f)
    }

    @Test
    fun `recording peak accumulator holds an isolated clipped sample across buffers`() {
        val held = FloatArray(2)
        val first = pcmBuffer(1_000, 2_000, Short.MAX_VALUE, 3_000)
        accumulatePcmPeaks(first, byteCount = 8, channelCount = 2, target = held)
        val second = pcmBuffer(500, 4_000, -500, -4_000)
        accumulatePcmPeaks(second, byteCount = 8, channelCount = 2, target = held)

        assertEquals(Short.MAX_VALUE.toFloat() / 32768f, held[0], 0f)
        assertEquals(4_000f / 32768f, held[1], 0f)
    }
}
