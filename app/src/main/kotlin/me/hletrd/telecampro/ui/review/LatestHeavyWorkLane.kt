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
import kotlinx.coroutines.withTimeoutOrNull
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

/** Exact publication/disposal token detached from the generic lane's nested-type projections. */
internal class ProgressiveWorkCompletion<R : Any>(
    private val request: Any,
    private val value: R,
    private val dispose: (R) -> Unit,
) {
    private val terminal = AtomicBoolean(false)

    internal fun owns(candidate: Any): Boolean = request === candidate

    internal fun publish(block: (R) -> Unit): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        block(value)
        return true
    }

    internal fun discard() {
        if (terminal.compareAndSet(false, true)) runCatching { dispose(value) }
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
    private val terminalTimeoutMs: Long = REVIEW_WORK_TERMINAL_TIMEOUT_MS,
    private val work: (I) -> R?,
    private val dispose: (R) -> Unit,
) {
    internal sealed interface Submission<out C> {
        data class Completed<C>(val completion: C) : Submission<C>
        data object Retired : Submission<Nothing>
        data object TimedOut : Submission<Nothing>
        data object CapacityExhausted : Submission<Nothing>
    }

    private enum class RequestStage { QUEUED, STARTED, PRODUCED, TERMINAL }

    internal inner class Request internal constructor(
        val owner: Any,
        private val input: I,
    ) {
        val result = CompletableDeferred<Submission<ProgressiveWorkCompletion<R>>>()
        private val stage = AtomicReference(RequestStage.QUEUED)
        private val produced = AtomicReference<ProgressiveWorkCompletion<R>?>(null)

        fun retire(): Boolean = terminate(Submission.Retired)

        private fun terminate(outcome: Submission<Nothing>): Boolean {
            while (true) {
                val current = stage.get()
                if (current == RequestStage.TERMINAL) return false
                if (!stage.compareAndSet(current, RequestStage.TERMINAL)) continue
                latest.compareAndSet(this, null)
                if (current == RequestStage.PRODUCED) produced.getAndSet(null)?.discard()
                result.complete(outcome)
                return true
            }
        }

        fun execute() {
            if (latest.get() !== this) {
                retire()
                return
            }
            executeOwned()
        }

        /** The request has reached a consumer and is about to enter synchronous blocking work. */
        internal fun executeOwned() {
            if (!stage.compareAndSet(RequestStage.QUEUED, RequestStage.STARTED)) return
            val value = runCatching { work(input) }.getOrNull()
            if (value == null) {
                retire()
                return
            }
            val completion = ProgressiveWorkCompletion(this, value, dispose)
            produced.set(completion)
            if (!stage.compareAndSet(RequestStage.STARTED, RequestStage.PRODUCED)) {
                produced.getAndSet(null)?.discard()
                return
            }
            result.complete(Submission.Completed(completion))
        }

        /**
         * One atomic timeout boundary: a produced value wins completion; otherwise this call owns
         * retirement. Only a request still waiting for a consumer represents exhausted capacity.
         */
        internal suspend fun terminalAfterTimeout(): Submission<ProgressiveWorkCompletion<R>> {
            while (true) {
                when (stage.get()) {
                    RequestStage.PRODUCED -> {
                        val completion = produced.get()
                        if (completion != null) return Submission.Completed(completion)
                    }
                    RequestStage.QUEUED -> if (terminate(Submission.CapacityExhausted)) {
                        return Submission.CapacityExhausted
                    }
                    RequestStage.STARTED -> if (terminate(Submission.TimedOut)) {
                        return Submission.TimedOut
                    }
                    RequestStage.TERMINAL -> return result.await()
                }
            }
        }

        internal fun take(completion: ProgressiveWorkCompletion<R>): Boolean =
            produced.compareAndSet(completion, null) &&
                stage.compareAndSet(RequestStage.PRODUCED, RequestStage.TERMINAL)
    }

    private val latest = AtomicReference<Request?>(null)
    private val requests = Channel<Request>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = { request -> request.retire() },
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        require(workerCount >= 2) { "blocking latest-work lanes require at least two workers" }
        require(terminalTimeoutMs > 0L) { "blocking latest-work timeout must be positive" }
        repeat(workerCount) {
            scope.launch {
                while (true) requests.receive().execute()
            }
        }
    }

    suspend fun submit(owner: Any, input: I): Submission<ProgressiveWorkCompletion<R>> {
        val request = Request(owner, input)
        latest.getAndSet(request)?.retire()
        if (requests.trySend(request).isFailure) request.retire()
        return try {
            val awaited = withTimeoutOrNull(terminalTimeoutMs) {
                request.result.await()
            }
            awaited ?: request.terminalAfterTimeout()
        } catch (cancelled: CancellationException) {
            request.retire()
            throw cancelled
        }
    }

    /** Final publication gate on the caller's context. */
    fun claim(completion: ProgressiveWorkCompletion<R>, publish: (R) -> Unit): Boolean {
        val request = latest.get()
        if (request == null || !completion.owns(request) ||
            !latest.compareAndSet(request, null) || !request.take(completion)
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

    /** Focused ownership assertion seam; production behavior never branches on this value. */
    internal fun hasLatestRequest(): Boolean = latest.get() != null
}

internal const val REVIEW_PROCESS_WORKER_COUNT = 4
internal const val REVIEW_LANE_WORKER_COUNT = 2
internal const val REVIEW_WORK_TERMINAL_TIMEOUT_MS = 5_000L

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
