package me.hletrd.telecampro.camera

import android.hardware.camera2.params.SessionConfiguration

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
    // Stock manual WB with FAST processing. Standard CCT and qcamera3.manualWB temperature
    // are ignored on this route; AWBCCT reaches the OEM Kelvin table without bypassing its colour.
    const val WB_CCT = "com.qti.stats.awbwrapper.AWBCCT"
    const val WB_TONE = "com.oplus.manualWB.color_tone"
    // Repeating requests only. On the OEM Log pipeline this selects native tuning (Feature 2 = 0)
    // and restores the unequal DCG gains. It does not submit a still or a Camera2 capture burst.
    const val CAPTURE_TYPE = "com.oplus.burst.capture.type"
}

/** Ordinary standalone TC session type; separate from both maker protocols. */
internal const val VENDOR_TC_SESSION_TYPE = 0x80b4

/** Standalone TC maker VIDEO session type. Reserved for an explicitly admitted debug probe. */
internal const val MAKER_VIDEO_SESSION_TYPE = 0x80b5

/** One operation-mode choice; maker modes take precedence over the ordinary TC ladder rung. */
internal fun sessionTypeFor(
    standaloneMakerVideo: Boolean,
    logicalMaker: Boolean,
    ordinaryTc: Boolean,
): Int = when {
    standaloneMakerVideo -> MAKER_VIDEO_SESSION_TYPE
    logicalMaker -> MakerLogProtocol.SESSION_TYPE
    ordinaryTc -> VENDOR_TC_SESSION_TYPE
    else -> SessionConfiguration.SESSION_REGULAR
}

/**
 * PMA110's measured [DeviceProfile.makerLogTeleMaxWireZoom], device-measured in THIS route on
 * 2026-10-26 (logical camera 0, maker session, dim room, chart ~1 m; converter glass NOT mounted):
 * LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID stayed on the host tele (4) at wire 8.91, 9.61 and 9.89,
 * but EVERY entry to 10.0 (5 of 5) handed the frame to the 10× periscope (5) for 1.6–1.9 s before
 * the HAL fell back to the tele. The owner's stock check the same day (PRO VIDEO / O-Log2 at the 3×,
 * 6× and 10× presets, moderate light; passive glass does not change HAL routing) saw that fallback,
 * not the handover; with a distant subject in good light the HAL may well STAY on the periscope. So
 * the ceiling sits below the periscope's own field (230 ÷ 23.4 mm ≈ 9.83 on the logical scale),
 * where the logical camera physically cannot serve the frame from it: 9.8 — ≈42.6× TOTAL through
 * the 300 mm kit. Low light (a hand-DOWN to the brighter main lens) held the host in the one dim room
 * measured (HAL AE pegged at ISO 12404, FIELD_CHECKS A22); the lens guard catches it elsewhere.
 */
internal const val MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110 = 9.8f

/**
 * The measured factory path is the rear logical camera, STANDARD PRIVATE/TP10, ordinary 16:9 video
 * at up to 30 fps. Keep unsupported requests as intent; their effective output is the ordinary
 * S-Log3 fallback.
 *
 * The teleconverter no longer withholds it (owner request 2026-10-26) where the profile measured a
 * TC span ([teleconverterRouteMeasured], i.e. [DeviceProfile.makerLogTeleMaxWireZoom] non-null):
 * stock never combines its own TC mode with Log, and the TC session type 0x80b4 (standalone 4) gives
 * an SDR-like gamma under `pro_video_log_mode`, so TC + maker Log keeps the converter's GL/readout
 * world and only moves the SESSION onto the logical camera's measured maker protocol, zoomed onto
 * the host tele ([MakerTeleRoute]). Losing 0x80b4 costs nothing demonstrated (FIELD_CHECKS C3).
 */
fun makerLogOffered(
    profileAllows: Boolean,
    teleconverterRouteMeasured: Boolean,
    route: CameraRoute,
    teleconverter: Boolean,
    cameraOverride: String?,
    frameRate: Double,
    openGate: Boolean,
): Boolean = profileAllows && route == CameraRoute.BACK && (!teleconverter || teleconverterRouteMeasured) &&
    cameraOverride == null && frameRate.isFinite() && frameRate > 0.0 && frameRate <= 30.0 && !openGate

internal fun makerLogSessionWanted(video: Boolean, transfer: ColorTransfer, offered: Boolean): Boolean =
    video && transfer == ColorTransfer.MAKER_LOG && offered

