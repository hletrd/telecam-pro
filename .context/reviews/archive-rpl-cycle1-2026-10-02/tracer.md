# Tracer review: causal flow tracing (2026-09-30)

Scope: the six requested flows, traced through the actual code (MainActivity → CameraViewModel →
CameraEngine → CameraController/GlPipeline/VideoRecorder/StillCapturePipeline/MediaStoreWriter).
I checked every candidate against CLAUDE.md, so none of the findings below are settled owner
decisions. This was a read-only pass: nothing was built and nothing was run on a device.

Status legend: **confirmed** means the code path was read end to end and the failure follows
from it directly. **likely** means the path is read but one runtime condition is assumed.
**needs-manual-validation** means the finding depends on device timing or HAL behavior.

---

## Flow 1: settings restore on cold launch

**Traced path.** `CameraViewModel.init` runs `seedPhoneModel()` and then
`restoreSettingsIfEnabled()`, which calls `applyLoaded(honorPreserveOptions=true)` (`CameraViewModel.kt:1243`).
That function runs `restoredOptics(...)` (`ZoomMath.kt:372`) and then
`engine.setResolvedOptics(mode, lens, TC, declaration, controls, photoExposure, recalledVideoSize,
transfer, codec, candidates)` (`CameraEngine.kt:2683`). The engine has not started yet, so it
publishes fields only. After that come the non-transaction setters: aspect, hi-res, open gate, fps,
the renderer assists, and `setRawWanted(safeFormats.dngRaw)` (`:1461`). Last,
`loadEncoderInventoryAsync()` calls `applyEncoderInventory`, which runs `setVideoPipeline` and
`setRawWanted` again (`:2626`).

Every route input does reach the engine: DNG, hi-res, aspect, fps, transfer, codec, converter,
lens, and TELE. Facing is never persisted. `saveSettingsIfEnabled` substitutes the pre-front rear
snapshot while FRONT. The encoder-inventory race is handled by the `pending*UntilInventory` fields.

### T1. Restoring a DNG Photo setup drops the lens band (High, confirmed)
- **Where:** `ZoomMath.kt:398-401` (the `restoredOptics` PHOTO branch), called from `CameraViewModel.kt:1328`.
- **Why:** The PHOTO branch always treats the saved `zoomRatio` as **unified** and derives the lens
  with `LensChoice.forZoom(unified)`. It never asks `standaloneRouteWanted(false, dngRaw,
  rawForcesStandalone)`. With DNG on (PMA110: `rawRequiresStandalone`), Photo sits on a standalone
  lens and the saved ratio is **lens-local**. The `preserveChangedOptics` branch (`:1311-1325`)
  already handles this correctly. The ordinary branch, which covers the default launch and every
  MR recall, does not.
- **Failure:**
  - Photo + DNG + 3× lens stores (lens = TELE3X, zoom = 1.0 local). After a relaunch or MR recall it
    comes back as lens = `forZoom(1.0)` = MAIN, zoom 1.0. `resolveNonTeleId(MAIN)` then opens the
    standalone **main** lens, so the operator's 70 mm framing becomes 23 mm.
  - Local 2.0 on the 3× lens (6× unified) restores as 2× on the main lens.
  - The ultrawide with DNG restores as main 1×.
  - This is the same bug class as the "zoom scale follows the ROUTE, not the mode" bullet, on a
    seventh site that bullet does not list.
- **Fix:** Pass `photoStandalone = standaloneRouteWanted(false, e.dngRaw, rawForcesStandalone)` into
  `restoredOptics`. When it is true, keep `requestedLens` and clamp the zoom as lens-local
  (1..MAX_VIDEO_LOCAL_ZOOM), exactly like the VIDEO branch. Add a test for
  `restoredOptics(PHOTO, TELE3X, dng standalone, 1.0)` → `TELE3X/1.0`.

### T2. The persisted/MR `videoResolution` is the engine's fallback choice, not the operator's request (Medium, likely)
- **Where:**
  - `CameraViewModel.kt:801-806`: `onVideoSizeChosen` writes the engine's *chosen* size into
    `state.videoResolution`.
  - `CameraViewModel.kt:1586`: `currentExtras` persists that value.
  - `CameraEngine.kt:2742`: restore writes it into `requestedVideoSize`.
  - `CameraEngine.kt:7664-7671`: `chooseVideoSize` falls back to `auto` when the request is not offered.
