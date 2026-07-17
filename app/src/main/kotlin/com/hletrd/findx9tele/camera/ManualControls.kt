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

/** Returns [requested] only when Camera2 advertised that exact integer mode value. */
internal fun exactAdvertisedMode(requested: Int, advertised: IntArray): Int? =
    requested.takeIf(advertised::contains)

/** Which metering-region keys may be emitted for one request. */
internal data class MeteringRegionTargets(val ae: Boolean, val af: Boolean)

internal fun meteringRegionTargets(
    maxAeRegions: Int,
    maxAfRegions: Int,
    focusMode: FocusMode,
): MeteringRegionTargets = MeteringRegionTargets(
    ae = maxAeRegions > 0,
    af = maxAfRegions > 0 && focusMode != FocusMode.MANUAL,
)

internal fun touchAfMayTrigger(
    touchAfActive: Boolean,
    maxAfRegions: Int,
    focusMode: FocusMode,
    afModes: IntArray,
): Boolean = touchAfActive && maxAfRegions > 0 && focusMode != FocusMode.MANUAL &&
    exactAdvertisedMode(CameraMetadata.CONTROL_AF_MODE_AUTO, afModes) != null

/** Normalizes a control packet against the selected route's flattened Camera2 capabilities. */
internal fun ManualControls.normalizedFor(caps: CameraCaps): ManualControls =
    normalizedFor(caps.controlCapabilities())

/**
 * Android-type-free normalization core. Unsupported selections fall back to a known advertised
 * enum value; when an entire mode array is absent, the neutral UI default is retained and request
 * construction omits that key.
 */
