package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StatusPublicationOwnershipTest {
    @Test
    fun `MR status and timer arm cannot interleave with an Engine event`() {
        val gate = CameraReadyPublicationGate()
        val recallPublished = CountDownLatch(1)
        val releaseRecall = CountDownLatch(1)
        val eventAttempted = CountDownLatch(1)
        val eventFinished = CountDownLatch(1)
        val visible = AtomicReference<String?>(null)
        val timerOwner = AtomicReference<String?>(null)

        val recall = thread(start = true) {
            gate.serializedStatus { owner ->
                visible.set("MR1 loaded")
                recallPublished.countDown()
                assertTrue(releaseRecall.await(2, TimeUnit.SECONDS))
                owner.requireHeld()
                timerOwner.set("MR1 loaded")
            }
        }
        assertTrue(recallPublished.await(2, TimeUnit.SECONDS))

        val event = thread(start = true) {
            eventAttempted.countDown()
            gate.serializedStatus { owner ->
                visible.set("Camera unavailable")
                owner.requireHeld()
                timerOwner.set("Camera unavailable")
            }
            eventFinished.countDown()
        }
        assertTrue(eventAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("Engine event entered between recalled state and timer arm", eventFinished.await(100, TimeUnit.MILLISECONDS))
        assertEquals("MR1 loaded", visible.get())
        assertEquals(null, timerOwner.get())

        releaseRecall.countDown()
        recall.join(2_000)
        event.join(2_000)
        assertFalse(recall.isAlive)
        assertFalse(event.isAlive)
        assertEquals("Camera unavailable", visible.get())
        assertEquals("Camera unavailable", timerOwner.get())
    }

    @Test
    fun `status timer permit rejects every arm outside the serialized gate`() {
        val gate = CameraReadyPublicationGate()
        lateinit var escaped: CameraReadyPublicationGate.StatusOwner

        gate.serializedStatus { owner ->
            owner.requireHeld()
            escaped = owner
        }

        assertThrows(IllegalStateException::class.java) { escaped.requireHeld() }
    }
}
