# Backlog & Status — Find X9 Ultra Teleconverter Camera

Living handoff document. Read after `CLAUDE.md`. Goal: **Google Play release.** Status as of
2026-07-02.

Legend: ✅ done & verified · 🟡 done in code, **unverified on device** · 🔴 not started · ⏸ deferred

---

## 0. Immediate — needs a physical device in hand

The device is on **wireless ADB** (IP/port change per session — ask the user for the current
`adb connect` target). These items are implemented and compile, but their correctness is a matter of
**pixels on the real phone through the actual teleconverter**, which only the user can currently see.
Verify each, then flip 🟡 → ✅ (or open a fix).

- 🟡 **Preview rotation sign.** Preview now uses `-sensorOrientation` (+ afocal 180° in tele mode) →
  total 90° in tele mode. User reported the previous `+sensorOrientation` (270°) was 90° CCW off; the
  negation is the reasoned fix but the **final upright result is not yet visually confirmed**. If it's
  now upside-down, the afocal 180° term is wrong; if still 90° off, revisit the `FlipRenderer`
  texcoord/aspect coupling. Source: `CameraEngine.previewRotationDegrees()`.
- 🟡 **Capture orientation (stills).** HEIF pixel-rotates and DNG sets the EXIF orientation tag from
  `captureRotationDegrees()` = `sensorOrientation + afocal180 + deviceOrientation(gravity)`. **Pull a
  real HEIC and DNG and confirm both are upright** in portrait AND landscape holds. The GL(texcoord)
  vs bitmap(pixel) sign asymmetry is intentional — verify it empirically, don't assume.
  `adb pull` the file from `/sdcard/DCIM` or `/sdcard/Pictures` and inspect.
- 🟡 **Exposure default.** Now boots in **auto exposure** (was manual ISO400/1/125s → very dark
  through the tele). Confirm the preview is well-exposed on launch and that switching to manual still
  works. Source: `ManualControls(autoExposure = true)`.
- 🟡 **300 mm EIS actually stabilizes.** Client gyro EIS is wired and scaled by
  `TELECONVERTER_MAGNIFICATION` (300/70 ≈ 4.286×), HAL stabilization OFF. But `GyroEis` itself flags
  that the **axis mapping and sign need on-device tuning** (a wrong sign amplifies shake instead of
  cancelling it). Hand-hold test at 300 mm: does the preview steady or jitter worse? Tune
  `GlPipeline.drawFrame` shift signs / `GyroEis` axis order accordingly.
- 🟡 **Tap-to-focus mapping.** `CameraEngine.setTapPoint` inverts `previewRotationDegrees()` to map a
  view tap → sensor coords, but ignores EIS/punch-in crop and may need an axis/mirror flip. Tap a
  known point and confirm the AF/AE region lands there.
- ✅ **Camera opens without crashing** (standalone id 4, SDR session, RAW gated) — verified on device
  (`Session configured (fallback=0, hlg=false, jpeg=true, raw=true)`, no SIGSEGV).
- ✅ **Session-lifecycle disconnect crash fixed** — `closed`/`paused` guards; verified no
  `CAMERA_DISCONNECTED` crash after relaunch on an awake device.
- ✅ **Preview is no longer black** — TextureView host renders camera frames (verified: textured
  preview visible under the grid overlay).

---

## 1. Correctness gaps (before Play)

- 🔴 **Video orientation ignores the G-sensor.** Stills are now device-orientation-aware, but video
  is encoded from the GL output, which uses the **fixed preview rotation** (no device orientation).
  A clip shot in landscape saves portrait-framed-rotated. Options: rotate the encoder draw by device
  orientation captured at record-start, or write a rotation matrix / `MediaFormat` rotation hint into
  the muxer. Decide and implement in `VideoRecorder` / `GlPipeline` encoder path.
- 🟡 **HLG/Log video color is approximate.** True OPPO LOG is not reproducible (stock-app exclusive);
  we apply a GL Log OETF and tag Rec.2020. Honestly labeled. Verify HLG playback looks correct on an
  HDR display and that Log grades sanely; document the gamut/curve we actually emit.
