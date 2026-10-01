# Code-reviewer — RPL cycle 2 (2026-10-02, HEAD e5729ffd)

Lane: code quality, logic bugs, SOLID, maintainability. Read-only. Scope: all `app/src/main/**/*.kt`
(105 files / 60k lines; split across four parallel read passes — VM/UI/settings, CameraEngine,
controller/capture/GL, storage/video/recovery — then every finding below re-traced by hand at the
cited lines), plus the cycle-1 diff `ba5b16e7..HEAD` (20 main files, +850/-168).

Owner decisions NOT re-raised: ZSL dark refusal, FocusDetail threshold, CameraUnit SDK, proprietary
HDR, orientation-moves-no-control. Nothing here is device-verified; camera/GL items are PENDING
DEVICE per CLAUDE.md.

## Cycle-1 fix verification

Checked correct: `remapRouteScaleOptics` + its `onSetPhotoFormats` wiring (both directions; no-op on
TC / lens-local route / unchanged answer); `restoredOptics(photoStandalone)` + the single
`restoredRouteStandalone` in `applyLoaded`; `lensBandFollowsZoom` at all three band sites
(`CameraEngine.kt:589, :2684, :2837`); `exposureModeHandoff`/`withShutterModeTakingOwnership`;
`onAspectRatio` rejectIfRecording; rollback DNG chip mirror; `currentExtras` pending codec/transfer;
lazy `rawChars` retry in `tryComplete`; HEIF EXIF best-effort; tri-state open-failure retention;
recovery progress past an exhausted page; prior-family tombstone; change-gated identity warnings;
drain idiom / `wroteAudioSample`; token-scoped recorder worker door; `nativelog` no longer arming
the still-less session.

Incomplete or regressed: AGG-36 (CR2-4), AGG-4 / 3ec126e1 rollback rule (CR2-5), AGG-4 recall split
(CR2-6), AGG-10 amplifies a latent loop clamp (CR2-7), AGG-10 not applied to three dial doors
(CR2-8), AGG-34 not applied to the format door (CR2-9), AGG-16 only half-closed (CR2-10), AGG-19
lazy retry not applied to metering (CR2-14), AGG-17 continuation drains the reserved log budget
(CR2-16).

## Findings

### CR2-1 — A rejected DNG pre-allocation runs the timelapse / AEB continuation TWICE (High / High, confirmed)

- Where: `camera/CameraEngine.kt:4822` (`return owner.start() == ACCEPTED`) with
  `camera/DngPreCaptureAllocation.kt:120-121, :159-161` and the callers at
  `CameraEngine.kt:5045-5059` (timelapse) and `:4967-4975`, `:4996-5004` (AEB).
- Why: when `ProcessPreNativeMediaAllocator::dispatch` returns OVERFLOW/SHUTDOWN (or the deadline
  `arm()` fails, which calls `onTimeout` → `attempt.retire`), `attempt.retire { onFailure(null) }`
  runs synchronously → `onRetired` → `settleBeforeCamera()` → `settleRegisteredStillShot(...)` →
  `onDone?.invoke()`. `dispatchStillCapture` then ALSO returns `false`, and every chain caller treats
  `false` as "onDone will never run".
- Failure scenario (timelapse, DNG on): the shared 2-worker / 4-slot pre-native allocator is full
  (a MediaProvider stall with recording/DNG allocations in flight). Each tick: `onDone` schedules
  tick N+1, then `if (!dispatched) schedule(...)` schedules it again. Only the newest future is kept
  in `timelapseFuture`, so `stopTimelapse` cancels one of them; the others survive (their
  `timelapseRun.owns(generation)` check stops them only after stop). While the run is live the
  scheduled-task count doubles per interval (2^n), each tick re-registering a capture family and
  re-hitting the saturated allocator. AEB: `onDone = { fire(i + 1) }` advances the bracket inside the
  dispatch, then the outer `if (!dispatched) ctrl.updateControls(controls)` resets the preview to the
  base controls in the middle of the next bracket step.
- Fix: make the contract single-terminal. Either `dispatchStillCapture` returns `true` once
  `settleRegisteredStillShot` has run (onDone owns the continuation), or the pre-camera settle path
  suppresses `onDone` when the dispatch is rejected synchronously (pass a flag into
  `settleBeforeCamera`). Add a test with a fake allocator returning OVERFLOW asserting exactly one
  continuation per tick.

