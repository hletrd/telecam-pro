package me.hletrd.telecampro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.hletrd.telecampro.video.AudioInputInspector
import me.hletrd.telecampro.video.AudioReadOutcome
import me.hletrd.telecampro.video.ColorProfiles
import me.hletrd.telecampro.video.NativeAcquisitionResult
import me.hletrd.telecampro.video.UnsafeRecorderQuarantine
import me.hletrd.telecampro.video.AudioLevelFrame
import me.hletrd.telecampro.video.accumulateChannelPeaks
import me.hletrd.telecampro.video.channelRms
import me.hletrd.telecampro.video.classifyAudioRead
import me.hletrd.telecampro.video.resolveAudioChannelCount
import me.hletrd.telecampro.video.standbyMeterShouldRecreate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal enum class StandbyAudioFailureReason {
    INVALID_BUFFER,
    CONSTRUCTION,
    UNINITIALIZED,
    START,
    THREAD_LAUNCH,
    RETRY_SCHEDULER,
    TERMINAL_READ,
}

internal data class StandbyAudioUnavailable(
    val reason: StandbyAudioFailureReason,
    val failedGenerations: Int,
)

internal interface StandbyAudioInput {
    /**
     * Interleaved channel count of [read]'s buffer; drives per-channel metering. Defaults to mono,
     * which is both the honest fallback for an input that does not report one and what keeps the
     * JVM suite's fakes describing exactly the shape they actually produce.
     */
    val channelCount: Int get() = 1
    fun start()
    fun read(samples: ShortArray): Int
    fun stop()
    fun release()
}

internal sealed interface StandbyAudioSetupResult {
    data class Ready(val input: StandbyAudioInput) : StandbyAudioSetupResult
    data class Failure(val reason: StandbyAudioFailureReason) : StandbyAudioSetupResult
}

internal fun interface StandbyAudioSetup {
    fun create(): StandbyAudioSetupResult
}

internal fun interface StandbyThreadLauncher {
    /** Returns true only when [task] was accepted for execution. */
    fun launch(name: String, task: () -> Unit): Boolean
}

internal fun interface StandbyRetryScheduler {
    /** Returns true only when [task] was accepted for delayed execution. */
    fun schedule(delayMs: Long, task: () -> Unit): Boolean
}

/** Dispatches a potentially blocking input-stop call away from lifecycle and REC callers. */
internal fun interface StandbyStopDispatcher {
    fun dispatch(task: () -> Unit)
}

/** Strong process-long owner for one standby input whose native lifetime became uncertain. */
internal data class QuarantinedStandbyInput(
    val input: StandbyAudioInput,
    val terminationOwner: StandbyInputTerminationOwner<StandbyAudioInput>,
)

/**
 * Exact-generation owner for the live standby input's stop/release boundary.
 *
 * A blocking [StandbyAudioInput.read] cannot observe the intent bit that ended its generation.
 * The controller therefore publishes this owner before start and lets disable/REC handoff request
 * `stop()` from a separate thread. Stop is exactly-once; release remains worker-owned and waits for
 * an already-dispatched stop to return, so it can never race the native stop call. Every action
 * captures this owner and its input, rather than consulting the controller's replaceable slot.
 */
