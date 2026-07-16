package com.hletrd.findx9tele

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionPolicyTest {

    @Test
    fun freshInstallRemainsRequestable() {
        assertEquals(
            CameraPermissionDisposition.REQUESTABLE,
            classifyCameraPermission(
                granted = false,
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun canceledFirstPromptRemainsRequestable() {
        val requestedBefore = updatedCameraPermissionRequestHistory(false, result = null)
        assertEquals(
            CameraPermissionDisposition.REQUESTABLE,
            classifyCameraPermission(
                granted = false,
                requestedBefore = requestedBefore,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun firstDenialRemainsRequestableWhileAndroidShowsRationale() {
        val requestedBefore = updatedCameraPermissionRequestHistory(false, result = false)
        assertEquals(
            CameraPermissionDisposition.REQUESTABLE,
            classifyCameraPermission(
                granted = false,
                requestedBefore = requestedBefore,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun fixedDenialRequiresSettings() {
        val requestedBefore = updatedCameraPermissionRequestHistory(false, result = false)
        assertEquals(
            CameraPermissionDisposition.SETTINGS_REQUIRED,
            classifyCameraPermission(
                granted = false,
                requestedBefore = requestedBefore,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun grantWinsEvenIfAStaleFixedFlagIsObserved() {
        assertEquals(
            CameraPermissionDisposition.GRANTED,
            classifyCameraPermission(
                granted = true,
                requestedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun grantClearsPriorRequestHistory() {
        assertEquals(false, updatedCameraPermissionRequestHistory(true, result = true))
    }
}
