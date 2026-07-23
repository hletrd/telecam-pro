package com.hletrd.findx9tele.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.util.Range
import android.util.Rational
import android.util.Size
import kotlin.math.hypot

private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

// Device-verified ceiling for a STILL request's SENSOR_EXPOSURE_TIME on this HAL (PMA110,
// bisected 2026-07-18 on the standalone TELE camera): 4 s captures complete with correct EXIF;
// 5 s and above error the whole camera device (CAMERA_ERROR(3)) and silently lose the shot,
// even with the repeating stream clamped short. Applied to the advertised exposure range at the
// caps seam so every consumer (ruler ladder, request clamps, AEB, watchdog) stays truthful.
internal const val HAL_SAFE_MAX_STILL_EXPOSURE_NS = 4_000_000_000L

/**
 * The caps-seam still-exposure clamp as pure Long math (TEST4-17): the single most safety-critical
 * transform of cycle 3 was inline against android.util.Range and had no host test — a dropped or
 * inverted guard would compile clean and re-arm the CAMERA_ERROR(3) shot-loss on the next 5 s+
 * selection. Both ends clamp (a pathological advertised lower above the ceiling must not produce
 * an inverted Range, which throws at construction).
 */
internal fun clampStillExposureRange(
    lowerNs: Long,
    upperNs: Long,
    ceilingNs: Long = HAL_SAFE_MAX_STILL_EXPOSURE_NS,
): Pair<Long, Long> = minOf(lowerNs, ceilingNs) to minOf(upperNs, ceilingNs)

/** Immutable optics subset safe to cache before a still callback needs physical-lens EXIF. */
internal data class LensExifMetadata(
    val focalLengthMm: Float,
    val apertureF: Float,
    val equivalentFocalMm: Float,
)

/** Pure lens-metadata calculation shared by broad capability reads and lightweight EXIF prefetch. */
internal fun lensExifMetadataOf(
    focalLengthMm: Float,
    apertureF: Float,
    sensorWidthMm: Float,
    sensorHeightMm: Float,
): LensExifMetadata {
    val diagonalMm = hypot(sensorWidthMm, sensorHeightMm)
    val equivalentFocalMm = if (diagonalMm > 0f && focalLengthMm > 0f) {
        focalLengthMm * FULL_FRAME_DIAGONAL_MM / diagonalMm
    } else {
        0f
    }
    return LensExifMetadata(focalLengthMm, apertureF, equivalentFocalMm)
}

/**
 * Reads only the three immutable values needed for EXIF. Callers must run this on setup work, never
 * the Camera2 callback handler; failures are a cache miss and use the selected-route fallback.
 */
internal fun readLensExifMetadata(manager: CameraManager, cameraId: String): LensExifMetadata? =
    runCatching {
        val chars = manager.getCameraCharacteristics(cameraId)
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        lensExifMetadataOf(
            focalLengthMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f,
            apertureF = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 0f,
            sensorWidthMm = physical?.width ?: 0f,
            sensorHeightMm = physical?.height ?: 0f,
        )
    }.getOrNull()

