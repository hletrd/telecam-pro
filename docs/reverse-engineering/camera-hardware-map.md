# Find X9 Ultra (PMA110) — Camera Hardware Map

- Device: OPPO **PMA110** (Find X9 Ultra), Android 16 / ColorOS `PMA110_16.0.8.306(CN01)`
- Source: real device `adb shell dumpsys media.camera` (113,873 lines), captured 2026-07-01
- Camera provider: `android.hardware.camera.provider/vendor_qti/0-0` (Qualcomm), **7 physical devices**

## Camera Mapping

35mm-equivalent = actual focal length × 43.267 / sensor diagonal. Sensor diagonal calculated from `SENSOR_INFO_PHYSICAL_SIZE`.

| HAL dev | Actual FL | **35mm-equiv** | Sensor (mm) | facing | Identity |
|---|---|---|---|---|---|
| 0 | 7.73mm | **~23mm** | 11.42 × 8.58 (1/1.12") | BACK | **Main wide** 200MP f/1.5 |
| 1 | 3.25mm | (front) | 5.24 × 3.93 | FRONT | Front |
| 2 | 7.73mm | ~23mm | 11.42 × 8.58 | BACK | Main wide (secondary mode/logical duplicate) |
| 3 | 2.63mm | **~14mm** | 6.55 × 4.92 (1/1.95") | BACK | **Ultra-wide** 50MP f/2.0 |
| **4** | **20.10mm** | **~70mm** | 10.03 × 7.52 (1/1.28") | BACK | **★ 3x periscope 200MP f/2.2 — teleconverter attachment target** |
| 5 | 34.80mm | **~230mm** | 5.24 × 3.93 (1/2.75") | BACK | 10x periscope 50MP f/3.5 |
| 6 | 1.934mm | ~37mm | 1.79 × 1.34 | BACK | Auxiliary small sensor (multispectral/auxiliary inference) |

> dev0 and dev2 have identical focal length & sensor → appear to be same main sensor exposed with 2 IDs (full/crop or logical+physical).

## Teleconverter (Explorer)

- Hasselblad "Earth Explorer" 300mm teleconverter affixed afocally to **dev4 (3x, 70mm)** → **~300mm (≈4.28×)**, equivalent to 13x.
- Therefore the lens our app should target is **dev4 (70mm-equiv)**.

## App Selector Implications (Important)

- **Must NOT select by "longest focal length"** → that would pick dev5 (230mm, 10x).
- Correct logic: among rear cameras (and physical sub-cameras), **select the one whose 35mm-equivalent is closest to 70mm** (= dev4).
- `getCameraIdList()` IDs exposed to app may differ from HAL dev order → match at runtime by 35mm-equivalent focal length. Verify actual exposed ID/physical ID via `VendorTagInspector` in app (on-device).
- Implementation: `CameraSelector2` selects lens closest to target 35mm-equivalent focal length (default 70mm) + manual override.

## Stabilization Notes (Summary; details in vendor-tags-catalog.md / oplus-camera-explorer-analysis.md)

- Stock app enters teleconverter mode **manually** (not auto-detect). The converter's electronic chip (`explorer.chip.state`) appears to be for damage/temperature/wireless-charge safety checks and authentication.
- Stabilization controlled via Qualcomm EIS (`org.quic.camera.eisrealtime`, `sessionParameters.EISMode`) + OPPO vendor tags (`com.oplus.ois.control.mode`, `sois.custom.info`, `video.stabilization.mode`). Key is profile switching tuned to effective focal length.
