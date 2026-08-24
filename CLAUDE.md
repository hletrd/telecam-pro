# CLAUDE.md — Find X9 Ultra Teleconverter Camera

Project-level instructions for any agent working in this repo. Read this **first**, then
`docs/ARCHITECTURE.md` (the committed current as-built design authority). Private maintainer context
such as `docs/BACKLOG.md`, `docs/TESTING.md`, `docs/UX_POLICY.md`, and the historical specification
is **optional in clean clones**: read it when present, but its absence must not block work. This file,
the committed architecture, and `docs/FIELD_CHECKS.md` are the self-contained fallback authority.
This file overrides default behavior; user/global `~/.claude/CLAUDE.md` still applies on top (git
rules, latest-versions, destructive-action safety, look-up-before-answering).

## What this is

A professional camera app **built and measured on the OPPO Find X9 Ultra (model PMA110)** and
installable from **Android 13 (API 33)** up. It was single-device until 2026-08-01; that framing
survived here for two days after the decision that ended it, which is exactly the drift the
constraints below exist to prevent. Its purpose is photography and video through a **Hasselblad
"Earth Explorer" afocal 300 mm
teleconverter** that clamps onto the phone's **3× / 70 mm periscope** lens (turning ~70 mm into
~300 mm, ≈4.286× magnification).