/**
 * Defensive open-time admission. The established logical route is unchanged; the standalone route
 * additionally requires TC, standalone (non-logical) camera truth and its own explicit admission.
 */
internal fun makerLogRequestAdmitted(
    makerLog: Boolean,
    makerPipeline: Boolean,
    logicalMultiCamera: Boolean,
    standaloneMakerVideo: Boolean,
    standaloneVideoAdmitted: Boolean,
    teleconverterMode: Boolean,
    physicalId: String?,
    lensFacingFront: Boolean,
): Boolean {
    if (!makerLog || !makerPipeline || physicalId != null || lensFacingFront) return false
    return if (standaloneMakerVideo) {
        standaloneVideoAdmitted && teleconverterMode && !logicalMultiCamera
    } else {
        logicalMultiCamera
    }
}

/** Mode 40 shares its key with pro_video_log_mode, so neither maker route may write it. */
internal fun applyHasselbladMode40(
    teleconverter: Boolean,
    standaloneMakerVideo: Boolean,
    logicalMaker: Boolean,
): Boolean = teleconverter && !standaloneMakerVideo && !logicalMaker

/** Standalone TC zoom is already lens-local; only the converter's magnification multiplies it. */
internal fun originalZoomHintForStandaloneTc(localZoom: Float, magnification: Float): Float =
    localZoom.coerceAtLeast(1f) * magnification

/** The exact measured PMA110 probe can augment its maker profile in debug only; siblings and release stay unchanged. */
internal fun debugStandaloneMakerVideoProfile(
    profile: DeviceProfile,
    model: String?,
    debugBuild: Boolean,
    probeEnabled: Boolean,
): DeviceProfile = if (
    debugBuild && probeEnabled && profile.makerLogPipeline &&
    DeviceProfile.standaloneMakerVideoProbeMeasured(model)
) {
    profile.copy(makerLogStandaloneTeleVideo = true)
} else {
    profile
}

/** The session carrying TC + maker Log. The standalone choice needs separate measured admission. */
enum class MakerTeleSessionRoute { LOGICAL_MAKER, STANDALONE_VIDEO }

/**
 * Preserve the measured logical-maker route unless the standalone VIDEO session was explicitly
 * admitted. Admission is deliberately independent of TC/maker intent and of [makerLogOffered].
 */
fun makerTeleSessionRoute(
    tc: Boolean,
    maker: Boolean,
    standaloneVideoAdmitted: Boolean,
): MakerTeleSessionRoute = if (tc && maker && standaloneVideoAdmitted) {
    MakerTeleSessionRoute.STANDALONE_VIDEO
} else {
    MakerTeleSessionRoute.LOGICAL_MAKER
}

/** Native output is admitted by accepted camera truth, not the requested Gamma mode. */
fun effectiveRecordingTransfer(requested: ColorTransfer, makerLogAccepted: Boolean): ColorTransfer =
    if (requested == ColorTransfer.MAKER_LOG && !makerLogAccepted) ColorTransfer.SLOG3 else requested

/**
 * A tag echo is insufficient: the full OEM path publishes its actual 1024-entry Log gamma. The
 * measured O-Log2 black entry is 754; the TC/SDR gamma starts at zero. Check before publishing Ready,
 * once per session, rather than copying this array on every frame throughout a recording. Only the
 * first 256 entries carry the curve; the remaining published storage is zero-filled.
 */
internal fun makerLogSourceConfirmed(
    expectedOperationMode: Int,
    operationMode: Int?,
    logMode: Int?,
    gamma: IntArray?,
): Boolean = operationMode == expectedOperationMode && logMode == 0 && gamma?.size == 1024 &&
    gamma[0] == 754 && gamma[255] > gamma[0]

/**
 * The teleconverter riding the maker pipeline: the LOGICAL camera, zoomed onto the converter's host
 * tele. Everything the converter owns — the afocal 180°, the TC-local zoom the UI and readouts use,
 * the declared focal/magnification, EXIF and OSD — stays exactly as on the ordinary TC route; only
 * the camera, its session and the zoom's WIRE scale differ, and those live in the controller.
 *
 * [wireZoomScale] is the host tele's position on the logical camera's unified scale (the value the
 * 3× chip applies, [makerTeleWireZoomScale]); the controller multiplies every CONTROL_ZOOM_RATIO it
 * writes by it and divides every one it reads back, so engine, GL compensation and UI keep seeing
 * TC-local values. [wireZoomCeiling] is the measured [DeviceProfile.makerLogTeleMaxWireZoom].
 * [hostPhysicalId] is the logical camera's physical member under the converter, resolved by
 * enumerated focal ([CameraSelector2.pickPhysicalMember]) — never a literal id — or null when the
 * enumeration could not name it (the lens guard then cannot prove anything and stays quiet).
 */
