package me.hletrd.telecampro.camera

import kotlin.math.max
import kotlin.math.min

internal enum class PendingControlsDisposition { DRAIN_BEFORE_OPTICS, CANCEL_FOR_REPLACEMENT }

/** Which delayed whole-controls packet, if any, still belongs to the next camera transaction. */
internal fun pendingControlsForTransition(
    pending: ManualControls?,
    disposition: PendingControlsDisposition,
): ManualControls? = when (disposition) {
    PendingControlsDisposition.DRAIN_BEFORE_OPTICS -> pending
    PendingControlsDisposition.CANCEL_FOR_REPLACEMENT -> null
}

internal data class AcceptedOpticsAuxState(
    val preTeleUnifiedZoom: Float,
    /** What the accepted session writes for the request; a readout, never stored back (AGG3-18). */
    val effectivePhotoFormats: PhotoFormats,
)

/** Complete Engine -> UI rollback publication for one generation-owned optics transaction. */
data class OpticsRollbackPublication(
    val mode: CaptureMode,
    /** Restored next-Video selection, distinct from Photo's active SDR session transfer. */
    val transfer: ColorTransfer,
    val videoCodec: VideoCodec,
    val lens: LensChoice,
    val teleconverter: Boolean,
    val facing: CameraFacing,
    val route: CameraRoute,
    val controls: ManualControls,
    val photoExposureTimeNs: Long,
    val userPin: String?,
    val preTeleUnifiedZoom: Float,
    val declaration: TeleconverterDeclaration,
    val generation: Long,
    /** Exact video-pipeline publication restored or retained by this rollback. */
    val videoPipelineGeneration: Long,
    /**
     * The DNG route input restored with the route it selected. Without it a failed DNG reopen left
     * the engine wanting RAW over the restored logical session, and the UI chip on (AGG-4).
     * Deliberately REQUIRED (no default): DNG is a route input, and a silently defaulted route input
     * is exactly how a rollback can restore the wrong route.
     */
    val rawWanted: Boolean,
    /** The operator's recording-size REQUEST restored with the packet (not the delivered size). */
    val requestedVideoSize: android.util.Size?,
)

/** Auxiliary UI state changes only when the desired camera transaction reaches Ready. */
internal fun acceptedOpticsAuxState(
    teleconverter: Boolean,
    photoOutputs: PhotoSessionOutputs,
    preTeleUnifiedZoom: Float,
    photoFormats: PhotoFormats,
    heifStandIn: Boolean,
): AcceptedOpticsAuxState = AcceptedOpticsAuxState(
    preTeleUnifiedZoom = if (teleconverter) preTeleUnifiedZoom else Float.NaN,
    // An accepted session edits NOTHING in the operator's format request (AGG3-18 / VER3-2). Each
    // axis it once rewrote was device-reproduced as a lost selection:
    //
    // - No still lane AT ALL is a session STATE — the 10-bit video session drops both still readers
    //   by design. Normalising against that wrote the EMPTY set over the request, which persisted
    //   on background and came back as HEIF-only next launch (2026-07-29).
    // - The RAW axis: RAW's presence is a CONSEQUENCE of this very request — wanting DNG is what
    //   moves the route — so letting the session clear it froze an engine/UI divergence behind
    //   `setRawWanted`'s change gate (seen on the front-camera trip, 2026-07-29).
    // - The PROCESSED axis: a DNG-only request on a session without RAW (FRONT, the drop-RAW rung)
    //   normalised to HEIF+DNG and persisted, so the rear route then wrote HEIFs never chosen.
    //
    // The session's answer is still real, so it is returned as a READOUT ([effectiveFor]); and
    // capture-time normalisation in CameraEngine refuses to shoot a missing output, so keeping the
    // request here can never produce a bogus capture.
    effectivePhotoFormats = photoFormats.effectiveFor(photoOutputs, heifStandIn),
)

