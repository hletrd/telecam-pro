package com.hletrd.findx9tele

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareInputPolicyTest {

    @Test
    fun availableDownStartsAndOwnsCameraKey() {
        val decision = cameraKeyDecision(
            hasCameraPermission = true,
            cameraInputBlocked = false,
            alreadyOwned = false,
            edge = CameraKeyEdge.DOWN,
        )

        assertTrue(decision.consume)
        assertTrue(decision.start)
        assertTrue(decision.ownAfter)
        assertFalse(decision.release)
    }

    @Test
    fun blockedOrUnauthorizedDownPassesToAndroid() {
        listOf(
            cameraKeyDecision(true, true, false, CameraKeyEdge.DOWN),
            cameraKeyDecision(false, false, false, CameraKeyEdge.DOWN),
        ).forEach { decision ->
            assertFalse(decision.consume)
            assertFalse(decision.start)
            assertFalse(decision.ownAfter)
        }
    }

    @Test
    fun ownedRepeatRemainsConsumedAcrossModalOpening() {
        val decision = cameraKeyDecision(
            hasCameraPermission = true,
            cameraInputBlocked = true,
            alreadyOwned = true,
            edge = CameraKeyEdge.REPEAT,
        )

        assertTrue(decision.consume)
        assertTrue(decision.ownAfter)
        assertFalse(decision.start)
        assertFalse(decision.release)
    }

    @Test
    fun ownedUpReleasesEvenAfterPermissionOrModalChanges() {
        listOf(
            cameraKeyDecision(true, true, true, CameraKeyEdge.UP),
            cameraKeyDecision(false, false, true, CameraKeyEdge.UP),
        ).forEach { decision ->
            assertTrue(decision.consume)
            assertTrue(decision.release)
            assertFalse(decision.ownAfter)
        }
    }

    @Test
    fun unownedRepeatAndUpPassToAndroid() {
        listOf(CameraKeyEdge.REPEAT, CameraKeyEdge.UP).forEach { edge ->
            val decision = cameraKeyDecision(true, false, false, edge)

            assertFalse(decision.consume)
            assertFalse(decision.start)
            assertFalse(decision.release)
            assertFalse(decision.ownAfter)
        }
    }
}
