package me.hletrd.telecampro.ui

import android.content.Context
import androidx.annotation.StringRes
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CameraStatus
import me.hletrd.telecampro.camera.CameraStatusArgument
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.ui.controls.lensLabel

@StringRes
internal fun CameraStatusMessage.stringResourceId(): Int = when (this) {
    CameraStatusMessage.STARTING_CAMERA -> R.string.status_starting_camera
    CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN -> R.string.status_preview_unavailable_reopen
    CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING -> R.string.status_preview_interrupted_recovering
    CameraStatusMessage.CAMERA_ERROR_RECOVERING -> R.string.status_camera_error_recovering
    CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN -> R.string.status_camera_unavailable_reopen
    CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING -> R.string.status_preview_unavailable_retrying
    CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING -> R.string.status_camera_unavailable_retrying
    CameraStatusMessage.STOP_RECORDING_FIRST -> R.string.status_stop_recording_first
    CameraStatusMessage.STOP_RECORDING_MODE_UNCHANGED -> R.string.status_stop_recording_mode_unchanged
    CameraStatusMessage.STOP_RECORDING_RECALL_UNCHANGED -> R.string.status_stop_recording_recall_unchanged
    CameraStatusMessage.STOP_RECORDING_LENS_UNCHANGED -> R.string.status_stop_recording_lens_unchanged
    CameraStatusMessage.STOP_RECORDING_CAMERA_UNCHANGED -> R.string.status_stop_recording_camera_unchanged
    CameraStatusMessage.SWITCH_TO_REAR_FIRST -> R.string.status_switch_to_rear_first
    CameraStatusMessage.CAMERA_UNAVAILABLE_MODE_UNCHANGED -> R.string.status_camera_unavailable_mode_unchanged
    CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED -> R.string.status_camera_unavailable_recall_unchanged
    CameraStatusMessage.CAMERA_UNAVAILABLE_FACING_UNCHANGED -> R.string.status_camera_unavailable_facing_unchanged
    CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED -> R.string.status_camera_unavailable_camera_unchanged
    CameraStatusMessage.PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED -> R.string.status_preview_unavailable_camera_unchanged
    CameraStatusMessage.FRONT_CAMERA_UNAVAILABLE -> R.string.status_front_camera_unavailable
    CameraStatusMessage.TELE_LENS_UNAVAILABLE_UNCHANGED -> R.string.status_tele_lens_unavailable_unchanged
    CameraStatusMessage.LENS_UNAVAILABLE_UNCHANGED -> R.string.status_lens_unavailable_unchanged
    CameraStatusMessage.SELECTED_RESOLUTION_UNAVAILABLE -> R.string.status_selected_resolution_unavailable
    CameraStatusMessage.SELECTED_FPS_UNAVAILABLE -> R.string.status_selected_fps_unavailable
    CameraStatusMessage.SELECTED_CODEC_UNAVAILABLE -> R.string.status_selected_codec_unavailable
    CameraStatusMessage.CAMERA_RECONFIGURING -> R.string.status_camera_reconfiguring
    CameraStatusMessage.STILL_CAPTURE_UNAVAILABLE -> R.string.status_still_capture_unavailable
    CameraStatusMessage.PROCESSED_STILL_UNAVAILABLE_DNG_ONLY -> R.string.status_processed_still_unavailable_dng_only
    CameraStatusMessage.RAW_UNAVAILABLE -> R.string.status_raw_unavailable
    CameraStatusMessage.FINISHING_PREVIOUS_PHOTO -> R.string.status_finishing_previous_photo
    CameraStatusMessage.PHOTO_CAPTURE_FAILED -> R.string.status_photo_capture_failed
    CameraStatusMessage.PHOTO_SAVE_FAILED -> R.string.status_photo_save_failed
    CameraStatusMessage.HEIF_SAVE_FAILED -> R.string.status_heif_save_failed
    CameraStatusMessage.JPEG_SAVE_FAILED -> R.string.status_jpeg_save_failed
    CameraStatusMessage.DNG_SAVE_FAILED -> R.string.status_dng_save_failed
    CameraStatusMessage.DNG_SAVE_DELAYED -> R.string.status_dng_save_delayed
    CameraStatusMessage.DNG_CAPTURE_FAILED -> R.string.status_dng_capture_failed
    CameraStatusMessage.OUTPUT_SAVED_PENDING -> R.string.status_output_saved_pending
    CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY -> R.string.status_output_saved_pending_recovery
    CameraStatusMessage.FINISHING_PREVIOUS_CLIP -> R.string.status_finishing_previous_clip
    CameraStatusMessage.RECORDING_ALREADY_ACTIVE -> R.string.status_recording_already_active
    CameraStatusMessage.RECORDING_FAILED -> R.string.status_recording_failed
    CameraStatusMessage.MICROPHONE_BUSY -> R.string.status_microphone_busy
    CameraStatusMessage.UNSAFE_RECORDER_RESTART -> R.string.status_unsafe_recorder_restart
    CameraStatusMessage.VIDEO_SAVED -> R.string.status_video_saved
    CameraStatusMessage.VIDEO_SAVE_DELAYED -> R.string.status_video_save_delayed
    CameraStatusMessage.VIDEO_SAVE_FAILED -> R.string.status_video_save_failed
    CameraStatusMessage.RECORDING_WITHOUT_AUDIO -> R.string.status_recording_without_audio
    CameraStatusMessage.MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO -> R.string.status_microphone_denied_recording_without_audio
    CameraStatusMessage.MICROPHONE_DENIED_AUDIO_OFF -> R.string.status_microphone_denied_audio_off
    CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON -> R.string.status_microphone_allowed_audio_on
    CameraStatusMessage.STANDBY_MICROPHONE_UNAVAILABLE -> R.string.status_standby_microphone_unavailable
    CameraStatusMessage.USE_AUTO_WB -> R.string.status_use_auto_wb
    CameraStatusMessage.CUSTOM_WB_MEASUREMENT_FAILED -> R.string.status_custom_wb_measurement_failed
    CameraStatusMessage.CUSTOM_WB_SET -> R.string.status_custom_wb_set
    CameraStatusMessage.AUDIO_INPUT_USING_DEFAULT -> R.string.status_audio_input_using_default
    CameraStatusMessage.MEMORY_SLOT_SAVED -> R.string.status_memory_slot_saved
    CameraStatusMessage.MEMORY_SLOT_EMPTY -> R.string.status_memory_slot_empty
    CameraStatusMessage.MEMORY_SLOT_LOADED -> R.string.status_memory_slot_loaded
    CameraStatusMessage.DELETED -> R.string.status_deleted
    CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_CAPTURE -> R.string.status_some_files_not_deleted_retry_capture
    CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY -> R.string.status_some_files_not_deleted_retry_gallery
    CameraStatusMessage.COULD_NOT_DELETE_FILE -> R.string.status_could_not_delete_file
}

internal fun CameraStatus.resolve(context: Context): String {
    val args = arguments.map<CameraStatusArgument, Any> {
        when (it) {
            is CameraStatusArgument.Text -> it.value
            is CameraStatusArgument.Number -> it.value
            is CameraStatusArgument.AudioInput -> it.value.resolve(context)
            is CameraStatusArgument.Lens -> lensLabel(it.value)
        }
    }.toTypedArray()
    return context.getString(message.stringResourceId(), *args)
}
