package com.hletrd.findx9tele.camera

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedSnapshotBudgetTest {

    @Test
    fun `production budget retains one active and one queued single snapshot`() {
        val budget = ProcessedSnapshotBudget()
        val active = budget.tryAcquire()
        val queued = budget.tryAcquire()

        assertEquals(2, MAX_RETAINED_SINGLE_PROCESSED_SNAPSHOTS)
        assertNotNull(active)
        assertNotNull(queued)
        assertNull(budget.tryAcquire())

        assertTrue(active!!.release())
        assertNotNull(budget.tryAcquire())
    }

    @Test
    fun `lease release is exactly once and cannot over-credit the budget`() {
        val budget = ProcessedSnapshotBudget(capacity = 1)
        val lease = budget.tryAcquire()!!

        assertTrue(lease.release())
        assertFalse(lease.release())
        val replacement = budget.tryAcquire()
        assertNotNull(replacement)
        assertNull(budget.tryAcquire())
    }

    @Test
    fun `concurrent release returns one slot exactly once`() {
        val budget = ProcessedSnapshotBudget(capacity = 1)
        val lease = budget.tryAcquire()!!
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = List(2) { index ->
            thread(name = "snapshot-budget-release-$index") {
                ready.countDown()
                assertTrue(start.await(1, TimeUnit.SECONDS))
                results += lease.release()
            }
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(1_000) }

        assertEquals(listOf(false, true), results.sorted())
        assertNotNull(budget.tryAcquire())
        assertNull(budget.tryAcquire())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive capacity is rejected`() {
        ProcessedSnapshotBudget(capacity = 0)
    }
}
