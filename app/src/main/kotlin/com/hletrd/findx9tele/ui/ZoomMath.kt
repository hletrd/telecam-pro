package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE
import com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM
import com.hletrd.findx9tele.camera.TELE_ZOOM_SNAPS
import com.hletrd.findx9tele.camera.normalizedForCaptureMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

internal data class ZoomBounds(val lower: Float, val upper: Float)

/**
 * The ONE main-relative zoom DISPLAY multiplier (DES4-1): TELE uses the constant converter scale
 * (13–60× round numbers; the caps-measured 69.4 mm would read 59.5× at the 60× ceiling), other
 * routes use openedLensEquiv ÷ mainEquiv (≈3.0× at the 3× tele's native position). The HUD pill
 * and the Fn/My-Menu ZOOM value MUST both read through this — the Fn tile used to show the raw
 * lens-local ratio ("2.3×") while the pill showed "30.0×" for the identical physical state. (The
 * Shooting-tab slider and Zoom ruler are EDIT surfaces on the lens-local scale outside TELE and
 * deliberately keep their own base.)
 */
internal fun zoomDisplayMultiplier(
    teleconverter: Boolean,
    equivalentFocalMm: Float?,
    frontFacing: Boolean = false,
): Float = when {
    // The main-relative scale is a REAR concept (which rear lens the unified zoom sits on). The
    // front camera has no place on it — front-equiv ÷ main-equiv would read "0.9×" at the selfie
    // 1× — so front zoom displays as its honest lens-local ratio.
    frontFacing -> 1f
    teleconverter -> TELE_DISPLAY_BASE
    else -> (equivalentFocalMm ?: LensChoice.MAIN.targetEquivMm) / LensChoice.MAIN.targetEquivMm
}

/** Camera-style zoom typography shared by every read-only zoom surface. */
internal fun formatZoomMultiplier(zoom: Float): String = "%.1f×".format(Locale.US, zoom)

/** Main-relative display value for a lens-local zoom request. */
internal fun formatDisplayZoom(
    localZoomRatio: Float,
    teleconverter: Boolean,
    equivalentFocalMm: Float?,
    frontFacing: Boolean = false,
): String = formatZoomMultiplier(
    localZoomRatio * zoomDisplayMultiplier(teleconverter, equivalentFocalMm, frontFacing),
)

/** One zoom range shared by input targets and the value that can actually be applied. */
internal fun effectiveZoomBounds(
    capsLower: Float?,
    capsUpper: Float?,
    teleconverter: Boolean,
): ZoomBounds? {
    if (!teleconverter) {
        if (capsLower == null || capsUpper == null || capsLower > capsUpper) return null
        return ZoomBounds(capsLower, capsUpper)
    }
    val teleUpper = TELE_MAX_DISPLAY_ZOOM / TELE_DISPLAY_BASE
    val lower = max(1f, capsLower ?: 1f)
    val upper = min(teleUpper, capsUpper ?: teleUpper)
    return if (lower <= upper) ZoomBounds(lower, upper) else ZoomBounds(upper, upper)
}

/**
 * Applies TELE's magnetic marks with hysteresis. Entering or crossing a mark snaps once; a value
 * already at/inside that snap band can move away in small increments instead of being trapped.
 */
internal fun normalizeZoomRequest(
    requested: Float,
    currentApplied: Float,
    bounds: ZoomBounds?,
    teleconverter: Boolean,
): Float {
    var value = bounds?.let { requested.coerceIn(it.lower, it.upper) } ?: requested
    if (!teleconverter || !value.isFinite()) return value

    val requestedDisplay = value * TELE_DISPLAY_BASE
    val currentDisplay = currentApplied * TELE_DISPLAY_BASE
    val snap = TELE_ZOOM_SNAPS.firstOrNull { mark ->
        val band = mark * SNAP_FRACTION
        val requestedDistance = abs(requestedDisplay - mark)
        val currentDistance = abs(currentDisplay - mark)
        val enteringBand = currentDistance >= band && requestedDistance < band
        val crossingMark = currentDistance > SNAP_EPSILON &&
            (currentDisplay - mark) * (requestedDisplay - mark) < 0f
        (enteringBand || crossingMark) && requestedDistance < band
    }
    if (snap != null) value = snap / TELE_DISPLAY_BASE
    return bounds?.let { value.coerceIn(it.lower, it.upper) } ?: value
}

