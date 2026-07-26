# Release Status & Backlog — TeleCam Pro

Current release board. Read after `CLAUDE.md`; use `ARCHITECTURE.md` for implementation details.
Historical investigation notes are snapshots under `docs/reviews/` and `.context/reviews/`, not
active TODO lists. Last synced 2026-07-25 (cycle 8, plus the namespace/SDK-removal and rotation doc passes);
per-file history via `git log -- docs/BACKLOG.md`.

## Release State

Version 1.0 (`versionCode=1`). **A signed, validated v1 artifact EXISTS and its release-device
matrix PASSED** — but it is frozen on a source lineage `main` has since left, so upload is an owner
DECISION, not a blocked gate.

- **The artifact:** built 2026-07-25 from the dedicated release worktree `.claude/worktrees/release-v1`
  at `9541697`. `bundletool validate`, `jarsigner -verify`, APK v2 signing, and 16 KiB alignment all
  passed, and the PMA110 release matrix passed the same day (photo / TELE DNG / video / persistence /
  permission flow, zero app crashes or ANRs). Exact hashes, the upload certificate, and matrix detail
  live in `docs/play-console-submit.md` — that sheet is the single home for artifact identity; do not
  copy hashes here.
- **Why it is not simply "upload it":** `main` has moved past `9541697` — most consequentially the
  Kotlin namespace move (`6ae3979`, `com.hletrd.findx9tele` → `me.hletrd.findx9tele`), which changes
  every class name and the launcher activity component in a release build. `applicationId` is
  unchanged (`me.hletrd.telecampro`), so Play identity is unaffected. The `com.oplus.ocs` removal
  (`2b4bc55`) was `debugImplementation`-only and does NOT change release bytes. Cycle-8
  responsiveness, the pseudo-ZSL ring, and the focus-confidence detector are all post-`9541697` and
  deliberately excluded from v1.
- **The one hard blocker either way:** all six Play phone screenshots are STALE / DO-NOT-UPLOAD. The
  console sequence cannot complete until they are recaptured from whichever exact candidate ships.
- **Owner decision:** (a) ship the frozen `9541697` artifact as v1 — nothing more to build, recapture
  screenshots and go; or (b) re-cut v1 from `main`, which requires a new signed AAB, a fresh hash
  block in `docs/play-console-submit.md`, and a re-run PMA110 matrix, because the namespace move
  invalidates the "same source lineage" evidence basis.

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

### Landed 2026-07-25 — cycle 8 responsiveness (device-verified except the focus tag)

Plan: [`plans/2026-07-25-rpf-cycle8.md`](plans/2026-07-25-rpf-cycle8.md); live status in
`.context/cycle8/status.md`. All slices gate-green (incl. coverage A ≥ 99.5%). Device evidence was
received 2026-07-25 for slices 1, 2 and 4; slice 3's own check ran and is recorded in place below:

1. **Preview brightness simulation** — AE-OFF previews ride a 1/15 s fluidity cap
   (`PREVIEW_FLUIDITY_MAX_EXPOSURE_NS`); the residual shortfall past ISO headroom renders as
   bounded (≤×16) linear GL gain (`uDigitalGain`); zebra/false-color/scopes/AE meter the
   simulated still exposure; files and stills untouched; the 500 ms HAL-safety clamp stays outer.
2. **AE step schedule** — `maxStepStops` = 0.5×|err| in [0.30, 1.20]; big scene changes converge
   ~2× faster; steady-state smoothness unchanged.
