package me.hletrd.telecampro.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import me.hletrd.telecampro.BuildConfig
import kotlin.math.log10
import kotlin.math.pow

/**
 * DEBUG-ONLY experiment (2026-10-14): does this HAL honour a caller-programmed ISP tone curve
 * (TONEMAP_MODE_CONTRAST_CURVE, MANUAL_POST_PROCESSING, maxCurvePoints 512 on every PMA110 camera)?
 * If it does, the stream can carry S-Log3 straight from the ISP instead of the stock tone curve.
 * Armed only by a file `tonecurve` in the app's external files dir whose content is `linear`,
 * `slog3` or `gamma`; release builds never read it. Also forces edge/NR OFF while armed.
 */
internal object DebugToneCurveExperiment {
    @Volatile
    var mode: String? = null

    fun refresh(externalFilesDir: java.io.File?) {
        mode = if (!BuildConfig.DEBUG) null else runCatching {
            java.io.File(externalFilesDir, "tonecurve").takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull()
    }

    fun curve(mode: String): FloatArray? {
        val f: (Double) -> Double = when (mode) {
            "linear" -> { x -> x }
            "gamma" -> { x -> x.pow(1.0 / 2.4) }
            "slog3" -> { x -> slog3(x) }
            else -> return null
        }
        val n = 64
        return FloatArray(n * 2) { i ->
            val k = i / 2
            val x = k.toDouble() / (n - 1)
            (if (i % 2 == 0) x else f(x).coerceIn(0.0, 1.0)).toFloat()
        }
    }

    private fun slog3(x: Double): Double =
        if (x >= 0.01125) (420.0 + log10((x + 0.01) / 0.19) * 261.5) / 1023.0
        else (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0

    fun apply(builder: CaptureRequest.Builder) {
        val m = mode ?: return
        val pts = curve(m) ?: return
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        builder.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
    }
}
