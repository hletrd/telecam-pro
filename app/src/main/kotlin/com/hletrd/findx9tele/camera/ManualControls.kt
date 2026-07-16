package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector

/**
 * Full manual/pro capture parameters. Immutable; the ViewModel copies with updated fields and
 * re-applies to the repeating request and still/video captures.
 */
data class ManualControls(
    // Focus — default to continuous AF so the preview is sharp out of the box (a bare-lens near
    // subject at manual ∞ is blurry). Through the afocal teleconverter AF still converges near ∞ on a
    // distant subject; the user switches to MANUAL for fine focus around infinity.
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusDistanceDiopters: Float = 0f, // 0 = infinity
    val afLock: Boolean = false,
    // Exposure — PASM-style mode. PROGRAM (default) = HAL auto ISO+shutter, correctly exposed on
    // launch. SHUTTER = you set shutter, an app-side loop auto-drives ISO. ISO = you set ISO, the loop
    // auto-drives shutter. MANUAL = you set both. (This device's tele aperture is fixed, so there is
    // no aperture-priority mode.) In SHUTTER/ISO the app-side controller keeps the driven field fresh
    // in [iso]/[exposureTimeNs], so the capture path treats S/ISO/M identically (AE off, sensor set).
    val exposureMode: ExposureMode = ExposureMode.PROGRAM,
    // PROGRAM runs APP-SIDE for stills (auto min-shutter by the 1/focal rule + Auto ISO — the HAL AE
    // can't take a minimum-shutter hint, so it happily picks 1/30 s at 300 mm). The ViewModel keeps
    // this flag true whenever mode is PROGRAM + PHOTO and the flash doesn't need the HAL AE
    // (AUTO/ON flash metering only exists with AE ON); video PROGRAM stays on the HAL AE.
    val programAppSide: Boolean = false,
    val iso: Int = 400,
    val exposureTimeNs: Long = 8_000_000L, // ~1/125 s (SPEED mode)
    val shutterMode: ShutterMode = ShutterMode.SPEED,
    val shutterAngle: Float = 180f, // cine ANGLE mode: exposure = (angle/360)/fps
    val exposureCompensation: Int = 0,
    val aeLock: Boolean = false,
    val antibanding: Antibanding = Antibanding.AUTO,
    val fps: Int = 30,
    // Snap increment (in EV/stops) for the manual ISO and shutter dials.
    val exposureStep: ExposureStep = ExposureStep.THIRD,
    // White balance
    val wbMode: WbMode = WbMode.AUTO,
    val wbKelvin: Int = 5200,
    val wbTint: Int = 0, // -50 (green) .. +50 (magenta)
    // Measured custom WB (grey/white-card capture); active when wbMode == CUSTOM.
    val customWbGains: WbGains? = null,
    val awbLock: Boolean = false,
    // Metering (SPOT/CENTER apply an AE region; region rect computed in CameraController)
    val meteringMode: MeteringMode = MeteringMode.MATRIX,
    // Tap-AF / spot region size (Sony Spot S/M/L).
    val afSpotSize: AfSpotSize = AfSpotSize.MEDIUM,
    // Processing — OFF by default: pros want a clean, un-sharpened, un-denoised signal to grade
    // themselves (and it keeps the afocal-tele image honest). The user opts into edge/NR per shot.
    val edge: ProcessingLevel = ProcessingLevel.OFF,
    val noiseReduction: ProcessingLevel = ProcessingLevel.OFF,
    val colorEffect: ColorEffect = ColorEffect.NONE,
    // Optics
    val flash: FlashMode = FlashMode.OFF,
    val oisEnabled: Boolean = true,
    val zoomRatio: Float = 1f,
    // Output
    val jpegQuality: Int = 95,
) {
    /**
     * True when the HAL auto-exposure is ON (PROGRAM only). In SHUTTER/ISO/MANUAL the HAL AE is OFF
     * and the sensor values are set directly — in SHUTTER/ISO the app-side controller supplies the
     * driven value, so the capture path treats all three the same. Kept as the single read-only
     * meaning of "AE on" that the whole codebase already reasons about.
     */
    val autoExposure: Boolean get() = exposureMode == ExposureMode.PROGRAM && !programAppSide

    /** SHUTTER mode: the app-side AE loop drives ISO (user owns the shutter). */
    val autoIsoDriven: Boolean get() = exposureMode == ExposureMode.SHUTTER

    /** ISO mode: the app-side AE loop drives the shutter/exposure time (user owns ISO). */
    val autoShutterDriven: Boolean get() = exposureMode == ExposureMode.ISO
}

