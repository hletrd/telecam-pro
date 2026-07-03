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

## Tele camera (dev4 / standalone id "4") — capture capabilities & 3A behavior

Source: `adb shell dumpsys media.camera` static characteristics for Camera ID 4 + live app 3A logs,
captured 2026-07-03 on the standalone tele the app actually opens.

- **`REQUEST_AVAILABLE_CAPABILITIES`**: `BACKWARD_COMPATIBLE, CONSTRAINED_HIGH_SPEED_VIDEO, RAW,
  YUV/PRIVATE_REPROCESSING, READ_SENSOR_SETTINGS, MANUAL_SENSOR, BURST_CAPTURE,
  MANUAL_POST_PROCESSING, STREAM_USE_CASE, DYNAMIC_RANGE_TEN_BIT, COLOR_SPACE_PROFILES,
  LOGICAL_MULTI_CAMERA`. So full manual sensor + RAW + 10-bit + high-speed are all advertised on the
  standalone id — the app doesn't need the logical multicamera for these.
- **`SENSOR_INFO_SENSITIVITY_RANGE`**: `100 … 9100` (ISO).
- **`SENSOR_INFO_EXPOSURE_TIME_RANGE`**: `83063 … 30000000000` ns ≈ **1/12000 s … 30 s**.
- **`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`** (int32[18]): includes `[10,10] [15,15] [10,22]
  [10,24] [24,24] [10,30] [30,30] [10,60] [60,60]`. Note the **`[10,30]`** low-floor range — the app
  uses `CameraCaps.autoFpsRange()` to pick it so AE can lengthen exposure in dim light.
- **`CONTROL_AF_AVAILABLE_MODES`** = byte[6], **`CONTROL_AE_AVAILABLE_MODES`** present, AE/AF LOCK
  available — AF/AE are NOT capability-gapped on the standalone tele.

**Observed 3A behavior (live logs):** with a fixed `[30,30]` target-fps range AE converges
(`aeState=2`) but pins exposure at **1/30 s** (can't brighten a dark scene); AF at `AF_MODE=4`
(CONTINUOUS) `PASSIVE_SCAN`es without locking in low contrast. Fixes in the app: low-floor auto fps
range (brighter live view) + tap-to-focus forcing `AF_MODE_AUTO` for a one-shot region scan that
LOCKS (`afState` then reaches FOCUSED). Through an f/2.2 tele at night both are near the physical
floor — retest AE/AF in normal light.

## Video encoders (Snapdragon 8 Gen 5 "Elite")

Source: `adb shell cat /vendor/etc/media_codecs*.xml` + `MediaCodecList`, captured 2026-07-03. Used
by `video/EncoderCaps.kt` (runtime `MediaCodecList` scan) to decide which codecs to offer.

| MIME | Encoder | HW? | Notes |
|---|---|---|---|
| `video/avc` | `c2.qti.avc.encoder` | HW | H.264 (MP4). |
| `video/hevc` | `c2.qti.hevc.encoder` (+`.hdr`, `.cq`) | HW | HEVC Main/Main10; HDR variant. Default. |
| `video/dolby-vision` | `c2.qti.dv.encoder` | HW | Dolby Vision — detected; optionally exposed. |
| `video/av01` | `c2.android.av1.encoder` | **SW only** | No HW AV1 → label "slow/SW", gate to ≤4K. |

- Encoder size limits report large maxes (e.g. HEVC up to ~4096×… / 8K via tiling per profile) and
  frame-rate up to 120 — so 8K30 / 4K120 are encoder-feasible; actual availability is gated by the
  **selected camera's** `StreamConfigurationMap` + high-speed configs (the tele may not offer 8K/120;
  the main camera does). `VideoFrameRate` enforces 8K≤30 and 120 only where a high-speed config
  exists.
- **Open-Gate** = recording the full 4:3 sensor readout (not a codec feature) — pick a 4:3 recording
  size from the camera's sizes.

## Stabilization Notes (Summary; details in vendor-tags-catalog.md / oplus-camera-explorer-analysis.md)

- Stock app enters teleconverter mode **manually** (not auto-detect). The converter's electronic chip (`explorer.chip.state`) appears to be for damage/temperature/wireless-charge safety checks and authentication.
- Stabilization controlled via Qualcomm EIS (`org.quic.camera.eisrealtime`, `sessionParameters.EISMode`) + OPPO vendor tags (`com.oplus.ois.control.mode`, `sois.custom.info`, `video.stabilization.mode`). Key is profile switching tuned to effective focal length.
