package me.hletrd.telecampro.capture

import androidx.exifinterface.media.ExifInterface

/**
 * Extracts the APP1 EXIF payload (`Exif\u0000\u0000` + TIFF data) from a JPEG. HeifWriter expects
 * exactly this payload, without the JPEG marker or two-byte segment length. Kept pure so the byte
 * framing is host-tested independently of Android's ExifInterface implementation.
 */
internal fun extractExifApp1(jpeg: ByteArray): ByteArray? {
    if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte()) return null
    var offset = 2
    while (offset + 4 <= jpeg.size) {
        if (jpeg[offset] != 0xff.toByte()) {
            offset++
            continue
        }
        val marker = jpeg[offset + 1].toInt() and 0xff
        if (marker == 0xda || marker == 0xd9) return null
        if (marker == 0xff) {
            // 0xFF fill byte, not a marker: the byte AFTER it may itself start the real marker
            // (FF FF E1 ...), so advance by ONE — a two-byte step consumed the real marker's lead
            // byte and landed mid-segment.
            offset += 1
            continue
        }
        if (marker == 0x00 || marker == 0x01 || marker in 0xd0..0xd7) {
            // Stuffed byte (FF 00), TEM, and RST are length-less two-byte codes.
            offset += 2
            continue
        }
        val segmentLength = ((jpeg[offset + 2].toInt() and 0xff) shl 8) or
            (jpeg[offset + 3].toInt() and 0xff)
        if (segmentLength < 2) return null
        val payloadStart = offset + 4
        val payloadEnd = offset + 2 + segmentLength
        if (payloadEnd > jpeg.size) return null
        if (marker == 0xe1 && payloadEnd - payloadStart >= EXIF_SIGNATURE.size &&
            EXIF_SIGNATURE.indices.all { index -> jpeg[payloadStart + index] == EXIF_SIGNATURE[index] }
        ) {
            return jpeg.copyOfRange(payloadStart, payloadEnd)
        }
        offset = payloadEnd
    }
    return null
}

/**
 * Dimension tags that must describe the HEIF image receiving this EXIF payload, not the tiny JPEG
 * used only as an ExifInterface serialization vessel.
 *
 * MediaStore's WIDTH/HEIGHT columns are read-only and are indexed from ImageWidth/ImageLength.
 * PixelX/YDimension are the corresponding compressed-image tags and also let ExifInterface repair
 * the primary dimensions while parsing. Writing both pairs keeps the embedded metadata and the
 * published MediaStore row aligned with the actual HEIF item dimensions.
 */
internal fun heifExifDimensionAttributes(
    width: Int,
    height: Int,
): List<Pair<String, String>> {
    require(width > 0 && height > 0) { "HEIF dimensions must be positive" }
    val widthValue = width.toString()
    val heightValue = height.toString()
    return listOf(
        ExifInterface.TAG_IMAGE_WIDTH to widthValue,
        ExifInterface.TAG_IMAGE_LENGTH to heightValue,
        ExifInterface.TAG_PIXEL_X_DIMENSION to widthValue,
        ExifInterface.TAG_PIXEL_Y_DIMENSION to heightValue,
    )
}

/**
 * Byte ranges of [jpeg] that follow the spliced APP1, or null when the header cannot be walked.
 *
 * AGG4-6 / AGG3-24: the JPEG lanes used to write the encoded bytes to the pending row and then let
 * `ExifInterface.saveAttributes()` rewrite that same row in place from offset 0. The rewrite is
 * longer than the original (it gains APP1) and does not truncate, so a kill mid-rewrite left a new
 * header over the old bytes shifted by the APP1 length — still ending `FF D9`, which launch
 * recovery's tail probe adopts as VALID. The EXIF segment is therefore spliced into the encoded
 * buffer BEFORE the single write, and no byte of the pending row is ever rewritten.
 *
 * The plan keeps every header segment except existing `Exif` APP1s (the replacement already
 * carries whatever of them the composer chose to preserve), then everything from SOS to the end
 * verbatim. A missing SOI, a segment that overruns, or an EOI before any SOS is null — the caller
 * then writes the encoded bytes WITHOUT EXIF rather than guess at a structure it could not read.
 */
