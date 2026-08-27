package me.hletrd.telecampro.storage

import android.net.Uri
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.ProcessAdmissionSignal
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
    fun `registered commit owns the row without probing or deleting`() {
        var providerCalls = 0
        assertEquals(
            PendingRegistrationDisposition.REGISTERED,
            pendingRegistrationDisposition(
                register = { true },
                delete = { providerCalls++; 1 },
                rowExists = { providerCalls++; false },
                clearRegistered = { providerCalls++; true },
            ),
        )
        assertEquals(0, providerCalls)
    }

    @Test
    fun `failed registration releases only authoritative absence with cleared metadata`() {
        listOf(
            "deleted" to Triple({ 1 }, { true as Boolean? }, { true }),
            "already absent" to Triple({ 0 }, { false as Boolean? }, { true }),
        ).forEach { (name, effects) ->
            assertEquals(
                name,
                PendingRegistrationDisposition.ABSENT,
                pendingRegistrationDisposition(
                    register = { false },
                    delete = effects.first,
                    rowExists = effects.second,
                    clearRegistered = effects.third,
                ),
            )
        }
    }

    @Test
    fun `failed registration retains present unavailable and uncleared rows`() {
        val cases = listOf<Pair<String, () -> PendingRegistrationDisposition>>(
            "delete false and row present" to {
                pendingRegistrationDisposition({ false }, { 0 }, { true }, { true })
            },
            "delete throws and row unavailable" to {
                pendingRegistrationDisposition(
                    register = { false },
                    delete = { error("provider delete unavailable") },
                    rowExists = { null },
                    clearRegistered = { true },
                )
            },
            "absence metadata cleanup fails" to {
                pendingRegistrationDisposition({ false }, { 0 }, { false }, { false })
            },
            "registration throws" to {
                pendingRegistrationDisposition(
                    register = { error("preference unavailable") },
                    delete = { 0 },
                    rowExists = { true },
                    clearRegistered = { true },
                )
            },
        )
        cases.forEach { (name, classify) ->
            assertEquals(name, PendingRegistrationDisposition.RETAINED, classify())
        }
    }

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
        val admission = ProcessAdmissionSignal(initial = true)
        val admissionEvents = mutableListOf<Boolean>()
        val subscription = admission.subscribe(admissionEvents::add)
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
            onAvailabilityChanged = admission::publish,
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
            assertEquals(listOf(true, false, true), admissionEvents)
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("reused-capacity"))
        } finally {
            subscription.close()
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
    fun `production storage subscription publishes close and reopen capacity edges`() {
        assertTrue(MediaStoreWriter.rejectedOutputAdmissionAvailable())
        val events = mutableListOf<Boolean>()
        val subscription = MediaStoreWriter.subscribeStillStorageAdmission(events::add)
        val reservations = mutableListOf<RejectedOutputCleanupReservation<MediaStoreWriter.RejectedOutput>>()
        try {
            repeat(REJECTED_OUTPUT_CLEANUP_WORKER_COUNT + REJECTED_OUTPUT_CLEANUP_BACKLOG_CAPACITY) {
                reservations += requireNotNull(MediaStoreWriter.reserveRejectedOutputCleanup())
            }
            assertFalse(MediaStoreWriter.rejectedOutputAdmissionAvailable())
            assertEquals(listOf(true, false), events)

            assertTrue(reservations.removeAt(0).cancel())
            assertTrue(MediaStoreWriter.rejectedOutputAdmissionAvailable())
            assertEquals(listOf(true, false, true), events)

            subscription.close()
            val detachedEvents = events.toList()
            val extra = requireNotNull(MediaStoreWriter.reserveRejectedOutputCleanup())
            extra.cancel()
            assertEquals(detachedEvents, events)
        } finally {
            reservations.forEach { it.cancel() }
            subscription.close()
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

    @Test
    fun `process retry scheduler runs and cancels its default future wrapper`() {
        val field = Class.forName("me.hletrd.telecampro.storage.MediaStoreWriterKt")
            .getDeclaredField("processPendingIdentityRetryScheduler")
            .apply { isAccessible = true }
        val scheduler = field.get(null) as RejectedOutputRetryScheduler
        val ran = CountDownLatch(1)

        requireNotNull(scheduler.schedule(1L) { ran.countDown() })
        assertTrue(ran.await(2, TimeUnit.SECONDS))
        val cancelled = requireNotNull(scheduler.schedule(60_000L) { error("cancelled retry ran") })
        cancelled.cancel()
    }

    @Test
    fun `dispatch rejection and throwing callbacks retain truth while releasing reservation`() {
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 2,
            discardEffect = { PendingOutputDiscardResult.DELETED },
        )
        val reservation = requireNotNull(owner.reserve())
        owner.shutdownNowForTest()

        assertFalse(reservation.submit("rejected") { error("observer failure") })
        assertEquals(1, owner.unresolvedCount())
        assertEquals(0, owner.admittedCount())
    }

    @Test
    fun `discard and completion exceptions become retained unresolved truth`() {
        val completed = CountDownLatch(1)
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 2,
            discardEffect = { error("discard failure") },
        )
        try {
            assertEquals(
                RejectedOutputCleanupDispatch.ACCEPTED,
                owner.dispatch("row") {
                    completed.countDown()
                    error("completion failure")
                },
            )
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            awaitCondition { owner.unresolvedCount() == 1 && owner.admittedCount() == 0 }
        } finally {
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `retry without a permit reschedules one claim until capacity returns`() {
        val scheduler = ManualRetryScheduler()
        val attempts = AtomicInteger()
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 3,
            discardEffect = {
                attempts.incrementAndGet()
                PendingOutputDiscardResult.UNRESOLVED
            },
            retryScheduler = scheduler,
            retryInitialDelayMs = 1L,
            retryMaxDelayMs = 2L,
        )
        try {
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("retry"))
            awaitCondition { attempts.get() == 1 && scheduler.pendingCount() == 1 }
            val firstBlocker = requireNotNull(owner.reserve())
            val secondBlocker = requireNotNull(owner.reserve())
            scheduler.runNext()
            assertEquals(1, scheduler.pendingCount())
            assertEquals(1, attempts.get())
            firstBlocker.cancel()
            secondBlocker.cancel()
            scheduler.runNext()
            awaitCondition { attempts.get() == 2 && scheduler.pendingCount() == 1 }
        } finally {
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `retry executor rejection releases permit and reschedules retained claim`() {
        val scheduler = ManualRetryScheduler()
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 2,
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
            retryScheduler = scheduler,
            retryInitialDelayMs = 1L,
            retryMaxDelayMs = 2L,
        )
        assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("row"))
        awaitCondition { owner.unresolvedCount() == 1 && scheduler.pendingCount() == 1 }
        owner.shutdownNowForTest()

        scheduler.runNext()
        assertEquals(1, scheduler.pendingCount())
        assertEquals(0, owner.admittedCount())
        assertEquals(1, owner.unresolvedCount())
    }

    @Test
    fun `manual retry trigger rearms after scheduler rejection without duplication`() {
        val scheduler = RejectOnceRetryScheduler()
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 2,
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
            retryScheduler = scheduler,
            retryInitialDelayMs = 1L,
            retryMaxDelayMs = 2L,
        )
        try {
            assertEquals(RejectedOutputCleanupDispatch.ACCEPTED, owner.dispatch("row"))
            awaitCondition { owner.unresolvedCount() == 1 && scheduler.rejections.get() == 1 }
            repeat(5) { owner.retryUnresolved() }
            assertEquals(1, scheduler.pendingCount())
        } finally {
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `ownership invariant reports an already admitted overflow`() {
        val uncaught = CountDownLatch(1)
        val owner = RejectedOutputCleanupCapacityOwner<String>(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 1,
            ownershipLimit = 1,
            threadFactory = java.util.concurrent.ThreadFactory { task ->
                Thread(task, "identity-overflow-test").apply {
                    isDaemon = true
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                        if (failure is IllegalStateException) uncaught.countDown()
                    }
                }
            },
            discardEffect = { PendingOutputDiscardResult.UNRESOLVED },
        )
        val first = requireNotNull(owner.reserve())
        val second = requireNotNull(owner.reserve())
        try {
            assertTrue(first.submit("first"))
            assertTrue(second.submit("second"))
            assertTrue(uncaught.await(2, TimeUnit.SECONDS))
            assertEquals(1, owner.unresolvedCount())
        } finally {
            owner.shutdownNowForTest()
        }
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

    private class RejectOnceRetryScheduler : RejectedOutputRetryScheduler {
        private val delegate = ManualRetryScheduler()
        val rejections = AtomicInteger()

        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): RejectedOutputRetryCancellation? = if (rejections.getAndIncrement() == 0) {
            null
        } else {
            delegate.schedule(delayMs, action)
        }

        fun pendingCount(): Int = delegate.pendingCount()
    }
}
