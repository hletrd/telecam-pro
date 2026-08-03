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
• Public and auditable: github.com/hletrd/telecam-pro
• No ads, analytics, in-app purchases, account, or cloud sync.

TELECONVERTER MODE
• The Teleconverter toggle pins the lens your converter clamps onto and corrects the afocal
  180-degree flip in preview, photos, and video. Lens presets stay independent, so changing framing
  never silently turns the converter on or off.
• Tell the app which converter you mounted: pick your phone, then the optics that fit it. Presets
  cover the Hasselblad 300 mm and 230 mm, ZEISS 200 mm and 400 mm, generic clip-ons, and a custom
  magnification. Each preset's magnification is computed from the host lens it was designed for, so
  the focal readout and EXIF report what the combination actually produces — not the number printed
  on the barrel.
• Uses the device's available Camera2 OIS and video stabilization.

LENSES
• Focal presets filtered to the ones your phone can actually reach, with the live effective focal
  length in the viewfinder. A preset reachable only by cropping is labelled zoom, not lens.

FULL MANUAL CONTROL
• Manual focus with a nonlinear slider tuned near infinity — essential for a collimated converter.
• ISO, shutter (speed or cine angle), white balance (presets, measured custom, manual Kelvin/tint),
  exposure compensation, metering, and drive modes (single, burst, bracketing, timelapse) on
  stop-snapping dials.
• Vibration-free hardware controls: volume keys, and where the phone delivers them, the camera
  button's full press, light press, and slide. Each is reassignable.

PHOTO
• HEIF (where the phone can encode it), JPEG and RAW (DNG) — separately or together, on any rear
  lens that advertises RAW. Saved files use device-orientation-aware rotation.

VIDEO
• HEVC or H.264. HEVC adds HLG and app-rendered S-Log3 / S-Log3.Cine / LogC3 alongside SDR on
  phones whose encoder supports 10-bit; where it does not, SDR only — rather than a file that
  misdescribes itself.
• Open Gate records the full sensor readout instead of a 16:9 crop.
• Hardware OIS + EIS to cut motion blur at long focal lengths.
• Sound Focus / Sound Stage audio scenes, passed to the device's audio system where its HAL
  supports them.
• Up to 4K UHD at standard and fractional NTSC rates, to ~99 Mbps at 4K30 Max. Resolutions and
  frame rates come from what the selected camera and encoder advertise.

FRAMING AND MONITORING
• Focus peaking, zebra, false color, grid, level, punch-in loupe, histogram, waveform, and
  pinch-to-zoom review to check focus right after the shot.
• Separate photo/video Fn menus, My Menu, and MR memory banks for Sony-style operation.

PRIVACY
• No ads, analytics, tracking, or internet permission.
• Microphone: only for enabled video audio and the visible level meter while Video mode is armed.
  Meter input is processed locally and never saved.
• Photo and video access is optional, requested only when you open the in-app gallery and there is
  nothing to show, so it can find captures saved before a reinstall. Declining changes nothing else.
• Nothing is uploaded, collected by the developer, or shared with third parties.

TeleCam Pro is independent and is not affiliated with, endorsed by, or sponsored by OPPO,
Hasselblad, or any hardware maker. Product names describe hardware compatibility only. S-Log is a
trademark of Sony Group Corporation; LogC of ARRI. The log profiles are this app's own
implementations of the published curves, named only for grading compatibility.

Requires Android 13 or newer. Built and measured on the OPPO Find X9 Ultra (PMA110); the app
resolves lenses and features from each device's advertised Camera2 capabilities rather than a model
list, so other Android devices are supported on a best-effort basis.
```

Every concrete claim above was audited against the shipping source on 2026-08-03; 15 inaccuracies
were found and corrected. The ones worth remembering, because they were all the same mistake —
copy written for one phone and one kit, left standing after the app became multi-device:

- "Requires Android 16" (the floor is `minSdk 33`), and "other Android 16 phones".
- "One tap selects the 3x periscope lens and enables teleconverter mode" — lens presets deliberately
  do NOT bundle the converter; `onToggleTeleconverter` owns that state.
- "Focal presets for every rear lens your phone advertises" — the rail is filtered by what the optics
  can reach, and an unreachable preset is spoken as zoom.
- HEVC-only (H.264 is offered too), HLG/log unconditional (gated on a 10-bit encoder), RAW on "any
  lens" (only lenses advertising RAW), HEIF unconditional (gated on an HEVC encoder).
- No mention of photo/video access in PRIVACY, though the manifest declares it.

## Required graphic assets

| Asset | Spec | Source |
|---|---|---|
| Hi-res app icon | 512×512 PNG, 32-bit with alpha | `docs/assets/play/icon-512.png` (generated) |
| Feature graphic | 1024×500 PNG/JPG, no alpha | `docs/assets/play/feature-graphic.png` (generated) |

Regenerating the feature graphic from its SVG (macOS; the SVG is a 1024×1024 canvas whose design
sits in the centre 500-row band, so it renders edge-to-edge and is then cropped):

```bash
cd docs/assets/play
qlmanage -t -s 1024 -o /tmp/fg feature-graphic.svg
python3 - <<'EOF'
from PIL import Image
im = Image.open('/tmp/fg/feature-graphic.svg.png')
# RGB, not RGBA: Play REJECTS a feature graphic that carries an alpha channel.
im.crop((0, 262, 1024, 762)).convert('RGB').save('feature-graphic.png', optimize=True)
EOF
```

The tagline names no specific optic. It said "300 mm afocal teleconverter" while the app targeted
one phone and one kit; the converter became a selectable pair, so a fixed focal length on the
store's largest asset advertised a limit the app does not have.

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