internal fun ManualControls.normalizedFor(caps: CameraControlCapabilities): ManualControls {
    val normalizedFocus = selectAdvertisedEnum(
        requested = focusMode,
        fallbackOrder = listOf(FocusMode.CONTINUOUS, FocusMode.AUTO, FocusMode.MACRO, FocusMode.MANUAL),
        advertised = caps.afModes,
        metadata = FocusMode::afMetadata,
        allowed = { it != FocusMode.MANUAL || caps.supportsManualFocus && caps.hasFocusDistanceRange },
    ) ?: FocusMode.CONTINUOUS

    val requestedWbAllowed = when (wbMode) {
        WbMode.MANUAL -> caps.supportsManualPostProcessing
        WbMode.CUSTOM -> caps.supportsManualPostProcessing && customWbGains != null
        else -> true
    }
    val normalizedWb = if (requestedWbAllowed &&
        exactAdvertisedMode(wbMode.awbMetadata, caps.awbModes) != null
    ) {
        wbMode
    } else {
        selectAdvertisedEnum(
            requested = WbMode.AUTO,
            fallbackOrder = listOf(
                WbMode.AUTO,
                WbMode.DAYLIGHT,
                WbMode.CLOUDY,
                WbMode.SHADE,
                WbMode.INCANDESCENT,
                WbMode.FLUORESCENT,
                WbMode.MANUAL,
            ),
            advertised = caps.awbModes,
            metadata = WbMode::awbMetadata,
            allowed = { it != WbMode.MANUAL || caps.supportsManualPostProcessing },
        ) ?: WbMode.AUTO
    }

    val manualAeAvailable = caps.supportsManualSensor && caps.hasIsoRange && caps.hasExposureTimeRange &&
        exactAdvertisedMode(CameraMetadata.CONTROL_AE_MODE_OFF, caps.aeModes) != null
    var normalizedExposureMode = exposureMode
    var normalizedProgramAppSide = programAppSide && exposureMode == ExposureMode.PROGRAM
    var normalizedFlash = if (caps.flashAvailable) flash else FlashMode.OFF

    if (normalizedExposureMode != ExposureMode.PROGRAM && !manualAeAvailable) {
        normalizedExposureMode = ExposureMode.PROGRAM
        normalizedProgramAppSide = false
    }
    if (normalizedProgramAppSide && !manualAeAvailable) normalizedProgramAppSide = false
    // AUTO/ON flash metering requires HAL AE. If that exact AE variant exists, route Program back
    // to HAL AE; otherwise keep app-side Program and fall back to flash-off.
    if (normalizedExposureMode == ExposureMode.PROGRAM && normalizedProgramAppSide &&
        (normalizedFlash == FlashMode.AUTO || normalizedFlash == FlashMode.ON)
    ) {
        if (autoFlashAvailable(normalizedFlash, caps)) {
            normalizedProgramAppSide = false
        } else {
            normalizedFlash = FlashMode.OFF
        }
    }
    // OFF and TORCH do not need HAL flash metering. On an AE_OFF-only route, move Program to the
    // app-side program line before normalization so TORCH remains a truthful, usable selection.
    if (normalizedExposureMode == ExposureMode.PROGRAM && !normalizedProgramAppSide &&
        (normalizedFlash == FlashMode.OFF || normalizedFlash == FlashMode.TORCH) &&
        !autoFlashAvailable(normalizedFlash, caps) && manualAeAvailable
    ) {
        normalizedProgramAppSide = true
    }
    val usesManualAe = normalizedExposureMode != ExposureMode.PROGRAM || normalizedProgramAppSide
    normalizedFlash = if (usesManualAe) {
        normalizedFlash.takeIf { it == FlashMode.TORCH && caps.flashAvailable } ?: FlashMode.OFF
    } else {
        normalizeAutoFlash(normalizedFlash, caps)
    }
    // A sparse route may expose only AE_OFF. Program can still be truthful by using the existing
    // app-side program line; publishing HAL Program would otherwise select a mode we cannot set.
    if (normalizedExposureMode == ExposureMode.PROGRAM && !normalizedProgramAppSide &&
        normalizedFlash == FlashMode.OFF &&
        exactAdvertisedMode(CameraMetadata.CONTROL_AE_MODE_ON, caps.aeModes) == null &&
        manualAeAvailable
    ) {
        normalizedProgramAppSide = true
    }

    val normalizedAntibanding = selectAdvertisedEnum(
        requested = antibanding,
        fallbackOrder = listOf(Antibanding.AUTO, Antibanding.OFF, Antibanding.HZ50, Antibanding.HZ60),
        advertised = caps.antibandingModes,
        metadata = Antibanding::antibandingMetadata,
    ) ?: Antibanding.AUTO
    val normalizedEdge = selectAdvertisedEnum(
        requested = edge,
        fallbackOrder = listOf(ProcessingLevel.OFF, ProcessingLevel.FAST, ProcessingLevel.HIGH_QUALITY),
        advertised = caps.edgeModes,
        metadata = ProcessingLevel::edgeMetadata,
    ) ?: ProcessingLevel.OFF
    val normalizedNoise = selectAdvertisedEnum(
        requested = noiseReduction,
        fallbackOrder = listOf(ProcessingLevel.OFF, ProcessingLevel.FAST, ProcessingLevel.HIGH_QUALITY),
        advertised = caps.noiseReductionModes,
        metadata = ProcessingLevel::noiseMetadata,
    ) ?: ProcessingLevel.OFF
    val normalizedEffect = selectAdvertisedEnum(
        requested = colorEffect,
        fallbackOrder = listOf(
            ColorEffect.NONE,
            ColorEffect.MONO,
            ColorEffect.NEGATIVE,
            ColorEffect.SEPIA,
            ColorEffect.AQUA,
            ColorEffect.POSTERIZE,
        ),
        advertised = caps.effectModes,
        metadata = ColorEffect::metadata,
    ) ?: ColorEffect.NONE

    return copy(
        focusMode = normalizedFocus,
        afLock = afLock && normalizedFocus != FocusMode.MANUAL && caps.supportsManualFocus &&
            caps.hasFocusDistanceRange &&
            exactAdvertisedMode(CameraMetadata.CONTROL_AF_MODE_OFF, caps.afModes) != null,
        exposureMode = normalizedExposureMode,
        programAppSide = normalizedProgramAppSide,
        aeLock = aeLock && normalizedExposureMode == ExposureMode.PROGRAM &&
            !normalizedProgramAppSide &&
            exactAdvertisedMode(normalizedFlash.autoAeMetadata, caps.aeModes) != null,
        wbMode = normalizedWb,
        awbLock = awbLock && normalizedWb == WbMode.AUTO &&
            exactAdvertisedMode(CameraMetadata.CONTROL_AWB_MODE_AUTO, caps.awbModes) != null,
        antibanding = normalizedAntibanding,
        meteringMode = if (caps.maxAeRegions > 0) meteringMode else MeteringMode.MATRIX,
        edge = normalizedEdge,
        noiseReduction = normalizedNoise,
        colorEffect = normalizedEffect,
        flash = normalizedFlash,
    )
}

