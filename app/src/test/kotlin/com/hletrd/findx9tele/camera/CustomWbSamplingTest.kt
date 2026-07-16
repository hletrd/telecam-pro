package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertFalse
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
}
