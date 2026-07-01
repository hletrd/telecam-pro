# OPPO Find X9 Ultra — "Explorer" Teleconverter Mode & Its Stabilization Behavior

**Reverse-engineering analysis of the stock `OplusCamera.apk`**
**Date:** 2026-07-01
**Scope:** Authorized analysis of a legally-obtained APK on the owner's own device, to build a compatible third-party camera app.
**Sources:** `jadx-out/` (16,697 decompiled Java files), `dumpsys_camera.txt`, `vendor_tags.txt`, `all_strings.txt`.

---

## TL;DR / Summary

- **The stock app does NOT command the Explorer teleconverter's OIS/EIS focal-length change itself.** All the low-level stabilization vendor tags that would retune OIS/EIS to the ~300 mm effective focal length — `com.oplus.ois.control.mode` (0x811900fc), `com.oplus.eis.supersteady.enable` (0x81190047), `com.oplus.sois.custom.info` (0x81190000), `com.oplus.camera.dualois.value.oistoeis` (0x81190056), `com.oplus.gyroFromAP` (0x8119004f) — **appear only in the HAL vendor-tag descriptor and are NOT referenced by any string or `CaptureRequest.Key` in the decompiled app.** The app never writes them. The focal-length-appropriate OIS/EIS behavior is decided **inside the camera HAL**, triggered by the combination of (a) the tele sensor's selected operation/sensor mode, (b) the zoom ratio, and (c) the Explorer chip-state that the app passes down at session-configure time.
- **What the app actually does for stabilization:** it sets a *coarse, generic* stabilization mode string via the OPPO camera SDK — `CameraParameter.VIDEO_STABILIZATION_MODE` (tag `com.oplus.configure.video.stabilization`) to one of `video_stabilization` / `video_stabilization_ois` / `super_stabilization`. **This selection has no Explorer-specific branch** (see `va/e.java`). The HAL maps "super steady / video stabilization" onto whatever OIS/EIS profile is correct for the active lens+converter.
- **Detection is NOT automatic mode-switching.** The Explorer chip is read for **safety/health**: `com.oplus.explorer.hw.capacity` (a `CaptureResult`) is polled every frame; the value `1073741824` (0x40000000) means the chip is *damaged* → the app shows `explorer_chip_damage_tip` and force-closes the camera. The derived state (0 = bad, 1 = OK) is cached in DataManager (`key_explorer_chip_state`) and echoed back to the session via `CameraParameter.KEY_EXPLORER_CHIP_STATE` (tag `com.oplus.explorer.chip.state`). Entering "Explorer/teleconverter" is **user-initiated** (a mode/zoom selection in the UI), consistent with the device owner's report.
- **Most important single finding about stabilization:** to obtain native teleconverter stabilization from a third-party app, you cannot simply set an "Explorer OIS mode" key — there isn't one in the app. The correct-focal-length OIS/EIS is a HAL side-effect of selecting the tele physical camera in its Explorer operation/sensor mode at the right zoom, plus passing the chip-state configure key. The genuinely device-specific `com.oplus.explorer.*` tags are **not even present in this device's static vendor-tag descriptor** (only engineering tags `engineercamera.explorer.status` 0x81190102 and `engineercamera.explorercamera.mipi.(not)bypass.sensormode` 0x81190021/0x81190022 exist), so third-party `CaptureRequest`/`CaptureResult` access to them is very likely gated/unavailable.

---

## 1. How the Explorer / teleconverter is DETECTED

**Conclusion:** The chip is used for **hardware health / damage / safety gating and telemetry**, not for automatic mode switching. Selecting the teleconverter view is a manual UI action.

### 1.1 Capability flag (device supports the accessory at all)

Everywhere Explorer logic runs, it is gated by a static config boolean:

```java
CameraConfig.getConfigBooleanValue("com.oplus.feature.explorer.support")
```

Referenced in `vh/g.java`, `am/o0.java`, `xa/s.java`, `x8/c.java`, `nj/f.java`, `com/oplus/camera/Camera.java`, and `com/oplus/camera/module/BaseMode.java`.

### 1.2 Chip hardware state read path (per-frame CaptureResult)

`com/oplus/camera/module/BaseMode.java`, method `pg(OplusCaptureResult)` (lines ~7720–7749):

