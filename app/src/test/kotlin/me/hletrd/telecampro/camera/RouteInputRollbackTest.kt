package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure route-input rules the optics rollback and the DNG door share (RPL cycle 2, lane A1). */
class RouteInputRollbackTest {
    @Test
    fun `DNG moves only the non-TELE rear photo route under the standalone RAW law`() {
        assertTrue(dngIntentChangesRearRoute(video = false, teleconverter = false, from = false, to = true, rawForcesStandalone = true))
        assertTrue(dngIntentChangesRearRoute(video = false, teleconverter = false, from = true, to = false, rawForcesStandalone = true))
        // TELE already pins the standalone 3×, Video its standalone lens; a spec device keeps RAW logical.
        assertFalse(dngIntentChangesRearRoute(video = false, teleconverter = true, from = false, to = true, rawForcesStandalone = true))
        assertFalse(dngIntentChangesRearRoute(video = true, teleconverter = false, from = false, to = true, rawForcesStandalone = true))
        assertFalse(dngIntentChangesRearRoute(video = false, teleconverter = false, from = false, to = true, rawForcesStandalone = false))
        assertFalse(dngIntentChangesRearRoute(video = false, teleconverter = false, from = true, to = true, rawForcesStandalone = true))
    }

    @Test
    fun `rollback restores the baseline unless a direct write leaves the restored route alone`() {
        fun restore(
            direct: Boolean,
            video: Boolean = false,
            tele: Boolean = false,
            route: CameraRoute = CameraRoute.BACK,
            law: Boolean = true,
        ) = rollbackRawWanted(
            current = true,
            baseline = false,
            directWriteSinceBaseline = direct,
            restoredVideo = video,
            restoredTeleconverter = tele,
            restoredRoute = route,
            rawForcesStandalone = law,
        )
        assertFalse("no direct write: the transaction's own value rolls back", restore(direct = false))
        assertFalse("direct write would move restored logical Photo", restore(direct = true))
        assertTrue(restore(direct = true, video = true))
        assertTrue(restore(direct = true, tele = true))
        assertTrue(restore(direct = true, route = CameraRoute.FRONT))
        assertTrue(restore(direct = true, route = CameraRoute.EXTERNAL))
        assertTrue("a spec device keeps RAW on the logical route", restore(direct = true, law = false))
    }

    @Test
    fun `rollback re-accepts the baseline under its own or the failing door's preflight generation`() {
        fun restorable(
            current: Long,
            preflight: Long? = null,
            ready: Boolean = true,
            matches: Boolean = true,
            paused: Boolean = false,
        ) = rollbackRestorableSessionGeneration(
            beforeReady = ready,
            controllerMatches = matches,
            paused = paused,
            beforeSessionGeneration = 7L,
            currentSessionGeneration = current,
            preflightSessionGeneration = preflight,
        )
        assertEquals(7L, restorable(current = 7L))
        assertNull("an ordinary rollback after a session bump stays Not-Ready", restorable(current = 8L))
        assertEquals("the door's own pre-close invalidation retired no camera", 8L, restorable(current = 8L, preflight = 8L))
        assertNull("a later camera error/pause bump is not restorable", restorable(current = 9L, preflight = 8L))
        assertNull(restorable(current = 7L, ready = false))
        assertNull(restorable(current = 7L, matches = false))
        assertNull(restorable(current = 8L, preflight = 8L, paused = true))
    }

    // AGG3-7: a bare reopen's baseline is snapshotted AFTER its door mutated, so its own
    // preflight invalidation must not license re-accepting the outgoing controller.
    @Test
    fun `only a pre-mutation baseline may restore under its preflight generation`() {
        assertEquals(8L, preflightRestorableSessionGeneration(baselinePrecedesMutation = true, preflightSessionGeneration = 8L))
        assertNull(preflightRestorableSessionGeneration(baselinePrecedesMutation = false, preflightSessionGeneration = 8L))
        // Composed: a bare door whose reopen already bumped the session (7 -> 8) stays Not-Ready.
        assertNull(
            rollbackRestorableSessionGeneration(
                beforeReady = true,
                controllerMatches = true,
                paused = false,
                beforeSessionGeneration = 7L,
                currentSessionGeneration = 8L,
                preflightSessionGeneration = preflightRestorableSessionGeneration(false, 8L),
            ),
        )
    }

    // MRG4-3: only a refusal nothing else owns counts as a park that needs the terminal status.
    @Test
    fun `retry refusal separates supersession and lifecycle from a real park`() {
        fun refusal(
            current: Boolean = true,
            open: Boolean = true,
            started: Boolean = true,
            paused: Boolean = false,
            recorder: Boolean = false,
            input: Boolean = true,
            pending: Boolean = false,
        ) = coldStartRetryRefusal(current, open, started, paused, recorder, input, pending)
        assertEquals(ColdStartRetryOutcome.SUPERSEDED, refusal(current = false, recorder = true))
        assertEquals(ColdStartRetryOutcome.LIFECYCLE_OWNED, refusal(paused = true, recorder = true))
        assertEquals(ColdStartRetryOutcome.LIFECYCLE_OWNED, refusal(started = false))
        assertEquals(ColdStartRetryOutcome.LIFECYCLE_OWNED, refusal(open = false))
        assertEquals(ColdStartRetryOutcome.LIFECYCLE_OWNED, refusal(input = false, pending = true))
        assertEquals(ColdStartRetryOutcome.BLOCKED, refusal(recorder = true))
        assertEquals(ColdStartRetryOutcome.BLOCKED, refusal(input = false))
        assertEquals(ColdStartRetryOutcome.OWNED, refusal())
    }

    // AGG4-2: the bare door's preflight failure converges through the bounded retry; only a
    // pre-mutation baseline may restore the outgoing session, and a cold start keeps its retry.
    @Test
    fun `preflight failure disposition never parks a bare door Not-Ready`() {
        assertEquals(
            PreflightFailureDisposition.COLD_RETRY,
            preflightFailureDisposition(recoverColdPreflight = true, baselinePrecedesMutation = false),
        )
        assertEquals(
            PreflightFailureDisposition.COLD_RETRY,
            preflightFailureDisposition(recoverColdPreflight = true, baselinePrecedesMutation = true),
        )
        assertEquals(
            PreflightFailureDisposition.RESTORE_ROLLBACK,
            preflightFailureDisposition(recoverColdPreflight = false, baselinePrecedesMutation = true),
        )
        assertEquals(
            PreflightFailureDisposition.BARE_RETRY,
            preflightFailureDisposition(recoverColdPreflight = false, baselinePrecedesMutation = false),
        )
    }

    @Test
    fun `a newer transaction-less write survives an older rollback`() {
        assertEquals("1080p", keepNewerDirectWrite(current = "1080p", baseline = "4K", directWriteSinceBaseline = true))
        assertEquals("4K", keepNewerDirectWrite(current = "1080p", baseline = "4K", directWriteSinceBaseline = false))
    }

    @Test
    fun `dual-open candidate never installs once paused or recording`() {
        fun admitted(
            quarantined: Boolean = false,
            gl: Boolean = true,
            owns: Boolean = true,
            paused: Boolean = false,
            recording: Boolean = false,
        ) = dualOpenCandidateInstallAdmitted(quarantined, gl, owns, paused, recording)
        assertTrue(admitted())
        assertFalse("pause landed between the outer check and the install", admitted(paused = true))
        assertFalse(admitted(recording = true))
        assertFalse(admitted(quarantined = true))
        assertFalse(admitted(gl = false))
        assertFalse(admitted(owns = false))
    }
}
