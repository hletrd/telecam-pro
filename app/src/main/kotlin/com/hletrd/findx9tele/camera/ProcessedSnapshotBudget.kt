package com.hletrd.findx9tele.camera

import java.util.concurrent.atomic.AtomicBoolean

/**
 * At most one processed SINGLE save may run while one more full-resolution snapshot waits behind
 * it. Sequence drive modes already chain on save completion and therefore do not use this budget.
 */
internal const val MAX_RETAINED_SINGLE_PROCESSED_SNAPSHOTS = 2

/** Android-free, thread-safe admission budget for retained processed still snapshots. */
internal class ProcessedSnapshotBudget(
    private val capacity: Int = MAX_RETAINED_SINGLE_PROCESSED_SNAPSHOTS,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private var retained = 0

    @Synchronized
    fun tryAcquire(): Lease? {
        if (retained >= capacity) return null
        retained++
        return Lease(this)
    }

    @Synchronized
    private fun release() {
        check(retained > 0) { "processed snapshot budget underflow" }
        retained--
    }

    internal class Lease internal constructor(private val owner: ProcessedSnapshotBudget) {
        private val released = AtomicBoolean(false)

        /** Returns true only for the call that returned this slot to the budget. */
        fun release(): Boolean {
            if (!released.compareAndSet(false, true)) return false
            owner.release()
            return true
        }
    }
}
