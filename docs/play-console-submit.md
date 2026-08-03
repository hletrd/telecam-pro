# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

## Upload Artifact

> ## ✅ UPLOAD-READY (2026-08-02) — signed re-cut from `main` at `0f1421e`, clean tree.
>
> Supersedes `66734db`, `a4a7d12`, `fc43953`, and every candidate before it. `applicationId` unchanged
> (`me.hletrd.telecampro`); upload certificate byte-identical to the recorded one (`9dfdb903…`).
>
> **`fc43953` must not be uploaded: video recording was impossible on a whole class of device.**
> Testing this cut on Android 13 (the `minSdk 33` floor, which had never been exercised) found two
> real defects, both now fixed and both verified on real hardware as well as the emulator:
> 1. The encoder buffer is swapped to PORTRAIT for the cycle-4 framing contract, and nothing ever
>    asked the encoder whether it accepts that shape. Encoders that cap HEIGHT below width — the
>    AOSP software HEVC encoder among them — refused every recording. Now a same-aspect fallback
>    ladder finds a shape they take.
> 2. Four of the five gamma options request 10-bit `Main10`; an 8-bit-only encoder does not refuse,
>    it silently returns `Main`. The Lenovo TB336ZU (hardware `c2.mtk.hevc.encoder`, no Main10) was
>    therefore writing clips tagged `bt2020 / arib-std-b67` (HLG) over an 8-bit `yuv420p` stream —
>    files that misdescribed themselves. Those gammas are no longer offered where they cannot be
>    honoured.
>
> Also in this cut: the standby audio meter now follows the SELECTED input (USB / Bluetooth / wired)
> instead of always reading the built-in mic, and meters per channel so a dead channel on a stereo
> mic is visible instead of averaged away.
>
> And a THIRD device-found defect, from a Lenovo TB331FC (Android 15): the still-size rule took the
> largest advertised JPEG that fits the sensor array, and that tablet advertises a SQUARE
> 2448x2448 with more pixels than its own 4:3 2592x1944 — so every photo saved square, throwing away
> the field the viewfinder had composed. Shape now precedes size.
>
> A FOURTH, from the same tablet: it arrived with the camera withheld from this app alone
> (`appops CAMERA: ignore` at UID level, `REVOKED_COMPAT` on the permission) so
> `checkSelfPermission` read GRANTED while every open was refused — the app showed normal
> viewfinder chrome over a black frame and said nothing. It now shows the existing permission gate
> with "Camera blocked for this app on this device." + [Settings], confirmed against AppOps rather
> than inferred from the ambiguous `CAMERA_DISABLED` code (the platform raises the same code for a
> transient keyguard-relaunch race, so inferring it would have produced false accusations).
>
> **Screenshots: still the recaptured 2026-07-27 set.** Nothing here changes what they show on a
> PMA110 — see the screenshot section before uploading.

Do not upload debug APKs or any unsigned/stale release bundle.

### Final v1 upload artifacts (built + device-verified 2026-08-03 from `main` at `0f1421e`)