3. **Focus-confidence tag** — one compact amber OSD tag (700 ms hold), two proofs. `AF_LIMIT`
   (AF failed/hunting near `LENS_INFO_MINIMUM_FOCUS_DISTANCE`) shows `TOO CLOSE` with an optional
   closer-lens suffix. `FRAME_DETAIL` (`gl/FocusDetail.kt`, a pure curvature-ratio metric riding
   the existing analysis readback) shows `SOFT` and no suffix — it proves the frame resolves no
   fine detail, not that the subject is too close. Added because the TELE HAL false-locks
   `FOCUSED` at infinity on a ~9 cm subject, which makes `AF_LIMIT` unreachable on that route
   (see CLAUDE.md). **DEVICE-CHECKED 2026-07-25 — the detector MISSES the case it was built for.**
   Measured from its own tile votes (a `FocusConfidence` DEBUG trace added for this check):
   - sharp reference (front camera, lit room at normal distance): **5-6 soft of 75-76 judgeable**
     (7-8% soft), `bestRatio` 0.89-1.05 → RESOLVED, correct.
   - defocused, subject ~3 cm on TELE (min focus 120 cm): **53 soft of 72** (74%), `bestRatio` 0.30
     → **RESOLVED, a MISS.** Same subject at 1x (min focus 15 cm): 45 of 73 (62%), `bestRatio` 0.19,
     also a MISS.
   The binding constraint is the FRAME rule, not the metric: `FOCUS_SHARP_TOLERANCE = 0.02` requires
   ≈1 sharp tile out of ~74, and genuinely defocused frames still show 19-28 (sensor noise raises
   the fine-lag term — by design, see `FOCUS_SOFT_RATIO`; noiseless synthetic frames cannot expose
   this, so no host test could have caught it). The metric ITSELF separates cleanly, and `bestRatio`
   separates best (0.19-0.30 defocused vs 0.89-1.05 sharp).
   **Left unchanged deliberately.** The contract is "may miss, must never false-fire", and a miss
   does not violate it. Relaxing the tolerance to 0.50 was tried and REVERTED: it broke the existing
   "a small sharp subject inside a defocused field keeps the frame resolved" test — i.e. it would
   false-fire on shallow-depth-of-field shots, which for a 300 mm telephoto app is the *normal*
   photograph. Three scenes are not enough evidence to move a never-false-fire threshold.
   **To close this properly** (deferred): drive the frame verdict off `bestRatio` (nothing anywhere
   in the frame resolves) instead of a sharp-tile count, and calibrate it on a device set that
   MUST include bokeh — a sharp subject on a blurred background — plus flat, dark, grainy, and
   motion-blurred scenes. Until then the FRAME_DETAIL path is effectively dormant on this device
   and `AF_LIMIT` remains the only live proof (itself unreachable on TELE).
4. **Pseudo-ZSL — S4a measured, S4b LANDED.** S4a soak and S4b serve numbers are recorded once, in
   CLAUDE.md's pseudo-ZSL bullet; the ring shipped as `4ec42c0` (pure admission seam) + `7ffb406`
   (3-deep ring, inline serve, exact-timestamp adoption), and the debug toggle is now fps-logging
   only. **The dark-shot refusal is the accepted design, not an open defect.** A session-ladder rung
   later made the deep reader degradable (`SessionAttemptPlan.useDeepZslReader`) so a gralloc
   rejection of the ~5×19 MB allocation costs zero-lag serving instead of all stills. The ZSL probe
   also found YUV/PRIVATE reprocessing ADVERTISED on cams 0–5 (evidence:
   `.context/cycle8/zsl-probe-2026-07-25.md`) — see the true-ZSL deferral below.

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

- **RESOLVED 2026-07-24 (disposition kept for the durable record): the ColorOS installer gate on
  the instrumented test APK is bypassed by a plain streamed `adb install -r -t` of
  `app-debug-androidTest.apk`** (the install-session path UTP uses gets `-99` from the OPlus
  gate; the streamed path goes through). The test package is deliberately LEFT INSTALLED on the
  PMA110 — uninstalling re-arms the gate. Full workaround + the HOME-key lifecycle caveat live in
  docs/TESTING.md ("PMA110 device caveat"); merged coverage is reproducible via
  `tools/coverage/union_report.py`. Connected leg verified: 4/4 instrumented tests pass, merged
  overall 48.64% (exact union, basis drift 0).

- Record the same real scene with Sound Focus/Stage on and off, then compare off-axis rejection.
  Camera/HAL parameter acceptance is verified; the acoustic effect needs ears and a suitable scene.
- **RESOLVED 2026-07-25 — the held-orientation STILL matrix is device-verified (and it caught the
  cycle-6 MAJOR for real):** the pre-fix `+dev` matrix saved a landscape-held rear still 180°
  rotated (laptop shot, keyboard-up); after a209830 (BACK −dev / FRONT +dev, dev being GyroEis
  CCW-positive) the full matrix passed on device: rear portrait ✓, rear landscape LEFT-90 ✓ and
  RIGHT-90 ✓ (room shots, both upright with landscape dims), front portrait ✓, front landscape ✓
  (one direction; the other follows algebraically). REMAINING residual: a held-landscape VIDEO
  clip in an external gallery — `RotationMath.videoOrientationHint` now carries the same
  device-confirmed −dev/+dev term, but the container-hint playback check itself has not been run.
