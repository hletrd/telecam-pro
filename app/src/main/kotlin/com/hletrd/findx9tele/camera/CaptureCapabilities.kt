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
    /**
     * Max sensor frame duration (ns), from SENSOR_INFO_MAX_FRAME_DURATION; 0 if unreported. This is
     * the ceiling a single frame's exposure can occupy — a manual shutter slower than 1/fps needs the
     * frame duration stretched up to here (Camera2 requires frameDuration >= exposureTime), otherwise
     * the HAL silently caps the exposure at 1/fps (kills long-exposure/astro through the tele).
     */
    val maxFrameDurationNs: Long,
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
    /** CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES: which HAL video-stab modes this lens supports (0/1/2). */
    val videoStabModes: IntArray,
    val afModes: IntArray,
    val awbModes: IntArray,
    val aeModes: IntArray,
    val antibandingModes: IntArray,
    val effectModes: IntArray,
    val edgeModes: IntArray,
    val noiseReductionModes: IntArray,
    val availableFpsRanges: Array<Range<Int>>,
    /** 16:9 SurfaceTexture output sizes (width <= 7680), largest-first; empty if none. */
    val availableVideoSizes: List<Size>,
    /** 4:3 SurfaceTexture output sizes (Open Gate / full sensor readout), largest-first; empty if none. */
    val openGateVideoSizes: List<Size>,
    /**
     * High-speed (slow-motion) video configs the camera advertises, as size → max fps
     * (from StreamConfigurationMap.getHighSpeedVideoSizes / getHighSpeedVideoFpsRangesFor). A size
     * present here can drive a CameraConstrainedHighSpeedCaptureSession up to that fps. Empty if the
     * camera exposes no high-speed configs.
     */
    val highSpeedConfigs: Map<Size, Int>,
) {
    val supportsManualFocus: Boolean get() = minFocusDistanceDiopters > 0f
    val maxFocalMm: Float get() = focalLengthsMm.maxOrNull() ?: 0f
    fun supportsHlg10(): Boolean = supportedDynamicRangeProfiles.contains(DynamicRangeProfiles.HLG10)
    fun hasEffect(mode: Int): Boolean = effectModes.contains(mode)

    /** Distinct fixed frame rates the device advertises (upper bound of each fps range), sorted. */
    val availableFps: List<Int>
        get() = availableFpsRanges.map { it.upper }.distinct().sorted()

    /** Max high-speed fps advertised for [size] (0 if the camera exposes no high-speed config for it). */
    fun highSpeedFpsFor(size: Size): Int = highSpeedConfigs[size] ?: 0

    /**
     * The CONTROL_VIDEO_STABILIZATION_MODE value to request for [mode], honoring what this lens
     * actually supports: ENHANCED falls back to ON when PREVIEW_STABILIZATION is absent, and any HAL
     * mode falls back to OFF when unsupported. OFF requests OFF (there is no app-side EIS mode —
     * the HAL's OIS+EIS owns stabilization; see [VideoStabMode]).
     */
    fun videoStabControlMode(mode: VideoStabMode): Int = videoStabControlModeFor(videoStabModes, mode)

    /** A supported target-fps range for [fps]: prefer a fixed [fps,fps] range, else one covering it. */
    fun clampFpsRange(fps: Int): Range<Int>? =
        clampFpsBounds(availableFpsRanges.map { it.lower to it.upper }, fps)
            ?.let { (lo, hi) -> availableFpsRanges.first { it.lower == lo && it.upper == hi } }

    /**
     * For AUTO-exposure preview: the supported `[floor, maxFps]` range with the LOWEST floor, so AE
     * can drop the frame rate in low light and expose longer (a brighter live view — the behavior a
     * fixed `[maxFps,maxFps]` range prevents). Falls back to a covering/fixed range.
     */
    fun autoFpsRange(maxFps: Int): Range<Int>? =
        autoFpsBounds(availableFpsRanges.map { it.lower to it.upper }, maxFps)
            ?.let { (lo, hi) -> availableFpsRanges.first { it.lower == lo && it.upper == hi } }

    companion object {
        private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

        /**
         * Reads and flattens the characteristics. Throws (CameraAccessException) only when BOTH the
         * physical and logical reads fail — a transient camera-service outage. Callers run on
         * background executors and must guard the call; an uncaught throw there kills the process.
         */
        fun read(manager: CameraManager, logicalId: String, physicalId: String?): CameraCaps {
            val chars: CameraCharacteristics = runCatching {
                manager.getCameraCharacteristics(physicalId ?: logicalId)
            }.recoverCatching {
                // The fallback read was previously unguarded inside getOrElse: a second service
                // hiccup threw straight out of read() on the setup thread. recoverCatching keeps the
                // failure a Result until getOrThrow, so exactly one exception surfaces to the caller.
                manager.getCameraCharacteristics(logicalId)
            }.getOrThrow()

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            fun has(cap: Int) = caps.contains(cap)

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            // SurfaceTexture output sizes (the recording/preview path). Split by aspect: 16:9 for
            // standard video, 4:3 for Open Gate (full sensor readout). Both largest-first, ≤8K wide.
            val stSizes = (map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray())
                .filter { it.width <= 7680 }
                .distinct()
            val videoSizes = stSizes
                .filter { it.height * 16 == it.width * 9 }
                .sortedByDescending { it.width.toLong() * it.height }
            val openGateSizes = stSizes
                .filter { it.height * 4 == it.width * 3 }
                .sortedByDescending { it.width.toLong() * it.height }

            // High-speed (slow-motion) configs: size → max advertised fps. Read defensively — some
            // cameras/HALs return null or throw for getHighSpeedVideoFpsRangesFor on odd sizes.
            val highSpeed: Map<Size, Int> = runCatching {
                (map?.highSpeedVideoSizes ?: emptyArray()).associateWith { sz ->
                    runCatching { map!!.getHighSpeedVideoFpsRangesFor(sz).maxOf { it.upper } }.getOrDefault(0)
                }.filterValues { it > 0 }
            }.getOrDefault(emptyMap())

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
                maxFrameDurationNs = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L,
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
                videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0),
                afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
                aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0),
                antibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: IntArray(0),
                effectModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: IntArray(0),
                edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0),
                noiseReductionModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0),
                availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray(),
                availableVideoSizes = videoSizes,
                openGateVideoSizes = openGateSizes,
                highSpeedConfigs = highSpeed,
            )
        }
    }
}

