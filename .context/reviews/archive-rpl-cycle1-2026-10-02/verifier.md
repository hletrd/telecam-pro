# Verifier review: CLAUDE.md / ARCHITECTURE.md invariants vs. code (2026-09-30)

Scope: I listed the code invariants that CLAUDE.md ("Hard-won device facts", constraints,
conventions) and `docs/ARCHITECTURE.md` state in checkable form, then compared each one with
`app/src/main/kotlin/**` and, where relevant, `app/src/test/**`. This was a read-only pass: no
build, no device. Line numbers are against HEAD `ba5b16e7`.

Result: **8 violations or partial implementations** (2 High, 2 Medium, 4 Low), plus 3 stale
code comments that contradict code a few lines away. About 70 invariants checked OK (table at
the end).

---

## V1. Restoring settings or recalling MR with DNG on reads the lens-local zoom as unified and drops the lens band (HIGH)

- **Where:** `ui/ZoomMath.kt:372-402` (`restoredOptics`), called from
  `ui/CameraViewModel.kt:1328` (`applyLoaded`, which serves both settings restore and MR recall).
- **Stated invariant:** "The zoom SCALE follows the ROUTE, not the mode … `zoomRatio` is
  main-relative on the logical seamless camera and LENS-LOCAL on any standalone one … wanting
  DNG is itself what moves photo off the seamless camera" (CLAUDE.md). ARCHITECTURE.md §Zoom
  adds: "Photo, TC off, RAW/DNG wanted → Standalone rear lens … Lens-local zoom … route-scale
  conversion follows the standalone home."
- **What the code does:** `restoredOptics` decides the scale from `mode` alone. For PHOTO it
  always runs `unified = safeZoom.coerceIn(0.6, 20)` and `lens = LensChoice.forZoom(unified)`.
  It ignores `requestedLens` and takes no RAW or standalone input. The value it receives is
  lens-local whenever DNG is on:
  - `saveSettingsIfEnabled` (`CameraViewModel.kt:1605-1624`) persists `s.controls.zoomRatio` as
    it is. On the DNG standalone route that value is lens-local.
  - The preserve branch (`CameraViewModel.kt:1299-1311`) goes further. It calls
    `standaloneRouteWanted(...)` itself and computes a lens-local ratio
    (`zoomPreset / opticalBase`), then passes it to `restoredOptics`, which reads it as unified.
    The same function converts to local and then misreads the result.
- **Failure scenario (PMA110):** Photo, DNG on, 3× lens tapped. The session is standalone
  70 mm and the wire zoom is lens-local `1.0`. Background the app and relaunch (Remember
  Settings and Preserve Lens are both on by default).
  - `restoredOptics(PHOTO, TELE3X, …, 1.0)` returns `lens = MAIN, zoom = 1.0`.
  - `setResolvedOptics(resolvedLens = MAIN)` and `setRawWanted(true)` follow, so
    `resolveNonTeleId(MAIN)` opens the standalone 23 mm main at 1.0.
  - The operator's 3× (or 10×) selection is silently lost on every launch and every MR recall
    while DNG is on. A saved local 2.0 on the 10× lens comes back as 2× on the main lens.
- **Tests:** `ZoomMathTest.kt:124-160` covers only non-DNG photo, video, and TELE. No test pins
  a DNG photo restore.
- **Fix:** Give `restoredOptics` the route: `standaloneRoute = standaloneRouteWanted(mode ==
  VIDEO, safeFormats.dngRaw, engine.rawForcesStandalone)`. When it is true, keep
  `requestedLens`, treat `savedZoomRatio` as lens-local, and clamp it to `[1, MAX_VIDEO_LOCAL_ZOOM]`
  or the lens-local contract, as the VIDEO branch already does. Add a
  `photo DNG restore keeps lens band and local ratio` test.
- **Confidence:** High on the code contradiction. The device symptom is inferred and not
  reproduced.
- **Status:** Open.

## V2. Two Engine same-camera fast-path commits still ask "is this video?" before `LensChoice.forZoom` (HIGH, partial fix of the route-scale invariant)