internal class StandbyInputTerminationOwner<T : Any>(
    val generationId: Long,
    private val stopDispatcher: StandbyStopDispatcher,
    private val stop: (T) -> Unit,
    private val stopDeadlineScheduler: RecordingTeardownScheduler = processStandbyStopScheduler,
    private val stopTimeoutMs: Long = STANDBY_STOP_TIMEOUT_MS,
    private val onStopTimeout: (T, StandbyInputTerminationOwner<T>) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val stopCompleted = CountDownLatch(1)
    private var input: T? = null
    private var starting = false
    private var started = false
    private var stopRequested = false
    private var stopClaimed = false
    private var finished = false
    private var abandoned = false

    fun bind(value: T): Boolean = synchronized(lock) {
        if (finished || input != null) return false
        input = value
        true
    }

    /** Linearizes start against an earlier disable/handoff. */
    fun beginStart(value: T): Boolean = synchronized(lock) {
        if (finished || input !== value || stopRequested) return false
        starting = true
        true
    }

    /** Publishes start completion, then wakes a stop request that arrived during native start. */
    fun finishStart(value: T, succeeded: Boolean) {
        val stopNow = synchronized(lock) {
            if (input !== value || !starting) return
            starting = false
            started = succeeded
            claimAsyncStopLocked()
        }
        stopNow?.let(::dispatchStop)
    }

    /** Non-blocking caller edge used from main/lifecycle and the serial REC lane. */
    fun requestStop() {
        val stopNow = synchronized(lock) {
            stopRequested = true
            claimAsyncStopLocked()
        }
        stopNow?.let(::dispatchStop)
    }

    /**
     * Worker terminal edge. If no async stop owns the input, stop inline on the worker; otherwise
     * wait off-main until that exact stop returns. Release follows stop and happens exactly once.
     */
    fun finishAndRelease(value: T, release: (T) -> Unit) {
        val stopHere = synchronized(lock) {
            if (finished || abandoned || input !== value) return
            if (!stopClaimed) {
                stopClaimed = true
                true
            } else {
                false
            }
        }
        var interrupted = false
        if (stopHere) {
            stopAndSignal(value)
        } else {
            while (stopCompleted.count > 0L) {
                val completed = try {
                    stopCompleted.await(STOP_WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
                if (completed) break
                // A dispatch failure relinquishes the claim. The worker then becomes the safe
                // fallback owner; it never releases concurrently with an in-flight stop.
                val claimFallback = synchronized(lock) {
                    if (!stopClaimed && !finished && input === value) {
                        stopClaimed = true
                        true
                    } else {
                        false
                    }
                }
                if (claimFallback) {
                    stopAndSignal(value)
                    break
                }
            }
        }
        val releaseNow = synchronized(lock) {
            if (abandoned || input !== value) {
                finished = true
                false
            } else {
                input = null
                finished = true
                started = false
                starting = false
                true
            }
        }
        if (releaseNow) runCatching { release(value) }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Retains native ownership without stop/release. Logical waiters are released by the controller's
     * quarantine callback; a worker or stop task that returns later observes [abandoned] and is inert.
     */
    fun abandon(value: T): Boolean = synchronized(lock) {
        if (finished || abandoned || input !== value) return false
        abandoned = true
        started = false
        starting = false
        stopCompleted.countDown()
        true
    }

    internal fun isAbandoned(): Boolean = synchronized(lock) { abandoned }

    private fun claimAsyncStopLocked(): T? {
        if (!stopRequested || !started || starting || stopClaimed || finished) return null
        val value = input ?: return null
        stopClaimed = true
        return value
    }

    private fun dispatchStop(value: T) {
        runCatching {
            stopDispatcher.dispatch { stopAndSignal(value) }
        }.onFailure {
            synchronized(lock) {
                if (!finished && input === value && stopCompleted.count > 0L) stopClaimed = false
            }
        }
    }

    private fun stopAndSignal(value: T) {
        val deadline = RecordingOperationDeadline(
            scheduler = stopDeadlineScheduler,
            timeoutMs = stopTimeoutMs,
            failure = { java.util.concurrent.TimeoutException("Standby AudioRecord.stop timed out") },
            onTimeout = {
                if (abandon(value)) onStopTimeout(value, this)
            },
        )
        if (!deadline.arm()) return
        runCatching { stop(value) }
        // A timeout owns abandonment and already released logical stop waiters. A late return must
        // not overwrite that terminal or authorize release of the retained native input.
        if (deadline.complete()) stopCompleted.countDown()
    }

    private companion object {
        const val STOP_WAIT_SLICE_MS = 25L
    }
}

private const val STANDBY_STOP_TIMEOUT_MS = 1_500L

/** One process daemon; every termination owner still owns an independent first-wins deadline. */
private val processStandbyStopScheduler: RecordingTeardownScheduler by lazy {
    val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "StandbyAudioStopDeadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    RecordingTeardownScheduler { delayMs, action ->
        runCatching {
            executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()?.let { future ->
            RecordingTeardownCancellation { future.cancel(false) }
        }
    }
}

/**
 * Last-resort async retry lane for a rejected main-loop post. It is intentionally separate from
 * AudioRecord generation accounting: process ownership contention is not an audio-input failure.
 */
private fun threadBackedStandbyRetryScheduler(): StandbyRetryScheduler =
    StandbyRetryScheduler { delayMs, task ->
        runCatching {
            Thread(
                {
                    try {
                        Thread.sleep(delayMs)
                        runCatching(task)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
                "StandbyAudioRetryFallback",
            ).start()
        }.isSuccess
    }

private class AndroidStandbyAudioInput(
    private val recorder: AudioRecord,
    override val channelCount: Int,
) : StandbyAudioInput {
    override fun start() = recorder.startRecording()
    override fun read(samples: ShortArray): Int = recorder.read(samples, 0, samples.size)
    override fun stop() = recorder.stop()
    override fun release() = recorder.release()
}

// The controller checks RECORD_AUDIO immediately before reserving every generation. Extraction into
// this injectable factory hides that dominating guard from lint, so keep the suppression local.
@SuppressLint("MissingPermission")
private fun createAndroidStandbyAudioInput(
    context: Context,
    preference: AudioInputPreference,
): StandbyAudioSetupResult {
    val sampleRate = 48_000
    // The meter must listen to the SAME microphone the next take will record from. Before this it
    // hardcoded MONO on the default route, so selecting a USB/BT/wired mic changed the recording
    // but left the armed meter reading the phone's own capsule — the operator could not check the
    // external mic was live until after a take (2026-08-02).
    val device = runCatching { AudioInputInspector.preferredDevice(context, preference) }.getOrNull()
    val channelCount = resolveAudioChannelCount(
        device?.channelCounts,
        device != null && AudioInputInspector.isBluetoothInput(device.type),
    )
    val channelMask =
        if (channelCount >= ColorProfiles.AUDIO_CHANNELS) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    val minBuffer = runCatching {
        AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    }.getOrNull()
    if (minBuffer == null || minBuffer <= 0) {
        return StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.INVALID_BUFFER)
    }
    val recorder = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
    }.getOrNull() ?: return StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.CONSTRUCTION)
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        runCatching { recorder.release() }
        return StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.UNINITIALIZED)
    }
    // Advisory, exactly as VideoRecorder treats it: a refused route still meters (on the default
    // device), and the Route row already tells the operator which input actually resolved. Failing
    // the meter outright would be a worse answer than a working meter on a different mic.
    if (device != null) runCatching { recorder.setPreferredDevice(device) }
    if (me.hletrd.telecampro.BuildConfig.DEBUG) {
        // ONE line per AudioRecord generation, not per read: ColorOS drops everything past a
        // 300-row per-process quota, so a per-buffer line would eat the traces that matter.
        Log.i(
            "StandbyAudioMeter",
            "standby format: pref=${preference.name} device=${device?.type ?: -1} " +
                "channels=$channelCount routed=${runCatching { recorder.routedDevice?.type }.getOrNull()}",
        )
    }
    return StandbyAudioSetupResult.Ready(AndroidStandbyAudioInput(recorder, channelCount))
}

