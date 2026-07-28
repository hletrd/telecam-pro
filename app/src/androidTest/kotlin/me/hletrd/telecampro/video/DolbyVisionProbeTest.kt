package me.hletrd.telecampro.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE PROBE (not a shipping assertion): can a THIRD-PARTY app actually record Dolby Vision on
 * this device?
 *
 * This exists because the answer is not inferable from the codec dump. `c2.qti.dv.encoder` is
 * present and hardware-accelerated, and — unlike APV — AOSP's MPEG4Writer genuinely special-cases
 * `video/dolby-vision` (`mHasDolbyVision`, `getDoviFourCC()`), so the APV "muxer refuses it" verdict
 * does NOT transfer. The three things that decide feasibility can only be answered on hardware:
 *
 *  1. Is the vendor encoder reachable/configurable from a non-privileged package at all?
 *  2. Does it emit codec-specific data and real samples in ByteBuffer mode?
 *  3. Does MediaMuxer accept the resulting track and close a valid MP4?
 *
 * The test NEVER fails the build: it logs a verdict under `DVProbe` and passes regardless, so a
 * device that simply lacks the codec does not break CI. Read the verdict with
 * `adb logcat -s DVProbe`.
 */
@RunWith(AndroidJUnit4::class)
class DolbyVisionProbeTest {

    private companion object {
        const val TAG = "DVProbe"
        const val MIME = "video/dolby-vision"
        const val W = 1920
        const val H = 1080
        const val FRAMES = 12
        const val TIMEOUT_US = 2_000_000L
    }

    @Test
    fun probeDolbyVisionEncodeAndMux() {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }
        Log.i(TAG, "step1 visibleEncoders=${encoders.map { it.name }}")
        if (encoders.isEmpty()) {
            Log.i(TAG, "VERDICT: no Dolby Vision encoder visible to a third-party app")
            return
        }

        val info = encoders.first()
        val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull()
        Log.i(
            TAG,
            "step2 name=${info.name} hw=${runCatching { info.isHardwareAccelerated }.getOrNull()} " +
                "profiles=${caps?.profileLevels?.joinToString { "${it.profile}/${it.level}" }} " +
                "colorFormats=${caps?.colorFormats?.joinToString { "0x%x".format(it) }}",
        )

        // Profile 8.4 (DvheSt) is the only one this encoder advertises; pair it with a 10-bit
        // ByteBuffer colour format so no EGL/Surface plumbing is needed inside the probe.
        val profile = caps?.profileLevels?.firstOrNull()?.profile
            ?: MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt
        val level = caps?.profileLevels?.firstOrNull()?.level ?: 0
        val colorFormat = caps?.colorFormats?.firstOrNull { it == 0x36 }
            ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

        val format = MediaFormat.createVideoFormat(MIME, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, profile)
            if (level != 0) setInteger(MediaFormat.KEY_LEVEL, level)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

        val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrElse {
            Log.i(TAG, "VERDICT: createByCodecName FAILED: $it")
            return
        }
        runCatching {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }.onFailure {
            Log.i(TAG, "VERDICT: configure/start FAILED: $it")
            runCatching { codec.release() }
            return
        }
        Log.i(TAG, "step3 configured+started OK colorFormat=0x%x profile=$profile".format(colorFormat))

        // Written to the app's EXTERNAL files dir (not cacheDir) and deliberately RETAINED, so the
        // container can be pulled off the device and inspected: "the muxer accepted the track" and
        // "the file is a valid Dolby Vision MP4 carrying a dvcC/dvvC configuration box" are
        // different claims, and only the second one decides feasibility.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "dv-probe.mp4")
        out.delete()
        var muxer: MediaMuxer? = null
        var track = -1
        var samples = 0
        var muxerStarted = false
        var verdict = "inconclusive"

        try {
            val bufInfo = MediaCodec.BufferInfo()
            var fed = 0
            var sawEos = false
            var guard = 0
            while (!sawEos && guard++ < 400) {
                if (fed < FRAMES) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        // P010 is 3 bytes/px (16-bit Y plane + 16-bit interleaved half-res CbCr).
                        val need = minOf(buf.capacity(), W * H * 3)
                        val grey = ByteArray(need) { if (it % 2 == 0) 0 else 0x40 }
                        buf.put(grey, 0, need)
                        val ptsUs = fed * 33_333L
                        codec.queueInputBuffer(inIdx, 0, need, ptsUs, 0)
                        fed++
                    }
                } else {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(
                            inIdx, 0, 0, FRAMES * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        fed = Int.MAX_VALUE
                    }
                }

                when (val outIdx = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        Log.i(TAG, "step4 encoder outputFormat=$outFormat")
                        val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        muxer = mx
                        track = runCatching { mx.addTrack(outFormat) }.getOrElse {
                            verdict = "MediaMuxer.addTrack REJECTED video/dolby-vision: $it"
                            Log.i(TAG, "step5 $verdict")
                            break
                        }
                        mx.start()
                        muxerStarted = true
                        Log.i(TAG, "step5 muxer accepted the DV track (index=$track)")
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIdx >= 0) {
                        val encoded = codec.getOutputBuffer(outIdx)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            bufInfo.size > 0 && encoded != null && muxerStarted
                        ) {
                            runCatching {
                                muxer?.writeSampleData(track, encoded, bufInfo)
                                samples++
                            }.onFailure {
                                verdict = "writeSampleData FAILED after $samples samples: $it"
                                Log.i(TAG, "step6 $verdict")
                            }
                        }
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
            if (verdict == "inconclusive") {
                verdict = if (samples > 0) "ENCODE+MUX WORKED ($samples samples)" else "no samples produced"
            }
        } catch (t: Throwable) {
            verdict = "threw mid-drain: $t"
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }

        val size = if (out.exists()) out.length() else -1L
        Log.i(TAG, "VERDICT: $verdict | samples=$samples fileBytes=$size path=${out.absolutePath}")
    }
}
