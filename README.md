<div align="center">

<img src="docs/assets/logo.svg" width="112" alt="TeleCam Pro logo" />

<h1>TeleCam Pro</h1>

<p><b>Professional manual camera for the OPPO Find X9 Ultra periscope telephoto and 300&nbsp;mm afocal teleconverters</b><br/>
4-lens switcher, afocal 180° flip, HAL OIS+EIS, SDR-to-HLG / O-Log2 profiles, directional audio</p>

<p>
<img src="https://img.shields.io/badge/Android-16%20(API%2036)-3DDC84?logo=android&logoColor=white" alt="Android 16" />
<img src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
<img src="https://img.shields.io/badge/Jetpack%20Compose-2026.06-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
<img src="https://img.shields.io/badge/Camera2-Pro%20manual-FF7043" alt="Camera2" />
<img src="https://img.shields.io/badge/Gradle-9.6.1-02303A?logo=gradle&logoColor=white" alt="Gradle" />
<img src="https://img.shields.io/badge/Device-Find%20X9%20Ultra%20(CPH2841%2FPMA110)-000000" alt="Find X9 Ultra" />
</p>

</div>

## Features

- **Single-device exclusive**: Android 16 (API 36), latest toolchain only (no backward compatibility). Camera2 direct — no CameraX.
- **Sony-style pro UX**: Fn access, My Menu, MR banks, PASM-like exposure, compact OSD, peaking,
  zebra, histogram, waveform, and review zoom. No tutorial banners, warning chips, or helper overlays
  over the viewfinder. See [`docs/UX_POLICY.md`](docs/UX_POLICY.md).
