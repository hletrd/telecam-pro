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
