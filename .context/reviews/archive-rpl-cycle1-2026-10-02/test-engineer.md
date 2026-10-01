# Test-engineer review — 2026-09-30 (HEAD ba5b16e7)

Scope: `app/src/test/**` (252 files, 2,271 `@Test`, 64 Robolectric classes), `app/src/androidTest/**`
(4 files, compiled by the gate but not run), `tools/tests/**` (8 suites), `tools/coverage/tests/**`,
`device-tests/tests/**` (5 suites). Read-only. Gradle was not run, so every finding here comes from
reading the code. Confidence ratings are about the reasoning, not about a reproduced failure.

## What is already solid

- No test reads production source text. The only file read is the shader in
  `ShaderProgramCompileTest`.
- There is no sleep-based synchronization. The only two `Thread.sleep(1)` calls sit inside
  deadline-bounded polling loops (`StandbyAudioControllerTest.kt:1533`,
  `FamilyDeletionMarkerIntegrationRobolectricTest.kt:181`).
- Short timeouts are mostly driven by manual schedulers (`ManualDeadlineScheduler`,
  `ManualRetryScheduler`, `ManualDeadline`) instead of wall-clock time.
- 10 of the last 12 `fix(...)` commits included a regression test. The two without one are
  2eb57e4e (see TE-7) and c70842b5, which is tooling.
- Pure logic is held to a 99.5% line-coverage gate for Partition A, with every residual miss
  documented. The coverage gaps below are therefore about structure, not raw line counts.

## Findings

### TE-1 — Retry in the lane test must finish within a real 100 ms, then an unchecked cast (Medium, flaky)
- `app/src/test/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLaneTest.kt:145-189` (lane built at :152-164, retry at :177-178)
- **Problem.** The lane is built with `terminalTimeoutMs = 100` so that the `slow` request times out.
  The same lane is then reused for `lane.submit(Any(), "retry")`. That retry has to be dispatched onto
  the 2-thread pool, run, and be observed inside `withTimeoutOrNull(100)` in real time. The result is
  then cast with `as ProgressiveLatestWorkLane.Submission.Completed`.
- **Scenario.** Under host load (JaCoCo instrumentation, a parallel Gradle daemon, or a GC pause over
  100 ms) the retry returns `TimedOut`. The cast throws `ClassCastException`, which says nothing
  about the cause. This is the same "real clock plus shared host" pattern that ba5b16e7 just fixed.
- **Fix.** Pass the timeout per call, or build a second lane for the retry with the production 5 s
  timeout (`REVIEW_WORK_TERMINAL_TIMEOUT_MS`). The alternative is to inject the timeout source the way
  `ExactHandlePrepareOwner` takes `schedule`. Replace the cast with
  `assertTrue(result.toString(), result is Completed)`.
- **Status:** open.

### TE-2 — "The caller did not block" is checked with a 250 ms wall-clock budget (Medium, flaky)
- `app/src/test/kotlin/me/hletrd/telecampro/ui/OwnerlessMediaDeleteLifecycleTest.kt:59-64`
- `app/src/test/kotlin/me/hletrd/telecampro/ui/OwnerlessMediaDeleteOperationTest.kt:66-74`
- **Problem.** Both tests time a dispatch with `System.nanoTime()` and assert `elapsedMs < 250L`.
  The first dispatch in a fresh Robolectric sandbox pays for executor and thread creation, JIT, and
  JaCoCo probes. A stop-the-world GC can also take more than 250 ms on a loaded CI host.
- **Why the check adds little.** In the lifecycle test the provider lambda blocks on an untimed
  `allowProvider.await()`. If the caller really did block, the test thread would deadlock before
  reaching the assertion. So the wall-clock check adds flake risk without adding detection.
- **Fix.** Make the proof structural. Run the call on a helper thread and assert that its
  "returned" latch opens while `providerEntered` has fired and the provider is still parked. Use timed
  awaits so that a regression fails the test instead of hanging it. Then drop the elapsed-time
  threshold.
- **Status:** open.

### TE-3 — `DiagnosticLogTest` is order-dependent and becomes vacuous once the process budget is spent (Medium, weak and flaky)
- `app/src/test/kotlin/me/hletrd/telecampro/camera/DiagnosticLogTest.kt:20-54`
- **Problem.** The test uses the process-global `processDiagnosticLogBudget` (180 rows) and
  `processReservedDiagnosticLogBudget` (120 rows). All Robolectric classes share one sandbox, and
  engine and ViewModel tests emit warnings, so the reserved budget is probably exhausted before this
  class runs (this was not measured). When it is, both admitted counts are 0, `ShadowLog` holds 0
  rows, and `in 0..2` / `in 0..3` pass. The test then passes even if `DiagnosticLog.w` never logs.