- **Why:** The engine keeps the operator's pick (`requestedVideoSize`) apart from what it could
  deliver on the current route/aspect (`videoSize`). The VM collapses both into one field and
  persists the delivered value.
- **Failure:** The operator picks 1080p, then enables Open Gate. The engine chooses 2560×1920 and
  the VM shows and persists that. After a background kill and relaunch, `requestedVideoSize` is
  2560×1920. When Open Gate is turned off, that size is not in the 16:9 list, so `auto` picks 4K UHD.
  The 1080p pick is silently lost. The same thing happens through a lens/front route that lacks the
  requested size, and through MR save/recall. Within one process the engine still holds the right
  request, so the bug only shows up across persistence. That makes it hard to notice.
- **Fix:** Mirror the *requested* size separately in the VM. Update it only in `onVideoResolution`
  and restore, and persist that value. Keep `videoResolution` as the display-only delivered size.

---

## Flow 2: Photo↔Video and lens/TC/front/DNG doors

**Traced path.** `onModeChange` → `remapModeOptics` (ZoomMath) → `invalidateOpticsDerivedState`
and `clearTapFocusUi` → `engine.setVideoMode`, which runs `beginOpticsTransaction` →
`setupExecutor` → reconfigure or fast-path `commitFastPathOrReconfigure`.

`onLens` and `onToggleTeleconverter` go to `engine.setLens` → `resolveLensOpticsIntent` /
`resolveTeleZoomTransition`. `onToggleFrontCamera` goes to `engine.setFrontCamera`, using the
unified pre-front snapshot and `rearReturnZoom`. Rollback runs `commitOpticsRollbackLocked` →
`onOpticsRollback`, which mirrors mode, lens, TC, facing, route, controls, declaration and preTele.

On these five doors, zoom-scale conversion, `ZoomGlideState` invalidation, tap-AF retirement
(engine `retireTapFocusLocked` plus the VM mirror) and the finder/punch-in resolve are consistent.
**The DNG toggle is the exception.** It is documented as a route input, yet it is not handled as
an optics-remap door.

### T3. Toggling DNG in Photo changes the route and the zoom scale, but never converts the zoom (High, confirmed)
- **Where:** `CameraViewModel.kt:2370-2390` (`onSetPhotoFormats`) and `CameraEngine.kt:3959-3981` (`setRawWanted`).
- **Why:**
  - `setRawWanted` opens an optics transaction that sets only `overrideId = pin`, then reconfigures.
  - `controls.zoomRatio` and `lensChoice` are carried across unchanged, even though the route moves
    logical (unified scale) ↔ standalone (lens-local scale).
  - `reconcileControlsWithCaps` (`:560-595`) only clamps to the caps range.
  - `onSetPhotoFormats` also does none of the remap-door hygiene: no zoom rewrite, no
    `invalidateOpticsDerivedState()`, no `cancelPendingControls`, no synchronous `pushTeleFinder`.
- **Failure (PMA110, Photo, TC off):**
  - **DNG ON at unified 3.0:** lens = TELE3X band, so `resolveNonTeleId(TELE3X)` opens standalone
    70 mm at local 3.0. The operator sees a sudden 3× digital crop on the 70 mm lens. The OSD
    `unifiedZoom` reads 9× / ~208 mm. These are exactly the "208 mm / 9.1×" symptoms that CLAUDE.md
    records as fixed for the *lens tap*; this is the same bug reached through the format chip.
  - At unified 5× the result is 15× local-read.
  - **DNG OFF on the 3× lens (local 1.0):** logical at unified 1.0, then `forZoom(1.0)` = MAIN. The
    framing jumps 3× → 1×.
  - Other effects:
    - A pending coalesced control packet or a hardware-key glide target set in the old scale keeps
      driving the old number.
    - The focus-confidence evidence from the old route is not invalidated.
    - The GL Loupe Overview gate, which depends on `rawWanted` through `unifiedZoomOf`, stays stale
      until `applyStabilization` runs after the reopen.
