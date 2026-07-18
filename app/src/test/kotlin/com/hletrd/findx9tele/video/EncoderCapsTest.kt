package com.hletrd.findx9tele.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two pure seams of the encoder scan (TEST4-19): the name heuristic that backs a throwing
 * isHardwareAccelerated, and the hardware-first tie-break. EncoderCaps itself needs a live
 * MediaCodecList and stays device-verified.
 */
class EncoderCapsTest {

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