/** Cache-only physical-lens lookup for a live still callback. */
internal fun resolveLensExifMetadata(
    activePhysicalId: String?,
    cachedByCameraId: Map<String, LensExifMetadata>,
    routeFallback: LensExifMetadata?,
): LensExifMetadata? = activePhysicalId?.let(cachedByCameraId::get) ?: routeFallback

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
    // Physical lens optics for EXIF parity with the stock camera (FocalLength 20.1 mm / FNumber
    // f/2.2 on the 3×, per the reference sample): the ACTUAL lens focal and aperture, not the
    // 35 mm equivalent.
    val lensFocalLengthMm: Float,
    val lensApertureF: Float,
    /** Native focal length expressed in image widths (f_px / sensorWidthPx). For gyro EIS scaling. */
    val nativeFocalInImageWidths: Float,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val rawSize: Size?,
    val supportedDynamicRangeProfiles: Set<Long>,
    val largestJpegSize: Size?,
    /**
     * Full-sensor (remosaic, e.g. 200MP) still size, null when the camera advertises none. Two
     * PUBLIC-API sources, in priority order: the standard ULTRA_HIGH_RESOLUTION_SENSOR path (the
     * largest JPEG in SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), else a vendor-exposed
     * remosaic size sitting in the REGULAR stream map ([pickVendorHiResSize] — the size the
     * [largestJpegSize] active-array filter deliberately excludes for the binned path). NOTE a
     * THIRD documented path exists (SENSOR_PIXEL_MODE javadoc): the key "may also be present on
     * devices which do not support the ULTRA_HIGH_RESOLUTION_SENSOR capability", and on such
     * devices the MAXIMUM_RESOLUTION stream map "will always be present" — the capability-gated
     * read here misses that class of device, and the vendor fallback would not set the
     * then-required pixel mode. Latent for this single-device app (PMA110 advertises neither,
     * probed 2026-07-22); revisit before any multi-device build. Hi-res is session-gated
     * (standalone-only, first-drop on the fallback ladder) — advertising here alone never
     * admits it.
     */
    val hiResJpegSize: Size?,
    /**
     * True when [hiResJpegSize] came from the standard ultra-high-res path: the STILL request must
     * then carry SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION or the HAL serves the binned readout into the
     * full-size buffer. The vendor path sets nothing extra (the size itself selects remosaic).
     */
    val hiResUsesMaxResolutionMode: Boolean,
    // Largest YUV_420_888 still size within the camera's own active array. On the LOGICAL
    // multicamera the still path uses YUV instead of JPEG: this device's gralloc rejects the
    // ~42 MB JPEG blob allocation on the plain logical session ("SnapAlloc: ValidateDescriptor
    // invalid" — the image never arrives and the shot dies), while YUV buffers allocate fine.
    val largestYuvSize: Size?,
    val isLogicalMultiCamera: Boolean,
    val oisAvailable: Boolean,
    val flashAvailable: Boolean,
    val zoomRatioRange: Range<Float>?,
    /** CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES: which HAL video-stab modes this lens supports (0/1/2). */
    val videoStabModes: IntArray,
    val afModes: IntArray,
    val awbModes: IntArray,
    val aeModes: IntArray,
    /** Maximum simultaneous CONTROL_AE_REGIONS entries; zero means the key is unavailable. */
    val maxAeRegions: Int,
    /** Maximum simultaneous CONTROL_AF_REGIONS entries; zero means the key is unavailable. */
    val maxAfRegions: Int,
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

    internal fun lensExifMetadata(): LensExifMetadata =
        LensExifMetadata(lensFocalLengthMm, lensApertureF, equivalentFocalMm)

    /** Distinct fixed frame rates whose advertised lower and upper bounds are equal, sorted. */
    val availableFps: List<Int>
        get() = fixedFpsValues(availableFpsRanges.map { it.lower to it.upper })

    /** Max high-speed fps advertised for [size] (0 if the camera exposes no high-speed config for it). */
    fun highSpeedFpsFor(size: Size): Int = highSpeedConfigs[size] ?: 0

    /**
     * The CONTROL_VIDEO_STABILIZATION_MODE value to request for [mode], honoring what this lens
     * actually supports: ENHANCED falls back to ON when PREVIEW_STABILIZATION is absent, and any HAL
     * mode falls back to OFF when unsupported. OFF requests OFF (there is no app-side EIS mode —
     * the HAL's OIS+EIS owns stabilization; see [VideoStabMode]).
     */
    fun videoStabControlMode(mode: VideoStabMode): Int = videoStabControlModeFor(videoStabModes, mode)

    /** The exact fixed target range, or null when the camera cannot pin both bounds to [fps]. */
    fun fixedFpsRange(fps: Int): Range<Int>? =
        fixedFpsBounds(availableFpsRanges.map { it.lower to it.upper }, fps)
            ?.let { (lo, hi) -> availableFpsRanges.first { it.lower == lo && it.upper == hi } }

    /**
     * For AUTO-exposure preview: the supported `[floor, maxFps]` range with the LOWEST floor, so AE
     * can drop the frame rate in low light and expose longer (a brighter live view — the behavior a
     * range with both bounds at [maxFps] prevents). Falls back to a variable range covering [maxFps];
     * video/manual pinning never uses that fallback.
     */
    fun autoFpsRange(maxFps: Int): Range<Int>? =
        autoFpsBounds(availableFpsRanges.map { it.lower to it.upper }, maxFps)
            ?.let { (lo, hi) -> availableFpsRanges.first { it.lower == lo && it.upper == hi } }

    companion object {
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
            // Cap stills at the camera's OWN active array: the logical multicamera's stream map also
            // advertises the physical sub-cameras' larger JPEG sizes (4096×3072 here vs the logical
            // array's 4080×3064), and allocating that blob on the plain logical session fails in
            // gralloc ("SnapAlloc: ValidateDescriptor invalid") — the JPEG image then never arrives
            // and the capture wedges. Standalone cameras are unaffected (their max JPEG == array).
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val jpegCandidates = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val jpegSize = jpegCandidates
                .filter { activeArray == null || (it.width <= activeArray.width() && it.height <= activeArray.height()) }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: jpegCandidates.maxByOrNull { it.width.toLong() * it.height }
            // Full-sensor (remosaic) still size, both public-API paths. Standard: the ultra-high-res
            // capability's dedicated maximum-resolution stream map. Vendor: some OEM HALs skip that
            // capability and advertise the remosaic JPEG size directly in the REGULAR map — exactly
            // the ≥2×-active-array sizes the [jpegSize] filter above must keep EXCLUDING for the
            // binned path (allocating them on an ordinary session is the gralloc-wedge class).
            // Defensive reads: a vendor map/characteristics hiccup must degrade to "not advertised",
            // never fail the whole capability read.
            val maxResJpeg: Size? = if (has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                runCatching {
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                        ?.getOutputSizes(ImageFormat.JPEG)
                        ?.maxByOrNull { it.width.toLong() * it.height }
                }.getOrNull()
            } else {
                null
            }
            val vendorHiResJpeg: Size? = pickVendorHiResSize(
                jpegCandidates = jpegCandidates.map { it.width to it.height },
                activeArrayW = activeArray?.width() ?: 0,
                activeArrayH = activeArray?.height() ?: 0,
            )?.let { (w, h) -> jpegCandidates.first { it.width == w && it.height == h } }
            val hiResJpeg = maxResJpeg ?: vendorHiResJpeg

            val yuvCandidates = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val yuvSize = yuvCandidates
                .filter { activeArray == null || (it.width <= activeArray.width() && it.height <= activeArray.height()) }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: yuvCandidates.maxByOrNull { it.width.toLong() * it.height }
            // SurfaceTexture output sizes (the recording/preview path). Split by aspect: 16:9 for
            // standard video, 4:3 for Open Gate (full sensor readout). Both largest-first, ≤8K wide.
            val stSizes = (map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray())
                .filter { it.width <= 7680 }
                .distinct()
            val videoSizes = stSizes
                .filter { matchesStreamAspect(it.width, it.height, fourByThree = false) }
                .sortedByDescending { it.width.toLong() * it.height }
            val openGateSizes = stSizes
                .filter { matchesStreamAspect(it.width, it.height, fourByThree = true) }
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
            val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val lensExif = lensExifMetadataOf(
                focalLengthMm = focals.firstOrNull() ?: 0f,
                apertureF = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull() ?: 0f,
                sensorWidthMm = physical?.width ?: 0f,
                sensorHeightMm = physical?.height ?: 0f,
            )
            val focalMm = lensExif.focalLengthMm
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
                // The advertised upper (≥20 s here) is a LIE on this HAL: a STILL request above
                // 4 s errors the whole camera device ~one exposure after the shot with
                // CAMERA_ERROR(3) and the image never arrives — device-bisected 2026-07-18 on the
                // standalone TELE (2/3.2/4 s OK with correct EXIF; 5/6.3 s reproducibly fatal,
                // repeating stream already safely clamped by PREVIEW_SAFE_MAX_EXPOSURE_NS at the
                // time, so the still request alone is the trigger). Clamping HERE — the single
                // caps seam — keeps the shutter ruler, request clamps, AEB brackets, and the
                // still watchdog all truthful at once.
                exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?.let { adv ->
                        val (lo, hi) = clampStillExposureRange(adv.lower, adv.upper)
                        if (lo == adv.lower && hi == adv.upper) adv else Range(lo, hi)
                    },
                maxFrameDurationNs = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L,
                evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3),
                focalLengthsMm = focals,
                equivalentFocalMm = lensExif.equivalentFocalMm,
                lensFocalLengthMm = focalMm,
                lensApertureF = lensExif.apertureF,
                nativeFocalInImageWidths = focalInImageWidths,
                supportsManualSensor = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                supportsManualPostProcessing = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                supportsRaw = has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                rawSize = rawSize,
                supportedDynamicRangeProfiles = dynamicProfiles,
                largestJpegSize = jpegSize,
                hiResJpegSize = hiResJpeg,
                hiResUsesMaxResolutionMode = maxResJpeg != null,
                largestYuvSize = yuvSize,
                isLogicalMultiCamera = has(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
                oisAvailable = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
                flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                zoomRatioRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
                videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0),
                afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
                aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0),
                maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
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
 * Android-type-free projection of the Camera2 facts that own manual-control request values. Keeping
 * arrays and scalar flags here lets host tests exercise sparse/absent capability matrices without
 * constructing android.util.Range/Rational/Size-backed [CameraCaps].
 */