private fun <T> selectAdvertisedEnum(
    requested: T,
    fallbackOrder: List<T>,
    advertised: IntArray,
    metadata: (T) -> Int,
    allowed: (T) -> Boolean = { true },
): T? {
    if (allowed(requested) && exactAdvertisedMode(metadata(requested), advertised) != null) return requested
    return fallbackOrder.firstOrNull {
        allowed(it) && exactAdvertisedMode(metadata(it), advertised) != null
    }
}

private fun normalizeAutoFlash(
    requested: FlashMode,
    caps: CameraControlCapabilities,
): FlashMode = requested.takeIf { autoFlashAvailable(it, caps) } ?: FlashMode.OFF

private fun autoFlashAvailable(mode: FlashMode, caps: CameraControlCapabilities): Boolean =
    (mode == FlashMode.OFF || caps.flashAvailable) &&
        exactAdvertisedMode(mode.autoAeMetadata, caps.aeModes) != null

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

// HAL-safety ceiling for ANY repeating (preview) request exposure, all AE-OFF modes. A multi-second
// SENSOR_EXPOSURE_TIME on the REPEATING request wedges this QTI HAL's still handoff: a queued still
// sits inert behind the in-flight long frame and the device errors with CAMERA_ERROR(3) after ~one
// exposure duration — device-reproduced 3/3 at 6.3 s (S mode, and P mode with the ISO ceiling
// exhausted so the neutral trade above was skipped); the 0.8 s control repeated fine. 500 ms keeps
// a ≥2 fps live view with safety margin under the proven 0.8 s. The STILL request is untouched and
// carries the true exposure — only the viewfinder trades exposure for ISO (brightness-neutrally
// while headroom lasts, then honestly darker).
internal const val PREVIEW_SAFE_MAX_EXPOSURE_NS = 500_000_000L

/**
 * Preview-only exposure→ISO trade for the repeating request, pure for host tests.
 * [neutralCapNs] is the mode's fps-motivated target (PROGRAM's 1/30 s) or null for user-owned
 * S/ISO/M modes (WYSIWYG below the safety ceiling); [safetyCapNs] is the unconditional HAL bound.
 * Returns the exposure/ISO pair the REPEATING request should carry.
 */
internal fun previewExposureTrade(
    wantExposureNs: Long,
    iso: Int,
    isoUpper: Int,
    neutralCapNs: Long?,
    safetyCapNs: Long = PREVIEW_SAFE_MAX_EXPOSURE_NS,
): Pair<Long, Int> {
    val capNs = neutralCapNs ?: safetyCapNs
    if (wantExposureNs <= capNs || iso <= 0) {
        // Below every applicable ceiling (or no usable ISO to trade against): WYSIWYG.
        return wantExposureNs.coerceAtMost(safetyCapNs) to iso
    }
    // Brightness-neutral first: shorten exposure and raise ISO by the same factor, bounded by the
    // advertised ISO headroom. The 1.05 floor avoids churning the request for sub-tenth-stop trades.
    var exposureNs = wantExposureNs
    var tradedIso = iso
    val scale = minOf(wantExposureNs.toDouble() / capNs, isoUpper.toDouble() / iso)
    if (scale > 1.05) {
        exposureNs = (wantExposureNs / scale).toLong()
        tradedIso = (iso * scale).toInt().coerceAtMost(isoUpper)
    }
    // The safety clamp is NOT conditional on headroom — with the ISO ceiling exhausted the preview
    // gets darker instead of carrying a HAL-lethal long frame (the exact skipped-trade path that
    // shipped the 6.3 s crash).
    return exposureNs.coerceAtMost(safetyCapNs) to tradedIso
}

