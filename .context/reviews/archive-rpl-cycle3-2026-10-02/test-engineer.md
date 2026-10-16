# Test-engineer review — RPL cycle 3 (HEAD e3a2bdd4, 2026-10-02)

Scope: every cycle-2 fix commit `c2892dda..e3a2bdd4` (39 commits), each commit's test diff, the
Partition-A residual manifest against the current JaCoCo report
(`app/build/reports/coverage/test/debug/report.xml`, written 05:08, after HEAD at 04:57, no source
changes since), and a flakiness sweep of `app/src/test/**`. Read-only; no Gradle run. For each fix
the question was: does some test fail if the production change is reverted?

## Cycle-2 fix → regression-test audit

| Commit | Fix | Fails if reverted? |
|---|---|---|
| 4edd2a14 | Recall carries DNG in its own transaction | Engine: yes (compile + `OpticsRouteInputTransactionRobolectricTest`). Re-adding the VM trailing `setRawWanted`: no. |
| b565abae | Route-aware rollback keep rule | Yes (engine Robolectric + pure). |
| 3ef6e845 | One continuation per rejected DNG shot | **No.** The test rebuilds the wiring itself (TE3-2). |
| b5c57e8a | Ready restored after preflight failure | Helper yes; the two `reconfigureCamera` call sites **no** (TE3-3). |
| 5e4cc454 | No same-camera reopen for DNG on TELE | Yes. |
| 4d0c5e3c | Size picked mid-door survives rollback | Yes (engine + VM). |
| 8f84b0bc | Dual-open pause recheck | Predicate yes; call site **no** (TE3-3). |
| 17c162d4 | Marker-failed DNG reported retained | Status helper yes; the engine `is DngWriteResult.Failed` branch **no** (TE3-3). |
| ae359201 | FRONT-retained TELE zoom round trip | Pure yes; VM `rearReturnZoom` call **no** (TE3-3). |
| d1341513 | Persist auto video size | Yes. |
| 475dcf1f | Pre-inventory format edits merge | Pure `withEdit` yes; VM wiring **no** (TE3-5). The pure merge also has a gap (TE3-7). |
| e5f1fc7a | Rollback DNG in pending formats | Yes. |
| c6caab59 | DNG door follows engine RAW law | The generic-law negative path: yes. The PMA110 positive path has no VM test (TE3-5). |
| f6f25a5b | Camera override refused mid-REC | Yes. |
| d9ed066d | DNG answer in the recall caps gate | Pure yes; VM arguments **no**, and they are silent because the new params default to `false` (TE3-4). |
| b5e8d622 | Detach before purge, `cleared` guards | 3 of 6 guards yes; the detach order **no** (TE3-9). |
| 15e5f3cb | Dial doors seed from live | Pure yes; VM arguments **no**, and silent because of `= null` defaults (TE3-4). |
| f64417fa | Denial reason only on applied audio-on recall | Pure + `recallMemorySlot` yes; the MainActivity condition is untested (Activity-bound, acceptable). |
| 2d391a7d | P seed clamp carried into ISO | Yes (`AutoExposureTest`, product within 0.05 stop). |
| c4cc92e7 | Extractor throw → INDETERMINATE | Yes. |
| 03203d17 | Shared rate-limited `chars()` | Gate yes; wiring **no**. The shared gate also introduces a regression (TE3-6). |
| c91d741f | ZSL ring flush on streaming stop | Edge predicate yes; the `zslRingFlush()` call **no** (TE3-3). |
| f4ecda3d | Exhausted recovery query skipped | Yes (coordinator). The same edit to the dead `cleanupOrphanedPending` loop: n/a (TE3-8). |
| a0a12b7d | Null decode logs once | No test (one log row; acceptable). |
| 531060e6 | Analysis failures logged once per class | Gate yes; GlPipeline call site no (GL-bound; acceptable). |
| 56803b42 | Startup deadline retired without self-interrupt | Helper yes; `cancelVideoStartupDeadline` reverting to `shutdownNow()` **no** (TE3-3). |
| 5a49bbfd | Standby meter invariants degrade | Yes (escape-catching fake launcher). |
| b1447900 | Bounded Photo shutter / WB tint | Yes, both ends. |
| 25631385 / 4a1c6507 | Change-gated storage warnings, fresh budgets | Yes, exact counts. TE2-2 is closed. |
| ab862007 | Interrupt kept across retry backoff | `markCompletionWithRetry` yes; the other three loops (`markWithRetry` journal loop, family deletion mark, `publish`) **no** (TE3-3). |
| 1f7e14b3 | Failed settings commit reported once | Yes. |
| ba3ee153 | 10-bit caption keyed on request | Helper yes; ProSheet argument **no** (low risk). |
| b953b741 | "Correct" stale residual regions | **Introduced two new wrong regions** (TE3-1). |

