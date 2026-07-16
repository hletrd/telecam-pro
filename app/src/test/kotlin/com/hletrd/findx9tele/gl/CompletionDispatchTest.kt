package com.hletrd.findx9tele.gl

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionDispatchTest {

    @Test
    fun `accepted post completes after task exactly once`() {
        lateinit var queued: Runnable
        val work = AtomicInteger()
        val completions = mutableListOf<Result<Unit>>()

        val accepted = dispatchWithResult(
            post = { queued = it; true },
            block = { work.incrementAndGet() },
            onComplete = { completions += it },
        )

        assertTrue(accepted)
        assertEquals(0, work.get())
        assertEquals(0, completions.size)
        queued.run()
        queued.run() // Defensive duplicate execution still cannot duplicate completion delivery.
        assertEquals(2, work.get())
        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
    }

    @Test
    fun `rejected post completes inline without running task`() {
        val work = AtomicInteger()
        val completions = mutableListOf<Result<Unit>>()

        val accepted = dispatchWithResult(
            post = { false },
            block = { work.incrementAndGet() },
            onComplete = { completions += it },
        )

        assertFalse(accepted)
        assertEquals(0, work.get())
        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
    }

    @Test
    fun `throwing post completes inline exactly once`() {
        val completions = mutableListOf<Result<Unit>>()

        val accepted = dispatchWithResult(
            post = { throw IllegalStateException("dead looper") },
            block = { error("must not run") },
            onComplete = { completions += it },
        )

        assertFalse(accepted)
        assertEquals(1, completions.size)
        assertEquals("dead looper", completions.single().exceptionOrNull()?.message)
    }

    @Test
    fun `task failure still completes once`() {
        lateinit var queued: Runnable
        val completions = mutableListOf<Result<Unit>>()
        dispatchWithResult(
            post = { queued = it; true },
            block = { throw IllegalArgumentException("EGL") },
            onComplete = { completions += it },
        )

        queued.run()
        assertEquals(1, completions.size)
        assertEquals("EGL", completions.single().exceptionOrNull()?.message)
    }
}