/** One ~30 Hz hardware-glide tick decision. */
internal sealed interface ZoomEaseStep {
    /** Apply [value] and keep the glide ticking. */
    data class Step(val value: Float) : ZoomEaseStep

    /** Apply [target] once and stop the glide. */
    data class Land(val target: Float) : ZoomEaseStep
}

/**
 * One hardware-glide tick: exponential approach in log-zoom space (`cur * (target/cur)^0.4`), so
 * the sweep feels like a powered zoom rocker. Lands (applies the exact target and stops) when the
 * remaining log-distance is inside [EASE_LANDING_LOG], or immediately when either value is
 * non-finite/non-positive — a corrupted current ratio (0/NaN) would otherwise make pow/ln produce
 * NaN, and NaN comparisons are always false, keeping a ~30 Hz ticker alive forever.
 */
internal fun zoomEaseStep(current: Float, target: Float): ZoomEaseStep {
    if (!current.isFinite() || current <= 0f) return ZoomEaseStep.Land(target)
    val next = (current * Math.pow((target / current).toDouble(), EASE_EXPONENT)).toFloat()
    if (!next.isFinite() || next <= 0f) return ZoomEaseStep.Land(target)
    if (abs(kotlin.math.ln((target / next).toDouble())) < EASE_LANDING_LOG) {
        return ZoomEaseStep.Land(target)
    }
    return ZoomEaseStep.Step(next)
}

internal data class RestoredOptics(
    val lens: LensChoice,
    val teleconverter: Boolean,
    val zoomRatio: Float,
)

internal data class ModeOptics(
    val lens: LensChoice,
    val controls: ManualControls,
)

internal data class ModeExposureState(
    val controls: ManualControls,
    val photoExposureTimeNs: Long,
)

internal data class RestoredExposureState(
    val activeExposureTimeNs: Long,
    val photoExposureTimeNs: Long,
)

/**
 * Returns whether the currently published caps describe a restored target route. Photo's non-TELE
 * focal presets all share the logical camera, while Video presets select distinct standalone
 * cameras. Hidden Photo exposure memory never uses Video caps; this predicate only governs the
 * restored packet that is about to become active.
 */
internal fun restoredRouteUsesCurrentCaps(
    cameraReady: Boolean,
    currentMode: CaptureMode,
    currentLens: LensChoice,
    currentTeleconverter: Boolean,
    currentOverrideId: String?,
    targetMode: CaptureMode,
    targetLens: LensChoice,
    targetTeleconverter: Boolean,
    currentFrontFacing: Boolean = false,
): Boolean {
    // A recall always targets a REAR route (facing is never persisted, and setResolvedOptics exits
    // FRONT). While FRONT the current mode/lens fields can coincidentally equal the target's, but
    // the live caps describe the front camera — never authoritative for the recalled route.
    if (currentFrontFacing) return false
    if (
        !cameraReady || currentOverrideId != null || currentMode != targetMode ||
        currentTeleconverter != targetTeleconverter
    ) {
        return false
    }
    return targetTeleconverter || targetMode == CaptureMode.PHOTO || currentLens == targetLens
}

/** Preserves an inactive Photo shutter until authoritative Photo-route caps can validate it. */
internal fun restoredExposureState(
    targetMode: CaptureMode,
    activeExposureTimeNs: Long,
    storedPhotoExposureTimeNs: Long,
    authoritativeMinNs: Long?,
    authoritativeMaxNs: Long?,
): RestoredExposureState {
    val active = if (
        authoritativeMinNs != null && authoritativeMaxNs != null &&
        authoritativeMinNs <= authoritativeMaxNs
    ) {
        activeExposureTimeNs.coerceIn(authoritativeMinNs, authoritativeMaxNs)
    } else {
        activeExposureTimeNs.coerceAtLeast(1L)
    }
    return RestoredExposureState(
        activeExposureTimeNs = active,
        photoExposureTimeNs = if (targetMode == CaptureMode.PHOTO) {
            active
        } else {
            storedPhotoExposureTimeNs.coerceAtLeast(1L)
        },
    )
}

