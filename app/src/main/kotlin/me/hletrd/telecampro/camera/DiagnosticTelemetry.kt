package me.hletrd.telecampro.camera

import kotlin.math.ln
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-lifetime allowance for recurring DEBUG diagnostics.
 *
 * ColorOS caps the complete process at 300 rows. Recurring evidence producers share this smaller
 * allowance so startup, frame-gap, recovery, and fault rows always retain an explicit reserve.
 */
internal class ProcessDiagnosticLogBudget(private val maxRows: Int) {
    private val used = AtomicInteger(0)

    init {
        require(maxRows > 0)
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = used.get()
            if (current >= maxRows) return false
            if (used.compareAndSet(current, current + 1)) return true
        }
    }

    internal fun usedRows(): Int = used.get()
}

internal const val RECURRING_DIAGNOSTIC_ROW_BUDGET = 180
internal const val RESERVED_DIAGNOSTIC_ROW_BUDGET = 120
internal const val COLOR_OS_PROCESS_LOG_ROW_LIMIT = 300
internal val processDiagnosticLogBudget = ProcessDiagnosticLogBudget(RECURRING_DIAGNOSTIC_ROW_BUDGET)
internal val processReservedDiagnosticLogBudget =
    ProcessDiagnosticLogBudget(RESERVED_DIAGNOSTIC_ROW_BUDGET)

/** The only admission door for repeatable DEBUG information rows. Fault/error logs stay reserved. */
internal fun recurringDiagnosticAllowed(
    debugEnabled: Boolean,
    budget: ProcessDiagnosticLogBudget = processDiagnosticLogBudget,
): Boolean = debugEnabled && budget.tryAcquire()

/** Finite process allowance for warnings/errors that must not overrun ColorOS's real quota. */
internal fun reservedDiagnosticAllowed(
    budget: ProcessDiagnosticLogBudget = processReservedDiagnosticLogBudget,
): Boolean = budget.tryAcquire()

/** Every production warning/error crosses the finite reserved owner before touching logcat. */
internal object DiagnosticLog {
    fun d(tag: String, message: String) {
        if (recurringDiagnosticAllowed(debugEnabled = true)) android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (recurringDiagnosticAllowed(debugEnabled = true)) android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (reservedDiagnosticAllowed()) android.util.Log.w(tag, message)
    }

    fun w(tag: String, message: String, failure: Throwable?) {
        if (reservedDiagnosticAllowed()) android.util.Log.w(tag, message, failure)
    }

    fun e(tag: String, message: String) {
        if (reservedDiagnosticAllowed()) android.util.Log.e(tag, message)
    }

    fun e(tag: String, message: String, failure: Throwable?) {
        if (reservedDiagnosticAllowed()) android.util.Log.e(tag, message, failure)
    }
}

/**
 * Tap-focus diagnostics are action-repeatable, so only a real scan/reset edge may spend one row.
 * Empty clear calls remain silent and neither edge can bypass the process recurring-row ceiling.
 */
internal fun tapFocusDiagnosticAllowed(
    debugEnabled: Boolean,
    edgeOwned: Boolean,
    budget: ProcessDiagnosticLogBudget = processDiagnosticLogBudget,
): Boolean = edgeOwned && recurringDiagnosticAllowed(debugEnabled, budget)

/**
 * Separates preview render cadence from real SurfaceTexture producer cadence.
 *
 * Cached zoom redraws update [lastRenderMs] only. They may decide that another cached repaint is
 * unnecessary, but they can neither satisfy nor emit Camera2 frame-health evidence.
 */
internal class PreviewFrameTiming(
    private val frameGapThresholdMs: Long = PREVIEW_FRAME_GAP_THRESHOLD_MS,
) {
    private var lastRenderMs = 0L
    private var lastCameraFrameMs = 0L

    init {
        require(frameGapThresholdMs > 0L)
    }

    fun renderIdleMs(nowMs: Long): Long = if (lastRenderMs == 0L) Long.MAX_VALUE else nowMs - lastRenderMs

    fun recordDraw(nowMs: Long, realCameraFrame: Boolean): Long? {
        lastRenderMs = nowMs
        if (!realCameraFrame) return null
        val gap = if (lastCameraFrameMs != 0L) nowMs - lastCameraFrameMs else 0L
        lastCameraFrameMs = nowMs
        return gap.takeIf { it > frameGapThresholdMs }
    }

    fun reset() {
        lastRenderMs = 0L
        lastCameraFrameMs = 0L
    }
}

