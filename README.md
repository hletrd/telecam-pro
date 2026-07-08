<div align="center">

<img src="docs/assets/logo.svg" width="112" alt="TeleCam Pro logo" />

<h1>TeleCam Pro</h1>

<p><b>Professional manual camera for the OPPO Find X9 Ultra periscope telephoto and 300&nbsp;mm afocal teleconverters</b><br/>
4-lens switcher, afocal 180° flip, HAL OIS+EIS, HAL-native log, directional audio</p>

<p>
<img src="https://img.shields.io/badge/Android-16%20(API%2036)-3DDC84?logo=android&logoColor=white" alt="Android 16" />
<img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
<img src="https://img.shields.io/badge/Jetpack%20Compose-2026.06-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
<img src="https://img.shields.io/badge/Camera2-Pro%20manual-FF7043" alt="Camera2" />
<img src="https://img.shields.io/badge/Gradle-9.6.1-02303A?logo=gradle&logoColor=white" alt="Gradle" />
<img src="https://img.shields.io/badge/Device-Find%20X9%20Ultra%20(CPH2841%2FPMA110)-000000" alt="Find X9 Ultra" />
</p>

</div>

## Features

- **Single-device exclusive**: Android 16 (API 36), latest toolchain only (no backward compatibility). Camera2 direct — no CameraX.
- **Sony-style pro UX**: quiet, operator-facing camera controls inspired by Sony Alpha / Xperia Pro
  workflows: Fn access, My Menu, MR banks, PASM-like exposure, and compact status readouts. The app
  intentionally avoids phone-camera tutorial banners, nagging warning chips, or decorative helper UI
  that would distract from the viewfinder. See [`docs/UX_POLICY.md`](docs/UX_POLICY.md).
- **4-lens switcher + teleconverter bundle**: UW (14 mm) / main (23 mm) / 3× (70 mm) / 10× (230 mm), resolved by 35 mm-equivalent focal (no hardcoded ids, standalone-preferred to avoid the QTI-HAL routing crash). Selecting the 3× lens **bundles teleconverter mode on** (afocal 180° flip + gyro-EIS scaled to ~300 mm) in one tap; other lenses turn it off.
- **Afocal 180° flip**: The teleconverter is afocal, so images arrive flipped 180° → preview/photos/videos all corrected (GL texcoord rotation for preview, pixel rotation for HEIF/JPEG, EXIF tag for DNG).
- **Full manual control**: Focus (nonlinear slider tuned near infinity), ISO, shutter (speed or cine angle), WB (presets + Kelvin/tint), EV, metering, drive modes (single/burst/AEB/timelapse). Stop-snapping dials with haptic detents; AF→MF handoff seeds the manual slider from AF's live lens position.
- **Volume-key hardware shutter**: vibration-free release at 300 mm (photo capture / video start-stop).
- **Directional audio (Sound Focus / Sound Stage)**: drives the vendor audio-HAL params (`vendor_audiorecord_effect_type` …), the device's own directional-audio path — Sound Focus narrows the mic toward the framed subject and tightens with zoom.
- **Photos**: HEIF + JPEG + RAW (DNG), any combination. Device-orientation-aware (stills save upright in any hold via gyro gravity).
- **Video**: 10-bit HEVC (Main10, Rec.2020) in **HLG / O-Log2 / SDR**, plus HAL-native log (`com.oplus.log.video.mode`); 8-bit AVC; AV1 (SW). 4K DCI max (HEVC/AVC HW ceiling); 24/25/30/60 fps + NTSC drop-frame (23.976/29.97/59.94) + 120 fps high-speed; **Low → Max bitrate presets up to ~134 Mbps at 4K**; Open-Gate (full 4:3 sensor); AAC 48 kHz stereo.
- **Video stabilization = HAL OIS+EIS** (the stock "super steady" path): OIS physically cuts per-frame motion blur at 300 mm (Off / Gyro / OIS-Standard / OIS-Enhanced).
- **Vendor features (experimental)**: in-sensor zoom (`EnableInsensorZoom`), driven directly via the QTI vendor session keys. (Auto HDR was tried but gated out — it SIGABRTs the camera HAL on reopen+capture.)
- **Aspect ratios**: 4:3 (full sensor) / 16:9 (center crop). Sony-style mode-aware OSD.
- **Capture aids**: focus peaking (adjustable sensitivity/color), zebra, false color, grid, spirit level, movable punch-in loupe, histogram, waveform, in-app last-shot pinch-to-zoom review.
- **Settings persistence**: pro controls saved across launches ("Remember Settings", default ON).

## Open source

