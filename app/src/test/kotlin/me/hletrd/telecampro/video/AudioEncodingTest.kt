package me.hletrd.telecampro.video

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The soundtrack format inventory and its rules (AudioEncoding.kt): what a device offers is exactly
 * what its regular AAC encoders advertise at 48 kHz, a request normalizes onto that, and each
 * platform encoder's measured priming is the PTS shift the recorder applies.
 */
class AudioEncodingTest {

    private val fdkProfiles = setOf(
        CodecProfileLevel.AACObjectLC, CodecProfileLevel.AACObjectHE, CodecProfileLevel.AACObjectHE_PS,
        CodecProfileLevel.AACObjectLD, CodecProfileLevel.AACObjectELD,
    )

    private fun aac(name: String, profiles: Set<Int>, min: Int, max: Int, has48k: Boolean = true, channels: Int = 2) =
        CodecComponent(
            name = name,
            encoder = true,
            supportedTypes = setOf(MediaFormat.MIMETYPE_AUDIO_AAC),
            hardwareAccelerated = false,
            hevcProfiles = emptySet(),
            aac = AacEncoderCapabilities(profiles, min, max, has48k, channels),
        )

    /** PMA110's regular list (AacEncoderProbeTest, 2026-10-25), a vendor encoder put first. */
    private val pma110 = listOf(
        aac("c2.vendor.aac.encoder", setOf(CodecProfileLevel.AACObjectLC), 8_000, 192_000),
        aac("c2.android.aac.encoder", fdkProfiles, 8_000, 510_000, channels = 6),
        aac("OMX.google.aac.encoder", fdkProfiles, 8_000, 510_000, channels = 6),
        aac("c2.android.xheaac.encoder", setOf(CodecProfileLevel.AACObjectXHE), 12_000, 320_000),
    )

    @Test
    fun `every advertised profile is offered, platform encoders first`() {
        val options = buildAudioEncoderOptions(pma110)
        assertEquals(AudioCodec.entries.toSet(), options.keys)
        assertEquals("c2.android.aac.encoder", options.getValue(AudioCodec.AAC_LC).codecName)
        assertEquals("c2.android.aac.encoder", options.getValue(AudioCodec.HE_AAC).codecName)
        assertEquals("c2.android.aac.encoder", options.getValue(AudioCodec.HE_AAC_V2).codecName)
        assertEquals("c2.android.xheaac.encoder", options.getValue(AudioCodec.XHE_AAC).codecName)
        assertEquals(listOf(128, 192, 256, 320), options.getValue(AudioCodec.AAC_LC).bitratesKbps)
        assertEquals(AudioCodec.entries, CodecInventory(emptyMap(), options).availableAudioCodecs)
    }

    @Test
    fun `a board without an encoder or its profile lists fewer formats`() {
        // Only an LC-only vendor encoder capped at 192 kb/s: one format, two bitrates.
        val vendorOnly = buildAudioEncoderOptions(listOf(pma110.first()))
        assertEquals(setOf(AudioCodec.AAC_LC), vendorOnly.keys)
        assertEquals(listOf(128, 192), vendorOnly.getValue(AudioCodec.AAC_LC).bitratesKbps)
        // No 48 kHz, a mono-only encoder for the stereo-only profile, the OMX alias, a decoder: nothing.
        val unusable = buildAudioEncoderOptions(
            listOf(
                aac("c2.a", fdkProfiles, 8_000, 510_000, has48k = false),
                aac("c2.b", setOf(CodecProfileLevel.AACObjectHE_PS), 8_000, 510_000, channels = 1),
                aac("OMX.google.aac.encoder", fdkProfiles, 8_000, 510_000),
                aac("c2.d", fdkProfiles, 8_000, 510_000).copy(encoder = false),
                aac("c2.e", fdkProfiles, 8_000, 64_000).copy(aac = null),
            ),
        )
        assertTrue(unusable.isEmpty())
        // A range that covers no preset offers nothing for that format.
        assertNull(buildAudioEncoderOptions(listOf(aac("c2.f", fdkProfiles, 8_000, 16_000)))[AudioCodec.AAC_LC])
        assertTrue(buildCodecInventory(emptyList()).availableAudioCodecs.isEmpty())
    }

