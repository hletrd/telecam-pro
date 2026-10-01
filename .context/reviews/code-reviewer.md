# Code-reviewer report: logic correctness (2026-09-30)

Scope: read-only review of `app/src/main/kotlin/**`, `app/src/test/**` (coverage cross-checks) and `tools/*.py`.
Primary deep focus: `ui/CameraViewModel.kt`, `camera/CameraState.kt`, `camera/ManualControls.kt`,
`camera/CaptureCapabilities.kt`, `camera/Teleconverter.kt`, `camera/AutoExposure.kt`,
`storage/SettingsStore.kt`, `ui/controls/*.kt`, and `ui/ZoomMath.kt` (the restore/remap math those files call).
Secondary sweep: storage/capture, video/audio, GL shaders, and UI/activity. Every finding below was checked
against the code path, not against comments.

Settled owner decisions from CLAUDE.md are **not** reported: ZSL dark refusal, the FocusDetail threshold, the
declined CameraUnit SDK, no proprietary HDR, no APV/AV1, and the Loupe Overview's afocal omission.

Inventory: 125 main Kotlin files (~59k LOC; the largest are CameraEngine 8.6k, CameraViewModel 4.5k,
CameraScreen 3.4k, MediaStoreWriter 3.0k and CameraController 2.8k), 252 unit/Robolectric test files, and 12
Python/shell tools (plus 8 test modules). Strings: 496 English names and 478 Korean ones. Every translatable
name has a `values-ko` entry, and printf argument sets match across all pairs (checked by script).

Status values used below: **confirmed** means the code path was traced end to end. **likely** means it is
strongly supported by the code, but runtime/HAL behaviour is involved. **needs-manual-validation** means it
depends on device or platform behaviour.

---

## Findings (ranked by severity)

### CR-1 [HIGH] Toggling DNG moves Photo between the logical and standalone route without converting zoom to the new scale
- **Where:** `ui/CameraViewModel.kt:2370-2390` (`onSetPhotoFormats`) and `camera/CameraEngine.kt:3959-3982`
  (`setRawWanted` → `reconfigureCamera(pin, transaction)`).
- **Why:** on PMA110, wanting DNG is a route input (`standaloneRouteWanted`). On the logical route `zoomRatio`
  is unified (main-relative); on a standalone route it is lens-local. `setRawWanted` opens a new optics
  transaction but never converts `controls.zoomRatio`, and `onSetPhotoFormats` only pushes the flag and writes
  `photoFormats`.
  - Neither the caps seam (`reconcileControlsWithCaps` / `normalizeControlsForRoute`, which only clamps) nor the
    ViewModel's `reconcileZoomToCaps` converts it either.
  - Every other scale door does convert: `onLens`, `onModeChange` (`remapModeOptics`), the TC door
    (`resolveTeleZoomTransition`) and the front door (`rearReturnZoom`). This is the one door that doesn't.
- **Failure scenarios:**
  - **DNG on:** Photo, tap the 3× chip. State is `lens=TELE3X`, wire zoom 3.0 unified. Enable DNG. The session
    reopens on the standalone 70 mm lens with wire zoom 3.0, now read as lens-local. Framing jumps to 9× (OSD
    ~210 mm), and `unifiedZoom` reports 9×. At a 5× pinch it becomes 15×; at 10× it clamps to the 70 mm lens's
    ceiling.
  - **DNG off:** the reverse happens. Standalone TELE at local 1.0 returns to the logical camera at unified
    1.0, silently jumping from 3× to 1×. `reconcileZoomToCaps` then re-bands `lens` to MAIN.
- **Fix:** treat the DNG toggle as a zoom-scale remap door. Inside the `setRawWanted` transaction, when
  `standaloneRouteWanted` flips, rewrite `controls.zoomRatio`:
  - toward standalone: `localZoomOf(unifiedZoom, optical)`, with the lens set from `LensChoice.forZoom(unified)`;
  - toward logical: `unifiedZoomOf(lens, local, standaloneRoute = true, optical)`.

  Mirror the same rewrite in `onSetPhotoFormats`, and call `invalidateOpticsDerivedState()` and
  `clearTapFocusUi()` like every other remap door. Add an `OpticsTransitionPolicyTest` case for both directions.
