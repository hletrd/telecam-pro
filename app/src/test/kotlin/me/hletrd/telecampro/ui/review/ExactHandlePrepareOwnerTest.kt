package me.hletrd.telecampro.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactHandlePrepareOwnerTest {
    private class FakePlaybackHandle(private val name: String) {
        override fun toString(): String = name
    }

    private class ManualDeadlineScheduler {
        private data class Task(
            val timeoutMs: Long,
            val callback: () -> Unit,
            var canceled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()

        fun schedule(timeoutMs: Long, callback: () -> Unit): ReviewDeadlineRegistration {
            val task = Task(timeoutMs, callback)
            tasks += task
            return ReviewDeadlineRegistration { task.canceled = true }
        }

        fun fire(index: Int, includingCanceled: Boolean = false) {
            val task = tasks[index]
            if (!task.canceled || includingCanceled) task.callback()
        }

        fun timeoutAt(index: Int): Long = tasks[index].timeoutMs
    }

    @Test
    fun `prepared handle retires deadline and releases once on lifecycle close`() {
        val scheduler = ManualDeadlineScheduler()
        val handle = FakePlaybackHandle("success")
        val released = mutableListOf<FakePlaybackHandle>()
        var timedOut = false
        val owner = ExactHandlePrepareOwner(
            timeoutMs = 5_000L,
            schedule = scheduler::schedule,
            dispose = released::add,
        )

        owner.replace(handle)
        owner.arm(handle) { timedOut = true }

        assertEquals(5_000L, scheduler.timeoutAt(0))
        assertTrue(owner.prepared(handle))
        scheduler.fire(0, includingCanceled = true)
        assertFalse(timedOut)
        assertTrue(owner.owns(handle))

        assertTrue(owner.releaseCurrent())
        assertFalse(owner.releaseCurrent())
        assertEquals(listOf(handle), released)
    }

    @Test
    fun `emitted prepare error releases exact handle once and retires deadline`() {
        val scheduler = ManualDeadlineScheduler()
        val handle = FakePlaybackHandle("error")
        val released = mutableListOf<FakePlaybackHandle>()
        var timedOut = false
        val owner = ExactHandlePrepareOwner(
            timeoutMs = 100L,
            schedule = scheduler::schedule,
            dispose = released::add,
        )

        owner.replace(handle)
        owner.arm(handle) { timedOut = true }

        assertTrue(owner.release(handle))
        assertFalse(owner.release(handle))
        scheduler.fire(0, includingCanceled = true)
        assertFalse(timedOut)
        assertEquals(listOf(handle), released)
    }

    @Test
    fun `never-callback prepare timeout releases owner once and remains retryable`() {
        val scheduler = ManualDeadlineScheduler()
        val handle = FakePlaybackHandle("timeout")
        val released = mutableListOf<FakePlaybackHandle>()
        var timeoutCount = 0
        val owner = ExactHandlePrepareOwner(
            timeoutMs = 100L,
            schedule = scheduler::schedule,
            dispose = released::add,
        )

        owner.replace(handle)
        owner.arm(handle) { timeoutCount += 1 }

        scheduler.fire(0)
        scheduler.fire(0, includingCanceled = true)
        assertEquals(1, timeoutCount)
        assertEquals(listOf(handle), released)
        assertFalse(owner.owns(handle))

        val retry = FakePlaybackHandle("retry")
        owner.replace(retry)
        assertTrue(owner.owns(retry))
        assertTrue(owner.releaseCurrent())
        assertEquals(listOf(handle, retry), released)
    }

    @Test
    fun `Back before prepare timeout releases once and makes timer inert`() {
        val scheduler = ManualDeadlineScheduler()
        val handle = FakePlaybackHandle("back")
        val released = mutableListOf<FakePlaybackHandle>()
        var timeoutCount = 0
        val owner = ExactHandlePrepareOwner(
            timeoutMs = 100L,
            schedule = scheduler::schedule,
            dispose = released::add,
        )

        owner.replace(handle)
        owner.arm(handle) { timeoutCount += 1 }

        assertTrue(owner.releaseCurrent())
        scheduler.fire(0, includingCanceled = true)
        assertEquals(0, timeoutCount)
        assertEquals(listOf(handle), released)
        assertFalse(owner.releaseCurrent())
    }

    @Test
    fun `stale timeout after replacement cannot release or fail newer handle`() {
        val scheduler = ManualDeadlineScheduler()
        val old = FakePlaybackHandle("old")
        val replacement = FakePlaybackHandle("replacement")
        val released = mutableListOf<FakePlaybackHandle>()
        val timedOut = mutableListOf<String>()
        val owner = ExactHandlePrepareOwner(
            timeoutMs = 100L,
            schedule = scheduler::schedule,
            dispose = released::add,
        )

        owner.replace(old)
        owner.arm(old) { timedOut += "old" }
        owner.replace(replacement)
        owner.arm(replacement) { timedOut += "replacement" }

        scheduler.fire(0, includingCanceled = true)
        assertTrue(owner.owns(replacement))
        assertEquals(listOf(old), released)
        assertTrue(timedOut.isEmpty())

        scheduler.fire(1)
        assertEquals(listOf(old, replacement), released)
        assertEquals(listOf("replacement"), timedOut)
    }
}
