package com.hletrd.findx9tele.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.storage.MediaStoreWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Records HEVC Main10 (Rec.2020, HLG/Log) video plus optional AAC audio into a MediaStore MP4.
 *
 * The 180° flip is already baked into the frames by [com.hletrd.findx9tele.gl.GlPipeline], which
 * renders into [inputSurface]; therefore NO MediaMuxer orientation hint is set. Video output is
 * drained synchronously on its own thread; audio (if enabled) runs a second capture+encode thread.
 * Both write to one MediaMuxer guarded by [muxerLock]; the muxer starts once all tracks are added.
 */
class VideoRecorder(private val context: Context) {

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    var inputSurface: Surface? = null
        private set

    private var videoTrack = -1
    private var audioTrack = -1
    private var expectedTracks = 1
    private var muxerStarted = false
    private val muxerLock = Any()

    @Volatile private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    // Software input gain applied to recorded PCM (1f = passthrough) and a throttled level-meter
    // callback, both set by [start] and consumed on the audio-encode thread in [runAudio].
    private var audioGain = 1f
    private var onLevel: ((Float) -> Unit)? = null
    private var lastLevelEmitNs = 0L
    // Directional-audio scene (stock Sound Focus / Sound Stage) + the current zoom and device
    // orientation, applied to the audio HAL via AudioManager.setParameters after AudioRecord init.
    private var audioScene = AudioScene.STANDARD
    private var audioZoom = 1f
    private var audioOrientation = 0

    /**
     * Returns the encoder input Surface for the GL pipeline, or null on failure. [encoderRate] is the
     * true (possibly fractional, drop-frame) frame rate; [captureRate] > 0 marks a high-speed clip so
     * the encoder is told it is fed faster than real-time (KEY_CAPTURE_RATE).
     */
    fun start(
        uri: Uri,
        size: Size,
        encoderRate: Double,
        captureRate: Double,
        bitRate: Int,
        transfer: ColorTransfer,
        codec: VideoCodec,
        recordAudio: Boolean,
        audioGain: Float = 1f,
        orientationHint: Int = 0,
        audioScene: AudioScene = AudioScene.STANDARD,
        audioZoom: Float = 1f,
        onLevel: ((Float) -> Unit)? = null,
    ): Surface? {
        this.uri = uri
        this.audioGain = audioGain
        this.audioScene = audioScene
        this.audioZoom = audioZoom
        this.audioOrientation = ((orientationHint % 360) + 360) % 360
        this.onLevel = onLevel
        val descriptor = MediaStoreWriter.openParcelFd(context, uri, "rw") ?: return null
        pfd = descriptor

        val videoOk = runCatching {
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // GL already bakes the afocal 180° into the frames; this hint adds ONLY the physical device
            // orientation (0/90/180/270) captured at record start, so a landscape-held clip plays
            // upright. Must be set before start(). Sign is device-verify (may need (360-deg)%360).
            runCatching { muxer?.setOrientationHint(((orientationHint % 360) + 360) % 360) }

            val vFmt = ColorProfiles.videoFormat(codec, size.width, size.height, encoderRate, captureRate, bitRate, transfer)
            val vCodec = MediaCodec.createEncoderByType(ColorProfiles.mimeFor(codec))
            vCodec.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vCodec.createInputSurface()
            vCodec.start()
            videoCodec = vCodec
        }.isSuccess

        if (!videoOk) {
            // Video encoder/muxer setup failed; clean up whatever got created and bail out.
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
            videoCodec = null
            muxer = null
            pfd = null
            inputSurface = null
            return null
        }

        val doAudio = recordAudio && hasRecordPermission()
        expectedTracks = if (doAudio) 2 else 1
        if (doAudio) {
            runCatching { startAudio() }.onFailure {
                // Audio setup failed after video was already configured; degrade to video-only
                // instead of aborting the whole recording.
                runCatching { audioRecord?.release() }
                runCatching { audioCodec?.stop() }
                runCatching { audioCodec?.release() }
                audioRecord = null
                audioCodec = null
                expectedTracks = 1
            }
        }

        running = true
        videoThread = thread(name = "video-drain") { drainVideo() }
        return inputSurface
    }

