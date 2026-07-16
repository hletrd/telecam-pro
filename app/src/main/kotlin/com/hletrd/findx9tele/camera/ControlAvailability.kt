package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata

/**
 * One route-owned projection for every manual-control surface. It contains no Android values, so
 * sparse Camera2 matrices can be verified on the host and Compose never has to reinterpret broad
 * capability flags differently from request normalization.
 */
internal data class ControlAvailability(
    val exposureModes: List<ExposureMode>,
    val focusModes: List<FocusMode>,
    val wbModes: List<WbMode>,
    val antibandingModes: List<Antibanding>,
    val meteringModes: List<MeteringMode>,
    val edgeModes: List<ProcessingLevel>,
    val noiseReductionModes: List<ProcessingLevel>,
    val colorEffects: List<ColorEffect>,
    val flashModes: List<FlashMode>,
    val manualFocusDialEnabled: Boolean,
    val shutterDialEnabled: Boolean,
    val isoDialEnabled: Boolean,
    val wbDialEnabled: Boolean,
    val evDialEnabled: Boolean,
    val zoomDialEnabled: Boolean,
    val afSpotSizeEnabled: Boolean,
    val afLockEnabled: Boolean,
    val aeLockEnabled: Boolean,
    val awbLockEnabled: Boolean,
    val customWbCaptureEnabled: Boolean,
)

/**
 * Builds the UI contract from the same exact integer modes and scalar range facts used at the
 * request boundary. Neutral singleton choices keep a sparse/empty route truthful and renderable;
 * capability-dependent entry points remain disabled until real caps arrive.
 */
internal fun controlAvailability(
    caps: CameraControlCapabilities?,
    controls: ManualControls,
): ControlAvailability {
    if (caps == null) {
        return ControlAvailability(
            exposureModes = listOf(controls.exposureMode),
            focusModes = listOf(controls.focusMode),
            wbModes = listOf(controls.wbMode),
            antibandingModes = listOf(controls.antibanding),
            meteringModes = listOf(controls.meteringMode),
            edgeModes = listOf(controls.edge),
            noiseReductionModes = listOf(controls.noiseReduction),
            colorEffects = listOf(controls.colorEffect),
            flashModes = listOf(controls.flash),
            manualFocusDialEnabled = false,
            shutterDialEnabled = false,
            isoDialEnabled = false,
            wbDialEnabled = false,
            evDialEnabled = false,
            zoomDialEnabled = false,
            afSpotSizeEnabled = false,
            afLockEnabled = false,
            aeLockEnabled = false,
            awbLockEnabled = false,
            customWbCaptureEnabled = false,
        )
    }

    val normalized = controls.normalizedFor(caps)
    val manualFocusAvailable = caps.supportsManualFocus && caps.hasFocusDistanceRange &&
        caps.afModes.has(CameraMetadata.CONTROL_AF_MODE_OFF)
    val manualAeAvailable = caps.supportsManualSensor && caps.hasIsoRange &&
        caps.hasExposureTimeRange && caps.aeModes.has(CameraMetadata.CONTROL_AE_MODE_OFF)
    val manualWbAvailable = caps.supportsManualPostProcessing &&
        caps.awbModes.has(CameraMetadata.CONTROL_AWB_MODE_OFF)

    val focusModes = FocusMode.entries.filter { mode ->
        caps.afModes.has(mode.afMetadata) && (mode != FocusMode.MANUAL || manualFocusAvailable)
    }.orNeutral(FocusMode.CONTINUOUS)
    val wbModes = WbMode.entries.filter { mode ->
        caps.awbModes.has(mode.awbMetadata) && when (mode) {
            WbMode.MANUAL -> manualWbAvailable
            WbMode.CUSTOM -> manualWbAvailable && normalized.customWbGains != null
            else -> true
        }
    }.orNeutral(WbMode.AUTO)

    val exposureModes = buildList {
        add(ExposureMode.PROGRAM)
        if (manualAeAvailable) addAll(
            listOf(ExposureMode.SHUTTER, ExposureMode.ISO, ExposureMode.MANUAL),
        )
    }
    val meteringModes = buildList {
        add(MeteringMode.MATRIX)
        if (caps.maxAeRegions > 0) addAll(listOf(MeteringMode.CENTER, MeteringMode.SPOT))
    }
    val manualExposureOwnsFlash = normalized.exposureMode != ExposureMode.PROGRAM
    val flashModes = FlashMode.entries.filter { mode ->
        when (mode) {
            FlashMode.OFF -> true
            FlashMode.AUTO, FlashMode.ON -> !manualExposureOwnsFlash && caps.flashAvailable &&
                caps.aeModes.has(mode.autoAeMetadata)
            FlashMode.TORCH -> caps.flashAvailable &&
                (caps.aeModes.has(CameraMetadata.CONTROL_AE_MODE_ON) || manualAeAvailable)
        }
    }
    val evPathAvailable = when (normalized.exposureMode) {
        ExposureMode.MANUAL -> false
        ExposureMode.SHUTTER, ExposureMode.ISO -> manualAeAvailable
        ExposureMode.PROGRAM -> if (normalized.programAppSide) manualAeAvailable
        else caps.aeModes.has(normalized.flash.autoAeMetadata)
    }

    return ControlAvailability(
        exposureModes = exposureModes,
        focusModes = focusModes,
        wbModes = wbModes,
        antibandingModes = Antibanding.entries
            .filter { caps.antibandingModes.has(it.antibandingMetadata) }
            .orNeutral(Antibanding.AUTO),
        meteringModes = meteringModes,
        edgeModes = ProcessingLevel.entries
            .filter { caps.edgeModes.has(it.edgeMetadata) }
            .orNeutral(ProcessingLevel.OFF),
        noiseReductionModes = ProcessingLevel.entries
            .filter { caps.noiseReductionModes.has(it.noiseMetadata) }
            .orNeutral(ProcessingLevel.OFF),
        colorEffects = ColorEffect.entries
            .filter { caps.effectModes.has(it.metadata) }
            .orNeutral(ColorEffect.NONE),
        flashModes = flashModes,
        manualFocusDialEnabled = manualFocusAvailable,
        shutterDialEnabled = manualAeAvailable,
        isoDialEnabled = manualAeAvailable,
        wbDialEnabled = manualWbAvailable,
        evDialEnabled = caps.hasEvCompensationRange && evPathAvailable,
        zoomDialEnabled = caps.hasZoomRatioRange,
        afSpotSizeEnabled = caps.maxAfRegions > 0 && normalized.focusMode != FocusMode.MANUAL,
        afLockEnabled = manualFocusAvailable && normalized.focusMode != FocusMode.MANUAL,
        aeLockEnabled = normalized.exposureMode == ExposureMode.PROGRAM &&
            !normalized.programAppSide && caps.aeModes.has(normalized.flash.autoAeMetadata),
        awbLockEnabled = normalized.wbMode == WbMode.AUTO &&
            caps.awbModes.has(CameraMetadata.CONTROL_AWB_MODE_AUTO),
        // A grey-card sample must come from the HAL's live, unlocked AUTO path. Manual/preset/
        // locked results merely echo already-requested gains and cannot truthfully be relabeled as
        // a new measurement, even though AWB_OFF is available for applying the captured gains.
        customWbCaptureEnabled = manualWbAvailable && normalized.wbMode == WbMode.AUTO &&
            !normalized.awbLock && caps.awbModes.has(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    )
}

private fun IntArray.has(value: Int): Boolean = exactAdvertisedMode(value, this) != null

private fun <T> List<T>.orNeutral(neutral: T): List<T> = ifEmpty { listOf(neutral) }
