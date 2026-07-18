package com.hletrd.findx9tele.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureFamilyTest {

    @Test
    fun `versioned siblings round-trip to one exact family key`() {
        val key = CaptureFamilyKey(
            media = CaptureFamilyMedia.STILL,
            capturedAtEpochMillis = 1_752_676_496_123L,
            sequence = 42L,
        )

        val heic = CaptureFamilyKey.parse(key.displayName("HEIC"))
        val jpeg = CaptureFamilyKey.parse(key.displayName(".jpg"))
        val dng = CaptureFamilyKey.parse(key.displayName("dng"))

        assertEquals(key, heic?.familyKey)
        assertEquals(key, jpeg?.familyKey)
        assertEquals(key, dng?.familyKey)
        assertEquals("heic", heic?.extension)
        assertEquals("IMG_TELECAM_F1_1752676496123_0000000042.heic", key.displayName("heic"))
        assertEquals(
            setOf("heic", "heif", "jpg", "jpeg", "dng"),
            key.knownOutputDisplayNames().map { it.substringAfterLast('.') }.toSet(),
        )
    }

    @Test
    fun `video family remains distinct from still family at the same timestamp`() {
        val video = CaptureFamilyKey(CaptureFamilyMedia.VIDEO, 1_752_676_496_123L, 42L)

        assertEquals(video, CaptureFamilyKey.parse(video.displayName("mp4"))?.familyKey)
        assertEquals("VID_TELECAM_F1_1752676496123_0000000042.mp4", video.displayName("mp4"))
    }

    @Test
    fun `legacy and malformed names never become proven families`() {
        assertNull(CaptureFamilyKey.parse("IMG_TELECAM_20260716_123456_789_042.heic"))
        assertNull(CaptureFamilyKey.parse("IMG_TELECAM_F1_1752676496123_0000000042"))
        assertNull(CaptureFamilyKey.parse("IMG_TELECAM_F1_1752676496123_42.heic"))
        assertNull(CaptureFamilyKey.parse(null))
    }

    @Test
    fun `constructor bounds are pinned at their exact edges (TEST4-20)`() {
        // The field-width invariants back the fixed-width filename format; loosening them without
        // widening the format would silently produce unparseable display names.
        val maxTimestamp = 9_999_999_999_999L
        val maxSequence = 9_999_999_999L
        val atMax = CaptureFamilyKey(CaptureFamilyMedia.STILL, maxTimestamp, maxSequence)
        assertEquals(maxTimestamp, atMax.capturedAtEpochMillis)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CaptureFamilyKey(CaptureFamilyMedia.STILL, maxTimestamp + 1, 0L)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CaptureFamilyKey(CaptureFamilyMedia.STILL, 0L, maxSequence + 1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CaptureFamilyKey(CaptureFamilyMedia.STILL, -1L, 0L)
        }
        // displayName rejects a non [a-z0-9] extension instead of writing a malformed name.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            atMax.displayName("He!c")
        }
    }
}