- Added by review-plan-fix cycles 2-4 (2026-07-17/18 runs):
  P-mode brightness-target judgment in a lit room (QA-3); EIS warp-band re-confirmation per the
  established lit-scene frame-extraction method (QA-4); a long-exposure bisect of the LOGICAL
  camera's still ceiling (the shipped 4 s clamp is tele-verified-only and conservatively global);
  level-overlay responsiveness feel after the roll-alpha retune (0.93); the mid-REC dead-mic
  meter zeroing (hard to trigger on demand — code-inspected); held portrait/landscape playback of
  the new PORTRAIT-buffer video files in external galleries (orientation-hint sign, U1).
- Added by review-plan-fix cycle 6 (2026-07-23; the phone was desk-bound via a remote proxy this
  cycle, so every held-device item below is NEEDS-HUMAN):
  - **RESOLVED 2026-07-25 — capture-rotation device-orientation term sign, BOTH facings.** Cycle-6
    debugger F1 was CORRECT: the signs were swapped. `a209830` landed BACK = sensor − dev and
    FRONT = sensor + dev (dev being the GyroEis CCW-positive gravity read), and the held matrix
    passed on device — see the RESOLVED entry above for the evidence.
    `RotationMath.videoOrientationHint` carries the same term; only its EXTERNAL-PLAYER playback
    check remains open (below).
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

- **R8/minify:** keep disabled until enum persistence has explicit keep rules and another
  physical-device release pass. (The "reflection-sensitive OEM SDK paths" half of this blocker
  retired with the `com.oplus.ocs` removal on 2026-07-25 — no build variant links an OEM SDK now,
  and `app/proguard-rules.pro` carries no vendor keep rules, only the staged, still-commented
  SettingsStore enum rule.)
- **Dolby Vision:** device-probed end to end on 2026-07-26 and the technical path WORKS — the
  blockers are legal and honesty, not engineering. `DolbyVisionProbeTest` (androidTest; logs under
  `DVProbe`, never fails the build) showed `c2.qti.dv.encoder` is HW and visible to our own
  third-party package, configured it, encoded 12 P010 frames, and MediaMuxer accepted the track and
  closed a file `ffprobe` reads as a genuine DV stream: `dvvC` box, `dby1` brand, `dv_profile=8`,
  `dv_level=11`, `rpu_present_flag=1`, `bl_present_flag=1`. Do NOT reason from the APV exclusion —
  AOSP's `MPEG4Writer` special-cases `video/dolby-vision` rather than rejecting it. The emitted
  transfer is `arib-std-b67` (HLG) even when PQ is requested, i.e. **Profile 8.4**, whose base layer
  is the same HLG signal our GL pipeline already builds, with the encoder generating the RPU. So the
  remaining engineering is small (a 10-bit encoder EGL config plus the DV MIME/profile). What must
  be resolved first, in order:
  1. **Source honesty.** The stream is still the ISP's display-referred 8-bit SDR output (see the
     entry below). A DV-branded file would assert more than the pipeline can back — the same rule
     that already forbids marketing our HLG as end-to-end 10-bit.
  2. **Trademark.** Using the device's licensed encoder through public Camera2/MediaCodec is one
     question; putting "Dolby Vision" in the UI or the Play listing is a separate permission
     question that public Dolby documentation does not answer (their Implementation Handbook covers
     chip and device makers only). Requires contacting Dolby before any user-visible naming.
- **End-to-end 10-bit Camera2 input:** the stable shipping Camera2/EGL source is SDR/8-bit; the prior
  HLG10 + JPEG + RAW combination crashed the HAL.
