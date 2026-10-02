# Tracer review — RPL cycle 5 (2026-10-02)

Role: causal tracing of end-to-end flows with competing hypotheses. ID prefix `TR5-`.
Read-only: no source/test/doc/plan edits, no Gradle, no state-changing git. All paths below are
relative to `app/src/main/kotlin/me/hletrd/telecampro/` unless stated otherwise. Line numbers are
from `main` at `ea7d4374`.

Method: each of the six requested flows was traced hop by hop through real call sites (callers
grepped, thread/executor and monitor/generation guard identified per hop). For every suspicious hop
a "breaks" and a "holds" hypothesis were stated and settled from code. The headline findings
(TR5-1, TR5-3, TR5-4, TR5-5, TR5-6, TR5-9, TR5-13) were re-read line by line by the lead tracer
after the per-flow passes. Already-tracked items (cycle-4 aggregate, cycle-4 plan "later cycle",
"carried", "Deferred") are not re-reported as new.

## Summary

| ID | Sev | Conf | Status | Flow | One line |
|---|---|---|---|---|---|
| TR5-1 | High | High (logic) | Confirmed; timing needs device | 6 (pause) / 3 (launch) | A pause inside cold GL start makes `resume()` skip the preview bind or the GL input-ready setup forever: black viewfinder, or app-side AE/scopes/assists dead |
| TR5-2 | Medium-Low | Medium | Likely | 6 | A preview-recovery rebind refused while paused is never replayed on resume; Ready stays false |
| TR5-3 | Medium | High | Confirmed (PMA110 trigger needs dumpsys) | 3 / 5 | Caps reconciliation on routes that never record (Photo logical, FRONT) permanently narrows and persists the operator's video stabilization and frame rate |
| TR5-4 | Medium | High | Confirmed | 1 | One failed durable delete marker latches `deletionJournalUnavailable` for the process: the photo shutter is dead until restart |
| TR5-5 | Medium | High | Confirmed in code; needs a non-PMA110 device | 5 | Multi-lens device with no logical back camera: Photo routes to a standalone lens while zoom stays on the unified scale (the "208 mm" class) |
| TR5-6 | Low-Medium | High | Confirmed | 2 / 1 | An in-REC snapshot suppresses the clip's SAVED/FAILED/RETAINED status and its review registration |
| TR5-7 | Low-Medium | Medium-High | Likely | 5 | VM/Engine zoom-interaction state diverge across a remap door: per-tick ~180 ms submits for a whole gesture, or a stuck wide-aim/boost after a converter-optic change |
| TR5-8 | Low-Medium | High | Confirmed | 1 | A processed-only (HEIF/JPEG) still is discarded when the characteristics read fails, although only the DNG lane needs it |
| TR5-9 | Low | High | Confirmed | 4 | A.4 fix: the silent audio restore after a recall goes through `onToggleRecordAudio`, which clears the `activeMemorySlot` the recall just lit |
| TR5-10 | Low | High | Confirmed (rare) | 4 / 3 | A.6 is incomplete on the async-rollback path: pre-inventory pending codec/transfer stay armed with the rolled-back bank's values |
| TR5-11 | Low | Medium-High | Likely | 4 | M.6 is incomplete: recall cancels a held AEL, then an optics rollback restores the held `aeLock=true`; the release restores nothing and it is persisted |
| TR5-12 | Low | Medium | Likely | 4 | Punch-in assist ownership survives a recall / explicit toggle; closing the Focus ruler turns punch-in off and persists it |
| TR5-13 | Low | Medium | Likely (µs window) | 2 | A latched Stop can be absorbed by a racing REC press: the published recorder keeps running, unstoppable from the UI |
| TR5-14 | Low | Medium-High | Confirmed (uncommon combination) | 2 | Standby meter stays off after a Stop-latched admission fails: its restart runs before the pending token is abandoned |
| TR5-15 | Low | Medium-Low | Likely (timing) | 1 | RAW-only BURST/AEB continuation can be refused because the camera thread releases the DNG admission after handing the tail to a worker |
| TR5-16 | Low | Medium | Likely (narrow) | 5 | A zoom flush between the Engine optics rollback and the VM rollback post writes the failed attempt's scale onto the restored route |
| TR5-17 | Low | Low-Medium | Likely (timing) | 3 / 5 | Route-inventory fold copies a setup-thread `activeCameraRoute` snapshot without a generation check and resets zoom without glide hygiene |
| TR5-18 | Info | Medium | Confirmed | 1 | BURST/AEB `capturePhoto` returns true even when the head frame was refused, so the shutter blinks on a refusal |

Counts: High 1, Medium 3 (+1 Medium-Low), Low-Medium 3, Low 8, Info 1. Total 18.

---

## TR5-1 — Pause during cold GL start: `resume()` never repeats the work skipped while paused

- **Severity** High. **Confidence** High that the logic is broken; the window is a timing race.
  **Status** Confirmed from code. Needs device validation of how often the window is hit (launch
  behind the keyguard, which CLAUDE.md already documents as delivering `onStop` mid-start).
