package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MakerStandaloneTeleRouteTest {
    @Test fun `standalone video route requires its own measured admission`() {
        assertEquals(
            MakerTeleSessionRoute.LOGICAL_MAKER,
            makerTeleSessionRoute(tc = true, maker = true, standaloneVideoAdmitted = false),
        )
        assertEquals(
            MakerTeleSessionRoute.STANDALONE_VIDEO,
            makerTeleSessionRoute(tc = true, maker = true, standaloneVideoAdmitted = true),
        )
        assertEquals(
            MakerTeleSessionRoute.LOGICAL_MAKER,
            makerTeleSessionRoute(tc = false, maker = true, standaloneVideoAdmitted = true),
        )
        assertEquals(
            MakerTeleSessionRoute.LOGICAL_MAKER,
            makerTeleSessionRoute(tc = true, maker = false, standaloneVideoAdmitted = true),
        )
    }

    @Test fun `source confirmation checks the expected operation mode`() {
        val validOemGamma = IntArray(1024) { if (it < 256) 754 + it else 0 }
        assertTrue(makerLogSourceConfirmed(MAKER_VIDEO_SESSION_TYPE, MAKER_VIDEO_SESSION_TYPE, 0, validOemGamma))
        assertFalse(makerLogSourceConfirmed(MAKER_VIDEO_SESSION_TYPE, MakerLogProtocol.SESSION_TYPE, 0, validOemGamma))
        assertTrue(makerLogSourceConfirmed(MakerLogProtocol.SESSION_TYPE, MakerLogProtocol.SESSION_TYPE, 0, validOemGamma))
        assertFalse(makerLogSourceConfirmed(MakerLogProtocol.SESSION_TYPE, MAKER_VIDEO_SESSION_TYPE, 0, validOemGamma))
        assertEquals(0x80b5, MAKER_VIDEO_SESSION_TYPE)
    }

    @Test fun `standalone video never creates logical wire zoom conversion`() {
        val logical = makerTeleLogicalWireRoute(
            MakerTeleSessionRoute.LOGICAL_MAKER,
            wireZoomScale = 3f,
            wireZoomCeiling = MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110,
            hostPhysicalId = "4",
        )
        assertNotNull(logical)
        assertEquals(3f, logical!!.wireZoomScale, 0f)
        assertNull(
            makerTeleLogicalWireRoute(
                MakerTeleSessionRoute.STANDALONE_VIDEO,
                wireZoomScale = 3f,
                wireZoomCeiling = MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110,
                hostPhysicalId = "4",
            ),
        )
    }

    @Test fun `standalone refusal selects ordinary standalone TC and S-Log3`() {
        // A refused maker session clears maker acceptance, not the operator's TC intent. The existing
        // route and transfer laws then select the proven standalone TC session and app S-Log3.
        assertTrue(standaloneRouteWanted(videoMode = true, rawWanted = false, rawForcesStandalone = true, makerLog = false))
        assertEquals(ColorTransfer.SLOG3, effectiveRecordingTransfer(ColorTransfer.MAKER_LOG, makerLogAccepted = false))
    }

    @Test fun `standalone maker video is not admitted by any production profile`() {
        for (model in listOf("PMA110", "CPH2841", "PMA120", "SM-F741N")) {
            assertFalse(model, DeviceProfile.resolve(model).makerLogStandaloneTeleVideo)
        }
        assertFalse(DeviceProfile.GENERIC.makerLogStandaloneTeleVideo)
    }
}
