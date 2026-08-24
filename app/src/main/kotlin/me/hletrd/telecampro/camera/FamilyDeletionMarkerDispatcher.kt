package me.hletrd.telecampro.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import me.hletrd.telecampro.storage.CaptureFamilyKey

internal enum class FamilyDeletionMarkerDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

internal data class FamilyDeletionMarkerAdmission(
    val dispatch: FamilyDeletionMarkerDispatch,
    val reservation: FamilyDeletionMarkerReservation? = null,
)

/**
 * Per-Engine dynamic completion owner for deletion attempts accepted by the process lane.
 *
 * Queued work captures only this registry plus a numeric token, never the ViewModel callback itself.
 * `detachCallbacks()` takes the same write lease, clears every not-yet-admitted callback, and drains
 * a callback that already won before returning. A wedged old task may therefore retain this compact
 * owner, but cannot retain or call the stale ViewModel graph after detach.
 */
internal class FamilyDeletionCompletionRegistry<R : Any> {
    internal data class Token(val id: Long)

    private val lock = ReentrantReadWriteLock(true)
    private val sequence = AtomicLong()
    private val callbacks = LinkedHashMap<Long, (R) -> Unit>()
    private var closed = false

    fun register(callback: (R) -> Unit): Token? = lock.write {
        if (closed) return@write null
        val token = Token(sequence.incrementAndGet())
        callbacks[token.id] = callback
        token
    }

    fun complete(token: Token, result: R): Boolean = lock.write {
        if (closed) return@write false
        val callback = callbacks.remove(token.id) ?: return@write false
        runCatching { callback(result) }
        true
    }

    fun cancel(token: Token): Boolean = lock.write { callbacks.remove(token.id) != null }

    fun closeAndDrain() = lock.write {
        closed = true
        callbacks.clear()
    }

    internal fun callbackCount(): Int = lock.read { callbacks.size }
}

/**
 * One pre-marker family admission reserved before CameraEngine publishes its in-memory tombstone.
 *
 * Reservation is deliberately separate from submission: overflow/shutdown can restore review before
 * any Engine delete state exists, while accepted work can snapshot that state and then enter the
 * process lane. Exactly one of [submit] or [cancel] releases this family's finite capacity.
 */
internal class FamilyDeletionMarkerReservation internal constructor(
    val family: CaptureFamilyKey,
    private val capacityOwner: FamilyDeletionMarkerCapacityOwner,
) {
    private val terminal = AtomicBoolean(false)

    fun submit(task: Runnable): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        return capacityOwner.submitReserved(family, task)
    }

    fun cancel(): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        capacityOwner.releaseReservation()
        return true
    }
}

/** Per-Engine admission facade over one process-lifetime family-marker capacity owner. */
internal class FamilyDeletionMarkerDispatcher internal constructor(
    private val capacityOwner: FamilyDeletionMarkerCapacityOwner,
) {
    private val admissionLock = Any()
    private var accepting = true

    constructor(workerCount: Int, backlogCapacity: Int) : this(
        ProcessFamilyDeletionMarkerOwner.capacity(workerCount, backlogCapacity),
    )

    internal constructor(
        workerCount: Int,
        backlogCapacity: Int,
        threadFactory: ThreadFactory,
    ) : this(FamilyDeletionMarkerCapacityOwner(workerCount, backlogCapacity, threadFactory))

    fun reserve(family: CaptureFamilyKey): FamilyDeletionMarkerAdmission = synchronized(admissionLock) {
        if (!accepting) {
            FamilyDeletionMarkerAdmission(FamilyDeletionMarkerDispatch.SHUTDOWN)
        } else {
            capacityOwner.reserve(family)?.let {
                FamilyDeletionMarkerAdmission(FamilyDeletionMarkerDispatch.ACCEPTED, it)
            } ?: FamilyDeletionMarkerAdmission(FamilyDeletionMarkerDispatch.OVERFLOW)
        }
    }

    fun shutdown() {
        // Accepted reservations/tasks retain their exact family and completion identity. Only new
        // work from this stale Engine is refused; the process owner itself is never shut down.
        synchronized(admissionLock) { accepting = false }
    }

    internal fun activeTaskCount(): Int = capacityOwner.activeTaskCount()

    internal fun queuedTaskCount(): Int = capacityOwner.queuedTaskCount()

    internal fun admittedFamilyCount(): Int = capacityOwner.admittedFamilyCount()
}

/** The only active+queued pre-marker capacity shared by all Engine generations. */
internal class FamilyDeletionMarkerCapacityOwner(
    private val workerCount: Int,
    private val backlogCapacity: Int,
    threadFactory: ThreadFactory = familyDeletionMarkerThreadFactory(),
) {
    private val executor: ThreadPoolExecutor
    private val admission = Semaphore(workerCount + backlogCapacity, true)

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

    fun reserve(family: CaptureFamilyKey): FamilyDeletionMarkerReservation? =
        if (admission.tryAcquire()) FamilyDeletionMarkerReservation(family, this) else null

    internal fun submitReserved(family: CaptureFamilyKey, task: Runnable): Boolean = try {
        executor.execute(
            Runnable {
                try {
                    task.run()
                } finally {
                    releaseReservation()
                }
            },
        )
        true
    } catch (_: RejectedExecutionException) {
        // The semaphore matches worker+queue cardinality, so production reaches this only if the
        // executor itself becomes unavailable. Return ownership to the caller rather than running
        // marker/provider work inline on a UI/camera thread.
        releaseReservation()
        false
    }

    internal fun releaseReservation() {
        admission.release()
        check(admission.availablePermits() <= workerCount + backlogCapacity) {
            "Family deletion marker reservation overflow"
        }
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size

    internal fun admittedFamilyCount(): Int = workerCount + backlogCapacity - admission.availablePermits()

    /** Isolated-owner seam for proving executor rejection returns capacity without inline work. */
    internal fun shutdownNowForTest() {
        executor.shutdownNow()
    }
}

internal const val FAMILY_DELETION_MARKER_WORKER_COUNT = 1
internal const val FAMILY_DELETION_MARKER_BACKLOG_CAPACITY = 31

/** Process lifetime bounds blocked marker/preferences calls across Engine and Activity replacement. */
internal object ProcessFamilyDeletionMarkerOwner {
    private val capacityOwner = FamilyDeletionMarkerCapacityOwner(
        workerCount = FAMILY_DELETION_MARKER_WORKER_COUNT,
        backlogCapacity = FAMILY_DELETION_MARKER_BACKLOG_CAPACITY,
    )

    fun capacity(workerCount: Int, backlogCapacity: Int): FamilyDeletionMarkerCapacityOwner {
        require(workerCount == FAMILY_DELETION_MARKER_WORKER_COUNT) {
            "Process family-marker worker count must be $FAMILY_DELETION_MARKER_WORKER_COUNT"
        }
        require(backlogCapacity == FAMILY_DELETION_MARKER_BACKLOG_CAPACITY) {
            "Process family-marker backlog must be $FAMILY_DELETION_MARKER_BACKLOG_CAPACITY"
        }
        return capacityOwner
    }
}

private fun familyDeletionMarkerThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "family-deletion-marker-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}