- **Citations**
  - `camera/CameraEngine.kt:1943-1951`: `dispatchStart` refuses only when `paused` is already set.
  - `camera/CameraEngine.kt:1963-1968`: T1 (setupExecutor) checks `paused` only once, at entry.
  - `camera/CameraEngine.kt:1974`: `glInputPending = true`.
  - `camera/CameraEngine.kt:1988-1992`: the GL input-ready callback sets `glInputPending = false`
    and then returns on `paused`, skipping everything below it.
  - `camera/CameraEngine.kt:1993-2018`: what is skipped: `setAnalysisCallback`, `setTransfer`,
    `setPreviewDigitalGain`, `rendererAssists.replayAll`, `gyro.start`, route resolve, the first
    `reconfigureCamera`, and `publishLensInventoryOnce`.
  - `camera/CameraEngine.kt:2026-2041`: `started = true`, then `bindPreviewSurface(...)` dispatches
    the bind as a SEPARATE setupExecutor task (T2).
  - `camera/CameraEngine.kt:2279-2283`: T2's `isCurrent` requires `!paused`; when it fails there is no
    `setPreviewOutput`, so GL never creates the input Surface.
  - `camera/CameraEngine.kt:7566-7568` (`pause()` sets `paused = true` on main).
  - `camera/CameraEngine.kt:7671-7707` (`resume()`): with `started == true` it only queues a task that
    calls `reconfigureCamera` when `controller == null`. It never re-binds and never replays the GL
    input setup.
  - `camera/CameraEngine.kt:4331-4334` (`reconfigureCamera`): `inputSurface == null && glInputPending`
    returns silently. The comment there assumes "the input callback snapshots the latest generation";
    after TR5-1a that callback can never fire.
  - `camera/CameraEngine.kt:2205`: "Backgrounding does NOT destroy the surface", so
    `onSurfaceTextureAvailable` does not fire again on foreground (only callers of
    `onPreviewSurfaceAvailable` are `ui/CameraScreen.kt:917` and the Engine's own replay paths at
    `:7219, :7248, :7341, :7675`, none of which runs here).
- **Competing hypotheses**
  - *Holds:* some later event re-binds (size change, preview retry, an optics door). Refuted:
    `onPreviewSurfaceChanged` fires only on resize, the preview retry only follows a bind FAILURE, and
    every optics door converges through `reconfigureCamera`, which returns at `:4334`.
  - *Breaks:* settled as above.
- **Causal chain, variant a (bind skipped)**
  1. Cold launch: `onPreviewSurfaceAvailable` → T1 on setupExecutor passes its `paused` check,
     `ownedGl.start(...)`, `started = true`, and enqueues bind T2.
  2. `onStop` → `pause()` sets `paused = true` before T2 runs.
  3. T2's `isCurrent` is false; no preview output; `inputSurface` stays null; `glInputPending` stays
     true.
  4. `onStart` → `resume()`: `started` is true, so it skips the surface branch. The queued task sees
     `controller == null`, calls `reconfigureCamera`, which returns at `:4334`.
  5. Result: black viewfinder and a dead shutter for the life of the process. Every later door
     returns at the same line. `coldStartRetryRefusal` classifies it as lifecycle-owned (`:8730`), so
     no status explains it. Only a surface recreation (config change) or a process kill recovers.
- **Causal chain, variant b (input-ready setup skipped)** — wider window: it spans EGL surface
  creation and `renderer.init` on the GL thread (`gl/GlPipeline.kt:440-462`).
  1. T2 binds successfully; the GL thread creates the input Surface and posts `onInputReady`.
  2. `pause()` lands before the callback's `paused` check: `glInputPending = false`, then return.
  3. `resume()`: `controller == null` → `reconfigureCamera` finds `inputSurface` and opens the camera.
     The preview reaches Ready, so nothing looks wrong at first.
  4. But `setAnalysisCallback` was never installed, so `onAnalysis` never fires and
     `CameraViewModel.applyAutoExposure` (`ui/CameraViewModel.kt:1141`) never runs. Photo PROGRAM is
     app-side by default on PMA110, so exposure is frozen at the restored ISO/shutter; S and ISO
     modes, histogram, waveform and focus confidence are dead. Restored renderer assists and the log
     transfer are missing until toggled. `publishLensInventoryOnce` never ran (harmless on PMA110;
     wrong presets on single-camera devices). This persists until the GL generation is replaced.
- **Suggested fix (host-testable)**
  1. In the input-ready callback, run the pure GL re-seed (`:1993-2000`) and the lens-inventory
     enqueue BEFORE the `paused` check; gate only route resolve + `reconfigureCamera` on `paused`.
     None of those acquire the camera.
  2. In `resume()`'s started branch, when `previewSurface != null` and (`glInputPending` or the GL
     owner has no bound preview output), call `bindPreviewSurface(previewSurface, previewSurfaceW,
     previewSurfaceH, previewSurfaceGeneration.get())` before queueing the reconfigure.
  3. Robolectric/engine-harness tests: interleave `pause()` (a) between T1 and T2 and (b) between the
     bind and the input-ready callback; after `resume()` assert the preview is bound, the analysis
     callback is installed, `replayAll` ran, and Ready is reached.
- **PMA110 behavior:** unchanged on the normal path; only the interrupted-cold-start path changes,
  which is currently broken.

## TR5-2 — A preview-recovery rebind dropped while paused is never replayed

- **Severity** Medium-Low. **Confidence** Medium. **Status** Likely.
- **Citations:** `camera/CameraEngine.kt:2394` (`previewReady = false` on failure),
  `:2424-2428` (the delayed rebind requires `!paused`), `:8218` (`PREVIEW_RECOVERY_DELAY_MS` = 200 ms),
  `:869` (`commitOpticsReady` publishes `cameraReady = previewReady`), `:7671-7707` (`resume()`).
- **Chain:** a preview EGL failure schedules a rebind 200 ms later → the user backgrounds inside the
  window → the rebind is dropped → `resume()` reopens the camera, but `commitOpticsReady` publishes
  Not-Ready because `previewReady` is still false and nothing binds the surface again. The app sits
  under the "preview interrupted, recovering" status with a disabled shutter.
- **Fix:** the same resume-side rebind as TR5-1 step 2 (condition it on `!previewReady` too). Test in
  the same harness. **PMA110:** no change on the normal path.

## TR5-3 — Caps reconciliation on non-recording routes ratchets video stabilization and frame rate down, persistently

- **Severity** Medium. **Confidence** High that the ratchet exists. **Status** Confirmed in code;
  whether it fires on PMA110 depends on which stab modes / fixed fps ranges logical camera 0 and the
  front camera advertise (needs `dumpsys media.camera`). It fires on any device whose front camera
  lacks `PREVIEW_STABILIZATION` (common) after one FRONT visit.
