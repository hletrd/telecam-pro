package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector

/**
 * All manual capture parameters. Immutable; the ViewModel copies with updated fields and re-applies.
 *
 * @param focusDistanceDiopters 0 = infinity, up to CameraCaps.minFocusDistanceDiopters (closest).
 * @param exposureTimeNs        shutter time in nanoseconds.
 * @param wbKelvin              correlated color temperature for manual white balance.
 */
data class ManualControls(
    val autoFocus: Boolean = false,
    val focusDistanceDiopters: Float = 0f,
    val autoExposure: Boolean = false,
    val iso: Int = 400,
    val exposureTimeNs: Long = 8_000_000L, // ~1/125 s
    val exposureCompensation: Int = 0,
    val autoWhiteBalance: Boolean = true,
    val wbKelvin: Int = 5200,
)

/**
 * Writes the manual parameters onto a CaptureRequest.Builder, clamping to hardware ranges and
 * honoring capability gates (manual sensor / manual post-processing). Applied to both the repeating
 * preview request and still/video capture requests so WYSIWYG holds.
 */
fun CaptureRequest.Builder.applyManualControls(c: ManualControls, caps: CameraCaps) {
    // ---- Focus ----
    if (!c.autoFocus && caps.supportsManualFocus) {
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        val d = c.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistanceDiopters)
        set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
    } else {
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    // ---- Exposure ----
    if (!c.autoExposure && caps.supportsManualSensor) {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        caps.isoRange?.let { set(CaptureRequest.SENSOR_SENSITIVITY, c.iso.coerceIn(it.lower, it.upper)) }
        caps.exposureTimeRange?.let {
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exposureTimeNs.coerceIn(it.lower, it.upper))
        }
    } else {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        val ev = c.exposureCompensation.coerceIn(caps.evRange.lower, caps.evRange.upper)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
    }

    // ---- White balance ----
    if (!c.autoWhiteBalance && caps.supportsManualPostProcessing) {
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggbGains(c.wbKelvin))
    } else {
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }
}

/**
 * Approximate correlated-color-temperature -> RGGB channel gains for manual WB.
 * Uses a Tanner-Helland style blackbody approximation, then converts target illuminant RGB into
 * per-channel gains normalized so the smallest gain is 1.0 (camera gains must be >= 1.0).
 * This is an approximation good enough for a manual dial; precise WB should be dialed by eye.
 */
fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
    val t = (kelvin.coerceIn(1000, 40000)) / 100.0

    val r: Double = if (t <= 66.0) 255.0 else {
        (329.698727446 * Math.pow(t - 60.0, -0.1332047592)).coerceIn(0.0, 255.0)
    }
    val g: Double = if (t <= 66.0) {
        (99.4708025861 * Math.log(t) - 161.1195681661).coerceIn(0.0, 255.0)
    } else {
        (288.1221695283 * Math.pow(t - 60.0, -0.0755148492)).coerceIn(0.0, 255.0)
    }
    val b: Double = when {
        t >= 66.0 -> 255.0
        t <= 19.0 -> 0.0
        else -> (138.5177312231 * Math.log(t - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
    }

    // Illuminant RGB -> gains that neutralize it. Guard against divide-by-zero.
    val rr = (r / 255.0).coerceAtLeast(1e-3)
    val gg = (g / 255.0).coerceAtLeast(1e-3)
    val bb = (b / 255.0).coerceAtLeast(1e-3)
    var gainR = 1.0 / rr
    var gainG = 1.0 / gg
    var gainB = 1.0 / bb
    val minGain = minOf(gainR, gainG, gainB)
    gainR /= minGain; gainG /= minGain; gainB /= minGain

    return RggbChannelVector(gainR.toFloat(), gainG.toFloat(), gainG.toFloat(), gainB.toFloat())
}
