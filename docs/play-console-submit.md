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
  - all are 1440 x 2880 no-alpha PNGs captured from the physical PMA110 and tracked with Git LFS

## Device Catalog

Restrict availability to Find X9 Ultra variants:

- Global/international: `CPH2841`
- China/import/tested device: `PMA110`

The app requires Android 16 / API 36 and is intentionally single-device.

## Local Signing Material

These files are intentionally gitignored and stay only on the local machine:

- `telecampro-upload.jks`
- `telecampro-upload-passwords.txt.gpg`
- `keystore.properties`

To rebuild the signed AAB locally:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

while IFS='=' read -r key value; do
  case "$key" in
    TELECAMPRO_STORE_PASSWORD|TELECAMPRO_KEY_PASSWORD) export "$key=$value" ;;
  esac
done < <(gpg --batch --quiet --decrypt telecampro-upload-passwords.txt.gpg)

./gradlew :app:bundleRelease
```