- **Confidence:** High. **Status:** confirmed in code. Device check pending: tap 3×, toggle DNG, read OSD focal.

### CR-2 [HIGH] Restore and MR recall rebuild a DNG Photo framing as if it were unified zoom, losing lens and zoom
- **Where:** `ui/ZoomMath.kt:372-402` (`restoredOptics`, PHOTO branch) called from `ui/CameraViewModel.kt:1328-1334`.
  The engine takes `restoredLens` from it at `:1407-1418`.
- **Why:** for a non-TELE Photo packet, `restoredOptics` always runs
  `LensChoice.forZoom(savedZoom.coerceIn(0.6, 20))` and ignores `e.lens`. A packet saved while DNG was on
  (a standalone Photo route) stores a lens-local ratio, so the persisted lens is thrown away and the band is
  derived from a local number.
  - `applyLoaded`'s own `preserveChangedOptics` branch (`:1297-1311`) does compute a standalone local ratio
    correctly, then hands it to the same function, which misreads it again.
- **Failure scenario:** DNG on, 3× lens, local zoom 1.0 (or 2.0, i.e. 6× framing), then relaunch or recall
  that MR bank. `restoredOptics` returns `lens = forZoom(1.0) = MAIN`, so `setResolvedOptics` opens the main
  standalone lens at 1× (or 2×). The saved framing is lost on every launch for DNG shooters.
- **Fix:** pass the target route into `restoredOptics` (`standaloneRouteWanted(mode == VIDEO, dngRaw,
  rawForcesStandalone)`). When standalone, keep `requestedLens` and clamp local zoom to `[1, MAX_VIDEO_LOCAL_ZOOM]`,
  exactly like the VIDEO branch. Add a `ZoomMathTest` case (PHOTO + standalone + `TELE3X`, local 1.0 →
  `TELE3X`, 1.0).
- **Confidence:** High. **Status:** confirmed. There is no test for this case; `ZoomMathTest:125-156` covers
  only the logical and video branches.

### CR-3 [MEDIUM-HIGH] ANGLE shutter unit is reachable in ISO priority and app-side PROGRAM, where the AE loop's shutter writes are ignored
- **Where:**
  - `ui/controls/FnQuickActions.kt:112-114`: the Fn SHUTTER quick action toggles SPEED↔ANGLE whenever
    `shutterDialEnabled`, i.e. whenever manual AE exists, in **any** exposure mode.
  - `ui/CameraViewModel.kt:1997`: `onShutterMode` has no exposure-mode guard.
  - `:1976-1991`: `onExposureMode` forces SPEED only when entering ISO, not PROGRAM.
  - `:1368-1380`: `applyLoaded` forces SPEED only for app-side PROGRAM, not ISO.
  - `camera/AutoExposure.kt:109-151` and `ui/CameraViewModel.kt:3970-3993` are the loop that writes into the
    ignored field.
- **Why:** `effectiveExposureNs()` ignores `exposureTimeNs` in ANGLE mode (`ManualControls.kt:441-446`). The
  ISO-priority loop and the app-side program line both drive exposure by writing `exposureTimeNs`.
- **Failure scenarios:**
  - **ISO priority:** tap the Fn SHUTTER tile. `withShutterMode(ANGLE)` succeeds. `driveShutterNs` now
    re-emits the same `newNs` on every ~6 Hz tick (another `updateControls` and sensor fast-path submit each
    time), but the wire exposure never moves, so ISO priority stops auto-exposing entirely.
  - **App-side PROGRAM:** from M with an angle set, switch to P. The shutter stays fixed at angle/fps (1/60 s
    at 180°/30p) regardless of the 1/focal rule. `driveProgram` computes `isoStops = corr - shutterStops`
    assuming a shutter move that never lands, so ISO lags by up to 0.35 stop per tick. In the dark, once ISO
    clamps, the overflow "slower shutter" is dropped and the photo underexposes.
  - **Restore:** a persisted ISO+ANGLE blob restores straight into this state.
