# Test-engineer review — RPL cycle 2 (HEAD e5729ffd, 2026-10-02)

Scope: `app/src/test/**` (257 files, 2,313 `@Test`), `app/src/androidTest/**` (4 files, compiled
only), `tools/tests/**` (10 suites), `tools/coverage/tests/**`, `device-tests/tests/**` (5 suites),
and every commit in `ba5b16e7..HEAD`. Read-only. One targeted Gradle run
(`ExposureModeHandoffTest`, `ZoomMathTest`, `StorageFailureDiagnosticsTest`, `SettingsStoreTest`,
`PendingTokenNativeStartTest`, `FacingRollbackPunchInRobolectricTest`) exited 0. Its result XMLs were
overwritten by other lanes running in the shared `app/build`, so the exit code is the only evidence
from that run. Every other finding comes from reading the code.

## Cycle-1 fix → regression-test audit

The question for each commit: is there a test that would fail if the fix were reverted?

| Commit | Fix | Fails without fix? |
|---|---|---|
| 89bb7aab | DNG restore on lens-local scale (`restoredOptics`, `remapRouteScaleOptics`) | Yes for the pure helpers (`ZoomMathTest`). The VM `applyLoaded` wiring is not tested. |
| 99e7af87 | DNG toggle as an optics door (engine + VM) | **No.** See TE2-1. |
| 3ec126e1 | Rollback keeps a later route-neutral DNG choice | **No.** See TE2-1. |
| 40145fbf | Single `setRawWanted` call (refactor) | n/a, but the call is untested (TE2-1). |
| 1e79810e | Exposure handoffs | Yes for the pure helpers. VM wiring and the `applyLoaded` ISO→SPEED force: **no** (TE2-4). |
| 7c76e7bc | Phone seed fallback / pending-inventory extras | Store half yes. VM `currentExtras` half **no** (TE2-6). |
| 81a0b55a | Aspect refused while recording | **No** (TE2-5). |
| 87932ced | Loupe hit-test clearance | Yes (`FinderGeometryTest`). Without the parameter, the call with `bottomClearance` would not compile, and the old rect contains `drawnBottom + 2`. |
| d255afbc | nativelog kept out of the Camera2 session | **No** (TE2-11). Debug-only. |
| 079b665e | Ladder pinned after the 10-bit rung | Yes (`SessionFallbackLadderTest`). |
| 8780b494 | Persisted exposure/fps/Kelvin bounds | Partly: only the lower bounds of exposure and fps and the upper bound of Kelvin are tested. The sibling `photoExposureTimeNs` key is still unbounded (TE2-7). |
| b476d1dd | Persist the requested video size | `onVideoResolution` path yes. Recall and rollback mirrors **no** (TE2-6). |
| fe7d0578 | Recall clears the audio-denial reason | **No** (TE2-8). |
| e72fcf8c / de3ff566 | Required owner/param | Compile-time enforced; no test needed. |
| 4e57fff2 | Recorder workers admitted by token | The gate is tested. The production wiring that makes workers use the token is not (TE2-10). |
| 38d99950 | Drain idiom / `wroteAudioSample` | No host test (plan admits it: MediaCodec-bound). |
| 1edb68c6 | Tri-state finalized-video probe | Yes (`FinalizedVideoTrackProbeTest`, RecorderQuarantine disposition). One pinned verdict is questionable (TE2-12). |
| a1fee383 | Recovery advances past an exhausted row | Yes (`LaunchRecoveryProgressTest`). |
| fc8a458c | Deleted prior family no longer blocks restores | Yes (`CaptureOutputTrackerTest`). |
| 1c5a0060 | Lazy characteristics retry | **No** (TE2-9; the plan admits it). |
| d062927a | HEIF saves without EXIF on payload failure | Only the 10-line wrapper is tested, not the call site (TE2-11). |
| 98164b9e | Change-gated identity warnings | Yes, with an injected gate and an injected sink. |
| f41b1ae3 | One reserved row per failure edge | Storage half only, and conditionally vacuous (TE2-2). The VideoRecorder/CameraEngine rows are untested. |
| 7dd6a6b2 / 8c3b09bd / af74382c | Tooling | Yes (`test_host_preflight`, `test_upload_key_gate`, `test_tool_contracts` via cd4f24b9). |

