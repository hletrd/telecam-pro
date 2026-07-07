# Google Play listing — TeleCam Pro

Copy-paste source for the Play Console listing. Character limits noted; everything here is within them.
Replace the contact email placeholder before publishing.

---

## App details

| Field | Value |
|---|---|
| **App name** (≤30) | `TeleCam Pro` |
| **Package name** | `com.hletrd.telecampro` |
| **Category** | Photography |
| **Content rating** | Everyone (no user-generated content shared, no ads, no data collection) |
| **Contact email** | `your-contact-email@example.com` *(replace before publishing)* |
| **Privacy policy URL** | Host `PRIVACY.md` publicly and paste its URL (e.g. GitHub Pages, or the raw file URL from the public repo) |
| **Ads** | No |
| **In-app purchases** | No |

## Short description (≤80 chars)

```
Pro manual camera for Find X9 Ultra telephoto and 300mm teleconverters.
```

## Full description (≤4000 chars)

```
TeleCam Pro is a professional manual camera built for one phone — the OPPO Find X9 Ultra — and tuned for
its periscope telephoto lens and for 300 mm afocal teleconverters that clamp onto it.

It talks to the camera through Camera2 directly, so you get real manual control and the device's own pro
video pipeline instead of a generic auto camera.

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
• 10-bit HEVC (Rec.2020) in HLG, O-Log2, or SDR, plus a native scene-referred log mode.
• Hardware OIS + EIS stabilization to cut motion blur at long focal lengths.
• Directional audio (Sound Focus / Sound Stage) that narrows the mic toward your subject.
• 4K DCI, standard and NTSC drop-frame rates, high-speed 120 fps, up to ~134 Mbps.

FRAMING AND MONITORING
• Focus peaking, zebra, false color, grid, spirit level, punch-in loupe, histogram, waveform, and an
  in-app pinch-to-zoom review to check focus right after the shot.

PRIVACY
• No ads. No analytics. No internet permission. Nothing leaves your device.

TeleCam Pro is an independent app and is not affiliated with, endorsed by, or sponsored by OPPO,
Hasselblad, or any hardware maker. Product names are used only to describe hardware compatibility.

Requires an OPPO Find X9 Ultra running Android 16. It will not work on other devices.
```

## Data Safety form answers

- **Does your app collect or share any of the required user data types?** → **No.**
- Justification: the app declares no `INTERNET` permission and contains no analytics, ads, or third-party
  SDKs; camera and microphone input is used only on-device to produce files saved to local storage.
- **Is all user data encrypted in transit?** → N/A (no data transmitted).
- **Do you provide a way to request data deletion?** → N/A (no data collected); users delete their own
  photos/videos via the gallery.

## Required graphic assets

| Asset | Spec | Source |
|---|---|---|
| Hi-res app icon | 512×512 PNG, 32-bit | `docs/assets/play/icon-512.png` (generated) |
| Feature graphic | 1024×500 PNG/JPG | `docs/assets/play/feature-graphic.png` (generated) |
| Phone screenshots | ≥2, 16:9 or 9:16, 320–3840 px | Capture on device (see below) |

### Screenshots to capture (on the Find X9 Ultra)

Take these with the app running on the device (`adb exec-out screencap -p`), ideally on a lit scene:
1. Main viewfinder with the OSD (mode, ISO, shutter, focal) visible.
2. Pro settings sheet open (manual dials / lens picker).
3. A focus-monitoring overlay (peaking or punch-in loupe) active.
4. The in-app review screen with pinch-to-zoom.

## Release checklist

1. Create the upload keystore and `keystore.properties` (see `keystore.properties.example`).
2. `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. Play Console → create app → upload the AAB to an **internal testing** track.
4. Paste the descriptions above, set category/rating, complete the Data Safety form as above.
5. Add the privacy policy URL, contact email, icon, feature graphic, and screenshots.
6. Roll out internal testing → closed → production.
