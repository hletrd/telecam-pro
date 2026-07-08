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
- **UI/UX reference: Sony Alpha / Xperia Pro.** Keep the viewfinder quiet. Use Fn, My Menu, MR banks,
  PASM-style exposure, compact OSD, peaking, zebra, histogram, waveform, and review zoom. Do not add
  tutorial banners, warning chips, coach marks, marketing copy, or helper overlays unless the user
  asks. Important states belong in the OSD, Fn, or menu rows. See `docs/UX_POLICY.md`.

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
  The scopes (histogram/waveform) DO rotate, via a `rotateLayout` modifier that reserves the ROTATED
  bounding box (swaps W/H at 90/270) so a stack of rotated scopes doesn't overlap — the reason plain
  `rotate()` couldn't be used and they were screen-fixed before.
- **PASM+ISO exposure: only video-P (and flash-metered P) uses the HAL AE; everything else is
  app-side.** `ExposureMode { PROGRAM, SHUTTER, ISO, MANUAL }` (no aperture-priority — fixed tele
  aperture). Camera2 has no shutter-/ISO-priority AND no min-shutter hint, so `camera/AutoExposure.kt`
  closes the loop off the GL preview luma: SHUTTER drives ISO, ISO drives exposure time, and **photo
  PROGRAM runs a real program line** (`driveProgram`): shutter held at the handheld 1/(effective focal)
  rule (≈1/300 s with the TC), ISO carries exposure, shutter slides (≤1 stop/tick,
  brightness-neutral) only when ISO clamps — down to a 1/10 s ceiling in the dark, faster at base ISO
  in the bright. `ManualControls.programAppSide` (recomputed on mode/flash/exposure-mode changes) keeps
  video-P and AUTO/ON-flash-P on the HAL AE (flash metering needs AE ON). The GL luma readback is
  force-enabled whenever the app-side loop needs it (`gl.setAeMetering`). `autoExposure` is a derived
  `val` (`== PROGRAM && !programAppSide`); the capture path treats all app-side modes identically
  (AE off, sensor values set) because the loop keeps `iso`/`exposureTimeNs` fresh.
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
- **Video caps come from the device, not hardcodes.** `video/EncoderCaps.kt` scans `MediaCodecList`.
  Only **HEVC + AVC** are offered (both HW). **AV1 was removed** (the only AV1 encoder here is SW
  `c2.android.av1.encoder` — too slow/low-res to ship). **APV** (`VideoCodec.APV`, HW
  `c2.qti.apv.encoder`) is defined but **intentionally EXCLUDED**: Android's MediaMuxer (API 36) rejects
  APV in an MP4 container (device-verified — it errors the encoder mid-drain).
  `VideoFrameRate` gates drop-frame/high-speed fps per resolution (8K≤30; 120 only where a high-speed
  config exists). Resolutions come from the selected camera's `StreamConfigurationMap`.
- **Settings persist across launches** via `storage/SettingsStore.kt` (SharedPreferences, enums by
  name, defensive load). Gated by a "Remember Settings" toggle that **defaults ON**; saved on
  background, restored on launch (pushed to the engine pre-start).
- **LOG = GL O-Log2, and it now renders in the live PREVIEW (behavior corrected 2026-07-08).**
  `ColorTransfer.LOG` bakes OPPO's published O-Log2 OETF in the GL stage (white paper EN v1:
  `P = 0.08550479·log₂(R+0.00964052)+0.69336945`, parabolic toe below R=0.006, O-Gamut = BT.2020/D65
  full-range) after a γ2.2 linearization of the display-referred SDR stream + 709→2020 matrix; 18 %
  grey lands on the official 0.4868 anchor, so OPPO's public O-Log2 LUTs restore it. **The preview draw
  now applies the LOG curve too** (`previewTransfer = if (transfer == LOG) LOG else null` in
  `GlPipeline`) — previously the preview was hardcoded SDR (`null`) and ONLY the encoder got the curve,
  so LOG baked into the recorded file but the live preview never looked flat (user-reported; fixed and
  device-confirmed). HLG/SDR preview stays natural (an HLG curve on the SDR preview surface just looks
  washed — HDR is monitored on an HDR display, not here).
- **HAL-native log is reachable but NO LONGER wired to LOG.** The vendor tag `com.oplus.log.video.mode`
  (Integer **session** key) IS in the tele's `availableRequestKeys`+`availableSessionKeys` and
  device-verified (value `1`≡`2` engages a genuine scene-referred stream, flat, mean luma ~½ SDR);
  `com.oplus.movie.log.enable` (the byte gate) is NOT exposed. But it only flattens the RECORD stream,
  not the preview, so `ColorTransfer.LOG` drives GL O-Log2 instead (above) and `vendorLogMode` is kept
  **OFF**. The separate "Native Log" toggle was removed; the key infra stays dormant for a future
  scene-referred path. NOTE: leaving `KEY_COLOR_TRANSFER` unset on a BT2020 full-range HEVC format makes
  the QTI encoder tag the VUI **ST2084 (PQ)** — players then tone-map log footage as HDR. Tag a transfer
  explicitly, always.
