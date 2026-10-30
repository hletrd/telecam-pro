<div align="center">

<img src="docs/assets/logo.svg" width="112" alt="TeleCam Pro logo" />

<h1>TeleCam Pro</h1>

<p><b>Manual camera for periscope telephoto phones and clip-on afocal teleconverters</b></p>

<p>
<a href="https://play.google.com/store/apps/details?id=me.hletrd.telecampro"><img src="https://img.shields.io/badge/Google%20Play-Download-414141?logo=googleplay&logoColor=white" alt="Get TeleCam Pro on Google Play" /></a>
<img src="https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?logo=android&logoColor=white" alt="Android 13 and newer" />
<img src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
<img src="https://img.shields.io/badge/Jetpack%20Compose-2026.09-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
<img src="https://img.shields.io/badge/Camera2-Pro%20manual-FF7043" alt="Camera2" />
<img src="https://img.shields.io/badge/License-Apache%202.0-000000" alt="Apache License 2.0" />
</p>

<p><a href="https://play.google.com/store/apps/details?id=me.hletrd.telecampro"><b>Get it on Google Play</b></a></p>

</div>

Manual camera for shooting through clip-on afocal teleconverters (Hasselblad, ZEISS, generic) on a
phone's periscope lens.

## Features

- 180° converter flip corrected in the main viewfinder, stills, and video (the Loupe Overview shows
  the raw delivered stream).
- Converter presets per phone, with true focal length in the OSD and EXIF.
- Manual focus tuned for near-infinity, PASM, 1/3-stop ISO, WB / Kelvin, face-priority AF, AEB,
  timelapse.
- HEIF / JPEG / DNG. On the Find X9 Ultra, wanting RAW moves photo onto a standalone camera.
- HEVC / H.264 up to 4K; on the Find X9 Ultra (PMA110), 8K30 HEVC in Rec. 709 on the 1× and 3×
  lenses (no stills during an 8K take). Gamma: Off, HLG, Log (S-Log3, S-Log3.Cine or LogC3 on the ISP pipeline)
  and an experimental RAW Log that develops the same profiles in-app from the sensor RAW.
- Peaking, zebra, false colour, histogram, waveform, loupe, Fn / My Menu / MR banks.

Android 13+. Tested on the OPPO Find X9 Ultra; other phones are best-effort. Device-specific
workarounds live only in `camera/DeviceProfile.kt`.

## Build

JDK 21 and an Android SDK with Platform 37 and Build Tools 36.0.0. The SDK is found from
`local.properties` `sdk.dir`, `ANDROID_HOME`, or `$HOME/Library/Android/sdk` / `$HOME/Android/Sdk`.

```bash
./gradlew assembleDebug   # For local development only
python3 tools/verify_host.py
```

| Component | Version |
|---|---|
| AGP | 9.4.1 |
| Gradle | 9.8.0 |
| Kotlin / Compose compiler | 2.4.20 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 33 |
| JDK | 21 |

### Android SDK setup

```bash
sdkmanager --channel=0 "platforms;android-37" "build-tools;36.0.0" "emulator"
```

### Release builds

`python3 tools/build_immutable_release.py :app:bundleRelease`, signed from a gitignored
`keystore.properties`; its `storeFile` is sealed by the wrapper, and an ambient
`TELECAMPRO_STORE_FILE` cannot override it.

## Licence

[Apache 2.0](LICENSE), © 2026 Jiyong Youn. No ads, analytics or accounts. Trademarks are listed in
[`NOTICE`](NOTICE); this project is independent of their owners. Privacy policy:
[`privacy-policy/`](privacy-policy/index.html).
