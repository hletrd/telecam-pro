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
            assertTrue(model, p.logicalStillRequiresYuv)
            assertTrue(model, p.rawRequiresStandalone)
            assertEquals(model, HAL_SAFE_MAX_STILL_EXPOSURE_NS, p.stillExposureCeilingNs)
            assertTrue(model, p.videoMinFrameDurationsUnderstated)
        }
    }

    @Test
    fun `the global and satellite Find X9 Ultra take the whole PMA110 profile`() {
        for (model in listOf("CPH2841", "cph2841", " CPH2841 ", "PMA120", " pma120 ")) {
            assertEquals(model, DeviceProfile.PMA110, DeviceProfile.resolve(model))
        }
    }

    @Test
    fun `unknown models and null resolve to spec behavior`() {
        for (model in listOf(null, "", "SM-S928B", "V2324A", "PMA111")) {
            val p = DeviceProfile.resolve(model)
            assertFalse("$model", p.frontStreamPreMirrored)
            assertFalse("$model", p.vendorTcSessionType)
            assertFalse("$model", p.vendorOplusRequestHints)
            assertFalse("$model", p.logicalStillRequiresYuv)
            assertFalse("$model", p.rawRequiresStandalone)
            assertNull("$model", p.stillExposureCeilingNs)
            assertFalse("$model", p.videoMinFrameDurationsUnderstated)
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
                teleconverterMode = false, // TC on + GENERIC profile collapses to false,
                logicalMultiCamera = false,
                wantHiRes = false,
                tenBitVideoOnly = false,
                frontRoute = false,
                yuvStillRequired = true,
                rawStandaloneOnly = true,
            )
            assertFalse("attempt $attempt", plan.useVendorOperationMode)
        }
    }

    @Test
    fun `an external camera never inherits host handset quirks`() {
        assertEquals(DeviceProfile.GENERIC, deviceProfileForRoute(DeviceProfile.PMA110, externalRoute = true))
        assertEquals(DeviceProfile.PMA110, deviceProfileForRoute(DeviceProfile.PMA110, externalRoute = false))
    }

    // ---- native log flavor (owner decision 2026-10-15: the phone's OWN log, per phone) ----

    /** Every sourced Build.MODEL (Google Play supported-devices list) → its maker log. */
    private val nativeLogTable = mapOf(
        // OPPO O-Log2 — Find X9 Ultra.
        "PMA110" to ColorTransfer.OLOG2, "CPH2841" to ColorTransfer.OLOG2, "PMA120" to ColorTransfer.OLOG2,
        // OPPO O-Log V1 — Find X8 Ultra, OnePlus 15, Find X9, Find X9 Pro.
        "PKJ110" to ColorTransfer.OLOG, "PKU110" to ColorTransfer.OLOG,
        "PLK110" to ColorTransfer.OLOG, "CPH2745" to ColorTransfer.OLOG,
        "CPH2747" to ColorTransfer.OLOG, "CPH2749" to ColorTransfer.OLOG,
        "PLJ110" to ColorTransfer.OLOG, "CPH2797" to ColorTransfer.OLOG, "OPG07" to ColorTransfer.OLOG,
        "PLG110" to ColorTransfer.OLOG, "PLG120" to ColorTransfer.OLOG, "CPH2791" to ColorTransfer.OLOG,
        // Xiaomi Mi-Log — Xiaomi 15 Ultra, Xiaomi 17 Ultra.
        "25010PN30C" to ColorTransfer.MILOG, "25010PN30G" to ColorTransfer.MILOG,
        "25010PN30I" to ColorTransfer.MILOG, "25019PNF3C" to ColorTransfer.MILOG,
        "2512BPNDAC" to ColorTransfer.MILOG, "2512BPNDAG" to ColorTransfer.MILOG,
        "2512BPNDAI" to ColorTransfer.MILOG, "25128PNA1C" to ColorTransfer.MILOG,
    )

    @Test
    fun `each sourced model resolves to its maker's log, case- and whitespace-insensitively`() {
        for ((model, flavor) in nativeLogTable) {
            for (spelling in listOf(model, model.lowercase(), " $model ")) {
                assertEquals(spelling, flavor, DeviceProfile.resolve(spelling).nativeLogFlavor)
            }
        }
    }

    @Test
    fun `only Find X9 Ultra codes take the PMA110 quirks and every other mapped model is GENERIC plus its flavor`() {
        for ((model, flavor) in nativeLogTable) {
            val p = DeviceProfile.resolve(model)
            if (model == "PMA110" || model == "CPH2841" || model == "PMA120") {
                assertEquals(model, DeviceProfile.PMA110, p)
            } else {
                assertEquals(model, DeviceProfile.GENERIC.copy(nativeLogFlavor = flavor), p)
            }
        }
        assertEquals(ColorTransfer.OLOG2, DeviceProfile.PMA110.nativeLogFlavor)
    }

    @Test
    fun `unknown models, look-alike siblings and null have no native log`() {
        // OnePlus 15R (CPH2767/2769/2771), Find X9s (CPH2873) / X9s Pro (PME110) are different phones.
        for (model in listOf(null, "", "SM-S928B", "V2324A", "PMA111", "CPH2767", "CPH2769", "CPH2771", "CPH2873", "PME110")) {
            assertNull("$model", DeviceProfile.resolve(model).nativeLogFlavor)
            assertEquals("$model", DeviceProfile.GENERIC, DeviceProfile.resolve(model))
        }
        assertNull(DeviceProfile.GENERIC.nativeLogFlavor)
    }

    @Test
    fun `every native flavor resolved is a native-log transfer, and every native-log transfer has a phone`() {
        val flavors = nativeLogTable.values.toSet()
        assertTrue(flavors.all { it.isNativeLog })
        assertEquals(ColorTransfer.entries.filter { it.isNativeLog }.toSet(), flavors)
    }

    @Test
    fun `an external camera has no native log flavor`() {
        for ((model, _) in nativeLogTable) {
            assertNull(model, deviceProfileForRoute(DeviceProfile.resolve(model), externalRoute = true).nativeLogFlavor)
        }
    }
}
