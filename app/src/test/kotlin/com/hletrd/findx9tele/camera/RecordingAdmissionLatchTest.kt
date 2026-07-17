package com.hletrd.findx9tele.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the REC stop-during-start ordering the async admission move (fb72b14) introduced: a stop
 * arriving while admission is in flight is latched exactly-once and executed only after a
 * SUCCESSFUL admission publishes — a failed admission has nothing to stop.
 */
class RecordingAdmissionLatchTest {

    @Test
    fun `second admission is refused while one is in flight`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        assertFalse(latch.tryBeginAdmission())
    }

    @Test
    fun `admission slot frees after completion`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        latch.completeAdmission(succeeded = true)
        assertTrue(latch.tryBeginAdmission())
    }

    @Test
    fun `stop with no admission in flight is not latched`() {
        val latch = RecordingAdmissionLatch()
        assertFalse(latch.requestStop())
    }

    @Test
    fun `stop during successful admission runs after publication`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        assertTrue(latch.requestStop())
        assertTrue(latch.completeAdmission(succeeded = true))
    }

    @Test
    fun `stop during failed admission is discarded - nothing to stop`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        assertTrue(latch.requestStop())
        assertFalse(latch.completeAdmission(succeeded = false))
    }

    @Test
    fun `latch is consumed exactly once`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        assertTrue(latch.requestStop())
        assertTrue(latch.completeAdmission(succeeded = true))
        // A follow-up admission must not inherit the consumed stop.
        assertTrue(latch.tryBeginAdmission())
        assertFalse(latch.completeAdmission(succeeded = true))
    }

    @Test
    fun `executor rejection abandonment frees the slot without a phantom stop`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        assertFalse(latch.completeAdmission(succeeded = false))
        assertTrue(latch.tryBeginAdmission())
    }

    @Test
    fun `racing stop against completion never loses or doubles the stop`() {
        // A stop that wins the monitor before completion must be reported by completeAdmission;
        // one that loses must be told to stop the published recorder itself (requestStop false).
        repeat(100) {
            val latch = RecordingAdmissionLatch()
            assertTrue(latch.tryBeginAdmission())
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            var latchedStop = false
            var stopLatched = false
            val completer = Thread {
                ready.countDown()
                go.await()
                latchedStop = latch.completeAdmission(succeeded = true)
            }
            val stopper = Thread {
                ready.countDown()
                go.await()
                stopLatched = latch.requestStop()
            }
            completer.start()
            stopper.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            completer.join(5000)
            stopper.join(5000)
            // Exactly one path owns the stop. Latched (stop won the monitor): the completion must
            // report it (latched-but-unreported would LOSE the stop). Not latched (completion won):
            // the stopper stops the published recorder directly and the completion must not also
            // report one (a phantom double stop). Either way the two observations agree.
            assertEquals(stopLatched, latchedStop)
        }
    }
}
