package me.hletrd.telecampro.camera

import me.hletrd.telecampro.storage.PendingOutputDiscardResult

/** Result of routing a completed private still through deleted-capture ownership. */
internal enum class RetainedStillDisposition {
    /** The capture is live; normal launch recovery may retain/adopt this private output. */
    RETAIN_FOR_RECOVERY,

    /** The capture was deleted; the Engine's durable discard path owns this output. */
    DISCARD_DELETED_CAPTURE,

    /** Neither immediate deletion nor durable launch-recovery ownership succeeded yet. */
    DISCARD_RETRY_PENDING,
}

/** Result of the Engine-owned publish boundary for one completed still output. */
internal enum class DeletedStillPublication {
    LIVE_PUBLISHED,
    LIVE_PUBLICATION_FAILED,
    DISCARD_DELETED_CAPTURE,
    DISCARD_RETRY_PENDING,
}

/**
 * Callback-independent owner of the deleted-family veto for late retained still outputs.
 *
 * The ViewModel publishes a capture tombstone synchronously before starting provider deletion.
 * Retained HEIF/JPEG/DNG completion belongs to the Engine's still lane and can outlive ViewModel
 * teardown, so that lane consumes the tombstone directly instead of calling back into a cleared UI
 * owner or submitting to its shut-down executor.
 */