internal data class FrameGapSummary(
    val count: Int,
    val maximumMs: Long,
    val under400Ms: Int,
    val under1Second: Int,
    val atLeast1Second: Int,
)

/** Constant-memory bounded summaries for recurring producer stalls. */
internal class FrameGapAccumulator(
    private val summaryIntervalMs: Long = FRAME_GAP_SUMMARY_INTERVAL_MS,
) {
    private var lastSummaryMs = Long.MIN_VALUE
    private var count = 0
    private var maximumMs = 0L
    private var under400Ms = 0
    private var under1Second = 0
    private var atLeast1Second = 0

    init {
        require(summaryIntervalMs > 0L)
    }

    fun record(nowMs: Long, gapMs: Long): FrameGapSummary? {
        require(gapMs > PREVIEW_FRAME_GAP_THRESHOLD_MS)
        count++
        maximumMs = maxOf(maximumMs, gapMs)
        when {
            gapMs < 400L -> under400Ms++
            gapMs < 1_000L -> under1Second++
            else -> atLeast1Second++
        }
        if (lastSummaryMs != Long.MIN_VALUE && nowMs - lastSummaryMs < summaryIntervalMs) return null
        lastSummaryMs = nowMs
        return takeSummary()
    }

    fun finish(): FrameGapSummary? = if (count == 0) null else takeSummary()

    private fun takeSummary() = FrameGapSummary(
        count = count,
        maximumMs = maximumMs,
        under400Ms = under400Ms,
        under1Second = under1Second,
        atLeast1Second = atLeast1Second,
    ).also {
        count = 0
        maximumMs = 0L
        under400Ms = 0
        under1Second = 0
        atLeast1Second = 0
    }
}

/** Change-gated diagnostic with a slow heartbeat and a floor for flapping state. */
internal class DiagnosticChangeLogGate<T>(
    private val minimumChangeIntervalMs: Long = DIAGNOSTIC_CHANGE_MIN_INTERVAL_MS,
    private val heartbeatMs: Long = DIAGNOSTIC_HEARTBEAT_MS,
) {
    private var lastEmitted: T? = null
    private var initialized = false
    private var lastEmitMs = Long.MIN_VALUE

    init {
        require(minimumChangeIntervalMs > 0L)
        require(heartbeatMs >= minimumChangeIntervalMs)
    }

    fun shouldEmit(nowMs: Long, value: T): Boolean {
        val elapsed = if (lastEmitMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastEmitMs
        val changedAndDue = initialized && lastEmitted != value && elapsed >= minimumChangeIntervalMs
        val heartbeatDue = initialized && elapsed >= heartbeatMs
        if (initialized && !changedAndDue && !heartbeatDue) return false
        initialized = true
        lastEmitted = value
        lastEmitMs = nowMs
        return true
    }
}

/** Start/end plus a slow liveness heartbeat for a held hardware-key stream. */
internal class HardwareKeyDiagnosticLogGate(
    private val repeatHeartbeatMs: Long = DIAGNOSTIC_HEARTBEAT_MS,
) {
    private val lastEmitByKey = mutableMapOf<Int, Long>()

    init {
        require(repeatHeartbeatMs > 0L)
    }

    @Synchronized
    fun shouldEmit(keyCode: Int, actionDown: Boolean, repeatCount: Int, nowMs: Long): Boolean {
        if (!actionDown) {
            val owned = lastEmitByKey.remove(keyCode) != null
            return owned
        }
        val previous = lastEmitByKey[keyCode]
        if (repeatCount <= 0 || previous == null || nowMs - previous >= repeatHeartbeatMs) {
            lastEmitByKey[keyCode] = nowMs
            return true
        }
        return false
    }
}

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
internal const val DIAGNOSTIC_CHANGE_MIN_INTERVAL_MS = 3_000L
internal const val DIAGNOSTIC_HEARTBEAT_MS = 15_000L
internal const val ZSL_SPIKE_WINDOW_MS = 1_000L
internal const val PREVIEW_FRAME_GAP_THRESHOLD_MS = 200L
internal const val FRAME_GAP_SUMMARY_INTERVAL_MS = 15_000L
