package com.hletrd.findx9tele.camera

import android.util.Size

/** Photo vs video capture mode. */
enum class CaptureMode { PHOTO, VIDEO }

/**
 * Video transfer function. HLG = HDR-viewable; LOG = flat, for grading (our GL applies the curve);
 * SDR = plain Rec.709 with no GL curve — HEVC Main 8-bit, for footage that needs zero grading.
 */
enum class ColorTransfer { HLG, LOG, SDR }

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

/**
 * Video stabilization strategy. The important consequence for the 300 mm teleconverter: at a fixed
 * video shutter (e.g. 1/60 s) the per-frame MOTION BLUR is set by the shutter, and only OIS — which
 * physically counter-moves the lens DURING the exposure — can reduce it. Frame-warp-only EIS steadies
 * jitter but cannot de-blur, so the app relies entirely on the HAL's own OIS+EIS profiles. (The
 * app-side gyro-EIS mode was dropped — unusable at 300 mm; [com.hletrd.findx9tele.stab.GyroEis]
 * stays only for device-orientation + gravity roll.)
 *
 *  - [OFF]      — no stabilization (OIS still follows the separate OIS toggle).
 *  - [STANDARD] — HAL `CONTROL_VIDEO_STABILIZATION_MODE_ON`: the HAL's own OIS+EIS.
 *  - [ENHANCED] — HAL `PREVIEW_STABILIZATION`: the modern combined OIS+EIS behind "super steady"
 *                 (the HAL also exposes the vendor mirror `com.oplus.video.stabilization.mode`).
 *                 Reduces motion blur via OIS; best on the tele.
 *
 * The HAL modes are gated by `CameraCaps.videoStabModes`.
 */
enum class VideoStabMode(val label: String) {
    OFF("Off"),
    STANDARD("OIS Std"),
    ENHANCED("OIS Enhanced");

    /** CONTROL_VIDEO_STABILIZATION_MODE value for the HAL modes; null for [OFF]. */
    val halControlMode: Int?
        get() = when (this) {
            STANDARD -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ENHANCED -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            else -> null
        }
}

/** Focus-peaking edge-detection threshold; a LOWER threshold highlights more edges (more sensitive). */
enum class PeakingLevel(val threshold: Float) { LOW(0.12f), MEDIUM(0.06f), HIGH(0.03f) }

/** Focus-peaking highlight color (RGB 0..1). */
enum class PeakingColor(val r: Float, val g: Float, val b: Float) {
    RED(1f, 0.15f, 0.15f),
    GREEN(0.1f, 1f, 0.25f),
    BLUE(0.3f, 0.55f, 1f),
    YELLOW(1f, 0.9f, 0f),
    MAGENTA(1f, 0.1f, 0.7f),
}

/** Zebra threshold: luma above which clipping stripes are drawn (100 = only fully clipped). */
enum class ZebraLevel(val threshold: Float) { IRE70(0.70f), IRE85(0.85f), IRE95(0.95f), CLIP100(1.0f) }

/** White balance: AUTO, a named preset (CONTROL_AWB_MODE_*), or MANUAL (Kelvin + tint). */
enum class WbMode { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY, SHADE, MANUAL }

/** Metering pattern for auto-exposure. SPOT/CENTER use an AE region; MATRIX uses the whole frame. */
enum class MeteringMode { MATRIX, CENTER, SPOT }

/**
 * Recording audio scene: the device's Sound Focus / Sound Stage. These run through the vendor
 * audio-HAL parameters (`vendor_audiorecord_effect_type` + friends) — NOT the standard
 * `AudioRecord.setPreferredMicrophoneDirection`, which the PMA110 HAL rejects. [effectType]
 * is the vendor int the HAL expects:
 *  - STANDARD (1) — normal stereo pickup.
 *  - SOUND_FOCUS (2) — directional "audio zoom": narrows the pickup toward the framed subject and
 *    tightens with optical/digital zoom (the 300 mm use case). Sets focus_zoom + focus_angle too.
 *  - SOUND_STAGE (5) — widened spatial stereo image.
 */
enum class AudioScene(val effectType: Int, val label: String) {
    STANDARD(1, "Standard"),
    SOUND_FOCUS(2, "Sound Focus"),
    SOUND_STAGE(5, "Sound Stage"),
}

