package me.hletrd.telecampro.ui

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestCaptureRestoreOwnerTest {

    private class QueuedExecutor {
        private val tasks = ArrayDeque<Runnable>()

        fun submit(task: Runnable): Boolean {
            tasks.addLast(task)
            return true
        }

        fun enqueue(task: () -> Unit) {
            tasks.addLast(Runnable(task))
        }

        fun runNext() = tasks.removeFirst().run()
        fun size(): Int = tasks.size
    }

    @Test
    fun `blocked query owns one submitted request and one conflated latest intent`() {
        val worker = QueuedExecutor()
        val completion = QueuedExecutor()
        var queries = 0
        val owner = LatestCaptureRestoreOwner<Int>(
            submit = worker::submit,
            postCompletion = completion::submit,
            query = { queries += 1; null },
            publish = { false },
        )

        owner.request()
        repeat(100) { owner.request() }

        assertEquals(1, worker.size())
        assertEquals(
            LatestCaptureRestoreSnapshot(1, 1, closed = false),
            owner.snapshot(),
        )

        worker.runNext()
        assertEquals(1, completion.size())
        completion.runNext()

        assertEquals(1, queries)
        assertEquals(1, worker.size())
        assertEquals(
            LatestCaptureRestoreSnapshot(1, 0, closed = false),
            owner.snapshot(),
        )

        worker.runNext()
        completion.runNext()
        assertEquals(2, queries)
        assertEquals(
            LatestCaptureRestoreSnapshot(0, 0, closed = false),
            owner.snapshot(),
        )
    }

    @Test
    fun `successful publication drops every request conflated behind it`() {
        val worker = QueuedExecutor()
        val completion = QueuedExecutor()
        var queries = 0
        val published = mutableListOf<Int>()
        val owner = LatestCaptureRestoreOwner(
            submit = worker::submit,
            postCompletion = completion::submit,
            query = { ++queries },
            publish = { value -> published += value; true },
        )

        owner.request()
        repeat(100) { owner.request() }
        worker.runNext()
        completion.runNext()

        assertEquals(1, queries)
        assertEquals(listOf(1), published)
        assertEquals(0, worker.size())
        assertEquals(
            LatestCaptureRestoreSnapshot(0, 0, closed = false),
            owner.snapshot(),
        )
    }

    @Test
    fun `failed query runs exactly one conflated follow-up`() {
        val worker = QueuedExecutor()
        val completion = QueuedExecutor()
        var queries = 0
        val published = mutableListOf<Int>()
        val owner = LatestCaptureRestoreOwner(
            submit = worker::submit,
            postCompletion = completion::submit,
            query = {
                queries += 1
                if (queries == 1) error("provider fault") else 73
            },
            publish = { value -> published += value; true },
        )

        owner.request()
        repeat(20) { owner.request() }
        worker.runNext()
        completion.runNext()
        worker.runNext()
        completion.runNext()

        assertEquals(2, queries)
        assertEquals(listOf(73), published)
        assertEquals(
            LatestCaptureRestoreSnapshot(0, 0, closed = false),
            owner.snapshot(),
        )
    }

    @Test
    fun `conflated follow-up stays behind user work accepted on shared serial lane`() {
        val worker = QueuedExecutor()
        val completion = QueuedExecutor()
        val order = mutableListOf<String>()
        var queries = 0
        val owner = LatestCaptureRestoreOwner<Int>(
            submit = worker::submit,
            postCompletion = completion::submit,
            query = { queries += 1; order += "query-$queries"; null },
            publish = { false },
        )

        owner.request()
        worker.enqueue { order += "user" }
        repeat(20) { owner.request() }

        worker.runNext()
        completion.runNext()
        assertEquals(2, worker.size())
        worker.runNext()
        worker.runNext()
        completion.runNext()

        assertEquals(listOf("query-1", "user", "query-2"), order)
    }

    @Test
    fun `submission and completion refusal never run provider or publication inline`() {
        var queries = 0
        var publications = 0
        val submitRefused = LatestCaptureRestoreOwner(
            submit = { false },
            postCompletion = { true },
            query = { ++queries },
            publish = { publications += 1; true },
        )

        submitRefused.request()
        assertEquals(0, queries)
        assertEquals(0, publications)
        assertEquals(
            LatestCaptureRestoreSnapshot(0, 0, closed = false),
            submitRefused.snapshot(),
        )

        val worker = QueuedExecutor()
        val completionRefused = LatestCaptureRestoreOwner(
            submit = worker::submit,
            postCompletion = { false },
            query = { ++queries },
            publish = { publications += 1; true },
        )
        completionRefused.request()
        worker.runNext()

        assertEquals(1, queries)
        assertEquals(0, publications)
        assertEquals(
            LatestCaptureRestoreSnapshot(0, 0, closed = false),
            completionRefused.snapshot(),
        )
    }

    @Test
    fun `close invalidates posted completion drops pending and refuses later requests`() {
        val worker = QueuedExecutor()
        val completion = QueuedExecutor()
        val published = mutableListOf<Int>()
        val owner = LatestCaptureRestoreOwner(
            submit = worker::submit,
            postCompletion = completion::submit,
            query = { 91 },
            publish = { value -> published += value; true },
        )

        owner.request()
        repeat(20) { owner.request() }
        worker.runNext()
        assertEquals(1, completion.size())

        owner.close()
        completion.runNext()
        owner.request()

        assertTrue(published.isEmpty())
        assertEquals(0, worker.size())
        assertEquals(
            LatestCaptureRestoreSnapshot(0, 0, closed = true),
            owner.snapshot(),
        )
    }
}