- **Fix:** keep ANGLE only where the user owns the shutter (SHUTTER or MANUAL).
  - In `onShutterMode`, refuse ANGLE (or no-op) when `autoShutterDriven || exposureMode == PROGRAM`.
  - Gate the Fn SHUTTER quick action the same way (as `ShutterRuler` already does with `enabled`).
  - Force SPEED in `onExposureMode` for PROGRAM too, and in `applyLoaded` for ISO.
  - Or normalize it once: `normalizedFor` could coerce `shutterMode = SPEED` whenever the loop owns the shutter.
- **Confidence:** High. **Status:** confirmed.

### CR-4 [MEDIUM] P→S/ISO/M handoff seeds from the preview's traded wire values, not the still exposure P intended
- **Where:** `ui/CameraViewModel.kt:1976-1991` (`onExposureMode`, `fromProgram` branch) and
  `camera/CameraController.kt:1097-1106`. That controller code publishes
  `SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME` from the repeating result in every mode.
- **Why:** photo P is app-side by default. Its intended still exposure lives in `controls.iso` and
  `controls.exposureTimeNs`, and the loop keeps both fresh. The repeating request carries the
  `previewExposureTrade` result instead: exposure capped at 1/30 s (PROGRAM neutral cap) or 1/15 s, ISO
  raised, and the residual left as GL digital gain. `liveIso`/`liveExposureNs` are those traded wire values,
  sampled every tenth frame.
- **Failure scenario:** dim scene, P settled at 1/10 s ISO 6400 (max), so the preview wire is about 1/30 s ISO
  6400 with ×3 GL gain. Switch to M. M is seeded with 1/30 s ISO 6400, and the first M still is about 1.6 stops
  darker than the P shot just before it. S/ISO inherit the same wrong seed, and the loop then has to walk back.
- **Fix:** seed from `live*` only when the outgoing P was HAL-AE (`!it.programAppSide`). Otherwise keep
  `it.iso` and `it.exposureTimeNs`.
- **Confidence:** High. **Status:** confirmed in code.

### CR-5 [MEDIUM-HIGH] 10-bit HLG video: preview, zebra/false-colour and the app-side AE meter all read raw HLG code values as SDR
- **Where:** `gl/GlPipeline.kt:1041` (`previewTransfer` is null unless log and not assist) and
  `:1309-1315` / `:1814` (`analysisReadbackTransfer` is always null). In `gl/Shaders.kt:209-229`, transfer
  code 0 never consults `uSourceHlg`, and `dgain()` applies BT.1886 math to the sampled values.
- **Why:** in a 10-bit session (`tenBitSessionWanted`: VIDEO and transfer ≠ SDR), `sourceHlg = 1` and the
  preview stream is HLG-encoded. Only the encode branches (1/2/4/5) linearise through `sourceLinear()`.
  Display, meter, zebra, false colour, peaking, and the AE/scope readback all run in the HLG code domain.
- **Failure scenarios:**
  - Video S/ISO priority with HLG: 18% grey is about HLG code 0.39 rather than SDR about 0.46, so the app-side
    loop (target 0.45) drives the scene brighter than intended, and that lands in the file.
  - HLG diffuse white sits at 0.75, so the IRE70/85/95 zebra presets mean something different.
  - With Gamma Display Assist on a log profile, the "normal display-referred image" is actually HLG codes.
- **Fix:** when `uSourceHlg == 1`, form the display, meter and dgain signal from
  `pow(min(sourceLinear(c), 1.0), 1/2.4)`, i.e. convert back to the BT.1886 display domain. Do the same in the
  analysis readback so metering is transfer-independent, as the comments already promise.
- **Confidence:** Medium-High. **Status:** likely; confirm on device with an 18% card, S-priority HLG against
  SDR.

### CR-6 [MEDIUM] AE Lock (including the AEL hardware binding) is a silent no-op in every app-side exposure mode, the default Photo mode included
- **Where:**
  - `ui/CameraViewModel.kt:3192-3205`: `HardwareKeyAction.AEL -> onToggleAeLock(active)`.
  - `camera/ManualControls.kt:263-265`: `aeLock` is normalized to false unless HAL-AE PROGRAM.
  - `ui/CameraViewModel.kt:3944-3996` and `:1052-1059`: `applyAutoExposure` never checks a lock.
- **Why:** the UI toggle is correctly disabled (`ControlAvailability.aeLockEnabled`), but the reassignable
  AEL key binding is not. In photo P (app-side), S and ISO, pressing AEL writes `aeLock = true`, normalization
  strips it, and the loop keeps metering.
