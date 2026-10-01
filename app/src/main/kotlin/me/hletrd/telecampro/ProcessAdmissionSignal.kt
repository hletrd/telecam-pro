package me.hletrd.telecampro

/** Exact closeable subscription to one process-lifetime admission projection. */
internal fun interface ProcessAdmissionSubscription {
    fun close()
}

/**
 * Change-gated process state with monotonic, per-subscriber serialization.
 *
 * Publication never holds the signal monitor while invoking external code. Each listener owns a
 * separate monitor, so [ProcessAdmissionSubscription.close] drains an in-flight callback and no
 * callback can begin after close returns. A later sequence delivered before an initial snapshot
 * makes that older snapshot inert rather than reverting subscriber state.
 */
internal class ProcessAdmissionSignal(initial: Boolean) {
    private data class Publication(val sequence: Long, val available: Boolean)

    private class Listener(private val callback: (Boolean) -> Unit) {
        private var open = true
        private var deliveredSequence = Long.MIN_VALUE

        @Synchronized
        fun publish(publication: Publication) {
            if (!open || publication.sequence <= deliveredSequence) return
            deliveredSequence = publication.sequence
            callback(publication.available)
        }

        @Synchronized
        fun close() {
            open = false
        }
    }

    private val lock = Any()
    private var available = initial
    private var sequence = 1L
    private var nextSubscriber = 0L
    private val listeners = LinkedHashMap<Long, Listener>()

    fun current(): Boolean = synchronized(lock) { available }

    fun publish(value: Boolean) = publishLocked { value }

    /**
     * Re-reads the live admission state INSIDE the signal monitor and publishes it (AGG4-35).
     *
     * [publish] gates on a value its caller computed before taking the monitor. Two racing edges
     * could therefore land out of order: a release computed `true`, a reserve computed and published
     * `false` (a no-op), then the stale `true` flipped the signal while live capacity was `false`;
     * the next real `true` was then swallowed by the change gate, and a subscriber that re-snapshots
     * only on a delivered change kept the shutter closed while capacity was open. Evaluating [read]
     * under the monitor serializes read-and-publish, so the last publication always carries the
     * last read. Lock order is signal → owner; owners notify after releasing their own lock, so no
     * inversion exists. [read] must not call back into this signal.
     */
    fun refresh(read: () -> Boolean) = publishLocked(read)

    private inline fun publishLocked(read: () -> Boolean) {
        val publication: Publication
        val snapshot: List<Listener>
        synchronized(lock) {
            val value = read()
            if (available == value) return
            available = value
            publication = Publication(++sequence, value)
            snapshot = listeners.values.toList()
        }
        // Admission ownership must never depend on an observer. A stale/test callback that throws
        // is isolated exactly like Engine callback-sink delivery and cannot suppress later listeners.
        snapshot.forEach { listener -> runCatching { listener.publish(publication) } }
    }

    fun subscribe(callback: (Boolean) -> Unit): ProcessAdmissionSubscription {
        val listener = Listener(callback)
        val id: Long
        val initial: Publication
        synchronized(lock) {
            id = ++nextSubscriber
            listeners[id] = listener
            initial = Publication(sequence, available)
        }
        runCatching { listener.publish(initial) }
        return ProcessAdmissionSubscription {
            synchronized(lock) { listeners.remove(id) }
            listener.close()
        }
    }

    internal fun subscriberCount(): Int = synchronized(lock) { listeners.size }
}
