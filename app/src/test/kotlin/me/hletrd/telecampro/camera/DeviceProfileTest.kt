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
            // White balance, measured 2026-10-27 (WhiteBalance.kt): presets ignored by the HAL, and a
            // TRANSFORM_MATRIX still must ride the session's stabilization mode.
            assertTrue(model, p.awbPresetModesIgnored)
            assertTrue(model, p.matrixWbStillKeepsSessionStabilization)
            // ...and the FACE_PRIORITY scene must stand down while WB is app-owned.
            assertTrue(model, p.facePrioritySceneOverridesManualWb)
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
            assertFalse("$model", p.awbPresetModesIgnored)
            assertFalse("$model", p.matrixWbStillKeepsSessionStabilization)
            assertFalse("$model", p.facePrioritySceneOverridesManualWb)
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

    // ---- the per-phone maker-log table is gone (2026-10-26): the model seam maps HAL quirks only ----

    @Test
    fun `the former maker-log models are plain GENERIC, and look-alike siblings and null are too`() {
        // Find X10 / X10 Pro Max, Find X8 Ultra, OnePlus 15, Find X9 / X9 Pro, Xiaomi 15 / 17 Ultra —
        // they used to resolve to GENERIC plus their maker's curve; with the curves removed, nothing
        // distinguishes them from any other unmeasured handset.
        val formerMakerLogModels = listOf(
            "PMW110", "CPH2911", "OPG09", "PMX110", "CPH2913",
            "PKJ110", "PKU110", "PLK110", "CPH2745", "CPH2747", "CPH2749",
            "PLJ110", "CPH2797", "OPG07", "PLG110", "PLG120", "CPH2791",
            "25010PN30C", "25010PN30G", "25010PN30I", "25019PNF3C",
            "2512BPNDAC", "2512BPNDAG", "2512BPNDAI", "25128PNA1C",
        )
        val unrelated = listOf(null, "", "SM-S928B", "V2324A", "PMA111", "CPH2767", "CPH2873", "PME110")
        for (model in formerMakerLogModels + unrelated) {
            assertEquals("$model", DeviceProfile.GENERIC, DeviceProfile.resolve(model))
        }
        // Only the Find X9 Ultra codes take the PMA110 set, in any case or padding.
        for (model in listOf("PMA110", "CPH2841", "PMA120")) {
            for (spelling in listOf(model, model.lowercase(), " $model ")) {
                assertEquals(spelling, DeviceProfile.PMA110, DeviceProfile.resolve(spelling))
            }
        }
    }

    @Test
    fun `the Log EI offset is withheld on the PMA110 profile only`() {
        // Measured 2026-10-15: PMA110's ISP output is not exposure-linear under the programmed curve.
        assertFalse(DeviceProfile.PMA110.nativeLogEiSupported)
        for (model in listOf("PMA110", "CPH2841", "PMA120")) assertFalse(model, DeviceProfile.resolve(model).nativeLogEiSupported)
        assertTrue(DeviceProfile.GENERIC.nativeLogEiSupported)
        assertTrue(DeviceProfile.resolve("CPH2791").nativeLogEiSupported)
    }

    @Test
    fun `only the measured profile vouches for the native log curve`() {
        // Only PMA110's curve has been measured; an unmeasured (advertised) curve keeps working and
        // earns the Gamma warning.
        for (model in listOf("PMA110", "CPH2841", "PMA120")) assertTrue(model, DeviceProfile.resolve(model).nativeLogCurveMeasured)
        assertFalse(DeviceProfile.GENERIC.nativeLogCurveMeasured)
        assertFalse(DeviceProfile.resolve("SM-F741N").nativeLogCurveMeasured)
    }
}
