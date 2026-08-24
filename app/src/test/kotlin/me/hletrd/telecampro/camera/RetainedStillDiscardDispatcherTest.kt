package me.hletrd.telecampro.camera

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.storage.CurrentProcessFamilyRetirementScan
import me.hletrd.telecampro.storage.FamilyDeletionRetirementResult
import me.hletrd.telecampro.storage.MediaStoreWriter
import me.hletrd.telecampro.storage.PendingOutputDiscardResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedStillDiscardDispatcherTest {
    @Test
    fun `production facades share the exact process owner`() {
        val firstOwner = ProcessRetainedStillDiscardOwner.capacity(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        val secondOwner = ProcessRetainedStillDiscardOwner.capacity(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        assertTrue(firstOwner === secondOwner)

        val finished = CountDownLatch(1)
        val facade = RetainedStillDiscardDispatcher(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        assertEquals(
            RetainedStillDiscardDispatch.ACCEPTED,
            facade.dispatch(Runnable { finished.countDown() }),
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        facade.shutdown()
    }

    @Test
    fun `registered producer terminal survives old Engine facade shutdown`() {
        val staleEngine = RetainedStillDiscardDispatcher(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        staleEngine.shutdown()
        assertEquals(
            RetainedStillDiscardDispatch.SHUTDOWN,
            staleEngine.dispatch(Runnable { error("closed Engine facade admitted new work") }),
        )

        val terminalFinished = CountDownLatch(1)
        assertEquals(
            RetainedStillDiscardDispatch.ACCEPTED,
            ProcessRetainedStillDiscardOwner.dispatchRegisteredProducerTerminal(
                Runnable { terminalFinished.countDown() },
                Runnable { error("accepted terminal task must not request overflow rescan") },
            ),
        )
        assertTrue(terminalFinished.await(5, TimeUnit.SECONDS))

        val fallbackFinished = CountDownLatch(1)
        assertEquals(
            RetainedStillDiscardDispatch.ACCEPTED,
            dispatchDeletedFamilyRetirement(
                facade = staleEngine,
                task = Runnable { fallbackFinished.countDown() },
                overflowRescan = Runnable {
                    error("accepted process fallback must not request overflow rescan")
                },
            ),
        )
        assertTrue(fallbackFinished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `process owner rejects capacity drift across Engine generations`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRetainedStillDiscardOwner.capacity(
                RETAINED_STILL_DISCARD_WORKER_COUNT + 1,
                RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedStillDiscardCapacityOwner(workerCount = 0, backlogCapacity = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedStillDiscardCapacityOwner(workerCount = 1, backlogCapacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRetainedStillDiscardOwner.capacity(
                RETAINED_STILL_DISCARD_WORKER_COUNT,
                RETAINED_STILL_DISCARD_BACKLOG_CAPACITY + 1,
            )
        }
    }

    @Test
    fun `injected facade exposes its exact finite active and queued capacity`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val facade = RetainedStillDiscardDispatcher(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-retained-facade").apply { isDaemon = true }
            },
        )
        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                facade.dispatch(
                    Runnable {
                        workerEntered.countDown()
                        releaseWorker.await()
                        finished.countDown()
                    },
                ),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                facade.dispatch(Runnable { finished.countDown() }),
            )
            assertEquals(1, facade.activeTaskCount())
            assertEquals(1, facade.queuedTaskCount())
            releaseWorker.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        } finally {
            releaseWorker.countDown()
            facade.shutdown()
        }
    }

    @Test
    fun `overflowed family retirement conflates and rearms after finite lane drains`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val queuedFinished = CountDownLatch(1)
        val rescanFinished = CountDownLatch(1)
        val overflowRan = AtomicBoolean()
        val rescans = AtomicInteger()
        val caller = Thread.currentThread()
        val rescanThread = AtomicReference<Thread>()
        val capacity = RetainedStillDiscardCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-family-retirement").apply { isDaemon = true }
            },
        )
        val facade = RetainedStillDiscardDispatcher(capacity)
        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                dispatchDeletedFamilyRetirement(
                    facade,
                    Runnable {
                        workerEntered.countDown()
                        releaseWorker.await()
                    },
                    Runnable { error("initial task was accepted") },
                ),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                dispatchDeletedFamilyRetirement(
                    facade,
                    Runnable { queuedFinished.countDown() },
                    Runnable { error("queued task was accepted") },
                ),
            )

            repeat(32) {
                assertEquals(
                    RetainedStillDiscardDispatch.OVERFLOW,
                    dispatchDeletedFamilyRetirement(
                        facade,
                        Runnable { overflowRan.set(true) },
                        Runnable {
                            rescanThread.set(Thread.currentThread())
                            rescans.incrementAndGet()
                            rescanFinished.countDown()
                        },
                    ),
                )
            }

            assertEquals(1, capacity.activeTaskCount())
            assertEquals(1, capacity.queuedTaskCount())
            assertEquals(1, capacity.retirementRescanCount())
            assertFalse(overflowRan.get())
            assertEquals(0, rescans.get())

            releaseWorker.countDown()
            assertTrue(queuedFinished.await(5, TimeUnit.SECONDS))
            assertTrue(rescanFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, rescans.get())
            assertFalse(rescanThread.get() === caller)
            assertFalse(overflowRan.get())
        } finally {
            releaseWorker.countDown()
            facade.shutdown()
        }
    }

    @Test
    fun `retirement registry is bounded rollback-safe and listener isolated`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetainedStillRetirementRegistry(maxFamilies = 0, maxListenersPerFamily = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedStillRetirementRegistry(maxFamilies = 1, maxListenersPerFamily = 0)
        }
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_030_000_000L, 30L)
        val secondFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_030_000_001L, 31L)
        val registry = RetainedStillRetirementRegistry(maxFamilies = 2, maxListenersPerFamily = 2)
        val delivered = AtomicInteger()
        val failing = RetainedStillRetirementListener { error("one stale owner failed") }
        val healthy = RetainedStillRetirementListener { delivered.incrementAndGet() }

        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            registry.register(family, failing),
        )
        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            registry.register(family, healthy),
        )
        assertEquals(0, registry.reconcile(family, FamilyDeletionRetirementResult.RETAINED))
        assertEquals(0, registry.reconcile(family, FamilyDeletionRetirementResult.RETRYABLE))
        assertEquals(2, registry.registrationCount())
        assertEquals(2, registry.reconcile(family, FamilyDeletionRetirementResult.RETIRED))
        assertEquals(1, delivered.get())
        assertEquals(0, registry.registrationCount())

        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            registry.register(secondFamily, healthy),
        )
        assertTrue(registry.unregister(secondFamily, healthy))
        assertFalse(registry.unregister(secondFamily, healthy))
        assertEquals(0, registry.registrationCount())
    }

    @Test
    fun `registry bounds distinct families separately from listener fanout`() {
        val maxFamilies = MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS
        val registry = RetainedStillRetirementRegistry(
            maxFamilies = maxFamilies,
            maxListenersPerFamily = 2,
        )
        val oldEngine = RetainedStillRetirementListener { }
        val replacements = mutableListOf<RetainedStillRetirementListener>()
        val families = (0 until maxFamilies).map { index ->
            CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_032_000_000L + index, index.toLong())
        }

        families.forEach { family ->
            val replacement = RetainedStillRetirementListener { }
            replacements += replacement
            assertEquals(
                RetainedStillRetirementRegistrationResult.REGISTERED,
                registry.register(family, oldEngine),
            )
            assertEquals(
                RetainedStillRetirementRegistrationResult.REGISTERED,
                registry.register(family, replacement),
            )
        }
        assertEquals(maxFamilies, registry.familyCount())
        assertEquals(maxFamilies * 2, registry.registrationCount())

        assertEquals(
            RetainedStillRetirementRegistrationResult.CAPACITY_EXHAUSTED,
            registry.register(families.first(), RetainedStillRetirementListener { }),
        )
        val sixtyFifth = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_033_000_000L, 65L)
        assertEquals(
            RetainedStillRetirementRegistrationResult.CAPACITY_EXHAUSTED,
            registry.register(sixtyFifth, RetainedStillRetirementListener { }),
        )

        // Releasing the old Engine removes only its compact local-owner registrations. The exact
        // replacement owners remain, and retiring one family authoritatively reclaims family space.
        assertEquals(maxFamilies, registry.unregisterListener(oldEngine))
        assertEquals(maxFamilies, registry.registrationCount())
        assertEquals(1, registry.reconcile(families.first(), FamilyDeletionRetirementResult.RETIRED))
        assertEquals(maxFamilies - 1, registry.familyCount())
        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            registry.register(sixtyFifth, RetainedStillRetirementListener { }),
        )
    }

    @Test
    fun `retry owner conflates scans and exponentially backs off without retrying live rows`() {
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val effects = mutableListOf<String>()
        val owner = RetainedStillRetirementRetryOwner(
            initialDelayMs = 10L,
            maxDelayMs = 40L,
            schedule = { delay, task -> scheduled += delay to task; true },
            submit = { task -> task.run(); RetainedStillDiscardDispatch.ACCEPTED },
        )

        assertTrue(owner.request(Runnable { effects += "superseded" }))
        assertTrue(owner.request(Runnable { effects += "latest" }))
        assertEquals(1, scheduled.size)
        assertEquals(1, owner.pendingCount())
        assertEquals(20L, owner.nextDelayMs())
        val firstDeadline = scheduled.removeAt(0).second
        firstDeadline.run()
        firstDeadline.run() // duplicate timer delivery is inert after the pending task is consumed
        assertEquals(listOf("latest"), effects)
        assertEquals(0, owner.pendingCount())

        owner.request(Runnable { effects += "second" })
        assertEquals(20L, scheduled.single().first)
        scheduled.removeAt(0).second.run()
        owner.request(Runnable { effects += "third" })
        assertEquals(40L, scheduled.single().first)
        owner.resetBackoff()
        assertEquals(10L, owner.nextDelayMs())

        assertTrue(
            retirementRescanRequiresRetry(
                listOf(FamilyDeletionRetirementResult.RETRYABLE),
            ),
        )
        assertFalse(
            retirementRescanRequiresRetry(
                listOf(
                    FamilyDeletionRetirementResult.RETAINED,
                    FamilyDeletionRetirementResult.PRODUCERS_ACTIVE,
                    FamilyDeletionRetirementResult.RETIRED,
                ),
            ),
        )
        assertTrue(
            retirementScanRequiresRetry(
                CurrentProcessFamilyRetirementScan(emptyMap(), retryableFailure = true),
            ),
        )
    }

    @Test
    fun `process retirement registration rolls back marker-only and publishes exact owner`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_030_000_002L, 32L)
        val markerOnly = ProcessRetainedStillDiscardOwner.registerFamilyRetirement(family, null)
        assertEquals(RetainedStillRetirementRegistrationResult.REGISTERED, markerOnly)
        ProcessRetainedStillDiscardOwner.rollbackFamilyRetirementRegistration(
            family,
            listener = null,
            registration = markerOnly,
        )

        val delivered = AtomicInteger()
        val listener = RetainedStillRetirementListener { retired ->
            assertEquals(family, retired)
            delivered.incrementAndGet()
        }
        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            ProcessRetainedStillDiscardOwner.registerFamilyRetirement(family, listener),
        )
        assertEquals(
            0,
            ProcessRetainedStillDiscardOwner.reconcileFamilyRetirement(
                family,
                FamilyDeletionRetirementResult.RETAINED,
            ),
        )
        assertEquals(
            1,
            ProcessRetainedStillDiscardOwner.reconcileFamilyRetirement(
                family,
                FamilyDeletionRetirementResult.ALREADY_ABSENT,
            ),
        )
        assertEquals(1, delivered.get())

        val releasedFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_030_000_003L, 33L)
        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            ProcessRetainedStillDiscardOwner.registerFamilyRetirement(releasedFamily, listener),
        )
        assertEquals(
            1,
            ProcessRetainedStillDiscardOwner.releaseFamilyRetirementListener(listener),
        )
        assertEquals(
            0,
            ProcessRetainedStillDiscardOwner.reconcileFamilyRetirement(
                releasedFamily,
                FamilyDeletionRetirementResult.RETIRED,
            ),
        )
        assertEquals(1, delivered.get())
    }

    @Test
    fun `accepted old Engine rescan reconciles pending replacement Engine exactly once`() {
        val familyA = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_031_000_000L, 31L)
        val familyB = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_031_000_001L, 32L)
        val familyC = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_031_000_002L, 33L)
        val replacementOwner = RetainedStillDeletionOwner<String>(
            maxTombstones = 1,
            maxUnresolvedDiscards = 1,
            maxDiscardAttempts = 1,
            discard = { PendingOutputDiscardResult.UNRESOLVED },
        )
        replacementOwner.registerCaptureFamily(32, familyB)
        replacementOwner.markCaptureDeletedInMemory(32)
        replacementOwner.completeDeletionDurability(32, durable = true)
        replacementOwner.markCaptureProducersTerminal(32)
        assertTrue(replacementOwner.ownRetainedForAsyncDiscard("content://replacement/late", 32))
        assertFalse(replacementOwner.canAdmitCapture())

        val registry = RetainedStillRetirementRegistry(maxFamilies = 2, maxListenersPerFamily = 2)
        val replacementDeliveries = AtomicInteger()
        val oldListener = RetainedStillRetirementListener { }
        val replacementListener = RetainedStillRetirementListener { retired ->
            assertEquals(familyB, retired)
            replacementDeliveries.incrementAndGet()
            replacementOwner.retireDeletedFamily(retired)
        }
        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            registry.register(familyA, oldListener),
        )
        assertEquals(
            RetainedStillRetirementRegistrationResult.REGISTERED,
            registry.register(familyB, replacementListener),
        )
        assertEquals(
            RetainedStillRetirementRegistrationResult.ALREADY_REGISTERED,
            registry.register(familyB, replacementListener),
        )
        assertEquals(
            RetainedStillRetirementRegistrationResult.CAPACITY_EXHAUSTED,
            registry.register(familyC, RetainedStillRetirementListener { }),
        )

        val activeEntered = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val queuedFinished = CountDownLatch(1)
        val oldRescanEntered = CountDownLatch(1)
        val releaseOldRescan = CountDownLatch(1)
        val fillerFinished = CountDownLatch(1)
        val replacementRescanFinished = CountDownLatch(1)
        val replacementRescans = AtomicInteger()
        val capacity = RetainedStillDiscardCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-cross-engine-retirement").apply { isDaemon = true }
            },
        )
        val oldEngine = RetainedStillDiscardDispatcher(capacity)
        val replacementEngine = RetainedStillDiscardDispatcher(capacity)
        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                oldEngine.dispatch(
                    Runnable {
                        activeEntered.countDown()
                        releaseActive.await()
                    },
                ),
            )
            assertTrue(activeEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                oldEngine.dispatch(Runnable { queuedFinished.countDown() }),
            )
            assertEquals(
                RetainedStillDiscardDispatch.OVERFLOW,
                oldEngine.dispatchRetirement(
                    Runnable { error("overflowed old Engine task ran") },
                    Runnable {
                        oldRescanEntered.countDown()
                        releaseOldRescan.await()
                        registry.reconcile(familyA, FamilyDeletionRetirementResult.RETIRED)
                        // The process scan also retired B. Publication must reach B's exact local
                        // owner even though this accepted closure came from Engine A.
                        registry.reconcile(familyB, FamilyDeletionRetirementResult.RETIRED)
                    },
                ),
            )

            releaseActive.countDown()
            assertTrue(queuedFinished.await(5, TimeUnit.SECONDS))
            assertTrue(oldRescanEntered.await(5, TimeUnit.SECONDS))
            // Keep the lane full while B overflows behind A's already-accepted rescan.
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                replacementEngine.dispatch(Runnable { fillerFinished.countDown() }),
            )
            assertEquals(
                RetainedStillDiscardDispatch.OVERFLOW,
                replacementEngine.dispatchRetirement(
                    Runnable { error("overflowed replacement Engine task ran") },
                    Runnable {
                        replacementRescans.incrementAndGet()
                        registry.reconcile(familyB, FamilyDeletionRetirementResult.ALREADY_ABSENT)
                        replacementRescanFinished.countDown()
                    },
                ),
            )

            releaseOldRescan.countDown()
            assertTrue(fillerFinished.await(5, TimeUnit.SECONDS))
            assertTrue(replacementRescanFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, replacementRescans.get())
            assertEquals(1, replacementDeliveries.get())
            assertEquals(0, replacementOwner.unresolvedDiscardCount())
            assertTrue(replacementOwner.canAdmitCapture())
            assertEquals(0, registry.registrationCount())
            assertEquals(
                0,
                registry.reconcile(familyB, FamilyDeletionRetirementResult.ALREADY_ABSENT),
            )
            assertEquals(1, replacementDeliveries.get())
        } finally {
            releaseActive.countDown()
            releaseOldRescan.countDown()
            oldEngine.shutdown()
            replacementEngine.shutdown()
        }
    }

    @Test
    fun `blocked provider stays finite across repeated Engine replacement`() {
        val releaseProvider = CountDownLatch(1)
        val providerEntered = CountDownLatch(1)
        val oldFinished = CountDownLatch(2)
        val callbacks = CopyOnWriteArrayList<String>()
        val overflowRan = AtomicBoolean()
        val createdThreads = AtomicInteger()
        val capacity = RetainedStillDiscardCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-retained-discard-${createdThreads.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
        val oldEngine = RetainedStillDiscardDispatcher(capacity)

        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                oldEngine.dispatch(
                    Runnable {
                        providerEntered.countDown()
                        releaseProvider.await()
                        callbacks += "old-blocked"
                        oldFinished.countDown()
                    },
                ),
            )
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                oldEngine.dispatch(Runnable { callbacks += "old-queued"; oldFinished.countDown() }),
            )
            oldEngine.shutdown()
            assertEquals(
                RetainedStillDiscardDispatch.SHUTDOWN,
                oldEngine.dispatch(Runnable { callbacks += "stale-after-shutdown" }),
            )

            repeat(32) {
                val replacement = RetainedStillDiscardDispatcher(capacity)
                assertEquals(
                    RetainedStillDiscardDispatch.OVERFLOW,
                    replacement.dispatch(Runnable { overflowRan.set(true) }),
                )
                replacement.shutdown()
            }

            assertEquals(1, capacity.activeTaskCount())
            assertEquals(1, capacity.queuedTaskCount())
            assertEquals(1, createdThreads.get())
            assertFalse(overflowRan.get())

            releaseProvider.countDown()
            assertTrue(oldFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-blocked", "old-queued"), callbacks.toList())

            val currentFinished = CountDownLatch(1)
            val currentEngine = RetainedStillDiscardDispatcher(capacity)
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                currentEngine.dispatch(
                    Runnable {
                        callbacks += "current"
                        currentFinished.countDown()
                    },
                ),
            )
            assertTrue(currentFinished.await(5, TimeUnit.SECONDS))
            assertEquals("current", callbacks.last())
            currentEngine.shutdown()
        } finally {
            releaseProvider.countDown()
            oldEngine.shutdown()
        }
    }
}
