package me.hletrd.telecampro.camera

import android.content.Context
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CurrentProcessFamilyRetirementScan
import me.hletrd.telecampro.storage.FamilyDeletionRetirementResult
import me.hletrd.telecampro.storage.MediaStoreWriter

internal enum class RetainedStillDiscardDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

/**
 * Per-Engine admission facade over the one process-lifetime deleted-still provider lane.
 *
 * Engine shutdown rejects only new work from that stale facade. Accepted tasks retain their exact
 * deletion-owner identity and finish on the shared finite capacity; neither overflow nor shutdown
 * runs provider work inline. The already-durable capture-family tombstone remains launch recovery's
 * owner when admission is refused.
 */
internal class RetainedStillDiscardDispatcher internal constructor(
    private val capacityOwner: RetainedStillDiscardCapacityOwner,
) {
    private val admissionLock = Any()
    private var accepting = true

    constructor(workerCount: Int, backlogCapacity: Int) : this(
        ProcessRetainedStillDiscardOwner.capacity(workerCount, backlogCapacity),
    )

    internal constructor(
        workerCount: Int,
        backlogCapacity: Int,
        threadFactory: ThreadFactory,
    ) : this(RetainedStillDiscardCapacityOwner(workerCount, backlogCapacity, threadFactory))

    fun dispatch(task: Runnable): RetainedStillDiscardDispatch = synchronized(admissionLock) {
        if (!accepting) RetainedStillDiscardDispatch.SHUTDOWN else capacityOwner.dispatch(task)
    }

    /**
     * Retirement overflow is different from an ordinary retained-row discard: the durable family
     * marker is intentionally invisible to launch recovery for the rest of this process. Retain one
     * conflated process rescan signal so a worker completion rechecks every current-process marker.
     */
    fun dispatchRetirement(
        task: Runnable,
        overflowRescan: Runnable,
    ): RetainedStillDiscardDispatch = synchronized(admissionLock) {
        if (!accepting) {
            RetainedStillDiscardDispatch.SHUTDOWN
        } else {
            capacityOwner.dispatchRetirement(task, overflowRescan)
        }
    }

    fun shutdown() {
        synchronized(admissionLock) { accepting = false }
    }

    internal fun activeTaskCount(): Int = capacityOwner.activeTaskCount()

    internal fun queuedTaskCount(): Int = capacityOwner.queuedTaskCount()
}

