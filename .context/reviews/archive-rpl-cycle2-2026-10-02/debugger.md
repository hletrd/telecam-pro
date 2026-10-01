# Debugger review, RPL cycle 2: latent failure modes, swallowed errors, regressions

Reviewer: debugger lane (read-only). Date: 2026-10-02. HEAD `e5729ffd`. The cycle-1 range checked was `ba5b16e7..HEAD` (49 commits, 20 main-source files, +850/-168).

Scope and method:
1. I read the whole cycle-1 diff under `app/src/main` hunk by hunk and traced every changed seam into its callers:
   - DNG door and rollback: `CameraEngine.setRawWanted` and `rollbackOptics`, plus the ViewModel's `onSetPhotoFormats`, `applyLoaded`, `applyEncoderInventory` and rollback consumer.
   - Recorder token gate (`runRecorderWorkerNative`, `recorderHoldsAdmissionAgainstLocked`).
   - Tri-state finalized-video validation.
   - Launch-recovery continuation.
   - `CaptureOutputTracker` prior-family deletes.
   - Exposure handoffs, the settings bounds, the identity-warning gate and the lazy `rawChars` read.
2. I re-validated the deferred AGG-29/30/31/32 against the current code.
3. I swept the main source for `catch (_: Throwable)`, `!!`, executor lifecycles, the countdown, timelapse, BURST and AEB chains, hardware-key ownership and the review-player setup.

Settled owner decisions are not reported: the dark ZSL refusal, the FocusDetail threshold, the declined CameraUnit SDK, proprietary HDR formats, fail-closed identity and the log-quota policy.

Summary: **6 new findings** (1 Medium, 5 Low) and 4 re-validated deferred items, all still open. Everything here comes from static analysis, and nothing is device-verified.

## Regression verdict for the cycle-1 commits

I found no regression that breaks a previously working path. Individual verdicts:
- **`4e57fff2` (token-scoped recorder workers):** sound. A pending token now refuses the setup Engine's own GL and Camera2 acquisitions too. That is safe:
  - The encoder EGL attach (`GlPipeline.kt:747`) runs only after `publishAdmission` (`CameraEngine.kt:6379` precedes `setEncoderOutput` at `:6474`).
  - A refused preview attach is routed to `convergeRetainedSurfaceAfterNativeRefusal`, which waits for the replay (`CameraEngine.kt:7079-7106`).
  - The engine-side `nativeAcquisitionMayProceed` already parked on `setupPending`.
- **`1edb68c6` (tri-state validation):** an INDETERMINATE answer now fails closed correctly, and nothing can publish or delete through it (`VideoRecorder.kt:1968-1991`). The new parse-failure edge is DBG2-4.
- **`a1fee383` (launch recovery):** `continueAfterFailureExhaustion = nextCursor != cursor` is bounded. A failing collection query that never advances stops the loop once no sibling collection advances.
- **`fc8a458c` (prior-family delete):** correct. Survivors are removed from `deletedPriorOutputs` (`CaptureOutputTracker.kt:355`).
- **`3ec126e1` and `de3ff566` (DNG rollback):** these have a hole. The direct-write counter can keep a DNG intent that does move the route in the restored mode. See DBG2-1, and DBG2-2 for the inventory echo.

---

## DBG2-1. A failed recall rollback can strand DNG intent over the wrong route [Medium]

- **Where:**
  - `camera/CameraEngine.kt:1011`: `if (rawWantedDirectWrites == before.rawWantedDirectWrites) rawWanted = before.rawWanted`.
  - The direct-write branch at `camera/CameraEngine.kt:4001-4012`: `!routeFlips || !started || activeCameraRoute != BACK` leads to `rawWantedDirectWrites++`.
  - The producer is `ui/CameraViewModel.kt:1492`, where `applyLoaded` calls `engine.setRawWanted(safeFormats.dngRaw)` after `setResolvedOptics`.
