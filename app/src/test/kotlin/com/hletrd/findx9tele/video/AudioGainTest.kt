package com.hletrd.findx9tele.video

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the recording-audio gain/level math ([applyGainAndLevel]) — pure java.nio, JVM-testable. */
class AudioGainTest {

    private fun pcmBuffer(vararg samples: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        buf.rewind()
        return buf
    }

    private fun samplesOf(buf: ByteBuffer, count: Int): ShortArray {
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(count) { view.get(it) }
    }

    @Test
    fun `unity gain leaves samples untouched and reports RMS`() {
        val buf = pcmBuffer(1000, -1000, 1000, -1000)
        val level = applyGainAndLevel(buf, 8, 1f)
        assertEquals(listOf<Short>(1000, -1000, 1000, -1000), samplesOf(buf, 4).toList())
        // RMS of |1000| normalized by 32768.
        assertEquals(1000f / 32768f, level, 1e-4f)
    }

    @Test
    fun `gain amplifies in place`() {
        val buf = pcmBuffer(1000, -2000)
        val level = applyGainAndLevel(buf, 4, 2f)
        assertEquals(listOf<Short>(2000, -4000), samplesOf(buf, 2).toList())
        assertTrue(level > 0f)
    }

    @Test
    fun `gain clamps at the Short range instead of wrapping`() {
        val buf = pcmBuffer(30000, -30000)
        applyGainAndLevel(buf, 4, 4f)
        assertEquals(listOf(Short.MAX_VALUE, Short.MIN_VALUE), samplesOf(buf, 2).toList())
    }

    @Test
    fun `empty buffer reports zero level`() {
        assertEquals(0f, applyGainAndLevel(pcmBuffer(), 0, 1.5f), 0f)
    }

    @Test
    fun `level is normalized into 0-1`() {
        val buf = pcmBuffer(Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE)
        val level = applyGainAndLevel(buf, 8, 8f)
        assertTrue(level in 0f..1f)
    }
}
