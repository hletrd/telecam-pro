package me.hletrd.telecampro.storage

import me.hletrd.telecampro.camera.MAX_RETAINED_PROCESSED_SNAPSHOTS
import me.hletrd.telecampro.camera.RECORDING_STORAGE_BACKLOG_CAPACITY
import me.hletrd.telecampro.camera.RECORDING_STORAGE_WORKER_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedOutputOwnerTest {
    @Test
    fun `unresolved rejected outputs are bounded and close admission`() {
        val owner = BoundedRejectedOutputOwner<String>(
            admissionLimit = 2,
            ownershipLimit = 4,
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
        )

        owner.discard("heif")
        owner.discard("jpeg")
        owner.discard("dng")

        assertEquals(3, owner.unresolvedCount())
        assertFalse(owner.canAdmit())
    }

    @Test
    fun `retry retires only outputs with durable discard ownership`() {
        val durable = mutableSetOf<String>()
        val owner = BoundedRejectedOutputOwner<String>(
            admissionLimit = 3,
            discardEffect = { output ->
                if (output in durable) PendingOutputDiscardResult.RECOVERY_MARKED
                else PendingOutputDiscardResult.UNRESOLVED
            },
        )
        owner.discard("video")
        owner.discard("still")
        durable += "video"

        assertEquals(1, owner.retryUnresolved())
        assertTrue(owner.canAdmit())
        assertEquals(1, owner.unresolvedCount())
    }

    @Test
    fun `discard effect exception retains exact unresolved output`() {
        val owner = BoundedRejectedOutputOwner<String>(admissionLimit = 1) {
            error("provider failed")
        }

        assertEquals(PendingOutputDiscardResult.UNRESOLVED, owner.discard("still"))
        assertEquals(1, owner.unresolvedCount())
        assertFalse(owner.canAdmit())
    }

    @Test
    fun `hard ownership bound fails loudly instead of silently forgetting identity`() {
        val owner = BoundedRejectedOutputOwner<String>(
            admissionLimit = 1,
            ownershipLimit = 1,
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
        )
        owner.discard("owned")

        assertThrows(IllegalStateException::class.java) {
            owner.discard("unreachable-overflow")
        }
        assertEquals(1, owner.unresolvedCount())
    }

    @Test
    fun `capacity one retains every already admitted sibling in exact headroom`() {
        val durable = mutableSetOf<String>()
        val owner = BoundedRejectedOutputOwner<String>(
            admissionLimit = 1,
            ownershipLimit = 3,
            discardEffect = { output ->
                if (output in durable) PendingOutputDiscardResult.RECOVERY_MARKED
                else PendingOutputDiscardResult.UNRESOLVED
            },
        )

        owner.discard("shot.heic")
        owner.discard("shot.jpg")
        owner.discard("shot.dng")
        assertFalse(owner.canAdmit())
        assertEquals(3, owner.unresolvedCount())

        durable += setOf("shot.heic", "shot.jpg", "shot.dng")
        assertEquals(0, owner.retryUnresolved())
        assertTrue(owner.canAdmit())
    }

    @Test
    fun `production headroom equals every bounded already admitted source`() {
        assertEquals(
            MAX_RETAINED_PROCESSED_SNAPSHOTS * 3 +
                1 +
                RECORDING_STORAGE_WORKER_COUNT + RECORDING_STORAGE_BACKLOG_CAPACITY +
                1,
            MediaStoreWriter.MAX_ALREADY_ADMITTED_REJECTED_OUTPUTS,
        )
        val owner = BoundedRejectedOutputOwner<String>(
            admissionLimit = MediaStoreWriter.MAX_REJECTED_OUTPUTS,
            ownershipLimit = MediaStoreWriter.MAX_REJECTED_OUTPUTS +
                MediaStoreWriter.MAX_ALREADY_ADMITTED_REJECTED_OUTPUTS,
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
        )

        repeat(MediaStoreWriter.MAX_REJECTED_OUTPUTS) { owner.discard("ordinary-$it") }
        assertFalse(owner.canAdmit())
        repeat(MediaStoreWriter.MAX_ALREADY_ADMITTED_REJECTED_OUTPUTS) {
            owner.discard("already-admitted-$it")
        }
        assertEquals(
            MediaStoreWriter.MAX_REJECTED_OUTPUTS +
                MediaStoreWriter.MAX_ALREADY_ADMITTED_REJECTED_OUTPUTS,
            owner.unresolvedCount(),
        )
    }
}
