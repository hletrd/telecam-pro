package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The facing-door refusal/rollback matrix. [backOpticsDoorRefusal] is the ONE decision behind the
 * rear-only doors (lens presets, TC toggle) in both the engine's defensive gate and the
 * ViewModel's user-facing message; the rollback half pins that a refused facing flip restores the
 * exact prior facing only under its owning optics generation, like every other rolled-back axis.
 */
class FacingDoorPolicyTest {

    @Test
    fun `rear-only doors admit on the plain rear route`() {
        assertEquals(BackOpticsRefusal.NONE, backOpticsDoorRefusal(recording = false, frontFacing = false))
    }

    @Test
    fun `front route refuses lens and TC doors`() {
        assertEquals(BackOpticsRefusal.FRONT_ROUTE, backOpticsDoorRefusal(recording = false, frontFacing = true))
    }

    @Test
    fun `recording refusal outranks the front-route refusal`() {
        // "Stop REC first" is the actionable step even while FRONT (the flip door is REC-gated too).
        assertEquals(BackOpticsRefusal.RECORDING, backOpticsDoorRefusal(recording = true, frontFacing = true))
        assertEquals(BackOpticsRefusal.RECORDING, backOpticsDoorRefusal(recording = true, frontFacing = false))
    }

    @Test
    fun `rollback restores the exact prior facing under the owning generation`() {
        val beforeFrontAttempt = OpticsIntentState(
            mode = CaptureMode.PHOTO,
            lens = LensChoice.TELE3X,
            teleconverter = false,
            controls = ManualControls(),
            overrideId = null,
            facing = CameraFacing.BACK,
        )
        assertEquals(
            CameraFacing.BACK,
            rollbackOpticsState(currentGeneration = 5, expectedGeneration = 5, snapshot = beforeFrontAttempt)!!.facing,
        )
        // A failed EXIT restores FRONT the same way — facing is not special-cased to BACK.
        val beforeExitAttempt = beforeFrontAttempt.copy(facing = CameraFacing.FRONT)
        assertEquals(
            CameraFacing.FRONT,
            rollbackOpticsState(currentGeneration = 7, expectedGeneration = 7, snapshot = beforeExitAttempt)!!.facing,
        )
    }

    @Test
    fun `a superseded facing rollback is rejected with the whole packet`() {
        assertNull(
            rollbackOpticsState(
                currentGeneration = 6,
                expectedGeneration = 5,
                snapshot = OpticsIntentState(
                    mode = CaptureMode.PHOTO,
                    lens = LensChoice.MAIN,
                    teleconverter = false,
                    controls = ManualControls(),
                    overrideId = null,
                    facing = CameraFacing.FRONT,
                ),
            ),
        )
    }
}
