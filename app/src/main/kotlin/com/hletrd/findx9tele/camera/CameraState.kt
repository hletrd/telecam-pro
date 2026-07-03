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

/**
 * Video codec. HEVC supports 10-bit HLG/Log; AVC is 8-bit SDR only; AV1 is SW-only on this device
 * (`c2.android.av1.encoder` — no HW AV1 on SM8850), so it is slow and gated to ≤1080p/≤30fps.
 * Which of these are actually offered is decided at runtime from [android.media.MediaCodecList]
 * (see [com.hletrd.findx9tele.video.EncoderCaps]).
 */
enum class VideoCodec { HEVC, AVC, AV1 }

/** Video bitrate level as bits-per-pixel-per-frame factor. */
enum class BitrateLevel(val bpp: Float) { LOW(0.06f), MEDIUM(0.10f), HIGH(0.16f) }

/**
 * A selectable video frame rate. [encoderRate] is the TRUE rate handed to the encoder
 * (`MediaFormat.KEY_FRAME_RATE` as a float): the NTSC drop-frame rates are the real fractions
 * (24000/1001 ≈ 23.976, 30000/1001 ≈ 29.97, 60000/1001 ≈ 59.94), not their rounded neighbours.
 * [fps] is the rounded integer used for exposure math (AE target-fps range, cine shutter angle,
 * sensor frame duration) and for capability gating. [highSpeed] rates (120+) can only be captured
 * through a CameraConstrainedHighSpeedCaptureSession and are offered only when the selected camera
 * advertises a matching high-speed config for the chosen size.
 */
enum class VideoFrameRate(
    val label: String,
    val encoderRate: Double,
    val fps: Int,
    val dropFrame: Boolean,
    val highSpeed: Boolean = false,
) {
    FPS_23_976("23.976", 24000.0 / 1001.0, 24, true),
    FPS_24("24", 24.0, 24, false),
    FPS_25("25", 25.0, 25, false),
    FPS_29_97("29.97", 30000.0 / 1001.0, 30, true),
    FPS_30("30", 30.0, 30, false),
    FPS_50("50", 50.0, 50, false),
    FPS_59_94("59.94", 60000.0 / 1001.0, 60, true),
    FPS_60("60", 60.0, 60, false),
    FPS_120("120", 120.0, 120, false, highSpeed = true);

    companion object {
        /** The default (30 fps) — matches [ManualControls.fps]'s default and the old fixed rate. */
        val DEFAULT = FPS_30

        /**
         * The frame rates the [caps] camera can actually deliver at [size] with [codec], honoring:
         *  - 8K (height ≥ 4320) is capped to ≤30 fps (encoder + thermal reality);
         *  - normal (non-high-speed) rates require the camera to advertise the integer [fps] as a
         *    fixed AE target-fps range (this device exposes 24/30/60 → 25/50 are correctly dropped),
         *    so a drop-frame rate rides on its integer parent (29.97 needs 30, etc.);
         *  - high-speed rates (120) require a matching entry in the camera's high-speed config list
         *    for [size] at ≥ that rate;
         *  - AV1 (software) is clamped to ≤1080p and ≤30 fps regardless of what the camera offers.
         * Always returns at least one rate so the UI never shows an empty selector.
         */
        fun availableFor(caps: CameraCaps?, size: Size, codec: VideoCodec): List<VideoFrameRate> {
            if (caps == null) return listOf(FPS_24, FPS_30, FPS_60)
            return availableFor(caps.availableFps.toSet(), caps.highSpeedFpsFor(size), size.width, size.height, codec)
        }

        /** Pure core of [availableFor] (no Android types), so the gating rules are unit-testable. */
        fun availableFor(
            normalFps: Set<Int>,
            highSpeedMaxFps: Int,
            width: Int,
            height: Int,
            codec: VideoCodec,
        ): List<VideoFrameRate> {
            val is8k = height >= 4320
            val av1 = codec == VideoCodec.AV1
            val out = entries.filter { r ->
                when {
                    is8k && r.fps > 30 -> false
                    av1 && (width > 1920 || r.fps > 30) -> false
                    r.highSpeed -> highSpeedMaxFps >= r.fps
                    else -> normalFps.contains(r.fps)
                }
            }
            return out.ifEmpty { listOf(FPS_30) }
        }
    }
}

/**
 * Resolved video bitrate (bits/s) for [width]×[height] at [encoderRate] fps and the [bpp]
 * bits-per-pixel-per-frame level, clamped to a sane floor and a codec-specific ceiling (AV1's SW
 * encoder tops out ~20 Mbps; the QTI HEVC/AVC encoders go far higher). Shared by the engine (to
 * configure the encoder) and the UI (to display the exact Mbps).
 */
fun videoBitRate(width: Int, height: Int, encoderRate: Double, bpp: Float, codec: VideoCodec): Int {
    val raw = (bpp.toDouble() * width * height * encoderRate).toInt()
    val ceiling = if (codec == VideoCodec.AV1) 20_000_000 else 200_000_000
    return raw.coerceIn(8_000_000, ceiling)
}

/** Capture aspect ratio. FULL = whole sensor; others crop. w/h = 0 means full. */
// Only the two ratios that matter for this 4:3-native sensor: 4:3 is the full sensor readout, 16:9
// is a center crop of it. (1:1 / portrait dropped — not meaningful for this camera.)
enum class AspectRatio(val w: Int, val h: Int) { W4_3(4, 3), W16_9(16, 9) }

/**
 * Photo output formats. Any combination can be enabled at once. [heif] and [jpeg] are alternative
 * containers for the processed still (HEIF = 10-bit-capable, smaller; JPEG = universal); JPEG is
 * written straight from the camera's JPEG ImageReader bytes with no HEIF re-encode. [dngRaw] adds
 * the full-frame RAW sensor file alongside either.
 */
data class PhotoFormats(
    val heif: Boolean = true,
    val jpeg: Boolean = false,
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
    val aspectRatio: AspectRatio = AspectRatio.W4_3,
    // Teleconverter mode: manual (not auto-detected). ON = afocal 180° flip + EIS scaled to 300mm.
    val teleconverterMode: Boolean = true,
    // Stabilization
    val eisEnabled: Boolean = true,
    val eisStrength: EisStrength = EisStrength.MEDIUM,
    // Video
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.MEDIUM,
    val videoResolution: Size = Size(3840, 2160),
    val videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT,
    // Open Gate: record the full 4:3 sensor readout instead of a 16:9 crop. Switches the resolution
    // selector to the camera's 4:3 sizes and encodes at that aspect.
    val openGate: Boolean = false,
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
    // When true, pro settings are persisted across launches and restored on next start (default on).
    val rememberSettings: Boolean = true,
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