### CR2-2 — A preflight failure inside `reconfigureCamera` leaves a streaming camera permanently Not-Ready (High / High in code, needs-manual-validation for frequency)

- Where: `camera/CameraEngine.kt:4108` (`invalidateCameraReady()` bumps `cameraSessionGeneration`,
  `:541-557`) followed by the post-invalidate rollback branches at `:4136-4146`
  (`selectCurrentLens() == null`) and `:4148-4160` (`cachedCaps(...) == null`); rollback gate at
  `:1035-1036` (`restoreSession` requires `before.sessionGeneration == cameraSessionGeneration.get()`).
- Why: these branches run with the OLD controller still open and streaming (selection/caps are read
  "BEFORE closing"), but the session generation has already moved, so `commitOpticsRollbackLocked`
  always takes the Not-Ready `else` branch (`:1056-1066`): `acceptedCameraSession = null`,
  `cameraReady = false`. Nothing re-commits Ready afterwards — the old controller's `onReady` already
  fired, and `handlePreviewReady` needs an accepted session. The pre-invalidate GL branch (`:4093-4106`)
  restores correctly; only the post-invalidate ones are broken.
- Failure scenario: tap a lens / toggle DNG / change aspect while `getCameraCharacteristics` hits a
  transient failure (the same resume race CLAUDE.md documents for `openCamera`). Status says
  "camera unchanged", the preview keeps running, but shutter and REC stay disabled until the
  operator happens to trigger another optics change or a pause/resume. The cycle-1 AGG-4 rollback
  of `setRawWanted` inherits this.
- Fix: move `invalidateCameraReady()` after the selection/caps preflight (just before the close), or
  record the generation `invalidateCameraReady` produced and let the rollback restore Ready when the
  current generation equals it and `controller === before.readyController` (camera-error
  invalidations bump again and stay non-restorable). Add a test with a null `selectCurrentLens`.

### CR2-3 — Saving / storing an MR bank while FRONT with a retained TELE converts TELE zoom with the wrong base (Medium-High / High, confirmed)

- Where: `ui/CameraViewModel.kt:2789-2800` (`retainedRearZoomRatio`), snapshot at `:2902-2911`,
  used at `:1650-1652` (settings save) and by `onStoreMemorySlot`.
- Why: the snapshot is `unifiedZoomOf(TELE3X, z, standalone=true)` = `3·z` (base from the LENS).
  The save converts back with `localZoomOf(3z, optical)` whose base is `opticalBaseFor(3z)` — the
  10× lens once `3z >= 10`. The pair round-trips only while `z < 3.33`.
- Failure scenario (PMA110, Hasselblad kit): TELE at lens-local 4.0 (~52× total) → flip to FRONT →
  background (or store M1). Persisted: `teleconverter=true`, zoom `12/10 = 1.2`. Relaunch / recall
  lands at ~16× instead of ~52×. Same mismatch for any standalone (VIDEO / DNG) 3× lens zoomed past
  3.33×.
- Fix: when the target is TELE, divide by the host lens base
  (`opticalBaseFor(TELE3X.zoomPreset, optical).zoomPreset`), or snapshot the lens + lens-local ratio
  at FRONT entry and persist those verbatim. Add a round-trip test for TELE local 4.0.

### CR2-4 — AGG-36 fix still persists the DELIVERED size for an operator who never picked one (Medium / High, confirmed)

- Where: `ui/CameraViewModel.kt:1623`
  (`(requestedVideoResolution ?: s.videoResolution)`), contract at `storage/SettingsStore.kt:87-90`
  (`"" = never chosen -> auto-pick the largest`).
- Why: `requestedVideoResolution` is null until the operator picks, and the fallback writes the
  engine's delivered size — exactly the value AGG-36 said must not be persisted. `""` can no longer be
  written by any path.
- Failure scenario: never-picked user turns Open Gate on (delivered 2560×1920) or records on a route
  whose top size is 1080p, then backgrounds. Next launch `applyLoaded` (`:1446`) makes that the
  REQUEST and the engine honours it on the main lens: the user is pinned to 1080p / 2560×1920 with no
  action of their own, and every MR bank inherits it.
- Fix: persist `requestedVideoResolution?.let { "${it.width}x${it.height}" } ?: ""`.

### CR2-5 — The 3ec126e1 rollback rule keeps a DNG write that changes the meaning of the RESTORED route (Medium / Medium, confirmed logic)

