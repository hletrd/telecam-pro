package me.hletrd.telecampro.storage

import android.net.Uri
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingAllocationIdentityRecoveryTest {
    private val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 10L, 2L)
    private val allocation = PendingOutputAllocation(
        uri = Uri.parse("content://media/external_primary/images/media/10"),
        familyKey = family,
        identity = PendingDiscardIdentity(
            volumeName = "external_primary",
            providerVersion = "v1",
            rowId = 10L,
            generationAdded = 3L,
            displayName = family.displayName("dng"),
            relativePath = "DCIM/TeleCamPro/",
            mimeType = "image/x-adobe-dng",
            ownerPackageName = "me.hletrd.telecampro",
            familyIdentity = family.discardIdentity(),
            dateTaken = family.capturedAtEpochMillis,
        ),
    )

    @Test
    fun `uncertain identity never clears metadata or reaches destructive discard`() {
        var discards = 0
        var clears = 0
        val result = recoverPendingAllocationIdentity(
            capture = { PendingAllocationCaptureResult.Uncertain },
            discardExact = {
                discards++
                PendingOutputDiscardResult.DELETED
            },
            clearAbsent = {
                clears++
                true
            },
        )
        assertEquals(PendingOutputDiscardResult.UNRESOLVED, result)
        assertEquals(0, discards)
        assertEquals(0, clears)
    }

    @Test
    fun `later exact identity transfers only that immutable allocation to discard`() {
        var capture: PendingAllocationCaptureResult = PendingAllocationCaptureResult.Uncertain
        var discarded: PendingOutputAllocation? = null
        fun recover() = recoverPendingAllocationIdentity(
            capture = { capture },
            discardExact = {
                discarded = it
                PendingOutputDiscardResult.RECOVERY_MARKED
            },
            clearAbsent = { error("exact recovery must not clear REGISTERED as absent") },
        )
        assertEquals(PendingOutputDiscardResult.UNRESOLVED, recover())
        assertNull(discarded)
        capture = PendingAllocationCaptureResult.Exact(allocation)
        assertEquals(PendingOutputDiscardResult.RECOVERY_MARKED, recover())
        assertEquals(allocation, discarded)
    }

    @Test
    fun `authoritative absence clears only registered metadata and is terminal`() {
        var discards = 0
        var clears = 0
        val result = recoverPendingAllocationIdentity(
            capture = { PendingAllocationCaptureResult.Absent },
            discardExact = {
                discards++
                PendingOutputDiscardResult.DELETED
            },
            clearAbsent = {
                clears++
                true
            },
        )
        assertEquals(PendingOutputDiscardResult.DELETED, result)
        assertEquals(0, discards)
        assertEquals(1, clears)
    }

    @Test
    fun `failed metadata clear remains retained and non destructive`() {
        var discards = 0
        val result = recoverPendingAllocationIdentity(
            capture = { PendingAllocationCaptureResult.Absent },
            discardExact = {
                discards++
                PendingOutputDiscardResult.DELETED
            },
            clearAbsent = { false },
        )
        assertEquals(PendingOutputDiscardResult.UNRESOLVED, result)
        assertEquals(0, discards)
    }

    @Test
    fun `post launch failures retry with bounded backoff then release capacity`() {
        val scheduler = ManualRetryScheduler()
        val attempts = AtomicInteger()
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 1,
            discardEffect = {
                if (attempts.incrementAndGet() <= 3) PendingOutputDiscardResult.UNRESOLVED
                else PendingOutputDiscardResult.DELETED
            },
            retryScheduler = scheduler,
            retryInitialDelayMs = 5L,
            retryMaxDelayMs = 20L,
        )
        try {
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("post-launch-row"))
            awaitCondition { attempts.get() == 1 && scheduler.pendingCount() == 1 }
            assertFalse(owner.canAdmit())
            assertEquals(listOf(5L), scheduler.delays())
            scheduler.runNext()
            awaitCondition { attempts.get() == 2 && scheduler.pendingCount() == 1 }
            scheduler.runNext()
            awaitCondition { attempts.get() == 3 && scheduler.pendingCount() == 1 }
            scheduler.runNext()
            awaitCondition { attempts.get() == 4 && owner.unresolvedCount() == 0 }
            assertEquals(listOf(5L, 10L, 20L), scheduler.delays())
            assertTrue(owner.canAdmit())
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("reused-capacity"))
        } finally {
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `duplicate retry triggers keep one scheduled or in flight claim`() {
        val scheduler = ManualRetryScheduler()
        val attempts = AtomicInteger()
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 2,
            discardEffect = {
                attempts.incrementAndGet()
                PendingOutputDiscardResult.UNRESOLVED
            },
            retryScheduler = scheduler,
            retryInitialDelayMs = 1L,
            retryMaxDelayMs = 4L,
        )
        try {
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("row"))
            awaitCondition { attempts.get() == 1 && scheduler.pendingCount() == 1 }
            repeat(10) { owner.retryUnresolved() }
            assertEquals(1, scheduler.pendingCount())
            scheduler.runNext()
            awaitCondition { attempts.get() == 2 && scheduler.pendingCount() == 1 }
            assertEquals(1, scheduler.pendingCount())
        } finally {
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `scheduled recovery treats stable absence as metadata only and reuses capacity`() {
        val scheduler = ManualRetryScheduler()
        val attempts = AtomicInteger()
        var clears = 0
        var discards = 0
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 1,
            discardEffect = {
                recoverPendingAllocationIdentity(
                    capture = {
                        if (attempts.incrementAndGet() == 1) PendingAllocationCaptureResult.Uncertain
                        else PendingAllocationCaptureResult.Absent
                    },
                    discardExact = {
                        discards++
                        PendingOutputDiscardResult.DELETED
                    },
                    clearAbsent = {
                        clears++
                        true
                    },
                )
            },
            retryScheduler = scheduler,
            retryInitialDelayMs = 1L,
            retryMaxDelayMs = 4L,
        )
        try {
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("absent-row"))
            awaitCondition { attempts.get() == 1 && scheduler.pendingCount() == 1 }
            scheduler.runNext()
            awaitCondition { attempts.get() == 2 && owner.unresolvedCount() == 0 }
            assertEquals(1, clears)
            assertEquals(0, discards)
            assertTrue(owner.canAdmit())
        } finally {
            owner.shutdownNowForTest()
        }
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
            assertNull(owner.reserve())
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

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("condition did not become true", condition())
    }

    private class ManualRetryScheduler : RejectedOutputRetryScheduler {
        private data class Task(val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = ArrayDeque<Task>()
        private val observedDelays = mutableListOf<Long>()

        @Synchronized
        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): RejectedOutputRetryCancellation {
            observedDelays += delayMs
            val task = Task(action)
            tasks.addLast(task)
            return RejectedOutputRetryCancellation { synchronized(this) { task.cancelled = true } }
        }

        fun runNext() {
            val task = synchronized(this) { tasks.removeFirst() }
            if (!task.cancelled) task.action()
        }

        @Synchronized fun pendingCount(): Int = tasks.count { !it.cancelled }
        @Synchronized fun delays(): List<Long> = observedDelays.toList()
    }
}
