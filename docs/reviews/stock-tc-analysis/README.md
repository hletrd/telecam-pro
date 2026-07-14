# Stock OplusCamera TC vs non-TC analysis (2026-07-14, PMA110)

On-device comparison of the stock `com.oplus.camera` app in three states, to find what the
Hasselblad teleconverter (TC) mode does differently from normal operation. Captured via
`adb shell dumpsys media.camera` + `adb logcat | grep ProcessSensorModeUpdate`.

## The one-line finding

**The stock app switches WHICH CAMERA it opens for TC mode:**
- **Non-TC (standard Photo, 3× tele selected):** opens **camera id 0 — the LOGICAL MULTICAMERA**
  (cam0), with more output streams (incl. a RAW stream routed to physical sub-camera id 2).
  This is the seamless-zoom path.
- **TC mode (Hasselblad teleconverter, Photo AND Video):** opens **camera id 4 — the STANDALONE
  3× tele** (cam4), with fewer streams. Devices 1/2/3 are closed.

Both TC states (Photo + Video) use cam4. Non-TC uses cam0. This matches our app's standalone-cam4
path for TC, and confirms the stock app's normal (non-TC) path is the logical multicamera — which
is fast for the stock app via OCS but slow (~800 ms capture) for a raw-Camera2 third party.

## Sensor mode (CamX ProcessSensorModeUpdate)

| state | lighthousetele SensorMode |
|---|---|
| Non-TC (3× tele) | 3 |
| **TC mode** | **48** |

TC engage transitions through modes 18/21/22/14 then settles on lighthousetele **48**. This is the
HAL-internal sensor mode; it is NOT a Camera2 request/session key (verified — the dumpsys
session parameters are identical between TC and non-TC apart from the camera id + stream count).

## No Camera2-visible session-parameter difference

The `dumpsys-diff.txt` (non-TC vs TC Photo) diff is **entirely**: the active camera id (0→4),
the events log (timestamps/PIDs), frame counters, and the stream list (cam0 has more streams).
**No vendor key, operation mode, or session parameter differs at the Camera2 level.** The TC
mode is driven entirely HAL-internally via the OCS CameraUnit privileged path (the operation
mode + sensor-mode selection that the dumpsys does not expose).

## Implication for this app

- TC mode → standalone cam4 (what we already do) is correct and matches the stock app.
- Non-TC seamless zoom → the stock app uses cam0 (logical multicamera) via OCS. A raw-Camera2
  cam0 session is too slow on this HAL (~800 ms shutter, zoom stutter) to ship — confirmed
  empirically. So the app's app-level standalone lens switching (commit 9163ff6) is the
  pragmatic non-TC path; it can't match the stock app's OCS-driven cam0 smoothness.
- The 300 mm OIS (lighthouse-tele SensorMode 48) is **not reachable via raw Camera2** — neither
  a request key nor a session parameter differs; it is OCS-internal only.

## Artifacts (this directory)

| file | what |
|---|---|
| `README.md` | this summary |
| `dumpsys-nontc-3x.txt.gz` | full `dumpsys media.camera`, non-TC (standard Photo, 3× tele) — cam0 active |
| `dumpsys-tc.txt.gz` | full dump, TC Photo mode — cam4 active |
| `dumpsys-tc-video.txt.gz` | full dump, TC Video mode — cam4 active |
| `dumpsys-diff.txt` | non-TC vs TC-Photo diff (the camera-id + stream difference) |
| `dumpsys-diff-tcphoto-vs-tcvideo.txt` | TC Photo vs TC Video diff (video stream config) |
| `dumpsys-diff-nontc-vs-tcvideo.txt` | non-TC vs TC Video diff |

Gunzip the `.txt.gz` files to read the raw dumps. Reproduce: launch the stock app in each mode,
`adb shell dumpsys media.camera > dump.txt`, `adb logcat -d | grep ProcessSensorModeUpdate`.

## Method / device

- Device: OPPO Find X9 Ultra (PMA110), Android 16 / API 36, stock `com.oplus.camera`.
- The stock app drives the HAL via the OCS CameraUnit SDK (`com.coloros.ocs.opencapabilityservice`),
  which holds the camera under its own identity — the Camera2-level view (this dump) only shows
  the resulting camera id + streams, not the OCS operation mode.