/**
 * Whether choosing DNG can actually yield a RAW file.
 *
 * Two different questions used to share one flag. RAW is a DEVICE capability, but it is also a ROUTE
 * INPUT: in PHOTO, wanting DNG is exactly what moves the session off the logical camera onto a
 * standalone lens that can deliver it, so gating the chip on session truth made it unreachable
 * (disabled because RAW was absent, absent because it could not be enabled). Gating it purely on
 * device capability then over-corrected: in a 10-bit VIDEO session — which drops both still readers
 * by design — the chip stayed live and the caption read "HEIF/JPEG unavailable; DNG only" while DNG
 * was equally unavailable and no route change could bring it back.
 *
 * So: honour the capability, and require that the session either already carries RAW or belongs to a
 * mode where selecting DNG is what brings it. [hiResSession] is excluded because its one ladder rung
 * force-drops RAW (a full-sensor blob plus RAW is the over-demanding combo this HAL punishes), and
 * [frontFacing] because the session plan deliberately excludes RAW on the front route (`!frontRoute`
 * in `sessionAttemptPlan`) — a scope decision, not a measured HAL fault: the front route keeps its
 * processed still readers, but front RAW is unmeasured on every device. A front camera advertising
 * RAW would otherwise leave a live chip promising a DNG that never arrives.
 */
internal fun rawSelectable(
    deviceSupportsRaw: Boolean,
    rawInSession: Boolean,
    videoMode: Boolean,
    hiResSession: Boolean,
    frontFacing: Boolean,
): Boolean = deviceSupportsRaw && !frontFacing && (rawInSession || (!videoMode && !hiResSession))

/**
 * Clamps normalized optics again once the selected camera's live zoom range is authoritative.
 *
 * [teleconverterMagnification] is the SELECTED converter's magnification: TELE's contract ceiling is
 * a cap on TOTAL magnification, so the local ratio it permits scales inversely with the optic.
 */
internal fun reconcileZoomWithCaps(
    mode: CaptureMode,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    zoomRatio: Float,
    capsLower: Float?,
    capsUpper: Float?,
): Float {
    val contractLower = if (mode == CaptureMode.PHOTO && !teleconverter) 0.6f else 1f
    val contractUpper = when {
        teleconverter -> TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(teleconverterMagnification)
        mode == CaptureMode.VIDEO -> 10f
        else -> 20f
    }
    val safe = zoomRatio.takeIf { it.isFinite() }?.coerceIn(contractLower, contractUpper) ?: contractLower
    val liveLower = capsLower?.takeIf { it.isFinite() } ?: return safe
    val liveUpper = capsUpper?.takeIf { it.isFinite() } ?: return safe
    val lower = max(contractLower, liveLower)
    val upper = min(contractUpper, liveUpper)
    return if (lower <= upper) safe.coerceIn(lower, upper) else safe
}

/** One complete capability + route normalization boundary for recalled/live control packets. */
internal fun normalizeControlsForRoute(
    requested: ManualControls,
    capabilities: CameraControlCapabilities,
    mode: CaptureMode,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    capsLower: Float?,
    capsUpper: Float?,
): ManualControls {
    // Video PROGRAM normally belongs to HAL AE. Route switches happen while mode already equals
    // VIDEO, so clear ownership at this central caps seam rather than only on Photo -> Video entry;
    // an AE_OFF-only target will truthfully re-enable the app-side fallback during normalization.
    val modeIntent = if (mode == CaptureMode.VIDEO && requested.exposureMode == ExposureMode.PROGRAM) {
        requested.copy(programAppSide = false)
    } else {
        requested
    }
    val capabilityControls = modeIntent.normalizedFor(capabilities).normalizedForCaptureMode(mode)
    return capabilityControls.copy(
        zoomRatio = reconcileZoomWithCaps(
            mode = mode,
            teleconverter = teleconverter,
            teleconverterMagnification = teleconverterMagnification,
            zoomRatio = capabilityControls.zoomRatio,
            capsLower = capsLower,
            capsUpper = capsUpper,
        ),
    )
}

/**
 * Retained-session terminal normalization. Callers pass the live packet while holding the engine
 * monitor; taking a transition-time snapshot here would lose controls accepted while setup queued.
 */
internal fun normalizeRetainedControlsAtCommit(
    liveControls: ManualControls,
    capabilities: CameraControlCapabilities,
    mode: CaptureMode,
    teleconverter: Boolean,
    teleconverterMagnification: Float,
    capsLower: Float?,
    capsUpper: Float?,
): ManualControls = normalizeControlsForRoute(
    requested = liveControls,
    capabilities = capabilities,
    mode = mode,
    teleconverter = teleconverter,
    teleconverterMagnification = teleconverterMagnification,
    capsLower = capsLower,
    capsUpper = capsUpper,
)
