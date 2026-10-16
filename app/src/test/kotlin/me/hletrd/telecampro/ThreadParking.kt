package me.hletrd.telecampro

import java.util.concurrent.TimeUnit

/**
 * Bounded poll for [thread] parking on a monitor (`BLOCKED`) or a `java.util.concurrent` lock
 * (`WAITING`); false if it exits or never parks within [timeoutSeconds].
 *
 * AGG6-31 (TE6-9): a negative proved by `assertFalse(latch.await(25–100 ms))` passes vacuously when
 * the guarded event fires late — after the window but before the later positive await — so the
 * ordering bug it exists to catch goes unseen under a slow scheduler. Observing the contender parked
 * on the gate proves it is excluded NOW, independent of timing. Call it only for a thread whose sole
 * possible wait is the gate under test.
 */
internal fun awaitParked(thread: Thread, timeoutSeconds: Long = 5): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (System.nanoTime() < deadline) {
        when (thread.state) {
            Thread.State.BLOCKED, Thread.State.WAITING -> return true
            Thread.State.TERMINATED -> return false
            else -> Thread.onSpinWait()
        }
    }
    return false
}
