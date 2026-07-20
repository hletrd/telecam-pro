package com.hletrd.findx9tele.camera

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
import com.hletrd.findx9tele.video.AudioReadOutcome
import com.hletrd.findx9tele.video.UnsafeRecorderQuarantine
import com.hletrd.findx9tele.video.classifyAudioRead
import com.hletrd.findx9tele.video.standbyMeterShouldRecreate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

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

private class AndroidStandbyAudioInput(private val recorder: AudioRecord) : StandbyAudioInput {
    override fun start() = recorder.startRecording()
    override fun read(samples: ShortArray): Int = recorder.read(samples, 0, samples.size)
    override fun stop() = recorder.stop()
    override fun release() = recorder.release()
}

// The controller checks RECORD_AUDIO immediately before reserving every generation. Extraction into
// this injectable factory hides that dominating guard from lint, so keep the suppression local.
@SuppressLint("MissingPermission")
private fun createAndroidStandbyAudioInput(): StandbyAudioSetupResult {
    val sampleRate = 48_000
    val minBuffer = runCatching {
        AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    }.getOrNull()
    if (minBuffer == null || minBuffer <= 0) {
        return StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.INVALID_BUFFER)
    }
    val recorder = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
    }.getOrNull() ?: return StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.CONSTRUCTION)
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        runCatching { recorder.release() }
        return StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.UNINITIALIZED)
    }
    return StandbyAudioSetupResult.Ready(AndroidStandbyAudioInput(recorder))
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
    private val onLevel: (Float) -> Unit,
    private val canStart: () -> Boolean,
    private val recorderAbsent: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val audioSetup: StandbyAudioSetup,
    private val threadLauncher: StandbyThreadLauncher,
    private val retryScheduler: StandbyRetryScheduler,
    private val processBusyRetryFallback: StandbyRetryScheduler = threadBackedStandbyRetryScheduler(),
    private val onAvailable: () -> Unit,
    private val onUnavailable: (StandbyAudioUnavailable) -> Unit,
    private val reserveProcessAdmission: () -> (() -> Unit)? = { {} },
    private val runNativeAcquisition: ((() -> Unit) -> Boolean) = { block -> block(); true },
) {
    internal constructor(
        context: Context,
        audioGain: () -> Float,
        onLevel: (Float) -> Unit,
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
    ) : this(
        audioGain = audioGain,
        onLevel = onLevel,
        canStart = canStart,
        recorderAbsent = recorderAbsent,
        isPaused = isPaused,
        permissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
        audioSetup = StandbyAudioSetup(::createAndroidStandbyAudioInput),
        threadLauncher = StandbyThreadLauncher { name, task ->
            runCatching { Thread({ task() }, name).start() }.isSuccess
        },
        retryScheduler = StandbyRetryScheduler { delayMs, task ->
            Handler(Looper.getMainLooper()).postDelayed({ task() }, delayMs)
        },
        onAvailable = onAvailable,
        onUnavailable = onUnavailable,
        reserveProcessAdmission = {
            UnsafeRecorderQuarantine.reserveStandbyAdmission(processOwner)?.let { admission ->
                { UnsafeRecorderQuarantine.finishStandbyAdmission(admission) }
            }
        },
        runNativeAcquisition = UnsafeRecorderQuarantine::runNativeAcquisition,
    )

    private val ownership = StandbyMeterOwnership<CountDownLatch>()

    // Consecutive AudioRecord generations that failed setup/start/launch or reached a terminal read
    // without one PCM read. Explicit user intent and a successful PCM read reset the shared budget.
    private val failureStreak = AtomicInteger(0)

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            ownership.disable()
            return
        }
        // Explicit intent gets a fresh bounded recreation budget.
        failureStreak.set(0)
        start(updateIntent = true)
    }

    fun beginRecording(): StandbyMeterOwnership.RecordingClaim<CountDownLatch> =
        ownership.beginRecording()

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
    }

    /** Starts only if the latest intent still wants metering; internal retries never re-enable it. */
    private fun start(updateIntent: Boolean) {
        val admittedNow = canStart() && permissionGranted()
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
                val setupAdmitted = runNativeAcquisition {
                    setup = runCatching { audioSetup.create() }.getOrElse {
                        StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.CONSTRUCTION)
                    }
                }
                if (!setupAdmitted) return@meterTask
                when (val result = checkNotNull(setup)) {
                    is StandbyAudioSetupResult.Failure -> {
                        generationFailure = result.reason
                        return@meterTask
                    }
                    is StandbyAudioSetupResult.Ready -> audioInput = result.input
                }
                if (!ownership.ownsAndWants(owner) || !canStart()) return@meterTask
                var startFailed = false
                val startAdmitted = runNativeAcquisition {
                    startFailed = runCatching { checkNotNull(audioInput).start() }.isFailure
                }
                if (!startAdmitted) return@meterTask
                if (startFailed) {
                    generationFailure = StandbyAudioFailureReason.START
                    return@meterTask
                }
                if (!ownership.ownsAndWants(owner) || !canStart()) return@meterTask
                val samples = ShortArray(2048)
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
                    val now = System.nanoTime()
                    if (now - lastEmit < METER_EMIT_INTERVAL_NS) continue
                    lastEmit = now
                    var sum = 0.0
                    for (index in 0 until readCount) {
                        val value = samples[index].toDouble()
                        sum += value * value
                    }
                    // Same signed-16-bit full scale as VideoRecorder: no handoff jump in the meter.
                    val rms = sqrt(sum / readCount) / PCM_16_FULL_SCALE
                    onLevel((rms * audioGain()).toFloat().coerceIn(0f, 1f))
                }
            } finally {
                // Count the latch only after release on every path, including early returns.
                audioInput?.let { input ->
                    runCatching { input.stop() }
                    runCatching { input.release() }
                }
                runCatching { releaseProcessAdmission?.invoke() }
                completeGeneration(owner, generationFailure, retryForProcessBusy = processBusy)
            }
        }
        val launched = runCatching {
            threadLauncher.launch("StandbyAudioMeter", meterTask)
        }.getOrDefault(false)
        if (!launched) {
            completeGeneration(owner, StandbyAudioFailureReason.THREAD_LAUNCH)
        }
    }

    private fun completeGeneration(
        owner: StandbyMeterOwnership.Owner<CountDownLatch>,
        failure: StandbyAudioFailureReason?,
        retryForProcessBusy: Boolean = false,
    ) {
        val completion = ownership.complete(owner)
        owner.release.countDown()
        runCatching { onLevel(0f) }
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
        private const val PCM_16_FULL_SCALE = 32768.0
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
