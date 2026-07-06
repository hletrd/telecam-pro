# CLAUDE.md — Find X9 Ultra Teleconverter Camera

Project-level instructions for any agent working in this repo. Read this **first**, then
`docs/BACKLOG.md` (what's left) and `docs/ARCHITECTURE.md` (how it's built). This file overrides
default behavior; user/global `~/.claude/CLAUDE.md` still applies on top (git rules, latest-versions,
destructive-action safety, look-up-before-answering).

## What this is

A **single-device** professional camera app for the **OPPO Find X9 Ultra (model PMA110, Android 16 /
API 36)**. Its purpose is photography and video through a **Hasselblad "Earth Explorer" afocal 300 mm
teleconverter** that clamps onto the phone's **3× / 70 mm periscope** lens (turning ~70 mm into
~300 mm, ≈4.286× magnification).

Two consequences of the afocal converter drive the whole design:
1. **The image arrives rotated 180°** (afocal telescope, no erecting prism). Preview AND saved
   results must be corrected. Vertical + horizontal flip = 180° rotation (parity-preserving, NOT a
   mirror).
2. **Exit light is ~collimated**, so the phone lens focuses **near infinity** → manual focus, with a
   nonlinear slider that gives resolution around ∞, is essential.

Goal: **ship on Google Play.** Treat everything as production-bound — no throwaway hacks, no
deprecated APIs, latest stable everything.

## Non-negotiable constraints

- **Target device only.** No backward compat, no `minSdk` lowering, no CameraX. We use **Camera2**
  directly for physical-lens routing, `LENS_FOCUS_DISTANCE`, manual sensor, RAW/DNG, 10-bit HDR.