internal data class CameraControlCapabilities(
    val supportsManualFocus: Boolean = false,
    val supportsManualSensor: Boolean = false,
    val supportsManualPostProcessing: Boolean = false,
    val hasFocusDistanceRange: Boolean = false,
    // Manual-focus distance is always [0, max] diopters; zero means optical infinity.
    val focusDistanceMaxDiopters: Float? = null,
    val hasIsoRange: Boolean = false,
    val isoMin: Int? = null,
    val isoMax: Int? = null,
    val hasExposureTimeRange: Boolean = false,
    // Plain Longs (not android Range) so normalization can clamp NUMERIC exposure on the JVM.
    // Carries the caps-seam value, i.e. already capped at HAL_SAFE_MAX_STILL_EXPOSURE_NS: a
    // persisted/recalled over-ceiling shutter must NORMALIZE down so the OSD/ruler show the value
    // the camera actually applies (a restored 5 s chip over a 4 s request was the observed lie).
    val exposureTimeMinNs: Long? = null,
    val exposureTimeMaxNs: Long? = null,
    val hasEvCompensationRange: Boolean = false,
    val evCompensationMin: Int? = null,
    val evCompensationMax: Int? = null,
    val hasZoomRatioRange: Boolean = false,
    val flashAvailable: Boolean = false,
    val afModes: IntArray = IntArray(0),
    val awbModes: IntArray = IntArray(0),
    val aeModes: IntArray = IntArray(0),
    val maxAeRegions: Int = 0,
    val maxAfRegions: Int = 0,
    val antibandingModes: IntArray = IntArray(0),
    val effectModes: IntArray = IntArray(0),
    val edgeModes: IntArray = IntArray(0),
    val noiseReductionModes: IntArray = IntArray(0),
    // Hi-res ROUTE fact for the settings projection (cycle-6 architect F3): the selected camera
    // advertises a full-sensor size AND is a standalone route (plain id, not the logical
    // multicamera whose gralloc rejects the big blob). Folds the standalone+advertised axes of
    // [hiResAdmitted]; the LIVE mode/aspect axes stay with the UI via [hiResToggleEnabled].
    val hiResAdvertisedStandalone: Boolean = false,
)

