# Privacy Policy — TeleCam Pro

_Last updated: 2026-10-19_

TeleCam Pro is a camera app for Android phones and tablets. This policy covers its permissions, captured
media, and data handling.

## Summary

TeleCam Pro does not collect or share personal data. It has no ads, analytics, tracking, accounts,
cloud sync, or `INTERNET` permission.

## Permissions the app requests

| Permission | Use |
|---|---|
| **Camera** | Shows the viewfinder and captures photos and videos. |
| **Microphone** (`RECORD_AUDIO`) | Adds audio to videos when enabled. Before recording, it is also used while the input level meter is visible in armed Video mode. |

TeleCam Pro requests no other permission. In particular, it holds no photo or video library
permission (no `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or `READ_MEDIA_VISUAL_USER_SELECTED`).

Microphone input is processed locally. The visible standby meter reduces its input to a level reading
and does not save that audio; recorded audio remains in the video file on your device. Neither is
uploaded, collected by the developer, or shared with third parties.

TeleCam Pro rejects touch events that Android marks as fully or partially obscured by another
window before they reach camera, settings, permission, or delete controls. Ordinary unmarked touch,
stylus, mouse, accessibility, and system input continues through Android normally.

## Photos and videos you capture

Photos and videos are saved in `DCIM/TeleCamPro` through Android MediaStore. Uninstalling TeleCam Pro
removes its settings but leaves captures in shared media storage. TeleCam Pro does not upload or
back up captures. Delete them in TeleCam Pro, a gallery app, or a file manager.

Captures carry ordinary photographic metadata — camera make and model, lens, exposure, and the time
of the shot — and no location. The app requests no location permission and writes no GPS tags, so a
photo taken with it cannot record where you were.

## Data collection and sharing

The app has no analytics, crash reporting, advertising, or tracking SDKs. It does not collect names,
contacts, location, identifiers, or usage data.

The app does not request access to your photo and video library, and it does not send anything
anywhere: it has no Internet permission at all. Its in-app gallery uses Android's scoped storage,
which shows the app the package-owned media it saved in `DCIM/TeleCamPro` on this device. After a
reinstall, captures from the earlier installation stay in your system gallery but are not shown in
the app. If Android exposes an ownerless file in `DCIM/TeleCamPro` whose path, TeleCam filename
format, media collection, extension, and MIME type all match, the app treats it as a legacy
TeleCam-format candidate: those checks cannot verify who created it, so the review labels its origin
unverified and limits deletion to that file.

## Children

The app is a general-purpose camera tool and is not directed at children. It collects no data from any
user, including children.

## Changes to this policy

Policy changes will appear at this URL with a new last-updated date.

TeleCam Pro is an independent app and is not affiliated with, endorsed by, or sponsored by OPPO,
Hasselblad, or any hardware maker.

## Contact

Questions about this policy: `01@0101010101.com`
