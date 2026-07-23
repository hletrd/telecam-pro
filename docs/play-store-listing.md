# Google Play listing — TeleCam Pro

Copy-paste source for the Play Console listing. Character limits noted; everything here is within them.

---

## App details

| Field | Value |
|---|---|
| **App name** (≤30) | `TeleCam Pro` |
| **Package name** | `me.hletrd.telecampro` |
| **Category** | Photography |
| **Content rating** | Everyone (no user-generated content shared, no ads, no data collection) |
| **Contact email** | `mnmnnmnnn@gmail.com` |
| **Privacy policy URL** | `https://hletrd.github.io/telecam-pro/privacy-policy/` |
| **Source code URL** | `https://github.com/hletrd/telecam-pro` |
| **Ads** | No |
| **In-app purchases** | No |
| **Price** | Free (set in Play Console pricing, not in title or short description) |

## Short description (≤80 chars)

```
Open-source manual camera for Find X9 Ultra telephoto.
```

## Full description (≤4000 chars)

```
TeleCam Pro is an open-source manual camera for the OPPO Find X9 Ultra, tuned for its periscope
telephoto lens and 300 mm afocal teleconverter. Camera2 provides direct manual controls and access to
the device's video pipeline.

OPEN SOURCE
• Source code is public and auditable: github.com/hletrd/telecam-pro
• No ads, no analytics, no in-app purchases, no account, and no cloud sync.

TELECONVERTER MODE
• One tap selects the 3x periscope lens and enables teleconverter mode: the afocal converter flips the
  image 180 degrees, and the app corrects it in preview, photos, and video automatically.
• Uses the device's available Camera2 OIS and video-stabilization modes for long-lens shooting.

FOUR LENSES
• Ultra-wide, main, 3x, and 10x focal presets.

FULL MANUAL CONTROL
• Manual focus with a nonlinear slider tuned near infinity (essential for a collimated teleconverter).
• ISO, shutter (speed or cine angle), white balance (presets + Kelvin/tint), exposure compensation,
  metering, and drive modes (single, burst, bracketing, timelapse) with tactile stop-snapping dials.
• Volume keys as a vibration-free hardware shutter.

PHOTO
• HEIF and JPEG can be selected separately or together. RAW (DNG) is available only in TELE mode on
  the eligible standalone 3x camera; supported outputs can be combined. Saved formats use
  device-orientation-aware rotation.

VIDEO
• HEVC recording in HLG, app-rendered S-Log3 / S-Log3.Cine / LogC3 profiles, or SDR.
• Hardware OIS + EIS stabilization to cut motion blur at long focal lengths.
• Device Sound Focus / Sound Stage audio-scene controls (passed to the device's audio system).
• 4K UHD at standard and fractional NTSC frame rates, with up to ~99 Mbps target bitrate at 4K30 Max.

FRAMING AND MONITORING
• Focus peaking, zebra, false color, grid, spirit level, punch-in loupe, histogram, waveform, and an
  in-app pinch-to-zoom review to check focus right after the shot.
• Separate photo/video Fn menus, My Menu, and MR memory banks for Sony-style operation.

PRIVACY
• No ads, analytics, tracking, or internet permission.
• Microphone access is limited to enabled video audio and the visible input level meter while Video
  mode is armed. Meter input is processed locally and is not saved; nothing is uploaded, collected by
  the developer, or shared with third parties.

SOURCE
• Open-source project: github.com/hletrd/telecam-pro

TeleCam Pro is an independent app and is not affiliated with, endorsed by, or sponsored by OPPO,
Hasselblad, or any hardware maker. Product names are used only to describe hardware compatibility.
S-Log is a trademark of Sony Group Corporation; LogC is a trademark of Arnold & Richter Cine
Technik GmbH & Co. Betriebs KG (ARRI). The log profiles are the app's own implementations of the
published curve specifications, named only to describe grading-workflow compatibility.

Requires an OPPO Find X9 Ultra running Android 16. Target model codes: CPH2841 (global) and PMA110
(China/import). It will not work on other devices.
```

