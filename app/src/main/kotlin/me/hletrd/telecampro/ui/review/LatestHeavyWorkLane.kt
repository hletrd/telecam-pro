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
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fresh/replayable compressed input used by bounds, pixel, and EXIF decode stages. */
internal interface ReviewDecodeSource : Closeable {
    fun openInputStream(): InputStream
}

/**
 * Process-wide byte authority for immutable compressed review sources.
 *
 * Reservations grow with bytes actually accepted from the provider rather than pessimistically
 * claiming one complete per-source ceiling. The monitor covers arithmetic only; provider reads and
 * cache-file writes never run while it is held. A blocked/retired worker therefore keeps accounting
 * for exactly the bytes it still owns until its synchronous work can reach cleanup.
 */
internal class ReviewSourceByteBudget(private val maxBytes: Long) {
    init {
        require(maxBytes > 0L) { "review source budget must be positive" }
    }

    private var usedBytes = 0L

    internal inner class Lease internal constructor() : Closeable {
        private var reservedBytes = 0L
        private var closed = false

        fun tryGrow(byteCount: Int): Boolean = synchronized(this@ReviewSourceByteBudget) {
            if (closed || byteCount <= 0) return@synchronized false
            val delta = byteCount.toLong()
            if (usedBytes > maxBytes - delta) return@synchronized false
            usedBytes += delta
            reservedBytes += delta
            true
        }

        override fun close() = synchronized(this@ReviewSourceByteBudget) {
            if (closed) return@synchronized
            check(reservedBytes in 0L..usedBytes) { "review source reservation accounting diverged" }
            usedBytes -= reservedBytes
            reservedBytes = 0L
            closed = true
        }
    }

    fun openLease(): Lease = Lease()

    /** Focused test/diagnostic seam; production admission never branches on this snapshot. */
    internal fun usedBytes(): Long = synchronized(this) { usedBytes }
}

/**
 * One private, read-only compressed source used for bounds, pixels, and EXIF.
 *
 * The provider stream is consumed once into a cache file. Later decoder stages receive fresh
 * read-only streams over those same immutable bytes, avoiding both provider TOCTOU and the former
 * ByteArrayOutputStream + toByteArray heap duplication. Closing is exactly-once and releases both
 * the cache file and its process byte reservation.
 */
