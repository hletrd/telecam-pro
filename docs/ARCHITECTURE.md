# TeleCam Pro — Architecture

**Table of Contents**
1. [Overview](#overview)
2. [Module Map](#module-map)
3. [Data Flow](#data-flow)
4. [Threading Model](#threading-model)
5. [180° Flip + Rotation Pipeline](#180-flip--rotation-pipeline)
6. [Camera Selection & HAL Workarounds](#camera-selection--hal-workarounds)
7. [Stabilization and Orientation](#stabilization-and-orientation)
8. [Color & Video Pipeline](#color--video-pipeline)
9. [Capture & Storage](#capture--storage)
10. [Pro Controls Surface](#pro-controls-surface)
11. [Build & Toolchain](#build--toolchain)

---

## Overview

A professional single-device camera app for the **OPPO Find X9 Ultra** (Android 16 / API 36) that uses Camera2 to control the rear 3× periscope telephoto lens through a **Hasselblad "Earth Explorer" afocal 300 mm teleconverter** (≈4.286× magnification: 300 mm ÷ 70 mm). The app captures high-quality stills (HEIF + RAW/DNG) and HEVC video with HLG, O-Log2, or SDR profiles. For HAL stability, the shipping Camera2 and EGL input path is SDR/8-bit; HLG/O-Log2 uses an HEVC Main10 container profile but is not an end-to-end 10-bit source pipeline.

The UI/UX reference is **Sony Alpha / Sony Xperia Pro camera operation**. Use Fn access, My Menu, MR
banks, PASM-style exposure, compact OSD, peaking, zebra, histogram, waveform, and review zoom. Keep
the viewfinder quiet: no tutorial banners, warning chips, marketing cards, or helper overlays unless
the user asks. Important states belong in the OSD, Fn, or menu rows. See [`UX_POLICY.md`](UX_POLICY.md).

Two critical consequences of the afocal converter drive the entire design:
- **Image rotation**: The afocal telescope delivers light rotated 180° (no erecting prism). Both the live preview and saved results must be corrected. Vertical flip + horizontal flip = 180° rotation (parity-preserving, not a mirror).
- **Near-infinity focus**: Exit light is approximately collimated, so the phone lens focuses near infinity. Manual focus with a nonlinear slider is essential for fine-tuning that critical zone.

---

## Module Map

| Package / File | Single Responsibility |
|---|---|
| **camera/** | |
| `CameraEngine.kt` | Facade orchestrating Camera2, GL, capture encoders, video recorder, sensors, and storage. Unidirectional data flow with explicit cross-thread state publication. |
| `CameraController.kt` | Camera2 session lifecycle, request building, fallback ladder for stream configurations. Callback-driven, runs on a camera HandlerThread. |
| `CameraSelector2.kt` | Detects the telephoto physical lens: finds the camera with focal length closest to 70 mm, prefers standalone ID over physical sub-camera routing. |
| `CameraState.kt` | Enums (CaptureMode, ColorTransfer, FocusMode, DriveMode, etc.) and data classes (CameraUiState, ManualControls, CameraCaps) — the shared language between UI and engine. |
| `CaptureCapabilities.kt` | Queries Camera2 characteristics for manual-sensor, RAW, 10-bit HDR, focus range, metering regions — gate-keeping capabilities. |
| `ManualControls.kt` | Immutable snapshot of all pro capture parameters (focus, ISO, shutter, white balance, metering, processing). The ViewModel updates a copy; the Engine applies it to the repeating request. |
| `RotationMath.kt` | Pure, unit-tested functions for preview/capture/EXIF rotation math and the video muxer orientation hint (extracted from CameraEngine). |
| `AutoExposure.kt` | Pure, unit-tested app-side AE math: SHUTTER/ISO-priority drive functions and the photo-P program line (`driveProgram`), metered off the GL luma histogram. |
| `OcsProbe.kt` | Debug-source-set-only OPPO CameraUnit/OCS availability probe (release builds compile a no-op stub and do not link the OEM SDK). |
| `VendorTagInspector.kt` | Debug-only Camera2 capability logger for device-specific request/session keys. |
| **gl/** | |
| `GlPipeline.kt` | Owns the GL render thread. Receives camera SurfaceTexture, renders 180°-flipped quads to preview Surface and video encoder Surface. Owns EGL context, texture, sampling buffers. Drives histogram/waveform analysis on a background executor. |
| `FlipRenderer.kt` | Low-level OpenGL ES fullscreen quad renderer with texture-coordinate rotation (inverse of image rotation) to flip the 180° afocal image. Applies OETF (HLG / Log) in the fragment shader. Handles focus peaking (edge detection) and zebra (exposure clipping). |
| `EglCore.kt` | EGL/GLES setup: context creation and surface binding. Supports a 10-bit config, while v1 deliberately starts the stable 8-bit config. |
| `Shaders.kt` | Fragment/vertex shader source code. OETF (HLG, Log) application; peaking/zebra compositing; punch-in zoom. |
| **stab/** | |
| `GyroEis.kt` | Sensor helper for gravity-derived device orientation and the horizon overlay. It retains residual-shake math, but the shipping GL path disables app-side EIS in favor of HAL OIS+EIS. |
| **capture/** | |
| `HeifCapture.kt` | Encodes HEIF from a Bitmap after crop and `captureRotationDegrees()` pixel rotation. Writes via the ioExecutor off the camera thread. |
| `DngCapture.kt` | Writes DNG (RAW sensor frame) using DngCreator. Sets EXIF orientation tag (cannot pixel-rotate Bayer CFA). Synchronous in the photo callback while the raw Image is live. |
| **video/** | |
| `VideoRecorder.kt` | MediaCodec HEVC/AVC encoder + AAC audio encoder + MediaMuxer. Drains video/audio to MP4. Video input comes from GL (already 180°-flipped); audio captured from microphone on a separate thread. Software PCM gain. |
| `AudioInputInspector.kt` | Resolves the preferred recording input (built-in / wired / USB / BT) against connected AudioDeviceInfo entries; provides the route labels shown in the UI. |
| `ColorProfiles.kt` | Builds MediaFormat specs for HEVC Main10 (Rec.2020 + HLG/Log) and AVC 8-bit SDR. Tags dynamic range, color space, transfer function. |
| `EncoderCaps.kt` | Scans MediaCodecList and exposes the hardware AVC/HEVC encoders that are stable with MediaMuxer. |
| **storage/** | |
| `MediaStoreWriter.kt` | Scoped-storage wrapper: creates pending DCIM/X9Tele entries (IS_PENDING), publishes on success, deletes on failure. Opened files backed by ParcelFileDescriptor. |
| `SettingsStore.kt` | SharedPreferences persistence of ManualControls + ExtraSettings across launches, gated by a "Remember Settings" toggle (default ON); enums stored by name, defensive load. Lens and TELE restoration have separate default-on preserve toggles. |
| **focus/** | |
| `FocusMapping.kt` | Maps UI slider (0..1) to LENS_FOCUS_DISTANCE (diopters). Nonlinear to enhance resolution near infinity (√ curve and offset). Bidirectional. |
| **ui/** | |
| `CameraScreen.kt` | Compose root layout: preview TextureView, shutter button, mode toggle, gallery thumbnail, fixed settings panel, and capture overlays. Stateless, reads CameraUiState. |
| `CameraViewModel.kt` | StateFlow<CameraUiState> owner. Turns CameraActions (UI) into CameraEngine calls. Applies gesture-driven control changes with a trailing throttle. Ticks the recording timer and level overlay roll. UI-thread only. |
| `CameraActions.kt` | Callback interface for stateless UI commands such as focus, exposure, tap AF, lens, recording, persistence, and review actions. |
| **ui/controls/** | |
| `ManualDials.kt` | Horizontal scrolling dials for quick access to focus, shutter, ISO, white balance, EV, and zoom — the "Fn" layer. Mapped to CameraActions. |
| `ProSheet.kt` | Fixed Sony-style settings panel with a 9-tab left rail: My, Shoot, Exposure, Focus, Lens, Video, Image, Assist, and Setup. Each tab hosts controls feeding CameraActions. |
| `ProControls.kt` | Reusable Compose controls including rulers, segmented choices, toggles, sliders, and value rows. All are two-way bound to CameraUiState. |
| **ui/overlays/** | |
| `Overlays.kt` | Compose overlays: reticle (tap-to-focus), histogram/waveform, grid, spirit level, peaking, zebra, punch-in zoom indicator, AE/AWB/AF lock tags. Stateless off CameraUiState. |
| `MediaReview.kt` | In-app review of the last capture: zoomable photo viewer and a rotating video player (TextureView + MediaPlayer honoring the container rotation), with delete. |
| `ControlCycles.kt` | Shared enum tap-cycle orders and auto-exposure readout text used by ManualDials, ProSheet, and CameraScreen (single copy — no drift). |
| **ui/theme/** | |
| `Theme.kt` | Material3 dark theme tuned for a Sony-style pro camera surface, typography, color palette, text field/button shapes. |
| `MainActivity.kt` | Entry point. Requests CAMERA/RECORD_AUDIO permissions at runtime (ColorOS blocks pm grant). Hosts the Compose root and ViewModel. Lifecycle: onStart/onStop call engine pause/resume. |
| `TeleCameraApp.kt` | Application class, kept minimal. No wiring needed; all setup in MainActivity/ViewModel. |

---

## Data Flow

**Unidirectional pipeline:**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           COMPOSE UI (Stateless)                         │
│                    Reads CameraUiState, calls CameraActions              │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ UI tap/slide → action (e.g., onIso, onTapFocus)
                           ▼
                ┌──────────────────────┐
                │   CameraViewModel    │
                │   (StateFlow Owner)  │
                │ Implements           │
                │ CameraActions        │
                └──────────┬───────────┘
                           │ setControls(), setTransfer(), capturePhoto(), etc.
                           ▼
        ┌──────────────────────────────────────────┐
        │        CameraEngine (Facade)             │
        │  Orchestrates components' background     │
        │  threads; cross-thread @Volatile seams   │
        │  (selection, caps, controls, transfer…) │
        └──┬───────┬────────────┬──────────┬───────┘
           │       │            │          │
    ┌──────▼─┐ ┌──▼───────┐ ┌─▼────────┐ │
    │ Camera │ │GlPipeline│ │ Capture  │ │
    │Control │ │(GL th.)  │ │Encoders  │ │
    │(camera │ │          │ │          │ │
    │thread) │ │          │ │          │ │
    └────────┘ └──────────┘ └──────────┘ │
                                         ▼
                                  ┌─────────────┐
                                  │ VideoRecord │
                                  │ (audio/vid  │
                                  │  threads)   │
                                  └─────────────┘
                                         │
                                         ▼
                              ┌────────────────────┐
                              │ MediaStore Writer  │
                              │ (scoped storage)   │
                              └────────────────────┘
```

**Frame journey (preview):**
1. Camera2 → SurfaceTexture (backed by EGL texture) → GlPipeline.onFrameAvailable callback (GL thread).
2. FlipRenderer samples the camera texture with rotated texture coordinates (inverse of image rotation, so the visual result is 180°-flipped).
3. OETF and enabled monitoring overlays are applied in the fragment shader (passthrough for SDR).
4. Result rendered to:
   - **Preview Surface** (TextureView on main thread) → live on-screen.
   - **Encoder Surface** (MediaCodec input, if recording) → encoded into MP4.

**Still-photo journey (HEIF):**
1. Camera2 → JPEG ImageReader → photoCallback on camera thread.
2. JPEG bytes copied out of Image (which is valid only during callback).
3. ioExecutor: decode JPEG → Bitmap → center-crop (if AspectRatio is W16_9; W4_3 is the full-sensor, no-crop default) → Matrix.postRotate by `captureRotationDegrees()` (afocal 180° + sensor + device orientation) for pixel-level rotation → encode HEIF (or write JPEG straight from the ImageReader bytes).
4. MediaStore: create pending, write, publish on success; delete on failure.

**Still-photo journey (DNG/RAW):**
1. Camera2 → RAW_SENSOR ImageReader → photoCallback on camera thread.
2. Synchronously (Image still live): DngCreator.writeDng → map `captureRotationDegrees()` to the corresponding EXIF orientation tag → MediaStore write.
3. Cannot pixel-rotate Bayer CFA; EXIF tag is auto-applied by RAW renderers.

---

## Threading Model

**Threads / Executors:**

| Thread / Executor | Owner | Runs |
|---|---|---|
| **Main (UI)** | Android framework | Compose recomposition, ViewModel StateFlow updates, lifecycle callbacks (onStart/onStop). |
| **mainHandler tickers** (main-thread Handler) | CameraViewModel | Periodic self-reposting Runnables: recordTicker (200 ms elapsed-time readout), levelTicker (100 ms horizon roll), orientationTicker (200 ms device orientation), infoTicker (10 s battery/storage), zoomEaseTicker (glides zoomRatio toward the hardware-key target). |
| **gl-pipeline** HandlerThread | GlPipeline | EGL operations, texture sampling, rendering, GL shader execution. |
| **camera** HandlerThread | CameraController | Camera2 lifecycle and capture callbacks. Copies JPEG bytes while the Image is live and writes DNG synchronously. |
| **setupExecutor** (single-thread) | CameraEngine | Camera characteristic IPCs (CameraManager.getCameraCharacteristics) at startup and on camera override. Debug capability logging. |
| **ioExecutor** (single-thread) | CameraEngine | Deferred HEIF encoding (decode JPEG, center-crop, rotate, encode) off the camera thread to avoid OOM + stall. |
| **timelapseScheduler** (scheduled) | CameraEngine | Interval-driven timelapse capture trigger every N seconds. |
| **analysisExecutor** (single-thread) | GlPipeline | Histogram/waveform computation from GL readback (per-pixel math). |
| **audio-capture** (implicit thread) | VideoRecorder | AudioRecord polling loop and PCM-to-AAC encoding. |
| **video-drain** (implicit thread) | VideoRecorder | MediaCodec output buffer draining and MediaMuxer writes. |
| **StandbyAudioMeter** (thread) | CameraEngine | Levels-only AudioRecord tap while video is armed but not rolling; stops before VideoRecorder opens the mic (single-owner invariant). |

**@Volatile Seams (cross-thread visibility):**

Engine state published across worker boundaries:
- CameraEngine publishes mutable runtime configuration such as selection, capabilities, controls,
  video format, transfer, lens/TELE mode, stabilization mode, audio, and aspect ratio through
  `@Volatile` fields. Treat the declarations in `CameraEngine.kt` as the authoritative list.
- `GlPipeline.inputSurface` — published once after EGL texture creation, then read (safely) on setup thread.

Accessed from camera + UI threads:
- `CameraEngine.previewSurface` — set on UI thread, read on camera thread (idempotent: just used to check if it's been set).

Accessed from GL + audio/video threads:
- `VideoRecorder.running`, `inputSurface` — coordination flags and surface for encoder setup.

**Race-safety patterns:**
- **setupExecutor dispatch**: Camera selection and capability reads happen off the main thread to avoid jank; results published back to the engine as @Volatile fields, visible to GL + camera threads.
- **Lifecycle guards**: `CameraEngine.paused` flag prevents camera open during app backgrounding (onStop → pause() sets flag, GL's queued openCamera checks it). `CameraController.closed` flag gates late-arriving open callbacks.
- **Photo callback**: Image objects valid only during `CameraController.PhotoCallback.onPhoto()`. JPEG bytes copied out synchronously; HEIF encoding deferred to ioExecutor. DNG written synchronously (Image still live).
- **Recording**: VideoRecorder owns its video/audio threads and muxer lock; GL just writes frames to the input Surface (thread-safe by the Surface contract).

---

## 180° Flip + Rotation Pipeline

**Why two different rotation approaches:**

The afocal teleconverter's 180° flip must be applied to BOTH the preview and captures. However:
- **Preview** uses texture-coordinate rotation (inverse of image rotation) because GL draws once and the sampled pixels appear rotated.
- **Captures** use pixel-level rotation (direct) because encoded bytes must be rotated in the image buffer itself.

Additionally, **device orientation** from gravity (GyroEis.currentDeviceOrientation) is added to still captures so a photo framed in landscape saves landscape-correct, even though the UI is portrait-locked.

**Preview (GL):**

```kotlin
// RotationMath.previewRotationDegrees(teleconverterMode)
val rotation = if (teleconverterMode) 180 else 0   // afocal 180° only; sensor already applied by SurfaceTexture
// Then pass to FlipRenderer.setRotationDegrees(rotation)
// Example: sensorOrientation=90, teleconverter ON -> preview rotation = 180° (sensor term NOT added)
```

**Key insight**: The camera SurfaceTexture transform (applied via `stMatrix` in `FlipRenderer.draw`) 
**already rotates the sampled image by the sensor orientation**. The GL renderer adds **only the afocal 180°** 
in tele mode (and 0° otherwise). The sensor orientation is still passed to the renderer, but **only to pick the 
preview aspect ratio** (a ~90° rotation swaps displayed width/height). On-device testing confirmed: preview is 
upright when using 180° afocal correction alone, with no sensor-orientation term added. `FlipRenderer` still 
receives sensorOrientation for aspect calculation, not for image rotation.

**Captures (pixel rotation + device orientation):**

```kotlin
// RotationMath.captureRotationDegrees(sensorOrientation, teleconverterMode, deviceOrientation)
val base = sensorOrientation + (if (teleconverterMode) 180 else 0)
val total = (base + deviceOrientation) % 360
// Direct pixel rotation (Matrix.postRotate), no negation
// Example: phone held upright, teleconverter ON
//   sensorOrientation=90, teleconverter ON, device orientation=0
//   base = 90 + 180 = 270°
//   total = 270° (saves landscape-oriented)
```

Device orientation (from gravity via `GyroEis.currentDeviceOrientation()`) is added so a photo framed
while tilting the phone into landscape saves with the correct pixel orientation, matching the visual intent
in the portrait-locked preview (which does not rotate). The rotation functions are unit-tested; a lit,
deliberately held portrait/landscape saved-file check remains useful field verification.

**HEIF (pixel-rotated):**
1. JPEG → decode to Bitmap.
2. Bitmap.createBitmap(..., Matrix.postRotate(captureRotationDegrees), ...) → new rotated Bitmap.
3. Encode HEIF.

**DNG (EXIF orientation tag):**
1. RAW_SENSOR Image → DngCreator.
2. DngCreator.setOrientation(exifOrientationFor(captureRotationDegrees)) — tag set, Bayer pixels untouched.
3. RAW renderers auto-apply the orientation tag on playback.

**Mapping: degrees → EXIF tag**

```kotlin
// RotationMath.exifOrientationFor(degrees): degrees (0/90/180/270)
// 0   → ORIENTATION_NORMAL
// 90  → ORIENTATION_ROTATE_90
// 180 → ORIENTATION_ROTATE_180
// 270 → ORIENTATION_ROTATE_270
```

All rotation math (preview, capture, EXIF orientation mapping) is pure and unit-tested in `camera/RotationMath.kt`.

---

## Camera Selection & HAL Workarounds

**Telephoto detection (CameraSelector2.select):**
- Enumerates all cameras and picks the one with focal length **closest to 70 mm** (not the longest; the 230 mm 10× is ruled out).
- Returns both logical ID (for opening) and physical ID (if it's a sub-camera of a logical multicamera).
- **Key insight**: Prefer **physicalId == null** (standalone camera) over routing to a physical sub-camera via setPhysicalCameraId(). Routing crashes the QTI HAL.

**Session fallback ladder (CameraController.configureSession):**

Attempt 0 (full): preview (HLG10 if supported) + JPEG + RAW
→ Attempt 1 (drop RAW): preview + JPEG
→ Attempt 2 (drop HLG10): preview (SDR) + JPEG
→ Attempt 3 (preview-only): preview only

Each onConfigureFailed increments configAttempt and retries. Once exhausted, failure is surfaced to the app.

**Why the ladder:**
- HLG10 10-bit preview + full-res JPEG + RAW together crash ChiMulticameraBase::configureStreams (`Broken pipe -32` / SIGSEGV).
- RAW routed through a physical sub-camera's setPhysicalCameraId() crashes (`DataSpace override not allowed for format 0x20`). Only enable RAW for standalone (physicalId == null) cameras.
- Some HAL configs demand dropping one stream to allow another.

**Result in code:**

```kotlin
val useHlg = tenBitHlg && caps.supportsHlg10() && attempt < 2  // Drop HLG at attempt 2
val useJpeg = attempt < 3  // Drop JPEG at attempt 3
val useRaw = attempt < 1 && caps.supportsRaw && selection.physicalId == null  // Only attempt 0
```

**Auto-exposure frame-rate policy:**

Photo AUTO uses `CameraCaps.autoFpsRange()`, whose low floor lets AE extend exposure in dim scenes.
Video AUTO sets `pinAutoFps=true` so a selected 29.97 cadence cannot fall to 25 fps in low light.
App-side and manual exposure also pin the selected frame rate.

**Tap-to-focus (region AF):**

Continuous AF mode (`AF_MODE_CONTINUOUS_PICTURE`) with a bare trigger holds the current (often incorrect) 
focus distance. Instead, tapping a region sets a metering/AF region and forces a one-shot `AF_MODE_AUTO` 
scan that **locks** the focus on the tapped point (`touchAfActive` flag, cleared when focus mode changes). 
AF state reaches FOCUSED on device.

---

## Stabilization and Orientation

**Shipping stabilization path:**

Video uses the device HAL's OIS and video stabilization. `VideoStabMode` maps the UI's Off,
"Standard", and "Active" choices to the supported Camera2 request mode and mirrors the device's
`com.oplus.video.stabilization.mode` value. PMA110 result metadata verified `ois=1, vstab=2` for the
enhanced path. The app does not claim Explorer-only stabilization parameters that raw Camera2 cannot
access.

App-side GL gyro warping is disabled by `CameraEngine` with `gl.setEis(false, 0f, 0f)`. The renderer
and `GyroEis` retain dormant correction support, but it is not a user-facing or shipping stabilization
mode. This matters because whole-frame warping cannot remove motion blur accumulated during exposure;
the active HAL path can engage the physical lens OIS.

**Gravity-derived orientation:**

`GyroEis` remains active as a sensor-orientation provider:

```kotlin
// Used by the horizon and saved-still rotation paths.
val roll = gyroEis.currentRollDegrees()
val deviceOrientation = gyroEis.currentDeviceOrientation() // 0/90/180/270
```

The discrete orientation updates only when the phone is clearly held: in-plane gravity
`hypot(x, y)` must exceed `FLAT_GRAVITY_THRESHOLD`. When the phone lies flat, the last confident
orientation is retained instead of deriving a random quadrant from near-zero x/y values. The horizon
roll uses a similar confidence threshold when the phone points steeply up or down.

---

## Color & Video Pipeline

**Video codec and color profiles:**

Supported codecs are scanned at runtime via `EncoderCaps.kt` (MediaCodecList). Only hardware HEVC and
AVC encoders are exposed. Bitrate presets run Low → **Max** (`BitrateLevel`): the REQUESTED target at
Max computes to ~99 Mbps at 4K30 (0.40 bpp), hard-clamped at 120 Mbps (`videoBitRate`). A device
recording measured ~134 Mbps in the file — that is VBR encoder overshoot of the ~99 Mbps target (no
KEY_BITRATE_MODE is set), not a requested ceiling. The old High (0.16 bpp) left half the HW headroom
unused.

| Codec | Encoder profile | Color Space | Transfer | Container | Notes |
|---|---|---|---|---|---|
| HEVC (H.265) | Main10 profile (SDR: Main) | Rec.2020 (SDR: Rec.709) | HLG / O-Log2 / SDR | MP4 | Primary HW encoder. Shipping source/EGL is 8-bit; Main10 is the output profile, not an end-to-end 10-bit claim. |
| AVC (H.264) | 8-bit | Rec.709 | SDR | MP4 | Fallback; forces GL SDR (no HLG/Log); HW. |
| APV | — | — | — | — | HW `c2.qti.apv.encoder` (pro all-intra ≤2 Gbps) EXISTS but **gated out** — MediaMuxer rejects APV-in-MP4 (breaks the encoder mid-drain). |
| Dolby Vision | 10-bit | Rec.2020 | Dolby Vision | MP4 | HW `c2.qti.dv.encoder` detected (`hasDolbyVision`); not wired (clean DV-in-MP4 muxing non-trivial). |

**Vendor HAL features:** HAL OIS+EIS and directional-audio parameters are used where the device accepts
them. Native vendor log is inert for third-party Camera2; Auto HDR and in-sensor zoom were removed after
HAL stability testing. See CLAUDE.md for the per-key notes.

**Video resolution and frame rates:**

Resolutions come from the selected camera's `StreamConfigurationMap`, then the shipping selector caps
recording width at 3840. PMA110 exposes 4K UHD as the largest selected 16:9 mode. Frame rates include
standard rates (24/25/30/60 fps) and drop-frame equivalents (23.976/29.97/59.94). The UI deliberately
excludes 120 fps because the constrained high-speed session crashes this HAL.

Open-Gate (4:3-aspect recording; device-verified 2560×1920 on the tele — the recording surface is
capped at 3840 wide, so this is NOT the full 4096×3072 still readout) is available alongside the
standard 16:9 sizes.

Exact bitrate is displayed in Mbps and user-selectable per codec and resolution.

**Main10 output profiles (v1):**

HEVC Main10 profile → MediaCodec configured with:
```kotlin
ColorProfiles.videoFormat(
    codec=VideoCodec.HEVC,
    width, height, fps, bitRate,
    transfer=ColorTransfer.HLG or LOG
)
// Sets:
//   MediaFormat.KEY_MIME = "video/hevc"
//   MediaFormat.KEY_PROFILE = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
//   MediaFormat.KEY_BIT_RATE = bitRate
//   MediaFormat.KEY_COLOR_STANDARD = MediaFormat.COLOR_STANDARD_BT2020  (Rec.2020)
//   MediaFormat.KEY_COLOR_TRANSFER = COLOR_TRANSFER_HLG or COLOR_TRANSFER_SDR_VIDEO (O-Log2)
//   MediaFormat.KEY_COLOR_RANGE = LIMITED (HLG) or FULL (O-Log2)
```

**Shipping GL pipeline:**

```kotlin
// CameraEngine intentionally calls gl.start(tenBit = false)
EGL_RED_SIZE = 8, EGL_GREEN_SIZE = 8, EGL_BLUE_SIZE = 8, EGL_ALPHA_SIZE = 8
EGL_RECORDABLE_ANDROID = EGL_TRUE
```

Color-profile rendering happens in the fragment shader:
- **Input**: normalized [0, 1] RGBA from camera SurfaceTexture sampling.
- **OETF** (Opto-Electronic Transfer Function):
  - **HLG (Hybrid Log-Gamma)**: Rec.2100 standard. Applied in shader; supports HDR playback.
  - **O-Log2 (LOG)**: OPPO's official O-Log2 curve (white-paper constants), applied after γ2.2
    linearization of the SDR stream + Rec.709→BT.2020 matrix (O-Gamut). Grades with OPPO's public
    O-Log2 LUTs; no above-white headroom (HAL-native log is vendor-gated — see CLAUDE.md).
  - **SDR**: no shader curve; HEVC Main 8-bit BT.709 limited-range for zero-grading footage.
- **Output**: 8-bit EGL surface to a Main10-profile encoder for HLG/O-Log2.

**Fragment shader (Shaders.kt):**
```glsl
// Pseudocode
vec3 color = texture(camera, uv).rgb;  // Linear [0, 1]
color = applyOetf(color, transfer);    // HLG or Log curve
// v1 output precision remains 8-bit for HAL stability
```

**AVC 8-bit fallback:**

When user selects AVC (H.264), the GL pipeline is forced to SDR:
```kotlin
// CameraEngine.startRecording()
val glTransfer = if (codec == VideoCodec.AVC) null else transfer
gl.setTransfer(glTransfer)  // null = no OETF in shader (linear passthrough)
```

Result: 8-bit SDR MP4, which AVC can encode natively.

**Audio (AAC, 192 kbps):**

Captured via AudioRecord on a separate thread. Software PCM gain applied (user-settable, 0.5× to 2.0× × post-gain). AAC LC encoder. Live RMS level throttled to ~10 Hz for the UI level meter.

---

## Capture & Storage

**Photo formats:**

Users can select HEIF or JPEG for still captures (not mutually exclusive). Both capture paths start from 
the JPEG ImageReader and apply the same rotation.

**HEIF (still photo):**

1. Camera2 → JPEG ImageReader (full resolution).
2. photoCallback on camera thread: copy JPEG bytes out (Image valid only during callback).
3. ioExecutor (off-camera thread):
   - Decode JPEG → Bitmap.
   - Center-crop (if AspectRatio != W4_3).
   - Matrix.postRotate(captureRotationDegrees) → new Bitmap.
   - HeifCapture.writeHeif(ParcelFileDescriptor, Bitmap) → HEIF-encoded bytes.
4. MediaStore: create pending entry with IS_PENDING flag → write → publish on success; delete on failure.

**JPEG (still photo):**

JPEG runs the SAME processed-pixel pipeline as HEIF (`saveJpegAsync`): decode the ImageReader bytes →
center-crop to the selected aspect → rotate (afocal 180° + device) → re-encode at
`ManualControls.jpegQuality`. The mandatory pixel rotation means it is NOT a byte passthrough — the
output is a second lossy JPEG generation (accepted; keeping HEIF/JPEG framing identical wins). The
exposure EXIF is re-stamped after `Bitmap.compress` from the shot's own TotalCaptureResult.

**DNG (RAW, full-frame):**

1. Camera2 → RAW_SENSOR ImageReader.
2. photoCallback on camera thread (synchronous, Image still live):
   - DngCapture.writeDng(OutputStream, raw Image, CameraCharacteristics, TotalCaptureResult, exifOrientation).
   - DngCreator sets EXIF orientation tag (cannot rotate Bayer pixels).
3. MediaStore: create pending → write → publish.

**Aspect ratio (HEIF only):**

```kotlin
data class AspectRatio(val w: Int, val h: Int) {
    W4_3(4, 3),      // Full sensor (no crop, default, the no-crop sentinel)
    W16_9(16, 9)     // Center crop of 4:3 to 16:9 landscape
}
// CameraEngine.centerCrop(bitmap, w, h)
// Computes largest w:h rect centered in the bitmap, crops it out.
```

The sensor is 4:3-native; `W4_3` is full readout, and `W16_9` is its center crop. 
DNG always saves full-frame (crop not applied).

**MediaStore scoped storage (MediaStoreWriter):**

```kotlin
createPendingImage(context, fileName, mimeType) → Uri
// Creates entry in DCIM/X9Tele with IS_PENDING = 1
openParcelFd(context, uri, "rw") → ParcelFileDescriptor
// Caller writes to the FD
publish(context, uri)
// Updates IS_PENDING = 0 (visible in gallery)
delete(context, uri)
// Removes the entry (if write failed)
```

On failure (OOM, disk full, etc.), the pending entry is deleted → no partial files in gallery.

---

## Pro Controls Surface

**Overview:**
Core Camera2 capture parameters are housed in the immutable `ManualControls` data class. The ViewModel
copies it with updated fields on each interaction and re-applies it through
`CameraEngine.setControls()`. Capture-mode, video, assist, hardware-key, and persistence options live
in `CameraUiState`/`ExtraSettings` rather than being forced into `ManualControls`.

Settings are persisted across app launches via `SettingsStore.kt` (SharedPreferences), gated by a 
"Remember Settings" toggle that **defaults ON**. On launch, saved pro settings are restored from storage 
and pushed to the engine before the camera starts. Fresh installs open on the 1× main lens with TELE
off; separate default-on Setup toggles decide whether the saved lens and TELE state are restored. Enums
are stored by name for forward compatibility, and loads are defensive (unknown values revert to defaults).

**UI layout (ProSheet.kt):**

The fixed settings panel has nine left-rail tabs:

1. **My** — operator-selected shortcuts.
2. **Shoot** — capture mode, drive/timer, still format, and framing choices.
3. **Exposure** — PASM-like mode, ISO/shutter, EV, metering, WB, and related locks.
4. **Focus** — AF/MF mode, focus distance, tap-AF spot, and focus assistance.
5. **Lens** — 0.6x/1x/3x/10x selection, TELE mode, stabilization, and zoom.
6. **Video** — codec, transfer, resolution, FPS, bitrate, Open Gate, and audio.
7. **Image** — JPEG quality and device processing controls.
8. **Assist** — peaking, zebra, scopes, grid, level, and frame lines.
9. **Setup** — persistence, hardware controls, and app-level preferences.

**Quick Fn dials (ManualDials.kt):**

Horizontal scrolling wheels for 6 controls (focus, shutter, ISO, white balance, EV, zoom).

**Control application:**

```kotlin
// ViewModel.onIso(iso)
updateControls { it.copy(iso = iso, autoExposure = false) }
// → engine.setControls(updated)
// → CameraController.updateControls(updated)
// → CameraController builds new CaptureRequest, applies ManualControls via applyManualControls()

fun CaptureRequest.Builder.applyManualControls(c: ManualControls, caps: CameraCaps) {
    applyFocus(c, caps)
    applyExposure(c, caps)
    applyWhiteBalance(c, caps)
    applyProcessing(c, caps)
    applyFlash(c, caps)
    applyZoom(c, caps)
    // OIS per toggle here; HAL video stabilization (CONTROL_VIDEO_STABILIZATION_MODE) is owned by
    // CameraController and set per the selected VideoStabMode on the repeating request (not forced OFF).
    if (caps.oisAvailable)
        set(LENS_OPTICAL_STABILIZATION_MODE, if (c.oisEnabled) ON else OFF)
}
```

All values clamped to hardware ranges (CameraCaps gates what's supported).

---

## Build & Toolchain

See `CLAUDE.md` § **Toolchain** for complete toolchain versions and build setup details.

**Quick reference:**
- Kotlin / Compose compiler 2.4.0, AGP 9.2.1, Gradle 9.6.1
- compileSdk 37 / targetSdk 36 / minSdk 36 (API 36 is Android 16)
- JDK 21 required; set JAVA_HOME for CLI builds
- Compose BOM 2026.06.01

**Build:**
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:lintRelease :app:assembleRelease :app:bundleRelease  # signing credentials required
```

**Test suite:** `app/src/test/` is the source of truth (216 tests across 30 classes as of the 2026-07-10 cycle-2 pass; re-count with `./gradlew :app:testDebugUnitTest` rather than trusting this figure).

**Device verification:**
```bash
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n me.hletrd.telecampro.debug/com.hletrd.findx9tele.MainActivity
```

**Permissions:** CAMERA + RECORD_AUDIO requested at runtime (ColorOS blocks pm grant; user grants on device once).

---

## See Also

- `docs/BACKLOG.md` — release status, manual Play steps, residual checks, and deferred work.
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — original design doc (intent; superseded by actual code where it diverges).
- `CLAUDE.md` § **Hard-won device facts** — HAL crash workarounds and their signatures.
