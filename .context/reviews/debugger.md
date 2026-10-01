# Debugger review, RPL cycle 3: latent failure modes, swallowed errors, regressions

Reviewer: debugger lane (read-only). Date: 2026-10-02. HEAD `e3a2bdd4`. Finding prefix `DBG3-`.

## Scope and method

1. I read the full cycle-2 main-source diff (`c2892dda..HEAD`, 23 files, +978/-156) hunk by hunk. For each fix I traced its callers and checked whether it opened a new failure edge:
   - Optics/DNG rollback: `rollbackRawWanted`, `keepNewerDirectWrite`, `rollbackRestorableSessionGeneration`, `rollbackOpticsAfterPreflight`.
   - DNG door: `dngIntentChangesRearRoute`, `dngDoorRemapsZoomScale`, the `setResolvedOptics` `resolvedRawWanted` packet, and the paused and not-started branches of `setRawWanted`.
   - DNG continuation: `StillContinuationHandoff` against every `DngPreCaptureAllocation.start` terminal, including deadline-arm failure, plus the BURST, AEB and timelapse chains.
   - `DngWriteResult.Failed`, now retained for recovery.
   - Dual-open install admission and its candidate close.
   - AE program-line clamp residual; ISO/shutter/angle dial ownership handoffs.
   - Storage: the `LazyReadRetryGate` lazy `rawChars` accessor, the ZSL ring flush edge, `exhaustedCursor` launch recovery, `sleepPreservingInterrupt`, the per-URI storage warning gate, and the tri-state finalized-video probe.
   - Lifecycle and settings: the standby-meter invariant degradation, the startup-deadline retirement, `cleared` guards and detach-before-purge in `onCleared`, `recallMemorySlot`, and the settings commit result.
2. I re-checked the durable-recovery probes against REAL app output files in `device-tests/reports/**/evidence/` (HEIF and DNG). I parsed them with a small offline ISO-BMFF/TIFF script, so no device or Gradle was needed. This produced DBG3-1.
3. Sweeps:
   - Unit conversions: ns/ms/µs PTS, exposure, diopters and bitrate.
   - Ordinal persistence: none is persisted.
   - Throws that escape thread roots in camera callbacks.
   - Capture-chain continuations, recorder deadline executors, and the EXIF orientation transform.

I did not re-report owner decisions or items already open in the cycle-1/2 aggregates (AGG-39 A/V clock, AGG-29 double terminal, the rearReturnZoom divisor, the auto-size bank recall, and so on). Nothing here is device-verified.

**Summary: 3 findings** (1 Medium, 2 Low). Cycle-2 regression verdict: no regression found in the 40 cycle-2 commits (details at the end).

---

## DBG3-1. Launch recovery can never adopt a real app HEIF, so a "retained" HEIF stays private until MediaProvider expires it [Medium]

- **Severity:** Medium. **Confidence:** High. **Status:** Confirmed. The code path is traced, and the app's own device-written HEIF files were parsed. The final platform expiry step is needs-device.
- **Where:**
  - `storage/MediaStoreWriter.kt:2989-2991`, `parsePrimaryItemExtents`: `if (isPrimary && constructionMethod != 0) return HeifParse.Failure(PendingProbe.INDETERMINATE)`.
  - It is reached from `probeCompleteHeif` (`:1765`) through `probePendingMedia` (`:1709`), then `orphanDisposition` (`:2699-2714`). INDETERMINATE leads to `KEEP_PENDING`.
  - Producer: `capture/StillCapturePipeline.kt:288-306` (`writeProcessedHeif` → `markWriteComplete` → `completeStillPublication(markerDurable = completion.durable)`). On a non-durable marker it emits `OUTPUT_SAVED_PENDING_RECOVERY` ("HEIF save retained. Recovery marker failed.").
  - Encoder: `capture/HeifCapture.kt:28` (`HeifWriter.Builder(..., INPUT_MODE_BITMAP)`, grid left at its default `true`).
