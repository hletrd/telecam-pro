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
  ENUMERATED Camera2 capability. The global Find X9 Ultra (CPH2841) and the satellite edition (PMA120) resolve to the SAME PMA110
  profile by owner decision (2026-10-06) — inferred from shared hardware, never measured on
  CPH2841, so a contradicting CPH2841 report reopens the affected flag for that model. Still no CameraX — **Camera2** directly for physical-lens
  routing, `LENS_FOCUS_DISTANCE`, manual sensor, RAW/DNG, 10-bit HDR. Non-PMA110 handsets are
  UNVALIDATED until measured on-device; add quirks only with measurements, never speculatively.
- **The upload key is KEPT, as configured (owner decision 2026-10-13, explicit and final).** The current
  upload key (certificate SHA-256 `9dfdb903…`) and its current password stay. Do NOT add upload-key
  deny-lists, password-strength floors, rotation-approval flags, or any other gate that blocks signing
  with the configured keystore. Review findings about upload-key custody are recorded as accepted
  risk, not implemented. `tools/tests/test_no_upload_key_gates.py` fails if such a gate comes back.
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
| AGP | 9.4.1 | **Kotlin is built-in** — do NOT apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.20 | Compose compiler plugin version; AGP supplies Kotlin Android support |
| Gradle | 9.8.0 | wrapper |
| Compose BOM | 2026.09.00 | Material3 |
| compileSdk / targetSdk / minSdk | 37 / 36 / **33** | compileSdk 37 required by androidx core/core-ktx 1.19.x and the lifecycle 2.11.0 compose artifacts (`lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`); minSdk 33 since the 2026-08-01 multi-device decision — this row said 36 for two days while the constraint bullet above said 33; targetSdk 37 waits for an on-device Android 17 behaviour-change pass (Play has required 36 since 2026-08-31) |
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
# authoritative non-device host gate (Android + coverage + Python tools/harness/docs, plus
# :app:lintRelease on a clean tree; Kotlin warnings are fatal; its last line is the release-lint
# verdict, `release lint: RAN` or `release lint: SKIPPED (dirty tree)`)
python3 tools/verify_host.py
# :app:assembleDebugAndroidTest compiles/packages androidTest; it does not run or prove device behavior.

# normal implementation gate; its Gradle APK is developer-only, never device evidence
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Play-release gate (requires clean committed source + local signing credentials)
python3 tools/verify_host.py --release
# Direct Gradle release outputs are developer-only; caller-authored immutableRelease* properties are
# rejected rather than treated as authentication. Only this outer wrapper seals the exported inputs,
# rechecks them after build, freezes allowlisted outputs, and publishes release-evidence.json plus the
# verified commit/tree/output hashes in a unique immutable-release namespace. Signing is ordinary:
# Gradle signs with the keystore that keystore.properties configures (TELECAMPRO_* env fallback for
# alias/passwords); the scoped helper (run_scoped_signed_release.py) only pipes the passwords to the
# wrapper's child environment; check_release_artifact.py derives the expected signer from that same
# keystore via keytool. Gradle still refuses release package/sign tasks with no signing config or
# with IDE-injected android.injected.signing.* properties.
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

**Leave the latest validated app installed (owner instruction, 2026-10-27).** After device
investigation, install and retain the latest verified product build with the accumulated fixes.
Do not restore an older product APK as cleanup. Temporary probe packages and scoped test settings
may be cleaned up separately; verify the final installed product hash and report its build.

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
- **A session that carries STILLS is SDR/8-bit; an HLG video session (and log on the GL fallback)
  is REALLY 10-bit, and it buys that by dropping the stills (corrected 2026-08-05 — this bullet said
  "SDR/8-bit shipping session … `tenBit = false`" flatly, which stopped being true when video gained
  its own session and was still being quoted as a hard constraint months later; narrowed again
  2026-10-14 — native ISP log is the 8-bit session WITH stills, see the log bullet; and again
  2026-10-25 — on a `tenBitNativeLogBridge` profile native log is TEN-bit WITH stills, through the
  P010 bridge, see that bullet: the stills⇔10-bit trade belongs to HLG10, not to ten bits).** The HAL fact
  is unchanged and is the whole reason for the split: HLG10 preview + full-res JPEG + RAW together
  crash it. So the trade is structural, not a limitation — `tenBitSessionWanted(videoMode, transfer,
  nativeLogCurveSupported) = videoMode && transfer != SDR && !nativeLogSessionWanted(...)`
  (`CameraState.kt`) feeds `wantHlg`/`tenBitVideoOnly` (`CameraController.kt`), whose ladder rung
  configures HLG10 with **no still readers at all**. `videoSessionShape` (SDR / NATIVE_LOG /
  TEN_BIT) is the reopen boundary: any change of shape reconfigures; S-Log3 ↔ S-Log3.Cine ↔ LogC3
  on a native camera does not.
  That is exactly what the UI means by `"10-bit video · stills off"` — HLG (or GL-fallback log)
  only, and on the ladder's attempt 0
  only: if that rung fails, attempt 1 is HLG10 WITH the processed still reader (no RAW), so a session
  landing there is 10-bit with stills, and attempt 2 drops HLG (8-bit). It is also why
  `acceptedOpticsAuxState` must not normalize `photoFormats` in a still-less session (see the DNG
  route-input entry — a trip through log video otherwise wrote an empty format set over the
  operator's selection).
  **Scope: these source limitations describe HLG and app-generated Log, not the separately verified
  maker pipeline below. What is still NOT claimable for those mappings:** the SOURCE is the ISP's display-referred, already tone-mapped stream. Ten-bit video is a real
  10-bit *encode* of that stream, NOT recovered HDR and NOT scene-referred capture — see the next
  two bullets, which remain correct as written. Photo and any video left on SDR stay 8-bit; native-log
  video is 8-bit except through the P010 bridge, where it is ten bits of the SAME un-S-curved,
  clip-bounded signal (native log is un-S-curved, not un-clipped — see the log bullet).
- **HLG is a display-referred mapping, not recovered HDR.** The input stage follows accepted session
  truth: standard photo/SDR-video input takes the BT.1886 2.4 decode, while non-SDR video first takes
  the inverse HLG decode/reference-white normalization from its HLG10 Camera2 stream. The common
  display-light signal then maps through linear BT.709→BT.2020, the explicit reference-white/OOTF
  scale, and the BT.2100 HLG OETF (`Shaders.kt`; CPU anchors host-tested). In both cases the ISP has
  already tone-mapped the scene, so clipped/rolled-off highlights cannot be recovered. Every GL
  generation asks for an RGBA1010102 encoder target (2026-10-26, owner decision; PMA110 since
  2026-10-25) and falls back to RGBA8888 only where no recordable 1010102 config exists — so the
  HLG10 source reaches the Main10 encoder at ten bits wherever the device allows; playback
  appearance still needs a real HDR display and none of this is a scene-referred claim.
- **RAW only on the standalone camera — in BOTH failure modes (extended 2026-07-14).** RAW routed
  through physical sub-camera routing crashes configure (`DataSpace override not allowed for format
  0x20`), AND a still with the RAW target on the plain LOGICAL camera errors the whole camera device
  ~5 s after the shot (`CAMERA_ERROR(3)`, no image ever arrives). Gated to standalone selections
  only (`sessionAttemptPlan` `!logicalMultiCamera`) by the PMA110 profile's
  `DeviceProfile.rawRequiresStandalone`; GENERIC keeps RAW on the logical route when advertised there
  (table in `docs/ARCHITECTURE.md` § DeviceProfile Quirk Flags).
- **DNG is therefore a ROUTE INPUT, not a save option — and that makes it four different bugs
  (all device-fixed 2026-07-29).** On the PMA110 profile (`rawRequiresStandalone`), wanting RAW is
  what MOVES photo off the logical seamless camera onto a standalone lens, so DNG is available on
  EVERY lens, not only TELE; a GENERIC device keeps DNG on the seamless logical camera. Consequences that each
  cost a real defect:
  1. **Restore must push it.** `engine.setRawWanted` used to be called only from the live toggle, so
     a persisted DNG selection was silently inert on every launch — sheet showed DNG on, session came
     up `raw=false`, shutter wrote `outputs=jpg`. Every route input must reach the engine from the
     settings-restore path too; the reopen-triggering setters are enumerable, so check them all.
  2. **Its reopen must RE-RESOLVE the route.** The bare `reopenForSession()` reuses `overrideId`,
     which caches the id the LAST ACCEPTED session resolved to. After a Video→Photo trip that is the
     LOGICAL camera, so the reopen rebuilt the one route that cannot carry RAW — and because
     `setRawWanted` change-gates on `rawWanted`, the divergence was PERMANENT (no later toggle could
     recover it). `setRawWanted` begins its own optics transaction with `overrideId = userCameraPin`
     — but ONLY when the route answer actually flips (`dngIntentChangesRearRoute`). TELE (whose
     standalone session carries RAW by route), Video, FRONT/EXTERNAL, pre-start, and non-RAW-law
     (GENERIC) devices take direct writes, and a later rollback preserves a newer direct write
     instead of restoring its baseline unless keeping it would move the restored rear route
     (`rollbackRawWanted`). Reinstating the reopen there brings back a black dip per toggle. It is the ONLY reopen whose route ANSWER changes — hi-res,
     aspect, fps and transfer all keep the same camera — so it alone needs this.
  3. **The chip is gated by `rawSelectable`, neither session truth nor bare capability.** Session
     truth made it unreachable (disabled because RAW was absent, absent because it could not be
     enabled); bare capability left it live in a 10-bit VIDEO session that drops both still readers by
     design, under the caption "HEIF/JPEG unavailable; DNG only" while DNG was equally unavailable.
  4. **A still-less session must not edit the request.** `acceptedOpticsAuxState` normalized
     `photoFormats` against accepted outputs; in 10-bit video those are EMPTY, so a trip through log
     video wrote the empty set over the operator's selection, which persisted on background and came
     back as HEIF-only. Since 2026-10-02 (AGG3-18) NO accepted session edits the request: a
     DNG-only request on a session without RAW (FRONT, the drop-RAW rung) also came back HEIF+DNG.
     Readouts derive `CameraUiState.effectivePhotoFormats` at render time instead.
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
- **Stills on the LOGICAL camera are YUV, not HAL JPEG (2026-07-14; PMA110 profile
  `logicalStillRequiresYuv` — on GENERIC the YUV lane is an optimisation the ladder may drop for HAL
  JPEG).** gralloc rejects the ~42 MB
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
  14–16 fps at the dark fluidity cap over 5-minute soaks, zero FrameGap stalls >200 ms, zero camera
  errors, +0.3 °C battery.** S4b serve check, device-measured: **a lit 1× photo served 4/4 shutter
  presses at 0 ms delivery lag**; a dark M-mode 2 s shot served **none** and ran a real capture whose
  EXIF read a true 2.0 s. **The dark refusal is the DESIGN, not a gap — the user has explicitly
  accepted it.** `ZslAdmission.kt` admits a buffered frame only when its ACTUAL sensor values match
  the still's INTENDED values within 1/6 stop (plus zoom within 2 %, age <= 400 ms, app-side AE-OFF,
  processed-only, no AE-flash, no live gesture); in low light the fluidity cap deliberately diverges
  the preview from intent, so admission MUST fail and a full-quality real capture MUST run. **Do not
  "fix" the dark path by widening the tolerance** — that silently trades the user's exposure for
  latency. FRAMING obeys the same rule (2026-10-27): a ring frame is a repeating frame, framed by the
  session's stabilization mode, so it serves only when the real still would carry that mode
  (`framedLikeRing`; always for a servable still, since every still rides the session's mode — see
  "Stills are framed like the PREVIEW"). SINGLE drive only (`allowZsl = !singleShot` excludes the in-REC snapshot);
  TELE/rear-standalone/video/burst/AEB are untouched and keep the legacy blind-adopt path
  byte-for-byte. FRONT takes deep YUV on its full rung, degrades to shallow YUV, then returns to its
  proven HAL-JPEG path before preview-only; PMA110's mandatory-YUV quirk remains LOGICAL-only. The
  front route has capture-latency evidence, but its sustained idle/memory-pressure cost has no soak
  evidence yet and remains a field check (`docs/FIELD_CHECKS.md` A5) rather than an inferred success
  claim.
