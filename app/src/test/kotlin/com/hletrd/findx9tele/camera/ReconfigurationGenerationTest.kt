package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReconfigurationGenerationTest {

    @Test
    fun `preview recovery retries only a live current surface generation`() {
        assertEquals(
            PreviewRecoveryDecision.RETRY,
            previewRecoveryDecision(
                ownerCurrent = true,
                started = true,
                paused = false,
                nextAttempt = 1,
                maxAttempts = 3,
            ),
        )
        assertEquals(
            PreviewRecoveryDecision.RETRY,
            previewRecoveryDecision(true, true, false, nextAttempt = 3, maxAttempts = 3),
        )
    }

    @Test
    fun `preview recovery ignores stale stopped and paused owners`() {
        assertEquals(
            PreviewRecoveryDecision.IGNORE,
            previewRecoveryDecision(false, true, false, nextAttempt = 1, maxAttempts = 3),
        )
        assertEquals(
            PreviewRecoveryDecision.IGNORE,
            previewRecoveryDecision(true, false, false, nextAttempt = 1, maxAttempts = 3),
        )
        assertEquals(
            PreviewRecoveryDecision.IGNORE,
            previewRecoveryDecision(true, true, true, nextAttempt = 1, maxAttempts = 3),
        )
    }

    @Test
    fun `preview recovery reports exhaustion after its bounded budget`() {
        assertEquals(
            PreviewRecoveryDecision.EXHAUSTED,
            previewRecoveryDecision(true, true, false, nextAttempt = 4, maxAttempts = 3),
        )
    }

    @Test
    fun `session reopen requires owned desired generation`() {
        assertTrue(sessionReopenMayProceed(7, 7, true, paused = false, recording = false))
        assertFalse(sessionReopenMayProceed(8, 7, true, paused = false, recording = false))
    }

    @Test
    fun `session reopen rejects replaced controller lifecycle and recording`() {
        assertFalse(sessionReopenMayProceed(7, 7, false, paused = false, recording = false))
        assertFalse(sessionReopenMayProceed(7, 7, true, paused = true, recording = false))
        assertFalse(sessionReopenMayProceed(7, 7, true, paused = false, recording = true))
    }

    @Test
    fun `current controller owns an error before same controller fast commit`() {
        val gate = OpticsCommitGate()
        val controller = Any()
        val openGeneration = gate.begin { it }
        val pendingFastCommitGeneration = gate.begin { it }

        assertFalse(reconfigurationOwnsGeneration(pendingFastCommitGeneration, openGeneration))
        assertTrue(activeCameraFailureBelongsToController(controller, controller))
    }

    @Test
    fun `current controller still owns an error after same controller fast commit`() {
        val gate = OpticsCommitGate()
        val controller = Any()
        val currentController: Any = controller
        val openGeneration = gate.begin { it }
        val fastCommitGeneration = gate.begin { it }

        assertEquals(
            fastCommitGeneration,
            gate.commit(
                expectedGeneration = fastCommitGeneration,
                ownsTerminal = { currentController === controller },
            ) {},
        )
        assertFalse(reconfigurationOwnsGeneration(fastCommitGeneration, openGeneration))
        assertTrue(activeCameraFailureBelongsToController(currentController, controller))
    }

    @Test
    fun `replaced controller cannot deliver a stale error`() {
        val replacedController = Any()
        val currentController = Any()

        assertFalse(activeCameraFailureBelongsToController(currentController, replacedController))
    }

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
        val acceptedSize = android.util.Size(3840, 2160)
        val snapshot = OpticsIntentState(
            mode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(zoomRatio = 2.5f),
            overrideId = "4",
            photoExposureTimeNs = 4_000_000_000L,
            requestedVideoSize = acceptedSize,
        )

        val restored = rollbackOpticsState(7, 7, snapshot)
        assertSame(snapshot, restored)
        assertEquals(4_000_000_000L, restored?.photoExposureTimeNs)
        assertSame(acceptedSize, restored?.requestedVideoSize)
    }

    @Test
    fun `rollback keeps the routed target and the diagnostic pin distinct`() {
        // overrideId is the engine's ROUTED target (non-null after any door); userPin is only a
        // genuine setCameraOverride pin — the value the UI may surface as an active override.
        // A rollback that republished the routed id as the pin permanently showed the Setup
        // Camera ID row and refused the same-route recall fast path (cycle-6 code-review F5).
        val routineDoor = OpticsIntentState(
            mode = CaptureMode.PHOTO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(),
            overrideId = "4",
            userPin = null,
        )
        val restoredRoutine = rollbackOpticsState(7, 7, routineDoor)
        assertEquals("4", restoredRoutine?.overrideId)
        assertNull(restoredRoutine?.userPin)

        val pinnedDoor = routineDoor.copy(overrideId = "2", userPin = "2")
        assertEquals("2", rollbackOpticsState(7, 7, pinnedDoor)?.userPin)
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
        val acceptedSize = android.util.Size(3840, 2160)
        val accepted = OpticsIntentState(
            CaptureMode.PHOTO,
            LensChoice.MAIN,
            false,
            ManualControls(),
            null,
            photoExposureTimeNs = 4_000_000_000L,
            requestedVideoSize = acceptedSize,
        )
        val inFlight = accepted.copy(
            mode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            photoExposureTimeNs = 250_000_000L,
            requestedVideoSize = android.util.Size(1920, 1080),
        )

        val baseline = selectRollbackBaseline(
            cameraReady = false,
            current = inFlight,
            pendingBaseline = accepted,
        )
        assertSame(accepted, baseline)
        assertEquals(4_000_000_000L, baseline.photoExposureTimeNs)
        assertSame(acceptedSize, baseline.requestedVideoSize)
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
        assertTrue(cameraReadyPublicationIsCurrent(42L, 42L, 9L, 9L, cameraReady = true))
        assertFalse(cameraReadyPublicationIsCurrent(43L, 42L, 9L, 9L, cameraReady = true))
        assertFalse(cameraReadyPublicationIsCurrent(42L, 42L, 9L, 9L, cameraReady = false))
    }

    @Test
    fun `queued ready from a replaced session is rejected with unchanged optics`() {
        assertFalse(
            cameraReadyPublicationIsCurrent(
                currentOpticsGeneration = 42L,
                expectedOpticsGeneration = 42L,
                currentSessionGeneration = 10L,
                expectedSessionGeneration = 9L,
                cameraReady = true,
            ),
        )
    }

    @Test
    fun `newer not-ready publication prevents an older ready reducer commit`() {
        val gate = CameraReadyPublicationGate()
        val ready = CameraReadyPublication(
            sequence = 1L,
            ready = true,
            opticsGeneration = 42L,
            sessionGeneration = 9L,
            photoOutputs = PhotoSessionOutputs(processed = true, raw = true),
        )
        val notReady = CameraReadyPublication(
            sequence = 2L,
            ready = false,
            opticsGeneration = 42L,
            sessionGeneration = 10L,
        )
        val readyPassedEarlyCheck = CountDownLatch(1)
        val releaseReadyReducer = CountDownLatch(1)
        val staleReadyApplied = AtomicBoolean(false)

        assertTrue(gate.observe(ready))
        val reducer = thread(start = true, name = "queued-ready-reducer") {
            readyPassedEarlyCheck.countDown()
            assertTrue(releaseReadyReducer.await(1, TimeUnit.SECONDS))
            staleReadyApplied.set(gate.owns(ready))
        }

        assertTrue(readyPassedEarlyCheck.await(1, TimeUnit.SECONDS))
        assertTrue(gate.observe(notReady))
        releaseReadyReducer.countDown()
        reducer.join(1_000)

        assertFalse(reducer.isAlive)
        assertFalse(staleReadyApplied.get())
        assertTrue(gate.owns(notReady))
    }

    @Test
    fun `tap focus admission requires one live accepted AF session`() {
        val controller = Any()
        assertTrue(
            tapPointAdmissionAllowed(
                currentController = controller,
                acceptedController = controller,
                currentSessionGeneration = 9L,
                acceptedSessionGeneration = 9L,
                cameraReady = true,
                paused = false,
                canHoldFocus = true,
            ),
        )
        assertFalse(
            tapPointAdmissionAllowed(
                controller,
                controller,
                9L,
                9L,
                cameraReady = true,
                paused = false,
                canHoldFocus = false,
            ),
        )
    }

    @Test
    fun `tap publication applies UI inside its latest event boundary`() {
        val gate = TapFocusPublicationGate()
        val applied = mutableListOf<String>()
        val held = TapFocusPublication(sequence = 4L, held = true, point = 0.4f to 0.6f)
        val retired = TapFocusPublication(sequence = 5L, held = false)

        assertTrue(gate.applyIfLatest(held) { applied += "held" })
        assertTrue(gate.applyIfLatest(retired) { applied += "retired" })
        assertFalse(gate.applyIfLatest(held) { applied += "stale" })
        assertEquals(listOf("held", "retired"), applied)
    }

    @Test
    fun `tap hold publishes only after current session preview submission`() {
        assertEquals(
            TapFocusCompletionDecision.KEEP_PREVIOUS,
            tapFocusCompletionDecision(
                attemptCurrent = true,
                submission = TapFocusSubmissionResult.REJECTED_PREVIOUS_RESTORED,
                sessionCurrent = true,
            ),
        )
        assertEquals(
            TapFocusCompletionDecision.PUBLISH_HELD,
            tapFocusCompletionDecision(
                attemptCurrent = true,
                submission = TapFocusSubmissionResult.ACCEPTED,
                sessionCurrent = true,
            ),
        )
        assertEquals(
            TapFocusCompletionDecision.RETIRE,
            tapFocusCompletionDecision(
                attemptCurrent = true,
                submission = TapFocusSubmissionResult.ACCEPTED,
                sessionCurrent = false,
            ),
        )
        assertEquals(
            TapFocusCompletionDecision.RETIRE,
            tapFocusCompletionDecision(
                attemptCurrent = true,
                submission = TapFocusSubmissionResult.FAILED_UNCERTAIN,
                sessionCurrent = true,
            ),
        )
        assertEquals(
            TapFocusCompletionDecision.IGNORE,
            tapFocusCompletionDecision(
                attemptCurrent = false,
                submission = TapFocusSubmissionResult.ACCEPTED,
                sessionCurrent = true,
            ),
        )
    }

    @Test
    fun `tap completion survives preview-only NotReady on the exact accepted session`() {
        val accepted = Any()
        val controller = Any()

        // No cameraReady input by design: TextureView output replacement does not invalidate the
        // accepted Camera2 session or a trigger already submitted to its repeating request.
        assertTrue(
            tapFocusSessionOwnerIsCurrent(
                currentAcceptedSession = accepted,
                expectedAcceptedSession = accepted,
                currentController = controller,
                expectedController = controller,
                currentSessionGeneration = 12L,
                expectedSessionGeneration = 12L,
                paused = false,
            ),
        )
        assertFalse(
            tapFocusSessionOwnerIsCurrent(
                currentAcceptedSession = Any(),
                expectedAcceptedSession = accepted,
                currentController = controller,
                expectedController = controller,
                currentSessionGeneration = 12L,
                expectedSessionGeneration = 12L,
                paused = false,
            ),
        )
        assertFalse(
            tapFocusSessionOwnerIsCurrent(
                currentAcceptedSession = accepted,
                expectedAcceptedSession = accepted,
                currentController = controller,
                expectedController = controller,
                currentSessionGeneration = 13L,
                expectedSessionGeneration = 12L,
                paused = false,
            ),
        )
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

    @Test
    fun `same camera fast commit rejects a session invalidated after intent start`() {
        val gate = OpticsCommitGate()
        val generation = gate.begin { it }
        val expectedController = Any()
        var currentController: Any = expectedController
        val expectedSessionGeneration = 9L
        var currentSessionGeneration = expectedSessionGeneration
        var mutations = 0

        currentSessionGeneration++

        assertNull(
            gate.commit(
                expectedGeneration = generation,
                ownsTerminal = {
                    currentController === expectedController &&
                        currentSessionGeneration == expectedSessionGeneration
                },
            ) { mutations++ },
        )
        assertEquals(0, mutations)
    }

    @Test
    fun `invalidated current fast path schedules exactly one reconfigure`() {
        var commits = 0
        var reconfigures = 0

        assertFalse(
            convergeFastPathCommit(
                commit = { commits++; false },
                ownsTransaction = { true },
                canReconfigure = { true },
                reconfigure = { reconfigures++ },
            ),
        )

        assertEquals(1, commits)
        assertEquals(1, reconfigures)
    }

    @Test
    fun `superseded or inactive fast path does not reconfigure`() {
        var reconfigures = 0

        assertFalse(
            convergeFastPathCommit(
                commit = { false },
                ownsTransaction = { false },
                canReconfigure = { true },
                reconfigure = { reconfigures++ },
            ),
        )
        assertFalse(
            convergeFastPathCommit(
                commit = { false },
                ownsTransaction = { true },
                canReconfigure = { false },
                reconfigure = { reconfigures++ },
            ),
        )

        assertEquals(0, reconfigures)
    }

    @Test
    fun `successful fast path does not schedule redundant reconfigure`() {
        var ownershipChecks = 0
        var reconfigures = 0

        assertTrue(
            convergeFastPathCommit(
                commit = { true },
                ownsTransaction = { ownershipChecks++; true },
                canReconfigure = { true },
                reconfigure = { reconfigures++ },
            ),
        )

        assertEquals(0, ownershipChecks)
        assertEquals(0, reconfigures)
    }

    @Test
    fun `new intent waits until terminal mutation and callback generation are captured`() {
        val gate = OpticsCommitGate()
        val older = gate.begin { it }
        val terminalEntered = CountDownLatch(1)
        val releaseTerminal = CountDownLatch(1)
        val beginAttempted = CountDownLatch(1)
        val beginCompleted = CountDownLatch(1)
        val terminalState = AtomicReference("pending")
        val commitGeneration = AtomicReference<Long?>()
        val nextGeneration = AtomicReference<Long?>()

        val completion = thread(start = true, name = "owned-optics-commit") {
            commitGeneration.set(
                gate.commit(older) {
                    terminalState.set("mutating")
                    terminalEntered.countDown()
                    assertTrue(releaseTerminal.await(1, TimeUnit.SECONDS))
                    terminalState.set("committed")
                },
            )
        }
        assertTrue(terminalEntered.await(1, TimeUnit.SECONDS))

        val newerIntent = thread(start = true, name = "new-optics-intent") {
            beginAttempted.countDown()
            nextGeneration.set(gate.begin {
                assertEquals("committed", terminalState.get())
                it
            })
            beginCompleted.countDown()
        }
        assertTrue(beginAttempted.await(1, TimeUnit.SECONDS))
        assertFalse(beginCompleted.await(100, TimeUnit.MILLISECONDS))

        releaseTerminal.countDown()
        assertTrue(beginCompleted.await(1, TimeUnit.SECONDS))
        completion.join(1_000)
        newerIntent.join(1_000)

        assertFalse(completion.isAlive)
        assertFalse(newerIntent.isAlive)
        assertEquals(older, commitGeneration.get())
        assertEquals(older + 1, nextGeneration.get())
    }

    @Test
    fun `intent before GL input prevents launch route publication`() {
        val gate = OpticsCommitGate()
        var desiredRoute = "photo-logical"
        var publishedRoute: String? = null
        val launchGeneration = gate.begin { it }

        val videoGeneration = gate.begin {
            desiredRoute = "video-standalone"
            it
        }

        assertNull(gate.commit(launchGeneration) { publishedRoute = "photo-logical" })
        assertEquals(
            videoGeneration,
            gate.commit(videoGeneration) { publishedRoute = desiredRoute },
        )
        assertEquals("video-standalone", publishedRoute)
    }
}