## Findings

### TE3-1 — The Partition-A residual manifest cites the wrong lines for 6 of its 9 entries, and the "fix" commit added two of them (Medium, High) — Confirmed
- Files: `tools/coverage/partition-a-residuals.txt:4-12`, `tools/coverage/partition_report.py:131-149`
  (`Residuals.drift` compares class, missed **count**, and file **name** only).
- Evidence. I resolved each class's missed lines from the JaCoCo report generated at HEAD:

| Class | Manifest region | Actual missed line(s) at HEAD | What the cited lines contain |
|---|---|---|---|
| CameraControllerKt | CameraController.kt:2607-2610 | ~2665-2671 (`cameraFailureIsEviction`) | KDoc of a `SessionAttemptPlan` field. **Written by b953b741.** |
| CameraStateKt | CameraState.kt:904-906 | 906 | correct |
| CaptureFamilyKey$Companion | CaptureFamily.kt:61,65-66 | 61, 66 | correct |
| HeifBoundedReader | MediaStoreWriter.kt:2968-2970 | ~3048-3050 (`readUnsigned` width guard; class starts at :3036) | a different parser function outside the class. **Written by b953b741.** |
| LatestCaptureReducerKt | LatestCaptureReducer.kt:205,295-297,354-363 | 218, 310, 381 | unrelated lines; all three are stale |
| CaptureOutputTracker | CaptureOutputTracker.kt:259-260 | 260 | correct |
| OwnerlessMediaDeleteOverrides | CameraViewModel.kt:133-137 | 151, 154 (the two default lambdas) | `import` lines |
| ZoomMathKt | ZoomMath.kt:112-115 | 121 (`?: return emptyList()`) | the KDoc/signature of `teleZoomMarks` |
| FnQuickActionsKt | FnQuickActions.kt:93-94 | 113 (`FnSlot.SHUTTER` dial branch) | label rows of a different `when` |

- Why it matters. The manifest's header says it is "Enforced exactly". It is enforced for counts
  only. The plan marks C.8 (TE2-3) `[x]`, yet the regions are wrong. A reviewer who opens a cited
  region to check a "proven-unreachable" rationale sees unrelated code. A new, unreviewed miss in
  one of these classes would be accepted under an old rationale whenever a reviewed miss became
  covered in the same edit.
- Failure scenario. Suppose someone deletes the `?: return emptyList()` guard in
  `teleZoomMarks` and adds a different defensive `?:` elsewhere in ZoomMathKt. Coverage still has
  1 miss in ZoomMathKt.kt, so the gate passes, and the manifest "explains" a line that no longer
  exists.
- Fix (host-testable).
  1. In `partition_report.py`, collect `<sourcefile><line nr mi ci>` and the per-class method
     line spans, then require every missed line of a residual class to lie inside its cited
     region. Report `residual line drift` otherwise.
  2. Add a `tools/coverage/tests` fixture whose miss sits outside the cited range, and assert that
     the gate fails.
  3. Regenerate the six regions from the report. Do not hand-edit them.

### TE3-2 — The AGG2-3 regression test replicates the engine wiring, so reverting the fix keeps the suite green (Medium, High) — Confirmed
- Production: `camera/CameraEngine.kt:4844` (`val continuation = StillContinuationHandoff(onDone)`),
  `:4853` (`onDone = continuation::settle`), `:4938` (`return continuation.dispatchResult(owner.start())`).
- Test: `DngPreCaptureAllocationTest.kt` `rejectedChainStep(...)` builds its own
  `StillContinuationHandoff`, sets `onRetired = continuation::settle` itself, and calls
  `dispatchResult` itself. It tests the class, not the engine's use of it.
- Revert check. Restore `onDone = onDone` at :4853, or `return owner.start() == ACCEPTED` at :4938.
  Every test still passes. The original bug was High: timelapse ticks doubled every interval
  (2^n), and AEB reset controls mid-bracket.