internal fun CameraCaps.controlCapabilities(): CameraControlCapabilities = CameraControlCapabilities(
    supportsManualFocus = supportsManualFocus,
    supportsManualSensor = supportsManualSensor,
    supportsManualPostProcessing = supportsManualPostProcessing,
    hasFocusDistanceRange = minFocusDistanceDiopters > 0f,
    focusDistanceMaxDiopters = minFocusDistanceDiopters,
    hasIsoRange = isoRange != null,
    isoMin = isoRange?.lower,
    isoMax = isoRange?.upper,
    hasExposureTimeRange = exposureTimeRange != null,
    exposureTimeMinNs = exposureTimeRange?.lower,
    exposureTimeMaxNs = exposureTimeRange?.upper,
    hasEvCompensationRange = evRange.lower < evRange.upper,
    evCompensationMin = evRange.lower,
    evCompensationMax = evRange.upper,
    hasZoomRatioRange = zoomRatioRange?.let { it.lower < it.upper } == true,
    flashAvailable = flashAvailable,
    afModes = afModes,
    awbModes = awbModes,
    aeModes = aeModes,
    maxAeRegions = maxAeRegions,
    maxAfRegions = maxAfRegions,
    antibandingModes = antibandingModes,
    effectModes = effectModes,
    edgeModes = edgeModes,
    noiseReductionModes = noiseReductionModes,
    hiResAdvertisedStandalone = hiResJpegSize != null && physicalId == null && !isLogicalMultiCamera,
)

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
 * Extracts sorted distinct fixed rates from plain lower/upper pairs. Android Range getters throw
 * "not mocked" on the JVM, so both capability flattening and tests share this Android-free core.
 */