/**
 * Sony-style pre-roll level meter used while video mode is armed but not recording.
 *
 * Every engine-state dependency is a live lambda: this controller is entered from the main,
 * recorder, and meter threads, so captured snapshots would let a stale meter race recorder
 * ownership. [StandbyMeterOwnership] remains the single admission authority.
 */
internal class StandbyAudioController(
    private val audioGain: () -> Float,
    private val onLevels: (AudioLevelFrame) -> Unit,
    private val canStart: () -> Boolean,
    private val recorderAbsent: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val audioSetup: StandbyAudioSetup,
    private val threadLauncher: StandbyThreadLauncher,
    private val retryScheduler: StandbyRetryScheduler,
    private val processBusyRetryFallback: StandbyRetryScheduler = threadBackedStandbyRetryScheduler(),
    private val stopDispatcher: StandbyStopDispatcher = StandbyStopDispatcher { task ->
        Thread(task, "StandbyAudioStop").apply { isDaemon = true }.start()
    },
    private val stopDeadlineScheduler: RecordingTeardownScheduler = processStandbyStopScheduler,
    private val stopTimeoutMs: Long = STANDBY_STOP_TIMEOUT_MS,
    private val onAvailable: () -> Unit,
    private val onUnavailable: (StandbyAudioUnavailable) -> Unit,
    private val onUnsafeNative: () -> Unit = {},
    private val reserveProcessAdmission: () -> (() -> Unit)? = { {} },
    private val runNativeAcquisition: ((() -> Unit) -> NativeAcquisitionResult) = { block ->
        block()
        NativeAcquisitionResult.RETURNED_CURRENT
    },
    private val retainQuarantinedInput: (QuarantinedStandbyInput) -> Unit = {},
) {
    internal constructor(
        context: Context,
        audioGain: () -> Float,
        onLevels: (AudioLevelFrame) -> Unit,
        /**
         * Read LIVE, never captured: the operator can change the input while video is armed, and
         * each AudioRecord generation must resolve the choice that is current when it opens.
         */
        audioInputPreference: () -> AudioInputPreference,
        canStart: () -> Boolean,
        recorderAbsent: () -> Boolean,
        isPaused: () -> Boolean,
        processOwner: Any,
        onAvailable: () -> Unit = {},
        onUnavailable: (StandbyAudioUnavailable) -> Unit = { unavailable ->
            Log.w(
                TAG,
                "Standby microphone unavailable after ${unavailable.failedGenerations} " +
                "failed generations (${unavailable.reason})",
            )
        },
        onUnsafeNative: () -> Unit = {},
    ) : this(
        audioGain = audioGain,
        onLevels = onLevels,
        canStart = canStart,
        recorderAbsent = recorderAbsent,
        isPaused = isPaused,
        permissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
        audioSetup = StandbyAudioSetup { createAndroidStandbyAudioInput(context, audioInputPreference()) },
        threadLauncher = StandbyThreadLauncher { name, task ->
            runCatching { Thread({ task() }, name).start() }.isSuccess
        },
        retryScheduler = StandbyRetryScheduler { delayMs, task ->
            Handler(Looper.getMainLooper()).postDelayed({ task() }, delayMs)
        },
        onAvailable = onAvailable,
        onUnavailable = onUnavailable,
        onUnsafeNative = onUnsafeNative,
        reserveProcessAdmission = {
            UnsafeRecorderQuarantine.reserveStandbyAdmission(processOwner)?.let { admission ->
                { UnsafeRecorderQuarantine.finishStandbyAdmission(admission) }
            }
        },
        runNativeAcquisition = UnsafeRecorderQuarantine::runNativeAcquisitionWithResult,
        retainQuarantinedInput = { owner ->
            UnsafeRecorderQuarantine.quarantineNativeGraph(owner)
        },
    )

    private val ownership = StandbyMeterOwnership<CountDownLatch>()
    private val liveInputTermination = AtomicReference<StandbyInputTerminationOwner<StandbyAudioInput>?>(null)

    // Consecutive AudioRecord generations that failed setup/start/launch or reached a terminal read
    // without one PCM read. Explicit user intent and a successful PCM read reset the shared budget.
    private val failureStreak = AtomicInteger(0)
    private val nativeTerminal = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            disable()
            return
        }
        // Explicit intent gets a fresh bounded recreation budget.
        failureStreak.set(0)
        start(updateIntent = true)
    }

    fun beginRecording(): StandbyMeterOwnership.RecordingClaim<CountDownLatch> =
        ownership.beginRecording().also { claim ->
            if (claim.admitted) liveInputTermination.get()?.requestStop()
        }

    fun abortRecording() {
        ownership.abortRecording()
        if (!isPaused()) start(updateIntent = false)
    }

    fun finishRecording() {
        ownership.finishRecording()
        if (!isPaused()) start(updateIntent = false)
    }

    fun disable() {
        ownership.disable()
        liveInputTermination.get()?.requestStop()
    }

    /** Starts only if the latest intent still wants metering; internal retries never re-enable it. */
    private fun start(updateIntent: Boolean) {
        val admittedNow = !nativeTerminal.get() && canStart() && permissionGranted()
        // The immutable owner and release latch are published before Thread.start. REC can therefore
        // await that exact generation and never admits a second AudioRecord after a timeout.
        val createRelease = { CountDownLatch(1) }
        val owner = if (updateIntent) {
            ownership.reserve(
                enabled = true,
                canStart = admittedNow,
                createRelease = createRelease,
            )
        } else {
            ownership.reserveCurrentWanted(
                canStart = admittedNow,
                createRelease = createRelease,
            )
        } ?: return
        val terminationOwner = StandbyInputTerminationOwner<StandbyAudioInput>(
            generationId = owner.id,
            stopDispatcher = stopDispatcher,
            stop = StandbyAudioInput::stop,
            stopDeadlineScheduler = stopDeadlineScheduler,
            stopTimeoutMs = stopTimeoutMs,
            onStopTimeout = { input, exactOwner ->
                retainUnsafeInput(input, exactOwner, owner.release)
            },
        )
        check(liveInputTermination.compareAndSet(null, terminationOwner)) {
            "standby input generation overlap"
        }
        val meterTask: () -> Unit = meterTask@{
            var audioInput: StandbyAudioInput? = null
            var releaseProcessAdmission: (() -> Unit)? = null
            var processBusy = false
            // One PCM read resets the shared dead-route/setup budget.
            var sawPcm = false
            var generationFailure: StandbyAudioFailureReason? = null
            try {
                // Reservation is not start admission: REC can claim while this thread is queued.
                if (!ownership.ownsAndWants(owner) || !canStart()) return@meterTask
                releaseProcessAdmission = reserveProcessAdmission()
                if (releaseProcessAdmission == null) {
                    processBusy = true
                    return@meterTask
                }
                var setup: StandbyAudioSetupResult? = null
                when (runNativeAcquisition {
                    setup = runCatching { audioSetup.create() }.getOrElse {
                        StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.CONSTRUCTION)
                    }
                }) {
                    NativeAcquisitionResult.REJECTED -> return@meterTask
                    NativeAcquisitionResult.RETURNED_REVOKED -> {
                        val revokedInput = (setup as? StandbyAudioSetupResult.Ready)?.input
                            ?: return@meterTask
                        audioInput = revokedInput
                        check(terminationOwner.bind(revokedInput)) {
                            "standby input owner rejected revoked setup generation"
                        }
                        check(terminationOwner.abandon(revokedInput)) {
                            "standby input owner could not abandon revoked setup generation"
                        }
                        retainUnsafeInput(revokedInput, terminationOwner, owner.release)
                        return@meterTask
                    }
                    NativeAcquisitionResult.RETURNED_CURRENT -> Unit
                }
                when (val result = checkNotNull(setup)) {
                    is StandbyAudioSetupResult.Failure -> {
                        generationFailure = result.reason
                        return@meterTask
                    }
                    is StandbyAudioSetupResult.Ready -> {
                        audioInput = result.input
                        check(terminationOwner.bind(result.input)) {
                            "standby input owner rejected its generation"
                        }
                    }
                }
                if (!ownership.ownsAndWants(owner) || !canStart()) return@meterTask
                var startFailed = false
                val input = checkNotNull(audioInput)
                if (!terminationOwner.beginStart(input)) return@meterTask
                var startResult = NativeAcquisitionResult.REJECTED
                try {
                    startResult = runNativeAcquisition {
                        startFailed = runCatching { input.start() }.isFailure
                    }
                } finally {
                    terminationOwner.finishStart(
                        value = input,
                        succeeded = startResult == NativeAcquisitionResult.RETURNED_CURRENT && !startFailed,
                    )
                }
                if (startResult == NativeAcquisitionResult.RETURNED_REVOKED) {
                    check(terminationOwner.abandon(input)) {
                        "standby input owner could not abandon revoked start generation"
                    }
                    retainUnsafeInput(input, terminationOwner, owner.release)
                    return@meterTask
                }
                if (startResult == NativeAcquisitionResult.REJECTED) return@meterTask
                if (startFailed) {
                    generationFailure = StandbyAudioFailureReason.START
                    return@meterTask
                }
                if (!ownership.ownsAndWants(owner) || !canStart()) return@meterTask
                val samples = ShortArray(2048)
                val heldPeaks = FloatArray(input.channelCount.coerceAtLeast(1))
                var lastEmit = 0L
                while (ownership.ownsAndWants(owner) && recorderAbsent() && canStart()) {
                    val readCount = runCatching { checkNotNull(audioInput).read(samples) }.getOrElse {
                        generationFailure = StandbyAudioFailureReason.TERMINAL_READ
                        break
                    }
                    // Classify against ownership observed after the blocking read. A negative wake-up
                    // caused by REC handoff is Stopped, not a dead-route failure.
                    val stillWanted = ownership.ownsAndWants(owner)
                    when (classifyAudioRead(readCount, running = stillWanted)) {
                        is AudioReadOutcome.Pcm -> if (!sawPcm) {
                            sawPcm = true
                            failureStreak.set(0)
                            runCatching(onAvailable)
                        }
                        AudioReadOutcome.Retry -> continue
                        is AudioReadOutcome.Failure -> {
                            generationFailure = StandbyAudioFailureReason.TERMINAL_READ
                            break
                        }
                        AudioReadOutcome.Stopped -> break
                    }
                    accumulateChannelPeaks(
                        samples,
                        readCount,
                        input.channelCount,
                        audioGain(),
                        heldPeaks,
                    )
                    val now = System.nanoTime()
                    if (now - lastEmit < METER_EMIT_INTERVAL_NS) continue
                    lastEmit = now
                    // Per channel, and on the same signed-16-bit full scale VideoRecorder uses, so
                    // the meter does not jump at the standby -> REC handoff.
                    onLevels(
                        AudioLevelFrame(
                            rms = channelRms(
                            samples,
                            readCount,
                            input.channelCount,
                            audioGain(),
                        ),
                            peaks = heldPeaks.copyOf(),
                        ),
                    )
                    heldPeaks.fill(0f)
                }
            } finally {
                // Count the latch only after release on every path, including early returns.
                audioInput?.let { input -> terminationOwner.finishAndRelease(input, StandbyAudioInput::release) }
                liveInputTermination.compareAndSet(terminationOwner, null)
                runCatching { releaseProcessAdmission?.invoke() }
                completeGeneration(owner, generationFailure, retryForProcessBusy = processBusy)
            }
        }
        val launched = runCatching {
            threadLauncher.launch("StandbyAudioMeter", meterTask)
        }.getOrDefault(false)
        if (!launched) {
            liveInputTermination.compareAndSet(terminationOwner, null)
            completeGeneration(owner, StandbyAudioFailureReason.THREAD_LAUNCH)
        }
    }

    /** One terminal path shared by revoked create/start and a timed-out native stop. */
    private fun retainUnsafeInput(
        input: StandbyAudioInput,
        terminationOwner: StandbyInputTerminationOwner<StandbyAudioInput>,
        logicalRelease: CountDownLatch,
    ) {
        nativeTerminal.set(true)
        runCatching {
            retainQuarantinedInput(QuarantinedStandbyInput(input, terminationOwner))
        }
        // Logical waiters may proceed only far enough to observe process quarantine and refuse REC.
        // The concrete native input remains strongly retained and is never stopped/released here.
        logicalRelease.countDown()
        runCatching(onUnsafeNative)
    }

    private fun completeGeneration(
        owner: StandbyMeterOwnership.Owner<CountDownLatch>,
        failure: StandbyAudioFailureReason?,
        retryForProcessBusy: Boolean = false,
    ) {
        val completion = ownership.complete(owner)
        owner.release.countDown()
        runCatching { onLevels(AudioLevelFrame.EMPTY) }
        if (!completion.completed) return
        if (retryForProcessBusy) {
            scheduleProcessBusyRetry()
            return
        }
        if (completion.retryPending) {
            if (!isPaused()) start(updateIntent = false)
            return
        }
        if (failure == null) return

        val failedGenerations = failureStreak.incrementAndGet()
        if (isPaused() || !ownership.meterWanted()) return
        if (standbyMeterShouldRecreate(failedGenerations, MAX_RECREATES)) {
            // The owner latch is already released. A delayed callback rechecks wanted/paused/REC
            // state through reserveCurrentWanted, so it cannot steal the mic from a newer handoff.
            val scheduled = runCatching {
                retryScheduler.schedule(RETRY_BACKOFF_MS) {
                    if (!isPaused()) start(updateIntent = false)
                }
            }.getOrDefault(false)
            // A rejected scheduler must not make a transient failure sticky. Consume the same finite
            // generation budget synchronously; MAX_RECREATES bounds this fallback recursion.
            if (!scheduled && !isPaused() && ownership.meterWanted()) start(updateIntent = false)
            return
        }
        // Transient generations stay invisible; only terminal budget exhaustion is reported.
        if (ownership.meterWanted() && !isPaused()) {
            runCatching { onUnavailable(StandbyAudioUnavailable(failure, failedGenerations)) }
        }
    }

    /**
     * Waits quietly for a foreign process mic lease without charging [failureStreak]. A rejected or
     * throwing main-loop post moves once to an independent async fallback, preventing both a dead
     * wanted state and synchronous retry recursion. Every callback rechecks pause, wanted intent,
     * and recording ownership through [start]'s current-owner reservation.
     */
    private fun scheduleProcessBusyRetry() {
        if (isPaused() || !ownership.meterWanted()) return
        val retry = {
            if (!isPaused() && ownership.meterWanted()) start(updateIntent = false)
        }
        val scheduled = runCatching {
            retryScheduler.schedule(RETRY_BACKOFF_MS, retry)
        }.getOrDefault(false)
        if (scheduled) return

        val fallbackScheduled = runCatching {
            processBusyRetryFallback.schedule(RETRY_BACKOFF_MS, retry)
        }.getOrDefault(false)
        if (!fallbackScheduled && !isPaused() && ownership.meterWanted()) {
            // No executor can make progress. Surface an explicit infrastructure state rather than
            // silently leaving wanted=true with no owner or task; do not mutate the audio budget.
            runCatching {
                onUnavailable(
                    StandbyAudioUnavailable(
                        StandbyAudioFailureReason.RETRY_SCHEDULER,
                        failedGenerations = failureStreak.get(),
                    ),
                )
            }
        }
    }

    private companion object {
        private const val TAG = "StandbyAudioMeter"
        private const val MAX_RECREATES = 3
        private const val RETRY_BACKOFF_MS = 300L
        private const val METER_EMIT_INTERVAL_NS = 100_000_000L
    }
}