- **Evidence (real files, not fixtures):** both files have the same structure.

  | File | iloc | Primary item (from pitm) | Construction method | Data location | Other items |
  |---|---|---|---|---|---|
  | `device-tests/reports/20260723-224209/evidence/capture_then_kill_survives/IMG_TELECAM_F1_…0001.heic` | v1, `offset_size = length_size = 4`, 50 items | 10048 | **1** | `idat` box present in `meta` | 49 tiles, all construction 0 |
  | `IMG20260714212159.heic` (repo root) | v1, 110 items | 10108 | 1 (same structure) | `idat` | tiles |

  This is how AOSP `MPEG4Writer` writes any gridded HEIF: the grid descriptor lives in `idat` with `construction_method = 1`. `HeifWriter` grids every image larger than 512 px. Every 12.5 MP still this app writes therefore hits the INDETERMINATE branch, and the probe can never return VALID for app output. The host fixture `locatedHeif()` (`app/src/test/.../MediaDurabilityPolicyTest.kt:60`) models a construction-0 primary that the real encoder never produces. The suite even pins `"idat construction"` as INDETERMINATE (`:115`), so the gate cannot see this.
- **Why it matters:** HEIF is the default processed format (`PhotoFormats.heif = true`, `camera/CameraState.kt:1199`). Every HEIF row whose journal is still `REGISTERED` is kept pending on every launch, forever:
  - The COMPLETE marker exhausted all 3 commit attempts, or
  - The process died between `HeifWriter.stop()` and the marker commit.

  The status copy, and since 49f435e1 the sibling copy, promise that the take is retained for the next start. A row inserted with `IS_PENDING=1` gets a default `DATE_EXPIRES` about 7 days out, and MediaProvider idle maintenance deletes expired pending rows. Nothing in recovery refreshes that expiry (`grep DATE_EXPIRES` finds nothing). The photo is never shown in Gallery, and the platform then silently deletes it. The JPEG (FFD9 tail) and DNG probes do work on real output: I parsed `.../tele_dng_capture/…0004.dng`, which has IFD0 4096×3072, 3072 strips and DNGVersion present. So only the default format loses the recovery guarantee.
- **Failure scenario:**
  1. Storage is nearly full, or the prefs commit fails, after a HEIF finishes writing.
  2. The toast says "HEIF save retained. Recovery marker failed."
  3. On each later launch, recovery queries the row, and `probeHeifIsoBmff` returns INDETERMINATE, so the row is retained (the RETAINED counter increments).
  4. About a week later MediaProvider deletes the pending row. The user never sees the photo.

  A swipe-kill that lands in the narrow window between `HeifWriter.stop` and the marker reaches the same end state.
- **Fix (host-testable):** teach the probe the gridded-primary layout that `MPEG4Writer` writes.
  1. Parse `idat` and `iref` inside `meta`.
  2. For a primary with `construction_method == 1`, require:
     - its single extent lies wholly inside the `idat` payload;
     - the `ImageGrid` descriptor reads `rows × cols`;
     - `iref`/`dimg` from the primary lists exactly that many tile item ids;
     - every tile has a construction-0 extent that lies wholly inside an `mdat` payload.
  3. Return VALID only when all of that holds. Keep INDETERMINATE for any other construction method, and INVALID for out-of-range extents.

  Add a regression test whose fixture is the real `capture_then_kill_survives/*.heic` bytes. That file is checked in, about 1 MB; a truncated copy can serve as the INVALID case. A stopgap that is also worth doing on its own: when recovery KEEPs a row it cannot judge, extend `DATE_EXPIRES` (or log a reserved row), so "retained" never silently becomes "expired".

## DBG3-2. A drop-frame rate makes the encoder drop real camera frames instead of encoding true 23.976/29.97/59.94 [Low]

- **Severity:** Low. **Confidence:** Medium. **Status:** Needs-device (frame-timestamp dump of a 29.97 clip).
- **Where:**
  - `video/ColorProfiles.kt:44-49` (`applyFrameRate`: `KEY_MAX_FPS_TO_ENCODER = encoderRate`, for example 29.97).
  - `camera/CameraState.kt:1060-1068`: a drop-frame rate rides its integer parent, so the AE target range is `[30,30]` and the camera delivers 30.000 fps.
