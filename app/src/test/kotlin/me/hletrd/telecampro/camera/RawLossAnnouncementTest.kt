package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGG5-10: RAW loss is announced once per session shape, only where RAW was possible. */
class RawLossAnnouncementTest {

    @Test
    fun `structurally RAW-less routes stay silent`() {
        // FRONT / hi-res / 10-bit video: rawSelectable is false, the OSD and caption carry it.
        val result = rawLossAnnouncementAtReady(
            rawWanted = true,
            rawSelectable = rawSelectable(
                deviceSupportsRaw = true,
                rawInSession = false,
                videoMode = false,
                hiResSession = false,
                frontFacing = true,
            ),
            rawInSession = false,
            announcedShape = null,
            shape = "1|front",
        )
        assertFalse(result.announce)
    }

    @Test
    fun `a drop-RAW rung on a RAW-capable route is announced once per shape`() {
        val first = rawLossAnnouncementAtReady(
            rawWanted = true,
            rawSelectable = true,
            rawInSession = false,
            announcedShape = null,
            shape = "4|jpeg",
        )
        assertTrue(first.announce)
        val again = rawLossAnnouncementAtReady(
            rawWanted = true,
            rawSelectable = true,
            rawInSession = false,
            announcedShape = first.announcedShape,
            shape = "4|jpeg",
        )
        assertFalse("a fast commit/recovery of the same shape is not news", again.announce)
        val otherShape = rawLossAnnouncementAtReady(
            rawWanted = true,
            rawSelectable = true,
            rawInSession = false,
            announcedShape = again.announcedShape,
            shape = "2|jpeg",
        )
        assertTrue(otherShape.announce)
    }

    @Test
    fun `RAW returning or no DNG wish clears the latch`() {
        val restored = rawLossAnnouncementAtReady(
            rawWanted = true,
            rawSelectable = true,
            rawInSession = true,
            announcedShape = "4|jpeg",
            shape = "4|jpeg+raw",
        )
        assertFalse(restored.announce)
        assertEquals(null, restored.announcedShape)
        val lostAgain = rawLossAnnouncementAtReady(
            rawWanted = true,
            rawSelectable = true,
            rawInSession = false,
            announcedShape = restored.announcedShape,
            shape = "4|jpeg",
        )
        assertTrue(lostAgain.announce)
        assertFalse(
            rawLossAnnouncementAtReady(
                rawWanted = false,
                rawSelectable = true,
                rawInSession = false,
                announcedShape = null,
                shape = "4|jpeg",
            ).announce,
        )
    }

    @Test
    fun `RAW unavailable is a warning, not an assertive error`() {
        val status = CameraStatusMessage.RAW_UNAVAILABLE.status()
        assertEquals(CameraStatusSeverity.WARNING, status.severity)
        assertEquals(CameraStatusLivePriority.POLITE, status.livePriority)
    }
}
