package me.hletrd.telecampro.camera

import me.hletrd.telecampro.storage.OrphanRecoveryBatch
import me.hletrd.telecampro.storage.OrphanRecoveryCursor
import me.hletrd.telecampro.storage.RecoveryReport
import me.hletrd.telecampro.storage.RecoveryEvent
import me.hletrd.telecampro.storage.RecoveryRetryDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchMediaRecoveryCoordinatorTest {
    @Test
    fun `dispatcher rejection retires the sole admission before throwing`() {
        val coordinator = LaunchMediaRecoveryCoordinator<Int> { false }
        val owner = Any()

        assertThrows(IllegalStateException::class.java) {
            coordinator.request(owner, recover = { 1 }) {}
        }

        assertFalse(coordinator.isRunning())
        assertEquals(0, coordinator.subscriberCount())
    }

    @Test
    fun `blocked recovery stays single flight across engine replacement`() {
        val tasks = ArrayDeque<Runnable>()
        val coordinator = LaunchMediaRecoveryCoordinator<Int> { task -> tasks.addLast(task); true }
        val delivered = mutableListOf<Int>()
        val oldOwners = List(64) { Any() }
        oldOwners.forEachIndexed { index, owner ->
            val subscription = coordinator.request(owner, recover = { 37 }) { delivered += it }
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
        val coordinator = LaunchMediaRecoveryCoordinator<Int> { task -> tasks.addLast(task); true }
        var recoveries = 0

        coordinator.request(Any(), recover = { ++recoveries }) {}
        tasks.removeFirst().run()
        coordinator.request(Any(), recover = { ++recoveries }) {}

        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(2, recoveries)
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
}
