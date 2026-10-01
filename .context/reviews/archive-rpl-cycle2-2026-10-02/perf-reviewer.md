# Perf-reviewer — RPL cycle 2 (2026-10-02, HEAD e5729ffd)

Lane: performance, concurrency, thread-safety, CPU/memory/allocation, UI responsiveness, Compose
recomposition, GL-thread stalls, main-thread blocking. READ-ONLY review; nothing edited.

## Method

- Read CLAUDE.md, the cycle-1 aggregate (`.context/reviews/archive-rpl-cycle1-2026-10-02/_aggregate.md`)
  and plan (`docs/plans/2026-10-02-rpl-cycle1.md`).
- Inventory: `app/src/main/kotlin/**` (105 files, ~60k lines). Reviewed in four parallel slices:
  (1) ViewModel/UI (`CameraViewModel`, `CameraScreen`, `ZoomMath`, `controls/*`, `overlays/*`,
  `MainActivity`); (2) `CameraEngine` + `CameraController` + their helpers; (3) `gl/*`, `GyroEis`,
  `StandbyAudioController`, `RendererAssists`, `video/*`; (4) `storage/*`, `capture/*`,
  `CaptureOutputTracker`, `ui/review/*`, `camera/Retained*`, `LaunchMediaRecoveryCoordinator`,
  `RecordingStorageDispatcher`, `DiagnosticTelemetry`.
- Re-verified every cycle-1 change in `git diff ba5b16e7..HEAD -- app/src/main` for new races.
- Findings marked "verified" were re-read against the code at the cited lines by the lane lead.
- Owner decisions (ZSL dark refusal, FocusDetail threshold, CameraUnit SDK, proprietary HDR,
  orientation-moves-no-control, synchronous SettingsStore commit) are not re-raised. Cycle-1 items
  already scheduled (AGG-27, AGG-42, AGG-50..53, AGG-56..61) are referenced, not re-reported.

## Cycle-1 fixes checked and found sound

- **4e57fff2 token-scoped recorder workers**: `runRecorderWorkerNative` admits the exact token both
  while pending and after `publish` moves it to active (`video/VideoRecorder.kt:1347-1367`,
  `:1511-1525`). The owner door now refuses the same Engine while any token is pending
  (`:1335-1336`); that is consistent with the Engine's replay design
  (`CameraEngine.kt:7076-7100` → `awaitRecorderSetupReplay`), and the encoder EGL attach
  (`gl/GlPipeline.kt:747`) runs only after `publishAdmission` (`CameraEngine.kt:6379` precedes
  `setEncoderOutput` at `:6470`), so REC cannot refuse itself. Lock order muxerLock → gate lock has
  no reverse acquirer.
- **38d99950 `checkNotNull(muxer)` in drain loops**: `muxer` is nulled only before drain threads
  exist (`VideoRecorder.kt:352`) or after both joins proved the threads dead (`:501`, after
  `:418-419`); a live thread routes `stopNative` to QUARANTINE without touching `muxer`. No benign
  race became a failure; the audio-side throw still degrades to video-only (`:735-739`).
- **`audioDegradeLogged`**: CAS gives one reserved row per take.
- **`IdentityReadWarningGate`** (`storage/PendingDiscardJournal.kt:578-600`): `@Synchronized`,
  access-order LRU capped at 64, cleared on Present/Absent — bounded and thread-safe.
- **`onSetPhotoFormats` route remap** (`ui/CameraViewModel.kt:2433-2475`): main-thread only;
  `cancelPendingControls()` precedes the remap and `invalidateOpticsDerivedState()` resets
  `ZoomGlideState.pendingRatio`/`easeTarget` and removes trailing flush/quiet-landing/ease runnables
  before anything else can run on main.
- **`setRawWanted` unsynchronized early-return / `routeFlips`** (`CameraEngine.kt:3996-4000`): both
  callers are main-thread; a concurrent rollback only causes a redundant reopen. Not a finding.
- **`CaptureOutputTracker.deletedPriorOutputs`**: guarded by the class monitor, bounded at 64.
- `_state` writes from non-main threads all use `_state.update {}` (no raw `.value =` RMW remains).

## Findings

### PERF2-1 — Live finalized-video check DELETES a take on an extractor throw that launch recovery would KEEP
- **Where:** `storage/MediaStoreWriter.kt:2589-2595` (`classifyFinalizedVideoTrack`: parse
  exception → `PendingProbe.INVALID`), used by the live stop tail via `finalizedVideoTrackProbe`
  (`:1685-1706`) and `video/VideoRecorder.kt:1971-1981`. Recovery: `probeFinalizedVideo`
  (`MediaStoreWriter.kt:1657-1675`) wrapped by `pendingProbeOutcome` (`:2255-2260`), which maps
  ANY throw to INDETERMINATE (retain).