/**
 * Pure core of [CameraCaps.videoStabControlMode] (a full CameraCaps can't be built on the JVM —
 * Rational/Range constructors are unmocked stubs): ENHANCED falls back to ON when
 * PREVIEW_STABILIZATION is absent, anything unsupported falls back to OFF.
 */
internal fun videoStabControlModeFor(videoStabModes: IntArray, mode: VideoStabMode): Int {
    val off = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    val want = mode.halControlMode ?: return off
    if (videoStabModes.contains(want)) return want
    val on = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
    return if (mode == VideoStabMode.ENHANCED && videoStabModes.contains(on)) on else off
}

/**
 * Pure core of [CameraCaps.clampFpsRange] over plain `(lower, upper)` pairs (android.util.Range
 * getters throw "not mocked" on the JVM — same extraction pattern as [videoStabControlModeFor]):
 * prefer an exact fixed `[fps,fps]` range, else the first range covering [fps].
 */
internal fun clampFpsBounds(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? =
    ranges.firstOrNull { it.first == fps && it.second == fps }
        ?: ranges.firstOrNull { fps in it.first..it.second }

/**
 * Pure core of [CameraCaps.autoFpsRange]: the `[floor, maxFps]` range with the LOWEST floor so
 * photo-AUTO AE can extend exposure in low light (the documented "AE pinned at 1/30 s" bug zone);
 * falls back to [clampFpsBounds].
 */
internal fun autoFpsBounds(ranges: List<Pair<Int, Int>>, maxFps: Int): Pair<Int, Int>? =
    ranges.filter { it.second == maxFps }.minByOrNull { it.first }
        ?: clampFpsBounds(ranges, maxFps)
