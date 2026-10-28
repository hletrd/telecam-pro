package me.hletrd.telecampro.camera

import org.junit.Assert.*
import org.junit.Test

class MakerLogTest {
    @Test fun `factory mode requires the measured rear route and recording shape`() {
        fun offered(profile: Boolean = true, teleMeasured: Boolean = true, route: CameraRoute = CameraRoute.BACK,
                    tc: Boolean = false, pin: String? = null, fps: Double = 29.97, gate: Boolean = false) =
            makerLogOffered(profile, teleMeasured, route, tc, pin, fps, gate)
        assertTrue(offered())
        assertTrue(offered(fps = 30.0))
        assertFalse(offered(profile = false))
        assertFalse(offered(route = CameraRoute.FRONT))
        assertFalse(offered(route = CameraRoute.EXTERNAL))
        // TC no longer withholds it where the profile measured the TC span (owner request
        // 2026-10-26); a profile without that measurement keeps the old answer.
        assertTrue(offered(tc = true))
        assertFalse(offered(tc = true, teleMeasured = false))
        assertTrue(offered(tc = false, teleMeasured = false))
        // Every other gate still holds with the converter on.
        assertFalse(offered(tc = true, fps = 59.94))
        assertFalse(offered(tc = true, gate = true))
        assertFalse(offered(tc = true, pin = "4"))
        assertFalse(offered(tc = true, profile = false))
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
        assertTrue(makerLogSourceConfirmed(0x830b, 0x830b, 0, log))
        assertFalse(makerLogSourceConfirmed(0x830b, 0x830b, 0, null))
        assertFalse(makerLogSourceConfirmed(0x830b, 0x830b, 0, log.copyOf(1023)))
        assertFalse(makerLogSourceConfirmed(0x830b, 0x80b4, 0, log))
        assertFalse(makerLogSourceConfirmed(0x830b, 0x830b, 1, log))
        assertFalse(makerLogSourceConfirmed(0x830b, 0x830b, 0, IntArray(1024) { it * 4 }))
        assertFalse(makerLogSourceConfirmed(0x830b, 0x830b, 0, IntArray(1024) { 754 }))
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

    @Test fun `the TC wire scale is the tele chip position the 3x chip applies`() {
        val all = LensChoice.entries.toSet()
        assertEquals(3f, makerTeleWireZoomScale(all, ZoomPresets.NOMINAL), 0f)
        // A device whose "3x" is a crop of the main has no tele to ride: the scale collapses to the
        // main lens, the same divisor unifiedZoomOf uses (maker Log is PMA110-only anyway).
        assertEquals(1f, makerTeleWireZoomScale(setOf(LensChoice.MAIN), ZoomPresets.NOMINAL), 0f)
    }

    @Test fun `the TC-local window spans the host tele field up to the measured ceiling`() {
        // PMA110 logical camera 0: 0.6-20x advertised, tele at 3x, measured ceiling 9.8x.
        val window = makerTeleLocalZoomWindow(0.6f, 20f, 3f, MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110)!!
        assertEquals(1f, window.start, 0f)
        assertEquals(9.8f / 3f, window.endInclusive, 1e-6f)
        // The advertised range still narrows it.
        assertEquals(8f / 3f, makerTeleLocalZoomWindow(0.6f, 8f, 3f, 10f)!!.endInclusive, 1e-6f)
        // A HAL floor above the tele's field raises the local floor with it.
        assertEquals(4f / 3f, makerTeleLocalZoomWindow(4f, 20f, 3f, 10f)!!.start, 1e-6f)
        // Unknown range or an invalid scale: no window (route caps then stay the camera's own).
        assertNull(makerTeleLocalZoomWindow(null, 20f, 3f, 10f))
        assertNull(makerTeleLocalZoomWindow(0.6f, null, 3f, 10f))
        assertNull(makerTeleLocalZoomWindow(0.6f, 20f, 0f, 10f))
        assertNull(makerTeleLocalZoomWindow(0.6f, 20f, Float.NaN, 10f))
        // A ceiling below the tele collapses to one point rather than an inverted range.
        val collapsed = makerTeleLocalZoomWindow(0.6f, 20f, 3f, 2f)!!
        assertEquals(collapsed.start, collapsed.endInclusive, 0f)
    }

    @Test fun `TC-local zoom maps onto the logical wire and back, clamped to the measured span`() {
        val route = MakerTeleRoute(wireZoomScale = 3f, wireZoomCeiling = MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110, hostPhysicalId = "4")
        for (local in listOf(1f, 1.25f, 2f, 3f, 9.8f / 3f)) {
            val wire = makerTeleWireZoom(local, route)
            assertEquals(local * 3f, wire, 1e-5f)
            assertEquals(local, makerTeleLocalZoom(wire, route), 1e-6f)
        }
        // Below the tele's own field the logical camera would hand over to the main lens.
        assertEquals(3f, makerTeleWireZoom(0.5f, route), 0f)
        // At 10x the logical camera hands the frame to the 10x periscope (measured), so the wire
        // never reaches it: the measured ceiling holds.
        assertEquals(9.8f, makerTeleWireZoom(4.6f, route), 0f)
        assertEquals(9.8f, makerTeleWireZoom(10f / 3f, route), 0f)
        // The ceiling a TC-local value reaches is about 42.6x of TOTAL magnification on PMA110 (the
        // ruler reads local x teleDisplayBase = 300/70 x 70/23).
        val total = (9.8f / 3f) * (300f / 70f) * (70f / 23f)
        assertEquals(42.6f, total, 0.1f)
    }

    @Test fun `the lens guard fires only on a proven lens handover`() {
        assertFalse(makerTeleLensOffHost("4", "4"))
        assertTrue(makerTeleLensOffHost("2", "4"))
        assertTrue(makerTeleLensOffHost("5", "4"))
        // Unknown on either side proves nothing.
        assertFalse(makerTeleLensOffHost(null, "4"))
        assertFalse(makerTeleLensOffHost("2", null))
        assertFalse(makerTeleLensOffHost(null, null))
    }

    @Test fun `the lens-switched tag needs TC, Video, an accepted maker session and a proven handover`() {
        assertTrue(teleconverterLensSwitchedTagVisible(true, true, true, true))
        assertFalse(teleconverterLensSwitchedTagVisible(false, true, true, true))
        assertFalse(teleconverterLensSwitchedTagVisible(true, false, true, true))
        assertFalse(teleconverterLensSwitchedTagVisible(true, true, false, true))
        assertFalse(teleconverterLensSwitchedTagVisible(true, true, true, false))
    }

    @Test fun `the TC host is the logical member nearest the converter lens, never a literal id`() {
        val candidates = listOf(
            TeleSelection("0", "3", 14f), TeleSelection("0", "2", 23f),
            TeleSelection("0", "7", 69.4f), TeleSelection("0", "5", 230f),
            // The standalone id for the same lens is NOT the member id the logical camera reports.
            TeleSelection("4", null, 69.4f),
            TeleSelection("9", "8", 70f),
        )
        assertEquals("7", CameraSelector2.pickPhysicalMember(candidates, "0", LensChoice.TELE3X.targetEquivMm))
        assertEquals("8", CameraSelector2.pickPhysicalMember(candidates, "9", LensChoice.TELE3X.targetEquivMm))
        assertNull(CameraSelector2.pickPhysicalMember(candidates, "4", LensChoice.TELE3X.targetEquivMm))
        assertNull(CameraSelector2.pickPhysicalMember(listOf(TeleSelection("0", "4", 0f)), "0", 70f))
    }

    @Test fun `only the measured profile carries the TC span`() {
        for (model in listOf("PMA110", "CPH2841", "PMA120")) {
            assertEquals(model, MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110, DeviceProfile.resolve(model).makerLogTeleMaxWireZoom)
        }
        // Below the 10x periscope's own field (230 / 23.4 mm = 9.83 on the logical scale).
        assertEquals(9.8f, MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110, 0f)
        assertTrue(MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110 < 230f / 23.4f)
        assertNull(DeviceProfile.GENERIC.makerLogTeleMaxWireZoom)
        assertNull(DeviceProfile.resolve("SM-F741N").makerLogTeleMaxWireZoom)
    }
}
