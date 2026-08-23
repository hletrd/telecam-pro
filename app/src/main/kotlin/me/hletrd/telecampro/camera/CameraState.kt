package me.hletrd.telecampro.camera

import android.util.Size
import androidx.compose.runtime.Immutable
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.video.AudioRouteAvailability
import me.hletrd.telecampro.video.AudioRouteStatus

/** Photo vs video capture mode. */
enum class CaptureMode { PHOTO, VIDEO }

/**
 * Which side's camera the session is on. FRONT is a first-class optics door (its own generation-
 * owned transaction, like mode/lens/TC), but deliberately a BASIC one: the teleconverter, the
 * back focal rail, RAW-on-TELE, and the finder PIP are all rear-optics concepts and are forced
 * off / hidden while FRONT. Facing is NEVER persisted — this app exists for the rear tele, and a
 * relaunch that surprised the operator with a selfie camera would be wrong — so a fresh launch is
 * always BACK and SettingsStore carries no facing field.
 */
enum class CameraFacing { BACK, FRONT }

/**
 * The accepted camera route. Unlike [CameraFacing], this preserves EXTERNAL as a first-class
 * identity: an external camera has no fixed relationship to the host display, so treating it as
 * BACK corrupts rotation, zoom presentation, accessibility, and persisted-optics rules.
 */
enum class CameraRoute {
    BACK,
    FRONT,
    EXTERNAL;

    val facing: CameraFacing
        get() = if (this == FRONT) CameraFacing.FRONT else CameraFacing.BACK

    val lensLocalZoom: Boolean
        get() = this != BACK
}

/** Why a back-only optics door (TC toggle, lens preset) refused, in refusal priority order. */
internal enum class BackOpticsRefusal { NONE, RECORDING, FRONT_ROUTE }

/**
 * The ONE gate for optics doors that only exist on the rear camera (the TC toggle and the focal
 * rail's lens presets). Shared by the engine's defensive check and the ViewModel's user-facing
 * refusal message so the two can't disagree; RECORDING outranks FRONT_ROUTE because "Stop REC
 * first" is the actionable step even while FRONT (the flip door itself is recording-gated too).
 */
internal fun backOpticsDoorRefusal(recording: Boolean, frontFacing: Boolean): BackOpticsRefusal = when {
    recording -> BackOpticsRefusal.RECORDING
    frontFacing -> BackOpticsRefusal.FRONT_ROUTE
    else -> BackOpticsRefusal.NONE
}

/** Truthful scope promised by the media-review delete confirmation. */
enum class MediaDeleteScope { CAPTURE_FAMILY, FILE_ONLY }

/**
 * Exact family identity handed from review ownership to the Engine deletion boundary.
 *
 * [liveStillCaptureId] is deliberately narrower than a generic capture id: only a still admitted by
 * this Engine can produce a late HEIF/JPEG/DNG callback that needs the retained-still tombstone.
 * Restored families and video carry durable identity for MediaStore recovery without poisoning the
 * still producer gate.
 */
internal data class CaptureFamilyDeleteIntent(
    val familyKey: CaptureFamilyKey?,
    val scope: MediaDeleteScope,
    val liveStillCaptureId: Int? = null,
)

internal enum class CaptureFamilyDeleteDurability { NOT_REQUIRED, DURABLE, FAILED }

/**
 * Video transfer function. HLG = HDR-viewable; the [isLog] members are flat, for grading (our GL
 * bakes the industry-standard curve + gamut matrix — see [me.hletrd.telecampro.gl.LogProfiles]);
 * SDR = plain Rec.709 with no GL curve — HEVC Main 8-bit, for footage that needs zero grading.
 *
 * The log profiles replaced the former O-Log2 option (`LOG`, removed 2026-07-22: not a standard —
 * SettingsStore migrates the persisted "LOG" name to [SLOG3_CINE]). All three share one log-class
 * behavior: encoder gets the curve, preview renders it flat, Gamma Display Assist skips the forward
 * curve on the monitor only, and the container is tagged BT.2020 full-range with an explicit
 * SDR-class transfer (see [me.hletrd.telecampro.video.hevcColorTagsFor]). Like the old option,
 * these are display-referred curves — the accepted standard or HLG10 source is decoded first, and
 * the ISP has already tone-mapped either stream, so
 * this is grading convenience, NOT scene-referred camera log with recovered highlight latitude.
 */
enum class ColorTransfer(val isLog: Boolean = false) {
    HLG,
    /** Sony S-Log3 transfer in S-Gamut3 primaries. */
    SLOG3(isLog = true),
    /** Sony S-Log3 transfer in the smaller, grading-friendlier S-Gamut3.Cine primaries. */
    SLOG3_CINE(isLog = true),
    /** ARRI LogC3 (EI 800) transfer in ARRI Wide Gamut 3 primaries. */
    LOGC3(isLog = true),
    SDR,
}

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
enum class FrameLineType(val ratio: Float?) {
    OFF(null),
    CINEMA(2.39f),
    SQUARE(1f),
    VERTICAL(9f / 16f),
}

/** Tap-AF / spot-metering region size as a fraction of the active array (Sony Spot S/M/L). */
enum class AfSpotSize(val fraction: Float) {
    SMALL(0.06f),
    MEDIUM(0.10f),
    LARGE(0.16f),
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
 * app-side gyro-EIS mode was dropped — unusable at 300 mm; [me.hletrd.telecampro.stab.GyroEis]
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
enum class VideoStabMode {
    OFF,
    STANDARD,
    ENHANCED;

    /** CONTROL_VIDEO_STABILIZATION_MODE value for the HAL modes; null for [OFF]. */
    val halControlMode: Int?
        get() = when (this) {
            STANDARD -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ENHANCED -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            else -> null
        }
}

/** Sony-style memory recall banks: complete shooting setups saved by the user. */
enum class MemorySlot { MR1, MR2, MR3 }

/** Locale-neutral inputs for an MR row; Compose resolves the display prose for the active locale. */
@Immutable
data class MemoryPresetPresentation(
    val customName: String? = null,
    val customSummary: String? = null,
    val mode: CaptureMode,
    val focalMm: Float,
    val exposureMode: ExposureMode,
    val photoFormats: PhotoFormats,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoFrameRate: VideoFrameRate,
    val transfer: ColorTransfer,
    val bitrateLevel: BitrateLevel,
)

/**
 * Customizable shooting-screen Fn bar slots. The first six defaults mirror the current always-visible
 * dials; the rest are quick toggles/cycles a Sony user expects to keep out of the deep menu.
 */
enum class FnSlot {
    // "Mode", not "AE": this is the PASM selector, and "AE: M" (auto-exposure: manual) read as a
    // contradiction on the pro surface (2026-07-31 UI review #22). The sheet row is already "Mode".
    EXPOSURE_MODE,
    FOCUS,
    SHUTTER,
    ISO,
    WB,
    EV,
    ZOOM,
    STABILIZATION,
    DRIVE,
    METERING,
    PEAKING,
    ZEBRA,
    TRANSFER,
    // Pairs with the sheet row's "Directionality" (the "Audio" word belongs to the record toggle;
    // one word naming two controls was the findability failure — 2026-07-31 UI review #3).
    AUDIO_SCENE,
    GRID,
    LEVEL,
    PUNCH_IN,
    TELECONVERTER,
    OPEN_GATE,
    FRAME_LINES,

