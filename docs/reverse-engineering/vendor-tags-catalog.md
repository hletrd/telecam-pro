# Find X9 Ultra — Camera Vendor Tag Catalog (Curated)

- Source: "Vendor tags" section of `dumpsys media.camera` (1,509 definitions) + confirmed actual usage from stock `OplusCamera.apk` decompilation
- Purpose: Reference for replicating teleconverter stabilization/zoom + pro controls in our app

> ⚠️ Vendor tags are accessed via `new CaptureRequest.Key<>("com.oplus...", T.class)` / `CameraCharacteristics.Key`. Third-party apps can generally **read**, but **write** may be restricted by HAL to system apps (signature/permissions) → on-device verification needed.

## 1) Stabilization / OIS (com.oplus)

| tag id | name | type | purpose (inferred) |
|---|---|---|---|
| 0x811900fc | `ois.control.mode` | int32 | **OIS control mode** — key candidate for lens/effective focal-length profile switching |
| 0x81190000 | `sois.custom.info` | byte | **Smart OIS custom parameters** |
| 0x8119009e | `video.stabilization.mode` | int32 | OPPO video stabilization mode (app-settable) |
| — | `eis.supersteady.enable` | byte | Super-steady EIS |
| — | `tele.eis.active` | byte | Telephoto EIS active (app reads) |
| 0x81190056 | `camera.dualois.value.oistoeis` | byte | OIS→EIS handoff data |
| 0x8119015b | `camera.ois.value.oistoeis` | byte | OIS→EIS |
| — | `superEis.available.target.fps.ranges` | int[] | Super EIS supported fps |
| — | `eis.workon` / `eis.record.state` | byte/int | EIS operation/recording state |

## 2) Gyro (com.oplus) — app can inject gyro for EIS

| tag id | name | type | purpose |
|---|---|---|---|
| 0x8119004f | `gyroFromAP` | float | **Gyro injected by AP (app)** — same concept as our gyro EIS approach |
| 0x81190049 | `gyroSqrCutom` | float | Gyro custom |
| 0x81190131 | `gyro.data` | float | Gyro data |
| 0x81190153 | `camera.gyro.value.gyrotoeis` | int32 | Gyro→EIS |
| 0x81190164 | `camera.gyro.common.enable` | int32 | Gyro common enable |
| 0x811900c7 | `EISPreviewGyro` | byte | Preview EIS gyro |

## 3) Zoom / FOV (com.oplus) — override 3x to 300mm/13x range

| tag id | name | type | purpose |
|---|---|---|---|
| 0x81190007 | `custom.zoom.range` | float[] (Chars) | Photo zoom range override |
| 0x81190008 | `custom.video.zoom.range` | float[] | Video zoom range |
| 0x8119000a | `custom.operation.mode.zoom.range` | float[] | Per-mode zoom range |
| 0x8119000c | `expert.zoom.range` | float[] | Expert/Pro zoom range |
| 0x8119000f | `fov.Angle` | float | FOV angle |
| — | `com.oplus.recording.zoom.active` | int (Req) | Recording zoom active |

## 4) Teleconverter "Explorer" (app config/state, not camera vendor tag)

Manual entry (not auto-detect). Chip appears for damage/temperature/wireless-charge safety & authentication checks.

- `com.oplus.feature.explorer.support`, `com.oplus.configure.explorer.enable`
- `com.oplus.explorer.switch.current.state`, `com.oplus.explorer.chip.state`, `com.oplus.explorer.hw.capacity`
- `explorer_chip_damage_tip`, `explorer_disable_by_wireless_charge_tip`, `isMeetExplorerTemperatureThreshold`
- engineer: `engineercamera.explorer.status`, `engineercamera.explorercamera.mipi.bypass.sensormode`

## 5) QTI (Qualcomm) EIS Stack (org.quic / org.codeaurora / com.qti)

| section.tag | type | purpose |
|---|---|---|
| `org.codeaurora.qcamera3.sessionParameters.EISMode` (0x8020003a) | int32 | EIS mode (session parameter) |
| `org.quic.camera.eisrealtime.EISOISMode` (0x80060006) | byte | EIS+OIS combined mode |
| `org.quic.camera.eisrealtime.RequestedMargin/StabilizationMargins/MinimumMargin` | byte | EIS crop margin (longer focal length → larger margin needed) |
| `org.quic.camera.eisrealtime.MotionIndication` | byte | Motion indication |
| `org.quic.camera.eis3enable.EISV3Enable` (0x80980000) | byte | EIS v3 |
| `org.quic.camera2.usecase_ife.PreviewPathEISCorrection` | byte | Preview EIS correction |
| `com.qti.chi.stabilizationmode.imageStabilizationMode` (0x80ec0000) | byte | Image stabilization mode |
| `org.codeaurora.qcamera3.sessionParameters.ExtendedMaxZoom` | int32 | Extended max zoom |

## 6) Bonus — Additional Pro Control Vendor Tags (app-used, worth attempting in our app)

Pro controls exposed by device (beyond standard Camera2):

- **Manual WB/tint**: `manualWB.color_tone`, `manualWB.color_tone_range`, `manualTone.cold.warm`, **`manualTone.cyan.magenta`** (tint), `manualTone.contrast`, `manualTone.saturation`
- **Pro tone**: `controlEdgeLevel`, `controlVignetteLevel`, `controlClarityLevel`, `controlHueLevel`, `controlContrastHighlightLevel`, `controlContrastShadowLevel`, `controlSaturationLevel`
- **ISO extension**: `pro.extension.iso.range/support`, `movie.extension.iso.range`, `log.extension.iso.range`
- **LOG/HDR video**: `log.video.mode`, `movie.log.enable`, `movie.hdr.enable`
- **Modes**: `supernight.mode`, `ultra.resolution.mode`, `night.*`, `ai.scene.*`, `portrait.bokeh.state`, `bokeh.level`

> These are controls not available in standard Camera2. If vendor tag writes are permitted, we can replicate stock-level pro controls (tint, clarity, vignetting, Log, etc.). If not permitted, fall back to standard Camera2 + our GL/EIS.
