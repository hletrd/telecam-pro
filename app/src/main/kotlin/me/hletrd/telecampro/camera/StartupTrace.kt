package me.hletrd.telecampro.camera

import android.os.SystemClock
import me.hletrd.telecampro.camera.DiagnosticLog as Log
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

    /**
     * Starts a new Engine-owned attempt, revoking any older process owner.
     *
     * Production re-entrancy is decided by [EngineStartupTraceOwnership] before this call. Keeping
     * replacement here explicit prevents a newly created Engine from inheriting an older Engine's
     * still-armed resume origin merely because both live briefly in the same process.
     */
    @Synchronized
    internal fun beginNew(): Owner? {
        if (!BuildConfig.DEBUG) return null
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

    @Synchronized
    internal fun owns(expected: Owner?): Boolean = expected != null && owner === expected

    /** Host-test view of the buffered marks (label to elapsed-ms), in order. */
    @Synchronized
    internal fun marksForTest(): List<Pair<String, Long>> = marks.toList()
}

/**
 * Exact Engine/resume/controller owner for [StartupTrace].
 *
 * One Engine may re-enter the same live resume attempt without resetting its origin. Pause,
 * release, or an exact early terminal revokes that attempt. At most one CameraController may claim
 * it: constructing a replacement controller revokes the trace before either controller can report
 * a mixed milestone sequence.
 */
internal class EngineStartupTraceOwnership(
    private val beginNew: () -> StartupTrace.Owner? = StartupTrace::beginNew,
    private val isCurrent: (StartupTrace.Owner?) -> Boolean = StartupTrace::owns,
    private val disarm: (StartupTrace.Owner?) -> Unit = StartupTrace::disarm,
) {
    private var owner: StartupTrace.Owner? = null
    private var controllerClaimed = false

    @Synchronized
    fun begin(): StartupTrace.Owner? {
        owner?.takeIf(isCurrent)?.let { return it }
        owner = null
        controllerClaimed = false
        return beginNew().also { owner = it }
    }

    @Synchronized
    fun current(): StartupTrace.Owner? {
        val active = owner ?: return null
        if (isCurrent(active)) return active
        owner = null
        controllerClaimed = false
        return null
    }

    /** Returns the owner only to its first installed controller. A replacement revokes it. */
    fun claimController(expected: StartupTrace.Owner?): StartupTrace.Owner? {
        var revoked: StartupTrace.Owner? = null
        val claimed = synchronized(this) {
            if (expected == null) {
                revoked = owner
                owner = null
                controllerClaimed = false
                null
            } else if (owner !== expected || !isCurrent(expected)) {
                if (owner != null && !isCurrent(owner)) {
                    owner = null
                    controllerClaimed = false
                }
                null
            } else if (controllerClaimed) {
                revoked = expected
                owner = null
                controllerClaimed = false
                null
            } else {
                controllerClaimed = true
                expected
            }
        }
        revoked?.let(disarm)
        return claimed
    }

    /** Revokes only [expected], so a stale setup task cannot disarm a newer resume. */
    fun revoke(expected: StartupTrace.Owner?): Boolean {
        if (expected == null) return false
        val revoked = synchronized(this) {
            if (owner !== expected) false else {
                owner = null
                controllerClaimed = false
                true
            }
        }
        if (revoked) disarm(expected)
        return revoked
    }

    /** Lifecycle terminal for whichever exact attempt this Engine currently owns. */
    fun revoke(): Boolean {
        val revoked = synchronized(this) {
            owner.also {
                owner = null
                controllerClaimed = false
            }
        } ?: return false
        disarm(revoked)
        return true
    }
}

internal fun startupTraceRequestMayFinish(
    requestGeneration: Long,
    latestRequestGeneration: Long,
): Boolean = requestGeneration == latestRequestGeneration