- **Video stabilization = HAL OIS+EIS via the device's own path (verified 2026-07-07).** For VIDEO the
  shutter is fixed (e.g. 1/60 s), so per-frame MOTION BLUR is set by the shutter and only **OIS**
  (which moves the lens DURING the exposure) can cut it — app-side gyro EIS only warps whole frames
  and cannot de-blur. The HAL exposes the vendor int `com.oplus.video.stabilization.mode`
  (0x8119009e) alongside the standard key, and applies the right OIS/EIS profile for the active lens. The tele advertises standard `availableVideoStabilizationModes = [0,1,2]`
  (OFF/ON/**PREVIEW_STABILIZATION**) and both `videoStabilizationMode` + the vendor int are in its
  request+session keys. So we **no longer force video-stab OFF**: `VideoStabMode { OFF, STANDARD
  ("OIS Std"), ENHANCED ("OIS Enhanced") }` sets `CONTROL_VIDEO_STABILIZATION_MODE` on the repeating
  request (+ the vendor int mirror). **Device-verified: result metadata `ois=1`, `vstab=2` — OIS
  physically engaged at 1/30 s, preview + 4K recording fine.** App-side gyro EIS was **removed
  entirely** (it warps whole frames and can't de-blur; the HAL path is strictly better) — there is no
  `GYRO` mode. The Explorer-specific `com.oplus.ois.*` / `eisrealtime` tags remain gated — but the
  generic HAL video-stab is enough.
- **300 mm teleconverter OIS integration depends on OPPO CameraUnit availability (2026-07-08).**
  The 4.3× teleconverter stabilization profile appears to use CameraUnit extension parameters that
  are not exposed through raw Camera2 request/result keys. The app applies the public Camera2 overlap
  (`com.oplus.camera.mode=40`, `com.oplus.original.zoomRatio` 4.286×) and runs `OcsProbe` in debug
  builds as a read-only CameraUnit availability check. Enabling the full CameraUnit path requires the
  official OPPO developer registration flow and an AUTH_CODE; see `docs/BACKLOG.md` item #4 for the
  checklist and SDK notes.
- **The camera-control button: slides arrive as STANDARD `KEYCODE_ZOOM_IN`/`OUT` (live-verified
  2026-07-09).** Full mechanical press = standard `KEYCODE_CAMERA` (→ shutter, `onKeyDown`). The
  capacitive slide is re-emitted to the FOCUSED app as **KEYCODE_ZOOM_IN (168) / KEYCODE_ZOOM_OUT
  (169), repeating ~20 Hz** while the finger slides — a one-off earlier capture showed OPPO codes
  767/769/782 instead (config-dependent; both families are handled, `KEYCODE_FOCUS` too). The
  **light-press (half-press) is NOT delivered at all** in the current configuration (nothing reaches
  `dispatchKeyEvent`; likely stock-camera-only) — the FOCUS/782 handlers stay armed if it ever
  arrives. The discrete ~20 Hz repeats stutter if applied 1:1: `onHardwareZoomStep` moves a TARGET and
  a ~30 Hz ticker glides `zoomRatio` toward it (log-space exponential), like a powered zoom rocker.
  Full/half actions are a configurable `HardwareKeyAction` system (reassignable in Setup, persisted).
  **`adb input keyevent` injection does NOT reach the focused app** — only a physical press; verify
  button behavior on-device.
- **The horizon level holds its angle when the phone points steeply up/down.** Roll comes from
  `atan2(gravity.x, gravity.y)`; near-vertical the in-plane x/y → 0 and it's pure noise, so the level
  spun. `GyroEis` only updates the roll when `hypot(x,y) > LEVEL_GRAVITY_THRESHOLD` (~2.5), else holds
  the last confident angle (same idea as the discrete-orientation `FLAT_GRAVITY_THRESHOLD` guard).
- **GlPipeline drops anything posted before `start()` — re-seed GL state in the start callback.**
  `GlPipeline.post` is `handler?.post`, silently a no-op until the GL thread exists. Any GL state set
  during settings-restore (LOG transfer, AE metering, gamma assist) MUST be re-applied inside the
  `gl.start` callback in `CameraEngine` (it is — extend that block when adding GL state). Symptom when
  missed: "works only after the first recording pushes it" (the LOG-preview bug).
- **Exactly one owner of the mic.** The Sony-style standby audio meter is a levels-only `AudioRecord`
  tap that runs while video is ARMED but not rolling; `startRecording` stops it (flag + short join)
  BEFORE `VideoRecorder` opens its own AudioRecord, and it stays off outside video mode / while
  backgrounded. Never add a second concurrent AudioRecord.
- **`Bitmap.compress` strips ALL metadata — stamp JPEG EXIF back after writing.** `writeJpegExif`
  (androidx.exifinterface, "rw" pending FD, before publish) re-adds ISO / exposure / 35mm focal /
  make/model from the controller's latest capture result. HEIFs are currently NOT stamped (heifwriter
  has no EXIF API; androidx ExifInterface can't write HEIC). The review card's exposure line simply
  drops out for files without EXIF.
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
             ├─ video/VideoRecorder (HEVC/AVC, HLG/O-Log2/SDR) + EncoderCaps + ColorProfiles
             └─ storage/MediaStoreWriter (scoped, IS_PENDING) + SettingsStore (persist)
UI: CameraScreen (Sony-style pro surface) + controls/{ManualDials,ProSheet,ProControls} + overlays/*
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