/**
 * The four rear lenses, addressed by their 35mm-equivalent focal length (the app resolves each to
 * the back camera whose equiv focal is closest — no hardcoded ids). [TELE3X] is the 3×/70 mm
 * periscope the Hasselblad teleconverter clamps onto; selecting it bundles teleconverter mode ON
 * (afocal 180° flip + gyro-EIS scaled to the ~300 mm effective focal), and selecting any other lens
 * turns teleconverter mode OFF, all in one action.
 */
enum class LensChoice(val targetEquivMm: Float, val label: String) {
    ULTRAWIDE(14f, "UW"),
    MAIN(23f, "1×"),
    TELE3X(70f, "3×"),
    TELE10X(230f, "10×");

    val isTeleconverterLens: Boolean get() = this == TELE3X
}

/**
 * HAL-native log video via the vendor key `com.oplus.log.video.mode` (int32) — the device's own
 * session key for O-Log recording. Unlike the GL-baked curve (which can only re-map the ISP's
 * display-referred SDR output), this makes the ISP emit a SCENE-REFERRED log stream from sensor
 * data, before the OEM display tone mapping.
 *
 * The key is advertised in this device's `availableRequestKeys` AND `availableSessionKeys` for the
 * tele (dumpsys 2026-07-06), so setting it via Camera2 is legal API. Device-verified 2026-07-06: ON
 * genuinely engages the HAL log pipeline (recorded stream goes flat/low-contrast, mean luma ~half of
 * SDR, GL curve off). Values 1 and 2 produced identical output → the key is on/off here.
 *
 * CAVEAT: the resulting log is not a drop-in for OPPO's published O-Log2 LUT — it appears
 * scene-referred WITHOUT baked white balance (warm ambient reads warm), which a colorist neutralizes
 * in grade. For a LUT-accurate deliverable, the GL O-Log2 path ([ColorTransfer.LOG]) is exact. This
 * mode is for maximum latitude / minimal in-camera processing. Deliberately NOT persisted: an
 * experimental HAL mode must never survive a relaunch.
 */
enum class VendorLogMode(val halValue: Int) { OFF(0), ON(1) }

/** Shutter drive mode. */
enum class DriveMode { SINGLE, BURST, AEB, TIMELAPSE }

/**
 * Video codec. HEVC supports 10-bit HLG/Log; AVC is 8-bit SDR only. APV is the professional
 * intra-frame codec (`c2.qti.apv.encoder`, ISO/IEC 21794) — HW-accelerated up to ~2 Gbps, the
 * closest thing to ProRes / XAVC-I on this device (all-intra, huge bitrate, grade-ready). (AV1 was
 * removed — the only encoder on this SoC is software `c2.android.av1.encoder`, too slow to ship.)
 * Which of these are actually offered is decided at runtime from [android.media.MediaCodecList]
 * (see [com.hletrd.findx9tele.video.EncoderCaps]).
 */
enum class VideoCodec { HEVC, AVC, APV }

/**
 * Video bitrate level as bits-per-pixel-per-frame factor. The top presets reach the QTI HW encoder
 * ceilings measured on this device (HEVC/AVC ≈ 100 Mbps at 4K; MAX ≈ 100 Mbps at 4K30, matching the
 * stock O-Log2's ~120 Mbps class) — the old HIGH (0.16) left over half the HW headroom unused.
 */
enum class BitrateLevel(val bpp: Float) {
    LOW(0.06f), MEDIUM(0.10f), HIGH(0.16f), ULTRA(0.26f), MAX(0.40f)
}

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
        /** The default: 29.97 fps NTSC drop-frame (the standard cine/broadcast rate). */
        val DEFAULT = FPS_29_97

        /**
         * The frame rates the [caps] camera can actually deliver at [size] with [codec], honoring:
         *  - 8K (height ≥ 4320) is capped to ≤30 fps (encoder + thermal reality);
         *  - normal (non-high-speed) rates require the camera to advertise the integer [fps] as a
         *    fixed AE target-fps range (this device exposes 24/30/60 → 25/50 are correctly dropped),
         *    so a drop-frame rate rides on its integer parent (29.97 needs 30, etc.);
         *  - high-speed rates (120) are NEVER offered — the constrained high-speed session SIGABRTs
         *    this device's HAL (QA-confirmed), so [FPS_120] is intentionally unselectable.
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
            val out = entries.filter { r ->
                when {
                    is8k && r.fps > 30 -> false
                    // High-speed (≥120 fps constrained session) is disabled outright: it SIGABRTs the
                    // HAL on this device (QA-confirmed), so no high-speed rate is ever selectable —
                    // [highSpeedMaxFps] is ignored on purpose.
                    r.highSpeed -> false
                    else -> normalFps.contains(r.fps)
                }
            }
            return out.ifEmpty { listOf(FPS_30) }
        }
    }
}

/**
 * Resolved video bitrate (bits/s) for [width]×[height] at [encoderRate] fps and the [bpp]
 * bits-per-pixel-per-frame level, clamped to a sane floor and a codec-specific ceiling (APV's
 * all-intra pipe goes far higher than the QTI HEVC/AVC encoders). Shared by the engine (to
 * configure the encoder) and the UI (to display the exact Mbps).
 */