data class MakerTeleRoute(
    val wireZoomScale: Float,
    val wireZoomCeiling: Float,
    val hostPhysicalId: String?,
)

/**
 * Only the logical-maker session needs TC-local ↔ logical-wire conversion. A standalone session's
 * camera zoom is already TC-local, so manufacturing this conversion would scale every zoom twice.
 */
internal fun makerTeleLogicalWireRoute(
    sessionRoute: MakerTeleSessionRoute,
    wireZoomScale: Float,
    wireZoomCeiling: Float,
    hostPhysicalId: String?,
): MakerTeleRoute? = if (sessionRoute == MakerTeleSessionRoute.LOGICAL_MAKER) {
    MakerTeleRoute(wireZoomScale, wireZoomCeiling, hostPhysicalId)
} else {
    null
}

/**
 * The host tele's position on the logical wire scale: [unifiedZoomOf] for a TC-local 1× on the
 * TELE3X preset, i.e. the declared phone's tele chip position over the optical lens it reaches
 * (3.0 on PMA110 — exactly the ratio the 3× chip applies). Same machinery as every other
 * standalone↔logical conversion, so the two scales cannot drift apart.
 */
internal fun makerTeleWireZoomScale(optical: Set<LensChoice>, presets: ZoomPresets): Float =
    unifiedZoomOf(LensChoice.TELE3X, 1f, standaloneRoute = true, optical = optical, presets = presets)

/**
 * The TC-local span the maker TC route can reach: the logical camera's advertised range expressed
 * in TC-local units, cut to [1, ceiling ÷ scale] — below the host tele's own field the logical camera
 * hands over to a wider lens the converter is not on, and above the measured ceiling the handover to
 * the next lens is unmeasured. Published as the route caps' `zoomRatioRange` (CameraEngine), so the
 * ruler, the TC rail marks, every clamp and the ordinary caps reconciliation all read the reachable
 * span: ≈42.6× on PMA110 instead of the usual 60× TOTAL ceiling. Null when the HAL advertised no range.
 */
internal fun makerTeleLocalZoomWindow(
    rawLower: Float?,
    rawUpper: Float?,
    wireScale: Float,
    wireCeiling: Float,
): ClosedFloatingPointRange<Float>? {
    if (rawLower == null || rawUpper == null || !(wireScale > 0f) || !wireScale.isFinite()) return null
    val upper = minOf(rawUpper, wireCeiling) / wireScale
    val lower = maxOf(1f, rawLower / wireScale)
    return if (lower <= upper) lower..upper else upper..upper
}

/** TC-local zoom → logical wire zoom, clamped to the measured [scale, ceiling] span. */
internal fun makerTeleWireZoom(local: Float, route: MakerTeleRoute): Float =
    (local * route.wireZoomScale).coerceIn(
        route.wireZoomScale,
        maxOf(route.wireZoomScale, route.wireZoomCeiling),
    )

/** Logical wire zoom (a CaptureResult) → the TC-local value engine and GL compare against. */
internal fun makerTeleLocalZoom(wire: Float, route: MakerTeleRoute): Float = wire / route.wireZoomScale

/**
 * Lens guard: whether a maker TC session is PROVABLY recording through a lens other than the one the
 * converter sits on. The logical camera crosses physical lenses itself, and in low light a HAL may
 * prefer a brighter one; the result's LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID names the lens of each
 * frame. Unknown on either side is NOT a mismatch: a guard that fires without proof would teach the
 * operator to ignore it.
 */
internal fun makerTeleLensOffHost(activePhysicalId: String?, hostPhysicalId: String?): Boolean =
    activePhysicalId != null && hostPhysicalId != null && activePhysicalId != hostPhysicalId

/**
 * The OSD's lens-switched tag: only while the converter is declared, in Video, on an ACCEPTED maker
 * session (the only route that can leave the host lens — the ordinary TC route is the standalone host
 * camera itself), and with a proven [makerTeleLensOffHost] reading.
 */
internal fun teleconverterLensSwitchedTagVisible(
    teleconverter: Boolean,
    videoMode: Boolean,
    makerSessionAccepted: Boolean,
    lensOffHost: Boolean,
): Boolean = teleconverter && videoMode && makerSessionAccepted && lensOffHost