- **Failure scenario:** a Sony-habit user assigns the half-press key to AEL, meters a subject in photo P,
  recomposes, and the exposure follows the new framing anyway.
- **Fix:** add an app-side lock. Keep a ViewModel flag set by the AEL action (and allow the toggle in app-side
  modes) that makes `applyAutoExposure` return early. Alternatively, refuse AEL in `hardwareActionAdmitted`
  with a status message. Don't route it through `aeLock`, which is HAL-only.
- **Confidence:** High. **Status:** confirmed.

### CR-7 [MEDIUM] The app-side AE loop keeps running during a manual (AE-OFF) AEB bracket, so the bracket drifts mid-sequence
- **Where:** `ui/CameraViewModel.kt:1052-1059` and `:3944-3996`, which have no capture/bracket gate;
  `camera/CameraEngine.kt:4876-4906` (`captureAeb` manual branch: `manualAebStepControls(controls, steps[i])`
  reads the **live** `controls` on each fire).
- **Why:** in P (app-side), S and ISO, `!original.autoExposure` takes the time-bracket branch. Each step puts a
  ×¼ or ×4 exposure on the repeating request. The loop meters that brightened or darkened preview (GL gain
  simulates it) and rewrites `iso` (S/P) or exposure (ISO) through `engine.setControls`. The next bracket frame
  is then built from the loop-modified controls.
- **Failure scenario:** S-priority AEB. After the +2 EV step's preview, the loop lowers ISO by up to about 0.3
  stop per tick, so later frames carry a different ISO. The bracket is no longer ±2 EV about one base, and the
  restore at the end is moved by the loop as well.
- **Fix:** freeze the loop while a still chain is in flight (the engine already knows: a publish/`pending`
  flag, or a "sequence active" callback to the ViewModel). Alternatively, snapshot `original` and build every
  step from it instead of from live `controls`.
- **Confidence:** Medium. **Status:** likely (depends on how many loop ticks fit inside a bracket).

### CR-8 [MEDIUM] Recorded audio starts earlier than video, with no alignment, so every take has an A/V offset
- **Where:** `video/VideoRecorder.kt:795` (`audioPtsUs(totalSamples)` counts from the first PCM read after
  `startRecording()`) and `gl/GlPipeline.kt:1250-1252` (video PTS 0 is the first **encoder swap**).
- **Why:** audio and video each start at PTS 0 from unrelated wall-clock instants. The audio worker starts
  during recorder setup, before the encoder's first real-frame swap, which CLAUDE.md says can be slow
  (TB336ZU). Nothing uses `AudioRecord.getTimestamp` or trims early audio.
  - Also, while the audio thread blocks in `awaitMuxerStart()` (`:935-984`, up to the video startup
    deadline), it stops reading, so the about 40-85 ms AudioRecord buffer can overrun. Sample-count PTS then
    silently closes that gap.
- **Failure scenario:** a clap on camera is heard later than it is seen, by the gap between audio start and
  the first encoder frame (hundreds of ms on slow encoders).
- **Fix:** put both tracks on one monotonic timebase. Derive audio PTS from
  `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` and subtract the same base as video, or drop PCM captured
  before the first video frame. Keep reading while waiting on the muxer rendezvous (buffer encoded AAC instead).
- **Confidence:** Medium. **Status:** needs-manual-validation (clap test, frame-accurate).

### CR-9 [MEDIUM] Offered frame rates are not gated on the selected video size or codec
- **Where:** `camera/CameraState.kt:1078-1110` (`VideoFrameRate.availableFor`).
- **Why:** apart from the 8K ≤30 fps cap, a rate is offered whenever any fixed `[fps,fps]` AE range exists.
  `size` only feeds the 8K check; `codec` and `highSpeedMaxFps` are unused. It never consults
  `StreamConfigurationMap.getOutputMinFrameDuration(SurfaceTexture, size)` or the encoder's
  `VideoCapabilities.areSizeAndRateSupported`. CLAUDE.md claims these rates are gated against the selected size.
- **Failure scenario:** on a device where 4K or Open Gate 4:3 has a 33 ms minimum frame duration, 60/59.94p
  is still offered. The HAL delivers about 30 fps, the muxer is told 60, and the "60p" file is 30p with
  duplicated or timed-stretched frames.
