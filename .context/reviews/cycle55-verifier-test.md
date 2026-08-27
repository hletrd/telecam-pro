# Verifier and test-engineer deep review — cycle 55

Date: 2026-08-27
Reviewed revision: `121fcdf09265262ea1c5d2710bddb61b12c3a38f`

## Inventory and verification

I inventoried all 562 tracked paths and reviewed the 459 current files after excluding archived
review/plan history. I read the complete committed authorities, mapped every production Kotlin/Java
module to its JVM/Robolectric/Compose/instrumented or device-harness evidence, inspected Python tool
tests and release gates, searched for ignored/disabled/tautological tests, and traced the cycle-54
fixes through production entry points rather than accepting their unit seams as proof. The complete
`python3 tools/verify_host.py` gate passed: Android debug/androidTest assembly, all unit tests, lint,
99.83% Partition A coverage, 136 tool tests, nine coverage-tool tests, 195 device-harness self-tests,
155 documentation checks, Python compilation, and `git diff --check`.

## Findings

### VT55-01 — production DNG allocation lacks the deadline its sibling recording path proves necessary

- **Severity / confidence:** Medium / High.
- **Evidence:** `DngPreCaptureAllocation.kt:53-96` has no deadline dependency or terminal other than
  caller cancellation/return. `CameraEngine.kt:4465-4522` starts it while holding the leases acquired
  at `:4411-4423`. By contrast, recording arms `RecordingOperationDeadline` around provider
  allocation (`CameraEngine.kt:5629-5688`) and has deterministic timeout coverage in
  `CameraEngineRecordingPreNativeTest.kt:261-303`. DNG tests cover explicit cancellation of a blocked
  allocation (`DngPreCaptureAllocationTest.kt:23-73`), not a still-current request that never
  returns; no test drives the new DNG path through the production Engine entry point.
- **Failure scenario:** a current DNG press remains admitted forever against a wedged provider,
  keeping the shutter unavailable and eventually starving the allocator shared with REC after
  cancel/retry churn, while all configured gates stay green.
- **Suggested fix:** use the same armed first-wins deadline pattern as REC, expose deterministic DNG
  scheduler/provider overrides, and test production admission, timeout, late allocation cleanup,
  exact once settlement, and lane reuse.

### VT55-02 — the “every recurring producer” diagnostic test omits repeatable per-shot logs

- **Severity / confidence:** Medium / High.
- **Evidence:** `DiagnosticTelemetryTest.kt:68-107` names and claims every recurring producer but
  models only 3A, focus, motion, hardware input, and two ZSL-spike edges. Ordinary debug Single
  capture emits two unbudgeted `CaptureFamily` rows at `CameraEngine.kt:4433-4438` or
  `:5185-5192` and `:5048-5053`. CameraController emits up to three unbudgeted `ShutterLag` rows per
  real shot (`CameraController.kt:2106-2127,2228-2232`) plus one unbudgeted `ZslRefuse` or ZSL-served
  row per press (`:1558-1624`). These sites do not call `processDiagnosticLogBudget`, unlike zoom,
  3A, focus, motion, hardware keys, and ZSL-spike telemetry.
- **Failure scenario:** roughly 60 ordinary debug shots can spend ColorOS's measured 300-row process
  quota (two family rows plus about three shutter rows per shot) before startup/recovery/frame-gap
  evidence from the later part of a capture soak is collected. The exhaustive-looking green test
  still reports 120 rows reserved because it never executes those producers.
- **Suggested fix:** route every repeatable capture/ZSL diagnostic through the shared process budget
  (or one capture-owned bounded trace gate), and extend the executable composition test to count
  Single, ZSL-serve/refuse, standby-generation, and any other action-repeatable producer. Preserve
  the exact harness-required first/terminal records while budget remains.

## Final sweep

No ignored Kotlin tests or unconditional-success assertions were found. Coverage residuals match the
exact manifest, and tool/harness/documentation test totals are current. Manual field checks remain
evidence boundaries rather than host-test failures. Every relevant test and production module was
included in the final missed-issue sweep.
