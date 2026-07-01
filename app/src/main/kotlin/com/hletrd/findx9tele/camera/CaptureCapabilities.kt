package com.hletrd.findx9tele.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.util.Range
import android.util.Rational
import android.util.Size

/**
 * Flattened, hardware-derived capabilities for the selected (tele) camera.
 * Read once on open; consumed by control-building and the UI to clamp/enable features.
 */
data class CameraCaps(
    val logicalId: String,
    val physicalId: String?,
    val sensorOrientation: Int,
    /** Diopters. 0 => fixed-focus lens (no manual focus). Closest focus = this value. */
    val minFocusDistanceDiopters: Float,
    val hyperfocalDiopters: Float,
    val isoRange: Range<Int>?,
    /** Nanoseconds. */
    val exposureTimeRange: Range<Long>?,
    val evRange: Range<Int>,
    val evStep: Rational,
    val focalLengthsMm: FloatArray,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val rawSize: Size?,
    /** DynamicRangeProfiles.* bitmask values supported (e.g. HLG10, HDR10). STANDARD always present. */
    val supportedDynamicRangeProfiles: Set<Long>,
    val largestJpegSize: Size?,
) {
    val supportsManualFocus: Boolean get() = minFocusDistanceDiopters > 0f
    val maxFocalMm: Float get() = focalLengthsMm.maxOrNull() ?: 0f
    fun supportsHlg10(): Boolean = supportedDynamicRangeProfiles.contains(DynamicRangeProfiles.HLG10)

    companion object {
        fun read(manager: CameraManager, logicalId: String, physicalId: String?): CameraCaps {
            // Prefer the physical (tele) camera's own characteristics when available.
            val chars: CameraCharacteristics = runCatching {
                manager.getCameraCharacteristics(physicalId ?: logicalId)
            }.getOrElse { manager.getCameraCharacteristics(logicalId) }

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            fun has(cap: Int) = caps.contains(cap)

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }

            val dynamicProfiles: Set<Long> =
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.supportedProfiles
                    ?: setOf(DynamicRangeProfiles.STANDARD)

            return CameraCaps(
                logicalId = logicalId,
                physicalId = physicalId,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                minFocusDistanceDiopters =
                    chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                hyperfocalDiopters =
                    chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f,
                isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    ?: Range(0, 0),
                evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    ?: Rational(1, 3),
                focalLengthsMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: FloatArray(0),
                supportsManualSensor = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                supportsManualPostProcessing =
                    has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                supportsRaw = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                rawSize = rawSize,
                supportedDynamicRangeProfiles = dynamicProfiles,
                largestJpegSize = jpegSize,
            )
        }
    }

    // Explicit equals/hashCode omitted intentionally: instances are used as opaque snapshots,
    // not as map keys. (FloatArray identity is fine for that usage.)
}
