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
        val completions = AtomicInteger()

        val accepted = postWithCompletion(
            post = { queued = it; true },
            block = { work.incrementAndGet() },
            onComplete = { completions.incrementAndGet() },
        )

        assertTrue(accepted)
        assertEquals(0, work.get())
        assertEquals(0, completions.get())
        queued.run()
        queued.run() // Defensive duplicate execution still cannot duplicate completion delivery.
        assertEquals(2, work.get())
        assertEquals(1, completions.get())
    }

    @Test
    fun `rejected post completes inline without running task`() {
        val work = AtomicInteger()
        val completions = AtomicInteger()

        val accepted = postWithCompletion(
            post = { false },
            block = { work.incrementAndGet() },
            onComplete = { completions.incrementAndGet() },
        )

        assertFalse(accepted)
        assertEquals(0, work.get())
        assertEquals(1, completions.get())
    }

    @Test
    fun `throwing post completes inline exactly once`() {
        val completions = AtomicInteger()

        val accepted = postWithCompletion(
            post = { throw IllegalStateException("dead looper") },
            block = { error("must not run") },
            onComplete = { completions.incrementAndGet() },
        )

        assertFalse(accepted)
        assertEquals(1, completions.get())
    }

    @Test
    fun `task failure still completes once`() {
        lateinit var queued: Runnable
        val completions = AtomicInteger()
        postWithCompletion(
            post = { queued = it; true },
            block = { throw IllegalArgumentException("EGL") },
            onComplete = { completions.incrementAndGet() },
        )

        runCatching { queued.run() }
        assertEquals(1, completions.get())
    }
}
