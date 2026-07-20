package com.hletrd.findx9tele.gl

import java.util.concurrent.atomic.AtomicReference

/**
 * Identity-owned atomic slot for replaceable native-resource facades.
 *
 * A late completion may replace only the exact object it stopped. If another generation already
 * owns the slot, the stale completion is inert and cannot displace it.
 */
internal class AtomicOwnerSlot<T : Any>(
    initial: T,
    private val replacementFactory: () -> T,
) {
    private val owner = AtomicReference(initial)

    fun current(): T = owner.get()

    fun owns(candidate: T): Boolean = owner.get() === candidate

    fun replaceIfOwned(candidate: T): T? {
        if (!owns(candidate)) return null
        val replacement = replacementFactory()
        return replacement.takeIf { owner.compareAndSet(candidate, replacement) }
    }
}

/** Pure admission used before/after blocking work that carries a generation-owned input surface. */
internal fun glInputTransactionMayProceed(
    ownerCurrent: Boolean,
    engineStarted: Boolean,
    inputCurrent: Boolean,
): Boolean = ownerCurrent && engineStarted && inputCurrent

/** Restart only an installed replacement that still has foreground acquisition and a live output. */
internal fun glReplacementMayRestartPreview(
    replaced: Boolean,
    paused: Boolean,
    acquisitionOpen: Boolean,
    hasPreviewSurface: Boolean,
): Boolean = replaced && !paused && acquisitionOpen && hasPreviewSurface
