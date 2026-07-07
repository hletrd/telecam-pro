# Reverse Engineering — Knowledge Base

Collection of materials from analyzing the Find X9 Ultra stock camera (`com.oplus.camera`) and camera HAL to understand teleconverter ("Explorer") stabilization/zoom mechanisms and professional controls for replication in our app. (Authorized analysis of owner's own device + legally-obtained APK)

## Documents

- [`oplus-log-video-analysis.md`](oplus-log-video-analysis.md) — **The current, verified vendor-API reference.** How the stock app drives O-Log, video stabilization, directional audio, HDR and in-sensor zoom through the OCS SDK → vendor HAL keys; which keys are exposed to third-party Camera2; and the device-verified status of each (§6 has the full audit table). Read this first.
- [`camera-hardware-map.md`](camera-hardware-map.md) — 7-camera mapping (focal length / sensor / role), teleconverter target lens (dev4, 70mm), selector implications, the tele's manual-sensor/RAW/fps capabilities + observed 3A behavior, and the device's video encoders (HW AVC/HEVC/Dolby-Vision/APV, SW AV1).
- [`vendor-tags-catalog.md`](vendor-tags-catalog.md) — Curated vendor tags for stabilization/OIS/gyro/zoom/Explorer/pro controls.
- [`oplus-camera-explorer-analysis.md`](oplus-camera-explorer-analysis.md) — Deep decompilation analysis of stock app (Explorer detection, stabilization switching, zoom override code evidence). *(agent analysis result)*
- [`raw/vendor_tags.txt`](raw/vendor_tags.txt) — Raw dumpsys vendor tag definitions (1,509 entries).

## Capture Method (for reproduction)

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
ADB="$ANDROID_HOME/platform-tools/adb"
$ADB connect 172.30.50.127:38189            # wireless adb (reconnect needed if device sleeps)
$ADB shell dumpsys media.camera > dumpsys_camera.txt   # camera characteristics + vendor tags
$ADB shell pm path com.oplus.camera         # → /product/app/OplusCamera/OplusCamera.apk
$ADB pull <path> OplusCamera.apk            # stock app (169MB, single base.apk)
jadx -j 6 --no-res -d jadx-out OplusCamera.apk   # decompile (16,697 java files)
```

## Key Conclusions (Summary)

1. **Teleconverter target = 70mm 3x periscope (dev4, actual focal length 20.10mm)**. Not the longest focal length (230mm/10x) → selector targets 70mm-equiv. All four rear lenses (UW/main/3×/10×) open as standalone ids 3/2/4/5 (verified).
2. **Teleconverter mode is manual entry** (not auto-detect). Converter chip is a safety/authentication check → our app selects the 3× lens and bundles teleconverter mode on.
3. **Many stock vendor features ARE reachable from third-party Camera2** — this reverses the earlier "gated/unrealistic" conclusion. The stock app drives its capabilities through the OCS SDK, which maps to vendor HAL keys, and several of those keys are in the tele's `availableRequestKeys`+`availableSessionKeys`. Verified working: `com.oplus.log.video.mode` (O-Log), `com.oplus.video.stabilization.mode` + std `CONTROL_VIDEO_STABILIZATION_MODE` (OIS+EIS), `vendor_audiorecord_effect_type` (directional audio), `EnableAutoHDR`/`HDRMode`, `EnableInsensorZoom`. See [`oplus-log-video-analysis.md`](oplus-log-video-analysis.md) §6 for the full audit.
4. **Video stabilization = HAL OIS+EIS, not client gyro EIS.** At a fixed video shutter only OIS (lens moves during exposure) cuts per-frame motion blur; app-side gyro EIS only warps frames and cannot de-blur. We now set `CONTROL_VIDEO_STABILIZATION_MODE = PREVIEW_STABILIZATION` (the tele advertises modes [0,1,2]) — device-verified `ois=1, vstab=2`. The old client gyro EIS is kept as an optional `Gyro` mode.
5. The Explorer-specific focal OIS/EIS tags (`com.oplus.ois.*`, `sois.custom.info`, `gyroFromAP`, `eisrealtime`) remain absent from the exposed key set, but the GENERIC HAL video stabilization is enough — the HAL applies the right OIS profile for the active lens.

## Verification lesson

A vendor key being in `availableRequestKeys` means the framework will *accept* it, NOT that the
pipeline honors it end-to-end. Ideal RAW (`EnableIdealRAW`) and APV (`video/apv`) both configure a
clean session yet break capture (no DNG / MediaMuxer rejects APV). **Verify every vendor feature
through to a saved, valid file — never stop at "session configured".**
