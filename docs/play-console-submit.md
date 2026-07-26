# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> **UPLOAD-READY (2026-07-26) — re-cut from `main`.** The previously recorded `9541697` artifact is
> SUPERSEDED: `main` has since moved through the Kotlin namespace move (`6ae3979`), the
> `com.oplus.ocs` SDK removal, cycle-8 responsiveness, the pseudo-ZSL ring, and the focus-confidence
> detector. Re-cutting cost only a fresh device matrix, because ALL SIX Play screenshots had to be
> recaptured either way — so the candidate below is a build of `main`, not a frozen branch.
> `applicationId` is unchanged (`me.hletrd.telecampro`); the launcher component is now
> `me.hletrd.findx9tele.MainActivity`.
>
> **The remaining blocker is not a build:** the six phone screenshots are still stale and must be
> recaptured from THIS candidate on the physical PMA110 before the console sequence can complete.

- Signed Android App Bundle:
  `app/build/outputs/bundle/release/app-release.aab`
- Version: `versionCode=1`, `versionName=1.0`
- Package name: `me.hletrd.telecampro`
- Upload key alias: `telecampro`
- Upload certificate SHA-256 (keystore regenerated 2026-07-25 — the previous certificate was never
  uploaded to Play, so nothing is bound to it):
  `9D:FD:B9:03:26:92:38:EF:6D:E4:24:05:26:66:B0:58:14:57:7B:4B:3B:B4:3A:5E:3E:3A:05:57:26:60:E5:84`

Do not upload debug APKs or any unsigned/stale release bundle.

### Final v1 upload artifacts (built + verified 2026-07-26 from `main`)

- Artifact location: `app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256: `69af1574140c95dcb15e35526777b9bc49bfb83a06d20c34fc86da407d2ac753`
- Matching release APK SHA-256:
  `e56b3769b740fe56da192a553c0a550e265a78ecf09b36f85fa799d34597fed5`
- Launch component: `me.hletrd.telecampro/me.hletrd.findx9tele.MainActivity`
- `bundletool 1.18.3 validate`: passed; AAB `jarsigner -verify`: verified
- APK signing: v2 valid, 1 signer, certificate matches the upload certificate above
- APK alignment: 16 KiB passed (`zipalign -c -P 16 4`)
- Release gate: `lintRelease` 0 errors / 5 known-intentional warnings; host suite green at HEAD
  (Partition-A coverage 99.81%)
- Manifest: target/min SDK 36, no `INTERNET`, no `DEBUGGABLE`
- **Release dex contains ZERO `com/oplus/ocs` classes** (verified by dex string scan) — the OEM SDK
  is gone from the shipped binary entirely, which is what the Data-Safety answers now rest on.
- Superseded candidates (do NOT upload): `a737483f…` (9541697, pre-namespace-move), `7339e00d…`,
  `b45a3b8e…`.

### PMA110 release device matrix — PASSED 2026-07-26 (this exact artifact)

Installed APK SHA-256 verified byte-identical to the artifact above before testing.

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
  - `screenshots/05-lens-and-fn.png` — focal rail with the Fn quick-control sheet open
  - `screenshots/06-video-settings.png` — Video tab: codec, Open Gate, resolution, FPS, bitrate,
    live encoder summary, transfer curves
  - All six render UPRIGHT: the first captures had every rotating glyph sideways because the app
    counter-rotates glyphs by the gravity-derived device orientation and the phone was standing in
    a landscape pose. Capture with the phone PORTRAIT — this is app behaviour, not a bug, and no
    code change is involved.
  - REMAINING JUDGEMENT CALL (owner): the three viewfinder frames show whatever the lens was
    pointed at during capture, currently a close, out-of-focus surface. The chrome is correct and
    uploadable as-is, but a lit, textured, IN-FOCUS subject would make far better store art.

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
