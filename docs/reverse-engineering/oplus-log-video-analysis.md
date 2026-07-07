# OPPO Find X9 Ultra — HAL-native Log Video (`com.oplus.log.video.mode`)

**Reverse-engineering + on-device analysis of the stock O-Log recording path**
**Date:** 2026-07-06
**Scope:** Authorized analysis of the legally-obtained `OplusCamera.apk` (v pulled from
`/product/app/OplusCamera/OplusCamera.apk`) on the owner's own device, to build a compatible
third-party camera app.
**Tools:** `jadx` full decompile (16k+ classes), `adb shell dumpsys media.camera`, real recordings
pulled + `ffprobe`/`ffmpeg` + OPPO's official O-Log/O-Log2 `.cube` LUTs.

---

## TL;DR

- The stock app records O-Log through the **OPPO OCS camera SDK** (`com.oplus.ocs.camera`), not raw
  Camera2. The relevant SDK keys are **session-configure** keys:
  - `KEY_CONFIGURE_LOG_VIDEO_MODE` → vendor tag **`com.oplus.log.video.mode`** (Integer)
  - `KEY_MOVIE_LOG_ENABLE` → **`com.oplus.movie.log.enable`** (Byte)
  - `KEY_CONFIGURE_MASTER_VIDEO_CUSTOM_LUT_NAME` → `com.oplus.customize.lut.file.name` (byte[])
  - `KEY_LOG_VIDEO_PREVIEW_MONITOR_TYPE` → `com.oplus.log.video.preview.monitor.type` (Integer)
  - `VIDEO_DATASPACE` → `com.oplus.configure.dataspace` (Integer)
- On **this device**, `com.oplus.log.video.mode` is advertised in the tele camera's
  `android.request.availableRequestKeys` **and** `availableSessionKeys`, so a third-party app can set
  it through **raw Camera2** (`CaptureRequest.Key` + `SessionConfiguration.setSessionParameters`).
  `com.oplus.movie.log.enable` is **not** exposed — so `log.video.mode` alone is the on/off select.
- **Device-verified:** setting `com.oplus.log.video.mode = 1` makes the ISP emit a genuine
  **scene-referred log stream** (flat, low contrast, lifted blacks; mean luma ≈ half of the SDR
  stream) with our GL curve OFF. Values 1 and 2 gave byte-identical output → the key is on/off here.
- **Caveat:** the resulting log is **not** a drop-in for OPPO's published O-Log2 (or O-Log gen-1)
  `→Rec709` LUT — a naive ffmpeg round-trip leaves a warm/red cast. It appears to be
  scene-referred **without baked white balance** (correct for true log; the colorist sets WB in
  grade), and possibly without the exact color-management the stock app pairs via `VIDEO_DATASPACE` /
  `customize.lut.file.name`. For a **LUT-accurate O-Log2 deliverable**, the app's GL O-Log2 path
  (white-paper OETF, `ColorTransfer.LOG`) is exact and round-trips cleanly with OPPO's LUT (verified).

---

## 1. How the stock app selects log (decompiled)

The SDK key table (`com/oplus/ocs/camera/CameraParameter.java`):

```java
// all ConfigureKey → applied at session-configure time (session parameters)
KEY_CONFIGURE_LOG_VIDEO_MODE            = ConfigureKey<Integer>("com.oplus.log.video.mode")
KEY_MOVIE_LOG_ENABLE                    = ConfigureKey<Byte>   ("com.oplus.movie.log.enable")
KEY_CONFIGURE_MASTER_VIDEO_CUSTOM_LUT_NAME = ConfigureKey<byte[]>("com.oplus.customize.lut.file.name")
KEY_LOG_VIDEO_PREVIEW_MONITOR_TYPE      = ConfigureKey<Integer>("com.oplus.log.video.preview.monitor.type")
VIDEO_DATASPACE                         = ConfigureKey<Integer>("com.oplus.configure.dataspace")
```

The feature lives in the `com.oplus.camera.feature.logvideo` module (LUT picker UI in
`logvideo/mode/EditorLutSelectItem.java`, state flags `isLogVideoOpened` in `com/oplus/camera/d.java`,
the toggle entry point `AndroidTestAdapter.setProfessionalVideoLogState`). The app pushes the state
into the OCS SDK, which resolves the ConfigureKey string to the HAL vendor tag on the real Camera2
session. The concrete key strings are what a raw-Camera2 app reuses.

