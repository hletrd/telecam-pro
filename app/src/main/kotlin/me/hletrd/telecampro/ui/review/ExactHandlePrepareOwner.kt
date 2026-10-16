package me.hletrd.telecampro.ui.review

/** Cancelable registration returned by the composition-owned deadline scheduler. */
internal fun interface ReviewDeadlineRegistration {
    fun cancel()
}

/**
 * Exact-handle owner for a native playback resource while asynchronous preparation is pending.
 *
 * The handle stays owned after [prepared] retires the deadline so Back/disposal can release it.
 * Error, timeout, Back, and replacement all clear the same identity before invoking the release
 * callback, making late callbacks and stale timer deliveries inert.
 */
internal class ExactHandlePrepareOwner<H : Any>(
    private val timeoutMs: Long,
    private val schedule: (Long, () -> Unit) -> ReviewDeadlineRegistration,
    private val dispose: (H) -> Unit,
) {
    private class PrepareGeneration<H : Any>(
        val handle: H,
        val onTimeout: () -> Unit,
    ) {
        var registration: ReviewDeadlineRegistration? = null
    }

    private var current: H? = null
    private var preparing: PrepareGeneration<H>? = null

    init {
        require(timeoutMs > 0L) { "prepare timeout must be positive" }
    }

    /** Publishes [handle], releasing any exact predecessor before replacement. */
    fun replace(handle: H) {
        releaseCurrent()
        current = handle
    }

    /** Arms the exact current handle immediately before its asynchronous prepare call. */
    fun arm(handle: H, onTimeout: () -> Unit) {
        check(current === handle) { "only the current playback handle can prepare" }
        check(preparing == null) { "playback handle already has a prepare deadline" }
        val generation = PrepareGeneration(handle, onTimeout)
        preparing = generation
        val registration = schedule(timeoutMs) { expire(generation) }
        generation.registration = registration
        // A test scheduler may deliver synchronously; never leave that already-terminal timer live.
        if (preparing !== generation) registration.cancel()
    }

    /** Retires only [handle]'s deadline while retaining its prepared playback ownership. */
    fun prepared(handle: H): Boolean {
        if (current !== handle) return false
        val generation = preparing ?: return false
        if (generation.handle !== handle) return false
        preparing = null
        generation.registration?.cancel()
        return true
    }

    /** Releases [handle] only if it is still the exact published generation. */
    fun release(handle: H): Boolean {
        if (current !== handle) return false
        clearAndRelease(handle)
        return true
    }

    /** Back/disposal/surface retirement release the currently published generation, if any. */
    fun releaseCurrent(): Boolean {
        val handle = current ?: return false
        clearAndRelease(handle)
        return true
    }

    internal fun owns(handle: H): Boolean = current === handle

    private fun expire(generation: PrepareGeneration<H>) {
        if (preparing !== generation || current !== generation.handle) return
        val handle = generation.handle
        preparing = null
        current = null
        dispose(handle)
        generation.onTimeout()
    }

    private fun clearAndRelease(handle: H) {
        val generation = preparing
        current = null
        preparing = null
        if (generation?.handle === handle) generation.registration?.cancel()
        dispose(handle)
    }
}
