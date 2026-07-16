package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoFormatsTest {

    @Test
    fun `full session retains every requested available output`() {
        val requested = PhotoFormats(heif = true, jpeg = true, dngRaw = true)

        assertEquals(
            requested,
            requested.normalizedFor(PhotoSessionOutputs(processed = true, raw = true)),
        )
    }

    @Test
    fun `processed-only session drops raw but retains requested jpeg`() {
        assertEquals(
            PhotoFormats(heif = false, jpeg = true, dngRaw = false),
            PhotoFormats(heif = false, jpeg = true, dngRaw = true)
                .normalizedFor(PhotoSessionOutputs(processed = true)),
        )
    }

    @Test
    fun `processed-only session converts raw-only request to heif`() {
        assertEquals(
            PhotoFormats(heif = true, jpeg = false, dngRaw = false),
            PhotoFormats(heif = false, jpeg = false, dngRaw = true)
                .normalizedFor(PhotoSessionOutputs(processed = true)),
        )
    }

    @Test
    fun `raw-only session converts processed-only request to dng`() {
        assertEquals(
            PhotoFormats(heif = false, jpeg = false, dngRaw = true),
            PhotoFormats(heif = true, jpeg = true, dngRaw = false)
                .normalizedFor(PhotoSessionOutputs(raw = true)),
        )
    }

    @Test
    fun `preview-only session returns no effective output`() {
        assertEquals(
            PhotoFormats(heif = false, jpeg = false, dngRaw = false),
            PhotoFormats(heif = true, jpeg = true, dngRaw = true)
                .normalizedFor(PhotoSessionOutputs()),
        )
    }

    @Test
    fun `empty request deterministically prefers heif when both outputs exist`() {
        assertEquals(
            PhotoFormats(heif = true, jpeg = false, dngRaw = false),
            PhotoFormats(heif = false, jpeg = false, dngRaw = false)
                .normalizedFor(PhotoSessionOutputs(processed = true, raw = true)),
        )
    }

    @Test
    fun `pre-session persisted request becomes non-empty without assuming raw availability`() {
        assertEquals(
            PhotoFormats(heif = true),
            PhotoFormats(heif = false, jpeg = false, dngRaw = false).withDefaultIfEmpty(),
        )
        assertEquals(
            PhotoFormats(heif = false, jpeg = false, dngRaw = true),
            PhotoFormats(heif = false, jpeg = false, dngRaw = true).withDefaultIfEmpty(),
        )
    }
}