- **Seamless zoom = the logical camera, PHOTO ONLY (2026-07-14).** Camera 0 (`logicalMultiCamera`,
  physIds 3/2/4/5) spans zoomRatio 0.6–20 with HAL-internal lens crossing — pinch never reopens.
  Lens picks are zoom presets; TELE pins standalone 4 (digital 1–10×) and OFF returns to logical at
  3× — except Video + Log (maker), whose TELE stays on the logical camera held on the host tele by
  zoom (see the Gamma bullet's TC paragraph). Zoom ticks use the controller fast path (cached repeating builder, zoom keys only) — routing
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
  is an explicit per-call opt-in used by exactly two draws — the main preview (content + window term,
  only when the window is rotated) and this overview (window term only) — never a settable field.
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
  portrait playback). Gamma = Log (maker) is the one sanctioned exception, with or without TC: its
  pipeline exists only on the logical camera, inside the OEM 0x830b session; the A21 TC takes
  (Active selected, result vstab=1) show no edge band in extracted frames, and no other stabilization
  state on that route has been checked. `resolveNonTeleId`: photo=logical/seamless, video=matching standalone;
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
  **The DISPLAY multiplier is a separate law with ONE owner (cycle 5, AGG5-49 + MRG5-4).**
  `zoomDisplayMultiplier`, read only through `CameraUiState.mainRelativeZoomMultiplier`, feeds the
  pill, Fn chip/value, ruler and Shoot-tab slider. Its "1×" follows the DECLARED phone's maker
  (owner decision 2026-10-25): `oneXReferenceMm` is `PhoneModel.makerZoom.oneXEquivMm` (23 mm, or
  24 mm on the Find X10 Pro Max / X300 Pro / X500 Pro / Pro Max / Magic9 Pro Max; 23 mm on the
  35 mm-main vivo Ultras, whose main vivo calls "1.5×"); only `PhoneModel.OTHER` keeps the
  measured-main rule — the nominal 23 mm while the measured main is within
  `LENS_CAPTION_ROUNDING_BAND` (10 %) of it, the measured main otherwise (a 26 mm tablet main reads
  "1.0×", not "1.1×"). A standalone route divides its measured lens by that 1×. The logical
  seamless route reads its wire zoom × `logicalZoomDisplayFactor`: 1:1 while the logical camera's
  own measured equivalent (`LensInventory.homeEquivMm`) is inside the band of the maker 1×
  (multiplying PMA110's wire by 23.4 ÷ 23 read "3.1×" at the 3× preset — a scale applied twice),
  measured ÷ maker 1× outside it (35 ÷ 23 on a vivo Ultra), always 1:1 on OTHER. PMA110 declares
  23 mm, so every readout is byte-identical. The focal readings above predate this display law;
  its readouts are `docs/FIELD_CHECKS.md` A10.
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
  reproducibly fatal. On the PMA110 profile (`DeviceProfile.stillExposureCeilingNs`; GENERIC and
  EXTERNAL routes trust the advertised range) `HAL_SAFE_MAX_STILL_EXPOSURE_NS` (4 s) clamps the
  advertised range at the CAPS seam so the shutter ruler, request clamps, AEB brackets, and the still watchdog all stay
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
  `MainActivity` (`enableTeleCamEdgeToEdge`) sets `isAppearanceLightStatusBars` /
  `isAppearanceLightNavigationBars = false` directly through `WindowCompat.getInsetsController` — WHITE
  icons regardless of the system night setting — and no longer calls `enableEdgeToEdge()` at all
  (2026-10-16, Play Console recommendation): its `SystemBarStyle` path writes `Window.setStatusBarColor` /
  `setNavigationBarColor`, which Android 15 deprecated. API 35+ is edge-to-edge by platform
  enforcement; API 33–34 opt in with `WindowCompat.setDecorFitsSystemWindows(window, false)` plus
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, and the theme sets `enforceNavigationBarContrast=false`.
  Re-verified on PMA110 in light AND dark mode: no `LIGHT_STATUS_BARS` in the appearance region. The
  theme's `android:windowLightStatusBar=false` CANNOT do this job alone: it seeds only the STARTING
  window. Original device A/B on the PMA110 in light mode, one variable (the APK):
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
  drop HLG → preview-only. A 10-bit VIDEO request prepends its own attempt 0 (HLG10, no still
  readers) and then continues on the ordinary ladder from rung 1, so attempt 1 is HLG10 + the
  processed still reader (`SessionFallbackLadderTest`; changing it needs a PMA110 measurement). TELE tries vendor full/degraded plans, then regular full/degraded plans,
  and reserves both preview-only variants for last. Keep it; different capability combos fail on this HAL.
  **A SYNCHRONOUS device-fatal throw STOPS the ladder (2026-10-26).** When the HAL fails stream
  configuration with anything but BAD_VALUE, Camera3Device enters its ERROR state and
  `createCaptureSession` throws CAMERA_ERROR; every later rung then fails in microseconds on the dead
  device. `configureFailureIsDeviceFatal` (CAMERA_ERROR / DISCONNECTED / DISABLED) reports once and
  leaves the reopen to the Engine's bounded recovery; a soft rejection (onConfigureFailed with no
  throw, or a synchronous IllegalArgumentException) walks the ladder as before. Camera2 delivers BOTH
  the throw AND onConfigureFailed for one failed attempt, so `ConfigureAttemptGate` lets exactly one
  handle it — both used to, which ran two retry chains and scheduled two recoveries whose reopens
  evicted each other (`ConfigureAttempt.kt`).
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
  rule (≈1/300 s with the TC at local 1×; since cycle 4 "effective" includes zoom and, off the TC,
  the MEASURED equivalent, so PMA110 moves by up to −2.2 EV at TC 2–4.6× and ±0.025 EV even at the
  presets — that delta remains a field check and owner decision (`docs/FIELD_CHECKS.md` A6)), ISO
  carries exposure, shutter slides (≤0.35 stop/tick,
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
  **While a VIDEO take is starting or rolling the app-side loop HOLDS (owner request 2026-10-26;
  `appSideExposureHeldForRecording`).** Every loop step is a repeating-request swap, and a swap mid-REC
  froze the file 218–235 ms plus one more frame — 7 frames per exposure step (device-measured
  2026-10-26 with an induced zoom swap). So S, ISO and app-side P keep the exposure they settled on
  before REC, like AE lock on a camera body. A loop step staged in the 40 ms controls throttle just
  before the press is dropped when it lands (`controlsDeltaIsAppSideAeOnly`: the packet differs from
  the last one sent only in the field the loop owns in that mode — review finding, 2026-10-26) and the
  UI returns to the wire value; the operator's own changes still apply, the loop resumes
  when the take ends, and video P stays on HAL AE, which adjusts without swaps (moving video P
  app-side for the AE-off cadence was considered and REJECTED for exactly this cost). Device-checked
  2026-10-26: an 18 s S-mode take absorbed 107 loop ticks with the request generation unchanged
  (that dark room had the loop pegged at the ISO ceiling, so it shows the hold engaging, not a
  correction being held back).
- **White balance uses the route's own colour processing (owner request "WB must
  be fixed", device-measured 2026-10-27, `WhiteBalance.kt`, `docs/FIELD_CHECKS.md` A23).** Isolated
  Camera2 probe + ColorChecker on PMA110, every route (logical photo 0, standalone 2 SDR and native
  S-Log3, TELE 4 + 0x80b4, maker 0x830b at 1× and on the 3×): AWB PRESETS are accepted, echoed and
  IGNORED (AUTO gains); FAST/HIGH_QUALITY + `COLOR_CORRECTION_GAINS` — the app's manual WB until
  this fix — is ignored too (a fixed R 0.851 / B 5.263 blue cast; the Galaxy Z Flip6 also ignores
  FAST gains, and Camera2 documents the gains as camera-owned outside TRANSFORM_MATRIX); CCT mode
  (`COLOR_CORRECTION_MODE_CCT`, API 36, advertised [0 1 2 3] / 1500–10000 K) applies the HAL's own
  Kelvin table but an IDENTITY colour transform (pixels identical to TRANSFORM_MATRIX with the same
  gains and I — visibly desaturated) and on the maker session ignores the temperature outright;
  TRANSFORM_MATRIX with gains AND a transform is honoured byte-exactly everywhere, maker included.
  Outside the maker Kelvin/preset exception below: AWB OFF + TRANSFORM_MATRIX + gains from the active SENSOR's ColorMatrix calibration
  (`wbTargetGains`, Krystek Planckian locus, tint = Camera2's ±50 = ±0.01 Duv, + = magenta) + the
  transform the route's own AWB-AUTO frames report (`WbReferenceStore`, keyed by camera, session
  shape and physical sensor, looked up EXACTLY; mirrored to `wb_route_reference` prefs). The
  transform MUST be the route's own: the normal routes' tuned CCM, the maker route's wide log gamut,
  the 1× and 3× sensors of one logical camera, and the TC route (identity in a bare probe session,
  [1.39 −0.45 0.05 …] in the app's) all differ, and PMA110's logical camera 0 calibration even
  yields an identity CCM — no single calibrated matrix is right everywhere. A route/sensor never
  seen in AUTO "primes": AUTO until its first converged frame (6–9 frames measured, ≤ 3 s), then one
  rebuild. Calibration cross-check: 5000 K → R 2.13 / B 1.645 against the STOCK camera's own manual
  5000 K (R 2.172 / B 1.649). PMA110's ignored presets ride the same path at fixed Kelvin targets
  (`DeviceProfile.awbPresetModesIgnored`, `wbPresetTarget`: Incandescent 2856 K = CIE A, Fluorescent
  4230 K +10 = CIE F2, Daylight 5500, Cloudy 6500, Shade 7500); GENERIC keeps CONTROL_AWB_MODE
  presets (Flip6 honours them). Path order is by CAPABILITY, never a model string: calibration →
  matrix; else CCT mode; else uncalibrated matrix. The maker route's forced-AUTO stopgap
  (`makerLogWbControls`, 2026-10-26) is GONE — it rested on a FAST-only measurement.
  **The transform moves with the illuminant, so a route keeps one per white point (2026-10-27,
  A23; every matrix route — maker Log's Kelvin and presets ride AWBCCT instead, A25).** The Galaxy Z Flip6's own transform blue row is −0.20 / −1.27 / +2.47 at Incandescent,
  +0.04 / −0.75 / +1.71 at Daylight; replaying the one learned in a warm room under 5000 K gains
  drove blue to the floor (grey 127 / 81 / 7 against the Daylight preset's 125 / 89 / 23 at nearly
  the same gains). `WbReferenceStore` now tags each learned transform with the white point its own
  result gains balance for (`wbFrameKelvin`, the RAW developer's `neutralCct` through the sensor
  calibration), keeps up to 12 per route (10-mired merge; a surplus point is the older of the two
  closest in mired, so a sweep's end anchors survive AUTO learning), and `lookup(key, kelvin)` blends the two
  around the target linearly in mired (nearest outside the range; the latest when none carries a
  Kelvin — a route without calibration, or the pre-Kelvin store). On the Flip6 that predicts
  Daylight from Incandescent + Cloudy within 0.07 per element. Honoured presets on the wire are
  learned too, and a route whose HAL honours ≥ 2 locus presets (`wbSweepPresets`: Incandescent /
  Daylight / Cloudy / Shade as advertised) is SWEPT once when manual Kelvin or custom gains first
  need its transform (`wbSweepWanted`, `WbWire.Sweep`): each preset rides the repeating request
  until its second matching result (1.5 s cap per step), then the matrix — 1.9 s of preset-coloured
  finder on the Flip6, once per route per install (`isSwept` is persisted), NEVER while a take is
  starting or rolling (`wbLensRebuildHeld` = recorder published OR admission in flight: a REC press
  mid-sweep abandons it, swaps once onto the matrix, and REC admission waits — `awaitWbSweepSettled`,
  ≤ 800 ms, before any allocation — until the camera reports a non-sweep frame. Device-measured on
  the Flip6: without the wait 7 frames (0.23 s) of the Daylight step opened the clip; with it none,
  REC admitted 0.39 s after the abandon. The route stays unswept and sweeps at the next request
  rebuild after the take) and never on a still (a still mid-sweep takes the blended matrix; pseudo-ZSL refuses during a sweep
  and refuses any ring frame whose AWB mode differs from the still's). Flip6 A/B, mean |Δ| over 24
  patches against the HAL's own preset at the same white point: one transform per route 4.2–7.2
  sRGB codes (one patch 67 off), Kelvin-tagged from used presets 2.9–4.4, swept 1.7–3.3 (light drift
  over the batch 0.9–3.7). An offline "HAL look × calibration" model got only halfway and was not
  shipped. PMA110 ignores presets, so it never sweeps; it gains the per-white-point AUTO transforms
  as it meets different light. **No per-device colour matrices in the repo (owner decision
  2026-10-27): units of one model vary, so every transform is learned on the unit itself at runtime
  and lives only in that install's `wb_route_reference` prefs. Do not ship a table of measured
  transforms keyed by model.** The Flip6 / PMA110 matrices in `WhiteBalanceTest` are host-test
  fixtures only. **Two PMA110 STILL traps, both invisible in the preview:** (1) a
  matrix still at the template's stabilization OFF inside a stabilized session renders some other
  WB (orange came out blue), so EVERY still on every profile rides the session's mode and is framed
  like the preview (the "Stills are framed like the PREVIEW" bullet; a 4 s matrix still in Active
  has no working mode); (2, profile-gated) Face
  Priority's FACE_PRIORITY scene (CONTROL_MODE USE_SCENE_MODE) overrides an app-owned WB on stills —
  the result echoes the gains, the pixels carry the fixed blue cast
  (`facePrioritySceneOverridesManualWb`: the scene half stands down while WB is app-owned, detection
  stays, the settings row reads disabled). A still's result metadata is NOT proof of its white
  balance here; measure the pixels (`cmd media.camera watch` shows the per-frame keys).
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
    one OSD tag. Every NON-HANGUL symbol in a user-facing literal must stay inside those faces —
    the symbols user-facing literals use today (scanned 2026-10-02 over `values*/strings.xml` and
    Kotlin string literals) are `© ° · ± × — … → ∞ ’ ↑ ↓`; `§ γ ≈` are also in the faces but appear
    only in comments and docs. Hangul is the deliberate exception: no
    bundled Inter face carries it, so every `values-ko` string renders in the system face by design
    (EN+KO is mandatory above); do not strip Korean or bundle a CJK font to "fix" that.
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
  optical when a back lens's measured 35 mm-equivalent is within a ×1.35 ratio of the preset target
  (log-symmetric `max/min ≤ 1.35`: +35 % / −25.9 %, not ±35 %), else
  reachable only if the photo-home route's advertised zoom range covers the preset ratio — and the
  engine publishes it once on `setupExecutor` (`onLensInventory`). PMA110 keeps all four (all
  optical, pinned by test). A preset reachable only by zoom is spoken as "3× zoom", never "3× lens",
  and a rail left with a single preset renders nothing (pinch still covers the range). The Optics
  focal caption follows the same honesty: the round literal survives only within 10% of the
  measured equivalent (PMA110's ~69.4 mm 3× still reads "70 mm"; the tablet's main reads "26 mm").
  **The chips follow the DECLARED phone's maker (owner decision 2026-10-25).** The four
  `LensChoice` identities and their persisted names stay; `PhoneModel.makerZoom` (one row per
  phone, with its source) gives each its maker number — 0.5× on the Find X10 Pro Max, 2.8× on the
  Find X9s Pro / X10, 3.5× / 3.1× / 3.7× on the vivo and HONOR teles, 1.5× for the vivo X200 / X300
  Ultra's 35 mm main. `ZoomPresets` (`zoomPresetsFor(phone, LensInventory.homeEquivMm)`) carries the
  label and the chip's POSITION on the unified scale (label ÷ `logicalZoomDisplayFactor`; MAIN lands
  on 1.0 exactly when within the band), so a chip APPLIES its label; every conversion and band
  (`unifiedZoomOf`/`localZoomOf`/`opticalBaseFor`/`LensChoice.forZoom`, the Photo floor) takes that
  required `presets` argument on both engine and VM. Enumeration still decides which chips exist;
  `hidesTenX` only withholds 10× where the official row has none (X9s Pro, X10, X10 Pro Max).
  `OTHER` and PMA110 resolve to `ZoomPresets.NOMINAL` (0.6/1/3/10, pinned bit-identical).
- **Aspect ratio is only 4:3 or 16:9.** The sensor is 4:3-native: `AspectRatio.W4_3` = full readout
  (no crop, the default + the no-crop sentinel), `W16_9` = its center crop. Full/1:1/portrait removed.
- **The teleconverter's MAGNIFICATION is a setting, not a constant — and never a detection
  (2026-07-26).** The setting is a PAIR, asked as two dropdowns in the Lens tab: `PhoneModel`, then
  the `TeleconverterProfile`s that clamp onto it (`PhoneModel.converters()` = that phone's kits plus
  the fits-anything generics and CUSTOM). A flat chip row listing every brand's converters at once
  was the first shape and the user rejected it on device — optics that cannot mount on your phone
  should not be offered beside the ones that can. `camera/Teleconverter.kt` owns both enums, the
  first-party kit catalog (2026-10-25: every OFFICIAL maker kit found — Xiaomi, Samsung, OnePlus
  and realme sell none; model codes from Google Play's supported-devices CSV, focals from the makers'
  pages): OPPO Hasselblad 300 mm = 300/70 ≈ 4.286× on the X9 Ultra; the 3.28× kit (230/70) on the
  X9 Pro and X10 Pro Max, and on the 65 mm X9s Pro / X10, where OPPO's printed "253 mm" is a
  footnoted software crop and the app reports the optical ≈ 214 mm; vivo ZEISS 2.35× (200/85, Gen 1
  and Gen 2) on the X200 Ultra, X300 / Pro / s / Ultra and X500 / Pro / Pro Max (≈ 165 mm on the
  70 mm X300 / X300s, whose printed 200 mm is a vivo crop; ≈ 169 mm on the 72 mm X500), and the
  4.7× Gen 2 Ultra (400/85) on the X300 Ultra and X500 Pro Max; HONOR's 2.35×
  on the Magic8 Pro / RSR and G200 / G500 (200/85, 500/85) on the Magic9 Pro Max (G200 ≈ 188 mm on
  the 80 mm Magic9). Plus the generic 1.5/2/3× clip-ons, CUSTOM, and the
  pure helpers (`effectiveMagnification`, `normalizeMagnification`, `detectPhone`,
  `defaultConverterFor`, `reconcileConverter`, `effectiveFocalMm`) plus
  `teleDisplayBase(magnification, hostTeleEquivMm, mainEquivMm)` — the function that REPLACED the
  top-level `TELE_DISPLAY_BASE` val; its host is the SAME declared-or-measured host the focal uses and
  its main is `oneXReferenceMm`, the maker's 1× (PMA110 stays 70 ÷ 23; read only via
  `CameraUiState.teleDisplayBase` / `TeleconverterDeclaration.displayBase`).
  `TELECONVERTER_MAGNIFICATION` survives there as the Explorer default and as the anchor every
  older test and document quotes. A kit's `magnification` derives from the telephoto its GLASS was
  designed for (`designHostMm`, else its own host phone), never the selected one: moving glass to
  another body does not regrind it. Consumers read `CameraUiState.teleconverterMagnification` /
  `.teleconverterFocalMm`, so the profile and its custom value can never drift apart, and the old
  hardcoded `300f` / `"300 mm"` readouts (OSD, EXIF, Fn tile, MR summary, lens caption) are derived.
  Six rules hold this together:
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
     TOTAL magnification, so the local-zoom ceiling (`TELE_MAX_DISPLAY_ZOOM / teleDisplayBase`)
     widens as the converter weakens — a 2× converter earns nearly the full 1–10× digital range,
     and ordinary capability reconciliation clamps whatever the lens cannot actually deliver. The
     magnetic stops between floor and ceiling follow the declared maker (`teleZoomSnapsFor`):
     OPPO's 20× / 40× on the Find X9 Pro / X10 / X10 Pro Max, the generic `TELE_ZOOM_SNAPS`
     30× / 60× everywhere else (PMA110 included). The floor mark stays the OPTICAL field — the
     app never adopts a maker's cropped start such as the X10's printed 11×. The maker TC route
     (Video + Log (maker), 2026-10-26) reaches less: its route caps stop the TC-local zoom at the
     measured 9.8 ÷ 3 (≈42.6× TOTAL on the 300 mm kit) — the span that stays on the host tele —
     so the rail reads 13× / 30× / 42.6× and every clamp/ruler follows through the same caps seam.
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
     X300 Ultra takes the 200 mm and the 400 mm (so do the X500 Pro Max and, as G200 / G500, the
     HONOR Magic9 Pro Max), so its narrowed list carries both — but `defaultConverterFor` is
     `firstOrNull { it.phone == phone }`, which returns the base kit only because `ZEISS_200_X300`
     is DECLARED BEFORE `ZEISS_400` (likewise each later pair). That ordering is load-bearing and
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
  way), and the loupe/TEXTURE tap mapping needs NO un-flip (displayed x == texture x;
  `mapTapFocusGeometry(mirrorX=false)`), while the AE/AF METERING point IS un-flipped on EVERY front
  route (`meteringMirrorX` — the active array holds the true scene, so without it front tap-AF
  metered the horizontally opposite point). Both come from `FrontMirrorConvention` +
  `DeviceProfile.frontStreamPreMirrored`.
  Pushed as route state by `applyStabilization` via
  `GlPipeline.setFrontMirrorConvention(front, streamPreMirrored)` (roles in `FrontMirrorConvention.kt`).
  Since the 2026-08-01 multi-device decision this inversion IS the `DeviceProfile.frontStreamPreMirrored`
  quirk (PMA110 `true`); GENERIC takes the naive roles (preview adds the selfie mirror, files stay
  true-scene), so `mapTapFocusGeometry(mirrorX=false)` is the PMA110 value, not a universal constant.
  Capture rotation FRONT =
  `(sensor + device) % 360` — the gravity term is GyroEis CCW-POSITIVE, so it ADDS on the front and
  SUBTRACTS on the rear; the old `− device` here was the same sign error that saved every
  landscape-held REAR still 180° rotated (device-bisected 2026-07-25). Afocal never applies
  (`RotationMath.captureRotationDegrees(..., frontFacing)`); preview rotation stays 0. Hi-res and
  flash resolve off the existing capability/route axes with no facing term. RAW and the punch-in
  loupe (and with it the Loupe Overview) are DELIBERATELY excluded on FRONT: `sessionAttemptPlan`'s
  `!frontRoute` plus `rawSelectable`'s `!frontFacing` drop DNG, and `punchInResolved(enabled,
  frontFacing)` suppresses the loupe (toggle kept, restored on the rear). The RAW exclusion is a
  scope decision, not a measured HAL fault — the front route KEEPS its processed still readers
  (deep YUV → shallow YUV → HAL-JPEG); front RAW is simply unmeasured on every device.
- **Face Priority AF is the HAL's FACE_PRIORITY scene mode, never app-steered regions
  (2026-10-14; on-device behaviour UNVERIFIED — `docs/FIELD_CHECKS.md` A16).** Device-read facts:
  every PMA110 camera advertises `availableFaceDetectModes = [OFF, SIMPLE]`, `maxFaceCount = 10`, and
  `availableSceneModes` containing FACE_PRIORITY (1). With the persisted Focus-tab toggle on (default
  OFF writes no key — the request stays byte-identical) and AF-S/AF-C, `facePriorityKeysFor` puts
  `STATISTICS_FACE_DETECT_MODE = SIMPLE` + `CONTROL_MODE = USE_SCENE_MODE` + `CONTROL_SCENE_MODE =
  FACE_PRIORITY` on the repeating AND still requests through `applyAfOverrides` (the one applier all
  three paths share); a held tap-AF or AF Lock keeps precedence by writing AUTO/DISABLED there instead.
  Per spec FACE_PRIORITY is the one scene mode that keeps `aeMode`/`awbMode`/`afMode` active, so it
  also rides AE-OFF (S/ISO/M, app-side P) requests and manual exposure must stay in force — A16 checks
  exactly that, and a failure means restricting the scene half to HAL-AE packets. The same "keeps
  awbMode active" promise FAILED for white balance on PMA110 (measured 2026-10-27, A23): under the
  scene half an app-owned WB still carries the fixed blue cast, so the scene half stands down while
  WB is app-owned (`DeviceProfile.facePrioritySceneOverridesManualWb`; detection stays). **Do not add
  per-frame AF/AE regions from face results**: every repeating-request swap stalls this HAL ~180 ms,
  so app-steered face AF would make a moving face a slideshow. `STATISTICS_FACES` is read only to draw
  frames: time-gated to 10 Hz BEFORE the metadata read, change-gated, never logged, mapped to the view
  as the exact inverse of `mapTapFocusGeometry`'s metering half (round-trip host-tested) plus the
  stream crop the tap path does not model; the HAL-EIS crop of a stabilized video stream is not
  modelled either.
- **Video caps come from the device, not hardcodes.** `video/EncoderCaps.kt` scans `MediaCodecList`.
  Only **HEVC + AVC** are offered (both HW). **AV1 was removed** (the only AV1 encoder here is SW
  `c2.android.av1.encoder` — too slow/low-res to ship). **APV** (`VideoCodec.APV`, HW
  `c2.qti.apv.encoder`) is defined but **intentionally EXCLUDED**: on PMA110 (ColorOS, API 36) muxing
  APV into MP4 errored the encoder mid-drain (device-verified). That is a DEVICE/firmware observation,
  not a platform rule — the official MediaMuxer reference lists APV-in-MP4 as supported from SDK 36 —
  so the exclusion stays, and re-enabling APV on any device needs a measurement first. Resolutions come from
  the selected camera's `StreamConfigurationMap`, with the shipping selector capped at 3840 pixels
  wide; PMA110 tops out at 4K UHD in the UI. A rate is offered when the camera advertises its
  rounded parent as a FIXED AE target-fps range AND (on spec devices) the selected size's advertised
  SurfaceTexture minimum frame duration sustains it (2026-10-05; until then only the defensive 8K
  ≤30 cap looked at size, although this bullet claimed size gating). PMA110's TELE advertises a
  33.3 ms minimum for 3840×2160 yet records ~60 fps there, so `DeviceProfile.
  videoMinFrameDurationsUnderstated` skips that gate on the PMA110 profile. High-speed 120 fps is
  excluded because its constrained session crashes this HAL.
- **The SENSOR cannot be asked for an NTSC rate, so the FILE is retimed onto the exact grid (device-
  measured 2026-10-05 and 2026-10-26; grid since 2026-10-26, owner decision "29.97, 59.94 and 23.976
  are the standard").** Encoder PTS USED TO BE the SurfaceTexture sensor timestamps, so a file's frame
  interval was exactly what the camera delivered — up to 0.24 % off the tag, a 1.4 s audio drift per
  ten minutes in an editor that conforms to the tagged rate. `ConstantRateFrameClock` (GL thread, one
  per encoder output) now puts each real frame on the slot of the exact `rateNumerator/rateDenominator`
  grid nearest its capture time (≤ half a slot away; slot 0 is the first frame, whose sensor timestamp
  stays the video PTS 0 the A/V anchor and the gyro log use): a frame landing on a filled slot is
  dropped, a camera gap is filled by repeating the current frame, up to one second of slots
  (`encoderMaxFillSlots`). A still taken mid-take stalls the camera ~600 ms, and a third-of-a-second cap
  (tried after the review flagged the burst) left that as a 0.6 s hole that a constant-rate editor turns
  into an A/V slip. A fill is one burst of draws + swaps on the GL thread that also draws the viewfinder,
  so it extends the freeze the camera stall already caused: 79 ms for the still at 29.97, 285 ms at 59.94
  (4K60 encoder backpressure), measured per take in the `EncoderClock` row; a longer gap stays a hole. Integer rates take the same grid. The MP4 video track also
  needs a **120 kHz timescale** (`VIDEO_TRACK_TIME_SCALE`, MediaFormat `time-scale` → MPEG4Writer
  kKeyTimeScale, Android 13+): at the 90 kHz default 59.94 is 1501.5 ticks, and MPEG4Writer reuses a
  duration within 100 µs of the previous one, so a 59.94 take came out 1501 × 47 + 1511 — stamped
  ~59.952 fps although the encoder received exact 16683/16684 µs steps (perfetto). 120 kHz holds
  every offered period in whole ticks. Device-verified 2026-10-26: 29.97 takes in P (30 s), RAW log
  (30 s) and M (45 s) each `r_frame_rate = avg_frame_rate = 30000/1001`, every delta exactly 3003
  ticks at 90 kHz; after the timescale change a 20 s 59.94 P take with an induced mid-REC stall was
  every delta exactly 2002 ticks at 120 kHz (filled 43, longest fill burst 61 ms after a 208 ms camera
  stall), and a 36 s 23.976 take every delta 5005 with the one drop its fast 24.0096 fps sensor
  predicts; all decode clean. The dormant high-speed path (`captureRate > 0`) keeps raw timestamps.
  The history below is why. That cadence is a fixed sensor
  mode chosen from the `[fps,fps]` range and the AE mode: HAL-AE video (P) runs 16.80 ms (59.52 fps)
  for "59.94" and 33.44 ms (29.90 fps) for "29.97"; AE-off (S/ISO/M) runs 16.66 ms (60.04) and
  33.32 ms (30.01). `SENSOR_FRAME_DURATION` is IGNORED inside that range: M-mode 29.97 (requesting
  33.367 ms) and 30 (33.333 ms) produced byte-identical cadences, so making the app-side frame
  interval exact for drop-frame rates was measured, changed nothing, and was reverted. The container
  still tags the selected rate; players and NLEs follow the real timestamps.
  **Re-measured 2026-10-26 with a debug request override (camera 4, TELE, 4K native-log session),
  and the frame duration is ignored OUTRIGHT, not only inside a fixed range:** with AE off, requested
  durations of 33.367, 33.4, 34, 40 and 41.708 ms all ran 33.320 ms (`SENSOR_FRAME_DURATION` result
  33.333 ms) under `[30,30]`, `[10,30]` and even `CONTROL_MODE_OFF`. The cadence is 1/upper of the
  integer target range, about 0.04 % fast: `[24,24]` → 41.650 ms (24.0096 fps) with AE on or off,
  `[60,60]` AE off → 16.660 ms (60.024), AE on → 16.80 ms (59.52), `[30,30]` AE on → 33.4475 ms
  (29.898). Camera2 ranges are integers, so no public request reaches 30000/1001, 60000/1001 or
  24000/1001; an exact NTSC file can only come from retiming encoder timestamps onto the 1001 grid.
- **Recorded single-frame drops have three sources (two perfetto-bisected on PMA110, 2026-10-13;
  the third measured 2026-10-25).**
  (1) STEADY STATE, camera side: the HAL's sensor timestamps skip a slot when its 3A nodes run long
  (AWB p50 0.2 ms / p99 33 ms / max 69 ms; AF p99 13 ms) and the next request is admitted late —
  the request thread SLEEPS, it is not CPU-starved, and the camera→app queue never exceeds one
  buffer, so the app is not back-pressuring it. Video sessions therefore request
  `CONTROL_AF_MODE_CONTINUOUS_VIDEO` for continuous AF (`requestAfMode`; it was the picture variant):
  file-only A/B, four 12 s TELE 4K 59.94 P takes each, mean 1.12 % → 0.70 %, and after the first 3 s
  ~13 → 1 drop across the four takes. A fixed WB preset also roughly halves the rate (AWB is the
  heaviest node) but stays the operator's choice. (2) STARTUP, app side: ~1.3–3.5 s into every take
  the GL thread blocks 14–31 ms in `dequeueBuffer` on the encoder input surface
  (GraphicBufferSource), so the coalescer skips frames the camera DID deliver. Measured NOT to be the
  audio rendezvous (audio off keeps it) and NOT fixed by KEY_PRIORITY 0 + KEY_OPERATING_RATE
  (tried, no gain, reverted). Root cause still open. Measure drops from the FILE only: a
  `dumpsys media.camera` during REC is itself heavy enough to cause GL-side drops.
  (3) BY DESIGN, encoder side (measured 2026-10-25): `KEY_MAX_FPS_TO_ENCODER` = the selected rate
  makes GraphicBufferSource's FrameDropper trim a faster sensor down to it, so a 29.97 selection over
  PMA110's 30.01 fps RAW-log/AE-off cadence drops ONE frame every ~25.7 s, the first at ~1.5 s (its
  2 ms jitter allowance): 24 single-frame gaps in a 10-minute RAW log take, every other frame present,
  the developer and the camera clean (`missing(camera=0 developed=0)` in the debug develop profile).
  It keeps the frame count true to the NTSC tag, which is what keeps a constant-rate editor in sync;
  removing the cap would trade the periodic 67 ms hole for a 0.13 % rate mismatch (~0.8 s per
  10 min in such an editor). DECIDED 2026-10-26 for the exact grid: the drop now happens in
  `ConstantRateFrameClock` at the same rate but without the timestamp hole (the FrameDropper's cap stays
  and no longer fires, since grid spacing equals its interval). Video P stays on HAL AE: an app-side
  loop would have to swap the repeating request for every exposure step, and a swap mid-REC froze the
  file 218–235 ms plus one more frame (7 frames, device-measured 2026-10-26).
- **Audio sits on the video clock, minus the encoder's priming (measured 2026-10-25).** Video PTS 0
  is the first encoded frame's sensor timestamp; audio PTS 0 used to be just the first PCM sample
  read, so takes carried a per-take offset (logged 62–101 ms apart). On a REALTIME camera clock the
  recorder anchors sample 0 with `AudioRecord.getTimestamp(TIMEBASE_BOOTTIME)` and shifts every AAC
  frame at the muxer by (sample-0 capture − first-frame sensor time − priming), frozen per take;
  other clock sources keep the unanchored timeline, and a gap over 1 s is treated as a clock
  mismatch. Priming matters because MediaMuxer writes no encoder-delay edit list, so every player
  decodes the warm-up first: `c2.android.aac.encoder` LC 2048, HE-AAC 4096, HE-AAC v2 6142 (≈ 6144)
  samples, `c2.android.xheaac.encoder` 0 (`AacEncoderProbeTest`: an impulse through encode + the
  platform decoder, identical at every bitrate). Evidence (vibration bursts timed on the audio
  envelope and on the gyro sidecar with one 20 %-of-peak criterion, immutable debug `c892e1c8`):
  sound −60 ms EARLY before the anchor; +33/+32/+36 ms late with the anchor alone — the 42.7 ms LC
  priming; with both, LC −17, HE-AAC +10, HE-AAC v2 +12, xHE-AAC −9 ms. HE/HEv2 also depend on the
  DECODER's SBR delay (the host decoder here is ffmpeg's), and the gyro's own filter delay biases
  every reading early, so a clap take is the remaining ground truth (`docs/FIELD_CHECKS.md` D6).
- **The soundtrack format is a device-enumerated choice, AAC only (2026-10-25).** MediaMuxer's MP4
  writer (`MPEG4Writer.getFourCCForMime`) takes AAC, AMR-NB and AMR-WB audio and nothing else; AMR is
  narrowband speech, and the Opus/FLAC encoders this phone ships cannot be muxed. So the Audio tab
  offers AAC-LC (default 256 kb/s since 2026-10-26; it was 192), HE-AAC, HE-AAC v2 and xHE-AAC, each ONLY when a
  REGULAR encoder advertises that profile at 48 kHz (`video/AudioEncoding.kt`), with bitrate presets
  inside its advertised range — a board whose image lacks an encoder or its licence lists fewer, and
  REGULAR_CODECS already hides PMA110's `special-codec` `c2.qti.aac.hw.encoder` (LC-only, ≤ 192 kb/s).
  PMA110 offers all four (platform `c2.android.aac.encoder` and the mainline
  `c2.android.xheaac.encoder`); every preset was accepted and hit within ~6 % on device. HE-AAC v2
  is parametric stereo, so a mono route records HE-AAC v1. Editor/player import of the non-LC
  formats is `docs/FIELD_CHECKS.md` D7.
- **Sound Focus / Sound Stage need MODIFY_AUDIO_SETTINGS, which the app now DECLARES (owner decision
  2026-10-26; measured 2026-10-25).** `AudioManager.setParameters` needs that permission; without it
  AudioFlinger drops every `vendor_audiorecord_*` write SILENTLY: the HAL logged `mEffectType(0)` for
  all three Directionality choices, and a getParameters "echo" is always empty because ColorOS answers
  those keys only to "system or list" apps (the recorder no longer reads one). With the permission
  (debug-manifest experiment) the writes land: `OplusAudAlgoManager: effectType 5`, `orientation 0`,
  profile `mEffectType(5)`. It is a normal (install-time) permission, so nothing new is prompted; the
  closed release set is now CAMERA + RECORD_AUDIO + MODIFY_AUDIO_SETTINGS (`tools/release_permissions.py`).
  The shipping path (`VideoRecorder.applyAudioScene`) sends session id, orientation, focus zoom (and
  focus angle for Sound Focus), then the effect type for EVERY scene, `STANDARD` as `0`, and resets a
  non-standard effect to `0` when the take ends so the next app's capture does not inherit it.
  Device-verified 2026-10-26: Sound Stage logged `effectType 5` / `mEffectType(5)` during a take and
  `effectType 0` after Stop; the off-axis listening check is `docs/FIELD_CHECKS.md` D1. Facts it respects: the stock
  camera sends `effect_type=0` for ordinary video (Pro and normal Video) — the enum's old
  `STANDARD(1)` was a value no stock capture shows — plus `orientation`, `camera_id`, `telephoto` and
  `focus_zoom` (`camera_id`/`telephoto` are not sent until stock's TC-mode values are measured); and stock gets the HAL's
  4-capsule camcorder algorithm (`algo input channels(4)`) through a system-set `CamcorderSwitchMic=true`
  that a third-party app cannot flip (sending `oplus_camera_record=true` ourselves changed nothing;
  we always get 2 channels). The `audio_policy` logcat (`AudioFlingerExtImpl logSetParametersForDebug`,
  `OplusAudAlgoManager`) is the probe for all of this.
- **The built-in microphones are a bottom + top pair, 16-bit ≤ 48 kHz (measured 2026-10-25).**
  `getMicrophones`: bottom (y = 0.6 cm) and back/top (y = 14.6 cm), no channel mapping declared — so
  stereo is physical left/right only in LANDSCAPE, and which landscape maps which way depends on the
  orientation the HAL is told (`docs/FIELD_CHECKS.md` D8). Every built-in input profile in
  `dumpsys media.audio_policy` is PCM 16-bit ≤ 48 kHz (a 4-channel `fast_raw_input` mask exists);
  AudioRecord GRANTS float, 24/32-bit and 96 kHz requests but converts them from that, so they add no
  resolution. UNPROCESSED is declared supported, and a 4-channel index mask is granted — but channels 3
  and 4 are digital silence for CAMCORDER, UNPROCESSED, MIC and VOICE_RECOGNITION alike (in-app probe,
  2026-10-26): a third-party app gets exactly the two capsules, so a Sony-style 4-channel/backup track
  has no source here. UNPROCESSED left/right correlate 0.32–0.43 on room tone versus ~0.85 for the
  processed sources, so the stock processing mixes the capsules. A USB input
  profile offers PCM 32-bit / float up to 192 kHz. MediaMuxer cannot carry PCM in MP4 (AAC/AMR only);
  Media3's `Mp4Muxer` can (16/24/32-bit integer and 32-bit float tracks).
- **The Gyroflow gyro log (`.gcsv`, opt-in) lands in `Documents/TeleCamPro/`, not beside the clip,
  and is typed `application/octet-stream` (2026-10-14, UNVERIFIED on device).** MediaProvider refuses
  non-media MIME types under DCIM, and a typed `text/csv` makes it append `.csv` to the `.gcsv` name
  (`FileUtils.splitFileName`), which breaks Gyroflow's same-stem lookup. Rows are timed against the
  encoder's first-frame SurfaceTexture timestamp (video PTS 0) in the camera clock domain. Since the
  constant-rate grid (2026-10-26) a frame's PTS can sit up to half a frame from its capture time while
  the rows stay on capture time, a bounded sawtooth Gyroflow's sync has to absorb. The
  written readout direction is the true geometry (PMA110 rear 3, TELE/front 2), but Gyroflow
  documents support for 0/1 only. Sync, axis signs, and readout behaviour still need an on-device
  Gyroflow import (rear, TELE, front) before any of it is claimed.
- **PROPRIETARY / LICENSED HDR FORMATS ARE OUT OF SCOPE — do not add them, do not probe for them
  (owner decision 2026-08-04).** The video ladder is HEVC and AVC only. Some vendor encoders on this
  SoC advertise trademarked HDR formats and the platform muxer will accept them, so this is
  reachable — which is exactly why the rule is written down rather than left to judgement. Two
  independent reasons stand:
  1. **Honesty.** Camera2 supplies the ISP's display-referred, already tone-mapped rendition: 8-bit
     standard input for photo/SDR video, or an HLG10 still-less input for HLG/fallback-log video;
     native log replaces the global curve but is still 8-bit, sensor-clip-bounded input. A
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
  TELE mode when restoring saved settings. **Fresh defaults are general-purpose, not pro-flat (owner
  request 2026-10-26):** photo JPEG only (`PhotoFormats.DEFAULT`; the constructor's own defaults stay
  HEIF so derived values do not change meaning, and the OSD format tag stays silent at the default),
  video transfer SDR, Stabilization Standard, bitrate High, AAC-LC 256 kb/s stereo, NR and edge FAST.
  The persisted blob and the engine seeds carry the same values, and host tests pin each. Every menu
  setting carries a one-line `hint_*` caption (EN+KO); still quality snaps to 40–100 in steps of 5
  (`normalizeStillQuality`, also applied on load) and the menu ISO slider moves on the 1/3-stop marks.
- **Log = NATIVE ISP S-Log3 where the camera takes a programmed tone curve; GL log otherwise
  (rewritten 2026-10-14 — this bullet's title said "GL-only" until the public tone-map path was
  measured).** Device facts, PMA110, 2026-10-14: every camera (HAL 0–5) advertises
  MANUAL_POST_PROCESSING, `tonemap.availableToneMapModes` [CONTRAST_CURVE, FAST, HIGH_QUALITY],
  `maxCurvePoints` 512, EDGE_MODE_OFF, NOISE_REDUCTION_MODE_OFF and face detect [OFF, SIMPLE] max
  10. The HAL HONOURS `TONEMAP_MODE_CONTRAST_CURVE` on the repeating request in BOTH the 8-bit SDR
  and the HLG10 session — same grey wall, video P (HAL AE meters pre-curve), standalone camera 4,
  preview mean luma: stock 64.2 (std 9.5), linear curve 18.1 (3.0), S-Log3 curve 79.5 (4.4),
  gamma-2.4 curve 83.6 (6.7), stock again 64.4 (9.3); HLG10 session stock 79.7 vs linear 32.4.
  So `nativeLogCurveSupported(caps)` (`NativeLogCurve.kt`: MANUAL_POST_PROCESSING && CONTRAST_CURVE
  advertised && maxCurvePoints ≥ 32 — CAPABILITY-gated, never a model string) routes VIDEO + a log
  transfer onto the ordinary 8-bit session WITH stills and RAW; the REPEATING request (full
  rebuild, and therefore the cached zoom/sensor fast-path builder that IS it) carries
  `TONEMAP_CURVE` = the S-Log3 OETF over the ISP's linear input (1.0 = clip), 128 output-uniform
  points with one snapped onto the linear/log cut. Every log transfer programs that SAME transport
  curve; GL decodes it at sample time (`uSourceLog`, accepted-session truth via
  `CameraController.nativeLogConfigured` → `RendererAssists.setSourceLog`, replayed per GL
  generation): exact S-Log3 inverse → clamp → BT.1886 encode, so `base` — display, Gamma Display
  Assist, dgain, meter/zebra/false-color, peaking — and the unchanged transfer branches below run
  on the ISP's un-S-curved linear signal and re-encode S-Log3/S-Gamut3(.Cine) or LogC3/AWG3. Stills
  and the in-REC snapshot never carry the curve: in a native-log session they name TONEMAP_MODE
  HIGH_QUALITY (else FAST) explicitly (`stillToneMapMode`); photo requests are untouched. Pseudo-ZSL
  additionally refuses a native-log session. The GL-log path below (HLG10 source, stills off) is
  the FALLBACK for a camera without the capability. **Honesty limits that remain:** the curve
  removes the GLOBAL tone curve, but highlight headroom is still the sensor clip, mapped at S-Log3
  code ≈ 0.596 at the default Log EI offset 0 (headroom above it only through the offset — next
  bullet); whether QTI local tone mapping (ADRC/LTM — tags
  QTI-internal, absent from `availableRequestKeys`, so no public key controls them) still runs
  under a programmed curve is UNMEASURED; and an 8-bit stream or EGL target limits precision
  (S-Log3 spends ~130 of 256 codes) — lifted on a `tenBitNativeLogBridge` profile, where the P010
  bridge and the RGBA1010102 EGL target carry ten bits end to end (next-but-one bullet). The recorded-file and in-REC-still behaviour of
  this product path remains a field check (`docs/FIELD_CHECKS.md` A15). The owner's "file doesn't look like log" on the GL
  path was the ISP's S-curve UNDER a correctly applied GL log (S-Log3 clip of the wall: file centre
  Y10 p50 = 0.318 vs preview 0.31), not a missing curve. The debug `tonecurve` experiment hook that
  established all this (de1d1a03) is removed now that the product path exists.
  **Gamma is a MODE: Off / HLG / Log / RAW Log (owner decision 2026-10-26).** **UI names since
  2026-10-27 (owner request): Rec. 709 / HLG / Log / RAW Log / OEM Log** — the first mode is
  `VideoColorMode.SDR` (it was `OFF`; nothing persists the mode, so the rename moves no stored
  value), the OSD's short form is `709`, "OEM Log" is the label this file still calls "Log (maker)",
  and RAW Log's chip carries the `CameraColors.Caution` tint because it is experimental. The Video tab's
  Gamma row offers `VideoColorMode` in that order, and a Log Profile row (S-Log3 / S-Log3.Cine /
  LogC3, `VIDEO_LOG_PROFILES`) under the two log modes. The REQUEST stays the persisted
  `ColorTransfer` plus the RAW log intent (`rawLog`), plus a persisted `logProfile` memory that Log /
  RAW Log return to after Off or HLG; the mode is DERIVED at render time (`videoColorModeOf`,
  `CameraUiState.videoColorMode`), the same request/readout split as `effectivePhotoFormats`. RAW
  Log is offered only where `rawLogOffered` (a RAW-developable rear camera at a rate the RAW stream
  sustains); elsewhere a kept intent reads as Log, which is what the session then records.
  `CameraViewModel.onVideoColorMode` lands a mode through the two old doors, intent FIRST, so any
  change costs at most one reopen. The Fn tile / dial chip cycle the MODES (chip value "Off" / HLG /
  the compact profile / "<profile> RAW"); the profile is the menu's row. The owner's fifth slot —
  "Log (maker)", the phone maker's OWN log PIPELINE — is shown only on a device whose maker
  pipeline is reachable and integrated. PMA110 now offers `MAKER_LOG` on the rear automatic route,
  with or without TC (TC since 2026-10-26, below), at up to 30 fps and without Open Gate. It opens
  the logical camera with the native
  protocol below; this is a fifth mode, not another member of `VIDEO_LOG_PROFILES`. Its stable shader
  ID is 9; the retired IDs 3/6/7/8 remain vacant. Source confirmation precedes Ready. An unsupported
  or refused maker intent stays persisted but reads/records the ordinary S-Log3 fallback. Switching
  this route remaps lens-local/unified zoom in the same optics transaction. Native mode drops stills,
  bypasses the P010/RAW bridges and the programmed S-Log3 curve/EI offset; its own inverse is used
  only for monitor assists. The app's remembered standard Log Profile remains independent.
  **Source transfer correction (2026-10-27, A24):** the O-Log2 monitor inverse follows OPPO's
  official ACES CTL/DCTL V2: `exp2((code - 0.693) / 0.0855) - 0.0096`, no quadratic toe. The former
  Apple Log coefficients/toe crushed low positive OPPO shadows. Native recording still passes
  source codes through. HLG camera input already has BT.2020 primaries: matching HLG output now
  preserves its codes, and conversion to an app Log profile first changes linear BT.2020 into
  the BT.709 working coordinates without clipping. The former second 709-to-2020 matrix changed
  colours while neutral-only tests passed. Hardware shader tests cover both sources and SDR.
  **Maker Kelvin/preset WB uses the OEM table (2026-10-27, FIELD_CHECKS A25).** AWB OFF + FAST +
  `com.qti.stats.awbwrapper.AWBCCT` (int Kelvin) + `com.oplus.manualWB.color_tone` (int Tone) reaches
  the stock colour path on the confirmed maker session, including its tele route. Standard CCT,
  `qcamera3.manualWB.color_temperature`, and FAST plus standard gains do not substitute for AWBCCT.
  `WbWire.MakerCct` bypasses matrix priming; AUTO and custom gains retain their existing paths.
  In maker mode the tint control uses the OEM Tone units, not a calibrated ±0.01 Duv claim.
  The prior TRANSFORM_MATRIX path did honour its gains/CCM but those metadata did not prove pixel
  equality with stock FAST processing. The forced-AUTO stopgap remains removed.
  **Native HDR gain policy (2026-10-27, FIELD_CHECKS A26):** maker REPEATING requests carry
  `com.oplus.burst.capture.type = 1` (int). OEM `TuningPolicy::MatchFeature2Mode` then selects
  Feature 2 = 0 instead of the ThirdAPK profile 26, and the AEC applies the unequal gains:
  measured TC long/short 64/8, DRC 8 and Log LUT endpoint 3685, all matching stock, versus the
  old 64/64, DRC 1 and endpoint 2843. This selects the OEM policy on the existing video stream;
  it does not submit stills or a Camera2 burst. It is absent from session parameters and every
  non-maker request. TC chart means now match stock within RGB 1.70 / 0.60 / 1.28 ten-bit codes;
  the previously clipped white R=711 is restored to 762 (stock 764). A 93.694 s / 2808-frame
  product take has no PTS interval above 33.367 ms. The previous SDK-only/impossibility claims
  were disproved; neither this result nor the gain ratio is a certified total dynamic-range test.
  **TC + Log (maker) (owner request 2026-10-26: "we made the teleconverter work, so maker Log must
  work with it too"; exact-PMA110 standalone production route proven 2026-10-29).** Stock never pairs its own TC mode with Log (its HASSELBLAD TELECONVERTER video
  mode offers 1080p/4K at 30/60 fps and no O-Log2). Standalone camera 4 + VIDEO 0x80b5 carries the real
  OEM pipeline and measured strong stabilization. Mounted-converter evidence proves simultaneous
  operation: accepted real 1024-entry OEM gamma, HDRFeatureMode 1, unequal DRC/conversion gains,
  upright preview and Main10 file, TC-local zoom/readouts and clean decode. The owner narrowed the
  completion scope to that simultaneous-operation proof; no further stock bare-Log ISO/EV matching is
  required. `makerLogStandaloneTeleVideo` is therefore `true` ONLY on exact PMA110; CPH2841, PMA120
  and GENERIC stay false. The five-trial A/B used actual ISO 12414 / 12700 / 9100 across stock /
  ordinary / standalone, so it is NOT an exposure-matched ranking or a numeric 4.3× OIS-gain claim;
  standalone |dx| remained below stock TC in every trial with no directional-blur vote. Five aligned
  colour windows matched within 0.816 decoded RGB codes for that scene only (FIELD_CHECKS A30).
  Sensor mode 50 is accepted; mode 48 is not required. Earlier route facts
  remain: standalone 4 + 0x830b reported HDR 0 and standalone 4 + 0x80b4 gave an SDR-like gamma. The
  standalone OEM source's pixels arrive 180° pre-rotated while its SurfaceTexture matrix is identical
  to ordinary 0x80b4, so accepted source truth (`sourcePreRotated`, never maker intent) removes the
  duplicate afocal correction before Ready/REC; stabilization is not inferred from that rotation or
  from `original.zoomRatio`. On profiles WITHOUT standalone admission, Video + Log (maker)
  opens the LOGICAL camera with the native protocol
  (`makerLogTeleRouteWanted` / `teleRouteCameraId`, used by EVERY TELE door — startup/reopen, mode,
  lens/TC, recall) and holds it on the host tele by zoom; on that logical route the afocal 180°, TC-local zoom, focal and
  magnification readouts, OSD and EXIF are the ordinary TC route's, untouched. The zoom stays
  TC-LOCAL in engine, GL and UI; only the controller maps it (`MakerTeleRoute`: wire = local × the tele
  chip's unified position, 3.0 on PMA110, `makerTeleWireZoomScale`; results divided back so the GL
  compensation compares like with like). The route's caps carry the TC-local window `[1, 9.8 ÷ 3]`
  (`makerTeleLocalZoomWindow`; the caps cache keeps the camera's true range), so ruler, rail marks,
  every clamp and caps reconciliation read the reachable span. **The ceiling is 9.8, not 10, by
  measurement:** on this route (2026-10-26, dim room, chart ~1 m) the active physical camera stayed on
  the tele at wire 8.91 / 9.61 / 9.89, but every entry to 10.0 (5 of 5) handed the frame to the 10×
  periscope for 1.6–1.9 s before the HAL fell back — the owner's stock 3×/6×/10× check that day saw
  the fallback, not the handover, and a distant subject in good light may keep the periscope. 9.8
  sits below the periscope's own field (230 ÷ 23.4 ≈ 9.83), where the logical camera physically
  cannot serve from it; 5 of 5 jumps to 9.8 then stayed on 4 (`DeviceProfile.makerLogTeleMaxWireZoom`;
  null elsewhere withholds the TC combination). **Lens guard:** the controller reads
  `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (≤ 4 Hz, change-gated debug `MakerTele:` row) against the
  host MEMBER resolved by enumerated focal (`CameraSelector2.pickPhysicalMember`, never a literal
  "4"); a proven mismatch shows the red `LENS SWITCHED` OSD tag beside the focal
  (`teleconverterLensSwitchedTagVisible`, device-seen during the 10.0 handovers). Unknown on either
  side is never a mismatch. The Hasselblad `camera.mode` hint is not sent there (same key as
  `pro_video_log_mode`, and stock PRO VIDEO never carries it); `original.zoomRatio` carries the wire
  zoom, as on the non-TC maker route. **A maker refusal on the TC route never falls back IN PLACE**
  (an ordinary session on the logical camera would sit on the HAL's lens choice, and the TC ladder's
  0x80b4 is unmeasured there): the controller reports `onMakerLogTeleRefused`, the engine latches
  `makerLogRefused`, drops the cached id and reopens the ordinary TC route (standalone 4 + 0x80b4) —
  the route any other transfer, > 30 fps, Open Gate or a camera pin takes, byte-identical. Device
  evidence: `docs/FIELD_CHECKS.md` A21; low light (a hand-down to the brighter main lens) is A22 —
  passed 2026-10-26 in one dim room with HAL AE pegged at ISO 12404 / 1/30 s: 44 sampled frames at wire
  3.0 / 6.0 / 9.8 and the plain route at unified 3× all reported physical `4`, never the main lens, no
  tag. The converter was NOT mounted on any 2026-10-26 check (owner-confirmed): it is passive glass,
  so route, session, zoom and lens-choice results stand without it; light through the glass does not.
  **The phone-maker CURVES are GONE (owner decision 2026-10-26, superseding 2026-10-15's "the
  phone's own log, per phone").** O-Log2 / O-Log / Mi-Log were GL approximations of each maker's
  published curve over this app's own S-Log3 transport, offered per `Build.MODEL` through
  `DeviceProfile.nativeLogFlavor`. Owner: a log this app makes itself offers only the
  industry-standard curves everyone grades. Removed with them: the `ColorTransfer` members and
  `isNativeLog`, `nativeLogFlavor` (profile, Engine, UI state) and the whole per-model table — the
  model seam maps HAL quirks only again — their `LogProfiles` constants and shader functions, the
  maker caption, and the OPPO / Xiaomi attribution in the Setup legal block and NOTICE.
  SettingsStore migrates a persisted `"OLOG2"` / `"OLOG"` / `"MILOG"` (main blob and MR banks) to
  `SLOG3` (`migratedTransferName`), beside the legacy `"LOG"` → `SLOG3_CINE`. Shader codes 6/7/8 stay
  permanently VACANT like 3. The device measurements of O-Log2 in `docs/FIELD_CHECKS.md` A15 are
  history of a removed option.
  **The vendor log key alone did not activate native log in the measured third-party sessions
  (2026-07-09; scope corrected 2026-10-25).**
  `com.oplus.log.video.mode` is advertised in the tele's request+session
  keys and the HAL ACCEPTS it ("applied" logs), but those tests showed no native-log output:
  with the key set (as session parameter + on every request), the preview AND the recorded clip
  stay display-referred 709 — tested with both `TEMPLATE_PREVIEW` and `TEMPLATE_RECORD` repeating
  requests on device, judged in a lit scene. (Earlier "the file recorded as log" was the BT.2020
  full-range container tag being misread by players as a washed look; an "applied" log line only
  means the HAL didn't reject the key.) So on the GL fallback the log profiles bake standard curves in GL
  (`LogProfiles.kt` single-sources constants into `Shaders.kt`): BT.1886 2.4 decode → linear
  BT.709→gamut 3×3 (S-Gamut3 / S-Gamut3.Cine / ARRI Wide Gamut 3, D65→D65 so rows sum to 1) →
  defensive −0.0099 floor → the S-Log3 (uTransfer=2/4; 18 % grey → 420/1023 ≈ 0.4106) or LogC3
  EI800 (uTransfer=5; 18 % grey → ≈ 0.3910) OETF. Like
  the retired O-Log2 before them these are
  display-referred SDR-source curves, NOT scene-referred camera log — the ISP has already
  tone-mapped the stream and no highlight latitude is recovered. The encoder gets the curve, the
  preview renders it flat, and **Gamma Display Assist** shows the normal display-referred image
  instead (assist = skip the forward curve; the file always gets it; `ColorTransfer.isLog` is the
  one gate for all three). The original user-facing O-Log2 option (`ColorTransfer.LOG`) was REMOVED
  2026-07-22 ("not a standard"; a per-device `OLOG2` came and went 2026-10-15 → 2026-10-26, above); SettingsStore migrates a persisted `"LOG"` to `SLOG3_CINE`, and
  its forward OETF left `Shaders.kt` with it. **The dormant native-log plumbing that survived that
  removal is now GONE TOO (2026-08-04)**, because the vendor-SDK path it was waiting for was
  declined: the O-Log2-shaped de-log shader branch (`uTransfer == 3`) with its `olog2Inv`/`toRec709`
  helpers and toe constants, `shaderTransferCode`'s `delogAssist` parameter, `VendorLogMode`,
  `CameraEngine.vendorLogMode`, `GlPipeline.setNativeLog`, and `CameraController`'s
  `com.oplus.log.video.mode` + `com.oplus.VideoColorBT709` request keys.
  **The `nativelog` FLAG FILE is gone too (2026-10-26).** It survived the native-log removal as the
  gate of a debug-only RGBA1010102 experiment (`CameraEngine.tenBitExperimentEnabled()`); once every
  GL generation asked for the ten-bit target the flag had nothing left to force, so the gate was
  deleted with it. A leftover file on a device is simply ignored. **Shader code 3 is now permanently VACANT and the surviving codes keep their
  numbers** (0/1/2/4/5; 6/7/8, the removed phone-maker curves, are vacant too) — renumbering would silently re-map every branch and the shader's own
  `uTransfer == N` comparisons; a test asserts the gap stays unused. Do not re-add any of it.
  NOTE: leaving `KEY_COLOR_TRANSFER` unset on a BT2020 full-range HEVC format makes the QTI encoder
  tag the VUI **ST2084 (PQ)** — players then tone-map log footage as HDR. Tag a transfer
  explicitly, always; all three log profiles share one container policy (BT.2020 full-range +
  explicit SDR-class transfer). A trademark footnote for Sony/ARRI (EN+KO) sits in the Setup tab.

- **Ten-bit native log = a P010 stream bridged into GL; this SHIPPING ISP-curve path differs from stock O-Log2
  (device-measured PMA110 2026-10-25).** Three things the 07-28 investigation recorded as walls are
  FALSE: every rear camera (0–5) and the front list YCBCR_P010 (format `54` = `0x36`, 48–49 sizes
  incl. 3840×2160) and advertise `(SRGB, P010, STANDARD)` in the colour-space profiles (the old read
  grepped a decimal dump for a hex literal); `ImageReader.Builder.setDefaultHardwareBufferFormat
  (YCBCR_P010)` + `setDefaultDataSpace` set any dataspace from public API; and P010 + STANDARD keeps
  JPEG + RAW in the same session (configured on cam 2, and on cam 4 under `0x80b4`). GL still cannot
  be a camera's P010 consumer (a PRIVATE SurfaceTexture stream stays 8-bit NV12 UBWC under
  STANDARD), so `P010InputBridge` takes the camera stream in a P010 reader and an `ImageWriter`
  attaches each buffer, zero-copy, to the EXISTING GL input SurfaceTexture: raw Y sampled in GL showed
  94 distinct levels vs 27 for the same scene through an 8-bit stream, `SurfaceTexture.timestamp`
  equals `Image.timestamp`, 30 fps. **The bridge tags its buffers JFIF, because that is the matrix
  the HAL encodes P010 with (corrected 2026-10-25 — it first shipped wearing the stock O-Log2
  stream's BT.2020 tag).** BT.2020, BT.709 and BT.601 reader requests produced identical YCbCr bits,
  equal to the same session's YUV_420_888 stream that the HAL stamps JFIF; the tag only steers the
  DECODE, and Adreno decodes a BT.2020 tag exactly as BT.2020 (GL mean RGB vs a BT.2020-full decode:
  0.2 codes) while every other tag takes its one default decode — the 8-bit PRIVATE stream's, within
  ~3 codes of exact BT.601-full. The BT.2020 tag therefore skewed saturated colour by up to ~24
  ten-bit codes / ~5° of hue with neutrals exact, which is why grey-wall checks never saw it. The
  primaries are sRGB in both paths: `(SRGB, *, STANDARD)` is this camera's only STANDARD colour space
  (BT.2020 exists only as `BT2020_HLG` + HLG10), so the ISP clips to BT.709 before any curve and the
  shader's 709→target gamut matrix is a decode of that, not a choice. The ENCODER target must be ten-bit too — a 0.40–0.45 grey ramp
  reached the file as clean 10-bit steps through RGBA1010102 and as dithered 8-bit through RGBA8888 —
  and Java EGL cannot create the `EGL_KHR_no_config_context` context a per-surface config needs (EGL14
  rejects a null config), so EVERY GL generation starts on RGBA1010102 where the device has a
  recordable one (all profiles since 2026-10-26; the bridge profile first); SDR AVC/HEVC, HLG, preview
  and Photo were device-checked unchanged through it on PMA110 (no colour shift; FIELD_CHECKS A18), and
  the Galaxy Z Flip6 recorded HLG and RAW log through it. `GlPipeline.tenBitOutput` reports which
  config the generation got; the Ready publication copies it to `CameraUiState.glTenBitOutput`, and
  the RAW log caption says "10-bit" only then. That RGBA input costs a conversion INSIDE the encoder (perfetto,
  4K 29.97 native-log take, 2026-10-26): `c2.qti.hevc.encoder`'s image-transformer thread in
  `media.hwcodec` ran 2.43 CPU-s over the ~8 s take with two GPU `QueueSubmit`s per encoded frame and
  964 buffer import/free pairs. **Rendering YUV straight into the encoder works at 8 bit ONLY
  (`YuvEncoderSurfaceProbeTest`, 2026-10-26).** `eglChooseConfig` returns NO YUV config even with
  `EGL_COLOR_BUFFER_TYPE = EGL_YUV_BUFFER_EXT`, but `eglGetConfigs` lists 20 (all non-recordable,
  limited range, 601-tagged): the QTI NV12 config (visual `0x7fa30c04`) into a 4K HEVC Main encoder
  decoded to exactly the codes the shader wrote, and `rgb_2_yuv(itu_709)` lands within one code of the
  encoder's own RGBA conversion (which follows the container tags: BT.709 limited at 8 bit, BT.2020 full
  at 10). Per 90 frames the encoder's converter fell from 376 ms CPU / 180 GPU submits (RGBA8888; 558 ms
  from the RGBA1010102 this profile renders) to 117 ms / 90. The P010 config (`0x36`) is REFUSED by the
  Venus driver (`failed to queue buf for port:IN`, unrecoverable), so 10-bit log/HLG keep the RGBA path.
  NOT integrated: it saves ~0.15 core only on 8-bit SDR takes and would need a second, shared YUV EGL
  context plus an ESSL 3.00 port of the GLSL ES 1.00 renderer shader. Gating: `tenBitNativeLogBridgeSupported` = profile flag (PMA110
  family only — GENERIC unmeasured) + curve capability + `(P010, STANDARD)` advertised + the GL input
  size among the P010 sizes + not FRONT, resolved by `CameraEngine.tenBitNativeLogStreamFor` into
  `CameraController.open(tenBitNativeLogStream)`. The controller tries the bridge first on each rung
  and a refusal retries the SAME rung on the 8-bit SurfaceTexture; `tenBitNativeLogConfigured` /
  `PhotoSessionOutputs.tenBitNativeLog` are accepted truth (the Video-tab caption says "10-bit" only
  then). The writer is a PRODUCER on the GL input Surface — one producer at a time — so the bridge
  closes after the device in teardown, before any next session connects there. The repeated
  `ImageReader$ListenerHandler … dead thread` warning on Photo↔Video flips is PRE-EXISTING (six per
  eight flips with SDR and no bridge); do not chase it as a bridge leak (bridge threads were 0 after
  the flips). **What it is NOT:** the stock PRO VIDEO O-Log2. Both stock PRO VIDEO sessions (SDR and
  O-Log2) run a 2-exposure DOL usecase (`HDRFeatureMode 1`, sensor mode 21, DRC gain, a
  `com.oplus.gamma.info` LUT); a third-party session with the stock op mode `0x830b`, the String key
  `com.oplus.camera.mode = "pro_video_log_mode"`, `log.video.mode = 0`, `is.from.main.menu = 1`, the
  stock P010 stream shape and even `com.oplus.packageName = com.oplus.camera` (echoed in results)
  did not leave `HDRFeatureMode 0` in those earlier trials. The later two-tag experiment below
  reaches the stock route. An echoed repeating-request value does not establish the identity
  consumed at configure time, so the earlier claim that this ruled out package-dependent routing
  was too strong. Ten-bit native log is ten bits of the SAME
  un-S-curved, clip-bounded ISP signal, not the stock pipeline's latitude.
- **Stock O-Log2 processing route reached through direct Camera2 (PMA110, ColorOS 17,
  2026-10-25/26; integrated as Gamma = Log (maker)).** On `PMA110_17.0.0.106(CN01)`, Camera
  `7.006.119`, an independent probe with no SDK dependency, root, package impersonation or app Log
  shader produces the stock route. The missing configure-time pair is
  `com.oplus.is.sdk.camera.package = byteArrayOf(1)` AND `com.oplus.is.from.extension = 1` (int).
  Tested with logical camera 0, session type `0x830b`, `com.oplus.camera.mode` carrying NUL-terminated
  `pro_video_log_mode`, `com.oplus.log.video.mode = 0` (a format selection, NOT an enable boolean),
  `com.oplus.camera.is.from.main.menu = 1`, TEMPLATE_RECORD, 1080p PRIVATE preview and 4K P010 record
  or Main10 encoder surface, STANDARD profile, dataspace `0x8c60000`. Tags were set on session AND
  repeating requests. This does not restore the declined authenticated CameraUnit SDK.
  **The service and HAL gates differ.** The SDK byte selects full OEM processing instead of
  `OUsecaseLite`; but CameraService's `afterEndConfigure` authenticates the SDK tag's PRESENCE,
  including byte 0. With no extension marker, both 0 and 1 throw `SecurityException` after HAL
  configuration. The extension marker selects the service's extension path (both system CameraUnit
  versions use it). A fallback must OMIT the SDK tag; zero is not OFF.
  **Device proof:** at identical requested exposure, SDK-byte 0 → 1 → 0 with the extension marker
  changes sensor 47/HDR 0/no gamma LUT/P010 median Y10 7 → sensor 21/HDR 1/two exposures/1024-entry
  Log LUT/median 191 → the original result. Camera → encoder directly saves real 4K HEVC Main10,
  BT.2020 full-range `bt2020-10` files. At actual ISO 800 / 33,322,214 ns, stock and probe report
  IDENTICAL 1024-entry Log, 257-entry IPE and 257-entry preview gamma arrays, plus identical WB
  gains. A 361-frame/12.088 s probe clip fully decodes without errors (one startup frame gap).
  This establishes the route and reported curves, not pixel identity or a certified stop count.
  **Limits:** standalone main 2 / tele 4 under `0x830b` produce Log LUTs and flat pixels but report
  HDR mode 0, so full stock merge is not established there. The tested tele 4 + TC `0x80b4` +
  `pro_video_log_mode` combination reports HDR 1/two exposures but an SDR-like gamma (starts
  0,64,128); additional TC combinations remain open. The product's TC + maker route uses neither:
  logical 0, zoomed onto the host tele (the Gamma bullet's TC paragraph).
  Stock reports `VideoColorBT709=1`; the extension route returns 0 even if 1 is requested. The
  earlier ImageWriter bridge variants failed on generic controls too. Integration solved this by
  removing that bridge: the OEM session overrides the existing PRIVATE SurfaceTexture stream to
  TP10 (`0x7fa30c09`), STANDARD, dataspace `0x8c60000`. It feeds the existing RGBA1010102 GL renderer
  directly. Recording uses source codes unchanged by any OETF; `MakerLogSource` is an inverse only
  for display assists. Never apply the generic S-Log3 decoder or JFIF dataspace override here.
  `CameraController` waits for operation mode 0x830b, Log format 0 and the actual Log LUT before
  Ready. Its 1024-entry array contains 256 valid entries followed by zeros; the black entry is 754.
  Refusal rebuilds without any SDK tag. A camera-device failure disables the native attempt for the
  recovery sequence; explicit mode selection can retry. Product evidence is in FIELD_CHECKS A20:
  4K Main10 + AAC, 345 frames / 11.5115 s, maximum PTS interval 33.367 ms, full decode clean.
  **Former highlight gap — resolved in the measured comparison by A26 (2026-10-27).** The
  old route's equal long/short gains and LUT endpoint 2843 were real; changing DCG flags, explicit
  short gains, extension markers or stream profiles did not fix them. The missing repeating
  capture-type policy above restores stock's gain split and full Log LUT. Verified on main and
  tele probes and in the TC product recording/soak. Broader lighting/optical range and a formal
  dynamic-range chart are not certified by this check. WB uses the OEM path described in A25.
  The measured route is available on ColorOS 17; ColorOS 16 shares the configuration protocol but
  has not been retested with this integrated implementation.
  The two ColorOS versions share the Log configure-stage method and stock session/stream/sensor
  signature, but this two-tag route is device-tested on 17 only. Resolve vendor keys by NAME:
  168 of 455 shared result tags changed numeric IDs across the update. Optional private detail:
  `docs/reviews/2026-10-25-stock-log-routing.md`; local evidence `.context/stock-log-2026-10-26/`.
- **RAW log (EXPERIMENTAL) = S-Log3 developed in-app from the RAW stream (owner request 2026-10-25;
  `docs/FIELD_CHECKS.md` A19).** Gamma = RAW Log (a toggle until 2026-10-26), with every log profile
  — S-Log3 / S-Log3.Cine and, since 2026-10-26, LogC3: the developer always writes S-Log3 over
  S-Gamut3.Cine and GL re-encodes the selected profile from the unclamped decode, so the profile is a
  GL choice that never reopens the RAW session — on a RAW-developable rear camera
  at a rate the RAW stream sustains — RAW16 is advertised AND measured at 33.3 ms on cams 2/3/4, so
  ≤ 30 fps (`rawStreamSustains`). `RawLogBridge` is tried FIRST on each rung (RAW log → P010 → 8-bit;
  a refusal retries the same rung on native log) and the session's ONLY output is the RAW reader —
  **always on the REGULAR session type, never the 0x80b4 TC operation mode** (device-measured
  2026-10-26: RAW-only on camera 4 under 0x80b4 failed configure with -38 and put the camera device in
  ERROR, so TELE + RAW log froze the app and every relaunch, which restores both, froze again; on the
  regular type it configures at fallback 0, TELE off/on toggles cleanly and a take records). A RAW log
  attempt that does kill the device is reported as `RawLogConfigureFatalException`, and the Engine
  leaves RAW log out of that route (`rawLogRefusedRoute`, cleared when the operator turns RAW log on)
  so bounded recovery reopens on native log —
  the HAL accepted that on cam 4 at rung 0. A RAW-only session still runs AE/AWB/AF (the developer
  takes WB gains, dynamic black, the lens-shading map — requested ON — and the crop zoom from each
  result). The developer (own EGL ES 3 thread) renders RGBA1010102 S-Log3 over S-Gamut3.Cine into an
  EGL window surface on the GL input Surface with the sensor timestamp, so preview/encoder/scopes/REC
  are untouched; GL learns `uSourceWide`, and the log branches read the decode UNclamped in linear
  709 so S-Gamut3.Cine round-trips exactly — the log never passes THROUGH a 709 clip (owner: "log
  must not be 709-based, gamma or gamut"); measured 5.7 % of a warm interior outside 709 kept vs 0 %
  on the native path. Four facts that each cost a wrong turn: (1) **PMA110's ForwardMatrix1 ==
  ForwardMatrix2 == the standard sRGB→XYZ(D50) matrix to 1/128** — a placeholder that treats camera
  RGB as sRGB (camera→709 through it is identity ±0.01, i.e. NO colour correction); the ColorMatrix
  pair is real (differs D65 vs StdA), so `forwardMatrixInformative` rejects the identical FM pair
  from the DATA and the DNG ColorMatrix + Bradford path runs. (2) An EGL-produced frame carries buffer
  transform 0 while camera frames carry the sensor rotation, and Java EGL cannot set one —
  `SourceTransformEmulation` post-multiplies GLConsumer's own matrix (`camera = egl · T`). (3) The
  probe's first develop had R/B SWAPPED at green sites and resampled 4080→3840 by nearest site, which
  the encoder's co-sited chroma turned into a ±20-code 16 px checker; the app does MHC on whole Bayer
  tiles and resamples in linear light. (4) The developer swaps ASYNC (`eglSwapInterval 0`): a thread
  parked in eglSwapBuffers kept the GL input Surface connected past close's 1 s wait, and the next
  P010 writer was refused (one 8-bit landing in 22 OFF transitions before the fix). Costs, in the UI
  caption: no stills, no ISP NR/sharpening, gyro stabilization with OIS off, ≤ 30 fps. Colour vs a
  chart, a thermal soak and clipped highlights remain A19 field checks — the ColorMatrix rendering
  differs visibly from the ISP's tuned look and neither is claimed as ground truth.
  **Stabilization is the app's GYRO EIS, and the converter is why it must be (2026-10-25).** The HAL's
  EIS works on ISP output and never reaches RAW, and its OIS, engaged even in a RAW-only session
  (`ois=1`), compensates as if no converter were there: through the inverting Keplerian converter a
  rotation θ moves the image by −M·f·θ, so a bare-lens OIS adding −f·θ makes it WORSE, ×(M+1)/M — and
  no OIS samples are published (`availableOisDataModes` absent) to subtract it. So the RAW session
  turns HAL video stabilization AND `LENS_OPTICAL_STABILIZATION_MODE` OFF while EIS runs — **vstab
  OFF alone left `ois=1`; the explicit OIS OFF gives `ois=0`** — and `stab/RawEisMath` +
  `stab/RawEisGyro` correct everything: F = +f bare, −M·f with TELE; 8 rolling-shutter row bands
  timed by `SENSOR_TIMESTAMP` + exposure/2 + skew·row (gyro and camera share `elapsedRealtimeNanos`,
  timestampSource REALTIME); a causal smoother pushed toward the real path until the window fits. The
  Stabilization setting drives it (Standard 10 % / Active 20 % margin). On-device effectiveness and
  the TELE sign are A19 field checks — compiled math is not shake removed.
  **Resource facts (measured, cam 4 RAW 4096×3072 at 30 fps):** the per-frame CPU upload dominated —
  RAW16 straight from client memory 6.6 ms; RAW10 (offered at the same 33.3 ms) plus only the rows the
  demosaic reads 4.7 ms; the same bytes through two PBOs **2.4 ms** (2.1 ms is a plain copy, bounded by
  reading the camera's buffer — only a zero-copy AHardwareBuffer import, NDK-only, removes it). Four
  bytes per RGBA8UI texel did NOT help (upload unchanged, pass 1 doubled on the dynamic component
  pick). Process CPU 83 % → 56 % in preview with EIS (native log 46 %). **`lensShadingApplied =
  TRUE` is FALSE on every rear camera:** the RAW corners read 0.16–0.50 of centre and the DNG's own
  GainMap applies ~2.9× there, so the developer's shading step is required. Two tooling traps:
  **`dumpsys meminfo <pid>` FORCE-STOPS the app on ColorOS** (`am_kill … stop … due to from pid
  <shell>`; read `/proc/<pid>/status` instead), and `kgsl gpubusy` is relative to the current GPU
  clock (unreadable without root), so only the in-app GPU timer queries give absolute pass cost.
- **ISP noise reduction is honoured OFF on the log stream (device-measured 2026-10-25).** Fixed ISO
  1600 · 1/62 s, S-Log3 curve on the P010 stream: luma high-frequency energy (mean |Laplacian|) 9.7
  with NR OFF vs 7.0 FAST / HQ; EDGE_MODE made no measurable difference on that defocused scene. The
  app default is FAST for both since 2026-10-26 (`ManualControls`; general-purpose defaults, owner
  request — it was OFF), so a log take keeps the default's mild NR unless the operator picks OFF. Log
  deliberately gets no override: forcing one would overrule an operator who picks NR deliberately.
- **Log EI offset — HIDDEN on the PMA110 profile (device-measured 2026-10-15, owner decision).**
  On PMA110 the ISP's output under the programmed S-Log3 curve is NOT exposure-linear: M mode, AE
  off, fixed ISO 100, a lit wall, exposure ×1 / ×½ / ×¼ / ×⅛ decoded to 1 / 0.28 / 0.11 / 0.052 of
  the ×1 level (expected 0.5 / 0.25 / 0.125) — an exposure-dependent gain BEFORE the curve (QTI
  ADRC/GTM; `disableADRC` / `GTMManualCurveTag` exist as vendor tags but are not request keys). No
  fixed k restores grey: +2 read ~1.2 stops dark, banded and desaturated (8-bit source stretched).
  A k-scaled ISP curve was ALSO tried and gave the same darkening, so the k lift now lives in GL
  after the exact plain-S-Log3 decode (`sampleSource`/`nativeSceneLinear` multiply by
  `uSourceLogHeadroom`); the ISP curve is always the plain S-Log3. `DeviceProfile.nativeLogEiSupported`
  = false on PMA110/CPH2841/PMA120 hides the row and forces wire offset 0 (persisted value ignored);
  GENERIC keeps it (spec: TONEMAP_CURVE input is linear), UNVERIFIED. The same measurement means
  PMA110's native log itself is NOT scene-linear log: the stock S-curve is gone, but an adaptive
  gain still shapes the input. The original design follows (where it says "the curve becomes
  slog3(k·x)", read "GL multiplies the decode by k").
- **Log EI offset = highlight headroom bought with shadow noise, NATIVE log only (owner request
  2026-10-15; `docs/FIELD_CHECKS.md` A17).** `ManualControls.logEiOffsetStops`
  ∈ {0,1,2,3}, default 0, persisted with the controls packet (main blob + MR banks, corrupt values
  clamped); Video tab row "Log EI Offset" under Transfer, shown only for a log transfer on a
  `nativeLogCurveSupported` camera (`logEiOffsetSettingVisible`), locked while recording; the OSD log
  tag gains " +N". **0 is byte-identical to the previous behaviour** (curve array, wire keys, shader
  path — host-pinned). With N > 0 on a native-log session's REPEATING request only
  (`previewExposureCap = true`; the controller passes 0 for every still and the in-REC snapshot, which
  keep the operator's exposure under the stock tone map): (1) exposure is cut by k = 2^N — AE-OFF
  (S/ISO/M, app-side or manual) divides the FINAL N = 0 wire ISO by k (rounded up), and only what the
  ISO floor refuses comes out of a SHORTER exposure (never longer; frame duration unchanged — so at
  base ISO the offset shortens the shutter, a stated cost); HAL AE (video P) moves
  `CONTROL_AE_EXPOSURE_COMPENSATION` −N stops in the advertised step, clamped to the advertised range;
  (2) the curve becomes `slog3(k·x)` (`nativeLogToneCurve(points, k)`, same output-uniform sampling,
  knee snapped at x = cut/k, slog3(8) ≈ 0.83 < 1); (3) GL: `slog3Inverse(code)` is already
  scene-linear k·x, so the six LOG branches read `nativeSceneLinear` — the same decode clamped to
  [0, k] (`uSourceLogHeadroom`) instead of [0, 1] — while `base` (display, Gamma Display Assist,
  meter/zebra/false color, peaking, dgain, the analysis/AE readback) keeps the [0, 1] clamp, so the
  finder and the app-side AE meter stay at normal brightness and the loop does NOT chase the offset;
  `logInputLinear` takes the historical `sourceLinear(base)` path whenever k ≤ 1. **ACHIEVED, not
  requested, drives curve and GL:** every clamp (ISO floor + minimum exposure, the EV-comp range)
  can deliver less than k, and `applyExposure` returns the factor the wire actually carries
  (`NativeLogEiApplied`); the controller programs the curve for THAT factor and publishes it
  change-gated (`onNativeLogEiFactor` → `RendererAssists.setSourceLogHeadroom`, replayed per GL
  generation). Changing N is a controls delta → one full rebuild; a sensor fast-path tick that moves
  the achieved factor rewrites the curve on the SAME cached builder in the SAME single submit — never
  a second swap, never per frame. The live ISO/shutter readout (`onExposure`) is scaled back to EI
  values (`eiRatedReadout`; HAL AE attributes the whole factor to ISO) so the auto-mode chips and the
  P→M handoff seed never read the deliberately dark wire. **Honesty:** it is not free latitude — the
  sensor sees N stops less light (N stops more shadow noise), the stream and EGL target stay 8-bit
  (the extra stops share the same code budget), QTI local tone mapping under the curve stays
  unmeasured, and HAL AE may not reach the compensated target in every scene. **Known risk:** under
  HAL AE the in-REC snapshot is AE-ON at the operator's EV while the AE has converged at EV − N; a
  single still may not re-converge, so a dark in-REC still in video P is plausible until A17 says
  otherwise.
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
- **Stills are framed like the PREVIEW (owner decision 2026-10-27, "Auto and manual stills must
  match"; `StillFraming.kt`, `docs/FIELD_CHECKS.md` A23).** Under Active (PREVIEW_STABILIZATION)
  the HAL crops its EIS margin out of every processed stream of a request that carries the mode,
  and PMA110's HAL does the same under Standard (ON); a still at the still template's OFF gets the
  full field. Until this fix only a TRANSFORM_MATRIX (manual / preset
  / custom WB) still rode the session's mode — for its colour, the WB trap above — so on PMA110's
  logical 1× an AUTO still came out 15.6–16.7 % WIDER than the finder (bright frame, NCC / SIFT;
  Standard and Active alike), while a pseudo-ZSL-served AUTO still (a repeating
  frame) and every manual / preset still matched the finder: one bright press and one dim press of
  the same scene framed differently. Now EVERY still on EVERY profile — any WB mode, SINGLE /
  BURST / AEB / timelapse, the in-REC snapshot — carries the session's mode (GENERIC since the
  Flip6 measurement below; the `stillKeepsSessionStabilization` flag that gated it to PMA110 is
  gone). Measured after, HEAD `3c8bd2b3`, lit room: on the logical 1× and 3× under
  Standard and Active every AUTO / preset / manual still, every burst frame and every ZSL-served
  frame reads 0.856–0.864 of the unstabilized field and 1.000–1.004 of the finder (HEIF = JPEG);
  TELE (standalone 4 + 0x80b4) is cropped by neither mode at all — finder and stills 1.000. The
  in-REC snapshot rides the session's mode too, but on the video route the HAL crops the 16:9 video
  stream harder than a 4:3 still: the snapshot reads ~9 % wider (linear) than the recorded video,
  AUTO and manual alike. The other direction (every still at OFF) is CLOSED: a matrix
  still at OFF or ON inside an Active session renders another white balance. Two measured limits:
  (1) a still carrying PREVIEW_STABILIZATION completes up to 3.2 s and is LOST at 4 s
  (`onCaptureBufferLost`, no image; 1× / 3× / TELE alike), so a HAL-AWB still longer than
  `previewStabilizedStillMaxExposureNs` rides ON (completes, framed like the Active finder, same
  AWB colour); a matrix-WB 4 s still in Active has NO working mode (ON → deep blue, OFF → cyan, the
  vendor stabilization mirror changes nothing) and stays lost as it was since the matrix fix — an
  open owner decision, never a quiet wrong-colour file. (2) RAW is never cropped by stabilization
  (Camera2: PREVIEW_STABILIZATION covers "all other non-RAW streams"), so a DNG keeps the sensor
  field beside its narrower processed sibling (standalone 1× with DNG in Active: the DNG's field is
  1.165 × the JPEG's; TELE 1.000, nothing is cropped there); that is the sensor-native file, not a
  framing bug. Pseudo-ZSL gained a framing term (`ZslStillIntent.framedLikeRing` ←
  `zslStillFramingMatches`): a ring frame serves only when the real still would carry the ring's
  mode — always for a servable sub-1/15 s still. **The spec path, Galaxy Z Flip6 (2026-10-27):** its
  still template also carries OFF, so stills used to come out 1.24× wider than a Standard or Active
  finder (the finder is 0.807 of the field in both) and ZSL refused every stabilized press. Riding
  the session's mode (measured on experiment build `e8be6c6c`): Active stills read 1.000 of the
  finder (AUTO, 3000 K, 5000 K, Daylight, a 150 ms M still — its exposure ceiling), colour equal to
  an OFF session's, and a Standard-session still reaches the file in 0.51–0.57 s instead of the
  1.5–1.9 s it took to drop to OFF. Its Standard stills STILL read 1.24× the finder: that HAL crops
  only the preview under ON, which Camera2 allows (only PREVIEW_STABILIZATION promises one FoV
  across processed streams) — so Active is the WYSIWYG mode on a spec device. The long-still loss
  under PREVIEW_STABILIZATION is a PMA110 measurement; GENERIC has no limit and no device has shown
  one (the Flip6 tops out at 150 ms). Verify with
  `cmd media.camera watch` (the still frame's REQ `videoStabilizationMode`) AND the pixels — and
  check the installed APK hash first: on 2026-10-27 another session reinstalled its own debug app
  mid-batch (twice) and force-stopped / relaunched it with its own settings, and twenty minutes of
  runs measured that build (its AUTO stills at OFF looked exactly like an OEM rewrite).
- **TC session type 0x80b4 is ACCEPTED by the HAL (device-verified 2026-07-14) — the CameraUnit
  bypass.** Passing the stock app's TC operation_mode (0x80b4, captured from CamX
  `configure_streams`; stock pairs it with sensor mode 48 = the 300 mm TC OIS profile) as the
  SessionConfiguration sessionType on the standalone 3× camera configures a FULL session
  (fallback=0, stills+RAW alive, HEIC/DNG/4K-HEVC all verified, ois=1/vstab=2, clean 0x0 return
  on TELE off) — no AUTH_CODE needed. Lint WrongConstant is suppressed on configureSession with
  justification. Operator handheld A/B with the converter mounted (2026-07-28, `docs/FIELD_CHECKS.md`
  C3, closed) found no observable difference, so a distinct 300 mm OIS profile is not demonstrated;
  result metadata reads identically either way. Video + Log (maker) with TC does NOT use it: it runs
  the maker protocol (0x830b) on the logical camera instead (Gamma bullet), and C3 is why losing
  0x80b4 there costs nothing demonstrated. Every other TC case still configures 0x80b4 on
  standalone 4 (device-rechecked 2026-10-26 with TC + S-Log3 and TC + Off).
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
  passthrough-JPEG lane (pixels verbatim, no 200 MP decode; the shot's composed EXIF APP1, with
  orientation, is spliced in before the single write, and the HAL-seeded identifying tags —
  GPS, serials, owner, unique id, MakerNote — are stripped, AGG5-51), and the `HR` OSD tag keys on
  accepted-session truth.
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
- **Video codec/candidates/transfer are one independently owned rollback packet.** The operator's
  next-Video curve is retained separately from Photo's active SDR transfer; one
  `setVideoPipeline` command publishes codec, ordered candidates, requested transfer, and active
  Camera2/GL transfer under a monotonic pipeline-publication generation, while settings/MR puts the
  same immutable selection inside `setResolvedOptics`. Rollback restores its baseline packet only
  while that optics transaction still owns the pipeline generation; a newer codec/curve command
  survives in Engine, GL, the generation-checked ViewModel publication, and the next REC admission.
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
  then reports and schedules bounded recovery exactly once — once per CONTROLLER
  (`recoveryScheduledFor`), not once per report: a controller can report a configure failure and its
  device's onError back to back, and each used to schedule its own reopen.
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
  publication and consume its exactly-once `Result`. Under one Engine-monitor boundary, recording
  admission freezes the complete REC packet: accepted Camera2 controller/session, video size,
  selected frame/capture rate, codec, accepted transfer, and exact ordered encoder candidates. That
  immutable packet is carried through GL/native recorder setup rather than re-reading independently
  mutable fields, and its session ownership is atomically rechecked at publication. Until attach
  succeeds the UI is stoppable/locked but shows no tally or timer. An owned
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
  against an unpublished owner. **The PENDING token's own workers are admitted by TOKEN, exactly as
  an active token's are (device-found on TB336ZU 2026-09-09; scoped to the token 2026-10-02).**
  They enter through `runRecorderWorkerNative(token)`, never the owner-keyed door: the owner is the
  whole Engine, so the first fix (admit the pending token's OWNER) also re-admitted that Engine's own
  GL/EGL and Camera2 acquisitions mid-setup. The owner-keyed door refuses every caller while any
  token is pending. Recorder setup runs under the pending token and spawns the audio-encode worker,
  which reaches `AudioRecord.startRecording` before the Engine publishes the token whenever the
  encoder's first swap is slow (two of three takes on that MediaTek tablet); the gate used to
  reject ANY caller while a token was pending, so the worker exited silently with two tracks still
  expected and the 1 s muxer rendezvous degraded the take to a SILENT clip. Five of five takes
  carried AAC under the first (owner-admitting, 0ab5c1ba) fix; the current token-scoped door
  (4e57fff2) is host-tested only and PENDING DEVICE on TB336ZU (`docs/FIELD_CHECKS.md` D2). A silent clip with `AudioRecord: set/openRecord` but no `start` in
  logcat is this race, not a mic fault.
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
  **The app requests CAMERA and RECORD_AUDIO at runtime and declares one install-time permission,
  MODIFY_AUDIO_SETTINGS (2026-10-26, for the Sound Focus / Sound Stage parameters — see that bullet).
  The visual-media READ trio — READ_MEDIA_IMAGES /
  READ_MEDIA_VIDEO / READ_MEDIA_VISUAL_USER_SELECTED — was REMOVED 2026-10-19 (owner decision) and
  must not be re-added.** It was declared 2026-08-01 and requested at an empty-gallery tap so a
  reinstall's owner-cleared captures could seed in-app review, but Google Play flagged it under the
  Photo and Video Permissions policy ("use alternative system pickers"): broad photo/video access is
  reserved for apps whose CORE purpose needs it (galleries, editors), and a camera app reviewing its
  own captures does not qualify — an approved declaration did not protect it. In-app review is back
  to scoped storage's own-rows visibility; the accepted cost is that after a reinstall the previous
  install's captures appear only in the system Gallery. The empty-gallery tap now just retries the
  own-rows restore (`onGalleryAccessRequested`). The source manifest strips all three with
  `tools:node="remove"`, `tools/check_docs.py` pins those guards, and
  `tools/check_release_artifact.py` names any of them in the packaged manifest as forbidden
  (`FORBIDDEN_RELEASE_PERMISSIONS`). **There is still NO location permission and no
  location code, so captures carry no GPS tags** (verified by parsing a saved HEIF's TIFF IFDs: no
  GPS IFD pointer in IFD0 or ExifIFD). The one lane seeded from a HAL's own EXIF, the dormant-on-PMA110
  hi-res passthrough JPEG, strips the HAL's GPS directory, serials, owner name, unique id and
  MakerNote before composing (`PASSTHROUGH_PRIVACY_STRIPPED_TAGS`). A saved DNG's tags come from
  `DngCreator`, and its serial/unique-id absence remains a field check: `docs/FIELD_CHECKS.md` A14. Adding geotagging would be a new permission, a new Data
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
  reads through the same `AudioReadPolicy`: a zero-byte read retries, a negative read ends that AudioRecord
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
  `StillCapturePipeline` composes the shot's EXIF APP1 ONCE through `composeStillExifApp1` (the
  attributes go into a cache-only 1×1 JPEG seed through ExifInterface and the APP1 payload is
  extracted; no user media is opened). HEIF passes that payload to `HeifWriter.addExifData`; both
  JPEG lanes (processed and hi-res passthrough) splice it into the ENCODED buffer before the single
  pending-row write. There is no in-place `saveAttributes()` rewrite of the row any more: that
  rewrite grew the file without truncating, so a kill mid-rewrite left a shifted body that still
  ended `FF D9` and recovery adopted it as valid. ISO / exposure / 35mm focal / make / model
  therefore stay in parity across both processed formats; the device readback of a JPEG+HEIF pair
  from the splice lane remains a field check (`docs/FIELD_CHECKS.md` A7). Lightweight physical-lens metadata is prefetched on
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
  Destructive identity is frozen after REGISTERED. If that provider read is unavailable, ambiguous,
  or mismatched, a reservation acquired before insert transfers the row to one finite process owner;
  one daemon scheduler retries it non-destructively with bounded exponential backoff and one
  scheduled/in-flight retry per claim. An exact identity authorizes DISCARD; stable provider absence
  clears only the REGISTERED metadata; ambiguity/mismatch/unavailability remain fail-closed. The
  owner closes new image/video admission at capacity rather than forgetting rows until restart, and
  every reserve/retry/terminal edge publishes through the process admission signal so a replacement
  Engine cannot miss capacity reopening.
  The earlier edge is finite too: if the REGISTERED commit itself fails after insert, one immediate
  delete does not license forgetting the URI. Only authoritative provider absence plus successful
  preference cleanup releases the pre-insert reservation; a present/unavailable row, failed delete,
  or failed cleanup transfers the URI/family to that same typed retry owner.
- **MediaProvider answers an `EXTERNAL_CONTENT_URI` insert with a URI on the `external` UNION
  pseudo-volume, and that name is NEVER a mounted volume (device-found 2026-09-09 on BOTH Lenovo
  tablets, TB331FC/Android 15 and TB336ZU/Android 16; the maintainer's PMA110 runs never showed
  it).** `MediaStore.getVolumeName(insertedUri)` returns `"external"` on mainline MediaProvider,
  while `getExternalVolumeNames()` lists `external_primary` and card UUIDs only. The creation-time
  identity reader treated that as an unmounted volume, so EVERY capture was refused as "identity
  uncertain": `HEIF save failed` / `DNG save failed` / `Recording failed` toasts, the row deleted,
  no file, and NO log line, because the insert and the read both sat under
  `runCatching { }.getOrNull()`. Each refused row then parked in the finite identity-recovery
  owner, which retried the same failing read on backoff (the `V MediaStore: Examining volume` rows
  every 0.5/1/2/4 s ARE that loop) until capacity closed all photo AND video admission for the
  process, and launch recovery re-filled it after a relaunch, which read as "capture broke after a
  mode switch and never recovers" and was first filed against the camera. The reader now mirrors
  the provider's own `resolveVolumeName` (union → primary), confirms it against the row's
  `VOLUME_NAME`, and every silent exit on the insert / registration / identity path logs a
  reserved diagnostic row. A save that fails with no app log line is the signature of THIS class
  of defect: find the swallowed exception before touching the camera.
- **DNG publication does not hold the camera callback.** `DngCreator.writeImage` and the durable
  `COMPLETE` marker attempt remain synchronous while the RAW `Image` is valid; `saveDng` returns a
  frozen `PendingDngPublication` carrying whether that commit succeeded. Only `publishDng`
  (including the durable gate, resolver retry backoff, and callbacks) leaves the camera thread.
  EVERY completed DNG tail uses one process-wide finite owner (two daemon workers + two backlog
  slots) shared across Engine generations. RAW-only SINGLE/BURST/AEB/timelapse transfer directly;
  mixed-output tails queue only a lightweight transfer behind their processed sibling's terminal on
  `ioExecutor`, then publish on that same process owner. A mixed tail whose processed sibling was
  never queued has nothing to wait behind and transfers DIRECT like RAW-only output. Capacity
  overflow, facade shutdown, a real ordered-lane transfer rejection, or marker exhaustion
  settles the live capture family exactly once and keeps the structurally complete private row for
  launch recovery; provider work never falls back inline and complete DNG bytes are never deleted for
  lack of live publication capacity.
  A failed DNG write also does not turn into provider work on that callback: RAW admission reserves
  one process-finite rejected-output slot before Camera2 dispatch, and the failure returns an exact
  creation-time allocation packet to that two-worker/eight-backlog owner. MediaStore identity reads,
  DISCARD SQLite commits, retry sleeps, delete/probe work, and completion callbacks therefore run
  only after the live `Image` can close; saturation/shutdown keeps REGISTERED recovery truth and
  never falls back inline.
  DNG allocation itself has an 8 s first-wins deadline before Camera2 dispatch. Timeout or scheduler
  rejection releases capture/family/snapshot admission immediately; a late exact row remains bounded
  cleanup/recovery work. Once Camera2 owns the request, every no-complete-DNG terminal transfers the
  preallocated row through its reserved cleanup. DNG and MediaStore capacity are exact closeable
  process subscriptions: acquire/release/reserve/retry terminals recompute the combined shutter gate
  for every current Engine, while detach drains the old callback before its graph is cleared.
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
  startup trace and the focus-verdict trace outright; the threshold is STRICT (`gap > 200`), so it
  catches swap stalls whose producer gap exceeds 200 ms (the upper part of the measured 170–250 ms
  `setRepeatingRequest` band) and real wedges, while a swap stall at or under 200 ms is not counted;
  normal cadence is not news. (2) `StartupTrace`
  BUFFERS its marks and emits the whole cold start as ONE line at `finish()` — a requirement, not
  tidiness: per-mark logging gets silently eaten before it reaches logcat. Same rule for the
  `FocusConfidence` trace (change-gated with at least 3 s between changes, plus a 15 s heartbeat). The debug 3A trace likewise samples a
  bucketed state tuple, paces changes to at least 3 s, and uses a 15 s stable heartbeat (41 rows over
  the ten-minute A5 soak). Under continuous tuple changes its pacing alone would allow ~201 rows,
  MORE than the whole 168-row shared pool below, so 3A can exhaust that pool and starve every other
  shared producer; the pool, not the pacing, is the bound. The sustained-YUV probe
  accumulates cadence in constant memory and emits only enable + terminal summary rows. **Any new
  per-frame or per-tick log must be change-gated or thresholded.** (This quota is also what made the removed OPPO CameraUnit SDK's
  200+ startup rows decisive — see that bullet.)
  `FrameGap` measures only REAL SurfaceTexture frames: cached zoom redraws own a separate render
  timestamp and can neither hide nor invent a producer stall. Qualifying gaps accumulate in constant
  memory and emit one first/15-second/terminal summary with count, maximum, and 200–399/400–999/
  ≥1000 ms buckets, rather than one row per broken frame. Capture-family edges, ShutterLag/ZSL
  decisions, standby/audio shape, hardware, zoom, motion, focus-confidence, Touch-AF scan/reset, 3A,
  session/recording information, and those summaries share one 180-row recurring class: 168
  shared rows plus a 12-row EVIDENCE reserve that only the cold-start line and the TERMINAL FrameGap
  summary may spend, and only once the shared rows are gone — both are read as negative evidence
  ("no line" = "no stall" / "no measurement"), so a chatty soak must not be able to silence them.
  The reserve is two separate 6-row slices, one per producer, so repeated cold starts cannot spend
  the FrameGap row, and a FrameGap window is cleared only after its row is ADMITTED: a refused
  summary's stalls carry forward into the next admitted (ultimately the terminal) row.
  Every warning/error crosses a separate 120-row process owner, making the complete runtime maximum
  exactly 300. **A gated row is charged ONCE:** a caller that already passed
  `recurringDiagnosticAllowed`/`tapFocusDiagnosticAllowed`/`processDiagnosticLogBudget.tryAcquire`
  emits through `android.util.Log`, never back through the `DiagnosticLog as Log` door — ~28
  producers did, which halved the real allowance and spent the last row with nothing logged. The
  executable source inventory accepts only a real bounded facade/guard (and rejects that double
  charge); severity or an anchor string no longer pretends an unbounded producer is reserved.
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
  screen 5.2 s later because the former wording-classified `statusDisplayDurationMs` put this
  message in the neutral 2.5 s bucket. Duration is now TYPED, not inferred from wording:
  `CameraStatus.durationMs`, resolved per `CameraStatusMessage` through `CameraStatusLifecycle`. **A PROGRESS status must carry no timer** — it reports a condition,
  so an EVENT ends it (`CameraStatusLifecycle.PROGRESS` → null duration; the owned Ready publication clears it,
  guarded on the message still being that status so a message published during bring-up is not
  swallowed). The same rule covers reconfiguration, preview/camera recovery, and bounded retry
  conditions: Ready, rollback/exhaustion, pause, or a newer PROGRESS status ends them. A transient
  EVENT status only SHADOWS one: the plate is rank-arbitrated, not last-writer-wins (`StatusPlate`,
  `StatusPlateRank`), so the condition waits behind the event as `deferredProgress` and returns when
  that event expires — while a lower-ranked AMBIENT event can no longer replace an unexpired error or
  retained-take instruction. A RESPONSE to the operator's own input (`RESPONSE_MESSAGES`: refusals,
  MR results, delete outcomes, `VIDEO_SAVED`) always takes the plate and only COVERS that higher
  event, which returns for its remaining time; a status that RESOLVES the shown event (`DELETED` after
  a failed delete, `VIDEO_SAVED` after "without audio") wipes it. An ERROR-severity condition ranks at
  ERROR for arbitration. A condition-ENDING event (`CAMERA_CONDITION_ENDING_MESSAGES`) ENDS the
  condition instead, shown or deferred — deferring it there resurrected "retrying…" with no timer
  after the error expired — but only its own family (`endsCondition`): the `*_REOPEN` exhaustion ends
  every condition, a `*_UNCHANGED` rollback only the optics ones (`OPTICS_CONDITION_MESSAGES`), never
  a preview/camera-health recovery running beside it. A timer is wrong both
  ways: too long makes a fast transition read slow, too short claims ready before it is. Measure the
  pipeline before believing a latency report, and measure the pill too.
  Each armed measurement belongs first to one exact Engine resume/open transaction. That Engine
  passes the opaque owner explicitly through GL/preflight/retry/open and binds it only after its one
  CameraController is installed; wiring never samples a mutable process-global owner. Pause,
  release, quarantine, supersession, GL replacement, or a second controller revokes the exact owner.
  Marks, finish, and disarm require it, and only the latest repeating-request generation may finish
  it, so a queued callback from a pre-pause/replaced controller cannot fabricate the next resume.
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
- **Debug capability diagnostics queue behind initial camera work.** The debug-only camera inventory
  dump (`VendorTagInspector`: facing, focals, sensor size, physical ids, and the concurrent/physical
  finder feasibility probes — no request/session/vendor-key dump any more) runs on `setupExecutor` only after the initial route/open task is enqueued, so
  diagnostics cannot delay the first Camera2 setup task. It runs ONCE per process at INFO through the
  recurring door: the rows are facts, not faults, and at warning level per Engine start they used to
  spend the 120-row reserved owner that real onError/configure warnings need.
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
             ├─ P010InputBridge  ten-bit native log: P010 reader → ImageWriter → GL input SurfaceTexture
             ├─ RawLogBridge     EXPERIMENTAL RAW log: RAW reader → own EGL thread → S-Log3/S-Gamut3.Cine → GL input
             ├─ FacePriority     Face Priority AF gate/request keys/readout gate/sensor→view map (pure)
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
