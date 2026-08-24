package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpticsDeclarationTransactionTest {
    @Test
    fun `named phone declaration owns host focal even when caller supplies outgoing host`() {
        val recalled = teleconverterDeclaration(
            phone = PhoneModel.FIND_X9_ULTRA,
            profile = TeleconverterProfile.EXPLORER_300,
            customMagnification = TELECONVERTER_MAGNIFICATION,
            // The outgoing vivo declaration used 85 mm; a recalled OPPO packet must ignore it.
            measuredOtherHostEquivMm = 85f,
        )

        assertEquals(70f, recalled.hostTeleEquivMm, 0f)
        assertEquals(300f, effectiveFocalMm(recalled.magnification, recalled.hostTeleEquivMm), 0.001f)
    }

    @Test
    fun `owned async rollback restores declaration with the complete optics snapshot`() {
        val accepted = teleconverterDeclaration(
            PhoneModel.VIVO_X300_ULTRA,
            TeleconverterProfile.ZEISS_200_X300,
            TELECONVERTER_MAGNIFICATION,
        )
        val snapshot = OpticsIntentState(
            mode = CaptureMode.PHOTO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(zoomRatio = 1f),
            overrideId = "tele",
            declaration = accepted,
        )

        val restored = rollbackOpticsState(
            currentGeneration = 12,
            expectedGeneration = 12,
            snapshot = snapshot,
        )

        assertEquals(accepted, restored?.declaration)
        assertEquals(85f, restored?.declaration?.hostTeleEquivMm ?: 0f, 0f)
        assertEquals(
            200f,
            effectiveFocalMm(
                restored?.declaration?.magnification ?: 0f,
                restored?.declaration?.hostTeleEquivMm ?: 0f,
            ),
            0.001f,
        )
    }

    @Test
    fun `superseded async rollback has no declaration publication`() {
        val accepted = teleconverterDeclaration(
            PhoneModel.OTHER,
            TeleconverterProfile.GENERIC_2,
            TELECONVERTER_MAGNIFICATION,
            measuredOtherHostEquivMm = 69.4f,
        )
        val snapshot = OpticsIntentState(
            mode = CaptureMode.PHOTO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(),
            overrideId = "tele",
            declaration = accepted,
        )

        assertNull(
            rollbackOpticsState(
                currentGeneration = 13,
                expectedGeneration = 12,
                snapshot = snapshot,
            ),
        )
    }
}