Two consequences of the afocal converter drive the whole design:
1. **The image arrives rotated 180°** (afocal telescope, no erecting prism). The main viewfinder and
   saved still/video results must be corrected. The same-stream
   [Loupe Overview is the deliberate exception](docs/FIELD_CHECKS.md#loupe-overview-afocal-exception):
   it omits the afocal term and may show the raw, inverted field. Vertical + horizontal flip = 180°
   rotation (parity-preserving, NOT a mirror).
2. **Exit light is ~collimated**, so the phone lens focuses **near infinity** → manual focus, with a
   nonlinear slider that gives resolution around ∞, is essential.

Goal: **ship on Google Play.** Treat everything as production-bound — no throwaway hacks, no
deprecated APIs, latest stable everything.

## Non-negotiable constraints

- **PMA110-first, multi-device-installable (revised 2026-08-01, user decision — supersedes the
  original "target device only / no minSdk lowering" rule).** `minSdk = 33` (Android 13; the lint
  NewApi audit found zero unguarded sub-35 APIs). PMA110 behavior must stay byte-identical; every
  measured HAL workaround is gated by `camera/DeviceProfile.kt` (the SECOND sanctioned
  model-string seam beside `detectPhone`), and other devices take spec paths resolved by
  ENUMERATED Camera2 capability. Still no CameraX — **Camera2** directly for physical-lens
  routing, `LENS_FOCUS_DISTANCE`, manual sensor, RAW/DNG, 10-bit HDR. Non-PMA110 handsets are
  UNVALIDATED until measured on-device; add quirks only with measurements, never speculatively.
- **Latest toolchain, no deprecated APIs.** See versions below; bump when newer stable ships.
- **User-facing text is ENGLISH + KOREAN (corrected 2026-08-08 — this said "everything user-facing
  in English", which stopped being true when v1.0.1 shipped 126 Korean strings and `localeConfig`).**
  New user-facing text needs a `values-ko` entry too. Two standing exceptions, both deliberate:
  camera-standard abbreviations (ISO, WB, SS, EV, AF, NR, FPS, Fn, Open Gate) are
  `translatable="false"` because Korean camera bodies print them in Latin as well; and company and
  trademark names stay in the original inside translated text, because they IDENTIFY a rightsholder
  and translating them changes who is named. Prose is not covered by either exception — the Setup
  tab's Legal block sat in hardcoded English for exactly that reason until 2026-08-08.
  (Historical commit messages are Korean; do not rewrite history — that's a destructive op
  requiring explicit sign-off.)
- **UI/UX reference: Sony Alpha / Xperia Pro.** Keep the viewfinder quiet. Use Fn, My Menu, MR banks,
  PASM-style exposure, compact OSD, peaking, zebra, histogram, waveform, and review zoom. Do not add
  tutorial banners, warning chips, coach marks, marketing copy, or helper overlays unless the user
  asks. Important states belong in the OSD, Fn, or menu rows. The optional private
  `docs/UX_POLICY.md` adds maintainer examples when present; this paragraph is the committed
  clean-clone policy.

## Toolchain (all pinned in `gradle/libs.versions.toml`)

| Component | Version | Notes |
|---|---|---|
| AGP | 9.3.2 | **Kotlin is built-in** — do NOT apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.10 | Compose compiler plugin version; AGP supplies Kotlin Android support |
| Gradle | 9.7.1 | wrapper |
| Compose BOM | 2026.08.00 | Material3 |
| compileSdk / targetSdk / minSdk | 37 / 36 / **33** | compileSdk 37 required by lifecycle 2.11.0; minSdk 33 since the 2026-08-01 multi-device decision — this row said 36 for two days while the constraint bullet above said 33 |
| JDK | 21 (aarch64) | Homebrew `openjdk@21` |
| heifwriter | 1.1.0 | latest STABLE (the earlier "no stable 1.1.0 exists" note was wrong) |

**JAVA_HOME for CLI builds** (the login shell does not export it):
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

**Android SDK for CLI builds:** follow `README.md` § **Android SDK setup**. The repository CLI tools
honor an ignored `local.properties` `sdk.dir`, agreeing SDK environment variables, then conventional
macOS/Linux paths, and fail before Gradle with the missing Platform 37 / Build Tools 36.0.0 detail.

## Build / deploy / verify loop

```bash
# authoritative non-device host gate (Android + coverage + Python tools/harness/docs)
python3 tools/verify_host.py
# :app:assembleDebugAndroidTest compiles/packages androidTest; it does not run or prove device behavior.

# normal implementation gate; its Gradle APK is developer-only, never device evidence
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Play-release gate (requires clean committed source + local signing credentials)
python3 tools/verify_host.py --release
# Direct Gradle release outputs are developer-only; caller-authored immutableRelease* properties are
# rejected rather than treated as authentication. Only this outer wrapper seals the exported inputs,
# rechecks them after build, freezes allowlisted outputs, and publishes release-evidence.json plus the
# verified commit/tree/output hashes in a unique immutable-release namespace.
python3 tools/build_immutable_release.py :app:lintRelease :app:assembleRelease :app:bundleRelease

# Device evidence must use the exact immutable debug APK printed by the wrapper.
BUILD_RESULT="$(python3 tools/build_immutable_debug.py)"
printf '%s\n' "$BUILD_RESULT"
EVIDENCE_APK="${BUILD_RESULT##* apk=}"
test -n "$EVIDENCE_APK" && test -f "$EVIDENCE_APK"

# device is over wireless ADB — IP/port change between sessions, ask the user for the current one
adb connect <device-ip>:<port>
# debug installs as me.hletrd.telecampro.debug (applicationIdSuffix) — a SEPARATE app from the
# release me.hletrd.telecampro, so runtime permissions must be granted once per package.
adb install -r "$EVIDENCE_APK"
adb shell am start -n me.hletrd.telecampro.debug/me.hletrd.telecampro.MainActivity

# verify: no crash + a real preview. The device may be asleep/locked — wake first, and note that
# a screenshot of a flat-lying phone shows a dark textured surface, NOT a bug.
adb shell input keyevent KEYCODE_WAKEUP
adb logcat -d | grep -E "CameraController|AndroidRuntime|Session configured"
adb exec-out screencap -p > /tmp/shot.png
```

`pm grant` for runtime permissions **fails on ColorOS** (`GRANT_RUNTIME_PERMISSIONS not allowed`) —
the app requests CAMERA/RECORD_AUDIO itself at runtime; grant on the device once.

On this multi-homed Mac, direct wireless ADB can return `No route to host` even when the phone is
reachable. In that case, proxy the current phone port to a temporary loopback port and connect ADB to
`127.0.0.1:<proxy-port>`. Wireless-debugging ports are session-specific; stop the proxy after use.

## Hard-won device facts (do not relearn these the hard way)

- **Camera selection: standalone id "4", NOT the logical multicamera.** The 70 mm tele is exposed
  both as physical `0:4` (sub-camera of logical `0`) and as **standalone id `4`**. Routing streams to
  the physical sub-camera via `setPhysicalCameraId()` **crashes the QTI HAL**
  (`ChiMulticameraBase::configureStreams` → `Broken pipe -32` / SIGSEGV). `CameraSelector2` picks the
  35 mm-equiv **closest to 70 mm** (NOT the longest lens — that's the 230 mm 10×), and **prefers
  `physicalId == null`** on ties. Opening standalone `4` works and permits RAW.
- **A session that carries STILLS is SDR/8-bit; a video session with a transfer is REALLY 10-bit,
  and it buys that by dropping the stills (corrected 2026-08-05 — this bullet said "SDR/8-bit
  shipping session … `tenBit = false`" flatly, which stopped being true when video gained its own
  session and was still being quoted as a hard constraint months later).** The HAL fact is unchanged
  and is the whole reason for the split: HLG10 preview + full-res JPEG + RAW together crash it. So
  the trade is structural, not a limitation — `tenBitSessionWanted(videoMode, transfer) = videoMode
  && transfer != SDR` (`CameraState.kt`) feeds `wantHlg`/`tenBitVideoOnly`
  (`CameraController.kt`), whose ladder rung configures HLG10 with **no still readers at all**.
  That is exactly what the UI means by `"10-bit video · stills off"`, and it is why
  `acceptedOpticsAuxState` must not normalize `photoFormats` in a still-less session (see the DNG
  route-input entry — a trip through log video otherwise wrote an empty format set over the
  operator's selection).
  **What is still NOT claimable, and this is the part that must survive any rewrite of this bullet:**
  the SOURCE is the ISP's display-referred, already tone-mapped stream. Ten-bit video is a real
  10-bit *encode* of that stream, NOT recovered HDR and NOT scene-referred capture — see the next
  two bullets, which remain correct as written. Photo, and any video left on SDR, stay 8-bit.
- **HLG is a display-referred mapping, not recovered HDR.** The input stage follows accepted session
  truth: standard photo/SDR-video input takes the BT.1886 2.4 decode, while non-SDR video first takes
  the inverse HLG decode/reference-white normalization from its HLG10 Camera2 stream. The common
  display-light signal then maps through linear BT.709→BT.2020, the explicit reference-white/OOTF
  scale, and the BT.2100 HLG OETF (`Shaders.kt`; CPU anchors host-tested). In both cases the ISP has
  already tone-mapped the scene, so clipped/rolled-off highlights cannot be recovered. The release
  EGL target remains 8-bit even though the non-SDR source and HEVC Main10 output are 10-bit-class
  stages; playback appearance still needs a real HDR display and none of this is an end-to-end
  10-bit or scene-referred claim.
- **RAW only on the standalone camera — in BOTH failure modes (extended 2026-07-14).** RAW routed
  through physical sub-camera routing crashes configure (`DataSpace override not allowed for format
  0x20`), AND a still with the RAW target on the plain LOGICAL camera errors the whole camera device
  ~5 s after the shot (`CAMERA_ERROR(3)`, no image ever arrives). Gated to standalone selections
  only (`sessionAttemptPlan` `!logicalMultiCamera`).
- **DNG is therefore a ROUTE INPUT, not a save option — and that makes it four different bugs
  (all device-fixed 2026-07-29).** Wanting RAW is what MOVES photo off the logical seamless camera
  onto a standalone lens, so DNG is available on EVERY lens, not only TELE. Consequences that each
  cost a real defect:
  1. **Restore must push it.** `engine.setRawWanted` used to be called only from the live toggle, so
     a persisted DNG selection was silently inert on every launch — sheet showed DNG on, session came
     up `raw=false`, shutter wrote `outputs=jpg`. Every route input must reach the engine from the
     settings-restore path too; the reopen-triggering setters are enumerable, so check them all.
  2. **Its reopen must RE-RESOLVE the route.** The bare `reopenForSession()` reuses `overrideId`,
     which caches the id the LAST ACCEPTED session resolved to. After a Video→Photo trip that is the
     LOGICAL camera, so the reopen rebuilt the one route that cannot carry RAW — and because
     `setRawWanted` change-gates on `rawWanted`, the divergence was PERMANENT (no later toggle could
     recover it). `setRawWanted` begins its own optics transaction with `overrideId = userCameraPin`.
     It is the ONLY reopen whose route ANSWER changes — hi-res, aspect, fps and transfer all keep the
     same camera — so it alone needs this.
  3. **The chip is gated by `rawSelectable`, neither session truth nor bare capability.** Session
     truth made it unreachable (disabled because RAW was absent, absent because it could not be
     enabled); bare capability left it live in a 10-bit VIDEO session that drops both still readers by
     design, under the caption "HEIF/JPEG unavailable; DNG only" while DNG was equally unavailable.
  4. **A still-less session must not edit the request.** `acceptedOpticsAuxState` normalized
     `photoFormats` against accepted outputs; in 10-bit video those are EMPTY, so a trip through log
     video wrote the empty set over the operator's selection, which persisted on background and came
     back as HEIF-only. Normalization now runs only when the session has a still target at all.
     Capture-time normalization still guarantees no shot is attempted against a missing output.
- **Last-capture review is owned by monotonic capture id, then displayability.** A newer RAW-only
  success replaces an older thumbnail with a truthful DNG metadata placeholder. A processed sibling
  for that same capture upgrades the placeholder; a late RAW sibling never displaces its processed
  peer or a newer capture. Delete freezes and tombstones the whole capture before asynchronous
  MediaStore deletion, removes every known sibling, and immediately deletes any late sibling callback.
  Opening review pins that exact family outside the bounded ordinary history until close/delete; if
  pinning the frozen URI fails, the UI promises file-only deletion. Images and Video restore queries
  fail independently, so valid rows from either successful collection still participate.
  Across process restarts, every new capture's HEIF/JPEG/DNG outputs share one versioned timestamped
  filename key (video owns one-file family); a bounded Images+Video query reconstructs the newest exact
  family and seeds it below live ids. Legacy filenames are never proximity-grouped and expose truthful
  file-only delete copy. A late sibling of an id that the tracker's own bounded trim evicts DURING
  its `record()` is TRACK_ONLY, never the review owner — an evicted family would publish a review
  URI that can't be pinned and would degrade delete to file-only.
- **Stills on the LOGICAL camera are YUV, not HAL JPEG (2026-07-14).** gralloc rejects the ~42 MB
  JPEG blob allocation on the plain logical session (`SnapAlloc: ValidateDescriptor invalid` — the
  image never arrives and the shot wedges `pending`), at BOTH 4096×3072 and the logical array's own
  4080×3064. `StillSnapshot` repacks YUV_420_888→NV21 on the camera thread and JPEG-encodes lazily
  on the io thread; standalone cameras keep the proven HAL-JPEG path. A capture watchdog
  (`CAPTURE_WATCHDOG_FLOOR_MS` + the exposure-aware margin) fails any shot whose image never arrives so the shutter can never wedge.
- **Pseudo-ZSL on the LOGICAL and FRONT photo routes: bright shots serve a buffered frame, dark shots
  deliberately do NOT (LOGICAL device soak 2026-07-25; FRONT path added 2026-07-28).** The active
  route's full-res YUV still reader also streams on the
  REPEATING request (`zslStreamingActive`), and a 3-deep ring pairs frames with their
  `TotalCaptureResult` by exact `SENSOR_TIMESTAMP`. S4a soak, device-measured: **29–31 fps lit /
  14–16 fps at the dark fluidity cap over 5-minute soaks, zero FrameGap stalls ≥200 ms, zero camera
  errors, +0.3 °C battery.** S4b serve check, device-measured: **a lit 1× photo served 4/4 shutter
  presses at 0 ms delivery lag**; a dark M-mode 2 s shot served **none** and ran a real capture whose
  EXIF read a true 2.0 s. **The dark refusal is the DESIGN, not a gap — the user has explicitly
  accepted it.** `ZslAdmission.kt` admits a buffered frame only when its ACTUAL sensor values match
  the still's INTENDED values within 1/6 stop (plus zoom within 2 %, age <= 400 ms, app-side AE-OFF,
  processed-only, no AE-flash, no live gesture); in low light the fluidity cap deliberately diverges
  the preview from intent, so admission MUST fail and a full-quality real capture MUST run. **Do not
  "fix" the dark path by widening the tolerance** — that silently trades the user's exposure for
  latency. SINGLE drive only (`allowZsl = !singleShot` excludes the in-REC snapshot);
  TELE/rear-standalone/video/burst/AEB are untouched and keep the legacy blind-adopt path
  byte-for-byte. FRONT takes deep YUV on its full rung, degrades to shallow YUV, then returns to its
  proven HAL-JPEG path before preview-only; PMA110's mandatory-YUV quirk remains LOGICAL-only. The
  front route has capture-latency evidence, but its sustained idle/memory-pressure cost has no soak
  evidence yet and remains a field check (`docs/FIELD_CHECKS.md` A5) rather than an inferred success
  claim.
- **Seamless zoom = the logical camera, PHOTO ONLY (2026-07-14).** Camera 0 (`logicalMultiCamera`,
  physIds 3/2/4/5) spans zoomRatio 0.6–20 with HAL-internal lens crossing — pinch never reopens.
  Lens picks are zoom presets; TELE pins standalone 4 (digital 1–10×) and OFF returns to logical at
  3×. Zoom ticks use the controller fast path (cached repeating builder, zoom keys only) — routing
  them through the full `startPreview` rebuild read as stutter. Pinch/zoom events are additionally
  COALESCED in the ViewModel (leading apply + 16 ms trailing flush of the newest value, ~60 Hz) — per-event
  application recomposed the whole tree at input rate (~120 Hz) and read as jank.
- **The Loupe Overview omits the afocal term per draw (user-specified 2026-07-28).** Today's inset
  re-draws the SAME converter-fed stream, so omitting the afocal correction leaves that box showing
  the raw, inverted field relative to the converter-corrected main view. The omission becomes an
  upright world view only when the overview is fed by a future true-WIDE lens that the converter is
  not clamped to. The current finder draw passes only the window-rotation term through
  `rotationOverrideDeg` (0 on the portrait-locked phone), and the framing hint takes that same term
  to match (a hint that kept the afocal rotation inside a box that declined it would land
  point-mirrored). Rotation is otherwise renderer STATE shared by every draw role, so this override
  is an explicit per-call opt-in with exactly one caller — never make it a settable field.
  Device-verified by A/B: the overview's
  vertical gradient inverted (top-brighter −7.3 → bottom-brighter +10.4) while the main view's was
  unchanged (+0.9 → +0.5). **HONESTY LIMIT:** the genuinely upright version is the second-stream
  wide finder on the BACKLOG; declining a rotation on the current same stream cannot manufacture it.
- **Loupe Overview is HONEST about the single stream (gate corrected 2026-07-29).**
  The Assist toggle (default OFF, persisted) draws a bottom-right corner viewport re-drawing the
  FULL current camera frame while the PUNCH-IN LOUPE is active at either TELE or unified zoom >= 3x.
  Photo additionally requires 4:3; Video deliberately ignores the unrelated still-aspect setting,
  which once made the overlay appear or vanish mid-clip. It can NEVER show an unzoomed/wide field:
  the HAL's `CONTROL_ZOOM_RATIO` crop is baked into the one camera texture, so the overview is only
  genuinely wider than the main view while the loupe (or transient GL zoom compensation) magnifies
  past the delivered field. `FINDER_MIN_ZOOM = 3f` keeps a converterless steady 1x/2x view from
  duplicating the main frame ~1:1; a mounted converter qualifies at any zoom. A true wide finder is a
  BACKLOG design item (second stream or HAL-zoom-cap split). GL
  scissor box and Compose border derive from ONE pure seam (`finderRect`; the
  padding-before-fillMaxWidth chain drew the border ~6% small), the border anchor is
  layout-direction-absolute (RTL), the gate is ONE shared unit-tested predicate
  (`teleFinderResolved`/`teleFinderVisible`) resolved in one place (`pushTeleFinder`, re-pushed
  synchronously at every intent/mode/rollback door so the GL overview can't outlive a TC-off), and
  an `OVERVIEW` OSD tag appears only while the same-stream overview is actually visible. The finder
  draw is failure-isolated with
  `try/finally { glDisable(GL_SCISSOR_TEST) }` — scissor is CONTEXT state; a leak would clip the
  encoder/analysis draws, and a finder-only error must never fail preview health.
- **VIDEO stays on the STANDALONE lenses — the logical camera's EIS leaks its warp margin
  (2026-07-14).** With any video stabilization on (Standard AND Active), camera 0's stream carries
  an uncorrected EIS warp band (~6% of width) on one edge — in the PREVIEW and in the RECORDED
  FILE (device-verified frame extraction; displays as a rainbow-smear band at the bottom in
  portrait playback). `resolveNonTeleId`: photo=logical/seamless, video=matching standalone;
  **The zoom SCALE follows the ROUTE, not the mode (corrected 2026-08-04 after three
  user-visible symptoms).** `zoomRatio` is main-relative on the logical seamless camera and
  LENS-LOCAL on any standalone one. Six places decided which by asking `mode == VIDEO` — true of
  the video route but blind to the OTHER standalone door, since wanting DNG is itself what moves
  photo off the seamless camera. With DNG on, tapping 3× wrote the main-relative 3.0 into a
  lens-local slot: 3× digital zoom on the 70 mm lens, OSD "208 mm", readout 9.1× (3 × 70/23), and
  the rail collapsing to 1× because `forZoom()` read a lens-local 1.0 as unified. The wire zoom was
  correct at 3.0 throughout, which is why every zoom test kept passing — the value was never wrong,
  only the scale it was read on. ONE round-tripping pair now owns the conversion
  (`unifiedZoomOf`/`localZoomOf`), keyed on `standaloneRouteWanted(...)`, and it divides by the
  OPTICAL lens the route REACHES — not the band tapped: on a one-camera device the "3×" band is a
  crop of the 1× lens, so using the band double-counts it (the first fix did exactly that and broke
  the tablets while looking right on PMA110). Device-verified on all three: PMA110 23/69/230 mm,
  TB336ZU 26/78 mm, TB331FC 27/81 mm — the tablets' ×3 readings are the crop, and are correct.
- **setRepeatingRequest STALLS this HAL's preview ~180 ms per swap (measured 2026-07-14) — live
  zoom is GL-rendered.** Swapping the repeating request (zoom tick, control change, AE-OFF manual
  values, HAL-AE alike) gaps the stream 170–250 ms; per-tick zoom submits made zoom read as ~5 fps
  regardless of input smoothing (the true root cause behind THREE rounds of "핀치 버벅" reports).
  Architecture: the preview renders the REQUESTED zoom instantly (FlipRenderer `zoomComp` =
  requested ÷ HAL-reported zoom, GL self-redraws the last frame when the camera is quiet).
  **A MOVING gesture submits NOTHING (cycle 9, `submitNow = !interactionActive`)** — the ≥200 ms
  throttle that used to pace mid-gesture submits was REFUTED on device 2026-07-27: submits already
  ~400 ms apart (double the floor) stalled 210–413 ms just the same, because the stall belongs to
  the repeating-request SWAP, not to how tightly swaps are packed. **Do not "fix" gesture stutter by
  tuning `SENSOR_SUBMIT_MIN_INTERVAL_MS`** — that constant now paces only the sensor fast path; the
  quiet-window landing is independently scheduled 250 ms after the newest zoom flush. On the common
  no-FPS-change route, the START edge carries the 1.2× WIDE aim (zoom-out margin) and the quiet
  landing carries exact framing; the 700 ms end is state-only once that exact value is already on
  the wire, or lands exact if quiet never did. Routes whose boost flip really changes FPS still
  rebuild at the end. An injected two-finger pinch measured ZERO submits and ZERO frame gaps while
  the fingers move; the accepted cost is progressive softening while
  zooming in, since the HAL field stays frozen at the edge's target.
  Encoder/analysis only ever see REAL camera frames (self-redraws
  are preview-only). Plus: in low light the app-side P loop trades exposure→ISO brightness-
  neutrally during gestures (ISO-headroom-bounded) so the base frame rate rises. The fps-boost
  transition owns the edge request and exact still truth through
  `setSmoothPreviewBoost(active, finalZoom, halZoom, submitExactWhenFpsUnchanged)` — the old
  rebuild-then-correct order at gesture end
  re-submitted the stale wide-aimed ratio and paid two ~180 ms stalls back to back. The zoom RMW
  on engine `controls` takes the engine monitor (packet writers replace `controls` wholesale under
  it; `@Volatile` alone lost whole rollback packets mid-pinch — `setControls` now takes the same
  monitor, closing the last unsynchronized wholesale writer), and `onZoomResult→gl.setHalZoom`
  forwarding is change-gated with a per-rebuild reset (a suppressed final ramp value would
  otherwise wedge the GL compensation after a reopen). Since cycle 2: stills build from the EXACT
  requested ratio, never the wide-aimed HAL target (`setZoomRatio(halRatio, requestRatio)`); a
  quiet-window landing (~250 ms after the last flush) lands the exact ratio before the 700 ms
  boost tail so clips stop carrying ~1.2×-wide framing after finger-up; and the same ≥200 ms
  fast-path architecture covers the OTHER high-churn keys — `sensorFastPathAdmitted` admits
  focus-distance/ISO/exposure-only deltas (`sensorOnlyControlsDelta`) to a cached-builder resubmit
  (`applySensorValueControls`, the same derivation the full rebuild uses) so ruler drags and the
  app-side AE loop stop paying full ~180 ms rebuild stalls at 25 Hz. A live tap-AF/AF-lock
  override RIDES the fast path: `applyAfOverrides` restores the override keys (tap-AF
  AF_MODE_AUTO hold, AF-lock OFF + frozen distance; regions persist on the cached builder; never
  a re-trigger) after every fast-path value write, so key state equals the full rebuild's — the
  earlier wholesale refusal (c928eac) protected the tapped focus but starved the preview back to
  ~5 fps whenever the app-side AE loop ran with a held override. Device-verified 2026-07-18: the
  tapped lens distance holds bit-exact across ISO changes with the loop live.
- **Long exposures: the repeating request is CLAMPED and the still ceiling is 4 s (device-bisected
  2026-07-18).** Two independent HAL facts: (1) a multi-second `SENSOR_EXPOSURE_TIME` on the
  REPEATING request wedges the still handoff — the queued still sits inert behind the in-flight
  long frame and the device errors `CAMERA_ERROR(3)` after ~one exposure (shot silently lost,
  3/3 repro at 6.3 s) — so `previewExposureTrade` caps the preview exposure in EVERY AE-OFF mode.
  Since cycle 8 the cap previews actually ride is the tighter `PREVIEW_FLUIDITY_MAX_EXPOSURE_NS`
  (1/15 s — a ≥15 fps finder AND ~0.53 s pipeline lag instead of seconds); brightness beyond ISO
  headroom is a GL PREVIEW brightness simulation, not sensor exposure: the trade returns the
  residual shortfall (≤×16, `TradedPreviewExposure.digitalGain`) and the preview shader multiplies
  it in linear light (`uDigitalGain`) — display, zebra/false-color, peaking, scopes, AND the
  app-side AE meter all read the SIMULATED still exposure (the analysis readback stays unboosted;
  a 256-entry display LUT is applied CPU-side exactly once — AE metering the dimmed wire preview
  would ratchet the intended exposure), while files and the STILL request never see any of it.
  `PREVIEW_SAFE_MAX_EXPOSURE_NS` (500 ms) REMAINS as the outer safety invariant (PROGRAM keeps its
  1/30 s neutral trade target; the pre-fix trade SKIPPED entirely at the ISO ceiling, which is
  exactly how 6.3 s reached the wire in the dark). Brightness-sim appearance is DEVICE-VERIFIED
  2026-07-25: on a dark desk the FrameGap went 500 ms → ~66 ms sustained (a fluid ≥15 fps finder),
  and an M-mode 2 s want-exposure held that 66 ms cadence at full simulated brightness (mid-band
  luma 255) while the saved still's EXIF read a TRUE ExposureTime 2.0 s / ISO 1600 — the simulation
  never reached the file. (2) With the repeating
  stream safely short, a STILL request above 4 s STILL errors the device the same way — the
  advertised exposure upper (≥20 s) is a lie; 2/3.2/4 s complete with correct EXIF, 5/6.3 s are
  reproducibly fatal. `HAL_SAFE_MAX_STILL_EXPOSURE_NS` (4 s) clamps the advertised range at the
  CAPS seam so the shutter ruler, request clamps, AEB brackets, and the still watchdog all stay
  truthful; an over-ceiling persisted value captures at a clamped, EXIF-honest 4.0 s.
- **Compounding zoom inputs must base on the COALESCED pending value (2026-07-14).** Pinch factors,
  hardware-key steps, and the ease ticker all multiply "the current zoom" — but UI state lags the
  16 ms coalescing flush, so compounding against `_state` made zoom crawl between flushes then jump
  at the boundary (read as pinch jank twice before being root-caused). `currentZoomBase()` in the
  ViewModel is the one true base; reset `ZoomGlideState.pendingRatio` AND
  `ZoomGlideState.easeTarget` whenever anything
  outside the coalescer rewrites zoom (mode flip, lens preset, TC toggle) — the ease target is an
  ABSOLUTE number in the old zoom scale, and a glide surviving a scale remap eases toward an
  un-commanded framing in the new scale.
- **System bar icons are PINNED light; `enableEdgeToEdge()`'s default follows the SYSTEM night
  setting, not the app's (device-verified A/B 2026-08-04).** `TeleCamProTheme` is unconditionally
  dark (`TeleDarkColorScheme` — no `isSystemInDarkTheme`, no `values-night`), but a bare
  `enableEdgeToEdge()` defaults to `SystemBarStyle.auto()`, whose `detectDarkMode` reads the DEVICE's
  night setting. On a phone in LIGHT mode that resolved `isAppearanceLightStatusBars = true`, and the
  status bar went effectively BLANK over the dark viewfinder: clock, wifi, 5G, signal bars and the
  charging bolt all black-on-black, with only the green-backed battery pill showing through. It
  survived to v1 because every device in the lab sits in dark mode, where `auto()` and the fix agree.
  `MainActivity` now passes `SystemBarStyle.dark(...)` for BOTH bars — **"dark" names the BACKGROUND,
  so it yields WHITE icons** (`dark` sets `detectDarkMode = { true }`, and `setUp()` applies
  `isAppearanceLightStatusBars = !isDark`). The theme's `android:windowLightStatusBar=false` CANNOT
  do this job and never could: it seeds only the STARTING window, and `enableEdgeToEdge()` overwrites
  the appearance flags in `onCreate`. Device A/B on the PMA110 in light mode, one variable (the APK):
  `AppearanceRegion{LIGHT_STATUS_BARS bounds=…}` → `AppearanceRegion{ bounds=…}`, bright pixels in the
  status-bar band 0.33 % → 1.77 %. The probe is `dumpsys window | grep -A2
  mLastStatusBarAppearanceRegions`; screenshots alone are weak evidence here because the viewfinder
  behind the bar is live camera content.
- **The REC tally border must follow the panel's rounded corners.** A square full-screen border's
  corner segments fall OUTSIDE the visible display area and vanish. Read the radius from the
  WindowInsets RoundedCorner API and use the platform radius unscaled. The former ×1.2 multiplier
  was device-rejected on 2026-07-29: it turns the circular arc too early, shortens the straight runs,
  and leaves visible gaps along the sides. The tally is an edge indicator, so following the reported
  panel radius takes priority over approximating the glass's continuous-curvature squircle.
- **Analysis readback is FBO-downsampled (2026-07-14).** The scopes/AE readback used to
  `glReadPixels` the FULL preview framebuffer (~33 MB at 4K) every 5th frame — a periodic GL-thread
  stall that read as preview/zoom stutter, and it metered peaking/zebra overlay pixels. It now
  re-draws capture/EIS framing into an aspect-matched FBO whose long edge is at most 256 px
  (≤256 KiB RGBA readback). Preview-only punch-in/loupe framing never enters scopes or AE. The REC tally border follows the
  panel's physical rounded corners via the WindowInsets RoundedCorner API (a square border's
  corners fall outside the visible area and vanish). The executor, single-flight gate, FBO/buffer,
  byte snapshot, and callback authority are owned by one GL generation; stop retires that owner before
  shutdown, so old work cannot publish or clear a replacement generation's busy gate.
- **Session fallback ladder** in `CameraController.configureSession`: non-TELE is full → drop RAW →
  drop HLG → preview-only. TELE tries vendor full/degraded plans, then regular full/degraded plans,
  and reserves both preview-only variants for last. Keep it; different capability combos fail on this HAL.
  When hi-res is wanted (capability-gated; dormant on PMA110) the ladder PREPENDS a hi-res rung:
  attempt 0 is the full plan's hi-res variant with RAW forced off, and every later attempt maps
  onto the ORDINARY ladder shifted by one — a failed hi-res attempt falls back to full-WITH-RAW
  first, and `maxSessionAttempt` stretches the exhaustion bound by one.
  Ready state reports the processed/RAW readers from the session that actually succeeded, not the
  aspirational attempt. Photo and in-REC snapshot admission follow that accepted output mask;
  preview-only still permits video REC/Stop.
- **Preview host is a `TextureView`, not `SurfaceView`.** A SurfaceView's surface sits behind the
  window and was occluded by the opaque Compose background → black viewfinder. TextureView composites
  in the view hierarchy.
- **Lifecycle races crash the camera.** Launching behind the keyguard delivers `onStop` mid-session-
  config → device disconnects → `createCaptureRequest` throws `CAMERA_DISCONNECTED`. Guarded by a
  `closed` flag + `runCatching` in `CameraController`, and a `paused` flag in `CameraEngine` (never
  open the camera while backgrounded). The exact lifecycle chain is `MainActivity.onStart` →
  `CameraViewModel.onStart` → `engine.resume`, and `MainActivity.onStop` → `CameraViewModel.onStop` →
  `engine.pause`. `TerminalAcquisitionGate` closes before release's final
  `gl.stop`, so queued cold start cannot resurrect a GL generation afterward. Preview-window tasks
  also carry synchronous invalidation generations; stale/released native windows cannot bind later.
- **Large screens take a LANDSCAPE window; phones do not (2026-08-04).** The manifest carries no
  `screenOrientation` restriction. `MainActivity.lockPortraitOnHandsets` applies the portrait lock
  only when `smallestScreenWidthDp < 600`; PMA110 measures **411 dp**, so the phone remains
  device-fixed portrait while sw600dp+ is left free for Android 16/17 large-screen behavior.
  `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` is also GONE. Four things take a window term —
  preview rotation, displayed aspect, tap mapping
  (`RotationMath.unrotateViewPoint` inside `mapTapFocusGeometry`), and glyph counter-rotation — and
  **every one is proven inert at `ROTATION_0` by a unit test; that degeneracy is the phone's
  regression fence.** The preview term rides `FlipRenderer.draw`'s per-call `rotationOverrideDeg` and
  must NEVER be written into `setRotationDegrees`: rotation is renderer STATE shared by every draw
  role, and **capture masks and encoder framing stay GRAVITY-derived on purpose** so a clip records
  the same field however the window is turned — routing them through window shape re-opens the
  cycle-4 overscan bug. Sign device-BISECTED on TB336ZU, not assumed: the preview's brightness
  asymmetry moved top → left, i.e. 90° CCW. The optional private `docs/BACKLOG.md`, when present,
  carries the measurement log; this paragraph is the committed clean-clone result.
- **ORIENTATION MOVES NO CONTROL (2026-08-05, owner decision).** The shutter, gallery and Fn stay at
  the device's physical bottom however it is held; only what must be READ — text, chips, hints, and
  the histogram/waveform — rotates, in place, via `overlayRotation`. A wide window briefly earned a
  separate operator rail (`landscapeOperator`: a leading menu column plus a trailing capture rail,
  both widths subtracted from the preview box). It is DELETED, along with the TopBar Row-or-Column
  machinery it needed, because two shapes of chrome gave the shutter two homes and made the operator
  re-find it after every turn. Do not reintroduce a layout keyed on window aspect: a turned handset
  window is ~420 dp tall, so a top bar plus a bottom cluster eat it from both ends and push the rest
  of the chrome back onto the image — measured on PMA110 when exactly that was tried.
- **Sensor orientation = 90; the activity is portrait-locked ON PHONES (see the entry above for
  large screens); preview verified upright on device.** The
  camera SurfaceTexture transform *already* rotates the sampled image by the sensor orientation, so
  the GL renderer adds **only the afocal 180°** in tele mode (`CameraEngine.previewRotationDegrees()`
  returns 180 in tele, 0 otherwise) — NOT `±sensorOrientation` (both 270° and 90° read 90° off on
  device). It still passes `sensorOrientation` to the renderer purely to pick the preview **aspect**
  (the ~90° swaps displayed W/H). Captures rotate raw pixels by `sensorOrientation + afocal180 −
  deviceOrientation(gravity)` (`captureRotationDegrees()`; FRONT = `sensor + dev`) — the gravity
  term is GyroEis CCW-POSITIVE, so it SUBTRACTS on the rear (device-bisected 2026-07-25: the old
  `+dev` saved every landscape-held rear still 180° rotated; portrait, dev=0, was silently fine).
  HEIF pixel-rotates and DNG tags EXIF orientation. `camera/RotationMath.kt` holds this as pure,
  unit-tested functions. DEVICE-VERIFIED 2026-07-25: rear portrait + BOTH rear landscape
  directions + front portrait + front landscape all upright. The muxer hint carries the same
  −dev/+dev term, and its external-player playback check PASSED 2026-07-29 (operator-reported:
  held portrait + both landscape directions all play upright in an external player). **Rotation is
  now closed end to end — preview, stills, and video container.**
- **Device orientation only updates while the phone is HELD.** `GyroEis.currentDeviceOrientation()`
  derives 0/90/180/270 from gravity, but when the phone is **flat** the in-plane gravity is ~0 and
  `atan2(x,y)` is noise — so it updates the discrete value only when `hypot(x,y) > FLAT_GRAVITY_
  THRESHOLD` (~½ g) and otherwise holds the last confident value. Found via output-file check: a
  flat-on-desk DNG had wrongly tagged `ORIENTATION_NORMAL` instead of 270°.
- **On-screen glyph counter-rotation is `+deviceOrientation`, NOT `−` (device-confirmed 2026-07-08).**
  `atan2(x,y)` on gravity yields **dev=90 for a COUNTER-clockwise (left) landscape and dev=270 for a
  clockwise (right) landscape** — the opposite of the naive "90=right" assumption. So the counter-
  rotation that keeps glyphs upright is `+dev`. A `−dev` sign leaves BOTH landscapes 180° off, which is
  invisible on near-symmetric icons and only shows once text (mode labels, the zoom `×`) rotates. Only
  compact glyphs + short labels rotate; wide dial pills / the OSD row stay screen-fixed (rotating a wide
  box 90° pokes it out of its layout slot — `Modifier.rotate` is a draw transform, not a re-layout).
  The scopes (histogram/waveform) DO rotate, via a `rotateLayout` modifier that reserves the ROTATED
  bounding box (swaps W/H at 90/270) so a stack of rotated scopes doesn't overlap — the reason plain
  `rotate()` couldn't be used and they were screen-fixed before.
- **PASM+ISO exposure: only video-P (and flash-metered P) uses the HAL AE; everything else is
  app-side.** `ExposureMode { PROGRAM, SHUTTER, ISO, MANUAL }` (no aperture-priority — fixed tele
  aperture). Camera2 has no shutter-/ISO-priority AND no min-shutter hint, so `camera/AutoExposure.kt`
  closes the loop off the GL preview luma: SHUTTER drives ISO, ISO drives exposure time, and **photo
  PROGRAM runs a real program line** (`driveProgram`): shutter held at the handheld 1/(effective focal)
  rule (≈1/300 s with the TC), ISO carries exposure, shutter slides (≤1 stop/tick,
  brightness-neutral) only when ISO clamps — down to a 1/10 s ceiling in the dark, faster at base ISO
  in the bright. `ManualControls.programAppSide` (recomputed on mode/flash/exposure-mode changes) keeps
  video-P and AUTO/ON-flash-P on the HAL AE (flash metering needs AE ON). The GL luma readback is
  force-enabled whenever the app-side loop needs it (`gl.setAeMetering`). `autoExposure` is a derived
  `val` (`== PROGRAM && !programAppSide`); the capture path treats all app-side modes identically
  (AE off, sensor values set) because the loop keeps `iso`/`exposureTimeNs` fresh. Since cycle 8 the
  loop's per-tick clamp is ERROR-SCHEDULED (`maxStepStops` = 0.5×|error| in [0.30, 1.20] stops):
  ≤0.6-stop errors keep the user-tuned 0.30 smoothness ceiling, big scene changes converge in ~9
  ticks instead of ~17, and steps stay strictly below the remaining error (no overshoot); the loop
  meters the SIMULATED (gain-compensated) luma so a fluidity-capped dark preview cannot ratchet the
  intended exposure. DEVICE-VERIFIED 2026-07-25: a 4.6-stop dark↔lit flip converged in ~2 s with zero
  breathing at rest, settling on wire ISO 9052 / 66.7 ms — the wire ISO sitting ABOVE the user's
  1600 is the fluidity trade working as designed, not a bug.
- **Controls apply is a THROTTLE, not a debounce.** `CameraViewModel.updateControls` applies the newest
  value every 40 ms (25 Hz) *while* a gesture continues (a debounce starved: continuous pinch reset the
  timer so zoom only landed on finger-up; the earlier 80 ms window quantized the hardware slide-zoom
  into visible steps). Keep the `applyScheduled`-flag trailing throttle. Persistence is separate: user
  changes schedule a 500 ms trailing debounced synchronous commit (`scheduleSettingsSave`).
- **Video records the DISPLAYED portrait aspect since cycle 4 (2026-07-18).** The encoder buffer
  takes `RotationMath.encoderSurfaceSize` — swapped to portrait (2160×3840 for 4K UHD) whenever
  sensor+content rotation nets 90/270 — because the stream-shaped LANDSCAPE buffer made
  `coverScale` overscan ~3.16× and every clip recorded only a center band of the viewfinder field
  (device-measured via luminance-gradient A/B: pre-fix cell-map corr ≤0.29, post-fix 0.992). The
  camera STREAM stays `videoSize` (the HAL fixes that); only the MediaCodec buffer and EGL target
  swap. The muxer hint still carries device tilt only. Pre-cycle-4 "verified" video facts below
  describe container/codec truthfully but predate this framing fix.
- **Release output files verified on PMA110 (2026-07-10).** A serialized rapid double-shutter test
  produced exactly one valid DNG+HEIF pair (that 07-10 DNG measured 4080×3064 pre-RAW-re-route;
  since RAW moved onto standalone routes on 07-14 — a gating the 07-29 route-input model then
  widened to EVERY lens — TELE DNG is **4096×3072, 16-bit**, standalone cam 4's
  advertised RAW16 array, device-measured 2026-07-24 by `tele_dng_parity`). A 4K HLG clip was HEVC Main10
  3840×2160 at 30000/1001 with AAC 48 kHz stereo; Open Gate produced HEVC Main10 2560×1920 4:3 at
  30000/1001 with AAC. The release smoke test had no crash or ANR. Saved-STILL uprightness in
  deliberately held poses is CLOSED (device-verified 2026-07-25 — see the rotation bullet above),
  and the muxer orientation hint's EXTERNAL-player playback check is CLOSED too (operator-reported
  2026-07-29). No rotation residual remains. The optional private `docs/BACKLOG.md`, when present,
  carries the historical run log; this paragraph and `docs/FIELD_CHECKS.md` are the committed
  clean-clone result.
- **Photo and video AUTO use different target-FPS policies.** A fixed `[30,30]` range blocks photo
  AE from extending exposure in low light, so photo AUTO uses `CameraCaps.autoFpsRange()` with the
  lowest available floor. Video AUTO must hold the selected recording cadence: without that pin, a
  29.97 selection produced a real 25 fps file in low light. `CameraController.pinAutoFps` is therefore
  enabled in video mode. App-side/manual exposure also pins the selected FPS.
- **Tap-to-focus uses `AF_MODE_AUTO`.** CONTINUOUS + a bare trigger just holds the (often wrong)
  current distance; a tapped point sets a metering region and forces a one-shot AUTO scan that LOCKS
  (`touchAfActive`). The lock (and the tap-owned loupe center) releases on a NEW tap, a focus-mode
  change, an explicit reset, or an optics-remap door (mode/lens/TC/camera-override) — the 2 s
  reticle timer is VISUAL-ONLY and does not release it (cycle 4; it used to return AF to AF-C
  hunting 2 s after every tap). AF reaches FOCUSED on device.
- **The HAL FALSE-LOCKS `FOCUSED` at INFINITY on a point-blank subject (device-measured
  2026-07-25).** With a subject ~9 cm from the lens on the TELE route (whose advertised minimum
  focus is **120 cm**) the preview is visibly, completely defocused — yet the HAL reports
  `afState = 4` (`FOCUSED_LOCKED`) with `LENS_FOCUS_DISTANCE = 0.0068` diopters (≈146 m, i.e. racked
  to **infinity**, the opposite end of the range). It neither admits failure nor racks near. So the
  cycle-8 `macroTooCloseCandidate` predicate — which requires `AfIndication.FAILED/SCANNING` **and**
  the lens near its close limit — is **structurally unable to fire on this device**, and no amount
  of threshold tuning changes that. Advertised minimum focus distances (live `dumpsys`, this
  device): most rear cameras **6.67 dpt (15 cm)**, one ultrawide **25 dpt (4 cm)**, the periscope
  tele **0.833 dpt (120 cm)**. The predicate is KEPT (it proves strictly more, and it is live on the
  honest lenses); the missing coverage is supplied by the app's own pixels instead — see below.
- **The frame-detail detector proves "unresolved", NOT "too close" (`gl/FocusDetail.kt`,
  host-tested; DEVICE-CHECKED 2026-07-25 — it MISSES the case it was built for, and that is
  DELIBERATELY LEFT AS IS).** The contract is "may miss, must never false-fire", so a miss does not
  violate it; relaxing the threshold was tried and REVERTED because it false-fires on
  shallow-depth-of-field shots, which for a 300 mm telephoto app are the normal photograph. Measured
  votes, the exact binding constraint, and the deferred way to close it properly live in ONE place —
  the optional private `docs/BACKLOG.md` cycle 8, item 3 when present; this bullet preserves the
  committed binding constraint. Do not re-run the check expecting a defect. Because of the
  false-lock above, focus confidence is
  measured from the **existing** scopes/AE analysis readback (no second readback, no new GL pass —
  it is a pure CPU **rider** that computes only when that readback already runs, so it is silent in
  video-P / flash-metered P where none does). Per 16×16 tile, per axis, it takes the RMS **second
  difference** (curvature) at a fine lag and coarse lags {4, 8, 16, 32} and votes on
  `R = s_1 / max(s_coarse)`.
  - **CAN prove**: the frame carries coarse structure across ≥30% of itself and essentially no tile
    resolves anything finer than ~12 analysis px (~200 sensor px, ~5% of frame width).
  - **CANNOT prove**: *why*. A single frame cannot separate defocus from a soft subject, haze, a
    fogged converter, or isotropic shake. The OSD therefore says **`SOFT`**, never `TOO CLOSE`, and
    carries **no** `→ <lens>` suffix on this path — that suffix is a distance remedy and would
    smuggle back the causal claim. Only `AF_LIMIT` may say `TOO CLOSE → 1×`. The separator is
    **U+2192**, not the U+25B8 triangle it shipped with: **none of the three bundled Inter faces
    (`app/src/main/res/font/`) carries U+25B8**, so that glyph fell back to a system typeface inside
    one OSD tag. Every user-facing literal must stay inside those faces — the covered set in use is
    `§ © ° · ± × γ — … → ∞ ≈`.
  - **Deliberate design facts** (each cost a wrong turn to find): curvature, not gradient — a ramp
    is locally linear at every scale, so a first-difference ratio returns exactly `1/k` on a sky
    gradient (guaranteed false fire), while curvature is identically zero there. **No noise
    subtraction** — a per-frame noise floor estimated from the tiles IS the real fine content on a
    sharp uniformly textured frame, which reproducibly false-fired on an in-focus scene; without it,
    white noise lands in every lag equally and RAISES `R`, so grain suppresses the detector by
    construction. Lag **32** is load-bearing: capped at 16, modelled ~9 cm defocus came out
    UNJUDGEABLE. The metric takes **no `lut` parameter** so the digital-gain display LUT cannot
    reach it — an optics verdict must not move with a brightness simulation.
  - **Known MISSES, accepted**: a dark/grainy preview (noise suppresses it, and the exposure gate
    refuses above 16× the handheld rule anyway), a featureless subject (unjudgeable), video-P /
    flash-P (no readback), and anything softer than ~5% of frame width.
  - Admission adds: MANUAL focus, mid-`SCANNING`, recording/starting, an active zoom gesture, and
    statistics older than 1 s all refuse, and each refusal **resets** the 700 ms hold — which is why
    the settle wait after an AF scan needs no second timer.
- **The lens rail is ENUMERATED, never the PMA110 lens set (2026-08-02, user-reported).** The
  viewfinder rail and the Lens menu rendered `LensChoice.entries` (0.6/1/3/10×) unconditionally, so
  a single-camera device offered framings it could not reach — device-seen on a Lenovo TB336ZU
  (one 26 mm-equiv back lens, `zoomRatioRange` 1.0–8.0): 0.6× sat below the zoom floor and 10×
  above the ceiling, and tapping 0.6× left the wire zoom at 1.0. `LensInventory`
  (`camera/CameraState.kt`, pure + host-tested) resolves availability by ENUMERATION —
  optical when a back lens's measured 35 mm-equivalent is within ±35% of the preset target, else
  reachable only if the photo-home route's advertised zoom range covers the preset ratio — and the
  engine publishes it once on `setupExecutor` (`onLensInventory`). PMA110 keeps all four (all
  optical, pinned by test). A preset reachable only by zoom is spoken as "3× zoom", never "3× lens",
  and a rail left with a single preset renders nothing (pinch still covers the range). The Optics
  focal caption follows the same honesty: the round literal survives only within 10% of the
  measured equivalent (PMA110's ~69.4 mm 3× still reads "70 mm"; the tablet's main reads "26 mm").
- **Aspect ratio is only 4:3 or 16:9.** The sensor is 4:3-native: `AspectRatio.W4_3` = full readout
  (no crop, the default + the no-crop sentinel), `W16_9` = its center crop. Full/1:1/portrait removed.
- **The teleconverter's MAGNIFICATION is a setting, not a constant — and never a detection
  (2026-07-26).** The setting is a PAIR, asked as two dropdowns in the Lens tab: `PhoneModel`, then
  the `TeleconverterProfile`s that clamp onto it (`PhoneModel.converters()` = that phone's kits plus
  the fits-anything generics and CUSTOM). A flat chip row listing every brand's converters at once
  was the first shape and the user rejected it on device — optics that cannot mount on your phone
  should not be offered beside the ones that can. `camera/Teleconverter.kt` owns both enums, the
  five first-party kits (Hasselblad 300 mm = 300/70 ≈ 4.286× on the X9 Ultra; Hasselblad 230 mm =
  230/70 ≈ 3.29× on the X9 Pro; ZEISS 200 mm = 200/85 ≈ 2.35× on the X200 Ultra AND the X300 Ultra;
  ZEISS 400 mm = 400/85 ≈ 4.71× on the X300 Ultra), the generic 1.5/2/3× clip-ons, CUSTOM, and the
  pure helpers (`effectiveMagnification`, `normalizeMagnification`, `detectPhone`,
  `defaultConverterFor`, `reconcileConverter`, `effectiveFocalMm`) plus
  `teleDisplayBase(magnification)` — the function that REPLACED the top-level `TELE_DISPLAY_BASE`
  val. `TELECONVERTER_MAGNIFICATION` survives there as the Explorer default and as the anchor every
  older test and document quotes. A kit's `magnification` derives from ITS OWN host phone, never the
  selected one: moving glass to another body does not regrind it. Consumers read `CameraUiState.teleconverterMagnification` /
  `.teleconverterFocalMm`, so the profile and its custom value can never drift apart, and the old
  hardcoded `300f` / `"300 mm"` readouts (OSD, EXIF, Fn tile, MR summary, lens caption) are derived.
  Five rules hold this together:
  1. **Passive glass cannot announce itself; the PHONE can.** There is no contact, no ID, no optical
     trick that tells us a converter is mounted or which one — so the converter dropdown is pure
     declaration. `detectPhone` resolves `Build.MODEL` and only preselects the phone dropdown, and
     the caption may therefore say "Detected OPPO Find X9 Ultra." but never "detected a converter".
     `phoneModelDetected` must be RE-DERIVED (`phone == detectedPhone`) on every write of
     `phoneModel`, not latched at seed time: a latched flag survives the user overriding the
     dropdown and makes the caption claim "Detected Other phone." — a detection the app never made.
     Picking the real phone back re-claims it.
  2. **An UNRECOGNISED phone seeds `PhoneModel.OTHER`, not the Find X9 Ultra (2026-08-02).**
     `DEFAULT_PHONE_MODEL` remains the Explorer host for the state default, but `seedPhoneModel`
     now resolves `detectPhone(...) ?: OTHER`: leaving the default standing on foreign hardware
     showed a Lenovo tablet owner "Phone: OPPO Find X9 Ultra" with a Hasselblad 300 mm kit their
     device cannot mount. OTHER declares no host tele, so its converter focal multiplies the
     MEASURED lens the route would actually use (`LensInventory.teleHostEquivMm`) — a generic 1.5×
     on that tablet's 26 mm lens reads 39 mm, not the 105 mm the 70 mm assumption produced. Named
     kits still derive from their own declared host phone.
  3. **This and `DeviceProfile.resolve` are the ONLY places in the codebase that key off a model
     string** (the second sanctioned 2026-08-01 for measured HAL quirks), and this one may only
     choose which entry starts selected. Everything else still resolves hardware by ENUMERATING Camera2
     capabilities (`CameraSelector2` picks by measured equivalent focal, never by id). No
     capability, route, or request decision may branch on a model string.
  4. **`TELE_MAX_DISPLAY_ZOOM` (60×) stays FIXED and does not scale with the converter.** It caps
     TOTAL magnification, so the local-zoom ceiling (`TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(m)`)
     widens as the converter weakens — a 2× converter earns nearly the full 1–10× digital range,
     and ordinary capability reconciliation clamps whatever the lens cannot actually deliver.
  5. **A preset's PRODUCT NAME is not a focal length.** Each magnification is written as (converter
     focal ÷ the host tele focal it was designed for) so the arithmetic is auditable against the
     maker's printed figure — and host focals differ: the Hasselblad kits target a 70 mm periscope,
     the ZEISS ones an 85 mm. A "ZEISS 200" on THIS phone's 70 mm lens is therefore **165 mm**, not
     200. Labels keep the sold name so a user recognises their own glass, but the caption, OSD, and
     EXIF must always report `effectiveFocalMm` for the ACTIVE lens. Repeating the product number
     would write a false focal into saved files. Tests pin both the published figure (tolerance
     `1e-2`, because makers TRUNCATE — 230/70 = 3.2857 is sold as "3.28×", and a ±5e-3 band fails)
     and the exact ratios at `delta = 0`, which is what catches a preset that adopted a sibling's.
  6. **A phone with TWO official converters offers both, and defaults to the BASE one.** The vivo
     X300 Ultra takes the 200 mm and the 400 mm, so its narrowed list carries both — but
     `defaultConverterFor` is `firstOrNull { it.phone == phone }`, which returns the base kit only
     because `ZEISS_200_X300` is DECLARED BEFORE `ZEISS_400`. That ordering is load-bearing and
     invisible, so a test pins it: reordering the enum must fail rather than silently default those
     users to the exotic 4.7× optic. `reconcileConverter` keeps a generic/custom across a phone
     change and replaces a foreign kit, so no selection can outlive the phone that offered it —
     the two persisted keys are independent, and the UI must never be handed a converter its own
     narrowed dropdown does not contain.
  Changing magnification changes that ceiling, so both action handlers re-normalize the live zoom
  and reset `ZoomGlideState.pendingRatio` AND `easeTarget` — an in-flight ease target is an ABSOLUTE
  number in the OLD scale (same trap as a mode flip or lens preset).
- **Saved-file EXIF labels come from the BUILD and from MEASURED focal length, never from literals
  or camera ids (2026-07-28).** `camera/DeviceExifLabels.kt` owns `exifMake`/`exifModel`/
  `deviceLabel`/`lensNameForEquiv`/`exifLensModel`, all pure and unit-tested. Before this, `TAG_MAKE`
  /`TAG_MODEL` were the literals `"OPPO"`/`"OPPO Find X9 Ultra"` and the lens description keyed off
  camera ids `"2"/"3"/"4"/"5"` with `else -> "tele"` — on any other handset that wrote a FALSE camera
  model and a FALSE lens name into the user's files, and stamped one phone's marketing focal band
  onto a shot taken elsewhere. `TAG_MODEL` is the model IDENTIFIER by definition (photo software
  resolves the marketing name from it), so imitating the stock app's market name was wrong in
  principle as well as off-device. Make/model are carried on `ExifShot` rather than read inside
  `exifAttributeList`, because that formatter is covered by plain JVM tests with no Android
  framework. A blank build field OMITS its tag instead of writing empty.

- **Front (selfie) camera is a first-class optics door with BASIC scope (2026-07-22; mirror roles
  device-diagnosed 2026-07-23; capture-ROTATION sign device-bisected and verified 2026-07-25).**
  `CameraEngine.setFrontCamera` is a full generation-owned
  transaction (never a transaction-less close/open): entering FRONT forces the teleconverter off in
  the same publication, resets zoom to front-lens-local 1×, and reconfigures onto
  `CameraSelector2.pickFront` (enumerated LENS_FACING_FRONT, plain-id-preferred, largest array on
  tie — expected id "1" on PMA110 but NEVER hardcoded; opened plainly, no physical routing).
  Leaving returns to the rear mode-home (`resolveNonTeleId`). Lens presets and the TC toggle REFUSE
  while FRONT (`backOpticsDoorRefusal`, one seam for engine + VM); MR recall/settings restore EXIT
  front atomically (`setResolvedOptics` sets facing=BACK — recalled packets are rear-route optics);
  facing is never persisted (fresh launch is always BACK — the app exists for the rear tele).
  **MIRROR ROLES ARE INVERTED from the naive design (device-diagnosed 2026-07-23): this front HAL
  PRE-MIRRORS its SurfaceTexture stream.** The `frontStreamPreMirrored` trace proved the flag
  reaches the GL thread while the selfie still read unmirrored — our texcoord mirror was CANCELING
  the stream's own. So the PREVIEW draw adds NO mirror (the pre-mirrored stream IS the
  selfie-mirror view), the ENCODER/ANALYSIS draws apply the x-inversion (`gl/texCoordQuad` →
  `mirrorX`) to write the TRUE scene into files, stills are untouched HAL buffers (correct either
  way), and tap-AF needs NO un-flip (displayed x == texture x; `mapTapFocusGeometry(mirrorX=false)`).
  Pushed as route state by `applyStabilization` (`gl.setFrontStreamPreMirrored`). On a multi-device
  build this inversion becomes a DeviceProfile quirk flag. Capture rotation FRONT =
  `(sensor + device) % 360` — the gravity term is GyroEis CCW-POSITIVE, so it ADDS on the front and
  SUBTRACTS on the rear; the old `− device` here was the same sign error that saved every
  landscape-held REAR still 180° rotated (device-bisected 2026-07-25). Afocal never applies
  (`RotationMath.captureRotationDegrees(..., frontFacing)`); preview rotation stays 0. RAW,
  hi-res, flash, and the Loupe Overview all resolve off the existing capability/route axes — no
  facing special cases in those predicates.
