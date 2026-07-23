package com.hletrd.findx9tele.capture

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

private val EXIF_SIGNATURE = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