    @Test
    fun `a request normalizes onto what the device records`() {
        val options = buildAudioEncoderOptions(pma110)
        assertEquals(AudioCodec.XHE_AAC to 96, normalizeAudioEncoding(AudioCodec.XHE_AAC, 96, options))
        // Unoffered bitrate: the nearest preset of the same format.
        assertEquals(AudioCodec.AAC_LC to 256, normalizeAudioEncoding(AudioCodec.AAC_LC, 240, options))
        // Unavailable format: AAC-LC at its default.
        val lcOnly = buildAudioEncoderOptions(listOf(pma110[1]))
            .filterKeys { it == AudioCodec.AAC_LC }
        assertEquals(AudioCodec.AAC_LC to 256, normalizeAudioEncoding(AudioCodec.XHE_AAC, 96, lcOnly))
        // No LC either: whatever exists, at its default when offered, else its first preset.
        val xheOnly = buildAudioEncoderOptions(listOf(pma110[3]))
        assertEquals(AudioCodec.XHE_AAC to 128, normalizeAudioEncoding(AudioCodec.HE_AAC, 64, xheOnly))
        val narrowXhe = buildAudioEncoderOptions(listOf(aac("c2.x", setOf(CodecProfileLevel.AACObjectXHE), 12_000, 96_000)))
        assertEquals(AudioCodec.XHE_AAC to 64, normalizeAudioEncoding(AudioCodec.AAC_LC, 192, narrowXhe))
        // No AAC encoder at all: the request stands (the recorder degrades to video-only).
        assertEquals(AudioCodec.HE_AAC to 48, normalizeAudioEncoding(AudioCodec.HE_AAC, 48, emptyMap()))
    }

    @Test
    fun `HE-AAC v2 needs stereo`() {
        assertEquals(AudioCodec.HE_AAC, effectiveAudioCodec(AudioCodec.HE_AAC_V2, 1))
        assertEquals(AudioCodec.HE_AAC_V2, effectiveAudioCodec(AudioCodec.HE_AAC_V2, 2))
        assertEquals(AudioCodec.AAC_LC, effectiveAudioCodec(AudioCodec.AAC_LC, 1))
    }

    @Test
    fun `priming is the measured delay of the platform encoders, and nothing is guessed`() {
        assertEquals(2048, aacPrimingSamples("c2.android.aac.encoder", AudioCodec.AAC_LC))
        assertEquals(4096, aacPrimingSamples("c2.android.aac.encoder", AudioCodec.HE_AAC))
        assertEquals(6144, aacPrimingSamples("c2.android.aac.encoder", AudioCodec.HE_AAC_V2))
        assertNull(aacPrimingSamples("c2.android.aac.encoder", AudioCodec.XHE_AAC))
        assertEquals(0, aacPrimingSamples("c2.android.xheaac.encoder", AudioCodec.XHE_AAC))
        assertNull(aacPrimingSamples("c2.android.xheaac.encoder", AudioCodec.AAC_LC))
        assertNull(aacPrimingSamples("c2.vendor.aac.encoder", AudioCodec.AAC_LC))
        // 2048 samples at 48 kHz: the 42.7 ms every player decoded ahead of the sound.
        assertEquals(42_666L, audioSamplesToUs(2048))
    }

    @Test
    fun `a take resolves its encoder by name, or the default without an inventory`() {
        val options = buildAudioEncoderOptions(pma110)
        assertEquals(
            RecordingAudioEncoding(AudioCodec.HE_AAC_V2, 32_000, "c2.android.aac.encoder"),
            RecordingAudioEncoding.resolve(AudioCodec.HE_AAC_V2, 32, options),
        )
        assertEquals(RecordingAudioEncoding(), RecordingAudioEncoding.resolve(AudioCodec.XHE_AAC, 96, emptyMap()))
        assertEquals(256_000, RecordingAudioEncoding().bitrate)
    }
}