    // 2026-07-31 additions (user: "some fn functions are not available"). Persistence stores enum
    // NAMES, so appending here never disturbs saved lists; normalizeFnSlots drops unknowns on
    // downgrade by construction.
    FLASH,
    // Full sheet name; the held-landscape tile shortens through fnOverlayVisualLabel like STAB.
    TIMER,
    ASPECT,
    AUDIO_INPUT;

    companion object {
        val PHOTO_DEFAULT = listOf(EXPOSURE_MODE, FOCUS, SHUTTER, ISO, WB, EV)
        val VIDEO_DEFAULT = listOf(EXPOSURE_MODE, FOCUS, SHUTTER, ISO, WB, TRANSFER, STABILIZATION, AUDIO_SCENE)
        val DEFAULT = PHOTO_DEFAULT
        val MY_MENU_DEFAULT = listOf(STABILIZATION, PEAKING, ZEBRA, DRIVE, METERING, TRANSFER)
    }
}

/** Assignable action for physical keys. Camera slide zoom remains fixed because it has direction. */
enum class HardwareKeyAction {
    SHUTTER,
    AF_ON,
    AEL,
    // Label unified on "Loupe" app-wide (cycle-6 D-04; matches the Fn chip and the LOUPE OSD tag).
    // The enum NAME stays PUNCH_IN — persistence stores names, and renaming would drop the setting.
    PUNCH_IN,
    ZOOM_IN,
    ZOOM_OUT,
    NONE,
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

/** Total boundary for persisted/UI/recorder gain input (1 = passthrough, 0..2 supported). */
internal fun normalizeAudioGain(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 2f) else 1f

/** One canonical bounded slot policy shared by persistence, restore, editors, and both Fn bars. */
internal fun normalizeFnSlots(
    slots: List<FnSlot>,
    fallback: List<FnSlot>,
    limit: Int = 8,
): List<FnSlot> {
    val safeLimit = limit.coerceAtLeast(1)
    return slots.distinct().take(safeLimit)
        .ifEmpty { fallback.distinct().take(safeLimit) }
}

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
enum class AudioScene(val effectType: Int) {
    STANDARD(1),
    SOUND_FOCUS(2),
    SOUND_STAGE(5),
}

/**
 * Preferred recording input. AUTO lets Android pick the route; the others are resolved against
 * currently connected input-capable AudioDeviceInfo entries when video recording starts.
 */
enum class AudioInputPreference {
    AUTO,
    BUILT_IN,
    WIRED,
    USB,
    BLUETOOTH,
}

// The teleconverter's magnification, its preset catalog, and the derived display/focal scales now
// live in Teleconverter.kt — they stopped being constants when the converter became selectable.
// `TELECONVERTER_MAGNIFICATION` survives there as the Explorer default; `TELE_DISPLAY_BASE` became
// the `teleDisplayBase(magnification)` function.

// TELE mode's DISPLAY zoom ceiling (converter-equivalent, main-relative). Deliberately NOT scaled
// by the converter: it caps TOTAL magnification, so the local-zoom headroom derived from it
// (`TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(m)`) widens as the converter weakens. The user-spec
// range is 13–60× on the kit optic, with magnetic snaps at 30× / 60×.
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
const val FINDER_SIDE_MARGIN = 0.03f
// 0.14 → 0.22 (2026-07-29): once the box moved to the RIGHT edge it came alongside the focal rail,
// whose last chip ran into it. The rail is a 48 dp row sitting just above the preview's bottom, so
// the inset has to clear the rail's full height plus its breathing room, not just the edge.
// REPLACED by FINDER_TOP_ANCHOR (2026-08-04): a bottom-relative inset cannot place the box against
// screen-fixed chrome, because the preview box's bottom edge moves with its aspect while the chrome
// does not. Kept only as finderRect's unused default so existing callers and tests still compile.
const val FINDER_BOTTOM_MARGIN = 0.10f

// How far below the preview's TOP edge the overview's top sits, as a fraction of the box WIDTH.
// Device-measured on a 1440-wide phone: the placement that clears the focal rail by ~90 px lands
// the box ~1200 px below the preview top in 4:3 and ~1218 px in 16:9 — the same distance to within
// 1.5% of the box, which is why this edge is the stable one to measure from.
const val FINDER_TOP_ANCHOR = 0.84f

/**
 * Fallback floor for the gap under the overview, as a fraction of the frame HEIGHT.
 *
 * Only used when the caller cannot supply the real number. The preview box extends BEHIND the
 * bottom chrome by a distance that varies with device and aspect — measured, 13 dp on a 411 dp
 * phone in 4:3 and 90 dp on a 941 dp tablet — so no scale-free fraction of that box can express a
 * fixed clearance from chrome the box does not know about. Tuning this against one shape always
 * broke the other, which is why [finderRect] now takes [bottomClearance] from the layout instead.
 */
const val FINDER_MIN_BOTTOM_CLEARANCE = 0.088f
// The punch-in loupe's texcoord crop: the magnified preview samples a (1-crop) span of the frame
// (0.6 → 2.5× magnification). Shared between the GL draw (gl/GlPipeline) and the tap-mapping
// composition in CameraEngine (P2.8/AGG4-11) so the two cannot drift.
const val PUNCH_IN_CROP = 0.6f

/**
 * The engine-RESOLVED half of the finder gate (everything except the zoom floor): user toggle,
 * TELE mounted, and — in PHOTO — the 4:3 still aspect. In VIDEO the still aspect is not consulted
 * at all: it is semantically unrelated to the recorded framing, and keying the overlay off it is
 * what used to make the PIP appear/vanish mid-clip. 16:9 STILLS stay excluded because the
 * AspectMask pillarboxes would dim and misframe the corner box.
 * ONE implementation for the engine (`pushTeleFinder`) and the Compose border — the same
 * hand-written condition used to live in three places and could silently drift.
 */
/** Zoom at or past which the finder is offered without a converter mounted. */
const val FINDER_MIN_ZOOM = 3f

/**
 * The engine-RESOLVED punch-in loupe: the user's persisted toggle, suppressed on the FRONT route.
 *
 * The loupe exists to check CRITICAL FOCUS at long focal lengths, which is why it crops to
 * `1 - PUNCH_IN_CROP` = 40% of the frame. On a ~21 mm-equivalent selfie lens that magnification
 * buys nothing and instead reads as a broken camera: the preview shows a fraction of the field
 * while the saved file is full-frame, so the finder and the result disagree with no visible cause
 * (user-reported twice — "front preview is very narrow FOV, the taken picture is proper"). The
 * earlier round diagnosed the same symptom as "the loupe, not a front-camera fault" and left it,
 * which is what let it come back.
 *
 * Suppressed, not CLEARED: the toggle keeps its value so returning to the rear route restores the
 * aid the operator asked for. One implementation, consumed by `pushPunchIn` and by the tap-focus
 * geometry, so a tap cannot compose through a crop the preview is not applying.
 */
fun punchInResolved(enabled: Boolean, frontFacing: Boolean): Boolean = enabled && !frontFacing

/**
 * Whether the rear PHOTO route must pin a STANDALONE lens instead of the logical multicamera.
 *
 * Photo normally runs on the logical camera because that is what makes 0.6–20× pinch seamless: the
 * HAL crosses lenses internally and the session never reopens. But RAW cannot come off it — the
 * logical camera ADVERTISES the RAW capability and then errors the whole device ~5 s after a still
 * that carries a RAW target (`CAMERA_ERROR(3)`, no image ever arrives; device-measured). Every
 * physical camera on this device really does support RAW, so the limitation is the logical route,
 * not the sensor.
 *
 * So when the operator wants DNG, the route switches to the standalone lens nearest the current
 * framing and RAW works at ANY focal length — not just through the teleconverter. The cost is that
 * zoom then steps between lenses with a reopen instead of crossing seamlessly, exactly as the video
 * route already does. That is the trade, and it is opt-in: turning DNG off restores seamless zoom.
 */
/**
 * The OPTICAL lens a standalone route actually lands on for a main-relative [unified] ratio, and
 * therefore the divisor that turns that ratio into a LENS-LOCAL one.
 *
 * A standalone session is pinned to one physical lens, so `zoomRatio` there is that lens's own
 * scale. Dividing by the preset the user tapped is only right when the preset IS a lens: on a
 * one-camera tablet the "3×" preset is a crop of the 26 mm main, so local 1.0 would silently throw
 * the 3× framing away (device-seen 2026-08-03: 3× read 27 mm instead of 81 mm). Dividing by the
 * nearest optical lens at or below the target is right in both cases — it is 3× itself on a phone
 * that has a 3× lens, and 1× on a phone that does not.
 *
 * [optical] empty (caps not read yet) answers 1×, i.e. keep the ratio as-is: the honest fallback,
 * since an unknown inventory cannot prove a crop is unnecessary.
 */
fun opticalBaseFor(unified: Float, optical: Set<LensChoice>): LensChoice =
    optical.filter { it.zoomPreset <= unified + 1e-3f }.maxByOrNull { it.zoomPreset }
        ?: optical.minByOrNull { it.zoomPreset }
        ?: LensChoice.MAIN

/**
 * The MAIN-RELATIVE ("unified") zoom for a route whose stored ratio may be LENS-LOCAL, and its
 * inverse. One pair, so the six places that used to open-code this conversion cannot drift again —
 * every one of them asked "is this video?", which is true of the video route but misses the DNG
 * door, and each wrong answer showed up as a different symptom (208 mm focal, 9.1× readout, the
 * rail highlighting 1× while the focal said 69 mm).
 *
 * The base is the OPTICAL lens the route lands on, not the band the user tapped: on a one-camera
 * device the "3×" band is a crop of the 1× lens, so multiplying by 3 would double-count it.
 * [unifiedZoomOf] and [localZoomOf] therefore round-trip on both device shapes.
 */
fun unifiedZoomOf(
    lens: LensChoice,
    zoomRatio: Float,
    standaloneRoute: Boolean,
    optical: Set<LensChoice>,
): Float = if (standaloneRoute) {
    opticalBaseFor(lens.zoomPreset, optical).zoomPreset * zoomRatio.coerceAtLeast(1f)
} else {
    zoomRatio
}

/** The LENS-LOCAL ratio a standalone route needs to sit at main-relative [unified]. */
fun localZoomOf(unified: Float, optical: Set<LensChoice>): Float =
    (unified / opticalBaseFor(unified, optical).zoomPreset).coerceAtLeast(1f)

fun standaloneRouteWanted(
    videoMode: Boolean,
    rawWanted: Boolean,
    // [DeviceProfile.rawRequiresStandalone]: only that HAL forces DNG off the seamless camera. A
    // spec device carries RAW on the logical route, so moving it there would cost the seamless
    // zoom for nothing. VIDEO's standalone pin is a separate, still-universal EIS decision.
    rawForcesStandalone: Boolean = true,
): Boolean = videoMode || (rawWanted && rawForcesStandalone)

/**
 * Whether to ask the HAL for a 10-bit HLG10 session.
 *
 * VIDEO only, and only when a non-SDR transfer is selected — i.e. exactly when the extra bits are
 * spent on something. HLG and the log curves are graded or displayed wide; SDR is not, and eight
 * bits through a display-referred 709 chain is what it has always been.
 *
 * The cost is real and is why this is not simply always-on: the 10-bit rung drops the JPEG and RAW
 * readers (HLG10 + full-res JPEG + RAW together CRASH this HAL — a crash, not a rejection, so the
 * fallback ladder cannot rescue it), which costs the in-REC snapshot while a 10-bit clip is
 * recording. Photo mode never pays that, because photo never asks.
 *
 * Correctness of what the extra bits carry is pinned away from any device by LogFromHlgSourceTest:
 * 18% grey must reach the S-Log3 anchor from an HLG source exactly as it does from an SDR one.
 */
fun tenBitSessionWanted(videoMode: Boolean, transfer: ColorTransfer): Boolean =
    videoMode && transfer != ColorTransfer.SDR

/**
 * The zoom the OPERATOR sees: always MAIN-RELATIVE, whichever route the session is on.
 *
 * `controls.zoomRatio` is the wire value and carries two scales — main-relative on the logical
 * seamless camera, LENS-LOCAL on any standalone one. Every predicate written against a
 * main-relative threshold has to convert first, and [teleFinderResolved]'s [FINDER_MIN_ZOOM] is
 * exactly such a threshold. Its three call sites passed the raw wire value, so on a standalone
 * route (video, or photo with DNG wanted) picking the 3x LENS left the wire at lens-local 1.0 and
 * the gate shut: the operator was at 3x and the loupe would not arm until they pinched further.
 * Owner-reported as "switch the lens and the loupe does not show up".
 *
 * Derived here rather than at each site for the same reason the rail conversion is: three copies of
 * a scale conversion is how one of them ends up reading the wrong scale.
 */
val CameraUiState.unifiedZoom: Float
    get() = if (activeCameraRoute.lensLocalZoom) {
        controls.zoomRatio
    } else unifiedZoomOf(
        lens = lens,
        zoomRatio = controls.zoomRatio,
        standaloneRoute = standaloneRouteWanted(
            videoMode = mode == CaptureMode.VIDEO,
            rawWanted = photoFormats.dngRaw,
            rawForcesStandalone = rawForcesStandalone,
        ),
        optical = lensInventory.optical,
    )

/**
 * The zoom the REAR route resumes at when leaving FRONT.
 *
 * Restores the framing the operator actually had, not the lens PRESET. Falling back to the preset
 * meant that once TELE had been used — which pins the lens choice to the 3× — every front trip
 * returned to 3× regardless of where the user was standing, so flipping to the selfie camera and
 * back silently zoomed them in (user-reported 2026-07-28). The pre-front snapshot exists on both
 * sides already; it was simply never consumed on the way back.
 *
 * VIDEO still returns to lens-local 1×: that route pins a standalone lens, so a ratio captured in
 * the photo route's unified main-relative scale does not mean the same thing there.
 *
 * [preFrontZoom] is NaN when nothing was captured (a recall or settings restore exited front
 * without going through the flip), and the preset is then the honest fallback.
 */
fun rearReturnZoom(videoMode: Boolean, preFrontZoom: Float, lensPreset: Float): Float = when {
    videoMode -> 1f
    !preFrontZoom.isNaN() && preFrontZoom > 0f -> preFrontZoom
    else -> lensPreset
}

fun teleFinderResolved(
    enabled: Boolean,
    teleconverter: Boolean,
    videoMode: Boolean,
    aspect: AspectRatio,
    zoomRatio: Float = 1f,
): Boolean = enabled && (teleconverter || zoomRatio >= FINDER_MIN_ZOOM) &&
    // VIDEO now qualifies too (user-asked 2026-07-29). It was excluded because the gate keyed off
    // the 4:3 STILL aspect, so a photo setting made the overlay appear and vanish mid-clip with no
    // visible cause. The fix is to stop consulting that setting in video — where the recorded
    // framing is the video size, not the photo aspect — rather than to keep the whole aid out of
    // the mode a long lens most needs it in.
    (videoMode || aspect == AspectRatio.W4_3)

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
    zoomRatio: Float = 1f,
): Boolean = teleFinderResolved(enabled, teleconverter, videoMode, aspect, zoomRatio) && punchIn

/**
 * The ONE hi-res-still admission predicate (same single-implementation discipline as
 * [teleFinderResolved]): the engine resolves it in one place and re-resolves at every optics door.
 * Photo-only (a 200MP reader has no business in a video session), 4:3-only (the hi-res save path
 * is a byte PASSTHROUGH — no bitmap decode at 200MP, so the 16:9 center crop cannot be applied),
 * standalone-only (the logical camera's gralloc rejects big blob allocations), and only when the
 * selected camera actually advertises a full-sensor size.
 */
internal fun hiResAdmitted(
    requested: Boolean,
    videoMode: Boolean,
    aspect: AspectRatio,
    standalone: Boolean,
    advertised: Boolean,
): Boolean = requested && !videoMode && aspect == AspectRatio.W4_3 && standalone && advertised

/** Finder-PIP box in the preview box's own units, measured from the bottom-left corner. */
data class FinderRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The one geometry rule for the finder PIP, shared by the GL scissor/viewport (pixels) and the
 * Compose border overlay (dp): a [fraction]-sized box of the FULL preview box, inset from the left
 * by [sideMargin] and from the bottom by [bottomMargin], both as fractions of the short edge. The
 * larger bottom clearance keeps the same-stream overview above the persistent Fn/lens rail. Both
 * consumers MUST derive their rect from here — the original Compose modifier chain (`padding` before
 * `fillMaxWidth`) sized the border from padding-reduced constraints and drew it ~6% smaller than the
 * GL content box.
 */
fun finderRect(
    boxWidth: Float,
    boxHeight: Float,
    fraction: Float = FINDER_FRACTION,
    sideMargin: Float = FINDER_SIDE_MARGIN,
    @Suppress("UNUSED_PARAMETER") bottomMargin: Float = FINDER_BOTTOM_MARGIN,
    topAnchor: Float = FINDER_TOP_ANCHOR,
    // How far the preview box extends BEHIND the bottom chrome, in the same units as the box, plus
    // whatever breathing room the caller wants. Negative or NaN means "not known" and falls back to
    // the fraction. This is the number that actually decides the placement; everything else here is
    // an approximation of it that could not survive a second device shape.
    bottomClearance: Float = Float.NaN,
): FinderRect {
    val shortEdge = minOf(boxWidth, boxHeight)
    val width = boxWidth * fraction
    return FinderRect(
        // RIGHT edge, not left: the left column carries the vertical exposure/zoom ruler, and the
        // overview sat under it (user-reported 2026-07-29 — "loupe is overlapping with zoom bar").
        // The right column is the only side of the image with no persistent control on it.
        x = boxWidth - width - shortEdge * sideMargin,
        // Measured DOWN FROM THE BOX TOP, not up from its bottom. The inset exists to clear the
        // bottom chrome (focal rail, mode carousel), which sits at a fixed offset from the SCREEN
        // bottom — and the box's own bottom is the one edge that does not track the screen. Device-
        // measured on a 1440-wide phone: the 4:3 preview runs y 510-2430, the 16:9 one 304-2864, so
        // the taller aspect grows in BOTH directions and its bottom is only ~434 px lower, not the
        // 640 px the aspect difference suggests. Anchoring to the bottom therefore either dropped
        // the overview onto the rail (0.22, the original report) or lifted it 300 px clear of it
        // (subtracting the full aspect difference, the second report). The box top, by contrast,
        // sits ~1200 px below the preview top in BOTH aspects, so that is the edge to measure from.
        // Still a pure function of the box, so the GL scissor and the Compose border agree.
        // ...but never closer to the bottom edge than [FINDER_MIN_BOTTOM_CLEARANCE] of the frame
        // height. The top anchor is a fraction of the box WIDTH, and a tablet is far wider in dp
        // than a phone while the focal rail stays 48 dp tall — so the same anchor that leaves 88 px
        // under the box on a 411 dp phone left EXACTLY 0 px on a 941 dp tablet in 4:3 (measured on a
        // Lenovo TB331FC: overview bottom 1621, rail top 1621). A scale-free fraction cannot express
        // a fixed-dp clearance, so this floor is what keeps the box off the rail on wide screens; it
        // is inert on the phone, where the anchor already sits below it.
        // Three lower bounds, largest wins. The measured clearance is the one that actually knows
        // where the chrome is, but it is not always the biggest: it reports only the overlap, so an
        // aspect whose preview stops ABOVE the chrome yields a small number that would sit the box
        // lower than the fraction floor already guarantees. Keeping the floor as a bound means
        // adding the measurement can never place the box lower than it was without it.
        y = maxOf(
            maxOf(
                boxHeight - boxWidth * topAnchor - boxHeight * fraction,
                boxHeight * FINDER_MIN_BOTTOM_CLEARANCE,
            ),
            if (bottomClearance.isFinite() && bottomClearance >= 0f) bottomClearance else 0f,
        ),
        width = width,
        height = boxHeight * fraction,
    )
}

/**
 * The sub-rect of the finder PIP that marks WHERE THE MAIN VIEW IS LOOKING — the iPhone-style
 * framing hint drawn inside the overview.
 *
 * The finder draws the full delivered frame (`crop = 0`, `zoomComp = 1`), while the main view draws
 * `(1 - crop) / zoomComp` of that same frame centred on the loupe point — so the hint is exactly
 * that fraction of the finder box, positioned at the loupe centre. [visibleFraction] is that
 * quotient, already computed by the caller from the values it passed to the main draw, so the hint
 * cannot drift from the framing it claims to describe.
 *
 * [rotationDegrees] is the renderer's own texcoord rotation. Both draws apply it identically, so a
 * texcoord point lands at the same relative place in both — but that place is ROTATED, and the
 * finder is only ever reachable in tele, where the afocal correction is 180°. A centred loupe is
 * therefore unaffected (180° maps 0.5 to 0.5) and only a tapped, off-centre loupe can expose a sign
 * error here; the device check for this feature is to tap off-centre and confirm the hint follows
 * the same corner the magnified view actually shows.
 *
 * The result is clamped INSIDE the finder box: at zoomComp < 1 (transient, mid-gesture zoom-out) the
 * main view can genuinely be looking wider than the delivered frame, and a hint spilling past its
 * own border would read as a drawing bug rather than as the honest "you are at the edge".
 */
fun loupeHintRect(
    finder: FinderRect,
    visibleFraction: Float,
    centerTexX: Float,
    centerTexY: Float,
    rotationDegrees: Int,
    // The overview PRETENDS to be this many times wider than the delivered frame. 1 = honest
    // single-stream geometry (the hint marks the loupe crop within the frame the box actually
    // draws). In TELE the operator reads the upright corner box as the PRE-CONVERTER world — the
    // naked-eye orientation reference the un-rotated draw already role-plays — so the hint must
    // mark the main view's field ON THAT SCALE: a 4.3× converter makes the magnified view 4.3×
    // narrower against the world than against the delivered frame (operator-specified 2026-07-31,
    // same class of deliberate declination as the overview's rotationOverrideDeg = 0). Both the
    // size AND the centre offset divide by it: a point in the delivered frame sits at
    // 0.5 + (p − 0.5)/scale of the pretend-wide field the box stands in for.
    fieldScale: Float = 1f,
): FinderRect {
    val scale = fieldScale.coerceAtLeast(1f)
    val f = (visibleFraction / scale).coerceIn(0.01f, 1f)
    val cx = (centerTexX.coerceIn(0f, 1f) - 0.5f) / scale
    val cy = (centerTexY.coerceIn(0f, 1f) - 0.5f) / scale
    // Rotate the centre offset about the frame centre by the same texcoord rotation the draws use.
    val (rx, ry) = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> -cy to cx
        180 -> -cx to -cy
        270 -> cy to -cx
        else -> cx to cy
    }
    val w = finder.width * f
    val h = finder.height * f
    // BOTH axes take the rotated offset with the SAME sign. The y term looked like it needed an
    // extra negation for FinderRect's bottom-left origin, and shipping it that way put the hint
    // BELOW centre for a tap ABOVE centre — device-bisected 2026-07-27 by tapping the upper-left
    // quadrant and measuring: x moved −39 px against −37.8 px predicted (correct), y moved +64.5 px
    // against −62 predicted (inverted magnitude-correct). The loupe centre arrives already in the
    // draw's own coordinate space, so the origin flip is one the caller has performed, not one this
    // function owes.
    val x = finder.x + (rx + 0.5f) * finder.width - w / 2f
    val y = finder.y + (ry + 0.5f) * finder.height - h / 2f
    return FinderRect(
        x = x.coerceIn(finder.x, finder.x + finder.width - w),
        y = y.coerceIn(finder.y, finder.y + finder.height - h),
        width = w,
        height = h,
    )
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
// Magnetic zoom marks, in TOTAL-magnification units like the HUD pill — NOT lens-local ratios. The
// local ratio each one lands on therefore depends on the mounted converter (mark ÷
// teleDisplayBase), which is why `normalizeZoomRequest` converts in both directions instead of
// comparing raw ratios. On a weak enough converter the 60× mark simply falls outside the lens's
// range and the ordinary bounds clamp swallows it; that is intended, not a missing case.
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
/**
 * Which [LensChoice] presets THIS device can actually deliver, and which of those are a real lens
 * rather than digital zoom.
 *
 * The rail used to render [LensChoice.entries] unconditionally — the PMA110 lens set hardcoded into
 * the UI. On a device that does not have those optics it offered framings it could never reach:
 * user-reported 2026-08-02 on a single-camera Android 16 tablet where 0.6x and 10x were both dead
 * chips (0.6x below the advertised zoom floor of 1.0, 10x above its 8.0 ceiling — tapping 0.6x left
 * the wire zoom at 1.0). Everything here is resolved by ENUMERATING Camera2 capabilities, never by
 * a model string, per the project's standing rule.
 */
data class LensInventory(
    val available: Set<LensChoice>,
    /** The subset backed by a physical lens; the rest are reachable only as digital zoom. */
    val optical: Set<LensChoice>,
    /**
     * MEASURED 35 mm-equivalent of the lens a teleconverter would actually clamp onto here (the one
     * closest to the 3x target). Zero when unreadable. Only consulted for [PhoneModel.OTHER]: a
     * NAMED kit keeps deriving from its own declared host phone, because moving glass to another
     * body does not regrind it — but an unknown phone has no declared host, and assuming 70 mm there
     * told a 26 mm-lens tablet that a generic 1.5x clip-on yields 105 mm instead of 39 mm.
     */
    val teleHostEquivMm: Float = 0f,
    /**
     * What each preset ACTUALLY delivers here, in 35 mm-equivalent mm: the matched lens's measured
     * focal for an optical preset, else the main lens scaled by the preset's ratio (a digital 3x on
     * a 26 mm lens really is ~78 mm). Zero when unreadable.
     *
     * Per PRESET, never per ROUTE — the distinction is the whole point. On the seamless photo route
     * every preset rides the SAME logical camera, so that camera's own equivalent (~23 mm on
     * PMA110) describes only the 1x framing; using it for the caption made 3x read "23 mm" instead
     * of "70 mm" (verification 2026-08-02, caught before release).
     */
    val presetEquivMm: Map<LensChoice, Float> = emptyMap(),
) {
    companion object {
        /**
         * Pre-enumeration default: everything, so the first frames render exactly as before the
         * inventory arrives (a sub-second window on cold start) instead of flashing an empty rail.
         */
        val ALL = LensInventory(LensChoice.entries.toSet(), LensChoice.entries.toSet())
    }
}

/**
 * A preset is OFFERED when either route can reach it:
 *  - OPTICAL: a back lens exists whose 35 mm-equivalent is within [LENS_MATCH_TOLERANCE] of the
 *    preset's target (a band, not equality — real "3x" periscopes land anywhere from ~65 to ~85 mm),
 *  - ZOOM: the photo-home route's advertised zoom range covers the preset's ratio.
 * [LensChoice.MAIN] additionally always survives while any back lens was enumerated: it is the
 * reference framing, and a rail without it would be meaningless.
 */
internal const val LENS_MATCH_TOLERANCE = 1.35f

internal fun lensInventoryOf(
    backLensEquivMm: List<Float>,
    zoomRange: Pair<Float, Float>?,
): LensInventory {
    val usableLenses = backLensEquivMm.filter { it > 0f }
    if (usableLenses.isEmpty() && zoomRange == null) return LensInventory.ALL
    // The lens a converter clamps onto is the one the route resolver would pick: closest to the 3x
    // target (CameraSelector2 uses the same "closest measured equivalent" rule).
    val teleHost = usableLenses.minByOrNull { kotlin.math.abs(it - LensChoice.TELE3X.targetEquivMm) } ?: 0f
    // MUTUAL nearest, not "any lens within the band": the bands overlap (ultrawide 10.4-18.9,
    // main 17.0-31.1), so a single ~18 mm lens would otherwise claim BOTH presets and the rail
    // would draw a "0.6x lens" chip for what is the main camera — the same falsehood this
    // enumeration exists to remove (verification 2026-08-02).
    val matched = HashMap<LensChoice, Float>()
    for (equiv in usableLenses) {
        val nearest = LensChoice.entries.minByOrNull { choice ->
            kotlin.math.abs(kotlin.math.ln(equiv / choice.targetEquivMm))
        } ?: continue
        val ratio = if (equiv >= nearest.targetEquivMm) {
            equiv / nearest.targetEquivMm
        } else {
            nearest.targetEquivMm / equiv
        }
        if (ratio > LENS_MATCH_TOLERANCE) continue
        // Two lenses nearest the same preset: keep the closer one.
        val incumbent = matched[nearest]
        if (incumbent == null ||
            kotlin.math.abs(equiv - nearest.targetEquivMm) < kotlin.math.abs(incumbent - nearest.targetEquivMm)
        ) {
            matched[nearest] = equiv
        }
    }
    val optical = matched.keys.toMutableSet()
    if (usableLenses.isNotEmpty()) optical += LensChoice.MAIN
    val available = LensChoice.entries.filter { choice ->
        choice in optical ||
            (zoomRange != null && choice.zoomPreset >= zoomRange.first && choice.zoomPreset <= zoomRange.second)
    }.toSet()
    // Main is the scale reference for every preset reachable only by zoom.
    val mainEquiv = matched[LensChoice.MAIN]
        ?: usableLenses.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / LensChoice.MAIN.targetEquivMm)) }
        ?: 0f
    val presetEquiv = available.associateWith { choice ->
        matched[choice] ?: if (mainEquiv > 0f) mainEquiv * choice.zoomPreset else 0f
    }
    return LensInventory(
        available = available,
        optical = optical,
        teleHostEquivMm = teleHost,
        presetEquivMm = presetEquiv,
    )
}

