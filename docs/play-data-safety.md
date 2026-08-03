# Google Play Data Safety - TeleCam Pro

Use this as the source of truth when filling Play Console > Policy > App content > Data safety.

## Evidence

- Package name: `me.hletrd.telecampro`
- Declared permissions: `CAMERA`, `RECORD_AUDIO`
- No declared `INTERNET` permission
- No ads, analytics, accounts, cloud sync, or crash telemetry SDKs of any kind
- NO build variant bundles a third-party OEM SDK. The OPPO CameraUnit/OCS availability probe and its
  `com.oplus.ocs` dependency — previously debug-only, never in release — were removed entirely on
  2026-07-25. The enforceable guarantee is unchanged and is the merged manifest: the app declares no
  `INTERNET` permission, and the build strips one if any dependency ever tries to merge it in
- Camera input is used for the viewfinder and capture. Microphone input is processed locally when
  recording video audio and while the input level meter is visible in armed Video mode. Standby meter
  input is reduced to a level reading and is not saved. Photos and videos are saved in `DCIM/TeleCamPro`
  through Android MediaStore.
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

## Photo and Video Permissions declaration

Play requires a 250-character justification per permission on the "Photo and Video Permissions"
form. Paste verbatim:

**READ_MEDIA_IMAGES** (231/250)

```text
Lists photos the app previously saved, in its built-in review screen. After a reinstall Android no longer attributes those files to the app, so they are otherwise unreachable. Requested only when review is opened and finds nothing.
```

**READ_MEDIA_VIDEO** (234/250)

```text
Lists videos the app previously recorded, in its built-in review screen. After a reinstall Android no longer attributes those files to the app, so they are otherwise unreachable. Requested only when review is opened and finds nothing.
```

If Play asks why the Android photo picker is not used instead: the picker exists to let a user CHOOSE
an arbitrary file. This app is not choosing — it is re-finding captures it wrote itself, to list them
in its own review UI. A picker cannot express "show me the files this app saved", and the only reason
they are unreachable is that reinstalling clears the app's ownership of its own MediaStore rows.

## Data Types

Do not select any collected or shared data types.

Camera images and video frames are used only for the local viewfinder and captures. Microphone input
is used to encode enabled video audio and to calculate the visible input level while Video mode is
armed; standby meter input is not saved. TeleCam Pro does not upload this data, collect it for the
developer, share it with third parties, or use it for advertising or analytics.

## Store Listing Safety Text

Use this wording in any free-text notes if Play review asks for clarification:

```text
TeleCam Pro uses Camera for the viewfinder and photo/video capture. Microphone input is processed
locally when recording enabled video audio and while the visible input level meter is active in armed
Video mode; standby meter input is not saved. Captures are saved through Android MediaStore. Photo and
video library access (READ_MEDIA_*) is used only to show this app's own prior captures in its gallery
after a reinstall; it is requested contextually and processed entirely on device. The app
declares no INTERNET permission and includes no ads, analytics, accounts, cloud sync, crash telemetry,
or OEM SDK. Microphone, capture, and library data are not uploaded, collected, or shared by the developer.
```
