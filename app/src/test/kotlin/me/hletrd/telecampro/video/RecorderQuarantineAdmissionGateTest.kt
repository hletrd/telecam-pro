package me.hletrd.telecampro.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderQuarantineAdmissionGateTest {

    @Test
    fun `recorder local close revokes an entered return and rejects every later phase`() {
        val gate = RecorderNativeOperationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicReference<RecorderNativeOperationResult<Unit>>()
        val worker = Thread {
            returned.set(
                gate.run {
                    entered.countDown()
                    release.await()
                },
            )
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(gate.close())
        var laterPhase = false
        assertEquals(
            RecorderNativeOperationResult.Rejected,
            gate.run { laterPhase = true },
        )
        assertFalse(laterPhase)

        release.countDown()
        worker.join(5_000)
        val outcome = returned.get() as RecorderNativeOperationResult.Returned
        assertFalse(outcome.stillOpen)
        assertTrue(outcome.result.isSuccess)
        assertFalse(gate.close())
    }

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
    fun `terminal close converges while a native lease never returns`() {
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
        // Terminal ownership/admission converges without waiting for the native block whose hang is
        // exactly why quarantine exists.
        assertTrue(awaitQuarantine(gate))
        closer.join(1_000)
        assertTrue(closeDone.get())
        assertFalse(acquisitionDone.get())
        assertFalse(gate.awaitNativeAcquisitionsDrained(25, TimeUnit.MILLISECONDS))
        var lateAcquisition = false
        assertFalse(gate.runNativeIfSafe { lateAcquisition = true })
        assertFalse(lateAcquisition)
        assertFalse(gate.commit(UnsafeRecorderAdmissionToken(1L, this)) { })

        // A later native return remains revoked and merely makes bounded observation report drained.
        release.countDown()
        acquirer.join(5_000)
        assertTrue(acquisitionDone.get())
        assertFalse(acquisitionAccepted.get())
        assertTrue(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.SECONDS))
    }

    @Test
    fun `interrupted drain observation returns false and restores interrupt status`() {
        val gate = RecorderQuarantineAdmissionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            gate.runNativeIfSafe {
                entered.countDown()
                release.await()
            }
        }
        val waiting = CountDownLatch(1)
        val returned = AtomicBoolean(true)
        val interrupted = AtomicBoolean(false)
        val observer = Thread {
            waiting.countDown()
            returned.set(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.MINUTES))
            interrupted.set(Thread.currentThread().isInterrupted)
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        observer.start()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        observer.interrupt()
        observer.join(5_000)

        assertFalse(observer.isAlive)
        assertFalse(returned.get())
        assertTrue(interrupted.get())
        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun `pending native setup runs outside process lock and late return is revoked`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val worker = Thread {
            accepted.set(gate.runPendingNative(token) {
                entered.countDown()
                release.await()
            })
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // close() must not wait for the native setup that motivated quarantine.
        assertTrue(gate.close())
        assertFalse(gate.isCurrent(token))
        assertFalse(gate.publish(token) { true })
        assertFalse(gate.awaitNativeAcquisitionsDrained(25, TimeUnit.MILLISECONDS))

        release.countDown()
        worker.join(5_000)
        assertFalse(accepted.get())
        assertTrue(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.SECONDS))
    }

    @Test
    fun `stale token cannot enter pending native setup`() {
        val gate = RecorderQuarantineAdmissionGate()
        val current = checkNotNull(gate.snapshot(Any()))
        var entered = false

        assertFalse(
            gate.runPendingNative(UnsafeRecorderAdmissionToken(current.epoch + 1, Any())) {
                entered = true
            },
        )
        assertFalse(entered)
    }
}
