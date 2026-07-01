package com.hletrd.findx9tele.camera

/** Photo vs video capture mode. */
enum class CaptureMode { PHOTO, VIDEO }

/** Video transfer function. HLG = HDR-viewable; LOG = flat, for grading (our GL applies the curve). */
enum class ColorTransfer { HLG, LOG }

/** Focus behaviour. MANUAL drives LENS_FOCUS_DISTANCE; others use the AF engine. */
enum class FocusMode { MANUAL, AUTO, CONTINUOUS, MACRO }

/** Powerline anti-banding for exposure. */
enum class Antibanding { AUTO, HZ50, HZ60, OFF }

/** Processing quality level for edge (sharpening) and noise reduction. */
enum class ProcessingLevel { OFF, FAST, HIGH_QUALITY }

/** In-camera color effect. */
enum class ColorEffect { NONE, MONO, NEGATIVE, SEPIA, AQUA, POSTERIZE }

/** Flash behaviour (TORCH = constant on). */
enum class FlashMode { OFF, AUTO, ON, TORCH }

/** Composition grid style. */
enum class GridType { NONE, THIRDS, GOLDEN, SQUARE, CENTER }

/** Self-timer before the shutter fires. */
enum class ShutterTimer(val seconds: Int) { OFF(0), SEC3(3), SEC10(10) }

/** Photo output formats. Both can be enabled at once. */
data class PhotoFormats(
    val heif: Boolean = true,
    val dngRaw: Boolean = true,
)

/**
 * Immutable snapshot the UI renders. Hardware-independent so it can be previewed/unit-tested.
 * [controls] holds capture parameters; the remaining fields are viewfinder assists and app state.
 */
data class CameraUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val controls: ManualControls = ManualControls(),
    val transfer: ColorTransfer = ColorTransfer.HLG,
    val photoFormats: PhotoFormats = PhotoFormats(),
    val recordAudio: Boolean = true,
    // Teleconverter mode: manual (not auto-detected). ON = afocal 180° flip + EIS scaled to 300mm.
    val teleconverterMode: Boolean = true,
    // Stabilization
    val eisEnabled: Boolean = true,
    // Viewfinder assists
    val focusPeaking: Boolean = false,
    val zebra: Boolean = false,
    val falseColor: Boolean = false,
    val histogram: Boolean = false,
    val grid: GridType = GridType.THIRDS,
    val level: Boolean = false,
    val punchIn: Boolean = false,
    // Drive
    val timer: ShutterTimer = ShutterTimer.OFF,
    // Runtime
    val isRecording: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val timerCountdownSec: Int = 0,
    val caps: CameraCaps? = null,
    val cameraOverrideId: String? = null,
    val statusMessage: String? = null,
    val histogramData: HistogramData? = null,
)

/** Downsampled luminance + per-channel histogram (256 bins) for the viewfinder overlay. */
data class HistogramData(
    val luma: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
)
