# Find X9 Ultra Teleconverter Camera App — Architecture

**Table of Contents**
1. [Overview](#overview)
2. [Module Map](#module-map)
3. [Data Flow](#data-flow)
4. [Threading Model](#threading-model)
5. [180° Flip + Rotation Pipeline](#180-flip--rotation-pipeline)
6. [Camera Selection & HAL Workarounds](#camera-selection--hal-workarounds)
7. [Stabilization (Gyro EIS)](#stabilization-gyro-eis)
8. [Color & Video Pipeline](#color--video-pipeline)
9. [Capture & Storage](#capture--storage)
10. [Pro Controls Surface](#pro-controls-surface)
11. [Build & Toolchain](#build--toolchain)

---

## Overview

A professional single-device camera app for the **OPPO Find X9 Ultra** (Android 16 / API 36) that uses Camera2 to control the rear 3× periscope telephoto lens through a **Hasselblad "Earth Explorer" afocal 300 mm teleconverter** (≈4.286× magnification: 300 mm ÷ 70 mm). The app captures high-quality stills (HEIF + RAW/DNG) and video (10-bit HEVC Rec.2020 in HLG or Log transfer function).

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
| `CameraEngine.kt` | Facade orchestrating Camera2, GL, capture encoders, video recorder, gyro, and storage. Unidirectional data flow. Thread-safe @Volatile seams to GL and camera background threads. |
| `CameraController.kt` | Camera2 session lifecycle, request building, fallback ladder for stream configurations. Callback-driven, runs on a camera HandlerThread. |
| `CameraSelector2.kt` | Detects the telephoto physical lens: finds the camera with focal length closest to 70 mm, prefers standalone ID over physical sub-camera routing. |
| `CameraState.kt` | Enums (CaptureMode, ColorTransfer, FocusMode, DriveMode, etc.) and data classes (CameraUiState, ManualControls, CameraCaps) — the shared language between UI and engine. |
| `CaptureCapabilities.kt` | Queries Camera2 characteristics for manual-sensor, RAW, 10-bit HDR, focus range, metering regions — gate-keeping capabilities. |
| `ManualControls.kt` | Immutable snapshot of all pro capture parameters (focus, ISO, shutter, white balance, metering, processing). The ViewModel updates a copy; the Engine applies it to the repeating request. |
| `RotationMath.kt` | Pure, unit-tested functions for preview/capture/EXIF rotation math (extracted from CameraEngine). |
| `VendorTagInspector.kt` | Debug-only Camera2 capability logger for device-specific request/session keys. |
| **gl/** | |
| `GlPipeline.kt` | Owns the GL render thread. Receives camera SurfaceTexture, renders 180°-flipped quads to preview Surface and video encoder Surface. Owns EGL context, texture, sampling buffers. Drives histogram/waveform analysis on a background executor. |
| `FlipRenderer.kt` | Low-level OpenGL ES fullscreen quad renderer with texture-coordinate rotation (inverse of image rotation) to flip the 180° afocal image. Applies OETF (HLG / Log) in the fragment shader. Handles focus peaking (edge detection) and zebra (exposure clipping). |
| `EglCore.kt` | EGL/GLES setup: context creation, surface binding, 10-bit RGBA1010102 / FP16 color formats for Rec.2020. |
| `Shaders.kt` | Fragment/vertex shader source code. OETF (HLG, Log) application; peaking/zebra compositing; punch-in zoom. |
| **stab/** | |
| `GyroEis.kt` | Integrates gyroscope + accelerometer data into shake (high-frequency residual) and device orientation (gravity-derived). Provides yaw/pitch/roll correction radians (scaled by focal length in GL) and absolute roll degrees for the horizon overlay. |
| **capture/** | |
| `HeifCapture.kt` | Encodes HEIF from a Bitmap (JPEG decoded, center-cropped, pixel-rotated 180°). Writes via the ioExecutor off the camera thread. |
| `DngCapture.kt` | Writes DNG (RAW sensor frame) using DngCreator. Sets EXIF orientation tag (cannot pixel-rotate Bayer CFA). Synchronous in the photo callback while the raw Image is live. |
| **video/** | |
| `VideoRecorder.kt` | MediaCodec HEVC/AVC encoder + AAC audio encoder + MediaMuxer. Drains video/audio to MP4. Video input comes from GL (already 180°-flipped); audio captured from microphone on a separate thread. Software PCM gain. |
| `ColorProfiles.kt` | Builds MediaFormat specs for HEVC Main10 (Rec.2020 + HLG/Log) and AVC 8-bit SDR. Tags dynamic range, color space, transfer function. |
| `EncoderCaps.kt` | Scans MediaCodecList for available video encoders (HW AVC/HEVC, Dolby Vision; AV1 software-only) and reports which VideoCodec values are supported/hardware. |
| **storage/** | |
| `MediaStoreWriter.kt` | Scoped-storage wrapper: creates pending DCIM/Pictures entries (IS_PENDING), publishes on success, deletes on failure. Opened files backed by ParcelFileDescriptor. |
| `SettingsStore.kt` | SharedPreferences persistence of ManualControls + ExtraSettings across launches, gated by a "Remember Settings" toggle (default ON); enums stored by name, defensive load. Lens and TELE restoration have separate default-on preserve toggles. |
| **focus/** | |
| `FocusMapping.kt` | Maps UI slider (0..1) to LENS_FOCUS_DISTANCE (diopters). Nonlinear to enhance resolution near infinity (√ curve and offset). Bidirectional. |
| **ui/** | |
| `CameraScreen.kt` | Compose root layout: preview TextureView, shutter button, mode toggle, gallery thumbnail, collapsible pro panel, overlays (reticle, histogram, zebra, peaking, level, punch-in). Stateless, reads CameraUiState. |
| `CameraViewModel.kt` | StateFlow<CameraUiState> owner. Turns CameraActions (UI) into CameraEngine calls. Debounces slider-driven control updates. Ticks the recording timer and level overlay roll. UI-thread only. |
| `CameraActions.kt` | Callback interface: ~38 methods (onFocusMode, onIso, onShutterNs, onTapFocus, onTransfer, etc.). The UI stateless UI and ViewModel implement or call it. |
| **ui/controls/** | |
| `ManualDials.kt` | Horizontal scrolling dials for quick access to focus, ISO, shutter, white balance — the "Fn" layer. Mapped to CameraActions. |
| `ProSheet.kt` | 8-tab collapsible sheet: Focus, Exposure, White Balance, Processing, Optics, Drive, Recording, Format/Bit-depth. Each tab hosts controls feeding CameraActions. |
| `ProControls.kt` | Granular Compose components: range sliders, picker wheels, toggle switches, text fields (Kelvin, tint, zoom ratio, JPEG quality). All two-way bound to CameraUiState. |
| **ui/overlays/** | |
| `Overlays.kt` | Compose overlays: reticle (tap-to-focus), histogram/waveform, grid, spirit level, peaking, zebra, punch-in zoom indicator. Stateless off CameraUiState. |
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
3. EIS correction applied: gyro shake scaled by effective focal length, subtracted from clip coordinates → sampled pixels drift to counter shake.
4. OETF applied in fragment shader (HLG or Log curve for 10-bit; passthrough for SDR).
5. Result rendered to:
   - **Preview Surface** (TextureView on main thread) → live on-screen.
   - **Encoder Surface** (MediaCodec input, if recording) → encoded into MP4.

**Still-photo journey (HEIF):**
1. Camera2 → JPEG ImageReader → photoCallback on camera thread.
2. JPEG bytes copied out of Image (which is valid only during callback).
3. ioExecutor: decode JPEG → Bitmap → center-crop (if AspectRatio is W16_9; W4_3 is the full-sensor, no-crop default) → Matrix.postRotate by `captureRotationDegrees()` (afocal 180° + sensor + device orientation) for pixel-level rotation → encode HEIF (or write JPEG straight from the ImageReader bytes).
4. MediaStore: create pending, write, publish on success; delete on failure.

**Still-photo journey (DNG/RAW):**
1. Camera2 → RAW_SENSOR ImageReader → photoCallback on camera thread.
2. Synchronously (Image still live): DngCreator.writeDng → set EXIF orientation tag (ORIENTATION_ROTATE_180) → MediaStore write.
3. Cannot pixel-rotate Bayer CFA; EXIF tag is auto-applied by RAW renderers.

---

## Threading Model

**Threads / Executors:**

| Thread / Executor | Owner | Runs |
|---|---|---|
| **Main (UI)** | Android framework | Compose recomposition, ViewModel StateFlow updates, lifecycle callbacks (onStart/onStop). |
| **gl-pipeline** HandlerThread | GlPipeline | EGL operations, texture sampling, rendering, GL shader execution. |
| **camera** HandlerThread | CameraController | Camera2 callbacks (onOpened, onDisconnected, onCaptureCompleted, onCaptureProgressed). Photo encoding (HEIF decode/rotate, DNG write). |
| **setupExecutor** (single-thread) | CameraEngine | Camera characteristic IPCs (CameraManager.getCameraCharacteristics) at startup and on camera override. Debug capability logging. |
| **ioExecutor** (single-thread) | CameraEngine | Deferred HEIF encoding (decode JPEG, center-crop, rotate, encode) off the camera thread to avoid OOM + stall. |
| **timelapseScheduler** (scheduled) | CameraEngine | Interval-driven timelapse capture trigger every N seconds. |
| **analysisExecutor** (single-thread) | GlPipeline | Histogram/waveform computation from GL readback (per-pixel math). |
| **audio-capture** (implicit thread) | VideoRecorder | AudioRecord polling loop and PCM-to-AAC encoding. |
| **video-drain** (implicit thread) | VideoRecorder | MediaCodec output buffer draining and MediaMuxer writes. |

**@Volatile Seams (cross-thread visibility):**

Written from UI thread, read on GL thread:
- `CameraEngine.selection`, `caps`, `videoSize`, `videoCodec`, `bitrateLevel`, `driveMode`, `controls`, `transfer`, `overrideId`, `teleconverterMode`, `eisEnabled`, `eisCrop`, `audioGain`, `aspectRatio` — control parameters synchronized via JMM volatile read/write.
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
in the portrait-locked preview (which does NOT rotate). This ensures captures are always upright in any hold.

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

**Auto-exposure frame-rate floor:**

A fixed target-fps range `[30, 30]` pins the exposure at 1/30 s, capping brightness in low light. Automatic 
exposure now uses `CameraCaps.autoFpsRange()`, which provides the lowest floor fps at the target max. This 
allows AE to slow the preview frame rate and extend exposure in dim scenes. Manual exposure mode still pins fps.

**Tap-to-focus (region AF):**

Continuous AF mode (`AF_MODE_CONTINUOUS_PICTURE`) with a bare trigger holds the current (often incorrect) 
focus distance. Instead, tapping a region sets a metering/AF region and forces a one-shot `AF_MODE_AUTO` 
scan that **locks** the focus on the tapped point (`touchAfActive` flag, cleared when focus mode changes). 
AF state reaches FOCUSED on device.

---

## Stabilization (Gyro EIS)

**Overview:**
Client-side electronic stabilization using gyroscope + accelerometer. The gyro integrates angular velocity into absolute orientation, subtracts a low-pass "intended motion" estimate, leaving high-frequency shake. The GL pipeline applies the residual angles to shift the sampled image.

**Key innovation: focal-length scaling**
EIS correction is scaled by the **effective focal length in image widths**:
```kotlin
val mag = if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f  // 300/70 ≈ 4.286
gl.setEis(eisEnabled, nativeFocalInImageWidths * mag, eisCrop)
```

At 300 mm (teleconverter), a 1° shake moves the subject 5.2× more pixels than at 70 mm → the same 1° correction is 5.2× more effective. This is why we multiply by magnification.

**Gyro integration (GyroEis):**

```kotlin
// Per sample (@ ~200 Hz)
angPitch += gyro.values[0] * dt  // Integrated absolute orientation
angYaw += gyro.values[1] * dt
angRoll += gyro.values[2] * dt

// Low-pass the integrated orientation to estimate "intended motion"
smoothPitch += LOW_PASS_ALPHA * (angPitch - smoothPitch)  // LOW_PASS_ALPHA = 0.1

// Residual is the high-frequency shake
corrPitch = angPitch - smoothPitch
corrYaw = angYaw - smoothYaw
corrRoll = angRoll - smoothRoll
```

Low-pass time constant (~1–2 Hz at 200 Hz sampling; adjusted by SAMPLING_PERIOD_US) allows slow intentional pans to follow the intended-motion estimate while shake above the corner is left as residual.

**Device orientation (gravity-derived):**

Accelerometer sample → gravity vector → roll angle (atan2(x, y)).
```kotlin
rollDegrees = (rollDeg - rollDegrees) * ROLL_LOW_PASS_ALPHA
// Used for:
// 1. Horizon/level overlay (currentRollDegrees)
// 2. Auto-rotating captures while UI is portrait-locked (currentDeviceOrientation → 0/90/180/270)
```
`currentDeviceOrientation()` returns a `stableOrientation` that is **only updated while the phone is
clearly held** — the in-plane gravity magnitude `hypot(x, y)` must exceed `FLAT_GRAVITY_THRESHOLD`
(~half g). When the phone lies flat, `x`/`y` are ~0 and `atan2(x, y)` is pure noise, so the last
confident orientation is held instead of snapping randomly. This was found via output-file
inspection: a DNG shot flat-on-desk had tagged `ORIENTATION_NORMAL` (0°) instead of the expected
270°, because the pre-fix code derived a discrete value from the noisy flat-phone angle.

**Application in GL:**

```kotlin
// FlipRenderer applies EIS in the fragment shader:
// vec2 corrected_uv = uv + (correction_yaw, correction_pitch) * effective_focal * eisCrop
// sampledColor = texture(camera, corrected_uv)
```

Correction is scaled by eisCrop (0.06 to 0.18, default 0.10) to limit the headroom used — higher values crop more but leave less guard band.

**Stabilization strategy (updated 2026-07-07):** the app-side gyro EIS above is now ONE of four
`VideoStabMode` options (`Gyro`), no longer the default. For video the shutter is fixed, so per-frame
MOTION BLUR is set by the shutter and only **OIS** (lens moves during exposure) can reduce it — app-side
gyro EIS only warps whole frames. The default is now the HAL's own OIS+EIS via
`CONTROL_VIDEO_STABILIZATION_MODE = PREVIEW_STABILIZATION` (the tele advertises modes [0,1,2]), the
same stabilization path exposed through the device-specific `com.oplus.video.stabilization.mode` key
— device-verified `ois=1, vstab=2`. See CLAUDE.md for the full Camera2 capability notes.

**Remaining gyro-EIS notes (apply only to the `Gyro` mode):**
- Gyro axis/sign mapping + on-device tuning are approximate.
- OIS is a user toggle; keep it on at 300 mm (it de-blurs each frame).

---

## Color & Video Pipeline

**Video codec & 10-bit support:**

Supported codecs are scanned at runtime via `EncoderCaps.kt` (MediaCodecList), which detects
available HW encoders and Dolby Vision support. AV1 is software-only on this device (labeled "slow/SW",
gated ≤1080p/≤30fps). Bitrate presets run Low → **Max** (`BitrateLevel`), reaching the QTI HW ceiling
(~120 Mbps at 4K, device-verified ~134 Mbps at HEVC 4K30 Max — the old High left half the headroom
unused). Also alongside `com.oplus.log.video.mode` (HAL-native scene-referred log) as a separate path.

| Codec | Bit-depth | Color Space | Transfer | Container | Notes |
|---|---|---|---|---|---|
| HEVC (H.265) | 10-bit (SDR: 8-bit) | Rec.2020 (SDR: Rec.709) | HLG / O-Log2 / SDR | MP4 | Primary; Main10 (SDR: Main); HDR-playable (HLG); HW. O-Log2 = official OPPO OETF baked in GL. |
| AVC (H.264) | 8-bit | Rec.709 | SDR | MP4 | Fallback; forces GL SDR (no HLG/Log); HW. |
| AV1 | 8-bit | Rec.709 | SDR | MP4 | Software encoder only (slow); gated ≤1080p/≤30fps; labeled "slow/SW". |
| APV | — | — | — | — | HW `c2.qti.apv.encoder` (pro all-intra ≤2 Gbps) EXISTS but **gated out** — MediaMuxer rejects APV-in-MP4 (breaks the encoder mid-drain). |
| Dolby Vision | 10-bit | Rec.2020 | Dolby Vision | MP4 | HW `c2.qti.dv.encoder` detected (`hasDolbyVision`); not wired (clean DV-in-MP4 muxing non-trivial). |

**Vendor HAL features:** several vendor keys are reachable from third-party Camera2 and are
implemented — HAL-native log, HAL OIS+EIS stabilization, directional audio (Sound Focus/Stage), Auto
HDR, in-sensor zoom. Each is device-verified through to a saved file; see CLAUDE.md for the per-key
notes.

**Video resolution and frame rates:**

Resolutions come from the selected camera's `StreamConfigurationMap`. The camera reports video sizes 
up to 8K where available, plus high-speed capture sizes. Frame rates include standard rates (24/25/30/60 fps), 
drop-frame equivalents (23.976/29.97/59.94), and 120 fps high-speed capture where a high-speed configuration exists. 
The `VideoFrameRate` enum gates frame-rate availability per resolution: 8K is limited to 30 fps or below; 
120 fps high-speed is only offered where the HAL provides a dedicated high-speed config.

Open-Gate (full 4:3 sensor) is available as a recording option alongside standard crops.

Exact bitrate is displayed in Mbps and user-selectable per codec and resolution.

**10-bit paths:**

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
//   MediaFormat.KEY_COLOR_TRANSFER = MediaFormat.COLOR_TRANSFER_HLG or COLOR_TRANSFER_ST2084 (PQ) or COLOR_TRANSFER_LINEAR (Log approx.)
//   MediaFormat.KEY_COLOR_RANGE = MediaFormat.COLOR_RANGE_FULL
```

**GL pipeline (RGBA1010102 / Rec.2020):**

```kotlin
// EglCore creates 10-bit surface
EGL_SURFACE_TYPE = EGL_WINDOW_BIT
EGL_COLOR_BUFFER_TYPE = EGL_RGB_BUFFER (not luminance)
EGL_RED_SIZE = 10, EGL_GREEN_SIZE = 10, EGL_BLUE_SIZE = 10, EGL_ALPHA_SIZE = 2
EGL_RECORDABLE_ANDROID = EGL_TRUE
```

All 10-bit rendering happens in the fragment shader:
- **Input**: normalized [0, 1] RGBA from camera SurfaceTexture sampling.
- **OETF** (Opto-Electronic Transfer Function):
  - **HLG (Hybrid Log-Gamma)**: Rec.2100 standard. Applied in shader; supports HDR playback.
  - **O-Log2 (LOG)**: OPPO's official O-Log2 curve (white-paper constants), applied after γ2.2
    linearization of the SDR stream + Rec.709→BT.2020 matrix (O-Gamut). Grades with OPPO's public
    O-Log2 LUTs; no above-white headroom (HAL-native log is vendor-gated — see CLAUDE.md).
  - **SDR**: no shader curve; HEVC Main 8-bit BT.709 limited-range for zero-grading footage.
- **Output**: 10-bit RGBA1010102 to encoder.

**Fragment shader (Shaders.kt):**
```glsl
// Pseudocode
vec3 color = texture(camera, uv).rgb;  // Linear [0, 1]
color = applyOetf(color, transfer);    // HLG or Log curve
// 10-bit quantization happens in the driver during texture write
```

**AVC 8-bit fallback:**

When user selects AVC (H.264), the GL pipeline is forced to SDR:
```kotlin
// CameraEngine.startRecording()
val glTransfer = if (codec == VideoCodec.AVC) null else transfer
gl.setTransfer(glTransfer)  // null = no OETF in shader (linear passthrough)
```

Result: 8-bit SDR MP4, which AVC can encode natively.

**Audio (AAC, ~128 kbps):**

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

JPEG is written directly from the raw Camera2 JPEG ImageReader bytes (no re-encoding). Metadata and rotation 
are handled via the EXIF orientation tag (same as DNG). This avoids quality loss from re-compression.

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
// Creates entry in DCIM/Pictures with IS_PENDING = 1
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
All professional capture parameters are housed in ManualControls, a 44-field immutable data class. The ViewModel copies it with updated fields on each interaction and re-applies to the camera via CameraEngine.setControls().

Settings are persisted across app launches via `SettingsStore.kt` (SharedPreferences), gated by a 
"Remember Settings" toggle that **defaults ON**. On launch, saved pro settings are restored from storage 
and pushed to the engine before the camera starts. Fresh installs open on the 1× main lens with TELE
off; separate default-on Setup toggles decide whether the saved lens and TELE state are restored. Enums
are stored by name for forward compatibility, and loads are defensive (unknown values revert to defaults).

**Control categories (see ManualControls.kt for full list):**

| Category | Count | Examples |
|---|---|---|
| Focus | 3 | focusMode (MANUAL/AUTO/CONTINUOUS/MACRO), focusDistanceDiopters, afLock |
| Exposure | 8 | autoExposure, iso, exposureTimeNs, shutterMode (SPEED/ANGLE), shutterAngle, exposureCompensation, aeLock, antibanding, fps |
| White Balance | 4 | wbMode (AUTO/INCANDESCENT/DAYLIGHT/MANUAL), wbKelvin, wbTint, awbLock |
| Metering | 1 | meteringMode (MATRIX/CENTER/SPOT) |
| Processing | 3 | edge (ProcessingLevel), noiseReduction, colorEffect |
| Optics | 3 | flash, oisEnabled, zoomRatio |
| Output | 1 | jpegQuality |

**UI layout (ProSheet.kt):**

Eight collapsible tabs:
1. **Focus** — mode picker, slider (FocusMapping: 0..1 ↔ diopters), AF-lock toggle.
2. **Exposure** — ISO, shutter speed (Speed mode), shutter angle (Angle mode), EV, FPS, metering mode.
3. **White Balance** — WB mode, Kelvin/tint pickers (manual), AWB-lock.
4. **Processing** — edge, noise reduction, color effect dropdowns.
5. **Optics** — flash, OIS, zoom ratio slider.
6. **Drive** — drive mode (SINGLE/BURST/AEB/TIMELAPSE), interval, timer.
7. **Recording** — audio on/off, audio gain, bitrate, codec, resolution.
8. **Format** — photo formats (HEIF/DNG), transfer function, aspect ratio.

**Quick Fn dials (ManualDials.kt):**

Horizontal scrolling wheels for 4 most-frequent controls (focus, ISO, shutter, WB).

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
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

**Test suite:** FocusMappingTest, RotationMathTest, CameraSelector2Test, VideoCapabilitiesTest.

**Device verification:**
```bash
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hletrd.findx9tele/.MainActivity
```

**Permissions:** CAMERA + RECORD_AUDIO requested at runtime (ColorOS blocks pm grant; user grants on device once).

---

## See Also

- `docs/BACKLOG.md` — prioritized remaining work + known-unverified items.
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — original design doc (intent; superseded by actual code where it diverges).
- `CLAUDE.md` § **Hard-won device facts** — HAL crash workarounds and their signatures.
