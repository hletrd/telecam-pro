# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> Upload gate: the **PMA110 release device matrix PASSED 2026-07-25** (evidence below) on the
> post-chrome-fix build. One cosmetic commit (055bc04, shutter-ring inset) landed after that build:
> rebuild the signed AAB/APK once from current main, re-record the two hashes below, and upload.
> No further device matrix is required for that rebuild (055bc04 is a draw-only inset).

- Signed Android App Bundle:
  `app/build/outputs/bundle/release/app-release.aab`
- Version: `versionCode=1`, `versionName=1.0`
- Package name: `me.hletrd.telecampro`
- Upload key alias: `telecampro`
- Upload certificate SHA-256 (keystore regenerated 2026-07-25 — the previous certificate was never
  uploaded to Play, so nothing is bound to it):
  `9D:FD:B9:03:26:92:38:EF:6D:E4:24:05:26:66:B0:58:14:57:7B:4B:3B:B4:3A:5E:3E:3A:05:57:26:60:E5:84`

Do not upload debug APKs or any unsigned/stale release bundle.

### Current v1 candidate (artifacts verified 2026-07-25; device matrix pending)

- AAB SHA-256 (built from main at the cycle-7 close, signed with the regenerated upload key):
  `b45a3b8e46d09e5d6f5e67b252d9ee2389554b0b532c7ea76d1eb0060b1bc6d1`
- Matching release APK SHA-256:
  `23ecbcf28745d975ab46124a60a29d255d9b8e2d77b03eb2d91fd5002b038c8e`
- `bundletool 1.18.3 validate`: passed; AAB `jarsigner -verify`: verified
- APK signing: v2 signature valid, 1 signer, certificate matches the upload certificate above
- APK alignment: 16 KiB zip alignment passed (`zipalign -c -P 16 4`)
- Release gate: `lintRelease` 0 errors / 5 known-intentional warnings; host unit suite green at HEAD

- Manifest: target/min SDK 36, no `INTERNET`, no `DEBUGGABLE`
- The 2026-07-10 PMA110 smoke test (DNG+HEIF photo, 4K HLG/AAC video, Open Gate 4:3, settings
  persistence, no crash/ANR) is historical evidence from a superseded build/certificate; the
  current-candidate PMA110 release matrix is **pending** and gates the upload.

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
