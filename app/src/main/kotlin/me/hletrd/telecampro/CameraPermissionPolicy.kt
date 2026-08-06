package me.hletrd.telecampro

import me.hletrd.telecampro.camera.HardwareKeyAction

internal enum class CameraPermissionDisposition {
    GRANTED,
    REQUESTABLE,
    SETTINGS_REQUIRED,
}

/**
 * Classifies camera access without treating a missing rationale as permanent denial. Android returns
 * false for both a never-requested permission and a fixed denial, so [requestedBefore] records an
 * actual false launcher result. A canceled request produces no result and does not change history.
 */
internal fun classifyCameraPermission(
    granted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionDisposition = when {
    granted -> CameraPermissionDisposition.GRANTED
    shouldShowRationale -> CameraPermissionDisposition.REQUESTABLE
    requestedBefore -> CameraPermissionDisposition.SETTINGS_REQUIRED
    else -> CameraPermissionDisposition.REQUESTABLE
}

/** Updates durable request history; null means the permission dialog/request was canceled. */
internal fun updatedCameraPermissionRequestHistory(
    requestedBefore: Boolean,
    result: Boolean?,
): Boolean = when (result) {
    true -> false
    false -> true
    null -> requestedBefore
}

/** Why the app is about to ask for RECORD_AUDIO. */
internal enum class PendingAudioAction { ENABLE_AUDIO, START_RECORDING }

/** What a DECLINED microphone request should still do for the intent that triggered it. */
internal enum class MicrophoneDeclineOutcome {
    /** Leave audio off and stop there. */
    AUDIO_OFF,

    /** Leave audio off, then start the recording anyway — video-only. */
    AUDIO_OFF_AND_RECORD,
}

/**
 * Resolves a microphone denial into what should still happen.
 *
 * A [PendingAudioAction.START_RECORDING] intent keeps its recording: `VideoRecorder` records
 * video-only whenever RECORD_AUDIO is absent, so refusing the take would withhold something the
 * pipeline can fully deliver. Only an explicit [PendingAudioAction.ENABLE_AUDIO] — where recording
 * was never requested — ends at audio-off.
 */
internal fun microphoneDeclineOutcome(action: PendingAudioAction?): MicrophoneDeclineOutcome =
    when (action) {
        PendingAudioAction.START_RECORDING -> MicrophoneDeclineOutcome.AUDIO_OFF_AND_RECORD
        PendingAudioAction.ENABLE_AUDIO, null -> MicrophoneDeclineOutcome.AUDIO_OFF
    }

/**
 * Whether [action] must hold for the microphone permission before running.
 *
 * A recording start needs it only when audio is actually wanted for that take; once audio is off
 * (including right after a denial) the shutter runs straight through instead of re-prompting.
 */
internal fun microphonePermissionRequired(
    action: PendingAudioAction,
    videoMode: Boolean,
    recording: Boolean,
    recordAudio: Boolean,
): Boolean = when (action) {
    PendingAudioAction.ENABLE_AUDIO -> true
    PendingAudioAction.START_RECORDING -> videoMode && !recording && recordAudio
}

/**
 * Whether a microphone grant should hand the operator their audio back.
 *
 * `recordAudio = false` conflates two states that are not the same thing: "this operator wants a
 * silent clip" and "we gave up on audio because the permission was refused". Only the first is a
 * preference. The second is a consequence — and it was SELF-LOCKING, because
 * [microphonePermissionRequired] makes a START_RECORDING prompt conditional on `recordAudio`, so
 * once audio is off the shutter never asks again. Granting the permission in Android Settings
 * therefore changed nothing: clips stayed silent AND the level meter stayed hidden (its UI gate is
 * the same flag), which is exactly the "I allowed the microphone but it is still mute" report.
 *
 * So remember WHICH of the two it was. A denial-disabled audio track is restored the moment the
 * permission is observed granted; audio the operator switched off themselves stays off forever.
 * This mirrors what CAMERA already does one function over — a Settings grant there resets the denial
 * history rather than letting an obsolete refusal outlive itself.
 */
internal fun audioRestoredByMicrophoneGrant(
    audioDisabledByDenial: Boolean,
    recordAudio: Boolean,
    hasMicrophonePermission: Boolean,
): Boolean = audioDisabledByDenial && !recordAudio && hasMicrophonePermission


/**
 * Whether a hardware full-key press should silently drop audio and record video-only.
 *
 * The hardware shutter deliberately never opens the rationale dialog — a physical press should start
 * the take, not a modal — so when the mic is wanted but absent it drops audio and proceeds. Two
 * constraints this predicate exists to hold:
 *
 * - The full-press action is USER-REASSIGNABLE ([HardwareKeyAction]): a volume key mapped to Zoom In
 *   must NOT flip the audio setting off — pre-fix it did, and because `recordAudio = false` also
 *   suppresses the touch path's mic prompt, one zoom press while un-granted silently condemned every
 *   later recording to video-only without the user ever being asked (2026-07-30 review C7).
 * - Only the SHUTTER action may drop audio, and the caller must record that drop as a DENIAL
 *   consequence (AUDIO_OFF_BY_DENIAL_KEY), not a preference — otherwise a later Settings grant
 *   cannot restore it and the self-locking silent-clip state returns (review C8).
 */
internal fun hardwareShutterAudioDrop(
    fullKeyAction: HardwareKeyAction,
    videoMode: Boolean,
    recording: Boolean,
    recordAudio: Boolean,
    hasMicrophonePermission: Boolean,
): Boolean = fullKeyAction == HardwareKeyAction.SHUTTER &&
    !hasMicrophonePermission &&
    microphonePermissionRequired(
        action = PendingAudioAction.START_RECORDING,
        videoMode = videoMode,
        recording = recording,
        recordAudio = recordAudio,
    )

/**
 * Which visual-media READ grant is in force.
 *
 * [PARTIAL] is Android 14+'s "Select photos": READ_MEDIA_VISUAL_USER_SELECTED granted while the full
 * permissions are denied. It is genuinely access — MediaStore widens to the user-selected set — but
 * it is access to a set the USER chose, which is why it cannot be collapsed into [FULL]. See
 * [shouldRequestVisualMediaAccess] for the distinction that costs a bug when it is missing.
 */
internal enum class VisualMediaAccess { NONE, PARTIAL, FULL }

/** Resolves the grant triple to a level. FULL wins: either broad permission subsumes USER_SELECTED. */
internal fun visualMediaAccessLevel(
    imagesGranted: Boolean,
    videoGranted: Boolean,
    userSelectedGranted: Boolean,
): VisualMediaAccess = when {
    imagesGranted || videoGranted -> VisualMediaAccess.FULL
    userSelectedGranted -> VisualMediaAccess.PARTIAL
    else -> VisualMediaAccess.NONE
}

/**
 * Whether ANY visual-media READ grant is in force. Android 14+'s "Select photos" flow grants
 * READ_MEDIA_VISUAL_USER_SELECTED while denying the full permissions — that partial grant still
 * widens MediaStore visibility to the user-selected set and must count as access, not a denial
 * (2026-08-01, reinstall gallery restore). Pure so the three-way OR is pinned by a host test
 * rather than re-derived at each call site.
 *
 * Expressed via [visualMediaAccessLevel] so "is there access" and "which level" cannot drift apart.
 */
internal fun hasVisualMediaAccess(
    imagesGranted: Boolean,
    videoGranted: Boolean,
    userSelectedGranted: Boolean,
): Boolean = visualMediaAccessLevel(imagesGranted, videoGranted, userSelectedGranted) !=
    VisualMediaAccess.NONE

/**
 * Whether an empty-gallery tap should (re-)launch the media-access request rather than just re-run
 * the restore.
 *
 * WHY THIS IS NOT `!hasVisualMediaAccess(...)` (the bug it exists to fix, 2026-08-06 Play review
 * policy:H1): a PARTIAL grant answers "yes, there is access", so the old call site took the
 * already-have-access branch and only re-ran the restore. But the restore is exactly what just came
 * back empty — the gallery tap only happens when there is nothing to review. A user who chose
 * "Select photos" and did not hand-pick their own `DCIM/TeleCamPro` files was therefore left with an
 * empty gallery and NO in-app path to widen the selection, ever: every subsequent tap re-ran the
 * same empty query and the system picker was never shown again.
 *
 * Re-requesting under a partial grant is Android's own documented remedy — the platform re-shows the
 * selection UI rather than treating it as an already-answered permission. So:
 *
 * - [VisualMediaAccess.NONE] → request (the original contextual ask).
 * - [VisualMediaAccess.PARTIAL] → request AGAIN, so the user can add the files they missed.
 * - [VisualMediaAccess.FULL] → never; there is nothing further to grant, and re-asking a user who
 *   already said yes to everything is pure nag.
 *
 * No loop risk: the launcher's result callback re-runs the restore on any access and does not call
 * back into this predicate, so a user who cancels or picks nothing simply stays where they were.
 * PARTIAL is unreachable below API 34 (the permission does not exist), so API 33 sees only
 * NONE/FULL and behaves exactly as before.
 */
internal fun shouldRequestVisualMediaAccess(level: VisualMediaAccess): Boolean =
    level != VisualMediaAccess.FULL
