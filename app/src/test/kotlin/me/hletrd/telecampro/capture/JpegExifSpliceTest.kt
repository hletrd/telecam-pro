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
import me.hletrd.telecampro.storage.PendingProbe
import me.hletrd.telecampro.storage.probeCompleteJpegStructure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // TE5-7: the old "parity" test called composeStillExifApp1 twice and compared — determinism, not
    // wiring. AGG5-35: the lane orchestration now composes ONCE and hands the same payload to both.
    @Test
    fun `a dual-format shot composes its EXIF once and both formats receive that payload`() {
        val payload = EXIF + byteArrayOf(4, 2)
        var compositions = 0
        val received = mutableListOf<Pair<String, ByteArray?>>()
        val failures = mutableListOf<String>()
        writeProcessedStillFormats(
            wantHeif = true,
            wantJpeg = true,
            composeExif = { compositions++; payload },
            writeHeif = { received += "heif" to it; throw IllegalStateException("HEIF encoder died") },
            writeJpeg = { received += "jpeg" to it },
            onHeifFailure = { failures += "heif" },
            onJpegFailure = { failures += "jpeg" },
        )
        assertEquals(1, compositions)
        assertEquals(listOf("heif", "jpeg"), received.map { it.first })
        received.forEach { (_, exif) -> assertSame(payload, exif) }
        assertEquals("a HEIF failure never costs the JPEG", listOf("heif"), failures)

        // One format: still one composition; a best-effort null reaches the lane as "no EXIF".
        compositions = 0
        received.clear()
        writeProcessedStillFormats(
            wantHeif = false,
            wantJpeg = true,
            composeExif = { compositions++; null },
            writeHeif = { error("not wanted") },
            writeJpeg = { received += "jpeg" to it },
            onHeifFailure = { error("not wanted") },
            onJpegFailure = { throw it },
        )
        assertEquals(1, compositions)
        assertEquals(listOf<Pair<String, ByteArray?>>("jpeg" to null), received)
    }

    // AGG5-34 + TE5-7: the processed lane hands the encoder's own buffer (capacity > count) to the
    // single write, and that write is byte-identical to the in-memory splice of the trimmed JPEG.
    @Test
    fun `the single JPEG write splices from the encoder buffer in one open`() {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(20, 140, 90))
        val encoded = EncodedJpegBuffer(processedJpegInitialCapacity(48, 32))
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded))
        bitmap.recycle()
        assertTrue("the buffer is pre-sized beyond the JPEG", encoded.buffer().size > encoded.size())
        val trimmed = encoded.toByteArray()
        val payload = composeStillExifApp1(cacheDir, shot(), 48, 32, ExifInterface.ORIENTATION_NORMAL, null)

        var opens = 0
        val written = ByteArrayOutputStream()
        val refusals = mutableListOf<String>()
        assertTrue(
            writeJpegOnce(
                open = { opens++; written },
                encoded = encoded.buffer(),
                length = encoded.size(),
                exifPayload = payload,
                onSpliceRefused = { refusals += it },
            ),
        )
        assertEquals(1, opens)
        assertEquals(emptyList<String>(), refusals)
        assertArrayEquals(requireNotNull(spliceExifApp1(trimmed, payload)), written.toByteArray())
        assertEquals(1, exifApp1Count(written.toByteArray()))
        assertNotNull(BitmapFactory.decodeByteArray(written.toByteArray(), 0, written.size()))

        // An unspliceable payload: the JPEG prefix verbatim (never the buffer's spare capacity).
        val verbatim = ByteArrayOutputStream()
        assertTrue(writeJpegOnce({ verbatim }, encoded.buffer(), encoded.size(), byteArrayOf(1, 2), { refusals += it }))
        assertArrayEquals(trimmed, verbatim.toByteArray())
        assertEquals(1, refusals.size)
        // No payload at all: verbatim, no refusal row.
        val plain = ByteArrayOutputStream()
        assertTrue(writeJpegOnce({ plain }, encoded.buffer(), encoded.size(), null, { refusals += it }))
        assertArrayEquals(trimmed, plain.toByteArray())
        assertEquals(1, refusals.size)
        // No stream: nothing written, reported to the caller.
        assertFalse(writeJpegOnce({ null }, encoded.buffer(), encoded.size(), payload, { refusals += it }))
    }

    @Test
    fun `the splice plan reads only the declared JPEG length`() {
        val scan = byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2, 7, 7, 0xff.toByte(), 0xd9.toByte())
        val jpeg = SOI + segment(0xe0, ByteArray(3)) + scan
        val padded = jpeg + ByteArray(32) { 0x55 }
        assertEquals(exifSplicePlan(jpeg), exifSplicePlan(padded, jpeg.size))
        assertThrows(IllegalArgumentException::class.java) { exifSplicePlan(jpeg, jpeg.size + 1) }
        assertTrue(processedJpegInitialCapacity(4080, 3064) >= 4080 * 3064 / 2)
        assertEquals(64 * 1024, processedJpegInitialCapacity(1, 1))
    }

    // TE5-7: the passthrough lane's wiring — the HAL EXIF seeds the composer (MRG4-9's sideways save
    // was the lane passing null) and the capture rotation lands as the orientation TAG.
    @Test
    fun `the passthrough EXIF build keeps the HAL tags and tags the capture rotation`() {
        val source = File.createTempFile("hal-lane-", ".jpg", cacheDir)
        source.writeBytes(encodedJpeg(32, 24))
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_SOFTWARE, "HAL 2.0")
            saveAttributes()
        }
        val hal = source.readBytes()

        val payload = composePassthroughStillExif(cacheDir, hal, shot(), rotationDegrees = 90)

        val exif = ExifInterface(ByteArrayInputStream(requireNotNull(spliceExifApp1(hal, payload))))
        assertEquals("HAL 2.0", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals(
            me.hletrd.telecampro.camera.RotationMath.exifOrientationFor(90),
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0),
        )
        assertEquals(32, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(24, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
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

    // AGG5-51 (SR5-5): the passthrough seed is the HAL's own APP1, which the app does not control.
    @Test
    fun `a HAL-seeded composition drops GPS, serials, owner, unique id, and MakerNote`() {
        val source = File.createTempFile("hal-private-", ".jpg", cacheDir)
        source.writeBytes(encodedJpeg(32, 24))
        ExifInterface(source).apply {
            setLatLong(37.5665, 126.9780)
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "38/1")
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:10:02")
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SN-0042")
            setAttribute(ExifInterface.TAG_LENS_SERIAL_NUMBER, "LSN-7")
            setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, "Owner")
            setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, "0123456789abcdef0123456789abcdef")
            setAttribute(ExifInterface.TAG_SOFTWARE, "HAL 3.0")
            saveAttributes()
        }
        val hal = source.readBytes()
        val halExif = ExifInterface(ByteArrayInputStream(hal))
        assertNotNull("fixture precondition: the HAL APP1 carries GPS", halExif.latLong)
        assertEquals("SN-0042", halExif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))

        val payload = composeStillExifApp1(
            cacheDir,
            shot(),
            32,
            24,
            ExifInterface.ORIENTATION_ROTATE_90,
            sourceExifApp1 = extractExifApp1(hal),
        )

        val exif = ExifInterface(ByteArrayInputStream(requireNotNull(spliceExifApp1(hal, payload))))
        assertNull(exif.latLong)
        PASSTHROUGH_PRIVACY_STRIPPED_TAGS.forEach { tag -> assertNull("$tag survived", exif.getAttribute(tag)) }
        // Everything else the HAL wrote still sits under ours, and ours still lands.
        assertEquals("HAL 3.0", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("200", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        // The strip list covers the directory and identifiers the finding names.
        assertTrue(ExifInterface.TAG_MAKER_NOTE in PASSTHROUGH_PRIVACY_STRIPPED_TAGS)
        assertTrue(ExifInterface.TAG_GPS_LATITUDE in PASSTHROUGH_PRIVACY_STRIPPED_TAGS)
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

    // MRG4-9: a HAL EXIF whose thumbnail sits near the APP1 cap used to cost the passthrough ALL of
    // its EXIF — Orientation included, so a rotated hi-res capture displayed sideways.
    @Test
    fun `an oversized-thumbnail HAL EXIF keeps orientation by dropping only the thumbnail`() {
        val source = halExifWithThumbnail(thumbnailLength = 65_000, littleEndian = true)
        assertTrue("the fixture is itself a legal APP1", isSpliceableExifPayload(source))
        val compose = { seed: ByteArray? ->
            composeStillExifApp1(cacheDir, shot(), 32, 24, ExifInterface.ORIENTATION_ROTATE_90, seed)
        }
        // The single-tier composition the lane used before cannot be spliced (or is refused outright).
        val untiered = runCatching { compose(source) }.getOrNull()
        assertFalse(untiered != null && isSpliceableExifPayload(untiered))

        val payload = composePassthroughExifApp1(source, compose)

        assertTrue(isSpliceableExifPayload(payload))
        val spliced = requireNotNull(spliceExifApp1(encodedJpeg(32, 24), payload))
        val exif = ExifInterface(ByteArrayInputStream(spliced))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("the HAL's own tags survive the thumbnail drop", "HAL 1.0", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertFalse(exif.hasThumbnail())
    }

    @Test
    fun `passthrough tiers keep the full source when it fits and fall back to ours alone last`() {
        val seen = mutableListOf<ByteArray?>()
        val small = EXIF + byteArrayOf(7)
        assertArrayEquals(small, composePassthroughExifApp1(small) { seen += it; small })
        assertEquals(1, seen.size)

        seen.clear()
        val ours = EXIF + byteArrayOf(1)
        val source = halExifWithThumbnail(thumbnailLength = 16, littleEndian = false)
        val result = composePassthroughExifApp1(source) { seed ->
            seen += seed
            if (seed == null) ours else throw IllegalStateException("too large")
        }
        assertArrayEquals(ours, result)
        assertEquals(3, seen.size)
        assertArrayEquals(source, seen[0])
        assertNull(seen[2])

        // No source at all: ours, once.
        seen.clear()
        assertArrayEquals(ours, composePassthroughExifApp1(null) { seen += it; ours })
        assertEquals(listOf<ByteArray?>(null), seen)
    }

    @Test
    fun `the thumbnail unlink zeroes only IFD0's next-IFD link and refuses what it cannot walk`() {
        for (littleEndian in listOf(true, false)) {
            val source = halExifWithThumbnail(thumbnailLength = 16, littleEndian = littleEndian)
            val stripped = requireNotNull(exifApp1WithoutThumbnailIfd(source))
            // IFD0 at TIFF offset 8 with one entry: the link sits at 6 + 8 + 2 + 12.
            val link = 6 + 8 + 2 + 12
            assertArrayEquals(ByteArray(4), stripped.copyOfRange(link, link + 4))
            assertArrayEquals(source.copyOfRange(0, link), stripped.copyOfRange(0, link))
            assertArrayEquals(source.copyOfRange(link + 4, source.size), stripped.copyOfRange(link + 4, source.size))
            // Already unlinked: nothing to strip.
            assertNull(exifApp1WithoutThumbnailIfd(stripped))
        }
        val good = halExifWithThumbnail(thumbnailLength = 16, littleEndian = true)
        assertNull("not an EXIF APP1", exifApp1WithoutThumbnailIfd(byteArrayOf(1, 2, 3)))
        assertNull("too short for a TIFF header", exifApp1WithoutThumbnailIfd(EXIF + byteArrayOf(0x49, 0x49)))
        assertNull("unknown byte order", exifApp1WithoutThumbnailIfd(good.copyOf().also { it[6] = 0x41; it[7] = 0x41 }))
        assertNull("bad TIFF magic", exifApp1WithoutThumbnailIfd(good.copyOf().also { it[8] = 0x2b }))
        assertNull("IFD0 inside the header", exifApp1WithoutThumbnailIfd(good.copyOf().also { it[10] = 4 }))
        assertNull("IFD0 past the end", exifApp1WithoutThumbnailIfd(good.copyOf().also { it[11] = 0x7f }))
        assertNull(
            "an entry count whose link overruns",
            exifApp1WithoutThumbnailIfd(good.copyOf().also { it[6 + 8] = 0xff.toByte(); it[6 + 9] = 0x0f }),
        )
    }

    /**
     * A hand-built `Exif\0\0` + TIFF: IFD0 = Software "HAL 1.0"; IFD1 = a JPEG thumbnail of
     * [thumbnailLength] bytes (a real tiny JPEG, padded), the shape a HAL emits near the cap.
     */
    private fun halExifWithThumbnail(thumbnailLength: Int, littleEndian: Boolean): ByteArray {
        val order = if (littleEndian) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN
        val software = "HAL 1.0\u0000".toByteArray(Charsets.US_ASCII)
        val ifd0 = 8
        val softwareAt = ifd0 + 2 + 12 + 4
        val ifd1 = softwareAt + software.size
        val thumbAt = ifd1 + 2 + 3 * 12 + 4
        val tiff = java.nio.ByteBuffer.allocate(thumbAt + thumbnailLength).order(order)
        tiff.put(if (littleEndian) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4d, 0x4d))
        tiff.putShort(0x2a).putInt(ifd0)
        tiff.putShort(1)
        tiff.putShort(0x0131).putShort(2).putInt(software.size).putInt(softwareAt)
        tiff.putInt(ifd1)
        tiff.put(software)
        tiff.putShort(3)
        tiff.putShort(0x0103).putShort(3).putInt(1).putShort(6).putShort(0) // Compression = JPEG
        tiff.putShort(0x0201.toShort()).putShort(4).putInt(1).putInt(thumbAt)
        tiff.putShort(0x0202.toShort()).putShort(4).putInt(1).putInt(thumbnailLength)
        tiff.putInt(0)
        val thumbnail = encodedJpeg(8, 8)
        tiff.put(thumbnail, 0, minOf(thumbnail.size, thumbnailLength))
        return EXIF + tiff.array()
    }

    // ---- recovery's structural JPEG probe (AGG5-71) ---------------------------------------------

    @Test
    fun `a passthrough file killed after its APP1 write ends FF D9 yet is not a complete JPEG`() {
        // A thumbnail-carrying APP1 whose IFD1 JPEG thumbnail is its LAST bytes (the HAL layout the
        // passthrough tier seeds from), so the payload ends in the thumbnail's own EOI.
        val payload = halExifWithThumbnail(thumbnailLength = encodedJpeg(8, 8).size, littleEndian = true)
        assertTrue(isSpliceableExifPayload(payload))
        val headerOnly = SOI + segment(0xe1, payload)
        assertArrayEquals(
            "fixture precondition: the header-only prefix ends in an EOI",
            byteArrayOf(0xff.toByte(), 0xd9.toByte()),
            headerOnly.copyOfRange(headerOnly.size - 2, headerOnly.size),
        )
        assertEquals(PendingProbe.INVALID, probeJpeg(headerOnly))

        val whole = requireNotNull(spliceExifApp1(encodedJpeg(32, 24), payload))
        assertEquals(PendingProbe.VALID, probeJpeg(whole))
        assertEquals(PendingProbe.VALID, probeJpeg(encodedJpeg(16, 16)))
    }

    @Test
    fun `the JPEG probe needs SOI, a scan before the tail, and a walkable header`() {
        val scan = byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 4, 7, 7, 0xff.toByte(), 0xd9.toByte())
        assertEquals(PendingProbe.VALID, probeJpeg(SOI + scan))
        assertEquals(
            "fill bytes and RST are walked like the splice plan",
            PendingProbe.VALID,
            probeJpeg(SOI + segment(0xe0, ByteArray(3)) + byteArrayOf(0xff.toByte()) + RST0 + scan),
        )
        assertEquals("too short", PendingProbe.INVALID, probeJpeg(byteArrayOf(0xff.toByte(), 0xd9.toByte())))
        assertEquals("no EOI tail", PendingProbe.INVALID, probeJpeg(SOI + scan.copyOf(6)))
        assertEquals("no SOI", PendingProbe.INVALID, probeJpeg(byteArrayOf(0, 0) + scan))
        assertEquals("EOI only", PendingProbe.INVALID, probeJpeg(SOI + byteArrayOf(0xff.toByte(), 0xd9.toByte())))
        assertEquals(
            "a segment that reaches the tail",
            PendingProbe.INVALID,
            probeJpeg(SOI + segment(0xe0, byteArrayOf(1, 0xff.toByte(), 0xd9.toByte()))),
        )
        assertEquals(
            "a segment shorter than itself",
            PendingProbe.INVALID,
            probeJpeg(SOI + byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0, 1) + scan),
        )
        assertEquals(
            "a stuffed byte where a marker must be",
            PendingProbe.INVALID,
            probeJpeg(SOI + byteArrayOf(0xff.toByte(), 0x00) + scan),
        )
        assertEquals(
            "garbage between segments",
            PendingProbe.INVALID,
            probeJpeg(SOI + byteArrayOf(0x12, 0x34) + scan),
        )
        assertEquals(
            "a short read proves nothing complete",
            PendingProbe.INVALID,
            probeCompleteJpegStructure((SOI + scan).size.toLong()) { _, _ -> null },
        )
    }

    private fun probeJpeg(bytes: ByteArray): PendingProbe =
        probeCompleteJpegStructure(bytes.size.toLong()) { offset, count ->
            val start = offset.toInt()
            if (start < 0 || start + count > bytes.size) null else bytes.copyOfRange(start, start + count)
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