/**
 * PASM-style exposure mode. No aperture-priority: the tele's aperture is fixed, so there is nothing
 * to prioritize. [letter] is the compact dial badge; [label] the settings-row name.
 */
enum class ExposureMode(val letter: String, val label: String) {
    PROGRAM("P", "Program"),
    SHUTTER("S", "Shutter priority"),
    ISO("ISO", "ISO priority"),
    MANUAL("M", "Manual"),
}

/** Snap increment for the manual ISO/shutter dials, in EV (stops). */
enum class ExposureStep(val ev: Float, val label: String) {
    THIRD(1f / 3f, "1/3"),
    HALF(1f / 2f, "1/2"),
    FULL(1f, "1"),
}

/** Effective exposure time (ns): derived from the cine angle in ANGLE mode, else the raw speed. */
// Preview exposure ceiling for the auto program (1/30 s → a ≥30 fps live view when ISO allows).
private const val PREVIEW_MAX_EXPOSURE_NS = 33_333_333L

fun ManualControls.effectiveExposureNs(): Long =
    if (shutterMode == ShutterMode.ANGLE && fps > 0) {
        ((shutterAngle.coerceIn(1f, 360f) / 360.0) / fps * 1_000_000_000.0).toLong()
    } else {
        exposureTimeNs
    }

/**
 * Exposure times (ns) for a MANUAL-exposure AEB bracket: -2 / 0 / +2 EV around [baseNs] (×¼ / ×1 /
 * ×4), clamped to the sensor's [minNs]..[maxNs] and deduplicated after clamping (a base near a
 * range edge collapses to fewer, distinct shots). Needed because with AE OFF the HAL ignores
 * CONTROL_AE_EXPOSURE_COMPENSATION — an EV-comp bracket fires three identical frames — so the
 * bracket must vary SENSOR_EXPOSURE_TIME itself (ISO untouched). Pure so it is unit-testable.
 */
fun manualAebExposuresNs(baseNs: Long, minNs: Long, maxNs: Long): List<Long> {
    if (minNs > maxNs) return listOf(baseNs)
    return listOf(baseNs / 4, baseNs, baseNs * 4).map { it.coerceIn(minNs, maxNs) }.distinct()
}

/**
 * The SENSOR_FRAME_DURATION (ns) to request in manual exposure. Camera2 requires the frame duration
 * be >= the exposure time, so a shutter slower than 1/[fps] must stretch the frame duration up to
 * [exposureNs] — otherwise the HAL silently caps the exposure at 1/fps (which killed long-exposure /
 * astro through the tele). Bounded by [maxFrameDurationNs] when the sensor reports it (>0). With
 * [fps] <= 0 the nominal 1/fps term drops out and the exposure alone drives the duration. Pure so the
 * "exposure longer than the frame interval" edge case is unit-testable off-device.
 */
fun sensorFrameDurationNs(fps: Int, exposureNs: Long, maxFrameDurationNs: Long): Long {
    val nominal = if (fps > 0) 1_000_000_000L / fps else 0L
    val needed = maxOf(nominal, exposureNs)
    return if (maxFrameDurationNs > 0L) needed.coerceAtMost(maxFrameDurationNs) else needed
}

/**
 * Applies the parameters to a CaptureRequest, clamping to hardware ranges and honoring capability
 * gates. Sets OIS per the user toggle. HAL video stabilization (CONTROL_VIDEO_STABILIZATION_MODE)
 * is owned by [CameraController], which sets it on the repeating preview/video request per the
 * selected [VideoStabMode] — not here, so it isn't forced onto still captures.
 */
fun CaptureRequest.Builder.applyManualControls(
    c: ManualControls,
    caps: CameraCaps,
    pinAutoFps: Boolean = false,
    // PREVIEW requests only: cap the app-side P exposure at 1/30 s (ISO raised brightness-
    // neutrally) so the live view never becomes a 10-15 fps slideshow in dim light; the STILL
    // request keeps the true program exposure. See applyExposure.
    previewExposureCap: Boolean = false,
) {
    applyFocus(c, caps)
    applyExposure(c, caps, pinAutoFps, previewExposureCap)
    applyWhiteBalance(c, caps)
    applyProcessing(c, caps)
    // Flash runs AFTER exposure: when AE is ON, the auto/always-flash variants set CONTROL_AE_MODE
    // and must win over the AE mode that applyExposure set (see applyFlash).
    applyFlash(c, caps)
    applyZoom(c, caps)
    set(CaptureRequest.JPEG_QUALITY, c.jpegQuality.coerceIn(1, 100).toByte())

    // OIS: physically counter-moves the lens during exposure → the only thing that cuts per-frame
    // motion blur at 300 mm. HAL video stabilization is applied separately by the controller.
    if (caps.oisAvailable) {
        set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (c.oisEnabled) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )
    }
}

