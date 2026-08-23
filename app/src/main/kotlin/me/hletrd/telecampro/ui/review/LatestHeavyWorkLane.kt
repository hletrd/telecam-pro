package me.hletrd.telecampro.ui.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Latest-wins lane for synchronous work that may block without observing coroutine cancellation.
 *
 * Two workers let the newest request progress when one retired provider/native call is permanently
 * blocked. The channel retains only the latest not-yet-running request; replacing that slot retires
 * the superseded continuation immediately. All production instances share [processReviewDispatcher],
 * so separate metadata, thumbnail, and playback lanes still have one fixed process thread ceiling.
 */
internal class ProgressiveLatestWorkLane<I, R : Any>(
    dispatcher: CoroutineDispatcher = processReviewDispatcher,
    workerCount: Int = REVIEW_LANE_WORKER_COUNT,
    private val work: (I) -> R?,
    private val dispose: (R) -> Unit,
) {
    internal inner class Request internal constructor(
        val owner: Any,
        private val input: I,
    ) {
        val result = CompletableDeferred<Completion?>()
        val retired = AtomicBoolean(false)
        private val produced = AtomicReference<Completion?>(null)

        fun retire() {
            if (!retired.compareAndSet(false, true)) return
            latest.compareAndSet(this, null)
            produced.get()?.discard()
            result.complete(null)
        }

        fun execute() {
            if (retired.get() || latest.get() !== this) {
                retire()
                return
            }
            val value = runCatching { work(input) }.getOrNull()
            if (value == null) {
                retire()
                return
            }
            val completion = Completion(this, value)
            produced.set(completion)
            if (retired.get() || latest.get() !== this || !result.complete(completion)) {
                completion.discard()
            }
        }
    }

    internal inner class Completion internal constructor(
        private val request: Request,
        private val value: R,
    ) {
        private val terminal = AtomicBoolean(false)

        internal fun owns(candidate: Request): Boolean = request === candidate

        internal fun publish(block: (R) -> Unit): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            block(value)
            return true
        }

        internal fun discard() {
            if (terminal.compareAndSet(false, true)) runCatching { dispose(value) }
        }
    }

    private val latest = AtomicReference<Request?>(null)
    private val requests = Channel<Request>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = { request -> request.retire() },
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        require(workerCount >= 2) { "blocking latest-work lanes require at least two workers" }
        repeat(workerCount) {
            scope.launch {
                while (true) requests.receive().execute()
            }
        }
    }

    suspend fun submit(owner: Any, input: I): Completion? {
        val request = Request(owner, input)
        latest.getAndSet(request)?.retire()
        if (requests.trySend(request).isFailure) request.retire()
        return try {
            request.result.await()
        } catch (cancelled: CancellationException) {
            request.retire()
            throw cancelled
        }
    }

    /** Final publication gate on the caller's context. */
    fun claim(completion: Completion, publish: (R) -> Unit): Boolean {
        val request = latest.get()
        if (request == null || !completion.owns(request) ||
            !latest.compareAndSet(request, null) || !request.retired.compareAndSet(false, true)
        ) {
            completion.discard()
            return false
        }
        return completion.publish(publish)
    }

    fun invalidate(owner: Any) {
        while (true) {
            val request = latest.get() ?: return
            if (request.owner !== owner) return
            if (latest.compareAndSet(request, null)) {
                request.retire()
                return
            }
        }
    }
}

internal const val REVIEW_PROCESS_WORKER_COUNT = 4
internal const val REVIEW_LANE_WORKER_COUNT = 2

/** One daemon pool shared by every blocking review-media lane for the process lifetime. */
private val processReviewDispatcher: CoroutineDispatcher by lazy {
    val sequence = AtomicInteger()
    Executors.newFixedThreadPool(
        REVIEW_PROCESS_WORKER_COUNT,
        ThreadFactory { task ->
            Thread(task, "review-media-${sequence.incrementAndGet()}").apply { isDaemon = true }
        },
    ).asCoroutineDispatcher()
}
