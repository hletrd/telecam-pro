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
| AGP | 9.2.1 | **Kotlin is built-in** — do NOT apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.0 | Compose compiler plugin version; AGP supplies Kotlin Android support |
| Gradle | 9.6.1 | wrapper |
| Compose BOM | 2026.06.01 | Material3 |
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
# debug installs as me.hletrd.telecampro.debug (applicationIdSuffix) — a SEPARATE app from the
# release me.hletrd.telecampro, so runtime permissions must be granted once per package.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n me.hletrd.telecampro.debug/com.hletrd.findx9tele.MainActivity

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
- **On-screen glyph counter-rotation is `+deviceOrientation`, NOT `−` (device-confirmed 2026-07-08).**
  `atan2(x,y)` on gravity yields **dev=90 for a COUNTER-clockwise (left) landscape and dev=270 for a
  clockwise (right) landscape** — the opposite of the naive "90=right" assumption. So the counter-
  rotation that keeps glyphs upright is `+dev`. A `−dev` sign leaves BOTH landscapes 180° off, which is
  invisible on near-symmetric icons and only shows once text (mode labels, the zoom `×`) rotates. Only
  compact glyphs + short labels rotate; wide dial pills / the OSD row stay screen-fixed (rotating a wide
  box 90° pokes it out of its layout slot — `Modifier.rotate` is a draw transform, not a re-layout).
- **PASM+ISO exposure = HAL AE only in PROGRAM; S/ISO/M are app-side.** `ExposureMode { PROGRAM,
  SHUTTER, ISO, MANUAL }` (no aperture-priority — fixed tele aperture). Camera2 has no shutter-/ISO-
  priority, so `camera/AutoExposure.kt` closes the loop off the GL preview luma: SHUTTER drives ISO,
  ISO drives exposure time, toward an EV-shifted mid-grey (log-domain P-control, deadband, unit-tested).
  The GL luma readback is force-enabled in S/ISO via `gl.setAeMetering(true)` independent of the scope
  toggles. `autoExposure` is now a **derived** `val` (`== PROGRAM`); the capture path treats S/ISO/M
  identically (AE off, sensor values set) because the controller keeps `iso`/`exposureTimeNs` fresh.
- **Controls apply is a THROTTLE, not a debounce.** `CameraViewModel.updateControls` applies the newest
  value at ~12 Hz *while* a gesture continues (a debounce starved: continuous pinch reset the timer so
  zoom only landed on finger-up). Keep the `applyScheduled`-flag trailing throttle.
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
- **HAL-native log IS reachable via `com.oplus.log.video.mode` (verified 2026-07-06).**
  The vendor tag `com.oplus.log.video.mode` (Integer, a **session** key) is in the tele's
  `availableRequestKeys`+`availableSessionKeys` (dumpsys), so raw Camera2 can set it — do so as a
  **session parameter** (from `TEMPLATE_RECORD`) AND on every request, fully guarded. Device-verified:
  value `1` engages a genuine scene-referred log stream (flat, mean luma ~½ of SDR, GL curve off);
  `1`≡`2` (on/off). `com.oplus.movie.log.enable` (the byte gate) is NOT exposed. CAVEAT: the HAL log
  is not a clean round-trip for the published O-Log2/O-Log-gen1 LUTs (scene-referred,
  un-white-balanced, warm cast) — for a LUT-accurate deliverable use the GL O-Log2 path below.
  Exposed as Pro→Advanced→"Native Log" (`VendorLogMode`, not persisted).
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
- **Video stabilization = HAL OIS+EIS via the device's own path (verified 2026-07-07).** For VIDEO the
  shutter is fixed (e.g. 1/60 s), so per-frame MOTION BLUR is set by the shutter and only **OIS**
  (which moves the lens DURING the exposure) can cut it — app-side gyro EIS only warps whole frames
  and cannot de-blur. The HAL exposes the vendor int `com.oplus.video.stabilization.mode`
  (0x8119009e) alongside the standard key, and applies the right OIS/EIS profile for the active lens. The tele advertises standard `availableVideoStabilizationModes = [0,1,2]`
  (OFF/ON/**PREVIEW_STABILIZATION**) and both `videoStabilizationMode` + the vendor int are in its
  request+session keys. So we **no longer force video-stab OFF**: `VideoStabMode { OFF/GYRO/STANDARD/
  ENHANCED }` (default ENHANCED = PREVIEW_STABILIZATION) sets `CONTROL_VIDEO_STABILIZATION_MODE` on the
  repeating request (+ the vendor int mirror). **Device-verified: result metadata `ois=1`, `vstab=2`
  — OIS physically engaged at 1/30 s, preview + 4K recording fine.** App-side gyro EIS is suppressed
  while a HAL mode runs (no double-warp); it stays as the `GYRO` option. The Explorer-specific
  `com.oplus.ois.*` / `eisrealtime` tags remain gated — but the generic HAL video-stab is enough.
- **`manager.openCamera()` can throw synchronously.** Opening from a background proc state (relaunch
  behind the keyguard / screen just woke) raises `CameraAccessException CAMERA_DISABLED` from the
  `openCamera` call itself, not the StateCallback — wrap it in `runCatching → onError` or it crashes.

## Architecture (one-liner per module; full map in docs/ARCHITECTURE.md)

```
MainActivity → CameraViewModel(CameraUiState/CameraActions) → CameraEngine (facade)
CameraEngine ├─ CameraSelector2  pick tele (closest-to-70mm, standalone; pickBest pure+tested)
             ├─ CameraController Camera2 session, fallback ladder, capture, 3A/tap-AF
             ├─ RotationMath     pure preview/capture/EXIF rotation (unit-tested)
             ├─ GlPipeline       GL thread: afocal 180° + EIS + OETF + scopes + AE luma readback
             │    └─ FlipRenderer / EglCore / Shaders
             ├─ GyroEis          gyro shake + gravity roll + device orientation
             ├─ AutoExposure     app-side S/ISO-priority AE loop (meters GL luma; pure+tested)
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
- `docs/superpowers/specs/2026-07-01-...md` — original design doc (intent; some details superseded
  by the as-built notes above).
- `.context/reviews/` — architecture/code/perf/security review notes (findings already addressed).
