# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> **UPLOAD-READY (2026-07-27, cycle 9) — re-cut from current `main`.** This supersedes BOTH earlier
> candidates (`a0d4dbc` and, before it, `69af1574` / `9541697`). It carries the selectable
> teleconverter and the UI pass that the `a0d4dbc` note describes, PLUS cycle 9: the device-derived
> TELE rail, the AE lens-switch seed, the camera-switch dip, the loupe framing hint, the
> gallery-restore fix, and the zoom-gesture submit policy. `applicationId` is unchanged
> (`me.hletrd.telecampro`) and so is the upload certificate, so Play identity is unaffected.
>
> **RESOLVED 2026-07-27 (cycle 9): the six screenshots on record were RECAPTURED and are current.**
> Provenance is now mixed on purpose — see the screenshot section below. The earlier warning stood
> because the set predated the Converter dropdowns and the UI hierarchy pass; both are now shown.

Do not upload debug APKs or any unsigned/stale release bundle.

### Final v1 upload artifacts (built + verified 2026-07-27 from `main` at cycle 9, `6bf2325`)

**This supersedes the `a0d4dbc` cut** (AAB `84a74f64…`, APK `f95161cc…`), which predates cycle 9 —
the TELE focal rail, the AE lens-switch seed, the camera-switch dip, the loupe framing hint, the
gallery-restore fix and the zoom-gesture submit policy. Do not upload the older bundle.

- Artifact location: `app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256: `c238c1cf1d30e8930aa12075b56641b3824541757dfe2e5cfc6b7d8a94888363`
- Matching release APK SHA-256:
  `615ff06d044f0fbd378471e80a8f351eacb21a106b1ae2eddc460904d849d4e3`
- Launch component: `me.hletrd.telecampro/me.hletrd.telecampro.MainActivity`
- `bundletool 1.18.3 validate`: passed; AAB `jarsigner -verify`: verified
- APK signing: v2 valid (v1/v3/v3.1/v4 absent, as before), 1 signer, certificate SHA-256
  `9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584` — matches the upload
  certificate above
- APK alignment: 16 KiB passed (`zipalign -c -P 16 4`)
- Release gate (cycle-9 cut): `lintRelease` **0 errors / 5 warnings**, every one pre-existing and
  outside the changed files (`ApplySharedPref`, `UseKtx` ×3, an AGP-version-available notice);
  host suite **1185 tests, 0 failures**
- Carries a **baseline profile** (`assets/dexopt/baseline.prof`) installed by
  androidx.profileinstaller. Without it the shipped APK sat at `status=verify` and ran
  interpreted until JIT warmed; device-measured, the worst frame on opening the settings sheet
  went 61 ms → 22 ms and idle-viewfinder p99 10 ms → 7 ms.
- Installed and exercised on the PMA110 from this exact APK: viewfinder, TELE toggle
  (rail reads `13x / 30x / 60x`), Shooting/Focus/Video tabs — which is where three of the six
  store screenshots come from
- Manifest: target/min SDK 36, no `INTERNET`, no `DEBUGGABLE` (checked in the packaged binary
  manifest, not only the source)
- **Release dex contains ZERO `com.oplus.ocs` occurrences** (string scan over both dex files) — the
  OEM SDK is absent from the shipped binary, which is what the Data-Safety answers rest on.
- Superseded candidates (do NOT upload): `69af1574…` (2026-07-26), `a737483f…` (9541697,
  pre-namespace-move), `7339e00d…`, `b45a3b8e…`.

### PMA110 release device matrix — PENDING for the 2026-07-27 artifact

The run recorded below passed against the SUPERSEDED `69af1574…` build. It is kept because its
findings (the clean REC refusal without the microphone, the bounded recovery after a transient
disconnect) describe behaviour the code still has — but it is NOT evidence for the artifact this
sheet now names, and the console sequence must not treat it as such. Re-run it against
`84a74f64…` and replace this block.

Installed APK SHA-256 was verified byte-identical to the SUPERSEDED artifact before that run.

- Fresh install (package uninstalled first) → clean cold launch, live PID, **zero crashes/ANRs**
  across the whole session.
- Runtime permissions from scratch: CAMERA granted through the app's own prompt; with RECORD_AUDIO
  still denied, REC was **refused cleanly with no phantom file** (the documented admission
  behaviour), and after granting the microphone a full clip recorded.
- Photo: HEIF 4080×3064 written and pulled.
- Video: HEVC 2160×3840 portrait, `arib-std-b67` (HLG) transfer, AAC audio track, 9.05 s,
  clean full decode.
- One transient `CameraService: disconnect` was observed after an interrupted REC attempt; a
  relaunch restored a healthy session with no data loss — consistent with the documented bounded
  recovery, not a defect in this build.

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
