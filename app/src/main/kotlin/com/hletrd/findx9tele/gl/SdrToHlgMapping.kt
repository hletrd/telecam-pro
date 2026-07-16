package com.hletrd.findx9tele.gl

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Android-free reference for the simplified display-referred SDR-to-HLG mapping in
 * ITU-R BT.2408-9 §5.1.3.4.
 *
 * The camera preview is already a display-referred SDR signal. The mapping therefore decodes that
 * signal with the BT.1886 2.4-power approximation, converts linear-light BT.709 to BT.2020, scales
 * normalized display light so SDR reference white lands at 75% HLG, applies the simplified inverse
 * HLG OOTF independently to each component, and finally applies the BT.2100 HLG OETF. This does not
 * recover highlights that were removed by the ISP's SDR tone mapping.
 */
internal object SdrToHlgMapping {
    const val SDR_EOTF_GAMMA = 2.4
    const val NORMALIZED_DISPLAY_LIGHT_SCALE = 0.2546
    const val HLG_SYSTEM_GAMMA = 1.03

    const val HLG_A = 0.17883277
    const val HLG_B = 0.28466892
    const val HLG_C = 0.55991073

    // Linear-light BT.709 -> BT.2020 matrix. These are shared verbatim with the shader and retain
    // the existing coefficients used by the O-Log2 path.
    const val R_FROM_R = 0.6274
    const val R_FROM_G = 0.3293
    const val R_FROM_B = 0.0433
    const val G_FROM_R = 0.0691
    const val G_FROM_G = 0.9195
    const val G_FROM_B = 0.0114
    const val B_FROM_R = 0.0164
    const val B_FROM_G = 0.0880
    const val B_FROM_B = 0.8956

    fun encode(red: Double, green: Double, blue: Double): Rgb {
        val linear709 = Rgb(red, green, blue).map { it.coerceIn(0.0, 1.0).pow(SDR_EOTF_GAMMA) }
        val linear2020 = Rgb(
            red = R_FROM_R * linear709.red + R_FROM_G * linear709.green + R_FROM_B * linear709.blue,
            green = G_FROM_R * linear709.red + G_FROM_G * linear709.green + G_FROM_B * linear709.blue,
            blue = B_FROM_R * linear709.red + B_FROM_G * linear709.green + B_FROM_B * linear709.blue,
        )
        val inverseOotfExponent = 1.0 / HLG_SYSTEM_GAMMA
        val hlgSceneLight = linear2020.map {
            (it * NORMALIZED_DISPLAY_LIGHT_SCALE).coerceAtLeast(0.0).pow(inverseOotfExponent)
        }
        return hlgSceneLight.map(::hlgOetf)
    }

    private fun hlgOetf(value: Double): Double = when {
        value <= 0.0 -> 0.0
        value <= 1.0 / 12.0 -> sqrt(3.0 * value)
        else -> HLG_A * ln(12.0 * value - HLG_B) + HLG_C
    }
}

internal data class Rgb(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    fun map(transform: (Double) -> Double): Rgb = Rgb(
        red = transform(red),
        green = transform(green),
        blue = transform(blue),
    )
}
