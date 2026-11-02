package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoColorModeBlockTest {
    @Test
    fun `OEM Log is offered exactly when the device has it and nothing blocks it`() {
        for (profile in listOf(true, false))
            for (teleMeasured in listOf(true, false))
                for (route in CameraRoute.entries)
                    for (tc in listOf(true, false))
                        for (pin in listOf(null, "4"))
                            for (fps in listOf(23.976, 29.97, 30.0, 59.94, Double.NaN))
                                for (openGate in listOf(true, false)) {
                                    val offered = makerLogOffered(profile, teleMeasured, route, tc, pin, fps, openGate)
                                    val block = makerLogBlock(profile, teleMeasured, route, tc, pin, fps, openGate)
                                    val case = "profile=$profile tele=$teleMeasured $route tc=$tc pin=$pin $fps og=$openGate"
                                    assertEquals(case, profile && block == null, offered)
                                    if (!profile) assertEquals(case, null, block)
                                }
    }

    @Test
    fun `each block names the condition the operator can change`() {
        fun block(
            route: CameraRoute = CameraRoute.BACK,
            tc: Boolean = false,
            teleMeasured: Boolean = true,
            pin: String? = null,
            fps: Double = 29.97,
            openGate: Boolean = false,
        ) = makerLogBlock(true, teleMeasured, route, tc, pin, fps, openGate)

        assertEquals(null, block())
        assertEquals(VideoColorModeBlock.OPEN_GATE, block(openGate = true))
        assertEquals(VideoColorModeBlock.FRAME_RATE, block(fps = 59.94))
        assertEquals(VideoColorModeBlock.REAR_ONLY, block(route = CameraRoute.FRONT))
        assertEquals(VideoColorModeBlock.CAMERA_PIN, block(pin = "2"))
        assertEquals(VideoColorModeBlock.TELECONVERTER, block(tc = true, teleMeasured = false))
        assertEquals(null, block(tc = true, teleMeasured = true))
    }
}
