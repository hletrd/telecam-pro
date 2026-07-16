package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedCameraSessionTest {

    private val controller = Any()

    @Test
    fun `ready matching identity and generation admits recording`() {
        assertTrue(acceptedCameraSessionIsCurrent(controller, controller, 4, 4, true, false))
    }

    @Test
    fun `replaced controller or generation rejects recording`() {
        assertFalse(acceptedCameraSessionIsCurrent(Any(), controller, 4, 4, true, false))
        assertFalse(acceptedCameraSessionIsCurrent(controller, controller, 5, 4, true, false))
    }

    @Test
    fun `not ready or paused rejects recording`() {
        assertFalse(acceptedCameraSessionIsCurrent(controller, controller, 4, 4, false, false))
        assertFalse(acceptedCameraSessionIsCurrent(controller, controller, 4, 4, true, true))
    }
}