`ConfigureKey` = **session parameter** (chosen at pipeline-configure time), which is why the log mode
must ride in `SessionConfiguration.setSessionParameters(...)`, not only in per-frame requests.

## 2. What third parties can reach (dumpsys)

`dumpsys media.camera`, tele (standalone id `4`) static info:

| vendor tag | id | in requestKeys? | in sessionKeys? |
|---|---|---|---|
| `com.oplus.log.video.mode` | 0x811901e5 | ✅ | ✅ |
| `com.oplus.customize.lut.file.name` | 0x811901e4 | ✅ | ✅ |
| `com.oplus.movie.log.enable` | 0x81190063 | ❌ | ❌ |
| `com.oplus.movie.hdr.enable` | 0x81190064 | ❌ | ❌ |

So `log.video.mode` (and, if ever needed, a custom LUT name) are settable from a third-party app;
the `movie.log.enable` / `movie.hdr.enable` byte gates are HAL-internal only.

`android.tonemap.availableToneMapModes = [0,1,2]` and `maxCurvePoints = 512` — i.e.
`TONEMAP_MODE_CONTRAST_CURVE` is also available as a standard-API fallback for a client-supplied
curve, but the vendor log key is the exact stock path and needs no per-frame 512-point curve upload.

## 3. On-device verification (2026-07-06)

Same framing (300 mm, static wall), three matched clips, GL curve passthrough, Y-plane means
(tag-independent, 8-bit):

| `log.video.mode` | mean Y | look |
|---|---|---|
| OFF (0) | 203 | bright, contrasty display-referred |
| 1 | 91 | flat, low-contrast, lifted blacks — **log** |
| 2 | 91 | identical to 1 |

- logcat: `CameraController: vendor log.video.mode=1 applied` + `Session configured (... vendorLog=1)`.
- No crash; the tele session configures with the vendor session parameter set.
- The dramatic flattening with the GL curve OFF proves the log is applied **inside the ISP to sensor
  data**, i.e. genuinely scene-referred — the thing an app cannot get from the display-referred SDR
  stream.

### Curve identity (inconclusive)

Applying OPPO's official `O-Log2-to-Rec709_Gamma24` and `O-Log-Rec.709 Gamma2.4` LUTs to the HAL clip
(correctly tagged BT.2020 full-range) both leave a warm/red cast rather than a neutral restoration.
**Control:** the same O-Log2 LUT on the app's own GL-generated O-Log2 clip restores a clean neutral
grey — so the ffmpeg+LUT pipeline is correct and the HAL curve is simply not that LUT's input. Most
likely the HAL log is scene-referred without display white-balance (a grading decision) and/or needs
the stock app's `VIDEO_DATASPACE` + custom-LUT color management to become picture-ready. Confirming
the exact IDT belongs in a grading tool (Resolve CST), out of scope for a quick ffmpeg check.

## 4. Implementation in this app

