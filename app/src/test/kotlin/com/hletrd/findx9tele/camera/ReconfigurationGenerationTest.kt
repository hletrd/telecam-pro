package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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

    @Test
    fun `queued ready publication is rejected after supersession or pause`() {
        assertTrue(cameraReadyPublicationIsCurrent(42L, 42L, cameraReady = true))
        assertFalse(cameraReadyPublicationIsCurrent(43L, 42L, cameraReady = true))
        assertFalse(cameraReadyPublicationIsCurrent(42L, 42L, cameraReady = false))
    }

    @Test
    fun `terminal commit rejects an older generation after its early check`() {
        val gate = OpticsCommitGate()
        val older = gate.begin { it }
        val earlyCheckComplete = CountDownLatch(1)
        val releaseOldCompletion = CountDownLatch(1)
        val attempted = AtomicBoolean(false)
        val result = AtomicReference<Long?>()
        val mutated = AtomicBoolean(false)

        val oldCompletion = thread(start = true, name = "old-optics-completion") {
            assertTrue(reconfigurationOwnsGeneration(older, older))
            earlyCheckComplete.countDown()
            assertTrue(releaseOldCompletion.await(1, TimeUnit.SECONDS))
            attempted.set(true)
            result.set(gate.commit(older) { mutated.set(true) })
        }

        assertTrue(earlyCheckComplete.await(1, TimeUnit.SECONDS))
        val newer = gate.begin { it }
        releaseOldCompletion.countDown()
        oldCompletion.join(1_000)

        assertFalse(oldCompletion.isAlive)
        assertTrue(attempted.get())
        assertTrue(newer > older)
        assertNull(result.get())
        assertFalse(mutated.get())
    }

    @Test
    fun `terminal commit requires the expected controller identity`() {
        val gate = OpticsCommitGate()
        val generation = gate.begin { it }
        val controllerA = Any()
        val controllerB = Any()
        var currentController: Any = controllerB
        var mutations = 0

        assertNull(
            gate.commit(
                expectedGeneration = generation,
                ownsTerminal = { currentController === controllerA },
            ) { mutations++ },
        )
        currentController = controllerA
        assertEquals(
            generation,
            gate.commit(
                expectedGeneration = generation,
                ownsTerminal = { currentController === controllerA },
            ) { mutations++ },
        )
        assertEquals(1, mutations)
    }
}