- **Fix:** Treat the DNG flip as an optics door in both VM and engine, like `resolveTeleZoomTransition`:
  - Compute `unified = unifiedZoomOf(lens, zoom, oldStandalone, optical)`.
  - Rewrite zoom to `localZoomOf(unified)` (→ standalone) or `unified` (→ logical), and the lens to
    the band inside the same `beginOpticsTransaction` publication and the matching VM `_state` write.
  - Call `invalidateOpticsDerivedState()` and `cancelPendingControls()` in `onSetPhotoFormats` when
    `standaloneRouteWanted` changes, and `pushTeleFinder()` in `setRawWanted`.
  - Only act when the standalone answer actually flips (TC and FRONT are unaffected).

### T4. The same-route fast-path terminal mutations re-derive the lens band while ignoring DNG (Medium, likely)
- **Where:** `CameraEngine.kt:2665-2667` (`setVideoMode` fast path) and `CameraEngine.kt:2818-2820`
  (`setResolvedOptics` fast path). Both use
  `if (!video && !TC && route == BACK) lensChoice = LensChoice.forZoom(controls.zoomRatio)`.
- **Why:** These are the two surviving sites of the pre-2026-08-04 predicate. The caps-install seam
  (`:589`), the VM zoom path (`CameraViewModel.kt:2181`) and `reconcileZoomToCaps` (`:2968`) were
  all corrected to `!standaloneRouteWanted(video, rawWanted, …)`.
- **Failure:** With DNG on, a Video→Photo flip that keeps the same standalone camera and the same
  stream size takes the fast path (for example Open Gate 4:3 on a lens whose photo field matches).
  So does an MR recall of a DNG Photo preset onto the same standalone camera. In both cases the
  lens-local ratio is read as unified: local 1.0 on the 70 mm lens becomes `lensChoice = MAIN`. The
  next bare reopen, `resolveNonTeleId(MAIN)`, then moves the session to the main lens. The rail also
  highlights 1× while the focal readout says 69 mm, which is the 2026-08-04 symptom again.
- **Fix:** Use the same `!standaloneRouteWanted(videoMode, rawWanted, rawRequiresStandalone)` guard at
  both sites. Better still, route all four sites through one helper.

### T5. `rawWanted` is not part of the optics snapshot, so a failed DNG reopen or a failed MR recall leaves DNG on over a logical session (Medium, likely)
- **Where:** `CameraEngine.kt:724-748` (`currentOpticsSnapshot`, which has no `rawWanted`),
  `:940-1000` (`commitOpticsRollbackLocked`), and `:3959-3981`. On the VM side:
  `CameraViewModel.kt:915-960` (rollback mirror, no `photoFormats`) and `OpticsConstraints.kt:68-69`
  (Ready keeps `dngRaw` by design).
- **Why:** Rollback restores `overrideId` to the previous route. That is the logical id, because
  `setRawWanted` gets there through `reconfigureCamera`, whose `selectCurrentLens`/`cachedCaps` can
  roll back with `CAMERA_UNAVAILABLE_CAMERA_UNCHANGED`, or the preview-unavailable branch. But
  `rawWanted = true` survives the rollback. `applyLoaded` has the same problem: `setRawWanted` runs
  after `setResolvedOptics`, so an async rollback of the recall restores optics but not the DNG
  input.
- **Failure:**
  - The chip shows DNG on and the VM keeps `dngRaw = true`, as designed, but the session is logical
    with `raw = false`.
  - Every shutter press writes only HEIF/JPEG and posts `RAW_UNAVAILABLE`.
  - Every later bare reopen (aspect, hi-res, 10-bit) reuses `overrideId` = logical.
  - Re-tapping DNG on is a no-op because of the `rawWanted == enabled` gate. The operator has to
    toggle it off and on again to recover.
- **Fix:** Add `rawWanted` to `OpticsSnapshot`/`OpticsIntentState`. On rollback, restore it and
  publish it in `OpticsRollbackPublication`, and have the VM mirror `photoFormats.dngRaw`. Otherwise,
  on a rollback of a `setRawWanted` transaction, clear `overrideId` so the next reopen re-resolves.

### T6. `setRawWanted` while `started && paused` leaves a stale cached route that `resume()` reuses (Low, needs-manual-validation)
- **Where:** `CameraEngine.kt:3968`, where `if (!started || paused) return` runs after `rawWanted`
  is mutated. `resume()` at `:7373-7379` reopens with `currentOpticsReconfiguration().overrideId`,
  which is the last accepted id.
- **Why:** The early return is correct only for `!started`, where the first configure resolves from
  `rawWanted`. When `started && paused`, `overrideId` still caches the pre-pause camera. That is
  exactly bug #2 in the CLAUDE.md DNG bullet.