- **Why:** The counter treats a direct write as "never moved the route", and that is true only in the mode where it was written. `setResolvedOptics` publishes `videoMode` synchronously inside its transaction (`CameraEngine.kt:2737-2738`). The `setRawWanted` that follows in `applyLoaded` therefore runs with `videoMode == true`. Its standalone answer cannot flip, so it takes the direct branch and bumps the counter. If that recall transaction then fails, rollback restores Photo mode and the Photo route (`overrideId = before.overrideId`), but it keeps the recalled DNG value because the counter moved. Two ways the recall can fail:
  - The target id cannot be resolved: `rollbackOptics(... CAMERA_UNAVAILABLE_RECALL_UNCHANGED)` at `:2795`.
  - The reconfigure fails.

  The publication at `:1032` then hands that kept DNG value to the UI (`CameraViewModel.kt:973-979`).
- **Failure scenario (both directions):**
  1. Photo with DNG off (logical camera), then recall a Video MR bank saved with DNG on, and the recall fails. Rollback lands on Photo plus logical, with `rawWanted = true` and the chip ON. The session has no RAW reader, so the shutter writes HEIF/JPEG only. `setRawWanted(true)` is change-gated (`:3997`), so tapping DNG does nothing. The operator must toggle it off and on again to repair it. This is the "DNG wanted over a logical session the change gate refuses to repair" shape that AGG-4 set out to close.
  2. Photo with DNG on (standalone 70 mm), then recall a Video bank with DNG off, and the recall fails. Rollback lands on Photo plus the standalone route with `rawWanted = false`. `lensBandFollowsZoom` (`:602-604`) now reads the lens-local zoom as unified, so the next caps reconcile parks the lens band on 1× while the session stays on 70 mm (the AGG-3 symptom).
- **Fix:** keep the newer direct write only when it does not change the route answer in the restored mode:
  ```kotlin
  val law = activeDeviceProfile().rawRequiresStandalone
  val keepNewer = rawWantedDirectWrites != before.rawWantedDirectWrites &&
      (restored.route != CameraRoute.BACK ||
       standaloneRouteWanted(restored.mode == VIDEO, rawWanted, law) ==
       standaloneRouteWanted(restored.mode == VIDEO, before.rawWanted, law))
  if (!keepNewer) rawWanted = before.rawWanted
  ```
  The alternative keeps the operator's value and schedules a re-resolving reopen once the rollback has published. The cleaner fix is AGG-49: carry `rawWanted` inside the `setResolvedOptics` packet so a recall is one transaction rather than two calls.
- **Confidence:** Medium. The code path is fully traced, but the trigger needs a failed structural recall. **Status: likely; needs manual validation.** Validate by forcing a CAMERA_UNAVAILABLE recall with a debug override, then check the `raw=` value logged by the session against the chip.

## DBG2-2. Encoder inventory can replay a rolled-back DNG choice with no zoom conversion [Low]

- **Where:**
  - `ui/CameraViewModel.kt:2693-2715` (`applyEncoderInventory` calls `engine.setRawWanted(safeFormats.dngRaw)` with no `resolvedLens`/`resolvedControls`).
  - The rollback consumer at `ui/CameraViewModel.kt:945-980` updates `photoFormats.dngRaw` but never `pendingPhotoFormatsUntilInventory`.
- **Why:** Before the async encoder inventory lands, `onSetPhotoFormats` records the pick in `pendingPhotoFormatsUntilInventory` (`:2428`). A DNG toggle that rolls back (failed reopen) restores `rawWanted` in the engine and in `photoFormats`, but the pending copy keeps the rejected value. When the inventory then lands, `applyEncoderInventory` adopts the pending formats and pushes them. The standalone answer flips with null remap arguments, and the engine reopens on the other route with the zoom number still on the old scale.
- **Failure scenario:** A cold start on PMA110 at unified 3×. The operator taps DNG within the inventory window, and that reopen fails and rolls back. The inventory lands, the session moves to standalone 70 mm, and zoom 3.0 is read as lens-local, so the result is a 9× crop (OSD ~208 mm). This is the AGG-1 symptom, through a door that AGG-1 did not cover.
- **Fix:** In the rollback consumer, also apply `pendingPhotoFormatsUntilInventory = pendingPhotoFormatsUntilInventory?.copy(dngRaw = rollback.rawWanted)`. As defense in depth, have `applyEncoderInventory` compute `remapRouteScaleOptics` whenever `safeFormats.dngRaw` differs from the current state.
- **Confidence:** Medium on the code, but the window is narrow (pre-inventory plus a failed reopen). **Status: likely.**

