package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins [CameraUiState.activeFnSlots]: the Fn bar reflects the current capture mode. */
class CameraStateTest {

    @Test
    fun activeFnSlots_selectsByMode() {
        val photoSlots = listOf(FnSlot.WB)
        val videoSlots = listOf(FnSlot.ISO)
        assertEquals(
            photoSlots,
            CameraUiState(mode = CaptureMode.PHOTO, photoFnSlots = photoSlots, videoFnSlots = videoSlots).activeFnSlots,
        )
        assertEquals(
            videoSlots,
            CameraUiState(mode = CaptureMode.VIDEO, photoFnSlots = photoSlots, videoFnSlots = videoSlots).activeFnSlots,
        )
    }
}
