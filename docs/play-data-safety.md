# Google Play Data Safety - TeleCam Pro

Use this as the source of truth when filling Play Console > Policy > App content > Data safety.

## Evidence

- Package name: `me.hletrd.telecampro`
- Declared permissions: `CAMERA`, `RECORD_AUDIO` — nothing else
- No photo/video library permission. `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and
  `READ_MEDIA_VISUAL_USER_SELECTED` were removed on 2026-10-19 after Play flagged them under the
  Photo and Video Permissions policy; the source manifest strips them with `tools:node="remove"` and
  the release gate fails if one reaches the packaged manifest
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
- Touch events Android marks as fully or partially obscured are rejected at the Activity boundary
  before camera, settings, permission, or delete controls receive them.
- A bounded restore query runs under scoped storage only, so it sees the current package's own
  MediaStore rows; it treats that ownership as authorship evidence. If a provider exposes an
  owner-null row, it is only a recognized legacy TeleCam-format candidate when its TeleCam directory,
  filename grammar, collection, extension, and MIME all match. Those public attributes do not prove
  authorship; the UI labels the origin unverified and permits file-only deletion.
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

Not needed. Since 2026-10-19 the app declares no `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` /
`READ_MEDIA_VISUAL_USER_SELECTED`, so the Play Console "Photo and Video Permissions" form has nothing
to justify. The earlier justifications were rejected: Play reserves broad photo/video access for apps
whose core purpose needs it (galleries, editors), and a camera app reviewing its own captures does
not qualify. Scoped storage already shows the app its own captures; the accepted cost is that a
reinstall's earlier captures appear only in the system gallery, not in in-app review.

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
Video mode; standby meter input is not saved. Captures are saved through Android MediaStore. The app
requests no photo/video library permission; its in-app review uses scoped storage and shows only the
captures this installation saved under DCIM/TeleCamPro. The app
declares no INTERNET permission and includes no ads, analytics, accounts, cloud sync, crash telemetry,
or OEM SDK. Microphone and capture data are not uploaded, collected, or shared by the developer.
```