- Where: `camera/CameraEngine.kt:1011` (`if (rawWantedDirectWrites == before.rawWantedDirectWrites)
  rawWanted = before.rawWanted`) with the direct-write branch at `:4006-4015`.
- Why: a write is classed "direct" when it does not flip the route in the mode current AT WRITE TIME
  (VIDEO, FRONT/EXTERNAL, pre-start). A rollback can restore a different mode/route in which the kept
  value DOES flip the route.
- Failure scenario: Photo, DNG off, logical camera. Recall an MR bank that is Video + DNG on:
  `setResolvedOptics(video)` opens T; `applyLoaded` then calls `setRawWanted(true)` with
  `videoMode == true` → direct write, counter++. T fails (e.g. `id == null` at `:2794`). Rollback
  restores Photo + logical `overrideId` + the Ready logical session but keeps `rawWanted = true` and
  publishes it to the VM (chip on). Every shot drops DNG with RAW_UNAVAILABLE, and
  `lensBandFollowsZoom`/`unifiedZoom` now read the unified wire value as lens-local — the
  "permanent divergence" shape of CLAUDE.md DNG bug #2 (it only clears when the operator toggles DNG
  off and on).
- Fix: keep the later value only if
  `standaloneRouteWanted(restoredVideo, rawWanted, law) == standaloneRouteWanted(restoredVideo,
  before.rawWanted, law)` for the restored BACK route; otherwise restore `before.rawWanted` (or
  publish Not-Ready with `overrideId = userPin` so the route re-resolves).

### CR2-6 — MR/settings recall of a DNG photo bank can re-band the lens-local zoom before the DNG transaction supersedes it (Medium / Low-Medium, needs-manual-validation)

- Where: `ui/CameraViewModel.kt:1492` (`engine.setRawWanted(safeFormats.dngRaw)` AFTER
  `setResolvedOptics`), `camera/CameraEngine.kt:2785-2845` (fast-path terminal mutation runs on
  `setupExecutor`, `lensBandFollowsZoom` reads the still-old `rawWanted = false`).
- Why: `restoredOptics(photoStandalone = true)` hands the engine a lens-local ratio (e.g. TELE3X
  @ 1.0). `setResolvedOptics` publishes it while the engine still believes the route is logical; if
  `setupExecutor` reaches the same-camera fast path before main reaches `setRawWanted`, the terminal
  mutation sets `lensChoice = LensChoice.forZoom(1.0) = MAIN`. `setRawWanted(true)` then opens its
  transaction WITHOUT a resolved lens and keeps `MAIN`, reopening the standalone MAIN lens at 1.0
  instead of the 70 mm lens. The cycle-1 plan logged the split as structural (AGG-49); this is its
  concrete failure.
- Fix: carry `rawWanted` inside `setResolvedOptics`'s packet (publish it in the same
  `beginOpticsTransaction` block and let `resolveNonTeleId`/the fast-path predicate see it), and drop
  the trailing `setRawWanted` from `applyLoaded`.

### CR2-7 — Entering app-side P from a long manual exposure cuts it to 1/10 s with no ISO compensation (Medium / High, confirmed math)

- Where: `camera/AutoExposure.kt:125-152` (`newNs.coerceIn(expMinNs, slowCapNs)` after ISO was
  computed for only a ±0.35-stop shutter move), seeded by `refreshProgramAppSide(seedFromLive =
  false)` (`ui/CameraViewModel.kt:2032`).
- Why: when `currentNs > slowCapNs` (100 ms), the final clamp silently removes
  `log2(currentNs/slowCapNs) − 0.35` stops. Before AGG-10 the seed was the live traded preview
  (≤ 1/15 s), so this was rarely hit; the AGG-10 fix now seeds the program line from the M exposure
  itself.
- Failure scenario: Photo M at 4 s / ISO 400 → switch to P. Tick 1: 3.1 s → clamped 0.1 s (−5 stops)
  while ISO rises ~0.35 stop + correction → preview and the next shot ~4.6 stops dark; recovery takes
  several ticks.
- Fix: clamp `currentNs` into `[expMinNs, slowCapNs]` first and add `log2(currentNs / clamped)` to
  `isoStops` (overflow then flows through the existing ISO-max branch). Unit test: 4 s seed keeps
  brightness within one tick.

### CR2-8 — ISO / shutter / angle dial doors leave HAL-AE PROGRAM without the live seed (Medium / Medium, likely)

