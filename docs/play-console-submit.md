# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> **UPLOAD-READY (2026-07-25, final).** Built from the dedicated release worktree at 9541697 —
> includes the device-CONFIRMED capture-rotation sign fix (a209830; landscape-held stills saved
> 180° rotated before it) and the draw-only ring inset (055bc04); cycle-8 responsiveness work is
> deliberately EXCLUDED from v1. Evidence basis: the full release matrix passed on the same source
> lineage, and the rotation fix itself was device-verified by the held-orientation still matrix
> (rear portrait + both rear landscape directions + front portrait + front landscape, all upright).
> Upload the AAB whose SHA-256 matches this sheet exactly — regenerate this block if the source
> moves again.

- Signed Android App Bundle:
  `app/build/outputs/bundle/release/app-release.aab`
- Version: `versionCode=1`, `versionName=1.0`
- Package name: `me.hletrd.telecampro`
- Upload key alias: `telecampro`
- Upload certificate SHA-256 (keystore regenerated 2026-07-25 — the previous certificate was never
  uploaded to Play, so nothing is bound to it):
  `9D:FD:B9:03:26:92:38:EF:6D:E4:24:05:26:66:B0:58:14:57:7B:4B:3B:B4:3A:5E:3E:3A:05:57:26:60:E5:84`

Do not upload debug APKs or any unsigned/stale release bundle.

### Final v1 upload artifacts (built + verified 2026-07-25 from release worktree @ 9541697)

- Artifact location: `.claude/worktrees/release-v1/app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256 (signed with the regenerated upload key):
  `a737483fb621b0d64b5859976b126fbc513960b0755ed38f641d202fd1f8f2b2`
- Matching release APK SHA-256:
  `8efa65e25d94991b4a9cb3e4c9aa59bde86fa72f15efb27dd00344bb94360995`
- Superseded same-day candidates (do not upload): AAB `7339e00d…` / `b45a3b8e…` (pre-rotation-fix).
- `bundletool 1.18.3 validate`: passed; AAB `jarsigner -verify`: verified
- APK signing: v2 signature valid, 1 signer, certificate matches the upload certificate above
- APK alignment: 16 KiB zip alignment passed (`zipalign -c -P 16 4`)
- Release gate: `lintRelease` 0 errors / 5 known-intentional warnings; host unit suite green at HEAD
- Manifest: target/min SDK 36, no `INTERNET`, no `DEBUGGABLE`

### PMA110 release device matrix — PASSED 2026-07-25 (post-chrome-fix build, same source lineage)

- Fresh install + runtime-permission flow (CAMERA, then RECORD_AUDIO) verified; with the mic still
  denied, REC admission refused cleanly (no phantom file) and recorded video-only-degrade never
  misfired; after the grant, full AV recording worked.
- Photo: HEIF 3064×4080. TELE: DNG+HEIF family with one filename key; DNG 4096×3072, 16-bit,
  SamplesPerPixel 1, CFA, sane EXIF (ISO 12800, 1/10 s dark-room AE).
- Video: HEVC Main10 (`yuv420p10le`) 2160×3840 portrait, HLG `arib-std-b67` + `bt2020nc`/`bt2020`
  (no PQ mistag), AAC track present, 21.9 s clean full decode.
- Settings persistence across force-stop: VIDEO mode + HLG + STEADY + TELE all restored.
- Zero app crashes/ANRs. Incident note: a vendor QTI camera HAL process (`provider-service_64`)
  SIGABRT crash-looped under force-stop→instant-relaunch stress (`SensorDevice::InitializeHardware`);
  the app auto-restarted, held Not-Ready honestly, and recovered fully once the HAL settled —
  device/vendor-level, not an app defect.
- The 2026-07-10 smoke sheet remains historical evidence from a superseded build/certificate.

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
- Phone screenshots — **all six are STALE / DO NOT UPLOAD**:
  - **STALE:** `docs/assets/play/screenshots/01-main-viewfinder.png`
  - **STALE:** `docs/assets/play/screenshots/02-pro-settings.png`
  - **STALE:** `docs/assets/play/screenshots/03-focus-loupe.png`
  - **STALE:** `docs/assets/play/screenshots/04-video-controls.png`
  - **STALE:** `docs/assets/play/screenshots/05-lens-selection.png`
  - **STALE:** `docs/assets/play/screenshots/06-video-settings.png`
  - historical 2026-07-10 physical-PMA110 captures, 1440 x 2560 (9:16), before the current Fn/chrome behavior
  - recapture from the exact signed candidate only after its current release-device matrix passes;
    cycle/debug UI verification screenshots are not substitute Play assets

## Device Catalog

Restrict availability to Find X9 Ultra variants:

- Global/international: `CPH2841`
- China/import/tested device: `PMA110`

The app requires Android 16 / API 36 and is intentionally single-device.

## Manual Console Sequence

1. Generate and verify a new signed AAB, update this sheet's hashes, and complete the current PMA110
   release matrix; only then upload that exact artifact to Internal testing.
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