enum class LensChoice(val targetEquivMm: Float, val zoomPreset: Float) {
    ULTRAWIDE(14f, 0.6f),
    MAIN(23f, 1f),
    TELE3X(70f, 3f),
    TELE10X(230f, 10f);

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
 * (see [me.hletrd.telecampro.video.EncoderCaps]).
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
    val encoderRate: Double,
    val fps: Int,
    val dropFrame: Boolean,
    val highSpeed: Boolean = false,
) {
    FPS_23_976(24000.0 / 1001.0, 24, true),
    FPS_24(24.0, 24, false),
    FPS_25(25.0, 25, false),
    FPS_29_97(30000.0 / 1001.0, 30, true),
    FPS_30(30.0, 30, false),
    FPS_50(50.0, 50, false),
    FPS_59_94(60000.0 / 1001.0, 60, true),
    FPS_60(60.0, 60, false),
    // Dormant in the shipping picker; see availableFor and CameraViewModel's restore guard.
    FPS_120(120.0, 120, false, highSpeed = true);

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

/**
 * Drops HEIF when this device cannot encode it, promoting JPEG so the shutter still writes a file.
 * HeifWriter needs the platform HEVC encoder, which is not CDD-mandatory at API 33; without this a
 * fresh install on such a handset produced NO output at all from its own default (2026-08-02).
 */
/**
 * The gamma choices this device can encode HONESTLY. HLG and all three log profiles are tagged
 * BT.2020 and carry a 10-bit HEVC profile; on an 8-bit-only encoder they yield a Main/yuv420p stream
 * wearing those tags, so they are withheld rather than offered as a claim the file cannot back.
 * SDR always survives — it is the 8-bit BT.709 case by construction.
 */
fun availableTransfers(
    codec: VideoCodec,
    tenBitEncodeAvailable: Boolean,
): List<ColorTransfer> = if (codec == VideoCodec.HEVC) {
    if (tenBitEncodeAvailable) ColorTransfer.entries.toList() else listOf(ColorTransfer.SDR)
} else {
    listOf(ColorTransfer.SDR)
}

/** One codec/transfer invariant shared by restore, recall, live selection, and REC admission. */
fun ColorTransfer.normalizedForEncoder(
    codec: VideoCodec,
    tenBitEncodeAvailable: Boolean,
): ColorTransfer = if (this in availableTransfers(codec, tenBitEncodeAvailable)) {
    this
} else {
    ColorTransfer.SDR
}

fun PhotoFormats.normalizedForEncoder(heifEncodeAvailable: Boolean): PhotoFormats = when {
    heifEncodeAvailable || !heif -> this
    else -> copy(heif = false, jpeg = true)
}


/** Actual still readers present in one accepted Camera2 session. */
data class PhotoSessionOutputs(
    val processed: Boolean = false,
    val raw: Boolean = false,
    // True only when the ACCEPTED session's processed reader is the full-sensor hi-res one — never
    // the requested intent (the fallback ladder drops hi-res first, so intent and session truth
    // routinely diverge). Downstream honesty keys off this: format collapse to passthrough JPEG,
    // the OSD HR tag, and the still request's SENSOR_PIXEL_MODE.
    val hiRes: Boolean = false,
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
        // Equality is idempotent, matching CameraReadyPublicationGate: sequences are minted by
        // incrementAndGet (first is 1 against the 0 seed), so an EQUAL sequence can only be a
        // re-delivery of an already-applied publication and must not re-run [apply].
        if (publication.sequence <= latestSequence.get()) return false
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
    // Hi-res session: the ONLY still lane is the passthrough HAL JPEG. HEIF would decode the
    // ~200MP JPEG into an ~800 MB ARGB bitmap for its pixel-rotate re-encode (guaranteed OOM),
    // and RAW was force-dropped by the session plan (a 200MP blob + RAW in one session is the
    // over-demanding combo this HAL punishes), so both collapse regardless of the request.
    if (outputs.hiRes) return PhotoFormats(heif = false, jpeg = outputs.processed, dngRaw = false)
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
    val audioRoute: AudioRouteStatus = AudioRouteStatus(
        preference = AudioInputPreference.AUTO,
        availability = AudioRouteAvailability.AUTO,
    ),
    val audioGain: Float = 1f, // 0..2 software gain applied to recorded PCM
    /**
     * Live input level per CHANNEL (0..1 RMS), in interleave order; empty while the meter is off.
     * A list rather than one number because a stereo or multi-capsule external mic can lose ONE
     * channel, and a single averaged bar hides exactly that (2026-08-02).
     */
    val audioLevels: List<Float> = emptyList(),
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
    // Focus-confidence tag: which proof (if any) currently holds, after the ~700 ms hold that keeps
    // AF hunting from flickering it (focus/MacroProximity.kt). Rendered as ONE compact amber OSD
    // tag whose TEXT follows the proof — AF_LIMIT may say TOO CLOSE, FRAME_DETAIL may only say
    // SOFT. [macroCloserLensLabel] names a rear lens that focuses closer (resolved per route from
    // the per-lens metadata cache, null when none qualifies); only AF_LIMIT may show it.
    val focusConfidence: FocusConfidenceSource? = null,
    val macroCloserLens: LensChoice? = null,
    // Live camera health: false while opening/reconfiguring/recovering (and after recovery gives
    // up). The shutter dims on it so a dead session never hides behind a ready-looking button.
    val cameraReady: Boolean = false,
    // The camera-switch dip (ui/SwitchCoverPolicy.kt): true only while a REOPEN — one that actually
    // changed the session generation — owes the viewfinder a frame, and only after that reopen has
    // outlived the grace delay. Deliberately NOT derived from [cameraReady]: every optics door
    // clears that bit, including the same-route fast path behind every photo lens preset.
    val switchCoverVisible: Boolean = false,
    // True while the full-screen media-review overlay is up (also used to freeze its media URI).
    val reviewOpen: Boolean = false,
    // One Activity-facing gate for every full-screen modal (settings, Fn, review). Hardware shutter,
    // zoom and half-press input must not mutate the hidden viewfinder behind any of them.
    val cameraInputBlocked: Boolean = false,
    // Selected rear lens. Default 1× main for a normal app launch. Lens picks are zoom presets
    // and do NOT bundle teleconverter mode (see [LensChoice]); the TELE toggle owns the converter.
    val lens: LensChoice = LensChoice.MAIN,
    // FRONT hides the rear-only chrome (TELE chip, focal rail) and mirrors the PREVIEW only; saved
    // files stay unmirrored. Not persisted — fresh launch is always BACK (see [CameraFacing]).
    // [lens] keeps the last rear band across a front trip so flipping back restores that preset.
    val facing: CameraFacing = CameraFacing.BACK,
    // Exact accepted/startup route. Facing remains for the established front/rear UI and rollback
    // contracts, but EXTERNAL must never be reconstructed from `facing == BACK && !routes.back`.
    val activeCameraRoute: CameraRoute = CameraRoute.BACK,
    // Device-static route truth, enumerated before the first Camera2 open. UNKNOWN deliberately
    // preserves the historical PMA110 chrome for the few milliseconds before publication.
    val cameraRoutes: CameraRouteInventory = CameraRouteInventory.UNKNOWN,
    // Teleconverter mode: manual (not auto-detected). ON = afocal 180° flip; locked to the 3× lens.
    val teleconverterMode: Boolean = false,
    // The converter setting is a PAIR (see Teleconverter.kt): the phone decides WHICH kits clamp on,
    // then the converter decides the magnification. Only the phone is ever resolved automatically —
    // Build.MODEL is readable, passive glass is not — and even that only picks a starting selection.
    val phoneModel: PhoneModel = DEFAULT_PHONE_MODEL,
    // True only when Build.MODEL actually matched a known phone THIS boot. Deliberately not
    // persisted and deliberately separate from [phoneModel]: the caption has to distinguish "we
    // recognised your phone" from "this is merely the default", and a default is not a detection.
    val phoneModelDetected: Boolean = false,
    // WHICH converter is clamped on, and — for [TeleconverterProfile.CUSTOM] — its magnification.
    // Manual: passive glass cannot announce itself; it never gates a capability or a request.
    val teleconverterProfile: TeleconverterProfile = DEFAULT_TELECONVERTER_PROFILE,
    val teleconverterCustomMagnification: Float = TELECONVERTER_MAGNIFICATION,
    // Stabilization. Default ENHANCED = HAL OIS+EIS ("super steady"): at 300 mm it reduces the
    // per-frame motion blur (see [VideoStabMode]).
    val videoStabMode: VideoStabMode = VideoStabMode.ENHANCED,
    // Video
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.ULTRA,
    val videoResolution: Size = Size(3840, 2160),
    /** Actual encoder raster while REC owns a configured component; null outside recording. */
    val activeEncoderResolution: Size? = null,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT,
    // Open Gate: record the full 4:3 sensor readout instead of a 16:9 crop. Switches the resolution
    // selector to the camera's 4:3 sizes and encodes at that aspect.
    val openGate: Boolean = false,
    // Drive
    val timer: ShutterTimer = ShutterTimer.OFF,
    val driveMode: DriveMode = DriveMode.SINGLE,
    val intervalSec: Int = 5,
    // A timelapse RUN is live (between the starting shutter press and stop/mode-exit) — distinct
    // from driveMode == TIMELAPSE, which is only the SELECTION. Drives the unattended screen dim.
    val timelapseRunning: Boolean = false,
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
    // The user's loupe TOGGLE. What the preview actually applies is [punchInActive] — the settings
    // switch shows this raw value so it does not appear to flip itself on a camera change.
    val punchIn: Boolean = false,
    // TELE finder PIP Assist toggle (default OFF; see FINDER_* above for the honest contract).
    val teleFinder: Boolean = false,
    // Hi-res still INTENT (the user toggle). Accepted truth is photoSessionOutputs.hiRes only —
    // the session plan drops hi-res first on configure failure, and [hiResAdmitted] gates it to
    // photo + 4:3 + standalone + advertised, so intent and session truth routinely diverge.
    val hiResStill: Boolean = false,
    // Sony-style customization: Fn row, My Menu, recent changed settings and MR banks.
    val photoFnSlots: List<FnSlot> = FnSlot.PHOTO_DEFAULT,
    val videoFnSlots: List<FnSlot> = FnSlot.VIDEO_DEFAULT,
    val myMenuSlots: List<FnSlot> = FnSlot.MY_MENU_DEFAULT,
    val recentSettingSlots: List<FnSlot> = emptyList(),
    val activeMemorySlot: MemorySlot? = null,
    val savedMemorySlots: Set<MemorySlot> = emptySet(),
    val memorySlotPresentations: Map<MemorySlot, MemoryPresetPresentation> = emptyMap(),
    // Hardware controls. The OPPO half-press key defaults to AF-ON; volume/camera full press defaults
    // to shutter/REC. [halfPressActive] only drives the viewfinder feedback ring/chip.
    val volumeKeyAction: HardwareKeyAction = HardwareKeyAction.SHUTTER,
    val halfPressAction: HardwareKeyAction = HardwareKeyAction.AF_ON,

    /**
     * The OPPO quick/action button. The physical press (KEYCODE_ACTION_BUTTON_CLICK, scan 735) is
     * intercepted by the system's StrategyActionButtonKeyLaunchApp, which injects keycode 781 to
     * the focused app (device-measured 2026-07-31). A camera app's natural default is the shutter.
     */
    val quickButtonAction: HardwareKeyAction = HardwareKeyAction.SHUTTER,
    val halfPressActive: Boolean = false,
    // Gamma Display Assist (Sony): while shooting a log profile, the MONITOR shows the normal
    // 709-ish image and only the FILE stays log. Off = judge the flat log directly.
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
    // Device-static, enumerated once: which lens presets this hardware can actually deliver.
    val lensInventory: LensInventory = LensInventory.ALL,
    // Device-static codec truth starts conservative until the one off-main inventory completes.
    val encoderInventoryLoaded: Boolean = false,
    val availableVideoCodecs: List<VideoCodec> = emptyList(),
    // Whether HEIF stills can be encoded here at all (HeifWriter needs HEVC).
    val heifAvailable: Boolean = false,
    /** False when the device's HEVC encoder has no Main10 profile; withholds HLG/log gammas. */
    val tenBitEncodeAvailable: Boolean = false,
    /**
     * [DeviceProfile.rawRequiresStandalone], mirrored so the UI can answer the SAME route question
     * the engine answers. `zoomRatio` is main-relative on the logical camera and lens-local on a
     * standalone one, and wanting DNG is itself a standalone door — so the UI cannot decide the
     * zoom scale from the capture mode alone.
     */
    val rawForcesStandalone: Boolean = true,
    /**
     * The platform refuses to open the camera for THIS app although the runtime permission reads
     * granted (appops/policy block). The UI shows the permission gate instead of a black viewfinder
     * behind interactive-looking chrome.
     */
    val cameraPolicyBlocked: Boolean = false,
    val cameraOverrideId: String? = null,
    val status: CameraStatus? = null,
    // The newest saved capture owner (HEIF/JPEG/video, or RAW when no displayable sibling exists).
    val lastMediaUri: android.net.Uri? = null,
    // Canonical live/restored families can delete every known sibling; legacy filenames cannot.
    val lastMediaDeleteScope: MediaDeleteScope = MediaDeleteScope.FILE_ONLY,
    /** False only for a genuine fail-closed still-output ownership condition. */
    val stillCaptureAdmissionAvailable: Boolean = true,
    val histogramData: HistogramData? = null,
    val waveformData: WaveformData? = null,
) {
    val activeFnSlots: List<FnSlot>
        get() = if (mode == CaptureMode.VIDEO) videoFnSlots else photoFnSlots

    /** Operator-facing file raster: accepted fallback during REC, requested/session size otherwise. */
    val encodedVideoResolution: Size
        get() = activeEncoderResolution ?: videoResolution

    /**
     * The converter magnification in force. Every focal, zoom-scale, and HAL-hint consumer reads
     * this instead of a constant, so the profile and its custom value can never drift apart.
     */
    val teleconverterMagnification: Float
        get() = effectiveMagnification(teleconverterProfile, teleconverterCustomMagnification)

    /**
     * The effective 35 mm-equivalent focal through the converter, e.g. 300 mm on the kit optic.
     * Host focal = the DECLARED phone's tele (70 mm OPPO / 85 mm vivo kits; review 2026-08-01),
     * except on [PhoneModel.OTHER], which declares no host: there the MEASURED lens this device
     * would actually use is the only honest base ([LensInventory.teleHostEquivMm]).
     */
    val teleconverterFocalMm: Float
        get() = effectiveFocalMm(teleconverterMagnification, teleconverterHostEquivMm)

    /** The host-lens focal the converter multiplies — declared for a known phone, measured for OTHER. */
    val teleconverterHostEquivMm: Float
        get() = if (phoneModel == PhoneModel.OTHER && lensInventory.teleHostEquivMm > 0f) {
            lensInventory.teleHostEquivMm
        } else {
            phoneModel.teleEquivMm
        }
    /**
     * The loupe the preview ACTUALLY applies — [punchIn] suppressed on the front route.
     *
     * Every consumer that asks "is the view magnified" reads this: the OSD tag, the Loupe Overview
     * gate, and the finder geometry. Reading the raw toggle instead let the finder advertise LOUPE
     * over an unmagnified selfie preview, and would let the overview border draw for a crop GL was
     * not applying. Same derivation as the engine's `pushPunchIn`, through one shared predicate.
     */
    val punchInActive: Boolean
        get() = punchInResolved(punchIn, facing == CameraFacing.FRONT)
    val stillCaptureReady: Boolean
        get() = cameraReady && photoSessionOutputs.hasStillTarget && stillCaptureAdmissionAvailable
    val primaryShutterHealthy: Boolean
        get() = cameraReady && (
            mode == CaptureMode.VIDEO ||
                (photoSessionOutputs.hasStillTarget && stillCaptureAdmissionAvailable)
        )
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

/**
 * What the frame-detail metric (gl/FocusDetail.kt) could establish about ONE analysis frame.
 *
 * - [UNJUDGEABLE]: not enough tiles carried coarse structure on both axes to judge anything —
 *   blank wall, clear sky, smooth gradient, crushed/blown exposure, or a 1-D-only pattern.
 * - [RESOLVED]: fine-scale energy is present relative to coarse. That is EITHER real detail OR
 *   grain; the metric does not separate them, and does not need to — both mean "do not claim the
 *   frame resolves nothing".
 * - [SOFT]: coarse structure is present across the frame and essentially no tile resolves anything
 *   fine. The ONLY verdict that may arm the OSD tag.
 */
enum class FrameDetail { UNJUDGEABLE, RESOLVED, SOFT }

/**
 * One frame's detail verdict plus the counters that make a device bring-up pass diagnosable from a
 * single log line — which of the coarse floor, the coverage rule, or the ratio refused. Deliberate:
 * ColorOS drops app logs over a 300-row-per-process quota, so the instrument has to be one line on
 * verdict CHANGE, not a per-tick dump.
 *
 * @Immutable: constructed once by the analysis executor before publication.
 */
@Immutable
data class FocusDetailData(
    val verdict: FrameDetail,
    val totalTiles: Int,
    val judgeableTiles: Int,
    val softTiles: Int,
    /** Highest fine/coarse ratio any judgeable tile reached — the frame's best evidence of detail. */
    val bestRatio: Float,
) {
    val sharpTiles: Int get() = judgeableTiles - softTiles

    companion object {
        /** Nothing could be judged (degenerate buffer, or no tile passed the gates). */
        val UNJUDGED = FocusDetailData(FrameDetail.UNJUDGEABLE, 0, 0, 0, 0f)
    }
}

/** Capability/session truth for the viewfinder's accessibility-only focus commands. */
internal data class ViewfinderFocusActionAvailability(
    val focusAtCenter: Boolean,
    val resetFocusPoint: Boolean,
)

/**
 * Mirrors the Engine's region-AUTO-AF admission using state that crossed the accepted Ready seam.
 * Reset is independent: a held point remains worth clearing if the camera becomes Not-Ready.
 */
internal fun viewfinderFocusActionAvailability(
    cameraReady: Boolean,
    maxAfRegions: Int,
    focusMode: FocusMode,
    afModes: IntArray,
    tapFocusHeld: Boolean,
): ViewfinderFocusActionAvailability = ViewfinderFocusActionAvailability(
    focusAtCenter = cameraReady && touchAfMayTrigger(
        touchAfActive = true,
        maxAfRegions = maxAfRegions,
        focusMode = focusMode,
        afModes = afModes,
    ),
    resetFocusPoint = tapFocusHeld,
)

/**
 * Whether scene motion agrees with the rotation the gyro measured.
 *
 * - [MATCHES]: the frame moves the way the phone turned — the current rotation setting is right.
 * - [INVERTED]: it moves the opposite way, so the frame is 180 degrees out relative to the setting.
 *   An afocal converter with TELE off, or TELE on with no converter — the metric cannot tell which,
 *   and does not need to: the remedy is the same toggle either way.
 * - [UNJUDGEABLE]: not enough rotation, not enough directional texture, or no supermajority. The
 *   overwhelmingly common state, and the only safe default.
 *
 * See `gl/MotionInversion.kt` for what this can and cannot prove. It proves the IMAGE is inverted,
 * never that a teleconverter is attached — no user-facing string may name the accessory.
 */
enum class MotionAgreement { UNJUDGEABLE, MATCHES, INVERTED }

/**
 * One frame's motion-inversion verdict plus the block tallies, so a device bring-up can tell which
 * gate refused from a single log line (same ColorOS 300-row-quota constraint as [FocusDetailData]).
 *
 * @Immutable: constructed once by the analysis executor before publication.
 */
@Immutable
data class MotionInversionData(
    val verdict: MotionAgreement,
    val totalBlocks: Int,
    /** Blocks that carried enough directional texture AND a decisive SAD profile to vote. */
    val votingBlocks: Int,
    val agreeVotes: Int,
    val opposeVotes: Int,
    /**
     * Magnitude of the gyro rotation this frame pair was judged against, in milliradians.
     *
     * DIAGNOSTIC ONLY — no verdict reads it; the metric uses the DIRECTION and discards the length
     * (see gl/MotionInversion.kt on why being scale-free is the point). It is carried because the
     * counters alone cannot distinguish the two ways a judged frame can be meaningless: a large
     * rotation that the image genuinely followed, versus a barely-over-threshold rotation whose
     * image motion actually came from TRANSLATION or a moving subject. Those look identical in
     * agree/oppose and produce opposite conclusions about whether the answer can be trusted.
     */
    val predictedMrad: Float = 0f,
    /**
     * SIGNED predicted scene-motion direction in analysis-frame axes (x right, y down), milliradians.
     *
     * DIAGNOSTIC ONLY, and the one that makes a sloppy gesture usable. The magnitude alone cannot
     * answer the question a device bisection actually turns on: does the verdict flip WITH the
     * direction of rotation? It must not — a sign comparison is direction-invariant, because
     * reversing the pan reverses the gyro reading and the image motion together. So verdict
     * correlated with the sign of [predictedX] is the signature of a pairing error (rotation from
     * one interval judged against image motion from another), while a verdict independent of it
     * points at the optics instead.
     *
     * With this, ANY back-and-forth sweep is a valid experiment and the operator does not have to
     * produce a perfectly steady one-direction pan.
     */
    val predictedX: Float = 0f,
    val predictedY: Float = 0f,
) {
    companion object {
        /** Degenerate buffer, too little predicted rotation, or no block cleared the gates. */
        val UNJUDGED = MotionInversionData(MotionAgreement.UNJUDGEABLE, 0, 0, 0, 0, 0f)
    }
}

/**
 * Which proof raised the focus-confidence OSD tag. They are NOT interchangeable — see
 * focus/MacroProximity.kt for the wording each one licenses.
 *
 * - [AF_LIMIT]: AF declined a verdict while the lens sat racked against its close limit. That is a
 *   real distance proof, so it may say TOO CLOSE and may name a closer-focusing lens.
 * - [FRAME_DETAIL]: the app's own pixels resolved no fine detail. Says only SOFT: a single frame
 *   cannot separate defocus from haze, a fogged converter, or isotropic shake.
 */
enum class FocusConfidenceSource { AF_LIMIT, FRAME_DETAIL }
