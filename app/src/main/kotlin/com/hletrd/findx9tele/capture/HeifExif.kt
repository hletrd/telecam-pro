package com.hletrd.findx9tele.capture

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
        if (marker == 0x00 || marker == 0xff || marker in 0xd0..0xd7) {
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

private val EXIF_SIGNATURE = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