- ⏸ **10-bit HDR *preview*.** Deferred: HLG10 preview + full-res JPEG/RAW crashes the HAL, so the
  live session is SDR (video still encodes 10-bit HLG/Log). Revisit if a stream combo is found that
  the HAL accepts (e.g. HLG preview with RAW dropped, smaller JPEG), via the fallback ladder.
- 🟡 **Manual WB Kelvin→RGGB gains** use a Tanner-Helland blackbody approximation. Verify neutral
  grey looks neutral at 5200 K and that tint behaves; may need per-device calibration.

## 2. Deferred features (pro-camera completeness)

- 🔴 **Slow-motion / high-speed video** (120/240 fps) — needs a `CameraConstrainedHighSpeedCaptureSession`;
  not yet wired. UI has no entry point.
- 🔴 **Geotagging** — needs the location permission + fusing GPS into EXIF (HEIF/DNG) and the MP4
  udta. Not started; add opt-in toggle.
- 🔴 **Custom save folder / filename pattern** — currently fixed `IMG_/VID_` + timestamp into
  DCIM/Pictures. Add a setting.
- 🔴 **Bracketing beyond AEB / focus stacking**, **interval-shooting UI polish** — drive modes exist
  (SINGLE/BURST/AEB/TIMELAPSE) but UX/edge cases need a pass.
- 🟡 **Histogram/waveform/zebra/peaking/false-color** are implemented (GL readback + shader) — verify
  they read correctly and don't stall rendering on device (analysis is throttled to every ~12th
  frame; profile it).

## 3. Play-release readiness (release engineering)

- 🔴 **Release build**: signing config, `minifyEnabled`/R8 rules (keep Camera2/DngCreator/heifwriter
  reflection-safe), `shrinkResources`, a real app icon + adaptive icon, splash.
- 🔴 **Single-device distribution**: this app targets exactly one device model. Decide the Play
  strategy — device catalog restriction, or a clear "will only work on Find X9 Ultra + teleconverter"
  store listing. `minSdk 36` already excludes most devices.
- 🔴 **Permissions & privacy**: CAMERA + RECORD_AUDIO (+ future LOCATION) — write the data-safety form
  and a privacy policy. No network is used; state that.
- 🔴 **Store assets**: screenshots (portrait), feature graphic, description, category.
- 🔴 **Crash/ANR hardening pass**: exercise permission-denied, rapid mode switching, background/
  foreground during record, low storage, teleconverter chip absent.
- 🔴 **Licensing**: confirm heifwriter `-alpha01` is acceptable for a release, or pin to the newest
  stable when it lands.
- 🔴 **Versioning**: set `versionCode`/`versionName`; wire a changelog.

## 4. Engineering hygiene

- 🟡 **Test coverage** is thin: only `FocusMappingTest`. Add pure-logic unit tests for
  `CameraSelector2` (closest-to-70 mm + standalone tie-break), rotation math
  (`previewRotationDegrees`/`captureRotationDegrees`/`exifOrientationFor`), `GyroEis.currentDeviceOrientation`,
  Kelvin→RGGB, and color-profile params. Hardware paths stay manual-verify.
- 🔴 **Commit history is Korean** (pre-English-switch). Not rewritten (destructive; needs explicit
  sign-off). New commits are English.
- 🟢 Reviews in `.context/reviews/` (architect/code/perf/security) — findings were addressed in the
  earlier `/review-plan-fix` pass; re-run before release.

---

## How to verify on device (quick reference)

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb connect 172.30.50.127:<port>      # ask user for current port
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n com.hletrd.findx9tele/.MainActivity
adb logcat -d | grep -E "CameraController|AndroidRuntime|Session configured"
adb exec-out screencap -p > /tmp/shot.png      # flat phone = dark texture, not a bug
# to check capture orientation, take a shot then:
adb shell 'ls -t /sdcard/DCIM/Camera /sdcard/Pictures 2>/dev/null | head'
adb pull /sdcard/Pictures/<file>.heic /tmp/
```
