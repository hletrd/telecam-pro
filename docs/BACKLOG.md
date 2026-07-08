# Backlog & Status — Find X9 Ultra Teleconverter Camera

Living handoff document. Read after `CLAUDE.md`. Goal: **Google Play release.** Updated 2026-07-08.

## ★ Current status (2026-07-08) — read this first

The app is **rebranded "TeleCam Pro"** (`com.hletrd.telecampro`), **public on GitHub** (`telecam-pro`),
and **Play-submission-ready** at the packaging level. Since the last device-verified baseline, a large
**UX-polish + feature-trim pass** landed (mostly code-complete; several items still need on-device eyes).

### ✅ Done & device-verified
- **Play release engineering**: signed AAB (upload keystore + gitignored `keystore.properties`, password
  backed up encrypted to the user's GPG key), privacy policy live on GitHub Pages
  (`/privacy-policy/`), store-listing + data-safety docs, 512 icon + 1024×500 feature graphic, phone
  screenshots. Release build re-verified: **not debuggable**, **no `X9TeleVendor` diagnostic dump**.
- **`.debug` applicationIdSuffix** — a QA gate caught a debug APK impersonating the release id; fixed.
- **Camera-reopen race fixed** — reopens run off the main thread on `setupExecutor`, `close()` is
  idempotent + awaits the HAL device release. 0 ANRs; in-sensor-zoom/lens reopen + capture 100% in a
  13-cycle stress loop. Capture pipeline (HEIC+DNG, burst, HEVC/HLG/AAC video) all re-verified on release.
- **HAL-hostile features gated out**: Auto HDR (SIGABRTs the camera-provider HAL) and **120 fps
  high-speed** (same crash class). HAL-disconnect **auto-recovery** added (bounded reopen).
- **Native log confirmed engaging** on the release build (`vendor log.video.mode=1 applied`, `vendorLog=1`).

### 🟢 Done in code, deployed, needs on-device visual verification
- **Trim to standard-API / HAL-stable**: removed in-sensor zoom, ideal-RAW, app-side gyro-EIS, and AV1
  (SW-only on this SoC). **LOG transfer now drives the native HAL log** (removed the separate
  experimental toggle). Default bitrate → **Ultra**. Default frame rate → **29.97 (NTSC)**.
- **Teleconverter toggle locks to the 3× lens.**
- **OSD shows effective ~300 mm** in teleconverter mode (rounded); status bar nudged down for top margin.
- **UI**: pinch-to-zoom, iPhone-style control-glyph rotation, lens picker in its own tab, tune/sliders
  gear icon, fixed-width AE/AF/shutter chips.

### 🔴 Open polish items (found in the 2026-07-08 live-test loop — TODO)
1. **LOG preview not visibly flat** — native log key IS applied, but the preview/output doesn't read as
   log; investigate the GL pass-through (`gl.setTransfer(null)`) + ffprobe a recorded clip.
2. **Icon rotation — left-landscape is 180° off** (right-landscape is correct).
3. **Icon rotation — rotated text pills overflow / rounded-rect corners stretch** (nine-patch-like); the
   rotate approach needs bounds-aware layout or to rotate only compact glyphs.
4. **Scopes (waveform/histogram)** should rotate with orientation and **refresh faster**.
5. **Pinch-to-zoom does nothing** — verify the tele's `zoomRatioRange` values + the tap/transform gesture
   conflict.
6. **Settings sheet still overscrolls/bounces at the bottom** (`LocalOverscrollFactory = null` didn't fix it).

### 🔧 Infra note — wireless ADB on this Mac
`adb connect 172.30.50.127:<port>` returns **"No route to host"** even though ping + raw TCP reach the
phone, because the Mac is multi-homed (en0 Ethernet + en1 Wi-Fi on the same /17) with Tailscale — adb's
server binds a path that can't reach the phone. **Workaround: a localhost TCP proxy**
(`scratchpad/adbproxy.py <phone-ip> <port>`) then `adb connect 127.0.0.1:5599`. The wireless-debug **port
changes every time Wi-Fi/wireless-debugging is toggled** — re-read it from the phone each time.

---


## 0-bis. Verified on device 2026-07-06 (USB ADB) ✅

Full-app review session; screenshots + pulled files + ffprobe as evidence.

- ✅ **Volume-key hardware shutter** — photo mode saved HEIC (4096×3072) + DNG on `KEYCODE_VOLUME_DOWN`;
  video mode started/stopped real clips. *(files + logcat)*
- ✅ **AF→MF handoff + live focus readout** — Focus chip showed `AF-C ∞ + 40` live (matches logged
  `lens=0.4276`); tapping it entered MF seeded at the same `∞ + 40`. *(screenshots)*
- ✅ **MF auto punch-in loupe** — opening the Focus ruler magnified the preview; closed restores. *(screenshot)*
- ✅ **Sony-style OSD** — photo mode shows `69mm TELE · HEIF+DNG · EIS`; video mode shows
  `4K 30p HEVC 24Mb · <TF> · EIS`; camera id only on DEBUG. *(screenshots)*
- ✅ **O-Log2 recording** — clip is HEVC 4K 10-bit **BT.2020 + full-range** (the LOG fingerprint);
  frame reads flat-log; official `O-Log2-to-Rec709_Gamma24` LUT restores contrast. Curve verified
  numerically against the white-paper anchors (18 % → 0.4868). *(ffprobe + frame extract)*
- ✅ **LOG transfer-tag fix** — before: VUI defaulted to `smpte2084` (PQ) → players tone-mapped log as
  HDR; after: SDR-class `bt2020-10`. HLG clip verified tagging `arib-std-b67` + `tv` range. *(ffprobe)*
- ✅ **HAL-native log IS reachable** — the vendor tag `com.oplus.log.video.mode` (Integer, session key)
  IS in the tele's availableRequest+SessionKeys. Set via raw Camera2 (session param + request);
  device-verified value 1 engages a genuine scene-referred log stream (flat, mean luma ~½ SDR). Only
  `com.oplus.movie.log.enable` is gated. Exposed as Pro→Advanced→"Native Log". CAVEAT: not a clean
  round-trip for the published O-Log2/O-Log-gen1 LUTs (un-white-balanced) — GL O-Log2 stays the
  LUT-accurate option.

New in code this session, **not yet device-verified**: manual-exposure AEB shutter bracket (unit-tested;
needs a 3-file exposure sweep check), SDR (Rec.709) HEVC clip, JPEG-only format capture, quadrant level
gauge in a landscape hold.

Legend: ✅ done & verified · 🟢 done in code, gates green · 🟡 done in code, **unverified on device** ·
🔴 not started · ⏸ deferred

---

## 0. Verified on the physical device this session ✅

The device is on **wireless ADB** (IP/port change per session — ask the user; last was
`172.30.50.127:34265`). Confirmed via **screenshots + logcat only** — see the caveat below on
output-file verification.

- ✅ **Camera opens, no crash** — standalone tele (cam 4), SDR session, RAW gated. Also survives the
  keyguard/lifecycle race (`closed`/`paused` guards). *(logcat)*
- ✅ **Preview renders and is upright** — TextureView host; tele mode applies the afocal 180° only
  (the SurfaceTexture transform already bakes in the sensor orientation). User-confirmed upright. *(screenshot)*
- ✅ **Auto-exposure + continuous-AF defaults** — preview is exposed & sharp on launch. *(screenshot)*
- ✅ **Tap-to-focus works** — reticle lands on the tap; AF scans that region (logcat `Touch AF` +
  `afState` reaches FOCUSED). Uses `CONTROL_AF_MODE_AUTO` one-shot lock. *(screenshot + logcat)*
- ✅ **Exposure-dial UX** — AE Auto/Manual chip; ISO/shutter **stop-snap with haptic detents**;
  relative focus scale. (Iterated until it *felt* snapped on device.) *(screenshot)*
- ✅ **Photo capture saves valid files** — pulled + inspected real output (2026-07-03): HEIF decodes
  as HEVC HEIF **4096×3072 (4:3 full sensor, ~12.6 MP)**; DNG is valid 16-bit uncompressed RAW,
  `OPPO PMA110`, 4096×3072, EXIF `ISO 12800 / 1/30 s`; HEIF+DNG both saved from one shutter; unique
  filenames with a working monotonic counter (`_000`.._003`). Files land in `/sdcard/DCIM/X9Tele/`.

### ⚠️ Capture ORIENTATION still unverified (real finding)
The saved **DNG orientation tag came out "Normal" (0°)** on the test shot. For a portrait-held phone
`captureRotationDegrees()` should resolve to 270° (sensor 90 + afocal 180 + device 0) — a 0° result
means the gyro reported **device orientation = 90°**. Likely cause: the phone was **lying flat**, so
gravity is ~all-Z and `GyroEis.currentDeviceOrientation()` (from `atan2(x,y)`) is noisy/ambiguous when
flat. The scene was also near-black (ISO 12800), so pixels give no visual "up" either. **To confirm
upright capture:** shoot a lit subject with a clear top, held deliberately in portrait AND in
landscape, pull the HEIC/DNG, and check each looks upright + the DNG tag matches. Also consider
snapping device-orientation to the last stable non-flat value when gravity is near-vertical.

- ✅ **Video records a valid MP4** — pulled + ffprobe'd real output (2026-07-03): **HEVC 4K
  (3840×2160)**, ~29.97 fps (measured 29.9 — the drop-frame default works), **~172 Mbps**, **AAC
  audio track** present, ~4.5 s, playable. File in `/sdcard/DCIM/X9Tele/`.

Still unverified at the output-file level:
- Capture/video **upright-ness** in portrait/landscape (see the orientation note above — needs a lit,
  deliberately-held shot); JPEG-format option produces a valid JPEG; 8K / 4K120 / AV1 / Open-Gate
  variants (default was 4K HEVC 29.97).

### 3A finding (important context for the next agent)
On-device 3A logs decoded as: **AE converges** but was pinned at 1/30s by the fixed 30fps target,
and **AF passive-scans** in low light. Fixes landed: AUTO exposure now uses a **low fps floor**
(`CameraCaps.autoFpsRange`) so AE can lengthen exposure in dim scenes, and tap-to-focus forces
`AF_MODE_AUTO` for a real region scan+lock. **Re-test AE/AF in normal room light** — through an
f/2.2 tele at night they are near the physical floor, which reads as "AE/AF barely work."

---

## 1. Done in code, needs on-device verification 🟡

- 🟡 **Video capabilities** (8K/4K, drop-frame fps 23.976/29.97/59.94, 120fps high-speed, H.264/
  HEVC/AV1(SW), HEIF/JPEG, Mbps readout, Open-Gate). Builds + unit-tested, but the implementing
  agent was interrupted before a recording was pulled. **Verify a real MP4 saves & plays** at each
  new resolution/fps/codec; confirm 8K/4K120 availability tracks the *selected* camera (tele may not
  offer them — tied to the lens switcher below). See `video/EncoderCaps.kt`, `VideoRecorder.kt`.
- 🟡 **300 mm EIS actually stabilizes.** Wired + scaled by `TELECONVERTER_MAGNIFICATION` (300/70),
  HAL stabilization OFF. `GyroEis` flags the axis/sign need on-device tuning (wrong sign amplifies
  shake). Hand-hold at 300 mm and confirm the preview steadies. (task #3)
- 🟡 **TELE toggle ↔ OIS/EIS focal 70↔300.** EIS focal switches with the mode; OIS on/off is a user
  toggle and its focal is HAL-owned (vendor-gated). Confirm the device-native behavior. (task #4)
- 🟡 **Capture orientation in landscape** — stills rotate by device orientation (gravity); verify a
  landscape-held HEIC/DNG saves upright.

---

## 2. Remaining feature work (prioritized by the user) 🔴

The user explicitly wants all of these; ordered by their stated priority.

- ✅ **Lens switcher — DONE 2026-07-06.** `LensChoice` (UW 14 / main 23 / 3× 70 / 10× 230), resolved
  by 35mm-equiv focal via `CameraSelector2.overrideIdForFocal` (standalone-preferred). Pro sheet →
  Stabilization → Lens picker; `engine.setLens()` bundles teleconverter mode (3× on: afocal 180° +
  EIS ×4.3; others off) in one reopen. **Device-verified on PMA110: all four standalone lenses (ids
  3/2/4/5) open cleanly with RAW; only 3× re-engages TELE+flip.** Front lens not added (rear-tele
  app). Still unblocks 8K/4K120 on the main camera — verify those resolutions on the main lens next.
- 🔴 **Settings UX overhaul** (task #15/#17/#18):
  - ✅ ~~Surface **color transfer (Log / SDR Rec.709 / HLG)** on the main screen~~ — done 2026-07-06:
    `ColorTransfer.SDR` added (HEVC Main 8-bit BT.709) and a video-mode **TF quick chip** cycles
    HLG → O-Log2 → SDR on the dial row.
  - **Manual WB Kelvin + Tint** sliders when WB=Manual (both exist in the Exposure/Color tab; Tint
    is not on the quick WB ruler).
  - **Audio options** — mic source/sample rate/channel, and **direction** if the device API supports
    it (AudioRecord preferred-mic-direction / spatial). (task #8)
  - Make the settings sheet more intuitive overall (per-element adjustment feels indirect).
- 🔴 **AV1 caveat UX** — only a software AV1 encoder exists; keep it labeled "slow/SW" and gate to
  ≤4K (done in code — verify the label/guard on device).

## 3. Deferred / lower priority ⏸

- ⏸ **10-bit HDR *preview*** — HLG10 preview + full-res JPEG/RAW crashes the HAL; live session stays
  SDR (video still encodes 10-bit HLG/Log). Revisit if a HAL-accepted stream combo is found.
- 🔴 **Slow-motion playback metadata**, **geotagging** (needs location permission), **custom save
  folder/filename**, **focus stacking / advanced bracketing UX**.

## 4. Play-release readiness 🔴

- 🟡 Release build: signing config is wired and release bundling fails fast if
  `keystore.properties` is missing, so an unsigned AAB cannot be mistaken for a Play-ready artifact.
  R8/minify remains intentionally off for v1 until a full device re-verification pass.
- 🔴 Single-device distribution strategy (device-catalog restriction or a clear store listing);
  `minSdk 36` already excludes most devices.
- 🟡 Data-safety form + privacy policy: policy file, app-internal link, no-network claim, and listing
  copy are in place; Play Console form submission remains.
- 🟡 Store assets: icon + feature graphic + listing text are present; portrait screenshots,
  crash/ANR hardening pass, `versionCode`/`versionName`, and changelog remain.

## 5. Engineering hygiene 🟢/🟡

- 🟢 **Gates green** every commit: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
- 🟢 **Tests**: `FocusMappingTest`, `RotationMathTest`, `CameraSelector2Test`, `VideoCapabilitiesTest`.
  Pure logic (rotation, selection, fps gating, focus mapping). Hardware paths stay manual-verify.
- 🟢 **QA agent**: `.claude/agents/qa-adversary.md` — build/test/device feature gate. Run before
  releases. There is also `qa-adversary` as a registered subagent type.
- 🟡 **`review-plan-fix` loop** ran cycle 1 (21 commits) + cycle 2 (review only; then rate-limited).
  The large background review fan-out FAILS in this environment — do reviews **inline** or with ≤2
  synchronous helpers. `.context/reviews/_aggregate.md` + `docs/plans/2026-07-03-*` hold the findings.
- 🔴 Commit history is Korean pre-English-switch (not rewritten — destructive, needs sign-off). New
  commits are English.

---

## How to verify on device (quick reference)

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb connect 172.30.50.127:<port>     # ask user for current port
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n com.hletrd.findx9tele/.MainActivity
adb logcat -d | grep -E "CameraController|3A:|Touch AF|AndroidRuntime|Session configured"
adb exec-out screencap -p > /tmp/shot.png       # flat phone = dark texture, not a bug

# ---- OUTPUT-FILE verification (the part still not done — UNLOCK THE PHONE FIRST) ----
# Grant CAMERA/RECORD_AUDIO on the device once (pm grant fails on ColorOS). Then, with the app
# in the foreground and UNLOCKED, tap the shutter (find bounds via `uiautomator dump`, don't
# guess pixels) and pull the newest capture:
adb shell 'ls -t /sdcard/DCIM/Camera /sdcard/Pictures /sdcard/DCIM 2>/dev/null | grep X9TELE | head'
adb pull "/sdcard/Pictures/<file>.heic" /tmp/ && sips -g pixelWidth -g pixelHeight -g orientation /tmp/<file>.heic
adb pull "/sdcard/DCIM/Camera/<file>.dng" /tmp/ && exiftool -Orientation -ImageWidth -ImageHeight /tmp/<file>.dng
# video: record a few seconds, stop, then
adb pull "/sdcard/DCIM/Camera/<file>.mp4" /tmp/ && ffprobe -hide_banner /tmp/<file>.mp4   # codec, dims, fps, non-zero
# CHECK: portrait AND landscape holds both save upright; DNG orientation tag correct; MP4 plays.
```

**AVD note:** a `camtest` AVD (android-36, 1440×3168) exists for UI/crash checks, but its camera is
synthetic — tele/RAW/HLG/EIS/capture behavior is **device-only**. Kill the emulator when done
(`adb -s emulator-5554 emu kill`); headless QEMU otherwise pins the CPU.
