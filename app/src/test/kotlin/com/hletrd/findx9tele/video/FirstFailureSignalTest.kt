package com.hletrd.findx9tele.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFailureSignalTest {

    @Test
    fun `first cause wins and observer runs once`() {
        val signal = FirstFailureSignal()
        val first = IllegalStateException("video")
        val second = IllegalStateException("audio")
        val observed = mutableListOf<Throwable>()

        assertTrue(signal.record(first, observed::add))
        assertFalse(signal.record(second, observed::add))

        assertSame(first, signal.cause)
        assertEquals(listOf(first), observed)
    }

    @Test
    fun `observer exception is contained after cause is latched`() {
        val signal = FirstFailureSignal()
        val first = IllegalStateException("codec")

        assertTrue(signal.record(first) { throw AssertionError("owner callback") })
        assertSame(first, signal.cause)
        assertFalse(signal.record(IllegalStateException("later")) {})
    }

    @Test
    fun `racing reporters notify exactly once`() {
        val signal = FirstFailureSignal()
        val workers = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(16)
        val go = CountDownLatch(1)
        val done = CountDownLatch(16)
        val notifications = AtomicInteger()
        val observed = AtomicReference<Throwable>()

        repeat(16) { index ->
            workers.execute {
                val cause = IllegalStateException("failure-$index")
                ready.countDown()
                go.await()
                signal.record(cause) {
                    observed.set(it)
                    notifications.incrementAndGet()
                }
                done.countDown()
            }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        go.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        workers.shutdownNow()

        assertEquals(1, notifications.get())
        assertSame(signal.cause, observed.get())
    }

    @Test
    fun `racing audio and video failures retain exactly one terminal cause`() {
        val signal = FirstFailureSignal()
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        val notifications = AtomicInteger()
        val audioFailure = IllegalStateException("AudioRecord.read failed: ERROR_DEAD_OBJECT (-6)")
        val videoFailure = IllegalStateException("video codec failed")

        listOf(audioFailure, videoFailure).forEach { failure ->
            Thread {
                ready.countDown()
                go.await()
                signal.record(failure) { notifications.incrementAndGet() }
                done.countDown()
            }.start()
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        go.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))

        assertEquals(1, notifications.get())
        assertTrue(signal.cause === audioFailure || signal.cause === videoFailure)
    }
}
