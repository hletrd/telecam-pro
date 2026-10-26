package me.hletrd.telecampro.camera

import org.junit.Assert.*
import org.junit.Test

class MakerLogTest {
    @Test fun `factory mode requires the measured rear route and recording shape`() {
        fun offered(profile: Boolean = true, route: CameraRoute = CameraRoute.BACK,
                    tc: Boolean = false, pin: String? = null, fps: Double = 29.97, gate: Boolean = false) =
            makerLogOffered(profile, route, tc, pin, fps, gate)
        assertTrue(offered())
        assertTrue(offered(fps = 30.0))
        assertFalse(offered(profile = false))
        assertFalse(offered(route = CameraRoute.FRONT))
        assertFalse(offered(route = CameraRoute.EXTERNAL))
        assertFalse(offered(tc = true))
        assertFalse(offered(pin = "2"))
        assertFalse(offered(fps = 60.0))
        assertFalse(offered(fps = Double.NaN))
        assertFalse(offered(fps = 0.0))
        assertFalse(offered(gate = true))
    }

    @Test fun `maker mode is a separate pipeline and never an app or RAW curve`() {
        assertFalse(rawLogTransfer(ColorTransfer.MAKER_LOG))
        assertFalse(ColorTransfer.MAKER_LOG in VIDEO_LOG_PROFILES)
        assertEquals(VideoColorMode.MAKER_LOG,
            videoColorModeOf(ColorTransfer.MAKER_LOG, true, true, true))
        assertEquals(VideoColorMode.LOG,
            videoColorModeOf(ColorTransfer.MAKER_LOG, true, true, false))
        assertEquals(VideoColorMode.MAKER_LOG,
            availableVideoColorModes(VideoCodec.HEVC, true, true, true).last())
        assertFalse(VideoColorMode.MAKER_LOG in availableVideoColorModes(VideoCodec.HEVC, true, true, false))
    }

    @Test fun `a tag echo or an SDR LUT cannot publish a native source`() {
        val log = IntArray(1024) { if (it < 256) 754 + it else 0 }
        assertTrue(makerLogSourceConfirmed(0x830b, 0, log))
        assertFalse(makerLogSourceConfirmed(0x830b, 0, null))
        assertFalse(makerLogSourceConfirmed(0x830b, 0, log.copyOf(1023)))
        assertFalse(makerLogSourceConfirmed(0x80b4, 0, log))
        assertFalse(makerLogSourceConfirmed(0x830b, 1, log))
        assertFalse(makerLogSourceConfirmed(0x830b, 0, IntArray(1024) { it * 4 }))
        assertFalse(makerLogSourceConfirmed(0x830b, 0, IntArray(1024) { 754 }))
    }

    @Test fun `refused maker intent records the named fallback without rewriting intent`() {
        assertEquals(ColorTransfer.MAKER_LOG, effectiveRecordingTransfer(ColorTransfer.MAKER_LOG, true))
        assertEquals(ColorTransfer.SLOG3, effectiveRecordingTransfer(ColorTransfer.MAKER_LOG, false))
        for (transfer in VIDEO_LOG_PROFILES + ColorTransfer.SDR + ColorTransfer.HLG) {
            assertEquals(transfer, effectiveRecordingTransfer(transfer, false))
        }
    }

    @Test fun `maker video uses logical zoom while photo DNG retains its own route`() {
        assertFalse(standaloneRouteWanted(true, true, true, makerLog = true))
        assertTrue(standaloneRouteWanted(true, false, true, makerLog = false))
        assertTrue(standaloneRouteWanted(false, true, true, makerLog = true))
        assertEquals(VideoSessionShape.MAKER_LOG,
            videoSessionShape(true, ColorTransfer.MAKER_LOG, true, false, true))
    }
}
