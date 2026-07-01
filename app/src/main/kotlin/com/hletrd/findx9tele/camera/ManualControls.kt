package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector

/**
 * Full manual/pro capture parameters. Immutable; the ViewModel copies with updated fields and
 * re-applies to the repeating request and still/video captures.
 */
data class ManualControls(
    // Focus
    val focusMode: FocusMode = FocusMode.MANUAL,
    val focusDistanceDiopters: Float = 0f, // 0 = infinity
    // Exposure
    val autoExposure: Boolean = false,
    val iso: Int = 400,
    val exposureTimeNs: Long = 8_000_000L, // ~1/125 s
    val exposureCompensation: Int = 0,
    val aeLock: Boolean = false,
    val antibanding: Antibanding = Antibanding.AUTO,
    // White balance
    val autoWhiteBalance: Boolean = true,
    val wbKelvin: Int = 5200,
    val wbTint: Int = 0, // -50 (green) .. +50 (magenta)
    val awbLock: Boolean = false,
    // Processing
    val edge: ProcessingLevel = ProcessingLevel.HIGH_QUALITY,
    val noiseReduction: ProcessingLevel = ProcessingLevel.HIGH_QUALITY,
    val colorEffect: ColorEffect = ColorEffect.NONE,
    // Optics
    val flash: FlashMode = FlashMode.OFF,
    val oisEnabled: Boolean = true,
    val zoomRatio: Float = 1f,
    // Output
    val jpegQuality: Int = 95,
)

/**
 * Applies the parameters to a CaptureRequest, clamping to hardware ranges and honoring capability
 * gates. Also forces HAL video stabilization OFF (its gain is wrong for the afocal teleconverter —
 * our gyro EIS handles stabilization at the true focal length) and sets OIS per the user toggle.
 */
fun CaptureRequest.Builder.applyManualControls(c: ManualControls, caps: CameraCaps) {
    applyFocus(c, caps)
    applyExposure(c, caps)
    applyWhiteBalance(c, caps)
    applyProcessing(c, caps)
    applyFlash(c)
    applyZoom(c, caps)
    set(CaptureRequest.JPEG_QUALITY, c.jpegQuality.coerceIn(1, 100).toByte())

    // Stabilization
    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    if (caps.oisAvailable) {
        set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (c.oisEnabled) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )
    }
}

private fun CaptureRequest.Builder.applyFocus(c: ManualControls, caps: CameraCaps) {
    when (c.focusMode) {
        FocusMode.MANUAL -> if (caps.supportsManualFocus) {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistanceDiopters))
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        FocusMode.AUTO -> set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        FocusMode.CONTINUOUS -> set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        FocusMode.MACRO -> set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_MACRO)
    }
}

private fun CaptureRequest.Builder.applyExposure(c: ManualControls, caps: CameraCaps) {
    if (!c.autoExposure && caps.supportsManualSensor) {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        caps.isoRange?.let { set(CaptureRequest.SENSOR_SENSITIVITY, c.iso.coerceIn(it.lower, it.upper)) }
        caps.exposureTimeRange?.let {
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exposureTimeNs.coerceIn(it.lower, it.upper))
        }
    } else {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
        set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            c.exposureCompensation.coerceIn(caps.evRange.lower, caps.evRange.upper),
        )
    }
    set(
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
        when (c.antibanding) {
            Antibanding.AUTO -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
            Antibanding.HZ50 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            Antibanding.HZ60 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            Antibanding.OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
        },
    )
}

private fun CaptureRequest.Builder.applyWhiteBalance(c: ManualControls, caps: CameraCaps) {
    if (!c.autoWhiteBalance && caps.supportsManualPostProcessing) {
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinTintToRggbGains(c.wbKelvin, c.wbTint))
    } else {
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
    }
}

