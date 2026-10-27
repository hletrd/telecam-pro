package me.hletrd.telecampro.camera

import android.util.Size
import me.hletrd.telecampro.video.EncoderSelection
import kotlin.math.roundToInt

/** Automatic selection stays below 8K; an extended raster is admitted only by explicit choice. */
const val AUTO_VIDEO_MAX_WIDTH = 3840

/** Every legacy size through 4096×3072 keeps its existing admission and rate rules. */
fun requiresExtendedVideoProof(width: Int, height: Int): Boolean = width > 4096 || height > 3072

/**
 * The nominal rate used for a platform size/rate probe and its projected map key. NTSC selections
 * retain their exact 1001-grid [VideoFrameRate.encoderRate] for encoding; MediaCodec's capability
 * query is expressed in the standard's named 23.976/29.97/59.94 rates.
 */
internal val VideoFrameRate.recordableCapabilityRate: Double
    get() = (encoderRate * 1000.0).roundToInt() / 1000.0

/**
 * Projects raw camera stream sizes through the exact encoder candidates for the active video packet.
 * Legacy sizes never need a probe. An extended size needs a supported rate and, for RAW Log, a RAW
 * source at least as large; otherwise it is removed rather than offered and refused at session time.
 */
fun projectRecordableVideoSizes(
    raw: CameraCaps,
    candidates: List<EncoderSelection>,
    rawLog: Boolean,
): CameraCaps {
    val standardRates = VideoFrameRate.entries.map { it.recordableCapabilityRate }.filter { it <= 30.0 }.toSet()
    fun projected(sizes: List<Size>): Pair<List<Size>, Map<Size, Set<Double>>> {
        val rateMap = sizes.filter { requiresExtendedVideoProof(it.width, it.height) }.mapNotNull { size ->
            if (rawLog && raw.rawSize?.let { it.width >= size.width && it.height >= size.height } != true) {
                return@mapNotNull null
            }
            val rates = standardRates.filter { rate ->
                candidates.any { it.sizeRateSupport?.supports(size.width, size.height, rate) == true }
            }.toSet()
            if (rates.isEmpty()) null else size to rates
        }.toMap()
        val kept = sizes.filter { !requiresExtendedVideoProof(it.width, it.height) || it in rateMap }
        return kept to rateMap
    }
    val video = projected(raw.availableVideoSizes)
    val gate = projected(raw.openGateVideoSizes)
    return raw.copy(
        availableVideoSizes = video.first,
        openGateVideoSizes = gate.first,
        extendedVideoSizeRates = video.second + gate.second,
    )
}
