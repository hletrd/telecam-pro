package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [afOverrideFor], the pure decision behind the ONE shared AF-override applier (ARCH4-5).
 * The full rebuild and the sensor fast path both route through it, so these cases are the
 * regression net for the tap-AF/AF-lock drift class (c928eac/f61594a).
 */
class AfOverrideTest {

    @Test
    fun `no override when neither tap nor lock is active`() {
        assertNull(
            afOverrideFor(
                touchAfUsesAuto = false,
                afLock = false,
                focusMode = FocusMode.CONTINUOUS,
                supportsManualFocus = true,
                afOffAdvertised = true,
                lastFocusDistance = 1.5f,
            ),
        )
    }

    @Test
    fun `tap hold alone applies AF_MODE_AUTO`() {
        assertEquals(
            AfOverride.TouchAuto,
            afOverrideFor(
                touchAfUsesAuto = true,
                afLock = false,
                focusMode = FocusMode.CONTINUOUS,
                supportsManualFocus = true,
                afOffAdvertised = true,
                lastFocusDistance = 1.5f,
            ),
        )
    }

    @Test
    fun `lock alone pins AF_MODE_OFF at the last distance`() {
        assertEquals(
            AfOverride.LockAt(2.25f),
            afOverrideFor(
                touchAfUsesAuto = false,
                afLock = true,
                focusMode = FocusMode.CONTINUOUS,
                supportsManualFocus = true,
                afOffAdvertised = true,
                lastFocusDistance = 2.25f,
            ),
        )
    }

    @Test
    fun `lock WINS over a simultaneous tap hold (the pinned precedence)`() {
        // Both write CONTROL_AF_MODE; the historical sequential apply left the lock as the final
        // key state. A refactor flipping this to touch-wins must fail here, not on device.
        assertEquals(
            AfOverride.LockAt(0.75f),
            afOverrideFor(
                touchAfUsesAuto = true,
                afLock = true,
                focusMode = FocusMode.CONTINUOUS,
                supportsManualFocus = true,
                afOffAdvertised = true,
                lastFocusDistance = 0.75f,
            ),
        )
    }

    @Test
    fun `lock preconditions fall back to the tap hold when unmet`() {
        // MANUAL focus mode, no manual-focus support, or AF_MODE_OFF not advertised each disable
        // the lock branch; a live tap hold must then still apply.
        val expectTouch = afOverrideFor(
            touchAfUsesAuto = true,
            afLock = true,
            focusMode = FocusMode.MANUAL,
            supportsManualFocus = true,
            afOffAdvertised = true,
            lastFocusDistance = 1f,
        )
        assertEquals(AfOverride.TouchAuto, expectTouch)
        assertEquals(
            AfOverride.TouchAuto,
            afOverrideFor(
                touchAfUsesAuto = true,
                afLock = true,
                focusMode = FocusMode.CONTINUOUS,
                supportsManualFocus = false,
                afOffAdvertised = true,
                lastFocusDistance = 1f,
            ),
        )
        assertEquals(
            AfOverride.TouchAuto,
            afOverrideFor(
                touchAfUsesAuto = true,
                afLock = true,
                focusMode = FocusMode.CONTINUOUS,
                supportsManualFocus = true,
                afOffAdvertised = false,
                lastFocusDistance = 1f,
            ),
        )
    }

    @Test
    fun `AF telemetry belongs only to the latest preview request`() {
        assertFalse(
            shouldPublishAfState(
                requestGeneration = 8L,
                latestRequestGeneration = 9L,
                firstResultForRequest = true,
                requestAfTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
                afState = 4,
                lastReportedAfState = 4,
            ),
        )
        assertTrue(
            shouldPublishAfState(
                requestGeneration = 9L,
                latestRequestGeneration = 9L,
                firstResultForRequest = true,
                requestAfTrigger = null,
                afState = 4,
                lastReportedAfState = 4,
            ),
        )
        assertFalse(
            shouldPublishAfState(
                requestGeneration = 9L,
                latestRequestGeneration = 9L,
                firstResultForRequest = false,
                requestAfTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
                afState = 4,
                lastReportedAfState = 4,
            ),
        )
        assertTrue(
            shouldPublishAfState(
                requestGeneration = 9L,
                latestRequestGeneration = 9L,
                firstResultForRequest = false,
                requestAfTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
                afState = 5,
                lastReportedAfState = 4,
            ),
        )
    }

    @Test
    fun `AF telemetry ignores CANCEL and START control captures`() {
        for (trigger in listOf(
            CameraMetadata.CONTROL_AF_TRIGGER_CANCEL,
            CameraMetadata.CONTROL_AF_TRIGGER_START,
        )) {
            assertFalse(
                shouldPublishAfState(
                    requestGeneration = 9L,
                    latestRequestGeneration = 9L,
                    firstResultForRequest = true,
                    requestAfTrigger = trigger,
                    afState = CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
                    lastReportedAfState = CameraMetadata.CONTROL_AF_STATE_INACTIVE,
                ),
            )
        }
    }

    @Test
    fun `AF Lock suppresses the tap trigger without rejecting the tap region`() {
        assertTrue(tapAfTriggerRequired(true, touchAfUsesAuto = true, afLock = false))
        assertFalse(tapAfTriggerRequired(true, touchAfUsesAuto = true, afLock = true))
    }

    @Test
    fun `unlocking AF Lock rearms only a surviving tap owned point`() {
        assertTrue(
            tapAfShouldRearmAfterUnlock(
                previousAfLock = true,
                nextAfLock = false,
                touchAfActive = true,
                meteringPointPresent = true,
            ),
        )
        assertFalse(tapAfShouldRearmAfterUnlock(false, false, true, true))
        assertFalse(tapAfShouldRearmAfterUnlock(true, true, true, true))
        assertFalse(tapAfShouldRearmAfterUnlock(true, false, false, true))
        assertFalse(tapAfShouldRearmAfterUnlock(true, false, true, false))
    }
}