## DBG2-3. A Remember Settings save turns "auto" recording size into a fixed pick [Low]

- **Where:** `ui/CameraViewModel.kt:1623`: `videoResolution = (requestedVideoResolution ?: s.videoResolution)...`. `ExtraSettings.videoResolution` defaults to `""`, which means auto (`SettingsStore.kt:90`). Restore reads `""` as auto (`CameraViewModel.kt:1446`, `parseVideoResolution` returns null).
- **Why:** AGG-36 separated the request from the delivered size, but the save still writes the delivered size whenever there is no request. An operator who never picked a size therefore has the stream size persisted. The next launch restores it as an explicit request (`requestedVideoResolution = it`), which also reaches the engine's `requestedVideoSize`, so "auto" is lost after the first save. This is the same class of bug as AGG-36, for the auto user.
- **Failure scenario:** An auto user records with Open Gate on, so the delivered size is 2560×1920 and gets persisted. After a relaunch the request is 2560×1920 for good. On any device whose lenses advertise different maximum sizes (the enumerated, multi-device path), the size delivered on a smaller-max lens is persisted. That size is later honored on the main lens instead of its largest size, so the user gets 1080p where auto would give 4K.
- **Fix:** Write `requestedVideoResolution?.let { "${it.width}x${it.height}" } ?: ""`, and make the MR bank save use the same expression.
- **Confidence:** High on the code; the user-visible impact depends on the device. **Status: confirmed (code).**

## DBG2-4. The live finalized-video check deletes a take on a post-open read error [Low]

- **Where:** `storage/MediaStoreWriter.kt:2591-2596` (`classifyFinalizedVideoTrack`: any exception from `hasVideoTrack` maps to `INVALID`). The consumer is `video/VideoRecorder.kt:1968-1980`, where INVALID becomes `FAILED`, which deletes.
- **Why:** AGG-16 made a provider open failure INDETERMINATE. However, `MediaExtractor.setDataSource(fd)` on a MediaProvider FUSE descriptor raises the same `IOException("Failed to instantiate extractor")` for a corrupt container as for a transient FUSE or provider I/O error after the open, for example MediaProvider being killed mid-read. The live tail therefore stays stricter than launch recovery in the destructive direction for that sub-case.
- **Failure scenario:** A take whose mic died mid-recording (the degraded-audio stop is the only path that reaches this probe), followed by a MediaProvider restart during the reopen. The extractor throws, the result is INVALID, and a good video is deleted.
- **Fix:** Classify a thrown `IOException` from `setDataSource` as INDETERMINATE and let the RETAINED_VALIDATION_UNAVAILABLE path handle it. Keep INVALID only for "opened and parsed, but no video track".
- **Confidence:** Low-Medium. **Status: needs manual validation** (a fault-injection test that kills the provider during the reopen).

## DBG2-5. A null processed-still decode fails the shot with no log row [Low]

- **Where:** `capture/StillCapturePipeline.kt:192`: `if (d == null) { emitStatus(PHOTO_SAVE_FAILED); return }`.
- **Why:** `BitmapFactory.decodeByteArray` returns null rather than throwing on a corrupt or truncated JPEG. Two possible sources are a YUV-to-JPEG repack bug in `StillSnapshot.jpegBytes()` and a HAL JPEG blob with garbage. Every other failure edge in this function now logs through the reserved facade (cycle 1 added the snapshot and encode rows at `CameraEngine.kt:5592-5608`). This one still produces a "Photo save failed" toast with no app line, which CLAUDE.md names as the signature of the 2026-09-09 class of defect.
- **Fix:** `Log.e("StillCapturePipeline", "processed decode returned null (${bytes.size} bytes, hiRes=${spec.hiRes})")` before the status. That is one reserved row per failed shot.
- **Confidence:** High. **Status: confirmed (code).**