- **Fix:** add a `minFrameDurationNs(size) <= 1e9 / fps` filter (carry the duration per size in `CameraCaps`),
  and optionally the encoder capability check keyed on `codec`.
- **Confidence:** High that the code is ungated; the impact on PMA110 depends on its stream map.
  **Status:** confirmed (code) / needs-manual-validation (PMA110 impact).

### CR-10 [MEDIUM] Deleting a restored, fully owned family tombstones the synthetic prior-process id, blocking every later gallery restore
- **Where:** `ui/CaptureOutputTracker.kt:263-270` (tombstone added for `CAPTURE_FAMILY`) and `:93`
  (`seedPriorCapture` refuses while `PRIOR_PROCESS_CAPTURE_ID in tombstones`). The id is
  `Int.MIN_VALUE` (`:445`).
- **Why:** `PRIOR_PROCESS_CAPTURE_ID` is a reusable slot, not a live capture, so no late sibling callback can
  arrive for it. The file-only path deliberately avoids tombstoning it (see the comment at `:258-262`); the
  family path does not.
- **Failure scenario:** launch, the prior capture restores, the user deletes it, and `lastMediaUri` becomes
  null. Tapping the empty gallery runs `restoreLatestPublishedCapture` and finds the next-older family, but
  `seedPriorCapture` returns false. The thumbnail stays empty for the rest of the process, until more than
  `maxTombstones` newer deletes evict the entry.
- **Fix:** don't tombstone `PRIOR_PROCESS_CAPTURE_ID`, and instead protect against resurrecting the
  just-deleted family by URI (the deleted outputs are already known). Or clear that tombstone on a seed whose
  family key differs. Add a tracker test: delete a seeded family, then seed a different family, expect true.
- **Confidence:** Medium-High. **Status:** likely (there is no test covering re-seed after delete).

### CR-11 [MEDIUM] Launch media recovery stops at the first persistently failing row outside the DISCARD stage
- **Where:** `storage/MediaStoreWriter.kt:1243-1253` (preflight) and `:1287-1398` (media pages), where
  `continueAfterFailureExhaustion` defaults to false (`:2325`). Only the DISCARD stage sets it (`:1272`).
  The early return is at `camera/LaunchMediaRecoveryCoordinator.kt:266-269`.
- **Why:** a single row that keeps failing (`DELETE_FAILED`/`PUBLISH_FAILED`/`UNAVAILABLE`), or a
  deleted-family marker, exhausts the retry budget. Recovery then returns without advancing the cursor, so
  later Images/Video pages and the whole DISCARD stage never run, on every launch.
- **Fix:** set `continueAfterFailureExhaustion = true` for media and preflight batches. The failed row is
  already retained, and the cursor is already able to advance.
- **Confidence:** Medium. **Status:** likely (it depends on which failures set `retryRequired`).

### CR-12 [LOW-MEDIUM] The app-side P program line ignores digital zoom and the declared host focal
- **Where:** `ui/CameraViewModel.kt:1784-1799` and `:4531-4538` (`preferredProgramShutterNs`).
- **Why:** the 1/focal rule uses `s.lens.targetEquivMm × (TC ? magnification : 1)`, a fixed 70 mm band
  focal, and ignores the zoom ratio. It also uses 70 mm for vivo/OTHER hosts instead of
  `teleconverterHostEquivMm`.
- **Failure scenario:** TELE with the Explorer at local 5× digital (about 1500 mm). P holds about 1/300 s,
  which is 2.3 stops too slow for the handheld rule this line exists to enforce, so shots at the app's
  headline use case blur.
- **Fix:** use the effective focal, `unifiedZoom × MAIN.targetEquivMm` off-TELE, or
  `teleconverterFocalMm × localZoom` in TELE.
- **Confidence:** High on the math. **Status:** confirmed; product intent to confirm.

### CR-13 [LOW-MEDIUM] Window rotation goes stale on a direct 90°↔270° landscape flip (large screens only)
- **Where:** `ui/CameraScreen.kt:581-597`. The value is `remember(LocalConfiguration.current)` over
  `display.rotation`, with `configChanges="orientation|screenSize|…"` in the manifest (`AndroidManifest.xml:72`).
  There is no `DisplayListener` anywhere.
