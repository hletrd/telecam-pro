package me.hletrd.telecampro.ui

/**
 * The camera-switch dip: a short fade to black over the viewfinder while a REOPEN replaces the
 * Camera2 session, and back out when the new session is accepted.
 *
 * Why a dip and not a crossfade: [me.hletrd.telecampro.camera.CameraEngine.applyPreviewOutput]
 * creates ONE SurfaceTexture/OES texture per GL generation and the incoming camera overwrites it,
 * so no frame of the OUTGOING lens survives to dissolve from — a true A→B dissolve would need a
 * second full-resolution texture allocated per switch.
 *
 * What it replaces is NOT a black gap. Between the outgoing `controller.close()` and the new
 * stream's first frame nothing redraws (the only two draw drivers are the frame-available listener
 * and the zoom self-redraw, and with no producer the preview EGL surface keeps its last swapped
 * buffer), so today the user stares at a FROZEN frame of the old lens — on TELE-off a *magnified*
 * one, because `seedGlZoom` posts the new zoom target before the new HAL zoom arrives and the
 * self-redraw keeps drawing the stale frame at `zoomComp = newTarget / OLD halZoom`. On a
 * photo↔video flip that same stale frame is additionally stretched by the aspect-box resize.
 *
 * Three constraints shape everything below; each is a regression if broken.
 *
 * 1. **The discriminator is a session-generation CHANGE, never `cameraReady` itself.** Every optics
 *    door publishes Not-Ready — including the same-route FAST PATH, which passes
 *    `expectedSessionGeneration = transaction.before.sessionGeneration` and never increments it. In
 *    PHOTO, `resolveNonTeleId` returns the cached logical camera for EVERY lens preset (lens picks
 *    are zoom presets; pinch never reopens), so a `cameraReady`-keyed cover would flash black on the
 *    most-used control in the app, where today nothing happens at all. Only a real reopen calls
 *    `cameraSessionGeneration.incrementAndGet()`.
 * 2. **Repeated Not-Ready inside one reopen is IDEMPOTENT.** A reopen publishes Not-Ready at least
 *    twice with two different generations (`invalidateCameraReady`, then the controller-install /
 *    dual-open candidate), and a fault-recovery reopen adds more. Each is "a cover is owed", not
 *    "restart the animation" — hence [SwitchCoverState.epoch], which advances only on the RISING
 *    edge and keys both the fade and the release deadline.
 * 3. **The cover must be released by something it owns.** Three reachable paths deliver no further
 *    publication at all — `rollbackOptics` publishing `ready=false`, recovery exhausting its attempt
 *    budget, and doors that return early on `paused` after Not-Ready was already published. A cover
 *    that outlives one of those is a permanently black viewfinder, which is strictly worse than the
 *    abrupt cut it replaces. [SWITCH_COVER_RELEASE_DEADLINE_MS] is therefore a correctness
 *    condition, not polish: the worst case must degrade to today's behaviour (frozen frame behind
 *    the already-dimmed shutter), never to permanent black.
 *
 * Ordering: publications are minted under the engine monitor but delivered from several threads
 * (the Not-Ready branch runs inline on the engine thread, Ready posts to main), so the fold is
 * main-confined in the ViewModel and drops any publication whose sequence it has already passed —
 * the same latest-wins discipline
 * [me.hletrd.telecampro.camera.CameraReadyPublicationGate] applies one layer up.
 */

/**
 * How long a reopen must stay un-Ready before the cover becomes VISIBLE.
 *
 * The trigger fires at `invalidateCameraReady`, but the outgoing camera keeps streaming for the
 * whole preflight that follows it — id resolve, characteristics read, EXIF prefetch, and the
 * dual-open of the next device — and only stops at the `old?.close()` on the far side of that
 * (deliberately: those Binder IPCs were moved OUT of the blackout so the old camera covers them).
 * Dimming at the trigger would therefore eat live picture the user has today.
 *
 * 120 ms is chosen as the shortest delay that is still honest at both ends: it is longer than two
 * frames of even the dark 15 fps fluidity cap (66.7 ms), so a transition that resolves within a
 * couple of frames never flashes anything; and it is shorter than this HAL's `openCamera →
 * onOpened` leg (the dominant early segment of the measured 544 ms cold start), so a normal switch
 * is already covered by the time the old stream actually dies. It is NOT device-measured — the
 * device was offline for this change; treat it as a defensible starting point, not a pinned fact.
 */