- **Why:** P3.1/AGG-16's stated rule is that the live path must never be stricter than recovery in
  the destructive direction. That now holds for a provider-open failure but not for a throw AFTER
  the open (`MediaExtractor.setDataSource`/`getTrackFormat` IOException).
- **Failure scenario:** mic drops mid-REC → audio degrades → `muxer.stop()` throws over the
  sample-less audio track (the tolerated stop) → validation SKIPPED → this probe. A transient FUSE
  read error in `setDataSource` returns INVALID → `failure` set → `shouldPublishRecording` false →
  the row is discarded. The same file left by a crash at that instant would have been adopted by
  recovery.
- **Fix:** map parse exceptions to INDETERMINATE on the live path too (retain REGISTERED for
  recovery's structural probe), and pin live/recovery parity with a test whose `hasVideoTrack`
  throws. Only a clean "no video track" answer should be INVALID.
- **Severity / confidence:** Medium / Medium. **Label:** confirmed (code divergence verified);
  real-device frequency of extractor throws on a valid file needs manual validation.

### PERF2-2 — Rollback keeps a newer `rawWanted` even when it changes the RESTORED route's answer (AGG-4 divergence returns)
- **Where:** `camera/CameraEngine.kt:1011`
  (`if (rawWantedDirectWrites == before.rawWantedDirectWrites) rawWanted = before.rawWanted`), the
  direct-write branch `:4006-4016`, the change gate `:3996`, and the VM mirror
  `ui/CameraViewModel.kt:925-927`, `:970-977`.
- **Why:** the 3ec126e1 rule keeps any later direct (transaction-less) DNG write. Direct writes
  happen in VIDEO, on FRONT/EXTERNAL, before start, and on devices without the RAW law — all places
  where the write did not move the route THEN. But the rollback can restore a different mode/route
  (e.g. PHOTO on the logical camera), where the kept value DOES change the route answer, and the
  rollback restores `overrideId`/selection without re-resolving.
- **Failure scenario:** PHOTO, DNG on → T1 pending. Switch to VIDEO → T2 (baseline B0: PHOTO,
  logical, raw=false). In VIDEO toggle DNG off→on (two direct writes; raw=true). T2 fails → rollback
  restores PHOTO + logical route but keeps raw=true; `OpticsRollbackPublication.rawWanted=true` keeps
  the chip on; the next `setRawWanted(true)` returns at `:3996`. DNG is silently dropped by output
  normalization until the operator toggles DNG off and on — the exact permanent-divergence shape
  CLAUDE.md "DNG bug #2" describes.
- **Fix:** in the rollback commit keep the newer `rawWanted` only if
  `standaloneRouteWanted(restored.mode == VIDEO, rawWanted, law) ==
  standaloneRouteWanted(restored.mode == VIDEO, before.rawWanted, law)`; otherwise restore
  `before.rawWanted` (or keep it, clear `overrideId` to the user pin, and queue a re-resolving
  reopen).
- **Related VM half:** the VM applies `rollback.rawWanted` after only an optics-generation check.
  A direct DNG write (no generation bump) made between the engine posting the rollback and the
  main-thread apply is overwritten in `photoFormats.dngRaw` and persisted, while the engine keeps
  the newer value → chip/settings/engine disagree and the change gate freezes it. Fix: carry
  `rawWantedDirectWrites` in the publication and skip the `dngRaw` mirror when stale (or read the
  engine's current value on main).
- **Severity / confidence:** Medium / Medium. **Label:** likely (narrow timing: toggles during a
  pending reconfigure plus a failure).

### PERF2-3 — Dual-open candidate install does not recheck `paused` inside the monitor; a camera can be opened after `onStop`
- **Where:** `camera/CameraEngine.kt:4191-4198` (install predicate in `reconfigureCamera`'s
  dual-open path). `paused` is checked only outside the monitor at `:4177`. The sequential path
  checks `paused` inside the same monitor (`:2368`).
- **Why:** `pause()` (`:7299`) sets `paused`, then nulls `controller` under the monitor and queues
  `close()`. A pause that lands between `:4177` and `:4191` lets this task install `controller = next`
  and call `next.open(...)` (`:4237`) while backgrounded; it is closed only after
  `waitForDualOpenBoundary` later observes `paused`. Worse, if pause's `controller = null` ran
  before the install, pause queued no close for `next` at all.
- **Failure scenario:** lens/mode switch immediately followed by Home/lock opens a CameraDevice
  behind the keyguard — privacy indicator, CAMERA_DISABLED, or evicting another app's camera.
  CLAUDE.md: "never open the camera while backgrounded".
- **Fix:** add `|| paused || recorder != null` to the in-monitor predicate at `:4192`, matching the
  sequential path.
- **Severity / confidence:** Low-Medium / Medium. **Label:** confirmed in code (verified); device
  effect needs manual validation.

### PERF2-4 — `onCleared` purges main-thread callbacks before detaching engine callbacks; a late post can restart the self-reposting `recordTicker` forever
- **Where:** `ui/CameraViewModel.kt:4155` (`mainHandler.removeCallbacksAndMessages(null)`) runs
  before `:4160` (`engine.detachCallbacks()`); `recordTicker` (`:232-243`) reposts every 200 ms with
  no `cleared` guard.
- **Why:** engine callbacks post to `mainHandler` from camera/setup/recorder threads. Anything
  posted between the purge and the detach (or by a callback lambda already read before the detach)
  survives.
- **Failure scenario:** activity finishes while REC start is in flight; the `onRecordingStarted`
  post (`:1142-1150`) lands after the purge and posts `recordTicker`, which then runs whole-state
  updates at 5 Hz for the rest of the process and pins the cleared ViewModel (and the engine graph
  it references). Other late posts (`onCapsReady`, `onOpticsRollback`) call engine setters on an
  engine being released.
- **Fix:** `engine.detachCallbacks()` before the purge, purge again after it, and guard
  self-reposting runnables with `if (cleared) return`.
- **Severity / confidence:** Low / Medium. **Label:** confirmed ordering (verified);
  reachability needs manual validation.

### PERF2-5 — Lazy `rawChars` retry (cycle-1 1c5a0060) makes a Binder call on the camera thread for every shot while the read keeps failing
- **Where:** `camera/CameraController.kt:2266` (`rawChars ?: readRawCharacteristics()`), `:2412-2421`.
- **Why:** `getCameraCharacteristics` runs inside `tryComplete` on the camera handler — the thread
  that delivers images/results — while the live Images are held, before `onPhoto`. With a
  persistent failure (policy-disabled camera, provider churn) every still, including each frame of
  a BURST/AEB chain, pays one IPC there. Only the first failure is logged.
- **Fix:** do the retry on `setupExecutor` before each still dispatch (or rate-limit it, e.g. one
  attempt per second), keeping the camera-thread path cache-only like the Engine's EXIF metadata
  prefetch.
- **Severity / confidence:** Low / High. **Label:** confirmed.

### PERF2-6 — Launch recovery now walks every page after an exhausted failure, 3 retries each, under a process-terminal 120 s deadline
- **Where:** `storage/MediaStoreWriter.kt:1414` (`continueAfterFailureExhaustion = nextCursor != cursor`),
  `camera/LaunchMediaRecoveryCoordinator.kt:259-285`, deadline `:236-245`
  (`PROCESS_LAUNCH_MEDIA_RECOVERY_DEADLINE_MS = 120_000`).
- **Why:** one batch covers both Images and Video. If one collection's query fails persistently
  (cursor not advanced) while the other advances, `nextCursor != cursor` stays true and every
  following page re-runs the failing query, each page retried `MAX_MEDIA_RECOVERY_ATTEMPTS` = 3
  times with backoff and full structural re-probes of every pending row on the page. Before cycle
  1 (a1fee383) recovery stopped at the first exhausted page; now its cost is
  O(pages × 3 × probe cost).
- **Failure scenario:** many legacy indeterminate rows, or an unqueryable Images collection with
  several 64-row Video pages → the 2-minute watchdog fires `exhaust()`, and launch recovery is
  unavailable for that process (every later subscriber gets
  `LaunchMediaRecoveryCapacityExhaustedException`). No data loss (rows stay pending), but a liveness
  regression introduced by the fix.
- **Fix:** track progress per collection; once a collection's query is exhausted, skip it for the
  rest of the run and advance without further retries; retry only the failed rows, not the whole
  page.
- **Severity / confidence:** Low-Medium / Medium. **Label:** needs-manual-validation.

### PERF2-7 — Focus-evidence epoch sampled when the analysis callback runs, not when the readback was taken
- **Where:** `ui/CameraViewModel.kt:1051-1070` (`val epoch = focusEvidenceEpoch` inside
  `engine.onAnalysis`, GL/analysis thread); the field (`:548`) is a plain `Long`, not `@Volatile`.
- **Why:** the guard protects only the post→main gap. A readback taken before an optics door, whose
  callback fires after the door bumped the epoch on main, samples the NEW epoch and its old-route
  FocusDetail/motion verdict is folded into the new route. The non-volatile cross-thread read can
  also be stale (false drops only).
- **Failure scenario:** lens tap while the scopes/AE readback is in flight → the old lens's SOFT
  evidence feeds the new route's 700 ms hold or the motion accumulator for one tick.
- **Fix:** stamp the epoch where GL captures the readback (GL already carries
  `motionEvidenceEpoch`) and pass it through the callback; at minimum mark the field `@Volatile`.
- **Severity / confidence:** Low / Medium. **Label:** likely.

### PERF2-8 — Frame-notification coalescing may leave a standing SurfaceTexture backlog (latency, not cadence)
- **Where:** `gl/FrameNotificationCoalescer.kt:21-40`, `gl/GlPipeline.kt:447-455`, the single
  `st.updateTexImage()` per drain at `:984`.
- **Why:** N `onFrameAvailable` notifications collapse into one drain, and each drain latches once.
  The comments assume `updateTexImage` "latches the newest frame". In GLConsumer, `updateTexImage`
  acquires with `expectedPresent = 0`, which takes the oldest queued buffer without dropping, unless
  the producer queues in droppable/async mode. Whether the Camera3 stream into this SurfaceTexture is
  droppable is not established here.
- **Failure scenario:** if not droppable, two frames arriving during one busy GL interval (the
  analysis `glReadPixels`, a zoom self-redraw, a slow encoder swap) leave one buffer queued
  permanently. Each later burst can add another, until the queue is full. The finder and encoder
  then run k frames late (3–5 frames ≈ 100–170 ms at 30 fps) at an unchanged ~30 fps cadence, so
  FrameGap never fires.
- **Fix:** count notifications (`pending.getAndSet(0)`), call `updateTexImage()` that many times,
  and draw once; keep one encoder frame per drain so PTS stays monotonic. Do not loop on "timestamp
  changed".
- **Severity / confidence:** Medium / Low. **Label:** needs-manual-validation (on device, log
  `elapsedRealtimeNanos() - st.timestamp` at draw time under scopes-on + pinch load).

### PERF2-9 — Video-startup deadline task interrupts its own thread before invoking `onFailure`
- **Where:** `video/VideoRecorder.kt:1022-1045`, `recordFailure` `:1047-1055`.
- **Why:** the expiry runs on the `video-start-proof` executor thread and calls `recordFailure` →
  `cancelVideoStartupDeadline()` → `videoStartupDeadlineExecutor.shutdownNow()`, which interrupts
  the current thread. `firstFailure.record { onFailure }` then runs
  `CameraEngine.handleUnexpectedRecorderFailure` with the interrupt flag set. That path also takes
  `muxerLock` unbounded; a drain thread wedged in native code under `muxerLock` blocks this daemon
  forever (thread leak).
- **Failure scenario:** benign today (the failure path only posts/dispatches). Any interruptible
  wait/IO added later to the failure path fails immediately on this thread.
- **Fix:** use `shutdown()` (not `shutdownNow()`) when called from the deadline task, or clear the
  interrupt flag before `onFailure`; take `muxerLock` with `tryLock(timeout)` as
  `quarantineUnsafeNativeGraph` already does.
- **Severity / confidence:** Low / High (mechanism). **Label:** likely latent.

### PERF2-10 — Standby meter-thread invariant failures crash the process instead of degrading
- **Where:** `camera/StandbyAudioController.kt:631-633` (`check(terminationOwner.bind(input))`),
  `:648`, `:655` (`checkNotNull`), `:543` (`check(liveInputTermination.compareAndSet(...))`).
- **Why:** these throw inside the plain `Thread` started by `StandbyThreadLauncher` (`:578-580`);
  `finally` releases state, but the exception is uncaught on a non-daemon thread → process crash.
  `:543` throws on main, on the meter thread's retry, or on the retry-fallback thread.
- **Failure scenario:** an ownership regression or unexpected publication ordering kills the app
  while it is merely armed in VIDEO (standby meter), not even recording.
- **Fix:** wrap the meter task body in `catch (t: Throwable)` → mark the generation failed, log
  once; make `start()` refuse with a log rather than `check`.
- **Severity / confidence:** Low / Medium. **Label:** needs-manual-validation.

### PERF2-11 — Recording-storage presentation reducer invokes UI listeners while holding its lock
- **Where:** `camera/RecordingStorageDispatcher.kt:171-177`, called from
  `CameraEngine.presentRecordingStorageResult` (`CameraEngine.kt:7243-7259`).
- **Why:** `present` runs `onMediaSaved`/`onStatus` on a storage worker under `lock`;
  `observeCapture` takes the same lock on the REC start path (`CameraEngine.kt` after publication).
  A slow listener stalls the next recording's admission. The current VM listeners only post, so no
  deadlock today; the ordering is deliberate.
- **Fix:** document the "listeners must not block" contract at the seam, or publish outside the
  lock with a sequence recheck.
- **Severity / confidence:** Low / Low. **Label:** needs-manual-validation.

## Known items re-observed (not re-reported)

- AGG-42 audio EOS wait spins in Java and is classified as a native wedge on join timeout
  (`VideoRecorder.kt:886-925`, `:418-435`) — unchanged by cycle-1 drain edits.
- AGG-51 `GyroEis.start()` → `reset()` on the GL thread vs main-thread sensor callbacks on plain
  integrator fields.
- AGG-53 `GlPipeline.thread/handler` plain fields written by the cleanup Runnable on the GL thread
  and by `stop()` on the caller (`gl/GlPipeline.kt:1620-1690`).
- AGG-27 lifetime diagnostic budget.

## Final sweep (commonly missed) — clean

- No `runBlocking`; `Thread.sleep` only on worker retry loops (AGG-30 interrupt-flag note stands).
- Bounded waits on main: `GlPipeline.stop` (1.5 s) runs from release on the dedicated
  `camera-engine-release` thread; `pause()` moves controller close to `setupExecutor`.
- Lock order process admission lock → Engine monitor → `recorderOwnershipLock` is consistent;
  `isActive()`/`TerminalAcquisitionGate.isOpen()` are lock-free; no `synchronized(this)` takes the
  process lock.
- Analysis single-flight (`AnalysisGenerationOwner` CAS + retire), `FocusDetail` scratch in a
  `ThreadLocal`, per-frame draw allocations removed (`coverScaleInto`).
- Still path memory bounded (`ProcessedSnapshotBudget`; NV21 snapshot dropped after encode; Images
  closed in `tryComplete`'s `finally`); finite worker owners release permits exactly once
  (`RejectedOutputCleanupCapacityOwner` worst case 41 < ownership limit).
- Review decode is off main on the shared review dispatcher; superseded results are disposed.

## Summary

| ID | Sev / Conf | One line | Where |
|---|---|---|---|
| PERF2-1 | Medium / Medium | Live video validation deletes on extractor throw; recovery would retain | `storage/MediaStoreWriter.kt:2594` |
| PERF2-2 | Medium / Medium | Rollback keeps a newer DNG intent that changes the restored route; VM mirror can clobber a newer direct write | `camera/CameraEngine.kt:1011`, `ui/CameraViewModel.kt:925` |
| PERF2-3 | Low-Med / Medium | Dual-open install lacks in-monitor `paused` recheck → open while backgrounded | `camera/CameraEngine.kt:4191` |
| PERF2-4 | Low / Medium | `onCleared` purges before detaching callbacks; `recordTicker` can repost forever | `ui/CameraViewModel.kt:4155` |
| PERF2-5 | Low / High | Lazy characteristics retry does a Binder call on the camera thread per shot | `camera/CameraController.kt:2266` |
| PERF2-6 | Low-Med / Medium | Recovery retries every page 3× after exhaustion; can trip the process-terminal 120 s deadline | `storage/MediaStoreWriter.kt:1414` |
| PERF2-7 | Low / Medium | Focus-evidence epoch sampled at callback time, not readback time | `ui/CameraViewModel.kt:1051` |
| PERF2-8 | Medium / Low | One `updateTexImage` per coalesced drain may build a standing frame backlog | `gl/FrameNotificationCoalescer.kt:21` |
| PERF2-9 | Low / High | Startup-deadline task self-interrupts before `onFailure`; unbounded muxerLock | `video/VideoRecorder.kt:1022` |
| PERF2-10 | Low / Medium | Standby meter `check`/`checkNotNull` crash the process | `camera/StandbyAudioController.kt:631` |
| PERF2-11 | Low / Low | Presentation reducer runs UI listeners under its lock | `camera/RecordingStorageDispatcher.kt:171` |

Total: 11 findings (0 High, 3 Medium, 8 Low/Low-Medium). Nothing device-verified; every
camera/GL/audio item stays PENDING DEVICE.