- Where: `ui/CameraViewModel.kt:2007-2020` (`onIso`, `onShutterNs`), `:2050-2056`
  (`onShutterAngle`).
- Why: cycle 1 made `onExposureMode` and `onShutterMode(ANGLE)` seed from `liveIso/liveExposureNs`
  when the outgoing exposure was HAL-AE (video P, flash-metered photo P). These three doors also
  escalate PROGRAM → MANUAL but copy only the dialled value, keeping the other axis stale.
- Failure scenario: Video P, HAL settled at ISO 1600 / 1/30 s; drag the ISO ruler to 1600 → MANUAL
  with `exposureTimeNs` still at its stale stored value (e.g. 1/125 s) → ~2 stops darker. Angle dial:
  stale ISO.
- Fix: when `it.autoExposure`, start from `it.exposureModeHandoff(MANUAL, live.liveIso,
  live.liveExposureNs)` and then apply the dialled field; reuse one helper for all four doors.

### CR2-9 — A format tap before the encoder inventory lands permanently converts HEIF to JPEG (Low-Medium / High, confirmed)

- Where: `ui/CameraViewModel.kt:2427-2428`; `heifAvailable` defaults false (`camera/CameraState.kt:1649`).
- Why: `normalizedForEncoder(false)` promotes HEIF→JPEG BEFORE the value is stored in
  `pendingPhotoFormatsUntilInventory`. `onTransfer` (`:2408`) correctly stores the raw request; the
  format door stores the degraded one. The chip also builds the new set from the already-degraded
  state.
- Failure scenario: cold launch with HEIF+DNG saved; toggle DNG off before `EncoderCaps.load`
  finishes → pending = JPEG → `applyEncoderInventory` adopts it and the AGG-34 `currentExtras` path
  persists it. HEIF is gone for good.
- Fix: while `!encoderInventoryLoaded`, apply the toggled axis to the pending request (not to the
  degraded state) and store it un-normalized, mirroring `onTransfer`.

### CR2-10 — Live video validation still deletes a take on a parse throw that launch recovery would keep (Low-Medium / Medium, likely)

- Where: `storage/MediaStoreWriter.kt:2590-2596` (`catch → onParseFailure; INVALID`), consumed by the
  stop tail at `video/VideoRecorder.kt:1972-1978`; recovery uses `pendingProbeOutcome` (`:2255`)
  which maps any throw to INDETERMINATE.
- Why: AGG-16 was closed only for provider OPEN failure. A transient `IOException` from
  `MediaExtractor.setDataSource` on a successfully opened FUSE fd (e.g. during a media scan) is
  classified INVALID → FAILED → delete, while the same bytes after a kill would be retained and
  probed by recovery. The live path is the stricter one in the destructive direction.
- Fix: return INDETERMINATE for `IOException`/`SecurityException`; keep INVALID only for "parsed,
  no `video/` track" (and optionally `IllegalArgumentException` = unrecognized container).

### CR2-11 — DNG toggle with TELE on does a needless full reopen (Low / High, confirmed)

- Where: `camera/CameraEngine.kt:3997-3999, :4006` (`routeFlips` ignores `teleconverterMode`);
  RAW reader plan at `camera/CameraController.kt:2735` is route-based, not `rawWanted`-based.
- Why: with TELE on, Photo is already on standalone 3×; the VM remap already treats it as no change.
  The engine still closes/reopens the same id + TC session → black dip per toggle.
- Fix: `routeFlips = !teleconverterMode && standaloneRouteWanted(...) != standaloneRouteWanted(...)`.

### CR2-12 — `onCameraOverride` publishes the override even when the engine refuses mid-REC (Low / High, confirmed)

- Where: `ui/CameraViewModel.kt:3420-3429`; engine refusal with status only at
  `camera/CameraEngine.kt:4060`.
- Why/scenario: during REC tap "Reset" on the Camera ID row: VM sets `cameraOverrideId = null`,
  invalidates zoom/tap state; the engine pin stays. The row disappears while the pin is live.
- Fix: `if (rejectIfRecording()) return` at the top (or have the engine return Boolean).

### CR2-13 — ZSL ring is not flushed when `setPinAutoFps` turns streaming off (Low / Medium, likely)

- Where: `camera/CameraController.kt:1870-1895`; contrast `setZslServePossible` (`:1496-1497`).
- Why/scenario: FRONT Photo→Video keeps the controller; `zslStreamingActive()` turns false but up to
  three full-res YUV images (~3×19 MB) stay acquired for the whole video session, leaving the in-REC
  snapshot reader with fewer free buffers.
