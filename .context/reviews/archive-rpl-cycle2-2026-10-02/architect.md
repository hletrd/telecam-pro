# Architect review — 2026-10-02 (RPL cycle 2)

Lane: architecture and design risk (coupling, layering, ownership/generation models, invariants
spread across sites, module boundaries). Read-only pass over `app/src/main/kotlin/me/hletrd/telecampro/**`
at HEAD `e5729ffd`, with `CLAUDE.md`, `docs/ARCHITECTURE.md`, the cycle-1 archive
(`.context/reviews/archive-rpl-cycle1-2026-10-02/`) and `docs/plans/2026-10-02-rpl-cycle1.md`.
Nothing here was device-verified. Owner decisions (ZSL dark refusal, FocusDetail threshold,
CameraUnit SDK, proprietary HDR, orientation-moves-no-control, CPH2841 pending) are not relitigated.

## Inventory

| Package | Main owners (lines) | Cycle-1 delta |
|---|---|---|
| `camera/` | `CameraEngine` (8,690; 7 `beginOpticsTransaction` doors, 6 bare `reopenForSession()` doors), `CameraController` (2,878), `CameraState` (1,899), `ManualControls` (1,208), 11 `Process*` owners | DNG door now owns a transaction; `rawWanted` joined `OpticsSnapshot`; `lensBandFollowsZoom`; lazy characteristics retry |
| `ui/` | `CameraViewModel` (4,629), `CameraScreen` (3,404), `ZoomMath` (461), `CaptureOutputTracker`, `controls/*`, `review/*` | `remapRouteScaleOptics`, `restoredOptics(photoStandalone)`, rollback mirror of `dngRaw` |
| `video/` | `VideoRecorder` (2,496) + `UnsafeRecorderQuarantine` admission gate | token-scoped worker door; pending token now excludes every owner-keyed caller |
| `storage/` | `MediaStoreWriter` (3,068, `object`), `PendingDiscardJournal`, `SettingsStore` | tri-state video probe, identity-warning gate, persisted bounds, `missingPhoneModel` seed |
| `gl/`, `capture/`, `stab/`, `focus/` | `GlPipeline` (2,239), `FlipRenderer`, `StillCapturePipeline` (713), `GyroEis`, `MacroProximity` | diagnostics only |
| top level | `MainActivity` (1,172), `CameraPermissionPolicy` | MR recall now clears `AUDIO_OFF_BY_DENIAL_KEY` |

Checked with no new finding: layering is unchanged (`camera/ gl/ video/ capture/ storage/` import no
`ui/`; `ui/` imports no `camera2`); every raw `android.util.Log` call sits behind
`recurringDiagnosticAllowed` or `processDiagnosticLogBudget.tryAcquire()`; the new admission gate
(pending token excludes all owner-keyed callers, workers enter by token) is consistent with the
replay observer, which parks same-owner callers on `pendingToken` and releases them in `publish`
(`VideoRecorder.kt:1514-1527, 1559-1570`), and the encoder EGL attach runs only after
`publishAdmission` (`CameraEngine.kt:6383-6420`); `acceptedOpticsAuxState` keeps the RAW axis
(`OpticsConstraints.kt:73-77`); process singletons are unchanged since cycle 1.

The cycle-1 theme still holds. Most defects come from **one fact owned in two places**: the
Engine's field versus the ViewModel's mirror, or one transaction versus a trailing setter. ARCH2-1
through ARCH2-4 are four concrete ways that split still shows.

---

## Findings

### ARCH2-1: A DNG write that did not change the route at the time survives a rollback that makes it change the route. The engine then wants RAW over a restored logical session (the AGG-4 divergence again)
- **Where:** `camera/CameraEngine.kt:3225-3230` (`rawWantedDirectWrites`), `:4005-4015` (the
  direct-write branch, used whenever `!routeFlips || !started || activeCameraRoute != BACK`),
  `:1011` (`if (rawWantedDirectWrites == before.rawWantedDirectWrites) rawWanted = before.rawWanted`),
  `:1032` (publication carries the surviving value), and the VM mirror at `ui/CameraViewModel.kt:971-975`.