/**
 * Single-owner admission for the standby AudioRecord and the recording handoff. The release object
 * is generic so the JVM suite can prove ownership without Android audio classes.
 */
internal class StandbyMeterOwnership<R> {
    data class Owner<R>(val id: Long, val release: R)
    data class RecordingClaim<R>(val admitted: Boolean, val release: R?)
    data class Completion(val completed: Boolean, val retryPending: Boolean)

    private var nextId = 0L
    private var wanted = false
    private var active: Owner<R>? = null
    private var recordingClaimed = false
    private var restoreWantedOnAbort = false
    private var wantedChangedSinceClaim = false
    private var restartAfterActive = false

    @Synchronized
    fun reserve(enabled: Boolean, canStart: Boolean, createRelease: () -> R): Owner<R>? {
        if (recordingClaimed) wantedChangedSinceClaim = true
        wanted = enabled
        return reserveWantedLocked(canStart, createRelease)
    }

    /** Internal restart path: observes current intent without changing it. */
    @Synchronized
    fun reserveCurrentWanted(canStart: Boolean, createRelease: () -> R): Owner<R>? =
        reserveWantedLocked(canStart, createRelease)

    private fun reserveWantedLocked(canStart: Boolean, createRelease: () -> R): Owner<R>? {
        if (!wanted || !canStart || recordingClaimed) return null
        if (active != null) {
            restartAfterActive = true
            return null
        }
        restartAfterActive = false
        return Owner(++nextId, createRelease()).also { active = it }
    }

