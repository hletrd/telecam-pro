# Tracer review: RPL cycle 2 (2026-10-02)

Scope: causal tracing of the cycle-1 changes (`git log ba5b16e7..HEAD`) through every caller, plus
the standing flows: optics transactions/rollback, settings restore → engine, Ready publication,
capture → MediaStore → review, REC admission/stop latch, zoom coalescing/scale, and the DNG route
input. Read-only pass: nothing was built, run, or tried on a device. Owner decisions (ZSL dark
refusal, FocusDetail threshold, declined CameraUnit SDK, proprietary HDR) were not reopened.

Status legend: **confirmed** = path read end to end, and the failure follows directly from it.
**likely** = path read, but it needs one runtime condition (usually an optics rollback) that I
could not force on the host. **needs-manual-validation** = depends on device timing or provider
behavior.

## Cycle-1 changes traced and found sound

These were checked against every caller. No defect found:

- **`remapRouteScaleOptics` + `onSetPhotoFormats` door (99e7af87, 40145fbf).** Both directions
  round-trip with `unifiedZoomOf`/`localZoomOf` on PMA110's four optical presets. Ultrawide 0.6 maps
  to local 1.0, 20× maps to TELE10X local 2.0, and TELE10X local 10 clamps to 20× (the HAL range
  limit, not a bug). Glide, ease, trailing flush and quiet landing are invalidated after the engine
  push, on the same main turn. `s.controls` already holds every throttled control value, because
  `updateControls` writes `_state` synchronously, so `cancelPendingControls` loses nothing the
  packet does not carry.
- **`restoredOptics(photoStandalone)` (89bb7aab).** `applyLoaded` derives the restored scale from
  the restored `dngRaw`, which is the same packet that saved the zoom. `currentExtras` saves the
  pending formats together with the state zoom, and both are written together in `onSetPhotoFormats`.
- **`lensBandFollowsZoom` (AGG-3).** All three band re-derivation sites now ask one predicate, and
  it is evaluated inside the terminal mutation.
- **Front trip vs DNG.** `preFrontRearUnifiedZoom` is unified, and both the engine
  (`CameraEngine.kt:3907`) and the VM (`CameraViewModel.kt:2933`) convert it with the *current* DNG
  answer when leaving FRONT. A DNG choice made while FRONT therefore exits onto the correct scale.
  (`rawSelectable` disables the chip on FRONT anyway.)
- **Recall with a Photo DNG flip.** `setResolvedOptics` (T1) followed by `setRawWanted` (T2):
  T2 supersedes T1, both share the not-Ready baseline, and a T2 rollback restores route, scale and
  DNG together.
- **Token-scoped recorder worker door (4e57fff2).** `publishAdmission` (`CameraEngine.kt:~6378`)
  runs before the encoder EGL attach. That attach (`GlPipeline.kt:747`, owner-keyed) therefore sees
  an ACTIVE token with the same owner and is admitted. Only the audio worker and the muxer start run
  under the pending token, and they now use `runRecorderWorkerNative`. Preview, EGL and Camera2
  acquisitions refused during a pending setup go back to the pre-0ab5c1ba replay path. No deadlock.
- **Exposure handoffs (1e79810e).** `exposureModeHandoff` seeds from live values only when the
  outgoing P was HAL-AE, and `refreshProgramAppSide(seedFromLive = outgoingHalAe)` agrees with it.
  M→P, flash OFF↔AUTO, ISO+ANGLE and app-side-P→ANGLE all land on the intended still exposure.
