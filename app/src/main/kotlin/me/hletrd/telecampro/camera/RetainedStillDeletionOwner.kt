package me.hletrd.telecampro.camera

/** Result of routing a completed private still through deleted-capture ownership. */
internal enum class RetainedStillDisposition {
    /** The capture is live; normal launch recovery may retain/adopt this private output. */
    RETAIN_FOR_RECOVERY,

    /** The capture was deleted; the Engine's durable discard path owns this output. */
    DISCARD_DELETED_CAPTURE,
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
    private val discard: (T) -> Unit,
) {
    private val tombstones = LinkedHashSet<Int>()

    init {
        require(maxTombstones > 0)
    }

    @Synchronized
    fun markCaptureDeleted(captureId: Int) {
        tombstones.remove(captureId)
        tombstones.add(captureId)
        while (tombstones.size > maxTombstones) tombstones.remove(tombstones.first())
    }

    fun handleRetained(output: T, captureId: Int): RetainedStillDisposition {
        val deleted = synchronized(this) { captureId in tombstones }
        if (!deleted) return RetainedStillDisposition.RETAIN_FOR_RECOVERY
        discard(output)
        return RetainedStillDisposition.DISCARD_DELETED_CAPTURE
    }

    @Synchronized
    internal fun tombstoneCount(): Int = tombstones.size
}
