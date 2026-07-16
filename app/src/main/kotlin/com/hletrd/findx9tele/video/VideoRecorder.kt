package com.hletrd.findx9tele.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
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
import com.hletrd.findx9tele.camera.AudioInputPreference
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.RotationMath
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.storage.MediaStoreWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Records HEVC Main10 (Rec.2020, HLG/Log) video plus optional AAC audio into a MediaStore MP4.
 *
 * Orientation is a two-part scheme: the afocal 180° flip is already baked into the frame PIXELS by
 * [com.hletrd.findx9tele.gl.GlPipeline] (which renders into [inputSurface]), and the MediaMuxer
 * orientation hint carries ONLY the physical device tilt captured at record start
 * ([RotationMath.videoOrientationHint]) so a landscape-held clip plays upright. Video output is
 * drained synchronously on its own thread; audio (if enabled) runs a second capture+encode thread.
 * Both write to one MediaMuxer guarded by [muxerLock]; the muxer starts once all tracks are added.
 */
class VideoRecorder(private val context: Context) {

    data class StopResult(
        val saved: Boolean,
        val error: Throwable? = null,
    )

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
    // Written under muxerLock (compound check-then-start), but read by awaitMuxerStart()'s spin
    // loop WITHOUT the lock from the other drain thread — @Volatile provides that JMM edge.
    @Volatile private var muxerStarted = false
    private val muxerLock = Any()

