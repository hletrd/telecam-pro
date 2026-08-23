package me.hletrd.telecampro.camera

import androidx.compose.runtime.Immutable

/** Stable message identity emitted by camera, capture, storage, recorder, and UI intent layers. */
enum class CameraStatusMessage {
    STARTING_CAMERA,
    PREVIEW_UNAVAILABLE_REOPEN,
    PREVIEW_INTERRUPTED_RECOVERING,
    CAMERA_ERROR_RECOVERING,
    CAMERA_UNAVAILABLE_REOPEN,
    PREVIEW_UNAVAILABLE_RETRYING,
    CAMERA_UNAVAILABLE_RETRYING,
    STOP_RECORDING_FIRST,
    STOP_RECORDING_MODE_UNCHANGED,
    STOP_RECORDING_RECALL_UNCHANGED,
    STOP_RECORDING_LENS_UNCHANGED,
    STOP_RECORDING_CAMERA_UNCHANGED,
    SWITCH_TO_REAR_FIRST,
    CAMERA_UNAVAILABLE_MODE_UNCHANGED,
    CAMERA_UNAVAILABLE_RECALL_UNCHANGED,
    CAMERA_UNAVAILABLE_FACING_UNCHANGED,
    CAMERA_UNAVAILABLE_CAMERA_UNCHANGED,
    PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED,
    FRONT_CAMERA_UNAVAILABLE,
    TELE_LENS_UNAVAILABLE_UNCHANGED,
    LENS_UNAVAILABLE_UNCHANGED,
    SELECTED_RESOLUTION_UNAVAILABLE,
    SELECTED_FPS_UNAVAILABLE,
    SELECTED_CODEC_UNAVAILABLE,
    CAMERA_RECONFIGURING,
    STILL_CAPTURE_UNAVAILABLE,
    PROCESSED_STILL_UNAVAILABLE_DNG_ONLY,
    RAW_UNAVAILABLE,
    FINISHING_PREVIOUS_PHOTO,
    PHOTO_CAPTURE_FAILED,
    PHOTO_SAVE_FAILED,
    HEIF_SAVE_FAILED,
    JPEG_SAVE_FAILED,
    DNG_SAVE_FAILED,
    DNG_SAVE_DELAYED,
    DNG_CAPTURE_FAILED,
    OUTPUT_SAVED_PENDING,
    OUTPUT_SAVED_PENDING_RECOVERY,
    FINISHING_PREVIOUS_CLIP,
    RECORDING_ALREADY_ACTIVE,
    RECORDING_FAILED,
    MICROPHONE_BUSY,
    UNSAFE_RECORDER_RESTART,
    VIDEO_SAVED,
    VIDEO_SAVE_FAILED,
    RECORDING_WITHOUT_AUDIO,
    MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO,
    MICROPHONE_DENIED_AUDIO_OFF,
    MICROPHONE_ALLOWED_AUDIO_ON,
    STANDBY_MICROPHONE_UNAVAILABLE,
    USE_AUTO_WB,
    CUSTOM_WB_MEASUREMENT_FAILED,
    CUSTOM_WB_SET,
    AUDIO_INPUT_USING_DEFAULT,
    MEMORY_SLOT_SAVED,
    MEMORY_SLOT_EMPTY,
    MEMORY_SLOT_LOADED,
    DELETED,
    SOME_FILES_NOT_DELETED_RETRY_CAPTURE,
    SOME_FILES_NOT_DELETED_RETRY_GALLERY,
    COULD_NOT_DELETE_FILE,
}

enum class CameraStatusSeverity { INFO, SUCCESS, WARNING, ERROR }
enum class CameraStatusLivePriority { POLITE, ASSERTIVE }
enum class CameraStatusLifecycle { PROGRESS, EVENT }

/** A formatting argument with no presentation wording embedded in the domain event. */
sealed interface CameraStatusArgument {
    @Immutable data class Text(val value: String) : CameraStatusArgument
    @Immutable data class Number(val value: Long) : CameraStatusArgument
    @Immutable data class AudioInput(val value: AudioInputPreference) : CameraStatusArgument
    @Immutable data class Lens(val value: LensChoice) : CameraStatusArgument
}

/**
 * Fully typed transient presentation event. Metadata is fixed at creation rather than inferred by
 * searching translated copy, so changing a translation cannot alter urgency or dismissal.
 */
@Immutable
data class CameraStatus(
    val message: CameraStatusMessage,
    val arguments: List<CameraStatusArgument> = emptyList(),
    val severity: CameraStatusSeverity,
    val livePriority: CameraStatusLivePriority,
    val lifecycle: CameraStatusLifecycle,
    val durationMs: Long?,
)

