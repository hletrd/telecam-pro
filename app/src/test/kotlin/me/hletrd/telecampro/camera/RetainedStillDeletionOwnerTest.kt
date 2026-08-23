package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedStillDeletionOwnerTest {

    @Test(expected = IllegalArgumentException::class)
    fun `tombstone capacity must be positive`() {
        RetainedStillDeletionOwner<String>(maxTombstones = 0, discard = {})
    }

    @Test
    fun `deleted capture discards a retained still without a ViewModel continuation`() {
        val discarded = mutableListOf<String>()
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = discarded::add)

        // Models the lifecycle fault: UI deletion publishes ownership, then the UI callback graph is
        // gone before the already-accepted still tail reports its retained private row.
        owner.markCaptureDeleted(17)
        val disposition = owner.handleRetained("content://image/late", captureId = 17)

        assertEquals(RetainedStillDisposition.DISCARD_DELETED_CAPTURE, disposition)
        assertEquals(listOf("content://image/late"), discarded)
    }

    @Test
    fun `live capture remains pending for recovery`() {
        val discarded = mutableListOf<String>()
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = discarded::add)

        assertEquals(
            RetainedStillDisposition.RETAIN_FOR_RECOVERY,
            owner.handleRetained("content://image/live", captureId = 9),
        )
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun `deleted capture tombstones stay bounded and newest remain authoritative`() {
        val discarded = mutableListOf<Int>()
        val owner = RetainedStillDeletionOwner<Int>(maxTombstones = 2, discard = discarded::add)
        owner.markCaptureDeleted(1)
        owner.markCaptureDeleted(2)
        owner.markCaptureDeleted(2) // duplicate refreshes recency without growing the set
        owner.markCaptureDeleted(3)

        assertEquals(2, owner.tombstoneCount())
        assertEquals(RetainedStillDisposition.RETAIN_FOR_RECOVERY, owner.handleRetained(1, 1))
        assertEquals(RetainedStillDisposition.DISCARD_DELETED_CAPTURE, owner.handleRetained(2, 2))
        assertEquals(RetainedStillDisposition.DISCARD_DELETED_CAPTURE, owner.handleRetained(3, 3))
        assertEquals(listOf(2, 3), discarded)
    }
}