TeleCam Pro is free and open source. The source code is public at
[`github.com/hletrd/telecam-pro`](https://github.com/hletrd/telecam-pro), and the app contains no ads,
analytics, in-app purchases, accounts, or cloud sync.

## Toolchain

See [`CLAUDE.md`](CLAUDE.md) § **Toolchain** for pinned versions and build setup.

| Component | Version |
|---|---|
| AGP | 9.2.1 |
| Gradle | 9.6.1 |
| Kotlin / Compose compiler | 2.4.0 |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 |
| JDK | 21 (aarch64) |

> compileSdk 37 satisfies the latest AndroidX (lifecycle 2.11.0); targetSdk/minSdk 36 target Android 16. AGP 9 has Kotlin built-in; the `kotlin.android` plugin is not applied.

## Build

```bash
./gradlew assembleDebug        # debug APK
./gradlew installDebug          # install to device
```

Requires JDK 21 + Android SDK (API 36, build-tools 36.0.0). Design document: [`docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md`](docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md)

### Release build (Google Play)

The signed release artifact is a **Play App Bundle** (`.aab`). Signing is driven by a gitignored
`keystore.properties` — no keys live in git. One-time setup:

```bash
# 1. create an upload keystore (from the repo root)
keytool -genkeypair -v -keystore telecampro-upload.jks -alias telecampro \
  -keyalg RSA -keysize 4096 -validity 10000

# 2. copy the template and fill in the path/alias (passwords should stay out of the file)
cp keystore.properties.example keystore.properties   # then edit storeFile/keyAlias if needed

# 3. provide passwords for this shell without putting them in shell history
read -s "TELECAMPRO_STORE_PASSWORD?Store password: "; echo
export TELECAMPRO_STORE_PASSWORD
read -s "TELECAMPRO_KEY_PASSWORD?Key password (enter the same value if you reused it): "; echo
export TELECAMPRO_KEY_PASSWORD

# 4. build the signed bundle (fails fast if signing credentials are missing)
./gradlew bundleRelease        # → app/build/outputs/bundle/release/app-release.aab
```

Without `keystore.properties`, debug builds, tests, and lint still work; release bundling intentionally
fails instead of producing an unsigned artifact that cannot be uploaded to Play.
R8/minify is intentionally off for v1. Store listing text, privacy policy, Data Safety answers, and
graphic assets live in [`docs/play-store-listing.md`](docs/play-store-listing.md),
[`privacy-policy/index.html`](privacy-policy/index.html), [`docs/play-data-safety.md`](docs/play-data-safety.md),
and [`docs/assets/play/`](docs/assets/play/).

This working copy has a local upload keystore at `telecampro-upload.jks`, a gitignored
`keystore.properties` containing only the keystore path/alias, and an encrypted password backup at
`telecampro-upload-passwords.txt.gpg`. Decrypt that backup locally and export the two `TELECAMPRO_*`
password variables before rebuilding release bundles.

## Device vendor HAL features

Beyond the standard Camera2 surface, the device's camera HAL advertises vendor session/request keys
for its pro pipeline, and several are available to third-party apps on the tele. TeleCam Pro drives
them directly, each device-verified through to a saved file (not just "session configured"):

| Feature | Key | Status |
|---|---|---|
| Native log | `com.oplus.log.video.mode` (session key) | ✅ scene-referred log stream verified |
| Video stabilization | `CONTROL_VIDEO_STABILIZATION_MODE` + `com.oplus.video.stabilization.mode` | ✅ `ois=1, vstab=2` verified |
| Directional audio | `vendor_audiorecord_effect_type` / `focus_angle` … | ✅ HAL `track_support=true` |
| In-sensor zoom | `EnableInsensorZoom` | ✅ verified |
| Auto HDR / Ideal RAW / APV / macro / custom-LUT | — | ⛔ tried, gated/excluded (Auto HDR SIGABRTs the camera HAL on reopen+capture; the rest break capture or are inert) |

## Implementation Status

- ✅ **Build & gates**: `./gradlew assembleDebug testDebugUnitTest lintDebug` all pass.
- ✅ **Unit tests**: FocusMappingTest, RotationMathTest, CameraSelector2Test, VideoCapabilitiesTest, ExposureMathTest.
- ✅ **Device-verified on PMA110**: all 4 lenses open (standalone, no HAL crash) with RAW; teleconverter bundling; preview upright; tap-to-focus lock; AF→MF handoff; volume-key shutter; HEIF (4096×3072) + DNG + JPEG saves; HEVC 4K video incl. Max bitrate (~134 Mbps); HAL log + HAL OIS+EIS + directional-audio support + in-sensor zoom all accepted end-to-end (release build re-verified: not debuggable, no diagnostic dump; camera-reopen race fixed; Auto HDR gated out after it SIGABRT'd the HAL). Global Find X9 Ultra device code is CPH2841 per OPPO's public specs; Play device catalog should allow CPH2841 and PMA110.
- ⏳ **Needs your eyes/ears in a real scene**: the acoustic effect of directional audio (off-axis A/B), and the image gain of in-sensor zoom (distant subjects) — undetectable from a static desk.
- ✅ **Play-release scaffolding**: release signing config with unsigned-bundle fail-fast, public
  privacy policy URL, store-listing text, Data Safety answer sheet, icon + feature graphic — see the
  Release build section above. Remaining human steps: complete the Play Console listing/data-safety
  form and restrict availability to CPH2841/PMA110.
- 🚧 **Not started**: R8/minify (deferred — needs enum keep-rules + device re-verification). Dolby Vision (HW encoder detected, MP4 muxing non-trivial). See [`docs/BACKLOG.md`](docs/BACKLOG.md).