- **Second problem.** When the budget is not exhausted, a leftover background thread from an earlier
  test (a daemon retry scheduler or a released engine executor) can spend a row between the
  `before` and `after` reads. That row is counted in `reservedAdmitted` but logged under a different
  tag, so `assertEquals(..., ShadowLog.getLogsForTag(tag).size)` fails.
- **Fix.** Give the `DiagnosticLog` doors an injectable budget, the way
  `recurringDiagnosticAllowed(debugEnabled, budget)` already has one. Test each door against a fresh
  `ProcessDiagnosticLogBudget` with exact counts (d/i spend 2, w/w/e spend 3). Add one exhaustion
  test that proves a full budget suppresses the row. Keep the process-global relational check only
  as a secondary assertion.
- **Status:** open.

### TE-4 — The same pattern ba5b16e7 fixed survives next to it: a plain `mutableListOf` sink on a process-wide signal (Medium, flaky)
- `app/src/test/kotlin/me/hletrd/telecampro/storage/PendingAllocationIdentityRecoveryTest.kt:294-318`
- **Problem.** `production storage subscription publishes close and reopen capacity edges`
  subscribes a non-thread-safe `mutableListOf<Boolean>()` to
  `MediaStoreWriter.subscribeStillStorageAdmission`. That signal combines the process-wide
  rejected-output owner and the pending-identity recovery owner. The test asserts exact lists
  (`[true,false]`, `[true,false,true]`) and requires `rejectedOutputAdmissionAvailable()` to be true
  at entry.
- **Scenario.** Both owners live in the shared Robolectric sandbox and use a real daemon retry
  scheduler with backoff. Suppose any earlier Robolectric test (engine, ViewModel, or storage)
  transferred an UNRESOLVED row into either owner. Then either the entry assertion fails, or a
  worker-thread retry publishes an extra edge into the list mid-test. Because the list is not
  thread-safe, a concurrent `add` can also corrupt it.
- **Fix.** Use `CopyOnWriteArrayList`. Assert relative to a snapshot: record `events.size` after
  subscribe and compare only the edges that follow. Where the test depends on a clean singleton,
  replace the bare `assertTrue(rejectedOutputAdmissionAvailable())` with an
  `awaitCondition { MediaStoreWriter.rejectedOutputAdmissionAvailable() }` precondition.
- **Status:** open.

### TE-5 — No per-test or suite timeout, and many untimed `join()`/`await()` calls, so a regression hangs the gate instead of failing it (Medium)
- `app/build.gradle.kts:648-652` and `:687-691` configure the `Test` tasks without setting `timeout`.
  No test uses a JUnit `Timeout` rule or `@Test(timeout=)`.
- **Examples of untimed waits.**
  - `gl/CompletionDispatchTest.kt:687`: `second.join()` runs before `finish.countDown()`. If
    `runCleanup` regresses so that the second caller blocks on the first, the test waits forever.
    That regression is exactly what the test exists to catch.
  - `CompletionDispatchTest.kt:506-507` and `:651-653`: bare `join()` calls.
  - `LatestHeavyWorkLaneTest.kt` has about 20 untimed `Deferred.await()`/`join()` calls inside
    `runBlocking`.
  - `CameraEngineRecordingPreNativeTest.kt` has about 10, and there are more in
    `MediaReviewOwnershipTest`, `RecordingTeardownTerminalGateTest`, and
    `RecorderQuarantineAdmissionGateTest`.
- **Scenario.** A deadlock regression in any ownership gate turns `python3 tools/verify_host.py`
  into an indefinite hang with no failing test name. For a codebase whose central risk is ownership
  and lock ordering, this is the most likely way these tests will fail.
- **Fix.** Add a suite-level backstop: `tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(30)) }`.
  In the concurrency classes, use `join(5_000); assertFalse(t.isAlive)` and
  `withTimeout(5_000) { deferred.await() }`, or add a class-level `@get:Rule val timeout = Timeout.seconds(30)`.
- **Status:** open.

### TE-6 — The encoder `MediaFormat` builders are never exercised, so the PQ-tag trap has no guard (Medium, coverage gap)
- `app/src/main/kotlin/me/hletrd/telecampro/video/ColorProfiles.kt:56-137`. The whole object is listed
  in Partition B at `tools/coverage/partition-b.txt` (`video/ColorProfiles`).
- **Problem.** `ColorTagsTest` fully covers the pure tag tables (`hevcColorTagsFor`,
  `apvColorTagsFor`). Nothing checks that `hevcFormat`, `avcFormat`, `videoFormat`, or `aacFormat`
  actually write those tags, or that they write `KEY_MAX_FPS_TO_ENCODER`,
  `KEY_CAPTURE_RATE`/`KEY_OPERATING_RATE`, or `KEY_PROFILE`.