- **Where:** `camera/CameraEngine.kt:2665-2667` (in `setVideoMode`, same-camera
  `commitFastPathOrReconfigure` terminal mutation) and `camera/CameraEngine.kt:2818-2820` (in
  `setResolvedOptics`, the non-structural fast path).
- **Stated invariant:** CLAUDE.md: "Six places decided which by asking `mode == VIDEO` … the
  rail collapsing to 1× because `forZoom()` read a lens-local 1.0 as unified … ONE
  round-tripping pair now owns the conversion, keyed on `standaloneRouteWanted(...)`."
  `reconcileControlsWithCaps` (`CameraEngine.kt:583-593`) carries this exact fix with the comment
  "This asked `!videoMode`, which misses the DNG door".
- **What the code does:** Both terminal mutations still run
  `if (!enabled/!enabledVideo && !teleconverterMode && route == BACK) lensChoice = LensChoice.forZoom(controls.zoomRatio)`.
  They have no `standaloneRouteWanted(videoMode, rawWanted, profile.rawRequiresStandalone)`
  term, so on a Photo+DNG standalone route a lens-local ratio is read as unified.
- **Failure scenario:**
  - (a) MR recall of a photo bank on the same standalone camera while DNG is on (for example
    3× standalone at local 1.0) takes the non-structural path. The Engine sets
    `lensChoice = MAIN` while the camera stays on the 70 mm lens. The next reopen (aspect,
    hi-res, fps) then uses `resolveNonTeleId(MAIN)` and jumps to the 23 mm lens.
  - (b) Video→Photo with DNG on and Open Gate (same camera, and possibly the same 4:3 preview
    stream) takes the same branch. The VM's `remapModeOptics` keeps TELE3X
    (`photoIsStandalone` early return) while the Engine now holds MAIN, so Engine and UI
    diverge.
- **Tests:** None. `reconcileControlsWithCaps` has the right predicate, but the two fast-path
  mutations are not covered.
- **Fix:** Use the same predicate as `CameraEngine.kt:589` at both sites:
  `!standaloneRouteWanted(<targetVideo>, rawWanted, activeDeviceProfile().rawRequiresStandalone) && !TC && route == BACK`.
  Better still, move it into one helper that all three sites call.
- **Confidence:** High on the contradiction. Medium on how often scenario (b) can be reached.
- **Status:** Open.

## V3. FRONT has facing special cases for RAW and for the Loupe Overview, which CLAUDE.md says do not exist (MEDIUM, doc and code disagree)

- **Where:** `camera/CameraController.kt:2705` (`useRaw = … && !frontRoute && …`),
  `camera/OpticsConstraints.kt:92-97` (`rawSelectable(… frontFacing)` → `!frontFacing`), and
  `camera/CameraState.kt:414` (`punchInResolved(enabled, frontFacing) = enabled && !frontFacing`,
  which gates `teleFinderVisible`, i.e. the Loupe Overview).
- **Stated invariant:** CLAUDE.md, front-camera bullet: "RAW, hi-res, flash, and the Loupe
  Overview all resolve off the existing capability/route axes — **no facing special cases in
  those predicates**."