    fun stop() {
        running = false
        runCatching { videoCodec?.signalEndOfInputStream() }
        videoThread?.join(3000)
        audioThread?.join(3000)

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        synchronized(muxerLock) {
            if (muxerStarted) runCatching { muxer?.stop() }
        }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { muxer?.release() }
        runCatching { pfd?.close() }

        // Publish only if the muxer actually started (≥1 track added, i.e. real content). Stopping
        // before the encoder emitted its output format would otherwise publish a 0-byte / unplayable
        // MP4 into the gallery — delete it instead.
        uri?.let { if (muxerStarted) MediaStoreWriter.publish(context, it) else MediaStoreWriter.delete(context, it) }

        videoCodec = null
        audioCodec = null
        muxer = null
        pfd = null
        inputSurface = null
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
    }

    private fun drainVideo() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        // The encoder can error asynchronously (a codec/container mismatch — e.g. a format the
        // MediaMuxer rejects — makes dequeueOutputBuffer throw IllegalStateException "Pending
        // dequeue ... cancelled"). This runs on its own thread, so an uncaught throw crashes the
        // app; guard the whole loop and end the recording cleanly instead.
        try {
            drainVideoLoop(codec, info)
        } catch (t: IllegalStateException) {
            Log.w(TAG, "video drain aborted (encoder error): ${t.message}")
        }
    }

    private fun drainVideoLoop(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                // Do not exit on !running here: the encoder may not have emitted its EOS buffer
                // yet, and breaking early would truncate the tail. stop() bounds this loop via
                // videoThread.join(timeout) after signalling EOS, so looping is safe.
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    videoTrack = muxer!!.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null && awaitMuxerStart()) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        synchronized(muxerLock) { runCatching { muxer?.writeSampleData(videoTrack, buf, info) } }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun startAudio() {
        val minBuf = AudioRecord.getMinBufferSize(
            ColorProfiles.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                ColorProfiles.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 2).coerceAtLeast(8192),
            )
        }.getOrNull() ?: run { expectedTracks = 1; return }
        // An AudioRecord that failed to initialize (busy mic, unsupported config) is left in
        // STATE_UNINITIALIZED; calling startRecording() on it throws on the audio thread → crash.
        // Degrade to video-only instead.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            expectedTracks = 1
            return
        }
        audioRecord = record
        applyAudioScene(record)

        val codec = MediaCodec.createEncoderByType(ColorProfiles.MIME_AAC)
        codec.configure(ColorProfiles.aacFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec

        audioThread = thread(name = "audio-encode") { runAudio(record, codec) }
    }

    /**
     * Applies the device's Sound Focus / Sound Stage effect via the vendor audio-HAL parameters
     * (`vendor_audiorecord_effect_type` etc.). Uses [AudioManager.setParameters] (public, forwards
     * to the audio HAL) while our CAMCORDER-source AudioRecord is live — the standard recording
     * source the audio HAL applies the effect to. Best-effort and fully
     * guarded: we log the HAL echo (getParameters) so device acceptance is verifiable, and a
     * rejected param never affects recording. No-op for STANDARD.
     */
    private fun applyAudioScene(record: AudioRecord) {
        if (audioScene == AudioScene.STANDARD) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val support = runCatching { am.getParameters("vendor_audiorecord_track_support") }.getOrNull()
        fun set(kv: String) { runCatching { am.setParameters(kv) } }
        // Session id scopes the param to our record where the HAL honors it; harmless if ignored.
        val sid = record.audioSessionId
        set("vendor_audiorecord_session_id=$sid")
        set("vendor_audiorecord_effect_type=${audioScene.effectType}")
        set("vendor_audiorecord_orientation=$audioOrientation")
        if (audioScene == AudioScene.SOUND_FOCUS) {
            set("vendor_audiorecord_focus_zoom=$audioZoom")
            // Pickup angle narrows as zoom rises: ~60° at 1× down to ~36° by 6× (stock uses 36/60).
            val angle = (60f - (audioZoom.coerceIn(1f, 6f) - 1f) / 5f * 24f)
            set("vendor_audiorecord_focus_angle=$angle")
        }
        val echo = runCatching {
            am.getParameters(
                "vendor_audiorecord_effect_type;vendor_audiorecord_focus_angle;" +
                    "vendor_audiorecord_focus_zoom;vendor_audiorecord_orientation",
            )
        }.getOrNull()
        Log.i(TAG, "audioScene=$audioScene applied (zoom=$audioZoom orient=$audioOrientation) " +
            "trackSupport=[$support] echo=[$echo]")
    }

    private fun runAudio(record: AudioRecord, codec: MediaCodec) {
        // Guard startRecording() too: if the mic is grabbed between init and here it throws, and an
        // uncaught throw on this thread would crash the app — bail to video-only instead.
        if (runCatching { record.startRecording() }.isFailure) return
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L
        val bytesPerFrame = 2 * ColorProfiles.AUDIO_CHANNELS
        var sentEos = false

        while (true) {
            if (!sentEos) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    buf?.clear()
                    val read = if (running && buf != null) record.read(buf, buf.capacity()) else 0
                    if (read > 0 && buf != null) {
                        // Apply gain in place and emit a throttled level update before this PCM
                        // buffer is queued to the AAC encoder below.
                        val level = applyGainAndLevel(buf, read, audioGain)
                        maybeEmitLevel(level)
                    }
                    val ptsUs = 1_000_000L * totalSamples / ColorProfiles.AUDIO_SAMPLE_RATE
                    if (!running) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sentEos = true
                    } else if (read > 0) {
                        codec.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                        totalSamples += read / bytesPerFrame
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    }
                }
            }

            // Non-EOS polling stays non-blocking (0) so the input-buffer loop above keeps
            // feeding the encoder; once EOS was queued, block with a short timeout instead of
            // busy-spinning while waiting for the final EOS-flagged output buffer.
            val outTimeout = if (sentEos) TIMEOUT_US else 0L
            var outIdx = codec.dequeueOutputBuffer(info, outTimeout)
            while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        audioTrack = muxer!!.addTrack(codec.outputFormat)
                        maybeStartMuxer()
                    }
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null && awaitMuxerStart()) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        synchronized(muxerLock) { runCatching { muxer?.writeSampleData(audioTrack, buf, info) } }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                outIdx = codec.dequeueOutputBuffer(info, outTimeout)
            }
            if (sentEos && !running) {
                // keep draining until EOS output seen (handled by return above)
            }
        }
    }

    /**
     * Applies [gain] to every 16-bit PCM sample in `buf[0, byteCount)` IN PLACE (clamped to the
     * Short range so it can't wrap), then returns the post-gain RMS level normalized to 0..1.
     * The short view shares [buf]'s backing memory, so writes here are visible to the caller
     * before the buffer is queued to the encoder.
     */
    private fun applyGainAndLevel(buf: ByteBuffer, byteCount: Int, gain: Float): Float {
        val samples = buf.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(0)
            limit(byteCount)
        }.asShortBuffer()
        val count = samples.remaining()
        if (count == 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val amplified = (samples[i] * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            samples.put(i, amplified)
            sumSquares += amplified.toDouble() * amplified.toDouble()
        }
        return (sqrt(sumSquares / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /** Forwards [level] to [onLevel], throttled to roughly [LEVEL_THROTTLE_NS] between calls. */
    private fun maybeEmitLevel(level: Float) {
        val now = System.nanoTime()
        if (now - lastLevelEmitNs < LEVEL_THROTTLE_NS) return
        lastLevelEmitNs = now
        onLevel?.invoke(level)
    }

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && (expectedTracks == 1 || audioTrack >= 0)) {
            muxer?.start()
            muxerStarted = true
        }
    }

    /**
     * Blocks until the muxer has started, or [running] flips false while it is still waiting.
     * Returns true if the muxer actually started (safe to write samples), false if it gave up
     * because recording was stopped before all expected tracks were added (e.g. audio never
     * emitted a format) — callers must skip the sample write in that case.
     */
    private fun awaitMuxerStart(): Boolean {
        while (running && !muxerStarted) Thread.sleep(2)
        return muxerStarted
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "VideoRecorder"
        const val TIMEOUT_US = 10_000L
        // ~10 Hz cap on onLevel callbacks so the UI meter isn't spammed once per PCM buffer.
        const val LEVEL_THROTTLE_NS = 100_000_000L
    }
}
