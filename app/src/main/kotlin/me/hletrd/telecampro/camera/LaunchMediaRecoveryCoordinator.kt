package me.hletrd.telecampro.camera

import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.storage.OrphanRecoveryBatch
import me.hletrd.telecampro.storage.OrphanRecoveryCursor
import me.hletrd.telecampro.storage.RecoveryReport
import me.hletrd.telecampro.storage.RecoveryFailureClass
import me.hletrd.telecampro.storage.RecoveryRetryDecision
import me.hletrd.telecampro.storage.recoveryRetryDecision

internal fun interface LaunchMediaRecoverySubscription {
    fun cancel()
}

internal fun interface LaunchMediaRecoveryDeadlineCancellation {
    fun cancel()
}

internal fun interface LaunchMediaRecoveryDeadlineScheduler {
    /** Returns null, or throws, when the process watchdog cannot accept the deadline. */
    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): LaunchMediaRecoveryDeadlineCancellation?
}

/**
 * Typed terminal result for a process whose only recovery worker can no longer be trusted to return.
 * The same instance is replayed to every later subscriber until process restart.
 */
internal class LaunchMediaRecoveryCapacityExhaustedException(
    val deadlineMs: Long,
    message: String =
        "Launch media recovery exceeded its $deadlineMs ms process deadline; " +
            "recovery capacity remains exhausted until process restart",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Process-wide single-flight owner for launch recovery. Engine recreation replaces/cancels only its
 * subscriber; it can neither start another provider scan nor interrupt the scan already preserving
 * prior-process media. A typed success/failure result is delivered once to every still-live Engine
 * subscriber; an unexpected recovery exception therefore cannot silently erase the terminal edge.
 * A deadline does not interrupt an uncancellable provider call. It instead exhausts this process's
 * sole worker permanently, making the late return inert and preventing Engine recreation from
 * multiplying wedged Binder calls.
 */
internal class LaunchMediaRecoveryCoordinator<T : Any>(
    private val dispatch: (Runnable) -> Boolean,
    private val deadlineScheduler: LaunchMediaRecoveryDeadlineScheduler,
    private val deadlineMs: Long,
) {
    private class Subscriber<T : Any>(
        val order: Long,
        val onComplete: (Result<T>) -> Unit,
    ) {
        private val live = AtomicBoolean(true)

        fun cancel() {
            live.set(false)
        }

        fun deliver(result: Result<T>) {
            if (live.compareAndSet(true, false)) runCatching { onComplete(result) }
        }
    }

    private class Recovery<T : Any>(
        val recover: () -> T,
        var deadline: LaunchMediaRecoveryDeadlineCancellation? = null,
    )

    private data class TerminalDelivery<T : Any>(
        val deadline: LaunchMediaRecoveryDeadlineCancellation?,
        val subscribers: List<Subscriber<T>>,
    )

    private val lock = Any()
    private val subscribers = IdentityHashMap<Any, Subscriber<T>>()
    private val subscriberSequence = AtomicLong()
    private var active: Recovery<T>? = null
    private var exhausted: LaunchMediaRecoveryCapacityExhaustedException? = null

    init {
        require(deadlineMs > 0L) { "launch-recovery deadline must be positive" }
    }

    fun request(
        owner: Any,
        recover: () -> T,
        onComplete: (Result<T>) -> Unit,
    ): LaunchMediaRecoverySubscription {
        val subscriber = Subscriber(subscriberSequence.incrementAndGet(), onComplete)
        var exhaustedFailure: LaunchMediaRecoveryCapacityExhaustedException? = null
        val recovery = synchronized(lock) {
            exhaustedFailure = exhausted
            if (exhaustedFailure != null) {
                null
            } else {
                subscribers.put(owner, subscriber)?.cancel()
                if (active != null) null else Recovery(recover).also { active = it }
            }
        }
        exhaustedFailure?.let { subscriber.deliver(Result.failure(it)) }
        recovery?.let(::start)
        return LaunchMediaRecoverySubscription {
            subscriber.cancel()
            synchronized(lock) {
                if (subscribers[owner] === subscriber) subscribers.remove(owner)
            }
        }
    }

    internal fun subscriberCount(): Int = synchronized(lock) { subscribers.size }
    internal fun isRunning(): Boolean = synchronized(lock) { active != null }
    internal fun isExhausted(): Boolean = synchronized(lock) { exhausted != null }

    private fun start(recovery: Recovery<T>) {
        val scheduled = runCatching {
            deadlineScheduler.schedule(deadlineMs) { exhaust(recovery) }
        }
        val cancellation = scheduled.getOrNull()
        if (cancellation == null) {
            exhaust(
                recovery,
                LaunchMediaRecoveryCapacityExhaustedException(
                    deadlineMs = deadlineMs,
                    message = "Launch media recovery deadline could not be armed; " +
                        "recovery capacity remains exhausted until process restart",
                    cause = scheduled.exceptionOrNull(),
                ),
            )
            return
        }
        val installed = synchronized(lock) {
            if (active !== recovery || exhausted != null) false else {
                recovery.deadline = cancellation
                true
            }
        }
        if (!installed) {
            runCatching { cancellation.cancel() }
            return
        }

        val dispatchFailure = runCatching {
            dispatch(Runnable {
                complete(recovery, runCatching(recovery.recover))
            })
        }.fold(
            onSuccess = { accepted ->
                if (accepted) null
                else IllegalStateException("process launch-recovery dispatcher rejected its sole task")
            },
            onFailure = { it },
        )
        if (dispatchFailure != null) fail(recovery, dispatchFailure)
    }

    private fun complete(recovery: Recovery<T>, result: Result<T>) {
        val delivery = synchronized(lock) {
            if (active !== recovery || exhausted != null) null else {
                active = null
                terminalDelivery(recovery)
            }
        } ?: return
        deliver(delivery, result)
    }

    private fun fail(recovery: Recovery<T>, failure: Throwable) {
        val delivery = synchronized(lock) {
            if (active !== recovery || exhausted != null) null else {
                active = null
                terminalDelivery(recovery)
            }
        } ?: return
        deliver(delivery, Result.failure(failure))
    }

    private fun exhaust(
        recovery: Recovery<T>,
        failure: LaunchMediaRecoveryCapacityExhaustedException =
            LaunchMediaRecoveryCapacityExhaustedException(deadlineMs),
    ) {
        val delivery = synchronized(lock) {
            if (active !== recovery || exhausted != null) null else {
                active = null
                exhausted = failure
                terminalDelivery(recovery, cancelDeadline = false)
            }
        } ?: return
        deliver(delivery, Result.failure(failure))
    }

    private fun terminalDelivery(
        recovery: Recovery<T>,
        cancelDeadline: Boolean = true,
    ): TerminalDelivery<T> = TerminalDelivery(
        deadline = recovery.deadline.takeIf { cancelDeadline }.also { recovery.deadline = null },
        subscribers = subscribers.values.sortedBy { it.order }.also { subscribers.clear() },
    )

    private fun deliver(delivery: TerminalDelivery<T>, result: Result<T>) {
        runCatching { delivery.deadline?.cancel() }
        delivery.subscribers.forEach { it.deliver(result) }
    }
}

/** One recovery daemon, one watchdog daemon, and zero per-Engine queues per process lifetime. */
internal object ProcessLaunchMediaRecovery {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "media-recovery").apply { isDaemon = true }
    }
    private val deadlineExecutor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "media-recovery-deadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val coordinator = LaunchMediaRecoveryCoordinator<MediaRecoveryCompletion>(
        dispatch = { task -> runCatching { executor.execute(task) }.isSuccess },
        deadlineScheduler = LaunchMediaRecoveryDeadlineScheduler { delayMs, action ->
            val future = deadlineExecutor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            LaunchMediaRecoveryDeadlineCancellation { future.cancel(false) }
        },
        // Recovery is off the Camera2 startup path and may legitimately cross many 64-row pages.
        // Two minutes is deliberately conservative while still bounding a wedged provider Binder.
        deadlineMs = PROCESS_LAUNCH_MEDIA_RECOVERY_DEADLINE_MS,
    )

    fun request(
        owner: Any,
        recover: () -> MediaRecoveryCompletion,
        onComplete: (Result<MediaRecoveryCompletion>) -> Unit,
    ): LaunchMediaRecoverySubscription = coordinator.request(owner, recover, onComplete)
}

