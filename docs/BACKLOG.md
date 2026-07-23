# Release Status & Backlog — TeleCam Pro

Current release board. Read after `CLAUDE.md`; use `ARCHITECTURE.md` for implementation details.
Historical investigation notes are snapshots under `docs/reviews/` and `.context/reviews/`, not
active TODO lists. Last synced by [review-plan-fix Cycle 6](plans/2026-07-23-rpf-cycle6.md)
(2026-07-23); per-file history via `git log -- docs/BACKLOG.md`.

## Release State

Version 1.0 (`versionCode=1`) is a release candidate, **not currently ready for upload**. Source has
changed since the recorded 2026-07-10 artifact, so the path below and its old hash are historical
until a new signed bundle is generated and all current release evidence is refreshed:

`app/build/outputs/bundle/release/app-release.aab`

`docs/play-console-submit.md` marks the old SHA-256 **DO NOT UPLOAD**. Before Play submission, re-run
the complete debug/release gates, regenerate and validate/sign-check/align-check the AAB/APK, update
that sheet with the new hashes, and run the current PMA110 release-device matrix. The current-cycle
PMA110 and release-artifact evidence is **NOT RUN / pending**.

### Verified 2026-07-10

- Release build, bundle, lint, and the then-current JVM suite passed. The suite has since grown;
  re-run the release gate before upload and treat `app/src/test/` as the source of truth rather than
  copying a test/task count here.
- `bundletool 1.18.3 validate`, APK v2 signing, and 16 KiB zip alignment passed.
- Manifest has target/min SDK 36, no `INTERNET`, and no `DEBUGGABLE` flag.
- The installed PMA110 release APK matched the local verified APK byte-for-byte.
- Fresh state starts on 1x / 23 mm with TELE off.
- Remember Settings, Preserve Lens, and Preserve TELE default on; lens and TELE restore independently.
- Rapid double-shutter produces one serialized DNG+HEIF pair; both files are valid.
- 4K HLG records HEVC Main10 at 30000/1001 with AAC 48 kHz stereo.
- Open Gate records 2560x1920 4:3 HEVC Main10 at 30000/1001 with AAC.
- Core photo/video UI, menu hierarchy, settings persistence, and Play screenshots were reviewed on
  the physical PMA110. No crash or ANR was observed.

### Verified 2026-07-14 — seamless zoom + capture-pipeline session
1. **iPhone-style seamless zoom (photo)** — logical camera 0 (zoomRatio 0.6–20, physIds 3/2/4/5),
   pinch never reopens; lens picks = zoom presets; OSD shows the live effective focal.
   Device-verified: zero reopens across a 0.6→10 sweep.
2. **Video re-homed to standalone lenses** — the logical camera's EIS (Standard AND Active) leaks
   its uncorrected warp margin (~6% of width) into the preview AND the recorded file (verified by
   frame extraction; shows as a rainbow band at the bottom in portrait playback).
   `resolveNonTeleId` splits camera homes by mode; `setVideoMode` remaps unified↔lens-local zoom.
3. **Shutter could permanently wedge on the logical camera** — gralloc rejects its HAL-JPEG blob
   (the image never arrives) and a RAW target errors the camera device ~5 s post-shot. Fixed:
   YUV stills via `StillSnapshot`, RAW gated standalone-only (DNG = TELE mode), an 8 s capture
   watchdog, and instant shutter-blink feedback (the physical lag is pipeline-depth ×
   frame-duration: ~0.85 s at a 1/10 s dark-room preview, ~0.3–0.5 s in normal light).
4. **Zoom lag/jank, three layers** — full-request rebuild per tick (→ controller fast path on a
   cached repeating builder), per-input-event state updates at ~120 Hz (→ 16 ms/~60 Hz coalescing;
   compounding inputs MUST base on the coalesced pending value, not the stale UI state), and a
   ~33 MB full-res analysis glReadPixels every 5th frame (→ aspect-matched FBO, ≤256 px long edge).
5. **REC tally border vanished at the panel's rounded corners** — now follows the physical corner
   radius (WindowInsets RoundedCorner API, ×1.2 squircle compensation, user-tuned on device).
6. **UI pills** — Fn-row edge-fade scroll hint; one shared 12 dp left inset (OSD / meter / Fn row).

