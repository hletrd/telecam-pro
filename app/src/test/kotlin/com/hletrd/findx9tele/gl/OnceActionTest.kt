package com.hletrd.findx9tele.gl

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins OnceAction's exactly-once contract, including the sealed-throw consumption. */
class OnceActionTest {

    @Test
    fun `runs its callback exactly once across repeated calls`() {
        val runs = AtomicInteger(0)
        val once = OnceAction { runs.incrementAndGet() }

        assertTrue(once.run())
        assertFalse(once.run())
        assertFalse(once.run())
        assertEquals(1, runs.get())
    }

    @Test
    fun `a throwing callback is sealed and still consumes the once`() {
        val runs = AtomicInteger(0)
        val once = OnceAction {
            runs.incrementAndGet()
            throw IllegalStateException("listener failure")
        }

        // The failure is contained (a completion callback must never crash the GL thread) and the
        // action is still spent — a retry could double-deliver a resource-release notification.
        assertTrue(once.run())
        assertFalse(once.run())
        assertEquals(1, runs.get())
    }
}