```java
public void pg(OplusCaptureResult oplusCaptureResult) {
    if (CameraConfig.getConfigBooleanValue("com.oplus.feature.explorer.support")) {
        Object objA = s.a(s.f28961f2, oplusCaptureResult);          // read com.oplus.explorer.hw.capacity
        if (!(objA instanceof Integer)) { this.Q0 = 0; return; }
        int iIntValue = ((Integer) objA).intValue();
        this.Q0 = iIntValue;
        int i5 = (iIntValue == 1073741824 || iIntValue == 0) ? 0 : 1; // derive chip state
        DataManager dataManager = DataManager.getInstance();
        DataKey<Integer> dataKey = x6.d.f43917v2;                     // "key_explorer_chip_state"
        if (i5 != ((Integer) dataManager.b(dataKey, 1)).intValue()) {
            DataManager.getInstance().g(dataKey, Integer.valueOf(i5));
            if (i5 == 0 && I()) {
                ((e2) j9()).v.H(R.string.explorer_chip_damage_tip, 2000, new Object[0]); // show damage tip
            } else {
                ((e2) j9()).v.hideCameraScreenHintText(R.string.explorer_chip_damage_tip);
            }
            if (1073741824 == iIntValue) {                            // 0x40000000 = fatal/damaged
                Activity activity = this.U0;
                if (activity.isFinishing()) return;
                ReportThread.postEvent("camera_stability", new ...d0(2));
                activity.finish();                                    // force-close camera
            }
        }
    }
}
```

- `s.f28961f2` is defined in `i8/s.java:697` as
  `new CaptureResult.Key<>("com.oplus.explorer.hw.capacity", Integer.class)`.
- **Semantics inferred from the code:** `0` = no/absent chip → state 0; `0x40000000` (1073741824) = **damaged** → warn + `finish()`; any other value → state 1 (chip OK).
- The derived boolean-ish state is cached in DataManager under key `key_explorer_chip_state` (`x6/d.java:819`, `f43917v2 = ... "key_explorer_chip_state"`, default `1`).

A second, redundant damage-tip site is `am/o0.java:3354`:

```java
if (CameraConfig.getConfigBooleanValue("com.oplus.feature.explorer.support")
        && ((Integer) DataManager.getInstance().b(x6.d.f43917v2, 1)).intValue() == 0
        && d0().m5().K()) {
    this.v.H(R.string.explorer_chip_damage_tip, 2000, new Object[0]);
}
```

### 1.3 Chip state pushed back into the session configuration

`com/oplus/camera/module/BaseMode.java:3221–3222`:

```java
if (CameraConfig.getConfigBooleanValue("com.oplus.feature.explorer.support")) {
    tVar.a(CameraParameter.KEY_EXPLORER_CHIP_STATE,
           (Integer) DataManager.getInstance().b(x6.d.f43917v2, 1));
}
```

- `CameraParameter.KEY_EXPLORER_CHIP_STATE` = `ConfigureKey<Integer>("com.oplus.explorer.chip.state", ...)` (`CameraParameter.java:283, 1052`).
- So the cached chip health value is fed **into the HAL at session-configure time**. This is how the HAL learns the accessory is present/healthy — the app does not run a separate "attach detect" state machine.

### 1.4 Explorer switch-state during recording (telemetry only)

`nj/n0.java:3282` calls `VideoExplorerState.processExplorerRecordingState(bArr)` with the byte[] read from
`com.oplus.explorer.switch.current.state` (`i8/s.java:702`, field `f28986k2 = new CaptureResult.Key<>("com.oplus.explorer.switch.current.state", byte[].class)`).

`com/oplus/camera/statistics/events/group202/VideoExplorerState.java` shows this only accumulates **HDR/AINR duration statistics** ("HDR_2dol_AINR_normal_time", etc.) for DCS analytics; it is not a control path. Related DCS keys: `KEY_EXPLORER_STATE`, `KEY_EXPLORER_RECORDING_ALGORITHM_STATE`, `INVAILD_EXPLORER_STATE`, `key_explorer_off_reason` (statistics events group 202 / `EventVideoRecord.java`, `EventKeys.java`).

### 1.5 System-service state / thermal & wireless-charge safety

`com/oplus/camera/d.java` is a reflective proxy onto a system service and declares:

```java
A3        = new b<>("getExplorerState", Integer.class);
Z3        = new b<>("getEnvironmentTemperatureLevel", Integer.class);
c4(...)   = new b<>("isMeetExplorerTemperatureThreshold", Boolean.class);
d4(...)   = new b<>("isHighTemperatureExit", Boolean.class);
```

These, plus the strings `explorer_disable_by_wireless_charge_tip` and `isMeetExplorerTemperatureThreshold`, confirm the chip/state machinery is oriented toward **safety** (over-temperature, wireless charging conflict, chip damage) — again, not auto mode entry.

### 1.6 Mode entry (user-initiated)

- `CameraParameter.KEY_EXPLORER_ENABLE` = `ConfigureKey<String>("com.oplus.configure.explorer.enable", ...)` is **defined** (`CameraParameter.java:284, 1053`) but **no reference to it was found anywhere in the decompiled app** — the app does not appear to toggle "explorer.enable" through this SDK key in this build.
- No `ExplorerMode`/`enterExplorerMode` class exists. Instead, Explorer support **modulates existing pipelines**: e.g. `HumanEffectFusionApiHelper.getPreviewInstance(boolean supportExplorer)` (`HumanEffectFusionApiHelper.java:266`) returns the **V3** human-effect/video-bokeh pipeline when `supportExplorer==true`, otherwise V1. `x8/c.java` gates AI-enhancement-video on explorer support + chip state.
- **Inference:** "Teleconverter/Explorer" is entered like any other tele zoom/lens selection in the UI; the app then (a) passes chip-state down, (b) picks the tele physical camera in its Explorer operation/sensor mode, and (c) the HAL adapts OIS/EIS. This matches the owner's report that the mode is entered manually.

---

## 2. What changes for STABILIZATION when Explorer is active

**Key result: the app applies only generic stabilization modes and never sets Explorer-specific OIS/EIS keys. The HAL owns the focal-length adaptation.**

### 2.1 What the app *does* set — generic video stabilization mode

`va/e.java` (a "SuperEISPresenter") is the single place that writes the stabilization mode, via the SDK key `CameraParameter.VIDEO_STABILIZATION_MODE`:

```java
// movie mode
if ("movie".equals(m())) {
    if (((String) DataManager...b(f.f44036k, "on")).equals("on"))
        ((t) tgVar).a(CameraParameter.VIDEO_STABILIZATION_MODE,
                      CameraParameter.VideoStabilizationMode.VIDEO_STABILIZATION);   // "video_stabilization"
    return;
}
boolean superSteady = D2();
if (!"masterVideo".equals(this.f24879q)) {
    if (superSteady)
        ((t) tgVar).a(VIDEO_STABILIZATION_MODE,
            n1() ? VideoStabilizationMode.SUPER_STABILIZATION_FRONT
                 : VideoStabilizationMode.SUPER_STABILIZATION);                       // "super_stabilization"
} else if (superSteady) {
    ((t) tgVar).a(VIDEO_STABILIZATION_MODE, VideoStabilizationMode.VIDEO_STABILIZATION);
} else if (!n1() && "ois".equals(m0.b(wa.a.f42725b, this.f24879q, "off"))) {
    ((t) tgVar).a(VIDEO_STABILIZATION_MODE, VideoStabilizationMode.VIDEO_OIS_STABILIZATION); // "video_stabilization_ois"
}
```

Definitions (`CameraParameter.java`):
- `VIDEO_STABILIZATION_MODE = new ConfigureKey<>(FeatureName.P_STABILIZATION, String.class, ...)` (line 1031).
- `FeatureName.P_STABILIZATION = "com.oplus.configure.video.stabilization"` (`com/oplus/ocs/camera/config/FeatureName.java:20`).
- Allowed values (`@VideoStabilizationMode`, lines 974–979):
  `"super_stabilization"`, `"super_stabilization_front"`, `"video_stabilization_ois"`, `"video_stabilization"`.

**There is no `if (explorer)` branch here.** The exact same value set is used with or without the converter. (There is also `AI_NIGHT_VIDEO_MODE = new ConfigureKey<>("com.oplus.video.stabilization.mode", Integer.class, ...)` at line 1083 — this is the *int-typed* alias of the underlying vendor tag `0x8119009e`; the SDK translates the string mode to that int internally. The jadx field name is misleading.)

### 2.2 What the app *reads* about EIS/OIS (all read-only, HAL→app)

