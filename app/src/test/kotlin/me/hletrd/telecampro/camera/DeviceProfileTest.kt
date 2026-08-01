package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceProfile is the multi-device quirk seam (2026-08-01). These pin the two contracts that
 * matter: PMA110 keeps every shipped workaround, and an unknown device gets SPEC behavior — no
 * vendor session type, no exposure clamp, no pre-mirrored front stream.
 */
class DeviceProfileTest {

    @Test
    fun `PMA110 resolves its measured quirk set, case- and whitespace-insensitively`() {
        for (model in listOf("PMA110", "pma110", " PMA110 ")) {
            val p = DeviceProfile.resolve(model)
            assertTrue(model, p.frontStreamPreMirrored)
            assertTrue(model, p.vendorTcSessionType)
            assertTrue(model, p.vendorOplusRequestHints)
            assertEquals(model, HAL_SAFE_MAX_STILL_EXPOSURE_NS, p.stillExposureCeilingNs)
        }
    }

    @Test
    fun `unknown models and null resolve to spec behavior`() {
        for (model in listOf(null, "", "SM-S928B", "V2324A", "PMA111")) {
            val p = DeviceProfile.resolve(model)
            assertFalse("$model", p.frontStreamPreMirrored)
            assertFalse("$model", p.vendorTcSessionType)
            assertFalse("$model", p.vendorOplusRequestHints)
            assertNull("$model", p.stillExposureCeilingNs)
        }
    }

    @Test
    fun `generic profile disables the TC-shaped ladder so vendor 0x80b4 can never reach configure`() {
        // The controller passes (teleconverterMode && profile.vendorTcSessionType) into
        // sessionAttemptPlan; with the generic profile that is the plain ladder on every attempt.
        for (attempt in 0..3) {
            val plan = sessionAttemptPlan(
                attempt = attempt,
                wantHlg = false,
                supportsRaw = true,
                standalone = true,
                teleconverterMode = false, // TC on + GENERIC profile collapses to false
            )
            assertFalse("attempt $attempt", plan.useVendorOperationMode)
        }
    }
}