fun ManualControls.effectiveExposureNs(): Long =
    if (shutterMode == ShutterMode.ANGLE && fps > 0) {
        ((shutterAngle.coerceIn(1f, 360f) / 360.0) / fps * 1_000_000_000.0).toLong()
    } else {
        exposureTimeNs
    }

/** The exact app-owned exposure placed on a request after applying the advertised sensor range. */
internal fun ManualControls.clampedEffectiveExposureNs(minNs: Long?, maxNs: Long?): Long {
    return clampExposureNs(effectiveExposureNs(), minNs, maxNs)
}

private fun clampExposureNs(requestedNs: Long, minNs: Long?, maxNs: Long?): Long =
    if (minNs != null && maxNs != null && minNs <= maxNs) {
        requestedNs.coerceIn(minNs, maxNs)
    } else {
        requestedNs
    }

internal const val CAPTURE_WATCHDOG_FLOOR_MS = 8_000L
internal const val CAPTURE_DELIVERY_MARGIN_MS = 8_000L

/**
 * Deadline for a pending still. HAL-owned exposure keeps the historical [floorMs]; an app-owned
 * exposure adds its full, request-clamped duration to a fixed delivery/readout margin. Arithmetic
 * saturates so malformed/extreme capability values can never wrap into an immediate timeout.
 */