- **Video caps come from the device, not hardcodes.** `video/EncoderCaps.kt` scans `MediaCodecList`.
  Only **HEVC + AVC** are offered (both HW). **AV1 was removed** (the only AV1 encoder here is SW
  `c2.android.av1.encoder` — too slow/low-res to ship). **APV** (`VideoCodec.APV`, HW
  `c2.qti.apv.encoder`) is defined but **intentionally EXCLUDED**: Android's MediaMuxer (API 36) rejects
  APV in an MP4 container (device-verified — it errors the encoder mid-drain). Resolutions come from
  the selected camera's `StreamConfigurationMap`, with the shipping selector capped at 3840 pixels
  wide; PMA110 tops out at 4K UHD in the UI. Standard and NTSC drop-frame rates are gated against the
  selected size. High-speed 120 fps is excluded because its constrained session crashes this HAL.
- **PROPRIETARY / LICENSED HDR FORMATS ARE OUT OF SCOPE — do not add them, do not probe for them
  (owner decision 2026-08-04).** The video ladder is HEVC and AVC only. Some vendor encoders on this
  SoC advertise trademarked HDR formats and the platform muxer will accept them, so this is
  reachable — which is exactly why the rule is written down rather than left to judgement. Two
  independent reasons stand:
  1. **Honesty.** Camera2 supplies the ISP's display-referred, already tone-mapped rendition: 8-bit
     standard input for photo/SDR video, or an HLG10 still-less input for non-SDR video. A
     proprietary HDR-branded container would still assert latitude/format rights this pipeline
     cannot back — the same rule that keeps HLG and the log profiles described as display-referred
     mappings rather than recovered HDR.
  2. **Licensing.** Those formats are trademarked and their use is governed by agreements between
     the format owner and chip/device makers. A third-party app riding a device's implementation is
     a separate permission question that public documentation does not answer, and the owner's
     decision is not to touch it.
  The former exploratory probe and its findings were REMOVED from this repository on 2026-08-04, and
  the encoder inventory no longer detects or names such formats. Do not reintroduce either; if the
  question is ever reopened it starts with a licensing answer, not a probe.