- **Tri-state video validation (1edb68c6), recovery progress (a1fee383), prior-family tombstone
  (fc8a458c), lazy characteristics (1c5a0060), HEIF EXIF best-effort (d062927a), nativelog
  (d255afbc), bounded settings (8780b494; the Kelvin bounds match the ruler's 2000–10000).**
  - The media-page continuation is bounded. A failing Images query with an advancing Video cursor
    keeps going only until Video completes, then `nextCursor == cursor` stops it.
  - None of these changes is destructive.
- **Recall audio decorator (fe7d0578).** `applyLoaded` sets `activeMemorySlot` synchronously, so
  the post-call check sees an applied recall. Its only caller is the ProSheet.

---

## TR2-1. A rollback keeps a later direct DNG write even when that write flips the restored route (Medium, likely; regression from 3ec126e1)

**Traced path.**
1. `CameraViewModel.applyLoaded` (MR recall or settings restore, started) calls
   `engine.setResolvedOptics(enabledVideo = true, …)` at `CameraViewModel.kt:1429`. This opens
   transaction T1. Its baseline B is the last-Ready snapshot: Photo, logical,
   `rawWanted = false`, `rawWantedDirectWrites = n`.
2. `videoMode = true` is published inside T1's monitor (`CameraEngine.kt:2737`).
3. `engine.setRawWanted(safeFormats.dngRaw = true)` runs at `CameraViewModel.kt:1492`. In Video,
   `standaloneRouteWanted` is true either way, so `routeFlips == false`. The direct branch at
   `CameraEngine.kt:4005-4014` sets `rawWanted = true` and `rawWantedDirectWrites = n+1`.
4. T1 fails. Possible causes: `id == null` → `CAMERA_UNAVAILABLE_RECALL_UNCHANGED`
   (`:2795`), the `selectCurrentLens`/`cachedCaps` failure in `reconfigureCamera` (`:4145`, `:4159`),
   or the recorder branch.
5. `commitOpticsRollbackLocked` (`CameraEngine.kt:1011`) runs. The counter differs from B's, so
   `rawWanted` stays `true`. Mode, lens, controls and `overrideId` all go back to B (Photo,
   logical, unified zoom). `restoreSession` is true (B was Ready on the same controller), so the
   logical Photo session is retained as-is.
6. The publication carries `rawWanted = true` (`:1032`). The VM mirror (`CameraViewModel.kt:973`)
   sets `photoFormats.dngRaw = true`.

**Hypotheses considered.**
- *(a) The counter protects only a route-neutral later choice.* That is the intent of 3ec126e1
  ("a DNG toggle that did not move the route"). But "did not move the route" is judged in the mode
  current *at write time* (Video). The rollback then restores a *different* mode (Photo), where the
  same value does move the route. **Accepted.**
- *(b) The chip could not be tapped in Video while T1 is pending.* That is true for the interactive
  chip: `rawSelectable` uses the previous session's outputs. It does not matter for the recall path,
  because `applyLoaded` always calls `setRawWanted` right after `setResolvedOptics`, so no user
  timing is involved. **Rejected as a mitigation.**
- *(c) A later Ready would repair it.* It does not. Ready normalization deliberately keeps `dngRaw`
  (`OpticsConstraints.kt:68-69`). Bare reopens reuse `overrideId` = logical, and re-tapping DNG ON
  is refused by the `rawWanted == enabled` gate. **Rejected.**

**Failure.** This is the AGG-4 state that cycle 1 set out to remove. The UI chip reads DNG on, the
engine wants RAW, and the session is logical with `raw = false`.
- Every shutter writes HEIF/JPEG only and posts RAW_UNAVAILABLE.
- The VM reads the restored *unified* zoom through `unifiedZoomOf(standaloneRoute = true)`, so the
  OSD and focal readout show e.g. 3.0 × 3 = 9× / ~208 mm, the 2026-08-04 symptom.

The mirror case (baseline Photo with DNG on, a standalone TELE3X at local 1.0, recall of a Video
bank with `dngRaw = false`) is worse:
- The restored standalone session keeps RAW, but `rawWanted = false`.
- The lens-local 1.0 is read as unified, so `lensBandFollowsZoom` re-derives the band to MAIN and
  the rail shows 1× / 23 mm on the 70 mm lens.
- The next DNG ON then runs `remapRouteScaleOptics(toStandalone)` on that "unified" 1.0, which
  moves the session to the main lens.

Before 3ec126e1 this path restored `before.rawWanted`, which was consistent with the restored route.

**Fix.** In `commitOpticsRollbackLocked`, keep a later direct write only when it is route-neutral
*for the restored packet*:

```kotlin
val law = activeDeviceProfile().rawRequiresStandalone   // after activeCameraRoute is restored
val keepLater = rawWantedDirectWrites != before.rawWantedDirectWrites &&
    (restored.route != CameraRoute.BACK || restored.teleconverter ||
        standaloneRouteWanted(before.videoMode, rawWanted, law) ==
            standaloneRouteWanted(before.videoMode, before.rawWanted, law))
if (!keepLater) rawWanted = before.rawWanted
```

Publish the value actually kept, as the code does today. Also count a direct write only when it is
route-neutral in **every** mode, or fold `rawWanted` into the `setResolvedOptics` packet (the
AGG-49 note) so recall stops writing DNG outside its own transaction.

**Test gap.** No engine-level test covers `setRawWanted`'s transaction, the counter, or the rollback
(`OpticsTransitionPolicyTest` covers pure helpers only). Add a fake-engine rollback test:
recall Video + `dngRaw` flip → rollback → assert `rawWanted == before.rawWanted`.

---

## TR2-2. Toggling DNG with TELE on runs a full close/reopen of the same camera (Low, confirmed; T7 fix incomplete)

**Path.**
1. Photo with TC on: `onSetPhotoFormats` computes `fromStandalone != toStandalone`, because
   `standaloneRouteWanted` ignores TC.
2. `remapRouteScaleOptics` returns identity (the `teleconverter` early return), but `routeOptics` is
   non-null.
3. `setRawWanted` sees `routeFlips && started && BACK`, so it calls `beginOpticsTransaction` and
   then `reconfigureCamera(pin)` (`CameraEngine.kt:4043-4053`).

**Why it is pointless.** `selectCurrentLens` resolves standalone 4 either way. The RAW reader does
not depend on `rawWanted` at all: `sessionAttemptPlan` (`CameraController.kt:2665`) keys only on
`supportsRaw`/`standalone`, and `rawWanted` appears in the engine only in route resolution.

**Failure.** Each DNG tap at 300 mm costs:
- a Not-Ready dip, a full HAL close/open of camera 4, and loss of the 0x80b4 TC session in between;
- tap-AF retirement (`retireTapFocusLocked`) on both sides;
- a VM `cancelPendingControls`, which drops a ruler value still inside its 40 ms throttle window.

The route does not change. 3ec126e1 and 99e7af87 exempted FRONT/EXTERNAL for exactly this reason
but missed TC. This predates cycle 1.

**Fix.** In `setRawWanted`, treat `teleconverterMode` like a non-BACK route and do a direct write
(counted, since it is route-neutral while TC holds). Likewise, have `onSetPhotoFormats` compute
`routeOptics = null` when `s.teleconverterMode`, so it skips `cancelPendingControls`/`clearTapFocusUi`.

---

## TR2-3. The rollback mirror leaves `pendingPhotoFormatsUntilInventory` stale; the inventory then re-applies the rolled-back DNG without the zoom remap (Low, likely)

**Path.**
1. Before the encoder inventory lands, `onSetPhotoFormats` sets
   `pendingPhotoFormatsUntilInventory = formats` (`CameraViewModel.kt:2428`). Say `dngRaw = true`,
   remapped, in transaction T.
2. T rolls back. The engine restores `rawWanted = false` and the mirror sets `photoFormats.dngRaw =
   false` at `:973`, with unified controls. The pending field is not touched.
3. `applyEncoderInventory` (`:2689-2715`) reads `requestedFormats = pending` (dngRaw = true) and
   calls `engine.setRawWanted(true)` **with no resolved optics**. `routeFlips` is true, so it opens a
   transaction carrying the *unified* ratio onto a standalone lens. That is AGG-1 again: unified 3.0
   becomes a 3× crop on 70 mm, 9× / ~208 mm.
4. Separately, `currentExtras` (`:1582`) persists the pending `dngRaw = true` next to the
   rolled-back unified zoom. The next launch's `restoredOptics(photoStandalone = true)` then reads
   that unified ratio as lens-local.

**Precondition.** A DNG transaction that rolls back inside the async `EncoderCaps.load()` window at
cold start. The window is narrow, but nothing else closes it.

**Fix.** In the rollback mirror, also rewrite
`pendingPhotoFormatsUntilInventory = pending?.copy(dngRaw = rollback.rawWanted)`. Also make
`applyEncoderInventory` go through the same remap door when the DNG answer differs from the
engine's (or have `setRawWanted` refuse a route flip without a resolved packet when started).

---

## TR2-4. `setVideoResolution` writes `requestedVideoSize` outside any transaction, so an older rollback reverts it; b476d1dd now persists that revert (Low, likely; same class as 3ec126e1)

**Path.**
1. In Photo, `onVideoResolution` → `engine.setVideoResolution` (`CameraEngine.kt:3692-3704`) sets
   `requestedVideoSize = s` directly. There is no reopen in Photo and no counter.
2. An older in-flight lens/TC transaction rolls back. `commitOpticsRollbackLocked` restores
   `requestedVideoSize = restored.requestedVideoSize` (`:1008`) and publishes it.
3. The new VM mirror `requestedVideoResolution = rollback.requestedVideoSize`
   (`CameraViewModel.kt:951`) overwrites the operator's pick, and `currentExtras` persists the
   reverted request.

**Failure.** A resolution picked while a lens switch is still in flight is silently lost if that
switch fails. Before b476d1dd the engine reverted it too, so this is not new loss. The difference
now is that the persisted request mirrors exactly the rollback. Rare: it needs a failing optics
transaction plus a menu pick inside its window.

**Fix.** Give `requestedVideoSize` the same direct-write treatment as DNG: a counter in the
snapshot, and restore only when no direct write happened. Or make `setVideoResolution` (Photo
branch) begin its own transaction, as recall already does.

---

## TR2-5. New reserved-budget warnings are per event and not change-gated, so a persistent storage fault drains the 120-row lifetime budget (Low, needs-manual-validation)

**Path.** f41b1ae3 and d062927a added unconditional `DiagnosticLog.w` rows at:
- `MediaStoreWriter.openParcelFd`/`openOutputStream` (`MediaStoreWriter.kt:1021-1029`);
- publish exhaustion (`:1080`);
- `StillCapturePipeline.writeProcessedHeif`'s EXIF payload failure (`StillCapturePipeline.kt:255`),
  which logs on *every* shot, not "once" as the commit message says;
- the still snapshot/encode failures in `CameraEngine`.

All of them spend the process-lifetime `RESERVED_DIAGNOSTIC_ROW_BUDGET = 120`
(`DiagnosticTelemetry.kt:32`). The lifetime cap itself is deferred AGG-27.

**Failure.** Some conditions persist across shots: a full cache dir (EXIF seed temp file), or a
provider that refuses opens. Under those, a timelapse at 1 s or a couple of bursts spends the whole
budget in about two minutes. After that, every camera-fault, recorder and storage warning in the
process is dropped, which is the starvation that 98164b9e fixed for identity reads one commit
earlier. VideoRecorder's "no output descriptor" row also double-counts with `openParcelFd`'s own row.

**Fix.** Route these through the same per-(site, reason) change gate as `PendingDiscardJournal`'s
identity warnings, or gate them once per process per site with a counter that appears in the next
emitted row.

---

## Hypotheses checked and rejected

- **VM/engine `rawForcesStandalone` divergence.** The VM mirrors a route-dependent value
  (`activeDeviceProfile()` turns GENERIC on EXTERNAL) that is republished only on inventory
  publications. On PMA110, EXTERNAL needs `!back`, which never happens. On GENERIC devices both
  values are false. Not reachable today.
- **The token door starving encoder EGL.** Rejected; see the sound list above (publication precedes
  the attach).
- **The paused `setRawWanted` branch is not counted as a direct write.** Rejected: the rollback then
  restores `rawWanted` *and* `overrideId` together, which is route-consistent. Its only caller today
  is the no-op inventory re-push.
- **The recovery continuation could loop forever.** Rejected: it advances only on a cursor change,
  and the cursor is finite.

## Summary

| # | Finding | Severity | Status |
|---|---|---|---|
| TR2-1 | Rollback keeps a later direct DNG write that flips the restored route (recall of a Video bank with a different DNG) | Medium | likely (regression from 3ec126e1) |
| TR2-2 | A DNG toggle with TELE on runs a pointless full reopen of camera 4 | Low | confirmed (pre-existing; T7 fix incomplete) |
| TR2-3 | The rollback mirror leaves pending inventory formats stale; inventory re-applies DNG without remap and persists a scale mismatch | Low | likely |
| TR2-4 | `requestedVideoSize` direct write is reverted by an older rollback, and the revert is now persisted | Low | likely |
| TR2-5 | New reserved warnings are not change-gated and can drain the 120-row lifetime budget | Low | needs-manual-validation |

Common thread: TR2-1, TR2-3 and TR2-4 are all "a field written outside the optics transaction, then
reconciled by a rollback." The structural fix is AGG-49: every route-relevant input (DNG,
recording size) either rides its own transaction or is part of the recall packet. All camera-path
items need PMA110 confirmation with an injected recall failure.
