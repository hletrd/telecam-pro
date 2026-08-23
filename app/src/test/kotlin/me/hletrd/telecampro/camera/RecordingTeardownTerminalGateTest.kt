package me.hletrd.telecampro.camera

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.video.RecorderQuarantineAdmissionGate
import me.hletrd.telecampro.video.FinalizedRecordingValidation
import me.hletrd.telecampro.video.FrozenRecordingStorage
import me.hletrd.telecampro.video.RecordingStorageEffects
import me.hletrd.telecampro.video.VideoRecorder
import me.hletrd.telecampro.video.completeFrozenRecordingStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTeardownTerminalGateTest {

    @Test(expected = IllegalArgumentException::class)
    fun `pending is not a terminal native classification`() {
        RecorderNativeFinalizationGate().classify(RecorderNativeFinalization.PENDING)
    }

    @Test
    fun `interrupted native-classification wait preserves interruption and remains pending`() {
        val classification = RecorderNativeFinalizationGate()
        val observed = java.util.concurrent.atomic.AtomicReference<RecorderNativeFinalization>()
        val interrupted = AtomicBoolean(false)
        val worker = Thread {
            Thread.currentThread().interrupt()
            observed.set(classification.await(1, TimeUnit.SECONDS))
            interrupted.set(Thread.currentThread().isInterrupted)
        }

        worker.start()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertEquals(RecorderNativeFinalization.PENDING, observed.get())
        assertTrue(interrupted.get())
    }

    @Test
    fun `native finalization is first-wins across release and quarantine`() {
        repeat(100) {
            val classification = RecorderNativeFinalizationGate()
            val start = CountDownLatch(1)
            val releaseWon = AtomicBoolean(false)
            val quarantineWon = AtomicBoolean(false)
            val releaseThread = Thread {
                start.await()
                releaseWon.set(classification.classify(RecorderNativeFinalization.RELEASED))
            }
            val quarantineThread = Thread {
                start.await()
                quarantineWon.set(classification.classify(RecorderNativeFinalization.QUARANTINED))
            }

            releaseThread.start()
            quarantineThread.start()
            start.countDown()
            releaseThread.join(5_000)
            quarantineThread.join(5_000)

            assertTrue(releaseWon.get() xor quarantineWon.get())
            assertEquals(
                if (releaseWon.get()) {
                    RecorderNativeFinalization.RELEASED
                } else {
                    RecorderNativeFinalization.QUARANTINED
                },
                classification.current(),
            )
            assertEquals(classification.current(), classification.await(0, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `blocked storage tail cannot quarantine released native graph and admission is free`() {
        val processAdmission = RecorderQuarantineAdmissionGate()
        val active = checkNotNull(processAdmission.snapshot(Any()))
        assertTrue(processAdmission.publish(active) { true })
        val classification = RecorderNativeFinalizationGate()
        val storageTailStillBlocked = CountDownLatch(1)

        assertTrue(
            completeRecorderNativeRelease(
                deadlineComplete = { true },
                classification = classification,
                releaseProcessAdmission = { processAdmission.finish(active) },
            ),
        )

        // Engine release observes native truth only; the independent task/storage latch is still
        // blocked and must have no power to turn RELEASED into process quarantine.
        assertEquals(1L, storageTailStillBlocked.count)
        assertEquals(
            RecorderNativeFinalization.RELEASED,
            nativeFinalizationAtEngineRelease(classification, 0, TimeUnit.MILLISECONDS),
        )
        assertNotNull(processAdmission.snapshot(Any()))
    }

    @Test
    fun `real post-native executor block admits and stores a second recording independently`() {
        val nativeLane = Executors.newSingleThreadExecutor()
        val storageLane = Executors.newCachedThreadPool()
        val processAdmission = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val firstToken = checkNotNull(processAdmission.snapshot(owner))
        assertTrue(processAdmission.publish(firstToken) { true })
        val firstNative = RecorderNativeFinalizationGate()
        val firstPublishEntered = CountDownLatch(1)
        val releaseFirstPublish = CountDownLatch(1)
        val secondStored = CountDownLatch(1)
        val firstStored = CountDownLatch(1)
        val firstResult = AtomicReference<VideoRecorder.StopResult>()
        val secondResult = AtomicReference<VideoRecorder.StopResult>()
        val completed = ConcurrentLinkedQueue<String>()
        val deleted = ConcurrentLinkedQueue<String>()

        try {
            nativeLane.execute {
                assertTrue(
                    completeRecorderNativeRelease(
                        deadlineComplete = { true },
                        classification = firstNative,
                        releaseProcessAdmission = { processAdmission.finish(firstToken) },
                    ),
                )
                storageLane.execute {
                    firstResult.set(
                        completeFrozenRecordingStorage(
                            completeFrozen("first"),
                            RecordingStorageEffects(
                                validateVideoTrack = { true },
                                markComplete = { completed += it; true },
                                publish = {
                                    firstPublishEntered.countDown()
                                    releaseFirstPublish.await()
                                    false // durable COMPLETE row stays pending for launch recovery
                                },
                                delete = { deleted += it },
                            ),
                        ),
                    )
                    firstStored.countDown()
                }
            }

            assertTrue(firstPublishEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RecorderNativeFinalization.RELEASED,
                nativeFinalizationAtEngineRelease(firstNative, 0, TimeUnit.MILLISECONDS),
            )

            // The first provider call is genuinely blocked, yet strict native release returned the
            // process lease and the same owner can acquire/publish the second REC immediately.
            val secondToken = checkNotNull(processAdmission.snapshot(owner))
            assertTrue(processAdmission.publish(secondToken) { true })
            val secondNative = RecorderNativeFinalizationGate()
            nativeLane.execute {
                assertTrue(
                    completeRecorderNativeRelease(
                        deadlineComplete = { true },
                        classification = secondNative,
                        releaseProcessAdmission = { processAdmission.finish(secondToken) },
                    ),
                )
                storageLane.execute {
                    secondResult.set(
                        completeFrozenRecordingStorage(
                            completeFrozen("second"),
                            RecordingStorageEffects(
                                validateVideoTrack = { true },
                                markComplete = { completed += it; true },
                                publish = { true },
                                delete = { deleted += it },
                            ),
                        ),
                    )
                    secondStored.countDown()
                }
            }

            assertTrue(secondStored.await(5, TimeUnit.SECONDS))
            assertTrue(secondResult.get().saved)
            assertFalse(firstStored.await(25, TimeUnit.MILLISECONDS))
            assertEquals(listOf("first", "second"), completed.toList())
            assertTrue(deleted.isEmpty())

            releaseFirstPublish.countDown()
            assertTrue(firstStored.await(5, TimeUnit.SECONDS))
            assertFalse(firstResult.get().saved)
            assertEquals(null, firstResult.get().error)
            assertEquals(RecorderNativeFinalization.RELEASED, secondNative.current())
            assertNotNull(processAdmission.snapshot(owner))
        } finally {
            releaseFirstPublish.countDown()
            nativeLane.shutdownNow()
            storageLane.shutdownNow()
        }
    }

    private fun completeFrozen(id: String) = FrozenRecordingStorage(
        outputUri = id,
        muxerStarted = true,
        wroteVideoSample = true,
        failure = null,
        finalizedValidation = FinalizedRecordingValidation.NOT_REQUIRED,
    )

    @Test
    fun `unresolved native graph is quarantined and late release cannot free admission`() {
        val processAdmission = RecorderQuarantineAdmissionGate()
        val active = checkNotNull(processAdmission.snapshot(Any()))
        assertTrue(processAdmission.publish(active) { true })
        val classification = RecorderNativeFinalizationGate()
        var admissionReleased = false

        assertEquals(
            RecorderNativeFinalization.QUARANTINED,
            nativeFinalizationAtEngineRelease(classification, 0, TimeUnit.MILLISECONDS),
        )
        processAdmission.close()
        assertFalse(
            completeRecorderNativeRelease(
                deadlineComplete = { false },
                classification = classification,
                releaseProcessAdmission = {
                    admissionReleased = true
                    processAdmission.finish(active)
                },
            ),
        )

        assertFalse(admissionReleased)
        assertEquals(RecorderNativeFinalization.QUARANTINED, classification.current())
        assertNull(processAdmission.snapshot(Any()))
    }

    @Test
    fun `release callback refuses a classification already owned by quarantine`() {
        val classification = RecorderNativeFinalizationGate()
        assertTrue(classification.classify(RecorderNativeFinalization.QUARANTINED))
        var admissionReleased = false

        assertFalse(
            completeRecorderNativeRelease(
                deadlineComplete = { true },
                classification = classification,
                releaseProcessAdmission = { admissionReleased = true },
            ),
        )

        assertFalse(admissionReleased)
        assertEquals(RecorderNativeFinalization.QUARANTINED, classification.current())
    }

    @Test
    fun `operation timeout makes late completion inert`() {
        val scheduler = DeterministicScheduler()
        val failures = mutableListOf<Throwable>()
        val deadline = RecordingOperationDeadline(
            scheduler = scheduler,
            timeoutMs = 500L,
            failure = { TimeoutException("native operation timed out") },
            onTimeout = failures::add,
        )

        assertTrue(deadline.arm())
        assertEquals(RecordingOperationState.ACTIVE, deadline.current())
        scheduler.fire(0)

        assertEquals(RecordingOperationState.TIMED_OUT, deadline.current())
        assertEquals("native operation timed out", failures.single().message)
        assertFalse(deadline.complete())
    }

    @Test
    fun `operation completion cancels deadline and late timer is inert`() {
        val scheduler = DeterministicScheduler()
        var timeouts = 0
        val deadline = RecordingOperationDeadline(
            scheduler = scheduler,
            timeoutMs = 500L,
            failure = { TimeoutException() },
            onTimeout = { timeouts++ },
        )

        assertTrue(deadline.arm())
        assertFalse(deadline.arm())
        assertTrue(deadline.complete())
        scheduler.fireEvenIfCancelled(0)

        assertEquals(RecordingOperationState.COMPLETED, deadline.current())
        assertEquals(0, timeouts)
        assertEquals(listOf(1, 1), scheduler.cancellationCounts())
    }

    @Test
    fun `operation scheduler rejection fails closed before native work`() {
        val scheduler = DeterministicScheduler(rejectCalls = setOf(1))
        val failures = mutableListOf<Throwable>()
        val deadline = RecordingOperationDeadline(
            scheduler = scheduler,
            timeoutMs = 500L,
            failure = { IllegalStateException("operation watchdog unavailable") },
            onTimeout = failures::add,
        )

        assertFalse(deadline.arm())
        assertEquals(RecordingOperationState.TIMED_OUT, deadline.current())
        assertEquals("operation watchdog unavailable", failures.single().message)
        assertFalse(deadline.complete())
    }

    @Test
    fun `accepted detach that never calls back enters recovery then hard quarantine`() {
        val scheduler = DeterministicScheduler()
        val recoveryFailures = mutableListOf<Throwable>()
        val terminals = mutableListOf<TerminalEvent>()
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = recoveryFailures::add,
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertTrue(coordinator.start { /* Accepted, but the GL owner never runs the callback. */ })
        assertEquals(listOf(DETACH_TIMEOUT_MS, HARD_TIMEOUT_MS), scheduler.delays())

        scheduler.fire(0)

        assertTrue(coordinator.hasStartedRecovery())
        assertTrue(recoveryFailures.single() is TimeoutException)
        assertNull(coordinator.current())
        assertTrue(terminals.isEmpty())

        scheduler.fire(1)

        assertEquals(RecordingTeardownTerminal.QUARANTINE, coordinator.current())
        assertEquals(RecordingTeardownTerminal.QUARANTINE, terminals.single().terminal)
        assertTrue(terminals.single().failure is TimeoutException)
        assertEquals(listOf(1, 1), scheduler.cancellationCounts())
    }

    @Test
    fun `explicit detach failure starts recovery and strict release finalizes`() {
        val scheduler = DeterministicScheduler()
        val detachFailure = IllegalStateException("detach failed")
        val recoveryFailures = mutableListOf<Throwable>()
        val terminals = mutableListOf<TerminalEvent>()
        lateinit var detachResult: (Result<Unit>) -> Unit
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = recoveryFailures::add,
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertTrue(coordinator.start { detachResult = it })
        detachResult(Result.failure(detachFailure))

        assertSame(detachFailure, recoveryFailures.single())
        assertTrue(coordinator.hasStartedRecovery())
        assertNull(coordinator.current())

        coordinator.resourcesReleased()

        assertEquals(RecordingTeardownTerminal.FINALIZE, coordinator.current())
        assertEquals(TerminalEvent(RecordingTeardownTerminal.FINALIZE, null), terminals.single())
        assertEquals(listOf(1, 1), scheduler.cancellationCounts())
    }

    @Test
    fun `direct detach success finalizes without recovery`() {
        val scheduler = DeterministicScheduler()
        var recoveryCalls = 0
        val terminals = mutableListOf<TerminalEvent>()
        lateinit var detachResult: (Result<Unit>) -> Unit
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = { recoveryCalls += 1 },
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertTrue(coordinator.start { detachResult = it })
        detachResult(Result.success(Unit))

        assertEquals(RecordingTeardownTerminal.FINALIZE, coordinator.current())
        assertFalse(coordinator.hasStartedRecovery())
        assertEquals(0, recoveryCalls)
        assertEquals(TerminalEvent(RecordingTeardownTerminal.FINALIZE, null), terminals.single())
        assertEquals(listOf(1, 1), scheduler.cancellationCounts())
    }

    @Test
    fun `failed detach followed by abandoned recovery quarantines`() {
        val scheduler = DeterministicScheduler()
        val detachFailure = IllegalStateException("detach failed")
        val abandonment = IllegalStateException("GL owner abandoned")
        val recoveryFailures = mutableListOf<Throwable>()
        val terminals = mutableListOf<TerminalEvent>()
        lateinit var detachResult: (Result<Unit>) -> Unit
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = recoveryFailures::add,
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertTrue(coordinator.start { detachResult = it })
        detachResult(Result.failure(detachFailure))
        coordinator.recoveryAbandoned(abandonment)

        assertSame(detachFailure, recoveryFailures.single())
        assertEquals(RecordingTeardownTerminal.QUARANTINE, coordinator.current())
        assertEquals(RecordingTeardownTerminal.QUARANTINE, terminals.single().terminal)
        assertSame(abandonment, terminals.single().failure)
        assertEquals(listOf(1, 1), scheduler.cancellationCounts())
    }

    @Test
    fun `hard timeout wins and every late callback is inert`() {
        val scheduler = DeterministicScheduler()
        val terminals = mutableListOf<TerminalEvent>()
        var recoveryCalls = 0
        lateinit var detachResult: (Result<Unit>) -> Unit
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = { recoveryCalls += 1 },
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertTrue(coordinator.start { detachResult = it })
        scheduler.fire(1)
        val winningFailure = terminals.single().failure

        detachResult(Result.success(Unit))
        detachResult(Result.failure(IllegalStateException("late detach failure")))
        coordinator.resourcesReleased()
        coordinator.recoveryAbandoned(IllegalStateException("late abandonment"))
        scheduler.fireEvenIfCancelled(0)

        assertEquals(RecordingTeardownTerminal.QUARANTINE, coordinator.current())
        assertEquals(1, terminals.size)
        assertTrue(winningFailure is TimeoutException)
        assertEquals(0, recoveryCalls)
        assertFalse(coordinator.hasStartedRecovery())
    }

    @Test
    fun `first scheduler rejection quarantines before detach submission`() {
        listOf(
            DeterministicScheduler(rejectCalls = setOf(1)),
            DeterministicScheduler(throwCalls = setOf(1)),
        ).forEach { scheduler ->
            val terminals = mutableListOf<TerminalEvent>()
            var submissions = 0
            val coordinator = coordinator(
                scheduler = scheduler,
                onRecoveryRequired = { error("recovery must not run") },
                onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
            )

            assertFalse(coordinator.start { submissions += 1 })

            assertEquals(0, submissions)
            assertEquals(1, scheduler.scheduleAttempts.get())
            assertTrue(scheduler.tasks.isEmpty())
            assertEquals(RecordingTeardownTerminal.QUARANTINE, coordinator.current())
            assertEquals(RecordingTeardownTerminal.QUARANTINE, terminals.single().terminal)
            assertEquals("Recording detach watchdog unavailable", terminals.single().failure?.message)
        }
    }

    @Test
    fun `second scheduler rejection cancels first deadline and never submits detach`() {
        val scheduler = DeterministicScheduler(rejectCalls = setOf(2))
        val terminals = mutableListOf<TerminalEvent>()
        var submissions = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = { error("recovery must not run") },
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertFalse(coordinator.start { submissions += 1 })

        assertEquals(0, submissions)
        assertEquals(2, scheduler.scheduleAttempts.get())
        assertEquals(listOf(DETACH_TIMEOUT_MS), scheduler.delays())
        assertEquals(listOf(1), scheduler.cancellationCounts())
        assertEquals(RecordingTeardownTerminal.QUARANTINE, coordinator.current())
        assertEquals(RecordingTeardownTerminal.QUARANTINE, terminals.single().terminal)
        assertEquals("Recording quarantine watchdog unavailable", terminals.single().failure?.message)
    }

    @Test
    fun `recovery may synchronously report resources released without deadlock`() {
        val scheduler = DeterministicScheduler()
        val terminals = mutableListOf<TerminalEvent>()
        val detachFailure = IllegalStateException("detach failed")
        lateinit var detachResult: (Result<Unit>) -> Unit
        lateinit var coordinator: RecordingTeardownCoordinator
        coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = { failure ->
                assertSame(detachFailure, failure)
                coordinator.resourcesReleased()
            },
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertTrue(coordinator.start { detachResult = it })
        detachResult(Result.failure(detachFailure))

        assertTrue(coordinator.hasStartedRecovery())
        assertEquals(RecordingTeardownTerminal.FINALIZE, coordinator.current())
        assertEquals(TerminalEvent(RecordingTeardownTerminal.FINALIZE, null), terminals.single())
        assertEquals(listOf(1, 1), scheduler.cancellationCounts())
    }

    @Test
    fun `strict release and hard timeout race selects exactly one terminal`() {
        repeat(100) {
            val scheduler = DeterministicScheduler()
            val terminals = ConcurrentLinkedQueue<TerminalEvent>()
            val coordinator = coordinator(
                scheduler = scheduler,
                onRecoveryRequired = { error("recovery must not run") },
                onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
            )
            assertTrue(coordinator.start { /* Keep detach pending while terminal events race. */ })
            val start = CountDownLatch(1)
            val releaseThread = Thread {
                start.await()
                coordinator.resourcesReleased()
            }
            val timeoutThread = Thread {
                start.await()
                scheduler.fireEvenIfCancelled(1)
            }

            releaseThread.start()
            timeoutThread.start()
            start.countDown()
            releaseThread.join()
            timeoutThread.join()

            assertEquals(1, terminals.size)
            assertEquals(terminals.single().terminal, coordinator.current())
            assertTrue(
                coordinator.current() == RecordingTeardownTerminal.FINALIZE ||
                    coordinator.current() == RecordingTeardownTerminal.QUARANTINE,
            )
        }
    }

    @Test
    fun `terminal claim precedes delayed side effects and late events cannot duplicate cleanup`() {
        val scheduler = DeterministicScheduler()
        val terminalEntries = AtomicInteger()
        val completionSignals = AtomicInteger()
        val microphoneReleases = AtomicInteger()
        val recoveryCalls = AtomicInteger()
        val sideEffectEntered = CountDownLatch(1)
        val allowSideEffect = CountDownLatch(1)
        lateinit var detachResult: (Result<Unit>) -> Unit
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = { recoveryCalls.incrementAndGet() },
            onTerminal = { _, _ ->
                terminalEntries.incrementAndGet()
                sideEffectEntered.countDown()
                assertTrue(allowSideEffect.await(5, TimeUnit.SECONDS))
                microphoneReleases.incrementAndGet()
                completionSignals.incrementAndGet()
            },
        )
        assertTrue(coordinator.start { detachResult = it })
        val terminalThread = Thread { detachResult(Result.success(Unit)) }

        terminalThread.start()
        try {
            assertTrue(sideEffectEntered.await(5, TimeUnit.SECONDS))
            assertEquals(RecordingTeardownTerminal.FINALIZE, coordinator.current())

            scheduler.fireEvenIfCancelled(0)
            scheduler.fireEvenIfCancelled(1)
            detachResult(Result.failure(IllegalStateException("late detach failure")))
            coordinator.resourcesReleased()
            coordinator.recoveryAbandoned(IllegalStateException("late abandonment"))
        } finally {
            allowSideEffect.countDown()
            terminalThread.join(5_000)
        }

        assertFalse(terminalThread.isAlive)
        assertEquals(1, terminalEntries.get())
        assertEquals(1, microphoneReleases.get())
        assertEquals(1, completionSignals.get())
        assertEquals(0, recoveryCalls.get())
        assertEquals(RecordingTeardownTerminal.FINALIZE, coordinator.current())
    }

    @Test
    fun `synchronously throwing detach submission starts recovery and refuses the start`() {
        // submitDetach posts to the GL thread; a dead/rejecting executor throws HERE, not in a
        // callback. That is a detach failure like any other: recovery owns it, start reports false.
        val scheduler = DeterministicScheduler()
        val submitFailure = IllegalStateException("GL queue rejected detach")
        val recoveryFailures = mutableListOf<Throwable>()
        val terminals = mutableListOf<TerminalEvent>()
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = recoveryFailures::add,
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertFalse(coordinator.start { throw submitFailure })

        assertSame(submitFailure, recoveryFailures.single())
        assertTrue(coordinator.hasStartedRecovery())
        // Recovery is in flight, not terminal: strict release may still finalize this recorder.
        assertNull(coordinator.current())
        assertTrue(terminals.isEmpty())
        assertEquals(listOf(DETACH_TIMEOUT_MS, HARD_TIMEOUT_MS), scheduler.delays())
    }

    @Test
    fun `hard deadline firing inside schedule is terminal before installation`() {
        // A degenerate watchdog may run its action synchronously inside schedule() (zero-delay
        // executor, or a clock already past the deadline). The quarantine terminal then lands
        // BEFORE armDeadline can install the cancellation — which must be cancelled, not leaked,
        // and the already-terminal coordinator must refuse the installation and the detach submit.
        val cancelCounts = mutableListOf<AtomicInteger>()
        var scheduleCalls = 0
        val scheduler = RecordingTeardownScheduler { _, action ->
            scheduleCalls++
            val cancels = AtomicInteger().also(cancelCounts::add)
            if (scheduleCalls == 2) action() // the hard deadline fires during scheduling
            RecordingTeardownCancellation { cancels.incrementAndGet() }
        }
        val recoveryFailures = mutableListOf<Throwable>()
        val terminals = mutableListOf<TerminalEvent>()
        var submitted = false
        val coordinator = coordinator(
            scheduler = scheduler,
            onRecoveryRequired = recoveryFailures::add,
            onTerminal = { terminal, failure -> terminals += TerminalEvent(terminal, failure) },
        )

        assertFalse(coordinator.start { submitted = true })

        assertFalse(submitted)
        assertTrue(recoveryFailures.isEmpty())
        assertEquals(RecordingTeardownTerminal.QUARANTINE, coordinator.current())
        // Exactly one terminal (the in-schedule hard timeout); the follow-up "watchdog
        // unavailable" finish is inert against the already-claimed terminal.
        assertEquals(RecordingTeardownTerminal.QUARANTINE, terminals.single().terminal)
        assertTrue(terminals.single().failure is TimeoutException)
        // Both the installed detach deadline and the never-installed hard cancellation cancel once.
        assertEquals(listOf(1, 1), cancelCounts.map { it.get() })
    }

    private fun coordinator(
        scheduler: RecordingTeardownScheduler,
        onRecoveryRequired: (Throwable) -> Unit,
        onTerminal: (RecordingTeardownTerminal, Throwable?) -> Unit,
    ) = RecordingTeardownCoordinator(
        scheduler = scheduler,
        detachTimeoutMs = DETACH_TIMEOUT_MS,
        hardTimeoutMs = HARD_TIMEOUT_MS,
        onRecoveryRequired = onRecoveryRequired,
        onTerminal = onTerminal,
    )

    private data class TerminalEvent(
        val terminal: RecordingTeardownTerminal,
        val failure: Throwable?,
    )

    private class DeterministicScheduler(
        private val rejectCalls: Set<Int> = emptySet(),
        private val throwCalls: Set<Int> = emptySet(),
    ) : RecordingTeardownScheduler {
        val scheduleAttempts = AtomicInteger()
        val tasks = mutableListOf<ScheduledTask>()

        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): RecordingTeardownCancellation? {
            val call = scheduleAttempts.incrementAndGet()
            if (call in throwCalls) {
                throw RejectedExecutionException("rejected schedule $call")
            }
            if (call in rejectCalls) return null
            val task = ScheduledTask(delayMs, action)
            tasks += task
            return RecordingTeardownCancellation {
                task.cancelled.set(true)
                task.cancellationCalls.incrementAndGet()
            }
        }

        fun delays(): List<Long> = tasks.map { it.delayMs }

        fun cancellationCounts(): List<Int> = tasks.map { it.cancellationCalls.get() }

        fun fire(index: Int) {
            tasks[index].takeUnless { it.cancelled.get() }?.action?.invoke()
        }

        fun fireEvenIfCancelled(index: Int) {
            tasks[index].action()
        }
    }

    private data class ScheduledTask(
        val delayMs: Long,
        val action: () -> Unit,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val cancellationCalls: AtomicInteger = AtomicInteger(),
    )

    private companion object {
        const val DETACH_TIMEOUT_MS = 2_000L
        const val HARD_TIMEOUT_MS = 4_500L
    }
}
