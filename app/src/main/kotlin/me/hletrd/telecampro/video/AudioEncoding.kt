package me.hletrd.telecampro.video

import android.media.MediaCodecInfo

/**
 * The soundtrack formats a recording can carry, and how each is chosen on THIS device.
 *
 * Every entry is an AAC object type, because the container decides: the platform MP4 writer
 * (MediaMuxer → libstagefright MPEG4Writer.getFourCCForMime) accepts AAC (`mp4a` + esds), AMR-NB and
 * AMR-WB audio and nothing else. AMR is a narrowband speech codec (8/16 kHz mono) and is not offered;
 * the Opus and FLAC encoders PMA110 ships cannot be muxed into MP4 at all. So the choice that exists
 * is which AAC profile, and which bitrate — and a profile is offered only when a REGULAR encoder on
 * the device advertises it at 48 kHz ([buildAudioEncoderOptions]): nothing is assumed from the
 * platform version, so a board whose image lacks an encoder (or its licence) simply lists fewer
 * entries. MediaCodecList.REGULAR_CODECS already hides components gated behind a required vendor
 * feature (PMA110's `c2.qti.aac.hw.encoder` is `special-codec`, LC-only, ≤ 192 kb/s).
 *
 * [bitratesKbps] are the presets offered when the encoder's advertised range covers them; every one
 * was accepted and hit within ~6 % by PMA110's encoders (AacEncoderProbeTest, 2026-10-25).
 * [stereoOnly]: HE-AAC v2 is parametric stereo and cannot code one channel — a mono input route
 * records HE-AAC v1 instead ([effectiveAudioCodec]).
 */
enum class AudioCodec(
    val aacProfile: Int,
    val stereoOnly: Boolean,
    val bitratesKbps: List<Int>,
    val defaultKbps: Int,
) {
    /** Plays everywhere; the format every editor and camera body reads. */
    AAC_LC(MediaCodecInfo.CodecProfileLevel.AACObjectLC, false, listOf(128, 192, 256, 320), ColorProfiles.AUDIO_BIT_RATE / 1000),
    /** SBR: low bitrates only — above ~64 kb/s stereo LC is the better use of the bits. */
    HE_AAC(MediaCodecInfo.CodecProfileLevel.AACObjectHE, false, listOf(48, 64, 96), 64),
    /** SBR + parametric stereo: the smallest stereo files. */
    HE_AAC_V2(MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS, true, listOf(24, 32, 48), 32),
    /** MPEG-D USAC: efficient at every rate, but not every player or editor decodes it yet. */
    XHE_AAC(MediaCodecInfo.CodecProfileLevel.AACObjectXHE, false, listOf(64, 96, 128, 192), 128),
}

/** One device encoder able to record [codec] at 48 kHz, with its advertised bitrate range. */
data class AudioEncoderOption(
    val codec: AudioCodec,
    val codecName: String,
    val minBitrate: Int,
    val maxBitrate: Int,
    val maxChannels: Int,
) {
    /** The presets of [codec] this encoder's range covers, ascending. */
    val bitratesKbps: List<Int> get() = codec.bitratesKbps.filter { it * 1000 in minBitrate..maxBitrate }
}

/** An encoder component's AAC capabilities as the platform reports them (null fields = query failed). */
internal data class AacEncoderCapabilities(
    val profiles: Set<Int>,
    val minBitrate: Int,
    val maxBitrate: Int,
    val supports48k: Boolean,
    val maxChannels: Int,
)

/**
 * The best encoder per [AudioCodec]: a component must advertise the profile, 48 kHz, and a bitrate
 * range covering at least one preset. Platform `c2.android.*` components rank first — their priming
 * delay is measured ([aacPrimingSamples]), a vendor encoder's is not — then registry order; the
 * legacy `OMX.google.*` alias of the same software encoder adds nothing.
 */
internal fun buildAudioEncoderOptions(components: List<CodecComponent>): Map<AudioCodec, AudioEncoderOption> = buildMap {
    for (codec in AudioCodec.entries) {
        val option = components.asSequence()
            .filter { it.encoder && !it.name.startsWith("OMX.google.") }
            .mapNotNull { component -> component.aac?.let { component to it } }
            .filter { (_, aac) -> codec.aacProfile in aac.profiles && aac.supports48k }
            .map { (component, aac) ->
                AudioEncoderOption(codec, component.name, aac.minBitrate, aac.maxBitrate, aac.maxChannels)
            }
            .filter { it.bitratesKbps.isNotEmpty() && (!codec.stereoOnly || it.maxChannels >= 2) }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<AudioEncoderOption>> { it.value.codecName.startsWith("c2.android.") }.thenBy { it.index })
            .firstOrNull()?.value
        if (option != null) put(codec, option)
    }
}