private fun CaptureRequest.Builder.applyFocus(c: ManualControls, caps: CameraCaps) {
    // Expression-position `when` so the compiler enforces exhaustiveness: a statement-position enum
    // `when` compiles fine with a missing case and silently no-ops it — the session would keep the
    // PREVIOUS AF mode while the UI shows the new one (the exact silent-drift trap a future 5th
    // FocusMode would spring).
    val afMode = when (c.focusMode) {
        FocusMode.MANUAL ->
            if (caps.supportsManualFocus) CameraMetadata.CONTROL_AF_MODE_OFF
            else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        FocusMode.AUTO -> CameraMetadata.CONTROL_AF_MODE_AUTO
        FocusMode.CONTINUOUS -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        FocusMode.MACRO -> CameraMetadata.CONTROL_AF_MODE_MACRO
    }
    set(CaptureRequest.CONTROL_AF_MODE, afMode)
    if (c.focusMode == FocusMode.MANUAL && caps.supportsManualFocus) {
        set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistanceDiopters))
    }
}

private fun CaptureRequest.Builder.applyExposure(
    c: ManualControls,
    caps: CameraCaps,
    pinAutoFps: Boolean,
    previewExposureCap: Boolean = false,
) {
    if (!c.autoExposure && caps.supportsManualSensor) {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        var iso = c.iso
        var wantExposureNs = c.effectiveExposureNs()
        // PREVIEW-only cap for the auto program (app-side P): its program line legitimately parks
        // the STILL exposure at 1/14-1/10 s in dim light, but a preview at that exposure IS a
        // 10-15 fps viewfinder — the user-reported "low preview fps". Trade exposure for ISO
        // BRIGHTNESS-NEUTRALLY, bounded by ISO headroom (past the ceiling the preview honestly
        // slows, like every camera's night view). User-owned modes (S/ISO/M) stay WYSIWYG.
        if (previewExposureCap && c.exposureMode == ExposureMode.PROGRAM && wantExposureNs > PREVIEW_MAX_EXPOSURE_NS) {
            val isoUpper = caps.isoRange?.upper
            if (isoUpper != null && iso > 0) {
                val scale = minOf(wantExposureNs.toDouble() / PREVIEW_MAX_EXPOSURE_NS, isoUpper.toDouble() / iso)
                if (scale > 1.05) {
                    wantExposureNs = (wantExposureNs / scale).toLong()
                    iso = (iso * scale).toInt().coerceAtMost(isoUpper)
                }
            }
        }
        caps.isoRange?.let { set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(it.lower, it.upper)) }
        val exposureNs = caps.exposureTimeRange
            ?.let { wantExposureNs.coerceIn(it.lower, it.upper) }
            ?: wantExposureNs
        caps.exposureTimeRange?.let { set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs) }
        // Frame duration must be >= the exposure (Camera2 contract). Stretch it to the exposure so a
        // shutter slower than 1/fps survives instead of being clamped to 1/fps (long-exposure/astro).
        set(CaptureRequest.SENSOR_FRAME_DURATION, sensorFrameDurationNs(c.fps, exposureNs, caps.maxFrameDurationNs))
    } else {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
        set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            c.exposureCompensation.coerceIn(caps.evRange.lower, caps.evRange.upper),
        )
    }
    // Photo AUTO preview can lower fps for a brighter low-light view. Video AUTO must stay at the
    // selected rate; otherwise a 29.97p clip can silently become 25p when AE chooses 1/25 s.
    val fpsRange = if (pinAutoFps || (!c.autoExposure && caps.supportsManualSensor)) {
        caps.fixedFpsRange(c.fps)
    } else {
        caps.autoFpsRange(c.fps)
    }
    fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
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
    when (c.wbMode) {
        WbMode.MANUAL -> if (caps.supportsManualPostProcessing) {
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            // FAST (not TRANSFORM_MATRIX) honors COLOR_CORRECTION_GAINS without also requiring a
            // COLOR_CORRECTION_TRANSFORM — which we never set, so TRANSFORM_MATRIX left color undefined.
            set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinTintToRggbGains(c.wbKelvin, c.wbTint))
        } else {
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        WbMode.CUSTOM -> {
            val g = c.customWbGains
            if (caps.supportsManualPostProcessing && g != null) {
                // Measured card WB: replay the AWB gains sampled at capture time (see FAST-mode note
                // on the MANUAL branch above).
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.r, g.gEven, g.gOdd, g.b))
            } else {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
        }
        WbMode.AUTO -> {
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
        }
        else -> set(CaptureRequest.CONTROL_AWB_MODE, c.wbMode.awbMetadata)
    }
}

