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
 * Flattened, hardware-derived capabilities for the selected (tele) camera. Read once on open;
 * consumed by control-building (to clamp/gate) and the UI (to enable controls).
 */
data class CameraCaps(
    val logicalId: String,
    val physicalId: String?,
    val sensorOrientation: Int,
    /** Diopters. 0 => fixed-focus (no manual focus). Closest focus = this value. */
    val minFocusDistanceDiopters: Float,
    val hyperfocalDiopters: Float,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?, // ns
    val evRange: Range<Int>,
    val evStep: Rational,
    val focalLengthsMm: FloatArray,
    /** 35mm-equivalent focal length of this lens (0 if unknown). Used for tele selection + EIS. */
    val equivalentFocalMm: Float,
    /** Native focal length expressed in image widths (f_px / sensorWidthPx). For gyro EIS scaling. */
    val nativeFocalInImageWidths: Float,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val rawSize: Size?,
    val supportedDynamicRangeProfiles: Set<Long>,
    val largestJpegSize: Size?,
    val oisAvailable: Boolean,
    val flashAvailable: Boolean,
    val zoomRatioRange: Range<Float>?,
    val afModes: IntArray,
    val awbModes: IntArray,
    val aeModes: IntArray,
    val antibandingModes: IntArray,
    val effectModes: IntArray,
    val edgeModes: IntArray,
    val noiseReductionModes: IntArray,
    val availableFpsRanges: Array<Range<Int>>,
) {
    val supportsManualFocus: Boolean get() = minFocusDistanceDiopters > 0f
    val maxFocalMm: Float get() = focalLengthsMm.maxOrNull() ?: 0f
    fun supportsHlg10(): Boolean = supportedDynamicRangeProfiles.contains(DynamicRangeProfiles.HLG10)
    fun hasEffect(mode: Int): Boolean = effectModes.contains(mode)

    /** Distinct fixed frame rates the device advertises (upper bound of each fps range), sorted. */
    val availableFps: List<Int>
        get() = availableFpsRanges.map { it.upper }.distinct().sorted()

    /** A supported target-fps range for [fps]: prefer a fixed [fps,fps] range, else one covering it. */
    fun clampFpsRange(fps: Int): Range<Int>? =
        availableFpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: availableFpsRanges.firstOrNull { fps in it.lower..it.upper }

    companion object {
        private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

        fun read(manager: CameraManager, logicalId: String, physicalId: String?): CameraCaps {
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

            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
            val focalMm = focals.firstOrNull() ?: 0f
            val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val diagMm = physical?.let { kotlin.math.hypot(it.width, it.height) } ?: 0f
            val equiv = if (diagMm > 0f && focalMm > 0f) focalMm * FULL_FRAME_DIAGONAL_MM / diagMm else 0f
            val focalInImageWidths = if (physical != null && physical.width > 0f && focalMm > 0f) {
                focalMm / physical.width
            } else 0f

            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)

            return CameraCaps(
                logicalId = logicalId,
                physicalId = physicalId,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                minFocusDistanceDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                hyperfocalDiopters = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f,
                isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3),
                focalLengthsMm = focals,
                equivalentFocalMm = equiv,
                nativeFocalInImageWidths = focalInImageWidths,
                supportsManualSensor = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                supportsManualPostProcessing = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                supportsRaw = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                rawSize = rawSize,
                supportedDynamicRangeProfiles = dynamicProfiles,
                largestJpegSize = jpegSize,
                oisAvailable = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
                flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                zoomRatioRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
                afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
                aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0),
                antibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: IntArray(0),
                effectModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: IntArray(0),
                edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0),
                noiseReductionModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0),
                availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray(),
            )
        }
    }
}
