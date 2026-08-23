package me.hletrd.telecampro.video

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
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.RotationMath
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.normalizeAudioGain
import me.hletrd.telecampro.storage.CompletedOutputPublication
import me.hletrd.telecampro.storage.MediaStoreWriter
import me.hletrd.telecampro.storage.publishCompletedOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Records HEVC Main10 (Rec.2020, HLG/Log) video plus optional AAC audio into a MediaStore MP4.
 *
 * Orientation is a two-part scheme: the afocal 180° flip is already baked into the frame PIXELS by
 * [me.hletrd.telecampro.gl.GlPipeline] (which renders into [inputSurface]), and the MediaMuxer
 * orientation hint carries ONLY the physical device tilt captured at record start
 * ([RotationMath.videoOrientationHint]) so a landscape-held clip plays upright. Video output is
 * drained synchronously on its own thread; audio (if enabled) runs a second capture+encode thread.
 * Both write to one MediaMuxer guarded by [muxerLock]; the muxer starts once all tracks are added.
 */
class VideoRecorder(private val context: Context) {

    enum class StorageDisposition {
        NOT_APPLICABLE,
        PUBLISHED,
        RETAINED_MARKER_UNAVAILABLE,
        RETAINED_PUBLICATION_UNAVAILABLE,
    }

    data class StopResult(
        val saved: Boolean,
        val error: Throwable? = null,
        val nativeGraphDisposition: NativeGraphDisposition = NativeGraphDisposition.RELEASED,
        /** Distinguishes recoverable private bytes from both a saved publication and data loss. */
        val storageDisposition: StorageDisposition = if (saved) {
            StorageDisposition.PUBLISHED
        } else {
            StorageDisposition.NOT_APPLICABLE
        },
    )

    internal data class NativeStopResult(
        val error: Throwable? = null,
        val nativeGraphDisposition: NativeGraphDisposition = NativeGraphDisposition.RELEASED,
        val storageTail: RecordingStorageTail? = null,
    )

    /** Process-wide REC lease, released only after strict finalization; assigned before native setup. */
    internal var processAdmissionToken: UnsafeRecorderAdmissionToken? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    /**
     * The buffer size the encoder ACTUALLY accepted, which may be a smaller same-aspect rung than
     * the caller asked for (see [encoderSizeLadder]). The engine must size the GL encoder viewport
     * from this, not from its request, or it would draw at the wrong scale on such a device. Null
     * until a successful configure.
     */
    @Volatile var configuredSize: Size? = null
        private set
    @Volatile var configuredEncoderSelection: EncoderSelection? = null
        private set
    @Volatile var configuredBitRate: Int? = null
        private set

    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    private val inputSurfaceOwner = ExactlyOnceResourceOwner<Surface>()
    /** Linearizes every recorder-owned native create/start/stop/release against quarantine. */
    private val nativeOperations = RecorderNativeOperationGate()
    /** First admitted cleanup call that threw; any such graph must be retained process-long. */
    private val nativeCleanupFailure = AtomicReference<Throwable?>()
    /** Strong owners for attempts that quarantine may freeze before publication or cleanup. */
    private val provisionalVideoOwners = Collections.synchronizedList(
        mutableListOf<MediaCodecAttemptOwner>(),
    )

    val inputSurface: Surface?
        get() = inputSurfaceOwner.get()

    private var videoTrack = -1
    private var audioTrack = -1
    private var expectedTracks = 1
    // A terminal attempt is retained as well as success so neither drain worker can retry a refused
    // or throwing MediaMuxer.start(). The Condition replaces the historical 2 ms hot poll.
    @Volatile private var muxerStartState = MuxerStartState.WAITING
    private val muxerStarted: Boolean
        get() = muxerStartState == MuxerStartState.STARTED
    private val muxerLock = ReentrantLock()
    private val muxerStateChanged = muxerLock.newCondition()
    private var audioTrackDeadlineNs = Long.MAX_VALUE

