package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAcquisitionGateTest {

    @Test
    fun `quarantine rejects every native acquisition family`() {
        val attempted = mutableListOf<String>()
        val families = listOf("GL", "preview", "Camera2", "standby microphone")

        families.forEach { family ->
            if (nativeAcquisitionAllowed(acquisitionOpen = false, recorderQuarantined = true)) {
                attempted += family
            }
        }

        assertTrue(attempted.isEmpty())
        assertFalse(nativeAcquisitionAllowed(acquisitionOpen = true, recorderQuarantined = true))
        assertFalse(nativeAcquisitionAllowed(acquisitionOpen = false, recorderQuarantined = false))
        assertTrue(nativeAcquisitionAllowed(acquisitionOpen = true, recorderQuarantined = false))
    }

    @Test
    fun `closed gate rejects later acquisition`() {
        val gate = TerminalAcquisitionGate()
        val ran = AtomicBoolean(false)

        gate.close()

        assertFalse(gate.runIfOpen { ran.set(true) })
        assertFalse(ran.get())
        assertFalse(gate.isOpen())
    }

    @Test
    fun `close waits for in-flight acquisition`() {
        val gate = TerminalAcquisitionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquisitionDone = CountDownLatch(1)
        val closeDone = CountDownLatch(1)

        val acquisition = thread {
            gate.runIfOpen {
                entered.countDown()
                release.await(1, TimeUnit.SECONDS)
            }
            acquisitionDone.countDown()
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val closer = thread {
            gate.close()
            closeDone.countDown()
        }

        assertFalse(closeDone.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(acquisitionDone.await(1, TimeUnit.SECONDS))
        assertTrue(closeDone.await(1, TimeUnit.SECONDS))
        acquisition.join()
        closer.join()
        assertFalse(gate.isOpen())
    }

    /**
     * REGRESSION FENCE, device-diagnosed 2026-08-09.
     *
     * `isOpen` is the advisory half of the gate — its only caller is
     * [nativeAcquisitionMayProceed], which feeds the pure [nativeAcquisitionAllowed] predicate as a
     * "should I bother starting this" hint. It participates in no compound operation, and the
     * AUTHORITATIVE check is the one inside [TerminalAcquisitionGate.runIfOpen] under the monitor.
     *
     * It must therefore never block behind an in-flight acquisition. When it did, rapid Photo↔Video
     * churn on an SM-S918N stalled the MAIN THREAD for 192 ms — `Long monitor contention with owner
     * pool-5-thread-1 at TerminalAcquisitionGate.runIfOpen ... in isOpen()`, waiter tid == pid.
     * `runIfOpen` deliberately holds that monitor across a process lock plus a native camera open,
     * so the wait is bounded only by how long the HAL takes.
     *
     * Note this is the exact COMPLEMENT of `close waits for in-flight acquisition` above: close must
     * block (it is compound with the acquisition), isOpen must not (it is not). Both directions are
     * pinned so a future "simplification" that re-synchronizes isOpen, or that drops the monitor
     * around block(), fails here.
     */
    @Test
    fun `isOpen does not block behind an in-flight acquisition`() {
        val gate = TerminalAcquisitionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val acquisition = thread {
            gate.runIfOpen {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        // The acquisition is parked inside the block. A reader must get its answer immediately.
        val probe = CountDownLatch(1)
        val observed = AtomicBoolean(false)
        val reader = thread {
            observed.set(gate.isOpen())
            probe.countDown()
        }
        val answeredPromptly = probe.await(200, TimeUnit.MILLISECONDS)

        release.countDown()
        acquisition.join()
        reader.join()

        assertTrue("isOpen blocked behind the in-flight acquisition", answeredPromptly)
        assertTrue("gate was still open, so isOpen must say so", observed.get())
    }
}