- **Citations**
  - `ui/CameraViewModel.kt:826-833` (`onCapsReady`): `reconcileZoomToCaps(caps)` and
    `reconcileFrameRate()` run on EVERY route's caps, in any mode.
  - `ui/CameraViewModel.kt:3170-3172, 3207, 3211-3214`: `videoStabMode` is narrowed with
    `normalizedForAvailableModes`, written to state, pushed with `engine.setVideoStabMode(narrowed)`
    and persisted with `scheduleSettingsSave()`.
  - `camera/CaptureCapabilities.kt:584-592`: ENHANCED → STANDARD → OFF; never widens back.
  - `ui/CameraViewModel.kt:3160-3166` (`reconcileFrameRate`) → `onVideoFrameRate` (`:3129-3146`):
    writes the engine, clears `activeMemorySlot`, persists.
  - `camera/CameraState.kt:1661`: the default `videoStabMode` is ENHANCED.
  - The request seam already falls back per route without editing the request:
    `camera/CaptureCapabilities.kt:564-570` (`videoStabControlModeFor`).
- **Competing hypotheses:** *holds* — the narrowing is a deliberate "truthful label" for the current
  route. *Breaks* — the label is persisted and re-fed to the engine as the REQUEST, so it outlives
  the route. This is the mirror of CLAUDE.md DNG item 4 ("a still-less session must not edit the
  request"): here a session that never records edits the video request. Code supports *breaks*.
- **Scenario:** launch in Photo; visit FRONT (or, if logical 0 lacks mode 2, simply launch). ENHANCED
  becomes STANDARD (or OFF), is saved, and every later 300 mm clip on the tele (which advertises
  `[0,1,2]`) records without the Active OIS+EIS profile. A saved 24/25/60 fps snaps to 30 the same
  way and never returns; MR banks stored afterwards capture the narrowed values.
- **Fix:** keep the operator's request separate from the displayed label (the way
  `requestedVideoResolution` already is): normalize only for display and the wire, persist and push
  the request. Or run stab/fps reconciliation only when `mode == VIDEO` on a rear route. Pure tests:
  Photo/FRONT caps without mode 2 / 60 fps, then tele Video caps with them → ENHANCED/60 restored,
  `currentExtras` never contains the narrowed value.
- **PMA110:** changes only if a non-recording PMA110 route lacks those modes (dumpsys needed). The fps
  half extends AGG4-13 (per-size fps gating, later cycle) with this cross-route persistence angle.

## TR5-4 — A failed durable delete marker closes the photo shutter until the process restarts

- **Severity** Medium. **Confidence** High. **Status** Confirmed; the user-visible trigger needs a
  failure (full disk, marker/registry capacity), so device validation of frequency is pending.
- **Citations**
  - `camera/RetainedStillDeletionOwner.kt:101-109`: `completeDeletionDurability(id, false)` sets
    `deletionJournalUnavailable = true`. The only assignment is `:108`; nothing ever clears it.
  - `camera/RetainedStillDeletionOwner.kt:244-246`: `canAdmitCapture()` is false while it is set.
  - `camera/CameraEngine.kt:5424-5427`: `durable = false` when the retirement registry is
    `CAPACITY_EXHAUSTED` or `markFamilyDeletedResult != DURABLE` (`storage/MediaStoreWriter.kt:736-762`:
    SharedPreferences `commit()` failing 3×, or `MAX_DELETED_FAMILY_MARKERS = 64` at `:63`).
  - `camera/CameraEngine.kt:5454-5457` (throw) and `:5466-5474` (refused submit) take the same path.
  - `ui/CaptureOutputTracker.kt:143-152, 262-265, 441, 459`: a family stays in `liveStillFamilies`
    until trimmed/deleted, so deleting any of the last 8 stills sends a non-null live id
    (`ui/CameraViewModel.kt:4022-4027`) even when its saves finished long ago.
  - `camera/CameraEngine.kt:5477-5482, 5103-5106`: admission false → every press refused with
    `STILL_CAPTURE_UNAVAILABLE`.
- **Scenario:** storage is full; the user deletes the last photo to free space; the marker commit fails;
  the VM restores review with "Could not delete file"; from then on every shutter press says "Still
  capture unavailable", even after space is freed. The Engine survives onStop/onStart, so only a
  process kill recovers.
- **Why the closure is too wide:** the comment's rationale ("rather than accepting an unowned tail")
  applies only while that capture can still produce outputs. Once `markCaptureProducersTerminal` has
  run for the id, no tail can arrive.
- **Fix:** replace the Boolean with a set of capture ids whose durability failed; `canAdmitCapture()`
  is false only while one of them is not yet producer-terminal (or still has unresolved discards);
  `markCaptureProducersTerminal` removes the id and republishes admission. Tests: durable=false after
  producers terminal leaves admission open; durable=false before terminal closes it and it reopens at
  terminal. **PMA110:** success path unchanged.
- Not in AGG1–4 (a pre-RPL archive raised the capacity trigger only).

## TR5-5 — No-logical-camera multi-lens devices: Photo goes standalone while zoom stays unified

- **Severity** Medium. **Confidence** High. **Status** Confirmed in code; needs a device with several
  standalone back lenses and no logical multi-camera (not PMA110, not the single-lens tablets).
- **Citations:** `camera/CameraEngine.kt:4197-4205` (`resolveNonTeleId` falls back to
  `cachedIdForFocal(choice.targetEquivMm)` when `cachedLogicalBack()` is null);
  `camera/CameraState.kt:547-556` (`standaloneRouteWanted` has no "no logical home" input). Every
  scale decision reads that predicate: `camera/CameraEngine.kt:599-601, 612-614, 3985-3999`;
  `ui/CameraViewModel.kt:2333-2343, 2923-2926, 2942-2981, 3189-3197`.
