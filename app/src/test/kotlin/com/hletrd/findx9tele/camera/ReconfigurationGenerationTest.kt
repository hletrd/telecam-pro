package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconfigurationGenerationTest {
    @Test
    fun `current transaction owns rollback`() {
        assertTrue(reconfigurationOwnsGeneration(currentGeneration = 7, expectedGeneration = 7))
    }

    @Test
    fun `superseded transaction cannot roll back newer intent`() {
        assertFalse(reconfigurationOwnsGeneration(currentGeneration = 8, expectedGeneration = 7))
    }

    @Test
    fun `current rollback restores the complete accepted optics snapshot`() {
        val snapshot = OpticsIntentState(
            mode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(zoomRatio = 2.5f),
            overrideId = "4",
        )

        assertEquals(snapshot, rollbackOpticsState(7, 7, snapshot))
    }

    @Test
    fun `superseded rollback returns no snapshot`() {
        val snapshot = OpticsIntentState(
            CaptureMode.PHOTO,
            LensChoice.MAIN,
            false,
            ManualControls(),
            null,
        )

        assertNull(rollbackOpticsState(8, 7, snapshot))
    }

    @Test
    fun `rapid second intent keeps the last ready baseline`() {
        val accepted = OpticsIntentState(CaptureMode.PHOTO, LensChoice.MAIN, false, ManualControls(), null)
        val inFlight = accepted.copy(mode = CaptureMode.VIDEO, lens = LensChoice.TELE3X)

        assertEquals(
            accepted,
            selectRollbackBaseline(cameraReady = false, current = inFlight, pendingBaseline = accepted),
        )
    }

    @Test
    fun `ready intent snapshots current optics`() {
        val current = OpticsIntentState(CaptureMode.VIDEO, LensChoice.TELE3X, false, ManualControls(), "4")
        val stale = current.copy(mode = CaptureMode.PHOTO, overrideId = null)

        assertEquals(
            current,
            selectRollbackBaseline(cameraReady = true, current = current, pendingBaseline = stale),
        )
    }
}
