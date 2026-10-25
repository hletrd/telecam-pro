package me.hletrd.telecampro.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE PROBE (not a shipping assertion): what can THIS device's AAC encoders record for video, and
 * how late does each one's decoded audio land?
 *
 * For every regular AAC encoder and every AAC object type the recorder might offer (LC, HE-AAC v1,
 * HE-AAC v2, xHE-AAC) it encodes one second of 48 kHz stereo carrying a single impulse at a known
 * sample, at a ladder of bitrates, and reports per cell: configure/start accepted, the achieved
 * bitrate, and the PRIMING delay — where the impulse lands after a decode with the platform AAC
 * decoder, which (like every player reading an MP4 with no encoder-delay edit list) trims nothing.
 * MediaMuxer writes no such edit list, so that delay is exactly how late a recorded soundtrack
 * plays unless the recorder moves its PTS by it.
 *
 * Never fails the build. Results: `adb shell run-as <pkg> cat cache/aac_probe.txt` (one file, not
 * logcat — the ColorOS 300-row quota would eat a per-cell log).
 */
@RunWith(AndroidJUnit4::class)
class AacEncoderProbeTest {

    private companion object {
        const val TAG = "AacProbe"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val RATE = 48_000
        const val CHANNELS = 2
        const val IMPULSE_AT = 9_600 // 200 ms in: clear of any encoder start-up ramp
        const val SECONDS = 2
        val PROFILES = linkedMapOf(
            "LC" to MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            "HE" to MediaCodecInfo.CodecProfileLevel.AACObjectHE,
            "HEv2" to MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS,
            "xHE" to MediaCodecInfo.CodecProfileLevel.AACObjectXHE,
        )
        val BITRATES = mapOf(
            "LC" to intArrayOf(96_000, 128_000, 192_000, 256_000, 320_000, 384_000),
            "HE" to intArrayOf(32_000, 48_000, 64_000, 80_000, 96_000, 128_000),
            "HEv2" to intArrayOf(16_000, 24_000, 32_000, 48_000, 64_000),
            "xHE" to intArrayOf(32_000, 64_000, 96_000, 128_000, 192_000, 256_000, 320_000),
        )
    }

    private val out = StringBuilder()

    private fun line(s: String) {
        out.append(s).append('\n')
    }

