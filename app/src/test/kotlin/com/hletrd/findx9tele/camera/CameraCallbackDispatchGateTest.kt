package com.hletrd.findx9tele.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCallbackDispatchGateTest {

    @Test
    fun `late framework callback never touches queue after close begins`() {
        val gate = CameraCallbackDispatchGate()
        gate.beginClose { }
        var postInvoked = false

        assertFalse(gate.dispatch {
            postInvoked = true
            true
        })
        assertFalse(postInvoked)
    }

    @Test
    fun `close waits for an admitted post before quitting queue`() {
        val gate = CameraCallbackDispatchGate()
        val postEntered = CountDownLatch(1)
        val allowPostReturn = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closeRan = AtomicBoolean(false)
        val postWasReleased = AtomicBoolean(false)
        val dispatchAccepted = AtomicBoolean(false)

        val dispatcher = thread(name = "camera-callback-dispatch") {
            dispatchAccepted.set(gate.dispatch {
                postEntered.countDown()
                postWasReleased.set(allowPostReturn.await(2, TimeUnit.SECONDS))
                true
            })
        }
        assertTrue(postEntered.await(2, TimeUnit.SECONDS))
        val closer = thread(name = "camera-callback-close") {
            closeAttempted.countDown()
            gate.beginClose { closeRan.set(true) }
        }

        assertTrue(closeAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(closeRan.get())
        allowPostReturn.countDown()
        dispatcher.join(2_000)
        closer.join(2_000)
        assertFalse(dispatcher.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(postWasReleased.get())
        assertTrue(dispatchAccepted.get())
        assertTrue(closeRan.get())
    }

    @Test
    fun `queue exception rejects dispatch for inline fallback`() {
        val gate = CameraCallbackDispatchGate()
        assertFalse(gate.dispatch { error("dead handler") })
    }
}
