# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> **UPLOAD-READY (2026-07-27) — re-cut from `main` at `a0d4dbc`.** The 2026-07-26 candidate
> (`69af1574…`) is SUPERSEDED: `main` moved 24 commits past it, and 25 shipped source files changed
> (+2468 / −760). That is not a docs drift — it includes a NEW FEATURE (the teleconverter became a
> selectable optic with phone/converter dropdowns) and a UI pass that changed the type scale, the
> menu copy, the colour tokens and two clipped chrome badges. `applicationId` is unchanged
> (`me.hletrd.telecampro`) and so is the upload certificate, so Play identity is unaffected.
>
> **The six phone screenshots on record are from the SUPERSEDED build and must not be uploaded.**
> They show the pre-fix hierarchy (option chips larger than the row labels naming them, section
> headers indistinguishable from captions) and predate the Converter dropdowns entirely.

Do not upload debug APKs or any unsigned/stale release bundle.

### Final v1 upload artifacts (built + verified 2026-07-27 from `main` at `a0d4dbc`)

- Artifact location: `app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256: `84a74f645a55c06163486db756dbd71a0d5836a17e118398710cb6837250641d`
- Matching release APK SHA-256:
  `f95161cc0828ef010f2e561c7136fc5047e8afddf483beb4733d885b0dd65291`
- Launch component: `me.hletrd.telecampro/me.hletrd.findx9tele.MainActivity`
- `bundletool 1.18.3 validate`: passed; AAB `jarsigner -verify`: verified
- APK signing: v2 valid (v1/v3/v3.1/v4 absent, as before), 1 signer, certificate SHA-256
  `9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584` — matches the upload
  certificate above
- APK alignment: 16 KiB passed (`zipalign -c -P 16 4`)
- Release gate: `lintRelease` **0 errors / 5 warnings**, every one pre-existing and outside the
  changed files (`MediaStoreWriter` ×3, `StillCapturePipeline`, an AGP 9.3.1-available notice);
  host suite **1131 tests, 0 failures**
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
- Phone screenshots — recaptured 2026-07-26 from the RELEASE candidate above, cropped to
  **1440x2880 (exactly 2:1)** 24-bit PNG, no alpha. Play rejects anything taller than 2:1 and this
  panel is 1440x3168 (1:2.2), so the crop removes the OS status bar and gesture bar only — no app
  content is lost. The 2026-07-10 captures (`03-focus-loupe.png`, `05-lens-selection.png` and the
  older versions of the rest) are retired: they showed a superseded UI.
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
  - The three viewfinder frames (01/04/05) were deliberately captured against a BLACK scene, so the
    chrome — grid, OSD tags, focal rail, mode carousel, shutter — reads without competing with
    whatever the lens happened to see. Swap in a lit, in-focus subject if the listing wants
    photography-led art instead; the framing and crop stay valid either way.

## Device Catalog

Restrict availability to Find X9 Ultra variants:

- Global/international: `CPH2841`
- China/import/tested device: `PMA110`

The app requires Android 16 / API 36 and is intentionally single-device.

## Manual Console Sequence

1. Decide the candidate (see the header block): either upload the recorded `9541697` artifact, whose
   hashes are above and whose matrix passed, or generate a new signed AAB from `main`, regenerate
   this sheet's hashes, and re-run the PMA110 matrix before uploading that one.
2. Enter the Store Listing and Data Safety answers from this repository.
3. Recapture and review all six phone flows from that exact candidate, then upload the icon, feature
   graphic, and the six replacement screenshots; do not use the stale checked-in captures.
4. Restrict the device catalog to CPH2841 and PMA110 before any wider rollout.
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
