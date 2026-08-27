# Play Console Submission Sheet - TeleCam Pro

Use this sheet for the parts that must be entered manually in Play Console.

<!-- release-state: target=v1.0.2 artifact=none -->

## Upload Artifact

> ## 🚫 NOT UPLOAD-READY — v1.0.2 must be re-cut from the finalized current HEAD.
>
> The previously attested `8d5f461` cut predates current source changes. Its historical digest and
> device evidence remain below as provenance, but they do not attest the current candidate and that
> AAB must not be uploaded. `app/build/outputs/bundle/release/app-release.aab` is a mutable build
> output, not an immutable release identity: any later build can replace its bytes without changing
> the path.
>
> Current source still declares `versionCode 4` / `versionName 1.0.2`; it is the current
> source/release target, but **no current artifact candidate exists**. A new upload candidate exists
> only after the final source commit is built, verified without inventing device evidence, copied to
> a commit-and-digest-qualified path, and accepted by `tools/check_release_artifact.py` against a
> SHA-256-protected attestation. Until then this sheet is preparation material, not upload approval.
> Independently, the documented upload key is **SECURITY-BLOCKED**: its six-digit password was
> transmitted in plaintext. No signed candidate may be cut or uploaded until the owner explicitly
> approves a strong-key rotation or completes Google's upload-key reset and records the new public
> certificate fingerprint in the local fail-closed prerequisite described below.
> `versionCode 1` (v1.0, from `ca3d33c`) and `versionCode 3` (v1.0.1) are BOTH spent — Play
> rejects a re-used versionCode outright (see the note in `app/build.gradle.kts`). Release notes live in
> `docs/play-store-listing.md`.
>
> **v1.0.1 IS PUBLISHED** (`versionCode 3`), so 1.0.2 is a real release over it, not a re-cut of the
> same one. That governs the release notes: the Korean UI and the minified download shipped in 1.0.1
> and must NOT be re-announced, while the large-screen side rail — which 1.0.1's own notes sold to
> tablet owners as a new landscape layout — is REMOVED here, which is a visible change to something
> users already have. The 1.0.1 bullet list below is retained as the record of what that release
> contained, not as a description of what is new in this one.
>
> **What 1.0.2 changes on top of the 1.0.1 list:**
> - **Play stops filtering the app off hardware it runs on.** CAMERA and RECORD_AUDIO each imply a
>   `uses-feature` that Play defaults to `required=true`, and badging confirmed both were live on the
>   earlier artifact. `android.hardware.camera` is REAR-specific when the real requirement is
>   `camera.any` (FRONT is a first-class optics door), and `android.hardware.microphone` contradicts
>   the app's own design, which records video-only and silently when the mic is declined. Both are now
>   `required=false` — verified in this artifact's badging as `uses-feature-not-required`.
> - **A partial photo grant is no longer a one-way door.** "Select photos" grants
>   READ_MEDIA_VISUAL_USER_SELECTED while denying the broad pair, and the empty-gallery handler read
>   that as "has access" and re-ran the very restore query that had just come back empty. A user who
>   picked "Select photos" without hand-picking their own captures got an empty gallery and no in-app
>   way to widen the selection, ever. It now branches on the access LEVEL, and re-requests under a
>   partial grant — which is Android's own remedy, since the platform re-shows the selection UI.
> - **Orientation and the rail (2026-08-10):**
> - **Orientation moves no control.** The shutter, gallery and Fn buttons stay at the device's
>   physical bottom however it is held; only text, chips and the scopes rotate, in place. The
>   large-screen side rail is gone — device-verified across PMA110, TB331FC and TB336ZU in both
>   window orientations, plus all four injected gravity poses on an emulator: control anchors are
>   byte-identical in every pose, with zero overlapping elements.
> - **Korean UI naturalized.** One file was using two words for focus (탭 포커스 vs 탭 초점), Gamma
>   Display Assist did not use the label Korean camera bodies print (감마 표시 지원), 촬영물 read like
>   a filed document when TalkBack spoke it, and the microphone rationale's last sentence changed
>   subject mid-clause. Store copy: "조작 해상도" was a literal carry of "resolution of control" and
>   meant nothing, and the RAW line said RAW works 후면 렌즈에서만 — turning the English's selling
>   point (any rear lens advertising RAW, not TELE only) into a restriction.
> - **Feature graphic re-grounded on the icon's palette.** It used a navy radial and blue-grey body
>   text that exist in neither the icon nor the app; every colour now comes from the icon itself, so
>   the lens mark's blue is the only accent in frame. The mark is untouched — it is the shipped icon.
> - **Verified on a fourth device class: Samsung Galaxy S23 Ultra (SM-S918N), Android 16, ko-KR.**
>   First non-OPPO/non-Lenovo target, and the first with a native Korean locale, so the Korean UI was
>   read as a Korean user actually gets it rather than through `set-app-locales`. Signed 1.0.2 release
>   APK: live preview on a lit scene, zero crashes, zero overlapping elements, layout anchors matching
>   every other device. Two things this device proved that the lab fleet could not:
>   - **`LensInventory` genuinely enumerates.** The rail offered 0.6x / 1x / 3x / 10x and spoke all
>     four as 렌즈 (optical), which is exactly the S23 Ultra's four rear cameras — where the
>     single-lens tablets speak their presets as 줌. Nothing is hardcoded to the PMA110 lens set.
>   - **EXIF carries the REAL device.** A HEIF capture wrote `Make=samsung`, `Model=SM-S918N`,
>     `LensModel=samsung SM-S918N wide camera 22mm f/1.7`. Before `DeviceExifLabels.kt` those were the
>     literals `OPPO` / `OPPO Find X9 Ultra` with the lens name keyed off camera IDs, so this build is
>     the first one proven not to stamp a false camera model into another vendor's files. MediaStore
>     published 3060x4080 with `orientation=0` — the HEIF path pixel-rotates rather than tagging.
> - **Verified at the minSdk FLOOR on real hardware: Xiaomi 21061110AG, Android 13 (API 33), MediaTek.**
>   Until now API 33 had only ever been exercised on an emulator, and every physical target was
>   Qualcomm. Signed 1.0.2 release APK: camera 0 connects and is held by the app, preview streams with
>   no errors from our package, zero overlaps, layout anchors matching every other device.
>   - **The lens rail is honest about optics it does not have.** It offered `1x lens`, `3x zoom`,
>     `10x zoom`, and omitted 0.6x entirely — this phone has one usable back camera, its ultrawide is
>     not reachable through Camera2, and a preset reachable only by cropping is spoken as zoom, never
>     lens. Four hardware shapes now produce four different correct rails (PMA110 and S23 Ultra: four
>     optical; the two tablets: 1 lens + 3 zoom; this: 1 lens + 3/10 zoom). Nothing is hardcoded.
>   - **The dark-preview fluidity cap holds on non-Qualcomm silicon.** Frame delivery measured a
>     steady 14.86-15.26 fps in a dim room, which is exactly the >=15 fps floor
>     PREVIEW_FLUIDITY_MAX_EXPOSURE_NS (1/15 s) was designed to guarantee. That behaviour was tuned
>     and measured on the Qualcomm PMA110; this is the first evidence it reproduces on a MediaTek ISP.
>   - NOT verified here: capture. MIUI refuses adb input injection without its separate "USB debugging
>     (Security settings)" toggle, so the shutter cannot be driven remotely on this device.
> - **Basic functionality is OPERATOR-VERIFIED on this cut (2026-08-11).** The automated sweeps in
>   this section cover launch, layout, capture mechanics and file structure — they do not exercise
>   the controls. The owner checked basic functionality by hand and reports it working, which is the
>   same standing this sheet gives the other hands-on checks (the muxer playback check, the tablet
>   rail comparisons). Scope is what the owner stated — basic functionality — so it is recorded at
>   that width and not read as coverage of any specific subsystem.
>   **The TELECONVERTER path is included in that check.** It is the one thing no harness here can
>   reach: it needs the physical Hasselblad clamp-on mounted on the periscope, and every automated
>   sweep in this document ran on bare phones. It is also the app's entire purpose — the afocal 180
>   correction, the near-infinity manual focus and the effective-focal readouts all exist for it — so
>   an unverified converter path would have left the headline feature untested on the shipping cut.
> - **PMA110 — the reference device — IS verified on this artifact (2026-08-10).** It had been
>   unreachable for days, which was the largest caveat on this release. Wireless debugging is now on a
>   FIXED port (`adb tcpip 5555`) rather than the rotating one, and `tools/adb_fleet.sh` brings the
>   whole fleet up on stable endpoints. Result: portrait-locked 1440x3168, 17 labelled nodes, all four
>   lens presets spoken as OPTICAL, zero overlaps, zero crashes. A fresh capture wrote
>   `Make=OPPO`, `Model=PMA110`, `Lens=OPPO PMA110 periscope tele camera 230mm f/3.5`, 35 mm 234,
>   3064x4080, orientation 1 — the 10x periscope, and the exposure landed on the same 1/10 s
>   program-line floor measured on Samsung.
>   **A suspected launch stall was investigated and WITHDRAWN.** One launch had sat on
>   "Starting camera…" with the controls disabled, which looked like the documented keyguard race
>   failing to recover. Reproducing it deliberately showed something simpler: every failing attempt
>   had the device LOCKED — `isKeyguardShowing=true`, `deviceLocked=1`, the activity never
>   topResumed — because this handset has a secure lock that cannot be dismissed over adb, and it
>   re-locks between a `KEYCODE_WAKEUP` and the next command. An app behind a keyguard not holding
>   the camera is correct behaviour, not a defect. The successful capture above was taken while the
>   device was genuinely unlocked. No code issue is open from this; anyone re-testing should confirm
>   `deviceLocked=0` BEFORE launching, or the run measures the lock screen rather than the app.
> - **POCO 21061110AG (Android 13 / API 33, MediaTek) verified on this artifact (2026-08-10).** The
>   minSdk FLOOR on real hardware and the only non-Qualcomm ISP in the fleet, back after its wireless
>   port rotated and now on the same fixed 5555. Zero crashes, camera client live, 13 labelled nodes,
>   zero overlaps, and the rail honest about optics it does not have: `1x lens`, `3x zoom`,
>   `10x zoom`, no 0.6x at all.
>   **Preview proven with REAL CONTENT, not just cadence.** Pointed at a lit scene the viewfinder
>   renders correctly (mean luma 123.7, stddev 40.5, grid overlay drawn over live pixels), so the
>   camera -> GL -> display path is confirmed end to end at API 33 on a non-Qualcomm ISP. Earlier runs
>   on this device could only show frame CADENCE (the ~15 fps dark fluidity cap) against a dark frame.
>   **Capture on this device is OWNER-VERIFIED (2026-08-12).** It could not be driven from here: MIUI
>   refuses adb input injection without its separate "USB debugging (Security settings)" toggle,
>   re-confirmed twice this session rather than assumed from the earlier run. So the last unverified
>   surface on the fleet was closed by hand, on the one device that is both the minSdk FLOOR (API 33)
>   and the only non-Qualcomm ISP.
> - **Fleet-wide re-verification of the shipping artifact (2026-08-09, APK `50a64755…`).** All four
>   reachable devices installed, launched and captured a still: SM-S918N (A16), TB331FC (A15),
>   TB336ZU (A16), emulator (A13). Zero crashes, zero overlapping elements, correct anchors on every
>   one, and each rail device-appropriate (S23 four optical 렌즈; both tablets 1 lens + 3 zoom;
>   emulator 1 렌즈 + 3/10 줌).
>   **EXIF identity is per-device, which is the whole point of `DeviceExifLabels.kt`:**
>   `samsung`/`SM-S918N`, `LENOVO`/`TB331FC`, `LENOVO`/`TB336ZU`, `Google`/`sdk_gphone64_arm64` —
>   four vendors, four correct identities, where the pre-fix literals would have stamped
>   `OPPO`/`OPPO Find X9 Ultra` into all of them. Orientation is 1 on all four, correct for the HEIF
>   path, which pixel-rotates rather than tagging. TB331FC self-consistency: its 3x preset reported
>   81 mm against a measured 27 mm main, matching the 27/81 figure recorded for that tablet.
>   Caveat on one number: the emulator's file is 3.4 KB and its 357 mm reading comes from the fake
>   camera's synthetic characteristics — an emulator artifact, not a measurement of anything.
> - **Dark-room stills on Samsung land exactly on the documented program-line floor.** A still shot
>   in a genuinely unlit room wrote a TRUE `ExposureTime 1/10 s, ISO 3200, f/1.7, 22 mm equiv`, with
>   `Make=samsung` / `Model=SM-S918N`. 1/10 s is precisely the dark ceiling `driveProgram` is
>   specified to slide to once ISO clamps, so the app-side program line reproduces its designed
>   behaviour on non-PMA110 hardware. Nothing from the GL preview leaked into the file.
>   Observation, not a defect: that still decodes far darker (mean luma 2.9) than a video frame of the
>   same room (24.0). It follows from the documented AE split — photo PROGRAM is app-side and caps the
>   shutter at 1/10 s for handheld sharpness, while video-P runs on the HAL AE, which is free to gain
>   further. The clamp premise is CONFIRMED, not assumed: this camera advertises
>   `android.sensor.info.sensitivityRange [50 3200]` and `maxAnalogSensitivity [3200]`, so ISO 3200 is
>   the ceiling and the loop stopped where it must. (The values live on the line AFTER each key-index
>   entry in `dumpsys media.camera`; reading only the index line is what first made this look
>   unverifiable.) A dark single-shot at the sensor's ceiling with a handheld-safe shutter is the
>   honest limit of this pipeline, not a defect — closing it would need multi-frame stacking, which
>   the app does not do.
>   Also NOT tested: the up-to-x16 GL brightness simulation, which needs an M-mode long want-exposure
>   rather than PROGRAM.
> - **Video verified on the whole reachable fleet (2026-08-09).** A clip recorded through the UI on
>   each of the four devices, all HEVC + AAC 48 kHz stereo, two tracks, ~11 s:
>   S23 `2160x3840` 29.97 fps 79.9 Mbps; TB331FC `1008x1792` 29.97 fps 31.8 Mbps;
>   TB336ZU `1440x2560` 29.75 fps 28.8 Mbps; emulator `540x960` 29.5 fps 0.2 Mbps.
>   **Every one is PORTRAIT geometry**, which is the `RotationMath.encoderSurfaceSize` swap holding
>   across four vendors — including both tablets, whose WINDOWS were landscape at the time, exactly as
>   designed (encoder framing is gravity-derived, never window-derived).
>   TB331FC decoded REAL LIT CONTENT (mean luma 115, stddev 58) matching its own viewfinder, so this
>   is a true end-to-end check on at least one device, not just a container inspection.
>   One honest limit remains: S23 and TB336ZU decoded dark/black because both rooms were unlit — for
>   TB336ZU this was checked rather than assumed, since its still minutes earlier was lit (mean 99.9):
>   its PHOTO and VIDEO previews now read 5.2 and 1.0, so the scene changed between captures, and a
>   video-only fault would have shown photo lit against video black.
>   **Playback orientation is CLOSED (owner-verified 2026-08-12): correct in BOTH modes and ALL
>   orientations.** The harness could not settle this — no clip here carried rotation side-data
>   because the tablets lie FLAT, where gravity holds its last confident value by design, so a
>   flat-device run measures the hold rather than the hint. It needed a held device and a human
>   watching playback, which is exactly what it got.
> - **Video verified off the PMA110 for the first time (Galaxy S23 Ultra, 2026-08-08).** A clip
>   recorded through the UI muxed as HEVC **2160x3840** + AAC 48 kHz stereo, 30000/1001, 82.3 Mbps,
>   10.08 s, both tracks present. The portrait geometry is the point: `RotationMath.encoderSurfaceSize`
>   swaps the encoder buffer to the DISPLAYED portrait aspect so `coverScale` records the viewfinder
>   field instead of a centre band, and that cycle-4 framing contract was measured only on the PMA110
>   until now. No rotation side-data, correct for a portrait-held device.
>   **Honest limit:** the room was dark (02:45 local), so decoded frames are near-black — mean luma
>   24.0. That is the SCENE, not the encoder: a screenshot of the live preview taken at the same
>   moment reads mean 26.0 over the same band, so the file matches what the sensor was seeing. It
>   proves container, codec, geometry, rate and track structure; it does NOT prove image quality, and
>   a lit re-shoot is still owed.
> - **Labels no longer lie on their side on a resting tablet.** The glyph rotation is the residual
>   `deviceOrientation - windowRotation`, and both terms hold their last confident value when a
>   device goes flat — on INDEPENDENT thresholds. On TB331FC the window held ROTATION_90 while
>   gravity never left its initial 0, and the two stale numbers subtracted into a confident-looking
>   90°, laying every label, chip and OSD tag on its side beside an upright menu rail. A free window
>   is now treated as authoritative and owed no glyph rotation.
>
> **What v1.0.1 (`versionCode 3`) added over v1.0 — already published, listed for the record:**
> - **Korean UI.** 126 strings became resources with a `ko` translation, and the app declares
>   `localeConfig` so the platform treats it as locale-aware — without that, an explicit
>   `cmd locale set-app-locales ko-KR` left the UI in English with the Korean resources unused in the
>   APK. Camera-standard abbreviations (ISO, WB, SS, EV, AF, NR, FPS, Fn, Open Gate) are
>   `translatable="false"`: Korean camera bodies print them in Latin too, so that DECLARES the intent
>   rather than suppressing the lint warning that asked. The count is source-derived from the
>   published versionCode-3 pin `bcbeaf0c`: its `values-ko/strings.xml` contains exactly 126
>   `<string>` entries.
> - **Top chrome no longer collides.** In VIDEO the button row shifts down to clear the preview edge
>   while the OSD row was pinned at a fixed 60 dp, so the buttons landed on top of it — measured on
>   PMA110 as buttons y=332-500 over OSD text at y=391-436, with STEADY/LOUPE/battery squeezed into
>   the 28 px gaps between buttons. Both rows now take the same offset. In PHOTO, eight 48 dp targets
>   need 384 dp and a 411 dp phone leaves 387 dp after padding, so the eighth was clipped to a 12 px
>   sliver — and GRID lost that race, whose lines paint on the live image and whose button is the only
>   thing that clears them. The self-timer gives up its IDLE slot (owner's call); an armed timer still
>   draws.
> - **Loupe Overview clears the focal rail in every aspect.** Its inset is now measured by the layout
>   rather than guessed as a fraction of the preview box: the preview runs BEHIND the bottom chrome by
>   13 dp on a 411 dp phone and 90 dp on a 941 dp tablet, so no scale-free fraction could express it.
>   A related defect went with it — the wide (tablet rail) layout was reading a STALE portrait
>   bottom-cluster height across the rotation that flips the branch, which affected the preview
>   placement itself, not just the overview.
> - **Play's large-screen orientation flag is gone.** `android:screenOrientation="portrait"` only ever
>   reached handsets (Android 16 ignores it at sw600dp+, API 37 removes the opt-out), but Play's check
>   reads the manifest statically and could not see that. The lock is applied at runtime from
>   `smallestScreenWidthDp` instead — identical behaviour, and now the static claim is true.
> - Korean store copy for the listing, and a deslop pass over the UI strings and both descriptions.
> - **The Focus-ruler loupe assist is no longer a setting.** Opening the ruler punches in by itself,
>   and it did that through the operator's own toggle — which schedules a save. Backgrounding with
>   the ruler open therefore persisted a loupe nobody asked for, and it came back on the next launch;
>   the branch that undoes the assist never runs on that path. The assist now has its own entry point
>   that does not persist, and the save snapshot reads the operator's value while the assist owns the
>   loupe, because any unrelated save landing mid-assist would otherwise capture it just the same.
>
> **`versionCode 2` was cut and never registered; `1` and `3` are both SPENT on Play.** `4` is the
> upload candidate.
>
> Supersedes `91b26a2`, `0f1421e`, `66734db`, `a4a7d12`, `fc43953`, and every candidate before it.
> `applicationId` unchanged (`me.hletrd.telecampro`); upload certificate byte-identical to the
> recorded one (`9dfdb903…`).
>
> **The main thing this cut adds over `0f1421e`: the zoom-scale fix — a user-reported defect on the
> target device.** Tapping `3×` on a PMA110 jumped the viewfinder to 9.1×, and the rail pill then
> disagreed with the zoom it had produced. `zoomRatio` carries TWO scales — main-relative on the
> logical seamless camera, lens-local on any standalone lens — and three call sites were reading
> whichever one they happened to receive. The route now decides, through one conversion pair
> (`unifiedZoomOf` / `localZoomOf`, host-tested), so the preset, the wire ratio, the focal readout,
> and the highlighted pill all describe the same framing. Fixed across three commits (`f94f2b5`,
> `1914eac`, `c66993d`) because the first attempt was correct on the phone and wrong on both
> tablets — a second device shape is what caught it.
>
> **And the second user-reported defect: starting the camera did not take long — the status pill
> did.** `am start` returns in 412 ms and the session configures at ~950 ms, but `"Starting camera…"`
> matched no keyword in the status classifier and fell into its neutral 2.5 s bucket, so the pill
> outlived the bring-up it described. A progress message reports a condition, so an event ends it:
> it now carries no display timer and the owned Ready publication retires it. Device A/B on two
> shapes — before, still on screen 5.2 s (TB331FC) and 4.1 s (Android 13) after `am start`; after,
> never sampled across a 20 s window on either.
>
> Also in this cut: five UI strings corrected (each contradicted a rule this project states
> itself), Apache-2.0 licensing with a NOTICE naming every trademark owner, and the store/privacy
> documents brought into agreement with the shipped permission set.
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

### Historical v1.0.2 cut at `8d5f461` (superseded — DO NOT UPLOAD)

- Historical build output location: `app/build/outputs/bundle/release/app-release.aab` (mutable;
  this path does not prove those bytes are still present)
- AAB SHA-256: `3180dc5622be16b5b319d647f7f9fd198c37a232b095435ad9f2e720a41a1d36`
- Matching release APK SHA-256:
  `648fae69a92e2bb73de625036b248c136cd58ffb718b2d9520667173abfa0093`
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
- Release gate: `lintRelease` **0 errors**; host suite **1388 tests, 0 failures**
- Carries a **baseline profile** (`assets/dexopt/baseline.prof` + `.profm`)
- **Release dex contains ZERO `com.oplus.ocs` occurrences** (raw byte scan)
- Superseded candidates (do NOT upload): `fe6a8a0` (`9f4c02b9…`, versionCode 3 — this is the PUBLISHED v1.0.1; spent, do not re-upload), `0bc4c2f` (`bfd3eb2e…`, versionCode 2, never registered), `ca3d33c` (`674a9bd3…`, the PUBLISHED v1.0), `91b26a2` (`8cb63592…`), `0f1421e` (`543a8343…`), `66734db` (`152f1c33…`), `a4a7d12` (`5685b0c0…`), `fc43953` (`88d00e12…`), `2d6c35b` (`02cbd69d…`),
  `c6722bb` (`a5654855…`), `961b080` (`0516b0d8…`), `3a3d034` (`1c160b8c…`), `26266db`
  (`59ccb318…`), `3ff3b4b` (`19ef2b7d…`), `9f367c1` (`f04028f7…`), `3c70639` (`70f83bdd…`),
  `6bf2325` (`c238c1cf…`), `a0d4dbc` (`84a74f64…`), `69af1574…`, `a737483f…`, `7339e00d…`,
  `b45a3b8e…`

### Device matrix — 2026-08-02 (four targets) plus 08-03 and 08-04 re-checks

**These matrices all PREDATE v1.0.2 and are kept as the record of the cuts they name, not as a
statement about the current one.** Rows that mention the rail layout were true of `fe6a8a0`; the rail
was removed on 2026-08-05. The old 1.0.2 cut's verification remains in the superseded provenance
above; no current upload-ready banner exists.

The four-target matrix below was measured on the `66734db` cut. Two later cuts were re-checked on
whichever targets were reachable at the time, each proven byte-identical to its artifact first.

**Measured 2026-08-04 on the signed `fe6a8a0` RELEASE artifact (`fb49f6fb…`), bytes confirmed on
each device before testing:**

| | OPPO PMA110 | Lenovo TB336ZU | Lenovo TB331FC |
|---|---|---|---|
| OS | Android 16 (36) | Android 16 (36) | **Android 15 (35)** |
| Top-chrome overlaps, photo / video | 0 / 0 | 0 / 0 | 0 / 0 |
| Loupe Overview vs focal rail | 98 px clear in BOTH aspects | no chip overlap (rail layout) | no chip overlap (rail layout) |
| Still | written | written | written |
| Video | operator-verified | operator-verified | operator-verified |
| Crashes / ANRs | 0 / 0 | 0 / 0 | 0 / 0 |

Overlap is counted as a real rectangle intersection (>4 px on both axes) between every pair of
top-area nodes, not by eye. The tablets sit in the WIDE layout, where the controls own a side column
rather than a row under the frame, so the overview and the rail are separated horizontally — the
vertical distance there is not a clearance and is not reported as one.

**The Android 13 emulator is out of scope for this cut (owner's call) and was not re-verified.** Its
`minSdk 33` floor coverage stands from the `66734db` matrix below.

**Every focal is an exact multiple of that device's own `1×`**, which is the point: the OSD renders
`caps.equivalentFocalMm × zoomRatio`, so a preset that wrote into the wrong scale shows up here
immediately (the reported defect put ~208 mm behind the PMA110's "3×"). PMA110 measures 23.4 mm
equivalent on this route, hence 14.0 / 23.4 / 70.2 / 234. An earlier debug run on the same code
read 23 / 69 / 230 — the same exact multiples of a route whose measured equivalent was 23.0 mm, not
a discrepancy.

Only the PMA110 reaches `3×` and `10×` with real glass; both tablets' `3×` is a crop and reads
exactly ×3 of their own main lens, which is why they say "3× zoom" rather than "3× lens". That
split between phone and tablets is the evidence the conversion resolves by ROUTE rather than by the
preset tapped.

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

**Historical pre-fix observation: a device may block the camera for ONE app while the permission
reads granted.** On this matrix's superseded cut, the TB331FC arrived with `appops CAMERA: ignore` at
the UID level and `REVOKED_COMPAT` on the permission, so `checkSelfPermission` returned GRANTED while
`openCamera` was rejected with `Camera "0" disabled by policy` — the stock camera worked. That cut
did not crash and correctly disabled the shutter, but it said nothing and left a black viewfinder.
This gap is closed in the current build: the AppOps-confirmed path shows "Camera blocked for this app
on this device." plus Settings, as recorded in the current release delta at lines 281–287 above.
The original condition remains realistic on managed/work devices and OEM privacy managers.

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

### PMA110 smoke test on a signed binary — 2026-08-01 artifact `e95aa8d4…` (SUPERSEDED)

**Do not upload this artifact.** It is kept for its MEASUREMENTS: this is the most recent run of
the photo / video / DNG / audio pipelines against a *signed release* binary on the target phone,
and those pipelines were unchanged by the immediately following historical cuts. There is no current
upload candidate; the top of this sheet requires a fresh immutable cut.

The full supersession chain behind it lives in `git log`; every superseded digest is listed in the
do-not-upload bullet under the current pin. Re-narrating each cut's delta here is what let this
section's own heading claim "last signed cut" through two later signed cuts.

One carry-forward that is a submission action, not history: the manifest permission set CHANGED at
`2d6c35b` (the visual-media READ trio was added). Re-answer the Data Safety "Photos and videos"
ACCESS question and use the current `docs/play-data-safety.md` / `PRIVACY.md` wording.

- Historical build output location: `app/build/outputs/bundle/release/app-release.aab` (mutable path;
  retained only to describe where the measured bytes were produced)
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
  benign); host suite **1291 tests, 0 failures** at the time of that cut
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
  the release binary. **NOT on a minified one** — THAT cut had `isMinifyEnabled = false`, verified
  2026-08-03 by finding `CameraEngine` (155 hits), `encoderSizeLadder` and `pickStillSize` as plain
  strings in `classes3.dex`. The earlier wording here claimed R8 coverage the build never had. R8 was
  enabled on 2026-08-04 and re-earned its own evidence — see the minified-build entry below:
  - Photo: HEIF written.
  - Video: recorded and stopped, MP4 written.
  - **DNG: enabled live from the sheet, which re-resolves the route, then captured — DNG + HEIF
    written, DNG parsed at 4080×3064 16-bit, `FocalLength` 7.73 mm, Make `OPPO` / Model `PMA110`.**
    This proves the fix survives into the release VARIANT. It proves nothing about `keep` rules —
    the earlier claim that "a keep rule miss would have shown here" was false, because nothing is
    minified. That repeat came due when minification landed and was carried out — see below.
  - **Audio: measured, not assumed.** With audio off the clip carried a VIDEO track only (the
    `doAudio = recordAudio && hasRecordPermission()` path). With audio on, the same scene produced
    AAC 48 kHz stereo whose PCM measured mean −52.3 dB / peak −38.6 dB with 98.6 % non-zero samples —
    real room ambience, not a silent track. The recording level meter was visible throughout.
  - `logcat`: **zero** `FATAL EXCEPTION` and zero ANRs for `me.hletrd.telecampro` across the run.
- **Minified-build verification (2026-08-04) — R8 enabled in response to Play Console's "app is not
  optimized" recommended action.** `isMinifyEnabled = true`; every claim above that was explicitly
  scoped to an unminified binary is re-earned here on a minified one.
  - **Static, from `mapping.txt`:** 82 app enum classes carrying 316 constants, **0 constants
    renamed**, while all 82 enum CLASSES were themselves obfuscated (`CameraEngine -> gj`,
    `GlPipeline -> ib0`) and 1161 non-enum app fields were renamed. That two-sidedness is the point:
    the keep rule pins the names `Enum.name`/`enumValueOf` depend on WITHOUT disabling optimization.
  - **The keep-rule failure mode, tested directly on device:** set mode to VIDEO → `am force-stop`
    (a swipe-kill equivalent) → relaunch came back in VIDEO. A renamed constant would make
    `enumValueOf` throw, `SettingsStore`'s `runCatching` would swallow it, and the app would relaunch
    into defaults with no crash and nothing in logcat — silent corruption of every persisted setting.
  - **Capture on the minified binary:** a still wrote HEIF 2.77 MB + DNG 25.2 MB
    (`CameraEngine: CaptureFamily: settled … outputs=heic,dng`); an 8 s clip muxed to HEVC
    **2160×3840** @ 29.95 fps + AAC 48 kHz stereo, 2 streams, `ffprobe` duration 7.98 s — the
    portrait encoder buffer of the cycle-4 framing fix, not a landscape band.
  - `logcat`: zero `FATAL EXCEPTION` for the package across launch, capture and recording.
  - **Size:** DEX **46.67 MB → 2.48 MB**, APK **47.91 MB → 3.71 MB** (−92.3 %), measured against the
    installed non-minified v1.0 on the same device. The bulk is `material-icons-extended`, which
    ships its entire icon set unless R8 strips it — this app references a handful of icons.
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
- Short description: `Open-source manual camera for periscope telephoto + teleconverters.`
- Category: Photography
- Price: Free
- Ads: No
- In-app purchases: No
- Contact email: `01@0101010101.com`
- Privacy policy URL: `https://hletrd.github.io/telecam-pro/privacy-policy/`
- Source code URL: `https://github.com/hletrd/telecam-pro`
- Full listing copy: [`docs/play-store-listing.md`](play-store-listing.md)

The app uses other companies' product names (OPPO, Hasselblad, ZEISS, vivo, Sony, ARRI) only to
describe compatibility. It is not affiliated with, endorsed by, or sponsored by any of them; the
listing copy carries the full trademark attribution.

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
- Phone screenshots — **NOT SUBMISSION-READY**. The checked-in set was recaptured 2026-07-27
  (cycle 9), cropped to **1440x2880 (exactly 2:1)**
  24-bit PNG, no alpha. Play rejects anything taller than 2:1 and this panel is 1440x3168 (1:2.2).
  The crop box is **(0, 168) → (1440, 3048)**, derived by MEASUREMENT rather than guessed: on this
  panel the OS status-bar glyphs end at y=101 and the lowest app pixel (the shutter ring) sits at
  y=3041, so this window strips both system bars and loses no app content. Verified per file — only
  `02` touches the bottom edge, and that is the MR card of a SCROLLING sheet, which reads correctly
  as "more below".
  - **Frames 02 and 06 are blocking stale assets.** Frame 02 visibly says `Shooting` / `JPEG
    Quality` instead of current `Shoot` / `Still Quality`; frame 06 says `Transfer` / `Applied to
    the SDR stream` instead of current `Gamma` / `Applied to the camera’s already tone-mapped
    stream`. Do not upload the phone set until both are recaptured and the checked manifest is ready.
  - Historical provenance is mixed. The three viewfinder frames come from the operator's own
    handheld captures against real subjects (buildings, sky) because the listing wants
    photography-led art; the three menu frames come from the signed RELEASE build installed on the
    PMA110. That record has no immutable source-manifest digest and no longer proves current UI.
  - `docs/assets/play/screenshots/asset-validity.json` pins every existing PNG digest, names the two
    blockers, and binds expected copy to current string resources. Recapture 02 and 06 on PMA110 in
    portrait `en-US` from the exact immutable-debug APK printed by the wrapper; record its schema-2
    source-manifest digest and APK SHA-256 before changing `submission_ready` to true. Preserve the
    measured `(0, 168) → (1440, 3048)` crop. Do not fabricate or edit a host-side replacement.
  - Landscape captures were REJECTED for this set. The app counter-rotates glyphs by the
    gravity-derived device orientation, so a landscape-held capture renders the focal rail and mode
    carousel sideways. This is app behaviour, not a bug — capture PORTRAIT. All six historical
    frames render upright; uprightness does not make the stale menu copy current.
  - The 2026-07-10 and 2026-07-26 captures are retired: they showed a superseded UI, and every
    TELE-engaged frame among them shows the OLD focal rail (`0.6x 1x 3x 10x`) where the shipping
    build now reads `13x / 30x / 60x`.
  - `screenshots/01-main-viewfinder.png` — photo viewfinder: OSD row, focal rail, mode carousel,
    shutter
  - `screenshots/02-pro-settings.png` — **STALE / BLOCKING:** old Shooting/JPEG Quality copy
  - `screenshots/03-focus-tools.png` — Focus tab: AF modes, spot size, AF lock, peaking level/colour
  - `screenshots/04-video-controls.png` — video mode: REC control, live encoder OSD
  - `screenshots/05-lens-and-tele.png` — focal rail with the 300 mm TELECONVERTER engaged (the
    app's signature route: 3x pinned, TELE tag lit). The Fn quick-control sheet was tried first but
    would not survive a screencap — it dismisses before the frame lands, so this shot carries the
    lens story instead.
  - `screenshots/06-video-settings.png` — **STALE / BLOCKING:** old Transfer/SDR-stream copy
  - 01 and 04 now carry LIT, real subjects (the "photography-led art" option this note always
    offered). **05 is still a BLACK-scene frame and is the one weak spot in the set**: it is the
    only slot that requires TELE engaged, and every real-subject TELE capture on hand predates the
    focal-rail change, so it would advertise a rail the shipping build no longer draws. It is
    correct and current as-is — it shows `13x / 30x / 60x` and the loupe overview with its framing
    hint — but a handheld TELE capture against a distant subject would make the three viewfinder
    frames consistent. Replace it with a PORTRAIT capture, TELE lit, and re-crop with the box above.

### Tablet screenshots — **NOT SUBMISSION-READY**

The four tracked 1920×1200 landscape frames are historical TB331FC captures. Their asset commit
records the stored recapture, but it did not record the exact source commit, immutable debug
source-manifest digest, or APK SHA-256 that drew them. That is not immutable capture provenance, so
all four are blocked from Play upload even though their visible labels currently agree with the
resource authority:

- `screenshots/tablet/02-shooting.png`
- `screenshots/tablet/03-focus.png`
- `screenshots/tablet/04-lens.png`
- `screenshots/tablet/05-video-settings.png`

`screenshots/tablet/asset-validity.json` pins every tracked tablet PNG digest, the copy each future
recapture must show, the incomplete historical record, and the fail-closed readiness verdict. This
committed sheet and `python3 tools/check_docs.py` are the clean-clone authority; the optional private
listing document is not required to discover the block.

To replace the set, capture all four frames in `en-US` landscape on a sw600dp+ Android tablet from
the exact immutable-debug APK printed by `tools/build_immutable_debug.py`. Record its full source
commit, schema-2 source-manifest digest, and APK SHA-256 in the tablet manifest, update all four PNG
digests, and clear the blocking list only after the checker passes. Do not promote a partial recut or
infer source identity from the asset commit. Camera controls keep the same homes when the tablet
turns; the settings panel may dock as a side sheet, but there is no separate operator rail.

## Device Catalog

**The app is no longer single-device.** Hardware is resolved by enumerating Camera2 capabilities
rather than model names, and the teleconverter presets explicitly cover the Find X9 Ultra and X9 Pro,
vivo X200 Ultra and X300 Ultra, plus generic clip-ons — a catalog locked to two model codes would
contradict the app's own UI.

The floor is **`minSdk 33` (Android 13)**, so catalog installability is much broader than measured
behavior. Keep evidence classes separate; none of the historical rows below attests current HEAD or
the eventual recut, because there is no current upload candidate.

| Evidence class | Models | Recorded scope | Limit |
|---|---|---|---|
| Physical capture, artifact-linked | `PMA110`, `SM-S918N`, `TB331FC`, `TB336ZU` | Physical still/video evidence on signed 1.0.2 cuts | Those cuts are superseded and do not attest current HEAD |
| Physical capture, owner report | `21061110AG` | Owner-verified capture; preview/layout and API-33/MediaTek behavior separately measured | Remote harness could not actuate the shutter itself |
| Preview/layout-only | None currently | POCO initially occupied this class | Elevated by the 2026-08-12 owner capture; do not invent a replacement |
| Emulator | `sdk_gphone64_arm64`, API 33 | API floor, enumeration, session, save/encoder mechanics, layout | Synthetic camera; no optics, image-quality, or HAL evidence |
| Unvalidated equivalent | `CPH2841` | Same-hardware expectation only | Not measured and not capture-verified |
| Unvalidated catalog population | Other Android 13+ devices and named preset hosts | Installability/catalog coverage only | No device validation implied |

The rollout choice remains open catalog versus staged widening, but it must be made from this matrix
and must not turn historical device evidence into exact-current-artifact verification.

## Manual Console Sequence

1. Finalize and commit the source first. Run all release and required device gates against that exact
   cut; record only evidence actually observed. Build through `tools/build_immutable_release.py`,
   retain its `release-evidence.json`, and copy the receipt-recorded signed AAB (never mutable
   `app/build/outputs`) to a gitignored immutable name such as
   `releases/telecam-pro-1.0.2-<short-commit>-<aab-sha-prefix>.aab`.
2. Create `releases/release-attestation.json` with the exact fields below, write a sibling
   `<attestation>.sha256` sidecar over those JSON bytes, and run the checker. `status` may become
   `upload-ready` only after the evidence for that exact commit/artifact is complete.

   ```json
   {
     "schema_version": 2,
     "status": "upload-ready",
     "git_commit": "<full 40-character commit>",
     "version_code": 4,
     "version_name": "1.0.2",
     "aab_path": "releases/telecam-pro-1.0.2-<commit>-<digest>.aab",
     "aab_sha256": "<64 lowercase hex characters>",
     "signer_sha256": "9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584",
     "release_evidence_path": "app/build/immutable-release/<unique-child>/release-evidence.json"
   }
   ```

   ```bash
   shasum -a 256 releases/release-attestation.json > releases/release-attestation.json.sha256
   python3 tools/check_release_artifact.py releases/release-attestation.json
   ```

   The checker fails unless the tree is clean; HEAD and source/package versions match; the AAB name
   carries its commit and digest; the bytes match the schema-2 wrapper receipt's unique AAB output
   and `source_authority=sealed-wrapper-export-v1`;
   `jarsigner` succeeds; and the certificate is the recorded Play upload key. It rejects
   `app/build/outputs` and any missing/out-of-namespace wrapper receipt. After every verifier,
   digest, source-identity recheck, and private inspection-file cleanup has finished, one
   NUL-delimited Git porcelain-v2 status process supplies a final best-effort observation of HEAD plus
   tracked, untracked, and ignored protected-source drift. Git does not freeze the worktree during
   that scan, so it is not the release source authority and is not called an atomic snapshot. The
   signed AAB's packaged commit/tree joined to the exact sealed-wrapper receipt is authoritative;
   live status remains a fail-closed operator warning. Re-run the checker immediately before upload
   to minimize any subsequent operator-drift interval.
3. Upload the exact attested `aab_path`, never a fresh lookup of `app-release.aab`. Re-run the checker
   immediately before the console upload. Any source, artifact, attestation, version, or signer
   change invalidates the result and requires a new cut.
4. Enter the Store Listing and Data Safety answers from this repository. **The Data Safety "Photos
   and videos" ACCESS question must be re-answered** for the visual-media READ trio (see Data
   Safety below) — the last submission-ready answer set predates it.
5. **App content → Photo and Video Permissions: file the broad-access declaration.** Required
   because the manifest carries `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`; the per-permission
   justification text is prepared verbatim in `docs/play-data-safety.md` § "Photo and Video
   Permissions declaration", along with the answer to Play's "why not the photo picker" follow-up.
   Google's stated consequence for shipping the broad pair without an approved declaration is
   REMOVAL, not rejection, so this is not an optional step.

   **Status: DONE for the live listing** — confirmed by the owner 2026-08-06 (declaration filed,
   release approved). This step exists because that fact lived ONLY in the console: an adversarial
   review read the repo, found no checklist entry and no record, and graded the dimension a D on the
   reasonable inference that an unrecorded step was an unperformed one. Re-confirm on any release
   that changes the media permissions; leave alone otherwise.
6. Upload the icon and feature graphic. **Do not upload any checked-in phone or tablet
   screenshots:** phone frames 02 and 06 are stale, and all four tablet frames lack immutable
   capture provenance. This step remains blocked until both screenshot validity manifests say
   `submission_ready=true`, carry the required immutable recapture identities, and
   `python3 tools/check_docs.py` passes.
7. Set the device catalog per the Device Catalog section above (open vs staged is an owner call).
8. Review Play's automated checks and pre-launch report.
9. Promote the same artifact only after the internal-test install succeeds.

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
> 2. **Historical incident, now an active security block:** the July-25 upload-key password is weak
>    (six digits) and was transmitted in plaintext. The original note said nothing was on Play yet;
>    that is historical truth, not current truth — v1.0.1 is now published. The credential is
>    therefore unusable for the next upload. Do not rotate or reset it from this checklist: the
>    owner must explicitly approve either a strong-key rotation (only if Play has not registered the
>    certificate) or Google's upload-key reset workflow. Until that external action is confirmed,
>    `uploadKeyRotationApproved` must remain absent/false and the scoped helper refuses to build.
>
> Whenever the keystore is rotated, verify with
> `keytool -list -keystore telecampro-upload.jks -alias telecampro` — the certificate SHA-256 must
> match whatever this sheet records as the upload certificate, or the wrong keystore is in place.

After the separately approved rotation/reset is complete, add these **non-secret** fields to the
gitignored `keystore.properties`: `uploadKeyRotationApproved=true` and
`uploadKeyCertificateSha256=<the new 64-hex public certificate SHA-256>`. The helper verifies that
the configured keystore actually exports that certificate before it runs Gradle. Merely setting the
Boolean cannot bless the old key.

To rebuild the signed AAB locally after that prerequisite:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Non-secret preflight. This fails before a release attempt unless owner approval and the exact
# replacement certificate fingerprint are present.
python3 tools/run_scoped_signed_release.py --check-prerequisites

release_root="app/build/immutable-release/$(git rev-parse --short=12 HEAD)-$(python3 -c 'import secrets; print(secrets.token_hex(4))')"
# The decrypted payload travels only through this pipe. The short-lived helper validates the exact
# storePassword/keyPassword field set and the machine-checkable generated-secret floor (20+
# characters, at least three character classes, no surrounding whitespace/repetition/sequences;
# this rejects obvious weak shapes but does not claim to prove randomness), verifies the certificate with
# keytool's -storepass:env form (the VALUE never enters argv), runs the immutable wrapper, and clears
# its child environment on every success/failure terminal. It writes no transient secret file and
# cannot export anything into this caller shell.
gpg --batch --quiet --decrypt telecampro-upload-passwords.txt.gpg | \
  python3 tools/run_scoped_signed_release.py --output "$release_root" \
    :app:lintRelease :app:assembleRelease :app:bundleRelease
```

The command refuses to overwrite `release_root`, making output discovery unambiguous: release lint
reports are under `$release_root/logs/`, the signed APK under `$release_root/apk/release/`, and the
signed AAB under `$release_root/bundle/release/`. `$release_root/release-evidence.json` is written
only after the sealed export, copied signing inputs, and frozen allowlisted outputs pass their final
checks; it records the verified commit/tree and each exported output hash. A direct Gradle release
output remains developer-only even when its embedded clean-source identity is valid.

To inspect the real release binary **without** signing (manifest, permissions, alignment, dex scans
— everything except the signature), route `packageRelease` through the same immutable boundary:

```bash
inspection_root="app/build/immutable-release/$(git rev-parse --short=12 HEAD)-unsigned-$(python3 -c 'import secrets; print(secrets.token_hex(4))')"
python3 tools/build_immutable_release.py --output "$inspection_root" :app:packageRelease
find "$inspection_root/apk/release" -maxdepth 1 -type f -name '*-unsigned.apk' -print
```

The `find` result is the inspection APK for that unique immutable cut. Never upload it.