- **Chain:** Photo, DNG off, tap 3× → `standaloneRouteFor` false → VM and Engine write unified 3.0,
  lens TELE3X → `resolveNonTeleId` opens the standalone 70 mm id → caps reconcile keeps 3.0, now
  lens-local → 3× digital on the 70 mm lens (~9× framing), OSD ~9×, rail says 3×. The exact symptom
  CLAUDE.md records for the DNG door ("208 mm", readout 9.1×). Tapping 0.6× opens the UW standalone,
  zoom clamps to 1.0, re-band sets `lens = MAIN` while UW streams; a FRONT round trip then returns on
  MAIN at a framing jump.
- **Fix:** Engine publishes `logicalHomeAvailable = cachedLogicalBack() != null` (like
  `rawForcesStandalone`); make it a required parameter of `standaloneRouteWanted` (true when absent)
  and thread it through VM and Engine callers. Table test over {logical present/absent} × {mode, DNG}
  plus a VM/Engine law test for `onLens(TELE3X)` on a logical-less inventory.
- **PMA110:** unchanged (logical present ⇒ identical answer).

## TR5-6 — An in-REC snapshot hides the clip's terminal status and review registration

- **Severity** Low-Medium. **Confidence** High. **Status** Confirmed.
- **Citations:** `camera/CameraEngine.kt:5652-5656` (`shotSpec` calls
  `recordingStoragePresentation.observeCapture(captureId)` for every still, incl. the in-REC snapshot);
  `camera/CameraEngine.kt:7519-7528` (`presentRecordingStorageResult`); `camera/RecordingStorageDispatcher.kt:187-195`
  (`result.captureId < newestCaptureId` → nothing presented).
- **Chain:** REC starts with id N → in-REC snapshot gets N+1 and advances the gate → Stop → the
  clip's terminal (id N) is dropped: no `onMediaSaved` (the clip never reaches `CaptureOutputTracker`)
  and no VIDEO_SAVED / SAVE_DELAYED / KEPT_UNVERIFIED / SAVE_FAILED status.
