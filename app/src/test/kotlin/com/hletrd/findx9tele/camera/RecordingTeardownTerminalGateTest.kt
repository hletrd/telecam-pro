package com.hletrd.findx9tele.camera

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTeardownTerminalGateTest {

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