## Data Safety form answers

- **Does your app collect or share any of the required user data types?** -> **No.**
- Justification: the app declares no `INTERNET` permission and contains no analytics or ads SDKs.
  Camera input supports the local viewfinder and captures. Microphone input is processed locally for
  enabled video audio and while the visible input level meter is active in armed Video mode; standby
  meter input is not saved. Captures remain on-device through Android MediaStore, and no input is
  uploaded, collected by the developer, or shared with third parties. The release build bundles no
  OEM SDK; the OPPO CameraUnit/OCS availability probe is debug-only.
- **Is all user data encrypted in transit?** -> N/A (no data transmitted).
- **Do you provide a way to request data deletion?** -> N/A (no data collected); users delete their own
  photos/videos via the gallery.
- Full console answer sheet: [`docs/play-data-safety.md`](play-data-safety.md).

## Required graphic assets

| Asset | Spec | Source |
|---|---|---|
| Hi-res app icon | 512×512 PNG, 32-bit with alpha | `docs/assets/play/icon-512.png` (generated) |
| Feature graphic | 1024×500 PNG/JPG, no alpha | `docs/assets/play/feature-graphic.png` (generated) |
| Phone screenshots | >=2, PNG/JPG, 320-3840 px, max side <=2x min side, no alpha | `docs/assets/play/screenshots/` |

### Screenshots captured on PMA110

> **STALE — DO NOT UPLOAD ANY FILE IN THIS LIST.** These 1440 x 2560 (9:16), no-alpha PNGs are
> historical 2026-07-10 PMA110 captures from the then-current candidate, after the 1x default-lens
> and separate Preserve Lens / Preserve TELE settings update. They predate the current Fn/chrome
> behavior and are not evidence for an exact current signed release candidate. Cycle/debug UI
> verification screenshots are also not replacement Play assets. Recapture all six only after the
> exact signed candidate passes the current release-device matrix.

1. **STALE — DO NOT UPLOAD:** `docs/assets/play/screenshots/01-main-viewfinder.png` - historical main still viewfinder with 1x / 23 mm and TELE off.
2. **STALE — DO NOT UPLOAD:** `docs/assets/play/screenshots/02-pro-settings.png` - historical Setup tab with Remember, Preserve Lens, and Preserve TELE.
3. **STALE — DO NOT UPLOAD:** `docs/assets/play/screenshots/03-focus-loupe.png` - historical Focus controls and focus-assist settings.
4. **STALE — DO NOT UPLOAD:** `docs/assets/play/screenshots/04-video-controls.png` - historical video viewfinder with 4K 29.97p HEVC HLG status.
5. **STALE — DO NOT UPLOAD:** `docs/assets/play/screenshots/05-lens-selection.png` - historical Lens tab with 0.6x / 1x / 3x / 10x selection.
6. **STALE — DO NOT UPLOAD:** `docs/assets/play/screenshots/06-video-settings.png` - historical Video tab with codec, resolution, FPS, and bitrate.

## Release checklist

1. Create the upload keystore and `keystore.properties` (see `keystore.properties.example`).
2. `./gradlew bundleRelease` -> `app/build/outputs/bundle/release/app-release.aab`; this now fails
   fast when signing credentials are missing so an unsigned bundle cannot be uploaded by mistake.
3. Play Console → create app → upload the signed AAB to an **internal testing** track.
4. Set pricing to **free**, paste the descriptions above, set category/rating, complete the Data Safety form as above.
5. Add the privacy policy URL, contact email, icon, feature graphic, and screenshots.
6. Device catalog -> restrict availability to OPPO Find X9 Ultra / CPH2841 and PMA110 before wider rollout.
7. Roll out internal testing, review the pre-launch report, then promote to production. A closed test
   remains optional for this 2015 developer account.

Console-ready summary: [`docs/play-console-submit.md`](play-console-submit.md).
