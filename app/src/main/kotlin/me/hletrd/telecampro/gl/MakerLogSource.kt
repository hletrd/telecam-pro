package me.hletrd.telecampro.gl

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
}
