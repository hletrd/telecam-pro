<div align="center">

<img src="docs/assets/logo.svg" width="112" alt="Find X9 Ultra Tele Camera logo" />

<h1>Find X9 Ultra Tele Camera</h1>

<p><b>Professional camera app for OPPO Find X9 Ultra</b><br/>
3x periscope telephoto + afocal teleconverter (300mm) · full manual control · afocal 180° flip · gyro EIS</p>

<p>
<img src="https://img.shields.io/badge/Android-16%20(API%2036)-3DDC84?logo=android&logoColor=white" alt="Android 16" />
<img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
<img src="https://img.shields.io/badge/Jetpack%20Compose-2026.06-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
<img src="https://img.shields.io/badge/Camera2-Pro%20manual-FF7043" alt="Camera2" />
<img src="https://img.shields.io/badge/Gradle-9.6.1-02303A?logo=gradle&logoColor=white" alt="Gradle" />
<img src="https://img.shields.io/badge/Device-Find%20X9%20Ultra%20(PMA110)-000000" alt="Find X9 Ultra" />
</p>

</div>

## Features

- **Single-device exclusive**: Android 16 (API 36), latest toolchain only (no backward compatibility).
- **Afocal 180° flip**: The teleconverter is afocal, so images are flipped 180° on input → preview/photos/videos all corrected.
- **Full manual control**: Focus (fine-tuning near infinity), ISO, shutter, WB, EV.
- **Photos**: HEIF + RAW (DNG) saved simultaneously. HEIF with actual 180° pixel rotation, DNG with orientation tag.
- **Video**: 10-bit HEVC, Rec.2020, HLG / Log selectable. Audio included.
- **Capture aids**: Focus peaking, zebra, grid, spirit level, punch-in.

## Toolchain

| Component | Version |
|---|---|
| AGP | 9.2.0 (Kotlin built-in) |
| Gradle | 9.6.1 |
| Kotlin | 2.3.10 (AGP 9.2 built-in) |
| Compose BOM | 2026.06.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 |
| JDK | 21 (aarch64) |

> compileSdk targets API 37 to satisfy the latest AndroidX (lifecycle 2.11.0), while targetSdk/minSdk target Android 16 (API 36) runtime (compileSdk and targetSdk are decoupled). From AGP 9 onward, Kotlin is built-in; the `kotlin.android` plugin is not used.

## Build

```bash
./gradlew assembleDebug        # debug APK
./gradlew installDebug          # install to device
```

Requires JDK 21 + Android SDK (API 36, build-tools 36.0.0). Design document: [`docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md`](docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md)

## Implementation Status

Complete scaffold + core implementation (Camera2 tele selection & manual control, GL 180° flip preview/encoder, HEIF+DNG photos, HEVC/Rec.2020/HLG·LOG video, Compose pro UI).

- ✅ **`./gradlew assembleDebug` successful** — `app-debug.apk` built. `compileDebugKotlin` passed.
- ✅ **`./gradlew testDebugUnitTest` successful** — FocusMapping unit tests passed.
- ⏳ **On-device execution & camera behavior unverified** — need to verify manual focus/RAW/10-bit support on actual Find X9 Ultra tele physical lens and visually verify flip/focus/color.
- ⏳ 10-bit HDR EGL path, LOG curve, physical lens+RAW+10bit stream combination requires hardware verification/tuning (see design document §10).
