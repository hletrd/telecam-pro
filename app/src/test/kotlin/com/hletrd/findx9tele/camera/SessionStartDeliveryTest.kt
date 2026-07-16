package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStartDeliveryTest {
    @Test
    fun `accepted repeating request delivers ready`() {
        assertEquals(SessionStartDelivery.READY, sessionStartDelivery(true))
    }

    @Test
    fun `failed repeating request delivers error`() {
        assertEquals(SessionStartDelivery.ERROR, sessionStartDelivery(false))
    }
}
