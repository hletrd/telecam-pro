package me.hletrd.telecampro.camera

/**
 * The EXPERIMENTAL RAW log route's gates ([RawLogBridge]): which transfers it develops into, which
 * routes can stream full-size RAW into the developer at the selected cadence, and when a video
 * session asks for it. Pure and host-tested; the controller re-checks the same answer at open.
 *
 * Owner decision 2026-10-25: a log this app develops itself from RAW has no reason to imitate a
 * phone maker's curve, so it targets the industry-standard Sony pair only — S-Log3 over S-Gamut3 or
 * S-Gamut3.Cine. Every other log transfer keeps the native ISP path.
 */
fun rawLogTransfer(transfer: ColorTransfer): Boolean =
    transfer == ColorTransfer.SLOG3 || transfer == ColorTransfer.SLOG3_CINE

/**
 * Whether the RAW stream's advertised minimum frame duration sustains [fps]. Zero means "not
 * available" in Camera2, which is not evidence a ~25 MB-per-frame stream keeps the cadence, so it
 * fails closed. The tolerance absorbs rounding in advertised durations (PMA110 camera 4 lists
 * 33.3222 ms, camera 2 33.3333 ms — both the measured ~29.8 fps stream).
 */
fun rawStreamSustains(minFrameDurationNs: Long, fps: Double): Boolean =
    minFrameDurationNs > 0L && fps > 0.0 &&
        minFrameDurationNs <= (1_000_000_000.0 / fps) * (1.0 + RAW_CADENCE_TOLERANCE)

/**
 * Capability half: a rear camera whose RAW the developer understands ([CameraCaps.rawLogDevelopable]:
 * a Bayer CFA, a ForwardMatrix, a calibrated reference illuminant), streaming at [fps]. [standalone]
 * and [rawRequiresStandalone] carry the PMA110 RAW law (DeviceProfile.rawRequiresStandalone: RAW on
 * the logical camera errors the device) exactly as the still ladder does. FRONT is excluded by
 * scope, like front DNG: front RAW has never been measured.
 */
fun rawLogSupported(
    caps: CameraCaps,
    standalone: Boolean,
    rawRequiresStandalone: Boolean,
    fps: Double,
): Boolean =
    caps.rawLogDevelopable && caps.supportsRaw && caps.rawSize != null && !caps.lensFacingFront &&
        (!rawRequiresStandalone || (standalone && !caps.isLogicalMultiCamera)) &&
        rawStreamSustains(caps.rawMinFrameDurationNs, fps)

/** VIDEO + the operator's experimental toggle + a Sony log transfer + a capable route. */
fun rawLogSessionWanted(
    videoMode: Boolean,
    transfer: ColorTransfer,
    enabled: Boolean,
    supported: Boolean,
): Boolean = videoMode && enabled && supported && rawLogTransfer(transfer)

/**
 * Whether the Video tab offers the RAW log row: a Sony log selected on a camera that can develop
 * RAW at the selected rate. Route standalone-ness is the engine's call (the accepted session then
 * says whether RAW log actually configured); the row only needs to exist where it can act.
 */
fun rawLogSettingVisible(transfer: ColorTransfer, caps: CameraCaps, fps: Double): Boolean =
    rawLogTransfer(transfer) && caps.rawLogDevelopable && caps.supportsRaw && caps.rawSize != null &&
        !caps.lensFacingFront && rawStreamSustains(caps.rawMinFrameDurationNs, fps)

private const val RAW_CADENCE_TOLERANCE = 0.005

/**
 * The RAW log route's gyro EIS configuration (stab/RawEisMath.kt, camera/RawLogBridge.kt). The HAL's
 * EIS works on ISP output and never touches RAW, so stabilizing a RAW log take is the app's job; the
 * existing Stabilization setting picks the level. [converterMagnification] is the declared converter's
 * angular magnification while TELE is on (null otherwise): through the inverting Keplerian converter
 * a phone rotation moves the image by −M·f instead of +f, which is the whole difference between a
 * stabilizer that works at 300 mm and one that doubles the shake.
 */
internal data class RawEisConfig(
    val strong: Boolean,
    /** Fraction of the crop held back for correction: the output shows (1 − margin) of it. */
    val margin: Double,
    val converterMagnification: Double?,
    val sensorOrientation: Int,
    /** Lens focal over sensor width (= focal in pixels ÷ array width), [CameraCaps.nativeFocalInImageWidths]. */
    val focalInImageWidths: Double,
)

/**
 * The EIS a RAW log session runs for [mode], or null for OFF (and for a camera whose focal geometry is
 * unknown — a zero focal would make every correction infinite). Standard holds back 10 % of the crop,
 * Active 20 % with slower following, mirroring the HAL's two classes.
 */
internal fun rawEisConfigFor(
    mode: VideoStabMode,
    teleconverterOn: Boolean,
    converterMagnification: Float,
    sensorOrientation: Int,
    focalInImageWidths: Float,
): RawEisConfig? {
    if (mode == VideoStabMode.OFF || focalInImageWidths <= 0f) return null
    val strong = mode == VideoStabMode.ENHANCED
    return RawEisConfig(
        strong = strong,
        margin = if (strong) RAW_EIS_STRONG_MARGIN else RAW_EIS_STANDARD_MARGIN,
        converterMagnification = converterMagnification.toDouble().takeIf { teleconverterOn && it > 1.0 },
        sensorOrientation = sensorOrientation,
        focalInImageWidths = focalInImageWidths.toDouble(),
    )
}

private const val RAW_EIS_STANDARD_MARGIN = 0.10
private const val RAW_EIS_STRONG_MARGIN = 0.20

/**
 * Sensor frames missing between two consecutive timestamps at [frameDurationNs] cadence (DEBUG drop
 * attribution in camera/RawLogBridge.kt): a 2× interval is one missing frame, and anything under 1.5×
 * is cadence jitter, not a drop. Zero for the first frame or an unknown duration.
 */
internal fun missingFrames(previousNs: Long, currentNs: Long, frameDurationNs: Long): Int {
    if (previousNs <= 0L || frameDurationNs <= 0L || currentNs <= previousNs) return 0
    return (Math.round((currentNs - previousNs).toDouble() / frameDurationNs) - 1).toInt().coerceAtLeast(0)
}