- **What the code does:** RAW is hard-excluded on FRONT whatever the capability. The punch-in,
  and with it the Overview, is hard-suppressed on FRONT. Both are deliberate and tested
  (`SessionFallbackLadderTest` "the front route drops RAW on every device — its readers are gone
  by design").
- **The rationale is itself stale.** `CameraController.kt:2704` says "that route drops both
  still readers by design". The current front route keeps its readers: deep YUV, then shallow
  YUV, then HAL-JPEG (`CameraController.kt:645-652`, CLAUDE.md pseudo-ZSL bullet). The RAW
  exclusion therefore has no current justification in the code.
- **Failure scenario:** On a GENERIC device whose front camera advertises RAW, the DNG chip is
  disabled on FRONT with no measured HAL reason. A maintainer who trusts CLAUDE.md and adds
  facing-free RAW logic elsewhere will contradict the plan's hard `!frontRoute`.
- **Fix:** This needs an owner decision.
  - Option 1: keep the exclusions and correct the CLAUDE.md bullet and the
    `CameraController.kt:2704` / `OpticsConstraints.kt:89-90` rationale.
  - Option 2: drop `!frontRoute` / `!frontFacing` for RAW and let `supportsRaw` plus the
    standalone law decide. On PMA110 this is still guarded by `rawRequiresStandalone`, since
    front is plain/standalone.
- **Confidence:** High that they disagree. Which side is intended is unknown.
- **Status:** Open (owner decision).

## V4. After the 10-bit video rung fails, the ladder does not fall to "the ordinary 8-bit ladder" (MEDIUM)

- **Where:** `camera/CameraController.kt:2659-2691` (`sessionAttemptPlan`).
- **Stated invariant:** The code comment at 2659-2663 says: "Attempt 0 only: any later attempt
  falls straight through to the ordinary 8-bit ladder." CLAUDE.md says: "HLG10 preview +
  full-res JPEG + RAW together crash it … whose ladder rung configures HLG10 with **no still
  readers at all**."
- **What the code does:**
  - The 10-bit rung takes attempt 0 but, unlike the hi-res rung, does not shift the ladder. So
    attempt 1 maps to `ladderAttempt = 1`.
  - With `wantHlg = tenBitHlg && supportsHlg10()` still true, that gives
    `useHlg = streamAttempt < 2 = true` and `useJpeg = true`. In other words HLG10 plus the
    full-res still reader. That is not 8-bit, and it is two of the three members of the
    documented crash combination.
  - The ordinary rung 0 is never tried, and `maxSessionAttempt` is not extended.
- **Failure scenario:** A rejected HLG10 still-less configure (a spec device, or a transient
  HAL refusal) retries HLG10 + JPEG/YUV. The accepted session then carries stills in a non-SDR
  video session. That contradicts the UI's `"10-bit video · stills off"` model and the
  `acceptedOpticsAuxState` still-less assumption. On PMA110 it risks the HLG+still configure
  that the rung exists to avoid.
- **Tests:** `SessionFallbackLadderTest.kt:389-405` pins attempt 0 only. Attempt 1+ with
  `tenBitVideoOnly = true` is untested.
- **Fix:** Decide the intended fallback.
  - If it is 8-bit: `useHlg = wantHlg && !tenBitVideoOnly && streamAttempt < 2`, and shift the
    ladder the way hi-res does.
  - If HLG+JPEG is acceptable: correct the comment and CLAUDE.md.
  - Either way, add attempt-1/2/3 assertions.
- **Confidence:** Medium. It is certain that code and comment disagree. Whether HLG10+JPEG
  without RAW crashes on PMA110 is unmeasured.
- **Status:** Open.

## V5. The debug `nativelog` flag reaches the Camera2 session, which its own KDoc says it does not do (LOW, debug-only)

- **Where:** `camera/CameraEngine.kt:2407` and `4177`:
  `tenBitHlg = tenBitSessionWanted(videoMode, transfer) || tenBitExperimentEnabled()`.
- **Stated invariant:** KDoc at `CameraEngine.kt:7502-7504`: "DEBUG-only RGBA1010102 EGL
  experiment gate. **It does not arm** native log or **the shipping HLG10 Camera2 session**."
  The GL-start comment at 1789-1791 says the same: "This separate debug flag asks only for an
  RGBA1010102 EGL target".
- **What the code does:** With the flag file present, `tenBitHlg` becomes true in PHOTO too. It
  selects the still-less HLG10 rung (V4), so a debug photo session has no still readers.
- **Failure scenario:** A debug build with a leftover `nativelog` file produces a photo mode
  that cannot shoot. A developer debugging "shutter disabled" gets a false lead.
- **Fix:** Drop the `|| tenBitExperimentEnabled()` term (the EGL target already reads it at
  1792), or correct the KDoc.
- **Confidence:** High. **Status:** Open.

## V6. An older settings blob with no phone key restores the Find X9 Ultra on unknown hardware (LOW)

- **Where:** `storage/SettingsStore.kt:270` (`enumOr(safeString("phoneModel"), ed.phoneModel)`
  with `ExtraSettings.phoneModel = DEFAULT_PHONE_MODEL` at line 61). The same applies to every
  MR slot prefix.
- **Stated invariant:** CLAUDE.md, TC rule 2: "An UNRECOGNISED phone seeds `PhoneModel.OTHER`,
  not the Find X9 Ultra … leaving the default standing on foreign hardware showed a Lenovo
  tablet owner 'Phone: OPPO Find X9 Ultra' with a Hasselblad 300 mm kit".
- **What the code does:** `seedPhoneModel` correctly seeds OTHER
  (`CameraViewModel.kt:2664`). `restoreSettingsIfEnabled` then applies the blob. A blob or MR
  bank written before the phone key existed (pre-2026-07-26) fills in `DEFAULT_PHONE_MODEL`
  (FIND_X9_ULTRA) and replaces the OTHER seed. `reconcileConverter` then keeps the Explorer 300
  kit.
- **Failure scenario:** A non-PMA110 install upgraded from a pre-phone-key build sees exactly
  the "OPPO Find X9 Ultra / Hasselblad 300 mm / 300 mm readout" state that rule 2 exists to
  prevent. EXIF focal is wrong from then on.
- **Fix:** When the key is absent, fall back to the already-seeded detection
  (`detectPhone(Build.MODEL) ?: OTHER`) instead of `DEFAULT_PHONE_MODEL`. One way is to pass
  the seed into `load()`, or to return a nullable phone and resolve it in the VM.
- **Confidence:** Medium. It depends on legacy blobs existing on foreign hardware.
- **Status:** Open.

## V7. Lens-match tolerance is log-symmetric ×1.35, not "±35%" (LOW, doc precision)

- **Where:** `camera/CameraState.kt:881, 900-906` (`LENS_MATCH_TOLERANCE = 1.35f`, ratio
  `max/min`).
- **Stated invariant:** CLAUDE.md: "optical when a back lens's measured 35 mm-equivalent is
  **within ±35%** of the preset target".
- **What the code does:** It accepts +35% / −25.9%. A lens 30% short of a target is rejected,
  although the doc text admits it. The code comment's bands (main 17.0-31.1) match the code, so
  CLAUDE.md is the imprecise side.
- **Fix:** Change the CLAUDE.md wording to "within a ×1.35 ratio (log-symmetric)".
- **Confidence:** High. **Status:** Open (doc only).

## V8. Stale in-code statements that contradict nearby code (LOW, comment drift)

These are grouped because none changes behavior, but each has already misled at least one of
the doc passes above.

1. `ui/CameraViewModel.kt:2651-2653` (KDoc of `seedPhoneModel`) says "On an unrecognised phone
   **nothing is seeded**: the state defaults stand". The body (2656-2664) seeds `OTHER`.
2. `ui/CameraViewModel.kt:378` says "The ONE android.os.Build.MODEL read in the app (see
   `seedTeleconverterProfile`)". That function no longer exists (it is `seedPhoneModel`), and
   `Build.MODEL` is also read at `CameraEngine.kt:1171, 7779, 7786` and
   `CameraController.kt:67`. The sanctioned `DeviceProfile.resolve` seam and EXIF labels are
   legitimate reads, so only the comment is wrong.