- **Settings persist across launches** via `storage/SettingsStore.kt` (SharedPreferences, enums by
  name, defensive load). Gated by a "Remember Settings" toggle that **defaults ON**; saved on
  background, restored on launch (pushed to the engine pre-start). Fresh launch defaults to the 1×
  main lens with TELE off; separate default-on Setup toggles preserve the last lens selection and
  TELE mode when restoring saved settings.
- **Log = GL S-Log3 / S-Log3.Cine / LogC3; the native log key is INERT for third-party Camera2
  (settled 2026-07-09).** `com.oplus.log.video.mode` is advertised in the tele's request+session
  keys and the HAL ACCEPTS it ("applied" logs), but it changes NOTHING a third-party session can
  see: with the key set (as session parameter + on every request), the preview AND the recorded clip
  stay display-referred 709 — tested with both `TEMPLATE_PREVIEW` and `TEMPLATE_RECORD` repeating
  requests on device, judged in a lit scene. (Earlier "the file recorded as log" was the BT.2020
  full-range container tag being misread by players as a washed look; an "applied" log line only
  means the HAL didn't reject the key.) So the log profiles bake standard curves in GL
  (`LogProfiles.kt` single-sources constants into `Shaders.kt`): BT.1886 2.4 decode → linear
  BT.709→gamut 3×3 (S-Gamut3 / S-Gamut3.Cine / ARRI Wide Gamut 3, D65→D65 so rows sum to 1) →
  defensive −0.0099 floor → the S-Log3 (uTransfer=2/4; 18 % grey → 420/1023 ≈ 0.4106) or LogC3
  EI800 (uTransfer=5; 18 % grey → ≈ 0.3910) OETF. Like O-Log2 before them these are
  display-referred SDR-source curves, NOT scene-referred camera log — the ISP has already
  tone-mapped the stream and no highlight latitude is recovered. The encoder gets the curve, the
  preview renders it flat, and **Gamma Display Assist** shows the normal display-referred image
  instead (assist = skip the forward curve; the file always gets it; `ColorTransfer.isLog` is the
  one gate for all three). The user-facing O-Log2 option (`ColorTransfer.LOG`) was REMOVED
  2026-07-22 ("not a standard"); SettingsStore migrates a persisted `"LOG"` to `SLOG3_CINE`, and
  its forward OETF left `Shaders.kt` with it. **The dormant native-log plumbing that survived that
  removal is now GONE TOO (2026-08-04)**, because the vendor-SDK path it was waiting for was
  declined: the O-Log2-shaped de-log shader branch (`uTransfer == 3`) with its `olog2Inv`/`toRec709`
  helpers and toe constants, `shaderTransferCode`'s `delogAssist` parameter, `VendorLogMode`,
  `CameraEngine.vendorLogMode`, `GlPipeline.setNativeLog`, and `CameraController`'s
  `com.oplus.log.video.mode` + `com.oplus.VideoColorBT709` request keys.
  **The `nativelog` FLAG FILE itself survives, and this bullet used to claim it did not (corrected
  2026-08-05).** What went is the native-LOG plumbing that read it; the file is still the gate for a
  SEPARATE debug-only 10-bit experiment — `CameraEngine.tenBitExperimentEnabled()` reads
  `getExternalFilesDir(null)/nativelog` and is `BuildConfig.DEBUG &&`-guarded, so release builds
  always read false. Either delete that gate too or leave it; do not re-delete it from the DOC while
  the code keeps it. **Shader code 3 is now permanently VACANT and the surviving codes keep their
  numbers** (0/1/2/4/5) — renumbering would silently re-map every branch and the shader's own
  `uTransfer == N` comparisons; a test asserts the gap stays unused. Do not re-add any of it.
  NOTE: leaving `KEY_COLOR_TRANSFER` unset on a BT2020 full-range HEVC format makes the QTI encoder
  tag the VUI **ST2084 (PQ)** — players then tone-map log footage as HDR. Tag a transfer
  explicitly, always; all three log profiles share one container policy (BT.2020 full-range +
  explicit SDR-class transfer). A trademark footnote for Sony/ARRI sits in the Setup tab.

- **Video stabilization = HAL OIS+EIS via the device's own path (verified 2026-07-07).** For VIDEO the
  shutter is fixed (e.g. 1/60 s), so per-frame MOTION BLUR is set by the shutter and only **OIS**
  (which moves the lens DURING the exposure) can cut it — app-side gyro EIS only warps whole frames
  and cannot de-blur. The HAL exposes the vendor int `com.oplus.video.stabilization.mode`
  (0x8119009e) alongside the standard key, and applies the right OIS/EIS profile for the active lens. The tele advertises standard `availableVideoStabilizationModes = [0,1,2]`
  (OFF/ON/**PREVIEW_STABILIZATION**) and both `videoStabilizationMode` + the vendor int are in its
  request+session keys. So we **no longer force video-stab OFF**: `VideoStabMode { OFF, STANDARD
  ("Standard"), ENHANCED ("Active") }` sets `CONTROL_VIDEO_STABILIZATION_MODE` on the repeating
  request (+ the vendor int mirror). **Device-verified: result metadata `ois=1`, `vstab=2` — OIS
  physically engaged at 1/30 s, preview + 4K recording fine.** App-side gyro EIS is **disabled**
  (`CameraEngine` seeds `gl.setEis(false, 0f, 0f)`); its sensor helper remains for level and capture
  orientation, but there is no user-facing `GYRO` mode. The Explorer-specific `com.oplus.ois.*` /
  `eisrealtime` tags remain gated — but the generic HAL video-stab is enough.
- **TC session type 0x80b4 is ACCEPTED by the HAL (device-verified 2026-07-14) — the CameraUnit
  bypass.** Passing the stock app's TC operation_mode (0x80b4, captured from CamX
  `configure_streams`; stock pairs it with sensor mode 48 = the 300 mm TC OIS profile) as the
  SessionConfiguration sessionType on the standalone 3× camera configures a FULL session
  (fallback=0, stills+RAW alive, HEIC/DNG/4K-HEVC all verified, ois=1/vstab=2, clean 0x0 return
  on TELE off) — no AUTH_CODE needed. Lint WrongConstant is suppressed on configureSession with
  justification. UNVERIFIED: whether the OIS profile actually differs at 300 mm (needs a physical
  shake A/B with the converter mounted); result metadata reads identically either way.
- **200MP remosaic is NOT exposed to third-party Camera2 (probed 2026-07-22).** `dumpsys
  media.camera` (116k lines, full characteristics for all 7 HAL devices): every camera's
  `pixelArraySize`/`activeArraySize` IS the binned ~12.5 MP readout (4080×3064 / 4096×3072), the
  `ULTRA_HIGH_RESOLUTION_SENSOR` capability appears nowhere, `pixelArraySizeMaximumResolution` has
  no values (key-index mention only), and no stream configuration anywhere exceeds 20 MP. The
  200 MP full-res path lives behind the stock app's private CameraUnit stack. The capability-gated
  Hi-Res feature (e943807) is therefore DORMANT on PMA110 — its toggle appears only if a future
  firmware or a different device advertises a hi-res size; do not expect it here and do not
  re-probe without cause. On a capable device: hi-res rides the PREPENDED ladder rung (RAW forced
  off on that one attempt; a rejected hi-res session falls back to full-with-RAW), fast commits
  compare intent against the CONFIGURED session (`hiResConfigured`), the still saves via the
  EXIF-orientation-only passthrough-JPEG lane, and the `HR` OSD tag keys on accepted-session truth.
- **300 mm teleconverter OIS would depend on OPPO CameraUnit, which is NOT integrated and NEVER
  WILL BE — the authenticated path was DECLINED by the owner 2026-08-04. Do not re-add the SDK,
  do not re-probe, do not plan around it.** The 4.3× teleconverter
  stabilization profile appears to use CameraUnit extension parameters that are not exposed through
  raw Camera2 request/result keys. What the app actually applies is the public Camera2 overlap
  (`com.oplus.camera.mode=40`, `com.oplus.original.zoomRatio` 4.286×) plus the vendor 0x80b4 TC
  session type — **none of which needs an AUTH_CODE**, and none of which is affected by this
  removal. The `com.oplus.ocs` dependency (camera 1.1.0 + base 1.0.16 and its
  base-auth/base-internal transitives), the OPPO OpenCapability maven repo, and the debug-only
  `OcsProbe` availability check were deleted on 2026-07-25 (`2b4bc55`, `c27744c`) — the CODE went
  first, the owner's decision that closed the option for good followed on 08-04 — because the probe's
  answer was a CONSTANT of the
  missing AUTH_CODE, not of the device: it returned `errorCode=1004` (AUTHCODE_EXPECTED) on every
  run and could never report anything else without an OPPO developer registration — which is itself
  the prerequisite for re-adding the SDK. For that constant it cost ~32 ms of DEBUG cold start
  (device-measured 2026-07-25, proc-start → `configure_streams` END, median of 4 runs each: 527 ms
  with the SDK, 495 ms without). Do NOT re-quote the release-vs-debug gap (354 ms vs 535 ms) as the
  SDK's cost — that number is debug-BUILD overhead in general and the SDK is only a small part of
  it; the isolated A/B above is the honest figure. The decisive cost was not milliseconds but the
  200+ log rows it emitted inside the cold-start window, which blew the ColorOS log quota (see that
  bullet below) and silently ate our own `StartupTrace` instrumentation — a measurement tax that
  corrupted measurement. Unlike the 200 MP entry above, this is no longer merely "do not re-probe
  without cause" — the option itself is CLOSED. The former re-enable checklist was deleted along
  with the decision (a step-by-step restore order under a declined item is how a closed decision
  quietly reopens itself); the optional private `docs/BACKLOG.md`, when present, carries the
  historical closure, while this bullet is the committed authority for what it rules out for good:
  the 200 MP remosaic, a scene-referred log stream, and this OIS profile. The dormant code kept
  alive for that future — the de-log shader branch and the `vendorLogMode`/`setNativeLog` plumbing —
  was REMOVED on 2026-08-04 in the same pass; see the log-profile entry for the exact inventory.
- **The camera-control button: slides arrive as STANDARD `KEYCODE_ZOOM_IN`/`OUT` (live-verified
  2026-07-09).** Full mechanical press = standard `KEYCODE_CAMERA` (→ shutter, `onKeyDown`). The
  capacitive slide is re-emitted to the FOCUSED app as **KEYCODE_ZOOM_IN (168) / KEYCODE_ZOOM_OUT
  (169), repeating ~20 Hz** while the finger slides — a one-off earlier capture showed OPPO codes
  767/769/782 instead (config-dependent; both families are handled, `KEYCODE_FOCUS` too). The
  **light-press (half-press) IS delivered — as OPPO keycode 767 (device-measured 2026-07-31,
  three presses, three exact DOWN/UP pairs ~3 ms apart, zero repeats).** The earlier "not delivered"
  conclusion was an artifact of two traps at once: (a) pressing while LOCKED launches the STOCK
  camera, whose own `registerKeyEventInterceptor` ({765,766,768,770,771,772,781,782} — note 767 is
  NOT in that list) consumes the event before any third-party app exists to receive it, and (b) our
  handler had 767 registered as a speculative SLIDE-IN alias from a one-off earlier capture, so
  every delivered half-press was silently eaten as a zoom nudge — the misroute made the key look
  dead from the UI. 767 now routes to the half-press family (FOCUS/782 stay as siblings), verified
  end-to-end: each press fires the assigned action (AF-ON: request-generation bump + afState
  scan→FOCUSED) with effZoom bit-identical across presses. The half-press SHUTTER binding carries
  the same denial-recorded audio drop as the full-key path. **The OPPO quick/action button reaches
  the app as INJECTED keycode 781** (device-measured 2026-07-31: the physical press is
  KEYCODE_ACTION_BUTTON_CLICK scan 735, intercepted by StrategyActionButtonKeyLaunchApp, re-emitted
  781 with isInjected=true): it is a third reassignable binding (`quickButtonAction`, default
  SHUTTER, persisted, Setup row "Quick Button") with the same denial-recorded audio drop. The discrete ~20 Hz repeats stutter if applied 1:1: `onHardwareZoomStep` moves a TARGET and
  a ~30 Hz ticker glides `zoomRatio` toward it (log-space exponential), like a powered zoom rocker.
  Full/half actions are a configurable `HardwareKeyAction` system (reassignable in Setup, persisted).
  **`adb input keyevent` injection does NOT reach the focused app** — only a physical press; verify
  button behavior on-device.
- **The horizon level holds its angle when the phone points steeply up/down.** Roll comes from
  `atan2(gravity.x, gravity.y)`; near-vertical the in-plane x/y → 0 and it's pure noise, so the level
  spun. `GyroEis` only updates the roll when `hypot(x,y) > LEVEL_GRAVITY_THRESHOLD` (~2.5), else holds
  the last confident angle (same idea as the discrete-orientation `FLAT_GRAVITY_THRESHOLD` guard).
- **GlPipeline drops anything posted before `start()` — re-seed GL state in the start callback.**
  `GlPipeline.post` is `handler?.post`, silently a no-op until the GL thread exists. Any GL state set
  during settings restore MUST be re-applied inside the `gl.start` callback in `CameraEngine`.
  Log transfer, AE metering, and gamma assist are re-seeded there; renderer-only assists live in one
  `RendererConfigStore` snapshot and the complete snapshot is replayed for every GL generation. Symptom when
  missed: "works only after the first recording pushes it" (the log-preview bug).
- **Cold startup is GL-first and latest-intent owned.** Start the GL generation before blocking
  Camera2 selection/capability preflight; when its input arrives, resolve the latest desired optics
  generation. A stale startup result must never roll back or publish over a newer route, and transient
  preflight failure uses the bounded retry gate while the preview surface remains live.
- **Every session reopen owns a complete optics generation.** Snapshot the desired override and
  transaction before clearing Ready, recheck it on `setupExecutor`, and converge through
  `reconfigureCamera`. Never restore a transaction-less close/open shortcut: it can pair an outgoing
  selection/capability snapshot with a newer mode/lens generation.
- **Ready binds controller, generations, and accepted still outputs atomically.** Every optics intent
  publishes Not-Ready with its desired generation. Only the synchronized terminal commit may install
  the Ready controller, exact session generation, actual processed/RAW reader mask, and Ready bit,
  after rechecking ownership and pause state. A rejected same-route terminal commit converges through
  reconfiguration only while its optics intent is still current; superseded work is a no-op. Every
  Ready/Not-Ready publication has a monotonic sequence, and the ViewModel rechecks that sequence inside
  its StateFlow reducer so an older Ready event cannot overwrite newer Not-Ready state.
- **Camera-health errors belong to the installed controller identity, not an optics generation.** A
  still-installed controller retains its error/disconnect authority across same-controller fast
  commits and pending optics generations. A replaced controller's late callback is inert. An owned
  fault advances the session generation, invalidates Ready and accepted outputs, claims any recorder,
  then reports and schedules bounded recovery exactly once.
- **EGL output release is checked unbind → destroy.** Move current ownership to a surviving preview
  output or to no surface before destroying an outgoing preview/encoder EGLSurface. Codec teardown
  requires verified current-ownership release plus either destroyed outputs or checked terminal EGL
  display teardown; never report successful completion without that proof. Encoder replacement,
  failure, stop, and final GL release use the same order, with terminal EGL reset as the fallback when
  an individual output cannot be proven destroyed.
- **Preview EGL health is part of Camera Ready.** Preview create/bind/init and runtime draw/swap
  failures complete one Surface/generation-owned signal. The Engine accepts only the current owner,
  publishes Not-Ready, and retries the same surface at most three times before terminal status; a
  stale replacement failure is inert. A draw/swap failure whose preview DETACH also fails applies
  the acquisition branch's containment: fail an active encoder owner, orphan the poisoned preview
  EGL surface (destroyed later under the checked orphan sweep), and abandon the frame — never
  retain a poisoned owner for ordinary same-surface retries while frames keep flowing to the
  encoder. A successful bind remains pending: Ready and retry-budget reset
  happen only after that owner completes its first successful real-camera-frame swap; cached-frame
  zoom redraws cannot publish Ready. Before every texture update, bind the live preview owner or
  otherwise the active encoder owner; contain acquisition failure inside the owning health path so
  preview loss cannot freeze an otherwise healthy active recording.
- **REC readiness comes from the first successful real encoder swap, not surface allocation or
  `VideoRecorder.start()` returning.** Candidate create/bind/restore remains pending until a real
  camera frame draws, presents, swaps, and restores preview ownership. Queue attach before recorder
  publication and consume its exactly-once `Result`. Recording admission
  snapshots the accepted Camera2 controller/session before mic handoff and atomically rechecks it at
  publication. Until attach succeeds the UI is stoppable/locked but shows no tally or timer. An owned
  Camera2 failure claims and ordered-finalizes the recorder before recovery; do not let camera errors
  leave phantom REC/audio/UI state. Only the in-flight latch/topology reservation and optimistic
  "starting" UI are synchronous on the caller. The recorder executor snapshots the accepted session
  and process admission, then dispatches pending-row insert/registration to the process-wide finite
  pre-native allocator (two workers + four backlog slots) under an already-armed deadline; it does
  NOT perform that MediaStore insert itself. Stop/pause/release/timeout retire the attempt without
  waiting for an uncancellable provider call, and a late row can only enter durable cleanup/recovery.
  A successfully claimed row returns to the serial recorder executor for the ≤400 ms standby-mic
  handoff and codec/muxer construction. A stop arriving anywhere mid-admission is LATCHED by
  `RecordingAdmissionLatch.requestStop()` and consumed exactly once by
  `RecordingAdmissionLatch.completeAdmission()` when admission publishes or refuses — never raced
  against an unpublished owner.
- **The MediaCodec input Surface has exactly one release owner.** `VideoRecorder` releases it on every
  partial setup failure and, on clean stop, only after the engine's checked EGL detach has completed;
  Surface release precedes codec release and ownership clearing, and repeated cleanup is a no-op. If a
  drain thread remains alive after its timeout, deliberately abandon the native graph: do not call
  `Surface.release`, codec/muxer release, or fd close while that thread may still be inside native code.
- **Camera2 control values are capability-normalized before UI and request publication.** Focus, WB,
  AE/flash, antibanding, edge, noise reduction, effect, and metering choices resolve to exact advertised
  values. Apply AE and AF metering regions independently only when each advertised maximum is positive;
  a zero maximum means omit that request key. Same-route settings/MR recall must normalize one complete
  packet against the installed route before its terminal commit and publish caps reconciliation before
  Ready; structural recall waits for target-route caps, never outgoing caps. The recalled phone,
  converter/profile/custom value, and host focal are one declaration inside that generation-owned
  packet: synchronous refusal mutates none of it, owned async failure restores all of it, and a
  superseded rollback restores nothing. Restored settings and every live update must show the value
  the selected camera can actually apply.
- **Settings, Fn cycles, and quick rulers share one capability projection.** Build visible choices and
  entry flags from the exact AE/AF/AWB, antibanding, edge, noise-reduction, effect, flash, manual/range,
  and AE/AF-region facts used by request normalization. Filter ProSheet choices, cycle only inside the
  projected lists, and require exact OFF modes/ranges before opening a manual ruler. The WB Fn chip may
  open the preset sheet when multiple advertised modes exist even if a Kelvin ruler does not; MANUAL
  WB still requires that ruler. Custom WB is enabled only in advertised, unlocked AUTO and consumes a
  later converged result from its exact tagged request—never cached preset/manual gains. Its accepted
  Ready-session owner is rechecked atomically after the callback crosses to main. If a route change
  invalidates an open ruler, close it and retain the normalized applied value.
- **A DECLINED microphone still records — video-only, silently (2026-07-28).** Declining at the
  rationale ("Not now") or at the system dialog turns audio off and then STARTS the take the user
  asked for; only an explicit "enable audio" that was declined stops at audio-off. `VideoRecorder`
  already handles it (`doAudio = recordAudio && hasRecordPermission()` → `expectedTracks = 1`), so
  refusing the press withheld a clip the pipeline can fully deliver — and it stranded anyone who
  simply never wants audio, because turning `recordAudio` off is exactly what makes the NEXT press
  skip the prompt, making a decline a two-press ritual whose first press vanished behind a transient
  status line (device-verified on a fresh install: "Not now" returned to an idle viewfinder having
  recorded nothing). Audio is disabled BEFORE starting so the UI toggle and the recorder's own
  permission gate agree; `_state` updates synchronously, so the start observes it. Both decisions are
  pure and unit-tested in `CameraPermissionPolicy.kt` (`microphoneDeclineOutcome`,
  `microphonePermissionRequired`) — `PendingAudioAction` lives there, not in `MainActivity`.
  **A denial-disabled audio track is RESTORED by a later grant; operator-chosen silence is not
  (2026-07-29).** `recordAudio = false` conflated "this operator wants a silent clip" with "we gave
  up on audio because the permission was refused", and the second was SELF-LOCKING: a
  START_RECORDING prompt is conditional on `recordAudio` (`microphonePermissionRequired`), so once
  audio was off the shutter never asked again, and granting the permission in Android Settings
  changed nothing. Both user-visible symptoms came from that ONE flag — silent clips
  (`doAudio = recordAudio && hasRecordPermission()`) AND a missing level meter during recording (its
  UI gate is `mode == VIDEO && recordAudio && (detailsVisible || isRecording)`), which reads as two
  separate bugs and is one. `AUDIO_OFF_BY_DENIAL_KEY` now records WHICH of the two it was:
  `audioRestoredByMicrophoneGrant` re-enables audio the moment the permission is observed granted,
  while an operator who switched audio off themselves keeps their silence forever. This mirrors what
  CAMERA already did — a Settings grant there resets the denial history rather than letting an
  obsolete refusal outlive itself. The recorder's own level emission is fine: non-unity gain is
  applied allocation-free to every PCM buffer that needs it, while RMS construction runs only when
  a non-null meter callback exists and the 100 ms emission cadence is due. Gain does not imply a
  discarded per-buffer level pass.
  **The app requests CAMERA, RECORD_AUDIO, and (since 2026-08-01, user decision) the visual-media
  READ trio — READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_VISUAL_USER_SELECTED — the last
  requested CONTEXTUALLY at an empty-gallery tap only, because a REINSTALL clears MediaStore row
  ownership and a permission-less app cannot see its previous install's captures on a spec
  provider. Declining keeps own-rows-only behavior; a partial "Select photos" grant counts as
  access (`hasVisualMediaAccess`). There is still NO location permission and no
  location code, so captures carry no GPS tags** (verified by parsing a saved HEIF's TIFF IFDs: no
  GPS IFD pointer in IFD0 or ExifIFD). Adding geotagging would be a new permission, a new Data
  Safety declaration, and a privacy-policy change — a feature, not a fix.
- **Exactly one owner of the mic.** The Sony-style standby audio meter is a levels-only `AudioRecord`
  tap that runs while video is ARMED but not rolling. Its synchronized ownership gate reserves one
  immutable owner and release latch before thread start. REC must claim the handoff and observe that
  exact release before `VideoRecorder` opens AudioRecord; on timeout it refuses the attempt. Internal
  restart paths only recheck current intent, so they cannot overwrite a newer disable/background
  transition. Never add a second concurrent AudioRecord. While REC is running, a negative
  `AudioRecord.read` is AUDIO-terminal but NOT clip-terminal: the recorder degrades to VIDEO-ONLY
  (`degradeAudioToVideoOnly` — drops the audio expectation, zeroes the live meter, never touches
  the shared `firstFailure` latch) and the cleanly-muxed video still PUBLISHES via the
  `shouldStartMuxer`/`shouldPublishRecording` gates; only VIDEO codec/muxer faults delete the clip.
  A `muxer.stop()` throw over a degraded, sample-less audio track is tolerated
  (`muxerStopFailureIsTerminal`) so a mic dropped in the add-track window cannot delete a good
  take. Only a negative read after stop is treated as normal end-of-stream. The STANDBY meter classifies
  reads through the same `AudioReadPolicy`: zero retries, a negative read ends that AudioRecord
  generation (release exactly once — `n <= 0 → continue` used to hot-spin the meter thread forever
  on a dead route), then a bounded backed-off recreation (≤3 generations, reset by any successful
  PCM read) re-arms only while the standby intent still wants a meter.
- **Still watchdog follows the request exposure.** HAL-auto keeps the historical 8 s timeout. Manual
  and app-side/AEB requests use the exact sensor-clamped exposure plus an 8 s delivery margin, with
  ceil-to-milliseconds and saturating arithmetic; a fixed 8 s deadline is not valid for long shots.
- **CAMERA permanent denial requires completed request history.** A fresh install and an empty
  `RequestMultiplePermissions` result remain requestable. Persist only an actual false result, combine
  it with `shouldShowRequestPermissionRationale`, clear history on grant, and suppress automatic
  re-request only when Settings is genuinely required.
- **Processed stills preserve one shot-owned EXIF snapshot.** `Bitmap.compress` strips metadata, so
  `StillCapturePipeline` re-stamps JPEG through ExifInterface before publish. For HEIF it composes the
  same EXIF attributes into a cache-only JPEG seed, extracts the APP1 payload, and passes it to
  `HeifWriter.addExifData`; ISO / exposure / 35mm focal / make / model therefore stay in parity
  across both processed formats. Lightweight physical-lens metadata is prefetched on
  `setupExecutor`; the camera callback is cache-only and copies the processed Image before composing
  ancillary metadata.
- **Pending MediaStore rows have durable write states.** Every insert commits a `REGISTERED` journal
  entry before bytes are written; a fully closed encoder/muxer output commits `COMPLETE` before
  publication. If every `COMPLETE` commit attempt fails, the closed row fails **closed**: it remains
  `IS_PENDING=1`/`REGISTERED`, publication and destructive cleanup are both forbidden, and the UI
  reports a recoverable retained take rather than a saved file or data-loss failure. Relaunch
  recovery may then adopt JPEG/DNG/video/HEIF only after the format's structural probe proves it
  complete. Recovery also adopts durable `COMPLETE` rows, deletes only proven-invalid unfinished
  output, and leaves indeterminate rows pending. A transient publish failure likewise retains a
  finalized photo/video for recovery instead of deleting it. Partial family deletion restores only a
  resolver-confirmed survivor into review with retry copy; an already-absent sibling is successful,
  and only an unresolvable survivor falls back to a Gallery retry message.
- **DNG publication does not hold the camera callback.** `DngCreator.writeImage` and the durable
  `COMPLETE` marker attempt remain synchronous while the RAW `Image` is valid; `saveDng` returns a
  frozen `PendingDngPublication` carrying whether that commit succeeded. Only `publishDng`
  (including the durable gate, resolver retry backoff, and callbacks) leaves the camera thread.
  RAW-only SINGLE tails use one process-wide finite owner (two daemon workers + two backlog slots)
  shared across Engine generations; mixed-output and sequence drives preserve their processed-save
  ordering on `ioExecutor`. Capacity overflow, facade shutdown, queue rejection, or marker exhaustion
  settles the live capture family exactly once and keeps the structurally complete private row for
  launch recovery; provider work never falls back inline and complete DNG bytes are never deleted for
  lack of live publication capacity.
- **A logged `CameraAccessException` may have NO app frame in it — read the stack before believing
  the app did something (2026-08-09).** Rapid Photo↔Video / front-rear churn logs
  `E CameraCaptureSession: CAMERA_ERROR (3) ... Function not implemented (-38)`, which reads like an
  app fault and was filed as one. The stack is
  `stopRepeating ← CameraCaptureSessionImpl.close ← CameraCaptureSessionImpl$2.onDisconnected ←
  CameraDeviceImpl$9.run` — **entirely framework-internal**: on a device disconnect the framework
  closes the session, which calls `stopRepeating` on the device it was just told is gone, gets
  ENOSYS, then CATCHES AND LOGS ITS OWN EXCEPTION. `CameraCaptureSessionImpl` is not ours; this
  cannot be fixed app-side and does not need to be. Measured recovery is ~1.3 s to `ready=true`, and
  the app's own fault path never fires. **Do not "fix" it, and do not let it mask the line under
  it** — the real defect in that same log was `Long monitor contention ...
  TerminalAcquisitionGate.runIfOpen ... in isOpen() for 192ms` with waiter tid == pid, i.e. a
  MAIN-THREAD stall, which is what a user actually sees. `isOpen()` is now a lock-free `@Volatile`
  read (advisory only; the authoritative check is inside `runIfOpen`, and `close()` must still block
  behind an in-flight acquisition — both directions are pinned by tests). The general rule:
  `grep -c telecampro` the stack first; zero app frames means the platform is talking to itself.
- **ColorOS enforces a 300-row per-process log quota — diagnostics that log per frame destroy the
  diagnostics you actually need (device-observed 2026-07-25).** Past the cap the platform prints
  `LOG_FLOWCTRL: LOGS OVER PROC QUOTA(300) ... DROPPED` and silently eats everything else this
  process emits. Two live consequences: (1) `GlPipeline`'s `FrameGap` line logs only gaps **>200 ms**,
  not >50 ms — since the cycle-8 fluidity cap a dark preview runs at a DESIGNED 66.7 ms cadence, so
  the old 50 ms rule fired ~15 rows/s and spent the whole quota in ~20 s, after which it ate the
  startup trace and the focus-verdict trace outright; 200 ms still catches the ~180 ms
  `setRepeatingRequest` stalls and real wedges, and normal cadence is not news. (2) `StartupTrace`
  BUFFERS its marks and emits the whole cold start as ONE line at `finish()` — a requirement, not
  tidiness: per-mark logging gets silently eaten before it reaches logcat. Same rule for the
  `FocusConfidence` trace (change-gated + 2 s heartbeat). **Any new per-frame or per-tick log must be
  change-gated or thresholded.** (This quota is also what made the removed OPPO CameraUnit SDK's
  200+ startup rows decisive — see that bullet.)
- **Cold start is instrumented, and the measured budget is `resume → first camera frame ≈ 544 ms`
  (debug, 2026-07-25).** `camera/StartupTrace.kt` marks `openCamera → onOpened →
  createCaptureSession → onConfigured → previewRequestBuilt → firstCameraResult` against a
  `resume`-origin clock (resume is the earliest point the app itself owns). It is armed idempotently
  at the first line of `CameraEngine.resume` and DISARMED on every path that returns without a real
  open — an armed zero-mark trace was otherwise finished by the next ordinary `startPreview` rebuild
  and printed a fabricated number. **The earlier "~1150 ms of HAL bring-up" figure is RETRACTED**: it
  was an artifact of quota-dropped log rows (above), not a measurement — do not optimize against it.
  **A "camera starts slowly" report is a UI question before it is a camera one (owner-reported,
  device-bisected 2026-08-04).** The one such report resolved to the STATUS PILL, not the pipeline:
  `am start` 412 ms, session configured ~950 ms, yet the `"Starting camera…"` pill was still on
  screen 5.2 s later because `statusDisplayDurationMs` classifies by wording and this message fell
  into the neutral 2.5 s bucket. **A PROGRESS status must carry no timer** — it reports a condition,
  so an EVENT ends it (`CameraStatusLifecycle.PROGRESS` → null duration; the owned Ready publication clears it,
  guarded on the message still being that status so a message published during bring-up is not
  swallowed). The same rule covers reconfiguration, preview/camera recovery, and bounded retry
  conditions: Ready, rollback/exhaustion, pause, or a newer status ends them. A timer is wrong both
  ways: too long makes a fast transition read slow, too short claims ready before it is. Measure the
  pipeline before believing a latency report, and measure the pill too.
- **`FLASH_STATE` LIES about the torch, and preview LUMA is not a light meter in any AE-active mode
  (2026-07-25).** This HAL reports `flashState = 3` (FIRED) on frames where the lamp is physically
  dark, so "state != 3" is NOT a torch discriminator — only a human eye, or a luma read taken under
  FIXED manual exposure with GL digital gain = 1, can tell. Independently: preview luma cannot
  indicate scene light whenever an AE loop is running (HAL-AE or the app-side loop) — the loop's
  whole job is to drive luma back to its target regardless of how much light there is. **The honest
  indicator is the exposure/ISO the loop SETTLES at**, not the brightness it reaches. Post-cycle-8
  there is a second trap in the same place: in AE-OFF modes the reported/analysis luma is the
  digital-gain-SIMULATED signal (up to ×16) while HAL-AE video is unboosted, so raw luma is not even
  comparable across modes. The DEBUG 3A line logs `flashMode` (what our request carried) and
  `flashState` (what the HAL claims) so wire truth stays separable from lamp truth.
- **Debug capability diagnostics queue behind initial camera work.** The debug-only broad capability
  and vendor-tag scan runs on `setupExecutor` only after the initial route/open task is enqueued, so
  diagnostics cannot delay the first Camera2 setup task.
- **SettingsStore commits synchronously (`edit(commit = true)`), never apply().** Saves fire on user
  actions (a mode switch), and the very next gesture can be a Recents swipe-kill — apply()'s async
  disk write dies with the process and the change is silently lost ("last mode not remembered", hit
  twice). The prefs file is tiny; the synchronous write is a few ms.
- **`manager.openCamera()` can throw synchronously.** Opening from a background proc state (relaunch
  behind the keyguard / screen just woke) raises `CameraAccessException CAMERA_DISABLED` from the
  `openCamera` call itself, not the StateCallback — wrap it in `runCatching → onError` or it crashes.

## Architecture (one-liner per module; full map in docs/ARCHITECTURE.md)

```
MainActivity → CameraViewModel(CameraUiState/CameraActions) → CameraEngine (facade)
CameraEngine ├─ CameraSelector2  pick tele (closest-to-70mm, standalone; pickBest pure+tested)
             ├─ CameraController Camera2 session, capability-safe requests, fallback, capture/3A
             ├─ RotationMath     pure preview/capture/EXIF rotation (unit-tested)
             ├─ RendererAssists  remembered renderer state + generation replay authority
             ├─ StandbyAudioController single-owner armed-video level meter lifecycle
             ├─ GlPipeline       checked EGL ownership + afocal 180° + color + scopes/AE luma
             │    └─ FlipRenderer / EglCore / Shaders / SdrToHlgMapping
             │    └─ FocusDetail  pure curvature-ratio frame-detail metric (rides the readback)
             ├─ GyroEis          gravity roll + held-device orientation (GL shake warp disabled)
             ├─ AutoExposure     app-side S/ISO-priority AE loop (meters GL luma; pure+tested)
             ├─ ZslAdmission     pure pseudo-ZSL serve/refuse predicate (logical/front photo routes)
             ├─ StartupTrace     buffered debug cold-start stopwatch (one line; quota-safe)
             ├─ focus/MacroProximity focus-confidence proofs + hold + OSD wording (pure+tested)
             ├─ capture/StillCapturePipeline (processed + RAW save orchestration)
             │    └─ HeifCapture (pixel-rotate/EXIF) + DngCapture (EXIF orient)
             ├─ video/VideoRecorder (exactly-once input Surface; HEVC/AVC + AAC/muxer)
             └─ storage/MediaStoreWriter (IS_PENDING + durable recovery) + SettingsStore
UI: CameraScreen + CaptureOutputTracker (capture-level review/delete) + controls/* + overlays/*
```

Data flow is unidirectional: Compose UI is stateless off `CameraUiState`; every interaction goes
through `CameraActions` → ViewModel → Engine. Image work runs off the UI thread on the components'
own threads/executors.

## Working conventions

- **Match the surrounding code.** These files carry dense "why" comments documenting HAL quirks —
  keep that density when you touch them. Don't delete a comment that explains a workaround.
- **Verify on device before claiming a camera/GL/orientation fix is done.** Compilation ≠ correct
  pixels. Hardware behavior (orientation sign, EIS axis, exposure, color) needs a real screenshot or
  a pulled capture. If the device is unreachable, say so and mark the item pending.
- **Git** (from global rules): fine-grained commits, one concern each; `-S` GPG sign; **no
  Co-Authored-By**; Conventional Commits `type(scope): <gitmoji> desc`; `git pull --rebase` before
  push; commit+push after each verified iteration.
- **Hot paths** (edited most, touch carefully): `camera/CameraEngine.kt`, `ui/CameraScreen.kt`,
  `ui/controls/ProControls.kt`.

## Pointers

- `docs/BACKLOG.md` — optional private maintainer status, manual Play steps, residual checks, and
  deferred work. In a clean clone, use this file plus `docs/ARCHITECTURE.md` and
  `docs/FIELD_CHECKS.md`; do not invent missing backlog state.
- `docs/ARCHITECTURE.md` — **current as-built design authority**: module map, threading, ownership,
  data flow, and gotchas in depth.
- `docs/superpowers/specs/2026-07-01-...md` — optional private historical design snapshot. It is
  superseded wherever it differs from the current architecture/code and is never current authority.
- `.context/reviews/` — architecture/code/perf/security review notes (findings already addressed).