- **Scenario.** CLAUDE.md documents that leaving `KEY_COLOR_TRANSFER` unset makes the QTI encoder tag
  the stream as ST2084 (PQ). If a refactor drops `setInteger(KEY_COLOR_TRANSFER, ...)` from
  `hevcFormat`, or stops applying `tags.profile`, every host test still passes.
- **Fix.** `MediaFormat` is a real class under Robolectric. Add a Robolectric test that iterates
  codec × `ColorTransfer`, calls `videoFormat(...)`, and asserts:
  - `containsKey` and the value for STANDARD, RANGE, TRANSFER, and PROFILE
  - `KEY_MAX_FPS_TO_ENCODER == 29.97f` at 30000/1001
  - `KEY_CAPTURE_RATE` is absent when `captureRate == 0`

  After that, move `ColorProfiles` from Partition B to Partition A.
- **Status:** open.

### TE-7 — The "a failed save with no log line" diagnostics from 2eb57e4e have no test (Low-Medium, gap)
- `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt`: the pending image and video
  insert, the registration disposition, and the identity-capture exits added in 2eb57e4e. No test
  file changed in that commit.
- **Why it matters.** CLAUDE.md now describes these warnings as the diagnostic signature of the
  external-union-volume class of defect. Nothing checks that:
  - an insert that throws or returns null emits exactly one reserved row and returns null;
  - an "identity recovery owner at capacity" refusal logs and does not leak the reservation.
- **Fix.** Reuse the `installProvider` pattern from `MediaStorePendingDiscardIdentityReaderTest` with
  a provider that throws, then one that returns null. Assert a null return, one row through an
  injected budget (see TE-3), and that `pendingIdentityRecoveryOwner` capacity is unchanged.
- **Status:** open.

### TE-8 — Several structurally pure functions are tested but outside the coverage gate (Low-Medium, structural)
- `tools/coverage/partition-b.txt` moves `camera/ManualControlsKt` and `camera/CaptureCapabilitiesKt`
  entirely into Partition B, labelled "mixed owners conservatively surrendered".
- **Problem.** `captureWatchdogTimeoutMs` (the saturating arithmetic), `manualAebExposuresNs`,
  `previewExposureTrade`, `effectiveExposureNs`, `kelvinTintToRggbGainValues`, and `meteringRegionTargets`
  all have tests (`ExposureMathTest`, `WbGainsTest`, `ControlCapabilityNormalizationTest`). But the
  99.5% gate does not see them, so a new untested branch here (a new AEB step, a new clamp) will not
  lower Partition A.
- **Fix.** Split the Camera2 request writers (`applyManualControls`, the `CaptureRequest.Builder`
  helpers, `kelvinTintToRggbGains`, which returns framework `RggbChannelVector`) into their own file,
  or into an `…Android.kt` class-level owner. The pure math then compiles into a Partition A class.
- **Status:** open.

### TE-9 — `DigitalGainTest` copies the formula it is testing (Low, tautological)
- `app/src/test/kotlin/me/hletrd/telecampro/gl/DigitalGainTest.kt:61-77` compared with `gl/GlPipeline.kt:2155-2165`.
- **Problem.** The expected value is computed with the same expression and the same shared constant
  (`SdrToHlgMapping.SDR_EOTF_GAMMA`) as production. The test name says the LUT "matches the shader
  chain", but the shader is never consulted. A wrong gamma constant, or a change of the encode
  exponent in both the test and production, would still pass.
- **Fix.** Add a few golden anchors computed by hand. For gain 4 with gamma 2.4, `lut[64]` should
  equal `round(255 * min((64/255)^2.4 * 4, 1)^(1/2.4))`, written as a literal. Also add an assertion
  that ties `SDR_EOTF_GAMMA` to the shader literal that `ShaderProgramCompileTest` already reads.
- **Status:** open.

### TE-10 — `StartupTraceTest` restores a different seam than production uses, and one test cannot fail (Low)
- `app/src/test/kotlin/me/hletrd/telecampro/camera/StartupTraceTest.kt:39-43` compared with
  `camera/StartupTrace.kt:39`.
- **Seam drift.** `restoreSeams` sets `emit = { android.util.Log.i(...) }`, but production's default is
  `Log.i(TAG, it)` through the `DiagnosticLog` alias, which is budget-gated. Any later plain-JVM test
  in the same JVM that reaches `finish()` would call the unmocked `android.util.Log`, and would
  bypass the quota facade that this commit series introduced.
