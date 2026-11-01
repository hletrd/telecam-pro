package me.hletrd.findx9tele.probe

import android.hardware.camera2.params.SessionConfiguration
import android.util.Size

/** Measured stock-Camera2 shape for the isolated Samsung 8K experiment; never a product route. */
internal data class Samsung8kProbeProtocol(
    val model: String,
    val cameraId: String,
    val recordingFps: Int,
    val jpegCaptureSize: Size,
    val yuvCaptureSize: Size,
    val sessionParameters: Map<String, Any>,
    val previewSize: Size = Size(1920, 1080),
    val recordSize: Size = Size(7680, 4320),
    val previewCallbackSize: Size = Size(1920, 1080),
    val previewStreamUseCase: Int = 0x10000,
    val captureStreamUseCase: Int = 0x20000,
    val recordStreamOption: Int = 0x41,
    val recordStreamUseCase: Int = 0x410000,
    val recordDataspace: Int = 0x104,
    val auxiliaryDataspace: Int = 0x08c20000,
    val sessionType: Int = SessionConfiguration.SESSION_REGULAR,
    val physicalCameraId: String? = null,
    val legacySessionParameters: Map<String, Any> = samsung8kLegacyParameters(recordingFps),
    val repeatingParameters: Map<String, Any> = mapOf(
        "samsung.android.control.shootingMode" to 3,
    ),
)

private fun samsung8kLegacyParameters(recordingFps: Int): Map<String, Any> = mapOf(
    "samsungcamera" to true,
    "shootingmode" to 3,
    "recording-fps" to recordingFps,
    "sw-vdis" to true,
    "recording_dr_mode" to "sdr",
    "stream_type" to 0,
)

private fun samsung8kS20SessionParameters(): Map<String, Any> {
    val prefix = "samsung.android.control."
    return mapOf(
        "${prefix}ssmShotMode" to 0,
        "${prefix}fastCaptureOption" to 0,
        "${prefix}swSuperVideoStabilization" to 0.toByte(),
        "${prefix}specialImageQualityPolicy" to 0L,
        "${prefix}videoBeautyFace" to 0.toByte(),
        "${prefix}swVideoStabilization" to 1.toByte(),
        "${prefix}recordingMaxFps" to 24,
        "${prefix}cameraClient" to 0,
        "${prefix}recordingDrMode" to 0.toByte(),
    )
}

private fun samsung8kS23SessionParameters(): Map<String, Any> {
    val prefix = "samsung.android.control."
    return mapOf(
        "${prefix}recordingDrMode" to 0.toByte(),
        "${prefix}recordingMaxFps" to 30,
        "${prefix}recordingMinFps" to 30,
        "${prefix}recordingExtraMode" to 0,
        "${prefix}externalLensType" to 0,
        "${prefix}swVideoStabilization" to 1.toByte(),
        "${prefix}specialImageQualityPolicy" to 2L,
        "${prefix}cameraClient" to 0,
        "${prefix}videoBeautyFace" to 0.toByte(),
        "${prefix}swSuperVideoStabilization" to 0.toByte(),
        "${prefix}sensorFlipMode" to 0,
        "${prefix}initialZoomRatio" to 1.0f,
        "${prefix}fastCaptureOption" to 0,
        "${prefix}adaptivePixelMode" to 0,
        "${prefix}ssmShotMode" to 0,
        "${prefix}colorSpaceMode" to 0,
        "${prefix}sensor.streamType" to 0,
    )
}

internal fun samsung8kProbeProtocolForTest(model: String): Samsung8kProbeProtocol? = when (model) {
    "SM-G981N" -> Samsung8kProbeProtocol(
        model = model,
        cameraId = "50",
        recordingFps = 24,
        jpegCaptureSize = Size(4032, 2268),
        yuvCaptureSize = Size(4032, 2268),
        sessionParameters = samsung8kS20SessionParameters(),
    )
    "SM-S918N" -> Samsung8kProbeProtocol(
        model = model,
        cameraId = "56",
        recordingFps = 30,
        jpegCaptureSize = Size(4000, 2252),
        yuvCaptureSize = Size(4000, 2252),
        sessionParameters = samsung8kS23SessionParameters(),
    )
    else -> null
}