- **Failure:** This needs a `setRawWanted` that actually changes the answer while backgrounded. The
  known trigger is a late `applyEncoderInventory` post after a fast background, but today that is a
  no-op because `normalizedForEncoder` never touches `dngRaw`. Any future background-time route
  input (a settings import, an intent) would reopen the wrong route permanently.
- **Fix:** When `paused` and started, set `overrideId = userCameraPin` under the monitor before
  returning, so `resume()` re-resolves.

### T7. `setRawWanted` on the FRONT route runs a full front reopen for nothing (Low, confirmed)
- **Where:** `CameraEngine.kt:3959-3981`. `standaloneRouteWanted` ignores facing, so the answer flips
  and `reconfigureCamera(null, …)` runs. `selectCurrentLens()` then returns `cachedFront()`, the
  same camera.
- **Failure:** Tapping the DNG chip while FRONT causes a visible black dip (close/open of the same
  front camera) with no route change.
- **Fix:** Skip the transaction when `activeCameraRoute != BACK`. The field update alone is enough,
  because leaving FRONT re-resolves through `resolveNonTeleId`.

---

## Flow 3: shutter → still → save → review → delete

**Traced path.**
- The VM `onCapturePhoto` → `dispatchPhotoShutter` → `fireShutterWithFeedback` → `engine.capturePhoto`.
- The engine checks `stillOutputAdmission`, then `currentAcceptedCameraSession()` (which gates on
  `cameraReady && !paused && controller && sessionGeneration`), then `formats.normalizedFor(accepted.outputs)`.
- Next come the drive branches (SINGLE, BURST, AEB, TIMELAPSE) → `dispatchStillCapture` →
  controller → `StillCapturePipeline` → `MediaStoreWriter` (REGISTERED → COMPLETE → publish).
- Callbacks `onMediaSaved`/`onRawSaved` → `recordCaptureOutput` → `CaptureOutputTracker.record`
  (synchronized, capture-id ordered) → review.
- Delete: `captureOutputs.beginDelete` (freeze + tombstone) → `engine.markCaptureDeleted` → dispatcher
  → `deleteUntrackedFamilySiblings` + `deleteKnownOutput` → survivor restore.

Ownership, tombstoning and survivor restore look consistent. BURST/AEB re-check
`acceptedSessionIsCurrent` on every link.

### T8. A running timelapse keeps the formats frozen at its start, while DNG/format toggles mid-run take effect on the route (Low-Medium, confirmed)
- **Where:** `CameraEngine.kt:4840` (`startTimelapse(formats)`) and `:4966`
  (`requestedFormats.normalizedFor(accepted.outputs)` per tick). `CameraViewModel.kt:2370`
  (`onSetPhotoFormats` only calls `cancelCountdown()`, and neither `stopTimelapse` nor the engine
  re-reads the formats).
- **Failure:** In a run started with HEIF only, turning DNG on moves the route to a standalone lens,
  so seamless zoom is lost, yet no DNG is ever written. The chip says DNG. In a run started with DNG,
  turning DNG off returns the route to logical, so ticks silently drop DNG. There is no status in
  either direction. HEIF↔JPEG changes are ignored for the rest of the run.
- **Fix:** Either stop the run on any format change (same idiom as a mode flip) or read the live
  formats per tick through an engine-held `@Volatile` selection.

---

## Flow 4: REC start → admission → first swap → stop/pause/background mid-admission

**Traced path.** The VM optimistically sets `isRecording && isRecordingStarting` (a generation token),
then calls `engine.startRecording`. After that:
- `RecordingAdmissionLatch.tryBeginAdmission` and the topology lease.
- The `recorderExecutor` runs `beginRecordingAllocation`, which takes a frozen
  `currentRecordingAdmissionSnapshot` (size, fps, codec, transfer, candidates, and an `isCurrent`
  that includes `!paused`), registers the family, and arms the process pre-native allocator with a
  deadline.
- A claimed row goes to `continueRecordingAfterAllocation`, then the standby mic claim, then
  `startRecordingClaimed` (≤400 ms release wait), then native setup, publication, and the first real
  encoder swap → `onRecordingStarted`.
- Stop is latched by `requestStop`/`completeAdmission`. Pause runs `retirePreNativeRecordingAllocation`
  plus the recorder claim, and the VM bumps `recordingAttemptGeneration`. Camera faults claim the
  recorder before `onRecordingTerminated`.

