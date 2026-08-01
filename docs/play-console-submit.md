# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> **UPLOAD-READY (2026-07-28) — re-cut from current `main` at `3c70639`.** This supersedes the
> cycle-9 cut (`6bf2325`) and every candidate before it. `applicationId` is unchanged
> (`me.hletrd.telecampro`) and the upload certificate is byte-identical to the recorded one, so Play
> identity is unaffected.
>
> Twenty-six commits landed since `6bf2325`, eleven of them touching app source: the baseline
> profile, device-independent EXIF identity, the phone-model removal from the app's own identity,
> the finder past 3× opening at 1×, the selfie route hidden then restored with the loupe diagnosis,
> the native-log experiment and its real blocker, the 10-bit HLG10 session proof, front-route
> pseudo-ZSL, the microphone-decline recording fix, and the front tap-AF metering-mirror fix.
>
> **Screenshots: still the recaptured 2026-07-27 set.** They predate none of the above visually
> except the 1× default opening framing — see the screenshot section before uploading.

Do not upload debug APKs or any unsigned/stale release bundle.

### Final v1 upload artifacts (built + verified 2026-07-31 from `main` at `c6722bb`)

**This supersedes the `961b080` cut** (AAB `0516b0d8…`, APK `54035d8a…`) — adds the OPPO
quick-button binding (781), the eviction-quiet camera health path, and the route-switch RAW toast
removal — and before it the `3a3d034` cut (AAB `1c160b8c…`, APK `8d73388e…`) — that one predates
the loupe-hint pre-converter scaling (operator-requested) and the OPPO-767 half-press routing fix —
and before it the `26266db` cut (AAB `59ccb318…`, APK `9658300c…`) — that one predates the
adversarial verification closure (must-fix: recall packets re-normalized against outgoing caps;
plus finder zoom-OUT edge, late-RAW retention veto, rollback mirror leg, OIS strip gate) — and
before it the `3ff3b4b` cut (AAB `19ef2b7d…`, APK `870ac286…`), which predates the
2026-07-30 whole-app review fixes (pre-open caps guard, finder zoom re-resolve, pending-sibling
sweep, MR recall/store fidelity, hardware-key audio scoping, gesture closure lifetimes, EXIF
honesty) — before it the `9f367c1` cut (AAB `f04028f7…`, APK `ad66b179…`), and before it the `3c70639` cut (AAB `70f83bdd…`, APK
`97e53333…`), which in turn superseded
the `6bf2325` cycle-9 cut (AAB `c238c1cf…`, APK `615ff06d…`). Do not upload either older bundle.
The re-cut exists because `3c70639` predates the DNG route-input fixes (`d6ef232`, `968f13b`,
`9f367c1`): on that artifact a persisted DNG selection was inert at launch, and a Video→Photo trip
could leave DNG permanently unable to produce a RAW file.