    @Volatile private var running = false
    // An encoder whose EGL input ownership could not be proven released is never stopped/released
    // in-process. Quarantine still needs to end Java-side work and microphone capture promptly;
    // this flag lets both drain loops leave without touching the unsafe native graph.
    private val terminallyQuarantined = AtomicBoolean(false)
    private val firstFailure = FirstFailureSignal()
    private val videoStartupProof = VideoStartupProof()
    private val videoStartupDeadlineExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "video-start-proof").apply { isDaemon = true }
    }
    private var videoStartupDeadline: ScheduledFuture<*>? = null
    @Volatile private var videoStartupDeadlineNs = Long.MAX_VALUE
    private var onFailure: ((Throwable) -> Unit)? = null
    @Volatile private var wroteVideoSample = false
    // Set the first time an AUDIO sample is muxed, and when a mid-REC audio fault degrades the
    // recording to video-only. Together they identify the one muxer.stop() failure that must NOT
    // delete the clip: a 2-track muxer whose audio track never received a sample because the mic
    // died right after addTrack (TR4-2) — MediaMuxer.stop() can throw over the empty track while
    // the video track is complete and playable.
    @Volatile private var wroteAudioSample = false
    @Volatile private var audioDegradedMidRec = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    // Software input gain applied to recorded PCM (1f = passthrough) and a throttled level-meter
    // callback, both set by [start] and consumed on the audio-encode thread in [runAudio].
    private var audioGain = 1f
    private var onLevel: ((FloatArray) -> Unit)? = null
    private var lastLevelEmitNs = 0L
    // Directional-audio scene (Sound Focus / Sound Stage) + the current zoom and device
    // orientation, applied to the audio HAL via AudioManager.setParameters after AudioRecord init.
    private var audioScene = AudioScene.STANDARD
    private var audioZoom = 1f
    private var audioOrientation = 0
    private var audioInputPreference = AudioInputPreference.AUTO
    private var audioChannelCount = ColorProfiles.AUDIO_CHANNELS
    private var onRoute: ((AudioRouteStatus) -> Unit)? = null

    private fun <T> nativeOperation(block: () -> T): T = when (
        val outcome = nativeOperations.run(block)
    ) {
        RecorderNativeOperationResult.Rejected -> throw RecorderNativeOperationRevokedException()
        is RecorderNativeOperationResult.Returned -> {
            if (!outcome.stillOpen) throw RecorderNativeOperationRevokedException()
            outcome.result.getOrThrow()
        }
    }

    /** True only when the cleanup call entered, succeeded, and returned before quarantine closed. */
    private fun nativeCleanup(block: () -> Unit): Boolean {
        // Once one required release is unproved, no later cleanup phase may begin. Quarantine needs
        // the complete graph exactly as it stood at the first failure, not a half-mutated remainder.
        if (nativeCleanupFailure.get() != null) return false
        return when (val outcome = nativeCleanupOutcome(nativeOperations.run(block))) {
            NativeCleanupOutcome.Completed -> true
            NativeCleanupOutcome.Revoked -> false
            is NativeCleanupOutcome.Failed -> {
                nativeCleanupFailure.compareAndSet(null, outcome.cause)
                false
            }
        }
    }

    /** Non-null means [start] returned no Surface while retaining an unproved native owner graph. */
    internal fun unsafeStartupFailure(): Throwable? = nativeCleanupFailure.get()

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
        bitRateForSize: (width: Int, height: Int) -> Int,
        transfer: ColorTransfer,
        encoderCandidates: List<EncoderSelection>,
        recordAudio: Boolean,
        audioGain: Float = 1f,
        orientationHint: Int = 0,
        frontFacing: Boolean = false,
        cameraRoute: CameraRoute = if (frontFacing) CameraRoute.FRONT else CameraRoute.BACK,
        audioScene: AudioScene = AudioScene.STANDARD,
        audioZoom: Float = 1f,
        audioInputPreference: AudioInputPreference = AudioInputPreference.AUTO,
        onRoute: ((AudioRouteStatus) -> Unit)? = null,
        onLevel: ((FloatArray) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
    ): Surface? {
        val admittedCandidates = encoderCandidates
            .filter { encoderSelectionAdmitsTransfer(it, transfer) }
            .distinctBy { it.codecName }
        val codec = admittedCandidates.firstOrNull()?.codec ?: return null
        if (admittedCandidates.any { it.codec != codec }) return null
        this.uri = uri
        this.audioGain = normalizeAudioGain(audioGain)
        this.audioScene = audioScene
        this.audioZoom = audioZoom
        // DIFFERENT DOMAIN from the muxer hint below, despite the shared input: the vendor audio HAL
        // key `vendor_audiorecord_orientation` describes how the PHONE is physically held (it aims
        // the Sound Focus beam / Sound Stage field), while [RotationMath.videoOrientationHint] is a
        // CONTAINER rotation. They were briefly the same function only because the hint used to be
        // the identity; a209830 made it −dev on rear routes (the device-verified still-rotation fix)
        // and silently started feeding the audio HAL the MIRRORED landscape (270 for a phone at 90).
        // Keep the two apart: the audio key takes the raw gravity device orientation.
        this.audioOrientation = RotationMath.normalize(orientationHint)
        this.audioInputPreference = audioInputPreference
        this.onRoute = onRoute
        this.onLevel = onLevel
        this.onFailure = onFailure
        val descriptor = nativeOperation {
            MediaStoreWriter.openParcelFd(context, uri, "rw")?.also { pfd = it }
        } ?: return null

        val videoOk = runCatching {
            nativeOperation {
                MediaMuxer(
                    descriptor.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                ).also { muxer = it }
            }
            // GL already bakes the afocal 180° into the frames; this hint adds ONLY the physical device
            // orientation (0/90/180/270) captured at record start, so a landscape-held clip plays
            // upright. Must be set before start(). Sign is device-verify (see RotationMath helper doc).
            nativeOperation {
                muxer?.setOrientationHint(
                    RotationMath.videoOrientationHint(orientationHint, cameraRoute),
                )
            }

            // Walk the same-aspect ladder: some encoders refuse the PORTRAIT buffer the cycle-4
            // framing contract asks for while accepting the identical pixel count in landscape (a
            // height cap, device-probed — see [encoderSizeLadder]). Their advertised capabilities
            // are wrong in both directions there, so an actual configure is the only honest oracle.
            // Rung 0 IS the requested size, so every verified handset takes this path unchanged.
            val accepted = firstConfiguredEncoderAttempt(
                attempts = encoderConfigureAttempts(
                    admittedCandidates,
                    size.width,
                    size.height,
                ),
                acquire = { selection ->
                    nativeOperation {
                        MediaCodecAttemptOwner(
                            MediaCodec.createByCodecName(selection.codecName),
                        ).also(provisionalVideoOwners::add)
                    }
                },
                configure = { owner, attempt ->
                    val attemptBitRate = bitRateForSize(attempt.width, attempt.height)
                    val vFmt = ColorProfiles.videoFormat(
                        codec, attempt.width, attempt.height,
                        encoderRate, captureRate, attemptBitRate, transfer,
                    )
                    nativeOperation {
                        owner.codec.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }
                    owner.surface = nativeOperation { owner.codec.createInputSurface() }
                    nativeOperation { owner.codec.start() }
                },
                releaseRejected = { owner ->
                    val surfaceReleased = nativeCleanup { owner.surface?.release() }
                    val codecStopped = surfaceReleased && nativeCleanup { owner.codec.stop() }
                    val codecReleased = codecStopped && nativeCleanup { owner.codec.release() }
                    if (codecReleased) provisionalVideoOwners.remove(owner)
                    codecReleased
                },
            )
            videoCodec = accepted.owner.codec
            configuredSize = Size(accepted.attempt.width, accepted.attempt.height)
            configuredEncoderSelection = accepted.attempt.selection
            configuredBitRate = bitRateForSize(accepted.attempt.width, accepted.attempt.height)
            inputSurfaceOwner.install(checkNotNull(accepted.owner.surface))
            provisionalVideoOwners.remove(accepted.owner)
        }.isSuccess

        // Setup timeout/quarantine may win while a vendor configure/start call is blocked. Once
        // that call returns, retain every partial owner untouched; the late setup result is revoked
        // by the process lease and must not run the ordinary failure cleanup below.
        if (terminallyQuarantined.get()) return null

        if (!videoOk) {
            // Video encoder/muxer setup failed before the Surface could leave this recorder. Release
            // its exactly-once owner first, then tear down the codec that created the native window.
            val surfaceReleased = inputSurfaceOwner.releaseConditionally { surface ->
                nativeCleanup { surface.release() }
            }
            if (inputSurfaceOwner.get() != null && !surfaceReleased) return null
            if (!nativeCleanup { videoCodec?.stop() }) return null
            if (!nativeCleanup { videoCodec?.release() }) return null
            if (!nativeCleanup { muxer?.release() }) return null
            if (!nativeCleanup { pfd?.close() }) return null
            videoCodec = null
            muxer = null
            pfd = null
            videoStartupDeadlineExecutor.shutdownNow()
            return null
        }

        // AudioRecord's worker reads this flag as soon as startAudio() creates its thread. Publish the
        // running state first so a fast scheduler cannot observe false and enqueue EOS immediately.
        running = true
        // One absolute video-start budget begins before either drain worker. An audio worker that
        // wins the scheduler race therefore cannot invent its own shorter missing-video deadline.
        armVideoStartupDeadline()

        val doAudio = recordAudio && hasRecordPermission()
        expectedTracks = if (doAudio) 2 else 1
        if (doAudio) {
            val audioStart = runCatching { startAudio() }
            if (terminallyQuarantined.get()) return null
            audioStart.onFailure {
                // Audio setup failed after video was already configured; degrade to video-only
                // instead of aborting the whole recording.
                onRoute?.invoke(AudioRouteStatus(audioInputPreference, AudioRouteAvailability.UNAVAILABLE))
                val recordReleased = nativeCleanup { audioRecord?.release() }
                val codecStopped = recordReleased && nativeCleanup { audioCodec?.stop() }
                val codecReleased = codecStopped && nativeCleanup { audioCodec?.release() }
                if (!codecReleased) return null
                audioRecord = null
                audioCodec = null
                expectedTracks = 1
            }
        }
        videoThread = thread(name = "video-drain") { drainVideo() }
        if (terminallyQuarantined.get()) return null
        return inputSurface
    }

    fun stop(nativeReleaseAccepted: () -> Boolean = { true }): StopResult {
        val native = stopNative(nativeReleaseAccepted)
        return native.storageTail?.complete() ?: StopResult(
            saved = false,
            error = native.error,
            nativeGraphDisposition = native.nativeGraphDisposition,
        )
    }

    /**
     * Completes every recorder-owned native/container operation and freezes the remaining provider
     * work into a provider-only continuation. The returned tail owns no codec, muxer, descriptor,
     * Surface, AudioRecord, or process REC lease and is therefore safe to run on an independent
     * executor while this Engine admits another recording.
     */
    internal fun stopNative(nativeReleaseAccepted: () -> Boolean = { true }): NativeStopResult {
        running = false
        muxerLock.withLock { muxerStateChanged.signalAll() }
        if (terminallyQuarantined.get()) return quarantinedNativeStopResult()
        cancelVideoStartupDeadline()
        var finalizedValidation = FinalizedRecordingValidation.NOT_REQUIRED
        if (!nativeCleanup { videoCodec?.signalEndOfInputStream() }) return quarantinedNativeStopResult()

        // AudioRecord.read() may still be blocked after running flips false. Stop the input before
        // joining so the worker can queue AAC EOS instead of timing out while waiting for PCM.
        if (!releaseRecorderNativeOwners(listOf(RecorderNativeOwnerOperation.AUDIO_INPUT_STOP))) {
            return quarantinedNativeStopResult()
        }
        if (terminallyQuarantined.get()) return quarantinedNativeStopResult()
        videoThread?.join(3000)
        audioThread?.join(3000)
        if (terminallyQuarantined.get()) return quarantinedNativeStopResult()

        // A drain thread still alive after its join timeout is wedged INSIDE the codec/muxer (e.g. a
        // dequeueOutputBuffer that never returns). Releasing those objects out from under it races
        // native MediaCodec/MediaMuxer state — a JVM-level catch does not stop a native SIGSEGV — so
        // on a wedge we report a typed quarantine outcome without releasing or clearing owners.
        val drainWedged = nativeGraphDispositionForDrainState(
            videoDrainAlive = videoThread?.isAlive == true,
            audioDrainAlive = audioThread?.isAlive == true,
        ) == NativeGraphDisposition.QUARANTINE_REQUIRED
        if (drainWedged) {
            val timeout = IllegalStateException("Encoder drain timed out")
            recordFailure(timeout)
            // Keep every graph owner and the still-open pending row intact. CameraEngine retains
            // this recorder process-long and closes process admission; clearing Java references
            // here would let a known-live drain coexist with the next recording.
            return NativeStopResult(
                error = firstFailure.cause ?: timeout,
                nativeGraphDisposition = NativeGraphDisposition.QUARANTINE_REQUIRED,
            )
        }

        if (!drainWedged) {
            // audioRecord joins the wedge-leak set: a wedged audio thread may be blocked INSIDE
            // record.read() on this exact object (stop() above doesn't always unblock it on this
            // HAL), and release() under a live read races native AudioRecord state the same way
            // codec/muxer release would — so it is only released on the clean path.
            if (!releaseRecorderNativeOwners(RECORDER_POST_DRAIN_PRE_MUXER_NATIVE_OWNERS)) {
                return quarantinedNativeStopResult()
            }
            muxerLock.withLock {
                if (muxerStarted) {
                    // A muxer.stop() throw is normally VIDEO-terminal (moov not finalized → delete).
                    // The one tolerated case is the TR4-2 corner: audio degraded mid-REC after its
                    // track was added but before any audio sample was muxed — stop() may throw over
                    // the empty audio track while the video track is complete. Failing the clip
                    // there would delete a good take over a dead mic, the exact loss class the
                    // degrade path exists to prevent; attempt the publish gate instead.
                    val stopOutcome = nativeOperations.run { muxer?.stop() }
                    if (stopOutcome is RecorderNativeOperationResult.Rejected ||
                        stopOutcome is RecorderNativeOperationResult.Returned && !stopOutcome.stillOpen
                    ) {
                        return quarantinedNativeStopResult()
                    }
                    check(stopOutcome is RecorderNativeOperationResult.Returned)
                    stopOutcome.result.onFailure { t ->
                        if (muxerStopFailureIsTerminal(wroteVideoSample, audioDegradedMidRec, wroteAudioSample)) {
                            recordFailure(t)
                        } else if (me.hletrd.telecampro.BuildConfig.DEBUG) {
                            finalizedValidation = FinalizedRecordingValidation.SKIPPED
                            Log.w(TAG, "muxer.stop() failed over sample-less degraded audio track; keeping clip: ${t.message}")
                        } else {
                            finalizedValidation = FinalizedRecordingValidation.SKIPPED
                        }
                    }
                }
            }
            // CameraEngine calls stop() only after GlPipeline's checked EGL detach callback. The
            // codec input Surface can therefore be released now, exactly once, before codec cleanup
            // and before videoCodec ownership is cleared below.
            if (!releaseRecorderNativeOwners(RECORDER_POST_DRAIN_NATIVE_OWNERS)) {
                return quarantinedNativeStopResult()
            }
        }
        // The deadline protects only native graph ownership. MediaStore journal/publish work below
        // can be slow without making a fully released codec/muxer graph unsafe.
        if (!nativeReleaseAccepted()) return quarantinedNativeStopResult()
        audioRecord = null

        val outputUri = uri
        val frozenStorage = FrozenRecordingStorage(
            outputUri = outputUri,
            muxerStarted = muxerStarted,
            wroteVideoSample = wroteVideoSample,
            failure = firstFailure.cause,
            finalizedValidation = finalizedValidation,
        )

        videoCodec = null
        audioCodec = null
        muxer = null
        pfd = null
        // Clear uri with every other field so a repeated stop() is a pure no-op: complete=false AND
        // outputUri=null means neither publish nor the destructive delete can run again — a 2nd stop()
        // can NEVER delete the clip the 1st already published (CR-4: uri used to survive this reset, so
        // a 2nd stop() recomputed saved=false and deleted the ALREADY-PUBLISHED file).
        uri = null
        videoTrack = -1
        audioTrack = -1
        muxerStartState = MuxerStartState.WAITING
        audioTrackDeadlineNs = Long.MAX_VALUE
        wroteVideoSample = false
        wroteAudioSample = false
        audioDegradedMidRec = false
        videoThread = null
        audioThread = null
        onFailure = null
        return NativeStopResult(
            error = frozenStorage.failure,
            storageTail = RecordingStorageTail(context, frozenStorage),
        )
    }

    private fun releaseRecorderNativeOwners(owners: List<RecorderNativeOwnerOperation>): Boolean =
        runRecorderNativeOwnerSequence(owners) { owner ->
            when (owner) {
                RecorderNativeOwnerOperation.AUDIO_INPUT_STOP -> nativeCleanup { audioRecord?.stop() }
                RecorderNativeOwnerOperation.AUDIO_INPUT_RELEASE -> nativeCleanup { audioRecord?.release() }
                RecorderNativeOwnerOperation.INPUT_SURFACE_RELEASE -> {
                    if (inputSurfaceOwner.get() == null) {
                        true
                    } else {
                        inputSurfaceOwner.releaseConditionally { surface -> nativeCleanup { surface.release() } }
                    }
                }
                RecorderNativeOwnerOperation.VIDEO_CODEC_STOP -> nativeCleanup { videoCodec?.stop() }
                RecorderNativeOwnerOperation.VIDEO_CODEC_RELEASE -> nativeCleanup { videoCodec?.release() }
                RecorderNativeOwnerOperation.AUDIO_CODEC_STOP -> nativeCleanup { audioCodec?.stop() }
                RecorderNativeOwnerOperation.AUDIO_CODEC_RELEASE -> nativeCleanup { audioCodec?.release() }
                RecorderNativeOwnerOperation.MUXER_RELEASE -> nativeCleanup { muxer?.release() }
                RecorderNativeOwnerOperation.DESCRIPTOR_CLOSE -> nativeCleanup { pfd?.close() }
            }
        } == null

    private fun quarantinedNativeStopResult(): NativeStopResult = NativeStopResult(
        error = nativeCleanupFailure.get()
            ?: firstFailure.cause
            ?: java.util.concurrent.TimeoutException("Recorder graph was quarantined"),
        nativeGraphDisposition = NativeGraphDisposition.QUARANTINE_REQUIRED,
    )

    /**
     * Ends app/audio activity without releasing any codec, muxer, descriptor, or input Surface.
     *
     * This is the terminal fallback when CameraEngine cannot prove that EGL relinquished the
     * codec's native window. Releasing those native owners could race a wedged GL thread and crash
     * the process. We instead make the worker loops observe a terminal flag, request AudioRecord
     * stop on a daemon helper (so a vendor stop wedge cannot strand engine state), zero the meter,
     * and retain this recorder process-long through [UnsafeRecorderQuarantine].
     */
    internal fun quarantineUnsafeNativeGraph(): Boolean {
        // Close native admission first. This is the linearization point every setup/finalization
        // action uses; a standalone AtomicBoolean check cannot close a later check-to-call window.
        if (!nativeOperations.close()) return false
        terminallyQuarantined.set(true)
        running = false
        cancelVideoStartupDeadline()
        onFailure = null
        onLevel = null
        // A muxer native call may be hung while holding muxerLock — acquiring it here would wedge
        // the watchdog that is trying to publish quarantine. Both wakeups are best-effort only;
        // terminal ownership is already published by the atomics above and the Engine clears UI.
        runCatching {
            Thread(
                { runCatching { muxerLock.withLock { muxerStateChanged.signalAll() } } },
                "unsafe-recorder-muxer-signal",
            ).apply { isDaemon = true }.start()
        }
        // Do not call AudioRecord.stop here. An already-admitted finalizer may still be returning
        // from another call on that exact owner; a concurrent stop/release pair is the native race
        // quarantine exists to prevent. The retained graph is process-terminal by definition.
        return true
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
            if (me.hletrd.telecampro.BuildConfig.DEBUG) Log.w(TAG, "video drain aborted (encoder error): ${t.message}")
            recordFailure(t)
        }
    }

    private fun drainVideoLoop(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            if (terminallyQuarantined.get()) return
            val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (terminallyQuarantined.get()) return
            when {
                // Do not exit on !running here: the encoder may not have emitted its EOS buffer
                // yet, and breaking early would truncate the tail. stop() bounds this loop via
                // videoThread.join(timeout) after signalling EOS, so looping is safe.
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxerLock.withLock {
                    videoTrack = muxer!!.addTrack(codec.outputFormat)
                    videoStartupProof.observeFormat()
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null && awaitMuxerStart()) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxerLock.withLock {
                            runCatching { checkNotNull(muxer).writeSampleData(videoTrack, buf, info) }
                                .onSuccess {
                                    wroteVideoSample = true
                                    if (videoStartupProof.observeMuxedSample()) {
                                        cancelVideoStartupDeadline()
                                    }
                                }
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
        val minBuf = nativeOperation {
            AudioRecord.getMinBufferSize(
                ColorProfiles.AUDIO_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            )
        }.let { first ->
            if (first > 0 || channelCount == 1) first else {
                channelCount = 1
                channelMask = AudioFormat.CHANNEL_IN_MONO
                nativeOperation {
                    AudioRecord.getMinBufferSize(
                        ColorProfiles.AUDIO_SAMPLE_RATE,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                }
            }
        }
        if (minBuf <= 0) {
            expectedTracks = 1
            onRoute?.invoke(AudioRouteStatus(audioInputPreference, AudioRouteAvailability.UNAVAILABLE))
            return
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(ColorProfiles.AUDIO_SAMPLE_RATE)
            .setChannelMask(channelMask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val record = runCatching {
            nativeOperation {
            @Suppress("MissingPermission")
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(8192))
                    .build()
                    .also { audioRecord = it }
            }
        }.getOrNull() ?: run {
            if (!nativeOperations.isOpen()) throw RecorderNativeOperationRevokedException()
            expectedTracks = 1
            onRoute?.invoke(AudioRouteStatus(audioInputPreference, AudioRouteAvailability.UNAVAILABLE))
            return
        }
        // An AudioRecord that failed to initialize (busy mic, unsupported config) is left in
        // STATE_UNINITIALIZED; calling startRecording() on it throws on the audio thread → crash.
        // Degrade to video-only instead.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            if (!nativeCleanup { record.release() }) throw RecorderNativeOperationRevokedException()
            audioRecord = null
            expectedTracks = 1
            onRoute?.invoke(AudioRouteStatus(audioInputPreference, AudioRouteAvailability.UNAVAILABLE))
            return
        }
        if (preferredDevice != null && !nativeOperation { record.setPreferredDevice(preferredDevice) }) {
            if (me.hletrd.telecampro.BuildConfig.DEBUG) {
                Log.w(TAG, "preferred audio input rejected: ${preferredDevice.productName}")
            }
        }
        audioChannelCount = channelCount
        nativeOperation { applyAudioScene(record) }

        val codec = nativeOperation {
            MediaCodec.createEncoderByType(ColorProfiles.MIME_AAC).also { audioCodec = it }
        }
        // Field assignment BEFORE configure/start: if either throws, the caller's failure cleanup
        // releases audioCodec — a local-only codec would leak the HW encoder instance.
        nativeOperation {
            codec.configure(
                ColorProfiles.aacFormat(audioChannelCount),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
        }
        nativeOperation { codec.start() }

        if (!nativeOperations.isOpen()) throw RecorderNativeOperationRevokedException()
        audioThread = thread(name = "audio-encode") {
            try {
                runAudio(record, codec)
            } catch (t: Exception) {
                // Every terminal audio fault reaches here (the negative-read throw, an audio codec
                // throw, or an audio-track muxer write that propagated). Degrade to video-only — a
                // cleanly-muxed video track must survive a dead mic, NOT be deleted via the shared
                // firstFailure latch (AGG3-2). VIDEO-side faults keep using recordFailure/delete.
                degradeAudioToVideoOnly(t)
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
        if (me.hletrd.telecampro.BuildConfig.DEBUG) {
            Log.i(TAG, "audioScene=$audioScene applied (zoom=$audioZoom orient=$audioOrientation) " +
                "trackSupport=[$support] echo=[$echo]")
        }
    }

    private fun runAudio(record: AudioRecord, codec: MediaCodec) {
        if (terminallyQuarantined.get()) return
        // This worker starts after VideoRecorder.start() has left its setup admission. Linearize the
        // actual native start against process quarantine, and recheck this recorder's terminal state
        // inside that process lock. A terminal refusal must leave the quarantined owner untouched.
        val audioStart = runCatching {
            startNativeOwnerIfSafe(
                runNativeAcquisition = { block ->
                    UnsafeRecorderQuarantine.runNativeAcquisition(processAdmissionToken?.owner, block)
                },
                isTerminal = terminallyQuarantined::get,
                start = record::startRecording,
            )
        }
        // Preserve the pre-existing ownership of an ordinary AudioRecord.startRecording() failure:
        // audio degrades to video-only. Process/local terminal refusal is different: quarantine owns
        // the graph, so this thread stops Java-side progression without touching any native owner.
        if (audioStart.isFailure) {
            onRoute?.invoke(AudioRouteStatus(audioInputPreference, AudioRouteAvailability.UNAVAILABLE))
            muxerLock.withLock {
                expectedTracks = 1
                maybeStartMuxer()
            }
            return
        }
        if (audioStart.getOrThrow() == NativeStartOutcome.REFUSED) return
        onRoute?.invoke(AudioInputInspector.routeStatus(audioInputPreference, record.routedDevice ?: record.preferredDevice))
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L
        val bytesPerFrame = 2 * audioChannelCount
        var sentEos = false
        var eosAttempts = 0

        while (true) {
            when (audioWorkerLoopDisposition(terminallyQuarantined.get(), audioDegradedMidRec)) {
                AudioWorkerLoopDisposition.CONTINUE -> Unit
                AudioWorkerLoopDisposition.EXIT_RETAIN_INPUT -> return
            }
            if (!sentEos) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    buf?.clear()
                    val read = if (running && buf != null) record.read(buf, buf.capacity()) else 0
                    if (terminallyQuarantined.get()) return
                    // Read may block while stop() flips running and calls AudioRecord.stop(). Judge
                    // the returned code against the state observed AFTER that call: a negative stop
                    // wake-up is normal EOS, but any negative code while still running is terminal.
                    val readOutcome = classifyAudioRead(read, running)
                    val ptsUs = audioPtsUs(totalSamples, ColorProfiles.AUDIO_SAMPLE_RATE)
                    when (readOutcome) {
                        is AudioReadOutcome.Pcm -> {
                            val pcmBuffer = checkNotNull(buf)
                            // Apply gain in place and emit a throttled level update before this PCM
                            // buffer is queued to the AAC encoder below. At unity gain the rewrite is
                            // a no-op, so the RMS pass is skipped entirely unless a level emit is due.
                            val emitDue = levelEmitDue()
                            if (audioGain != 1f || emitDue) {
                                val levels = applyGainAndLevel(pcmBuffer, readOutcome.byteCount, audioGain, audioChannelCount)
                                if (emitDue) maybeEmitLevel(levels)
                            }
                            codec.queueInputBuffer(inIdx, 0, readOutcome.byteCount, ptsUs, 0)
                            totalSamples += readOutcome.byteCount / bytesPerFrame
                        }
                        AudioReadOutcome.Retry -> codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                        AudioReadOutcome.Stopped -> {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sentEos = true
                        }
                        // A mid-REC negative read (dropped BT/USB/wired mic → ERROR_DEAD_OBJECT etc.)
                        // is AUDIO-terminal: throw so the thread catch degrades to VIDEO-ONLY (AGG3-2).
                        // It no longer deletes the cleanly-muxed video via the shared firstFailure latch.
                        is AudioReadOutcome.Failure -> throw audioReadFailure(readOutcome.code)
                    }
                } else if (!running && ++eosAttempts >= MAX_EOS_ATTEMPTS) {
                    // Stop was requested but the encoder has produced no free input buffer to carry
                    // EOS for ~3 s — it is effectively wedged. Bail instead of looping until stop()'s
                    // join gives up; stop() finalizes/fails the clip based on what was actually muxed.
                    // Degrade to video-only like every sibling audio-setup bail: if the wedge hit
                    // before the codec ever emitted its output format (audioTrack still -1), leaving
                    // expectedTracks == 2 would keep maybeStartMuxer waiting forever and stop()'s
                    // saved gate would discard a perfectly good video track over a dead AAC encoder.
                    degradeAudioToVideoOnly(IllegalStateException("AAC encoder input EOS timed out"))
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
                    muxerLock.withLock {
                        audioTrack = muxer!!.addTrack(codec.outputFormat)
                        maybeStartMuxer()
                    }
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null && awaitMuxerStart()) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxerLock.withLock {
                            // An audio-track muxer write failure is AUDIO-side: let it propagate to
                            // this thread's catch, which degrades to video-only (AGG3-2). The shared
                            // firstFailure/delete latch is reserved for VIDEO codec/muxer errors — a
                            // muxer that is globally broken also fails the video write (:drainVideoLoop),
                            // whose recordFailure then correctly wins the save gate and deletes.
                            muxer?.writeSampleData(audioTrack, buf, info)
                            wroteAudioSample = true
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

    /** Forwards per-channel [levels] to [onLevel], throttled to roughly [LEVEL_THROTTLE_NS]. */
    private fun maybeEmitLevel(levels: FloatArray) {
        val now = System.nanoTime()
        if (now - lastLevelEmitNs < LEVEL_THROTTLE_NS) return
        lastLevelEmitNs = now
        onLevel?.invoke(levels)
    }

    private fun maybeStartMuxer() {
        val ownedMuxer = muxer ?: return
        if (videoTrack >= 0 && expectedTracks > 1 && audioTrack < 0 &&
            audioTrackDeadlineNs == Long.MAX_VALUE
        ) {
            audioTrackDeadlineNs = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(TRACK_RENDEZVOUS_TIMEOUT_MS)
        }
        // Both drain workers can arrive here after VideoRecorder.start() has left setup admission.
        // Publish the immutable result before reporting a failure so re-entrant teardown/audio
        // degradation observes a terminal attempt and cannot touch MediaMuxer.start() again.
        val transition = transitionMuxerStart(
            state = muxerStartState,
            videoTrackReady = videoTrack >= 0,
            expectedTracks = expectedTracks,
            audioTrackReady = audioTrack >= 0,
        ) {
            startNativeOwnerIfSafe(
                runNativeAcquisition = { block ->
                    UnsafeRecorderQuarantine.runNativeAcquisition(processAdmissionToken?.owner, block)
                },
                isTerminal = terminallyQuarantined::get,
                start = ownedMuxer::start,
            )
        }
        muxerStartState = transition.state
        muxerStateChanged.signalAll()
        transition.failure?.let(::recordFailure)
    }

    /**
     * Condition-based, bounded rendezvous for the drain workers. If audio misses the deadline after
     * video is ready, preserve the take through the established video-only degradation. A missing
     * video format is clip-terminal. No codec output buffer is retained beyond this deadline.
     */
    private fun awaitMuxerStart(): Boolean {
        while (true) {
            var timeoutAction: MuxerRendezvousTimeoutAction? = null
            val started = muxerLock.withLock {
                while (running && muxerStartState == MuxerStartState.WAITING) {
                    val nowNs = System.nanoTime()
                    val videoReady = videoTrack >= 0
                    val audioReady = audioTrack >= 0
                    val action = startupRendezvousTimeoutAction(
                        nowNs = nowNs,
                        videoDeadlineNs = videoStartupDeadlineNs,
                        audioDeadlineNs = audioTrackDeadlineNs,
                        videoTrackReady = videoReady,
                        expectedTracks = expectedTracks,
                        audioTrackReady = audioReady,
                    )
                    if (action != null) {
                        timeoutAction = action
                        break
                    }
                    val deadlineNs = startupRendezvousWakeDeadlineNs(
                        videoDeadlineNs = videoStartupDeadlineNs,
                        audioDeadlineNs = audioTrackDeadlineNs,
                        videoTrackReady = videoReady,
                        expectedTracks = expectedTracks,
                        audioTrackReady = audioReady,
                    )
                    val remainingNs = deadlineNs - nowNs
                    if (remainingNs <= 0L) {
                        continue
                    }
                    muxerStateChanged.awaitNanos(remainingNs)
                }
                muxerStarted
            }
            when (timeoutAction) {
                MuxerRendezvousTimeoutAction.DEGRADE_AUDIO -> {
                    degradeAudioToVideoOnly(IllegalStateException("Audio track setup timed out"))
                    continue
                }
                MuxerRendezvousTimeoutAction.FAIL_VIDEO -> {
                    val reason = videoStartupProof.expire()
                        ?: "Video encoder startup deadline expired"
                    recordFailure(IllegalStateException(reason))
                    return false
                }
                null -> return started
            }
        }
    }

    private fun armVideoStartupDeadline() {
        videoStartupDeadlineNs = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(VIDEO_STARTUP_PROOF_TIMEOUT_MS)
        videoStartupDeadline = videoStartupDeadlineExecutor.schedule(
            {
                videoStartupProof.expire()?.let { reason ->
                    recordFailure(IllegalStateException(reason))
                }
            },
            VIDEO_STARTUP_PROOF_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelVideoStartupDeadline() {
        videoStartupDeadline?.cancel(false)
        videoStartupDeadline = null
        videoStartupDeadlineNs = Long.MAX_VALUE
        videoStartupDeadlineExecutor.shutdownNow()
    }

    private fun recordFailure(t: Throwable) {
        running = false
        muxerLock.withLock { muxerStateChanged.signalAll() }
        cancelVideoStartupDeadline()
        // Codec/muxer failures happen on the drain threads. Notify the owner immediately so it can
        // leave REC state and start ordered teardown instead of waiting for a manual Stop tap. The
        // atomic signal retains the first cause and invokes this callback at most once even when the
        // video and audio threads fail together (or stop() observes a second finalization error).
        firstFailure.record(t) { cause -> onFailure?.invoke(cause) }
    }

    /**
     * Terminal AUDIO-only fault MID-recording (a dropped mic's negative [AudioRecord.read] in
     * [runAudio], an audio codec throw, or an audio-track muxer write error): degrade to VIDEO-ONLY
     * exactly like every audio-SETUP bail, NEVER destroy a cleanly-muxed video track (AGG3-2). It
     * therefore does NOT route into the shared [firstFailure] latch (reserved for VIDEO codec/muxer
     * faults, whose delete IS correct), does NOT clear [running] (video keeps draining to the stop
     * point), and does NOT invoke [onFailure] (the recording continues, it is not auto-stopped) —
     * mirroring the [startAudio] degrades and the fa80574 AAC-wedge bail. Dropping the audio
     * expectation ([expectedTracks] = 1 + [maybeStartMuxer]) lets a muxer still waiting for the audio
     * track start on video alone; a muxer that already started with both tracks simply stops
     * receiving audio samples. Idempotent under repeated audio faults; safe to call while already
     * holding [muxerLock] (the monitor is reentrant).
     */
    private fun degradeAudioToVideoOnly(cause: Throwable) {
        if (me.hletrd.telecampro.BuildConfig.DEBUG) Log.w(TAG, "audio degraded to video-only (mid-REC): ${cause.message}")
        audioDegradedMidRec = true
        onRoute?.invoke(AudioRouteStatus(audioInputPreference, AudioRouteAvailability.UNAVAILABLE))
        // Zero the live meter explicitly: the mic is dead, and a meter frozen at its last level
        // would mislead the operator into believing audio is still being captured (CRIT4-6).
        onLevel?.invoke(FloatArray(0))
        muxerLock.withLock {
            expectedTracks = 1
            maybeStartMuxer()
        }
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
        const val TRACK_RENDEZVOUS_TIMEOUT_MS = 1_000L
        const val VIDEO_STARTUP_PROOF_TIMEOUT_MS = 4_000L
        // ~10 Hz cap on onLevel callbacks so the UI meter isn't spammed once per PCM buffer.
        const val LEVEL_THROTTLE_NS = 100_000_000L
        // ~3 s of 10 ms input-dequeue timeouts: how long the stop path keeps asking for a free
        // input buffer to carry AAC EOS before declaring the encoder wedged and bailing.
        const val MAX_EOS_ATTEMPTS = 300
    }
}

/**
 * Process-lifetime retention for a recorder whose native graph is unsafe to release.
 *
 * A fresh Activity/ViewModel must not silently create another camera/recorder graph while the old
 * one may still own EGL/codec/microphone resources. There is intentionally no reset API: process
 * restart is the only safe reclamation boundary after this rare terminal fault.
 */
internal class UnsafeRecorderAdmissionToken internal constructor(
    internal val epoch: Long,
    internal val owner: Any,
)

internal enum class NativeAcquisitionReplayOutcome { AVAILABLE, QUARANTINED }

internal data class GeneralNativeAcquisitionState(
    val quarantined: Boolean,
    val setupPending: Boolean,
    val activeRecorderOwnedByForeignEngine: Boolean,
)

/** Typed finite/terminal process-gate refusal; Engine decides replay versus restart-required. */
internal enum class NativeAcquisitionRefusalPhase { EGL_CONTEXT, PREVIEW_OUTPUT, CAMERA_GRAPH }

internal class NativeAcquisitionRefusedException(
    val phase: NativeAcquisitionRefusalPhase,
    message: String,
) : CancellationException(message)

/** One-shot cancellation for an Engine waiting behind a recorder setup/native owner. */
internal fun interface NativeAcquisitionReplayObservation {
    fun cancel()
}

internal class UnsafeStandbyAdmissionToken internal constructor(
    internal val epoch: Long,
    internal val owner: Any,
)

/** Process-global linearization between REC publication/native handoff and terminal quarantine. */
internal class RecorderQuarantineAdmissionGate {
    private val lock = ReentrantLock()
    private val nativeAcquisitionsDrained = lock.newCondition()
    private val epoch = AtomicLong(0L)
    private val quarantined = AtomicBoolean(false)
    private var pendingToken: UnsafeRecorderAdmissionToken? = null
    private var activeToken: UnsafeRecorderAdmissionToken? = null
    private var standbyToken: UnsafeStandbyAdmissionToken? = null
    private data class ReplayObserver(
        val tokenEpoch: Long,
        val owner: Any,
        val callback: (NativeAcquisitionReplayOutcome) -> Unit,
    )
    private val replayObserverId = AtomicLong(0L)
    private val replayObservers = LinkedHashMap<Long, ReplayObserver>()

    private fun notifyReplayObservers(
        observers: List<ReplayObserver>,
        outcome: NativeAcquisitionReplayOutcome,
    ) {
        observers.forEach { observer -> runCatching { observer.callback(outcome) } }
    }

    private fun removeReplayObserversLocked(
        predicate: (ReplayObserver) -> Boolean,
    ): List<ReplayObserver> {
        val removed = replayObservers.filterValues(predicate)
        removed.keys.forEach(replayObservers::remove)
        return removed.values.toList()
    }
    private var nativeAcquisitions = 0

    fun snapshot(owner: Any): UnsafeRecorderAdmissionToken? = lock.withLock {
        val foreignStandby = standbyToken?.owner?.let { it !== owner } == true
        if (quarantined.get() || pendingToken != null || activeToken != null || foreignStandby) {
            null
        } else {
            UnsafeRecorderAdmissionToken(epoch.incrementAndGet(), owner).also { pendingToken = it }
        }
    }

    /** Exactly one process standby mic, and never while any recorder admission owns the process. */
    fun reserveStandby(owner: Any): UnsafeStandbyAdmissionToken? = lock.withLock {
        if (quarantined.get() || pendingToken != null || activeToken != null || standbyToken != null) {
            null
        } else {
            UnsafeStandbyAdmissionToken(epoch.incrementAndGet(), owner).also { standbyToken = it }
        }
    }

    fun finishStandby(token: UnsafeStandbyAdmissionToken) = lock.withLock {
        if (standbyToken?.epoch == token.epoch && standbyToken?.owner === token.owner) {
            standbyToken = null
            epoch.incrementAndGet()
        }
    }

    fun isCurrent(token: UnsafeRecorderAdmissionToken): Boolean = lock.withLock {
        !quarantined.get() && (pendingToken == token || activeToken == token)
    }

    fun commit(token: UnsafeRecorderAdmissionToken, block: () -> Unit): Boolean = lock.withLock {
        if (!isCurrent(token)) return@withLock false
        block()
        true
    }

    /**
     * Takes one counted native-admission lease, executes outside [lock], then reports success only
     * if quarantine did not revoke the lease while the native call was in flight.
     *
     * The count closes the old check -> close -> native-entry hole: [close] first prevents another
     * lease. A block admitted before close may finish because a native call cannot be un-called, but
     * its false result prevents downstream publication or cleanup after quarantine owns the graph.
     * Drain observation is deliberately separate and bounded: quarantine cannot wait forever for
     * the native call whose failure caused the terminal transition.
     */
    fun generalNativeAcquisitionState(owner: Any?): GeneralNativeAcquisitionState = lock.withLock {
        GeneralNativeAcquisitionState(
            quarantined = quarantined.get(),
            setupPending = pendingToken != null,
            activeRecorderOwnedByForeignEngine = activeToken?.owner?.let { it !== owner } == true,
        )
    }

    fun runNativeIfSafe(block: () -> Unit): Boolean = runNativeIfSafe(null, block)

    fun runNativeIfSafe(owner: Any?, block: () -> Unit): Boolean {
        val admitted = lock.withLock {
            // A pending REC token can already be inside MediaCodec setup while its recorder has not
            // yet reached the published Engine slot. General GL/Camera2 acquisition must wait for
            // that setup to publish or retire; token-specific [runPendingNative] is its sole entry.
            val foreignActiveRecorder = activeToken?.owner?.let { it !== owner } == true
            if (quarantined.get() || pendingToken != null || foreignActiveRecorder) {
                false
            } else {
                nativeAcquisitions++
                true
            }
        }
        if (!admitted) return false
        try {
            block()
        } finally {
            lock.withLock {
                nativeAcquisitions--
                check(nativeAcquisitions >= 0) { "Native admission count underflow" }
                if (nativeAcquisitions == 0) nativeAcquisitionsDrained.signalAll()
            }
        }
        return lock.withLock { !quarantined.get() }
    }

    /** Token-specific setup lease: bookkeeping under lock, native work outside it. */
    fun runPendingNative(token: UnsafeRecorderAdmissionToken, block: () -> Unit): Boolean {
        val admitted = lock.withLock {
            if (quarantined.get() || pendingToken != token) {
                false
            } else {
                nativeAcquisitions++
                true
            }
        }
        if (!admitted) return false
        try {
            block()
        } finally {
            lock.withLock {
                nativeAcquisitions--
                check(nativeAcquisitions >= 0) { "Native admission count underflow" }
                if (nativeAcquisitions == 0) nativeAcquisitionsDrained.signalAll()
            }
        }
        return lock.withLock {
            !quarantined.get() && pendingToken == token
        }
    }

    fun publish(token: UnsafeRecorderAdmissionToken, block: () -> Boolean): Boolean {
        val observers = lock.withLock {
            if (quarantined.get() || pendingToken != token || activeToken != null) {
                return false
            }
            if (!block()) return false
            pendingToken = null
            activeToken = token
            // The setup-owning Engine may now safely continue against its published graph. A
            // replacement Engine remains parked until strict finalization releases this token.
            removeReplayObserversLocked { it.tokenEpoch == token.epoch && it.owner === token.owner }
        }
        notifyReplayObservers(observers, NativeAcquisitionReplayOutcome.AVAILABLE)
        return true
    }

    /** Clears a setup lease only; a successfully published owner remains process-exclusive. */
    fun abandonPending(token: UnsafeRecorderAdmissionToken) {
        val observers = lock.withLock {
            if (pendingToken != token) return
            pendingToken = null
            epoch.incrementAndGet()
            removeReplayObserversLocked { it.tokenEpoch == token.epoch }
        }
        notifyReplayObservers(observers, NativeAcquisitionReplayOutcome.AVAILABLE)
    }

    /** Strict recorder finalization releases the one process-wide active recording lease. */
    fun finish(token: UnsafeRecorderAdmissionToken?) {
        if (token == null) return
        val observers = lock.withLock {
            if (activeToken != token) return
            activeToken = null
            epoch.incrementAndGet()
            removeReplayObserversLocked { it.tokenEpoch == token.epoch }
        }
        notifyReplayObservers(observers, NativeAcquisitionReplayOutcome.AVAILABLE)
    }

    /**
     * Registers one exact Engine owner behind the current recorder token without a check/register
     * race. The setup owner is released at publication; foreign Engines remain parked through the
     * active recording and are released only by strict finalization.
     */
    fun observeNativeAcquisitionReplay(
        owner: Any,
        callback: (NativeAcquisitionReplayOutcome) -> Unit,
    ): NativeAcquisitionReplayObservation? = lock.withLock {
        val token = pendingToken ?: activeToken?.takeIf { it.owner !== owner } ?: return null
        val id = replayObserverId.incrementAndGet()
        replayObservers[id] = ReplayObserver(token.epoch, owner, callback)
        val cancelled = AtomicBoolean(false)
        NativeAcquisitionReplayObservation {
            if (cancelled.compareAndSet(false, true)) {
                lock.withLock { replayObservers.remove(id) }
            }
        }
    }

    /** Irreversible and non-blocking: closes publication/admission before terminal convergence. */
    fun close(): Boolean {
        val (firstClose, observers) = lock.withLock {
            val first = !quarantined.getAndSet(true)
            val removed = if (first) {
                epoch.incrementAndGet()
                pendingToken = null
                activeToken = null
                standbyToken = null
                removeReplayObserversLocked { true }
            } else {
                emptyList()
            }
            first to removed
        }
        notifyReplayObservers(observers, NativeAcquisitionReplayOutcome.QUARANTINED)
        return firstClose
    }

    /** Best-effort observation only; false retains every unsafe owner for process restart. */
    fun awaitNativeAcquisitionsDrained(timeout: Long, unit: TimeUnit): Boolean {
        var remainingNs = unit.toNanos(timeout.coerceAtLeast(0L))
        var interrupted = false
        val drained = lock.withLock {
            while (nativeAcquisitions > 0 && remainingNs > 0L) {
                try {
                    remainingNs = nativeAcquisitionsDrained.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    interrupted = true
                    remainingNs = 0L
                }
            }
            nativeAcquisitions == 0
        }
        if (interrupted) Thread.currentThread().interrupt()
        return drained
    }

    fun isQuarantined(): Boolean = quarantined.get()

    fun hasPendingRecorderSetup(): Boolean = lock.withLock { pendingToken != null }
}

internal object UnsafeRecorderQuarantine {
    private val admissionGate = RecorderQuarantineAdmissionGate()
    private val retained = Collections.synchronizedList(mutableListOf<VideoRecorder>())
    private val retainedNativeOwners = Collections.synchronizedList(mutableListOf<Any>())

    fun snapshotAdmission(owner: Any): UnsafeRecorderAdmissionToken? = admissionGate.snapshot(owner)

    fun reserveStandbyAdmission(owner: Any): UnsafeStandbyAdmissionToken? =
        admissionGate.reserveStandby(owner)

    fun finishStandbyAdmission(token: UnsafeStandbyAdmissionToken) {
        admissionGate.finishStandby(token)
    }

    fun isAdmissionCurrent(token: UnsafeRecorderAdmissionToken): Boolean = admissionGate.isCurrent(token)

    fun commitAdmission(token: UnsafeRecorderAdmissionToken, block: () -> Unit): Boolean =
        admissionGate.commit(token, block)

    fun generalNativeAcquisitionState(owner: Any?): GeneralNativeAcquisitionState =
        admissionGate.generalNativeAcquisitionState(owner)

    fun runNativeAcquisition(block: () -> Unit): Boolean = admissionGate.runNativeIfSafe(block = block)

    fun runNativeAcquisition(owner: Any?, block: () -> Unit): Boolean =
        admissionGate.runNativeIfSafe(owner, block)

    fun runPendingNativeSetup(token: UnsafeRecorderAdmissionToken, block: () -> Unit): Boolean =
        admissionGate.runPendingNative(token, block)

    fun hasPendingRecorderSetup(): Boolean = admissionGate.hasPendingRecorderSetup()

    fun observeNativeAcquisitionReplay(
        owner: Any,
        callback: (NativeAcquisitionReplayOutcome) -> Unit,
    ): NativeAcquisitionReplayObservation? =
        admissionGate.observeNativeAcquisitionReplay(owner, callback)

    fun publishAdmission(token: UnsafeRecorderAdmissionToken, block: () -> Boolean): Boolean =
        admissionGate.publish(token, block)

    fun abandonPendingAdmission(token: UnsafeRecorderAdmissionToken) {
        admissionGate.abandonPending(token)
    }

    fun finishAdmission(token: UnsafeRecorderAdmissionToken?) {
        admissionGate.finish(token)
    }

    /**
     * Irreversibly refuses every later native graph after a non-recorder owner cannot prove release.
     * The process gate already spans EGL, Camera2, codec/muxer, and AudioRecord acquisition; camera
     * teardown therefore uses the same authority without manufacturing a VideoRecorder to retain.
     */
    fun quarantineNativeGraph(owner: Any): Boolean {
        // Retain the Java owner as well as closing acquisition. A timed-out handler normally keeps
        // its queued teardown reachable, but a cleanup throw or dead queue must not let GC/finalizer
        // behavior become an accidental native-release strategy after quarantine has won.
        val firstClose = admissionGate.close()
        retainedNativeOwners += owner
        return firstClose
    }

    fun retain(recorder: VideoRecorder): Boolean {
        // Publish the irreversible process gate before recorder-side callbacks or AudioRecord.stop:
        // either may re-enter UI/Engine code, and no fresh native owner may be admitted in that gap.
        admissionGate.close()
        val newlyQuarantined = recorder.quarantineUnsafeNativeGraph()
        if (newlyQuarantined) retained += recorder
        return newlyQuarantined
    }

    fun isActive(): Boolean = admissionGate.isQuarantined()
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
 * Pure exactly-once owner for a native resource whose release must be ordered against another owner.
 * The value is atomically removed before [release] invokes external cleanup, so a throwing releaser
 * still cannot cause duplicate native release. [abandon] is the explicit no-release terminal path.
 */
internal class ExactlyOnceResourceOwner<T : Any> {
    private var value: T? = null

    @Synchronized
    fun install(resource: T) {
        check(value == null) { "Resource owner already has a value" }
        value = resource
    }

    @Synchronized
    fun get(): T? = value

    fun release(releaser: (T) -> Unit): Boolean {
        val owned = synchronized(this) {
            val current = value ?: return false
            value = null
            current
        }
        releaser(owned)
        return true
    }

    /** Keeps ownership when [releaser] was refused by terminal native-operation admission. */
    fun releaseConditionally(releaser: (T) -> Boolean): Boolean {
        val owned = synchronized(this) { value ?: return false }
        if (!releaser(owned)) return false
        synchronized(this) {
            if (value === owned) value = null
        }
        return true
    }

    /** Runs [afterRelease] even when no value exists or [releaser] throws, while preserving order. */
    fun releaseThen(releaser: (T) -> Unit, afterRelease: () -> Unit): Boolean =
        try {
            release(releaser)
        } finally {
            afterRelease()
        }

    /** Drops ownership without touching the native resource; used only when another thread is live. */
    fun abandon(): Boolean = synchronized(this) {
        if (value == null) {
            false
        } else {
            value = null
            true
        }
    }
}

/**
 * Pure MediaMuxer-start readiness gate, extracted from [VideoRecorder.maybeStartMuxer] so the video-only
 * degrade accounting is host-testable (AGG3-53/TEST-3 — fa80574 shipped it with zero coverage). The
 * one shared muxer may start only ONCE ([muxerStarted] false), only after the video track exists
 * ([videoTrackReady] — a MediaMuxer started with no video track produces an unplayable file), and —
 * unless the audio expectation was dropped to video-only ([expectedTracks] == 1) — only after the
 * audio track exists ([audioTrackReady], so audio samples are never lost before addTrack). The
 * `expectedTracks == 1` short-circuit is the DATA-LOSS fix behind both the AAC-wedge bail (fa80574)
 * and the mid-REC audio degrade (AGG3-2): a recording whose audio died starts on the video track
 * alone instead of waiting forever for a dead AAC encoder and discarding a clean video clip. Four
 * primitives / no Android types, next to [classifyAudioRead]'s pattern, so it is unit-testable.
 */
internal fun shouldStartMuxer(
    muxerStarted: Boolean,
    videoTrackReady: Boolean,
    expectedTracks: Int,
    audioTrackReady: Boolean,
): Boolean = !muxerStarted && videoTrackReady && (expectedTracks == 1 || audioTrackReady)

/** Final recorder-side defense against a stale/mismatched exact-component capability token. */
internal fun encoderSelectionAdmitsTransfer(
    selection: EncoderSelection,
    transfer: ColorTransfer,
): Boolean = selection.mime == ColorProfiles.mimeFor(selection.codec) && when (selection.codec) {
    VideoCodec.HEVC -> transfer == ColorTransfer.SDR || selection.main10
    VideoCodec.AVC -> transfer == ColorTransfer.SDR
    VideoCodec.APV -> false
}

internal data class EncoderConfigureAttempt(
    val selection: EncoderSelection,
    val width: Int,
    val height: Int,
)

private class MediaCodecAttemptOwner(
    val codec: MediaCodec,
    var surface: Surface? = null,
)

internal data class ConfiguredEncoderAttempt<T>(
    val attempt: EncoderConfigureAttempt,
    val owner: T,
)

internal class RecorderNativeOperationRevokedException : IllegalStateException(
    "recorder native-operation admission was revoked",
)

internal sealed interface RecorderNativeOperationResult<out T> {
    data object Rejected : RecorderNativeOperationResult<Nothing>

    data class Returned<T>(
        val result: Result<T>,
        /** False when quarantine closed admission while this already-entered call was returning. */
        val stillOpen: Boolean,
    ) : RecorderNativeOperationResult<T>
}

internal sealed interface NativeCleanupOutcome {
    data object Completed : NativeCleanupOutcome
    data object Revoked : NativeCleanupOutcome
    data class Failed(val cause: Throwable) : NativeCleanupOutcome
}

/**
 * Complete production native-owner inventory for recorder stop. The split lists mirror the only
 * ordering boundary: AudioRecord.stop must precede worker joins; every remaining owner releases
 * only after both drains have returned and muxer.stop has finalized the container.
 */
internal enum class RecorderNativeOwnerOperation {
    AUDIO_INPUT_STOP,
    AUDIO_INPUT_RELEASE,
    INPUT_SURFACE_RELEASE,
    VIDEO_CODEC_STOP,
    VIDEO_CODEC_RELEASE,
    AUDIO_CODEC_STOP,
    AUDIO_CODEC_RELEASE,
    MUXER_RELEASE,
    DESCRIPTOR_CLOSE,
}

internal val RECORDER_POST_DRAIN_PRE_MUXER_NATIVE_OWNERS = listOf(
    RecorderNativeOwnerOperation.AUDIO_INPUT_RELEASE,
)

internal val RECORDER_POST_DRAIN_NATIVE_OWNERS = listOf(
    RecorderNativeOwnerOperation.INPUT_SURFACE_RELEASE,
    RecorderNativeOwnerOperation.VIDEO_CODEC_STOP,
    RecorderNativeOwnerOperation.VIDEO_CODEC_RELEASE,
    RecorderNativeOwnerOperation.AUDIO_CODEC_STOP,
    RecorderNativeOwnerOperation.AUDIO_CODEC_RELEASE,
    RecorderNativeOwnerOperation.MUXER_RELEASE,
    RecorderNativeOwnerOperation.DESCRIPTOR_CLOSE,
)

/** Returns the first owner whose checked release failed; no later native phase is entered. */
internal fun runRecorderNativeOwnerSequence(
    owners: List<RecorderNativeOwnerOperation>,
    release: (RecorderNativeOwnerOperation) -> Boolean,
): RecorderNativeOwnerOperation? {
    for (owner in owners) if (!release(owner)) return owner
    return null
}

internal data class FrozenRecordingStorage<T>(
    val outputUri: T?,
    val muxerStarted: Boolean,
    val wroteVideoSample: Boolean,
    val failure: Throwable?,
    val finalizedValidation: FinalizedRecordingValidation,
)

/** Provider effects kept injectable so the real frozen tail can be integration-tested on host. */
internal data class RecordingStorageEffects<T>(
    val validateVideoTrack: (T) -> Boolean,
    val markComplete: (T) -> Boolean,
    val publish: (T) -> Boolean,
    val delete: (T) -> Unit,
)

internal fun <T> completeFrozenRecordingStorage(
    frozen: FrozenRecordingStorage<T>,
    effects: RecordingStorageEffects<T>,
): VideoRecorder.StopResult {
    var validation = frozen.finalizedValidation
    var failure = frozen.failure
    val outputUri = frozen.outputUri
    // The tolerated empty-audio-track stop exception is not proof of a finalized container.
    // This extractor/provider work deliberately happens only after every native owner is closed.
    if (validation == FinalizedRecordingValidation.SKIPPED) {
        validation = if (outputUri != null && effects.validateVideoTrack(outputUri)) {
            FinalizedRecordingValidation.PASSED
        } else {
            FinalizedRecordingValidation.FAILED
        }
        if (validation == FinalizedRecordingValidation.FAILED && failure == null) {
            failure = IllegalStateException("Finalized video track validation failed")
        }
    }

    val complete = shouldPublishRecording(
        muxerStarted = frozen.muxerStarted,
        wroteVideoSample = frozen.wroteVideoSample,
        hasFailure = failure != null,
        hasUri = outputUri != null,
        finalizedValidation = validation,
    )
    val publication = if (complete && outputUri != null) {
        // COMPLETE precedes publish. If its durable commit exhausts, fail closed: valid bytes stay
        // private for structural launch recovery and neither publish nor delete is authorized.
        publishCompletedOutput(
            markerDurable = runCatching { effects.markComplete(outputUri) }.getOrDefault(false),
            publish = { effects.publish(outputUri) },
        )
    } else {
        if (outputUri != null) effects.delete(outputUri)
        null
    }
    val storageDisposition = when (publication) {
        CompletedOutputPublication.PUBLISHED -> VideoRecorder.StorageDisposition.PUBLISHED
        CompletedOutputPublication.RETAINED_MARKER_UNAVAILABLE ->
            VideoRecorder.StorageDisposition.RETAINED_MARKER_UNAVAILABLE
        CompletedOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE ->
            VideoRecorder.StorageDisposition.RETAINED_PUBLICATION_UNAVAILABLE
        null -> VideoRecorder.StorageDisposition.NOT_APPLICABLE
    }
    return VideoRecorder.StopResult(
        saved = publication == CompletedOutputPublication.PUBLISHED,
        error = failure,
        storageDisposition = storageDisposition,
    )
}

internal class RecordingStorageTail(
    private val context: Context,
    private val frozen: FrozenRecordingStorage<Uri>,
) : me.hletrd.telecampro.camera.RecordingStorageCompletion {
    private var completed: VideoRecorder.StopResult? = null

    @Synchronized
    override fun complete(): VideoRecorder.StopResult {
        completed?.let { return it }
        return completeFrozenRecordingStorage(
            frozen,
            RecordingStorageEffects(
                validateVideoTrack = { MediaStoreWriter.hasReadableVideoTrack(context, it) },
                markComplete = {
                    val completion = MediaStoreWriter.markWriteComplete(context, it)
                    completion.durable
                },
                publish = {
                    MediaStoreWriter.publish(context, it).also { published ->
                        if (!published && me.hletrd.telecampro.BuildConfig.DEBUG) {
                            Log.w(
                                "VideoRecorder",
                                "publish() failed after retries; finalized file retained for recovery",
                            )
                        }
                    }
                },
                delete = { MediaStoreWriter.discardRejectedOutput(context, it) },
            ),
        ).also { completed = it }
    }
}

/** Keeps native-call success separate from the admission bit that may revoke an entered return. */
internal fun nativeCleanupOutcome(
    outcome: RecorderNativeOperationResult<Unit>,
): NativeCleanupOutcome = when (outcome) {
    RecorderNativeOperationResult.Rejected -> NativeCleanupOutcome.Revoked
    is RecorderNativeOperationResult.Returned -> when {
        !outcome.stillOpen -> NativeCleanupOutcome.Revoked
        outcome.result.isSuccess -> NativeCleanupOutcome.Completed
        else -> NativeCleanupOutcome.Failed(checkNotNull(outcome.result.exceptionOrNull()))
    }
}

internal enum class AudioWorkerLoopDisposition {
    CONTINUE,
    /** The recorder/finalizer retains sole authority to stop and release the AudioRecord. */
    EXIT_RETAIN_INPUT,
}

/** Degradation and quarantine end worker activity without creating a second native stop owner. */
internal fun audioWorkerLoopDisposition(
    terminallyQuarantined: Boolean,
    audioDegraded: Boolean,
): AudioWorkerLoopDisposition = if (terminallyQuarantined || audioDegraded) {
    AudioWorkerLoopDisposition.EXIT_RETAIN_INPUT
} else {
    AudioWorkerLoopDisposition.CONTINUE
}

/**
 * Recorder-local non-blocking close gate. A native call admitted before close may return because it
 * cannot be un-called, but that return is revoked and cannot authorize the next cleanup/setup phase.
 */
internal class RecorderNativeOperationGate {
    private val lock = ReentrantLock()
    private var open = true
    private var inFlight = 0

    fun <T> run(block: () -> T): RecorderNativeOperationResult<T> {
        val admitted = lock.withLock {
            if (!open) false else {
                inFlight++
                true
            }
        }
        if (!admitted) return RecorderNativeOperationResult.Rejected
        val result = runCatching(block)
        val stillOpen = lock.withLock {
            inFlight--
            check(inFlight >= 0) { "Recorder native-operation count underflow" }
            open
        }
        return RecorderNativeOperationResult.Returned(result, stillOpen)
    }

    /** Irreversible and non-blocking; never waits for the call whose hang motivated quarantine. */
    fun close(): Boolean = lock.withLock {
        if (!open) false else {
            open = false
            true
        }
    }

    fun isOpen(): Boolean = lock.withLock { open }
}

/**
 * Runs the real component/size attempt order with explicit rejected-owner cleanup. The generic
 * owner keeps the failure and release contract host-testable without mocking final MediaCodec APIs.
 */
internal fun <T> firstConfiguredEncoderAttempt(
    attempts: List<EncoderConfigureAttempt>,
    acquire: (EncoderSelection) -> T,
    configure: (T, EncoderConfigureAttempt) -> Unit,
    /** False means quarantine owns [T]; iteration must stop without another native action. */
    releaseRejected: (T) -> Boolean,
): ConfiguredEncoderAttempt<T> {
    var lastFailure: Throwable? = null
    for (attempt in attempts) {
        val owner = try {
            acquire(attempt.selection)
        } catch (revoked: RecorderNativeOperationRevokedException) {
            throw revoked
        } catch (failure: Throwable) {
            lastFailure = failure
            continue
        }
        val configured = try {
            configure(owner, attempt)
            true
        } catch (revoked: RecorderNativeOperationRevokedException) {
            throw revoked
        } catch (failure: Throwable) {
            lastFailure = failure
            false
        }
        if (configured) return ConfiguredEncoderAttempt(attempt, owner)
        if (!releaseRejected(owner)) throw RecorderNativeOperationRevokedException()
    }
    throw (lastFailure ?: IllegalStateException("no encoder size accepted"))
}

/** Preserve requested resolution first: try every exact component before spending a size rung. */
internal fun encoderConfigureAttempts(
    candidates: List<EncoderSelection>,
    width: Int,
    height: Int,
): List<EncoderConfigureAttempt> = encoderSizeLadder(width, height).flatMap { (w, h) ->
    candidates.distinctBy { it.codecName }.map { EncoderConfigureAttempt(it, w, h) }
}

internal enum class MuxerRendezvousTimeoutAction { DEGRADE_AUDIO, FAIL_VIDEO }

private const val VIDEO_STARTUP_AUDIO_GUARD_MS = 100L

/**
 * One absolute startup budget owns missing video. Optional audio receives its ordinary grace only
 * after video exists, capped just inside the video deadline so it can degrade without stealing the
 * encoded-video proof window.
 */
internal fun startupRendezvousWakeDeadlineNs(
    videoDeadlineNs: Long,
    audioDeadlineNs: Long,
    videoTrackReady: Boolean,
    expectedTracks: Int,
    audioTrackReady: Boolean,
): Long = if (videoTrackReady && expectedTracks > 1 && !audioTrackReady) {
    minOf(
        audioDeadlineNs,
        videoDeadlineNs - TimeUnit.MILLISECONDS.toNanos(VIDEO_STARTUP_AUDIO_GUARD_MS),
    )
} else {
    videoDeadlineNs
}

/** Fake-clock-friendly terminal decision for the unified recorder startup deadline. */
internal fun startupRendezvousTimeoutAction(
    nowNs: Long,
    videoDeadlineNs: Long,
    audioDeadlineNs: Long,
    videoTrackReady: Boolean,
    expectedTracks: Int,
    audioTrackReady: Boolean,
): MuxerRendezvousTimeoutAction? = when {
    !videoTrackReady && nowNs >= videoDeadlineNs -> MuxerRendezvousTimeoutAction.FAIL_VIDEO
    videoTrackReady && expectedTracks > 1 && !audioTrackReady && nowNs >=
        startupRendezvousWakeDeadlineNs(
            videoDeadlineNs,
            audioDeadlineNs,
            videoTrackReady,
            expectedTracks,
            audioTrackReady,
        ) -> MuxerRendezvousTimeoutAction.DEGRADE_AUDIO
    else -> null
}

/**
 * Independent recorder-output proof. EGL attach proves only that frames reached the codec input;
 * this state proves that the codec emitted a format and MediaMuxer accepted the first video sample.
 */
internal class VideoStartupProof {
    private var formatObserved = false
    private var muxedSampleObserved = false
    private var terminal = false

    @Synchronized
    fun observeFormat(): Boolean {
        if (terminal) return false
        formatObserved = true
        return muxedSampleObserved
    }

    @Synchronized
    fun observeMuxedSample(): Boolean {
        if (terminal) return false
        muxedSampleObserved = true
        return formatObserved
    }

    @Synchronized
    fun expire(): String? {
        if (terminal || (formatObserved && muxedSampleObserved)) return null
        terminal = true
        return if (!formatObserved) {
            "Video encoder produced no output format before startup deadline"
        } else {
            "Video encoder produced no muxed sample before startup deadline"
        }
    }
}

/** One-shot lifecycle of the recording's shared MediaMuxer native start. */
internal enum class MuxerStartState { WAITING, STARTED, TERMINAL }

/**
 * Immutable result of advancing [MuxerStartState]. A failure is carried only by the transition that
 * first made the state terminal, letting the recorder publish TERMINAL before notifying its
 * clip-level [FirstFailureSignal]. Subsequent calls are inert and therefore cannot notify or start
 * the same native owner twice.
 */
internal data class MuxerStartTransition(
    val state: MuxerStartState,
    val failure: Throwable? = null,
) {
    init {
        require(failure == null || state == MuxerStartState.TERMINAL)
    }
}

/** Result of one native start whose process/local terminal admission is checked atomically. */
internal enum class NativeStartOutcome { STARTED, REFUSED }

/**
 * Small Android-free leaf seam for worker-thread native starts.
 *
 * [isTerminal] executes inside [runNativeAcquisition], after process admission, so a local terminal
 * transition cannot be missed between the caller's outer check and [start]. A refusal deliberately
 * performs no cleanup: once quarantine owns the native graph, stop/release is itself unsafe. Native
 * start exceptions are not translated or swallowed; the caller retains its existing failure owner.
 */
internal fun startNativeOwnerIfSafe(
    runNativeAcquisition: ((() -> Unit) -> Boolean),
    isTerminal: () -> Boolean,
    start: () -> Unit,
): NativeStartOutcome {
    var started = false
    val admissionSurvived = runNativeAcquisition {
        if (isTerminal()) return@runNativeAcquisition
        start()
        started = true
    }
    return if (admissionSurvived && started) NativeStartOutcome.STARTED else NativeStartOutcome.REFUSED
}

/**
 * Advances a ready muxer exactly once. Success publishes STARTED; process/local terminal refusal
 * publishes TERMINAL without cleanup; a thrown native start is contained and carried on the one
 * transition to TERMINAL for the recorder's clip-level failure owner. WAITING that is not ready and
 * either completed state are inert.
 */
internal fun transitionMuxerStart(
    state: MuxerStartState,
    videoTrackReady: Boolean,
    expectedTracks: Int,
    audioTrackReady: Boolean,
    start: () -> NativeStartOutcome,
): MuxerStartTransition {
    if (state != MuxerStartState.WAITING ||
        !shouldStartMuxer(false, videoTrackReady, expectedTracks, audioTrackReady)
    ) {
        return MuxerStartTransition(state)
    }
    return try {
        when (start()) {
            NativeStartOutcome.STARTED -> MuxerStartTransition(MuxerStartState.STARTED)
            NativeStartOutcome.REFUSED -> MuxerStartTransition(MuxerStartState.TERMINAL)
        }
    } catch (failure: Exception) {
        MuxerStartTransition(MuxerStartState.TERMINAL, failure)
    }
}

/**
 * Pure gate for stop()'s muxer.stop() failure handling (TR4-2). A stop() throw normally means the
 * container was not finalized and the clip must be failed/deleted. The ONE tolerated combination is
 * a mid-REC audio degrade whose track never received a sample ([audioDegradedMidRec] true,
 * [wroteAudioSample] false) while the video track is complete ([wroteVideoSample] true) —
 * MediaMuxer.stop() may throw over the registered-but-empty audio track even though the video
 * track is playable. There the failure is NOT terminal: stop() proceeds to the publish gate, so a
 * dropped mic in the add-track→first-sample window cannot delete a clean take. Every other
 * combination (no video sample, no degrade, or audio samples actually muxed) stays terminal.
 */
/**
 * The stop() save gate, extracted pure (TEST4-5/P4.7): a recording is PUBLISHED only when the
 * muxer started, at least one video sample was muxed, no VIDEO-side failure latched, and the
 * pending uri still exists. A tolerated muxer-stop failure additionally requires PASSED structural
 * validation; FAILED or SKIPPED cannot publish. In particular a start immediately followed
 * by a stop (the same-executor-tick case the admission latch serializes) has no muxed video sample
 * yet, so the half-created pending file is DELETED, never published to the gallery.
 */
internal fun shouldPublishRecording(
    muxerStarted: Boolean,
    wroteVideoSample: Boolean,
    hasFailure: Boolean,
    hasUri: Boolean,
    finalizedValidation: FinalizedRecordingValidation = FinalizedRecordingValidation.NOT_REQUIRED,
): Boolean = muxerStarted &&
    wroteVideoSample &&
    !hasFailure &&
    hasUri &&
    (finalizedValidation == FinalizedRecordingValidation.NOT_REQUIRED ||
        finalizedValidation == FinalizedRecordingValidation.PASSED)

internal enum class FinalizedRecordingValidation { NOT_REQUIRED, PASSED, FAILED, SKIPPED }

enum class NativeGraphDisposition { RELEASED, QUARANTINE_REQUIRED }

/** A live drain makes every codec/muxer/fd owner unsafe to release or forget in this process. */
internal fun nativeGraphDispositionForDrainState(
    videoDrainAlive: Boolean,
    audioDrainAlive: Boolean,
): NativeGraphDisposition = if (videoDrainAlive || audioDrainAlive) {
    NativeGraphDisposition.QUARANTINE_REQUIRED
} else {
    NativeGraphDisposition.RELEASED
}

internal fun muxerStopFailureIsTerminal(
    wroteVideoSample: Boolean,
    audioDegradedMidRec: Boolean,
    wroteAudioSample: Boolean,
): Boolean = !(wroteVideoSample && audioDegradedMidRec && !wroteAudioSample)

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

/** Stable operator-facing route state for every audio setup degradation path. */
internal fun audioUnavailableLabel(preferenceLabel: String): String = "$preferenceLabel unavailable"

/**
 * Applies [gain] to every 16-bit PCM sample in `buf[0, byteCount)` IN PLACE (clamped to the
 * Short range so it can't wrap), then returns the post-gain RMS level normalized to 0..1.
 * The short view shares [buf]'s backing memory, so writes here are visible to the caller
 * before the buffer is queued to the encoder. Top-level (pure java.nio) so it is unit-testable.
 */
internal fun applyGainAndLevel(buf: ByteBuffer, byteCount: Int, gain: Float, channelCount: Int = 1): FloatArray {
    val safeGain = normalizeAudioGain(gain)
    val channels = channelCount.coerceAtLeast(1)
    val samples = buf.duplicate().apply {
        order(ByteOrder.LITTLE_ENDIAN)
        position(0)
        limit(byteCount)
    }.asShortBuffer()
    val count = samples.remaining()
    if (count == 0) return FloatArray(channels)
    // Per channel, not one number for the buffer: on a stereo or multi-capsule external mic a dead
    // channel is invisible behind a healthy one, and catching that is what an input meter is for.
    val sums = DoubleArray(channels)
    // Whole frames only, so a trailing partial frame cannot land in the wrong channel's accumulator.
    val frames = count / channels
    val usable = frames * channels
    if (safeGain == 1f) {
        // Unity gain (the default): the rewrite loop is a no-op transform — skip the per-sample
        // put() and only accumulate the RMS the level meter needs.
        for (i in 0 until usable) {
            val v = samples[i].toDouble()
            sums[i % channels] += v * v
        }
    } else {
        for (i in 0 until usable) {
            val amplified = (samples[i] * safeGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            samples.put(i, amplified)
            sums[i % channels] += amplified.toDouble() * amplified.toDouble()
        }
    }
    // The gain rewrite must still cover a trailing partial frame — it is real audio the encoder
    // gets — even though it is excluded from the metering average above.
    if (safeGain != 1f) {
        for (i in usable until count) {
            val amplified = (samples[i] * safeGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            samples.put(i, amplified)
        }
    }
    if (frames == 0) return FloatArray(channels)
    return FloatArray(channels) { c -> (sqrt(sums[c] / frames) / PCM_16_FULL_SCALE).toFloat().coerceIn(0f, 1f) }
}