internal const val PROCESS_LAUNCH_MEDIA_RECOVERY_DEADLINE_MS = 120_000L

/** Pages clean work to completion while bounding retries of a failing durable provider operation. */
internal fun executeLaunchMediaRecovery(
    maxFailureAttempts: Int,
    backoff: (attempt: Int) -> Unit = {},
    recoverBatch: (OrphanRecoveryCursor) -> OrphanRecoveryBatch,
): MediaRecoveryCompletion {
    var cursor = OrphanRecoveryCursor()
    var cumulative = RecoveryReport()
    var attempts = 0
    var consecutiveFailures = 0
    var exhaustedProgressFailures = emptySet<RecoveryFailureClass>()
    while (true) {
        attempts += 1
        val batch = recoverBatch(cursor)
        cumulative = cumulative.foldRecoveryAttempt(batch.report)
        if (batch.report.retryRequired) {
            consecutiveFailures += 1
            val decision = recoveryRetryDecision(
                report = batch.report,
                completedAttempts = consecutiveFailures,
                maxAttempts = maxFailureAttempts,
            )
            if (decision == RecoveryRetryDecision.EXHAUSTED) {
                if (!batch.continueAfterFailureExhaustion) {
                    return MediaRecoveryCompletion(cumulative, attempts, decision)
                }
                // Exact durable DISCARD entries are independent. After bounded retries, retain the
                // failed marker for a later launch but advance to the next page so one wedged row
                // cannot starve every lexicographically later delete forever.
                exhaustedProgressFailures += batch.report.failureClasses
                consecutiveFailures = 0
                cursor = batch.nextCursor
                if (!batch.hasMore) {
                    return MediaRecoveryCompletion(
                        cumulative.copy(failureClasses = exhaustedProgressFailures),
                        attempts,
                        RecoveryRetryDecision.EXHAUSTED,
                    )
                }
                continue
            }
            backoff(consecutiveFailures)
            continue
        }
        consecutiveFailures = 0
        cursor = batch.nextCursor
        if (!batch.hasMore) {
            val terminalReport = if (exhaustedProgressFailures.isEmpty()) {
                cumulative
            } else {
                cumulative.copy(failureClasses = exhaustedProgressFailures)
            }
            return MediaRecoveryCompletion(
                terminalReport,
                attempts,
                if (exhaustedProgressFailures.isEmpty()) {
                    RecoveryRetryDecision.COMPLETE
                } else {
                    RecoveryRetryDecision.EXHAUSTED
                },
            )
        }
    }
}
