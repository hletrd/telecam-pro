package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGG2-20: a Photo→Video flip on a surviving controller (FRONT) turns `zslStreamingActive` false
 * through `pinAutoFps`; the ring's held full-res Images must be released on exactly that edge.
 */
class ZslStreamingEdgeTest {
    @Test
    fun `only the true to false streaming edge flushes the ring`() {
        assertTrue(zslStreamingStopped(streamedBefore = true, streamedAfter = false))
        assertFalse(zslStreamingStopped(streamedBefore = true, streamedAfter = true))
        assertFalse(zslStreamingStopped(streamedBefore = false, streamedAfter = true))
        assertFalse(zslStreamingStopped(streamedBefore = false, streamedAfter = false))
    }
}
