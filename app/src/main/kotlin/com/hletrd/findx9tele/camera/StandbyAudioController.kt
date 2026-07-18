package com.hletrd.findx9tele.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.hletrd.findx9tele.video.AudioReadOutcome
import com.hletrd.findx9tele.video.classifyAudioRead
import com.hletrd.findx9tele.video.standbyMeterShouldRecreate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Sony-style pre-roll level meter used while video mode is armed but not recording.
 *
 * Every engine-state dependency is a live lambda: this controller is entered from the main,
 * recorder, and meter threads, so captured snapshots would let a stale meter race recorder
 * ownership. [StandbyMeterOwnership] remains the single admission authority.
 */
internal class StandbyAudioController(
    private val context: Context,
    private val audioGain: () -> Float,
    private val onLevel: (Float) -> Unit,
    private val canStart: () -> Boolean,
    private val recorderAbsent: () -> Boolean,
    private val isPaused: () -> Boolean,
) {
    private val ownership = StandbyMeterOwnership<CountDownLatch>()

    // Consecutive AudioRecord generations that died on a terminal read error without one PCM read.
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
        val admittedNow = canStart() &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
        val thread = Thread({
            var audioRecord: AudioRecord? = null
            // One PCM read resets the dead-route budget; only a terminal running read charges it.
            var sawPcm = false
            var terminalReadError = false
            try {
                // Reservation is not start admission: REC can claim while this thread is queued.
                if (!ownership.ownsAndWants(owner)) return@Thread
                val sampleRate = 48_000
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) return@Thread
                val recorder = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.CAMCORDER,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer * 2,
                    )
                }.getOrNull() ?: return@Thread
                audioRecord = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@Thread
                if (!ownership.ownsAndWants(owner)) return@Thread
                if (runCatching { recorder.startRecording() }.isFailure) return@Thread
                val samples = ShortArray(2048)
                var lastEmit = 0L
                while (ownership.ownsAndWants(owner) && recorderAbsent()) {
                    val readCount = recorder.read(samples, 0, samples.size)
                    // Classify against ownership observed after the blocking read. A negative wake-up
                    // caused by REC handoff is Stopped, not a dead-route failure.
                    val stillWanted = ownership.ownsAndWants(owner)
                    when (classifyAudioRead(readCount, running = stillWanted)) {
                        is AudioReadOutcome.Pcm -> if (!sawPcm) {
                            sawPcm = true
                            failureStreak.set(0)
                        }
                        AudioReadOutcome.Retry -> continue
                        is AudioReadOutcome.Failure -> {
                            terminalReadError = true
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
                audioRecord?.let { recorder ->
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                }
                val completion = ownership.complete(owner)
                owner.release.countDown()
                runCatching { onLevel(0f) }
                if (completion.retryPending && !isPaused()) {
                    start(updateIntent = false)
                } else if (terminalReadError && !isPaused() &&
                    standbyMeterShouldRecreate(
                        failedGenerations = failureStreak.incrementAndGet(),
                        maxRecreates = MAX_RECREATES,
                    )
                ) {
                    // The latch is already released, so this backoff cannot delay REC handoff.
                    runCatching { Thread.sleep(RETRY_BACKOFF_MS) }
                    start(updateIntent = false)
                }
            }
        }, "StandbyAudioMeter")
        runCatching { thread.start() }.onFailure {
            val completion = ownership.complete(owner)
            owner.release.countDown()
            if (completion.retryPending && !isPaused()) start(updateIntent = false)
        }
    }

    private companion object {
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