/**
 * The (codec, kb/s) a request can actually record with [options]: an unavailable codec falls back to
 * AAC-LC (then to whatever exists) at that format's default, and a bitrate the chosen encoder does
 * not offer snaps to the nearest offered preset. With no AAC encoder at all the request is returned unchanged — the recorder
 * then degrades to video-only exactly as for any audio-setup failure.
 */
internal fun normalizeAudioEncoding(
    codec: AudioCodec,
    kbps: Int,
    options: Map<AudioCodec, AudioEncoderOption>,
): Pair<AudioCodec, Int> {
    val chosen = options[codec] ?: options[AudioCodec.AAC_LC] ?: options.values.firstOrNull() ?: return codec to kbps
    val offered = chosen.bitratesKbps
    val rate = when {
        // A bitrate means something different in another format: a fallback takes ITS default.
        chosen.codec != codec -> chosen.codec.defaultKbps.takeIf { it in offered } ?: offered.first()
        kbps in offered -> kbps
        else -> offered.minBy { kotlin.math.abs(it - kbps) }
    }
    return chosen.codec to rate
}

/** HE-AAC v2 needs two channels; a mono route records HE-AAC v1 at the same bitrate. */
internal fun effectiveAudioCodec(codec: AudioCodec, channelCount: Int): AudioCodec =
    if (codec.stereoOnly && channelCount < 2) AudioCodec.HE_AAC else codec

/**
 * Decoded-output delay ("priming") of a platform AAC encoder, in samples at the output rate:
 * where an impulse lands after the platform decoder, which — like every player of an MP4 that has
 * no encoder-delay edit list, and MediaMuxer writes none — trims nothing. Measured on PMA110 with
 * AacEncoderProbeTest (2026-10-25), identical at every bitrate: LC 2048, HE-AAC 4096, HE-AAC v2 6142
 * (≈ 6144, the impulse smearing by two samples), xHE-AAC 0 (the USAC configuration carries its own
 * delay handling). Null for an unmeasured encoder: no compensation is guessed.
 */
internal fun aacPrimingSamples(codecName: String, codec: AudioCodec): Int? = when {
    codecName == "c2.android.aac.encoder" -> when (codec) {
        AudioCodec.AAC_LC -> 2048
        AudioCodec.HE_AAC -> 4096
        AudioCodec.HE_AAC_V2 -> 6144
        AudioCodec.XHE_AAC -> null
    }
    codecName == "c2.android.xheaac.encoder" && codec == AudioCodec.XHE_AAC -> 0
    else -> null
}

/**
 * Everything a take's audio encoder needs, frozen at REC admission. [codecName] null = the platform's
 * default AAC encoder (no inventory yet). The recorder looks the priming up by the name of the codec
 * it actually created ([aacPrimingSamples]), so the default path is compensated too.
 */
data class RecordingAudioEncoding(
    val codec: AudioCodec = AudioCodec.AAC_LC,
    val bitrate: Int = ColorProfiles.AUDIO_BIT_RATE,
    val codecName: String? = null,
) {
    companion object {
        /** The take's encoding for a request, normalized against [options]; the default with none. */
        fun resolve(codec: AudioCodec, kbps: Int, options: Map<AudioCodec, AudioEncoderOption>): RecordingAudioEncoding {
            val (safeCodec, safeKbps) = normalizeAudioEncoding(codec, kbps, options)
            val option = options[safeCodec] ?: return RecordingAudioEncoding()
            return RecordingAudioEncoding(safeCodec, safeKbps * 1000, option.codecName)
        }
    }
}

/** [samples] at the 48 kHz recording rate, in µs. */
internal fun audioSamplesToUs(samples: Int): Long = samples.toLong() * 1_000_000L / ColorProfiles.AUDIO_SAMPLE_RATE

/**
 * How many channels the operator wants recorded (owner request 2026-10-25). A CAP, never a promise:
 * the input device decides what it can deliver ([resolveAudioChannelCount] — a mono Bluetooth
 * headset stays mono under STEREO), and the take records the smaller of the two. The armed level
 * meter opens the same count, so the monitor shows exactly the channels the next take will carry.
 */
enum class AudioChannelMode(val maxChannels: Int) {
    MONO(1),
    STEREO(2),
}

/** The channel count a take (and the armed meter) opens: the device's own count under the cap. */
internal fun cappedAudioChannelCount(deviceChannels: Int, mode: AudioChannelMode): Int =
    deviceChannels.coerceIn(1, mode.maxChannels)
