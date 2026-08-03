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
Open-source manual camera for periscope telephoto + teleconverters.
```

## Release notes (≤500 chars per language)

First release. Paste into the Play Console "Release notes" field, language tags included.

```
<en-US>
First release.

TeleCam Pro is a manual camera for shooting through clip-on afocal teleconverters on a phone's
periscope telephoto. It corrects the 180-degree flip those converters introduce, in the viewfinder
and in saved photos and video.

Manual focus tuned near infinity, PASM exposure, RAW/DNG, focus peaking, zebra, histogram and
waveform.

Open source. No ads, no analytics, no account, and no network permission.
</en-US>
```

Deliberately makes no HDR or 10-bit claim: the capture source is the ISP's display-referred SDR
stream, and the HLG/log profiles are curves applied to it (see CLAUDE.md). Saying otherwise in store
copy would be a stronger claim than the pipeline can back.

## Full description (≤4000 chars)

```
TeleCam Pro is an open-source manual camera for shooting through clip-on afocal teleconverters on a
phone's periscope telephoto lens. It corrects the 180-degree flip those converters introduce, and
gives you the manual controls that long-lens work needs.

OPEN SOURCE
• Source code is public and auditable: github.com/hletrd/telecam-pro
• No ads, no analytics, no in-app purchases, no account, and no cloud sync.

TELECONVERTER MODE
• One tap selects the 3x periscope lens and enables teleconverter mode: the afocal converter flips the
  image 180 degrees, and the app corrects it in preview, photos, and video automatically.
• Tell the app which converter you mounted: pick your phone, then the optics that fit it. Presets
  cover the Hasselblad 300 mm and 230 mm, ZEISS 200 mm and 400 mm, generic clip-ons, and a custom
  magnification. The focal readout, EXIF focal length, and zoom range follow your choice — and each
  preset is computed from the lens it was built for, so glass moved to another phone reports its
  real focal length, not the number on the barrel.
• Uses the device's available Camera2 OIS and video-stabilization modes for long-lens shooting.

LENSES
• Focal presets for every rear lens your phone advertises, with the live effective focal length in
  the viewfinder.

FULL MANUAL CONTROL
• Manual focus with a nonlinear slider tuned near infinity (essential for a collimated teleconverter).
• ISO, shutter (speed or cine angle), white balance (presets + Kelvin/tint), exposure compensation,
  metering, and drive modes (single, burst, bracketing, timelapse) with tactile stop-snapping dials.
• Volume keys as a vibration-free hardware shutter.

PHOTO
• HEIF, JPEG and RAW (DNG) can be selected separately or together, on any lens your phone exposes as
  a standalone camera. Saved formats use device-orientation-aware rotation.

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

Requires Android 16. Built and verified on the OPPO Find X9 Ultra (CPH2841 / PMA110); the app reads
each phone's advertised camera capabilities rather than its model name, so other Android 16 phones
are supported on a best-effort basis. Teleconverter presets cover the Find X9 Ultra and X9 Pro,
vivo X200 Ultra and X300 Ultra, plus generic clip-ons and a custom magnification.
```

## Data Safety form answers

- **Does your app collect or share any of the required user data types?** -> **No.**
- Justification: the app declares no `INTERNET` permission and contains no analytics or ads SDKs.
  Camera input supports the local viewfinder and captures. Microphone input is processed locally for
  enabled video audio and while the visible input level meter is active in armed Video mode; standby
  meter input is not saved. Captures remain on-device through Android MediaStore, and no input is
  uploaded, collected by the developer, or shared with third parties. No build variant bundles an
  OEM SDK (the debug-only OPPO CameraUnit/OCS availability probe and its `com.oplus.ocs` dependency
  were removed 2026-07-25).
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

> **CURRENT — recaptured 2026-07-27 (cycle 9).** Six 1440 x 2880 (exactly 2:1) 24-bit no-alpha
> PNGs. Play rejects anything taller than 2:1 and this panel is 1440 x 3168, so the crop box
> `(0, 168) → (1440, 3048)` strips the OS status bar and gesture bar only. Provenance is mixed on
> purpose: the viewfinder frames are handheld captures against real subjects, the menu frames come
> from the signed release build. Capture PORTRAIT — the app counter-rotates glyphs by the
> gravity-derived orientation, so a landscape-held capture renders the focal rail sideways. Full
> rationale and the per-file measurement live in `docs/play-console-submit.md`.

1. `docs/assets/play/screenshots/01-main-viewfinder.png` - photo viewfinder against a real subject: OSD row, focal rail, mode carousel, shutter.
2. `docs/assets/play/screenshots/02-pro-settings.png` - Shooting tab: output format, aspect, zoom, JPEG quality, drive mode, self-timer.
3. `docs/assets/play/screenshots/03-focus-tools.png` - Focus tab: AF modes, spot size, AF lock, peaking level/colour.
4. `docs/assets/play/screenshots/04-video-controls.png` - video viewfinder: REC control with the live HLG / STEADY encoder OSD.
5. `docs/assets/play/screenshots/05-lens-and-tele.png` - TELE engaged: the device-derived `13x / 30x / 60x` rail and the Loupe Overview with its framing hint. The one black-scene frame; swap in a handheld TELE capture to match the others.
6. `docs/assets/play/screenshots/06-video-settings.png` - Video tab: codec, Open Gate, resolution, FPS, bitrate, live encoder summary, transfer curves.

## Release checklist

1. Create the upload keystore and `keystore.properties` (see `keystore.properties.example`).
2. `./gradlew bundleRelease` -> `app/build/outputs/bundle/release/app-release.aab`; this now fails
   fast when signing credentials are missing so an unsigned bundle cannot be uploaded by mistake.
3. Play Console → create app → upload the signed AAB to an **internal testing** track.
4. Set pricing to **free**, paste the descriptions above, set category/rating, complete the Data Safety form as above.
5. Add the privacy policy URL, contact email, icon, feature graphic, and screenshots.
6. Device catalog -> see play-console-submit.md; the app is no longer restricted to two model codes.
7. Roll out internal testing, review the pre-launch report, then promote to production. A closed test
   remains optional for this 2015 developer account.

Console-ready summary: [`docs/play-console-submit.md`](play-console-submit.md).
