# CLAUDE.md — Find X9 Ultra Teleconverter Camera

Project-level instructions for any agent working in this repo. Read this **first**, then
`docs/BACKLOG.md` (release status and deferred work) and `docs/ARCHITECTURE.md` (how it's built). This file overrides
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
| AGP | 9.3.0 | **Kotlin is built-in** — do NOT apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.10 | Compose compiler plugin version; AGP supplies Kotlin Android support |
| Gradle | 9.6.1 | wrapper |
| Compose BOM | 2026.06.01 | Material3 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 | compileSdk 37 required by lifecycle 2.11.0; decoupled from targetSdk 36 |
| JDK | 21 (aarch64) | Homebrew `openjdk@21` |
| heifwriter | 1.1.0 | latest STABLE (the earlier "no stable 1.1.0 exists" note was wrong) |

**JAVA_HOME for CLI builds** (the login shell does not export it):
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build / deploy / verify loop

```bash
# normal implementation gate
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Play-release gate (requires local signing credentials)
./gradlew :app:lintRelease :app:assembleRelease :app:bundleRelease

# device is over wireless ADB — IP/port change between sessions, ask the user for the current one
adb connect <device-ip>:<port>
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

On this multi-homed Mac, direct wireless ADB can return `No route to host` even when the phone is
reachable. In that case, proxy the current phone port to a temporary loopback port and connect ADB to
`127.0.0.1:<proxy-port>`. Wireless-debugging ports are session-specific; stop the proxy after use.

## Hard-won device facts (do not relearn these the hard way)

- **Camera selection: standalone id "4", NOT the logical multicamera.** The 70 mm tele is exposed
  both as physical `0:4` (sub-camera of logical `0`) and as **standalone id `4`**. Routing streams to
  the physical sub-camera via `setPhysicalCameraId()` **crashes the QTI HAL**
  (`ChiMulticameraBase::configureStreams` → `Broken pipe -32` / SIGSEGV). `CameraSelector2` picks the
  35 mm-equiv **closest to 70 mm** (NOT the longest lens — that's the 230 mm 10×), and **prefers
  `physicalId == null`** on ties. Opening standalone `4` works and permits RAW.
- **SDR/8-bit shipping session.** HLG10 preview + full-res JPEG + RAW together crash the HAL. The
  Camera2 stream and EGL config therefore stay SDR/8-bit (`tenBit = false`). HLG/Log files use HEVC
  Main10 container profiles, but v1 is not end-to-end 10-bit capture and must not be marketed as such.
- **RAW only on the standalone camera — in BOTH failure modes (extended 2026-07-14).** RAW routed
  through physical sub-camera routing crashes configure (`DataSpace override not allowed for format
  0x20`), AND a still with the RAW target on the plain LOGICAL camera errors the whole camera device
  ~5 s after the shot (`CAMERA_ERROR(3)`, no image ever arrives). Gated to standalone selections
  only (`sessionAttemptPlan` `!logicalMultiCamera`); DNG therefore exists only in TELE mode.
- **Stills on the LOGICAL camera are YUV, not HAL JPEG (2026-07-14).** gralloc rejects the ~42 MB
  JPEG blob allocation on the plain logical session (`SnapAlloc: ValidateDescriptor invalid` — the
  image never arrives and the shot wedges `pending`), at BOTH 4096×3072 and the logical array's own
  4080×3064. `StillSnapshot` repacks YUV_420_888→NV21 on the camera thread and JPEG-encodes lazily
  on the io thread; standalone cameras keep the proven HAL-JPEG path. A capture watchdog
  (`CAPTURE_WATCHDOG_MS`) fails any shot whose image never arrives so the shutter can never wedge.
- **Seamless zoom = the logical camera, PHOTO ONLY (2026-07-14).** Camera 0 (`logicalMultiCamera`,
  physIds 3/2/4/5) spans zoomRatio 0.6–20 with HAL-internal lens crossing — pinch never reopens.
  Lens picks are zoom presets; TELE pins standalone 4 (digital 1–10×) and OFF returns to logical at
  3×. Zoom ticks use the controller fast path (cached repeating builder, zoom keys only) — routing
  them through the full `startPreview` rebuild read as stutter. Pinch/zoom events are additionally
  COALESCED in the ViewModel (leading apply + 33 ms trailing flush of the newest value) — per-event
  application recomposed the whole tree at input rate (~120 Hz) and read as jank.
- **VIDEO stays on the STANDALONE lenses — the logical camera's EIS leaks its warp margin
  (2026-07-14).** With any video stabilization on (Standard AND Active), camera 0's stream carries
  an uncorrected EIS warp band (~6% of width) on one edge — in the PREVIEW and in the RECORDED
  FILE (device-verified frame extraction; displays as a rainbow-smear band at the bottom in
  portrait playback). `resolveNonTeleId`: photo=logical/seamless, video=matching standalone;
  `setVideoMode` remaps zoom between the unified main-relative scale and the lens-local scale so
  framing carries across the mode flip.
- **setRepeatingRequest STALLS this HAL's preview ~180 ms per swap (measured 2026-07-14) — live
  zoom is GL-rendered.** Swapping the repeating request (zoom tick, control change, AE-OFF manual
  values, HAL-AE alike) gaps the stream 170–250 ms; per-tick zoom submits made zoom read as ~5 fps
  regardless of input smoothing (the true root cause behind THREE rounds of "핀치 버벅" reports).
  Architecture: the preview renders the REQUESTED zoom instantly (FlipRenderer `zoomComp` =
  requested ÷ HAL-reported zoom, GL self-redraws the last frame when the camera is quiet); HAL
  submits are throttled to ≥200 ms and aimed 1.2× WIDE mid-gesture (zoom-out margin), landing on
  the exact value at gesture end. Encoder/analysis only ever see REAL camera frames (self-redraws
  are preview-only). Plus: in low light the app-side P loop trades exposure→ISO brightness-
  neutrally during gestures (ISO-headroom-bounded) so the base frame rate rises.
- **Compounding zoom inputs must base on the COALESCED pending value (2026-07-14).** Pinch factors,
  hardware-key steps, and the ease ticker all multiply "the current zoom" — but UI state lags the
  33 ms coalescing flush, so compounding against `_state` made zoom crawl between flushes then jump
  at the boundary (read as pinch jank twice before being root-caused). `currentZoomBase()` in the
  ViewModel is the one true base; reset `zoomPendingRatio` whenever anything outside the coalescer
  rewrites zoom (mode flip, lens preset, TC toggle).
- **The REC tally border must follow the panel's rounded corners.** A square full-screen border's
  corner segments fall OUTSIDE the visible display area and vanish. Read the radius from the
  WindowInsets RoundedCorner API and scale ×1.2 — the glass corner is a continuous-curvature
  squircle, and a circular arc at the nominal radius reads visibly tighter (user-compared on
  device).
- **Analysis readback is FBO-downsampled (2026-07-14).** The scopes/AE readback used to
  `glReadPixels` the FULL preview framebuffer (~33 MB at 4K) every 5th frame — a periodic GL-thread
  stall that read as preview/zoom stutter, and it metered peaking/zebra overlay pixels. It now
  re-draws the clean scene into a 256×192 FBO (~190 KB readback). The REC tally border follows the
  panel's physical rounded corners via the WindowInsets RoundedCorner API (a square border's
  corners fall outside the visible area and vanish).
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
  deviceOrientation(gravity)` (`captureRotationDegrees()`); HEIF pixel-rotates and DNG tags EXIF
  orientation. `camera/RotationMath.kt` holds this as pure, unit-tested functions. A deliberately held,
  lit portrait/landscape output check remains the final proof of visual uprightness.
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
  value every 40 ms (25 Hz) *while* a gesture continues (a debounce starved: continuous pinch reset the
  timer so zoom only landed on finger-up; the earlier 80 ms window quantized the hardware slide-zoom
  into visible steps). Keep the `applyScheduled`-flag trailing throttle. Persistence is separate: user
  changes schedule a 500 ms trailing debounced synchronous commit (`scheduleSettingsSave`).
- **Release output files verified on PMA110 (2026-07-10).** A serialized rapid double-shutter test
  produced exactly one valid DNG+HEIF pair (DNG 4080×3064, 16-bit). A 4K HLG clip was HEVC Main10
  3840×2160 at 30000/1001 with AAC 48 kHz stereo; Open Gate produced HEVC Main10 2560×1920 4:3 at
  30000/1001 with AAC. The release smoke test had no crash or ANR. **Saved-file uprightness in a
  deliberately held, lit portrait/landscape pose remains a residual field check** — see
  `docs/BACKLOG.md`.
- **Photo and video AUTO use different target-FPS policies.** A fixed `[30,30]` range blocks photo
  AE from extending exposure in low light, so photo AUTO uses `CameraCaps.autoFpsRange()` with the
  lowest available floor. Video AUTO must hold the selected recording cadence: without that pin, a
  29.97 selection produced a real 25 fps file in low light. `CameraController.pinAutoFps` is therefore
  enabled in video mode. App-side/manual exposure also pins the selected FPS.
- **Tap-to-focus uses `AF_MODE_AUTO`.** CONTINUOUS + a bare trigger just holds the (often wrong)
  current distance; a tapped point sets a metering region and forces a one-shot AUTO scan that LOCKS
  (`touchAfActive`, cleared on focus-mode change). AF reaches FOCUSED on device.
- **Aspect ratio is only 4:3 or 16:9.** The sensor is 4:3-native: `AspectRatio.W4_3` = full readout
  (no crop, the default + the no-crop sentinel), `W16_9` = its center crop. Full/1:1/portrait removed.
- **Video caps come from the device, not hardcodes.** `video/EncoderCaps.kt` scans `MediaCodecList`.
  Only **HEVC + AVC** are offered (both HW). **AV1 was removed** (the only AV1 encoder here is SW
  `c2.android.av1.encoder` — too slow/low-res to ship). **APV** (`VideoCodec.APV`, HW
  `c2.qti.apv.encoder`) is defined but **intentionally EXCLUDED**: Android's MediaMuxer (API 36) rejects
  APV in an MP4 container (device-verified — it errors the encoder mid-drain). Resolutions come from
  the selected camera's `StreamConfigurationMap`, with the shipping selector capped at 3840 pixels
  wide; PMA110 tops out at 4K UHD in the UI. Standard and NTSC drop-frame rates are gated against the
  selected size. High-speed 120 fps is excluded because its constrained session crashes this HAL.
- **Settings persist across launches** via `storage/SettingsStore.kt` (SharedPreferences, enums by
  name, defensive load). Gated by a "Remember Settings" toggle that **defaults ON**; saved on
  background, restored on launch (pushed to the engine pre-start). Fresh launch defaults to the 1×
  main lens with TELE off; separate default-on Setup toggles preserve the last lens selection and
  TELE mode when restoring saved settings.
- **LOG = GL O-Log2; the native log key is INERT for third-party Camera2 (settled 2026-07-09).**
  `com.oplus.log.video.mode` is advertised in the tele's request+session keys and the HAL ACCEPTS it
  ("applied" logs), but it changes NOTHING a third-party session can see: with the key set (as session
  parameter + on every request), the preview AND the recorded clip stay display-referred 709 — tested
  with both `TEMPLATE_PREVIEW` and `TEMPLATE_RECORD` repeating requests on device, judged in a lit
  scene. (Earlier "the file recorded as log" was the BT.2020 full-range container tag being misread by
  players as a washed look; an "applied" log line only means the HAL didn't reject the key.) So
  `ColorTransfer.LOG` bakes the official O-Log2 OETF in GL (white paper constants in `Shaders.kt`,
  uTransfer=2; 18 % grey → the 0.4868 anchor, LUT-accurate): the encoder gets the curve, the preview
  renders it flat, and **Gamma Display Assist** shows the normal display-referred image instead
  (assist = skip the forward curve; the file always gets it). The de-log shader (uTransfer=3, exact
  inverse incl. the toe root) and `vendorLogMode`/`setNativeLog` plumbing stay DORMANT for a future
  CameraUnit-authenticated scene-referred stream. NOTE: leaving `KEY_COLOR_TRANSFER` unset on a BT2020
  full-range HEVC format makes the QTI encoder tag the VUI **ST2084 (PQ)** — players then tone-map log
  footage as HDR. Tag a transfer explicitly, always.

- **Video stabilization = HAL OIS+EIS via the device's own path (verified 2026-07-07).** For VIDEO the
  shutter is fixed (e.g. 1/60 s), so per-frame MOTION BLUR is set by the shutter and only **OIS**
  (which moves the lens DURING the exposure) can cut it — app-side gyro EIS only warps whole frames
  and cannot de-blur. The HAL exposes the vendor int `com.oplus.video.stabilization.mode`
  (0x8119009e) alongside the standard key, and applies the right OIS/EIS profile for the active lens. The tele advertises standard `availableVideoStabilizationModes = [0,1,2]`
  (OFF/ON/**PREVIEW_STABILIZATION**) and both `videoStabilizationMode` + the vendor int are in its
  request+session keys. So we **no longer force video-stab OFF**: `VideoStabMode { OFF, STANDARD
  ("Standard"), ENHANCED ("Active") }` sets `CONTROL_VIDEO_STABILIZATION_MODE` on the repeating
  request (+ the vendor int mirror). **Device-verified: result metadata `ois=1`, `vstab=2` — OIS
  physically engaged at 1/30 s, preview + 4K recording fine.** App-side gyro EIS is **disabled**
  (`CameraEngine` seeds `gl.setEis(false, 0f, 0f)`); its sensor helper remains for level and capture
  orientation, but there is no user-facing `GYRO` mode. The Explorer-specific `com.oplus.ois.*` /
  `eisrealtime` tags remain gated — but the generic HAL video-stab is enough.
- **TC session type 0x80b4 is ACCEPTED by the HAL (device-verified 2026-07-14) — the CameraUnit
  bypass.** Passing the stock app's TC operation_mode (0x80b4, captured from CamX
  `configure_streams`; stock pairs it with sensor mode 48 = the 300 mm TC OIS profile) as the
  SessionConfiguration sessionType on the standalone 3× camera configures a FULL session
  (fallback=0, stills+RAW alive, HEIC/DNG/4K-HEVC all verified, ois=1/vstab=2, clean 0x0 return
  on TELE off) — no AUTH_CODE needed. Lint WrongConstant is suppressed on configureSession with
  justification. UNVERIFIED: whether the OIS profile actually differs at 300 mm (needs a physical
  shake A/B with the converter mounted); result metadata reads identically either way.
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
- **SettingsStore commits synchronously (`edit(commit = true)`), never apply().** Saves fire on user
  actions (a mode switch), and the very next gesture can be a Recents swipe-kill — apply()'s async
  disk write dies with the process and the change is silently lost ("last mode not remembered", hit
  twice). The prefs file is tiny; the synchronous write is a few ms.
- **`manager.openCamera()` can throw synchronously.** Opening from a background proc state (relaunch
  behind the keyguard / screen just woke) raises `CameraAccessException CAMERA_DISABLED` from the
  `openCamera` call itself, not the StateCallback — wrap it in `runCatching → onError` or it crashes.

## Architecture (one-liner per module; full map in docs/ARCHITECTURE.md)

```
MainActivity → CameraViewModel(CameraUiState/CameraActions) → CameraEngine (facade)
CameraEngine ├─ CameraSelector2  pick tele (closest-to-70mm, standalone; pickBest pure+tested)
             ├─ CameraController Camera2 session, fallback ladder, capture, 3A/tap-AF
             ├─ RotationMath     pure preview/capture/EXIF rotation (unit-tested)
             ├─ GlPipeline       GL thread: afocal 180° + OETF + scopes + AE luma readback
             │    └─ FlipRenderer / EglCore / Shaders
             ├─ GyroEis          gravity roll + held-device orientation (GL shake warp disabled)
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

- `docs/BACKLOG.md` — release status, manual Play steps, residual checks, and deferred work.
- `docs/ARCHITECTURE.md` — module map, threading model, data flow, gotchas in depth.
- `docs/superpowers/specs/2026-07-01-...md` — original design doc (intent; some details superseded
  by the as-built notes above).
- `.context/reviews/` — architecture/code/perf/security review notes (findings already addressed).