private fun CaptureRequest.Builder.applyProcessing(c: ManualControls, caps: CameraCaps) {
    if (caps.hasEffect(c.colorEffect.metadata)) {
        set(CaptureRequest.CONTROL_EFFECT_MODE, c.colorEffect.metadata)
    }
    if (caps.edgeModes.isNotEmpty()) set(CaptureRequest.EDGE_MODE, c.edge.edgeMetadata)
    if (caps.noiseReductionModes.isNotEmpty()) {
        set(CaptureRequest.NOISE_REDUCTION_MODE, c.noiseReduction.noiseMetadata)
    }
}

private fun CaptureRequest.Builder.applyFlash(c: ManualControls) {
    when (c.flash) {
        FlashMode.TORCH -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        FlashMode.ON -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
        FlashMode.OFF, FlashMode.AUTO -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
    }
}

private fun CaptureRequest.Builder.applyZoom(c: ManualControls, caps: CameraCaps) {
    caps.zoomRatioRange?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoomRatio.coerceIn(it.lower, it.upper)) }
}

private val ColorEffect.metadata: Int
    get() = when (this) {
        ColorEffect.NONE -> CameraMetadata.CONTROL_EFFECT_MODE_OFF
        ColorEffect.MONO -> CameraMetadata.CONTROL_EFFECT_MODE_MONO
        ColorEffect.NEGATIVE -> CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE
        ColorEffect.SEPIA -> CameraMetadata.CONTROL_EFFECT_MODE_SEPIA
        ColorEffect.AQUA -> CameraMetadata.CONTROL_EFFECT_MODE_AQUA
        ColorEffect.POSTERIZE -> CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE
    }

private val ProcessingLevel.edgeMetadata: Int
    get() = when (this) {
        ProcessingLevel.OFF -> CameraMetadata.EDGE_MODE_OFF
        ProcessingLevel.FAST -> CameraMetadata.EDGE_MODE_FAST
        ProcessingLevel.HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
    }

private val ProcessingLevel.noiseMetadata: Int
    get() = when (this) {
        ProcessingLevel.OFF -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
        ProcessingLevel.FAST -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        ProcessingLevel.HIGH_QUALITY -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
    }

/**
 * Approximate CCT + tint -> RGGB channel gains for manual WB.
 * Kelvin uses a Tanner-Helland blackbody approximation; tint shifts the green channel
 * (-50 greener .. +50 more magenta). Gains are normalized so the smallest is 1.0 (camera minimum).
 */
fun kelvinTintToRggbGains(kelvin: Int, tint: Int): RggbChannelVector {
    val t = (kelvin.coerceIn(1000, 40000)) / 100.0

    val r = if (t <= 66.0) 255.0 else (329.698727446 * Math.pow(t - 60.0, -0.1332047592)).coerceIn(0.0, 255.0)
    val g = if (t <= 66.0) {
        (99.4708025861 * Math.log(t) - 161.1195681661).coerceIn(0.0, 255.0)
    } else {
        (288.1221695283 * Math.pow(t - 60.0, -0.0755148492)).coerceIn(0.0, 255.0)
    }
    val b = when {
        t >= 66.0 -> 255.0
        t <= 19.0 -> 0.0
        else -> (138.5177312231 * Math.log(t - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
    }

    val rr = (r / 255.0).coerceAtLeast(1e-3)
    val gg = (g / 255.0).coerceAtLeast(1e-3)
    val bb = (b / 255.0).coerceAtLeast(1e-3)

    // Tint: >0 magenta (less green gain), <0 green (more green gain).
    val tintFactor = 1.0 - (tint.coerceIn(-50, 50) / 100.0)

    var gainR = 1.0 / rr
    var gainG = (1.0 / gg) * tintFactor
    var gainB = 1.0 / bb
    val minGain = minOf(gainR, gainG, gainB).coerceAtLeast(1e-3)
    gainR /= minGain; gainG /= minGain; gainB /= minGain

    return RggbChannelVector(gainR.toFloat(), gainG.toFloat(), gainG.toFloat(), gainB.toFloat())
}