### Verified 2026-07-14 (late) — 0x80b4 TC session experiment (user-landed, agent-verified)
- The stock app's TC operation_mode **0x80b4 passes through to the HAL** as a plain
  SessionConfiguration sessionType on the standalone 3× camera: CamX logs
  `configure_streams() operation_mode: 0x80b4`, the session configures at fallback=0 WITH RAW,
  stills (HEIC+DNG) and 4K HEVC recording work, and TELE off returns cleanly to 0x0. No
  CameraUnit AUTH_CODE involved — this may be the 300 mm TC OIS profile unlock (stock pairs the
  mode with sensor mode 48). **Residual field check:** mount the converter and A/B the shake
  damping at 300 mm vs the previous SESSION_REGULAR build to confirm the OIS profile actually
  differs (result metadata shows ois=1/vstab=2 either way).

### Verified 2026-07-18 — review-plan-fix cycles 3-4 (device-verified)

- Long-exposure stills: 2/3.2/4 s captured with correct EXIF on the standalone TELE; 5/6.3 s
  reproducibly error the device (`CAMERA_ERROR(3)`) — the 4 s caps-seam ceiling
  (`HAL_SAFE_MAX_STILL_EXPOSURE_NS`) plus the 500 ms repeating clamp ship as the fix. NOTE: the
  bisect covers ONLY the standalone TELE; the ceiling is applied to every route as a conservative
  assumption (see Residual Field Checks for the logical-camera bisect).
- Cycle-3 P9.2 session: zoom fast path + slider both directions, TC on/off, tap-AF + ISO-drag
  focus hold (bit-exact lens distance), photo↔video flips clean.
- Cycle 4: the recorded-video FRAMING defect was device-confirmed and fixed — the encoder buffer
  was stream-shaped landscape while GL content is portrait, so every clip carried a ~3.16× center
  band of the viewfinder field (luminance-gradient A/B; cell-map corr 0.29 pre-fix). Post-fix the
  encoder takes the displayed-aspect swapped buffer (2160×3840 for 4K UHD): preview↔file cell-map
  correlation 0.992, span ratio ~0.87. Older "Verified" video entries in this file predate this
  finding — their container/codec facts stand, their implied framing does not.

## Before Production

These are manual Play Console operations, not repository implementation work:

1. Create the app and upload the signed AAB to Internal testing.
2. Enter the listing, privacy policy, App content, and Data Safety answers from `docs/play-*.md`.
3. Upload the icon, feature graphic, and six 1440x2560 phone screenshots.
4. Restrict the device catalog to OPPO Find X9 Ultra codes CPH2841 and PMA110.
5. Install from Internal testing and review Play's automated checks and pre-launch report.
6. Promote the same tested artifact to production.

Use `docs/play-console-submit.md` as the operator checklist. The account was created in 2015, so the
new-personal-account closed-test rule does not apply; an internal test remains the release gate.

**Owner actions outside this repo (recorded by cycle 6, 2026-07-23):** the GitHub repository About
tagline currently claims "Raw / Log video" (cycle-6 security F-2) — RAW is stills-only (DNG) and
the log profiles are display-referred SDR bakes, so reword the tagline to match the listing's
honest copy. D-2 below also still stands: confirm the Play/privacy contact mailbox is real and
monitored and that the GitHub Pages privacy-policy URL is live before submission.

## Residual Field Checks

These do not require a code or metadata change unless the result exposes a defect:

- **PENDING-USER (one-time device setting): clear the ColorOS installer gate for the instrumented
  test APK.** The connected instrumented-coverage leg (docs/TESTING.md "PMA110 device caveat") is
  blocked because `me.hletrd.telecampro.debug.test` has no launcher activity and OPlus's installer
  offers only "Exit installation" — not automatable over wireless adb. On-device: approve the
  installer dialog when the install is retried (or enroll "Install via USB" / the HeyTap-account
  developer option). Once cleared, the committed smoke suite + merged report run immediately:
  `./gradlew :app:createDebugAndroidTestCoverageReport -PandroidTestCoverage=true`, then
  `createCoverageReport` for the merged HTML. Build infra and the 4-test smoke suite are already
  committed and host-green; only this approval is missing.

- Record the same real scene with Sound Focus/Stage on and off, then compare off-axis rejection.
  Camera/HAL parameter acceptance is verified; the acoustic effect needs ears and a suitable scene.
- Capture a clearly upright subject while deliberately holding the phone in portrait and both
  landscape directions. Confirm HEIF display orientation and DNG EXIF orientation in saved files.
  Rotation math and flat-phone orientation retention are unit-tested; this closes the final visual
  output check. Also confirm a held-landscape VIDEO clip in an external gallery (the muxer
  orientation-hint SIGN is unverified — `RotationMath.videoOrientationHint` pins the current
  mapping and is the one place to flip if wrong).