- **Latest toolchain, no deprecated APIs.** See versions below; bump when newer stable ships.
- **Everything user-facing in English.** (Historical commit messages are Korean; do not rewrite
  history — that's a destructive op requiring explicit sign-off.)

## Toolchain (all pinned in `gradle/libs.versions.toml`)

| Component | Version | Notes |
|---|---|---|
| AGP | 9.2.0 | **Kotlin is built-in** — do NOT apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.3.10 | matches AGP 9.2 built-in; Compose compiler plugin same version |
| Gradle | 9.6.1 | wrapper |
| Compose BOM | 2026.06.00 | Material3 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 | compileSdk 37 required by lifecycle 2.11.0; decoupled from targetSdk 36 |
| JDK | 21 (aarch64) | Homebrew `openjdk@21` |
| heifwriter | 1.2.0-alpha01 | no stable 1.1.0 exists; latest per policy |

**JAVA_HOME for CLI builds** (the login shell does not export it):
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build / deploy / verify loop

```bash
# build + unit tests (both must pass before you claim done)
./gradlew :app:assembleDebug :app:testDebugUnitTest

# device is over wireless ADB — IP/port change between sessions, ask the user for the current one
adb connect 172.30.50.127:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hletrd.findx9tele/.MainActivity

# verify: no crash + a real preview. The device may be asleep/locked — wake first, and note that
# a screenshot of a flat-lying phone shows a dark textured surface, NOT a bug.
adb shell input keyevent KEYCODE_WAKEUP
adb logcat -d | grep -E "CameraController|AndroidRuntime|Session configured"
adb exec-out screencap -p > /tmp/shot.png
```

`pm grant` for runtime permissions **fails on ColorOS** (`GRANT_RUNTIME_PERMISSIONS not allowed`) —
the app requests CAMERA/RECORD_AUDIO itself at runtime; grant on the device once.

## Hard-won device facts (do not relearn these the hard way)

- **Camera selection: standalone id "4", NOT the logical multicamera.** The 70 mm tele is exposed
  both as physical `0:4` (sub-camera of logical `0`) and as **standalone id `4`**. Routing streams to
  the physical sub-camera via `setPhysicalCameraId()` **crashes the QTI HAL**
  (`ChiMulticameraBase::configureStreams` → `Broken pipe -32` / SIGSEGV). `CameraSelector2` picks the
  35 mm-equiv **closest to 70 mm** (NOT the longest lens — that's the 230 mm 10×), and **prefers
  `physicalId == null`** on ties. Opening standalone `4` works and permits RAW.
- **SDR preview session.** HLG10 10-bit preview + full-res JPEG + RAW together crash the HAL. The
  live session is SDR (`tenBit = false`); video still tags HLG/Log in the **encoder**. 10-bit HDR
  *preview* is deferred (see backlog).
- **RAW only on the standalone camera.** RAW routed through physical sub-camera routing crashes
  (`DataSpace override not allowed for format 0x20`). Gated to `selection.physicalId == null`.
- **Session fallback ladder** in `CameraController.configureSession`: attempt 0 full → 1 drop RAW → 2
  drop HLG → 3 preview-only. Keep it; different capability combos fail on this HAL.
- **Preview host is a `TextureView`, not `SurfaceView`.** A SurfaceView's surface sits behind the
  window and was occluded by the opaque Compose background → black viewfinder. TextureView composites
  in the view hierarchy.
- **Lifecycle races crash the camera.** Launching behind the keyguard delivers `onStop` mid-session-
  config → device disconnects → `createCaptureRequest` throws `CAMERA_DISCONNECTED`. Guarded by a
  `closed` flag + `runCatching` in `CameraController`, and a `paused` flag in `CameraEngine` (never
  open the camera while backgrounded).
- **Sensor orientation = 90; activity is portrait-locked; preview verified upright on device.** The
  camera SurfaceTexture transform *already* rotates the sampled image by the sensor orientation, so
  the GL renderer adds **only the afocal 180°** in tele mode (`CameraEngine.previewRotationDegrees()`
  returns 180 in tele, 0 otherwise) — NOT `±sensorOrientation` (both 270° and 90° read 90° off on
  device). It still passes `sensorOrientation` to the renderer purely to pick the preview **aspect**
  (the ~90° swaps displayed W/H). Captures rotate raw pixels by `sensorOrientation + afocal180 +
  deviceOrientation(gravity)` (`captureRotationDegrees()`), so stills save upright in any hold; HEIF
  pixel-rotates, DNG tags EXIF orientation. `camera/RotationMath.kt` holds this as pure, unit-tested
  functions.
- **Device orientation only updates while the phone is HELD.** `GyroEis.currentDeviceOrientation()`
  derives 0/90/180/270 from gravity, but when the phone is **flat** the in-plane gravity is ~0 and
  `atan2(x,y)` is noise — so it updates the discrete value only when `hypot(x,y) > FLAT_GRAVITY_
  THRESHOLD` (~½ g) and otherwise holds the last confident value. Found via output-file check: a
  flat-on-desk DNG had wrongly tagged `ORIENTATION_NORMAL` instead of 270°.
- **Output-file capture verified on device (2026-07-03).** Pulled + inspected real files: HEIF =
  HEVC 4096×3072 (4:3 full sensor); DNG = valid 16-bit RAW (`OPPO PMA110`, ISO/exposure EXIF);
  video = HEVC 4K (3840×2160) ~29.97 fps drop-frame, ~172 Mbps, AAC audio, playable. Unique
  filenames confirmed (monotonic counter). **Capture upright-ness in a held portrait/landscape pose
  is still unverified** (needs a lit, deliberately-held shot) — see `docs/BACKLOG.md`.
- **Low-light AE was pinned at 1/30s.** A fixed `[30,30]` target-fps range caps exposure at 1/30s, so
  AE can't brighten dark scenes. Auto exposure uses `CameraCaps.autoFpsRange()` (lowest floor at the
  target max) so AE can slow the preview for a brighter live view. Manual exposure still pins fps.
- **Tap-to-focus uses `AF_MODE_AUTO`.** CONTINUOUS + a bare trigger just holds the (often wrong)
  current distance; a tapped point sets a metering region and forces a one-shot AUTO scan that LOCKS
  (`touchAfActive`, cleared on focus-mode change). AF reaches FOCUSED on device.
- **Aspect ratio is only 4:3 or 16:9.** The sensor is 4:3-native: `AspectRatio.W4_3` = full readout
  (no crop, the default + the no-crop sentinel), `W16_9` = its center crop. Full/1:1/portrait removed.
- **Video caps come from the device, not hardcodes.** `video/EncoderCaps.kt` scans `MediaCodecList`
  (HW AVC/HEVC + Dolby-Vision present; AV1 is **software-only** → label "slow/SW", gate ≤4K).
  `VideoFrameRate` gates drop-frame/high-speed fps per resolution (8K≤30; 120 only where a high-speed
  config exists). Resolutions come from the selected camera's `StreamConfigurationMap`.
- **Settings persist across launches** via `storage/SettingsStore.kt` (SharedPreferences, enums by
  name, defensive load). Gated by a "Remember Settings" toggle that **defaults ON**; saved on
  background, restored on launch (pushed to the engine pre-start).
- **HAL-native log IS reachable via the stock key `com.oplus.log.video.mode` (verified 2026-07-06).**
  Decompiling `OplusCamera.apk` showed the stock O-Log path is the OCS SDK ConfigureKey
  `KEY_CONFIGURE_LOG_VIDEO_MODE` → vendor tag `com.oplus.log.video.mode` (Integer, a **session** key).
  That tag IS in the tele's `availableRequestKeys`+`availableSessionKeys` (dumpsys), so raw Camera2
  can set it — do so as a **session parameter** (from `TEMPLATE_RECORD`) AND on every request, fully
  guarded. Device-verified: value `1` engages a genuine scene-referred log stream (flat, mean luma
  ~½ of SDR, GL curve off); `1`≡`2` (on/off). `com.oplus.movie.log.enable` (the byte gate) is NOT
  exposed. CAVEAT: the HAL log is not a clean round-trip for OPPO's published O-Log2/O-Log-gen1 LUTs
  (scene-referred, un-white-balanced, warm cast) — for a LUT-accurate deliverable use the GL O-Log2
  path below. Exposed as Pro→Advanced→"Native Log" (`VendorLogMode`, not persisted). Full analysis:
  `docs/reverse-engineering/oplus-log-video-analysis.md`.
- **LOG = official O-Log2, applied in GL; HAL-native log is vendor-gated (verified 2026-07-06).**
  The `ColorTransfer.LOG` path bakes OPPO's published O-Log2 OETF (white paper EN v1:
  `P = 0.08550479·log₂(R+0.00964052)+0.69336945`, parabolic toe below R=0.006, O-Gamut = BT.2020/D65
  full-range) after a γ2.2 linearization of the display-referred SDR stream + 709→2020 matrix. 18 %
  grey lands on the official 0.4868 anchor, so OPPO's public O-Log2 LUTs restore it. This GL path is
  the LUT-accurate deliverable, but it can only re-map the display-referred SDR output (no above-white
  headroom). For a true scene-referred stream use the HAL `com.oplus.log.video.mode` key (see the
  entry just above — it IS exposed; only `movie.log.enable` is gated). Also: leaving
  `KEY_COLOR_TRANSFER` unset on a BT2020 full-range HEVC format makes the QTI encoder tag the VUI
  **ST2084 (PQ)** — players then tone-map log footage as HDR. Tag a transfer explicitly, always.
