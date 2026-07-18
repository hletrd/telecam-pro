package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCapabilityNormalizationTest {

    @Test
    fun `route normalization makes a sparse fast recall stable before ready`() {
        val caps = CameraControlCapabilities(
            supportsManualFocus = false,
            supportsManualSensor = false,
            supportsManualPostProcessing = false,
            flashAvailable = true,
            afModes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO),
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON),
            maxAeRegions = 0,
            antibandingModes = intArrayOf(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ),
            effectModes = intArrayOf(CameraMetadata.CONTROL_EFFECT_MODE_MONO),
            edgeModes = intArrayOf(CameraMetadata.EDGE_MODE_FAST),
            noiseReductionModes = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY),
        )
        val recalled = ManualControls(
            focusMode = FocusMode.MANUAL,
            afLock = true,
            exposureMode = ExposureMode.MANUAL,
            wbMode = WbMode.MANUAL,
            flash = FlashMode.ON,
            antibanding = Antibanding.AUTO,
            meteringMode = MeteringMode.SPOT,
            edge = ProcessingLevel.OFF,
            noiseReduction = ProcessingLevel.OFF,
            colorEffect = ColorEffect.SEPIA,
            zoomRatio = 19f,
        )

        val normalized = normalizeControlsForRoute(
            requested = recalled,
            capabilities = caps,
            mode = CaptureMode.PHOTO,
            teleconverter = false,
            capsLower = 1f,
            capsUpper = 8f,
        )

        assertEquals(FocusMode.AUTO, normalized.focusMode)
        assertFalse(normalized.afLock)
        assertEquals(ExposureMode.PROGRAM, normalized.exposureMode)
        assertEquals(WbMode.DAYLIGHT, normalized.wbMode)
        assertEquals(FlashMode.OFF, normalized.flash)
        assertEquals(Antibanding.HZ60, normalized.antibanding)
        assertEquals(MeteringMode.MATRIX, normalized.meteringMode)
        assertEquals(ProcessingLevel.FAST, normalized.edge)
        assertEquals(ProcessingLevel.HIGH_QUALITY, normalized.noiseReduction)
        assertEquals(ColorEffect.MONO, normalized.colorEffect)
        assertEquals(8f, normalized.zoomRatio)
        assertEquals(
            normalized,
            normalizeControlsForRoute(
                requested = normalized,
                capabilities = caps,
                mode = CaptureMode.PHOTO,
                teleconverter = false,
                capsLower = 1f,
                capsUpper = 8f,
            ),
        )
    }

    @Test
    fun `sparse arrays retain every exactly advertised requested mode`() {
        val caps = CameraControlCapabilities(
            supportsManualFocus = true,
            supportsManualSensor = true,
            supportsManualPostProcessing = true,
            flashAvailable = true,
            afModes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_MACRO),
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH),
            maxAeRegions = 1,
            maxAfRegions = 1,
            antibandingModes = intArrayOf(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ),
            effectModes = intArrayOf(CameraMetadata.CONTROL_EFFECT_MODE_SEPIA),
            edgeModes = intArrayOf(CameraMetadata.EDGE_MODE_FAST),
            noiseReductionModes = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY),
        )
        val requested = ManualControls(
            focusMode = FocusMode.MACRO,
            wbMode = WbMode.DAYLIGHT,
            exposureMode = ExposureMode.PROGRAM,
            flash = FlashMode.AUTO,
            antibanding = Antibanding.HZ50,
            meteringMode = MeteringMode.SPOT,
            edge = ProcessingLevel.FAST,
            noiseReduction = ProcessingLevel.HIGH_QUALITY,
            colorEffect = ColorEffect.SEPIA,
        )

        val normalized = requested.normalizedFor(caps)

        assertEquals(FocusMode.MACRO, normalized.focusMode)
        assertEquals(WbMode.DAYLIGHT, normalized.wbMode)
        assertEquals(ExposureMode.PROGRAM, normalized.exposureMode)
        assertFalse(normalized.programAppSide)
        assertEquals(FlashMode.AUTO, normalized.flash)
        assertEquals(Antibanding.HZ50, normalized.antibanding)
        assertEquals(MeteringMode.SPOT, normalized.meteringMode)
        assertEquals(ProcessingLevel.FAST, normalized.edge)
        assertEquals(ProcessingLevel.HIGH_QUALITY, normalized.noiseReduction)
        assertEquals(ColorEffect.SEPIA, normalized.colorEffect)
    }

    @Test
    fun `sparse arrays replace unsupported selections with known advertised fallbacks`() {
        val caps = CameraControlCapabilities(
            flashAvailable = true,
            afModes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO),
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON),
            maxAeRegions = 1,
            antibandingModes = intArrayOf(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ),
            effectModes = intArrayOf(CameraMetadata.CONTROL_EFFECT_MODE_MONO),
            edgeModes = intArrayOf(CameraMetadata.EDGE_MODE_HIGH_QUALITY),
            noiseReductionModes = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_FAST),
        )
        val requested = ManualControls(
            focusMode = FocusMode.MANUAL,
            afLock = true,
            exposureMode = ExposureMode.MANUAL,
            wbMode = WbMode.MANUAL,
            flash = FlashMode.ON,
            antibanding = Antibanding.AUTO,
            meteringMode = MeteringMode.CENTER,
            edge = ProcessingLevel.OFF,
            noiseReduction = ProcessingLevel.OFF,
            colorEffect = ColorEffect.SEPIA,
        )

        val normalized = requested.normalizedFor(caps)

        assertEquals(FocusMode.AUTO, normalized.focusMode)
        assertFalse(normalized.afLock)
        assertEquals(ExposureMode.PROGRAM, normalized.exposureMode)
        assertFalse(normalized.programAppSide)
        assertEquals(WbMode.CLOUDY, normalized.wbMode)
        assertEquals(FlashMode.OFF, normalized.flash)
        assertEquals(Antibanding.HZ60, normalized.antibanding)
        assertEquals(MeteringMode.CENTER, normalized.meteringMode)
        assertEquals(ProcessingLevel.HIGH_QUALITY, normalized.edge)
        assertEquals(ProcessingLevel.FAST, normalized.noiseReduction)
        assertEquals(ColorEffect.MONO, normalized.colorEffect)
    }

    @Test
    fun `absent mode arrays normalize to neutral controls whose request keys are omitted`() {
        val requested = ManualControls(
            focusMode = FocusMode.MACRO,
            afLock = true,
            exposureMode = ExposureMode.ISO,
            programAppSide = true,
            wbMode = WbMode.SHADE,
            flash = FlashMode.TORCH,
            antibanding = Antibanding.HZ60,
            meteringMode = MeteringMode.SPOT,
            edge = ProcessingLevel.HIGH_QUALITY,
            noiseReduction = ProcessingLevel.FAST,
            colorEffect = ColorEffect.POSTERIZE,
        )

        val normalized = requested.normalizedFor(CameraControlCapabilities())

        assertEquals(FocusMode.CONTINUOUS, normalized.focusMode)
        assertFalse(normalized.afLock)
        assertEquals(ExposureMode.PROGRAM, normalized.exposureMode)
        assertFalse(normalized.programAppSide)
        assertEquals(WbMode.AUTO, normalized.wbMode)
        assertEquals(FlashMode.OFF, normalized.flash)
        assertEquals(Antibanding.AUTO, normalized.antibanding)
        assertEquals(MeteringMode.MATRIX, normalized.meteringMode)
        assertEquals(ProcessingLevel.OFF, normalized.edge)
        assertEquals(ProcessingLevel.OFF, normalized.noiseReduction)
        assertEquals(ColorEffect.NONE, normalized.colorEffect)
    }

    @Test
    fun `manual and custom WB require the advertised AWB off value`() {
        val autoOnly = CameraControlCapabilities(
            supportsManualPostProcessing = true,
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
        )
        val offOnly = CameraControlCapabilities(
            supportsManualPostProcessing = true,
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_OFF),
        )

        assertEquals(WbMode.AUTO, ManualControls(wbMode = WbMode.MANUAL).normalizedFor(autoOnly).wbMode)
        assertEquals(WbMode.MANUAL, ManualControls(wbMode = WbMode.MANUAL).normalizedFor(offOnly).wbMode)
        assertEquals(
            WbMode.CUSTOM,
            ManualControls(wbMode = WbMode.CUSTOM, customWbGains = WbGains(2f, 1f, 1f, 1.5f))
                .normalizedFor(offOnly)
                .wbMode,
        )
        assertEquals(WbMode.MANUAL, ManualControls(wbMode = WbMode.CUSTOM).normalizedFor(offOnly).wbMode)
    }

    @Test
    fun `flash fallback never enables a different firing mode`() {
        val autoFlashOnly = CameraControlCapabilities(
            flashAvailable = true,
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH),
        )
        val aeOnOnly = CameraControlCapabilities(
            flashAvailable = true,
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON),
        )

        assertEquals(FlashMode.OFF, ManualControls(flash = FlashMode.OFF).normalizedFor(autoFlashOnly).flash)
        assertEquals(FlashMode.OFF, ManualControls(flash = FlashMode.AUTO).normalizedFor(aeOnOnly).flash)
        assertEquals(FlashMode.OFF, ManualControls(flash = FlashMode.ON).normalizedFor(aeOnOnly).flash)
    }

    @Test
    fun `AE off only route uses app side Program and preserves requested torch`() {
        val caps = CameraControlCapabilities(
            supportsManualSensor = true,
            hasIsoRange = true,
            hasExposureTimeRange = true,
            flashAvailable = true,
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_OFF),
        )

        val off = ManualControls(programAppSide = false, flash = FlashMode.OFF).normalizedFor(caps)
        val torch = ManualControls(programAppSide = false, flash = FlashMode.TORCH).normalizedFor(caps)
        val unsupportedAuto = ManualControls(programAppSide = false, flash = FlashMode.AUTO).normalizedFor(caps)

        assertTrue(off.programAppSide)
        assertEquals(FlashMode.OFF, off.flash)
        assertTrue(torch.programAppSide)
        assertEquals(FlashMode.TORCH, torch.flash)
        assertTrue(unsupportedAuto.programAppSide)
        assertEquals(FlashMode.OFF, unsupportedAuto.flash)
    }

    @Test
    fun `zero region matrix admits AE and AF keys independently`() {
        assertEquals(MeteringRegionTargets(ae = false, af = false), meteringRegionTargets(0, 0, FocusMode.AUTO))
        assertEquals(MeteringRegionTargets(ae = true, af = false), meteringRegionTargets(1, 0, FocusMode.AUTO))
        assertEquals(MeteringRegionTargets(ae = false, af = true), meteringRegionTargets(0, 1, FocusMode.AUTO))
        assertEquals(MeteringRegionTargets(ae = true, af = true), meteringRegionTargets(1, 1, FocusMode.CONTINUOUS))
        assertEquals(MeteringRegionTargets(ae = true, af = false), meteringRegionTargets(1, 1, FocusMode.MANUAL))

        val spot = ManualControls(meteringMode = MeteringMode.SPOT)
        assertEquals(MeteringMode.MATRIX, spot.normalizedFor(CameraControlCapabilities(maxAeRegions = 0)).meteringMode)
        assertEquals(MeteringMode.SPOT, spot.normalizedFor(CameraControlCapabilities(maxAeRegions = 1)).meteringMode)
    }

    @Test
    fun `tap AF requires an advertised auto mode and a positive AF region maximum`() {
        val auto = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO)

        assertFalse(touchAfMayTrigger(true, 0, FocusMode.AUTO, auto))
        assertFalse(touchAfMayTrigger(true, 1, FocusMode.AUTO, IntArray(0)))
        assertFalse(touchAfMayTrigger(true, 1, FocusMode.MANUAL, auto))
        assertTrue(touchAfMayTrigger(true, 1, FocusMode.CONTINUOUS, auto))
    }

    @Test
    fun `request admission accepts only exact advertised integer values`() {
        val advertised = intArrayOf(
            CameraMetadata.EDGE_MODE_FAST,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY,
        )

        assertEquals(
            CameraMetadata.EDGE_MODE_HIGH_QUALITY,
            exactAdvertisedMode(CameraMetadata.EDGE_MODE_HIGH_QUALITY, advertised),
        )
        assertNull(exactAdvertisedMode(CameraMetadata.EDGE_MODE_OFF, advertised))
        assertNull(exactAdvertisedMode(CameraMetadata.EDGE_MODE_FAST, IntArray(0)))
    }

    @Test
    fun `numeric exposure normalizes into the caps range so the UI shows the applied value`() {
        // The caps seam carries the device-verified 4 s still ceiling; a persisted/recalled 6.3 s
        // (or a restored 5 s) must normalize DOWN so the OSD/ruler never display a shutter the
        // camera cannot execute — the request path always clamped, but the UI reads this field.
        val caps = CameraControlCapabilities(
            exposureTimeMinNs = 14_000L,
            exposureTimeMaxNs = 4_000_000_000L,
        )
        val restored = ManualControls(exposureTimeNs = 6_300_000_000L)
        assertEquals(4_000_000_000L, restored.normalizedFor(caps).exposureTimeNs)
        // In-range values pass through untouched; absent bounds leave the value alone.
        val inRange = ManualControls(exposureTimeNs = 800_000_000L)
        assertEquals(800_000_000L, inRange.normalizedFor(caps).exposureTimeNs)
        assertEquals(
            6_300_000_000L,
            ManualControls(exposureTimeNs = 6_300_000_000L)
                .normalizedFor(CameraControlCapabilities()).exposureTimeNs,
        )
    }
}