fun CameraStatusMessage.status(
    vararg arguments: CameraStatusArgument,
): CameraStatus {
    val severity = when (this) {
        CameraStatusMessage.STARTING_CAMERA,
        CameraStatusMessage.CAMERA_RECONFIGURING,
        CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING,
        CameraStatusMessage.FINISHING_PREVIOUS_PHOTO,
        CameraStatusMessage.FINISHING_PREVIOUS_CLIP,
        CameraStatusMessage.RECORDING_WITHOUT_AUDIO,
        CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON,
        -> CameraStatusSeverity.INFO

        CameraStatusMessage.VIDEO_SAVED,
        CameraStatusMessage.CUSTOM_WB_SET,
        CameraStatusMessage.MEMORY_SLOT_SAVED,
        CameraStatusMessage.MEMORY_SLOT_LOADED,
        CameraStatusMessage.DELETED,
        -> CameraStatusSeverity.SUCCESS

        CameraStatusMessage.STOP_RECORDING_FIRST,
        CameraStatusMessage.STOP_RECORDING_MODE_UNCHANGED,
        CameraStatusMessage.STOP_RECORDING_RECALL_UNCHANGED,
        CameraStatusMessage.STOP_RECORDING_LENS_UNCHANGED,
        CameraStatusMessage.STOP_RECORDING_CAMERA_UNCHANGED,
        CameraStatusMessage.SWITCH_TO_REAR_FIRST,
        CameraStatusMessage.PROCESSED_STILL_UNAVAILABLE_DNG_ONLY,
        CameraStatusMessage.OUTPUT_SAVED_PENDING,
        CameraStatusMessage.DNG_SAVE_DELAYED,
        CameraStatusMessage.RECORDING_ALREADY_ACTIVE,
        CameraStatusMessage.MICROPHONE_BUSY,
        CameraStatusMessage.MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO,
        CameraStatusMessage.MICROPHONE_DENIED_AUDIO_OFF,
        CameraStatusMessage.USE_AUTO_WB,
        CameraStatusMessage.AUDIO_INPUT_USING_DEFAULT,
        CameraStatusMessage.MEMORY_SLOT_EMPTY,
        -> CameraStatusSeverity.WARNING

        CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN,
        CameraStatusMessage.CAMERA_ERROR_RECOVERING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN,
        CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_MODE_UNCHANGED,
        CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED,
        CameraStatusMessage.CAMERA_UNAVAILABLE_FACING_UNCHANGED,
        CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED,
        CameraStatusMessage.PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED,
        CameraStatusMessage.FRONT_CAMERA_UNAVAILABLE,
        CameraStatusMessage.TELE_LENS_UNAVAILABLE_UNCHANGED,
        CameraStatusMessage.LENS_UNAVAILABLE_UNCHANGED,
        CameraStatusMessage.SELECTED_RESOLUTION_UNAVAILABLE,
        CameraStatusMessage.SELECTED_FPS_UNAVAILABLE,
        CameraStatusMessage.SELECTED_CODEC_UNAVAILABLE,
        CameraStatusMessage.STILL_CAPTURE_UNAVAILABLE,
        CameraStatusMessage.RAW_UNAVAILABLE,
        CameraStatusMessage.PHOTO_CAPTURE_FAILED,
        CameraStatusMessage.PHOTO_SAVE_FAILED,
        CameraStatusMessage.HEIF_SAVE_FAILED,
        CameraStatusMessage.JPEG_SAVE_FAILED,
        CameraStatusMessage.DNG_SAVE_FAILED,
        CameraStatusMessage.DNG_CAPTURE_FAILED,
        CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY,
        CameraStatusMessage.RECORDING_FAILED,
        CameraStatusMessage.UNSAFE_RECORDER_RESTART,
        CameraStatusMessage.VIDEO_SAVE_FAILED,
        CameraStatusMessage.STANDBY_MICROPHONE_UNAVAILABLE,
        CameraStatusMessage.CUSTOM_WB_MEASUREMENT_FAILED,
        CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_CAPTURE,
        CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
        CameraStatusMessage.COULD_NOT_DELETE_FILE,
        -> CameraStatusSeverity.ERROR
    }
    val lifecycle = if (this == CameraStatusMessage.STARTING_CAMERA) {
        CameraStatusLifecycle.PROGRESS
    } else {
        CameraStatusLifecycle.EVENT
    }
    val duration = when {
        lifecycle == CameraStatusLifecycle.PROGRESS -> null
        severity == CameraStatusSeverity.ERROR -> 6_000L
        severity == CameraStatusSeverity.SUCCESS -> 1_500L
        else -> 2_500L
    }
    return CameraStatus(
        message = this,
        arguments = arguments.toList(),
        severity = severity,
        livePriority = if (severity == CameraStatusSeverity.ERROR) {
            CameraStatusLivePriority.ASSERTIVE
        } else {
            CameraStatusLivePriority.POLITE
        },
        lifecycle = lifecycle,
        durationMs = duration,
    )
}
