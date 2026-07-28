package me.hletrd.telecampro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera-switch dip's trigger against the two ways it can go wrong: dimming the fast path
 * (a strict regression on the most-used control in the app) and outliving a reconfiguration that
 * never publishes again (a permanently black viewfinder).
 *
 * Generations here are the engine's real shapes: a same-route fast commit publishes Not-Ready and
 * then Ready on ONE session generation, while a reopen bumps it at `invalidateCameraReady` and
 * again at the controller install / dual-open candidate.
 */
class SwitchCoverPolicyTest {

    @Test
    fun `a same-generation not-ready is the fast path and never dims`() {
        // Every optics door — including the photo lens presets, which only move the logical
        // camera's zoom — publishes Not-Ready first. Only the generation separates them.
        assertFalse(switchCoverRaises(ready = false, sessionGeneration = 7, lastSessionGeneration = 7))

        val start = SwitchCoverState(sessionGeneration = 7)
        val notReady = start.onPublication(ready = false, sessionGeneration = 7, opticsGeneration = 3)
        assertFalse(notReady.covered)
        assertEquals(0L, notReady.epoch)

        val ready = notReady.onPublication(ready = true, sessionGeneration = 7, opticsGeneration = 3)
        assertFalse(ready.covered)
        assertEquals(0L, ready.epoch)
    }

    @Test
    fun `a session-generation change raises the cover and Ready releases it`() {
        assertTrue(switchCoverRaises(ready = false, sessionGeneration = 8, lastSessionGeneration = 7))

        val start = SwitchCoverState(sessionGeneration = 7)
        val invalidated = start.onPublication(ready = false, sessionGeneration = 8, opticsGeneration = 4)
        assertTrue(invalidated.covered)
        assertEquals(1L, invalidated.epoch)

        val accepted = invalidated.onPublication(ready = true, sessionGeneration = 9, opticsGeneration = 4)
        assertFalse(accepted.covered)
        assertEquals(9L, accepted.sessionGeneration)
    }

    @Test
    fun `the repeated not-ready publications of one reopen do not restart the cover`() {
        // invalidateCameraReady, then the controller-install / dual-open candidate, then whatever a
        // fault-recovery reopen adds: all of it is one cover, so the epoch (which keys the fade and
        // the release deadline) must not move again.
        val raised = SwitchCoverState(sessionGeneration = 7)
            .onPublication(ready = false, sessionGeneration = 8, opticsGeneration = 4)
        val second = raised.onPublication(ready = false, sessionGeneration = 9, opticsGeneration = 4)
        val third = second.onPublication(ready = false, sessionGeneration = 10, opticsGeneration = 4)

        assertTrue(third.covered)
        assertEquals(raised.epoch, second.epoch)
        assertEquals(raised.epoch, third.epoch)
        assertEquals(10L, third.sessionGeneration)
    }

    @Test
    fun `the release deadline bounds a cover that no publication ever ends`() {
        // rollbackOptics' Not-Ready, recovery exhausting its budget, and a door returning early on
        // `paused` all leave the fold here with nothing further arriving.
        val stranded = SwitchCoverState(sessionGeneration = 7)
            .onPublication(ready = false, sessionGeneration = 8, opticsGeneration = 4)
            .onPublication(ready = false, sessionGeneration = 9, opticsGeneration = 4)
        assertTrue(stranded.covered)

        val released = stranded.onReleaseDeadline(stranded.epoch)
        assertFalse(released.covered)
        // The last-seen generation survives, so the NEXT genuine reopen still raises a fresh cover
        // with its own epoch (and therefore its own deadline).
        assertEquals(9L, released.sessionGeneration)
        val next = released.onPublication(ready = false, sessionGeneration = 10, opticsGeneration = 5)
        assertTrue(next.covered)
        assertEquals(stranded.epoch + 1, next.epoch)
    }

    @Test
    fun `a deadline for a superseded epoch cannot release a newer cover`() {
        val first = SwitchCoverState(sessionGeneration = 7)
            .onPublication(ready = false, sessionGeneration = 8, opticsGeneration = 4)
        val settled = first.onPublication(ready = true, sessionGeneration = 8, opticsGeneration = 4)
        val second = settled.onPublication(ready = false, sessionGeneration = 9, opticsGeneration = 5)
        assertEquals(first.epoch + 1, second.epoch)

        assertTrue(second.onReleaseDeadline(first.epoch).covered)
        assertFalse(second.onReleaseDeadline(second.epoch).covered)
    }

    @Test
    fun `a rollback releases the cover and its own trailing not-ready cannot re-raise it`() {
        // "Camera unchanged": the failed door never closed the outgoing session, so live picture is
        // still arriving and a cover held until the deadline would black out a working viewfinder.
        // rollbackOptics then publishes Not-Ready on a FRESH session generation, tagged with the
        // same optics generation it just rolled back.
        val raised = SwitchCoverState(sessionGeneration = 7)
            .onPublication(ready = false, sessionGeneration = 8, opticsGeneration = 4)
        assertTrue(raised.covered)

        val rolledBack = raised.onOpticsRollback(opticsGeneration = 4)
        assertFalse(rolledBack.covered)

        val trailing = rolledBack.onPublication(ready = false, sessionGeneration = 9, opticsGeneration = 4)
        assertFalse(trailing.covered)
        assertEquals(9L, trailing.sessionGeneration)
    }

    @Test
    fun `a new intent after a rollback still dims`() {
        val afterRollback = SwitchCoverState(sessionGeneration = 7)
            .onPublication(ready = false, sessionGeneration = 8, opticsGeneration = 4)
            .onOpticsRollback(opticsGeneration = 4)
            .onPublication(ready = false, sessionGeneration = 9, opticsGeneration = 4)

        val retried = afterRollback.onPublication(ready = false, sessionGeneration = 10, opticsGeneration = 5)
        assertTrue(retried.covered)
    }

    @Test
    fun `a preview-health not-ready on the accepted session never dims`() {
        // markPreviewPending and handlePreviewFailure republish Not-Ready with the ACCEPTED
        // session's generation: an EGL re-bind is not a camera switch and blanks nothing today.
        val ready = SwitchCoverState(sessionGeneration = 7)
            .onPublication(ready = true, sessionGeneration = 7, opticsGeneration = 4)
        val pending = ready.onPublication(ready = false, sessionGeneration = 7, opticsGeneration = 4)
        assertFalse(pending.covered)
        val rebound = pending.onPublication(ready = true, sessionGeneration = 7, opticsGeneration = 4)
        assertFalse(rebound.covered)
        assertEquals(0L, rebound.epoch)
    }
}
