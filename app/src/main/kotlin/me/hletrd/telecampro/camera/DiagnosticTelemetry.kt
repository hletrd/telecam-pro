package me.hletrd.telecampro.camera

import kotlin.math.ln
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-lifetime allowance for recurring DEBUG diagnostics.
 *
 * ColorOS caps the complete process at 300 rows. The recurring class is 180 of them: 168 rows that
 * every repeatable information producer shares, plus a 12-row EVIDENCE reserve that only the
 * cold-start line ([StartupTrace]) and the terminal FrameGap summary may spend, and only once the
 * shared rows are gone ([evidenceDiagnosticAllowed]). Warnings/errors cross the separate 120-row
 * reserved owner. The evidence reserve exists because those two rows are read as NEGATIVE
 * evidence: a missing terminal FrameGap line reads as "no stall above the threshold", and a
 * missing cold-start line as "no measurement", so a chatty soak must not be able to silence them.
 * The reserve is two SEPARATE 6-row slices (AGG6-4): the cold-start line fires once per resume and
 * the terminal FrameGap row once per GL stop, so one shared 12 let ~12 background/foreground cycles
 * of cold starts spend the very row the soak's stall verdict depends on.
 */
internal class ProcessDiagnosticLogBudget(internal val maxRows: Int) {
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
internal const val EVIDENCE_DIAGNOSTIC_ROW_RESERVE = 12
internal const val STARTUP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE = 6
internal const val FRAME_GAP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE =
    EVIDENCE_DIAGNOSTIC_ROW_RESERVE - STARTUP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE
internal const val SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET =
    RECURRING_DIAGNOSTIC_ROW_BUDGET - EVIDENCE_DIAGNOSTIC_ROW_RESERVE
internal const val RESERVED_DIAGNOSTIC_ROW_BUDGET = 120
internal const val COLOR_OS_PROCESS_LOG_ROW_LIMIT = 300
internal val processDiagnosticLogBudget =
    ProcessDiagnosticLogBudget(SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET)
internal val processStartupEvidenceDiagnosticLogBudget =
    ProcessDiagnosticLogBudget(STARTUP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE)
internal val processFrameGapEvidenceDiagnosticLogBudget =
    ProcessDiagnosticLogBudget(FRAME_GAP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE)
internal val processReservedDiagnosticLogBudget =
    ProcessDiagnosticLogBudget(RESERVED_DIAGNOSTIC_ROW_BUDGET)

/**
 * The admission door for repeatable DEBUG information rows. Fault/error logs stay reserved.
 *
 * ONE charge per emitted row (AGG5-9): a caller that gates here has ALREADY paid for its row, so it
 * must emit through `android.util.Log` directly, never through [DiagnosticLog] (or a file's
 * `DiagnosticLog as Log` alias), whose door charges the same owner again. The double charge halved
 * the real allowance, and on the LAST row the gate took it while the inner door refused, so the
 * row was spent and nothing reached logcat. `tools/check_docs.py` rejects an aliased door call
 * directly under one of these gates.
 */
internal fun recurringDiagnosticAllowed(
    debugEnabled: Boolean,
    budget: ProcessDiagnosticLogBudget = processDiagnosticLogBudget,
): Boolean = debugEnabled && budget.tryAcquire()

/**
 * Admission for the two NEGATIVE-evidence rows (cold start, terminal FrameGap summary): the shared
 * recurring owner first, then that producer's OWN evidence slice, which no other producer can reach.
 * The default slice is the terminal FrameGap one (its only defaulted caller is the GL thread); the
 * cold-start line reaches its slice through [DiagnosticLogDoors.evidence]. Same one-charge rule as
 * [recurringDiagnosticAllowed].
 */
internal fun evidenceDiagnosticAllowed(
    debugEnabled: Boolean,
    shared: ProcessDiagnosticLogBudget = processDiagnosticLogBudget,
    evidence: ProcessDiagnosticLogBudget = processFrameGapEvidenceDiagnosticLogBudget,
): Boolean = debugEnabled && (shared.tryAcquire() || evidence.tryAcquire())

/**
 * Finite process allowance for warnings/errors that must not overrun ColorOS's real quota. The owner
 * is explicit: production binds [processReservedDiagnosticLogBudget] once, in [DiagnosticLog].
 */
internal fun reservedDiagnosticAllowed(
    budget: ProcessDiagnosticLogBudget,
): Boolean = budget.tryAcquire()

/**
 * The logcat doors over an explicit pair of owners. Production reaches them only through
 * [DiagnosticLog], which binds the two PROCESS owners; a test binds fresh owners so it can assert
 * exact admission counts instead of racing every other class that shares the sandbox's budgets.
 */
internal class DiagnosticLogDoors(
    private val recurring: ProcessDiagnosticLogBudget = processDiagnosticLogBudget,
    private val reserved: ProcessDiagnosticLogBudget = processReservedDiagnosticLogBudget,
    private val evidence: ProcessDiagnosticLogBudget = processStartupEvidenceDiagnosticLogBudget,
) {
    fun d(tag: String, message: String) {
        if (recurringDiagnosticAllowed(debugEnabled = true, recurring)) {
            android.util.Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (recurringDiagnosticAllowed(debugEnabled = true, recurring)) {
            android.util.Log.i(tag, message)
        }
    }

    /** Cold-start row that may fall back to its evidence slice; see [evidenceDiagnosticAllowed]. */
    fun evidence(tag: String, message: String) {
        if (evidenceDiagnosticAllowed(debugEnabled = true, recurring, evidence)) {
            android.util.Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (reservedDiagnosticAllowed(reserved)) android.util.Log.w(tag, message)
    }

    fun w(tag: String, message: String, failure: Throwable?) {
        if (reservedDiagnosticAllowed(reserved)) android.util.Log.w(tag, message, failure)
    }

    fun e(tag: String, message: String) {
        if (reservedDiagnosticAllowed(reserved)) android.util.Log.e(tag, message)
    }

    fun e(tag: String, message: String, failure: Throwable?) {
        if (reservedDiagnosticAllowed(reserved)) android.util.Log.e(tag, message, failure)
    }
}

/** Every production warning/error crosses the finite reserved owner before touching logcat. */
internal object DiagnosticLog {
    private val process = DiagnosticLogDoors()

    fun d(tag: String, message: String) = process.d(tag, message)

    fun i(tag: String, message: String) = process.i(tag, message)

    fun evidence(tag: String, message: String) = process.evidence(tag, message)

    fun w(tag: String, message: String) = process.w(tag, message)

    fun w(tag: String, message: String, failure: Throwable?) = process.w(tag, message, failure)

    fun e(tag: String, message: String) = process.e(tag, message)

    fun e(tag: String, message: String, failure: Throwable?) = process.e(tag, message, failure)
}

/** The most shared recurring rows the once-per-process debug capability dump may spend (AGG6-21). */
internal const val CAPABILITY_DUMP_ROW_SHARE = 20

/**
 * Row admission for the once-per-process debug capability dump (AGG6-21). The dump cost ~38 shared
 * rows on PMA110 ~5 s into every debug process — ~23% of the 168 the soak producers were budgeted
 * against — and its claim latched BEFORE any row was admitted, so a dump that met an exhausted
 * budget was lost for the process. Each row now crosses the shared owner only inside a fixed
 * [maxRows] share: content takes at most `maxRows - 1`, and the last slot is kept for one
 * truncation note so a capped dump never reads as "that capability is absent". The first refusal by
 * the shared owner closes the dump (that owner never refills). [settleClaim] returns the process
 * claim when not a single row was admitted, so a later Engine start can still take the dump.
 */
internal class CapabilityDumpRows(private val maxRows: Int = CAPABILITY_DUMP_ROW_SHARE) {
    var emitted = 0
        private set
    var truncated = false
        private set
    private var refused = false

    init {
        require(maxRows >= 2)
    }

    /** One content row; [shared] is the recurring gate, consulted (and charged) only within the share. */
    fun admit(shared: () -> Boolean): Boolean {
        val admitted = admitWithin(maxRows - 1, shared)
        if (!admitted && !refused) truncated = true
        return admitted
    }

    /** The single truncation note, in the slot content can never take. */
    fun admitTruncationNote(shared: () -> Boolean): Boolean = truncated && admitWithin(maxRows, shared)

    fun settleClaim(claim: java.util.concurrent.atomic.AtomicBoolean) {
        if (emitted == 0) claim.set(false)
    }

    private fun admitWithin(limit: Int, shared: () -> Boolean): Boolean {
        if (refused || emitted >= limit) return false
        if (!shared()) {
            refused = true
            return false
        }
        emitted++
        return true
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

/**
 * Constant-memory bounded summaries for recurring producer stalls.
 *
 * The window is cleared only when its row was ADMITTED (AGG6-4): [record] and [finish] hand each
 * summary to an `emit` that reports whether the log gate let it through. A refused periodic row
 * used to have its counts zeroed BEFORE the gate, so once the shared rows were gone every stall it
 * covered vanished, and a quiet tail made [finish] return nothing at all — the exact "no FrameGap
 * row = no stall" false pass the evidence reserve exists to prevent. Refused counts now carry
 * forward into the next due summary and the terminal one, and a refused terminal summary carries
 * into this pipeline's next terminal row (its next stop). The accumulator is a field of one
 * `GlPipeline`, so carried counts do NOT survive that pipeline's replacement: a bounded native
 * wedge that retires the object drops them with it (MRG6-9).
 */
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

    fun record(nowMs: Long, gapMs: Long, emit: (FrameGapSummary) -> Boolean) {
        require(gapMs > PREVIEW_FRAME_GAP_THRESHOLD_MS)
        count++
        maximumMs = maxOf(maximumMs, gapMs)
        when {
            gapMs < 400L -> under400Ms++
            gapMs < 1_000L -> under1Second++
            else -> atLeast1Second++
        }
        if (lastSummaryMs != Long.MIN_VALUE && nowMs - lastSummaryMs < summaryIntervalMs) return
        // The interval paces ATTEMPTS too: a refused row is retried at the next due gap, not on
        // every gap, so a stalling stream cannot hammer an exhausted gate.
        lastSummaryMs = nowMs
        emitAndClear(emit)
    }

    /** Terminal summary: emitted whenever any unreported stall exists, never for a clean window. */
    fun finish(emit: (FrameGapSummary) -> Boolean) {
        if (count > 0) emitAndClear(emit)
    }

    private fun emitAndClear(emit: (FrameGapSummary) -> Boolean) {
        val summary = FrameGapSummary(
            count = count,
            maximumMs = maximumMs,
            under400Ms = under400Ms,
            under1Second = under1Second,
            atLeast1Second = atLeast1Second,
        )
        if (!emit(summary)) return
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

/**
 * Once-per-class gate for the analysis executor's contained failures (AGG2-23 / DBG2-6).
 *
 * The analysis callback is also the app-side AE loop's meter feed, so a DETERMINISTIC exception in
 * the AE step, the FocusDetail rider, or a scope consumer is thrown again on EVERY readback (every
 * 5th frame). It used to be swallowed with no trace at all: the app-side exposure silently froze
 * while the OSD still showed S/ISO/P. Logging it per readback would spend the ColorOS 300-row quota
 * in seconds, so each GL generation (one instance per GlPipeline analysis generation) admits the
 * FIRST failure of each exception class, up to [maxClasses] distinct classes, and nothing else.
 */
internal class AnalysisFailureLogGate(private val maxClasses: Int = 4) {
    private val logged = HashSet<String>()

    init {
        require(maxClasses > 0)
    }

    @Synchronized
    fun shouldLog(failure: Throwable): Boolean {
        val key = failure.javaClass.name
        if (key in logged || logged.size >= maxClasses) return false
        logged += key
        return true
    }
}