- Fix: capture `zslStreamingActive()` before updating `pinAutoFps` and `zslRingFlush()` on a
  true→false edge.

### CR2-14 — Metering regions silently dropped while `rawChars` is null (Low / Medium, confirmed)

- Where: `camera/CameraController.kt:1308` (`rawChars?.get(...) ?: return`).
- Why: the cycle-1 lazy re-read (AGG-19) runs only in `tryComplete`. After a failed open-time read
  every tap-AF/AE request ships without regions while `setMeteringPoint` reports ACCEPTED.
- Fix: `(rawChars ?: readRawCharacteristics()?.also { rawChars = it })` here too (the once-per-
  controller log guard keeps it quota-safe).

### CR2-15 — Pseudo-ZSL admission ignores manual focus distance and white balance (Medium / Medium, likely)

- Where: `camera/ZslAdmission.kt:56-101`, used at `camera/CameraController.kt:1596-1631`.
- Why: admission matches exposure/ISO/zoom/flash/age/gesture only; ring frames may be 400 ms old.
  This is NOT the owner-decided dark refusal (exposure tolerance stays as is) — it is a missing axis.
- Failure scenario: logical photo route, bright light, MF ruler drag (or Kelvin drag) then shutter
  ~200 ms later → a frame at the previous lens position / WB gains is served as "the requested
  still"; at long focal lengths a visibly missed focus.
- Fix: in MANUAL focus compare the frame result's `LENS_FOCUS_DISTANCE` (tight epsilon) and refuse on
  a WB mode/Kelvin change (or stamp frames with a controls generation and require equality).

### CR2-16 — Launch recovery can spend the reserved 120-row warning budget (Low-Medium / Medium, likely)

- Where: `storage/MediaStoreWriter.kt:1080` (per-row publish-exhaustion warning, not change-gated),
  `:1414` (recovery now continues across pages, AGG-17), `storage/PendingDiscardJournal.kt` ~`:578`
  (identity gate per URI).
- Why/scenario: ~40 rows whose `IS_PENDING=0` update keeps throwing × up to 3 page attempts, or ~120
  DISCARD URIs on an unmounted card → one cold start drains the reserved owner; every later camera
  fault / save failure warning in that process is dropped (the AGG-26 class through a new door).
- Fix: log once per recovery run with a count, or gate the exhaustion row per (URI, failure class).

### CR2-17 — A persistently failing collection query still starves the DISCARD stage (Low / Medium, confirmed)

- Where: `storage/MediaStoreWriter.kt:1414` (`continueAfterFailureExhaustion = nextCursor != cursor`),
  `camera/LaunchMediaRecoveryCoordinator.kt:263-268`.
- Why: if the Images or Video query itself throws, the cursor never advances, recovery returns
  EXHAUSTED, and DISCARD never runs — on every launch while the query fails.
- Fix: after retries on a non-advancing page, mark that collection complete for this run and proceed
  to DISCARD (the next launch retries the query anyway).

### CR2-18 — Rollback overwrites a resolution picked while the reopen was in flight (Low / Low, needs-manual-validation)

- Where: `ui/CameraViewModel.kt:951` mirrors the unconditional engine restore
  `camera/CameraEngine.kt:1007-1008`.
- Why/scenario: Video, tap the 10× lens; while reconfiguring pick 1080p (only REC blocks
  `onVideoResolution`); the reopen fails → engine and `requestedVideoResolution` return to 4K, the
  operator's newer pick is lost. Same "older transaction wins" shape 3ec126e1 fixed for DNG.
- Fix: a direct-write counter for the size request (as for `rawWanted`), or refuse
  `onVideoResolution` while an optics transaction is pending.

### CR2-19 — YUV still size chosen by area, without the aspect rule the JPEG path got (Low / Low, needs-manual-validation)

- Where: `camera/CaptureCapabilities.kt:341-345` vs `pickStillSize` (`:653-670`).
- Why: the TB331FC square-JPEG fix prefers the native aspect; the YUV lane (FRONT rungs 0-1, logical
  rear) still takes the largest area, so a device advertising a larger off-aspect YUV size gets
  off-aspect stills.
- Fix: route YUV candidates through `pickStillSize(yuvCandidates, arrayW, arrayH)`.