- **Competing hypotheses:** *holds* — the comment at `:5653-5655` makes this deliberate ("a newer still
  owns current capture presentation"). *Breaks* — that reasoning covers the SAVED/review half, but
  failure and retained-take outcomes of the clip are suppressed too, so a failed or privately retained
  take is never announced. The tracker already orders by id, so forwarding `onMediaSaved(N)` would
  simply be TRACK_ONLY.
- **Fix:** always forward `onMediaSaved` to the tracker; gate only the SAVED status by id and always
  present FAILED / RETAINED / KEPT_UNVERIFIED. Pure reducer test: observe 10, observe 11, publish 10
  FAILED → presented. **PMA110:** yes (statuses appear for this case).

## TR5-7 — VM and Engine zoom-interaction state diverge across a remap door

- **Severity** Low-Medium. **Confidence** Medium-High. **Status** Likely; confirm with FrameGap on device.
- **Citations:** VM clears `interacting` synchronously at the door (`ui/ZoomGlideState.kt:88-94`,
  `ui/CameraViewModel.kt:2429-2436`); the Engine resets `zoomInteractionState` only later, on the
  setup thread (`camera/CameraEngine.kt:2450` via `:2529`/`:4420`, `:955`, `:1187`, `:7668`); moving
  ticks submit when the Engine state is inactive (`camera/CameraEngine.kt:4796-4806`,
  `camera/ZoomSubmitPlan.kt:33-39`); pinch is not gated on `cameraReady` (`ui/CameraScreen.kt:858-863`).
- **Chain a:** a mode/FRONT/DNG/TC door → the user pinches before `wireController` runs → first flush
  sets Engine and VM interacting → `wireController` resets the Engine to inactive → VM still says
  interacting, so it never re-sends `setZoomInteraction(true)` → every later 16 ms flush has
  `submitNow = true`: a ~180 ms repeating-request stall per tick for the whole gesture (the
  "pinch stutter" CLAUDE.md says cycle 9 removed), with no boost and no wide aim.
- **Chain b:** `applyTeleconverterOptic` (`ui/CameraViewModel.kt:2742-2769`) cancels the VM's
  interaction-end and quiet landing, but `setTeleconverterDeclaration`
  (`camera/CameraEngine.kt:3843-3858`) neither reopens nor resets → the Engine stays active →
  `reconcileZoomToTeleconverterOptic`'s `engine.setZoomRatio(z)` (`:2794`) only notes the request →
  the 1.2× wide aim and FPS boost stay on the wire until the next gesture.
- **Fix:** `invalidateOpticsDerivedState` calls a new `engine.resetZoomInteractionForRemap()` (state
  only; no controller call), or `flushZoom` re-issues `setZoomInteraction(true)` whenever the Engine
  reports inactive. Pure test with `ZoomInteractionState` + fake Engine. **PMA110:** removes stall /
  wide-aim residue; exact framing unchanged.

## TR5-8 — Processed-only still discarded on a characteristics read failure

- **Severity** Low-Medium. **Confidence** High. **Status** Confirmed. Extends AGG3-9 / AGG4-37.
- **Citations:** `camera/CameraController.kt:2308-2311` (`chars == null` → `onError("Missing camera
  characteristics")` regardless of `p.wantRaw`); in the Engine `rawChars` is only used by
  `stillPipeline.saveDng` (`camera/CameraEngine.kt:5916-5918`); HEIF/JPEG and `exifShotOf`
  (`:8080-8169`) read `spec.caps`.
- **Scenario:** a transient `getCameraCharacteristics` failure (resume race) or a later BURST/AEB frame
  whose read hits the spent metering gate drops a HEIF/JPEG-only shot whose Images the HAL already
  delivered.
- **Fix:** require `chars` only when `p.wantRaw`; make the callback's chars nullable and
  `checkNotNull` on the DNG branch. Host test via the chain-reread seam with a null read and
  `wantRaw = false`. **PMA110:** only on the failure path, for the better.

## TR5-9 — A.4 regression: the recall's silent audio restore clears the slot it just lit

- **Severity** Low. **Confidence** High. **Status** Confirmed.
- **Citations:** `AudioDenialReason.kt:61-68` (`afterRecall` → `restoreIfGranted(announce = false)`);
  `MainActivity.kt:1060-1063` (`restoreAudio` → `vm.onToggleRecordAudio(true)`);
  `ui/CameraViewModel.kt:2624-2629` (`onToggleRecordAudio` writes `activeMemorySlot = null` and saves).
- **Chain:** recall a denial-silent bank while the mic is granted → `applyLoaded` lights
  `activeMemorySlot = slot` → `afterRecall` writes reason=true, sees the grant, restores →
  `onToggleRecordAudio(true)` clears the slot. Every recall of that bank shows no active slot. The
  KDoc at `AudioDenialReason.kt:38-41` says the restored toggle "is what the operator recalled".
- **Why tests miss it:** `MemoryBankAudioProvenanceTest` uses a fake `restoreAudio` lambda.
- **Fix:** a VM entry (e.g. `restoreAudioAfterGrant()`) that sets `recordAudio = true`, refreshes the
  standby meter and saves without touching `activeMemorySlot`; use it for `announce = false`.
  Robolectric test: recall + granted → `activeMemorySlot == slot && recordAudio`. **PMA110:** UI only.

## TR5-10 — A.6 incomplete on the async rollback path

- **Severity** Low. **Confidence** High in code, rare in practice. **Status** Confirmed.
- **Citations:** `ui/CameraViewModel.kt:1517-1521` (pending triple armed from the bank once the
  engine accepts); rollback handler `:963-1050` only edits the DNG bit of
  `pendingPhotoFormatsUntilInventory` (`:1036`), not `pendingCodecUntilInventory` /
  `pendingTransferUntilInventory`, while it reverts `state.transfer`/`videoCodec` (`:1001-1002`) and
  saves (`:1048`); `currentExtras` (`:1666-1708`) and `applyEncoderInventory` (`:2819-2824`) prefer
  the pending values.
- **Scenario:** launch restore arms HLG/HEVC/HEIF → before the encoder inventory lands, an MR recall of
  an SDR/AVC/JPEG bank is accepted → its route fails asynchronously → optics roll back but the save
  persists the rolled-back bank's codec/transfer/formats, and the inventory applies them.
- **Fix:** snapshot the previous pending triple with the recall's optics generation in `applyLoaded`;
  restore it in the rollback handler when the generation matches and the inventory is still pending.
  Robolectric test. **PMA110:** unchanged except on this failure path.

## TR5-11 — M.6 incomplete: rollback after a hold-cancelling recall re-latches AE lock

- **Severity** Low. **Confidence** Medium-High. **Status** Likely.
- **Citations:** `ui/CameraViewModel.kt:1532-1533` (hold cancelled after the engine froze its rollback
  baseline); the baseline `before.controls` carried the held `aeLock = true` (`:320-324`); rollback
  restores it in the engine (`camera/CameraEngine.kt:1120, 1161`) and VM (`ui/CameraViewModel.kt:1007`);
  `:1757` `operatorOwnedControls` passes it through; `:1048` persists.
- **Chain:** AEL held (prior false) → MR recall accepted, hold cancelled (`prior = null`) → route fails,
  rollback sets `aeLock = true` → key release restores nothing → a momentary press becomes a persisted
  latched AE lock (the AGG4-74 defect via the rollback door).
- **Fix:** keep the cancelled hold's `prior` with the transaction generation; on rollback of that
  generation re-arm the hold or write `aeLock = prior` without persisting. Robolectric test.
  **PMA110:** no camera change.

## TR5-12 — Punch-in assist ownership survives a recall or explicit toggle

- **Severity** Low. **Confidence** Medium. **Status** Likely.
- **Citations:** `ui/CameraViewModel.kt:3354-3365` (`onAutoPunchIn(false)` writes `punchIn = false`
  even when `autoPunchInActive` is false); `applyLoaded` never clears `autoPunchInActive`
  (`:1701-1702` saves `punchInBeforeAuto` instead of the recalled value);
  `ui/controls/ManualDials.kt:255-263` calls `onAutoPunchIn(false)` on ruler close whenever
  `state.punchIn`; `ui/CameraScreen.kt:543-549` `openSheet` does not close an open ruler.
- **Scenario:** Focus ruler open (assist on, prior false) → recall a bank with punch-in on (or toggle
  punch-in off/on in the sheet) → close the ruler → punch-in turns off and is persisted.
- **Fix:** `onAutoPunchIn(false)` is a no-op when `!autoPunchInActive`; `applyLoaded` and the explicit
  toggle clear `autoPunchInActive`. VM tests. **PMA110:** UI only.

## TR5-13 — A latched Stop can be absorbed by a racing REC press

- **Severity** Low (Medium impact, microsecond window). **Confidence** Medium. **Status** Likely.
- **Citations:** `camera/CameraEngine.kt:6113-6120` (`completeAttempt`: `completeAdmission` → `onResult`
  → `stopRecording()`); `:6098-6110` (new `startRecording`: `tryBeginAdmission` then refusal on the
  held topology lease → `completeAdmission(false)`); `:6858-6861` (`stopRecording` →
  `recAdmission.requestStop()` returns true while ANY admission is in flight);
  `camera/RecordingAdmissionLatch.kt:20-53`.
- **Chain:** Stop pressed during admission and latched (VM `isRecording=false`) → admission succeeds,
  recorder published → recorder executor: `completeAdmission(true)` clears `inFlight` → main: a new
  REC press → `tryBeginAdmission()` sets `inFlight=true` → recorder executor: deferred
  `stopRecording()` → `requestStop()` returns true and latches onto the NEW admission → the new
  admission is refused (lease CAS) → `completeAdmission(false)` discards the latch. The published
  recorder keeps running with the mic live; the VM shows not-recording (`ui/CameraViewModel.kt:1208`
  ignores the late start), and later REC presses are refused RECORDING_ALREADY_ACTIVE. Only
  backgrounding ends the clip.
- **Fix:** when `completeAdmission` returns `stopNow`, run a private stop that claims the published
  recorder directly (skip `retirePreNativeRecordingAllocation` and the latch). Harness test of the
  interleaving. **PMA110:** none on normal paths.

## TR5-14 — Standby meter stays off after a Stop-latched admission fails

- **Severity** Low. **Confidence** Medium-High. **Status** Confirmed, uncommon combination.
- **Citations:** `camera/StandbyAudioController.kt:591-594, 608, 948` (`abortRecording` calls `start()`;
  `canStart` is false while a pending REC token exists; no retry); `camera/CameraEngine.kt:6831` runs
  `abortRecordingStart` before the `finally` at `:6438` calls `abandonPendingAdmission`;
  `ui/CameraViewModel.kt:3549-3558` ignores `onResult(false)` on generation mismatch; `:1782` refresh
  early-returns when `enabled == standbyMeterEnabled`.
- **Scenario:** Stop after allocation claim → VM refresh `setEnabled(true)` leaves `wanted=true` but no
  meter (token pending) → admission fails (native setup, superseded session, mic release timeout) →
  restart dropped while the token is still pending → VM ignores the result → the armed-video level
  meter stays dead until a mode/visibility/lifecycle change.
- **Fix:** restart standby after `abandonPendingAdmission`, or have `abandonPending` notify standby.
  Host test with an injectable `canStart`. **PMA110:** none on normal paths.

## TR5-15 — RAW-only BURST/AEB continuation refused by its own still-held DNG admission

- **Severity** Low. **Confidence** Medium-Low. **Status** Likely (needs preemption under load).
- **Citations:** `camera/CameraEngine.kt:5925-5940` (RAW-only DIRECT transfer hands `publishDng` to a
  process worker, `camera/StillPublicationDispatcher.kt:48-51, 127-154`); `:5967-5970` (camera thread
  calls `releaseDngAdmission()` only afterwards in `finally`); worker terminal `finishDng →
  settleRegisteredStillShot → onDone → fire(shot + 1)` (`:5838-5844, 5621-5644, 5197-5207`) →
  `ProcessDngPreCaptureAdmission.owner.tryAcquire()` (`:4935-4940`) fails; `captureBurst` ignores the
  false return; AEB restores controls and ends early (`:5247, 5277`). Write-failure (`:5941-5948`) and
  `onError` (`:5977-5988`) share the ordering.
- **Fix:** release the DNG admission before handing work to the publication/cleanup lanes (it is not
  needed after `saveDng` returns), or have chain continuations retry/report. Synchronous-dispatcher
  host test. **PMA110:** SINGLE unchanged. Related to AGG4-68 (already tracked), but a distinct effect.

## TR5-16 — Zoom flush between Engine rollback and VM rollback post

- **Severity** Low. **Confidence** Medium. **Status** Likely (narrow window).
- **Citations:** `camera/CameraEngine.kt:1120` (`controls = restored.controls` under the monitor),
  `:1266` (`onOpticsRollback` posted to main); `:4782-4806` (`setZoomRatio` has no generation/scale
  guard); `ui/CameraViewModel.kt:963-1007` (VM adopts `rollback.controls` later).
- **Chain:** Photo→Video door converts VM zoom to lens-local → reconfigure fails → Engine restores
  unified 3.0 on the still-streaming logical controller → before the main post, a pinch/ease flush from
  the VM's lens-local base (~1.1) calls `engine.setZoomRatio(1.1)` → wire, GL and still-truth go to
  1.1× unified → post sets VM zoom 3.0. OSD 3.0 vs preview/stills 1.1 until the next zoom input. Same
  class as AGG2-7/AGG3-10, for zoom.
- **Fix:** after applying the rollback, the VM re-pushes `rollback.controls.zoomRatio`; or zoom writes
  carry the optics generation the VM observed and superseded-scale writes are dropped. Fake-controller
  host test. **PMA110:** failure path only.

## TR5-17 — Route-inventory fold: stale route snapshot and no glide hygiene

- **Severity** Low. **Confidence** Low-Medium. **Status** Likely, timing-dependent; pre-existing (not
  caused or fixed by A.18 / 54c90d3a).
- **Citations:** `camera/CameraEngine.kt:1556` (also `:1582, 1609, 1624, 1632`): the setup thread
  evaluates `activeCameraRoute` into the callback argument; `ui/CameraViewModel.kt:795-805, 4524-4543`
  fold writes `facing`/`activeCameraRoute` from it unconditionally and, on a route change, resets zoom
  without `invalidateOpticsDerivedState` (`ZoomGlideState.pendingRatio` / `easeTarget` keep old-scale
  values); `camera/CameraEngine.kt:4110-4122` front door sets the route on main;
  `camera/CameraEngine.kt:1507-1528` Engine-side reset.
- **Chain:** on resume/GL start the setup thread reads BACK → main runs `onToggleFrontCamera` (Engine
  and VM FRONT) → the fold applies BACK; `routeChanged` is true → VM facing returns to BACK while the
  Engine streams FRONT. Separately, a real topology change resets zoom with a live old-scale glide.
- **Fix:** carry the Engine's route-publication generation and drop stale folds (or fold only
  `cameraRoutes` / `rawForcesStandalone` and leave facing to transaction-owned paths); route the
  route-changed branch through `invalidateOpticsDerivedState`. **PMA110:** none in practice.