- Fix. Either (a) move the whole "settle wiring + dispatch result" into one internal function that
  takes `start: () -> RecordingPreNativeDispatch` and `settle: (onDone) -> Unit`, have both the
  engine and the test call it, and keep the engine a one-line delegation; or (b) add a Robolectric
  engine test that saturates `ProcessPreNativeMediaAllocator` (or shuts it down) and drives a
  timelapse/AEB chain with `dngRaw = true`. Then assert exactly one continuation per tick: count
  `onDone` runs plus `false` returns.

### TE3-3 — About ten cycle-2 fixes are guarded only by an extracted pure helper; the call site can be reverted silently (Medium, High) — Confirmed
The cycle-2 pattern was: extract a pure predicate, unit-test it, and call it from the hot path.
Only the predicate is tested. Each of these call sites can be reverted with no test failing:
- `CameraEngine.kt` `reconfigureCamera`, the two `rollbackOpticsAfterPreflight(...)` calls (b5c57e8a).
  The Robolectric test invokes `invalidateCameraReady` and `rollbackOpticsAfterPreflight` directly
  by reflection. Reverting either call to `rollbackOptics(...)` brings AGG2-4 back (shutter/REC
  dead until another door).
- `CameraEngine.kt` ~:4291 dual-open install (8f84b0bc). Passing `paused = false` (or the old
  condition) is not caught: the test is `RouteInputRollbackTest.dualOpenCandidateInstallAdmitted` only.
- `CameraEngine.kt` `is DngWriteResult.Failed ->` branch (17c162d4). Reverting to
  `reportStatus(DNG_SAVE_FAILED)` compiles (`write.failure` still exists) and passes.
- `CameraController.kt` `setPinAutoFps` `zslRingFlush()` (c91d741f), and the
  `applyMetering`/`tryComplete` → `chars()` wiring (03203d17).
- `VideoRecorder.kt` `cancelVideoStartupDeadline` (56803b42). Adding `shutdownNow()` back passes.
- `MediaStoreWriter.kt` journal `mark` loop (~:665), family-deletion mark (~:749), and the
  `publish` retry loop (~:1110) (ab862007). Only `markCompletionWithRetry` is tested. Reverting
  any of the three to `runCatching { Thread.sleep }` passes.
- `CameraViewModel.kt` `rearReturnZoom` → `retainedRearWireZoom` (ae359201). Reverting to
  `localZoomOf` passes.
- `ProSheet.kt:898` `tenBitVideoWanted = tenBitSessionWanted(...)` (ba3ee153).
- Suggested fix, cheapest first:
  - (1) For engine/VM wiring, reuse the existing Robolectric harnesses
    (`OpticsRouteInputTransactionRobolectricTest`, `FacingRollbackPunchInRobolectricTest`,
    `ModeRollbackOwnershipRobolectricTest`) and drive the real entry point. For example, start the
    engine with a null selection so `reconfigureCamera`'s preflight fails, then assert
    `cameraReady`.
  - (2) For framework-bound sites (GL, MediaCodec, Camera2 handler), add a source-contract test in
    the style of the existing executable source inventory. It asserts the exact call appears
    inside the named function. This is weak but catches a revert.
  - (3) For the MediaStoreWriter loops, inject `backoff` like `markCompletionWithRetry` already
    does, and test each loop with an interrupted thread.

### TE3-4 — New owner parameters default to the "fix off" value, so dropping an argument is silent (Low-Medium, High) — Confirmed
- `ui/ZoomMath.kt:252-253` `currentStandalone: Boolean = false, targetStandalone: Boolean = false`
  (d9ed066d). The single production caller (`CameraViewModel.kt:1389-1407`) must pass both.
  Omitting them restores AGG2-13 exactly: a Photo/DNG recall clamps the exposure against the
  outgoing logical caps. `ZoomMathTest` passes either way.
- `camera/ManualControls.kt` `withIsoTakingOwnership / withShutterNsTakingOwnership /
  withShutterAngleTakingOwnership(…, liveIso: Int? = null, liveExposureNs: Long? = null)`
  (15e5f3cb). `CameraViewModel.onIso/onShutterNs/onShutterAngle` (~:2041-2090) must pass
  `live.liveIso/liveExposureNs`. Calling `it.withIsoTakingOwnership(iso)` restores AGG2-17 (MANUAL
  shoots at a stale 1/125 s, ~2 stops dark), and no test notices. This also extends carried TE2-4:
  no test calls `vm.onIso`, `vm.onShutterNs`, `vm.onShutterAngle`, `vm.onExposureMode` or
  `vm.onShutterMode`. The only hits are the `PerformQuickFnTest` stub.
