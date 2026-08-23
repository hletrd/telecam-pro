package me.hletrd.telecampro.camera

import me.hletrd.telecampro.storage.OrphanRecoveryBatch
import me.hletrd.telecampro.storage.OrphanRecoveryCursor
import me.hletrd.telecampro.storage.OrphanDisposition
import me.hletrd.telecampro.storage.PendingJournalState
import me.hletrd.telecampro.storage.PendingProbe
import me.hletrd.telecampro.storage.RecoveryReport
import me.hletrd.telecampro.storage.RecoveryEvent
import me.hletrd.telecampro.storage.RecoveryFailureClass
import me.hletrd.telecampro.storage.RecoveryRetryDecision
import me.hletrd.telecampro.storage.orphanDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchMediaRecoveryCoordinatorTest {
    private class TestDeadlineScheduler : LaunchMediaRecoveryDeadlineScheduler {
        private class Entry(
            val delayMs: Long,
            val action: () -> Unit,
        ) {
            var canceled = false
        }

        private val entries = mutableListOf<Entry>()

        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): LaunchMediaRecoveryDeadlineCancellation = Entry(delayMs, action).let { entry ->
            entries += entry
            LaunchMediaRecoveryDeadlineCancellation { entry.canceled = true }
        }

        fun fire(index: Int = entries.lastIndex) {
            entries[index].takeUnless { it.canceled }?.action?.invoke()
        }

        fun delayAt(index: Int = entries.lastIndex): Long = entries[index].delayMs
        fun isCanceled(index: Int = entries.lastIndex): Boolean = entries[index].canceled
        fun count(): Int = entries.size
    }

    private fun <T : Any> coordinator(
        scheduler: TestDeadlineScheduler = TestDeadlineScheduler(),
        dispatch: (Runnable) -> Boolean,
    ) = LaunchMediaRecoveryCoordinator<T>(
        dispatch = dispatch,
        deadlineScheduler = scheduler,
        deadlineMs = TEST_DEADLINE_MS,
    )

    @Test
    fun `dispatcher rejection terminally fails and retires the sole admission`() {
        val coordinator = coordinator<Int> { false }
        val owner = Any()
        var terminal: Result<Int>? = null

        coordinator.request(owner, recover = { 1 }) { terminal = it }

        assertTrue(terminal?.exceptionOrNull() is IllegalStateException)
        assertFalse(coordinator.isRunning())
        assertEquals(0, coordinator.subscriberCount())
    }

    @Test
    fun `dispatcher exception is the exact typed terminal failure`() {
        val failure = IllegalStateException("dispatcher unavailable")
        val coordinator = coordinator<Int> { throw failure }
        var terminal: Result<Int>? = null

        coordinator.request(Any(), recover = { 1 }) { terminal = it }

        assertTrue(terminal?.exceptionOrNull() === failure)
        assertFalse(coordinator.isRunning())
        assertEquals(0, coordinator.subscriberCount())
    }

    @Test
    fun `unavailable deadline exhausts capacity before provider work is dispatched`() {
        var dispatches = 0
        val coordinator = LaunchMediaRecoveryCoordinator<Int>(
            dispatch = {
                dispatches += 1
                true
            },
            deadlineScheduler = LaunchMediaRecoveryDeadlineScheduler { _, _ -> null },
            deadlineMs = TEST_DEADLINE_MS,
        )
        val delivered = mutableListOf<Result<Int>>()

        coordinator.request(Any(), recover = { 1 }) { delivered += it }
        coordinator.request(Any(), recover = { 2 }) { delivered += it }

        val failure = delivered.first().exceptionOrNull()
        assertEquals(0, dispatches)
        assertEquals(2, delivered.size)
        assertTrue(failure is LaunchMediaRecoveryCapacityExhaustedException)
        assertTrue(delivered.all { it.exceptionOrNull() === failure })
        assertTrue(coordinator.isExhausted())
        assertEquals(0, coordinator.subscriberCount())
    }

    @Test
    fun `blocked recovery stays single flight across engine replacement`() {
        val tasks = ArrayDeque<Runnable>()
        val coordinator = coordinator<Int> { task -> tasks.addLast(task); true }
        val delivered = mutableListOf<Int>()
        val oldOwners = List(64) { Any() }
        oldOwners.forEachIndexed { index, owner ->
            val subscription = coordinator.request(owner, recover = { 37 }) {
                delivered += it.getOrThrow()
            }
            if (index < oldOwners.lastIndex) subscription.cancel()
        }

        assertEquals(1, tasks.size)
        assertEquals(1, coordinator.subscriberCount())
        assertTrue(coordinator.isRunning())

        tasks.removeFirst().run()

        assertEquals(listOf(37), delivered)
        assertEquals(0, coordinator.subscriberCount())
        assertFalse(coordinator.isRunning())
    }

    @Test
    fun `request after completion starts one fresh recovery`() {
        val tasks = ArrayDeque<Runnable>()
        val coordinator = coordinator<Int> { task -> tasks.addLast(task); true }
        var recoveries = 0

        coordinator.request(Any(), recover = { ++recoveries }) {}
        tasks.removeFirst().run()
        coordinator.request(Any(), recover = { ++recoveries }) {}

        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(2, recoveries)
    }

    @Test
    fun `throwing recovery terminally fails live subscribers and permits a later request`() {
        val tasks = ArrayDeque<Runnable>()
        val coordinator = coordinator<Int> { task -> tasks.addLast(task); true }
        val failure = IllegalStateException("provider escaped")
        val firstOwner = Any()
        val secondOwner = Any()
        val canceledOwner = Any()
        val delivered = mutableListOf<Result<Int>>()

        coordinator.request(firstOwner, recover = { throw failure }) { delivered += it }
        coordinator.request(secondOwner, recover = { error("single flight must use the first recovery") }) {
            delivered += it
        }
        val canceled = coordinator.request(
            canceledOwner,
            recover = { error("single flight must use the first recovery") },
        ) { delivered += it }
        canceled.cancel()

        assertEquals(1, tasks.size)
        tasks.removeFirst().run()

        assertEquals(2, delivered.size)
        assertTrue(delivered.all { it.exceptionOrNull() === failure })
        assertFalse(coordinator.isRunning())
        assertEquals(0, coordinator.subscriberCount())

        var restored = 0
        coordinator.request(Any(), recover = { 41 }) { result ->
            restored = result.getOrThrow()
        }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(41, restored)
    }

    @Test
    fun `cancellation remains authoritative after terminal callbacks are snapshotted`() {
        val tasks = ArrayDeque<Runnable>()
        val coordinator = coordinator<Int> { task -> tasks.addLast(task); true }
        val delivered = mutableListOf<String>()
        lateinit var laterSubscription: LaunchMediaRecoverySubscription

        coordinator.request(Any(), recover = { 37 }) { result ->
            delivered += "first:${result.getOrThrow()}"
            // Terminal delivery already copied and cleared the subscriber set before invoking this
            // callback. Cancellation must still retire the later copied subscription token.
            laterSubscription.cancel()
        }
        laterSubscription = coordinator.request(
            owner = Any(),
            recover = { error("single flight must use the first recovery") },
        ) { result -> delivered += "later:${result.getOrThrow()}" }

        tasks.removeFirst().run()

        assertEquals(listOf("first:37"), delivered)
        assertFalse(coordinator.isRunning())
        assertEquals(0, coordinator.subscriberCount())
    }

    @Test
    fun `replacing one owner cancels its earlier subscription identity`() {
        val tasks = ArrayDeque<Runnable>()
        val coordinator = coordinator<Int> { task -> tasks.addLast(task); true }
        val owner = Any()
        val delivered = mutableListOf<String>()

        coordinator.request(owner, recover = { 41 }) { delivered += "old:${it.getOrThrow()}" }
        coordinator.request(owner, recover = { error("single flight must use the first recovery") }) {
            delivered += "new:${it.getOrThrow()}"
        }

        tasks.removeFirst().run()

        assertEquals(listOf("new:41"), delivered)
    }

    @Test
    fun `never-returning recovery deadline terminally fails every live engine subscriber`() {
        val tasks = ArrayDeque<Runnable>()
        val scheduler = TestDeadlineScheduler()
        val coordinator = coordinator<Int>(scheduler) { task -> tasks.addLast(task); true }
        val oldOwner = Any()
        val replacementOwner = Any()
        val delivered = mutableListOf<Pair<String, Throwable?>>()

        val oldSubscription = coordinator.request(oldOwner, recover = { error("never returns") }) {
            delivered += "old" to it.exceptionOrNull()
        }
        oldSubscription.cancel()
        coordinator.request(replacementOwner, recover = { error("must stay single-flight") }) {
            delivered += "replacement" to it.exceptionOrNull()
        }

        assertEquals(TEST_DEADLINE_MS, scheduler.delayAt())
        assertEquals(1, tasks.size)
        assertEquals(1, coordinator.subscriberCount())

        scheduler.fire()

        assertEquals(listOf("replacement"), delivered.map { it.first })
        assertTrue(delivered.single().second is LaunchMediaRecoveryCapacityExhaustedException)
        assertTrue(coordinator.isExhausted())
        assertFalse(coordinator.isRunning())
        assertEquals(0, coordinator.subscriberCount())
    }

    @Test
    fun `deadline snapshot still honors cancellation from an earlier callback`() {
        val tasks = ArrayDeque<Runnable>()
        val scheduler = TestDeadlineScheduler()
        val coordinator = coordinator<Int>(scheduler) { task -> tasks.addLast(task); true }
        val delivered = mutableListOf<String>()
        lateinit var laterSubscription: LaunchMediaRecoverySubscription

        coordinator.request(Any(), recover = { error("never returns") }) {
            delivered += "first"
            laterSubscription.cancel()
        }
        laterSubscription = coordinator.request(
            owner = Any(),
            recover = { error("must stay single-flight") },
        ) { delivered += "later" }

        scheduler.fire()

        assertEquals(listOf("first"), delivered)
        assertEquals(0, coordinator.subscriberCount())
        assertTrue(coordinator.isExhausted())
    }

    @Test
    fun `late recovery return is inert after deadline exhaustion`() {
        val tasks = ArrayDeque<Runnable>()
        val scheduler = TestDeadlineScheduler()
        val coordinator = coordinator<Int>(scheduler) { task -> tasks.addLast(task); true }
        val delivered = mutableListOf<Result<Int>>()
        var recoveries = 0

        coordinator.request(Any(), recover = { ++recoveries }) { delivered += it }
        scheduler.fire()
        val terminalFailure = delivered.single().exceptionOrNull()

        tasks.removeFirst().run()

        assertEquals(1, recoveries)
        assertEquals(1, delivered.size)
        assertTrue(delivered.single().exceptionOrNull() === terminalFailure)
        assertTrue(coordinator.isExhausted())
    }

    @Test
    fun `later requests reuse terminal capacity failure without retention or replacement workers`() {
        val tasks = ArrayDeque<Runnable>()
        val scheduler = TestDeadlineScheduler()
        val coordinator = coordinator<Int>(scheduler) { task -> tasks.addLast(task); true }
        val delivered = mutableListOf<Result<Int>>()

        coordinator.request(Any(), recover = { error("never returns") }) { delivered += it }
        scheduler.fire()
        val exhausted = delivered.single().exceptionOrNull()

        repeat(64) {
            coordinator.request(Any(), recover = { error("replacement worker forbidden") }) {
                delivered += it
            }
        }

        assertEquals(1, tasks.size)
        assertEquals(1, scheduler.count())
        assertEquals(65, delivered.size)
        assertTrue(delivered.all { it.exceptionOrNull() === exhausted })
        assertTrue(exhausted is LaunchMediaRecoveryCapacityExhaustedException)
        assertEquals(0, coordinator.subscriberCount())
        assertTrue(coordinator.isExhausted())
    }

    @Test
    fun `large pending set advances through bounded pages without failure budget`() {
        val pageSize = 64
        val totalRows = 10_000
        var nextRow = 0
        var largestPage = 0

        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 3) { cursor ->
            assertEquals(nextRow.toLong(), cursor.imagesAfterId)
            val rows = minOf(pageSize, totalRows - nextRow)
            largestPage = maxOf(largestPage, rows)
            nextRow += rows
            OrphanRecoveryBatch(
                report = RecoveryReport(scanned = rows),
                nextCursor = OrphanRecoveryCursor(imagesAfterId = nextRow.toLong()),
                hasMore = nextRow < totalRows,
            )
        }

        assertEquals(totalRows, completion.report.scanned)
        assertEquals(pageSize, largestPage)
        assertEquals(RecoveryRetryDecision.COMPLETE, completion.decision)
        assertTrue(completion.attempts > 3)
    }

    @Test
    fun `provider failures retry the same durable page and remain bounded`() {
        val cursors = mutableListOf<OrphanRecoveryCursor>()
        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 3) { cursor ->
            cursors += cursor
            OrphanRecoveryBatch(
                report = RecoveryReport().record(RecoveryEvent.QUERY_FAILED),
                nextCursor = cursor,
                hasMore = false,
            )
        }

        assertEquals(3, completion.attempts)
        assertEquals(List(3) { OrphanRecoveryCursor() }, cursors)
        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
    }

    @Test
    fun `failed discard page exhausts locally then advances to later durable entries`() {
        val cursors = mutableListOf<String?>()
        var laterPageRan = false
        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 3) { cursor ->
            cursors += cursor.discardAfterKey
            if (cursor.discardAfterKey == null) {
                OrphanRecoveryBatch(
                    report = RecoveryReport(scanned = 64).record(RecoveryEvent.DELETE_FAILED),
                    nextCursor = cursor.copy(discardAfterKey = "content://row/064"),
                    hasMore = true,
                    continueAfterFailureExhaustion = true,
                )
            } else {
                laterPageRan = true
                OrphanRecoveryBatch(
                    report = RecoveryReport(scanned = 12, deleted = 12),
                    nextCursor = cursor.copy(
                        discardAfterKey = "content://row/076",
                        discardComplete = true,
                    ),
                    hasMore = false,
                    continueAfterFailureExhaustion = true,
                )
            }
        }

        assertEquals(listOf(null, null, null, "content://row/064"), cursors)
        assertTrue(laterPageRan)
        assertEquals(4, completion.attempts)
        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
        assertTrue(RecoveryFailureClass.DELETE in completion.report.failureClasses)
        assertEquals(204, completion.report.scanned)
        assertEquals(12, completion.report.deleted)
    }

    @Test
    fun `durable discard row advances through media then cannot starve a later marker page`() {
        val cursors = mutableListOf<OrphanRecoveryCursor>()
        var laterMarkerRan = false
        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 2) { cursor ->
            cursors += cursor
            when {
                !cursor.preflightComplete -> OrphanRecoveryBatch(
                    report = RecoveryReport(),
                    nextCursor = cursor.copy(preflightComplete = true),
                    hasMore = true,
                )
                !cursor.mediaComplete -> {
                    // Models the exact generic-page row that previously called delete and exhausted
                    // the whole launch before the progressive journal stage could start.
                    assertEquals(
                        OrphanDisposition.KEEP_PENDING,
                        orphanDisposition(PendingJournalState.DISCARD, PendingProbe.INVALID),
                    )
                    OrphanRecoveryBatch(
                        report = RecoveryReport()
                            .record(RecoveryEvent.SCANNED)
                            .record(RecoveryEvent.RETAINED),
                        nextCursor = cursor.copy(
                            imagesAfterId = OrphanRecoveryCursor.COLLECTION_COMPLETE,
                            videoAfterId = OrphanRecoveryCursor.COLLECTION_COMPLETE,
                        ),
                        hasMore = true,
                    )
                }
                cursor.discardAfterKey == null -> OrphanRecoveryBatch(
                    report = RecoveryReport()
                        .record(RecoveryEvent.SCANNED)
                        .record(RecoveryEvent.DELETE_FAILED),
                    nextCursor = cursor.copy(discardAfterKey = "content://row/early"),
                    hasMore = true,
                    continueAfterFailureExhaustion = true,
                )
                else -> {
                    laterMarkerRan = true
                    OrphanRecoveryBatch(
                        report = RecoveryReport()
                            .record(RecoveryEvent.SCANNED)
                            .record(RecoveryEvent.DELETED),
                        nextCursor = cursor.copy(
                            discardAfterKey = "content://row/later",
                            discardComplete = true,
                        ),
                        hasMore = false,
                        continueAfterFailureExhaustion = true,
                    )
                }
            }
        }

        assertTrue(laterMarkerRan)
        assertEquals(5, completion.attempts)
        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
        assertEquals(setOf(RecoveryFailureClass.DELETE), completion.report.failureClasses)
        assertEquals(4, completion.report.scanned)
        assertEquals(1, completion.report.deleted)
        assertEquals(3, completion.report.retained)
        assertTrue(cursors[1].preflightComplete)
        assertFalse(cursors[1].mediaComplete)
        assertTrue(cursors[2].mediaComplete)
        assertEquals(cursors[2], cursors[3])
        assertEquals("content://row/early", cursors[4].discardAfterKey)
    }

    @Test
    fun `terminal failed discard page reports exhausted after its bounded retry budget`() {
        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 2) { cursor ->
            OrphanRecoveryBatch(
                report = RecoveryReport(scanned = 1).record(RecoveryEvent.DELETE_FAILED),
                nextCursor = cursor.copy(
                    discardAfterKey = "content://row/only",
                    discardComplete = true,
                ),
                hasMore = false,
                continueAfterFailureExhaustion = true,
            )
        }

        assertEquals(2, completion.attempts)
        assertEquals(2, completion.report.scanned)
        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
        assertEquals(setOf(RecoveryFailureClass.DELETE), completion.report.failureClasses)
    }

    private companion object {
        const val TEST_DEADLINE_MS = 1_000L
    }
}