internal fun captureWatchdogTimeoutMs(
    clampedExposureNs: Long?,
    deliveryMarginMs: Long = CAPTURE_DELIVERY_MARGIN_MS,
    floorMs: Long = CAPTURE_WATCHDOG_FLOOR_MS,
): Long {
    val floor = floorMs.coerceAtLeast(0L)
    if (clampedExposureNs == null) return floor

    val exposureNs = clampedExposureNs.coerceAtLeast(0L)
    val wholeMs = exposureNs / 1_000_000L
    val exposureMs = wholeMs + if (exposureNs % 1_000_000L == 0L) 0L else 1L
    val margin = deliveryMarginMs.coerceAtLeast(0L)
    val exposureAndMargin = if (exposureMs > Long.MAX_VALUE - margin) {
        Long.MAX_VALUE
    } else {
        exposureMs + margin
    }
    return maxOf(floor, exposureAndMargin)
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

/**
 * True when [next] differs from [previous] ONLY in the high-churn sensor scalars (manual focus
 * distance, ISO, exposure time) — the keys a focus/ISO/shutter drag or the app-side AE loop
 * rewrites continuously. The copy-normalize-compare shape is exhaustive by data-class equality:
 * a future ManualControls field defaults to "must match", so it can never silently ride the
 * fast path.
 */
internal fun sensorOnlyControlsDelta(previous: ManualControls, next: ManualControls): Boolean =
    previous != next && previous.copy(
        focusDistanceDiopters = next.focusDistanceDiopters,
        iso = next.iso,
        exposureTimeNs = next.exposureTimeNs,
    ) == next

/**
 * Full sensor fast-path admission: the delta must be sensor-only AND no AF override may be live
 * on the cached builder. Tap-to-focus (a one-shot AF_MODE_AUTO hold) and AF lock (AF_MODE_OFF +
 * frozen LENS_FOCUS_DISTANCE) are applied by the full rebuild AFTER [applyManualControls] — the
 * fast path re-runs [applySensorValueControls]→applyFocus, whose unconditional CONTROL_AF_MODE
 * write would silently RELEASE them; the app-side AE loop fires sensor-only deltas ~6×/s, so a
 * tapped focus would unlock within ~100 ms. While an override is live every delta takes the full
 * rebuild, which re-applies the override (those states are transient, and a quiet lens is wanted
 * then anyway).
 */
internal fun sensorFastPathAdmitted(
    previous: ManualControls,
    next: ManualControls,
    touchAfActive: Boolean,
): Boolean = !touchAfActive && !next.afLock && sensorOnlyControlsDelta(previous, next)

/**
 * The high-churn sensor half of [applyManualControls] — the SAME derivation functions the full
 * request build uses (MF distance clamp; manual-AE ISO/exposure clamps, preview exposure cap,
 * frame-duration stretch), so the fast path's VALUES can never drift from the full rebuild. The
 * re-applied AE mode key rewrites its current value (flash/AE resolution lives inside
 * applyManualControls and the admission delta pins those fields). The AF mode key is NOT safe by
 * construction — tap-to-focus/AF-lock overrides live OUTSIDE applyManualControls in the full
 * rebuild — which is exactly why [sensorFastPathAdmitted] refuses the fast path while either
 * override is live.
 */
internal fun CaptureRequest.Builder.applySensorValueControls(
    c: ManualControls,
    caps: CameraCaps,
    pinAutoFps: Boolean,
    previewExposureCap: Boolean,
) {
    applyFocus(c, caps)
    applyExposure(c, caps, pinAutoFps, previewExposureCap)
}

internal val FocusMode.afMetadata: Int
    get() = when (this) {
        FocusMode.MANUAL -> CameraMetadata.CONTROL_AF_MODE_OFF
        FocusMode.AUTO -> CameraMetadata.CONTROL_AF_MODE_AUTO
        FocusMode.CONTINUOUS -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        FocusMode.MACRO -> CameraMetadata.CONTROL_AF_MODE_MACRO
    }

internal val Antibanding.antibandingMetadata: Int
    get() = when (this) {
        Antibanding.AUTO -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        Antibanding.HZ50 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
        Antibanding.HZ60 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
        Antibanding.OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
    }

internal val FlashMode.autoAeMetadata: Int
    get() = when (this) {
        FlashMode.OFF, FlashMode.TORCH -> CameraMetadata.CONTROL_AE_MODE_ON
        FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
        FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
    }

private fun CaptureRequest.Builder.applyFocus(c: ManualControls, caps: CameraCaps) {
    // Expression-position `when` so the compiler enforces exhaustiveness: a statement-position enum
    // `when` compiles fine with a missing case and silently no-ops it — the session would keep the
    // PREVIOUS AF mode while the UI shows the new one (the exact silent-drift trap a future 5th
    // FocusMode would spring).
    val afMode = c.focusMode.afMetadata
    exactAdvertisedMode(afMode, caps.afModes)?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
    if (c.focusMode == FocusMode.MANUAL && caps.supportsManualFocus &&
        exactAdvertisedMode(CameraMetadata.CONTROL_AF_MODE_OFF, caps.afModes) != null
    ) {
        set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistanceDiopters))
    }
}

