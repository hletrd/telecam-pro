package com.hletrd.findx9tele.camera

import android.util.Size
import androidx.compose.runtime.Immutable

/** Photo vs video capture mode. */
enum class CaptureMode { PHOTO, VIDEO }

/** Truthful scope promised by the media-review delete confirmation. */
enum class MediaDeleteScope { CAPTURE_FAMILY, FILE_ONLY }

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

/**
 * Delivery-framing markers drawn over the viewfinder (Sony "Frame Lines"): a centered box of the
 * target aspect, for judging a crop that will happen in post (scope, square, vertical).
 */
enum class FrameLineType(val label: String, val ratio: Float?) {
    OFF("Off", null),
    CINEMA("2.39:1", 2.39f),
    SQUARE("1:1", 1f),
    VERTICAL("9:16", 9f / 16f),
}

/** Tap-AF / spot-metering region size as a fraction of the active array (Sony Spot S/M/L). */
enum class AfSpotSize(val fraction: Float, val label: String) {
    SMALL(0.06f, "S"),
    MEDIUM(0.10f, "M"),
    LARGE(0.16f, "L"),
}

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
    STANDARD("Standard"),
    ENHANCED("Active");

    /** CONTROL_VIDEO_STABILIZATION_MODE value for the HAL modes; null for [OFF]. */
    val halControlMode: Int?
        get() = when (this) {
            STANDARD -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ENHANCED -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            else -> null
        }
}

/** Sony-style memory recall banks: complete shooting setups saved by the user. */
enum class MemorySlot(val label: String) { MR1("MR1"), MR2("MR2"), MR3("MR3") }

/**
 * Customizable shooting-screen Fn bar slots. The first six defaults mirror the current always-visible
 * dials; the rest are quick toggles/cycles a Sony user expects to keep out of the deep menu.
 */
enum class FnSlot(val label: String) {
    EXPOSURE_MODE("AE"),
    FOCUS("Focus"),
    SHUTTER("Shutter"),
    ISO("ISO"),
    WB("WB"),
    EV("EV"),
    ZOOM("Zoom"),
    STABILIZATION("Stabilization"),
    DRIVE("Drive"),
    METERING("Meter"),
    PEAKING("Peaking"),
    ZEBRA("Zebra"),
    TRANSFER("Gamma"),
    AUDIO_SCENE("Audio"),
    GRID("Grid"),
    LEVEL("Level"),
    PUNCH_IN("Loupe"),
    TELECONVERTER("Tele"),
    OPEN_GATE("Open Gate"),
    FRAME_LINES("Frame");

    companion object {
        val PHOTO_DEFAULT = listOf(EXPOSURE_MODE, FOCUS, SHUTTER, ISO, WB, EV)
        val VIDEO_DEFAULT = listOf(EXPOSURE_MODE, FOCUS, SHUTTER, ISO, WB, TRANSFER, STABILIZATION, AUDIO_SCENE)
        val DEFAULT = PHOTO_DEFAULT
        val MY_MENU_DEFAULT = listOf(STABILIZATION, PEAKING, ZEBRA, DRIVE, METERING, TRANSFER)
    }
}

/** Assignable action for physical keys. Camera slide zoom remains fixed because it has direction. */
enum class HardwareKeyAction(val label: String) {
    SHUTTER("Shutter/REC"),
    AF_ON("AF-ON"),
    AEL("AEL"),
    PUNCH_IN("Punch-In"),
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    NONE("None"),
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
enum class WbMode { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY, SHADE, CUSTOM, MANUAL }

/** Measured custom white balance: raw R/G_even/G_odd/B channel gains (Camera2 RggbChannelVector). */
data class WbGains(val r: Float, val gEven: Float, val gOdd: Float, val b: Float)

/**
 * Coarse AF-engine state for the tap-AF reticle color (Sony green-on-lock / red-on-fail). Mapped
 * from CaptureResult.CONTROL_AF_STATE by [fromHal] — plain int constants, so the mapping is
 * JVM-unit-testable.
 */
enum class AfIndication {
    IDLE, SCANNING, FOCUSED, FAILED;

