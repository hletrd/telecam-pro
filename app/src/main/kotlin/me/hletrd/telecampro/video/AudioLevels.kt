package me.hletrd.telecampro.video

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Signed 16-bit PCM full scale, shared so the standby meter and REC meter cannot drift apart. */
internal const val PCM_16_FULL_SCALE = 32768.0

/**
 * Per-channel RMS of an INTERLEAVED signed-16-bit PCM buffer, normalized to 0..1 and scaled by
 * [gain].
 *
 * Both meters previously collapsed the whole buffer into one number, which is only the truth for a
 * mono source: on a stereo or multi-capsule external mic a dead right channel is invisible behind a
 * healthy left one, and that is exactly the failure an input meter exists to catch. Returns one
 * entry per channel, in interleave order.
 *
 * [readCount] is the number of SAMPLES returned by AudioRecord (not frames, not bytes). A partial
 * final frame is ignored rather than mis-attributed: taking `readCount / channelCount` frames means
 * a trailing half-frame cannot land in the wrong channel's accumulator.
 */
internal fun channelRms(
    samples: ShortArray,
    readCount: Int,
    channelCount: Int,
    gain: Float = 1f,
): FloatArray {
    val channels = channelCount.coerceAtLeast(1)
    val usable = readCount.coerceIn(0, samples.size)
    val frames = usable / channels
    if (frames <= 0) return FloatArray(channels)
    val sums = DoubleArray(channels)
    var i = 0
    repeat(frames) {
        for (c in 0 until channels) {
            val v = samples[i++].toDouble()
            sums[c] += v * v
        }
    }
    return FloatArray(channels) { c ->
        val rms = sqrt(sums[c] / frames) / PCM_16_FULL_SCALE
        (rms * gain).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Quantizes each channel to 1/256 so StateFlow's equality dedup can actually fire.
 *
 * The raw RMS float never repeats — even a silent room's noise floor jitters — so without this every
 * ~10 Hz emission was a whole-CameraUiState copy that recomposed the tree for a visually identical
 * bar (perf review #4). Multi-channel makes that worse, not better: N channels are N chances for a
 * jittering low bit to defeat the compare, so the quantization has to happen per channel BEFORE the
 * list is compared, not on some aggregate afterwards.
 */
internal fun quantizeLevels(levels: FloatArray): List<Float> =
    levels.map { (it.coerceIn(0f, 1f) * 256f).roundToInt() / 256f }