internal fun exifSplicePlan(jpeg: ByteArray): List<IntRange>? {
    if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte()) return null
    val kept = mutableListOf<IntRange>()
    var keptStart = 2
    var offset = 2
    while (offset + 2 <= jpeg.size) {
        if (jpeg[offset] != 0xff.toByte()) return null
        val marker = jpeg[offset + 1].toInt() and 0xff
        when {
            // Fill byte: stays inside the current kept run; the real marker follows it.
            marker == 0xff -> offset += 1
            marker == 0xda -> {
                kept += keptStart until jpeg.size
                return kept.filterNot { it.isEmpty() }
            }
            // EOI before any scan, or a stuffed byte where a header marker must be: not a walkable
            // header, so no EXIF rather than a guessed splice.
            marker == 0xd9 || marker == 0x00 -> return null
            marker == 0x01 || marker in 0xd0..0xd7 -> offset += 2
            else -> {
                if (offset + 4 > jpeg.size) return null
                val segmentLength = ((jpeg[offset + 2].toInt() and 0xff) shl 8) or
                    (jpeg[offset + 3].toInt() and 0xff)
                if (segmentLength < 2) return null
                val end = offset + 2 + segmentLength
                if (end > jpeg.size) return null
                val payloadStart = offset + 4
                val exif = marker == 0xe1 && end - payloadStart >= EXIF_SIGNATURE.size &&
                    EXIF_SIGNATURE.indices.all { index -> jpeg[payloadStart + index] == EXIF_SIGNATURE[index] }
                if (exif) {
                    kept += keptStart until offset
                    keptStart = end
                }
                offset = end
            }
        }
    }
    return null
}

/** True when [payload] is an `Exif\0\0` APP1 body that fits one JPEG segment. */
internal fun isSpliceableExifPayload(payload: ByteArray): Boolean =
    payload.size + 2 <= MAX_JPEG_SEGMENT_LENGTH &&
        payload.size >= EXIF_SIGNATURE.size &&
        EXIF_SIGNATURE.indices.all { index -> payload[index] == EXIF_SIGNATURE[index] }

/**
 * Writes SOI, one APP1 carrying [payload], then [plan]'s ranges of [jpeg] — the whole file in one
 * pass, so the destination is written exactly once (see [exifSplicePlan]).
 */
internal fun writeJpegWithExifApp1(
    out: java.io.OutputStream,
    jpeg: ByteArray,
    plan: List<IntRange>,
    payload: ByteArray,
) {
    require(isSpliceableExifPayload(payload)) { "not a spliceable EXIF APP1 payload" }
    val segmentLength = payload.size + 2
    out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()))
    out.write(byteArrayOf((segmentLength ushr 8).toByte(), segmentLength.toByte()))
    out.write(payload)
    plan.forEach { range -> out.write(jpeg, range.first, range.last - range.first + 1) }
}

/** In-memory [writeJpegWithExifApp1]; null when [jpeg] or [payload] cannot be spliced. */
internal fun spliceExifApp1(jpeg: ByteArray, payload: ByteArray): ByteArray? {
    if (!isSpliceableExifPayload(payload)) return null
    val plan = exifSplicePlan(jpeg) ?: return null
    return java.io.ByteArrayOutputStream(jpeg.size + payload.size + 4)
        .also { writeJpegWithExifApp1(it, jpeg, plan, payload) }
        .toByteArray()
}

/**
 * [payload] (an `Exif\0\0` APP1 body) with IFD0's next-IFD link zeroed, so a reader no longer
 * reaches IFD1 — the thumbnail directory. The thumbnail bytes stay in the buffer as unreferenced
 * data; ExifInterface re-serializes only what it parsed, so a composition seeded with this drops the
 * thumbnail (MRG4-9). Null when the TIFF header or IFD0 cannot be walked, or there is no IFD1.
 */
internal fun exifApp1WithoutThumbnailIfd(payload: ByteArray): ByteArray? {
    val tiff = EXIF_SIGNATURE.size
    if (!isSpliceableExifPayload(payload) || payload.size < tiff + 8) return null
    val littleEndian = when {
        payload[tiff] == 'I'.code.toByte() && payload[tiff + 1] == 'I'.code.toByte() -> true
        payload[tiff] == 'M'.code.toByte() && payload[tiff + 1] == 'M'.code.toByte() -> false
        else -> return null
    }
    fun u16(at: Int): Int = if (littleEndian) {
        (payload[at].toInt() and 0xff) or ((payload[at + 1].toInt() and 0xff) shl 8)
    } else {
        ((payload[at].toInt() and 0xff) shl 8) or (payload[at + 1].toInt() and 0xff)
    }
    fun u32(at: Int): Long = if (littleEndian) {
        (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
    } else {
        (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    }
    if (u16(tiff + 2) != 0x2a) return null
    val ifd0 = u32(tiff + 4)
    if (ifd0 < 8L || tiff + ifd0 + 2L > payload.size) return null
    val entries = u16(tiff + ifd0.toInt())
    val link = tiff + ifd0.toInt() + 2 + entries * 12
    if (link + 4 > payload.size || u32(link) == 0L) return null
    return payload.copyOf().also { stripped -> for (index in 0 until 4) stripped[link + index] = 0 }
}

private const val MAX_JPEG_SEGMENT_LENGTH = 0xffff

private val EXIF_SIGNATURE =byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