| Field (`i8/s.java`) | Vendor tag | Type | Where read | Purpose |
|---|---|---|---|---|
| `W2` (line 741) | `com.oplus.tele.eis.active` | `Byte` (CaptureResult) | `aj/b3.java:3267` → `this.f676d3 = (b==1)?1:2` | UI/state flag: is tele-EIS currently active |
| `f28991l2` (704) | `com.oplus.camera.gyro.value.gyrotoeis` | `int[]` (CaptureResult) | `ee/e.java:342` (EISProcessor) | gyro metadata fed to app-side `OplusSuperEISPreviewHelper` |
| `f28996m2` (705) | `com.oplus.camera.ois.value.oistoeis` | `byte[]` (CaptureResult) | `ee/e.java:343` | OIS metadata fed to app-side super-EIS |
| `f28961f2` (697) | `com.oplus.explorer.hw.capacity` | `Integer` (CaptureResult) | `BaseMode.pg()` | chip health (see §1.2) |
| `f28986k2` (702) | `com.oplus.explorer.switch.current.state` | `byte[]` (CaptureResult) | `VideoExplorerState` | recording telemetry |
| `f29015q3` (765) | `com.oplus.superEis.available.target.fps.ranges` | `int[]` (Characteristics) | zoom/eis capability query | which fps ranges support super-EIS |

`ee/e.java` (`EISProcessor`, "compiled from: EISProcessor.java") uses `OplusSuperEISPreviewHelper` and consumes the gyro/OIS-to-EIS metadata for **app-side Live-Photo super-EIS** — this is a generic feature, not Explorer-specific, and it only *reads* HAL-produced gyro/OIS values.

### 2.3 The Explorer/focal-length OIS/EIS tags the app never touches

Present in the HAL vendor-tag descriptor (`vendor_tags.txt` / `dumpsys_camera.txt`) but **absent from `all_strings.txt` and from any `CaptureRequest.Key` literal in the app** → the app does not write them:

- `com.oplus.ois.control.mode` — 0x811900fc (int32)
- `com.oplus.eis.supersteady.enable` — 0x81190047 (byte)
- `com.oplus.sois.custom.info` — 0x81190000 (byte)
- `com.oplus.camera.dualois.value.oistoeis` — 0x81190056 (byte)  *(note: the app instead reads the differently-named result `camera.ois.value.oistoeis` 0x8119015b)*
- `com.oplus.gyroFromAP` — 0x8119004f (float)  *(an "app-provides-gyro-to-HAL" channel — see §5; the app does not use it)*

**Therefore the retuning of OIS/EIS to the ~300 mm effective focal length is performed by the HAL**, using the chip-state configure key + the tele sensor's operation/sensor mode + zoom ratio. The app's role is limited to (a) selecting a generic stabilization mode, (b) passing chip health down, and (c) reading back status (`tele.eis.active`, gyro/OIS metadata) for UI and app-side super-EIS.

---

## 3. How ZOOM / FOV is changed to the 300 mm range

**Conclusion:** zoom limits are **read from the HAL as characteristics** and are keyed by operation mode; they are not hard-coded in the app. With the converter, the HAL returns a shifted zoom range for the tele operation mode.

Relevant `CameraCharacteristics.Key`s (`i8/s.java`):

```java
f28957e3 = new CameraCharacteristics.Key<>("com.oplus.custom.zoom.range", float[].class);            // 753 / 0x81190007
f28962f3 = new CameraCharacteristics.Key<>("com.oplus.expert.zoom.range", float[].class);            // 754 / 0x8119000c
f28982j3 = new CameraCharacteristics.Key<>("com.oplus.custom.operation.mode.zoom.range", float[].class); // 758 / 0x8119000a
J3       = new CameraCharacteristics.Key<>("com.oplus.fov.Angle", float[].class);                    // 786 / 0x8119000f
```

They are consumed in `i8/k1.java` (`OplusCameraCharacteristics`):

- `getSupportedZoomRange` reads `expert.zoom.range` (`f28962f3`) / `custom.zoom.range` (`f28957e3`) (lines 126, 248, 494).
- `getCustomZoomRange(int operationMode, int fps, Size)` (lines 412–459) parses `custom.operation.mode.zoom.range` (`f28982j3`) as a flat `float[]` in **strides of 10**:

```java
float[] fArr = (float[]) h(s.f28982j3);
for (int i = 0; i < fArr.length; i += 10) {
    int operationMode = (int) fArr[i];
    String key = ((int) fArr[i+3]) + "_" + new Size((int) fArr[i+1], (int) fArr[i+2]); // fps_WxH
    List<Float> range = [ fArr[i+4], fArr[i+5], fArr[i+6], fArr[i+7] ];                 // min, max, ...
    map.put(operationMode -> { key -> range });
}
// lookup: map.get(operationMode).get(fps + "_" + size)  => [minZoom, maxZoom, ...]
```

So the **min/max zoom ratio for a given operation mode + fps + output size is dictated by the HAL characteristic**. When the Explorer converter is attached, the tele camera's operation mode reports a zoom range shifted to the converter's reach (the periscope's native ~70 mm-equiv becomes ~300 mm-equiv, ≈4.28×). The stock app simply consumes whatever `custom.operation.mode.zoom.range` the HAL advertises for that mode; it does not compute the 4.28× itself.

Supporting result tags (read for the zoom HUD): `com.oplus.digital.zoom.ratio` (0x811900de), `com.oplus.custom.true.zoomratio` (0x811900e8, `i8/s.java:699` `f28971h2`), and `com.oplus.zoom.state`/`com.oplus.zoom.frame.info`. Per-sensor equivalent focal length is exposed as `com.oplus.sensor.properties.info.equivalent.focallength` (0x811900da, `CaptureResult`).

**Inference (unverified numerically):** the exact 300 mm zoom-range float values live in the device HAL and would surface in `custom.operation.mode.zoom.range` only when the accessory is attached; they are not in the static APK.

---

## 4. Camera id / physical id / operation mode used for the 70 mm tele + Explorer

- **Physical camera:** the 3× periscope (native ≈20.10 mm / ~70 mm-equiv). `android.lens.info.availableFocalLengths` in `dumpsys_camera.txt` lists a single focal length per physical camera (each is `float[1]`); the periscope entry is the tele one the converter attaches to. Logical/physical typing is exposed by OPPO tags `com.oplus.logical.camera.type`, `com.oplus.supported.cameraid.type` (0x81190002, byte) and QTI `org.codeaurora.qcamera3.logicalCameraType` / `logical_camera_type` (0x80b00000).
- **Operation mode:** zoom ranges are keyed by an **operation-mode int** (`custom.operation.mode.zoom.range`, §3). The specific int used for the tele+Explorer session is HAL/config-driven and not hard-coded as a literal in the app; the app looks it up dynamically.
- **Engineering sensor modes for the converter (from the vendor descriptor):**
  - `com.oplus.engineercamera.explorercamera.mipi.bypass.sensormode` — 0x81190021 (int32)
  - `com.oplus.engineercamera.explorercamera.mipi.notbypass.sensormode` — 0x81190022 (int32)
  - `com.oplus.engineercamera.explorer.status` — 0x81190102 (int32)

  These "bypass / not-bypass MIPI sensor mode" tags strongly imply the HAL selects a **dedicated sensor readout mode for the periscope when the converter's optical path is engaged** (bypass vs. not-bypass of some MIPI stage). They live in the `engineercamera` (factory/engineering) namespace — evidence the true Explorer switching is a HAL/sensor concern, exposed to engineering/system code, not to the normal capture API. (No app code references these; they are named only in the descriptor.)

---

## 5. App-provided gyro (`gyroFromAP`) / EIS margin tuning tied to Explorer

- `com.oplus.gyroFromAP` (0x8119004f, float) exists in the descriptor as an **app→HAL gyro injection** channel, but **the stock camera app does not write it** (absent from `all_strings.txt`). The app instead *reads* HAL-computed `gyro.value.gyrotoeis` / `ois.value.oistoeis` (§2.2) and runs its own `OplusSuperEISPreviewHelper` for Live-Photo super-EIS in `ee/e.java`.
- **No Explorer-specific EIS margin tuning was found in the app.** The QTI real-time EIS margin/mode tags mentioned in the brief — `org.quic.camera.eisrealtime.EISOISMode` / `RequestedMargin` / `StabilizationMargins`, `org.codeaurora.qcamera3.sessionParameters.EISMode`, `com.qti.chi.stabilizationmode.imageStabilizationMode` — **do not appear in `all_strings.txt` at all**, i.e. they are not referenced by the app; they are HAL/CHI-internal. The only EIS look-ahead result the app reads is `org.quic.camera.eislookahead.FrameDelay` (`i8/s.java:683`).
- **Inference:** any Explorer-specific EIS margin (a tele view needs a larger stabilization crop margin) is applied inside the HAL/CHI EIS node, keyed off the operation mode + chip state, not requested by the app.

---

## 6. Full inventory of OPPO/QTI zoom-, stabilization-, OIS-, EIS-, gyro-, explorer-, tele-, FOV-related keys found in `jadx-out`

All entries below are literal `new *.Key<>(...)` strings found in the decompiled app (primarily `i8/s.java`, plus `CameraParameter.java` SDK wrappers). Hex IDs are from `vendor_tags.txt` / `dumpsys_camera.txt` where the tag exists in the descriptor.

### 6.1 `CameraCharacteristics.Key` (device capabilities — readable)

| Field | Tag string | Type | Hex | Purpose |
|---|---|---|---|---|
| `f28957e3` | `com.oplus.custom.zoom.range` | float[] | 0x81190007 | photo custom zoom range |
| `f28962f3` | `com.oplus.expert.zoom.range` | float[] | 0x8119000c | pro/expert zoom range |
| `f28967g3` | `com.oplus.ultrawide.zoom.range` | float[] | — | ultrawide zoom range |
| `f28972h3` | `com.oplus.custom.video.zoom.range` | float[] | — | video zoom range |
| `f28977i3` | `com.oplus.custom.video.60fps.zoom.range` | float[] | — | 60fps video zoom range |
| `f28982j3` | `com.oplus.custom.operation.mode.zoom.range` | float[] | 0x8119000a | **per-operation-mode zoom range (drives the tele/Explorer reach)** |
| `f29015q3` | `com.oplus.superEis.available.target.fps.ranges` | int[] | — (not in descriptor) | fps ranges that support super-EIS |
| `F3` | `com.oplus.izoom.ability.support` | Integer | — | intelligent-zoom capability |
| `G3` | `com.oplus.mvg.fastzoom.point` | Integer | — | fast-zoom anchor point |
| `J3` | `com.oplus.fov.Angle` | float[] | 0x8119000f | field-of-view angles per lens |
| `—` | `com.oplus.logical.camera.type` | (int) | — | logical camera typing |
| `—` | `com.oplus.supported.cameraid.type` | byte | 0x81190002 | supported camera-id typing |

### 6.2 `CaptureRequest.Key` (settable by app)

| Field | Tag string | Type | Hex | Purpose |
|---|---|---|---|---|
| `E0` | `com.oplus.only.zoom.change` | Integer | — | signal a zoom-only change |
| `F0` | `com.oplus.recording.zoom.active` | Integer | — | zoom active during recording |
| `N` | `com.oplus.preview.inSensorZoom.en` | int[] | — | in-sensor zoom enable |
| (SDK) `VIDEO_STABILIZATION_MODE` | `com.oplus.configure.video.stabilization` | String | (int alias 0x8119009e) | **video stabilization mode select (generic)** |
| (SDK) `AI_NIGHT_VIDEO_MODE` (jadx misnamed) | `com.oplus.video.stabilization.mode` | Integer | 0x8119009e | int alias of stabilization mode |
| (SDK) `KEY_EXPLORER_CHIP_STATE` | `com.oplus.explorer.chip.state` | Integer | — (not in descriptor) | **pass chip health to HAL at configure** |
| (SDK) `KEY_EXPLORER_ENABLE` | `com.oplus.configure.explorer.enable` | String | — | defined but **unused in app** |

### 6.3 `CaptureResult.Key` (read-only status from HAL)

| Field | Tag string | Type | Hex | Purpose |
|---|---|---|---|---|
| `W2` | `com.oplus.tele.eis.active` | Byte | 0x811900d4 | tele-EIS active flag |
| `f28991l2` | `com.oplus.camera.gyro.value.gyrotoeis` | int[] | 0x81190153 | gyro metadata → app super-EIS |
| `f28996m2` | `com.oplus.camera.ois.value.oistoeis` | byte[] | 0x8119015b | OIS metadata → app super-EIS |
| `f28961f2` | `com.oplus.explorer.hw.capacity` | Integer | — (not in descriptor) | **Explorer chip health** |
| `f28986k2` | `com.oplus.explorer.switch.current.state` | byte[] | — (not in descriptor) | Explorer recording telemetry |
| `f28971h2` | `com.oplus.custom.true.zoomratio` | Float | 0x811900e8 | true (physical) zoom ratio |
| `Q3`/`R3` | `com.oplus.digital.zoom.ratio` | Float | 0x811900de | digital zoom ratio |
| `U0`/`V0` | `com.oplus.zoom.state` / `com.oplus.zoom.frame.info` | int[]/Integer | — | zoom state/frame |
| `A2` | `com.oplus.gyroSqrCutom` | float[] | — | gyro-square custom metric |
| `—` | `com.oplus.camera.lens.dirty` | — | — | lens-dirty detection |
| `—` | `com.oplus.sensor.properties.info.equivalent.focallength` | int32 | 0x811900da | equivalent focal length |
| `T1` | `org.quic.camera.eislookahead.FrameDelay` | (int) | — | QTI EIS look-ahead delay |

### 6.4 Vendor-descriptor tags that exist in HAL but are **NOT referenced by the app** (HAL-internal — the actual OIS/EIS focal-length machinery)

| Tag string | Type | Hex | Note |
|---|---|---|---|
| `com.oplus.ois.control.mode` | int32 | 0x811900fc | **OIS mode control — HAL-owned** |
| `com.oplus.eis.supersteady.enable` | byte | 0x81190047 | **super-steady EIS enable — HAL-owned** |
| `com.oplus.sois.custom.info` | byte | 0x81190000 | **sensor-OIS custom info — HAL-owned** |
| `com.oplus.camera.dualois.value.oistoeis` | byte | 0x81190056 | dual-OIS→EIS (HAL-owned; app reads the sibling `camera.ois.value.oistoeis`) |
| `com.oplus.gyroFromAP` | float | 0x8119004f | app→HAL gyro injection channel (unused by app) |
| `com.oplus.engineercamera.explorercamera.mipi.bypass.sensormode` | int32 | 0x81190021 | **Explorer sensor mode (bypass) — engineering** |
| `com.oplus.engineercamera.explorercamera.mipi.notbypass.sensormode` | int32 | 0x81190022 | **Explorer sensor mode (not-bypass) — engineering** |
| `com.oplus.engineercamera.explorer.status` | int32 | 0x81190102 | **Explorer status — engineering** |

*(QTI EIS session/margin tags `org.quic.camera.eisrealtime.*`, `org.codeaurora.qcamera3.sessionParameters.EISMode`, `com.qti.chi.stabilizationmode.imageStabilizationMode` were searched for and are **not present in `all_strings.txt`** — not referenced by the app.)*

---

## 7. Implications for our third-party app

### 7.1 What we can realistically set from a normal `CaptureRequest`

These are ordinary vendor tags a third-party app *may* be able to set if the platform doesn't reject non-system callers:

- **`com.oplus.video.stabilization.mode`** (0x8119009e, int32) — the generic stabilization selector. This is the only "stabilization mode" the stock app effectively drives (via its string SDK alias). Setting it to the super-steady/OIS value is the closest analog to what the stock app does. **It does not, by itself, retune OIS to 300 mm** — that is a HAL side-effect of the lens/operation-mode.
- **Zoom** via standard `CONTROL_ZOOM_RATIO` and reading `com.oplus.custom.operation.mode.zoom.range` (0x8119000a) / `com.oplus.expert.zoom.range` (0x8119000c) to discover the converter's max reach for the chosen operation mode.
- **`com.oplus.explorer.chip.state`** (`ConfigureKey<Integer>`) — replicating the stock app, we could pass the cached chip-state at session configure. **Risk: this tag is NOT in the device's static vendor-tag descriptor** (see 7.3), so a raw `CaptureRequest.Key` write may be silently dropped or rejected; it may only be honored through the OPPO OCS SDK.

### 7.2 The tags that would *actually* force 300 mm-appropriate OIS/EIS — and why they're risky

The genuine focal-length adaptation lives in:
`com.oplus.ois.control.mode` (0x811900fc), `com.oplus.eis.supersteady.enable` (0x81190047), `com.oplus.sois.custom.info` (0x81190000), and the engineering sensor-mode tags (0x81190021/0x81190022/0x81190102).

- **These are never written by the stock app**, so there is no observed "correct value" to copy — we would be guessing.
- The Explorer/engineering tags sit in the `engineercamera` namespace and the `com.oplus.explorer.*` app-facing tags are **absent from this device's vendor-tag descriptor**, which strongly suggests they are **gated to the system camera app / factory tooling** (registered dynamically, or filtered for non-system UIDs). A third-party `CaptureRequest` setting them will most likely be ignored or throw.
- **Best-effort strategy (inference):** select the **periscope physical camera** and drive `CONTROL_ZOOM_RATIO` into the converter's advertised range while requesting `com.oplus.video.stabilization.mode = super-steady`, and let the HAL apply the tele OIS/EIS profile. Optionally probe whether `com.oplus.explorer.chip.state` is accepted. Do **not** rely on being able to set `ois.control.mode` / `eis.supersteady.enable` / `sois.custom.info` directly.

### 7.3 Gating summary (system-app vs. third-party)

| Capability | Mechanism | Third-party feasibility |
|---|---|---|
| Generic video stabilization | `com.oplus.video.stabilization.mode` (0x8119009e) | **Likely** (standard vendor tag) — but no tele-specific effect on its own |
| Discover converter zoom reach | `custom.operation.mode.zoom.range` etc. (characteristics) | **Likely readable** |
| Read tele-EIS/gyro/OIS status | `tele.eis.active`, `gyro.value.gyrotoeis`, `ois.value.oistoeis` | **Likely readable** (result tags) |
| Pass Explorer chip state | `com.oplus.explorer.chip.state` (ConfigureKey) | **Uncertain** — tag not in static descriptor; may need OCS SDK / system UID |
| Force OIS/EIS to 300 mm profile | `ois.control.mode`, `eis.supersteady.enable`, `sois.custom.info`, engineering sensor modes | **Unlikely / gated** — not app-written; not exposed to normal capture API; likely system/factory only |
| Explorer status/enable | `engineercamera.explorer.*` (0x81190021/22/102), `configure.explorer.enable` | **Gated** — engineering namespace / unused even by the stock app |

**Bottom line:** native teleconverter stabilization is obtained by *lens + operation-mode + zoom selection that makes the HAL engage the Explorer sensor mode*, not by any single OIS/EIS key the app sets. Our app should focus on selecting the periscope camera in the converter's zoom range with `super_stabilization` requested, and treat the low-level `ois.control.mode` / `eis.supersteady.enable` / `sois.custom.info` / `explorer.*` tags as **read-only or system-gated** until testing proves otherwise on the device.

---

## Appendix: Key files (absolute paths)

- Vendor-tag Key repository: `…/re/jadx-out/sources/i8/s.java`
- OPPO camera SDK parameter defs: `…/re/jadx-out/sources/com/oplus/ocs/camera/CameraParameter.java`
- SDK feature names: `…/re/jadx-out/sources/com/oplus/ocs/camera/config/FeatureName.java`
- Chip-state read / damage handling / configure push: `…/re/jadx-out/sources/com/oplus/camera/module/BaseMode.java` (`pg()` ~7720; configure ~3221)
- DataManager chip-state key: `…/re/jadx-out/sources/x6/d.java` (`f43917v2` = `key_explorer_chip_state`)
- Stabilization mode setter (SuperEISPresenter): `…/re/jadx-out/sources/va/e.java`
- Zoom-range characteristics parser (OplusCameraCharacteristics): `…/re/jadx-out/sources/i8/k1.java`
- tele.eis.active reader: `…/re/jadx-out/sources/aj/b3.java` (~3267)
- App-side EIS processor (gyro/OIS→EIS): `…/re/jadx-out/sources/ee/e.java`
- Explorer recording telemetry: `…/re/jadx-out/sources/com/oplus/camera/statistics/events/group202/VideoExplorerState.java`
- System-service proxy (thermal/state): `…/re/jadx-out/sources/com/oplus/camera/d.java`
- Explorer-aware pipeline selection: `…/re/jadx-out/sources/com/oplus/ocs/camera/HumanEffectFusionApiHelper.java`
- AI-enhancement gating on explorer support/chip: `…/re/jadx-out/sources/x8/c.java`
- Second damage-tip site: `…/re/jadx-out/sources/am/o0.java` (~3354)
