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
 * Legacy sizes never need a probe. An extended size needs an encoder-supported rate that also
 * survives the camera's fixed-FPS/minimum-duration gates and, for RAW Log, a large enough RAW source;
 * otherwise it is removed rather than offered and refused at session time.
 */
fun projectRecordableVideoSizes(
    raw: CameraCaps,
    candidates: List<EncoderSelection>,
    rawLog: Boolean,
): CameraCaps {
    val standardRates = VideoFrameRate.entries.map { it.recordableCapabilityRate }.filter { it <= 30.0 }.toSet()
    val codec = candidates.firstOrNull()?.codec ?: VideoCodec.HEVC
    fun encoderRates(sizes: List<Size>): Map<Size, Set<Double>> =
        sizes.filter { requiresExtendedVideoProof(it.width, it.height) }.mapNotNull { size ->
            if (rawLog && raw.rawSize?.let { it.width >= size.width && it.height >= size.height } != true) {
                return@mapNotNull null
            }
            val rates = standardRates.filter { rate ->
                candidates.any { it.sizeRateSupport?.supports(size.width, size.height, rate) == true }
            }.toSet()
            if (rates.isEmpty()) null else size to rates
        }.toMap()
    val videoRates = encoderRates(raw.availableVideoSizes)
    val gateRates = encoderRates(raw.openGateVideoSizes)
    val withEncoderRates = raw.copy(extendedVideoSizeRates = videoRates + gateRates)
    fun recordable(sizes: List<Size>, rates: Map<Size, Set<Double>>): List<Size> =
        sizes.filter {
            !requiresExtendedVideoProof(it.width, it.height) ||
                (it in rates && VideoFrameRate.availableFor(withEncoderRates, it, codec).isNotEmpty())
        }
    val video = recordable(raw.availableVideoSizes, videoRates)
    val gate = recordable(raw.openGateVideoSizes, gateRates)
    val admitted = video + gate
    return raw.copy(
        availableVideoSizes = video,
        openGateVideoSizes = gate,
        extendedVideoSizeRates = (videoRates + gateRates).filterKeys { it in admitted },
    )
}
