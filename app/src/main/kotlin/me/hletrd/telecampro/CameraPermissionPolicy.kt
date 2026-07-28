package me.hletrd.telecampro

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
