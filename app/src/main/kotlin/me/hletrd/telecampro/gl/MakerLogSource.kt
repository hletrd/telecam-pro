package me.hletrd.telecampro.gl

import me.hletrd.telecampro.camera.MakerLogVendor
import kotlin.math.pow

/**
 * Monitor-only inverse of OPPO's O-Log2 ACES CTL/DCTL V2 (2026-05-22): a single log2 curve
 * over linear BT.2020. The official coefficients are 0.0855, 0.0096 and 0.693; Apple Log's
 * similar curve and quadratic toe are NOT interchangeable, especially in coloured shadows.
 * Recording passes the native source codes through without this inverse.
 * Source: https://www.oppo.com/en/o-log-technical-downloads/ (O-Log2 ACES Workflow).
 */
internal object MakerLogSource {
    const val BETA = 0.0096
    const val GAMMA = 0.0855
    const val DELTA = 0.693

    fun decode(code: Double): Double = 2.0.pow((code - DELTA) / GAMMA) - BETA

    /**
     * `uSourceMakerCurve`: which inverse the MONITOR path may apply to an accepted maker source.
     * [CURVE_OLOG2] (the renderer default, so every OPPO draw is unchanged) is the official inverse
     * above. [CURVE_AS_DELIVERED] is vivo: vivo publishes no Log formula, and this app will not invent
     * one, so the display path shows the source codes as delivered — Gamma Display Assist cannot
     * normalize a vivo source. Recording is the code-9 passthrough for both.
     */
    const val CURVE_OLOG2 = 0
    const val CURVE_AS_DELIVERED = 1

    /** The curve for an accepted session's vendor; null (no maker session) keeps the default. */
    fun curveFor(vendor: MakerLogVendor?): Int =
        if (vendor == MakerLogVendor.VIVO) CURVE_AS_DELIVERED else CURVE_OLOG2
}