- **Seamless zoom (photo)**: pinch sweeps **0.6×→20×** across all four lenses (UW 14 mm / main 23 mm / 3× 70 mm / 10× 230 mm) in one session on the logical multicamera — the HAL crosses the optics at their native ratios and fills between them digitally, iPhone-style; lens buttons are zoom presets, and the OSD reads the live effective focal. Video pins the matching standalone lens (the logical camera's EIS leaks a warp band into recordings — device-isolated), with lens-local digital zoom. The TELE toggle pins the standalone 3× for converter shooting.
- **Afocal 180° flip**: The teleconverter is afocal, so images arrive flipped 180° → preview/photos/videos all corrected (GL texcoord rotation for preview, pixel rotation for HEIF/JPEG, EXIF tag for DNG).
- **Full manual control**: Focus (nonlinear slider tuned near infinity), ISO, shutter (speed or cine
  angle), WB (presets + Kelvin/tint), EV, metering, drive modes (single/burst/AEB/timelapse).
  Stop-snapping dials have haptic detents; AF→MF handoff seeds the manual slider from AF's live lens
  position. Restored and live choices are normalized as one packet to the exact modes and zoom range
  the accepted camera advertises; route-changing recall waits for the target camera's capabilities.
  Settings and Fn cycles expose only applicable choices, and quick rulers require their exact manual
  mode/range. AE/AF regions are sent only when that camera reports region support.
- **Volume-key hardware shutter**: vibration-free release at 300 mm (photo capture / video start-stop).
- **Directional audio (Sound Focus / Sound Stage)**: drives the device's accepted vendor audio-HAL controls; the acoustic effect still needs an off-axis real-scene A/B check.
- **Photos**: HEIF and JPEG can be selected separately or together in photo mode. RAW (DNG) is
  additionally available only in TELE mode on the eligible standalone 3× camera; supported outputs
  can be combined. A DNG-only result gets a truthful RAW review tile; a processed sibling from the
  same capture upgrades it. Canonical families remain grouped across relaunch, and Delete removes
  every known sibling; legacy files that cannot prove grouping explicitly delete only that file. The
  fresh-launch review compares photos, RAW-only captures, and videos. All saved formats carry
  gravity-derived orientation correction.
- **Video**: HEVC Main10 profiles for **HLG / O-Log2** plus 8-bit HEVC/AVC SDR. HLG uses the
  display-referred SDR-to-HLG mapping from ITU-R BT.2408-9 (SDR reference white → 75% HLG); it
  cannot recover highlights already removed by the ISP's SDR tone mapping. The stable v1 Camera2 and
  EGL input is SDR/8-bit, so neither HLG nor O-Log2 is marketed as end-to-end 10-bit capture. 4K UHD
  max (HEVC/AVC HW ceiling); 24/25/30/60 fps class + NTSC drop-frame
  (23.976/29.97/59.94); **Low → Max bitrate presets up to ~120 Mbps at 4K**; Open-Gate
  4:3-aspect recording (2560×1920 verified on the tele); AAC 48 kHz stereo.
- **Video stabilization = HAL OIS+EIS** (the stock "super steady" path): OIS physically cuts per-frame motion blur at 300 mm (Off / Standard / Active — the in-app labels).
- **Vendor/HAL stability**: unstable or unmuxable device paths such as Auto HDR, high-speed 120 fps,
  AV1 software encode, APV MP4 muxing, and native vendor log are excluded from the shipped UI. O-Log2
  is the GL-baked shipping path.
- **Aspect ratios**: 4:3 (full sensor) / 16:9 (center crop). Sony-style mode-aware OSD.
- **Capture aids**: focus peaking (adjustable sensitivity/color), zebra, false color, grid, spirit level, movable punch-in loupe, histogram, waveform, in-app last-shot pinch-to-zoom review.
- **Settings persistence**: pro controls saved across launches ("Remember Settings", default ON),
  with separate default-on preserve toggles for lens selection and TELE mode.

## Open source

TeleCam Pro is free and open source. The source code is public at
[`github.com/hletrd/telecam-pro`](https://github.com/hletrd/telecam-pro), and the app contains no ads,
analytics, in-app purchases, accounts, or cloud sync.

## Toolchain

See [`CLAUDE.md`](CLAUDE.md) § **Toolchain** for pinned versions and build setup.

| Component | Version |
|---|---|
| AGP | 9.3.0 |
| Gradle | 9.6.1 |
| Kotlin / Compose compiler | 2.4.10 |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 |
| JDK | 21 (aarch64) |

> Android SDK Platform 37 is required to compile the app. The runtime target and minimum remain API 36
> (Android 16). AGP 9 has Kotlin built in; the `kotlin.android` plugin is not applied.

## Build

```bash
./gradlew assembleDebug        # debug APK
./gradlew installDebug          # install to device
```

Requires JDK 21, Android SDK Platform 37, and SDK Build Tools 36.0.0. The app still targets and requires
API 36 at runtime. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) is the current as-built design
authority. The [`2026-07-01 specification`](docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md)
is a preserved historical snapshot and is superseded wherever it differs; it is not the current design.

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

## Device camera capabilities

Beyond the standard Camera2 surface, the device advertises extra session/request capabilities for its
camera pipeline, and several are available to third-party apps on the tele. TeleCam Pro submits only
exact advertised values, normalizes the visible controls to those values, and suppresses AE/AF regions
when the corresponding advertised maximum is zero. Device behavior is verified through saved files,
not just session setup logs:

| Feature | Key | Status |
|---|---|---|
| Native log | `com.oplus.log.video.mode` (session key) | ⛔ HAL accepts the key, but third-party Camera2 output remains 709; GL O-Log2 ships |
| Video stabilization | `CONTROL_VIDEO_STABILIZATION_MODE` + `com.oplus.video.stabilization.mode` | ✅ `ois=1, vstab=2` verified |
| Directional audio | `vendor_audiorecord_effect_type` / `focus_angle` … | ✅ HAL `track_support=true` |
| Auto HDR / Ideal RAW / APV / AV1 / high-speed 120 / in-sensor zoom / macro / custom-LUT | — | ⛔ not exposed in the shipped UI; excluded after device compatibility checks |

## Implementation Status

- ✅ **Build & gates**: `./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease` passes.
- ✅ **Unit tests**: `app/src/test/` is the suite source of truth; rerun
  `./gradlew :app:testDebugUnitTest` for the current result instead of relying on a copied count.
- ✅ **Release device smoke test on PMA110**: fresh launch starts at 1x / 23 mm with TELE off;
  Preserve Lens and Preserve TELE default on and persist independently; rapid double-shutter in an
  eligible TELE session produces one valid DNG+HEIF pair; 4K HLG records HEVC Main10 at 30000/1001
  with AAC; Open Gate records
  2560x1920 4:3; and no crash or ANR was observed. The installed release APK matched the locally
  verified artifact byte-for-byte and was not debuggable.
- ✅ **UI and Play assets**: the menu hierarchy and core photo/video flows were reviewed on the
  physical device. Six 1440x2560 PMA110 screenshots are tracked with Git LFS.
- ⏳ **Remaining Play Console work**: upload the signed AAB, enter the listing and Data Safety answers,
  restrict the device catalog to CPH2841/PMA110, run internal testing, and review the pre-launch report.
  The exact operator checklist is in [`docs/play-console-submit.md`](docs/play-console-submit.md).
- 🔎 **Residual field checks**: directional-audio off-axis acoustic A/B, held portrait/landscape
  saved-file orientation, and post-mapping HLG appearance on a real HDR display are worth confirming;
  none changes the current build or Play metadata.
- 📌 **Deferred beyond v1**: R8/minify and Dolby Vision. See [`docs/BACKLOG.md`](docs/BACKLOG.md).
