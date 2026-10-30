package me.hletrd.telecampro.camera

import android.util.Size
import me.hletrd.telecampro.video.EncoderSelection
import kotlin.math.abs

/** The requested next-VIDEO graph. [automaticSize] includes the legacy direct-characteristics fallback. */
data class VideoSessionRequest(
    val route: TeleSelection,
    val codec: VideoCodec,
    val transfer: ColorTransfer,
    val rawLogRequested: Boolean,
    val openGate: Boolean,
    val requestedSize: Size?,
    val automaticSize: Size?,
    val frameRate: VideoFrameRate,
    val rawRequiresStandalone: Boolean,
    val contentRotation: Int,
    val bitrateLevel: BitrateLevel = BitrateLevel.HIGH,
)

/**
 * One immutable VIDEO graph, fully resolved before Camera2 configure or REC admission. In particular,
 * [frameRate] and [rawLog] describe the same fixed point: a rate correction that newly enables RAW Log
 * has already reapplied the RAW raster gate, and the encoder never asks the developer to upscale.
 */
data class VideoSessionIntent(
    val route: TeleSelection,
    val codec: VideoCodec,
    val transfer: ColorTransfer,
    val rawLog: Boolean,
    val openGate: Boolean,
    val streamSize: Size,
    val encoderSize: Size,
    val frameRate: VideoFrameRate,
)

/**
 * Resolves rate → RAW eligibility → raster → rate to a fixed point. Two passes are sufficient: those
 * are the only dependencies. An offered explicit size is never replaced merely because the second
 * pass makes it illegal; an unsupported retained request keeps the historical automatic fallback.
 */
fun resolveVideoSessionIntent(
    requested: VideoSessionRequest,
    caps: CameraCaps,
    candidates: List<EncoderSelection>,
): VideoSessionIntent? {
    val offered = if (requested.openGate) caps.openGateVideoSizes else caps.availableVideoSizes
    val streamSize = requested.requestedSize?.takeIf { it in offered } ?: requested.automaticSize
        ?: return null
    var rate = requested.frameRate
    var rawLog = rawLogAtRate(requested, caps, rate)

    repeat(2) {
        // projectRecordableVideoSizes applies this same gate while projecting the route. It must be
        // checked again HERE, after a corrected rate may have changed rawLog from false to true.
        if (rawLog && requiresExtendedVideoProof(streamSize.width, streamSize.height)) {
            val raw = caps.rawSize
            if (raw == null || raw.width < streamSize.width || raw.height < streamSize.height) {
                return null
            }
        }
        val legalRates = projectedLegalVideoRates(
            caps = caps,
            size = streamSize,
            candidates = candidates,
            transfer = requested.transfer,
            codec = requested.codec,
            sensorOrientation = caps.sensorOrientation,
            contentRotation = requested.contentRotation,
            bitrateLevel = requested.bitrateLevel,
        )
        val resolvedRate = if (rate in legalRates) {
            rate
        } else {
            // Legacy sizes historically left an unadvertised rate for the ViewModel's ordinary
            // reconciliation; only an extended raster requires a proven intersection up front.
            if (legalRates.isEmpty() && !requiresExtendedVideoProof(streamSize.width, streamSize.height)) {
                rate
            } else {
                legalRates.minByOrNull { abs(it.fps - rate.fps) }
            }
        } ?: return null
        val resolvedRawLog = rawLogAtRate(requested, caps, resolvedRate)
        if (resolvedRate == rate && resolvedRawLog == rawLog) {
            val (encoderWidth, encoderHeight) = RotationMath.encoderSurfaceSize(
                streamSize.width,
                streamSize.height,
                caps.sensorOrientation,
                requested.contentRotation,
            )
            return VideoSessionIntent(
                route = requested.route,
                codec = requested.codec,
                transfer = requested.transfer,
                rawLog = rawLog,
                openGate = requested.openGate,
                streamSize = streamSize,
                encoderSize = Size(encoderWidth, encoderHeight),
                frameRate = rate,
            )
        }
        rate = resolvedRate
        rawLog = resolvedRawLog
    }
    return null
}

private fun rawLogAtRate(
    requested: VideoSessionRequest,
    caps: CameraCaps,
    rate: VideoFrameRate,
): Boolean = rawLogSessionWanted(
    videoMode = true,
    transfer = requested.transfer,
    enabled = requested.rawLogRequested,
    supported = rawLogSupported(
        caps = caps,
        standalone = requested.route.physicalId == null,
        rawRequiresStandalone = requested.rawRequiresStandalone,
        fps = rate.encoderRate,
    ),
)
