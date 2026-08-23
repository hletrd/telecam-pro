package me.hletrd.telecampro.camera

import java.util.IdentityHashMap
import java.util.concurrent.Executors
import me.hletrd.telecampro.storage.OrphanRecoveryBatch
import me.hletrd.telecampro.storage.OrphanRecoveryCursor
import me.hletrd.telecampro.storage.RecoveryReport
import me.hletrd.telecampro.storage.RecoveryFailureClass
import me.hletrd.telecampro.storage.RecoveryRetryDecision
import me.hletrd.telecampro.storage.recoveryRetryDecision

internal fun interface LaunchMediaRecoverySubscription {
    fun cancel()
}

/**
 * Process-wide single-flight owner for launch recovery. Engine recreation replaces/cancels only its
 * subscriber; it can neither start another provider scan nor interrupt the scan already preserving
 * prior-process media. Completion is delivered once to every still-live Engine subscriber.
 */
internal class LaunchMediaRecoveryCoordinator<T : Any>(
    private val dispatch: (Runnable) -> Boolean,
) {
    private val lock = Any()
    private val subscribers = IdentityHashMap<Any, (T) -> Unit>()
    private var running = false

    fun request(
        owner: Any,
        recover: () -> T,
        onComplete: (T) -> Unit,
    ): LaunchMediaRecoverySubscription {
        val start = synchronized(lock) {
            subscribers[owner] = onComplete
            if (running) false else {
                running = true
                true
            }
        }
        if (start) {
            val accepted = runCatching {
                dispatch(Runnable {
                    val result = runCatching(recover)
                    val completions = synchronized(lock) {
                        running = false
                        subscribers.values.toList().also { subscribers.clear() }
                    }
                    result.onSuccess { recovered ->
                        completions.forEach { completion -> runCatching { completion(recovered) } }
                    }
                })
            }.getOrDefault(false)
            if (!accepted) {
                synchronized(lock) {
                    running = false
                    subscribers.remove(owner)
                }
                error("process launch-recovery dispatcher rejected its sole task")
            }
        }
        return LaunchMediaRecoverySubscription {
            synchronized(lock) { subscribers.remove(owner) }
        }
    }

    internal fun subscriberCount(): Int = synchronized(lock) { subscribers.size }
    internal fun isRunning(): Boolean = synchronized(lock) { running }
}

/** One daemon and zero per-Engine queues for every launch during this process lifetime. */
internal object ProcessLaunchMediaRecovery {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "media-recovery").apply { isDaemon = true }
    }
    private val coordinator = LaunchMediaRecoveryCoordinator<MediaRecoveryCompletion> { task ->
        runCatching { executor.execute(task) }.isSuccess
    }

    fun request(
        owner: Any,
        recover: () -> MediaRecoveryCompletion,
        onComplete: (MediaRecoveryCompletion) -> Unit,
    ): LaunchMediaRecoverySubscription = coordinator.request(owner, recover, onComplete)
}

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