- **Why:** with a Surface input, `max-fps-to-encoder` drives the input surface's frame dropper (GraphicBufferSource `FrameDropper`). That dropper discards a frame whenever the accumulated deficit between a 33.333 ms camera cadence and a 33.367 ms minimum interval exceeds its roughly 2 ms jitter tolerance. The PTS values are camera timestamps and are deliberately not retimed. The result is a file with mostly 33.33 ms frame intervals and a missing frame:
  - about 2 s into the take;
  - then about once every 1001 frames (about every 33 s at 29.97, about 42 s at 23.976, about 17 s at 59.94).

  That is a periodic visible motion hitch in a pan, and a VFR file that some NLEs conform poorly. It is not a constant-rate 29.97 stream. The comment ("honor 23.976/29.97/59.94 without retiming PTS") describes the intent, but this mechanism produces the result above.
- **Failure scenario:** a 2-minute 29.97 tripod pan has about 3-4 single-frame skips at regular intervals. In a 29.97 timeline each skip shows as a jump.
- **Fix:** pick one model explicitly:
  - (a) True cadence: rescale video PTS by 1000/1001 in `GlPipeline` (`ts - encoderBaseNs` × 1000/1001) and keep the audio clock as is. This changes the A/V sync model, so tie it to AGG-39.
  - (b) Encode the integer rate and label it honestly in the UI, dropping `KEY_MAX_FPS_TO_ENCODER`.

  In either case, device-verify with an `ffprobe -show_frames` interval histogram before and after.

## DBG3-3. Metering and still completion now share one 1 s `rawChars` retry budget, so a recovered read can still fail a delivered still [Low]

- **Severity:** Low. **Confidence:** Low-Medium. **Status:** Likely (latent, narrow).
- **Where:** `camera/CameraController.kt:161, 2431-2436` (`chars()` via `LazyReadRetryGate(1 s)`). It is called from `applyMetering` (`:1312`, on every repeating rebuild or still build that carries regions) and from `tryComplete` (`:2277`).
- **Why:** AGG2-19 routed both callers through one rate-limited gate, which is correct for a read that keeps failing. But the budget is shared. Suppose a metering rebuild consumes the retry at time t and fails. If the open-time race clears at t+0.2 s, a shutter at t+0.5 s finds the gate closed: `chars()` returns null even though the read would now succeed. The HAL has already delivered the Images, yet the shot goes to `onError("Missing camera characteristics")` and the frame is discarded. Before cycle 2, `tryComplete` always retried.
- **Fix:** let `tryComplete` bypass the rate limit (it holds delivered Images, and losing a captured frame costs far more than one Binder call). Alternatively, give it its own gate. Keep the limiter for `applyMetering`, which runs at rebuild rate. Host test: inject a gate clock and a read that fails once and then succeeds, run metering, then `tryComplete` 100 ms later, and expect `onPhoto`.

---

## Cycle-2 regression verdict (no new defect found)

- **`rollbackOpticsAfterPreflight` / `rollbackRestorableSessionGeneration` (AGG2-4):** restoring at the door's own preflight generation is sound.
  - `invalidateCameraReady` retires the tap focus with `tapResetPending = true`. `retainedOpticsApplyPlan` sees that flag, so the retained commit rebuilds and does not leave a stale AF-AUTO hold on the wire.
  - Any later bump (camera error, pause, newer door) moves the generation and keeps the rollback Not-Ready.
  - `publishTerminal` rechecks the session generation and the sequence.
- **`rollbackRawWanted` (AGG2-1):** it reads `activeDeviceProfile()` after `activeCameraRoute` is restored, so the law is the restored route's law.
  - The paused branch of `setRawWanted` is deliberately not counted as a direct write, so a rollback restores it together with the pre-door `overrideId`. That is consistent.
- **`StillContinuationHandoff` (AGG2-3):** every non-ACCEPTED `start()` retires synchronously, so the settle runs first and the dispatcher answers true. This covers deadline-arm failure, because `RecordingOperationDeadline.arm` invokes `onTimeout`, which calls `attempt.retire`.
  - Chains therefore continue exactly once. AEB does not restore base controls mid-bracket, because `fire(i+1)` runs inside `dispatchStillCapture`.
  - BURST ignores a `false` dispatch, which ends the burst early with a status. That is pre-existing and acceptable.