- **Why:** a 180° flip changes no public `Configuration` field, so there is no recomposition and
  `onWindowRotationChanged` never fires.
- **Failure scenario:** on an sw600dp tablet window, the preview draws upside down and tap-AF mapping is
  point-mirrored after the flip. The phone path (portrait-locked) is unaffected.
- **Fix:** observe `DisplayManager.DisplayListener.onDisplayChanged` in a `DisposableEffect`.
- **Confidence:** Medium. **Status:** needs-manual-validation (TB336ZU).

### CR-14 [LOW] A settings save before the encoder inventory loads persists the degraded formats and transfer
- **Where:** `ui/CameraViewModel.kt:1545-1603` (`currentExtras` reads `s.photoFormats` and `s.transfer`)
  against `:1276-1290`. Before inventory, state holds HEIF→JPEG-promoted formats and SDR, and the real intent
  is parked in `pending*UntilInventory`.
- **Failure scenario:** background, or trigger any immediate `saveSettingsIfEnabled` door (lens, mode, TC),
  inside the async `EncoderCaps.load()` window on a cold start. The HEIF or HLG/log choice is permanently
  rewritten to JPEG or SDR.
- **Fix:** in `currentExtras`, prefer `pendingPhotoFormatsUntilInventory`, `pendingTransferUntilInventory` and
  `pendingCodecUntilInventory` when `!encoderInventoryLoaded`.
- **Confidence:** Medium. **Status:** confirmed path, narrow window.

### CR-15 [LOW] The Loupe Overview tap-exclusion rect ignores the measured bottom clearance the drawn box uses
- **Where:** `camera/CameraState.kt:801-812` (`finderContainsTopLeftPoint` → `finderRect(boxWidth,
  boxHeight)` with the default `NaN` clearance) against `ui/CameraScreen.kt:1000-1004` and
  `gl/GlPipeline.kt:862`, which pass `bottomClearance`.
- **Failure scenario:** on large screens, where measured clearance exceeds the fraction floor, taps in the
  band just below the drawn overview are swallowed and produce no focus. This contradicts the "one geometry
  rule" docstring.
- **Fix:** thread `bottomClearance` into `finderContainsTopLeftPoint`, or drop the redundant check (the
  overlay already consumes its own pointer).
- **Confidence:** High on the mismatch. **Status:** confirmed.

### CR-16 [LOW] An EXIF build failure aborts the whole HEIF save
- **Where:** `capture/StillCapturePipeline.kt:~251`. `buildHeifExifData` (a cache temp file, a 1×1 encode,
  and an ExifInterface save) runs outside any `runCatching`, while the JPEG and passthrough lanes treat EXIF as
  best-effort.
- **Failure scenario:** a full cache or an I/O hiccup reports `HEIF_SAVE_FAILED` and loses the frame.
- **Fix:** `runCatching { … }.getOrNull()`, then write the HEIF without EXIF.
- **Confidence:** Medium. **Status:** likely.

### CR-17 [LOW] Missing `phoneModel` key restores `FIND_X9_ULTRA` instead of the seeded or detected phone
- **Where:** `storage/SettingsStore.kt:61` and `:270` (`enumOr(…, ed.phoneModel)`, where the default is
  `DEFAULT_PHONE_MODEL`); `ui/CameraViewModel.kt:2655-2676` seeds OTHER on unknown hardware, but the restore
  overwrites it.
- **Failure scenario:** a blob written before the key existed, or with the key corrupted, restores
  "Phone: OPPO Find X9 Ultra" plus the Hasselblad kit on a non-OPPO device. That is the exact falsehood the
  2026-08-02 OTHER seeding removed.
- **Fix:** have the load fallback use the ViewModel's detected-or-OTHER phone. Pass it in, or leave the field
  nullable and resolve it in `applyLoaded`.
- **Confidence:** Medium. **Status:** confirmed path; realistic only for corrupted or very old blobs.

### CR-18 [LOW] Smaller correctness and doc drift
- **`driveProgram` doc drift:** the doc says the shutter moves "at most one stop" per tick
  (`camera/AutoExposure.kt:104-106`), but the clamp is ±0.35 stop (`:129`). Behaviour is fine; the comment is
  wrong.
