package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE
import com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM
import com.hletrd.findx9tele.camera.TELE_ZOOM_SNAPS
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ZoomBounds(val lower: Float, val upper: Float)

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
): ModeOptics {
    if (fromMode == toMode || teleconverter) return ModeOptics(lens, controls)
    return if (toMode == CaptureMode.VIDEO) {
        val band = LensChoice.forZoom(controls.zoomRatio)
        ModeOptics(
            lens = band,
            controls = controls.copy(
                zoomRatio = (controls.zoomRatio / band.zoomPreset).coerceIn(1f, MAX_VIDEO_LOCAL_ZOOM),
            ),
        )
    } else {
        ModeOptics(
            lens = lens,
            controls = controls.copy(
                zoomRatio = (lens.zoomPreset * controls.zoomRatio.coerceAtLeast(1f))
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