3. `camera/CameraController.kt:2696-2697` says "DNG therefore exists only in TELE mode
   (standalone 3×) and on any explicit standalone selection". This contradicts the 2026-07-29
   route-input model ("DNG is available on EVERY lens"), which the code implements through
   `standaloneRouteWanted`.

- **Fix:** Update the three comments.
- **Confidence:** High. **Status:** Open.

### Considered and not raised

- `setRawWanted` returns early while `started && paused` and leaves `overrideId` cached. On
  resume `currentOpticsReconfiguration()` would reuse it. The only paths that could call it while
  paused are `applyEncoderInventory`, which never changes `dngRaw` because it only normalizes
  HEIF, and restore, which runs pre-start. I found no reachable trigger, so this is not reported.
- The Loupe Overview draws `mirrorX = false` (`GlPipeline.kt:1109`), and its comment says the
  finder "requires TC". The predicate does not require TC, but `punchInResolved` suppresses the
  finder on FRONT, so no mirror mismatch can be seen. Not raised; see V3 for the facing special
  case itself.

---

## Invariants verified OK

| # | Invariant (CLAUDE.md / ARCHITECTURE.md) | Evidence |
|---|---|---|
| 1 | `setRawWanted` begins its own optics transaction with `overrideId = userCameraPin` and re-resolves | `CameraEngine.kt:3979-3981` |
| 2 | Restore pushes the DNG route input to the Engine | `CameraViewModel.kt:1461`, `2626` |
| 3 | `acceptedOpticsAuxState` normalizes `photoFormats` only when a still target exists, and never clears RAW | `OpticsConstraints.kt:68-72` |
| 4 | DNG chip gated by `rawSelectable`, not session truth or bare capability | `ProSheet.kt:884-890` |
| 5 | `resolveNonTeleId` keys on `standaloneRouteWanted` | `CameraEngine.kt:3943-3951` |
| 6 | `reconcileControlsWithCaps`, VM zoom flush, and VM caps reconciliation use `standaloneRouteWanted` for `forZoom` | `CameraEngine.kt:589`, `CameraViewModel.kt:2181-2186`, `2968-2974` |
| 7 | `unifiedZoomOf` / `localZoomOf` divide by the OPTICAL base lens (`opticalBaseFor`) | `CameraState.kt:445-474` |
| 8 | `sessionAttemptPlan` RAW gated `standalone && !logicalMultiCamera` under `rawRequiresStandalone`; hi-res rung prepended; `maxSessionAttempt` +1 | `CameraController.kt:2674-2706, 2756` |
| 9 | Selector: closest to 70 mm, standalone preferred on ties, fails closed to standalone | `CameraSelector2.kt:344-364` |
| 10 | `pickFront` enumerated, plain-id preferred, largest array, no hardcoded id | `CameraSelector2.kt:374-427` |
| 11 | `backOpticsDoorRefusal` single seam | `CameraState.kt:52` |
| 12 | Facing never persisted | `SettingsStore.kt` (no facing key) |
| 13 | `FrameGap` threshold 200 ms, constant-memory summaries | `DiagnosticTelemetry.kt:127-170, 340`; `GlPipeline.kt:949` |
| 14 | Log quota 180 recurring + 120 reserved = 300; all direct `android.util.Log` calls sit behind the budget | `DiagnosticTelemetry.kt:31-73`; `MainActivity.kt:760`, `GlPipeline.kt:550, 887`, `FlipRenderer.kt:339` |
| 15 | SettingsStore uses `edit(commit = true)` everywhere; no `apply()` | `SettingsStore.kt:128-160`; `MainActivity.kt` |
| 16 | Remember Settings defaults ON; preserve lens/TC default ON | `SettingsStore.kt:125, 107-108` |
| 17 | Legacy `"LOG"` migrates to `SLOG3_CINE` | `SettingsStore.kt:273-277` |
| 18 | `openCamera` wrapped (`runCatching` inside `runNativeAcquisition`) | `CameraController.kt:330-332` |
| 19 | `SystemBarStyle.dark` on BOTH bars | `MainActivity.kt:210-211` |
| 20 | Portrait lock only when `smallestScreenWidthDp < 600`; manifest has no `screenOrientation` | `CameraScreenPolicy.kt:66`, `MainActivity.kt:353-354`, `AndroidManifest.xml:76` |
| 21 | Tap-AF: 2 s timer is visual only; release on new tap, focus-mode change, reset, remap | `CameraViewModel.kt:1908-1931, 1868-1893, 1935` |
| 22 | `HAL_SAFE_MAX_STILL_EXPOSURE_NS = 4 s` at the caps seam, PMA110 profile only; GENERIC trusts advertised | `CaptureCapabilities.kt:20`, `DeviceProfile.kt:67, 76` |
| 23 | `PREVIEW_FLUIDITY_MAX_EXPOSURE_NS` = 1/15 s, `PREVIEW_SAFE` = 500 ms, digital gain ≤ ×16 | `ManualControls.kt:325, 340, 341, 436` |
| 24 | Watchdog: HAL-auto 8 s; manual = ceil-ms exposure + 8 s, saturating | `ManualControls.kt:484-512`; `CameraController.kt:2019-2028` |
| 25 | Video AUTO `pinAutoFps = videoMode`; photo uses `autoFpsRange` | `CameraEngine.kt:2414, 4182`; `CaptureCapabilities.kt:263` |
| 26 | AE `maxStepStops = 0.5×\|err\|` in [0.30, 1.20]; GAIN 0.6 < 1 means no overshoot | `AutoExposure.kt:44-51, 78-79` |
| 27 | ZSL: 1/6 stop, zoom 2%, age ≤ 400 ms, app-side AE-OFF, processed-only, no AE-flash, no gesture | `ZslAdmission.kt:36-42, 74-100` |
| 28 | Front mirror roles through `FrontMirrorConvention` for preview, encoder, analysis, tap, metering | `FrontMirrorConvention.kt`; `GlPipeline.kt:1054, 1248, 1481` |
| 29 | Capture rotation BACK = sensor + afocal − dev; FRONT = sensor + dev; EXTERNAL = sensor | `RotationMath.kt:149-162` |
| 30 | Glyph rotation `+dev` residual; 0 when window follows device | `RotationMath.kt:98-102` |
| 31 | Gravity guards: FLAT 4.9, LEVEL 2.5, `hypot` gate | `GyroEis.kt:328-359` |
| 32 | App gyro EIS disabled (`gl.setEis(false, 0, 0)`) | `CameraEngine.kt:1927` |
| 33 | `FINDER_MIN_ZOOM = 3f`; Photo needs 4:3, Video ignores aspect; gate uses unified zoom | `CameraState.kt:397, 616-627`; `CameraEngine.kt:7484` |
| 34 | Finder draw scissor in `try/finally`, afocal term omitted through `rotationOverrideDeg` | `GlPipeline.kt:1092-1121` |
| 35 | `TELE_MAX_DISPLAY_ZOOM = 60f`, fixed | `CameraState.kt:353` |
| 36 | `ZEISS_200_X300` declared before `ZEISS_400`, pinned by a test | `Teleconverter.kt:87-90`; `TeleconverterTest.kt:237` |
| 37 | `phoneModelDetected` re-derived on every write | `CameraViewModel.kt:952, 1485, 2546, 2668` |
| 38 | Model-string branching only in `detectPhone` and `DeviceProfile.resolve` (EXIF reads are labels only) | grep for `Build.MODEL` |
| 39 | `com.oplus.*` request hints gated by `vendorOplusRequestHints`; the native log key is gone | `CameraController.kt:494-581`; grep |
| 40 | OCS SDK and `OcsProbe` removed | `app/build.gradle.kts`, `libs.versions.toml` |
| 41 | `nativelog` is `BuildConfig.DEBUG &&`-guarded | `CameraEngine.kt:7506-7509` (see V5 for its reach) |
| 42 | Shader code 3 vacant, pinned by a test | `ShaderTransferCodeTest.kt`; no `uTransfer == 3` |
| 43 | `KEY_COLOR_TRANSFER` set explicitly on every format path | `ColorProfiles.kt:63-65, 100-102, 113-115` |
| 44 | Only HEVC and AVC offered; APV scanned but excluded; AV1 absent | `EncoderCaps.kt:28-29` |
| 45 | 120 fps high-speed never offered; restore sanitizes it | `CameraState.kt:1098`; `CameraViewModel.kt:1266` |
| 46 | Video size capped at 3840 wide | `CameraEngine.kt:7703, 7714` |
| 47 | Keycodes: 767 half-press, 781 quick, 782 half sibling, 769 slide-out alias | `MainActivity.kt:1022-1031` |
| 48 | Hardware zoom glide about 30 Hz (33 ms) | `CameraViewModel.kt:447` |
| 49 | Coalescer 16 ms, quiet landing 250 ms, interaction end 700 ms, controls throttle 40 ms, save debounce 500 ms | `CameraViewModel.kt:2126, 2166, 2168, 3930, 4094` |
| 50 | Remap doors reset `pendingRatio` AND `easeTarget`, including the converter declaration seam | `ZoomGlideState.kt:88-94`; `CameraViewModel.kt:2268, 2555` |
| 51 | `SENSOR_SUBMIT_MIN_INTERVAL_MS = 200` | `CameraController.kt:2409` |
| 52 | Tally radius uses the RoundedCorner API unscaled (no ×1.2) | `CameraScreen.kt:1072-1085` |
| 53 | Analysis FBO long edge ≤ 256 | `GlPipeline.kt:1817` |
| 54 | Preview host is a `TextureView` | `CameraScreen.kt:902-907` |
| 55 | Glyphs: no U+25B8 anywhere; every non-Hangul non-ASCII literal (incl. ↑ ↓ ') is present in all three Inter faces (checked with fontTools) | scan of `res/values*/strings.xml` and Kotlin literals |
| 56 | SOFT carries no suffix; only AF_LIMIT says `TOO CLOSE → lens` (U+2192) | `strings.xml:505-507`; `MacroProximity.kt` |
| 57 | FocusDetail lags {4, 8, 16, 32}, takes no `lut` | `FocusDetail.kt:68, 157` |
| 58 | EN and KO string sets match (0 missing translatable keys); no hardcoded English `Text("…")` | scan |
| 59 | `microphoneDeclineOutcome`, `microphonePermissionRequired`, `audioRestoredByMicrophoneGrant`; operator-off clears the denial key | `CameraPermissionPolicy.kt:38-99`; `MainActivity.kt:516-528, 928-969` |
| 60 | CAMERA permanent denial needs recorded history; a grant clears it | `CameraPermissionPolicy.kt:26-35`; `MainActivity.kt:955-988` |
| 61 | READ_MEDIA trio declared; no location permission; INTERNET removed | `AndroidManifest.xml:5-22` |
| 62 | External union volume resolved to primary and checked against the row's `VOLUME_NAME`; no other volume comparisons exist | `PendingDiscardJournal.kt:586-613, 691-698` |
| 63 | `TerminalAcquisitionGate.isOpen()` lock-free volatile; `runIfOpen`/`close` synchronized | `CameraEngine.kt:8376-8393` |
| 64 | PROGRESS statuses carry no timer | `CameraStatus.kt:175-190` |
| 65 | Capacities: pre-native 2+4, still publication 2+2, rejected output 2+8, DNG allocation 8 s | `RecordingPreNativeAllocation.kt:278`, `StillPublicationDispatcher.kt:186`, `MediaStoreWriter.kt:1862`, `DngPreCaptureAllocation.kt:170` |
| 66 | Standby meter: at most 3 recreations, shared `classifyAudioRead`; REC handoff awaits 400 ms | `StandbyAudioController.kt:719, 866`; `CameraEngine.kt:6099` |
| 67 | AE/AF regions set independently per target | `CameraController.kt:1330-1331` |
| 68 | EXIF make/model from Build; blank values omit the tag | `DeviceExifLabels.kt:21-30`; `StillCapturePipeline.kt:687-688` |
| 69 | GL start re-seeds transfer, digital gain, and the full `rendererAssists` replay | `CameraEngine.kt:1814-1817` |
| 70 | Toolchain: AGP 9.3.2, Kotlin 2.4.10, Gradle 9.7.1, BOM 2026.08.00, compile/target/min 37/36/33, heifwriter 1.1.0, no `kotlin.android` plugin | `libs.versions.toml`, wrapper, `app/build.gradle.kts:493-505` |

## Test-coverage gaps tied to the findings

- There is no host test for `restoredOptics` with DNG on (V1), for the Engine fast-path
  `forZoom` predicate (V2), or for `sessionAttemptPlan` attempts ≥ 1 with `tenBitVideoOnly`
  (V4).
- The front RAW exclusion (V3) is tested, but under a rationale ("readers are gone by design")
  that is no longer true.
