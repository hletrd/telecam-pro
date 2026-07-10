# Find X9 Ultra Teleconverter Camera App — Design Document

> **Original design snapshot.** This records intent from 2026-07-01. The as-built source,
> `CLAUDE.md`, and `docs/ARCHITECTURE.md` take precedence where implementation diverges.

- Date: 2026-07-01
- Target device: OPPO Find X9 Ultra (single device), Android 16 (API 36)
- Purpose: Professional camera app for 3x periscope telephoto lens + afocal teleconverter (300mm equivalent) shooting

## 1. Background / Problem Definition

- The teleconverter is **afocal** (reflecting/refracting telescope without erecting prism), so images enter **rotated 180°**.
  Vertical flip + horizontal flip = 180° rotation (parity-preserving, not mirroring). Both preview and saved results must be corrected 180°.
- Exit light from the afocal attachment is approximately collimated, so the phone lens focuses **near infinity**.
  → Manual focus (fine-tuning near infinity) is key.
- Single device only, so generic compatibility layers unnecessary. Use Camera2 directly for low-level control.

## 2. Goals / Non-Goals

### Goals
- Shoot with rear 3x telephoto physical lens. Apply 180° flip to preview/photos/video.
- Full manual control: focus (LENS_FOCUS_DISTANCE), ISO (SENSITIVITY), shutter (EXPOSURE_TIME), WB, EV.
- Photos: **HEIF + RAW (DNG)** saved simultaneously. HEIF with actual 180° pixel rotation, DNG with orientation tag.
- Video: **10-bit HEVC, Rec.2020, HLG / Log selectable**, 180° flip actually encoded. Audio included.
- Manual focus/exposure aids: focus peaking, zebra, grid, spirit level.

### Non-Goals
- Multi-device compatibility, backward compatibility (lower minSdk), CameraX use.
- Auto scene detection / AI correction / filters, cloud sync.
- OEM LOG profile bit-perfect replication. We use the documented/available GL Log OETF path instead.

## 3. Build / Toolchain (all latest, deprecated excluded)

| Component | Version |
|---|---|
| AGP | 9.2.1 (Kotlin **built-in** — `kotlin.android` plugin not used) |
| Gradle | 9.6.1 (wrapper) |
| Kotlin | 2.4.0 |
| Compose Compiler plugin | 2.4.0 (`org.jetbrains.kotlin.plugin.compose`) |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 |
| Build Tools | 36.0.0 (+37.0.0 installed) |
| JDK | 21 (aarch64 / Apple Silicon) |

- Kotlin DSL (`build.gradle.kts`) + Version Catalog (`gradle/libs.versions.toml`).
- Java/Kotlin toolchain 21. `kotlin { jvmToolchain(21) }`.
- From AGP 9 onward, Kotlin is built-in; do not apply separate `org.jetbrains.kotlin.android` plugin.
- compileSdk 37 required by latest AndroidX (lifecycle 2.11.0); decoupled from targetSdk 36 (Android 16).
- Dev machine: ARM Mac. App runs on device. Build via Android Studio or CLI (`./gradlew`).

## 4. Architecture

Unidirectional data flow. Camera2 handles hardware, GL renderer handles 180° transform, Compose handles UI.

```
CameraController (Camera2)
   ├─ opens rear logical camera, routes streams to telephoto physical lens
   ├─ builds CaptureRequest (manual focus/ISO/shutter/WB/EV, DynamicRangeProfile)
   ├─ output targets:
   │    ├─ SurfaceTexture → GlPipeline (shared preview + video encoder)
   │    ├─ ImageReader (HEIF YUV/JPEG)
   │    └─ ImageReader (RAW_SENSOR → DNG)
   │
GlPipeline (OpenGL ES / EGL)
   ├─ renders camera SurfaceTexture input as fullscreen quad (180° rotation matrix)
   ├─ 10-bit path: RGBA1010102 / FP16, Rec.2020
   ├─ Transfer OETF: HLG or Log curve (applied in shader)
   ├─ output A: screen preview Surface (SurfaceView)
   └─ output B: MediaCodec (HEVC) input Surface (on recording)
   │
CapturePipeline (photos)
   ├─ HEIF: YUV/RGBA → Bitmap → 180° rotate → HeifWriter encode
   └─ DNG: RAW_SENSOR → DngCreator.setOrientation(ROTATE_180) → file
   │
VideoRecorder
   ├─ MediaCodec HEVC (Main10) + AAC, MediaMuxer (MP4)
   ├─ color: Rec.2020 primaries, HLG or Log transfer, 10-bit
   └─ input: GlPipeline output B (already 180° flipped)
   │
Storage (MediaStore) — save to DCIM/Pictures, metadata
   │
UI (Compose) — preview + pro control overlay + settings
```