/** Named WB preset -> CONTROL_AWB_MODE_*. AUTO/MANUAL handled separately in applyWhiteBalance. */
private val WbMode.awbMetadata: Int
    get() = when (this) {
        WbMode.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WbMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        WbMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WbMode.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WbMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        WbMode.AUTO, WbMode.MANUAL, WbMode.CUSTOM -> CameraMetadata.CONTROL_AWB_MODE_AUTO
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

/**
 * Flash via AE modes. Interacts with [applyExposure], which owns CONTROL_AE_MODE:
 *  - MANUAL exposure (`!autoExposure && supportsManualSensor`) → AE is OFF, so the AE-driven
 *    auto/always-flash modes are unusable; only TORCH and OFF are honored (via FLASH_MODE).
 *  - AE ON → set the flash AE-mode variant here (runs after applyExposure, so it wins):
 *      OFF → AE_MODE_ON + FLASH_MODE_OFF, AUTO → AE_MODE_ON_AUTO_FLASH,
 *      ON → AE_MODE_ON_ALWAYS_FLASH, TORCH → AE_MODE_ON + FLASH_MODE_TORCH.
 */
private fun CaptureRequest.Builder.applyFlash(c: ManualControls, caps: CameraCaps) {
    val aeManual = !c.autoExposure && caps.supportsManualSensor
    if (aeManual) {
        when (c.flash) {
            FlashMode.TORCH -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            else -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
    } else {
        // Expression-position `when`s for compiler-enforced exhaustiveness (see applyFocus): a new
        // FlashMode that forgets a branch here must fail the build, not silently keep the previous
        // AE flash variant on the repeating request.
        val aeMode = when (c.flash) {
            FlashMode.OFF, FlashMode.TORCH -> CameraMetadata.CONTROL_AE_MODE_ON
            FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        }
        set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        val flashMode = when (c.flash) {
            FlashMode.OFF -> CameraMetadata.FLASH_MODE_OFF
            FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
            // The AE flash variants own the firing decision; FLASH_MODE stays unset for them.
            FlashMode.AUTO, FlashMode.ON -> null
        }
        flashMode?.let { set(CaptureRequest.FLASH_MODE, it) }
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
 * Ceiling for a single WB channel gain. Unclamped, the Tanner-Helland approximation demands a
 * ~18-19× blue gain at the UI-reachable 2000 K — far outside the ~1-8× range sensor WB gains
 * actually span, leaving the HAL's behavior undefined. Color accuracy at the extreme presets is
 * necessarily approximate; a bounded gain beats an out-of-range request.
 */
const val MAX_WB_CHANNEL_GAIN = 8f

/**
 * Approximate CCT + tint -> RGGB channel gains for manual WB.
 * Kelvin uses a Tanner-Helland blackbody approximation; tint shifts the green channel
 * (-50 greener .. +50 more magenta). Gains are normalized so the smallest is 1.0 (camera minimum)
 * and each channel is capped at [MAX_WB_CHANNEL_GAIN].
 */
fun kelvinTintToRggbGains(kelvin: Int, tint: Int): RggbChannelVector {
    val v = kelvinTintToRggbGainValues(kelvin, tint)
    return RggbChannelVector(v[0], v[1], v[2], v[3])
}

/**
 * Pure core of [kelvinTintToRggbGains] as `[R, G_even, G_odd, B]` — RggbChannelVector's constructor
 * is an unmocked android.jar stub on the JVM, so the math is only unit-testable in this form.
 */
internal fun kelvinTintToRggbGainValues(kelvin: Int, tint: Int): FloatArray {
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

    val cap = MAX_WB_CHANNEL_GAIN.toDouble()
    return floatArrayOf(
        gainR.coerceAtMost(cap).toFloat(),
        gainG.coerceAtMost(cap).toFloat(),
        gainG.coerceAtMost(cap).toFloat(),
        gainB.coerceAtMost(cap).toFloat(),
    )
}
