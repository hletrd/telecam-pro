package me.hletrd.telecampro.ui.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-finite latest-wins lane for work whose individual result is expensive to retain.
 *
 * Only one [work] call can run at a time. A newer request replaces the publication owner
 * immediately; intermediate requests waiting for the mutex leave without running, and a running
 * stale/cancelled request disposes its result before releasing the lane. [claim] is the final
 * identity boundary on the caller's publication thread, closing the worker-return -> UI-write race.
 */
internal class LatestHeavyWorkLane<I, R : Any>(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val work: (I) -> R?,
    private val dispose: (R) -> Unit,
) {
    private class Request<I>(
        val id: Long,
        val owner: Any,
        val input: I,
    )

    internal class Completion<R : Any> private constructor(
        private val request: Any,
        internal val value: R,
    ) {
        internal companion object {
            fun <R : Any> create(request: Any, value: R) = Completion(request, value)
        }

        internal fun owns(candidate: Any): Boolean = request === candidate
    }

    private val sequence = AtomicLong(0L)
    private val latest = AtomicReference<Request<I>?>(null)
    private val mutex = Mutex()

    suspend fun submit(owner: Any, input: I): Completion<R>? {
        val request = Request(sequence.incrementAndGet(), owner, input)
        latest.set(request)
        var produced: Completion<R>? = null
        try {
            return withContext(dispatcher) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (latest.get() !== request) return@withLock null
                    val value = work(request.input) ?: return@withLock null
                    if (latest.get() !== request || !currentCoroutineContext().isActive) {
                        runCatching { dispose(value) }
                        return@withLock null
                    }
                    Completion.create(request, value).also { produced = it }
                }
            }
        } catch (cancelled: CancellationException) {
            produced?.let(::discard)
            throw cancelled
        }
    }

    /** Publishes only while no newer request or owner invalidation has won. */
    fun claim(completion: Completion<R>, publish: (R) -> Unit): Boolean {
        val request = latest.get()
        if (request == null || !completion.owns(request) || !latest.compareAndSet(request, null)) {
            runCatching { dispose(completion.value) }
            return false
        }
        publish(completion.value)
        return true
    }

    /** Invalidates only this UI owner's latest request; a replacement owner remains untouched. */
    fun invalidate(owner: Any) {
        while (true) {
            val request = latest.get() ?: return
            if (request.owner !== owner) return
            if (latest.compareAndSet(request, null)) return
        }
    }

    private fun discard(completion: Completion<R>) {
        val request = latest.get()
        if (request != null && completion.owns(request)) latest.compareAndSet(request, null)
        runCatching { dispose(completion.value) }
    }
}
