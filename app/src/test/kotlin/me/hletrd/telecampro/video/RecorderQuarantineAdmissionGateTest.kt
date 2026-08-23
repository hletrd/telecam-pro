package me.hletrd.telecampro.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderQuarantineAdmissionGateTest {

    private fun awaitQuarantine(gate: RecorderQuarantineAdmissionGate): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!gate.isQuarantined() && System.nanoTime() < deadline) Thread.yield()
        return gate.isQuarantined()
    }

    @Test
    fun `quarantine invalidates snapshots and rejects every later commit`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        var committed = false

        assertTrue(gate.close())

        assertFalse(gate.isCurrent(token))
        assertFalse(gate.commit(token) { committed = true })
        assertFalse(committed)
        assertTrue(gate.isQuarantined())
        assertFalse(gate.close())
        assertTrue(gate.snapshot(Any()) == null)
    }

    @Test
    fun `a current pending token commits exactly once`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        var commits = 0

        assertTrue(gate.commit(token) { commits++ })

        assertEquals(1, commits)
        // The commit is a guarded execution, not a consumption: the lease stays current.
        assertTrue(gate.isCurrent(token))
        assertTrue(gate.commit(token) { commits++ })
        assertEquals(2, commits)
    }

    @Test
    fun `publication and quarantine race has exactly one linearized winner`() {
        repeat(100) {
            val gate = RecorderQuarantineAdmissionGate()
            val token = checkNotNull(gate.snapshot(Any()))
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val publicationAccepted = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val publisher = Thread {
                ready.countDown()
                go.await()
                publicationAccepted.set(gate.publish(token) {
                    published.set(true)
                    true
                })
            }
            val closer = Thread {
                ready.countDown()
                go.await()
                closed.set(gate.close())
            }

            publisher.start()
            closer.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            publisher.join(5_000)
            closer.join(5_000)

            assertTrue(closed.get())
            assertTrue(publicationAccepted.get() == published.get())
            // Publication may linearize first, but it can never execute after the terminal close.
            if (!published.get()) assertFalse(gate.isCurrent(token))
            var lateCommit = false
            assertFalse(gate.commit(token) { lateCommit = true })
            assertFalse(lateCommit)
        }
    }

    @Test
    fun `pending and active recorder leases are process exclusive until abort or strict finish`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val first = checkNotNull(gate.snapshot(owner))
        assertTrue(gate.snapshot(owner) == null)

        gate.abandonPending(first)
        val second = checkNotNull(gate.snapshot(owner))
        var published = false
        assertTrue(gate.publish(second) {
            published = true
            true
        })
        assertTrue(published)
        assertTrue(gate.snapshot(owner) == null)

        gate.finish(second)
        assertTrue(gate.snapshot(owner) != null)
    }

    @Test
    fun `same owner may hand standby to REC while foreign owners stay excluded`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val foreignOwner = Any()
        val standby = checkNotNull(gate.reserveStandby(owner))

        assertTrue(gate.reserveStandby(foreignOwner) == null)
        assertTrue(gate.snapshot(foreignOwner) == null)
        val recording = checkNotNull(gate.snapshot(owner))
        assertTrue(gate.reserveStandby(owner) == null)

        gate.finishStandby(standby)
        assertTrue(gate.reserveStandby(foreignOwner) == null)
        assertTrue(gate.publish(recording) { true })
        assertTrue(gate.reserveStandby(foreignOwner) == null)

        gate.finish(recording)
        assertTrue(gate.reserveStandby(foreignOwner) != null)
    }

    @Test
    fun `terminal close drains a counted native lease without holding the process monitor`() {
        val gate = RecorderQuarantineAdmissionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquisitionAccepted = AtomicBoolean(false)
        val acquisitionDone = AtomicBoolean(false)
        val closeDone = AtomicBoolean(false)
        val acquirer = Thread {
            acquisitionAccepted.set(gate.runNativeIfSafe {
                entered.countDown()
                release.await()
                acquisitionDone.set(true)
            })
        }
        val closer = Thread {
            gate.close()
            closeDone.set(true)
        }

        acquirer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        closer.start()
        // The close boundary waits for the admitted block, but wait() releases the process monitor:
        // isQuarantined remains promptly observable and no ABBA-style lock hold blocks the worker.
        assertTrue(awaitQuarantine(gate))
        closer.join(50)
        assertFalse(closeDone.get())
        assertFalse(acquisitionDone.get())
        release.countDown()
        acquirer.join(5_000)
        closer.join(5_000)

        // The already-admitted call may finish, but the racing close revokes its result. Once close
        // returns, no native block remains and every later attempt is refused before entry.
        assertTrue(acquisitionDone.get())
        assertFalse(acquisitionAccepted.get())
        assertTrue(closeDone.get())
        var lateAcquisition = false
        assertFalse(gate.runNativeIfSafe { lateAcquisition = true })
        assertFalse(lateAcquisition)
        assertFalse(gate.commit(UnsafeRecorderAdmissionToken(1L, this)) { })
    }
}