- Fix. Remove the defaults so the compiler enforces the wiring. That costs ~6 test call-site edits.
  Add one Robolectric VM test: in HAL-AE video P with `liveIso = 1600, liveExposureNs = 1/30 s`,
  call `onIso(1600)` and assert `exposureTimeNs == 1/30 s`.

### TE3-5 — The ViewModel DNG door's PMA110 path is never executed by any test (Medium, High) — Confirmed
- Robolectric constructs `CameraEngine` with the GENERIC `DeviceProfile` (the c6caab59 test relies
  on this: `assertFalse(e.rawForcesStandalone)`). `engine.rawForcesStandalone` is therefore false
  in every VM test. `onSetPhotoFormats`'s remap branch (`CameraViewModel.kt` ~:2463-2500) is never
  taken, and the only VM DNG test is the negative one. That branch does the remap to TELE3X
  lens-local 1.0, `cancelPendingControls`, the tap-focus clear, and the timelapse stop.
- Also unexecuted: the AGG2-8 pre-inventory merge in the VM (`request = (pending ?: s.photoFormats).withEdit(...)`, `pendingPhotoFormatsUntilInventory = request`).
  Only `withEdit` itself is tested. Reverting the VM to store the normalized `formats` passes.
- TE2-1 item 3 is therefore still open for the device the app ships on.
- Fix. `ShadowBuild.setModel("PMA110")` before `createViewModel()`, which is how
  `OpticsRouteInputTransactionRobolectricTest.acceptedPma110Engine` already does it. Then:
  - (a) Photo at unified 3×, `onSetPhotoFormats(dngRaw = true)` → VM and engine both on TELE3X
    local 1.0, and the pending-controls throttle is cancelled.
  - (b) `encoderInventoryLoaded = false`, saved request HEIF+DNG, a DNG-off tap →
    `pendingPhotoFormatsUntilInventory == HEIF`, not JPEG.
  - (c) `timelapseRunning = true` plus a format change → timelapse stopped.

### TE3-6 — The shared `rawChars` retry gate lets a metering rebuild consume the shot's retry; a shot that would have recovered now fails (Low-Medium, Medium) — Likely
- `camera/CameraController.kt` `chars()` (~:2420), `applyMetering` (~:1312), `tryComplete` (~:2269),
  `LazyReadRetryGate(RAW_CHARS_RETRY_INTERVAL_NS = 1 s)`.
- Before 03203d17, `tryComplete` retried `readRawCharacteristics()` on every shot. Now both callers
  share one gate. Any full request rebuild with AE/AF regions (a tap-AF, a control change, a
  rebuild after a reopen) spends the single admission. A still completing within the next second
  then gets `null` and fails with `onError("Missing camera characteristics")`, even if the
  provider read would now succeed. The plan's intent was to rate-limit a *persistently* failing
  read. A *transient* failure (the resume race the original comment describes) now costs a shot
  that the old code saved.
- Failure scenario. The open-time read fails (keyguard resume race). The preview build at t = 0
  retries and fails. The race clears at t = 0.3 s. The user taps the shutter at t = 0.5 s, and
  `tryComplete` is refused by the gate, so the HAL image is discarded and the toast says "Photo
  save failed". Before the fix, this shot would have saved.
- Fix. Give `tryComplete` its own admission: always allow one retry per pending shot, or use a
  separate gate. Keep the metering path rate-limited. Host test: a fake `nowNs` and a counting
  reader; metering at t = 0 fails, a shot at t = 0.5 s must still attempt a read. (`LazyReadRetryGateTest`
  only covers the gate in isolation.) Needs-device only for the transient-race frequency.

### TE3-7 — `PhotoFormats.withEdit` makes a pre-inventory "JPEG off" tap a silent no-op when the request is HEIF (Low, High) — Confirmed (logic)
- `camera/CameraState.kt` `withEdit` (~:1256); `ProControls.kt` `jpegEnabled = processedAvailable && (!formats.jpeg || formats.heif || rawSelected)`.
- Trace:
  - Request `{heif, dng}`. Before the inventory, the display shows the degraded `{jpeg, dng}`.
    JPEG is enabled because DNG is selected.
  - The user turns JPEG off, so `edited = {dng}`.
  - `withEdit` sees only the jpeg axis change. heif keeps the request value (true), so the result
    is `{heif, dng}`, which is the unchanged request.
  - Normalization re-displays `{jpeg, dng}`. The tap did nothing, the persisted request still
    writes a processed still, and the user wanted DNG only.