- **Authenticated CameraUnit path:** still a legitimate deferred option, but the groundwork was
  REMOVED 2026-07-25 (commits `c27744c` probe + `2b4bc55` build graph) — restore from those rather
  than re-deriving coordinates or credentials. Gone: the `com.oplus.ocs` dependency (camera 1.1.0 +
  base 1.0.16 and its base-auth/base-internal transitives), the OPPO OpenCapability maven repo and
  its credentials, the four `gradle/verification-metadata.xml` components, and the debug-only
  `OcsProbe` availability check. **Why:** without an AUTH_CODE the probe could only ever return
  `errorCode=1004` (AUTHCODE_EXPECTED) — a constant of the missing registration, not of the device,
  so it could never be what tells us the answer changed — while costing ~32 ms of debug cold start
  (isolated A/B, proc-start → `configure_streams` END, median of 4 runs each: 527 ms with the SDK,
  495 ms without; the release-vs-debug 354/535 ms gap is debug-BUILD overhead and must not be
  quoted as the SDK's cost) and, decisively, 200+ log rows that blew ColorOS's 300-row per-process
  quota and ate our own `StartupTrace` instrumentation. Re-enable order, which
  is the ONLY sanctioned path:
  1. an AUTH_CODE actually ISSUED (not merely applied for) for applicationId `me.hletrd.telecampro`
     plus the signing cert, via OPPO developer registration;
  2. that code as a `com.coloros.ocs.camera.AUTH_CODE` manifest meta-data (the placeholder comment
     in `app/src/main/AndroidManifest.xml` marks the spot);
  3. restore the maven repo block, the version-catalog entries, the `debugImplementation` lines and
     the four `com.oplus.ocs` verification-metadata components from the commits above;
  4. re-add behind a build flag so it is NOT on by default even in debug, and re-measure cold start
     and startup log volume before merging;
  5. a product decision on a separate CameraUnit video session that lacks the current RAW/manual
     Camera2 surface.
  Orthogonal to the shipped TC path: vendor session type 0x80b4 and the public `com.oplus.*` Camera2
  request keys are device-verified, need no AUTH_CODE and no SDK, and were untouched by the removal
  (see "Verified 2026-07-14 (late)" above).
- **Optional product work:** configurable keep-screen-on, geotagging, custom save locations, slow-
  motion playback metadata, and advanced focus/bracketing workflows.
- **TELE pseudo-ZSL (cycle-8 deferral):** the ring design targets the LOGICAL photo route only;
  the standalone TELE keeps its proven HAL-JPEG capture. Extending ZSL there needs a full-res YUV
  repeating target INSIDE the vendor 0x80b4 session — a session-shape change on the proven TELE
  path. The S4a streaming evidence now exists (cycle 8 above) and the LOGICAL ring has shipped; what still
  gates TELE is the session-shape change alone — a full-res YUV repeating target INSIDE the vendor
  0x80b4 session. Reassess only with an explicit device session plan.
- **True ZSL via reprocessable session (cycle-8 deferral):** YUV_REPROCESSING/PRIVATE_REPROCESSING
  are ADVERTISED on cameras 0–5 (`maxNumInputStreams=1`, YUV/PRIVATE 4096×3072 INPUT → JPEG/YUV;
  probe: `.context/cycle8/zsl-probe-2026-07-25.md`), but an input stream is a new session shape on
  a HAL with a record of fatally over-advertising, and the reprocess JPEG output on camera 0 needs
  the exact blob reader gralloc already rejects there. Debug-gated device spike only, behind an
  explicit device window.
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

## Deferrals from the 2026-07-26 functional + UI review triage (durable record)

Everything else from that review was fixed in the same run; these are the items that need device
evidence or a design decision, with enough detail to act on without re-deriving them.

- **P3 — the always-on full-res YUV ZSL target's memory/thermal cost (needs a device soak).**
  `CameraController` raises the LOGICAL route's processed reader from `maxImages = 2` to
  `ZSL_RING_DEPTH + 2` at `caps.largestYuvSize` (4080×3064 ≈ 18.7 MB/buffer): ~94 MB of graphics
  memory instead of ~37 MB, held for the whole photo session, and the reader is on the REPEATING
  request, so the HAL writes ~18.7 MB × ~30 fps ≈ 560 MB/s whenever the photo viewfinder is up —
  whether or not the user ever shoots. These are the app's largest allocations. The 10-min soak
  already run measured +0.3 °C, but covered NEITHER a low-memory device state NOR a long idle
  viewfinder. Check: (1) a 30-min idle photo viewfinder with `dumpsys meminfo` + thermal sampling;
  (2) the same under memory pressure (several heavy apps resident) watching for a gralloc failure or
  a session reconfigure. Mitigations if it bites, in order: `ZSL_RING_DEPTH = 2` (still ~66 ms of
  history at 30 fps, far above the measured 0 ms serve latency), and/or detach the ZSL target while
  ProSheet or review is open.
- **P4 — a tap-to-focus fires two extra full-res YUV frames into the ring (needs measurement).**
  The AF CANCEL and START one-shots are submitted from the SAME cached builder that carries the ZSL
  target, so every tap costs two extra ~18.7 MB frames through the reader plus two extra
  `zslRingAdd`/close cycles on the camera thread. Correctness is unaffected (legitimate frames,
  legitimate results). Deferred rather than fixed because building the two triggers from a
  ZSL-less copy touches the device-verified tap-AF submit path for an unmeasured win. Check: trace
  camera-thread time and `Image` churn across a burst of taps; fix only if it shows.
- **UI13 — the dormant `landscapeOperator` two-pane layout.** ~15 lines of unreachable Compose
  emission plus two dead spacing ternaries in the app's hottest file, gated by a hardcoded `false`.
  KEPT deliberately: the comment records that it is dormant pending the orientation pipeline (GL
  sampling, capture masks, tap mapping and encoder framing all share a portrait-window contract),
  and deleting it would discard that design. Revisit together with that pipeline, not before.
- **UI16 — no 700-weight Inter is bundled, so `FontWeight.Bold` renders as SemiBold.**
  `app/src/main/res/font/` has Regular/Medium/SemiBold only, while ~22 call sites ask for Bold
  (`CameraScreen` ×11, `MediaReview` ×5, `ProSheet` ×3, `ManualDials` ×2, `ProControls` ×1). Font
  matching resolves 700 → 600, so Bold and SemiBold are pixel-identical today. The one concrete
  consequence: `FocalRail`'s selected-state emphasis is `Bold` vs `SemiBold`, so that weight step
  cannot render and only the filled pill carries the selection. The Theme KDoc now states this
  honestly. NOT fixed here because both options are design calls needing eyes on the device:
  bundling `inter_bold.ttf` (+~110 KB, OFL, same family) changes the weight of all 22 sites at once,
  and collapsing the sites to SemiBold needs a new unselected weight for FocalRail.
- **The green AF reticle can claim focus on a visibly defocused frame (correctness, not cosmetic).**
  `FocusReticle` draws the green bracket and announces "Focus locked" straight off
  `AfIndication.FOCUSED`. On the TELE route this HAL reports `afState = FOCUSED_LOCKED` with the
  lens racked to infinity while a 9 cm subject is completely defocused (device-measured
  2026-07-25), so the reticle is currently the only UI element that can make a FALSE POSITIVE claim
  about the capture. No UI-layer change fixes it — it needs a different truth source. The obvious
  candidate now exists: `gl/FocusDetail.kt` already produces a per-frame judgement, and
  `state.focusConfidence == FRAME_DETAIL` is exactly "the frame resolved no fine detail". Proposed
  shape (needs device work, and must not regress the honest routes): suppress the GREEN state — not
  the reticle — while the frame-detail verdict is SOFT, leaving the neutral bracket. Blocked on the
  frame-detail detector's own first device verification.

## Verification Quick Reference

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n me.hletrd.telecampro/me.hletrd.findx9tele.MainActivity
```

The component above is correct for `main`. The pinned v1 release artifact PREDATES the namespace
move, so an APK built from `.claude/worktrees/release-v1 @ 9541697` launches
`me.hletrd.telecampro/com.hletrd.findx9tele.MainActivity` instead. The applicationId is identical in
both, so the mismatch surfaces only as a silent "activity not found".

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
  above), **D-15** FnSlot table test on next enum growth, **D-16** OPPO Maven cred relocation — CLOSED
  BY REMOVAL 2026-07-25 (`2b4bc55`): the credentials were deleted from `gradle.properties` with the
  maven repo block that was their only reader, so the deferral's exit criterion ("CameraUnit
  registration lands") no longer gates anything. They were OPPO's documented public read-only
  credentials, so no rotation was required. **D-17** GyroEis roll wrap transient — CLOSED by cycle 3 (aa6cab9, wrap-aware smoothedRoll + GyroEisMathTest), **D-18** the
  lit-scene/human-ears residual field checks (above).

## Historical References

- `docs/reviews/README.md` — tracked review-archive index and usage rules.
- `docs/reviews/2026-07-09-backlog-handoff.md` — pre-release implementation handoff and device history.
- `docs/reviews/2026-07-03-comprehensive-review.md` — comprehensive review snapshot.
- `docs/plans/` — completed review/fix plans.
- `.context/reviews/` — optional local raw specialist review snapshots (gitignored).
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — original design intent.