- **Zebra/false-colour luma weights:** `gl/Shaders.kt:95` uses Rec.2020 luma weights, but zebra, false colour
  and peaking see BT.709 SDR input (the meter is display-referred). Saturated reds and blues get mis-weighted
  against the thresholds. Use Rec.709 weights (0.2126/0.7152/0.0722) for `meter`.
- **Aspect-ratio recording gate:** `ui/CameraViewModel.kt:2401-2406` (`onAspectRatio`) has no
  `rejectIfRecording`, yet the `ControlCycles.kt:225-227` comment states both actions reject mid-REC. It is
  harmless while ASPECT is Photo-only, but the stated contract is false. Add the guard or fix the comment.
- **`verify_host.py`:** `tools/verify_host.py:33-35` checks only `java` and `jarsigner`, but the error message
  demands keytool and "JDK 21", and there is no version check. A JDK 17 on `PATH` passes preflight and fails
  later inside Gradle.
- **`adb_proxy.py`:** `tools/adb_proxy.py:20-34` shuts sockets down but never `close()`s them, so each
  connection leaks two fds in a long-running proxy.
- **Discard identity (needs-manual-validation):** `storage/PendingDiscardJournal.kt:73-79` includes
  `DATE_TAKEN` in the frozen identity. If MediaProvider rewrites it from EXIF or MP4 on scan-at-close,
  `discardPendingOutput` goes UNRESOLVED for a sibling that finishes after a family delete. Verify by reading a
  row before and after a HEIF write.

---

## Final sweep: checked and found clean
- **SettingsStore:** save/load key symmetry covers all 79 data keys (script-checked; preset metadata keys are read separately). Legacy `LOG`→`SLOG3_CINE` and `fnSlots` are
  migrated, per-field defensive readers are in place, and the converter is reconciled on load.
- **Teleconverter:** kit magnification derives from each kit's own host. `defaultConverterFor` depends on
  declaration order, which a test pins. `reconcileConverter` and `normalizeMagnification` bounds match the
  slider range.
- **ManualControls:** `normalizedFor` flash/Program routing, `previewExposureTrade` (brightness-neutral ISO
  trade, gain = want/wire, clamps), `withShutterMode` round-trip, `captureWatchdogTimeoutMs` saturation,
  `kelvinTintToRggbGainValues` sign and normalisation, and `sensorFrameDurationNs`/`sensorRequestTiming`.
- **CaptureCapabilities:** still-exposure ceiling clamp, `pickStillSize` aspect-first selection,
  `pickVendorHiResSize`, `fixedFpsBounds`/`autoFpsBounds`, video-stab fallback, and the identity-keyed
  `controlCapabilities` cache (thread-safe by identity check).
- **CameraState:** `unifiedZoomOf`/`localZoomOf` round-trip, `resolveTeleZoomTransition`, `rearReturnZoom`,
  `lensInventoryOf` mutual-nearest matching, `PhotoFormats.normalizedFor`, and `videoBitRate`.
- **ui/controls:** `nextAvailable`, `quickFnEnabled` REC gates (apart from CR-18's aspect note), `isoStops`
  and `shutterStops` ladders, `formatShutterSpeed` edge cases, and `FocusMapping`.
- **Strings:** Korean parity is complete, format arguments match, and there are no hardcoded user-facing
  English literals in Compose (the sweep found only exempt OSD abbreviations).
- **Colour maths:** S-Log3 and LogC3 EI800 constants, the HLG OETF and its inverse, and every gamut matrix
  were recomputed from published primaries and match to 1e-9.

## Top 5 by severity
1. **CR-1:** the DNG toggle reopens a different zoom scale without conversion (3× becomes 9×, or 3× becomes 1×).
2. **CR-2:** restore and MR recall lose a DNG Photo framing (the lens is re-derived from a lens-local ratio).
3. **CR-3:** ANGLE shutter in ISO/app-side P freezes the AE loop's shutter axis.
4. **CR-5:** 10-bit HLG video displays and meters raw HLG codes as SDR (app-side video AE mis-exposes).
5. **CR-4:** P→S/ISO/M handoff seeds from traded preview values, not P's still exposure.
