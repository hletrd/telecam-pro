package me.hletrd.telecampro

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessAdmissionSignalTest {
    @Test
    fun `subscription receives current truth and change gated edges`() {
        val signal = ProcessAdmissionSignal(initial = true)
        val events = CopyOnWriteArrayList<Boolean>()
        val subscription = signal.subscribe(events::add)

        signal.publish(true)
        signal.publish(false)
        signal.publish(false)
        signal.publish(true)

        assertEquals(listOf(true, false, true), events.toList())
        subscription.close()
        signal.publish(false)
        assertEquals(listOf(true, false, true), events.toList())
    }

    @Test
    fun `close drains in flight callback and rejects every later publication`() {
        val signal = ProcessAdmissionSignal(initial = true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<Boolean>()
        val subscription = signal.subscribe { available ->
            events += available
            if (!available) {
                entered.countDown()
                release.await()
            }
        }
        val publisher = Thread { signal.publish(false) }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread {
            subscription.close()
            closeReturned.set(true)
        }.apply { start() }

        closer.join(50L)
        assertFalse(closeReturned.get())
        release.countDown()
        publisher.join(2_000L)
        closer.join(2_000L)
        assertTrue(closeReturned.get())

        signal.publish(true)
        assertEquals(listOf(true, false), events.toList())
        assertEquals(0, signal.subscriberCount())
    }

    @Test
    fun `throwing observer cannot suppress ownership state or sibling publication`() {
        val signal = ProcessAdmissionSignal(initial = true)
        val bad = signal.subscribe { if (!it) error("observer failure") }
        val events = CopyOnWriteArrayList<Boolean>()
        val good = signal.subscribe(events::add)

        signal.publish(false)

        assertFalse(signal.current())
        assertEquals(listOf(true, false), events.toList())
        bad.close()
        good.close()
    }
}
