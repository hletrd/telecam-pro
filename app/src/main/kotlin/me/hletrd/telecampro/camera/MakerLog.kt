package me.hletrd.telecampro.camera

/** Measured OPPO PRO VIDEO protocol. These are names, never firmware-specific numeric tag IDs. */
internal object MakerLogProtocol {
    const val SESSION_TYPE = 0x830b
    const val SDK_PACKAGE = "com.oplus.is.sdk.camera.package"
    const val FROM_EXTENSION = "com.oplus.is.from.extension"
    const val CAMERA_MODE = "com.oplus.camera.mode"
    const val LOG_MODE = "com.oplus.log.video.mode"
    const val FROM_MAIN_MENU = "com.oplus.camera.is.from.main.menu"
    const val MODE_NAME = "pro_video_log_mode"
    const val GAMMA_INFO = "com.oplus.gamma.info"
    const val OPERATION_MODE = "com.oplus.operation.mode"
}

/**
 * The measured factory path is the rear logical camera, STANDARD PRIVATE/TP10, ordinary 16:9 video
 * at up to 30 fps. TC has a different operation mode and has NOT produced the same Log gamma.
 * Keep unsupported requests as intent; their effective output is the ordinary S-Log3 fallback.
 */
fun makerLogOffered(
    profileAllows: Boolean,
    route: CameraRoute,
    teleconverter: Boolean,
    cameraOverride: String?,
    frameRate: Double,
    openGate: Boolean,
): Boolean = profileAllows && route == CameraRoute.BACK && !teleconverter && cameraOverride == null &&
    frameRate.isFinite() && frameRate > 0.0 && frameRate <= 30.0 && !openGate

internal fun makerLogSessionWanted(video: Boolean, transfer: ColorTransfer, offered: Boolean): Boolean =
    video && transfer == ColorTransfer.MAKER_LOG && offered

/** Native output is admitted by accepted camera truth, not the requested Gamma mode. */
fun effectiveRecordingTransfer(requested: ColorTransfer, makerLogAccepted: Boolean): ColorTransfer =
    if (requested == ColorTransfer.MAKER_LOG && !makerLogAccepted) ColorTransfer.SLOG3 else requested

/**
 * A tag echo is insufficient: the full OEM path publishes its actual 1024-entry Log gamma. The
 * measured O-Log2 black entry is 754; the TC/SDR gamma starts at zero. Check before publishing Ready,
 * once per session, rather than copying this array on every frame throughout a recording. Only the
 * first 256 entries carry the curve; the remaining published storage is zero-filled.
 */
internal fun makerLogSourceConfirmed(operationMode: Int?, logMode: Int?, gamma: IntArray?): Boolean =
    operationMode == MakerLogProtocol.SESSION_TYPE && logMode == 0 && gamma?.size == 1024 &&
        gamma[0] == 754 && gamma[255] > gamma[0]