- Added by review-plan-fix cycles 2-4 (2026-07-17/18 runs):
  P-mode brightness-target judgment in a lit room (QA-3); EIS warp-band re-confirmation per the
  established lit-scene frame-extraction method (QA-4); a long-exposure bisect of the LOGICAL
  camera's still ceiling (the shipped 4 s clamp is tele-verified-only and conservatively global);
  level-overlay responsiveness feel after the roll-alpha retune (0.93); the mid-REC dead-mic
  meter zeroing (hard to trigger on demand — code-inspected); held portrait/landscape playback of
  the new PORTRAIT-buffer video files in external galleries (orientation-hint sign, U1).
- Added by review-plan-fix cycle 6 (2026-07-23; the phone was desk-bound via a remote proxy this
  cycle, so every held-device item below is NEEDS-HUMAN):
  - **RELEASE-GATING — capture-rotation device-orientation term sign A/B, BOTH facings.** Cycle-6
    debugger F1: a pure-math derivation from the device-confirmed GyroEis CCW-positive convention
    (`atan2(x, y)` → CCW/left landscape yields dev=90; independently anchored by the 2026-07-08
    device-confirmed `+dev` glyph counter-rotation) concludes the standard Camera2 formulas in
    GyroEis terms are BACK = sensor − dev and FRONT = sensor + dev —
    `RotationMath.captureRotationDegrees` has the device term's sign exactly swapped for BOTH
    facings. If correct, every LANDSCAPE-held still saves 180° rotated (portrait and upside-down
    portrait are unaffected, which is why no field check has ever caught it), and
    `RotationMath.videoOrientationHint` shares the question (likely needs `(360 − dev) % 360`, and
    it has no front-facing branch — code-reviewer F1). Per the repo's device-verification rule the
    sign flip did NOT land this cycle. Exact check: in a lit scene, DELIBERATELY hold the phone in
    both landscape directions (rear, then front), capture stills (HEIF + DNG where eligible) and a
    short clip each, and inspect in an EXTERNAL gallery. If landscape stills display 180° rotated,
    flip the device-orientation term in `captureRotationDegrees` (one seam + its test matrix) and
    re-run the comparison.
  - **Front VIDEO file mirror truth.** Front STILL mirror truth WAS device-verified 2026-07-23
    (cycle-6 QA: a pulled front JPEG showed legible, unreversed "LG/WHISEN" text after a viewing
    180° — the saved file keeps the true scene). The encoder un-mirror path is shared with video,
    but no front CLIP has been pulled and checked: record a front video of legible text and
    confirm it reads unreversed in an external player.
  - **Front tap-AF aim — NOT moot.** Cycle-6 probe: the front camera (id "1") ADVERTISES
    `android.control.maxRegions = [AE=1, AWB=0, AF=1]`, so tap-AF/AE regions are live on the front
    route. Debugger F2's suspected sensor-half mirror error stands: the display half of the tap
    mapping is correct, but `viewTapToSensorPoint` undoes only rotation, never the mirror, so the
    metering region may land at the horizontally OPPOSITE active-array point. Check: tap a subject
    near the LEFT edge of the selfie preview against a depth-separated background and confirm
    focus/exposure drives from the tapped subject, not its horizontal mirror. If wrong, the sensor
    half needs the `1−nx` un-flip (seam: `gl/FrontMirrorConvention` + `mapTapFocusGeometry`).
  - **Log-profile on-device check PARTIALLY CLOSED.** An S-Log3.Cine 4K clip was ffprobe-verified
    2026-07-23 (HEVC Main10, `color_transfer=bt2020-10` — confirmed NOT PQ/ST2084, the exact
    mistag the explicit-transfer container policy exists to prevent). Still open: playback
    appearance of the HLG/log output on a real HDR/reference display.
  - **Hi-res dormancy device checks (dormant on PMA110; live code).** Before releasing on any
    device that actually advertises a hi-res size: (1) audit the passthrough-JPEG EXIF lane — HAL
    bytes go to disk verbatim, so verify no unexpected maker-note/GPS payload survives that the
    processed lane's re-stamp would have dropped (cycle-6 security F-5); (2) time the still
    watchdog against a real full-sensor capture — remosaic delivery may exceed the current
    exposure-derived margins (cycle-6 tracer T5).