internal class ReviewSourceSpool(
    private val file: File,
    val sizeBytes: Long,
    private val lease: ReviewSourceByteBudget.Lease,
    private val cleanup: ReviewSpoolCleanupReservation,
) : ReviewDecodeSource {
    private val closed = AtomicBoolean(false)

    override fun openInputStream(): InputStream {
        check(!closed.get()) { "review source spool is closed" }
        return Channels.newInputStream(
            Files.newByteChannel(
                file.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
    }

    internal fun exists(): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Typed absence truth for callers/tests that must distinguish deletion from retained cleanup. */
    internal fun closeWithResult(): ReviewSpoolDeleteDisposition {
        if (!closed.compareAndSet(false, true)) return cleanup.disposition()
        return cleanup.retire(file, lease)
    }

    override fun close() {
        closeWithResult()
    }
}

internal const val REVIEW_SOURCE_MAX_BYTES = 64L * 1024L * 1024L
internal const val REVIEW_TRUSTED_SOURCE_MAX_BYTES = 512L * 1024L * 1024L
internal const val REVIEW_SOURCE_PROCESS_MAX_BYTES = 2L * REVIEW_TRUSTED_SOURCE_MAX_BYTES
internal const val REVIEW_SPOOL_DIRECTORY_NAME = "review-sources-v1"
internal const val REVIEW_STALE_SPOOL_SCAN_LIMIT = 64
internal const val REVIEW_SPOOL_CLEANUP_CAPACITY = 16
private const val REVIEW_TRUSTED_DISK_SHARE = 4L
private const val REVIEW_SPOOL_PREFIX = "review-source-"
private val REVIEW_SPOOL_FILE = Regex("^review-source-[0-9a-f]{16}-[0-9]+\\.bin$")

internal data class ReviewSpoolLocation(
    val directory: File,
    val filePrefix: String,
)

internal data class ReviewSpoolCleanupReport(
    val examined: Int,
    val deleted: Int,
)

internal enum class ReviewSpoolDeleteDisposition {
    /** The exact no-follow path is authoritatively absent and byte accounting was released. */
    ABSENT,

    /** Deletion failed; the finite process owner retains the exact path and byte lease for retry. */
    RETAINED,

    /** No finite cleanup slot was available, so creation was refused before a file existed. */
    CAPACITY_EXHAUSTED,
}

internal data class ReviewSpoolRetryReport(val attempted: Int, val deleted: Int, val retained: Int)

/**
 * Finite same-process owner for live spools and exact deletion failures.
 *
 * Capacity is reserved before file creation. A false/throwing delete therefore cannot make the
 * source disappear from accounting: its reservation and byte lease stay strongly owned until a
 * bounded retry proves the no-follow path absent. Once every slot is live/retained, new spools fail
 * closed before consuming provider bytes or creating another cache file.
 */
internal class ReviewSpoolCleanupOwner(
    private val capacity: Int = REVIEW_SPOOL_CLEANUP_CAPACITY,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    private val lock = Any()
    private val unresolved = LinkedHashSet<ReviewSpoolCleanupReservation>()
    private var admitted = 0

    init {
        require(capacity > 0) { "review spool cleanup capacity must be positive" }
    }

    fun reserve(): ReviewSpoolCleanupReservation? {
        // Every new review request is a bounded retry edge for prior local deletion failures. This
        // runs on the finite review worker before provider consumption; at most [capacity] exact
        // paths are touched, and a still-live orphan keeps its slot/accounting fail-closed.
        retryUnresolved()
        return synchronized(lock) {
            if (admitted >= capacity) null
            else ReviewSpoolCleanupReservation(this, deleteFile).also { admitted++ }
        }
    }

    internal fun retain(reservation: ReviewSpoolCleanupReservation) = synchronized(lock) {
        unresolved.add(reservation)
    }

    internal fun release(reservation: ReviewSpoolCleanupReservation) = synchronized(lock) {
        unresolved.remove(reservation)
        check(admitted > 0) { "review spool cleanup admission underflow" }
        admitted--
    }

    fun retryUnresolved(): ReviewSpoolRetryReport {
        val snapshot = synchronized(lock) { unresolved.toList() }
        var deleted = 0
        snapshot.forEach { if (it.retry() == ReviewSpoolDeleteDisposition.ABSENT) deleted++ }
        return ReviewSpoolRetryReport(
            attempted = snapshot.size,
            deleted = deleted,
            retained = synchronized(lock) { unresolved.size },
        )
    }

    internal fun admittedCount(): Int = synchronized(lock) { admitted }
    internal fun unresolvedCount(): Int = synchronized(lock) { unresolved.size }
}

internal class ReviewSpoolCleanupReservation internal constructor(
    private val owner: ReviewSpoolCleanupOwner,
    private val deleteFile: (File) -> Boolean,
) {
    private enum class State { LIVE, RETIRING, RETAINED, ABSENT }

    private var state = State.LIVE
    private var retainedFile: File? = null
    private var retainedLease: ReviewSourceByteBudget.Lease? = null

    fun disposition(): ReviewSpoolDeleteDisposition = synchronized(this) {
        if (state == State.ABSENT) ReviewSpoolDeleteDisposition.ABSENT
        else ReviewSpoolDeleteDisposition.RETAINED
    }

    /** Releases a reservation that never created a file or admitted any bytes. */
    fun cancel(lease: ReviewSourceByteBudget.Lease): ReviewSpoolDeleteDisposition {
        val release = synchronized(this) {
            if (state != State.LIVE) false else {
                state = State.ABSENT
                true
            }
        }
        if (release) {
            lease.close()
            owner.release(this)
        }
        return disposition()
    }

    fun retire(
        file: File,
        lease: ReviewSourceByteBudget.Lease,
    ): ReviewSpoolDeleteDisposition {
        val ownsAttempt = synchronized(this) {
            if (state != State.LIVE) false else {
                state = State.RETIRING
                true
            }
        }
        if (!ownsAttempt) return disposition()
        return completeDeleteAttempt(file, lease)
    }

    internal fun retry(): ReviewSpoolDeleteDisposition {
        val retry = synchronized(this) {
            if (state != State.RETAINED) null else {
                state = State.RETIRING
                checkNotNull(retainedFile) to checkNotNull(retainedLease)
            }
        } ?: return disposition()
        return completeDeleteAttempt(retry.first, retry.second)
    }

    private fun completeDeleteAttempt(
        file: File,
        lease: ReviewSourceByteBudget.Lease,
    ): ReviewSpoolDeleteDisposition {
        runCatching { deleteFile(file) }
        val absent = !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        synchronized(this) {
            if (absent) {
                retainedFile = null
                retainedLease = null
                state = State.ABSENT
            } else {
                retainedFile = file
                retainedLease = lease
                state = State.RETAINED
            }
        }
        if (absent) {
            lease.close()
            owner.release(this)
            return ReviewSpoolDeleteDisposition.ABSENT
        }
        owner.retain(this)
        return ReviewSpoolDeleteDisposition.RETAINED
    }
}

/**
 * Process-generation owner for the one private spool directory.
 *
 * Preparation is synchronous and bounded before the first unverified-source admission. Only stale
 * regular files matching the exact spool schema are deleted without following links; unrelated cache
 * content, unexpected entry types, and this process generation's live files are never touched.
 */
internal class ReviewSpoolDirectoryOwner(
    private val processToken: String = UUID.randomUUID().toString().replace("-", "").take(16),
    private val scanLimit: Int = REVIEW_STALE_SPOOL_SCAN_LIMIT,
    /** Deterministic create-race seam; production leaves it empty. */
    private val beforeCreateDirectory: ((java.nio.file.Path) -> Unit)? = null,
) {
    init {
        require(processToken.matches(Regex("[0-9a-f]{16}"))) { "review spool token must be 16 hex characters" }
        require(scanLimit > 0) { "review stale-spool scan limit must be positive" }
    }

    private var prepared: ReviewSpoolLocation? = null
    private var cleanupReport = ReviewSpoolCleanupReport(0, 0)

    @Synchronized
    fun prepare(cacheRoot: File): ReviewSpoolLocation? {
        prepared?.let { return it }
        val directory = File(cacheRoot, REVIEW_SPOOL_DIRECTORY_NAME)
        val path = directory.toPath()
        val attributes = runCatching {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                beforeCreateDirectory?.invoke(path)
                Files.createDirectory(path)
            }
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.recoverCatching { failure ->
            if (failure !is FileAlreadyExistsException) throw failure
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!attributes.isDirectory || attributes.isSymbolicLink) return null

        val livePrefix = "$REVIEW_SPOOL_PREFIX$processToken-"
        var examined = 0
        var deleted = 0
        runCatching {
            Files.newDirectoryStream(path).use { entries ->
                val iterator = entries.iterator()
                while (examined < scanLimit && iterator.hasNext()) {
                    val entry = iterator.next()
                    examined++
                    val name = entry.fileName.toString()
                    if (name.startsWith(livePrefix) || !REVIEW_SPOOL_FILE.matches(name)) continue
                    val entryAttributes = runCatching {
                        Files.readAttributes(
                            entry,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                    }.getOrNull() ?: continue
                    if (!entryAttributes.isRegularFile || entryAttributes.isSymbolicLink) continue
                    if (runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)) deleted++
                }
            }
        }
        cleanupReport = ReviewSpoolCleanupReport(examined, deleted)
        return ReviewSpoolLocation(directory, livePrefix).also { prepared = it }
    }

    /** Focused diagnostic/test seam; production behavior never branches on this snapshot. */
    @Synchronized
    internal fun cleanupReport(): ReviewSpoolCleanupReport = cleanupReport
}

/** Two maximum-size sources may coexist; smaller thumbnail/full requests share the residual bytes. */
internal val processReviewSourceBudget = ReviewSourceByteBudget(REVIEW_SOURCE_PROCESS_MAX_BYTES)
internal val processReviewSpoolDirectoryOwner = ReviewSpoolDirectoryOwner()
internal val processReviewSpoolCleanupOwner = ReviewSpoolCleanupOwner()

/**
 * A trusted app-owned still may be a full-sensor JPEG larger than the strict unverified ceiling.
 * Admit at most one quarter of currently usable cache storage, capped at a bounded trusted
 * snapshot. Even all four finite review workers cannot consume more than the free space they each
 * observed, and the exact process byte budget independently admits at most two hard-cap sources.
 */
internal fun trustedReviewSourceMaxBytes(usableBytes: Long): Long {
    if (usableBytes <= 0L) return 0L
    return (usableBytes / REVIEW_TRUSTED_DISK_SHARE).coerceAtMost(REVIEW_TRUSTED_SOURCE_MAX_BYTES)
}

/**
 * Copies one provider source into an immutable private spool with per-source and process bounds.
 * Every failure path deletes the partial file and releases the exact bytes already admitted.
 */
internal sealed interface ReviewSpoolBuildResult {
    data class Ready(val source: ReviewSourceSpool) : ReviewSpoolBuildResult
    data class Failed(val cleanup: ReviewSpoolDeleteDisposition) : ReviewSpoolBuildResult
}

private object ReviewSpoolWriteRefused : RuntimeException(null, null, false, false)

internal fun spoolReviewSourceResult(
    cacheDirectory: File,
    input: InputStream,
    maxBytes: Long = REVIEW_SOURCE_MAX_BYTES,
    budget: ReviewSourceByteBudget = processReviewSourceBudget,
    filePrefix: String = REVIEW_SPOOL_PREFIX,
    cleanupOwner: ReviewSpoolCleanupOwner = processReviewSpoolCleanupOwner,
): ReviewSpoolBuildResult {
    if (maxBytes <= 0L) return ReviewSpoolBuildResult.Failed(ReviewSpoolDeleteDisposition.ABSENT)
    val cleanup = cleanupOwner.reserve()
        ?: return ReviewSpoolBuildResult.Failed(ReviewSpoolDeleteDisposition.CAPACITY_EXHAUSTED)
    val lease = budget.openLease()
    var spoolFile: File? = null
    fun failed(): ReviewSpoolBuildResult.Failed {
        val disposition = spoolFile?.let { cleanup.retire(it, lease) } ?: cleanup.cancel(lease)
        return ReviewSpoolBuildResult.Failed(disposition)
    }
    try {
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) return failed()
        val file = File.createTempFile(filePrefix, ".bin", cacheDirectory)
        spoolFile = file
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        Files.newOutputStream(
            file.toPath(),
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    if (total >= maxBytes || !lease.tryGrow(1)) throw ReviewSpoolWriteRefused
                    output.write(single)
                    total++
                    continue
                }
                if (total > maxBytes - read || !lease.tryGrow(read)) throw ReviewSpoolWriteRefused
                output.write(buffer, 0, read)
                total += read
            }
        }
        if (!file.setReadOnly()) return failed()
        return ReviewSpoolBuildResult.Ready(ReviewSourceSpool(file, total, lease, cleanup))
    } catch (_: Throwable) {
        return failed()
    }
}

internal fun spoolReviewSource(
    cacheDirectory: File,
    input: InputStream,
    maxBytes: Long = REVIEW_SOURCE_MAX_BYTES,
    budget: ReviewSourceByteBudget = processReviewSourceBudget,
    filePrefix: String = REVIEW_SPOOL_PREFIX,
    cleanupOwner: ReviewSpoolCleanupOwner = processReviewSpoolCleanupOwner,
): ReviewSourceSpool? = when (val result = spoolReviewSourceResult(
    cacheDirectory,
    input,
    maxBytes,
    budget,
    filePrefix,
    cleanupOwner,
)) {
    is ReviewSpoolBuildResult.Ready -> result.source
    is ReviewSpoolBuildResult.Failed -> null
}

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
