package com.hletrd.findx9tele.camera

/** Photo vs video capture mode. */
enum class CaptureMode { PHOTO, VIDEO }

/**
 * Video transfer function selection.
 * - HLG: standard Hybrid-Log-Gamma OETF, HDR-viewable directly (Rec.2020).
 * - LOG: flat log-like curve for grading; container tagged Rec.2020 but transfer is our custom OETF.
 */
enum class ColorTransfer { HLG, LOG }

/** Photo output formats the app writes. Both can be enabled simultaneously. */
data class PhotoFormats(
    val heif: Boolean = true,
    val dngRaw: Boolean = true,
)

/**
 * Immutable snapshot of everything the UI renders. Held by the ViewModel and pushed to Compose.
 * Hardware-independent so it can be unit-tested and previewed.
 */
data class CameraUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val controls: ManualControls = ManualControls(),
    val transfer: ColorTransfer = ColorTransfer.HLG,
    val photoFormats: PhotoFormats = PhotoFormats(),
    val recordAudio: Boolean = true,
    val focusPeaking: Boolean = false,
    val zebra: Boolean = false,
    val grid: Boolean = true,
    val level: Boolean = false,
    val punchIn: Boolean = false,
    val isRecording: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val caps: CameraCaps? = null,
    val cameraOverrideId: String? = null,
    val statusMessage: String? = null,
)
