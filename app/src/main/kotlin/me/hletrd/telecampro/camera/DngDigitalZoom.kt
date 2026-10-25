package me.hletrd.telecampro.camera

/*
 * A DNG never carries DIGITAL zoom (owner decision 2026-10-25: tell the operator, do not crop the RAW).
 *
 * Camera2 never crops a RAW_SENSOR output by CONTROL_ZOOM_RATIO or SCALER_CROP_REGION; the crop belongs
 * to the ISP's processed streams. So on a route whose wire zoom is LENS-LOCAL, any ratio above 1× frames
 * the preview, the HEIF and the JPEG while the DNG keeps the lens's full array. Device-verified on PMA110
 * 2026-10-25, user-reported first: TELE + Hasselblad 300 mm at the 60× preset wrote HEIF/JPEG with
 * DigitalZoomRatio 4.6 (1380 mm) beside a DNG of the full 4096×3072 array with DefaultCrop = full frame,
 * i.e. the 13× (local 1.0) framing. Not a defect: the file is the sensor's own field. Re-cropping it
 * through DefaultCrop was declined, so the only honest answer is a notice.
 */

/** Wire (lens-local) zoom above which the processed outputs are digitally cropped and the DNG is not. */
internal const val DNG_DIGITAL_ZOOM_NOTICE_RATIO = 1.01f

/**
 * Wire zoom at or below which the frame is back at the lens's native field, which re-arms the notice.
 * The band between this and [DNG_DIGITAL_ZOOM_NOTICE_RATIO] is hysteresis: a pinch hovering at the
 * notice threshold must not leave and re-enter the state on alternate ~60 Hz coalescer ticks.
 */
internal const val DNG_DIGITAL_ZOOM_REARM_RATIO = 1.001f

/**
 * Whether the next still's DNG will NOT match its processed framing.
 *
 * [stillWritesDng] is the capture-time truth (the request normalized against the ACCEPTED session's
 * readers), never the chip alone. [wireZoomLensLocal] says the wire ratio is the opened lens's own
 * scale, where every ratio above 1× is a digital crop the RAW does not receive. A logical seamless
 * route answers false: its wire ratio is main-relative and the HAL chooses which physical sensor
 * feeds the RAW (and how much of it), so the DNG's field is not knowable from the ratio. PMA110 never
 * carries RAW there anyway (`DeviceProfile.rawRequiresStandalone`); GENERIC does, and is left silent
 * rather than guessed at.
 */
internal fun dngSkipsDigitalZoom(
    stillWritesDng: Boolean,
    wireZoomLensLocal: Boolean,
    wireZoomRatio: Float,
): Boolean = stillWritesDng && wireZoomLensLocal && wireZoomRatio > DNG_DIGITAL_ZOOM_NOTICE_RATIO

/** One observation of the DNG-versus-processed framing, as the notice latch consumes it. */
internal enum class DngZoomFraming {
    /**
     * Not announced now: not Ready (a reopen, pause, cold start), VIDEO, or a full-screen modal over
     * the viewfinder (see [dngZoomFraming]).
     */
    NO_STILL,

    /** The next DNG matches the processed framing: no DNG, a logical route, or native lens zoom. */
    MATCHES,

    /** Inside the hysteresis band: neither news nor a return to the lens's native field. */
    MARGINAL,

    /** [dngSkipsDigitalZoom]: the processed outputs carry digital zoom the DNG will not. */
    SKIPS_ZOOM,
}

internal fun dngZoomFraming(
    stillPossible: Boolean,
    stillWritesDng: Boolean,
    wireZoomLensLocal: Boolean,
    wireZoomRatio: Float,
): DngZoomFraming = when {
    !stillPossible -> DngZoomFraming.NO_STILL
    dngSkipsDigitalZoom(stillWritesDng, wireZoomLensLocal, wireZoomRatio) -> DngZoomFraming.SKIPS_ZOOM
    stillWritesDng && wireZoomLensLocal && wireZoomRatio > DNG_DIGITAL_ZOOM_REARM_RATIO -> DngZoomFraming.MARGINAL
    else -> DngZoomFraming.MATCHES
}