- Artifact location: `app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256: `543a834339977b9f7df02beec8397e506c1f75bc393e295ec788aed7dbb498d8`
- Matching release APK SHA-256:
  `c3dec1a9f58e3d554e3e4a103b4d6593bdb9245c166690410f67084acc6e3f9d`
- Launch component: `me.hletrd.telecampro/me.hletrd.telecampro.MainActivity`
- AAB `jarsigner -verify`: **jar verified**; `bundletool 1.18.3 validate`: **OK**
- APK signing: **v2 valid, 1 signer**, `CN=Jiyong Youn, L=Seoul, ST=Seoul, C=KR`, certificate
  SHA-256 `9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584` — **unchanged from the
  recorded upload certificate**
- APK alignment: 16 KiB passed (`zipalign -c -P 16 4`)
- Packaged binary manifest: `minSdkVersion 33`, `targetSdkVersion 36`, `compileSdkVersion 37`;
  `uses-permission` exactly `CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`,
  `READ_MEDIA_VISUAL_USER_SELECTED` (plus the framework's dynamic-receiver permission); **no
  `INTERNET`**; **not debuggable**
- Release gate: `lintRelease` **0 errors / 8 warnings** (`UseKtx` ×4, `InlinedApi` ×2,
  `ApplySharedPref`, `AndroidGradlePluginVersion` — all benign); host suite **1348 tests, 0
  failures** (1364 at this cut)
- Carries a **baseline profile** (`assets/dexopt/baseline.prof` + `.profm`)
- **Release dex contains ZERO `com.oplus.ocs` occurrences** (raw byte scan)
- Superseded candidates (do NOT upload): `66734db` (`152f1c33…`), `a4a7d12` (`5685b0c0…`), `fc43953` (`88d00e12…`), `2d6c35b` (`02cbd69d…`),
  `c6722bb` (`a5654855…`), `961b080` (`0516b0d8…`), `3a3d034` (`1c160b8c…`), `26266db`
  (`59ccb318…`), `3ff3b4b` (`19ef2b7d…`), `9f367c1` (`f04028f7…`), `3c70639` (`70f83bdd…`),
  `6bf2325` (`c238c1cf…`), `a0d4dbc` (`84a74f64…`), `69af1574…`, `a737483f…`, `7339e00d…`,
  `b45a3b8e…`

### Device matrix — 2026-08-02 (four targets) plus a 2026-08-03 re-check

The four-target matrix below was measured on the `66734db` cut. `0f1421e` adds only the
policy-block gate and its AppOps confirmation; it was re-verified 2026-08-03 on the two targets
still reachable, both proven byte-identical to the artifact (`c3dec1a9…`) first:

| | Lenovo TB331FC (A15) | Android 13 emulator |
|---|---|---|
| Launch / camera | clean, gate absent | clean, gate absent |
| Lens rail | `1× lens` + `3× zoom` | `1× lens` + `3×` + `10× zoom` |
| Still | written | written |
| Video | written | written |
| Policy-block gate | shown when blocked, retracts when cleared | n/a (not blocked) |
| Crashes | 0 | 0 |

**NOT re-verified on `0f1421e`: the PMA110 and the Lenovo TB336ZU.** The PMA110 went off Tailscale
and is PIN-locked; the TB336ZU is off the network entirely (ping 100% loss, no mDNS). Their
`66734db` results below stand, and the delta between the two cuts touches only a code path that is
inert unless AppOps withholds the camera — but that is reasoning, not measurement, and is recorded
as such.

| | OPPO PMA110 | Lenovo TB336ZU | Lenovo TB331FC | Android 13 emulator |
|---|---|---|---|---|
| OS | Android 16 (36) | Android 16 (36) | **Android 15 (35)** | **Android 13 (33)** |
| Why it is here | the target device | one-camera tablet, MediaTek encoder | one-camera tablet, **Qualcomm** encoder | the `minSdk 33` floor |
| Lens rail | `0.6/1/3/10×` all **lens** | `1× lens` + `3× zoom` | `1× lens` + `3× zoom` | `1× lens` + `3×` + `10× zoom` |
| Still | 3064×4080 | 1920×2560 | **1944×2592** (was 2448×2448 square) | 1392×1856 |
| Video | Main 10 2160×3840 HLG | Main 1440×2560 `bt709` | Main 1008×1792 `bt709` (ladder −2 rungs) | Main 540×960 `bt709` (ladder −1 rung) |
| Audio | AAC 48 kHz stereo | AAC 48 kHz stereo | AAC 48 kHz stereo | AAC 48 kHz stereo |
| Gammas offered | all five | SDR only | SDR only | SDR only |
| Crashes / ANRs | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

**Two of the four encoders refuse the full-size PORTRAIT buffer** — the AOSP software encoder and
the TB331FC's Qualcomm one, the latter falling back two rungs (1512×2688 → 1008×1792). Only PMA110's
QTI encoder and the TB336ZU's MediaTek one take it. Without the same-aspect ladder, recording was
impossible on half the tested encoder families, which is why `fc43953` and earlier must not ship.

**Same-device before/after that justifies the gamma gate** — the TB336ZU, 1440p, hardware
`c2.mtk.hevc.encoder`:

| | stream | container tags |
|---|---|---|
| `fc43953` and earlier | `Main`, `yuv420p` (8-bit) | `bt2020nc / arib-std-b67 / bt2020` — HLG claim |
| this cut | `Main`, `yuv420p` | `bt709 / bt709 / bt709` — matches the stream |

**A device may block the camera for ONE app while the permission reads granted.** The TB331FC
arrived with `appops CAMERA: ignore` at the UID level and `REVOKED_COMPAT` on the permission, so
`checkSelfPermission` returned GRANTED while `openCamera` was rejected with `Camera "0" disabled by
policy` — the stock camera worked. The app did not crash and correctly disabled the shutter, but it
said NOTHING, leaving a black viewfinder with no explanation. **This is an open UX gap** (see
BACKLOG): the permission gate is satisfied, so the existing "Enable camera access in Settings" copy
never appears. Realistic on managed/work devices and OEM privacy managers.

**Honesty limits of the Android 13 coverage.** It is an emulator with a synthetic camera, so it
proves API-level compatibility, Camera2 enumeration, session bring-up, the encoder paths, and the
save pipeline — NOT image quality, optics, or any HAL-specific behaviour. A real Android 13 handset
has not been tested. The converter-mounted optical checks still need the physical teleconverter.

**Test-procedure note (cost a false alarm here).** The debug package is a separate `applicationId`,
so both apps can be installed at once — but they contend for the same camera. Launching the release
build while the debug build still held a device produced a black, frozen viewfinder with a disabled
shutter and a photo that silently never landed; `dumpsys media.camera` showed the release client
CONNECT then DISCONNECT ten seconds later. **Force-stop the other package before testing either
one**; the failure is a two-client artifact, not a product defect. Second gotcha: the release
package launches in its PERSISTED mode, so select Photo explicitly before expecting a still.

### Last SIGNED artifacts (2026-08-01, `main` at `2d6c35b`) — SUPERSEDED BY SOURCE, do not upload

Kept as the record of the last successful signed cut and of what has been smoke-tested on a signed
binary. It is **not** the upload candidate: see the drift list in the status block above.

**This supersedes the `c6722bb` cut** (AAB `a5654855…`, APK `7fd036ee…`) — adds BOTH perf batches
(16-item CPU/memory review incl. the ZSL never-serve drive gate and the unattended-timelapse dim),
`minSdk 33` + the DeviceProfile multi-device seam, the scope-stride fix, the 17-finding dual-lens
review closure (timelapse press-to-stop + run OSD, front-mirror parametrization, host-focal EXIF),
and the contextual READ_MEDIA reinstall gallery restore. **The manifest permission set CHANGED**
(visual-media READ trio added): re-answer the Data Safety "Photos and videos" ACCESS question and
use the updated `docs/play-data-safety.md` / `PRIVACY.md` wording at submission. Before it the
`961b080` cut (AAB `0516b0d8…`, APK `54035d8a…`) — that one predates the OPPO
quick-button binding (781), the eviction-quiet camera health path, and the route-switch RAW toast
removal — and before it the `3a3d034` cut (AAB `1c160b8c…`, APK `8d73388e…`) — that one predates
the loupe-hint pre-converter scaling (operator-requested) and the OPPO-767 half-press routing fix —
and before it the `26266db` cut (AAB `59ccb318…`, APK `9658300c…`) — that one predates the
adversarial verification closure (must-fix: recall packets re-normalized against outgoing caps;
plus finder zoom-OUT edge, late-RAW retention veto, rollback mirror leg, OIS strip gate) — and
before it the `3ff3b4b` cut (AAB `19ef2b7d…`, APK `870ac286…`), which predates the
2026-07-30 whole-app review fixes (pre-open caps guard, finder zoom re-resolve, pending-sibling
sweep, MR recall/store fidelity, hardware-key audio scoping, gesture closure lifetimes, EXIF
honesty) — before it the `9f367c1` cut (AAB `f04028f7…`, APK `ad66b179…`), and before it the `3c70639` cut (AAB `70f83bdd…`, APK
`97e53333…`), which in turn superseded
the `6bf2325` cycle-9 cut (AAB `c238c1cf…`, APK `615ff06d…`). Do not upload either older bundle.
The re-cut exists because `3c70639` predates the DNG route-input fixes (`d6ef232`, `968f13b`,
`9f367c1`): on that artifact a persisted DNG selection was inert at launch, and a Video→Photo trip
could leave DNG permanently unable to produce a RAW file.

- Artifact location: `app/build/outputs/bundle/release/app-release.aab`
- AAB SHA-256: `02cbd69dbcf3e7eff5745667911c2c8774203d24454ef98507232c4d11cf2602`
- Matching release APK SHA-256:
  `e95aa8d47547f57fa95b12d4d5b333916e075d3324eb94b1e2ef366d99329f15`
- Launch component: `me.hletrd.telecampro/me.hletrd.telecampro.MainActivity`
- AAB `jarsigner -verify`: **jar verified**; `bundletool validate`: **OK** (run on this machine
  against these exact bytes)
- APK signing: v2 valid, 1 signer,
  `CN=Jiyong Youn, L=Seoul, ST=Seoul, C=KR`, certificate SHA-256
  `9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584` — **unchanged from the recorded
  upload certificate**, so this is the same upload key
- APK alignment: 16 KiB passed (`zipalign -c -P 16 4`)
- Merged-manifest permissions confirmed on the built APK (`aapt2 dump badging`): CAMERA,
  RECORD_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED — and still
  **no INTERNET**
- Release gate: `lintRelease` **0 errors / 8 warnings** (`ApplySharedPref`, `UseKtx`,
  `AndroidGradlePluginVersion`, plus `InlinedApi` newly surfaced by the minSdk 33 floor — all
  benign); host suite **1291 tests, 0 failures** at the time of that cut (`main` is now 1329)
- Carries a **baseline profile** (`assets/dexopt/baseline.prof`, 11 KiB + `.profm`) installed by
  androidx.profileinstaller. Without it the shipped APK sat at `status=verify` and ran interpreted
  until JIT warmed; device-measured, the worst frame on opening the settings sheet went 61 ms → 22 ms
  and idle-viewfinder p99 10 ms → 7 ms.
- **Smoke-tested on the PMA110 from this exact APK** (2026-08-01). The installed APK's on-device
  `sha256sum` was confirmed byte-identical to the artifact above BEFORE the run
  (`e95aa8d4…` both sides), so this is evidence for this bundle and not an earlier one.
  This run selected PHOTO explicitly before the shutter (the prior cut's smoke learned that the
  release package launches in its persisted mode) and wrote a HEIF still; zero crashes/ANRs. The
  measured-audio and DNG-route evidence below is from the earlier cuts and those pipelines are
  unchanged by this re-cut. Covered on
  the release binary. **NOT on a minified one** — `isMinifyEnabled = false`, verified 2026-08-03 by
  finding `CameraEngine` (155 hits), `encoderSizeLadder` and `pickStillSize` as plain strings in
  `classes3.dex`. The earlier wording here claimed R8 coverage the build never had:
  - Photo: HEIF written.
  - Video: recorded and stopped, MP4 written.
  - **DNG: enabled live from the sheet, which re-resolves the route, then captured — DNG + HEIF
    written, DNG parsed at 4080×3064 16-bit, `FocalLength` 7.73 mm, Make `OPPO` / Model `PMA110`.**
    This proves the fix survives into the release VARIANT. It proves nothing about `keep` rules —
    the earlier claim that "a keep rule miss would have shown here" was false, because nothing is
    minified. If minification is ever enabled, this check must be repeated to mean that.
  - **Audio: measured, not assumed.** With audio off the clip carried a VIDEO track only (the
    `doAudio = recordAudio && hasRecordPermission()` path). With audio on, the same scene produced
    AAC 48 kHz stereo whose PCM measured mean −52.3 dB / peak −38.6 dB with 98.6 % non-zero samples —
    real room ambience, not a silent track. The recording level meter was visible throughout.
  - `logcat`: **zero** `FATAL EXCEPTION` and zero ANRs for `me.hletrd.telecampro` across the run.
- Packaged binary manifest (not just the source): `minSdkVersion 33`, `targetSdkVersion 36`,
  `compileSdkVersion 37`, **no `INTERNET`**, **no debuggable flag**. `uses-permission` is `CAMERA`,
  `RECORD_AUDIO`, and the visual-media READ trio (plus the framework's own dynamic-receiver
  permission). *(Corrected 2026-08-02: this line still read `minSdkVersion 36` and "exactly CAMERA
  + RECORD_AUDIO" long after the minSdk 33 floor and the READ_MEDIA trio landed — it contradicted
  the `aapt2 dump badging` line eight bullets above it in this same section. Re-verified against
  the packaged binary.)*
- **Release dex contains ZERO `com.oplus.ocs` occurrences** — verified by raw byte scan for both
  `com/oplus/ocs` and `com.oplus.ocs` across `classes.dex` and `classes2.dex`. The OEM SDK is absent
  from the shipped binary, which is what the Data-Safety answers rest on.
- Superseded candidates (do NOT upload): `3c70639` (`70f83bdd…`), `6bf2325` (`c238c1cf…`), `a0d4dbc` (`84a74f64…`),
  `69af1574…`, `a737483f…` (9541697, pre-namespace-move), `7339e00d…`, `b45a3b8e…`.

### PMA110 release device matrix — HISTORICAL (2026-07-28 artifact `99d227d6…`)

**This section does NOT describe the artifact pinned above, and never did.** It is the matrix for
the 2026-07-28 cut (`99d227d6…`), two signed cuts earlier; the heading said "THIS artifact" while
sitting under a section that has since been re-pinned twice, which reads as coverage the current
bundle does not have. Kept for the permission-lifecycle findings, which are still true of the code.

**Verified against the 2026-07-28 release APK `99d227d6…`** (installed as an update over the prior
release):

- Installed APK verified byte-identical to the artifact (`sha256sum` on device) before testing.
- Cold launch to a live viewfinder, loupe + corner overview active; live PID held.
- Photo: HEIF written (DCIM/TeleCamPro 14 → 15 files).
- `logcat -b crash`: **zero** entries for `me.hletrd.telecampro`; zero ANRs.

**Verified the same day on the DEBUG package built from `8519eaa`** (one commit before the front
tap-AF fix, which is host-tested only) (the debug variant is a
separate `applicationId`, so its runtime permissions are independent — which is what made a true
first-launch audit possible; `pm grant`/`pm revoke` are both refused on ColorOS, so virgin permission
state needs uninstall + reinstall):

- Fresh install, nothing granted → CAMERA requested immediately, no error.
- CAMERA denied once → the gate re-requests successfully; denied twice → "Enable camera access in
  Settings." with a button that opens `InstalledAppDetails`.
- With RECORD_AUDIO denied, REC now **records a video-only clip** — `RecordingSpec: admitted …
  audio=false`, `MUTE` in the OSD, a 30.3 s HEVC 2160×3840 file with exactly one stream and no audio
  track. **This supersedes the older "REC refused cleanly with no phantom file" note**, which
  described pre-`06fc6d1` behaviour and is no longer what the code does; refusing the take withheld a
  recording the pipeline can fully deliver.
- Granting the microphone afterwards through the rationale → system dialog re-enables audio normally.
- Saved stills carry no GPS: parsing a HEIF's TIFF IFDs found no GPS IFD pointer in IFD0 or ExifIFD.

**Still pending against this artifact**: a video record/stop cycle and an HLG transfer check on the
RELEASE package specifically (both were exercised on debug from the same source), and the
converter-mounted optical checks that need the physical teleconverter.

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

**Since 2026-08-01 the manifest also declares the visual-media READ trio (READ_MEDIA_IMAGES /
READ_MEDIA_VIDEO / READ_MEDIA_VISUAL_USER_SELECTED)** for the reinstall gallery restore. This
reads the user's own captures ON DEVICE only — nothing is collected or transmitted, so the
collect/share answers above stay "No".

Documentation status (2026-08-02): `docs/play-data-safety.md` and `PRIVACY.md` **both now carry the
on-device library-access wording** — that half is done. While checking it, `PRIVACY.md` was found
pointing users at `DCIM/X9Tele`; the shipped `MediaStoreWriter.CAPTURE_SUBDIR` is `TeleCamPro`, so
the published policy named a folder that does not exist on the user's phone. Fixed in `ee80094`.

**Still owner-only: the console answer itself.** The Data Safety "Photos and videos" ACCESS
question has to be re-answered in Play Console at submission; no repo change can do that.

## Assets

- Hi-res icon: `docs/assets/play/icon-512.png`
  - 512 x 512 PNG
  - 32-bit PNG with alpha
- Feature graphic: `docs/assets/play/feature-graphic.png`
  - 1024 x 500 PNG
  - no alpha
- Phone screenshots — recaptured **2026-07-27 (cycle 9)**, cropped to **1440x2880 (exactly 2:1)**
  24-bit PNG, no alpha. Play rejects anything taller than 2:1 and this panel is 1440x3168 (1:2.2).
  The crop box is **(0, 168) → (1440, 3048)**, derived by MEASUREMENT rather than guessed: on this
  panel the OS status-bar glyphs end at y=101 and the lowest app pixel (the shutter ring) sits at
  y=3041, so this window strips both system bars and loses no app content. Verified per file — only
  `02` touches the bottom edge, and that is the MR card of a SCROLLING sheet, which reads correctly
  as "more below".
  - **Provenance is mixed, deliberately.** The three viewfinder frames come from the operator's own
    handheld captures against real subjects (buildings, sky) because the listing wants
    photography-led art; the three menu frames come from the signed RELEASE build installed on the
    PMA110, since a menu needs no scene and must show the shipping UI.
  - Landscape captures were REJECTED for this set. The app counter-rotates glyphs by the
    gravity-derived device orientation, so a landscape-held capture renders the focal rail and mode
    carousel sideways. This is app behaviour, not a bug — capture PORTRAIT.
  - The 2026-07-10 and 2026-07-26 captures are retired: they showed a superseded UI, and every
    TELE-engaged frame among them shows the OLD focal rail (`0.6x 1x 3x 10x`) where the shipping
    build now reads `13x / 30x / 60x`.
  - `screenshots/01-main-viewfinder.png` — photo viewfinder: OSD row, focal rail, mode carousel,
    shutter
  - `screenshots/02-pro-settings.png` — Shooting tab: output format, aspect, zoom, JPEG quality,
    drive mode, self-timer
  - `screenshots/03-focus-tools.png` — Focus tab: AF modes, spot size, AF lock, peaking level/colour
  - `screenshots/04-video-controls.png` — video mode: REC control, live encoder OSD
  - `screenshots/05-lens-and-tele.png` — focal rail with the 300 mm TELECONVERTER engaged (the
    app's signature route: 3x pinned, TELE tag lit). The Fn quick-control sheet was tried first but
    would not survive a screencap — it dismisses before the frame lands, so this shot carries the
    lens story instead.
  - `screenshots/06-video-settings.png` — Video tab: codec, Open Gate, resolution, FPS, bitrate,
    live encoder summary, transfer curves
  - All six render UPRIGHT: the first captures had every rotating glyph sideways because the app
    counter-rotates glyphs by the gravity-derived device orientation and the phone was standing in
    a landscape pose. Capture with the phone PORTRAIT — this is app behaviour, not a bug, and no
    code change is involved.
  - 01 and 04 now carry LIT, real subjects (the "photography-led art" option this note always
    offered). **05 is still a BLACK-scene frame and is the one weak spot in the set**: it is the
    only slot that requires TELE engaged, and every real-subject TELE capture on hand predates the
    focal-rail change, so it would advertise a rail the shipping build no longer draws. It is
    correct and current as-is — it shows `13x / 30x / 60x` and the loupe overview with its framing
    hint — but a handheld TELE capture against a distant subject would make the three viewfinder
    frames consistent. Replace it with a PORTRAIT capture, TELE lit, and re-crop with the box above.

## Device Catalog

**The app is no longer single-device.** Hardware is resolved by enumerating Camera2 capabilities
rather than model names, and the teleconverter presets explicitly cover the Find X9 Ultra and X9 Pro,
vivo X200 Ultra and X300 Ultra, plus generic clip-ons — a catalog locked to two model codes would
contradict the app's own UI.

**This section's premise changed and the decision is now materially bigger.** It used to lean on
`minSdk 36` excluding almost every device on the market — Android 16 did the narrowing for us. The
floor is now **`minSdk 33` (Android 13)**, so an open catalog reaches an enormous, entirely
unverified device population. Only the PMA110 is device-verified; the Lenovo TB336ZU (Android 16
tablet) has now been through a real capture pass on the shipping artifact — still, video, EXIF,
enumeration, window shape — but not through optical quality judgement, and it is a tablet rather
than a phone.

Two options, an owner decision:

- **Open catalog (matches the app's own claims).** Ship to every Android 13+ device. Two devices
  are capture-verified out of that entire population, so accept that early reviews may come from
  untested hardware.
- **Staged.** Launch restricted to the verified models below, then widen as devices go through a
  real capture pass. Slower, but no unverified first impressions.

Capture-verified models: `PMA110` (China/import) and, as a generic-profile witness, `TB336ZU`.
`CPH2841` is the same hardware as PMA110 under the global model code and is expected to behave
identically, but has not itself been measured.

## Manual Console Sequence

1. Upload the AAB whose hashes are pinned at the top (`5685b0c0…`, from `a4a7d12`). Every other
   artifact listed in this file is superseded and must not be uploaded. If `main` has moved since,
   re-cut and re-pin first rather than uploading a stale bundle.
2. Enter the Store Listing and Data Safety answers from this repository. **The Data Safety "Photos
   and videos" ACCESS question must be re-answered** for the visual-media READ trio (see Data
   Safety below) — the last submission-ready answer set predates it.
3. Upload the icon, feature graphic, and the six checked-in screenshots — these ARE current
   (recaptured 2026-07-27, 1440x2880); the earlier "do not use the checked-in captures" warning no
   longer applies.
4. Set the device catalog per the Device Catalog section above (open vs staged is an owner call).
5. Review Play's automated checks and pre-launch report.
6. Promote the same artifact only after the internal-test install succeeds.

## Local Signing Material

These files are intentionally gitignored and stay only on the local machine:

- `telecampro-upload.jks`
- `telecampro-upload-passwords.txt.gpg`
- `keystore.properties`

> ### ✅ The stale-backup breakage is FIXED (2026-08-02) — and here is what it was
>
> `telecampro-upload-passwords.txt.gpg` was dated **2026-07-07** and held the password for the
> keystore that was **retired on 2026-07-25**. It was never re-encrypted when the key was rotated,
> so the documented rebuild procedure silently pointed at a dead secret: `keytool -list` answered
> `keystore password was incorrect` and `packageRelease` failed the same way.
>
> Resolved: the owner supplied the July-25 password, it is now in `keystore.properties` (gitignored)
> **and** in a re-encrypted backup that carries the keystore's creation date and certificate
> fingerprint in its own header, so the next rotation cannot leave the same ambiguity. Verified by
> round-trip decrypt and by a signed build whose certificate reads `9dfdb903…`.
>
> **Two things this incident should leave behind.**
> 1. `.gitignore` matched secrets by exact filename, so the `.gpg.2026-07-07.bak` copy made during
>    recovery — the RETIRED key's password — showed up as an untracked, committable file. The rules
>    now match by extension AND by name shape (`*password*`, `*secret*`, `telecampro-upload-*`,
>    `.bak`, `.orig`). Fixed in `fc43953`.
> 2. **The password is weak (six digits) and has been transmitted in plaintext.** Nothing is on Play
>    yet, so rotating the upload key today costs one `keytool -genkeypair` and one line in this
>    file. After the first upload it stops being a local decision and becomes a Play support
>    request. Rotating now is the cheap moment; this is an owner call, recorded here so it is not
>    silently forgotten.
>
> Whenever the keystore is rotated, verify with
> `keytool -list -keystore telecampro-upload.jks -alias telecampro` — the certificate SHA-256 must
> match whatever this sheet records as the upload certificate, or the wrong keystore is in place.

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

# Fail loudly instead of building an artifact nobody can verify: prove the password opens the
# keystore BEFORE spending a release build on it. (Skipping this is how the stale-backup breakage
# above stayed invisible — the build's own error surfaces only at the packaging step.)
keytool -list -keystore telecampro-upload.jks -alias telecampro \
  -storepass "$TELECAMPRO_STORE_PASSWORD" >/dev/null || {
    echo "keystore password rejected — see the stale-backup note above"; return 1 2>/dev/null || exit 1; }

./gradlew :app:lintRelease :app:assembleRelease :app:bundleRelease
```

To inspect the real release binary **without** signing (manifest, permissions,
alignment, dex scans — everything except the signature), use `:app:packageRelease`: it is outside
the `hasReleaseSigning` guard and drops `app-release-unsigned.apk` in
`app/build/outputs/apk/release/`. That is how the "Verified on current `main`" section above was
produced. Never upload that file.
