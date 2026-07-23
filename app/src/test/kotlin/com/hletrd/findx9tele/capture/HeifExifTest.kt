package com.hletrd.findx9tele.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HeifExifTest {

    @Test
    fun `extracts APP1 payload without JPEG marker and length`() {
        val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0, 1, 2, 3)
        val segmentLength = payload.size + 2
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe0.toByte(), 0, 4, 9, 9,
            0xff.toByte(), 0xe1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
            *payload,
            0xff.toByte(), 0xd9.toByte(),
        )

        assertArrayEquals(payload, extractExifApp1(jpeg))
    }

    @Test
    fun `walks 0xFF fill bytes without consuming the real marker`() {
        // FF FF FF E1: fill bytes pad the real APP1 marker. A two-byte step from the first fill
        // byte consumed the second FF AND the E1, landing mid-segment (cycle-6 code-review F10) —
        // each fill byte must advance the scan by exactly one.
        val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0, 7)
        val segmentLength = payload.size + 2
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xff.toByte(), // fill run
            0xff.toByte(), 0xe1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
            *payload,
            0xff.toByte(), 0xd9.toByte(),
        )

        assertArrayEquals(payload, extractExifApp1(jpeg))
    }

    @Test
    fun `skips stray non-marker garbage one byte at a time before APP1`() {
        // A byte that is not 0xFF between segments is not a marker lead — the scan must advance
        // by exactly one so the FF E1 that follows is still recognized at its own offset.
        val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0, 5)
        val segmentLength = payload.size + 2
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0x41, 0x42, // stray garbage, no 0xFF lead
            0xff.toByte(), 0xe1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
            *payload,
            0xff.toByte(), 0xd9.toByte(),
        )

        assertArrayEquals(payload, extractExifApp1(jpeg))
    }

    @Test
    fun `steps over length-less stuffed TEM and RST codes without a length read`() {
        // FF 00 (stuffed), FF 01 (TEM), and FF D0..D7 (RST) carry NO two-byte segment length.
        // Reading a length there would consume arbitrary bytes and lose the real APP1.
        val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0, 9)
        val segmentLength = payload.size + 2
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0x00, // stuffed byte
            0xff.toByte(), 0x01, // TEM
            0xff.toByte(), 0xd0.toByte(), // RST0
            0xff.toByte(), 0xd7.toByte(), // RST7
            0xff.toByte(), 0xe1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
            *payload,
            0xff.toByte(), 0xd9.toByte(),
        )

        assertArrayEquals(payload, extractExifApp1(jpeg))
    }

    @Test
    fun `rejects truncated or non EXIF APP1`() {
        assertNull(extractExifApp1(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertNull(
            extractExifApp1(
                byteArrayOf(
                    0xff.toByte(), 0xd8.toByte(),
                    0xff.toByte(), 0xe1.toByte(), 0, 8,
                    'N'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 'e'.code.toByte(), 0, 0,
                ),
            ),
        )
    }

    @Test
    fun `HEIF EXIF dimensions replace every one-pixel seed dimension`() {
        assertEquals(
            listOf(
                "ImageWidth" to "3072",
                "ImageLength" to "4096",
                "PixelXDimension" to "3072",
                "PixelYDimension" to "4096",
            ),
            heifExifDimensionAttributes(width = 3072, height = 4096),
        )
    }

    @Test
    fun `HEIF EXIF dimensions reject invalid encoded sizes`() {
        assertThrows(IllegalArgumentException::class.java) {
            heifExifDimensionAttributes(width = 0, height = 4096)
        }
        assertThrows(IllegalArgumentException::class.java) {
            heifExifDimensionAttributes(width = 3072, height = -1)
        }
    }
}
