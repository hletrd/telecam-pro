package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomWbSamplingTest {

    @Test
    fun `only a converged unlocked auto result from the exact tagged request completes`() {
        val tag = CustomWbSampleTag(7)

        assertTrue(
            customWbResultBelongsToRequest(
                expectedTag = tag,
                resultTag = tag,
                awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
                awbLocked = false,
                awbState = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                gainsAvailable = true,
            ),
        )
        assertFalse(
            eligible(tag, resultTag = CustomWbSampleTag(7)),
        )
        assertFalse(
            eligible(tag, awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        )
        assertFalse(eligible(tag, awbLocked = true))
        assertFalse(eligible(tag, awbState = CaptureResult.CONTROL_AWB_STATE_SEARCHING))
        assertFalse(eligible(tag, awbState = null))
        assertFalse(eligible(tag, gainsAvailable = false))
    }

    @Test
    fun `sample remains owned by the exact ready accepted session through publication`() {
        val acceptedSession = Any()
        val controller = Any()

        assertTrue(ownerCurrent(acceptedSession, acceptedSession, controller, controller))
        assertFalse(ownerCurrent(Any(), acceptedSession, controller, controller))
        assertFalse(ownerCurrent(acceptedSession, acceptedSession, Any(), controller))
        assertFalse(
            ownerCurrent(
                acceptedSession,
                acceptedSession,
                controller,
                controller,
                currentSessionGeneration = 10,
            ),
        )
        assertFalse(
            ownerCurrent(
                acceptedSession,
                acceptedSession,
                controller,
                controller,
                cameraReady = false,
            ),
        )
        assertFalse(
            ownerCurrent(
                acceptedSession,
                acceptedSession,
                controller,
                controller,
                paused = true,
            ),
        )
        assertFalse(
            ownerCurrent(
                acceptedSession,
                acceptedSession,
                controller,
                controller,
                wbMode = WbMode.DAYLIGHT,
            ),
        )
        assertFalse(
            ownerCurrent(
                acceptedSession,
                acceptedSession,
                controller,
                controller,
                awbLocked = true,
            ),
        )
    }

    @Test
    fun `a sample freezes its converged gains and exact owner token`() {
        // The callback thread hands main exactly this pair; identity (not equality) is what the
        // main-thread recheck compares against the installed accepted session.
        val gains = WbGains(1.9f, 1f, 1f, 2.4f)
        val owner = Any()
        val sample = CustomWbSample(gains, owner)

        assertSame(gains, sample.gains)
        assertSame(owner, sample.ownerToken)
    }

    private fun eligible(
        expectedTag: CustomWbSampleTag,
        resultTag: Any? = expectedTag,
        awbMode: Int? = CameraMetadata.CONTROL_AWB_MODE_AUTO,
        awbLocked: Boolean? = false,
        awbState: Int? = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
        gainsAvailable: Boolean = true,
    ): Boolean = customWbResultBelongsToRequest(
        expectedTag = expectedTag,
        resultTag = resultTag,
        awbMode = awbMode,
        awbLocked = awbLocked,
        awbState = awbState,
        gainsAvailable = gainsAvailable,
    )

    private fun ownerCurrent(
        currentAcceptedSession: Any?,
        expectedAcceptedSession: Any?,
        currentController: Any?,
        expectedController: Any?,
        currentSessionGeneration: Long = 9,
        cameraReady: Boolean = true,
        paused: Boolean = false,
        wbMode: WbMode = WbMode.AUTO,
        awbLocked: Boolean = false,
    ): Boolean = customWbSampleOwnerIsCurrent(
        currentAcceptedSession = currentAcceptedSession,
        expectedAcceptedSession = expectedAcceptedSession,
        currentController = currentController,
        expectedController = expectedController,
        currentSessionGeneration = currentSessionGeneration,
        expectedSessionGeneration = 9,
        cameraReady = cameraReady,
        paused = paused,
        wbMode = wbMode,
        awbLocked = awbLocked,
    )
}