internal fun fixedFpsValues(ranges: List<Pair<Int, Int>>): List<Int> =
    ranges.asSequence()
        .filter { (lower, upper) -> lower == upper }
        .map { it.first }
        .distinct()
        .sorted()
        .toList()

/** Pure exact-range core of [CameraCaps.fixedFpsRange], kept Android-free for JVM tests. */
internal fun fixedFpsBounds(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? =
    ranges.firstOrNull { it.first == fps && it.second == fps }

/**
 * Pure core of [CameraCaps.autoFpsRange]: the `[floor, maxFps]` range with the LOWEST floor so
 * photo-AUTO AE can extend exposure in low light (the documented "AE pinned at 1/30 s" bug zone);
 * falls back to the first variable range covering [maxFps]. This covering fallback belongs only to
 * photo AUTO; fixed video/manual requests use [fixedFpsBounds].
 */
internal fun autoFpsBounds(ranges: List<Pair<Int, Int>>, maxFps: Int): Pair<Int, Int>? =
    ranges.filter { it.second == maxFps }.minByOrNull { it.first }
        ?: ranges.firstOrNull { maxFps in it.first..it.second }

/**
 * THE stream-aspect rule (16:9 standard / 4:3 open-gate-and-photo), shared by [CameraCaps.read]'s
 * list building and the engine's pre-caps fallback picker so the two can't drift apart.
 */
internal fun matchesStreamAspect(width: Int, height: Int, fourByThree: Boolean): Boolean =
    if (fourByThree) height * 4 == width * 3 else height * 16 == width * 9

/**
 * Vendor-exposed remosaic detection: the largest JPEG candidate whose pixel area is at least twice
 * the active array's — the active array IS the binned readout, so a genuine full-sensor remosaic
 * size (4× area at a 2×2 binner) clears 2× while ordinary near-array sizes (4096×3072 vs 4080×3064)
 * never do. Null when the active array is unknown: without the baseline the 2× test is meaningless
 * and guessing would admit a session-crashing blob. Plain pairs — android.util.Size getters throw
 * on the JVM (same reason as [pickStreamSize]).
 */
internal fun pickVendorHiResSize(
    jpegCandidates: List<Pair<Int, Int>>,
    activeArrayW: Int,
    activeArrayH: Int,
): Pair<Int, Int>? {
    if (activeArrayW <= 0 || activeArrayH <= 0) return null
    val arrayArea = activeArrayW.toLong() * activeArrayH
    return jpegCandidates
        .filter { (w, h) -> w.toLong() * h >= 2L * arrayArea }
        .maxByOrNull { (w, h) -> w.toLong() * h }
}

/**
 * Pure core of the engine's fallback stream-size selection: the largest matching-aspect size at or
 * under [capWidth], else the largest size overall (never null for a non-empty input). Plain pairs —
 * android.util.Size getters throw on the JVM.
 */
internal fun pickStreamSize(
    sizes: List<Pair<Int, Int>>,
    capWidth: Int,
    fourByThree: Boolean,
): Pair<Int, Int>? =
    sizes.filter { (w, h) -> w <= capWidth && matchesStreamAspect(w, h, fourByThree) }
        .maxByOrNull { (w, h) -> w.toLong() * h }
        ?: sizes.maxByOrNull { (w, h) -> w.toLong() * h }
