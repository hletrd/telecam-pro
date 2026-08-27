package me.hletrd.telecampro.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PendingAllocationIdentityRecoveryTest {
    @Test
    fun `unavailable identity never reaches destructive discard`() {
        var discards = 0

        val result = recoverPendingAllocationIdentity<String>(
            resolve = { null },
            discard = {
                discards++
                PendingOutputDiscardResult.DELETED
            },
        )

        assertEquals(PendingOutputDiscardResult.UNRESOLVED, result)
        assertEquals(0, discards)
    }

    @Test
    fun `later exact identity transfers once to bounded discard`() {
        var exact: String? = null
        var discarded: String? = null

        assertEquals(
            PendingOutputDiscardResult.UNRESOLVED,
            recoverPendingAllocationIdentity(resolve = { exact }, discard = {
                discarded = it
                PendingOutputDiscardResult.RECOVERY_MARKED
            }),
        )
        assertEquals(null, discarded)

        exact = "content://media/exact-row"
        assertEquals(
            PendingOutputDiscardResult.RECOVERY_MARKED,
            recoverPendingAllocationIdentity(resolve = { exact }, discard = {
                discarded = it
                PendingOutputDiscardResult.RECOVERY_MARKED
            }),
        )
        assertEquals(exact, discarded)
    }

    @Test
    fun `finite identity owner closes before work can exceed worker plus backlog`() {
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 2,
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
        )
        val first = checkNotNull(owner.reserve())
        val second = checkNotNull(owner.reserve())
        try {
            assertFalse(owner.canAdmit())
            assertEquals(null, owner.reserve())
        } finally {
            first.cancel()
            second.cancel()
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `production identity claim executes its bounded recovery closure`() {
        val claim = MediaStoreWriter.PendingIdentityRecovery {
            PendingOutputDiscardResult.RECOVERY_MARKED
        }

        assertEquals(PendingOutputDiscardResult.RECOVERY_MARKED, claim.recover())
    }
}
