# Reverse Engineering — Knowledge Base

Collection of materials from analyzing the Find X9 Ultra stock camera (`com.oplus.camera`) and camera HAL to understand teleconverter ("Explorer") stabilization/zoom mechanisms and professional controls for replication in our app. (Authorized analysis of owner's own device + legally-obtained APK)

## Documents

- [`camera-hardware-map.md`](camera-hardware-map.md) — 7-camera mapping (focal length / sensor / role), teleconverter target lens (dev4, 70mm), selector implications.
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

1. **Teleconverter target = 70mm 3x periscope (dev4, actual focal length 20.10mm)**. Not the longest focal length (230mm/10x) → selector targets 70mm-equiv.
2. **Teleconverter mode is manual entry** (not auto-detect). Converter chip appears to be for safety/authentication checks → our app also **manual toggle**.
3. **Stabilization controlled by Qualcomm EIS (`EISMode`, `eisrealtime.*`) + OPPO vendor tags (`ois.control.mode`, `sois.custom.info`, `video.stabilization.mode`)**. Key is OIS/EIS profile switching tuned to effective focal length (300mm).
4. **Zoom/FOV created via `custom.zoom.range`/`expert.zoom.range`/`fov.Angle` override** to achieve 3x→13x (300mm) range.
5. `gyroFromAP` tag exists only in HAL descriptor and **stock app doesn't reference it** (gyro handled internally by HAL). We implement client-side gyro EIS directly.

## Our App Strategy

> **Deep decompilation conclusion (oplus-camera-explorer-analysis.md)**: Even the stock app **does not directly set** OIS/EIS vendor tags (`ois.control.mode`, `sois.custom.info`, `gyroFromAP`, etc). Correct focal-length stabilization is a **HAL side-effect** of: (1) selecting the periscope physical lens in Explorer operation/sensor mode, (2) zoom ratio, (3) passing `explorer.chip.state` at session configuration, (4) requesting generic `super_stabilization`. Explorer-specific tags are absent from the static vendor-tag descriptor (only engineering tags exist), so third-party `CaptureRequest` **cannot practically use them (system/factory gated)**.

- **Standard Camera2 + client-side gyro EIS (adopted)**: Select 70mm lens + GL gyro EIS (effective focal length 300mm scale) + HAL EIS off + OIS toggle. No vendor tags needed, works reliably for third-party → **this is our approach**.
- **Native vendor-tag path (unrealistic)**: Appears gated, unlikely to work. However, on-device verification via `VendorTagInspector` in app to see which tags are exposed to third-party is valuable (e.g., attempt to set `super_stabilization` series values).

## Remaining Tasks (reconnection needed)

- Engineer camera app (`com.oplus.engineercamera`), sensor/codec/camera config XML dumps.
- Live logcat capture while toggling teleconverter mode in stock app → verify actual tag/value settings.
- On-device installation & capture verification of our app.