### CR2-20 — Loupe framing hint and draw clamp the zoom compensation differently (Low / High, cosmetic)

- Where: `gl/GlPipeline.kt:1134-1135` (`coerceAtLeast(0.01f)`) vs `:1050` / `FlipRenderer.kt:369`
  (`coerceAtLeast(1f)`).
- Fix: apply `coerceAtLeast(1f)` to the hint ratio so both derive from the same value.

## Final sweep (commonly missed)

- `SettingsStore` bounds (AGG-33) skipped `photoExposureTimeNs` (only `coerceAtLeast(1L)`) and
  `wbTint`; low impact.
- `setRawWanted`'s FRONT direct-write branch writes VM controls into the engine; unreachable from UI
  today (`rawSelectable` disables the chip on FRONT) — keep it that way or skip the controls write.
- A rollback leaves `pendingPhotoFormatsUntilInventory` untouched, so a pre-inventory DNG rollback is
  re-applied by `applyEncoderInventory` via `setRawWanted(true)` without a scale remap (pairs with
  CR2-9).
- `DriveMode.SINGLE` in `capturePhoto`: on `dispatched.isFailure` the caller releases
  `snapshotLease` that `dispatchStillCapture` may already have released on an internal early path —
  verify `Lease.release()` is idempotent (it appears to be; not reported).

## Summary

| ID | Sev / Conf | One line | Where |
|---|---|---|---|
| CR2-1 | High / High | Rejected DNG pre-allocation runs timelapse/AEB continuation twice | `CameraEngine.kt:4822, :5045-5059` |
| CR2-2 | High / High | Post-invalidate preflight failure in reconfigure → camera Not-Ready forever | `CameraEngine.kt:4108, :4136-4160, :1035` |
| CR2-3 | Med-High / High | FRONT-retained TELE zoom saved with wrong base (4.0 → 1.2) | `CameraViewModel.kt:2789-2800` |
| CR2-4 | Medium / High | AGG-36 fix still persists delivered video size for never-picked users | `CameraViewModel.kt:1623` |
| CR2-5 | Medium / Medium | Rollback keeps a "direct" DNG write that flips the restored route | `CameraEngine.kt:1011, :4006` |
| CR2-6 | Medium / Low-Med | DNG bank recall: fast path re-bands lens-local zoom before setRawWanted | `CameraViewModel.kt:1492`, `CameraEngine.kt:2837` |
| CR2-7 | Medium / High | App-side P seeded from long M exposure clamps −5 stops uncompensated | `AutoExposure.kt:125-152` |
| CR2-8 | Medium / Medium | ISO/shutter/angle dials leave HAL-AE P with stale other axis | `CameraViewModel.kt:2007-2020, :2050` |
| CR2-9 | Low-Med / High | Pre-inventory format tap persists HEIF→JPEG | `CameraViewModel.kt:2427-2428` |
| CR2-10 | Low-Med / Medium | Live video parse throw → delete; recovery would retain | `MediaStoreWriter.kt:2590-2596` |
| CR2-11 | Low / High | DNG toggle in TELE reopens the same camera | `CameraEngine.kt:3997-4006` |
| CR2-12 | Low / High | onCameraOverride updates UI when engine refuses mid-REC | `CameraViewModel.kt:3420-3429` |
| CR2-13 | Low / Medium | ZSL ring not flushed on setPinAutoFps streaming-off edge | `CameraController.kt:1870-1895` |
| CR2-14 | Low / Medium | Metering regions dropped while rawChars null | `CameraController.kt:1308` |
| CR2-15 | Medium / Medium | ZSL admission ignores MF distance and WB | `ZslAdmission.kt:56-101` |
| CR2-16 | Low-Med / Medium | Recovery per-row warnings drain reserved log budget | `MediaStoreWriter.kt:1080, :1414` |
| CR2-17 | Low / Medium | Failing collection query starves DISCARD stage | `MediaStoreWriter.kt:1414` |
| CR2-18 | Low / Low | Rollback overwrites an in-flight resolution pick | `CameraViewModel.kt:951` |
| CR2-19 | Low / Low | YUV still size by area, no aspect rule | `CaptureCapabilities.kt:341-345` |
| CR2-20 | Low / High | Loupe hint clamp ≠ draw clamp | `GlPipeline.kt:1134` |

Total: 20 findings (2 High, 7 Medium/Med-High, 11 Low/Low-Med).