- Artifact location: `app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256: `a5654855f978972f4f1e87a2ae782a6cf98e3949904106d72d27b105816b901f`
- Matching release APK SHA-256:
  `7fd036ee1d26aca9983a82f860bc4ff83e09586914b9ea839c70eabe6b5311b7`
- Launch component: `me.hletrd.telecampro/me.hletrd.telecampro.MainActivity`
- AAB `jarsigner -verify`: **jar verified**. `bundletool validate` was NOT re-run for this
  cut — bundletool is not installed on this machine; the earlier cuts' passes are not
  evidence for these bytes, so run it before upload if you want that check on the record
- APK signing: v2 valid (v1/v3/v3.1/v4 absent, as before), 1 signer,
  `CN=Jiyong Youn, L=Seoul, ST=Seoul, C=KR`, certificate SHA-256
  `9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584` — **unchanged from the recorded
  upload certificate**, so this is the same upload key
- APK alignment: 16 KiB passed (`zipalign -c -P 16 4`)
- Release gate: `lintRelease` **0 errors / 5 warnings**, all pre-existing (`ApplySharedPref`,
  `UseKtx`, `AndroidGradlePluginVersion`); host suite **1284 tests, 0 failures**
- Carries a **baseline profile** (`assets/dexopt/baseline.prof`, 11 KiB + `.profm`) installed by
  androidx.profileinstaller. Without it the shipped APK sat at `status=verify` and ran interpreted
  until JIT warmed; device-measured, the worst frame on opening the settings sheet went 61 ms → 22 ms
  and idle-viewfinder p99 10 ms → 7 ms.
- **Smoke-tested on the PMA110 from this exact APK** (2026-07-30). The installed APK's on-device
  `sha256sum` was confirmed byte-identical to the artifact above BEFORE the run
  (`8d73388e…` both sides), so this is evidence for this bundle and not an earlier one.
  This run selected PHOTO explicitly before the shutter (the prior cut's smoke learned that the
  release package launches in its persisted mode) and wrote a HEIF still; zero crashes/ANRs. The
  measured-audio and DNG-route evidence below is from the earlier cuts and those pipelines are
  unchanged by this re-cut. Covered on
  the R8-minified binary, which is the only place the shipped code path is actually exercised:
  - Photo: HEIF written.
  - Video: recorded and stopped, MP4 written.
  - **DNG: enabled live from the sheet, which re-resolves the route, then captured — DNG + HEIF
    written, DNG parsed at 4080×3064 16-bit, `FocalLength` 7.73 mm, Make `OPPO` / Model `PMA110`.**
    This is the fix under test surviving minification; a `keep` rule miss would have shown here.
  - **Audio: measured, not assumed.** With audio off the clip carried a VIDEO track only (the
    `doAudio = recordAudio && hasRecordPermission()` path). With audio on, the same scene produced
    AAC 48 kHz stereo whose PCM measured mean −52.3 dB / peak −38.6 dB with 98.6 % non-zero samples —
    real room ambience, not a silent track. The recording level meter was visible throughout.
  - `logcat`: **zero** `FATAL EXCEPTION` and zero ANRs for `me.hletrd.telecampro` across the run.
- Packaged binary manifest (not just the source): `minSdkVersion 36`, `targetSdkVersion 36`,
  `compileSdkVersion 37`, **no `INTERNET`**, **no debuggable flag**. `uses-permission` is exactly
  `CAMERA` + `RECORD_AUDIO` (plus the framework's own dynamic-receiver permission).
- **Release dex contains ZERO `com.oplus.ocs` occurrences** — verified by raw byte scan for both
  `com/oplus/ocs` and `com.oplus.ocs` across `classes.dex` and `classes2.dex`. The OEM SDK is absent
  from the shipped binary, which is what the Data-Safety answers rest on.
- Superseded candidates (do NOT upload): `3c70639` (`70f83bdd…`), `6bf2325` (`c238c1cf…`), `a0d4dbc` (`84a74f64…`),
  `69af1574…`, `a737483f…` (9541697, pre-namespace-move), `7339e00d…`, `b45a3b8e…`.

### PMA110 release device matrix — PARTIAL for the 2026-07-28 artifact

**Verified against THIS artifact** (release APK `99d227d6…`, installed as an update over the prior
release):

- Installed APK verified byte-identical to the artifact (`sha256sum` on device) before testing.
- Cold launch to a live viewfinder, loupe + corner overview active; live PID held.
- Photo: HEIF written (DCIM/TeleCamPro 14 → 15 files).
- `logcat -b crash`: **zero** entries for `me.hletrd.telecampro`; zero ANRs.

**Verified the same day on the DEBUG package built from `8519eaa`** (one commit before the front
tap-AF fix, which is host-tested only) (the debug variant is a
separate `applicationId`, so its runtime permissions are independent — which is what made a true
first-launch audit possible; `pm grant`/`pm revoke` are both refused on ColorOS, so virgin permission
state needs uninstall + reinstall):

- Fresh install, nothing granted → CAMERA requested immediately, no error.
- CAMERA denied once → the gate re-requests successfully; denied twice → "Enable camera access in
  Settings." with a button that opens `InstalledAppDetails`.
- With RECORD_AUDIO denied, REC now **records a video-only clip** — `RecordingSpec: admitted …
  audio=false`, `MUTE` in the OSD, a 30.3 s HEVC 2160×3840 file with exactly one stream and no audio
  track. **This supersedes the older "REC refused cleanly with no phantom file" note**, which
  described pre-`06fc6d1` behaviour and is no longer what the code does; refusing the take withheld a
  recording the pipeline can fully deliver.
- Granting the microphone afterwards through the rationale → system dialog re-enables audio normally.
- Saved stills carry no GPS: parsing a HEIF's TIFF IFDs found no GPS IFD pointer in IFD0 or ExifIFD.

**Still pending against this artifact**: a video record/stop cycle and an HLG transfer check on the
RELEASE package specifically (both were exercised on debug from the same source), and the
converter-mounted optical checks that need the physical teleconverter.

### Historical v1 candidate (2026-07-10; superseded — DO NOT UPLOAD)

- AAB `8230d82f482807e6feae4ae80d6a8052d1633bb8921f4cf6b908d8192224fe62`, APK
  `1b2a9ba978f937f2cbcbd44e59e10ab9681156a72d8107df4485e795e9c3c190`, signed with the retired
  certificate `A6:D0:A0:3F:…:BC:AF` (never uploaded; keystore replaced 2026-07-25).

The developer account was created in 2015, so the closed-test production-access requirement for new
personal accounts created after November 13, 2023 does not apply.

## Store Listing

- App name: `TeleCam Pro`
- Short description: `Open-source manual camera for Find X9 Ultra telephoto.`
- Category: Photography
- Price: Free
- Ads: No
- In-app purchases: No
- Contact email: `mnmnnmnnn@gmail.com`
- Privacy policy URL: `https://hletrd.github.io/telecam-pro/privacy-policy/`
- Source code URL: `https://github.com/hletrd/telecam-pro`
- Full listing copy: [`docs/play-store-listing.md`](play-store-listing.md)