- **Fix.** Capture the original `elapsedMs`/`emit` in `@Before` and restore those exact values.
- **Tautology.** The test at `:96-104` (`elapsed values are monotonic`) runs against an injected
  clock that adds 5 on every call, so it cannot fail. Either delete it or feed a non-monotonic clock
  and assert what production does with it.
- **Status:** open.

### TE-11 — Process singletons have no reset, and no test asserts they are idle afterwards (Low, order dependency)
- **`UnsafeRecorderQuarantine`.**
  - `CameraEngineRecordingPreNativeTest.kt:39-44`: the teardown releases engines but never checks
    that the process recorder admission is free.
  - `release classifies claimed setup…` at `:569-609` removes `old` from `engines` and releases its
    blocked setup only on the final line (`releaseOldSetup.countDown()`). The old setup thread then
    finishes asynchronously after the test has returned.
  - Later tests in the same sandbox call `checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(...))`
    (`:692`, `:735`, `:774`), which assumes the process is idle.
- **`ProcessDngPreCaptureAdmission.owner`.** `DngPreCaptureAllocationTest.kt:72-77` and
  `ProcessStillAdmissionEngineTest.kt:20` assert `canAdmit()` / `tryAcquire() != null` on the process
  singleton at entry.
- **Fix.** Add an `@After` in each class that touches a facade:
  `awaitCondition { !UnsafeRecorderQuarantine.isActive() && ProcessDngPreCaptureAdmission.owner.canAdmit() }`.
  That turns a leak into a failure attributed to the leaking test. Also await the old setup thread's
  exit in the test at `:569`.
- **Status:** open.

### TE-12 — Negative waits prove little under load (Low, weak assertion)
- `CameraEngineRecordingPreNativeTest.kt:705` (`assertFalse(replayed.await(50, ms))`), `:741` and
  `:786` (100 ms). The same pattern appears in `ProcessAdmissionSignalTest.kt:52` (`closer.join(50L)`
  followed by `assertFalse(closeReturned)`).
- **Problem.** These waits cannot fail spuriously, but under load they pass without proving
  anything: a replay that arrives at 60 ms is missed.
- **Fix.** Where possible, pair each one with a structural witness, such as a counter read after the
  foreign token is released, or a barrier inside the replay path that records whether it ran before
  the release.
- **Status:** open.

### TE-13 — `tools/field/tap_af_aim.py` has no tests, and its first 3A reading can be arbitrarily stale (Low, tooling gap plus minor bug)
- `tools/field/tap_af_aim.py:70-84` (`latest_3a`), `:87-100` (`sensitivity_range`), `:124-135`.
- **Stale reading.** The initial `before = latest_3a(...)` does not run `logcat -c` first. The 3A
  row is now paced (3 s change interval, 15 s heartbeat) and capped by the 180-row process budget
  (`CameraController.kt:1184-1191`). So `before` can be the last row emitted before the budget ran
  out, possibly many minutes old. The "AE is RAILED" gate then runs against an ISO that no longer
  applies.
- **Parsers.** The `(\w+)=(-?\d+)` field parser and the `dumpsys` range regex have no unit test, and
  the harness's `test_tool_contracts.py` does not import this script.
- **Fix.** Run `logcat -c`, then poll for a fresh `3A:` row with a deadline longer than
  `THREE_A_HEARTBEAT_MS` before taking `before`. Add `tools/tests/test_field_tap_af_aim.py` with a
  fixture 3A line and a `dumpsys` excerpt.
- **Status:** open.

### TE-14 — `tools/release_permissions.py` is only tested indirectly (Low, gap)
- `tools/release_permissions.py:27-44`.
- **Problem.** `test_release_artifact.py` builds manifests only in the plain
  `<uses-permission android:name=…/>` form. Two paths are never exercised:
  - the `-sdk-\d+` alternative, which bundletool emits as `<uses-permission-sdk-23>`;
  - `packaged_permission_declarations` for a `<permission>` tag without `protectionLevel`.

  A regex regression would silently drop an sdk-23 permission from the closed-set comparison.
- **Fix.** Add three table-driven cases: sdk-23, attributes before `android:name`, and a declaration
  with no protection level.
- **Status:** open.

## Production observations made while reading (no defect confirmed)

- The union-volume identity fix (6b2f07dd) is well covered by
  `MediaStorePendingDiscardIdentityReaderTest`. The 2eb57e4e logging now shares the 120-row reserved
  budget with every other warning. After a long session with backoff-retry warnings, the one
  diagnostic row that CLAUDE.md calls the signature of this defect class could itself be suppressed
  by the quota. Consider reserving a small sub-budget for storage-refusal rows. This is a design
  observation, not a confirmed bug.

## Count

14 findings: 0 High, 6 Medium (TE-1 to TE-6), 2 Low-Medium (TE-7, TE-8), 6 Low (TE-9 to TE-14).
