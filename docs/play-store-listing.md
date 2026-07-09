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
TeleCam Pro is an open-source professional manual camera built for one phone - the OPPO Find X9 Ultra -
and tuned for its periscope telephoto lens and for 300 mm afocal teleconverters that clamp onto it.

It talks to the camera through Camera2 directly, so you get real manual control and the device's own pro
video pipeline instead of a generic auto camera.

OPEN SOURCE
• Source code is public and auditable: github.com/hletrd/telecam-pro
• No ads, no analytics, no in-app purchases, no account, and no cloud sync.
• Built for owners who want a focused camera tool, not another tracking surface.

TELECONVERTER MODE
• One tap selects the 3x periscope lens and enables teleconverter mode: the afocal converter flips the
  image 180 degrees, and the app corrects it in preview, photos, and video automatically.
• Image stabilization scaled for the long effective focal length so 300 mm framing stays usable.

FOUR LENSES
• Ultra-wide, main, 3x and 10x, selected by focal length — switch instantly.

FULL MANUAL CONTROL
• Manual focus with a nonlinear slider tuned near infinity (essential for a collimated teleconverter).
• ISO, shutter (speed or cine angle), white balance (presets + Kelvin/tint), exposure compensation,
  metering, and drive modes (single, burst, bracketing, timelapse) with tactile stop-snapping dials.
• Volume keys as a vibration-free hardware shutter.

PHOTO
• HEIF, JPEG, and RAW (DNG) in any combination. Stills save upright in any hold.

VIDEO
• 10-bit HEVC (Rec.2020) in HLG or GL-baked O-Log2, plus SDR for fast delivery.
• Hardware OIS + EIS stabilization to cut motion blur at long focal lengths.
• Directional audio (Sound Focus / Sound Stage) that narrows the mic toward your subject.
• 4K DCI, standard and NTSC drop-frame rates, up to ~120 Mbps.

FRAMING AND MONITORING
• Focus peaking, zebra, false color, grid, spirit level, punch-in loupe, histogram, waveform, and an
  in-app pinch-to-zoom review to check focus right after the shot.
• Separate photo/video Fn menus, My Menu, and MR memory banks for Sony-style operation.

PRIVACY
• No ads. No analytics. No internet permission. Nothing leaves your device.

SOURCE
• Open-source project: github.com/hletrd/telecam-pro

TeleCam Pro is an independent app and is not affiliated with, endorsed by, or sponsored by OPPO,
Hasselblad, or any hardware maker. Product names are used only to describe hardware compatibility.

Requires an OPPO Find X9 Ultra running Android 16. Target model codes: CPH2841 (global) and PMA110
(China/import). It will not work on other devices.
```

## Data Safety form answers

- **Does your app collect or share any of the required user data types?** -> **No.**
- Justification: the app declares no `INTERNET` permission and contains no analytics, ads, or
  network-capable SDKs; camera and microphone input is used only on-device to produce files saved to
  local storage. The OPPO CameraUnit/OCS SDK is used only for local OEM camera capability checks.
- **Is all user data encrypted in transit?** -> N/A (no data transmitted).
- **Do you provide a way to request data deletion?** -> N/A (no data collected); users delete their own
  photos/videos via the gallery.
- Full console answer sheet: [`docs/play-data-safety.md`](play-data-safety.md).

## Required graphic assets

| Asset | Spec | Source |
|---|---|---|
| Hi-res app icon | 512×512 PNG, 32-bit | `docs/assets/play/icon-512.png` (generated) |
| Feature graphic | 1024×500 PNG/JPG, no alpha | `docs/assets/play/feature-graphic.png` (generated) |
| Phone screenshots | >=2, PNG/JPG, 320-3840 px, max side <=2x min side, no alpha | `docs/assets/play/screenshots/` |

### Screenshots captured on PMA110

The checked-in screenshots are 1440 x 2880, no-alpha PNGs captured from the physical PMA110 after the
1x default-lens and separate Preserve Lens / Preserve TELE settings update. They are tracked with Git
LFS under `docs/assets/play/screenshots/`.

1. `docs/assets/play/screenshots/01-main-viewfinder.png` - main still viewfinder with 1x / 23 mm and TELE off.
2. `docs/assets/play/screenshots/02-pro-settings.png` - Setup tab with Remember, Preserve Lens, and Preserve TELE.
3. `docs/assets/play/screenshots/03-focus-loupe.png` - Focus controls and focus-assist settings.
4. `docs/assets/play/screenshots/04-video-controls.png` - video viewfinder with 4K 29.97p HEVC O-Log status.
5. `docs/assets/play/screenshots/05-lens-selection.png` - Lens tab with 0.6x / 1x / 3x / 10x selection.
6. `docs/assets/play/screenshots/06-video-settings.png` - Video tab with codec, resolution, FPS, and bitrate.

## Release checklist

1. Create the upload keystore and `keystore.properties` (see `keystore.properties.example`).
2. `./gradlew bundleRelease` -> `app/build/outputs/bundle/release/app-release.aab`; this now fails
   fast when signing credentials are missing so an unsigned bundle cannot be uploaded by mistake.
3. Play Console → create app → upload the signed AAB to an **internal testing** track.
4. Set pricing to **free**, paste the descriptions above, set category/rating, complete the Data Safety form as above.
5. Add the privacy policy URL, contact email, icon, feature graphic, and screenshots.
6. Device catalog -> restrict availability to OPPO Find X9 Ultra / CPH2841 and PMA110 before wider rollout.
7. Roll out internal testing -> closed -> production.

Console-ready summary: [`docs/play-console-submit.md`](play-console-submit.md).
