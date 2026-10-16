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

        // AGG5-64 (TE5-14): prove the closer is actually parked on the in-flight callback's monitor
        // instead of inferring it from a 50 ms join, which passed vacuously on a slow scheduler.
        assertTrue("close must block behind the in-flight callback", awaitBlocked(closer))
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

    @Test
    fun `refresh reads live state inside the monitor so a racing edge cannot leave it stale`() {
        // AGG4-35 / PERF4-3 interleaving: capacity exhausted (false). T1 releases (live true) and is
        // preempted between its read and its publish; T2 reserves (live false) and publishes. With
        // read-outside-the-lock, T1's stale `true` then flipped the signal over a live `false`, and
        // T3's real `true` was swallowed by the change gate.
        val signal = ProcessAdmissionSignal(initial = false)
        val events = CopyOnWriteArrayList<Boolean>()
        val subscription = signal.subscribe(events::add)
        val live = AtomicBoolean(false)
        val t1Read = CountDownLatch(1)
        val t1Resume = CountDownLatch(1)
        val t2Reads = java.util.concurrent.atomic.AtomicInteger()

        live.set(true) // T1's release
        val t1 = Thread {
            signal.refresh {
                val value = live.get()
                t1Read.countDown()
                check(t1Resume.await(5, TimeUnit.SECONDS))
                value
            }
        }.apply { start() }
        assertTrue(t1Read.await(5, TimeUnit.SECONDS))
        live.set(false) // T2's reserve
        val t2AboutToRefresh = CountDownLatch(1)
        val t2 = Thread {
            t2AboutToRefresh.countDown()
            signal.refresh {
                t2Reads.incrementAndGet()
                live.get()
            }
        }.apply { start() }
        // T2's read cannot begin while T1 holds the read-and-publish monitor. AGG5-64 (TE5-14): a
        // fixed sleep let an UNLOCKED refresh pass whenever T2 was simply not scheduled yet; T2 must
        // now be observed parked on the monitor before the negative is asserted.
        assertTrue(t2AboutToRefresh.await(5, TimeUnit.SECONDS))
        assertTrue("T2 must block on the read-and-publish monitor", awaitBlocked(t2))
        assertEquals(0, t2Reads.get())
        t1Resume.countDown()
        t1.join(5_000)
        t2.join(5_000)
        assertEquals(1, t2Reads.get())
        // The last publication carries the last read: the signal agrees with live capacity.
        assertFalse(signal.current())
        assertEquals(listOf(false, true, false), events.toList())

        // T3 releases: the change gate delivers it instead of swallowing it.
        live.set(true)
        signal.refresh(live::get)
        assertTrue(signal.current())
        assertEquals(listOf(false, true, false, true), events.toList())
        subscription.close()
    }

    /** Bounded poll for [thread] parking on a monitor; false if it never does within 5 s. */
    private fun awaitBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) return true
            if (!thread.isAlive) return false
            Thread.onSpinWait()
        }
        return false
    }
}
