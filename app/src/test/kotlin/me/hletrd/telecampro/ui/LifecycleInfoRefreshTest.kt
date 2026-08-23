package me.hletrd.telecampro.ui

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleInfoRefreshTest {

    private class QueuedWorker {
        private val tasks = ArrayDeque<Runnable>()

        fun submit(task: Runnable): Boolean {
            tasks.addLast(task)
            return true
        }

        fun enqueueUser(task: () -> Unit) {
            tasks.addLast(Runnable(task))
        }

        fun runNext() = tasks.removeFirst().run()
        fun size(): Int = tasks.size
    }

    @Test
    fun `sample carries the exact battery and storage values`() {
        val sample = LifecycleInfoSample(batteryPct = 73, freeBytes = 8_589_934_592L)

        assertEquals(73, sample.batteryPct)
        assertEquals(8_589_934_592L, sample.freeBytes)
    }

    @Test
    fun `executor refusal retires the request without sampling or delivery`() {
        var samples = 0
        val delivered = mutableListOf<Int>()
        val refresh = LifecycleInfoRefresh(
            submit = { false },
            sample = { ++samples },
            deliver = { _, value -> delivered += value },
        )

        val generation = refresh.start()
        refresh.request()

        assertTrue(refresh.isActive(generation))
        assertEquals(0, samples)
        assertTrue(delivered.isEmpty())
        assertEquals(
            LifecycleInfoRefreshSnapshot(generation, inFlightRequests = 0, pendingRequests = 0),
            refresh.snapshot(),
        )
    }

    @Test
    fun `blocked worker owns one submitted sample and one coalesced lifecycle intent`() {
        val worker = QueuedWorker()
        val delivered = mutableListOf<Pair<Long, Int>>()
        var samples = 0
        val refresh = LifecycleInfoRefresh(
            submit = worker::submit,
            sample = { ++samples },
            deliver = { generation, value -> delivered += generation to value },
        )

        val oldGeneration = refresh.start()
        refresh.request()
        repeat(100) { refresh.request() }

        assertEquals(1, worker.size())
        assertEquals(
            LifecycleInfoRefreshSnapshot(oldGeneration, inFlightRequests = 1, pendingRequests = 1),
            refresh.snapshot(),
        )

        refresh.stop()
        val currentGeneration = refresh.start()
        repeat(100) { refresh.request() }

        // Stop discarded the old pending intent. Start/ticker churn coalesced into exactly one
        // current-generation intent behind the still-blocked old worker.
        assertEquals(1, worker.size())
        assertEquals(
            LifecycleInfoRefreshSnapshot(currentGeneration, inFlightRequests = 1, pendingRequests = 1),
            refresh.snapshot(),
        )

        worker.runNext()

        assertTrue("the stopped generation must not publish", delivered.isEmpty())
        assertEquals(1, samples)
        assertEquals(1, worker.size())
        assertEquals(
            LifecycleInfoRefreshSnapshot(currentGeneration, inFlightRequests = 1, pendingRequests = 0),
            refresh.snapshot(),
        )

        worker.runNext()

        assertEquals(listOf(currentGeneration to 2), delivered)
        assertEquals(
            LifecycleInfoRefreshSnapshot(currentGeneration, inFlightRequests = 0, pendingRequests = 0),
            refresh.snapshot(),
        )
    }

    @Test
    fun `coalesced follow-up stays behind user work already accepted on the serial lane`() {
        val worker = QueuedWorker()
        val order = mutableListOf<String>()
        var sample = 0
        val refresh = LifecycleInfoRefresh(
            submit = worker::submit,
            sample = { ++sample },
            deliver = { _, value -> order += "deliver-$value" },
        )

        worker.enqueueUser { order += "user-before" }
        refresh.start()
        refresh.request()
        worker.enqueueUser { order += "user-after" }
        repeat(20) { refresh.request() }

        assertEquals(3, worker.size())
        worker.runNext()
        worker.runNext()
        // Completing the first sample appends its one coalesced successor at the executor tail;
        // it cannot jump ahead of the delete/restore task accepted while telemetry was pending.
        assertEquals(2, worker.size())
        worker.runNext()
        worker.runNext()

        assertEquals(
            listOf("user-before", "deliver-1", "user-after", "deliver-2"),
            order,
        )
    }

    @Test
    fun `main publication recheck rejects a generation stopped after worker delivery`() {
        val worker = QueuedWorker()
        val mainPosts = mutableListOf<() -> Unit>()
        val published = mutableListOf<Pair<Long, Int>>()
        lateinit var refresh: LifecycleInfoRefresh<Int>
        refresh = LifecycleInfoRefresh(
            submit = worker::submit,
            sample = { 73 },
            // Mirrors CameraViewModel's worker-to-main post: publication executes later and asks
            // the gate again rather than trusting the worker-time decision.
            deliver = { generation, value ->
                mainPosts += {
                    if (refresh.isActive(generation)) published += generation to value
                }
            },
        )

        val generation = refresh.start()
        refresh.request()
        worker.runNext()
        assertEquals(1, mainPosts.size)
        assertTrue(published.isEmpty())

        refresh.stop()
        mainPosts.single().invoke()

        assertFalse(refresh.isActive(generation))
        assertTrue(published.isEmpty())
    }
}