- The AGG2-8 test covers DNG toggles and processed-axis additions, but not removal of the
  displayed substitute.
- Fix. Treat the displayed JPEG as standing in for the requested HEIF while HEIF is unknown. When
  `edited` clears the processed axis that `displayed` showed, clear the request's processed axes
  too. Add the case to `TransferEncoderHonestyTest.format edit merges only the changed axis`.

### TE3-8 — `MediaStoreWriter.cleanupOrphanedPending` has no caller, yet f4ecda3d edited its loop (Low, High) — Confirmed
- `storage/MediaStoreWriter.kt:1246-1280`. `grep -rn "cleanupOrphanedPending(" app/src` finds only
  the definition. Production uses `executeLaunchMediaRecovery` (the coordinator).
- The dead loop has different semantics: "no retry budget, exhausted at once". f4ecda3d changed it
  anyway, so two loops encode the AGG2-21 rule and only one is tested and used. It also inflates
  MediaStoreWriter's uncovered-line count.
- Fix. Delete it, or route one test through it if it is meant as a public fallback.

### TE3-9 — The AGG2-15 test covers half the guards and not the detach-before-purge order (Low, High) — Confirmed
- `CameraViewModelTickersRobolectricTest` `self-reposting tickers stop once the ViewModel is cleared`
  runs `recordTicker`, `orientationTicker`, and `infoTicker` only. Removing the new `cleared` guard
  from `zoomEaseTicker`, `levelTicker`, or the countdown `tick` passes.
- The root-cause change was moving `engine.detachCallbacks()` before
  `mainHandler.removeCallbacksAndMessages(null)` (`CameraViewModel.kt` ~:4217). No test checks it.
  Moving it back passes.
- The class's `tearDown` calls `onCleared` again by reflection inside `runCatching`, so this test
  double-clears the VM. The second clear hands the engine to another release thread, and the
  `runCatching` hides any failure.
- Fix. Add the three remaining tickers (for `levelTicker`, set `state.level = true`). For order,
  install a fake engine callback that posts to main from inside `detachCallbacks`'s predecessor
  window, or assert via a recording `Engine` seam that `detachCallbacks` is invoked before the
  handler is empty. In `tearDown`, skip `onCleared` when `cleared` is already true.

### TE3-10 — Carried, with new evidence: refusal doors are still hand-written per door, and the aspect refusal is still untested (Low-Medium, High) — Confirmed
- f6f25a5b added `rejectIfRecording()` to `onCameraOverride` with a test, after AGG2-12 found that
  door missing. That is the same defect class TE2-5 predicted.
- There are 17 `rejectIfRecording` sites in `CameraViewModel.kt`, and `onAspect…` (81a0b55a) still
  has no test.
- A table-driven Robolectric test over every session-reconfiguring `CameraActions` member would
  catch the next missing door. Those members are: transfer, hi-res, aspect, codec, bitrate,
  resolution, frame rate, open gate, stab, front, recall, and camera override. With
  `setRecordingPresentation(recording = true)`, assert that state is unchanged and
  `status == STOP_RECORDING_FIRST`.

### TE3-11 — The remaining vacuous relational check in `DiagnosticLogTest` (Low, Medium) — Confirmed
- `camera/DiagnosticLogTest.kt:79-96` `production binding never exceeds the process ceilings` asserts
  `usedRows() <= BUDGET` and `getLogsForTag(tag).size <= 2`. Both pass with zero rows, so the
  production facade could stop logging entirely. TE2-2 fixed the same pattern in
  `StorageFailureDiagnosticsTest` (4a1c6507).
- The test labels itself "secondary", and the injected-door tests above it are exact. Keep it, but
  add one exact production-binding assertion that is budget-independent. For example, assert that
  `DiagnosticLog`'s doors are the process owners (identity), which pins the binding without
  depending on remaining budget.

## Flakiness sweep (no new defects)
- `Thread.sleep` appears in tests only inside deadline-bounded polling loops
  (`StandbyAudioControllerTest.kt:1575`, 2 s; `FamilyDeletionMarkerIntegrationRobolectricTest.kt:181`, 5 s).
- `findFallbackThread()` matches any live thread by name process-wide. A thread leaked by an earlier
  test with the same name would satisfy it. This is not observed and is noted only.