- **The teleconverter's "auto steady" is a HAL side-effect, not an API.** Reverse engineering (see
  `docs/reverse-engineering/`) confirmed the stock app sets no Explorer-specific OIS/EIS tag — the
  vendor tags (`com.oplus.ois.*`, `org.quic.camera.eisrealtime`, `explorer.chip.state`) exist in the
  HAL but are gated for third parties. So we do **client-side gyro EIS** scaled to the effective
  300 mm focal length, HAL video stabilization OFF. `explorer.chip.state` is a damage/safety check,
  not a stabilization control.

## Architecture (one-liner per module; full map in docs/ARCHITECTURE.md)

```
MainActivity → CameraViewModel(CameraUiState/CameraActions) → CameraEngine (facade)
CameraEngine ├─ CameraSelector2  pick tele (closest-to-70mm, standalone; pickBest pure+tested)
             ├─ CameraController Camera2 session, fallback ladder, capture, 3A/tap-AF
             ├─ RotationMath     pure preview/capture/EXIF rotation (unit-tested)
             ├─ GlPipeline       GL thread: afocal 180° + EIS + OETF + scopes
             │    └─ FlipRenderer / EglCore / Shaders
             ├─ GyroEis          gyro shake + gravity roll + device orientation
             ├─ capture/HeifCapture (pixel-rotate) + DngCapture (EXIF orient)
             ├─ video/VideoRecorder (HEVC/AVC/AV1, HLG/Log) + EncoderCaps + ColorProfiles
             └─ storage/MediaStoreWriter (scoped, IS_PENDING) + SettingsStore (persist)
UI: CameraScreen (Pixel-style) + controls/{ManualDials,ProSheet,ProControls} + overlays/*
```

Data flow is unidirectional: Compose UI is stateless off `CameraUiState`; every interaction goes
through `CameraActions` → ViewModel → Engine. Image work runs off the UI thread on the components'
own threads/executors.

## Working conventions

- **Match the surrounding code.** These files carry dense "why" comments documenting HAL quirks —
  keep that density when you touch them. Don't delete a comment that explains a workaround.
- **Verify on device before claiming a camera/GL/orientation fix is done.** Compilation ≠ correct
  pixels. Hardware behavior (orientation sign, EIS axis, exposure, color) needs a real screenshot or
  a pulled capture. If the device is unreachable, say so and mark the item pending.
- **Git** (from global rules): fine-grained commits, one concern each; `-S` GPG sign; **no
  Co-Authored-By**; Conventional Commits `type(scope): <gitmoji> desc`; `git pull --rebase` before
  push; commit+push after each verified iteration.
- **Hot paths** (edited most, touch carefully): `camera/CameraEngine.kt`, `ui/CameraScreen.kt`,
  `ui/controls/ProControls.kt`.

## Pointers

- `docs/BACKLOG.md` — prioritized remaining work + known-unverified items (READ THIS SECOND).
- `docs/ARCHITECTURE.md` — module map, threading model, data flow, gotchas in depth.
- `docs/reverse-engineering/` — device camera map, vendor-tag catalog, stock-app analysis.
- `docs/superpowers/specs/2026-07-01-...md` — original design doc (intent; some details superseded
  by the as-built notes above).
- `.context/reviews/` — architecture/code/perf/security review notes (findings already addressed).