fun videoBitRate(width: Int, height: Int, encoderRate: Double, bpp: Float, codec: VideoCodec): Int {
    val raw = (bpp.toDouble() * width * height * encoderRate).toLong()
    // Per-codec ceilings from this device's media_codecs.xml: APV pro-intra tops out ~2 Gbps but is
    // capped to a storage-sane 480 Mbps here; QTI HEVC/AVC advertise ~100-120 Mbps.
    val ceiling = when (codec) {
        VideoCodec.APV -> 480_000_000L
        else -> 120_000_000L
    }
    return raw.coerceIn(8_000_000L, ceiling).toInt()
}

/**
 * APV needs a far higher bits-per-pixel than a Long-GOP codec: it is ALL-INTRA (every frame a
 * keyframe), so the [BitrateLevel] bpp is scaled up when the codec is APV to land in the pro-intra
 * range (~ProRes 422 HQ / XAVC-I). e.g. 4K30 MEDIUM → ~200 Mbps, HIGH → ~320 Mbps.
 */
fun effectiveBpp(level: BitrateLevel, codec: VideoCodec): Float =
    if (codec == VideoCodec.APV) level.bpp * 8f else level.bpp

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
) {
    /**
     * True when the capture needs the camera's JPEG stream: BOTH processed containers (HEIF and
     * JPEG) are produced from that single ImageReader, so the controller must be asked for it when
     * either is enabled — gating it on [heif] alone made a JPEG-only selection fail with
     * "no capture target".
     */
    val wantsProcessedStill: Boolean get() = heif || jpeg
}

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
    // Directional audio (stock Sound Focus / Sound Stage) via vendor audio-HAL params.
    val audioScene: AudioScene = AudioScene.STANDARD,
    val audioGain: Float = 1f, // 0..2 software gain applied to recorded PCM
    val audioLevel: Float = 0f, // 0..1 live input level (RMS), for the meter
    val aspectRatio: AspectRatio = AspectRatio.W4_3,
    // Selected rear lens. Default 3× — the teleconverter lens. Selecting it bundles teleconverter
    // mode on; other lenses bundle it off (see [LensChoice]).
    val lens: LensChoice = LensChoice.TELE3X,
    // Teleconverter mode: manual (not auto-detected). ON = afocal 180° flip; locked to the 3× lens.
    val teleconverterMode: Boolean = true,
    // Stabilization. Default ENHANCED = HAL OIS+EIS ("super steady"): at 300 mm it reduces the
    // per-frame motion blur (see [VideoStabMode]).
    val videoStabMode: VideoStabMode = VideoStabMode.ENHANCED,
    // Video
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.ULTRA,
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
    val peakingLevel: PeakingLevel = PeakingLevel.MEDIUM,
    val peakingColor: PeakingColor = PeakingColor.MAGENTA,
    val zebra: Boolean = false,
    val zebraLevel: ZebraLevel = ZebraLevel.IRE95,
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
    // AE-resolved exposure while in auto (from CaptureResult); null in manual or before the first
    // result. Lets the Shutter/ISO chips show what AE actually chose instead of just "Auto".
    val liveIso: Int? = null,
    val liveExposureNs: Long? = null,
    // Live lens focus distance (diopters, from CaptureResult); null before the first result. Shows
    // where AF parked the lens and seeds the manual slider on the AF→MF handoff.
    val liveFocusDiopters: Float? = null,
    // Runtime
    val isRecording: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val timerCountdownSec: Int = 0,
    val caps: CameraCaps? = null,
    val cameraOverrideId: String? = null,
    val statusMessage: String? = null,
    // The most recently saved still (HEIF/JPEG) — powers the gallery thumbnail and the in-app review.
    val lastMediaUri: android.net.Uri? = null,
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