- Added by the 2026-07-10 review cycle (code changed since the recorded release AAB — re-run the
  full release gate and refresh `docs/play-console-submit.md`'s hash before upload):
  one HEIF capture on heifwriter 1.1.0 (stable, was 1.2.0-alpha01); waveform overlay visual parity
  after the drawPoints batching; the new OSD AE/AWB/AF lock tags + TeleChip hit-area layout on the
  3168 px screen; a TalkBack pass over the new dial/slider semantics.

## Deferred Beyond v1

- **R8/minify:** keep disabled until enum persistence and reflection-sensitive OEM SDK paths have
  explicit keep rules and another physical-device release pass.
- **Dolby Vision:** hardware support exists, but a correct Dolby Vision MP4 path is not implemented.
- **End-to-end 10-bit Camera2 input:** the stable shipping Camera2/EGL source is SDR/8-bit; the prior
  HLG10 + JPEG + RAW combination crashed the HAL.
- **Authenticated CameraUnit path:** requires OPPO developer registration, an issued `AUTH_CODE`, and
  a product decision on a separate video session that lacks the current RAW/manual Camera2 surface.
- **Optional product work:** configurable keep-screen-on, geotagging, custom save locations, slow-
  motion playback metadata, and advanced focus/bracketing workflows.
- **True wide-field TELE finder (design item, owner decision needed):** the shipped `Loupe Overview`
  assist (default OFF) honestly re-draws the FULL current camera frame — the single
  Camera2 stream already carries the HAL's `CONTROL_ZOOM_RATIO` crop, so no GL work can show a
  genuinely wider field. A real iPhone-style wide finder needs either (a) a second concurrent
  wide stream (high risk on this HAL — physical-sub-camera routing and several multi-stream
  combos crash it; must be device-verified per session plan), or (b) capping the HAL zoom and
  rendering the remaining main-view magnification in GL (touches AE metering, tap-AF region
  mapping, video encoder framing, and preview sharpness — a zoom-architecture change, not a
  finder change). 2026-07-21 device probes narrowed the options: public concurrent sets contain only
  `[0,1]`, so rear pairs `2+0`, `2+4`, and `2+5` cannot use separate CameraDevice sessions. A safe
  API-35 `CameraDeviceSetup` query returned `true` for logical-0 physical PRIVATE pairs `2+4` and
  `2+5` at both 640x480+640x480 and 640x480+1920x1440, but that metadata-only answer does not erase
  the existing QTI `configureStreams` SIGSEGV/Broken-pipe evidence for physical routing. The next
  step is an explicitly approved, isolated preview-only HAL experiment on `127.0.0.1:5602`; do not
  enable or present the same-stream overview as the requested 1x finder.

## Dispositions from review-plan-fix cycle 1 (2026-07-17 run; durable record)

Full records in `docs/plans/2026-07-17-rpf-cycle1.md` § Deferrals:

- **AGG-20 (Low/Medium)** — RETIRED BY IMPLEMENTATION (cycle 2, 2026-07-17): the sensor-keys-only
  fast path on the cached builder landed (`sensorOnlyControlsDelta`/`applySensorValueControls`,
  ≥200 ms pacing with trailing exact landing), covering the app-side AE pair and manual
  focus/ISO/shutter drags. Residual: on-device confirmation that dial-drag preview cadence
  improved rides the normal per-cycle device verification.

## Verification Quick Reference

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n me.hletrd.telecampro/com.hletrd.findx9tele.MainActivity
```

The wireless-debugging port changes by session. On this multi-homed Mac, direct ADB can report
`No route to host`; proxy the current phone port to a temporary loopback port, connect to
`127.0.0.1:<proxy-port>`, and stop the proxy when verification ends. Do not reuse a recorded old port.

Runtime CAMERA and RECORD_AUDIO permissions are granted through the app UI on ColorOS. The `camtest`
AVD is suitable only for UI/crash checks; telephoto routing, RAW, color, audio, stabilization, and
saved-file behavior require PMA110.

## Deferral dispositions from the 2026-07-17 cycle-2 run (updated 2026-07-18)

- **AGG2-36 / AGG3-17** CameraEngine god-object extraction: cycle 4 landed step 1
  (`StillCapturePipeline`); the current cycle landed steps 2-3 (`RendererAssists` and
  `StandbyAudioController`). The remaining ordered slices stay recorded in
  `docs/plans/2026-07-18-rpf-cycle4.md` §Deferrals.
- **AGG2-38** start↔stop same-tick REC race test: CLOSED by cycle 4 (`RecStartStopRaceTest` over
  the real `RecordingAdmissionLatch` + the extracted `shouldPublishRecording` save gate).

## Deferred from review-plan-fix cycle 2 (2026-07-10 run; durable record)

That 2026-07-10 loop ended with its cycle 2 (later runs — 2026-07-16 cycles 1-9 and the 2026-07-17
loop — have their own dated plans under `docs/plans/`), so the 2026-07-10 deferrals live HERE, not
"in the next cycle." Full citations, severities, and exit criteria:
`docs/plans/2026-07-10-rpf-cycle2.md` § Deferred (D-1..D-18). Summary of what remains open:

- **Device re-verification of the cycle-2 changes** — the PMA110 re-locked behind a secure keyguard
  mid-cycle. VERIFIED on device post-unlock by the orchestrator (2026-07-10): letterboxed preview
  geometry photo 4:3 / video 9:16, photo↔video mode-switch reopens, tap-AF reticle (plus the
  3ba28c8 AspectMask axis follow-up). STILL QUEUED: review-overlay hardware-key gating, resume/
  lens-tap serialization under load, record start/stop under the ordered encoder teardown,
  MediaReview tap-to-pause, flat-resume capture orientation.
- **D-1** muxer orientation-hint SIGN (already in Residual Field Checks above).
- **D-2** confirm the Play/privacy contact mailbox is real and monitored (owner decision;
  `play-store-listing.md` / `play-console-submit.md` / `PRIVACY.md` / `privacy-policy/index.html`).
- **D-3** (restated by cycle 6, 2026-07-23) O-Log2 is DORMANT-DE-LOG-ONLY: the user-facing option
  was removed 2026-07-22 and its forward OETF left `Shaders.kt` with it — only the dormant Gamma
  Display Assist inverse (uTransfer=3) remains, reserved for a future CameraUnit-authenticated
  scene-referred stream. The inverse-threshold discrepancy therefore concerns dormant code only,
  and its numeric test coverage is now string-only (the numeric pin left with the forward curve —
  cycle-6 test-review F-A2). Decide with OPPO's white paper in hand before reviving it; nothing
  shipping depends on it.
- **D-4** structural refactors (state triplication/applyExtras, CameraEngine split, AE-loop move,
  CameraSession seam, caps projection out of CameraUiState, telemetry-flow split, typed engine
  events) — MUST land before the CameraUnit / "Authenticated CameraUnit path" v2 work. Cycle-6
  sizes at review baseline: CameraEngine.kt 4889 lines (+221 that cycle; ~5000+ after the cycle-6
  fixes), CameraScreen.kt 2526 (+126), CameraViewModel.kt 2318 (+98), CameraState.kt 931 (+80).
  FACING is now the FOURTH hand-copied optics axis (mode/lens/TC/facing): every door hand-repeats
  the same invariant checklist and the ViewModel hand-mirrors every engine transaction (cycle-6
  architect F1/F2) — the gap between this trend and the "must land before v2" order is widening.
- **D-5..D-9** implementations are landed: downsampled analysis readback, REC admission off main,
  single-decode HEIF+JPEG, GL frame coalescing / cached request builder / waveform draw reuse, and
  DNG publication on `ioExecutor` after the required synchronous live-RAW write. Their remaining
  performance/feel evidence is part of the pending current PMA110 matrix.
- **D-10** MR-bank management placement — CLOSED 2026-07-21: save/recall lives in Shooting/My Menu,
  the viewfinder strip is removed, and only an active slot appears in the compact OSD. **D-11** AE metering under LOG preview,
  **D-12** dead backup_rules removal (needs explicit owner sign-off — file deletion),
  **D-13** pure-env release signing gate (when CI exists), **D-14** keep-screen-on toggle (already
  above), **D-15** FnSlot table test on next enum growth, **D-16** OPPO Maven cred relocation (with
  the CameraUnit AUTH_CODE work), **D-17** GyroEis roll wrap transient — CLOSED by cycle 3 (aa6cab9, wrap-aware smoothedRoll + GyroEisMathTest), **D-18** the
  lit-scene/human-ears residual field checks (above).

## Historical References

- `docs/reviews/README.md` — tracked review-archive index and usage rules.
- `docs/reviews/2026-07-09-backlog-handoff.md` — pre-release implementation handoff and device history.
- `docs/reviews/2026-07-03-comprehensive-review.md` — comprehensive review snapshot.
- `docs/plans/` — completed review/fix plans.
- `.context/reviews/` — optional local raw specialist review snapshots (gitignored).
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — original design intent.