The app uses OPPO and Hasselblad product names only to describe hardware compatibility. It is not
affiliated with, endorsed by, or sponsored by either company.

## Data Safety

Use [`docs/play-data-safety.md`](play-data-safety.md).

Summary:

- Does the app collect or share required user data types? No
- Encryption in transit: Not applicable; no data is transmitted
- Data deletion request mechanism: Not applicable; no developer-collected data exists
- Ads: No
- Child-directed: No

**Since 2026-08-01 the manifest also declares the visual-media READ trio (READ_MEDIA_IMAGES /
READ_MEDIA_VIDEO / READ_MEDIA_VISUAL_USER_SELECTED)** for the reinstall gallery restore. This
reads the user's own captures ON DEVICE only — nothing is collected or transmitted, so the
collect/share answers above stay "No" — but the Data Safety "Photos and videos" ACCESS question
and the privacy policy must both mention on-device photo/video library access before the next
review submission. Update `docs/play-data-safety.md` and `PRIVACY.md` in the same pass as the
re-cut.

## Assets

- Hi-res icon: `docs/assets/play/icon-512.png`
  - 512 x 512 PNG
  - 32-bit PNG with alpha
- Feature graphic: `docs/assets/play/feature-graphic.png`
  - 1024 x 500 PNG
  - no alpha
