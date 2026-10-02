package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGG6-22: onError after onPhoto must not settle a shot whose processed save is still queued. */
class StillSaveLanesTest {
    private var settled = 0
    private var leasesReleased = 0

    private fun lanes(processed: Boolean = true, dng: Boolean = false) = StillSaveLanes(
        wantsProcessed = processed,
        wantsDng = dng,
        onProcessedFinished = { leasesReleased++ },
        settle = { settled++ },
    )

    @Test
    fun `an error after onPhoto handed off the processed save does not own the lanes`() {
        val lanes = lanes()
        lanes.enterPhoto()
        // onPhoto's finally: the processed lane was queued, so it does not finish it.
        lanes.finishSequence()

        assertFalse("onError must leave the queued save's lane alone", lanes.claimErrorTerminal())
        assertEquals(0, settled)
        assertEquals(0, leasesReleased)

        lanes.finishProcessed() // the io-thread save's own finally
        assertEquals(1, settled)
        assertEquals(1, leasesReleased)
    }

    @Test
    fun `an error before onPhoto owns and settles every lane exactly once`() {
        val lanes = lanes(dng = true)

        assertTrue(lanes.claimErrorTerminal())
        lanes.finishProcessed()
        lanes.finishDng()
        lanes.finishSequence()
        lanes.finishProcessed()
        lanes.finishDng()

        assertEquals(1, settled)
        assertEquals(1, leasesReleased)
    }

    @Test
    fun `a shot settles only after its last wanted lane`() {
        val lanes = lanes(dng = true)
        lanes.enterPhoto()
        lanes.finishDng()
        lanes.finishSequence()
        assertEquals(0, settled)
        lanes.finishProcessed()
        assertEquals(1, settled)
    }
}