private fun CaptureRequest.Builder.applyExposure(
    c: ManualControls,
    caps: CameraCaps,
    pinAutoFps: Boolean,
    previewExposureCap: Boolean = false,
) {
    val isoRange = caps.isoRange
    val exposureRange = caps.exposureTimeRange
    val manualAe = !c.autoExposure && caps.supportsManualSensor &&
        isoRange != null && exposureRange != null &&
        exactAdvertisedMode(CameraMetadata.CONTROL_AE_MODE_OFF, caps.aeModes) != null
    if (manualAe) {
        val admittedIsoRange = checkNotNull(isoRange)
        val admittedExposureRange = checkNotNull(exposureRange)
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        var iso = c.iso
        var wantExposureNs = c.effectiveExposureNs()
        // PREVIEW-only exposure policy (still requests never pass previewExposureCap):
        //  - PROGRAM: its line legitimately parks the STILL exposure at 1/14-1/10 s in dim light,
        //    but a preview at that exposure IS a 10-15 fps viewfinder — trade exposure for ISO
        //    BRIGHTNESS-NEUTRALLY toward 1/30 s, bounded by ISO headroom.
        //  - ALL AE-OFF modes: the PREVIEW_SAFE_MAX_EXPOSURE_NS clamp applies UNCONDITIONALLY —
        //    a multi-second repeating frame wedges this HAL's still handoff (CAMERA_ERROR(3),
        //    lost shot, device-reproduced at 6.3 s). S/ISO/M stay WYSIWYG below that ceiling;
        //    above it the preview brightens via ISO while headroom lasts, then honestly darkens.
        if (previewExposureCap) {
            val neutralCap = if (c.exposureMode == ExposureMode.PROGRAM) PREVIEW_MAX_EXPOSURE_NS else null
            val (tradedExposure, tradedIso) = previewExposureTrade(
                wantExposureNs = wantExposureNs,
                iso = iso,
                isoUpper = admittedIsoRange.upper,
                neutralCapNs = neutralCap,
            )
            wantExposureNs = tradedExposure
            iso = tradedIso
        }
        set(
            CaptureRequest.SENSOR_SENSITIVITY,
            iso.coerceIn(admittedIsoRange.lower, admittedIsoRange.upper),
        )
        val exposureNs = clampExposureNs(
            requestedNs = wantExposureNs,
            minNs = admittedExposureRange.lower,
            maxNs = admittedExposureRange.upper,
        )
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        // Frame duration must be >= the exposure (Camera2 contract). Stretch it to the exposure so a
        // shutter slower than 1/fps survives instead of being clamped to 1/fps (long-exposure/astro).
        set(CaptureRequest.SENSOR_FRAME_DURATION, sensorFrameDurationNs(c.fps, exposureNs, caps.maxFrameDurationNs))
    } else {
        // Flash owns the final AE mode, but only an exact advertised variant may enter the builder.
        // Applying it here also makes AE lock/comp conditional on an actually available HAL-AE mode.
        exactAdvertisedMode(c.flash.autoAeMetadata, caps.aeModes)?.let { aeMode ->
            set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                c.exposureCompensation.coerceIn(caps.evRange.lower, caps.evRange.upper),
            )
        }
    }
    // Photo AUTO preview can lower fps for a brighter low-light view. Video AUTO must stay at the
    // selected rate; otherwise a 29.97p clip can silently become 25p when AE chooses 1/25 s.
    val fpsRange = if (pinAutoFps || manualAe) {
        caps.fixedFpsRange(c.fps)
    } else {
        caps.autoFpsRange(c.fps)
    }
    fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    exactAdvertisedMode(c.antibanding.antibandingMetadata, caps.antibandingModes)?.let {
        set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it)
    }
}

private fun CaptureRequest.Builder.applyWhiteBalance(c: ManualControls, caps: CameraCaps) {
    when (c.wbMode) {
        WbMode.MANUAL -> if (caps.supportsManualPostProcessing &&
            exactAdvertisedMode(CameraMetadata.CONTROL_AWB_MODE_OFF, caps.awbModes) != null
        ) {
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            // FAST (not TRANSFORM_MATRIX) honors COLOR_CORRECTION_GAINS without also requiring a
            // COLOR_CORRECTION_TRANSFORM — which we never set, so TRANSFORM_MATRIX left color undefined.
            set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinTintToRggbGains(c.wbKelvin, c.wbTint))
        }
        WbMode.CUSTOM -> {
            val g = c.customWbGains
            if (caps.supportsManualPostProcessing && g != null &&
                exactAdvertisedMode(CameraMetadata.CONTROL_AWB_MODE_OFF, caps.awbModes) != null
            ) {
                // Measured card WB: replay the AWB gains sampled at capture time (see FAST-mode note
                // on the MANUAL branch above).
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.r, g.gEven, g.gOdd, g.b))
            }
        }
        WbMode.AUTO -> {
            exactAdvertisedMode(CameraMetadata.CONTROL_AWB_MODE_AUTO, caps.awbModes)?.let {
                set(CaptureRequest.CONTROL_AWB_MODE, it)
                set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
            }
        }
        else -> exactAdvertisedMode(c.wbMode.awbMetadata, caps.awbModes)?.let {
            set(CaptureRequest.CONTROL_AWB_MODE, it)
        }
    }
}