`ManualControls`/engine: `VendorLogMode { OFF(0), ON(1) }`, surfaced in Pro sheet → Advanced → "Native
Log (HAL, experimental)". `CameraController` builds `CaptureRequest.Key("com.oplus.log.video.mode",
Integer)` and sets it as a **session parameter** (from `TEMPLATE_RECORD`) **and** on every
repeating/still request, fully guarded (a rejected vendor tag never kills the preview). Changing it
reopens the session (session key). When ON: the GL curve is bypassed and the file is force-tagged the
LOG profile (BT.2020 full-range) so players don't tone-map it. Not persisted across launches.

## 5. Video stabilization (`com.oplus.video.stabilization.mode`) — implemented 2026-07-07

Same shape as the log key. The stock app's IS drives the SDK `VIDEO_STABILIZATION_MODE`
(`com.oplus.configure.video.stabilization`, String: `video_stabilization` / `video_stabilization_ois`
/ `super_stabilization`), which the OCS SDK maps to the int vendor tag
`com.oplus.video.stabilization.mode` (0x8119009e). The HAL then applies the OIS/EIS profile for the
active lens.

- The tele advertises standard `android.control.availableVideoStabilizationModes = [0,1,2]`
  (OFF / ON / **PREVIEW_STABILIZATION**), and both the standard `videoStabilizationMode` (0x10011) and
  the vendor int (0x8119009e) are in its `availableRequestKeys` + `availableSessionKeys` — so a
  raw-Camera2 app can drive HAL video stabilization directly.
- **Why it matters:** at a fixed video shutter (1/60 s) the per-frame motion blur is set by the
  shutter; only **OIS** (lens moves during the exposure) reduces it. App-side gyro EIS warps whole
  frames and cannot de-blur. Engaging the HAL video-stab turns OIS on and lets the HAL run its
  combined OIS+EIS.
- **Device-verified (2026-07-07):** setting `CONTROL_VIDEO_STABILIZATION_MODE = PREVIEW_STABILIZATION`
  (2) on the tele gives result metadata `ois=1, vstab=2` — OIS physically engaged at 1/30 s, preview
  live, 4K HEVC recording valid, no crash. Implemented as `VideoStabMode { OFF/GYRO/STANDARD/ENHANCED }`
  (default ENHANCED); the vendor int is mirrored best-effort. This replaces the earlier "force
  video-stab OFF + client gyro EIS" approach (kept as the `GYRO` option).

## 6. Full vendor-feature audit (2026-07-07)

Every `com.oplus.*` / `org.codeaurora.qcamera3.*` key that is in the tele's
`availableRequestKeys`+`availableSessionKeys`, cross-checked against what the stock app drives and
what actually works from a third-party Camera2 app:

| Feature | Vendor key(s) | Status |
|---|---|---|
| HAL-native log | `com.oplus.log.video.mode` | ✅ implemented (§1–4), verified |
| Video stabilization | `com.oplus.video.stabilization.mode` + std `CONTROL_VIDEO_STABILIZATION_MODE` | ✅ implemented, verified `ois=1 vstab=2` |
| Directional audio | `vendor_audiorecord_effect_type/focus_angle/focus_zoom/orientation` | ✅ implemented (Sound Focus/Stage); HAL `track_support=true` |
| Auto HDR | `org.codeaurora.qcamera3.sessionParameters.EnableAutoHDR` + `HDRMode` (=1, the sole advertised `supportedHDRmodes.HDRModes`) | ✅ implemented, session reconfig clean + capture OK |
| In-sensor zoom | `…sessionParameters.EnableInsensorZoom` | ✅ implemented, verified |
| Ideal RAW | `…sessionParameters.EnableIdealRAW` | ⛔ gated — session configures but RAW capture then fails silently (no DNG; CaptureSession error). |
| Higher bitrate | (not a vendor key) | ✅ Ultra/Max presets, 120 Mbps ceiling; HEVC 4K30 Max ≈134 Mbps verified |
| APV pro-intra | `video/apv` (`c2.qti.apv.encoder`) | ⛔ gated — HW encoder exists but MediaMuxer rejects APV-in-MP4, errors the encoder mid-drain |
| Macro | `com.oplus.macro.closeup.enable` | ✗ excluded — physically meaningless through a 300 mm afocal converter |
| Custom LUT | `com.oplus.customize.lut.file.name` | ✗ excluded — HAL reads only the stock app's own LUT path; inert for third parties |
| Dolby Vision | `video/dolby-vision` (`c2.qti.dv.encoder`) | detected (`EncoderCaps.hasDolbyVision`), not wired (clean DV-in-MP4 muxing is non-trivial) |

Notable read-only characteristics (cannot be set): `org.codeaurora.qcamera3.platformCapabilities.
ExtendedMaxZoom`, `supportedHDRmodes.HDRModes`. The Explorer-specific OIS/EIS focal tags
(`com.oplus.ois.*`, `eisrealtime`) remain absent from the exposed key set (see
`oplus-camera-explorer-analysis.md`).

**Pattern:** a vendor key being in `availableRequestKeys` means the framework will *accept* it, but
NOT that the downstream pipeline honors it end-to-end — Ideal RAW and APV both configure cleanly yet
break capture. Every vendor feature must be verified through to a saved file, not just to
"session configured".

See also: `oplus-camera-explorer-analysis.md` (Explorer/stabilization), `vendor-tags-catalog.md`.
