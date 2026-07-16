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

    @Test
    fun `tele intent is complete before asynchronous preflight`() {
        val current = ManualControls(iso = 800, zoomRatio = 8f)

        val resolved = resolveLensOpticsIntent(
            mode = CaptureMode.PHOTO,
            currentLens = LensChoice.TELE3X,
            currentTeleconverter = false,
            currentControls = current,
            currentPreTeleUnifiedZoom = Float.NaN,
            requestedLens = LensChoice.TELE3X,
            requestedTeleconverter = true,
            restorePreTele = false,
        )

        assertEquals(LensChoice.TELE3X, resolved.lens)
        assertTrue(resolved.teleconverter)
        assertEquals(1f, resolved.controls.zoomRatio)
        assertEquals(8f, resolved.preTeleUnifiedZoom)
        assertEquals(800, resolved.controls.iso)
    }

    @Test
    fun `tele exit restores exact video framing as a complete packet`() {
        val resolved = resolveLensOpticsIntent(
            mode = CaptureMode.VIDEO,
            currentLens = LensChoice.TELE3X,
            currentTeleconverter = true,
            currentControls = ManualControls(zoomRatio = 2f),
            currentPreTeleUnifiedZoom = 12f,
            requestedLens = LensChoice.TELE3X,
            requestedTeleconverter = false,
            restorePreTele = true,
        )

        assertEquals(LensChoice.TELE10X, resolved.lens)
        assertFalse(resolved.teleconverter)
        assertEquals(1.2f, resolved.controls.zoomRatio, 0.0001f)
        assertTrue(resolved.preTeleUnifiedZoom.isNaN())
    }

    @Test
    fun `superseding generation rejects the older complete intent`() {
        val older = 41L
        val newer = 42L

        assertFalse(reconfigurationOwnsGeneration(newer, older))
        assertTrue(reconfigurationOwnsGeneration(newer, newer))
    }
}