**No new defect found.** Every async edge I followed is generation-owned or latched. A candidate
race does exist: a stale `onRecordingTerminated` post landing after a user stop+restart. It needs
two taps inside one main-queue hop while `recorderTeardownInFlight` already refuses the restart, so
I rate it **Low / needs-manual-validation** and leave it out of the count.

---

## Flow 5: background/foreground during capture, recording and session config

**Traced path.**
- **onStop:** `MainActivity.onStop` releases any held key edges, then `vm.onStop` (clears progress,
  countdown, REC UI, tickers, `invalidateOpticsDerivedState`, tap focus; saves settings; disables the
  standby mic), then `engine.pause`. `engine.pause` sets `paused`, revokes the startup trace, retires
  the pre-native REC attempt, cancels cold-start retry, disables standby, invalidates Ready (which
  cancels DNG pre-allocations), stops timelapse, finalizes the recorder off main, stops the gyro, and
  closes the controller on `setupExecutor`.
- **onStart:** `vm.onStart` → `engine.resume`, which re-resolves from the current desired fields.

Setup tasks re-check `paused` after every Binder phase. Nothing new beyond T6.

---

## Flow 6: permissions (camera, mic, visual media)

**Traced path.**
- **Camera:** a `RequestMultiplePermissions` launch at first composition →
  `recordCameraPermissionResult` → `refreshPermissionState` (also run on `onResume`).
- **Mic:** `permissionAwareActions` → `requestMicrophoneThen` (rationale → launcher) → grant/decline.
  `declineMicrophone` sets `AUDIO_OFF_BY_DENIAL` and, for START_RECORDING, still records.
  `refreshPermissionState` → `audioRestoredByMicrophoneGrant`.
- **Visual media:** a contextual launch at an empty-gallery tap → `onGalleryAccessRequested`.

### T9. MR recall/restore can set `recordAudio` without touching `AUDIO_OFF_BY_DENIAL`, so a later grant overrides a recalled deliberate silence (Low, likely)
- **Where:** `CameraViewModel.kt:1504` (`recordAudio = e.recordAudio` in `applyLoaded`) and
  `MainActivity.kt:961-969`.
- **Why:** The denial-reason flag is cleared only through the Activity's
  `onToggleRecordAudio(false)` decorator. After a denial the flag is true. The operator then recalls
  an MR bank saved with audio deliberately OFF, so `recordAudio` stays false and the flag stays true.
  On a later grant, audio is forced back ON and a status line says so. That overrides the preset's
  choice.
- **Fix:** Clear `AUDIO_OFF_BY_DENIAL` whenever a recall/restore publishes `recordAudio` (VM callback
  → Activity), or move the flag into the VM next to `recordAudio`.

---

## Summary

Total: **9 findings**.

| # | Finding | Confidence | Status |
|---|---|---|---|
| T1 | `restoredOptics` drops the lens band for DNG Photo | High | confirmed |
| T2 | The persisted video resolution is the engine's fallback, not the operator's request | Medium | likely |
| T3 | The DNG toggle changes route and scale but never converts zoom or runs door hygiene | High | confirmed |
| T4 | The fast-path `forZoom` re-derivation ignores DNG (2 sites) | Medium | likely |
| T5 | `rawWanted` is missing from the optics rollback snapshot | Medium | likely |
| T6 | `setRawWanted` while paused leaves `overrideId` stale for `resume()` | Low | needs-manual-validation |
| T7 | The DNG toggle on FRONT runs a pointless full reopen | Low | confirmed |
| T8 | A timelapse run ignores mid-run format/DNG changes | Low-Medium | confirmed |
| T9 | Recall doesn't clear `AUDIO_OFF_BY_DENIAL` | Low | likely |

**Common root cause:** T1, T3, T4, T5 and T7 all come from one design gap. DNG is documented as a
ROUTE INPUT, but in code it is still handled as a format option. It has no zoom-scale conversion,
no rollback membership, no remap-door hygiene, and no route-aware lens-band derivation at the
remaining fast-path sites. Closing it once, by giving the DNG flip the same `beginOpticsTransaction`
packet shape as a TELE on/off (including a rewrite of `lensChoice` and `zoomRatio`), fixes all five.
All of these need on-device confirmation on the PMA110 before they are claimed fixed.
