package me.hletrd.telecampro.gl

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameNotificationCoalescerTest {
    @Test
    fun `queued producer callbacks collapse to one latest-frame draw`() {
        val queue = ArrayDeque<Runnable>()
        var draws = 0
        val owner = FrameNotificationCoalescer(
            post = { queue.addLast(it); true },
            drawLatestFrame = { draws++ },
        )

        repeat(20) { owner.onFrameAvailable() }

        assertEquals(1, queue.size)
        queue.removeFirst().run()
        assertEquals(1, draws)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `notification during draw rearms exactly one follow-up`() {
        val queue = ArrayDeque<Runnable>()
        lateinit var owner: FrameNotificationCoalescer
        var draws = 0
        owner = FrameNotificationCoalescer(
            post = { queue.addLast(it); true },
            drawLatestFrame = {
                draws++
                if (draws == 1) repeat(8) { owner.onFrameAvailable() }
            },
        )

        owner.onFrameAvailable()
        queue.removeFirst().run()

        assertEquals(1, draws)
        assertEquals(1, queue.size)
        queue.removeFirst().run()
        assertEquals(2, draws)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `notification after completed draw arms a new real-frame draw`() {
        val queue = ArrayDeque<Runnable>()
        var draws = 0
        val owner = FrameNotificationCoalescer(
            post = { queue.addLast(it); true },
            drawLatestFrame = { draws++ },
        )

        owner.onFrameAvailable()
        queue.removeFirst().run()
        owner.onFrameAvailable()
        queue.removeFirst().run()

        assertEquals(2, draws)
    }

    @Test
    fun `cancel makes queued and future notifications inert`() {
        val queue = ArrayDeque<Runnable>()
        var draws = 0
        val owner = FrameNotificationCoalescer(
            post = { queue.addLast(it); true },
            drawLatestFrame = { draws++ },
        )

        owner.onFrameAvailable()
        owner.cancel()
        queue.removeFirst().run()
        owner.onFrameAvailable()

        assertEquals(0, draws)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `rejected scheduling retains no false scheduled owner`() {
        var accept = false
        val queue = ArrayDeque<Runnable>()
        val owner = FrameNotificationCoalescer(
            post = {
                if (accept) queue.addLast(it)
                accept
            },
            drawLatestFrame = {},
        )

        owner.onFrameAvailable()
        assertTrue(queue.isEmpty())
        accept = true
        owner.onFrameAvailable()

        assertFalse(queue.isEmpty())
    }
}