    @Synchronized
    fun disable(): R? {
        if (recordingClaimed) wantedChangedSinceClaim = true
        wanted = false
        restartAfterActive = false
        return active?.release
    }

    @Synchronized
    fun ownsAndWants(owner: Owner<R>): Boolean = wanted && active?.id == owner.id

    /** True only while standby intent still exists outside a recording claim. */
    @Synchronized
    fun meterWanted(): Boolean = wanted && !recordingClaimed

    @Synchronized
    fun complete(owner: Owner<R>): Completion {
        if (active?.id != owner.id) return Completion(completed = false, retryPending = false)
        active = null
        val retryPending = restartAfterActive && wanted && !recordingClaimed
        restartAfterActive = false
        return Completion(completed = true, retryPending = retryPending)
    }

    /** Claims the recording transition before any recorder object exists, blocking new meters. */
    @Synchronized
    fun beginRecording(): RecordingClaim<R> {
        if (recordingClaimed) return RecordingClaim(admitted = false, release = null)
        recordingClaimed = true
        restoreWantedOnAbort = wanted
        wantedChangedSinceClaim = false
        wanted = false
        restartAfterActive = false
        return RecordingClaim(admitted = true, release = active?.release)
    }

    @Synchronized
    fun abortRecording() {
        if (!wantedChangedSinceClaim) wanted = restoreWantedOnAbort
        recordingClaimed = false
        restoreWantedOnAbort = false
        wantedChangedSinceClaim = false
    }

    /** Releases recorder admission after its AudioRecord teardown; intent is rechecked separately. */
    @Synchronized
    fun finishRecording() {
        recordingClaimed = false
        restoreWantedOnAbort = false
        wantedChangedSinceClaim = false
    }
}