## TR5-18 — BURST/AEB refusal still blinks the shutter (Info)

- `camera/CameraEngine.kt:5177-5185`: BURST/AEB return true regardless of the head shot's dispatch, so
  `fireShutterWithFeedback` (`ui/CameraViewModel.kt:3412-3414`) blinks on a "Finishing previous photo"
  refusal; unlike SINGLE (`:5140-5174`) the dispatch is not `runCatching`-wrapped (no realistic thrower
  found). Fix: return the head dispatch result and wrap it like SINGLE. **PMA110:** no.

---

## Already tracked, re-observed (no new ID)

- AGG4-30 (fixed by A.11 for the codec): `frozenRecordingBitRate` still reads the live
  `bitrateLevel` (`camera/CameraEngine.kt:6608`); only the codec is frozen. Info; the diagnostic value
  can still disagree with the take if the level changes during admission.
- AGG4-10: the trailing `engine.setOpenGate / setHiResStill / setAspectRatio`
  (`ui/CameraViewModel.kt:1545-1563`) are not undone by `commitOpticsRollbackLocked`, which restores
  `videoSize`/`previewStreamSize` from the baseline (`camera/CameraEngine.kt:1126-1135`).
- AGG4-33 (Video + non-SDR launch configures SDR then reopens), AGG4-8, AGG4-23, AGG4-68 — no new
  evidence beyond TR5-15.