internal class RetainedStillDeletionOwner<T>(
    private val maxTombstones: Int,
    private val discard: (T) -> PendingOutputDiscardResult,
    private val maxDiscardAttempts: Int = 3,
    private val retryBackoff: (attempt: Int) -> Unit = {},
) {
    private enum class PublicationState { PUBLISHING, PUBLISHED_AWAITING_CALLBACK }

    private data class ActivePublication(val captureId: Int, val state: PublicationState)

    private val lock = Any()
    private val tombstones = LinkedHashSet<Int>()
    private val activePublications = LinkedHashMap<T, ActivePublication>()
    // An unresolved row stays strongly owned in-process and is retried on later owner activity and
    // Engine release. Attempts are bounded; the collection is not silently trimmed because dropping
    // an output here would permit exactly the deleted-media resurrection this owner prevents.
    private val unresolvedDiscards = LinkedHashMap<T, Int>()

    init {
        require(maxTombstones > 0)
        require(maxDiscardAttempts > 0)
    }

    /**
     * Publishes the family tombstone and returns already-published outputs whose saved callback has
     * not completed. The Engine schedules those deletes off the caller (normally UI) thread.
     * PUBLISHING outputs are not returned: their completion edge rechecks the tombstone and owns the
     * discard before it can emit a saved callback.
     */
    fun markCaptureDeleted(captureId: Int): List<T> = synchronized(lock) {
        tombstones.remove(captureId)
        tombstones.add(captureId)
        while (tombstones.size > maxTombstones) {
            // A capture with active/unresolved outputs cannot lose its delete authority. Prefer an
            // inactive tombstone for eviction; if all are active, keep temporary extra headroom.
            val evictable = tombstones.firstOrNull { candidate ->
                candidate != captureId &&
                    activePublications.values.none { it.captureId == candidate } &&
                    unresolvedDiscards.values.none { it == candidate }
            } ?: break
            tombstones.remove(evictable)
        }
        activePublications.entries
            .filter { (_, publication) ->
                publication.captureId == captureId &&
                    publication.state == PublicationState.PUBLISHED_AWAITING_CALLBACK
            }
            .map { it.key }
    }

    /**
     * Owns the full check→publish→recheck interval. A delete racing native/provider publication either
     * wins before [publish] or is observed immediately after it; the brief post-publication callback
     * window remains tracked so [markCaptureDeleted] can still schedule Engine-side deletion.
     */
    fun publishIfLive(output: T, captureId: Int, publish: () -> Boolean): DeletedStillPublication {
        val deletedBefore = synchronized(lock) {
            if (captureId in tombstones) {
                true
            } else {
                activePublications[output] = ActivePublication(captureId, PublicationState.PUBLISHING)
                false
            }
        }
        if (deletedBefore) return discardDeletedPublication(output, captureId)

        val published = runCatching(publish).getOrDefault(false)
        val deletedAfter = synchronized(lock) {
            val deleted = captureId in tombstones
            if (published && !deleted) {
                activePublications[output] = ActivePublication(
                    captureId,
                    PublicationState.PUBLISHED_AWAITING_CALLBACK,
                )
            } else {
                activePublications.remove(output)
            }
            deleted
        }
        return when {
            deletedAfter -> discardDeletedPublication(output, captureId)
            published -> DeletedStillPublication.LIVE_PUBLISHED
            else -> DeletedStillPublication.LIVE_PUBLICATION_FAILED
        }
    }

    /** Called after the saved callback returns; a concurrent tombstone keeps ownership until discard. */
    fun finishPublished(output: T, captureId: Int) {
        synchronized(lock) {
            val publication = activePublications[output]
            if (publication?.captureId == captureId && captureId !in tombstones) {
                activePublications.remove(output)
            }
        }
    }

    fun handleRetained(output: T, captureId: Int): RetainedStillDisposition {
        val deleted = synchronized(lock) { captureId in tombstones }
        if (!deleted) return RetainedStillDisposition.RETAIN_FOR_RECOVERY
        return discardDeleted(output, captureId)
    }

    /** Fast ownership transfer for a camera-thread/executor-rejection path; performs no I/O. */
    fun ownRetainedForAsyncDiscard(output: T, captureId: Int): Boolean = synchronized(lock) {
        if (captureId !in tombstones) return false
        unresolvedDiscards[output] = captureId
        true
    }

    /** Retries a published or pending output whose family is already tombstoned. */
    fun discardDeleted(output: T, captureId: Int): RetainedStillDisposition =
        when (discardDeletedResult(output, captureId)) {
            PendingOutputDiscardResult.DELETED,
            PendingOutputDiscardResult.RECOVERY_MARKED,
            -> RetainedStillDisposition.DISCARD_DELETED_CAPTURE
            PendingOutputDiscardResult.UNRESOLVED ->
                RetainedStillDisposition.DISCARD_RETRY_PENDING
        }

    private fun discardDeletedPublication(output: T, captureId: Int): DeletedStillPublication =
        when (discardDeletedResult(output, captureId)) {
            PendingOutputDiscardResult.DELETED,
            PendingOutputDiscardResult.RECOVERY_MARKED,
            -> DeletedStillPublication.DISCARD_DELETED_CAPTURE
            PendingOutputDiscardResult.UNRESOLVED ->
                DeletedStillPublication.DISCARD_RETRY_PENDING
        }

    private fun discardDeletedResult(output: T, captureId: Int): PendingOutputDiscardResult {
        val result = discardWithRetry(output)
        synchronized(lock) {
            if (result == PendingOutputDiscardResult.UNRESOLVED) {
                unresolvedDiscards[output] = captureId
            } else {
                unresolvedDiscards.remove(output)
                activePublications.remove(output)
            }
        }
        return result
    }

    /** One bounded retry pass, used at Engine release and available to recovery orchestration. */
    fun retryUnresolvedDiscards(): Int {
        val pending = synchronized(lock) {
            buildMap {
                putAll(unresolvedDiscards)
                activePublications.forEach { (output, publication) ->
                    if (publication.captureId in tombstones) put(output, publication.captureId)
                }
            }.toList()
        }
        pending.forEach { (output, captureId) -> discardDeleted(output, captureId) }
        return synchronized(lock) { unresolvedDiscards.size }
    }

    private fun discardWithRetry(output: T): PendingOutputDiscardResult {
        repeat(maxDiscardAttempts) { zeroBased ->
            val attempt = zeroBased + 1
            val result = runCatching { discard(output) }
                .getOrDefault(PendingOutputDiscardResult.UNRESOLVED)
            if (result != PendingOutputDiscardResult.UNRESOLVED) return result
            if (attempt < maxDiscardAttempts) retryBackoff(attempt)
        }
        return PendingOutputDiscardResult.UNRESOLVED
    }

    internal fun tombstoneCount(): Int = synchronized(lock) { tombstones.size }

    internal fun unresolvedDiscardCount(): Int = synchronized(lock) { unresolvedDiscards.size }

}