/**
 * Retains the photographer's Photo shutter while Video applies its fixed-frame-rate ceiling.
 * [ManualControls.exposureTimeNs] also holds the dormant SPEED value while ANGLE is selected, so it
 * must round-trip even when the active angle itself already fits within one frame.
 */
internal fun modeExposureState(
    fromMode: CaptureMode,
    toMode: CaptureMode,
    controls: ManualControls,
    rememberedPhotoExposureTimeNs: Long,
): ModeExposureState {
    val remembered = if (fromMode == CaptureMode.PHOTO) {
        controls.exposureTimeNs
    } else {
        rememberedPhotoExposureTimeNs.coerceAtLeast(1L)
    }
    val targetControls = if (fromMode == CaptureMode.VIDEO && toMode == CaptureMode.PHOTO) {
        controls.copy(exposureTimeNs = remembered)
    } else {
        controls
    }
    return ModeExposureState(targetControls, remembered)
}

/**
 * Resolves one Photo/Video transition. Photo zoom is unified/main-relative; non-TELE Video zoom is
 * local to the selected standalone lens. TELE is local in both modes and therefore stays unchanged.
 */
internal fun remapModeOptics(
    fromMode: CaptureMode,
    toMode: CaptureMode,
    lens: LensChoice,
    teleconverter: Boolean,
    controls: ManualControls,
    frontFacing: Boolean = false,
): ModeOptics {
    // PROGRAM is app-owned in Photo but normally HAL-owned in Video. Clear the Photo-derived flag
    // on an actual Video entry; route capability normalization may re-enable it later when a sparse
    // camera exposes only AE_OFF.
    val targetControls = if (fromMode != CaptureMode.VIDEO && toMode == CaptureMode.VIDEO) {
        controls.copy(programAppSide = false)
    } else {
        controls
    }
    val modeControls = targetControls.normalizedForCaptureMode(toMode)
    // FRONT is one camera in both modes with lens-local zoom throughout — like TELE, the unified↔
    // local remap does not apply (it would rewrite the retained rear band from a front-local ratio).
    if (fromMode == toMode || teleconverter || frontFacing) return ModeOptics(lens, modeControls)
    return if (toMode == CaptureMode.VIDEO) {
        val band = LensChoice.forZoom(modeControls.zoomRatio)
        ModeOptics(
            lens = band,
            controls = modeControls.copy(
                zoomRatio = (modeControls.zoomRatio / band.zoomPreset).coerceIn(1f, MAX_VIDEO_LOCAL_ZOOM),
            ),
        )
    } else {
        ModeOptics(
            lens = lens,
            controls = modeControls.copy(
                zoomRatio = (lens.zoomPreset * modeControls.zoomRatio.coerceAtLeast(1f))
                    .coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_UNIFIED_ZOOM),
            ),
        )
    }
}

/** Resolves the exact lens-local/unified zoom representation used by both engine and UI restore. */
internal fun restoredOptics(
    mode: CaptureMode,
    requestedLens: LensChoice,
    teleconverter: Boolean,
    savedZoomRatio: Float,
): RestoredOptics {
    val safeZoom = savedZoomRatio.takeIf { it.isFinite() } ?: when {
        teleconverter || mode == CaptureMode.VIDEO -> 1f
        else -> requestedLens.zoomPreset
    }
    if (teleconverter) {
        return RestoredOptics(
            lens = LensChoice.TELE3X,
            teleconverter = true,
            zoomRatio = safeZoom.coerceIn(1f, TELE_MAX_DISPLAY_ZOOM / TELE_DISPLAY_BASE),
        )
    }
    return if (mode == CaptureMode.VIDEO) {
        RestoredOptics(requestedLens, false, safeZoom.coerceIn(1f, MAX_VIDEO_LOCAL_ZOOM))
    } else {
        val unified = safeZoom.coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_UNIFIED_ZOOM)
        RestoredOptics(LensChoice.forZoom(unified), false, unified)
    }
}

private const val SNAP_FRACTION = 0.06f
private const val SNAP_EPSILON = 0.001f
private const val EASE_EXPONENT = 0.4
private const val EASE_LANDING_LOG = 0.004
private const val MIN_PHOTO_ZOOM = 0.6f
private const val MAX_PHOTO_UNIFIED_ZOOM = 20f
private const val MAX_VIDEO_LOCAL_ZOOM = 10f
