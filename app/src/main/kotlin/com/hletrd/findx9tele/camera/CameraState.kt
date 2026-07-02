package com.hletrd.findx9tele.camera

import android.util.Size

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

/** How shutter is expressed: absolute SPEED (exposure time) or cine ANGLE (relative to fps). */
enum class ShutterMode { SPEED, ANGLE }

/** Electronic stabilization crop strength (headroom). OFF handled by the EIS toggle. */
enum class EisStrength(val crop: Float) { LOW(0.06f), MEDIUM(0.10f), HIGH(0.18f) }

/** White balance: AUTO, a named preset (CONTROL_AWB_MODE_*), or MANUAL (Kelvin + tint). */
enum class WbMode { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY, SHADE, MANUAL }

/** Metering pattern for auto-exposure. SPOT/CENTER use an AE region; MATRIX uses the whole frame. */
enum class MeteringMode { MATRIX, CENTER, SPOT }

/** Shutter drive mode. */
enum class DriveMode { SINGLE, BURST, AEB, TIMELAPSE }

/** Video codec. HEVC supports 10-bit HLG/Log; AVC is 8-bit SDR only. */
enum class VideoCodec { HEVC, AVC }

/** Video bitrate level as bits-per-pixel-per-frame factor. */
enum class BitrateLevel(val bpp: Float) { LOW(0.06f), MEDIUM(0.10f), HIGH(0.16f) }

/** Capture aspect ratio. FULL = whole sensor; others crop. w/h = 0 means full. */
enum class AspectRatio(val w: Int, val h: Int) { FULL(0, 0), W16_9(16, 9), W4_3(4, 3), W1_1(1, 1) }

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
    val audioGain: Float = 1f, // 0..2 software gain applied to recorded PCM
    val audioLevel: Float = 0f, // 0..1 live input level (RMS), for the meter
    val aspectRatio: AspectRatio = AspectRatio.FULL,
    // Teleconverter mode: manual (not auto-detected). ON = afocal 180° flip + EIS scaled to 300mm.
    val teleconverterMode: Boolean = true,
    // Stabilization
    val eisEnabled: Boolean = true,
    val eisStrength: EisStrength = EisStrength.MEDIUM,
    // Video
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.MEDIUM,
    val videoResolution: Size = Size(3840, 2160),
    // Drive
    val timer: ShutterTimer = ShutterTimer.OFF,
    val driveMode: DriveMode = DriveMode.SINGLE,
    val intervalSec: Int = 5,
    // Viewfinder assists
    val focusPeaking: Boolean = false,
    val zebra: Boolean = false,
    val falseColor: Boolean = false,
    val histogram: Boolean = false,
    val waveform: Boolean = false,
    val grid: GridType = GridType.THIRDS,
    val level: Boolean = false,
    val levelRoll: Float = 0f,
    // Physical device orientation (0/90/180/270) from gravity; rotates overlays to stay upright.
    val deviceOrientation: Int = 0,
    val punchIn: Boolean = false,
    // When true, pro settings are persisted across launches and restored on next start.
    val rememberSettings: Boolean = false,
    // Transient tap point (normalized 0..1 in view space) for the focus/meter reticle; null = none.
    val tapPoint: Pair<Float, Float>? = null,
    // Runtime
    val isRecording: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val timerCountdownSec: Int = 0,
    val caps: CameraCaps? = null,
    val cameraOverrideId: String? = null,
    val statusMessage: String? = null,
    val histogramData: HistogramData? = null,
    val waveformData: WaveformData? = null,
)

/** Downsampled luminance + per-channel histogram (256 bins) for the viewfinder overlay. */
data class HistogramData(
    val luma: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
)

/**
 * Luma waveform: for each of [columns] screen columns, [rows] vertical luma buckets holding a count.
 * `bins[col * rows + row]` — row 0 = brightest (top), row [rows-1] = darkest (bottom).
 */
data class WaveformData(
    val columns: Int,
    val rows: Int,
    val bins: IntArray,
)