### Module/File Boundaries (each has single responsibility)
- `camera/CameraController.kt` — Camera2 session lifecycle, request building, capability detection.
- `camera/CameraSelector2.kt` — telephoto physical lens detection (maximum focal length) + manual override.
- `camera/CaptureCapabilities.kt` — query MANUAL_SENSOR/RAW/DynamicRange/focus range.
- `gl/GlPipeline.kt`, `gl/FlipRenderer.kt`, `gl/shaders/*` — EGL + 180° + OETF.
- `capture/HeifCapture.kt`, `capture/DngCapture.kt` — photo encoding.
- `video/VideoRecorder.kt`, `video/ColorProfiles.kt` — video encoding/color.
- `storage/MediaStoreWriter.kt` — storage.
- `ui/CameraScreen.kt`, `ui/controls/*`, `ui/overlays/*` — Compose.
- `ui/CameraViewModel.kt` — state management (focus/exposure/mode/format).
- `focus/FocusMapping.kt` — slider ↔ LENS_FOCUS_DISTANCE (fine-tuning near infinity) mapping.
- `MainActivity.kt`, permissions.

## 5. 180° Flip Details

- **Preview/video**: flip texture coordinates to `1-uv` in GL shader (= 180° rotation) and render. Single render pass outputs to both preview Surface and encoder Surface.
- **HEIF photo**: convert capture buffer to Bitmap, then `Matrix.postRotate(180)` for **actual pixel rotation**, then HEIF encode.
- **DNG (RAW)**: do not rotate Bayer pixels (breaks CFA); instead `DngCreator.setOrientation(ExifInterface.ORIENTATION_ROTATE_180)` to set orientation tag (RAW standard). Auto-corrected on rendering.

## 6. Color Pipeline (Video)

- Secure 10-bit stream via Camera2 `OutputConfiguration.setDynamicRangeProfile(HLG10)` (or supported profile).
- GL pipeline uses 10-bit surface (RGBA1010102), tagged Rec.2020 color space.
- Transfer selection:
  - **HLG**: standard HLG OETF. HDR playback compatible.
  - **Log**: flat Log-type curve (for grading). Applied in shader; container tagged Rec.2020.
- Encoder: `MediaCodec` HEVC `profile=HEVCProfileMain10` (HDR10/HLG capable), `MediaMuxer` MP4, `COLOR_STANDARD_BT2020` + matching transfer.
- Audio: AAC LC.

## 7. Manual Focus UX

- Slider → `LENS_FOCUS_DISTANCE` (diopters, 0=infinity ~ `LENS_INFO_MINIMUM_FOCUS_DISTANCE`).
- Afocal nature means near-infinity is critical → nonlinear mapping enhances resolution around infinity.
- Display current focus distance (m), depth-of-field hint.
- Aids: **focus peaking** (GL/shader edge-detect highlight), **zebra** (exposure clipping), magnify (1:1 punch-in).

## 8. UI (Compose)

- Fullscreen preview (flipped).
- Bottom shutter / mode (photo ↔ video) / gallery thumbnail.
- Collapsible pro panel: Focus / ISO / Shutter / WB / EV, auto ↔ manual toggle.
- Top indicators: format (HEIF+RAW), color (HLG/Log), camera ID, histogram (optional).
- Toggles: peaking, zebra, grid, spirit level, punch-in.
- Settings screen: camera ID override, save format, color profile, audio on/off.

## 9. Permissions / Storage

- `CAMERA`, `RECORD_AUDIO` (video). Request runtime permissions.
- Save via MediaStore to DCIM/Pictures (photos), DCIM (video). Scoped storage.
- Record EXIF/metadata (lens/focus/exposure).

## 10. Risks / Verification Needed (On-device)

1. Whether telephoto physical lens supports manual focus / RAW / 10-bit HDR all together — detect via capability, fallback/disable if unsupported.
2. Hardware constraints of physical lens + RAW + 10-bit stream combination — verify per stream configuration.
3. True vendor LOG not reproduced — approximate with GL Log curve (honestly labeled).
4. HEIF 10-bit encoding path — v1 uses HEIF 8-bit (pixel rotation) + DNG for data preservation; 10-bit HEIF in follow-up.
5. ✅ Toolchain installation (JDK 21 + SDK 36/37 + build-tools) → `assembleDebug`/`testDebugUnitTest` pass — compilation & unit test verification complete. Camera hardware behavior verification remains on-device.

## 11. Test Strategy

- Pure logic unit tests: rotation matrix, `FocusMapping` (slider ↔ diopters), camera selection (maximum focal length), DNG orientation value, color profile parameters.
- Hardware-dependent parts: on-device manual verification (visual check of capture result orientation/focus/color).

## 12. Milestones

1. Project scaffolding (Gradle/manifest/permissions/blank screen execution).
2. Camera2 preview + telephoto selection + GL 180° flip preview.
3. Manual focus/exposure control.
4. HEIF + DNG photo capture (180° handling).
5. 10-bit HEVC Rec.2020 HLG/Log video.
6. Peaking/zebra/settings/polish.
