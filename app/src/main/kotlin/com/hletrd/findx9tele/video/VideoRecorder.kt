package com.hletrd.findx9tele.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.storage.MediaStoreWriter
import kotlin.concurrent.thread

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

    /** Returns the encoder input Surface for the GL pipeline, or null on failure. */
    fun start(uri: Uri, size: Size, fps: Int, bitRate: Int, transfer: ColorTransfer, recordAudio: Boolean): Surface? {
        this.uri = uri
        val descriptor = MediaStoreWriter.openParcelFd(context, uri, "rw") ?: return null
        pfd = descriptor

        val videoOk = runCatching {
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vFmt = ColorProfiles.hevcFormat(size.width, size.height, fps, bitRate, transfer)
            val vCodec = MediaCodec.createEncoderByType(ColorProfiles.MIME_HEVC)
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

        uri?.let { MediaStoreWriter.publish(context, it) }

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
        audioRecord = record

        val codec = MediaCodec.createEncoderByType(ColorProfiles.MIME_AAC)
        codec.configure(ColorProfiles.aacFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec

        audioThread = thread(name = "audio-encode") { runAudio(record, codec) }
    }

    private fun runAudio(record: AudioRecord, codec: MediaCodec) {
        record.startRecording()
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
        const val TIMEOUT_US = 10_000L
    }
}
