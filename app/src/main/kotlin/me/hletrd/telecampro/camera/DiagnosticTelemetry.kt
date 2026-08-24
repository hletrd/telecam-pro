package me.hletrd.telecampro.camera

import kotlin.math.ln
import kotlin.math.roundToInt

/** Change key for bounded debug 3A telemetry; noisy scalars arrive pre-bucketed. */
internal data class ThreeADiagnosticKey(
    val opticsGeneration: Long,
    val requestGeneration: Long,
    val mode: CaptureMode,
    val aeState: Int?,
    val afState: Int?,
    val afMode: Int?,
    val isoStops: Int?,
    val exposureStops: Int?,
    val focusCentidiopters: Int,
    val ois: Int?,
    val videoStabilization: Int?,
    val flashMode: Int?,
    val flashState: Int?,
    val requestedVideoStabilization: Int,
    val teleconverter: Boolean,
    val effectiveZoomCentipercent: Int,
)

/**
 * Caps even continuously changing 3A diagnostics below 201 rows over a ten-minute soak, while a
 * stable tuple emits only the first row plus a 15-second heartbeat (41 rows total).
 */
internal class ThreeADiagnosticLogGate(
    private val minimumChangeIntervalMs: Long = THREE_A_CHANGE_MIN_INTERVAL_MS,
    private val heartbeatMs: Long = THREE_A_HEARTBEAT_MS,
) {
    init {
        require(minimumChangeIntervalMs > 0L)
        require(heartbeatMs >= minimumChangeIntervalMs)
    }

    private var lastEmitted: ThreeADiagnosticKey? = null
    private var lastEmitMs = Long.MIN_VALUE

    fun shouldEmit(nowMs: Long, key: ThreeADiagnosticKey, force: Boolean = false): Boolean {
        val previous = lastEmitted
        val elapsed = if (lastEmitMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastEmitMs
        val changedAndDue = previous != key && elapsed >= minimumChangeIntervalMs
        val heartbeatDue = elapsed >= heartbeatMs
        if (!force && previous != null && !changedAndDue && !heartbeatDue) return false
        lastEmitted = key
        lastEmitMs = nowMs
        return true
    }
}

/** One-sixth-stop bucket: enough to diagnose convergence without logging harmless sensor jitter. */
internal fun diagnosticStopBucket(value: Long?): Int? = value
    ?.takeIf { it > 0L }
    ?.let { (ln(it.toDouble()) / ln(2.0) * 6.0).roundToInt() }

internal data class ZslSpikeSummary(
    val frames: Long,
    val durationMs: Long,
    val windows: Int,
    val minimumWindowFps: Int?,
    val maximumWindowFps: Int?,
) {
    val averageFps: Long get() = if (durationMs > 0L) frames * 1_000L / durationMs else 0L
}

/** Bounded-memory cadence accumulator. Production emits one summary only when the probe ends. */
internal class ZslSpikeAccumulator {
    private var startedMs: Long? = null
    private var windowStartedMs: Long? = null
    private var totalFrames = 0L
    private var windowFrames = 0
    private var windows = 0
    private var minimumWindowFps: Int? = null
    private var maximumWindowFps: Int? = null

    fun recordFrame(nowMs: Long) {
        if (startedMs == null) {
            startedMs = nowMs
            windowStartedMs = nowMs
        }
        totalFrames++
        windowFrames++
        val elapsed = nowMs - checkNotNull(windowStartedMs)
        if (elapsed < ZSL_SPIKE_WINDOW_MS) return
        recordWindow(windowFrames * 1_000L / elapsed)
        windowStartedMs = nowMs
        windowFrames = 0
    }

    fun finish(nowMs: Long): ZslSpikeSummary {
        val start = startedMs ?: nowMs
        val windowStart = windowStartedMs ?: nowMs
        val partialElapsed = nowMs - windowStart
        if (windowFrames > 0 && partialElapsed > 0L) {
            recordWindow(windowFrames * 1_000L / partialElapsed)
            windowFrames = 0
        }
        return ZslSpikeSummary(
            frames = totalFrames,
            durationMs = (nowMs - start).coerceAtLeast(0L),
            windows = windows,
            minimumWindowFps = minimumWindowFps,
            maximumWindowFps = maximumWindowFps,
        )
    }

    private fun recordWindow(fps: Long) {
        val bounded = fps.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        minimumWindowFps = minimumWindowFps?.coerceAtMost(bounded) ?: bounded
        maximumWindowFps = maximumWindowFps?.coerceAtLeast(bounded) ?: bounded
        windows++
    }
}

internal const val THREE_A_CHANGE_MIN_INTERVAL_MS = 3_000L
internal const val THREE_A_HEARTBEAT_MS = 15_000L
internal const val ZSL_SPIKE_WINDOW_MS = 1_000L