- AGG4-3/AGG4-4: live tail uses `finalizedVideoTrackProbe(muxerStopThrew=true)`
  (`storage/MediaStoreWriter.kt:1797`), consistent with the 93c43931 recovery verdict.

## Hops checked that HOLD (coverage)

Flow 1 — still capture
- SINGLE snapshot lease released exactly once on every refusal (`camera/CameraEngine.kt:4903-4923,
  4936-4953, 5131-5175`; `Lease.release` idempotent, `camera/ProcessedSnapshotBudget.kt:43-47`).
- DNG pre-allocation cancel-before-start, deadline race, `claim`/`retire` exactly once
  (`camera/DngPreCaptureAllocation.kt:117-186`); late value cleaned via `onLateValue`; sync rejection
  continues once (`:202-237`). A.9 holds.
- Per-shot lane accounting: CAS-guarded finishes ⇒ one settle (`camera/CameraEngine.kt:5773-5802`);
  cancelled `RejectedOutputCleanupReservation.submit` returns false (`storage/MediaStoreWriter.kt:2103-2112`).
- Mixed DNG ordering behind the processed sibling on `ioExecutor`; DIRECT when the sibling was never
  queued (A.5/AGG4-7, `camera/StillPublicationDispatcher.kt:73-91`).
- Controller `capturePhoto` always reaches the callback (`camera/CameraController.kt:2030-2041,
  2085-2097, 2219-2243, 2354-2366`); watchdog vs late image token/timestamp checks
  (`:2071-2083, 2154-2166, 2281-2293`); ZSL ring skips unpaired entries (`:1644`).
- JPEG EXIF splice / passthrough tiers (`capture/HeifExif.kt:87-191`;
  `capture/StillCapturePipeline.kt:776-796`); publication ordering and tombstoned DISCARD
  (`capture/StillCapturePipeline.kt:553-610`; `camera/RetainedStillDeletionOwner.kt:157-202`);
  tracker RAW→displayable upgrade and TRACK_ONLY eviction (`ui/CaptureOutputTracker.kt:156-191`).
- EXIF zoom scale per route (`camera/CameraEngine.kt:8101-8114`).

Flow 2 — REC
- Stop while allocation WAITING/ALLOCATED → retire → abandon + `completeAttempt(false)`
  (`camera/RecordingPreNativeAllocation.kt:221-246`; `camera/CameraEngine.kt:6206-6214`); stop after
  claim latched (`:6860-6864`); pre-publication `hasStopRequest` recheck (`:6235`).
- Pause/failure during native setup and between publish and encoder attach
  (`camera/CameraEngine.kt:6653-6684, 6013-6021, 6765-6773`; `gl/GlPipeline.kt:750`).
- Camera fault mid-REC claimed under both monitors, detach-before-finalize (`camera/CameraEngine.kt:3584-3666`).
- Deadline first-wins (`camera/RecordingTeardownCoordinator.kt:111-158`); audio worker owner gate
  (`video/VideoRecorder.kt:1349-1370`); degrade-to-video-only and tolerated muxer stop
  (`video/VideoRecorder.kt:450-473, 860-861, 1073-1086`); input Surface single release (`:476-479`);
  standby restart after normal stop (`camera/CameraEngine.kt:7062-7070`); 7797d0b3 orphan helper.

Flow 3 — settings restore
- SettingsStore per-field readers, LOG→SLOG3_CINE, `reconcileConverter`, `missingPhoneModel`,
  synchronous commit (`storage/SettingsStore.kt:150-181, 241-399`).
- Init order and `recalledCameraRoute(UNKNOWN) = BACK` (`camera/CameraSelector2.kt:60-89`).
- One `setResolvedOptics` packet before start (`ui/CameraViewModel.kt:1491-1508`;
  `camera/CameraEngine.kt:2909-2944`); pre-start setters store intent only (`:2131-2145, 3486-3500,
  3819-3830, 3886-3891`).
- GL-first start snapshots intent after GL input (normal path; see TR5-1 for the paused path).
- Lens inventory only re-pushes the declaration; A.11 (53c0ac65), A.19 (85e1bef8), MRG4-8 (7a2d6cb5) hold.
- Ready reducer CAS ownership (`ui/CameraViewModel.kt:880-957`); Remember-OFF consistency
  (`:1315-1323, 1733-1735, 3593-3597`); save timing incl. `onStop` synchronous save after hold release
  (`:479-483, 1733-1752, 4307-4357`).