/** The only worker/queue capacity behind every Engine's teardown-discard facade. */
internal class RetainedStillDiscardCapacityOwner(
    workerCount: Int,
    backlogCapacity: Int,
    threadFactory: ThreadFactory = retainedStillDiscardThreadFactory(),
) {
    private val executor: ThreadPoolExecutor
    private val retirementRetryLock = Any()
    private var pendingRetirementRescan: Runnable? = null
    private var retirementRescanAccepted = false

    init {
        require(workerCount > 0)
        require(backlogCapacity > 0)
        executor = ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(backlogCapacity),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    fun dispatch(task: Runnable): RetainedStillDiscardDispatch = try {
        executor.execute(workerTask(task))
        RetainedStillDiscardDispatch.ACCEPTED
    } catch (_: RejectedExecutionException) {
        RetainedStillDiscardDispatch.OVERFLOW
    }

    /**
     * Keeps constant overflow memory: all retirement failures request the same semantic operation,
     * a fresh bounded scan of current-process durable markers. The newest request replaces the
     * pending closure, while an already accepted rescan is followed by at most one more rescan.
     */
    fun dispatchRetirement(
        task: Runnable,
        overflowRescan: Runnable,
    ): RetainedStillDiscardDispatch {
        val result = dispatch(task)
        if (result == RetainedStillDiscardDispatch.OVERFLOW) {
            synchronized(retirementRetryLock) {
                pendingRetirementRescan = overflowRescan
            }
            armRetirementRescan()
        }
        return result
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size

    internal fun retirementRescanCount(): Int = synchronized(retirementRetryLock) {
        (if (pendingRetirementRescan != null) 1 else 0) +
            (if (retirementRescanAccepted) 1 else 0)
    }

    private fun workerTask(task: Runnable): Runnable = Runnable {
        try {
            task.run()
        } finally {
            // ThreadPoolExecutor moves the next queued item to this worker only after run returns.
            // A direct submission can therefore still reject here; every later completion re-arms
            // the signal until a slot is authoritatively accepted.
            armRetirementRescan()
        }
    }

    private fun armRetirementRescan() {
        val rescan = synchronized(retirementRetryLock) {
            if (retirementRescanAccepted) return
            val pending = pendingRetirementRescan ?: return
            pendingRetirementRescan = null
            retirementRescanAccepted = true
            pending
        }
        try {
            executor.execute(
                Runnable {
                    try {
                        rescan.run()
                    } finally {
                        synchronized(retirementRetryLock) {
                            retirementRescanAccepted = false
                        }
                        armRetirementRescan()
                    }
                },
            )
        } catch (_: RejectedExecutionException) {
            synchronized(retirementRetryLock) {
                retirementRescanAccepted = false
                // A newer overflow request is already a complete rescan, so preserve it. Otherwise
                // restore this signal and let the next worker completion re-arm it.
                if (pendingRetirementRescan == null) pendingRetirementRescan = rescan
            }
        }
    }
}

internal const val RETAINED_STILL_DISCARD_WORKER_COUNT = 2
internal const val RETAINED_STILL_DISCARD_BACKLOG_CAPACITY = 8
internal const val RETAINED_STILL_RETIREMENT_MAX_LISTENERS_PER_FAMILY = 4
internal const val RETAINED_STILL_RETIREMENT_RETRY_INITIAL_MS = 250L
internal const val RETAINED_STILL_RETIREMENT_RETRY_MAX_MS = 30_000L

/** Exact local bookkeeping owner notified after one durable family marker is retired. */
internal fun interface RetainedStillRetirementListener {
    fun onFamilyRetired(family: CaptureFamilyKey)
}

internal enum class RetainedStillRetirementRegistrationResult {
    REGISTERED,
    ALREADY_REGISTERED,
    CAPACITY_EXHAUSTED,
}

/**
 * Bounded process authority joining durable marker retirement to every exact local Engine owner.
 *
 * Registration precedes the durable marker commit, so a process scan can never observe a marker
 * whose local continuation is still unknown. [maxFamilies] is measured in the same distinct-key
 * unit as the durable journal; [maxListenersPerFamily] independently bounds Engine-generation
 * fan-out. Listener identity makes repeated deletion callbacks idempotent. A retired family is
 * removed before delivery; duplicate/late results therefore do no work, while a listener failure
 * cannot prevent another Engine owner from reconciling.
 */
internal class RetainedStillRetirementRegistry(
    private val maxFamilies: Int,
    private val maxListenersPerFamily: Int,
) {
    private val lock = Any()
    private val listenersByFamily = LinkedHashMap<
        CaptureFamilyKey,
        IdentityHashMap<RetainedStillRetirementListener, Unit>,
    >()
    private var registrationCount = 0

    init {
        require(maxFamilies > 0)
        require(maxListenersPerFamily > 0)
    }

    fun register(
        family: CaptureFamilyKey,
        listener: RetainedStillRetirementListener,
    ): RetainedStillRetirementRegistrationResult = synchronized(lock) {
        val listeners = listenersByFamily[family]
        if (listeners?.containsKey(listener) == true) {
            return@synchronized RetainedStillRetirementRegistrationResult.ALREADY_REGISTERED
        }
        if (listeners == null && listenersByFamily.size >= maxFamilies) {
            return@synchronized RetainedStillRetirementRegistrationResult.CAPACITY_EXHAUSTED
        }
        if (listeners != null && listeners.size >= maxListenersPerFamily) {
            return@synchronized RetainedStillRetirementRegistrationResult.CAPACITY_EXHAUSTED
        }
        val familyListeners = listeners ?: IdentityHashMap<RetainedStillRetirementListener, Unit>()
            .also { listenersByFamily[family] = it }
        familyListeners[listener] = Unit
        registrationCount += 1
        RetainedStillRetirementRegistrationResult.REGISTERED
    }

    /** Rolls back only a registration newly installed for a marker commit that then failed. */
    fun unregister(
        family: CaptureFamilyKey,
        listener: RetainedStillRetirementListener,
    ): Boolean = synchronized(lock) {
        val listeners = listenersByFamily[family] ?: return@synchronized false
        if (listeners.remove(listener) == null) return@synchronized false
        registrationCount -= 1
        if (listeners.isEmpty()) listenersByFamily.remove(family)
        true
    }

    /** Demotes one released Engine without disturbing marker-only or replacement-Engine owners. */
    fun unregisterListener(listener: RetainedStillRetirementListener): Int = synchronized(lock) {
        var removed = 0
        val families = listenersByFamily.entries.iterator()
        while (families.hasNext()) {
            val entry = families.next()
            if (entry.value.remove(listener) != null) {
                registrationCount -= 1
                removed += 1
            }
            if (entry.value.isEmpty()) families.remove()
        }
        removed
    }

    fun reconcile(
        family: CaptureFamilyKey,
        result: FamilyDeletionRetirementResult,
    ): Int {
        if (result != FamilyDeletionRetirementResult.RETIRED &&
            result != FamilyDeletionRetirementResult.ALREADY_ABSENT
        ) return 0
        val listeners = synchronized(lock) {
            val removed = listenersByFamily.remove(family)?.keys?.toList().orEmpty()
            registrationCount -= removed.size
            removed
        }
        listeners.forEach { listener -> runCatching { listener.onFamilyRetired(family) } }
        return listeners.size
    }

    internal fun registrationCount(): Int = synchronized(lock) { registrationCount }

    internal fun familyCount(): Int = synchronized(lock) { listenersByFamily.size }
}

/**
 * Constant-memory delayed owner for retryable current-process retirement scans.
 *
 * New requests replace the pending scan while one deadline is armed. Each accepted deadline
 * advances an exponential delay up to [maxDelayMs]; a scan with no retryable outcomes resets it.
 * Provider work is submitted to the existing finite discard lane rather than run on this timer.
 */
internal class RetainedStillRetirementRetryOwner(
    private val initialDelayMs: Long,
    private val maxDelayMs: Long,
    private val schedule: (delayMs: Long, task: Runnable) -> Boolean,
    private val submit: (Runnable) -> RetainedStillDiscardDispatch,
) {
    private val lock = Any()
    private var pending: Runnable? = null
    private var deadlineArmed = false
    private var nextDelayMs = initialDelayMs

    init {
        require(initialDelayMs > 0L)
        require(maxDelayMs >= initialDelayMs)
    }

    fun request(task: Runnable): Boolean {
        val delay = synchronized(lock) {
            pending = task
            if (deadlineArmed) return true
            deadlineArmed = true
            val selected = nextDelayMs
            nextDelayMs = (nextDelayMs * 2L).coerceAtMost(maxDelayMs)
            selected
        }
        val accepted = runCatching {
            schedule(delay, Runnable { fire() })
        }.getOrDefault(false)
        if (!accepted) synchronized(lock) { deadlineArmed = false }
        return accepted
    }

    fun resetBackoff() = synchronized(lock) {
        nextDelayMs = initialDelayMs
    }

    private fun fire() {
        val task = synchronized(lock) {
            deadlineArmed = false
            pending.also { pending = null }
        } ?: return
        submit(task)
    }

    internal fun pendingCount(): Int = synchronized(lock) {
        if (pending != null || deadlineArmed) 1 else 0
    }

    internal fun nextDelayMs(): Long = synchronized(lock) { nextDelayMs }
}

/**
 * One finite dispatch boundary for deletion retirement, including a producer that becomes terminal
 * after its old Engine facade has closed. Overflow never falls back inline; its process-conflated
 * rescan owns the in-process retry while the durable marker remains the safety boundary.
 */
internal fun dispatchDeletedFamilyRetirement(
    facade: RetainedStillDiscardDispatcher,
    task: Runnable,
    overflowRescan: Runnable,
): RetainedStillDiscardDispatch = when (val result = facade.dispatchRetirement(task, overflowRescan)) {
    RetainedStillDiscardDispatch.SHUTDOWN ->
        ProcessRetainedStillDiscardOwner.dispatchRegisteredProducerTerminal(task, overflowRescan)
    else -> result
}

/** Process-lifetime capacity prevents blocked ContentResolver calls multiplying with Engines. */
internal object ProcessRetainedStillDiscardOwner {
    private val capacityOwner = RetainedStillDiscardCapacityOwner(
        workerCount = RETAINED_STILL_DISCARD_WORKER_COUNT,
        backlogCapacity = RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
    )
    private val retirementRegistry = RetainedStillRetirementRegistry(
        maxFamilies = MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS,
        maxListenersPerFamily = RETAINED_STILL_RETIREMENT_MAX_LISTENERS_PER_FAMILY,
    )
    private val markerOnlyRetirementListener = RetainedStillRetirementListener { }
    private val retirementRetryScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "retained-still-retirement-retry").apply { isDaemon = true }
    }
    private val retirementRetryOwner = RetainedStillRetirementRetryOwner(
        initialDelayMs = RETAINED_STILL_RETIREMENT_RETRY_INITIAL_MS,
        maxDelayMs = RETAINED_STILL_RETIREMENT_RETRY_MAX_MS,
        schedule = { delayMs, task ->
            runCatching {
                retirementRetryScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            }.isSuccess
        },
        submit = { task -> capacityOwner.dispatchRetirement(task, task) },
    )

    fun capacity(workerCount: Int, backlogCapacity: Int): RetainedStillDiscardCapacityOwner {
        require(workerCount == RETAINED_STILL_DISCARD_WORKER_COUNT) {
            "Process retained-still discard worker count must be $RETAINED_STILL_DISCARD_WORKER_COUNT"
        }
        require(backlogCapacity == RETAINED_STILL_DISCARD_BACKLOG_CAPACITY) {
            "Process retained-still discard backlog must be $RETAINED_STILL_DISCARD_BACKLOG_CAPACITY"
        }
        return capacityOwner
    }

    /**
     * Installs local continuation ownership before the durable marker can become visible. A family
     * without live Engine bookkeeping still registers the process marker-only sentinel so every
     * current-process marker participates in the same bounded authority.
     */
    fun registerFamilyRetirement(
        family: CaptureFamilyKey,
        listener: RetainedStillRetirementListener?,
    ): RetainedStillRetirementRegistrationResult = retirementRegistry.register(
        family,
        listener ?: markerOnlyRetirementListener,
    )

    fun rollbackFamilyRetirementRegistration(
        family: CaptureFamilyKey,
        listener: RetainedStillRetirementListener?,
        registration: RetainedStillRetirementRegistrationResult,
    ) {
        if (registration == RetainedStillRetirementRegistrationResult.REGISTERED) {
            retirementRegistry.unregister(family, listener ?: markerOnlyRetirementListener)
        }
    }

    /** A released Engine no longer needs admission publication and must not stay process-reachable. */
    fun releaseFamilyRetirementListener(listener: RetainedStillRetirementListener): Int =
        retirementRegistry.unregisterListener(listener)

    /** Exact and scan completions share this idempotent local-publication boundary. */
    fun reconcileFamilyRetirement(
        family: CaptureFamilyKey,
        result: FamilyDeletionRetirementResult,
    ): Int = retirementRegistry.reconcile(family, result)

    /** Process-only closure: delayed/overflow retries never capture an Engine or its callback graph. */
    fun currentProcessRetirementRescan(context: Context): Runnable {
        val applicationContext = context.applicationContext
        return Runnable { runCurrentProcessRetirementRescan(applicationContext) }
    }

    /** Arms one conflated delayed retry after an accepted attempt returned retryable uncertainty. */
    fun requestRetirementRetry(context: Context): Boolean =
        retirementRetryOwner.request(currentProcessRetirementRescan(context))

    private fun runCurrentProcessRetirementRescan(context: Context) {
        val scan = MediaStoreWriter.retireCurrentProcessFamilyDeletionsResult(context)
        scan.results.forEach { (family, result) ->
            reconcileFamilyRetirement(family, result)
        }
        if (retirementScanRequiresRetry(scan)) {
            requestRetirementRetry(context)
        } else {
            retirementRetryOwner.resetBackoff()
        }
    }

    /**
     * A still-family producer registered before Engine release may become terminal only after its
     * old facade closes. Admit that already-owned retirement continuation directly to the same
     * finite process lane; overflow leaves the marker durable and requests the conflated rescan.
     */
    fun dispatchRegisteredProducerTerminal(
        task: Runnable,
        overflowRescan: Runnable,
    ): RetainedStillDiscardDispatch = capacityOwner.dispatchRetirement(task, overflowRescan)
}

/** Only uncertainty/contention retries on a timer; authoritative live rows wait for mutation. */
internal fun retirementRescanRequiresRetry(
    results: Iterable<FamilyDeletionRetirementResult>,
): Boolean = results.any { it == FamilyDeletionRetirementResult.RETRYABLE }

internal fun retirementScanRequiresRetry(scan: CurrentProcessFamilyRetirementScan): Boolean =
    scan.retryableFailure || retirementRescanRequiresRetry(scan.results.values)

private fun retainedStillDiscardThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "retained-still-discard-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