## DBG2-6. Analysis and AE callback exceptions are swallowed on every frame with no trace [Low]

- **Where:** `gl/GlPipeline.kt:1550-1552` (`catch (_: Throwable) { /* best-effort */ }` around `cb.invoke(hist, wave, focus, motion)`) and `:1556`.
- **Why:** That callback is also the app-side AE loop's meter feed (S/ISO/app-side P). A deterministic exception in the AE step, the FocusDetail rider or a scope consumer is thrown and swallowed on every readback. The app-side exposure silently freezes while the OSD still shows S, ISO or P. There is no log line and no Not-Ready state, so nothing on screen points to the cause.
- **Fix:** Keep the containment, but log the first failure per GL generation (by class) through `DiagnosticLog.w`. A change-gated single row fits the quota rules.
- **Confidence:** Medium that this is a diagnosability gap. No trigger is known today. **Status: likely (latent).**

---

## Re-validated deferred items (cycle-1 AGG-29..32): all still OPEN

| ID | Current location | Current state |
|---|---|---|
| AGG-29 (D8) | `camera/CameraController.kt:2266-2272` | Unchanged. I re-traced the impact: `finishProcessed`, `finishDng` and `finishSequence` are CAS-idempotent (`CameraEngine.kt:5490-5510`), and `RejectedOutputCleanupReservation.submit` after `cancel` returns false (`MediaStoreWriter.kt:1991-1999`). A completed DNG therefore cannot be deleted by the double `onError`. The remaining effect is a spurious PHOTO_CAPTURE_FAILED status plus an error row. The new lazy `readRawCharacteristics()` added here is a synchronous Binder call on the camera handler, but it runs only when the open-time read failed. That is acceptable. |
| AGG-30 (D9) | `storage/MediaStoreWriter.kt:647,670,754,1075`; `camera/CameraEngine.kt:7461` | Unchanged: 5 `runCatching { Thread.sleep }` sites. |
| AGG-31 (D10) | `camera/CameraEngine.kt:5677-5681` | Unchanged: `DngWriteResult.Failed` still reports DNG_SAVE_FAILED. |
| AGG-32 (D11) | `storage/SettingsStore.kt:141,148,156,173` | Unchanged: the `edit(commit = true)` result is ignored. |

## Checked and clean (no new finding)

- **`setRawWanted` door:**
  - The ViewModel and the engine agree on the route answer: `rawForcesStandalone` comes from the same `activeDeviceProfile()`.
  - FRONT and EXTERNAL are lens-local, so no remap is computed and the engine takes the direct branch.
  - In the paused branch, the cached `overrideId` is dropped so resume re-resolves.
- **`IdentityReadWarningGate`:** bounded LRU memory, process-wide by default, and a successful read clears the URI, so a relapse reports again.
- **`exposureModeHandoff` and `withShutterModeTakingOwnership`:** I traced P(HAL)→M, P(app)→M, ISO→ANGLE and video-P→ANGLE. Only a HAL-AE outgoing mode seeds from the live values, and `refreshProgramAppSide` flips the flag correctly.
- **Persisted bounds (`SettingsStore.kt:245-260`):** clamped before any consumer, with generous outer limits.
- **Hardware key ownership:** cleared on focus loss (`MainActivity.kt:666-676`). Countdown and timelapse are cancelled in `onStop` and `stopTimelapseLocked`.
- **Review playback setup:** releases the player and the Surface on every exit (`MediaReview.kt:278-296`).
- **`VideoRecorder` deadline executor:** its thread is created lazily on the first `schedule`. Every early exit before `armVideoStartupDeadline` leaks nothing, and the post-arm exits call `cancelVideoStartupDeadline`.

## Top 3 by expected field impact

1. **DBG2-1:** a failed MR recall can leave DNG intent and the route diverged in a way the change gate cannot repair (silent missing DNG, or a lens band collapsed to 1×).
2. **DBG2-3:** "auto" recording size is pinned after the first save.
3. **DBG2-4:** the live degraded-audio stop can still delete a good take on a transient provider read error.
