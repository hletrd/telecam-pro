package com.hletrd.findx9tele.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAcquisitionGateTest {

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
}