internal const val SWITCH_COVER_GRACE_MS = 120L

/**
 * Hard bound on one cover, measured from its RISING edge (so the visible span is this minus
 * [SWITCH_COVER_GRACE_MS]). Above a normal reopen — preflight + device open + session configure,
 * comfortably inside a second on this HAL — and below the point where a stuck cover would read as a
 * dead app. When it fires the viewfinder returns to exactly what it shows today.
 */
internal const val SWITCH_COVER_RELEASE_DEADLINE_MS = 1_500L

/** Main-confined fold state for the dip. Pure data; the ViewModel owns the timers. */
internal data class SwitchCoverState(
    /** Session generation of the newest publication folded so far. */
    val sessionGeneration: Long = 0L,
    /** True while a reopen owes the viewfinder a frame. Visibility additionally waits out the grace. */
    val covered: Boolean = false,
    /** Advances once per rising edge into [covered]; keys the fade and the release deadline. */
    val epoch: Long = 0L,
    /**
     * Optics generation of a transaction that ROLLED BACK. Its own trailing Not-Ready carries the
     * same generation, and it means "the camera is unchanged" — the outgoing session never closed
     * and is still streaming live picture, so re-raising a cover over it would black out a working
     * viewfinder for the whole deadline. Any NEW user intent mints a new optics generation and is
     * unaffected. (Narrow, accepted hole: a camera FAULT arriving after a rollback with no
     * intervening intent carries that same generation and goes uncovered — i.e. today's behaviour.)
     */
    val rolledBackOpticsGeneration: Long = -1L,
)

/**
 * The discriminator, alone and testable: does this publication mean a camera SWITCH is under way?
 *
 * Same-generation Not-Ready is the fast path (photo lens presets, same-route mode commits) — it
 * keeps streaming throughout and must never dim.
 */
internal fun switchCoverRaises(
    ready: Boolean,
    sessionGeneration: Long,
    lastSessionGeneration: Long,
): Boolean = !ready && sessionGeneration != lastSessionGeneration

/** Folds one Ready/Not-Ready publication. */
internal fun SwitchCoverState.onPublication(
    ready: Boolean,
    sessionGeneration: Long,
    opticsGeneration: Long,
): SwitchCoverState {
    // Ready is the only ordinary release: the replacement session is installed. It arrives at the
    // terminal commit, which can slightly precede the first frame of the new stream — the fade-OUT
    // is what covers that residual, so it is deliberately the slower half of the animation.
    if (ready) return copy(sessionGeneration = sessionGeneration, covered = false)
    val raises = switchCoverRaises(ready = false, sessionGeneration, this.sessionGeneration) &&
        opticsGeneration != rolledBackOpticsGeneration
    val nowCovered = covered || raises
    return copy(
        sessionGeneration = sessionGeneration,
        covered = nowCovered,
        epoch = if (nowCovered && !covered) epoch + 1 else epoch,
    )
}

/**
 * Folds an optics ROLLBACK. The failed door left the previous camera installed and streaming, so
 * the cover is released immediately instead of waiting out the deadline over live picture.
 */
internal fun SwitchCoverState.onOpticsRollback(opticsGeneration: Long): SwitchCoverState =
    copy(covered = false, rolledBackOpticsGeneration = opticsGeneration)

/**
 * Folds the self-owned deadline for one [epoch]. A later epoch has already superseded this timer,
 * and an already-released cover is a no-op; [sessionGeneration] is retained so a subsequent genuine
 * reopen still raises a fresh (independently bounded) cover.
 */
internal fun SwitchCoverState.onReleaseDeadline(epoch: Long): SwitchCoverState =
    if (covered && this.epoch == epoch) copy(covered = false) else this