    @Test
    fun probe() {
        for (list in listOf("REGULAR" to MediaCodecList.REGULAR_CODECS, "ALL" to MediaCodecList.ALL_CODECS)) {
            for (info in MediaCodecList(list.second).codecInfos) {
                if (!info.isEncoder || info.supportedTypes.none { it.equals(MIME, true) }) continue
                val caps = info.getCapabilitiesForType(MIME)
                val audio = caps.audioCapabilities
                line(
                    "${list.first} ${info.name} hw=${info.isHardwareAccelerated} vendor=${info.isVendor} " +
                        "profiles=${caps.profileLevels.map { it.profile }.distinct()} " +
                        "bitrate=${audio?.bitrateRange} rates=${audio?.supportedSampleRates?.toList()} " +
                        "maxCh=${audio?.maxInputChannelCount}",
                )
            }
        }
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || info.supportedTypes.none { it.equals(MIME, true) }) continue
            if (info.name.startsWith("OMX.")) continue // legacy alias of the same component
            val advertised = info.getCapabilitiesForType(MIME).profileLevels.map { it.profile }.toSet()
            for ((label, profile) in PROFILES) {
                if (profile !in advertised) continue
                for (bitrate in BITRATES.getValue(label)) {
                    val t0 = System.nanoTime()
                    // Each cell on its own thread: a codec call that never returns (seen once on the
                    // PMA110) must cost one cell, not the whole run.
                    val result = java.util.concurrent.atomic.AtomicReference("TIMEOUT (codec call blocked > 15 s)")
                    val worker = Thread {
                        result.set(runCatching { cell(info.name, profile, bitrate) }
                            .getOrElse { "error ${it.javaClass.simpleName}: ${it.message}" })
                    }.apply { isDaemon = true; start() }
                    worker.join(15_000)
                    line("${info.name} $label ${bitrate / 1000}k: ${result.get()} [${(System.nanoTime() - t0) / 1_000_000}ms]")
                    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "aac_probe.txt").writeText(out.toString())
                }
            }
        }
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "aac_probe.txt")
        file.writeText(out.toString())
        Log.i(TAG, "wrote ${file.absolutePath} (${out.lines().size} lines)")
    }

    private fun cell(codecName: String, profile: Int, bitrate: Int): String {
        val format = MediaFormat.createAudioFormat(MIME, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createByCodecName(codecName)
        val packets = ArrayList<Pair<ByteArray, Long>>()
        var csd: ByteBuffer? = null
        var outFormat: MediaFormat? = null
        try {
            try {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
            } catch (t: Exception) {
                return "REFUSED (${t.javaClass.simpleName})"
            }
            val total = RATE * SECONDS
            val pcm = ByteBuffer.allocate(total * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until total) {
                val v: Short = if (i == IMPULSE_AT) 24_000 else 0
                pcm.putShort(v)
                pcm.putShort(v)
            }
            pcm.flip()
            var sent = 0L
            var eos = false
            val bi = MediaCodec.BufferInfo()
            var done = false
            val deadline = System.nanoTime() + 8_000_000_000L
            while (!done && System.nanoTime() < deadline) {
                if (!eos) {
                    val i = enc.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!
                        b.clear()
                        val n = minOf(b.remaining(), pcm.remaining(), 4096 * CHANNELS * 2)
                        if (n == 0) {
                            enc.queueInputBuffer(i, 0, 0, sent * 1_000_000 / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            val slice = pcm.duplicate().apply { limit(position() + n) }
                            b.put(slice)
                            pcm.position(pcm.position() + n)
                            enc.queueInputBuffer(i, 0, n, sent * 1_000_000 / RATE, 0)
                            sent += n / (CHANNELS * 2)
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(bi, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat
                    csd = outFormat.getByteBuffer("csd-0")
                } else if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bi.size > 0) {
                        val a = ByteArray(bi.size)
                        b.position(bi.offset)
                        b.get(a)
                        packets += a to bi.presentationTimeUs
                    }
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            runCatching { enc.stop() }
            enc.release()
        }
        if (packets.isEmpty()) return "ACCEPTED but no output"
        val bytes = packets.sumOf { it.first.size }
        val achieved = bytes * 8L / SECONDS
        val firstPts = packets.first().second
        val delay = decodeImpulse(packets, csd!!)
        val outKeys = outFormat?.let { f ->
            listOf("encoder-delay", "encoder-padding", "profile", "sample-rate", "channel-count")
                .filter { f.containsKey(it) }.joinToString(",") { k -> "$k=${runCatching { f.getInteger(k) }.getOrNull()}" }
        }
        return "OK achieved=${achieved / 1000}k packets=${packets.size} firstPts=$firstPts priming=$delay csd=${csd.toHex()} out[$outKeys]"
    }

    /** Decodes with the platform AAC decoder and returns where the impulse peak lands minus where it was put. */
    private fun decodeImpulse(packets: List<Pair<ByteArray, Long>>, csd: ByteBuffer): String {
        val fmt = MediaFormat.createAudioFormat(MIME, RATE, CHANNELS).apply { setByteBuffer("csd-0", csd.duplicate()) }
        val dec = MediaCodec.createDecoderByType(MIME)
        val samples = ArrayList<Short>()
        var decRate = RATE
        var decCh = CHANNELS
        try {
            dec.configure(fmt, null, null, 0)
            dec.start()
            var next = 0
            var eos = false
            var done = false
            val bi = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 8_000_000_000L
            var lastOutput = System.nanoTime()
            while (!done && System.nanoTime() < deadline &&
                !(eos && System.nanoTime() - lastOutput > 1_000_000_000L)
            ) {
                if (!eos) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        if (next < packets.size) {
                            val b = dec.getInputBuffer(i)!!
                            b.clear()
                            b.put(packets[next].first)
                            dec.queueInputBuffer(i, 0, packets[next].first.size, packets[next].second, 0)
                            next++
                        } else {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(bi, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decRate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    decCh = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    b.position(bi.offset)
                    val sb = b.asShortBuffer()
                    for (k in 0 until bi.size / 2) samples += sb.get(k)
                    lastOutput = System.nanoTime()
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    dec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            runCatching { dec.stop() }
            dec.release()
        }
        if (samples.isEmpty()) return "no-decode"
        var best = 0
        var bestV = 0
        val frames = samples.size / decCh
        for (f in 0 until frames) {
            val v = kotlin.math.abs(samples[f * decCh].toInt())
            if (v > bestV) {
                bestV = v
                best = f
            }
        }
        val scaled = best.toLong() * RATE / decRate
        return "${scaled - IMPULSE_AT}smp(decoded ${frames}fr @${decRate}Hz x$decCh peak=$bestV)"
    }

    private fun ByteBuffer.toHex(): String {
        val d = duplicate()
        return buildString { while (d.hasRemaining()) append("%02x".format(d.get())) }
    }
}
