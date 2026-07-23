package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAvailabilityTest {

    @Test
    fun `fully advertised choices all survive request normalization`() {
        val caps = fullCaps()
        val controls = ManualControls(customWbGains = WbGains(2f, 1f, 1f, 3f))
        val availability = controlAvailability(caps, controls)

        assertEquals(ExposureMode.entries, availability.exposureModes)
        assertEquals(FocusMode.entries, availability.focusModes)
        assertEquals(WbMode.entries, availability.wbModes)
        assertEquals(Antibanding.entries, availability.antibandingModes)
        assertEquals(MeteringMode.entries, availability.meteringModes)
        assertEquals(ProcessingLevel.entries, availability.edgeModes)
        assertEquals(ProcessingLevel.entries, availability.noiseReductionModes)
        assertEquals(ColorEffect.entries, availability.colorEffects)
        assertEquals(FlashMode.entries, availability.flashModes)

        availability.exposureModes.forEach { mode ->
            assertEquals(mode, controls.copy(exposureMode = mode).normalizedFor(caps).exposureMode)
        }
        availability.focusModes.forEach { mode ->
            assertEquals(mode, controls.copy(focusMode = mode).normalizedFor(caps).focusMode)
        }
        availability.wbModes.forEach { mode ->
            assertEquals(mode, controls.copy(wbMode = mode).normalizedFor(caps).wbMode)
        }
        availability.antibandingModes.forEach { mode ->
            assertEquals(mode, controls.copy(antibanding = mode).normalizedFor(caps).antibanding)
        }
        availability.meteringModes.forEach { mode ->
            assertEquals(mode, controls.copy(meteringMode = mode).normalizedFor(caps).meteringMode)
        }
        availability.edgeModes.forEach { mode ->
            assertEquals(mode, controls.copy(edge = mode).normalizedFor(caps).edge)
        }
        availability.noiseReductionModes.forEach { mode ->
            assertEquals(mode, controls.copy(noiseReduction = mode).normalizedFor(caps).noiseReduction)
        }
        availability.colorEffects.forEach { mode ->
            assertEquals(mode, controls.copy(colorEffect = mode).normalizedFor(caps).colorEffect)
        }
        availability.flashModes.forEach { mode ->
            assertEquals(mode, controls.copy(flash = mode).normalizedFor(caps).flash)
        }
    }

    @Test
    fun `sparse exact matrices expose only round trippable choices`() {
        val caps = CameraControlCapabilities(
            afModes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_MACRO),
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON),
            antibandingModes = intArrayOf(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ),
            effectModes = intArrayOf(CameraMetadata.CONTROL_EFFECT_MODE_SEPIA),
            edgeModes = intArrayOf(CameraMetadata.EDGE_MODE_FAST),
            noiseReductionModes = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY),
        )
        val normalized = ManualControls().normalizedFor(caps)
        val availability = controlAvailability(caps, normalized)

        assertEquals(listOf(ExposureMode.PROGRAM), availability.exposureModes)
        assertEquals(listOf(FocusMode.MACRO), availability.focusModes)
        assertEquals(listOf(WbMode.DAYLIGHT), availability.wbModes)
        assertEquals(listOf(Antibanding.HZ50), availability.antibandingModes)
        assertEquals(listOf(MeteringMode.MATRIX), availability.meteringModes)
        assertEquals(listOf(ProcessingLevel.FAST), availability.edgeModes)
        assertEquals(listOf(ProcessingLevel.HIGH_QUALITY), availability.noiseReductionModes)
        assertEquals(listOf(ColorEffect.SEPIA), availability.colorEffects)
        assertEquals(listOf(FlashMode.OFF), availability.flashModes)
        assertEquals(normalized, normalized.normalizedFor(caps))
    }

    @Test
    fun `empty mode arrays retain neutral display values and deny capability entries`() {
        val caps = CameraControlCapabilities(
            supportsManualFocus = true,
            supportsManualSensor = true,
            supportsManualPostProcessing = true,
            hasFocusDistanceRange = true,
            hasIsoRange = true,
            hasExposureTimeRange = true,
            hasEvCompensationRange = true,
            hasZoomRatioRange = false,
            flashAvailable = true,
            maxAeRegions = 0,
            maxAfRegions = 0,
        )
        val normalized = ManualControls(
            focusMode = FocusMode.MANUAL,
            exposureMode = ExposureMode.MANUAL,
            wbMode = WbMode.MANUAL,
            antibanding = Antibanding.HZ60,
            meteringMode = MeteringMode.SPOT,
            edge = ProcessingLevel.HIGH_QUALITY,
            noiseReduction = ProcessingLevel.FAST,
            colorEffect = ColorEffect.MONO,
            flash = FlashMode.TORCH,
        ).normalizedFor(caps)
        val availability = controlAvailability(caps, normalized)

        assertEquals(listOf(ExposureMode.PROGRAM), availability.exposureModes)
        assertEquals(listOf(FocusMode.CONTINUOUS), availability.focusModes)
        assertEquals(listOf(WbMode.AUTO), availability.wbModes)
        assertEquals(listOf(Antibanding.AUTO), availability.antibandingModes)
        assertEquals(listOf(MeteringMode.MATRIX), availability.meteringModes)
        assertEquals(listOf(ProcessingLevel.OFF), availability.edgeModes)
        assertEquals(listOf(ProcessingLevel.OFF), availability.noiseReductionModes)
        assertEquals(listOf(ColorEffect.NONE), availability.colorEffects)
        assertEquals(listOf(FlashMode.OFF), availability.flashModes)
        assertFalse(availability.manualFocusDialEnabled)
        assertFalse(availability.shutterDialEnabled)
        assertFalse(availability.isoDialEnabled)
        assertFalse(availability.wbDialEnabled)
        assertFalse(availability.evDialEnabled)
        assertFalse(availability.zoomDialEnabled)
        assertFalse(availability.afSpotSizeEnabled)
        assertFalse(availability.afLockEnabled)
        assertFalse(availability.aeLockEnabled)
        assertFalse(availability.awbLockEnabled)
        assertFalse(availability.customWbCaptureEnabled)
    }

    @Test
    fun `region and lock admissions follow exact current request paths`() {
        val caps = fullCaps()
        val auto = controlAvailability(caps, ManualControls())
        assertTrue(auto.afSpotSizeEnabled)
        assertTrue(auto.afLockEnabled)
        assertTrue(auto.aeLockEnabled)
        assertTrue(auto.awbLockEnabled)
        assertTrue(auto.customWbCaptureEnabled)

        val manual = controlAvailability(
            caps,
            ManualControls(
                focusMode = FocusMode.MANUAL,
                exposureMode = ExposureMode.MANUAL,
                wbMode = WbMode.MANUAL,
            ),
        )
        assertFalse(manual.afSpotSizeEnabled)
        assertFalse(manual.afLockEnabled)
        assertFalse(manual.aeLockEnabled)
        assertFalse(manual.awbLockEnabled)
        assertFalse(manual.customWbCaptureEnabled)
        assertEquals(listOf(FlashMode.OFF, FlashMode.TORCH), manual.flashModes)
        manual.flashModes.forEach { mode ->
            assertEquals(
                mode,
                ManualControls(exposureMode = ExposureMode.MANUAL, flash = mode)
                    .normalizedFor(caps)
                    .flash,
            )
        }
    }

    @Test
    fun `custom white balance requires unlocked auto metering`() {
        val caps = fullCaps()

        assertTrue(controlAvailability(caps, ManualControls()).customWbCaptureEnabled)
        assertFalse(
            controlAvailability(caps, ManualControls(awbLock = true)).customWbCaptureEnabled,
        )
        assertFalse(
            controlAvailability(caps, ManualControls(wbMode = WbMode.DAYLIGHT))
                .customWbCaptureEnabled,
        )
        assertFalse(
            controlAvailability(caps, ManualControls(wbMode = WbMode.MANUAL))
                .customWbCaptureEnabled,
        )
        assertFalse(
            controlAvailability(
                caps,
                ManualControls(
                    wbMode = WbMode.CUSTOM,
                    customWbGains = WbGains(2f, 1f, 1f, 3f),
                ),
            ).customWbCaptureEnabled,
        )
    }

    @Test
    fun `hi-res route fact projects only from advertised standalone caps`() {
        // Cycle-6 architect F3: the ProSheet row must see the route fact through the projection,
        // so an absent-caps route and a non-advertising route both deny it there.
        assertFalse(controlAvailability(null, ManualControls()).hiResAdvertisedStandalone)
        assertFalse(controlAvailability(fullCaps(), ManualControls()).hiResAdvertisedStandalone)
        assertTrue(
            controlAvailability(fullCaps().copy(hiResAdvertisedStandalone = true), ManualControls())
                .hiResAdvertisedStandalone,
        )
    }

    @Test
    fun `hi-res toggle enablement rides the shared admission predicate plus the recording lock`() {
        for (routeFact in booleanArrayOf(false, true))
            for (video in booleanArrayOf(false, true))
                for (fourThree in booleanArrayOf(false, true))
                    for (recording in booleanArrayOf(false, true)) {
                        val aspect = if (fourThree) AspectRatio.W4_3 else AspectRatio.W16_9
                        val availability = controlAvailability(
                            fullCaps().copy(hiResAdvertisedStandalone = routeFact),
                            ManualControls(),
                        )
                        // Exactly [hiResAdmitted]'s truth table over the folded route fact, with
                        // the recording lock as the row's one extra axis — no hidden interaction.
                        assertEquals(
                            "route=$routeFact video=$video aspect=$aspect recording=$recording",
                            !recording && routeFact && !video && fourThree,
                            hiResToggleEnabled(availability, video, aspect, recording),
                        )
                    }
    }

    @Test
    fun `EV dial follows the app-side loop in shutter and ISO priority`() {
        val caps = fullCaps()

        // S/ISO priority: the app-side loop drives the other side, so EV has a real lane whenever
        // manual AE exists on the route — not the HAL compensation key, but the same dial truth.
        listOf(ExposureMode.SHUTTER, ExposureMode.ISO).forEach { mode ->
            val normalized = ManualControls(exposureMode = mode).normalizedFor(caps)
            assertEquals(mode, normalized.exposureMode)
            assertTrue(controlAvailability(caps, normalized).evDialEnabled)
        }

        // MANUAL owns both sides of the exposure; the EV dial has nothing to drive.
        val manual = ManualControls(exposureMode = ExposureMode.MANUAL).normalizedFor(caps)
        assertFalse(controlAvailability(caps, manual).evDialEnabled)
    }

    private fun fullCaps() = CameraControlCapabilities(
        supportsManualFocus = true,
        supportsManualSensor = true,
        supportsManualPostProcessing = true,
        hasFocusDistanceRange = true,
        hasIsoRange = true,
        hasExposureTimeRange = true,
        hasEvCompensationRange = true,
        hasZoomRatioRange = true,
        flashAvailable = true,
        afModes = intArrayOf(
            CameraMetadata.CONTROL_AF_MODE_OFF,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_MACRO,
        ),
        awbModes = intArrayOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE,
            CameraMetadata.CONTROL_AWB_MODE_OFF,
        ),
        aeModes = intArrayOf(
            CameraMetadata.CONTROL_AE_MODE_OFF,
            CameraMetadata.CONTROL_AE_MODE_ON,
            CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH,
            CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
        ),
        maxAeRegions = 1,
        maxAfRegions = 1,
        antibandingModes = intArrayOf(
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO,
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ,
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ,
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF,
        ),
        effectModes = intArrayOf(
            CameraMetadata.CONTROL_EFFECT_MODE_OFF,
            CameraMetadata.CONTROL_EFFECT_MODE_MONO,
            CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE,
            CameraMetadata.CONTROL_EFFECT_MODE_SEPIA,
            CameraMetadata.CONTROL_EFFECT_MODE_AQUA,
            CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE,
        ),
        edgeModes = intArrayOf(
            CameraMetadata.EDGE_MODE_OFF,
            CameraMetadata.EDGE_MODE_FAST,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY,
        ),
        noiseReductionModes = intArrayOf(
            CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        ),
    )
}
