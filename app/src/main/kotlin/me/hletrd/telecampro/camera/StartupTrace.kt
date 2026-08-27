package me.hletrd.telecampro.camera

import android.os.SystemClock
import android.util.Log
import me.hletrd.telecampro.BuildConfig

/**
 * Cold-start stopwatch for the "Camera starting" window (cycle 8 S6). User-felt latency is process
 * start → first real camera frame; the system log brackets the HAL (`configure_streams`) but says
 * nothing about which of OUR steps sit between, so a regression there used to be invisible.
 *
 * Marks are BUFFERED and emitted as ONE line at [finish], never one Log call per milestone. That is
 * not tidiness — it is required on this device: ColorOS enforces a per-process log quota
 * (`LOG_FLOWCTRL: LOGS OVER PROC QUOTA(300) ... DROPPED`, device-observed 2026-07-25). The 200+-row
 * offender that made this measurable was the OPPO CameraUnit/OCS SDK the debug build pulled in
 * through `OcsProbe`; removing it (2026-07-25) bought headroom back but did NOT remove the quota —
 * it is process-wide, and the camera stack's own cold-start chatter still lands in this window.
 * Do NOT "simplify" this back to per-mark logging: the marks get silently eaten before logcat.
 *
 * DEBUG-only. [begin] is idempotent per cold start — the FIRST caller wins, so a re-entrant start
 * cannot reset the clock mid-measurement and report a fake-fast number.
 */
object StartupTrace {
    private const val TAG = "StartupTrace"

    internal class Owner internal constructor(internal val generation: Long)

    // Guarded by the object monitor: marks arrive from main (resume), setupExecutor (open/configure)
    // and the camera thread (first result), so the buffer needs real mutual exclusion.
    // Injected clock: android.os.SystemClock is NOT mocked on the host JVM, so the arming state
    // machine would be untestable bound directly to it (the same injected-clock seam the macro
    // hysteresis uses). Production never reassigns it.
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    // Same reason as the clock: android.util.Log is not mocked on the host, so the emit path needs
    // a seam or finish() cannot be exercised at all. Production never reassigns it.
    internal var emit: (String) -> Unit = { Log.i(TAG, it) }

    private var originMs = 0L
    private var nextGeneration = 0L
    private var owner: Owner? = null
    private val marks = mutableListOf<Pair<String, Long>>()

    /** Marks t=0 for a cold start and returns its exact owner. Re-entrant begin keeps that owner. */
    @Synchronized
    internal fun begin(): Owner? {
        if (!BuildConfig.DEBUG) return null
        owner?.let { return it }
        val started = Owner(++nextGeneration)
        owner = started
        originMs = elapsedMs()
        marks.clear()
        return started
    }

    /** Records `label` with milliseconds since [begin]. Silent when no measurement is armed. */
    @Synchronized
    internal fun mark(expected: Owner?, label: String) {
        if (!BuildConfig.DEBUG || expected == null || owner != expected) return
        marks += label to (elapsedMs() - originMs)
    }

    /** Emits the whole cold start as one line and disarms, so only the first frame is measured. */
    @Synchronized
    internal fun finish(expected: Owner?, label: String) {
        if (!BuildConfig.DEBUG || expected == null || owner != expected) return
        mark(expected, label)
        owner = null
        emit("cold start (ms since resume): " + marks.joinToString(" → ") { "${it.first} ${it.second}" })
    }

    /**
     * DISARMS a measurement that never became a real camera open, discarding its marks. The old
     * name (`reset`) and doc claimed the opposite ("arms the next cold start") while the body only
     * ever stopped one. [CameraEngine.resume] arms optimistically at its very first line — the only
     * honest "ms since resume" origin — and calls this on every path that returns without opening,
     * so a zero-mark trace can never be finished by an unrelated later preview rebuild.
     */
    @Synchronized
    internal fun disarm(expected: Owner?) {
        if (!BuildConfig.DEBUG || expected == null || owner != expected) return
        owner = null
        marks.clear()
    }

    /** Test/reset seam. Production teardown must use the exact owner overload above. */
    @Synchronized
    internal fun disarm() {
        owner = null
        marks.clear()
    }

    @Synchronized
    internal fun currentOwner(): Owner? = owner

    /** Host-test view of the buffered marks (label to elapsed-ms), in order. */
    @Synchronized
    internal fun marksForTest(): List<Pair<String, Long>> = marks.toList()
}

internal fun startupTraceRequestMayFinish(
    requestGeneration: Long,
    latestRequestGeneration: Long,
): Boolean = requestGeneration == latestRequestGeneration
