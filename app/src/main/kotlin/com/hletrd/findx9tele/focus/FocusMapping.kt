package com.hletrd.findx9tele.focus

import kotlin.math.pow

/**
 * Maps a linear UI slider [0,1] to a lens focus distance in diopters and back.
 *
 * Camera2 focus distance is in diopters: 0 = infinity, increasing toward the lens minimum focus
 * distance (closest). With an afocal teleconverter the phone lens sits near infinity, so we bias
 * slider resolution toward the near-infinity (small-diopter) end using a power curve (gamma > 1):
 *
 *   diopters = minFocusDiopters * slider^gamma
 *
 * Larger gamma => more of the slider's travel is spent in the fine, near-infinity region.
 * All functions are pure and unit-tested.
 */
object FocusMapping {
    const val DEFAULT_GAMMA = 3f

    fun sliderToDiopters(slider: Float, minFocusDiopters: Float, gamma: Float = DEFAULT_GAMMA): Float {
        if (minFocusDiopters <= 0f) return 0f
        val s = slider.coerceIn(0f, 1f).toDouble()
        return (minFocusDiopters * s.pow(gamma.toDouble())).toFloat()
    }

    fun dioptersToSlider(diopters: Float, minFocusDiopters: Float, gamma: Float = DEFAULT_GAMMA): Float {
        if (minFocusDiopters <= 0f) return 0f
        val ratio = (diopters / minFocusDiopters).coerceIn(0f, 1f).toDouble()
        return ratio.pow(1.0 / gamma.toDouble()).toFloat()
    }

    /** Human-readable focus distance. Infinity for diopters <= 0. */
    fun dioptersToMeters(diopters: Float): Float =
        if (diopters <= 0f) Float.POSITIVE_INFINITY else 1f / diopters
}
