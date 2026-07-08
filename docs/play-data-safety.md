# Google Play Data Safety - TeleCam Pro

Use this as the source of truth when filling Play Console > Policy > App content > Data safety.

## Evidence

- Package name: `me.hletrd.telecampro`
- Declared permissions: `CAMERA`, `RECORD_AUDIO`
- No declared `INTERNET` permission
- No ads, analytics, accounts, cloud sync, crash telemetry, or third-party SDKs
- Camera and microphone input are processed on device and written only to local user-selected media
  storage through Android MediaStore
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

Camera images, video frames, and microphone audio are accessed only to produce local photos/videos on
the user's device. They are not transmitted off device, uploaded, analyzed by the developer, shared
with another app by TeleCam Pro, or used for advertising/analytics.

## Store Listing Safety Text

Use this wording in any free-text notes if Play review asks for clarification:

```text
TeleCam Pro is an offline camera app. It uses Camera permission for the live viewfinder and photo/video
capture, and Microphone permission only to record audio into videos saved locally on the user's device.
The app declares no INTERNET permission and includes no ads, analytics, accounts, cloud sync, crash
telemetry, or third-party SDKs. No user data is collected or shared by the developer.
```