/**
 * [dngZoomFraming] for the live UI state.
 *
 * - PHOTO only, on a Ready session. The in-REC snapshot of an SDR or native-log video session on a
 *   RAW-capable standalone lens writes an unzoomed DNG too, but VIDEO stays quiet on purpose
 *   (owner review 2026-10-25): the plate would speak at REC start, about a still the operator is
 *   not taking, in the one moment the viewfinder must stay clean. VIDEO HOLDS the latch like
 *   Not-Ready, so a Photo→Video→Photo trip does not repeat it.
 * - Not while a full-screen modal covers the viewfinder ([CameraUiState.cameraInputBlocked]: the
 *   settings sheet, Fn, review). The Output → DNG chip lives IN the settings sheet, so turning DNG on
 *   at 30× published the notice behind the sheet and it expired unseen (device-found on PMA110,
 *   2026-10-25). The modal HOLDS the latch, so the decision is taken when the viewfinder is back:
 *   DNG turned on inside the sheet announces as the sheet closes; off-then-on inside one sheet visit
 *   is no net change and stays silent.
 * - The DNG truth is `CameraEngine.capturePhoto`'s own normalization of the REQUEST against the
 *   accepted session's readers ([normalizedFor]) — so hi-res, FRONT, a drop-RAW rung and a
 *   still-less session all answer "no DNG" exactly as the shot will.
 * - The scale is the ONE zoom-scale law ([unifiedZoom] / `mainRelativeZoomMultiplier`), not
 *   [CameraRoute.lensLocalZoom] alone, which covers only FRONT/EXTERNAL: TELE pins the standalone
 *   3× (`dngIntentChangesRearRoute`), and VIDEO or a DNG wish under the PMA110 law pins a standalone
 *   rear lens ([standaloneRouteWanted]). [rawForcesStandalone] must be the ENGINE's live law, not the
 *   state copy that defaults to the PMA110 answer until the first route inventory.
 */
internal fun CameraUiState.dngZoomFraming(rawForcesStandalone: Boolean): DngZoomFraming = dngZoomFraming(
    stillPossible = cameraReady && mode == CaptureMode.PHOTO && !cameraInputBlocked,
    stillWritesDng = photoFormats.normalizedFor(photoSessionOutputs, heifStandInAvailable).dngRaw,
    wireZoomLensLocal = activeCameraRoute.lensLocalZoom || teleconverterMode ||
        standaloneRouteWanted(
            videoMode = mode == CaptureMode.VIDEO,
            rawWanted = photoFormats.dngRaw,
            rawForcesStandalone = rawForcesStandalone,
        ),
    wireZoomRatio = controls.zoomRatio,
)

/**
 * Edge latch for the "DNG saves without digital zoom" notice: true exactly once when the framing
 * ENTERS [DngZoomFraming.SKIPS_ZOOM], re-armed only by [DngZoomFraming.MATCHES]. Main-confined.
 *
 * - Zoom ticks inside the state, and the [DngZoomFraming.MARGINAL] band, never re-announce.
 * - [DngZoomFraming.NO_STILL] HOLDS the latch: an aspect/fps/lens reopen, a pause/resume, or a
 *   Photo→Video→Photo trip that lands back in the same state is not news, and a resumed camera must
 *   not repeat it. A door that lands in the state from an ARMED latch (DNG on while zoomed, a lens
 *   pick or MR recall into a zoomed DNG route) announces on its first Ready.
 * - The FIRST observation in which a still is possible only primes the latch: launching into the
 *   state is a settings restore the operator did not perform in this process (they were told in the
 *   session that entered it), and announcing it would repeat on every cold start.
 * - The latch is set when the notice is PUBLISHED, shown or not. Unlike the Ready-fold notices
 *   (RAW loss, DNG-only), this state has no retry point that is not a zoom tick, so a notice the
 *   arbiter drops under a higher unexpired event is not chased; leaving and re-entering repeats it.
 */
internal class DngDigitalZoomNotice {
    private var primed = false
    private var latched = false

    /** Feeds one observation; true when the notice must be published now. */
    fun observe(framing: DngZoomFraming): Boolean {
        val announce = when (framing) {
            DngZoomFraming.NO_STILL -> return false
            DngZoomFraming.MATCHES -> {
                latched = false
                false
            }
            DngZoomFraming.MARGINAL -> false
            DngZoomFraming.SKIPS_ZOOM -> {
                val entered = primed && !latched
                latched = true
                entered
            }
        }
        primed = true
        return announce
    }
}