## Findings

### TE2-1 — The DNG optics door (99e7af87, 3ec126e1, 40145fbf) has no engine or ViewModel test (High-Medium, gap) — confirmed
- `camera/CameraEngine.kt` `setRawWanted(enabled, resolvedLens, resolvedControls)` (~:3990-4045) has
  three branches:
  - direct write, with the `rawWantedDirectWrites++` counter;
  - paused: drop `overrideId` to `userCameraPin`;
  - transaction: publish inside `beginOpticsTransaction`, then `pushTeleFinder`.
- `rollbackOptics` (~:1011) has `if (rawWantedDirectWrites == before.rawWantedDirectWrites) rawWanted = before.rawWanted` and publishes `rawWanted`.
- `lensBandFollowsZoom` replaced `!video` / `!enabled` at two fast-path commit sites (~:2679, ~:2834).
- `ui/CameraViewModel.kt` has the rollback mirror `photoFormats = … dngRaw = rollback.rawWanted`
  (~:962) and `onSetPhotoFormats` (~:2389-2440). The latter does the remap, `cancelPendingControls`,
  `invalidateOptics…`, `clearTapFocusUi`, and stops a running timelapse.
- `grep -rln "setRawWanted\|onSetPhotoFormats\|OpticsRollbackPublication(" app/src/test` finds only
  the pure `standaloneRouteWanted` tests and a no-op `PerformQuickFn` stub. No test builds an
  `OpticsRollbackPublication` or calls the DNG door. Reverting **any** line of 3ec126e1 keeps the
  suite green. The same holds for the paused `overrideId` drop (CLAUDE.md DNG bug #2, "the divergence
  was PERMANENT") and for the rollback restore of `rawWanted` (AGG-4).
- **Failure scenario (3ec126e1).** A FRONT entry transaction is in flight. The operator turns DNG on,
  which is a direct write because the route is not BACK. The FRONT open fails and rolls back. Without
  the counter, the rollback writes `rawWanted = false` and publishes it. The VM mirror turns the
  chip off and persists it. Nothing fails.
- **Proposed tests.** The harnesses already exist:
  1. `FacingRollbackPunchInRobolectricTest` style: `acceptedRoute(BACK)`, `setFrontCamera(true)`
     (baseline captured), `setRawWanted(true)` (direct write), `forceOwnedRollback`, then assert the
     `rawWanted` field is still `true`. Repeat without the intervening write and assert that rollback
     restores `false`.
  2. Same engine with `started=true, paused=true, overrideId="0"`, `userCameraPin=null`, then
     `setRawWanted(true)`. Assert `overrideId == null` and `lensChoice`/`controls` equal the resolved
     packet.
  3. `ModeRollbackOwnershipRobolectricTest.createAccepted(PHOTO)`: `vm.onSetPhotoFormats(dngRaw=true)`
     at unified 3×. Assert the VM and the engine both hold `TELE3X` with lens-local `1.0`, and that
     the pending-controls throttle was cancelled. Then force an owned rollback and assert the VM's
     `photoFormats.dngRaw == false` and that the lens/zoom are restored.
  4. A VM test with `timelapseRunning=true` and a format change that asserts `engine.stopTimelapse`
     ran (state `timelapseRunning` false after the callback).

### TE2-2 — `StorageFailureDiagnosticsTest` re-creates the vacuous/racy pattern that TE-3 just removed (Medium, weak and flaky) — confirmed
- `app/src/test/kotlin/me/hletrd/telecampro/storage/StorageFailureDiagnosticsTest.kt:36-47,49-68,72-73`.
- **Problem.** The assertions are `assertTrue(rows.size <= 2)` plus `if (budgetOpen) { assertEquals(2, …) }`,
  where `budgetOpen` is read once, before the calls, from the process-global
  `processReservedDiagnosticLogBudget`. If the shared Robolectric sandbox has already spent the
  reserved 120 rows, both tests pass with zero rows. They would then pass even if
  `openParcelFd`/`publish` never logged. This is the exact vacuity that 822f5a33 fixed in
  `DiagnosticLogTest`.
- **Race.** If the budget has 2 or 3 rows left at the check, a daemon retry thread from an earlier
  class (identity-recovery backoff, the rejected-output owner) can spend one between the check and
  the calls. `assertEquals(2, rows.size)` then fails spuriously.
- **Fix.** Give `MediaStoreWriter` an internal `DiagnosticLogDoors` seam (default `DiagnosticLog`'s
  process doors), the way `MediaStorePendingDiscardIdentityReader` takes `warn`. Bind fresh budgets
  in the test, assert exact counts unconditionally, and delete `reservedBudgetOpen()`.

### TE2-3 — The Partition-A residual manifest cites stale line ranges, and the gate never checks them (Medium, gate weakness) — confirmed
- `tools/coverage/partition-a-residuals.txt:4-5` and `tools/coverage/partition_report.py:131-149`.
- **Drift.**
  - `CameraStateKt … CameraState.kt:804 "minByOrNull … continue fallback"`. Line 804 is now a
    parameter line of `finderContainsTopLeftPoint`, after 87932ced. The real fallback is
    `CameraState.kt:904-906`.
  - `CameraControllerKt … CameraController.kt:2354-2360 "CameraAccessException construction"`.
    Those lines are now the `dispatchCameraTeardown` closures, after 1c5a0060 added lines. The
    classifier sits around `:2600-2656`.
- **Gate weakness.** `Residuals.drift` compares class name, missed **count**, and source **file
  name** only. Suppose a reviewed residual becomes covered while a new, unreviewed line in the same
  class goes uncovered. The count is unchanged and the gate passes. The new miss is then silently
  "accepted" under a rationale written for different code. The closed-rationale design ("every
  current … required exact review manifest") assumes line identity it never verifies.
- **Fix.** JaCoCo XML carries `<sourcefile><line nr mi ci>`. Have `partition_report.py` resolve each
  residual's missed line numbers and require them to fall inside the cited region. Add a
  `tools/coverage/tests/test_partition_report.py` case with a fixture whose miss moves outside the
  cited range, and assert drift is reported. Then correct the two stale regions.

### TE2-4 — Exposure-handoff ViewModel wiring is untested; only the pure helpers are (Medium, gap) — confirmed
- `ui/CameraViewModel.kt:2023-2033` (`onExposureMode`, which captures `outgoingHalAe` before the
  update), `:2043-2049` (`onShutterMode` → `withShutterModeTakingOwnership`), `:1806-1828`
  (`refreshProgramAppSide`, which is the **only** place PROGRAM entry converts ANGLE→SPEED, despite
  the plan's P2.2 text "(ISO, PROGRAM)"), and the `applyLoaded` ISO-priority SPEED force from P2.1.
- **Problem.** `ExposureModeHandoffTest` covers `exposureModeHandoff` and
  `withShutterModeTakingOwnership`. No test calls `vm.onShutterMode` / `vm.onExposureMode` (grep).
- **Regression shapes the suite would miss.**
  - Reading `live.controls.autoExposure` *after* `updateControls` makes the seed always
    app-side, which is AGG-10 again.
  - `onShutterMode` reverting to bare `withShutterMode`, which is AGG-8 again.
  - A future change making `refreshProgramAppSide` early-return when `programAppSide` is already
    true. A P entry from M+ANGLE would then keep ANGLE under the app-side loop, so AE freezes.
- **Proposed test.** A Robolectric VM test in Photo:
  - M + ANGLE → `onExposureMode(PROGRAM)`: assert SPEED and `programAppSide`.
  - ISO priority → `onShutterMode(ANGLE)`: assert MANUAL + ANGLE.
  - HAL-P with `liveIso`/`liveExposureNs` set → `onExposureMode(MANUAL)`: assert it is seeded from
    live.
  - App-side P → MANUAL: assert it keeps its own values.
  - `applyLoaded` of an ISO+ANGLE packet: assert SPEED.

### TE2-5 — The aspect mid-REC refusal (81a0b55a) has no test, and nothing enumerates the session-reconfiguring doors (Low-Medium, gap) — confirmed
- `ui/CameraViewModel.kt:2486-2490`. `setRecordingPresentation(v, recording = true, …)` already
  exists in `CameraViewModelRobolectricTest.kt:128`, so the test is about five lines: assert
  `aspectRatio` unchanged and `status == STOP_RECORDING_FIRST`.
- **Broader gap.** Each door's refusal is hand-written. A table-driven test over every
  session-reconfiguring action would catch the next door added without `rejectIfRecording()`. The
  list is: transfer, hi-res, aspect, codec, bitrate, resolution, frame rate, open gate, stab, front,
  and recall. That is the exact defect class AGG-66 was.

### TE2-6 — Requested-video-size and pending-inventory persistence: only one of three paths is tested (Low-Medium, gap) — confirmed
- **b476d1dd.** `requestedVideoResolution` is written by:
  - `onVideoResolution` (tested, `CameraViewModelRobolectricTest.kt:1050`);
  - recall `restoredVideoSize?.let { … }` (~:1444), untested;
  - optics rollback `requestedVideoResolution = rollback.requestedVideoSize` (~:949), untested;
    no test constructs an `OpticsRollbackPublication`.
- **Scenario.** Dropping the rollback assignment makes a failed Open Gate reopen persist the
  pre-rollback request, which is the AGG-36 symptom on the rollback path.
- **7c76e7bc.** The VM half has no test:
  - `currentExtras()` (~:1574-1611) saves `pending*UntilInventory` while
    `!encoderInventoryLoaded`;
  - the `SettingsStore(app) { detectedPhone ?: PhoneModel.OTHER }` wiring.
- **Proposed tests.**
  1. VM before inventory: restored HEIF+HLG+HEVC, trigger a save (`onGridType`), and assert the
     saved extras keep HEIF/HLG/HEVC rather than the JPEG/SDR placeholders.
  2. Rollback with a `requestedVideoSize` differing from state, then save, and assert the persisted
     size.

### TE2-7 — 8780b494's load-time bound skips the sibling `photoExposureTimeNs` key and `wbTint` (Low-Medium, incomplete fix plus missing test) — likely
- `storage/SettingsStore.kt:303-304`: `photoExposureTimeNs = safeLong(…).coerceAtLeast(1L)` has no
  upper bound. `:261`: `wbTint` is restored raw.
- `ui/ZoomMath.kt:285`: `restoredExposureState` passes the stored Photo exposure through with
  `coerceAtLeast(1L)` only, whenever the target mode is VIDEO.
- **Scenario.** A corrupt blob is saved in VIDEO with `photoExposureTimeNs = Long.MAX_VALUE`. Photo
  exposure is retained untouched until a Video→Photo flip makes it the active exposure. That value
  then reaches `withShutterMode` / `previewExposureTrade` / watchdog arithmetic, which is the same
  path AGG-33 bounded for `exposureTimeNs`. A caps clamp lands later, and on a route without
  manual sensor it never lands (the commit message's own argument).
- **Test gap.** `corruptExposureFpsAndKelvinAreBoundedAtLoad` covers exposure-low, fps-low and
  Kelvin-high only. Extend it to both ends of each bound, plus `photoExposureTimeNs` and `wbTint`
  with `MIN/MAX_PERSISTED_*`.

### TE2-8 — Recall clearing `AUDIO_OFF_BY_DENIAL_KEY` (fe7d0578) lives in the Activity with no test (Low-Medium, gap) — confirmed
- `MainActivity.kt:517-529`. The decision is "applied recall ⇔ `activeMemorySlot == slot` after
  `vm.onRecallMemorySlot`". It sits in Partition-B Activity code. Nothing pins two things:
  - a refused recall (REC active, empty slot) leaves the denial flag set;
  - an applied one clears it.
- **Fix.** Move the decision into `CameraPermissionPolicy.kt` beside
  `audioRestoredByMicrophoneGrant`, as a pure `denialReasonAfterRecall(applied: Boolean, current: Boolean)`,
  and table-test it. Alternatively, add an `ActivityScenario` Robolectric test that asserts the
  preference after recall into an empty slot, while recording, and into a saved slot.

### TE2-9 — Lazy characteristics retry (1c5a0060) is untested (Low, gap) — confirmed (acknowledged in the plan)
- `camera/CameraController.kt` `readRawCharacteristics()` and
  `rawChars ?: readRawCharacteristics()?.also { rawChars = it }` in `tryComplete`.
- **Fix.** Extract a tiny pure `RetryingValue<T>(read, onFirstFailure)`, which returns the cached
  value, retries on null, and logs once. Test four cases: first-read failure, recovery on the second
  read, a cached value never re-read, and the failure logged exactly once across N failures. This
  takes the "every still fails 'Missing camera characteristics'" class out of device-only territory.

### TE2-10 — The TB336ZU silent-clip guard now depends on untested wiring (Low-Medium, gap) — confirmed
- `video/VideoRecorder.kt:90` (`processAdmissionToken: …? = null`), `:787` and `:959` (worker doors),
  `:1711-1715` (a token-less fallback to the **anonymous** owner gate), and `camera/CameraEngine.kt:6235`
  (the only assignment).
- **Problem.** `PendingTokenNativeStartTest` proves the gate admits a token-holder. If the Engine
  assignment were dropped, or a new recorder construction site omitted it, the audio worker would
  silently take the anonymous door. That door is refused while the token is pending, which is
  exactly the 2026-09-09 silent-clip defect, and every host test would stay green.
- **Fix.** Either make the token a required constructor parameter (`VideoRecorder(context, token)`),
  so the compiler enforces it, or assert in `CameraEngineRecordingPreNativeTest` that the
  recorder the Engine builds carries the admission token it snapshotted.

### TE2-11 — Wrapper-only and absent tests for two small fixes (Low, gap) — confirmed
- **d062927a.** `HeifExifTest` tests `bestEffortHeifExif` in isolation. Nothing proves
  `writeProcessedHeif` (`capture/StillCapturePipeline.kt:250-260`) calls it, so an inlined revert to
  a bare `buildHeifExifData(...)` passes. Suggest a `StillCapturePipeline` seam for the EXIF builder,
  plus one test where it throws and the HEIF is still allocated, written and published with
  `exifData == null`.
- **d255afbc.** `tenBitHlg = tenBitSessionWanted(videoMode, transfer)` at `CameraEngine.kt:2421,4238`
  has no test. It is debug-only, so this is low priority. A pure `sessionTenBitHlg(video, transfer, experiment)`
  returning false for PHOTO+experiment would pin it.

### TE2-12 — The tri-state probe test pins the destructive verdict for a post-open I/O exception (Low-Medium, test pins questionable behavior) — likely
- `storage/MediaStoreWriter.kt` `classifyFinalizedVideoTrack` returns INVALID for **any** exception
  thrown by `hasVideoTrack`. `FinalizedVideoTrackProbeTest` ("an unparseable container after a
  successful open is invalid") pins `IOException("setDataSource failed")` → INVALID.
- **Why it matters.** An I/O error raised while reading through a just-opened provider fd is the same
  transient-provider class that 1edb68c6 made INDETERMINATE for open failures. Examples are a
  revoked fd during a media scan or a Binder hiccup. Launch recovery does not delete on an
  indeterminate read. Here an INVALID verdict deletes a good take on the live stop tail.
- **Fix.** Separate "the extractor parsed and found no video track" (INVALID) from "an I/O-class
  exception escaped" (INDETERMINATE). Change the pinning test accordingly. Owner/code-reviewer
  call; flagged here because the test currently locks the destructive branch in.

### TE2-13 — The suite-level backstop does not name the wedged test; untimed waits remain (Low, residual of TE-5) — confirmed
- `app/build.gradle.kts` (019ca41c) sets a 30-minute task timeout, which fails the **task** without
  naming a test.
- `grep` finds 22 bare `.join()` and 73 bare `.await()` in `app/src/test`. Examples:
  - `gl/CompletionDispatchTest.kt:687` still `second.join()`s before `finish.countDown()`, the
    exact deadlock that test exists to catch;
  - the same file at `:506-507` and `:651-653`.
- **Fix.** Add `@get:Rule val timeout: Timeout = Timeout.seconds(30)` to the ownership/concurrency
  classes (CompletionDispatch, LatestHeavyWorkLane, CameraEngineRecordingPreNative,
  RecordingTeardownTerminalGate, RecorderQuarantineAdmissionGate, MediaReviewOwnership). Also
  replace `join()` with `join(5_000); assertFalse(t.isAlive)`.

### TE2-14 — Remaining short positive wall-clock waits (Low, flaky under load) — likely
- `storage/RejectedOutputCleanupDispatcherTest.kt:30`: `assertTrue(returned.get(250, MILLISECONDS))`.
  It proves "submit does not block" with a 250 ms wall-clock budget on a freshly created executor
  thread, which is the TE-2 pattern that e1a45dfa removed elsewhere.
- `:73` `assertTrue(completed.await(100, ms))` and `camera/DngPreCaptureAllocationTest.kt:180`
  (`retired.await(250, ms)`) are fine only if completion is synchronous on that path.
- **Fix.** Where completion is synchronous, assert `latch.count == 0` immediately. Where it is not,
  use a ≥5 s positive bound plus a structural "provider still parked" witness, as e1a45dfa did.

## Re-validation of AGG-85..AGG-92 (scheduled "later cycle")

| ID | Orig | Current state at e5729ffd |
|---|---|---|
| AGG-85 | TE-7 | **Partially addressed.** f41b1ae3 added open/publish rows, tested only conditionally (TE2-2). Still untested: pending image/video insert throws or returns null (`MediaStoreWriter.kt:446-450,568-572`), identity-owner-at-capacity refusals (`:477,:552`), and registration-disposition rows (`:502-507`). Open. |
| AGG-86 | TE-8 | **Open.** `tools/coverage/partition-b.txt:33-34` still surrenders `ManualControlsKt` and `CaptureCapabilitiesKt`. 1e79810e added two more pure functions (`exposureModeHandoff`, `withShutterModeTakingOwnership`) to `ManualControlsKt`, so new pure branches land outside the 99.5% gate. The surface grew. |
| AGG-87 | TE-9 | **Open.** `gl/DigitalGainTest.kt:61-77` still recomputes the production formula with the same constant. No literal golden anchors. |
| AGG-88 | TE-10 | **Open.** `camera/StartupTraceTest.kt:39-43` still restores `android.util.Log.i` rather than the captured production seam. `:96-104` is still tautological under a +5 clock. |
| AGG-89 | TE-11 | **Open.** No `@After` idle assertion for `UnsafeRecorderQuarantine` / `ProcessDngPreCaptureAdmission` in `CameraEngineRecordingPreNativeTest.kt:39-44`, `DngPreCaptureAllocationTest`, or `ProcessStillAdmissionEngineTest`. |
| AGG-90 | TE-12 | **Open.** Negative waits unchanged: `CameraEngineRecordingPreNativeTest.kt:636,678,707,746,789,875`, `ProcessAdmissionSignalTest.kt:51`, `TerminalAcquisitionGateTest.kt:70`, `ReconfigurationGenerationTest.kt:839`, `FamilyDeletionMarkerDispatcherTest.kt:221`, `StatusPublicationOwnershipTest.kt:45`, `RejectedOutputCleanupDispatcherTest.kt:33`. |
| AGG-91 | TE-13 | **Open.** `tools/field/tap_af_aim.py:130` still takes `before = latest_3a(...)` with no `logcat -c` before it (the clear is only at `:170`). There is still no `tools/tests/test_field_tap_af_aim.py`. |
| AGG-92 | TE-14 | **Open.** `tools/tests/test_release_artifact.py` still has no `<uses-permission-sdk-23>` case and no `<permission>` without `protectionLevel` for `tools/release_permissions.py:27-44`. |

Cycle-1 items TE-1..TE-6 (AGG-79..84) are verified closed:
- a887b294 gives the retry its own lane at the production timeout.
- e1a45dfa makes the non-blocking proof structural.
- 822f5a33 runs `DiagnosticLogTest` against fresh budgets.
- 8f77f219 adds snapshot-relative edges.
- 019ca41c adds the suite timeout; the TE2-13 residual remains.
- 5076aedf adds `ColorProfilesFormatTest`, and `ColorProfiles` moved to Partition A.

## Final sweep notes (no finding)
- No `@Ignore` or `Assume` anywhere in `app/src/test`.
- Polling loops all have explicit 1-5 s deadlines followed by an assertion.
- `androidTest` (4 probes) is still compile-only in the gate, as documented.

## Count
14 findings:

| Severity | Findings |
|---|---|
| High-Medium | TE2-1 |
| Medium | TE2-2, TE2-3, TE2-4 |
| Low-Medium | TE2-5, TE2-6, TE2-7, TE2-8, TE2-10, TE2-12 |
| Low | TE2-9, TE2-11, TE2-13, TE2-14 |

Of the 8 re-validated scheduled items, AGG-85 is partially addressed and the other 7 are still open.