- New real-executor tests (`StartupDeadlineRetireTest`) use latches with 5 s waits and
  `@Test(timeout)`, which is fine.
- `LaunchRecoveryProgressTest` counts are deterministic (`imageQueries == 2` with
  `maxFailureAttempts = 2`).
- `MediaDurabilityPolicyTest` interrupt test clears the flag in `finally`, which is fine.
- `StorageFailureDiagnosticsTest` uses fresh budgets plus UUID URIs against the process-global
  `storageWarningGate`, so it is order-independent.

## Files examined
- Production (cycle-2 diffs and their surroundings):
  - `camera/CameraEngine.kt` (DNG dispatch :4760-4945, rollback :1000-1110, reconfigure :4160-4230, dual-open :4290)
  - `camera/DngPreCaptureAllocation.kt`
  - `camera/CameraController.kt` (applyMetering, tryComplete, chars, setPinAutoFps, LazyReadRetryGate, classifier :2640-2720)
  - `camera/ManualControls.kt`
  - `camera/AutoExposure.kt`
  - `camera/StandbyAudioController.kt`
  - `camera/DiagnosticTelemetry.kt`
  - `camera/LaunchMediaRecoveryCoordinator.kt`
  - `camera/CameraState.kt` (withEdit, lensInventoryOf)
  - `capture/StillCapturePipeline.kt`
  - `storage/MediaStoreWriter.kt` (open/publish/retry loops, recovery batch, HeifBoundedReader)
  - `storage/PendingDiscardJournal.kt` (IdentityReadWarningGate)
  - `storage/SettingsStore.kt`
  - `video/VideoRecorder.kt` (startup deadline, storage tail)
  - `gl/GlPipeline.kt` (analysis catch)
  - `ui/CameraViewModel.kt` (tickers, onCleared, recall, DNG door, dial doors, rollback mirror, currentExtras)
  - `ui/ZoomMath.kt`
  - `ui/controls/ProControls.kt`
  - `ui/controls/ProSheet.kt`
  - `ui/controls/FnQuickActions.kt`
  - `ui/CaptureOutputTracker.kt`
  - `storage/LatestCaptureReducer.kt`
  - `storage/CaptureFamily.kt`
  - `CameraPermissionPolicy.kt`
  - `MainActivity.kt` (recall)
- Tests:
  - `OpticsRouteInputTransactionRobolectricTest`
  - `RouteInputRollbackTest`
  - `DngPreCaptureAllocationTest`
  - `RetainedDngStatusTest`
  - `ImmediateDiscardIdentityTest`
  - `ZoomMathTest`
  - `CameraViewModelRobolectricTest`
  - `ModeRollbackOwnershipRobolectricTest`
  - `OpticsRecallTransactionRobolectricTest`
  - `CameraViewModelTickersRobolectricTest`
  - `ExposureModeHandoffTest`
  - `MicrophoneGrantRestoreTest`
  - `AutoExposureTest`
  - `FinalizedVideoTrackProbeTest`
  - `LaunchRecoveryProgressTest`
  - `ZslStreamingEdgeTest`
  - `LazyReadRetryGateTest`
  - `AnalysisFailureLogGateTest`
  - `StandbyAudioControllerTest`
  - `StartupDeadlineRetireTest`
  - `SettingsStoreTest`
  - `StorageFailureDiagnosticsTest`
  - `MediaDurabilityPolicyTest`
  - `DiagnosticLogTest`
  - `NoStillOutputCaptionTest`
  - `DeviceRouteLawsTest` (`TransferEncoderHonestyTest`)
  - `PerformQuickFnTest`
  - `FamilyDeletionMarkerIntegrationRobolectricTest` (sleep loop)
  - repo-wide greps for `Thread.sleep`, `ShadowLog`, `onSetPhotoFormats`, `onIso` / `onExposureMode`, and `cleanupOrphanedPending(`
- Gate:
  - `tools/coverage/partition-a-residuals.txt`
  - `tools/coverage/partition_report.py`
  - `app/build/reports/coverage/test/debug/report.xml` (HEAD-era)
- Context:
  - `.context/reviews/archive-rpl-cycle2-2026-10-02/_aggregate.md`
  - `.context/reviews/archive-rpl-cycle2-2026-10-02/test-engineer.md`
  - `docs/plans/2026-10-02-rpl-cycle2.md`