/** UI WB selection -> its exact CONTROL_AWB_MODE_* value. */
internal val WbMode.awbMetadata: Int
    get() = when (this) {
        WbMode.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WbMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        WbMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WbMode.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WbMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        WbMode.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        WbMode.MANUAL, WbMode.CUSTOM -> CameraMetadata.CONTROL_AWB_MODE_OFF
    }

private fun CaptureRequest.Builder.applyProcessing(c: ManualControls, caps: CameraCaps) {
    exactAdvertisedMode(c.colorEffect.metadata, caps.effectModes)?.let {
        set(CaptureRequest.CONTROL_EFFECT_MODE, it)
    }
    exactAdvertisedMode(c.edge.edgeMetadata, caps.edgeModes)?.let { set(CaptureRequest.EDGE_MODE, it) }
    exactAdvertisedMode(c.noiseReduction.noiseMetadata, caps.noiseReductionModes)?.let {
        set(CaptureRequest.NOISE_REDUCTION_MODE, it)
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
    val aeManual = !c.autoExposure && caps.supportsManualSensor &&
        caps.isoRange != null && caps.exposureTimeRange != null &&
        exactAdvertisedMode(CameraMetadata.CONTROL_AE_MODE_OFF, caps.aeModes) != null
    if (aeManual) {
        if (caps.flashAvailable) {
            when (c.flash) {
                FlashMode.TORCH -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                else -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        }
    } else {
        // Expression-position `when`s for compiler-enforced exhaustiveness (see applyFocus): a new
        // FlashMode that forgets a branch here must fail the build, not silently keep the previous
        // AE flash variant on the repeating request.
        exactAdvertisedMode(c.flash.autoAeMetadata, caps.aeModes)?.let {
            set(CaptureRequest.CONTROL_AE_MODE, it)
        }
        val flashMode = when (c.flash) {
            FlashMode.OFF -> CameraMetadata.FLASH_MODE_OFF
            FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
            // The AE flash variants own the firing decision; FLASH_MODE stays unset for them.
            FlashMode.AUTO, FlashMode.ON -> null
        }
        if (caps.flashAvailable) flashMode?.let { set(CaptureRequest.FLASH_MODE, it) }
    }
}

private fun CaptureRequest.Builder.applyZoom(c: ManualControls, caps: CameraCaps) {
    caps.zoomRatioRange?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoomRatio.coerceIn(it.lower, it.upper)) }
}

internal val ColorEffect.metadata: Int
    get() = when (this) {
        ColorEffect.NONE -> CameraMetadata.CONTROL_EFFECT_MODE_OFF
        ColorEffect.MONO -> CameraMetadata.CONTROL_EFFECT_MODE_MONO
        ColorEffect.NEGATIVE -> CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE
        ColorEffect.SEPIA -> CameraMetadata.CONTROL_EFFECT_MODE_SEPIA
        ColorEffect.AQUA -> CameraMetadata.CONTROL_EFFECT_MODE_AQUA
        ColorEffect.POSTERIZE -> CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE
    }

internal val ProcessingLevel.edgeMetadata: Int
    get() = when (this) {
        ProcessingLevel.OFF -> CameraMetadata.EDGE_MODE_OFF
        ProcessingLevel.FAST -> CameraMetadata.EDGE_MODE_FAST
        ProcessingLevel.HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
    }

internal val ProcessingLevel.noiseMetadata: Int
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
