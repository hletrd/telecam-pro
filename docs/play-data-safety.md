# Google Play Data Safety - TeleCam Pro

Use this as the source of truth when filling Play Console > Policy > App content > Data safety.

## Evidence

- Package name: `me.hletrd.telecampro`
- Declared permissions: `CAMERA`, `RECORD_AUDIO`
- No declared `INTERNET` permission
- No ads, analytics, accounts, cloud sync, or crash telemetry SDKs of any kind
- The release build bundles no third-party OEM SDK (the OPPO CameraUnit/OCS availability probe is
  debug-only); the enforceable guarantee is the merged manifest: the app declares no `INTERNET`
  permission, and the build strips one if any dependency ever tries to merge it in
- Camera and microphone input are used only for capture. Photos and videos are saved in
  `DCIM/X9Tele` through Android MediaStore.
- Privacy policy URL: `https://hletrd.github.io/telecam-pro/privacy-policy/`

Google defines Data Safety "collection" as transmitting data off the user's device. On-device access
and processing that is not sent off device does not need to be declared as collected.

Official references:

- Google Play Data safety form:
  `https://support.google.com/googleplay/android-developer/answer/10787469`
- Google Play User Data policy:
  `https://support.google.com/googleplay/android-developer/answer/10144311`

## Console Answers

| Play Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | No |
| Is all of the user data collected by your app encrypted in transit? | Not applicable - no user data is collected or transmitted |
| Do you provide a way for users to request that their data is deleted? | Not applicable - no data is collected by the developer; users can delete their local photos/videos from the device gallery |
| Is your app committed to follow the Play Families Policy? | No - not child-directed |
| Does your app contain ads? | No |

## Data Types

Do not select any collected or shared data types.

Camera images, video frames, and microphone audio are used only to create photos and videos on the
device. TeleCam Pro does not upload them, analyze them for the developer, or use them for advertising
or analytics.

## Store Listing Safety Text

Use this wording in any free-text notes if Play review asks for clarification:

```text
TeleCam Pro uses Camera for the viewfinder and photo/video capture, and Microphone only when recording
video with audio. Captures are saved through Android MediaStore. The app declares no INTERNET
permission and includes no ads, analytics, accounts, cloud sync, crash telemetry, or OEM SDK. No user
data is collected or shared by the developer.
```