    companion object {
        fun fromHal(state: Int): AfIndication = when (state) {
            android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN,
            android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN,
            -> SCANNING
            android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
            android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            -> FOCUSED
            android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
            -> FAILED
            else -> IDLE
        }
    }
}

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
 * Preferred recording input. AUTO lets Android pick the route; the others are resolved against
 * currently connected input-capable AudioDeviceInfo entries when video recording starts.
 */
enum class AudioInputPreference(val label: String) {
    AUTO("Auto"),
    BUILT_IN("Phone"),
    WIRED("Wired"),
    USB("USB"),
    BLUETOOTH("BT"),
}

/**
 * The Explorer teleconverter's angular magnification: ~300 mm effective over the native ~70 mm
 * periscope (≈4.286×). Single source of truth — this factor feeds the HAL zoom hints, the EXIF
 * 35 mm focal, the OSD focal readout, and the handheld-shutter rule; a corrected optic measurement
 * must change exactly one constant.
 */
const val TELECONVERTER_MAGNIFICATION = 300f / 70f

// TELE mode's DISPLAY zoom scale (converter-equivalent, main-relative): local 1.0 on the 3× lens
// with the 4.3× converter ≈ 13×. The user-spec range is 13–60× with magnetic snaps at 30× / 60×.
val TELE_DISPLAY_BASE: Float = (LensChoice.TELE3X.targetEquivMm / LensChoice.MAIN.targetEquivMm) * TELECONVERTER_MAGNIFICATION
const val TELE_MAX_DISPLAY_ZOOM = 60f

// TELE finder PIP: an opt-in corner viewport re-drawing the FULL current camera frame while the
// main view is magnified. Single-stream honesty: the HAL's CONTROL_ZOOM_RATIO crop is baked into
// the delivered frames, so the finder can only ever be as wide as the last HAL field — wider than
// the main view while GL zoom compensation (mid-gesture) or punch-in magnifies past it, identical
// once the HAL converges. A true unzoomed/wide 3× finder needs a second wide camera stream — see
// docs/BACKLOG.md (design item; this HAL's multi-stream fragility makes it a device-verified
// project, not a GL change). Fractions of the preview box, shared between the GL draw (content)
// and the Compose overlay (border) through [finderRect] so both boxes stay pixel-aligned.
const val FINDER_FRACTION = 0.30f
const val FINDER_MARGIN = 0.03f
// The punch-in loupe's texcoord crop: the magnified preview samples a (1-crop) span of the frame
// (0.6 → 2.5× magnification). Shared between the GL draw (gl/GlPipeline) and the tap-mapping
// composition in CameraEngine (P2.8/AGG4-11) so the two cannot drift.
const val PUNCH_IN_CROP = 0.6f

/**
 * The engine-RESOLVED half of the finder gate (everything except the zoom floor): user toggle,
 * TELE mounted, PHOTO mode, 4:3. Photo-only because the finder is a still-composition aid and
 * 4:3 is the STILL aspect — in video that setting is semantically unrelated to the recorded
 * framing, so keying the overlay off it made the PIP appear/vanish with a photo setting mid-clip.
 * 16:9 is excluded because the AspectMask pillarboxes would dim/misframe the corner box.
 * ONE implementation for the engine (`pushTeleFinder`) and the Compose border — the same
 * hand-written condition used to live in three places and could silently drift.
 */
fun teleFinderResolved(
    enabled: Boolean,
    teleconverter: Boolean,
    videoMode: Boolean,
    aspect: AspectRatio,
): Boolean = enabled && teleconverter && !videoMode && aspect == AspectRatio.W4_3

/**
 * The full visibility gate: the resolved flag plus an ACTIVE punch-in loupe (AGG4-29/P3.4). The
 * single camera stream means the PIP re-draws the SAME delivered frame as the main view — it is
 * only genuinely WIDER while the loupe magnifies past that frame, so the loupe is the honest gate
 * axis. The old raw zoom floor (1.15×) showed a corner box that duplicated the main view ~1:1 at
 * steady state ("adds nothing" by its own comment — the exact thing UX_POLICY says not to ship).
 * GL applies the same axis to its own resolved flag via its punch-in state.
 */
fun teleFinderVisible(
    enabled: Boolean,
    teleconverter: Boolean,
    videoMode: Boolean,
    aspect: AspectRatio,
    punchIn: Boolean,
): Boolean = teleFinderResolved(enabled, teleconverter, videoMode, aspect) && punchIn

/** Finder-PIP box in the preview box's own units, measured from the bottom-left corner. */
data class FinderRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The one geometry rule for the finder PIP, shared by the GL scissor/viewport (pixels) and the
 * Compose border overlay (dp): a [fraction]-sized box of the FULL preview box, inset by [margin]
 * of the short edge from the bottom-left corner. Both consumers MUST derive their rect from here —
 * the original Compose modifier chain (`padding` before `fillMaxWidth`) sized the border from
 * padding-reduced constraints and drew it ~6% smaller than the GL content box.
 */
fun finderRect(
    boxWidth: Float,
    boxHeight: Float,
    fraction: Float = FINDER_FRACTION,
    margin: Float = FINDER_MARGIN,
): FinderRect {
    val inset = minOf(boxWidth, boxHeight) * margin
    return FinderRect(inset, inset, boxWidth * fraction, boxHeight * fraction)
}

/**
 * Whether a top-left-origin UI pointer lands inside the bottom-left-origin finder rectangle.
 * Keeping this beside [finderRect] prevents the non-interactive PIP hit block from drifting away
 * from the GL viewport and Compose border it protects.
 */
fun finderContainsTopLeftPoint(
    pointX: Float,
    pointY: Float,
    boxWidth: Float,
    boxHeight: Float,
): Boolean {
    if (boxWidth <= 0f || boxHeight <= 0f) return false
    val rect = finderRect(boxWidth, boxHeight)
    val top = boxHeight - rect.y - rect.height
    return pointX >= rect.x && pointX <= rect.x + rect.width &&
        pointY >= top && pointY <= top + rect.height
}
val TELE_ZOOM_SNAPS = floatArrayOf(30f, 60f)

/**
 * The four rear lenses, addressed by their 35mm-equivalent focal length (the app resolves each to
 * the back camera whose equiv focal is closest — no hardcoded ids). [TELE3X] is the 3×/70 mm
 * periscope the Hasselblad teleconverter clamps onto. Lens picks are ZOOM PRESETS on the seamless
 * logical camera — they do NOT bundle teleconverter mode: TELE stays on only when it already is
 * AND the pick is its 3× host lens, and the separate TELE toggle owns converter shooting (the
 * afocal 180° flip — stabilization at 300 mm is the HAL's OIS+EIS via [VideoStabMode], not
 * app-side gyro warping).
 */
enum class LensChoice(val targetEquivMm: Float, val label: String, val zoomPreset: Float) {
    ULTRAWIDE(14f, "0.6×", 0.6f),
    MAIN(23f, "1×", 1f),
    TELE3X(70f, "3×", 3f),
    TELE10X(230f, "10×", 10f);

