package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFormatsTest {

    @Test
    fun normalizedFor_replacesEmptySelectionWithHeif() {
        val normalized = PhotoFormats(heif = false, jpeg = false, dngRaw = false)
            .normalizedFor(rawAvailable = false)

        assertEquals(PhotoFormats(heif = true, jpeg = false, dngRaw = false), normalized)
    }

    @Test
    fun normalizedFor_removesRawWhenSessionCannotProvideIt() {
        val normalized = PhotoFormats(heif = false, jpeg = true, dngRaw = true)
            .normalizedFor(rawAvailable = false)

        assertTrue(normalized.jpeg)
        assertFalse(normalized.dngRaw)
    }

    @Test
    fun normalizedFor_allowsRawOnlyCaptureWhenAvailable() {
        val normalized = PhotoFormats(heif = false, jpeg = false, dngRaw = true)
            .normalizedFor(rawAvailable = true)

        assertEquals(PhotoFormats(heif = false, jpeg = false, dngRaw = true), normalized)
    }
}