    @Volatile private var running = false
    private val firstFailure = FirstFailureSignal()
    private var onFailure: ((Throwable) -> Unit)? = null
    @Volatile private var wroteVideoSample = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    // Software input gain applied to recorded PCM (1f = passthrough) and a throttled level-meter
    // callback, both set by [start] and consumed on the audio-encode thread in [runAudio].
    private var audioGain = 1f
    private var onLevel: ((Float) -> Unit)? = null
    private var lastLevelEmitNs = 0L
    // Directional-audio scene (Sound Focus / Sound Stage) + the current zoom and device
    // orientation, applied to the audio HAL via AudioManager.setParameters after AudioRecord init.
    private var audioScene = AudioScene.STANDARD
    private var audioZoom = 1f
    private var audioOrientation = 0
    private var audioInputPreference = AudioInputPreference.AUTO
    private var audioChannelCount = ColorProfiles.AUDIO_CHANNELS
    private var onRoute: ((String) -> Unit)? = null

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
        audioInputPreference: AudioInputPreference = AudioInputPreference.AUTO,
        onRoute: ((String) -> Unit)? = null,
        onLevel: ((Float) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
    ): Surface? {
        this.uri = uri
        this.audioGain = audioGain
        this.audioScene = audioScene
        this.audioZoom = audioZoom
        this.audioOrientation = RotationMath.videoOrientationHint(orientationHint)
        this.audioInputPreference = audioInputPreference
        this.onRoute = onRoute
        this.onLevel = onLevel
        this.onFailure = onFailure
        val descriptor = MediaStoreWriter.openParcelFd(context, uri, "rw") ?: return null
        pfd = descriptor

        val videoOk = runCatching {
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // GL already bakes the afocal 180° into the frames; this hint adds ONLY the physical device
            // orientation (0/90/180/270) captured at record start, so a landscape-held clip plays
            // upright. Must be set before start(). Sign is device-verify (see RotationMath helper doc).
            runCatching { muxer?.setOrientationHint(RotationMath.videoOrientationHint(orientationHint)) }

            val vFmt = ColorProfiles.videoFormat(codec, size.width, size.height, encoderRate, captureRate, bitRate, transfer)
            val vCodec = MediaCodec.createEncoderByType(ColorProfiles.mimeFor(codec))
            // Assign the field BEFORE configure/createInputSurface/start: any of those can throw, and
            // a codec held only in the local would slip past the failure cleanup below — orphaning a
            // live HW encoder instance until process death.
            videoCodec = vCodec
            vCodec.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vCodec.createInputSurface()
            vCodec.start()
        }.isSuccess

        if (!videoOk) {
            // Video encoder/muxer setup failed; clean up whatever got created and bail out.
            runCatching { inputSurface?.release() }
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

        // AudioRecord's worker reads this flag as soon as startAudio() creates its thread. Publish the
        // running state first so a fast scheduler cannot observe false and enqueue EOS immediately.
        running = true

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

        videoThread = thread(name = "video-drain") { drainVideo() }
        return inputSurface
    }

    fun stop(): StopResult {
        running = false
        runCatching { videoCodec?.signalEndOfInputStream() }

        // AudioRecord.read() may still be blocked after running flips false. Stop the input before
        // joining so the worker can queue AAC EOS instead of timing out while waiting for PCM.
        runCatching { audioRecord?.stop() }
        videoThread?.join(3000)
        audioThread?.join(3000)

        // A drain thread still alive after its join timeout is wedged INSIDE the codec/muxer (e.g. a
        // dequeueOutputBuffer that never returns). Releasing those objects out from under it races
        // native MediaCodec/MediaMuxer state — a JVM-level catch does not stop a native SIGSEGV — so
        // on a wedge we mark the recording failed and deliberately LEAK codec/muxer/fd instead of
        // releasing them (rare error path; the clip is deleted below either way).
        val drainWedged = videoThread?.isAlive == true || audioThread?.isAlive == true
        if (drainWedged) {
            recordFailure(IllegalStateException("Encoder drain timed out"))
        }

        if (!drainWedged) {
            // audioRecord joins the wedge-leak set: a wedged audio thread may be blocked INSIDE
            // record.read() on this exact object (stop() above doesn't always unblock it on this
            // HAL), and release() under a live read races native AudioRecord state the same way
            // codec/muxer release would — so it is only released on the clean path.
            runCatching { audioRecord?.release() }
            synchronized(muxerLock) {
                if (muxerStarted) {
                    runCatching { muxer?.stop() }.onFailure(::recordFailure)
                }
            }
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
        }
        audioRecord = null

        // A track alone is not enough: require at least one successfully muxed video sample and no
        // asynchronous codec/muxer error. Keep failed or empty recordings out of the gallery.
        val outputUri = uri
        var saved = muxerStarted && wroteVideoSample && firstFailure.cause == null && outputUri != null
        if (saved) saved = MediaStoreWriter.publish(context, outputUri!!)
        if (!saved && outputUri != null) MediaStoreWriter.delete(context, outputUri)

        videoCodec = null
        audioCodec = null
        muxer = null
        pfd = null
        inputSurface = null
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
        wroteVideoSample = false
        videoThread = null
        audioThread = null
        onFailure = null
        return StopResult(saved = saved, error = firstFailure.cause)
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
        } catch (t: Exception) {
            if (com.hletrd.findx9tele.BuildConfig.DEBUG) Log.w(TAG, "video drain aborted (encoder error): ${t.message}")
            recordFailure(t)
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
                        synchronized(muxerLock) {
                            runCatching { muxer?.writeSampleData(videoTrack, buf, info) }
                                .onSuccess { wroteVideoSample = true }
                                .onFailure(::recordFailure)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun startAudio() {
        val preferredDevice = AudioInputInspector.preferredDevice(context, audioInputPreference)
        var channelCount = channelCountFor(preferredDevice)
        var channelMask = channelMaskFor(channelCount)
        val minBuf = AudioRecord.getMinBufferSize(
            ColorProfiles.AUDIO_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT,
        ).let { first ->
            if (first > 0 || channelCount == 1) first else {
                channelCount = 1
                channelMask = AudioFormat.CHANNEL_IN_MONO
                AudioRecord.getMinBufferSize(
                    ColorProfiles.AUDIO_SAMPLE_RATE,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            }
        }
        if (minBuf <= 0) {
            expectedTracks = 1
            onRoute?.invoke("${audioInputPreference.label} unavailable")
            return
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(ColorProfiles.AUDIO_SAMPLE_RATE)
            .setChannelMask(channelMask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val record = runCatching {
            @Suppress("MissingPermission")
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(8192))
                .build()
        }.getOrNull() ?: run {
            expectedTracks = 1
            onRoute?.invoke("${audioInputPreference.label} unavailable")
            return
        }
        // An AudioRecord that failed to initialize (busy mic, unsupported config) is left in
        // STATE_UNINITIALIZED; calling startRecording() on it throws on the audio thread → crash.
        // Degrade to video-only instead.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            expectedTracks = 1
            onRoute?.invoke("${audioInputPreference.label} unavailable")
            return
        }
        if (preferredDevice != null && !record.setPreferredDevice(preferredDevice)) {
            if (com.hletrd.findx9tele.BuildConfig.DEBUG) Log.w(TAG, "preferred audio input rejected: ${AudioInputInspector.routeLabel(audioInputPreference, preferredDevice)}")
        }
        audioChannelCount = channelCount
        audioRecord = record
        applyAudioScene(record)

        val codec = MediaCodec.createEncoderByType(ColorProfiles.MIME_AAC)
        // Field assignment BEFORE configure/start: if either throws, the caller's failure cleanup
        // releases audioCodec — a local-only codec would leak the HW encoder instance.
        audioCodec = codec
        codec.configure(ColorProfiles.aacFormat(audioChannelCount), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        audioThread = thread(name = "audio-encode") {
            try {
                runAudio(record, codec)
            } catch (t: Exception) {
                if (com.hletrd.findx9tele.BuildConfig.DEBUG) Log.w(TAG, "audio encode aborted: ${t.message}")
                recordFailure(t)
            }
        }
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
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            Log.i(TAG, "audioScene=$audioScene applied (zoom=$audioZoom orient=$audioOrientation) " +
                "trackSupport=[$support] echo=[$echo]")
        }
    }

    private fun runAudio(record: AudioRecord, codec: MediaCodec) {
        // Guard startRecording() too: if the mic is grabbed between init and here it throws, and an
        // uncaught throw on this thread would crash the app — bail to video-only instead.
        if (runCatching { record.startRecording() }.isFailure) {
            onRoute?.invoke("${audioInputPreference.label} unavailable")
            synchronized(muxerLock) {
                expectedTracks = 1
                maybeStartMuxer()
            }
            return
        }
        onRoute?.invoke(AudioInputInspector.routeLabel(audioInputPreference, record.routedDevice ?: record.preferredDevice))
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L
        val bytesPerFrame = 2 * audioChannelCount
        var sentEos = false
        var eosAttempts = 0

        while (true) {
            if (!sentEos) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    buf?.clear()
                    val read = if (running && buf != null) record.read(buf, buf.capacity()) else 0
                    if (read > 0 && buf != null) {
                        // Apply gain in place and emit a throttled level update before this PCM
                        // buffer is queued to the AAC encoder below. At unity gain the rewrite is a
                        // no-op, so the RMS pass is skipped entirely unless a level emit is due —
                        // ~90% of buffers otherwise computed an RMS that maybeEmitLevel discarded.
                        val emitDue = levelEmitDue()
                        if (audioGain != 1f || emitDue) {
                            val level = applyGainAndLevel(buf, read, audioGain)
                            if (emitDue) maybeEmitLevel(level)
                        }
                    }
                    val ptsUs = audioPtsUs(totalSamples, ColorProfiles.AUDIO_SAMPLE_RATE)
                    if (!running) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sentEos = true
                    } else if (read > 0) {
                        codec.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                        totalSamples += read / bytesPerFrame
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    }
                } else if (!running && ++eosAttempts >= MAX_EOS_ATTEMPTS) {
                    // Stop was requested but the encoder has produced no free input buffer to carry
                    // EOS for ~3 s — it is effectively wedged. Bail instead of looping until stop()'s
                    // join gives up; stop() finalizes/fails the clip based on what was actually muxed.
                    return
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
                        synchronized(muxerLock) {
                            runCatching { muxer?.writeSampleData(audioTrack, buf, info) }
                                .onFailure(::recordFailure)
                        }
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

    /** True when enough time has passed since the last [onLevel] emit for a new one to go out. */
    private fun levelEmitDue(): Boolean = System.nanoTime() - lastLevelEmitNs >= LEVEL_THROTTLE_NS

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

    private fun recordFailure(t: Throwable) {
        running = false
        // Codec/muxer failures happen on the drain threads. Notify the owner immediately so it can
        // leave REC state and start ordered teardown instead of waiting for a manual Stop tap. The
        // atomic signal retains the first cause and invokes this callback at most once even when the
        // video and audio threads fail together (or stop() observes a second finalization error).
        firstFailure.record(t) { cause -> onFailure?.invoke(cause) }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun channelCountFor(device: AudioDeviceInfo?): Int =
        resolveAudioChannelCount(
            device?.channelCounts,
            device != null && AudioInputInspector.isBluetoothInput(device.type),
        )

    private fun channelMaskFor(channelCount: Int): Int =
        if (channelCount >= ColorProfiles.AUDIO_CHANNELS) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    private companion object {
        const val TAG = "VideoRecorder"
        const val TIMEOUT_US = 10_000L
        // ~10 Hz cap on onLevel callbacks so the UI meter isn't spammed once per PCM buffer.
        const val LEVEL_THROTTLE_NS = 100_000_000L
        // ~3 s of 10 ms input-dequeue timeouts: how long the stop path keeps asking for a free
        // input buffer to carry AAC EOS before declaring the encoder wedged and bailing.
        const val MAX_EOS_ATTEMPTS = 300
    }
}

/**
 * Thread-safe first-failure latch used by [VideoRecorder]'s independent audio/video drain threads.
 * The first cause wins and its observer is invoked exactly once; observer exceptions are contained
 * so a UI/engine callback can never crash the encoder thread that reported the real failure.
 */
internal class FirstFailureSignal {
    private val causeRef = AtomicReference<Throwable?>()

    val cause: Throwable?
        get() = causeRef.get()

    fun record(cause: Throwable, onFirst: (Throwable) -> Unit): Boolean {
        if (!causeRef.compareAndSet(null, cause)) return false
        runCatching { onFirst(cause) }
        return true
    }
}

/**
 * Audio presentation timestamp for a sample count at [sampleRate]. Pure integer math, top-level so
 * it is unit-testable: 1e6 * samples / rate stays far below Long overflow even for multi-hour takes.
 */
internal fun audioPtsUs(totalSamples: Long, sampleRate: Int): Long =
    1_000_000L * totalSamples / sampleRate

/**
 * Resolves the AAC channel count for a capture device: stereo when the device advertises >=2
 * channels, stereo when it advertises nothing and is NOT Bluetooth (built-in mics report empty
 * caps), mono otherwise (a BT headset mic with empty caps is assumed mono — asking it for stereo
 * mismatches the channel count baked into the AAC MediaFormat). Null counts = no device selected →
 * default stereo. Top-level (plain IntArray/Boolean) so it is unit-testable.
 */
internal fun resolveAudioChannelCount(channelCounts: IntArray?, isBluetooth: Boolean): Int {
    if (channelCounts == null) return ColorProfiles.AUDIO_CHANNELS
    if (channelCounts.any { it >= ColorProfiles.AUDIO_CHANNELS }) return ColorProfiles.AUDIO_CHANNELS
    if (channelCounts.isEmpty() && !isBluetooth) return ColorProfiles.AUDIO_CHANNELS
    return 1
}

/**
 * Applies [gain] to every 16-bit PCM sample in `buf[0, byteCount)` IN PLACE (clamped to the
 * Short range so it can't wrap), then returns the post-gain RMS level normalized to 0..1.
 * The short view shares [buf]'s backing memory, so writes here are visible to the caller
 * before the buffer is queued to the encoder. Top-level (pure java.nio) so it is unit-testable.
 */
internal fun applyGainAndLevel(buf: ByteBuffer, byteCount: Int, gain: Float): Float {
    val samples = buf.duplicate().apply {
        order(ByteOrder.LITTLE_ENDIAN)
        position(0)
        limit(byteCount)
    }.asShortBuffer()
    val count = samples.remaining()
    if (count == 0) return 0f
    var sumSquares = 0.0
    if (gain == 1f) {
        // Unity gain (the default): the rewrite loop is a no-op transform — skip the per-sample
        // put() and only accumulate the RMS the level meter needs.
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sumSquares += v * v
        }
    } else {
        for (i in 0 until count) {
            val amplified = (samples[i] * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            samples.put(i, amplified)
            sumSquares += amplified.toDouble() * amplified.toDouble()
        }
    }
    return (sqrt(sumSquares / count) / 32768.0).toFloat().coerceIn(0f, 1f)
}