Flow 4 — MR recall
- A.21 provenance in VM (`ui/CameraViewModel.kt:3636-3642`); A.3 video-size reconfigure
  (`camera/CameraEngine.kt:3038-3042, 8826-8841`); A.5 slot cleared on rollback (`ui/CameraViewModel.kt:1029`);
  A.6 synchronous half (`:1452, 1512-1521`); recall refused while recording (`:3706, 3530`;
  `camera/CameraEngine.kt:2889, 2960-2964`); recall while FRONT is structural (`ui/ZoomMath.kt:307`);
  recall over recall dropped by generation guards (`camera/CameraEngine.kt:2955-2958, 841-844`);
  paused recall converges on resume (normal path); declaration packet restored on rollback.

Flow 5 — mode / facing / zoom
- `unifiedZoomOf`/`localZoomOf` round-trip with optical-inventory divisors (`camera/CameraState.kt:432-461`);
  `remapModeOptics` consumed verbatim (`ui/ZoomMath.kt:374-424`; `camera/CameraEngine.kt:2704-2758`);
  DNG door (`ui/CameraViewModel.kt:2565-2603`; `camera/CameraEngine.kt:4213-4293`); TC transition shared
  (`camera/CameraState.kt:476-520`); FRONT entry/exit lens-based return (A.10 holds); every main-thread
  door invalidates the glide and cancels zoom runnables (`ui/CameraViewModel.kt:2429-2436`); A.8, A.18
  (modulo TR5-17), A.19, A.23 hold; GL halZoom gate reset per `startPreview`
  (`camera/CameraController.kt:1062`).

Flow 6 — pause / resume
- Dual-open candidate vs pause (`camera/CameraEngine.kt:4422-4435, 4632-4639`); sequential open vs pause
  (`:2531, 842`); `commitOpticsReady` vs pause ordering (`:840-844, 550-567`); camera-health recovery vs
  pause (`:3589, 3683, 3711`); cold-start/bare retry cancel + MRG4-3 classification (`:3761, 8722-8733`);
  mode-flip task while paused (`:2783`); DNG door drops cached override while paused (`:4255-4268`);
  active REC claimed and finalized off main at pause (`:7578-7601`); `onStart`/`onStop` idempotency and
  `release()` gate-before-GL-stop (`ui/CameraViewModel.kt:4293-4309`; `camera/CameraEngine.kt:7873-7885`).

## Final sweep (commonly missed)

- Exactly-once: checked latch, allocation, lease, lane-finish, Surface release — only TR5-13 breaks it.
- "Skipped vs deferred" work under `paused`: the systematic gap is TR5-1/TR5-2 (work skipped while
  paused with no resume-side replay). Other `paused` early-returns (mode task, recall task, DNG door,
  recovery) are re-derived by `resume()` via `currentOpticsReconfiguration()` and hold.
- Request vs label: TR5-3 (stab/fps) is the remaining "normalization edits the request" instance; DNG
  formats were fixed in a still-less session per CLAUDE.md item 4.
- Process-lifetime latches: TR5-4 is the only one-way latch found on the shutter admission path.
- Scale: TR5-5 and TR5-16 are the only wrong-scale paths found; all others round-trip.
- Not deep-traced: process-wide DNG publish / identity-recovery / pre-native allocator owners across
  Engine generations beyond their admission edges; GL encoder-swap timing beyond the attach checks.

## Files covered

`camera/CameraEngine.kt` (lifecycle, GL start, preview bind, reconfigure, recovery, optics
transactions, rollback, capture, REC, storage presentation, deletion); `camera/CameraController.kt`
(capture, ZSL ring, watchdog, `tryComplete`, zoom-result gate); `camera/CameraState.kt`;
`camera/CaptureCapabilities.kt`; `camera/CameraSelector2.kt`; `camera/ZoomSubmitPlan.kt`;
`camera/ZslAdmission.kt`; `camera/DngPreCaptureAllocation.kt`; `camera/ProcessedSnapshotBudget.kt`;
`camera/StillPublicationDispatcher.kt`; `camera/RetainedStillDeletionOwner.kt`;
`camera/RetainedStillDiscardDispatcher.kt`; `camera/RecordingAdmissionLatch.kt`;
`camera/RecordingPreNativeAllocation.kt`; `camera/RecordingStorageDispatcher.kt`;
`camera/RecordingTeardownCoordinator.kt`; `camera/StandbyAudioController.kt`;
`camera/LaunchMediaRecoveryCoordinator.kt`; `camera/RendererAssists.kt`; `camera/OpticsConstraints.kt`;
`camera/DeviceProfile.kt`; `capture/StillCapturePipeline.kt`; `capture/HeifExif.kt`;
`capture/StillSnapshot.kt`; `capture/HeifCapture.kt`; `capture/DngCapture.kt`;
`storage/MediaStoreWriter.kt`; `storage/SettingsStore.kt`; `storage/LatestCaptureReducer.kt`;
`video/VideoRecorder.kt`; `video/AudioReadPolicy.kt`; `video/EncoderCaps.kt`; `gl/GlPipeline.kt`;
`ui/CameraViewModel.kt`; `ui/CaptureOutputTracker.kt`; `ui/ZoomGlideState.kt`; `ui/ZoomMath.kt`;
`ui/MomentaryHold.kt`; `ui/CameraScreen.kt`; `ui/controls/ManualDials.kt`; `ui/controls/FnQuickActions.kt`;
`ui/overlays/Overlays.kt`; `MainActivity.kt`; `AudioDenialReason.kt`; `CameraPermissionPolicy.kt`;
`HardwareInputPolicy.kt`; plus `CLAUDE.md`, the cycle-4 aggregate and plan, and commits 5528a958,
a41edd49, 93c43931, 7797d0b3, 54c90d3a, 6e75d245, 7f137b9b.
