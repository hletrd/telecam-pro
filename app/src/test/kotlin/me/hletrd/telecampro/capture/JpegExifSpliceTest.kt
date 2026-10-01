package me.hletrd.telecampro.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import me.hletrd.telecampro.camera.MeteringMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * AGG4-6 (+ AGG3-24): the JPEG lanes used to write the encoded bytes to the pending row and then
 * rewrite that row in place through `ExifInterface.saveAttributes()`; a kill mid-rewrite left a
 * shifted, corrupt file still ending `FF D9`, which launch recovery adopted. The APP1 is now spliced
 * into the encoded buffer before the single write. These tests prove the spliced bytes are a JPEG
 * that both ExifInterface and the decoder read, carrying the SAME tag set the HEIF lane embeds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegExifSpliceTest {
    private val cacheDir: File = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir

    @Test
    fun `a processed JPEG with the spliced APP1 parses with every exif tag and still decodes`() {
        withTimeZone("Asia/Seoul") {
            val encoded = encodedJpeg(64, 48)
            val payload = composeStillExifApp1(cacheDir, shot(), 64, 48, ExifInterface.ORIENTATION_NORMAL, null)
            val spliced = requireNotNull(spliceExifApp1(encoded, payload))

            val exif = ExifInterface(ByteArrayInputStream(spliced))
            // Parity with the HEIF lane: the identical composer call feeds both formats, and every
            // tag exifAttributeList stamps reads back from the spliced JPEG.
            exifAttributeList(shot()).forEach { (tag, _) ->
                assertNotNull("$tag missing", exif.getAttribute(tag))
            }
            assertEquals("200", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
            assertEquals("PMA110", exif.getAttribute(ExifInterface.TAG_MODEL))
            assertEquals("OPPO", exif.getAttribute(ExifInterface.TAG_MAKE))
            assertEquals("300", exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM))
            assertEquals("+09:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals(64, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
            assertEquals(48, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
            assertArrayEquals(payload, extractExifApp1(spliced))
            assertEquals(1, exifApp1Count(spliced))

            val decoded = requireNotNull(BitmapFactory.decodeByteArray(spliced, 0, spliced.size))
            assertEquals(64, decoded.width)
            assertEquals(48, decoded.height)
            // Everything after SOI is the encoder's own bytes, untouched.
            val tail = spliced.copyOfRange(spliced.size - (encoded.size - 2), spliced.size)
            assertArrayEquals(encoded.copyOfRange(2, encoded.size), tail)
        }
    }

    @Test
    fun `the HEIF payload and the JPEG payload are one composition`() {
        // writeProcessedHeif and writeProcessedJpeg both call composeStillExifApp1 with NORMAL and no
        // source; the composition is deterministic, so the two formats carry byte-identical EXIF.
        withTimeZone("UTC") {
            val heif = composeStillExifApp1(cacheDir, shot(), 40, 30, ExifInterface.ORIENTATION_NORMAL, null)
            val jpeg = composeStillExifApp1(cacheDir, shot(), 40, 30, ExifInterface.ORIENTATION_NORMAL, null)
            assertArrayEquals(heif, jpeg)
            assertTrue(isSpliceableExifPayload(heif))
        }
    }

    @Test
    fun `the passthrough lane keeps the source EXIF under ours and replaces its APP1`() {
        val source = File.createTempFile("hal-", ".jpg", cacheDir)
        source.writeBytes(encodedJpeg(32, 24))
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_SOFTWARE, "HAL 1.0")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "50")
            saveAttributes()
        }
        val hal = source.readBytes()
        assertEquals(1, exifApp1Count(hal))

        val payload = composeStillExifApp1(
            cacheDir,
            shot(),
            32,
            24,
            ExifInterface.ORIENTATION_ROTATE_90,
            sourceExifApp1 = extractExifApp1(hal),
        )
        val spliced = requireNotNull(spliceExifApp1(hal, payload))

        val exif = ExifInterface(ByteArrayInputStream(spliced))
        assertEquals("HAL 1.0", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("200", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(1, exifApp1Count(spliced))
        assertNotNull(BitmapFactory.decodeByteArray(spliced, 0, spliced.size))
    }

    @Test
    fun `a source that is not an EXIF APP1 makes the composer fail instead of guessing`() {
        assertThrows(IllegalStateException::class.java) {
            composeStillExifApp1(cacheDir, shot(), 8, 8, ExifInterface.ORIENTATION_NORMAL, byteArrayOf(1, 2, 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            composeStillExifApp1(cacheDir, shot(), 0, 8, ExifInterface.ORIENTATION_NORMAL, null)
        }
    }

    // ---- pure splice framing ---------------------------------------------------------------------

    @Test
    fun `the plan drops every old EXIF APP1 and keeps other segments, fill bytes, and the scan`() {
        val oldExif = segment(0xe1, EXIF + byteArrayOf(9, 9))
        val xmp = segment(0xe1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray())
        val app0 = segment(0xe0, "JFIF\u0000".toByteArray())
        val dqt = segment(0xdb, ByteArray(5))
        val scan = byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2, 7, 7, 0xff.toByte(), 0xd9.toByte())
        val jpeg = SOI + app0 + oldExif + byteArrayOf(0xff.toByte()) + xmp + RST0 + dqt + oldExif + scan
        val payload = EXIF + byteArrayOf(4, 2)

        val spliced = requireNotNull(spliceExifApp1(jpeg, payload))

        assertArrayEquals(
            SOI + segment(0xe1, payload) + app0 + byteArrayOf(0xff.toByte()) + xmp + RST0 + dqt + scan,
            spliced,
        )
        val written = ByteArrayOutputStream()
        writeJpegWithExifApp1(written, jpeg, requireNotNull(exifSplicePlan(jpeg)), payload)
        assertArrayEquals(spliced, written.toByteArray())
    }

    @Test
    fun `an unwalkable header or an unspliceable payload is refused`() {
        val payload = EXIF + byteArrayOf(1)
        val scan = byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2)
        // Not a JPEG.
        assertNull(spliceExifApp1(byteArrayOf(1, 2, 3, 4), payload))
        assertNull(exifSplicePlan(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        // EOI before any scan; a stuffed byte where a marker must be; garbage between segments.
        assertNull(exifSplicePlan(SOI + byteArrayOf(0xff.toByte(), 0xd9.toByte())))
        assertNull(exifSplicePlan(SOI + byteArrayOf(0xff.toByte(), 0x00) + scan))
        assertNull(exifSplicePlan(SOI + byteArrayOf(0x12, 0x34) + scan))
        // Segment length shorter than itself, overrunning the buffer, or cut inside its header.
        assertNull(exifSplicePlan(SOI + byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0, 1) + scan))
        assertNull(exifSplicePlan(SOI + byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0, 40, 1)))
        assertNull(exifSplicePlan(SOI + byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0)))
        // Header never reaches a scan.
        assertNull(exifSplicePlan(SOI + segment(0xe0, ByteArray(2))))
        // Payloads: missing signature, too short, or larger than one segment can carry.
        val jpeg = SOI + scan
        assertNull(spliceExifApp1(jpeg, byteArrayOf(1, 2, 3, 4, 5, 6, 7)))
        assertNull(spliceExifApp1(jpeg, byteArrayOf(0x45)))
        assertNull(spliceExifApp1(jpeg, EXIF + ByteArray(0xffff)))
        assertFalse(isSpliceableExifPayload(EXIF + ByteArray(0xffff - EXIF.size - 1)))
        assertTrue(isSpliceableExifPayload(EXIF + ByteArray(0xffff - EXIF.size - 2)))
        assertThrows(IllegalArgumentException::class.java) {
            writeJpegWithExifApp1(ByteArrayOutputStream(), jpeg, listOf(2 until jpeg.size), byteArrayOf(1))
        }
    }

    private fun encodedJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(200, 40, 90))
        return ByteArrayOutputStream().also {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            bitmap.recycle()
        }.toByteArray()
    }

    private fun exifApp1Count(jpeg: ByteArray): Int {
        var count = 0
        var offset = 2
        while (offset + 4 <= jpeg.size && jpeg[offset] == 0xff.toByte()) {
            val marker = jpeg[offset + 1].toInt() and 0xff
            if (marker == 0xda) break
            val length = ((jpeg[offset + 2].toInt() and 0xff) shl 8) or (jpeg[offset + 3].toInt() and 0xff)
            if (marker == 0xe1 && jpeg.copyOfRange(offset + 4, offset + 4 + EXIF.size).contentEquals(EXIF)) count++
            offset += 2 + length
        }
        return count
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    private fun shot(): ExifShot = ExifShot(
        iso = 200,
        expNs = 3_333_333L,
        lensFocalMm = 20.1f,
        lensApertureF = 2.2f,
        focal35mm = 300,
        digitalZoom = 1f,
        evBiasStops = 0f,
        meteringMode = MeteringMode.CENTER,
        flashFired = false,
        exposureProgram = 2,
        manualExposure = false,
        manualWb = false,
        lensModel = "OPPO 70mm f/2.2",
        deviceMake = "OPPO",
        deviceModel = "PMA110",
        takenAtMs = 1_700_000_000_000L,
    )

    private fun <T> withTimeZone(id: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private companion object {
        val SOI = byteArrayOf(0xff.toByte(), 0xd8.toByte())
        val RST0 = byteArrayOf(0xff.toByte(), 0xd0.toByte())
        val EXIF = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
    }
}