- **Why:** Commit 3ec126e1 classifies a DNG write by whether the route answer flipped *when the write
  happened*: in VIDEO (always standalone) or on FRONT/EXTERNAL it did not. A rollback, however,
  restores the baseline's mode and route. Under that restored packet the same `rawWanted` value
  does choose the route (PHOTO on BACK with `rawRequiresStandalone`). The guard keeps the later value
  and also restores the route that value contradicts. `rawWanted` is meant to be a route input
  that rolls back with its route (`CameraEngine.kt:663`), and this breaks that.
- **Failure scenario (PMA110):** (a) Photo, logical camera 0, DNG off, Ready, unified 3.0 on the
  TELE3X band. The user taps Video (T1: `setVideoMode`; the baseline is Photo/logical with
  `rawWanted=false`). While T1 is still queued, they turn DNG on in the sheet. Video is standalone
  either way, so this takes the direct-write branch and sets `rawWanted=true`, `writes+1`. T1 then
  fails (for example `resolveNonTeleId` returns null or the camera is busy, giving
  `CAMERA_UNAVAILABLE_MODE_UNCHANGED`). `rollbackOptics` restores Photo, BACK, and the logical
  controller (`restoreSession`) but keeps `rawWanted=true`, and the VM chip stays on. (b) The same
  happens with a FRONT trip whose front open fails after DNG was toggled while FRONT was pending.
  Result: `standaloneRouteWanted(PHOTO, true, true) == true` over a logical session.
  `lensBandFollowsZoom`, `pushTeleFinder`, OSD focal, and the VM's `unifiedZoomOf(...,
  standaloneRoute=true)` read the logical camera's unified 3.0 as lens-local on the 3× band, so the
  OSD shows about 9× / 208 mm, which is the AGG-1 symptom. Shots drop DNG at capture time. The
  obvious repair makes it worse: turning DNG off makes `onSetPhotoFormats` remap
  local→unified (`remapRouteScaleOptics`, `fromStandalone=true`). That turns the real unified 3.0
  into 9.0, and the engine publishes it inside the DNG transaction, so the logical camera lands at
  9×.
- **Fix:** In `commitOpticsRollbackLocked`, decide restoration by route consequence, not by write
  count. If `standaloneRouteWanted(restored.mode == VIDEO, rawWanted, law)` on the restored route
  differs from the same predicate with `before.rawWanted`, restore `before.rawWanted`. The
  publication already carries the field, so the chip follows. Keep the later value only when it is
  route-neutral under the RESTORED packet. Alternatively, keep it and immediately open a follow-up
  DNG door (`setRawWanted` semantics with `remapRouteScaleOptics`) after the rollback effects run.
  Add a host test: Photo/logical Ready, then a Video door, then a DNG write while pending, then a
  failed rollback. Assert that `rawWanted` matches the restored route answer.
- **Confidence:** Medium-High for the code path. The trigger needs an async door failure plus a
  sheet toggle in the pending window. **Status:** confirmed by code reading; device trigger
  needs-manual-validation.

### ARCH2-2: An MR recall or settings restore is one optics transaction plus about 25 trailing setters. An async rollback restores only the optics part, so a "recall unchanged" leaves a half-applied, persisted hybrid that is marked as the active bank
- **Where:** `ui/CameraViewModel.kt:1429-1492` (`setResolvedOptics`, then `setAeMetering`,
  `setGammaAssist`, `setVideoStabMode`, `setAspectRatio`, `setDriveMode`, …, `setHiResStill`,
  `setOpenGate`, `setVideoFrameRate`, `setRawWanted`), `:1497-1560` (state write including
  `photoFormats`, `aspectRatio`, `videoStabMode`, `openGate`, `hiResStill`, `recordAudio`,
  `activeMemorySlot = activeSlot`), the rollback mirror at `:925-982` (restores only optics, the
  declaration, and `dngRaw`; it never clears `activeMemorySlot`; it calls `scheduleSettingsSave()`), and
  `MainActivity.kt:517-529` (clears `AUDIO_OFF_BY_DENIAL_KEY` when `activeMemorySlot == slot`).
  Bare reopens under the recall's generation are at `CameraEngine.kt:1968-1983, 3299-3306,
  3608-3619, 3675-3680`, all through `reopenForSession()`, which captures the *current* generation
  (`:3320-3322`).
- **Why:** CLAUDE.md requires a recall to be "one complete packet", and a rejected recall to restore
  the accepted state. Only the fields inside `setResolvedOptics` meet that rule. Several trailing
  setters change the session (stabilization class is a session key, open gate changes
  `videoSize`/stream, aspect changes hi-res admission, hi-res, RAW). Every other setter changes
  persisted operator state. None of them is in `OpticsSnapshot` or `OpticsRollbackPublication`.
- **Failure scenario:** The user recalls MR2 (a Video bank with Active stabilization, Open Gate, 16:9,
  BURST, audio on). `setResolvedOptics` returns `true` (it accepts synchronously). Then the async
  task fails, for example the target lens is held by another app, `cachedCaps` returns null, or
  `selectCurrentLens()` returns null, and `rollbackOptics(..., CAMERA_UNAVAILABLE_RECALL_UNCHANGED)`
  runs. The UI shows "camera unavailable, recall unchanged" and restores the old mode, lens, zoom,
  and route. But stab mode, open gate (and the `videoSize` chosen from the *outgoing* selection by
  `applyVideoSize(chooseVideoSize(sel))` at `:3305`), aspect, hi-res, drive mode, HEIF/JPEG choice,
  peaking, Fn layout, and audio all stay at MR2's values. The MR2 badge stays lit, the
  denial-reason key has already been cleared, and the rollback handler persists the hybrid with
  `scheduleSettingsSave()`, so it survives relaunch. Secondary, needs device: on a successful recall
  that changes the stabilization class, `setVideoStabMode → reopenForSession()` enqueues a second
  `reconfigureCamera` under the same T1 generation behind T1's own reconfigure, so the recall pays
  two close/open blackouts.
- **Fix:** Fold every session-shaping recall input (stab mode, aspect, hi-res intent, open gate,
  frame rate, RAW) into the `setResolvedOptics` packet and `OpticsSnapshot`, as `requestedVideoSize`
  and the declaration already are, so one transaction both decides and rolls back the session. For
  the non-session fields, have the VM hold the pre-recall `CameraUiState` slice and restore it, and
  clear `activeMemorySlot`, when `onOpticsRollback` arrives for the recall's generation. Make the
  denial-key clear a VM-owned edge emitted only from the Ready commit of that recall, not a peek
  at `activeMemorySlot` right after a call that is still async.
- **Confidence:** High for the hybrid, which is deterministic once the async rollback fires. Medium
  for the double reopen. **Status:** confirmed by code reading (hybrid); needs-manual-validation
  (double blackout).

### ARCH2-3: The split recall (T1 `setResolvedOptics`, then T2 `setRawWanted`) can open the wrong route. `reconfigureCamera` resolves the camera from live fields when the task runs, not from T1's packet
- **Where:** `ui/CameraViewModel.kt:1429` and `:1492`. `camera/CameraEngine.kt:2780-2790`
  (`resolveNonTeleId(resolvedLens)` evaluated on `setupExecutor` with the *live* `rawWanted`),
  `:4123-4205` (`selectCurrentLens()` and `reconcileControlsWithCaps` under T1 ownership), and
  `:565-593` (`lensChoice = LensChoice.forZoom(controls.zoomRatio)` when `lensBandFollowsZoom`).
- **Why:** Cycle 1 tracked the two-transaction recall as a structural residual under AGG-49 and
  judged rollback safe. That judgment holds: `selectRollbackBaseline` keeps the last Ready
  baseline. But nothing orders T1's setup task after T2's `beginOpticsTransaction`. T1's packet
  carries the lens and the lens-local zoom of a DNG bank, yet the route is resolved later from
  `rawWanted`, which T2 has not published yet.
- **Failure scenario (PMA110):** Current state is DNG off on logical camera 0. The user recalls a
  bank with Photo, DNG on, the TELE3X band, and local 1.0 (the standalone 70 mm lens at 1×). T1
  publishes lens=TELE3X, zoom=1.0. If `setupExecutor` runs T1 before the main thread reaches line
  1492 (it is idle, ids and caps are cached, and the main thread still has about 17 setters to run),
  T1 resolves the logical id because `rawWanted` is still false. Its owned candidate swap then runs
  `reconcileControlsWithCaps`, and `lensBandFollowsZoom` is true, so `lensChoice = forZoom(1.0) =
  MAIN`. T2 then publishes `rawWanted=true` with `resolvedLens = null`, keeps `MAIN`, and reopens
  the standalone 23 mm main lens at 1.0. The VM still shows TELE3X, so the OSD focal reads about
  69 mm while the wire is 23 mm. The recalled 3× bank lands at 1×, and nothing re-converges until
  the next lens tap.
- **Fix:** Close the AGG-49 residual now. Add `resolvedRawWanted` to `setResolvedOptics`, write it
  inside its `beginOpticsTransaction`, and delete the trailing `engine.setRawWanted(...)` from
  `applyLoaded` (keep it for `applyEncoderInventory`). More generally, have `reconfigureCamera`
  resolve from the transaction's snapshot packet, not from live fields that later direct writers
  can change.
- **Confidence:** Medium. The ordering race is real. Its hit rate depends on how fast the
  executor runs versus the main thread. **Status:** likely; needs-manual-validation (instrumented
  recall on device, or a host test with a synchronous executor that runs T1 inline).

### ARCH2-4: ViewModel mirrors of Engine route facts start from different defaults than the Engine, and the TELE/FRONT doors compute the target zoom twice from those two copies
- **Where:** `camera/CameraState.kt:1644` (`lensInventory = LensInventory.ALL`) versus
  `camera/CameraEngine.kt:1262` (`acceptedOpticalPresets = emptySet()`, so `opticalBaseFor` answers
  MAIN). `CameraState.kt:1658` (`rawForcesStandalone = true`) versus `DeviceProfile.GENERIC.rawRequiresStandalone
  = false`. Independent computations: TELE at `ui/CameraViewModel.kt:2540-2556` versus
  `CameraEngine.kt:3760-3770`; FRONT at `ui/CameraViewModel.kt:2900-2945` versus
  `CameraEngine.kt:3886-3916`; the restore preserve branch at `ui/CameraViewModel.kt:1326-1328`.
  Mixed sources inside `applyLoaded` itself: `engine.rawForcesStandalone` at `:1324`, but
  `s.rawForcesStandalone` everywhere else.
- **Why:** "Mirrors the engine transaction exactly" (`:2876`) assumes the same function gets the
  same inputs. Before lens enumeration, which is deliberately queued after the first open, the two
  optical sets disagree on every device. `reconcileZoomToCaps` (`:3037-3040`) states the
  assumption outright ("the engine's controls and this state are the same packet"). The engine's
  result is never published back, so a divergence lasts until the next full controls push.
- **Failure scenario (one-camera tablet, TB336ZU class):** Video bank, TELE on last session, Setup
  "keep teleconverter" off. At launch `applyLoaded` runs in VM `init`, before enumeration. The
  preserve branch computes `3 / opticalBaseFor(3, ALL).zoomPreset = 3/3 = 1.0`, but the tablet's
  "3×" is a crop of its only lens, so the true lens-local value is 3.0. The session opens at 1×
  while the rail highlights the 3× band, which is the "first fix broke the tablets" shape from the
  2026-08-04 zoom-scale entry. On PMA110 the window is narrower: a TELE or FRONT toggle before
  enumeration gives the engine `optical = ∅` (divisor 1) and the VM `ALL` (divisor 3). The wire and
  the OSD then disagree until the next control change.
- **Fix:** Use one representation for "unknown". Either seed the VM mirrors from the Engine at
  construction (`engine.rawForcesStandalone` for BACK, and an `engine.opticalPresetsOrNull()`), or
  make both sides treat an unknown inventory identically (`null`, then leave the ratio alone).
  Better still, have the TELE and FRONT doors return the Engine's computed packet, as the DNG door
  now accepts one, so there is one reducer and the VM mirrors its output.
- **Confidence:** Medium. The divergent defaults are certain; the tablet scenario needs a
  non-default preserve toggle. **Status:** confirmed by code reading; device check pending.

### ARCH2-5: The lens-band predicate was centralized only on the Engine side. Four ViewModel sites still inline it
- **Where:** `camera/CameraEngine.kt:602-604` (private `lensBandFollowsZoom`, uses Engine
  `rawWanted`) versus inline copies at `ui/CameraViewModel.kt:2233-2236` (`applyZoomRatio`),
  `:3059-3063` (`reconcileZoomToCaps`), and the related route-scale branches at `:2825` (`onLens`)
  and `:1321` (`applyLoaded`).
- **Why:** AGG-3 was the same predicate drifting (`!video` versus `standaloneRouteWanted`). The fix
  made one helper, but kept it private to the Engine with Engine inputs. The VM copies use different
  sources (`s.photoFormats.dngRaw`, `s.rawForcesStandalone`), so ARCH2-1 and ARCH2-4 divergences
  produce different band answers on the two sides.
- **Failure scenario:** After the ARCH2-1 divergence, a pinch on the restored logical camera runs
  `applyZoomRatio`. The VM keeps `s.lens` (it thinks standalone) while the Engine's reconcile also
  holds the band. The rail freezes on the pre-failure band during a unified zoom from 1× to 10×.
- **Fix:** Move a pure `lensBandFollowsZoom(video, rawWanted, rawLaw, teleconverter, route)` into
  `CameraState.kt` beside `standaloneRouteWanted`, and call it from both layers. Pin it with a test
  that enumerates all 2^5 inputs.
- **Confidence:** Medium (design). **Status:** confirmed duplication; the visible effect depends on
  ARCH2-1/ARCH2-4.

### ARCH2-6: Persisted zoom has no scale tag. Its meaning depends on the device-profile law at load time, so the pending AGG-47 decision silently reinterprets saved banks
- **Where:** `storage/SettingsStore.kt` (persists `controls.zoomRatio` raw), interpreted by
  `ui/ZoomMath.kt:411-450` (`restoredOptics(photoStandalone)`) through `ui/CameraViewModel.kt:1321-1324`
  (`engine.rawForcesStandalone`, read from the *current* route's profile).
- **Why:** Whether a stored 3.0 is unified or lens-local is derived at load time from `(mode, dngRaw,
  rawRequiresStandalone)`. The third input is not persisted. It comes from `DeviceProfile.resolve`
  and `deviceProfileForRoute`. Any change to that mapping flips the reading of every existing DNG
  Photo bank.
- **Failure scenario:** AGG-47's preferred outcome ("map CPH2841 to the PMA110 profile after a device
  check") ships. A CPH2841 owner's saved Photo+DNG bank stored unified 3.0, because GENERIC kept DNG
  on the logical camera. After the update it loads as lens-local 3.0 on the 70 mm lens, which is
  9× and about 208 mm. The same applies to any future profile flag that changes route.
- **Fix:** Persist zoom in the canonical unified scale (convert with `unifiedZoomOf` at save time and
  `localZoomOf` at load time), or persist the scale explicitly with a schema version, before
  AGG-47 lands. Add a migration test that saves under GENERIC and loads under PMA110.
- **Confidence:** Medium (latent). **Status:** likely; becomes live with the AGG-47 change.

### ARCH2-7: Cycle 1 added about 10 new reserved-row producers to a deferred lifetime cap of 120. Per-shot storage-open failures can now drain it in one burst
- **Where:** `storage/MediaStoreWriter.kt:1021-1029` (`openParcelFd`/`openOutputStream` log every
  failed open, with no gate), `:1080` (publish exhaustion), `video/VideoRecorder.kt:257-262, 333-340`,
  `camera/CameraEngine.kt:5592-5608`, `camera/CameraController.kt:2407-2421`.
  `DiagnosticTelemetry.kt` is unchanged (lifetime counter, AGG-27 deferred).
- **Why:** Cycle 1 fixed silent failures by logging one row per event. That was the right fix
  locally, but AGG-27 kept the 120-row process-lifetime cap. Only the identity reader and the
  audio-degrade path are change-gated, and the per-output open helpers are not. Each failed still in
  a BURST or timelapse spends at least one row, two for HEIF plus DNG.
- **Failure scenario:** The user removes an SD-card volume or revokes storage mid-session, then
  holds the shutter in BURST, or runs a timelapse. Each frame fails `openOutputStream` or
  `openParcelFd` and logs. After about 120 frames every later camera fault, REC setup failure, and
  recovery warning in the process is dropped. That brings back the "failure with no app log line"
  signature CLAUDE.md uses for triage, now as a false positive.
- **Fix:** Pending the AGG-27 measurement, gate the per-URI open and publish helpers per failure
  episode, keyed by volume and exception class (reuse `IdentityReadWarningGate`), so a burst spends
  one row. Treat this as a precondition before adding any further per-event reserved producers.
- **Confidence:** Medium. **Status:** confirmed by code reading; needs-manual-validation for the
  ColorOS window (same exit criterion as AGG-27).

---

## Final sweep

- **Engine/VM duplicate reducers** (ARCH2-1, 2-3, 2-4, 2-5) are now the main structural risk. The
  cycle-1 AGG-48 recommendation (one pure `RouteInputs` and `resolveOpticsTransition`, with
  `OpticsSnapshot` embedding `RouteInputs` whole) would have prevented ARCH2-1 and ARCH2-3 by
  construction. Two new data points support scheduling it.
- **Recall packet completeness** (ARCH2-2) is a separate axis from route inputs. The rule "every
  route input must reach the engine from restore" was met by adding trailing setters. That met
  the letter of the rule but broke its rollback half.
- **Admission gate and quarantine:** the cycle-1 change is coherent end to end, with no finding.
- **Process singletons, layering, model-string seams:** no change since cycle 1. A3/AGG-47
  (CPH2841) stays an owner decision, but see ARCH2-6 for what it will break when decided.

## Summary

| ID | Severity | Confidence | Status |
|---|---|---|---|
| ARCH2-1 | Medium | Medium-High | confirmed (code); trigger needs device |
| ARCH2-2 | Medium | High (hybrid) / Medium (double reopen) | confirmed / needs-manual-validation |
| ARCH2-3 | Medium | Medium | likely; needs-manual-validation |
| ARCH2-4 | Low-Medium | Medium | confirmed (code) |
| ARCH2-5 | Low | Medium | confirmed (design) |
| ARCH2-6 | Low-Medium (latent) | Medium | likely; live once AGG-47 lands |
| ARCH2-7 | Low-Medium | Medium | confirmed (code); ColorOS window unmeasured |
