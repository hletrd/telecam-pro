package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.storage.PendingOutputDiscardResult
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.storage.OrphanDisposition
import me.hletrd.telecampro.storage.PendingJournalState
import me.hletrd.telecampro.storage.PendingProbe
import me.hletrd.telecampro.storage.orphanDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedStillDeletionOwnerTest {

    @Test(expected = IllegalArgumentException::class)
    fun `tombstone capacity must be positive`() {
        RetainedStillDeletionOwner<String>(maxTombstones = 0, discard = {
            PendingOutputDiscardResult.DELETED
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `discard retry count must be positive`() {
        RetainedStillDeletionOwner<String>(
            maxTombstones = 1,
            maxDiscardAttempts = 0,
            discard = { PendingOutputDiscardResult.DELETED },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unresolved discard capacity must be positive`() {
        RetainedStillDeletionOwner<String>(
            maxTombstones = 1,
            maxUnresolvedDiscards = 0,
            discard = { PendingOutputDiscardResult.DELETED },
        )
    }

    @Test
    fun `deleted capture discards a retained still without a ViewModel continuation`() {
        val discarded = mutableListOf<String>()
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = {
            discarded += it
            PendingOutputDiscardResult.DELETED
        })

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
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = {
            discarded += it
            PendingOutputDiscardResult.DELETED
        })

        assertEquals(
            RetainedStillDisposition.RETAIN_FOR_RECOVERY,
            owner.handleRetained("content://image/live", captureId = 9),
        )
        assertTrue(discarded.isEmpty())
        assertFalse(owner.ownRetainedForAsyncDiscard("content://image/live", captureId = 9))
    }

    @Test
    fun `live publication success and failure retire their exact owner`() {
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 2, discard = {
            PendingOutputDiscardResult.DELETED
        })

        assertEquals(
            DeletedStillPublication.LIVE_PUBLICATION_FAILED,
            owner.publishIfLive("content://image/fail", captureId = 7) { false },
        )
        assertEquals(
            DeletedStillPublication.LIVE_PUBLICATION_FAILED,
            owner.publishIfLive("content://image/throw", captureId = 7) {
                throw java.io.IOException("provider offline")
            },
        )
        assertEquals(
            DeletedStillPublication.LIVE_PUBLISHED,
            owner.publishIfLive("content://image/success", captureId = 8) { true },
        )
        owner.finishPublished("content://image/success", captureId = 8)
        assertTrue(owner.markCaptureDeleted(8).isEmpty())
    }

    @Test
    fun `deleted capture tombstones stay bounded and newest remain authoritative`() {
        val discarded = mutableListOf<Int>()
        val owner = RetainedStillDeletionOwner<Int>(maxTombstones = 2, discard = {
            discarded += it
            PendingOutputDiscardResult.DELETED
        })
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

    @Test
    fun `delete racing publication is rechecked before a saved callback can win`() {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val discarded = mutableListOf<String>()
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = {
            discarded += it
            PendingOutputDiscardResult.DELETED
        })
        val result = AtomicReference<DeletedStillPublication>()
        val publisher = Thread {
            result.set(
                owner.publishIfLive("content://image/dng", captureId = 31) {
                    publishEntered.countDown()
                    releasePublish.await()
                    true
                },
            )
        }

        publisher.start()
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))
        // PUBLISHING rows are owned by the completion recheck, not deleted concurrently underneath
        // the provider update.
        assertTrue(owner.markCaptureDeleted(31).isEmpty())
        releasePublish.countDown()
        publisher.join(5_000)

        assertFalse(publisher.isAlive)
        assertEquals(DeletedStillPublication.DISCARD_DELETED_CAPTURE, result.get())
        assertEquals(listOf("content://image/dng"), discarded)
    }

    @Test
    fun `published callback window remains Engine-owned across ViewModel detach`() {
        val discarded = mutableListOf<String>()
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = {
            discarded += it
            PendingOutputDiscardResult.RECOVERY_MARKED
        })

        assertEquals(
            DeletedStillPublication.LIVE_PUBLISHED,
            owner.publishIfLive("content://image/heif", captureId = 44) { true },
        )
        // Models delete + immediate ViewModel callback detachment before emitSaved can run.
        assertEquals(listOf("content://image/heif"), owner.markCaptureDeleted(44))
        assertEquals(
            RetainedStillDisposition.DISCARD_DELETED_CAPTURE,
            owner.discardDeleted("content://image/heif", 44),
        )
        owner.finishPublished("content://image/heif", 44)

        assertEquals(listOf("content://image/heif"), discarded)
        assertEquals(0, owner.unresolvedDiscardCount())
    }

    @Test
    fun `false journal plus delete remains typed and retries boundedly`() {
        var durable = false
        var attempts = 0
        val owner = RetainedStillDeletionOwner<String>(
            maxTombstones = 4,
            maxDiscardAttempts = 3,
            discard = {
                attempts++
                if (durable) PendingOutputDiscardResult.RECOVERY_MARKED
                else PendingOutputDiscardResult.UNRESOLVED
            },
        )
        owner.markCaptureDeleted(55)

        assertEquals(
            RetainedStillDisposition.DISCARD_RETRY_PENDING,
            owner.handleRetained("content://image/jpeg", 55),
        )
        assertEquals(3, attempts)
        assertEquals(1, owner.unresolvedDiscardCount())

        durable = true
        assertEquals(0, owner.retryUnresolvedDiscards())
        assertEquals(4, attempts)
        assertEquals(0, owner.unresolvedDiscardCount())
    }

    @Test
    fun `tombstoned publication with unresolved discard stays retry pending`() {
        var publishCalled = false
        val owner = RetainedStillDeletionOwner<String>(
            maxTombstones = 2,
            maxDiscardAttempts = 1,
            discard = { PendingOutputDiscardResult.UNRESOLVED },
        )
        owner.markCaptureDeleted(56)

        assertEquals(
            DeletedStillPublication.DISCARD_RETRY_PENDING,
            owner.publishIfLive("content://image/unresolved", captureId = 56) {
                publishCalled = true
                true
            },
        )
        assertFalse(publishCalled)
        assertEquals(1, owner.unresolvedDiscardCount())
    }

    @Test
    fun `discard exceptions stay unresolved while active publication and tombstone stay owned`() {
        var throws = true
        val owner = RetainedStillDeletionOwner<String>(
            maxTombstones = 1,
            maxDiscardAttempts = 1,
            discard = {
                if (throws) throw java.io.IOException("storage offline")
                PendingOutputDiscardResult.DELETED
            },
        )
        assertEquals(
            DeletedStillPublication.LIVE_PUBLISHED,
            owner.publishIfLive("content://image/active", captureId = 70) { true },
        )
        assertEquals(listOf("content://image/active"), owner.markCaptureDeleted(70))
        assertEquals(
            RetainedStillDisposition.DISCARD_RETRY_PENDING,
            owner.discardDeleted("content://image/active", 70),
        )
        // An active/unresolved capture retains its tombstone even when nominal capacity is full.
        owner.markCaptureDeleted(71)
        assertEquals(2, owner.tombstoneCount())

        throws = false
        assertEquals(0, owner.retryUnresolvedDiscards())
        owner.finishPublished("content://image/active", 70)
        owner.markCaptureDeleted(72)
        assertEquals(1, owner.tombstoneCount())
    }

    @Test
    fun `rejected DNG dispatch transfers ownership without provider IO on caller`() {
        var discardCalls = 0
        val owner = RetainedStillDeletionOwner<String>(maxTombstones = 4, discard = {
            discardCalls++
            PendingOutputDiscardResult.DELETED
        })
        owner.markCaptureDeleted(66)

        assertTrue(owner.ownRetainedForAsyncDiscard("content://image/dng", 66))
        assertEquals(0, discardCalls)
        assertEquals(1, owner.unresolvedDiscardCount())
        assertEquals(0, owner.retryUnresolvedDiscards())
        assertEquals(1, discardCalls)
    }

    @Test
    fun `more than 32 persistent failures stay bounded and close capture admission`() {
        val durableFamilies = linkedSetOf<CaptureFamilyKey>()
        val owner = RetainedStillDeletionOwner<String>(
            maxTombstones = 32,
            maxUnresolvedDiscards = 32,
            maxDiscardAttempts = 1,
            discard = { PendingOutputDiscardResult.UNRESOLVED },
            persistDeletionIntent = { durableFamilies.add(it) },
        )

        repeat(40) { index ->
            val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_000_000L + index, index.toLong())
            owner.registerCaptureFamily(index, family)
            owner.markCaptureDeleted(index)
            assertEquals(
                RetainedStillDisposition.DISCARD_RETRY_PENDING,
                owner.discardDeleted("content://image/$index", index),
            )
        }

        assertEquals(40, durableFamilies.size)
        assertEquals(32, owner.unresolvedDiscardCount())
        assertEquals(32, owner.tombstoneCount())
        assertFalse(owner.canAdmitCapture())
    }

    @Test
    fun `unowned registered families evict oldest entry at the configured bound`() {
        val persisted = mutableListOf<CaptureFamilyKey>()
        val owner = RetainedStillDeletionOwner<String>(
            maxTombstones = 2,
            discard = { PendingOutputDiscardResult.DELETED },
            persistDeletionIntent = { family -> persisted += family; true },
        )
        val first = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_000_001L, 1L)
        val second = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_000_002L, 2L)
        val third = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_000_003L, 3L)

        owner.registerCaptureFamily(1, first)
        owner.registerCaptureFamily(2, second)
        owner.registerCaptureFamily(3, third)
        owner.markCaptureDeleted(1)

        assertTrue(persisted.isEmpty())
        assertFalse(owner.canAdmitCapture())
    }

    @Test
    fun `release retry then late failure remains deleted for replacement recovery`() {
        val durableFamilies = linkedSetOf<CaptureFamilyKey>()
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_000_123L, 91L)
        val oldEngineOwner = RetainedStillDeletionOwner<String>(
            maxTombstones = 4,
            maxDiscardAttempts = 1,
            discard = { PendingOutputDiscardResult.UNRESOLVED },
            persistDeletionIntent = { durableFamilies.add(it) },
        )
        oldEngineOwner.registerCaptureFamily(91, family)
        oldEngineOwner.markCaptureDeleted(91)

        // Engine release performs its final retry before the accepted save tail arrives.
        assertEquals(0, oldEngineOwner.retryUnresolvedDiscards())
        assertEquals(
            RetainedStillDisposition.DISCARD_RETRY_PENDING,
            oldEngineOwner.discardDeleted("content://image/late-after-release", 91),
        )

        // A replacement Engine/launch sees the family journal, so even COMPLETE valid bytes are
        // deleted rather than adopted when the URI-level DISCARD and provider delete both failed.
        assertTrue(family in durableFamilies)
        assertEquals(
            OrphanDisposition.DELETE,
            orphanDisposition(
                PendingJournalState.COMPLETE,
                PendingProbe.VALID,
                familyDeleted = family in durableFamilies,
            ),
        )
    }
}
