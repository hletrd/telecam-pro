package me.hletrd.telecampro.gl

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Decode the REAL O-Log2 source for monitor assists. Recording at unity gain passes the source
 * codes through; this is not a selectable maker curve applied to an SDR/S-Log3 source.
 * Constants follow the OPPO O-Log2 paper, with the continuous Rt=0.01 join (see research history).
 */
internal object MakerLogSource {
    const val R0 = -0.05641088
    const val CUT = 0.01
    const val TOE = 47.28711236
    const val BETA = 0.00964052
    const val GAMMA = 0.08550479
    const val DELTA = 0.69336945
    const val CUT_CODE = TOE * (CUT - R0) * (CUT - R0)

    fun decode(code: Double): Double = if (code >= CUT_CODE) {
        2.0.pow((code - DELTA) / GAMMA) - BETA
    } else {
        sqrt(code.coerceAtLeast(0.0) / TOE) + R0
    }
}