    val isTeleconverterLens: Boolean get() = this == TELE3X

    companion object {
        /**
         * The lens band a MAIN-relative zoom sits in — which chip to highlight while a pinch sweeps
         * the logical camera's unified 0.6–20× range (the HAL crosses the physical lenses at ~these
         * ratios). Pure for JVM tests.
         */
        fun forZoom(zoom: Float): LensChoice = when {
            zoom < 1f -> ULTRAWIDE
            zoom < 3f -> MAIN
            zoom < 10f -> TELE3X
            else -> TELE10X
        }
    }
}

/**
 * HAL-native log video via the vendor key `com.oplus.log.video.mode` (int32) — the device's own
 * session key for O-Log recording. Unlike the GL-baked curve (which can only re-map the ISP's
 * display-referred SDR output), this makes the ISP emit a SCENE-REFERRED log stream from sensor
 * data, before the OEM display tone mapping.
 *
 * The key is advertised in this device's `availableRequestKeys` AND `availableSessionKeys` for the
 * tele, so setting it via Camera2 is standard vendor-tag usage. SUPERSEDED 2026-07-09: the key is
 * INERT for third-party Camera2 — with it set (session parameter + every request, both
 * TEMPLATE_PREVIEW and TEMPLATE_RECORD) the preview AND recorded clip stay display-referred 709;
 * the earlier 2026-07-06 "genuinely engages the log pipeline" reading was the BT.2020 full-range
 * container tag being misread as a washed look (see CameraEngine.setTransfer and CLAUDE.md). The
 * plumbing below stays DORMANT for a future CameraUnit-authenticated scene-referred stream.
 *
 * CAVEAT: the resulting log is not a drop-in for OPPO's published O-Log2 LUT — it appears
 * scene-referred WITHOUT baked white balance (warm ambient reads warm), which a colorist neutralizes
 * in grade. For a LUT-accurate deliverable, the GL O-Log2 path ([ColorTransfer.LOG]) is exact. This
 * mode is for maximum latitude / minimal in-camera processing. Deliberately NOT persisted: an
 * experimental device mode must never survive a relaunch.
 */
enum class VendorLogMode(val halValue: Int) { OFF(0), ON(1) }

/** Shutter drive mode. */
enum class DriveMode { SINGLE, BURST, AEB, TIMELAPSE }

/** A requested single shot ignores the saved Photo drive mode. */
internal fun captureDriveMode(selected: DriveMode, singleShot: Boolean): DriveMode =
    if (singleShot) DriveMode.SINGLE else selected

/**
 * Video codec. HEVC exposes Main10 HLG/Log profiles; AVC is 8-bit SDR only. APV is the professional
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
 * OEM O-Log2's ~120 Mbps class) — the old HIGH (0.16) left over half the HW headroom unused.
 */
enum class BitrateLevel(val bpp: Float) {
    LOW(0.06f), MEDIUM(0.10f), HIGH(0.16f), ULTRA(0.26f), MAX(0.40f)
}

/**
 * A selectable video frame rate. [encoderRate] is the TRUE rate handed to the encoder
 * (`MediaFormat.KEY_FRAME_RATE` as a float): the NTSC drop-frame rates are the real fractions
 * (24000/1001 ≈ 23.976, 30000/1001 ≈ 29.97, 60000/1001 ≈ 59.94), not their rounded neighbours.
 * [fps] is the rounded integer used for exposure math (AE target-fps range, cine shutter angle,
 * sensor frame duration) and for capability gating. [highSpeed] marks dormant 120+ entries retained
 * for persisted-schema compatibility and diagnostic session machinery. Shipping [availableFor]
 * always excludes them because the constrained high-speed session SIGABRTs this device's HAL.
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
    // Dormant in the shipping picker; see availableFor and CameraViewModel's restore guard.
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
         * Returns an empty list when capabilities are missing or advertise no compatible normal
         * rate; callers must show/handle that explicit unavailable state rather than invent 30 fps.
         */
        fun availableFor(caps: CameraCaps?, size: Size, codec: VideoCodec): List<VideoFrameRate> {
            if (caps == null) return emptyList()
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
            return out
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

/** Capture aspect ratio. W4_3 = the sensor-native full readout (no crop); W16_9 = center crop. */
// Only the two ratios that matter for this 4:3-native sensor: 4:3 is the full sensor readout, 16:9
// is a center crop of it. (1:1 / portrait dropped — not meaningful for this camera.)
enum class AspectRatio(val w: Int, val h: Int) { W4_3(4, 3), W16_9(16, 9) }

internal data class AspectDimensions(val width: Float, val height: Float)

/**
 * Still aspect as displayed in the portrait viewfinder. The sensor's approximately 90°
 * orientation swaps its width/height axes before Compose draws the capture mask.
 */
internal fun displayedStillAspect(ratio: AspectRatio): AspectDimensions =
    AspectDimensions(width = ratio.h.toFloat(), height = ratio.w.toFloat())

/** Largest target-aspect rectangle centered inside a container. */
internal data class CenteredRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * One floating-point geometry seam for a centered aspect fit. Sensor crop integerization remains
 * in [centerCropBox]; host tests bind its pixel rounding to this representation.
 */
internal fun largestCenteredRect(
    containerWidth: Float,
    containerHeight: Float,
    aspectWidth: Float,
    aspectHeight: Float,
): CenteredRect {
    if (containerWidth <= 0f || containerHeight <= 0f || aspectWidth <= 0f || aspectHeight <= 0f) {
        return CenteredRect(x = 0f, y = 0f, width = 0f, height = 0f)
    }
    val heightForFullWidth = containerWidth * aspectHeight / aspectWidth
    val (width, height) = if (heightForFullWidth <= containerHeight) {
        containerWidth to heightForFullWidth
    } else {
        (containerHeight * aspectWidth / aspectHeight) to containerHeight
    }
    return CenteredRect(
        x = (containerWidth - width) / 2f,
        y = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/**
 * Photo output formats. Any non-empty supported combination can be enabled at once. [heif] and
 * [jpeg] are alternative containers for the processed still (HEIF = smaller, JPEG = universal);
 * both share one HAL-JPEG or logical-camera YUV source and run the same processed-pixel pipeline
 * (decode → aspect crop → afocal/device rotation → re-encode) — the mandatory 180° rotation means
 * neither container is a straight byte passthrough. [dngRaw] adds a full-frame RAW sensor file and
 * can be the only output when the active standalone session actually exposes RAW.
 */
data class PhotoFormats(
    val heif: Boolean = true,
    val jpeg: Boolean = false,
    // RAW is session-dependent and therefore starts off on the logical-camera default. TELE or an
    // eligible standalone session can enable it explicitly; the engine retains a defensive guard.
    val dngRaw: Boolean = false,
) {
    /**
     * True when capture needs the processed ImageReader: BOTH processed containers (HEIF and JPEG)
     * are produced from that one HAL-JPEG/YUV stream, so the controller must request it when either
     * is enabled — gating it on [heif] alone made a JPEG-only selection fail with
     * "no capture target".
     */
    val wantsProcessedStill: Boolean get() = heif || jpeg
}

/** Actual still readers present in one accepted Camera2 session. */
data class PhotoSessionOutputs(
    val processed: Boolean = false,
    val raw: Boolean = false,
) {
    val hasStillTarget: Boolean get() = processed || raw
}

/** One owner-bound engine publication; Ready is valid only while both generations still match. */
data class CameraReadyPublication(
    val sequence: Long,
    val ready: Boolean,
    val opticsGeneration: Long,
    val sessionGeneration: Long,
    val photoOutputs: PhotoSessionOutputs = PhotoSessionOutputs(),
)

/** Latest-event identity gate for callbacks delivered across camera/setup/main threads. */
internal class CameraReadyPublicationGate {
    private val latestSequence = java.util.concurrent.atomic.AtomicLong(0)

    fun observe(publication: CameraReadyPublication): Boolean =
        latestSequence.accumulateAndGet(publication.sequence, ::maxOf) == publication.sequence

    fun owns(publication: CameraReadyPublication): Boolean =
        latestSequence.get() == publication.sequence
}

/** One ordered tap-point ownership event crossing the engine/ViewModel thread boundary. */
data class TapFocusPublication(
    val sequence: Long,
    val held: Boolean,
    /** View-normalized reticle point; present only for a submitted held request. */
    val point: Pair<Float, Float>? = null,
) {
    init {
        require(held == (point != null)) { "A held tap publication must carry exactly one point" }
    }
}

/** Camera-thread result of replacing the repeating request for one tap-owned AF/AE point. */
internal enum class TapFocusSubmissionResult {
    /** Required CANCEL/START (unless AF Lock owns the lens) and repeating were accepted. */
    ACCEPTED,
    /** Nothing from the attempted point reached Camera2, or the prior request was restored. */
    REJECTED_PREVIOUS_RESTORED,
    /** A partial request may have reached Camera2 and the prior request could not be restored. */
    FAILED_UNCERTAIN,
}

/** Prevents a delayed controller-loss event from clearing a newer accepted tap point. */
internal class TapFocusPublicationGate {
    private val latestSequence = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Applies one event while this gate owns its ordering boundary. Unlike an observe-then-post
     * sequence, a newer retirement cannot land between the last ownership check and the UI update;
     * out-of-order older callbacks are rejected without running [apply].
     */
    @Synchronized
    fun applyIfLatest(publication: TapFocusPublication, apply: () -> Unit): Boolean {
        if (publication.sequence < latestSequence.get()) return false
        latestSequence.set(publication.sequence)
        apply()
        return true
    }
}

/** Keeps a persisted/pre-session request non-empty without guessing which session outputs exist. */
internal fun PhotoFormats.withDefaultIfEmpty(): PhotoFormats =
    if (wantsProcessedStill || dngRaw) this else copy(heif = true)

/**
 * Resolves a request against readers that actually survived session fallback. Available requested
 * outputs win; otherwise prefer processed HEIF, then RAW-only DNG, and represent preview-only as an
 * empty set instead of inventing an unavailable capture target.
 */
internal fun PhotoFormats.normalizedFor(outputs: PhotoSessionOutputs): PhotoFormats {
    val supported = PhotoFormats(
        heif = heif && outputs.processed,
        jpeg = jpeg && outputs.processed,
        dngRaw = dngRaw && outputs.raw,
    )
    if (supported.wantsProcessedStill || supported.dngRaw) return supported
    return when {
        outputs.processed -> PhotoFormats(heif = true)
        outputs.raw -> PhotoFormats(heif = false, dngRaw = true)
        else -> PhotoFormats(heif = false, jpeg = false, dngRaw = false)
    }
}

/**
 * Immutable snapshot the UI renders. Hardware-independent so it can be previewed/unit-tested.
 * [controls] holds capture parameters; the remaining fields are viewfinder assists and app state.
 *
 * @Immutable is a PROMISE to the Compose compiler (PERF4-1): every field is replaced wholesale via
 * copy() and never mutated in place (the IntArray-bearing scope types are fresh per analysis tick).
 * Without it, strong skipping compared instances by identity and every telemetry emission
 * recomposed every whole-state child (~10-25 Hz during ordinary shooting).
 */
@Immutable
data class CameraUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val controls: ManualControls = ManualControls(),
    val transfer: ColorTransfer = ColorTransfer.HLG,
    val photoFormats: PhotoFormats = PhotoFormats(),
    // Reader truth from the accepted session, never inferred from route capabilities. Cleared while
    // opening/reconfiguring so stale fallback outputs cannot admit a capture.
    val photoSessionOutputs: PhotoSessionOutputs = PhotoSessionOutputs(),
    val recordAudio: Boolean = true,
    // Directional audio (Sound Focus / Sound Stage) via device audio-HAL params.
    val audioScene: AudioScene = AudioScene.STANDARD,
    val audioInputPreference: AudioInputPreference = AudioInputPreference.AUTO,
    val audioRouteLabel: String = "Auto",
    val audioGain: Float = 1f, // 0..2 software gain applied to recorded PCM
    val audioLevel: Float = 0f, // 0..1 live input level (RMS), for the meter
    val aspectRatio: AspectRatio = AspectRatio.W4_3,
    // Displayed preview aspect (W/H as shown on the portrait screen; the ~90° sensor orientation
    // already swaps the stream's W/H). The viewfinder TextureView is sized to this and letterboxed,
    // so the FULL capture field is visible — photo mode previews the 4:3 sensor, video the recording
    // stream. Default = 4:3 shown portrait (3/4), matching the fresh-launch photo mode.
    val previewAspect: Float = 3f / 4f,
    // Monotonic tick per shutter press — the viewfinder blinks on change (instant feedback while
    // the still itself takes pipeline-depth × frame-duration to even START exposing).
    val shutterFlashTick: Int = 0,
    // AF engine state (from CONTROL_AF_STATE) coloring the tap-AF reticle.
    val afIndication: AfIndication = AfIndication.IDLE,
    // Live camera health: false while opening/reconfiguring/recovering (and after recovery gives
    // up). The shutter dims on it so a dead session never hides behind a ready-looking button.
    val cameraReady: Boolean = false,
    // True while the full-screen media-review overlay is up (also used to freeze its media URI).
    val reviewOpen: Boolean = false,
    // One Activity-facing gate for every full-screen modal (settings, Fn, review). Hardware shutter,
    // zoom and half-press input must not mutate the hidden viewfinder behind any of them.
    val cameraInputBlocked: Boolean = false,
    // Selected rear lens. Default 1× main for a normal app launch. Lens picks are zoom presets
    // and do NOT bundle teleconverter mode (see [LensChoice]); the TELE toggle owns the converter.
    val lens: LensChoice = LensChoice.MAIN,
    // Teleconverter mode: manual (not auto-detected). ON = afocal 180° flip; locked to the 3× lens.
    val teleconverterMode: Boolean = false,
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
    // TELE finder PIP Assist toggle (default OFF; see FINDER_* above for the honest contract).
    val teleFinder: Boolean = false,
    // Sony-style customization: Fn row, My Menu, recent changed settings and MR banks.
    val photoFnSlots: List<FnSlot> = FnSlot.PHOTO_DEFAULT,
    val videoFnSlots: List<FnSlot> = FnSlot.VIDEO_DEFAULT,
    val myMenuSlots: List<FnSlot> = FnSlot.MY_MENU_DEFAULT,
    val recentSettingSlots: List<FnSlot> = emptyList(),
    val activeMemorySlot: MemorySlot? = null,
    val savedMemorySlots: Set<MemorySlot> = emptySet(),
    val memorySlotNames: Map<MemorySlot, String> = emptyMap(),
    val memorySlotSummaries: Map<MemorySlot, String> = emptyMap(),
    // Hardware controls. The OPPO half-press key defaults to AF-ON; volume/camera full press defaults
    // to shutter/REC. [halfPressActive] only drives the viewfinder feedback ring/chip.
    val volumeKeyAction: HardwareKeyAction = HardwareKeyAction.SHUTTER,
    val halfPressAction: HardwareKeyAction = HardwareKeyAction.AF_ON,
    val halfPressActive: Boolean = false,
    // Gamma Display Assist (Sony): while shooting O-Log, the MONITOR shows the normal 709-ish image
    // and only the FILE stays log. Off = judge the flat log directly.
    val gammaAssist: Boolean = false,
    // Delivery-framing markers over the viewfinder.
    val frameLines: FrameLineType = FrameLineType.OFF,
    // Battery % and free storage for the OSD info pill (refreshed by a slow ticker; -1 = unknown).
    val batteryPct: Int = -1,
    val freeBytes: Long = -1L,
    // When true, pro settings are persisted across launches and restored on next start (default on).
    val rememberSettings: Boolean = true,
    // Granular launch-restore policy for optics. Both default ON so existing operator choices survive
    // relaunches, while a fresh install still opens on the 1× main lens with TELE off.
    val preserveLensSelection: Boolean = true,
    val preserveTeleconverter: Boolean = true,
    // Transient tap point (normalized 0..1 in view space) for the focus/meter reticle; null means
    // the large reticle has faded, not necessarily that the functional AF/AE point was released.
    val tapPoint: Pair<Float, Float>? = null,
    // Functional tap-owned AF/AE region. Kept separate from tapPoint so the 2 s visual fade cannot
    // turn a still-active hold into invisible state; the UI exposes a persistent reset affordance.
    val tapFocusHeld: Boolean = false,
    // AE-resolved exposure while in auto (from CaptureResult); null in manual or before the first
    // result. Lets the Shutter/ISO chips show what AE actually chose instead of just "Auto".
    val liveIso: Int? = null,
    val liveExposureNs: Long? = null,
    // Live lens focus distance (diopters, from CaptureResult); null before the first result. Shows
    // where AF parked the lens and seeds the manual slider on the AF→MF handoff.
    val liveFocusDiopters: Float? = null,
    // Runtime
    val isRecording: Boolean = false,
    // Recorder admission succeeded but the encoder input has not yet attached to EGL. Controls stay
    // locked and the shutter remains a stop action, while tally/timer wait for genuine readiness.
    val isRecordingStarting: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val timerCountdownSec: Int = 0,
    val caps: CameraCaps? = null,
    val cameraOverrideId: String? = null,
    val statusMessage: String? = null,
    // The newest saved capture owner (HEIF/JPEG/video, or RAW when no displayable sibling exists).
    val lastMediaUri: android.net.Uri? = null,
    // Canonical live/restored families can delete every known sibling; legacy filenames cannot.
    val lastMediaDeleteScope: MediaDeleteScope = MediaDeleteScope.FILE_ONLY,
    val histogramData: HistogramData? = null,
    val waveformData: WaveformData? = null,
) {
    val activeFnSlots: List<FnSlot>
        get() = if (mode == CaptureMode.VIDEO) videoFnSlots else photoFnSlots
    val stillCaptureReady: Boolean
        get() = cameraReady && photoSessionOutputs.hasStillTarget
    val primaryShutterHealthy: Boolean
        get() = cameraReady && (mode == CaptureMode.VIDEO || photoSessionOutputs.hasStillTarget)
    val primaryShutterEnabled: Boolean
        get() = when {
            // A running self-timer is itself a primary-shutter action: tapping the shutter again
            // cancels it even if camera readiness changes during the countdown.
            mode == CaptureMode.PHOTO -> timerCountdownSec > 0 || stillCaptureReady
            isRecording -> true // stopping REC must survive a concurrent camera-health transition
            else -> cameraReady
        }
}

/** Downsampled luminance + per-channel histogram (256 bins) for the viewfinder overlay.
 * @Immutable: arrays are written once by the analysis executor before publication (PERF4-1). */
@Immutable
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
@Immutable
data class WaveformData(
    val columns: Int,
    val rows: Int,
    val bins: IntArray,
)
