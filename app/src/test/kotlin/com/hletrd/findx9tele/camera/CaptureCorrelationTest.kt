package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCorrelationTest {

    @Test
    fun callbackToken_doesNotFollowPendingSlotReuse() {
        val expiredShot = Any()
        val admittedShot = Any()

        assertFalse(captureTokenIsCurrent(admittedShot, expiredShot))
        assertTrue(captureTokenIsCurrent(admittedShot, admittedShot))
    }

    @Test
    fun imageTimestamp_onlyMatchesItsOwnShutterStart() {
        val firstShotTimestamp = 1_000_000L
        val secondShotTimestamp = 2_000_000L

        assertTrue(timestampBelongsToCapture(secondShotTimestamp, secondShotTimestamp))
        assertFalse(timestampBelongsToCapture(secondShotTimestamp, firstShotTimestamp))
    }
}