- **`DngWriteResult.Failed` (AGG-31):** the row stays REGISTERED with complete bytes, and the DNG probe adopts it at launch (confirmed against a real DNG). `finishDng` runs from the `finally` block because `dngPublishQueued` stays false.
- **Dual-open admission (AGG2-14):** a refused candidate is closed (`next.close()`) and the startup-trace owner is revoked, so there is no leak.
- **`AutoExposure.driveProgram` (AGG2-16):** the residual is exactly 0 inside the span, and ISO overflow still goes through the max/min branches and the final clamp. Degenerate ranges are still guarded by the `slowCapNs` floor.
- **`exhaustedCursor` (AGG2-21):** `hasMore` is computed from `nextCursor`, which is never more complete than `exhaustedCursor`. At worst it costs one extra no-op batch, and the loop terminates.
- **`retireStartupDeadline` (AGG2-24):** `shutdown()` purges the cancelled delayed task. The setup-failure path's `shutdownNow()` (`VideoRecorder.kt:354`) runs before the deadline is armed, so no thread exists to interrupt.
- **`onCleared` (AGG2-15):** `cleared` is set before detach and both purges. Every self-reposting runnable, including the countdown tick, checks it.
- **`recallMemorySlot` (AGG2-26):** `appliedLoadCount` increments only at `applyLoaded`'s applied exit, and `applyLoaded` runs synchronously inside `onRecallMemorySlot`.
- **`SettingsStore.commitEdit` (AGG-32):** the result is observed and the warning is logged once per store. The trailing-lambda constructor binding is preserved, because `onCommitFailure` is declared before `missingPhoneModel`.

## Files examined (grouped)

- **camera/:** `CameraEngine.kt` (cycle-2 hunks; rollback commit/effects; `setRawWanted`; `setVideoResolution`/`applyVideoSize`; `reopenForSession`; `reconfigureCamera` dual-open; `dispatchStillCapture`; `photoCallback`; burst/AEB/timelapse; stop recording), `CameraController.kt` (`capturePhoto`, `tryComplete`, `chars`, `applyControlsOnCamera`, `clearMeteringPoint`, ZSL ring), `DngPreCaptureAllocation.kt`, `RecordingTeardownCoordinator.kt` (`RecordingOperationDeadline`), `AutoExposure.kt`, `ManualControls.kt` (dial handoffs, watchdog, AEB math), `StandbyAudioController.kt`, `LaunchMediaRecoveryCoordinator.kt`, `DiagnosticTelemetry.kt`, `CameraState.kt` (zoom conversions, `rearReturnZoom`, `VideoFrameRate`, `videoBitRate`, `PhotoFormats`).
- **capture/:** `StillCapturePipeline.kt`, `StillSnapshot.kt`, `HeifCapture.kt`.
- **storage/:** `MediaStoreWriter.kt` (warning gate, retry backoffs, recovery batch/cursors, video/HEIF/JPEG/DNG probes, ISO-BMFF parser), `SettingsStore.kt`.
- **video/:** `VideoRecorder.kt` (audio loop, PTS, startup deadline, finalized validation), `ColorProfiles.kt`.
- **gl/:** `GlPipeline.kt` (encoder PTS rebase, analysis failure gate).
- **ui/:** `CameraViewModel.kt` (cycle-2 hunks, FRONT snapshot/return, countdown, recording toggle, recall), `ZoomMath.kt`, `CaptureOutputTracker.kt` (seed path), `review/MediaReview.kt` (decode bounds, EXIF transform).
- **App and policy:** `MainActivity.kt` (key ownership, recall denial clearing, onStop), `CameraPermissionPolicy.kt`.
- **Tests consulted:** `app/src/test/.../storage/MediaDurabilityPolicyTest.kt` (HEIF fixtures).
- **Real outputs parsed:** `device-tests/reports/20260723-224209/.../*.heic`, `IMG20260714212159.heic`, `device-tests/reports/20260722-212855/.../*.dng`.
- **Prior state:** `.context/reviews/archive-rpl-cycle2-2026-10-02/{_aggregate,debugger}.md`, `docs/plans/2026-10-02-rpl-cycle2.md`, `CLAUDE.md`.
