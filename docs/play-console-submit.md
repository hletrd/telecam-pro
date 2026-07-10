# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

- Signed Android App Bundle:
  `app/build/outputs/bundle/release/app-release.aab`
- Version: `versionCode=1`, `versionName=1.0`
- Package name: `me.hletrd.telecampro`
- Upload key alias: `telecampro`
- Upload certificate SHA-256:
  `A6:D0:A0:3F:B1:48:09:50:8F:CA:27:0F:1B:57:6E:F9:55:DF:FC:CB:D6:19:D4:5D:87:04:38:6A:29:F4:BC:AF`

Do not upload debug APKs or older unsigned/stale release bundles. Use the signed AAB above.

### Verified v1 artifact (2026-07-10)

- AAB SHA-256:
  `8230d82f482807e6feae4ae80d6a8052d1633bb8921f4cf6b908d8192224fe62`
- Matching release APK SHA-256:
  `1b2a9ba978f937f2cbcbd44e59e10ab9681156a72d8107df4485e795e9c3c190`
- `bundletool 1.18.3 validate`: passed
- APK signing: v2 signature valid; certificate matches the upload certificate above
- APK alignment: 16 KiB zip alignment passed
- Manifest: target/min SDK 36, no `INTERNET`, no `DEBUGGABLE`
- Build gates: 87 Gradle tasks passed, including the unit-test suite (216 tests as of cycle 2 —
  re-verify with the fresh release gate) and `lintRelease`
- PMA110 smoke test: DNG+HEIF photo, 4K HLG/AAC video, Open Gate 4:3 video, settings
  persistence, and no crash/ANR all passed

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
- Phone screenshots:
  - `docs/assets/play/screenshots/01-main-viewfinder.png`
  - `docs/assets/play/screenshots/02-pro-settings.png`
  - `docs/assets/play/screenshots/03-focus-loupe.png`
  - `docs/assets/play/screenshots/04-video-controls.png`
  - `docs/assets/play/screenshots/05-lens-selection.png`
  - `docs/assets/play/screenshots/06-video-settings.png`
  - all are 1440 x 2560 (9:16) no-alpha PNGs captured from the physical PMA110 and tracked with Git LFS

## Device Catalog

Restrict availability to Find X9 Ultra variants:

- Global/international: `CPH2841`
- China/import/tested device: `PMA110`

The app requires Android 16 / API 36 and is intentionally single-device.

## Manual Console Sequence

1. Create the app and upload the verified AAB to Internal testing.
2. Enter the Store Listing and Data Safety answers from this repository.
3. Upload the icon, feature graphic, and all six phone screenshots.
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