- Phone screenshots — recaptured **2026-07-27 (cycle 9)**, cropped to **1440x2880 (exactly 2:1)**
  24-bit PNG, no alpha. Play rejects anything taller than 2:1 and this panel is 1440x3168 (1:2.2).
  The crop box is **(0, 168) → (1440, 3048)**, derived by MEASUREMENT rather than guessed: on this
  panel the OS status-bar glyphs end at y=101 and the lowest app pixel (the shutter ring) sits at
  y=3041, so this window strips both system bars and loses no app content. Verified per file — only
  `02` touches the bottom edge, and that is the MR card of a SCROLLING sheet, which reads correctly
  as "more below".
  - **Provenance is mixed, deliberately.** The three viewfinder frames come from the operator's own
    handheld captures against real subjects (buildings, sky) because the listing wants
    photography-led art; the three menu frames come from the signed RELEASE build installed on the
    PMA110, since a menu needs no scene and must show the shipping UI.
  - Landscape captures were REJECTED for this set. The app counter-rotates glyphs by the
    gravity-derived device orientation, so a landscape-held capture renders the focal rail and mode
    carousel sideways. This is app behaviour, not a bug — capture PORTRAIT.
  - The 2026-07-10 and 2026-07-26 captures are retired: they showed a superseded UI, and every
    TELE-engaged frame among them shows the OLD focal rail (`0.6x 1x 3x 10x`) where the shipping
    build now reads `13x / 30x / 60x`.
  - `screenshots/01-main-viewfinder.png` — photo viewfinder: OSD row, focal rail, mode carousel,
    shutter
  - `screenshots/02-pro-settings.png` — Shooting tab: output format, aspect, zoom, JPEG quality,
    drive mode, self-timer
  - `screenshots/03-focus-tools.png` — Focus tab: AF modes, spot size, AF lock, peaking level/colour
  - `screenshots/04-video-controls.png` — video mode: REC control, live encoder OSD
  - `screenshots/05-lens-and-tele.png` — focal rail with the 300 mm TELECONVERTER engaged (the
    app's signature route: 3x pinned, TELE tag lit). The Fn quick-control sheet was tried first but
    would not survive a screencap — it dismisses before the frame lands, so this shot carries the
    lens story instead.
  - `screenshots/06-video-settings.png` — Video tab: codec, Open Gate, resolution, FPS, bitrate,
    live encoder summary, transfer curves
  - All six render UPRIGHT: the first captures had every rotating glyph sideways because the app
    counter-rotates glyphs by the gravity-derived device orientation and the phone was standing in
    a landscape pose. Capture with the phone PORTRAIT — this is app behaviour, not a bug, and no
    code change is involved.
  - 01 and 04 now carry LIT, real subjects (the "photography-led art" option this note always
    offered). **05 is still a BLACK-scene frame and is the one weak spot in the set**: it is the
    only slot that requires TELE engaged, and every real-subject TELE capture on hand predates the
    focal-rail change, so it would advertise a rail the shipping build no longer draws. It is
    correct and current as-is — it shows `13x / 30x / 60x` and the loupe overview with its framing
    hint — but a handheld TELE capture against a distant subject would make the three viewfinder
    frames consistent. Replace it with a PORTRAIT capture, TELE lit, and re-crop with the box above.

## Device Catalog

**The app is no longer single-device.** Hardware is resolved by enumerating Camera2 capabilities
rather than model names, and the teleconverter presets explicitly cover the Find X9 Ultra and X9 Pro,
vivo X200 Ultra and X300 Ultra, plus generic clip-ons — a catalog locked to two model codes would
contradict the app's own UI.

The `minSdk 36` manifest requirement already excludes almost every device on the market, so Android
16 is doing most of the narrowing on its own.

Two options, an owner decision:

- **Open catalog (matches the app's own claims).** Ship to all Android 16 phones. Only the Find X9
  Ultra is device-verified, so accept that early reviews may come from untested hardware.
- **Staged.** Launch restricted to the verified models below, then widen once another device has
  been through a real capture pass. Slower, but no unverified first impressions.

Verified models: `CPH2841` (global) and `PMA110` (China/import).

## Manual Console Sequence

1. Upload the cycle-9 AAB whose hashes are above. The candidate decision is CLOSED — the earlier
   `9541697` and `a0d4dbc` artifacts are superseded and must not be uploaded.
2. Enter the Store Listing and Data Safety answers from this repository.
3. Upload the icon, feature graphic, and the six checked-in screenshots — these ARE current
   (recaptured 2026-07-27, 1440x2880); the earlier "do not use the checked-in captures" warning no
   longer applies.
4. Set the device catalog per the Device Catalog section above (open vs staged is an owner call).
5. Review Play's automated checks and pre-launch report.
6. Promote the same artifact only after the internal-test install succeeds.

## Local Signing Material

These files are intentionally gitignored and stay only on the local machine:

- `telecampro-upload.jks`
- `telecampro-upload-passwords.txt.gpg`
- `keystore.properties`

To rebuild the signed AAB locally:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# The backup stores the passwords as storePassword= / keyPassword= lines — map them onto the
# TELECAMPRO_* variables the build reads (the old snippet grepped for TELECAMPRO_* keys that do
# not exist in the file, silently exporting nothing).
while IFS='=' read -r key value; do
  case "$key" in
    storePassword) export TELECAMPRO_STORE_PASSWORD="$value" ;;
    keyPassword) export TELECAMPRO_KEY_PASSWORD="$value" ;;
  esac
done < <(gpg --batch --quiet --decrypt telecampro-upload-passwords.txt.gpg)

./gradlew :app:bundleRelease
```
