package me.hletrd.telecampro.ui.controls

import androidx.compose.ui.input.key.Key
import kotlin.math.roundToInt

/**
 * Pure normalized keyboard policy shared by the settings slider and the physical finder ruler.
 * Horizontal arrows follow the slider's visual axis; vertical/Page/Home/End remain value-relative.
 */
internal fun sliderKeyTargetFraction(
    currentFraction: Float,
    key: Key,
    totalUnits: Int,
    rtlHorizontal: Boolean,
    enabled: Boolean,
): Float? {
    if (!enabled || !currentFraction.isFinite() || totalUnits <= 0) return null
    val current = currentFraction.coerceIn(0f, 1f)
    val unit = 1f / totalUnits
    val page = maxOf(1, totalUnits / 10) * unit
    val target = when (key) {
        Key.DirectionLeft -> current + if (rtlHorizontal) unit else -unit
        Key.DirectionRight -> current + if (rtlHorizontal) -unit else unit
        Key.DirectionUp -> current + unit
        Key.DirectionDown -> current - unit
        Key.PageUp -> current + page
        Key.PageDown -> current - page
        Key.MoveHome -> 0f
        Key.MoveEnd -> 1f
        else -> return null
    }
    return target.coerceIn(0f, 1f)
}

/** Number of keyboard intervals for a domain step, with the legacy normalized fallback. */
internal fun sliderDomainKeyUnits(span: Float, step: Float?, fallbackUnits: Int): Int {
    if (!span.isFinite() || span <= 0f || step == null || !step.isFinite() || step <= 0f) {
        return fallbackUnits
    }
    return (span / step).roundToInt().coerceAtLeast(1)
}

/** Maps a normalized target back into the domain and snaps it to the caller's representable grid. */
internal fun quantizedSliderDomainValue(
    fraction: Float,
    start: Float,
    endInclusive: Float,
    step: Float?,
): Float {
    val span = endInclusive - start
    val normalized = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val raw = start + normalized * span
    if (!span.isFinite() || span <= 0f || step == null || !step.isFinite() || step <= 0f) return raw
    val units = ((raw - start) / step).roundToInt()
    return (start + units * step).coerceIn(start, endInclusive)
}

/** Logical value ↔ physical horizontal position; mirroring is its own inverse. */
internal fun cameraSliderAxisFraction(fraction: Float, rtl: Boolean): Float {
    val normalized = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    return if (rtl) 1f - normalized else normalized
}
