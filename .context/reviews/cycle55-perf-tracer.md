# Cycle 55 performance + tracer review

Date: 2026-08-27
Reviewed revision: `121fcdf0` (`origin/main`)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle55.32UR9V`

## Findings

### PERF55-01 — DNG pre-capture allocation has no deadline and can hold capture admission forever

- **Severity / confidence / classification:** Medium / High / confirmed mechanism; provider wedge
  requires fault injection or device observation.
- **Evidence:** `CameraEngine.dispatchStillCapture` acquires the process-wide DNG lease, the processed
  snapshot lease, rejected-output capacity, and the family producer before starting allocation
  (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4411-4449`). The owner is then
  started directly at `:4465-4522`. `DngPreCaptureAllocation.start` only dispatches the uncancellable
  `allocate` call and supports explicit cancellation; it installs no scheduler/deadline
  (`app/src/main/kotlin/me/hletrd/telecampro/camera/DngPreCaptureAllocation.kt:40-97`). Its process
  dispatcher is finite but deliberately leaves a running provider call alone
  (`RecordingPreNativeAllocation.kt:35-82`). By contrast, the sibling pending-video path arms an
  8-second `RecordingOperationDeadline` before dispatch and retires the attempt when it fires
  (`CameraEngine.kt:5616-5665`). The DNG tests cover manual lifecycle cancellation of a blocked
  allocation, not elapsed-time retirement (`DngPreCaptureAllocationTest.kt:23-73`).
- **Concrete scenario:** `ContentResolver.insert`, REGISTERED commit, or
  `PendingDiscardJournal.captureAllocation` blocks while the camera session remains current and the
  app stays foregrounded. No lifecycle/optics edge calls `cancel`; Camera2 is correctly never entered,
  but the DNG admission, optional processed-snapshot slot, capture family producer, and one of the two
  shared pre-native workers remain owned indefinitely. Every later DNG shutter reports “Finishing
  previous photo,” and a second provider wedge can consume the other worker and strand recording
  allocation behind the finite queue.
- **Competing hypothesis rejected:** bounded workers prevent thread multiplication, but they do not
  provide attempt liveness or release the capture-facing leases. Explicit pause/route cancellation is
  not a substitute for a deadline while the operator remains on the same Ready route.
- **Suggested fix:** give each DNG allocation a first-wins `RecordingOperationDeadline` analogous to
  pending video. Arm it before dispatch; timeout must retire caller/capture ownership immediately,
  publish a truthful failure, and route any later exact row to rejected-output cleanup/recovery.
  Add blocked-allocation tests for timeout-vs-return, timeout-vs-optics cancellation, scheduler
  rejection, and late-row cleanup.

### PERF55-02 — sequence-drive processed snapshots are only bounded per Engine, not per process

- **Severity / confidence / classification:** Medium / High / confirmed mechanism; repeated Engine
  replacement during a blocked save is host-fault-injectable.
- **Evidence:** the process-wide `ProcessedSnapshotBudget` explicitly covers only SINGLE and excludes
  sequence drives because they chain on save completion
  (`app/src/main/kotlin/me/hletrd/telecampro/camera/ProcessedSnapshotBudget.kt:5-13`). SINGLE acquires
  that lease at `CameraEngine.kt:4561-4579`, but BURST and AEB call `dispatchStillCapture` without one
  (`:4624-4639`, `:4650-4707`), as does each timelapse tick (`:4710-4757`). The camera callback copies
  the full JPEG/YUV image and submits a closure retaining that snapshot to this Engine's unbounded
  single-thread `ioExecutor` (`:5238-5270`). `release()` calls only `ioExecutor.shutdown()`
  (`:7391-7399`), which neither interrupts nor reclaims a running encode/provider call. A replacement
  Engine creates a fresh executor (`:226-233`) and its sequence drive has no shared lease to observe
  the old snapshot.
- **Concrete scenario:** a BURST/AEB/timelapse processed save blocks in HEIF/JPEG or MediaProvider
  work after retaining a full-resolution snapshot. Finishing and reopening MainActivity in the same
  process clears the ViewModel but cannot stop that running task; the replacement Engine can retain
  another sequence snapshot. Repeating the lifecycle grows old workers plus roughly full-resolution
  YUV/JPEG/Bitmap state once per Engine until memory pressure/OOM, despite the process budget correctly
  preventing the same multiplication for SINGLE.
- **Competing hypothesis rejected:** chaining proves at most one sequence snapshot per Engine, not per
  process. Executor shutdown prevents new tasks on the old facade but has no effect on its running,
  uncancellable work. Durable MediaStore recovery protects bytes, not heap/thread ownership.
- **Suggested fix:** make every processed snapshot consume the process-wide budget, while retaining
  per-sequence chaining for ordering. If two SINGLE slots and sequence capacity need different UX,
  use one process owner with explicit classes/reservations, not an Engine-local exemption. Test an
  old blocked sequence save plus replacement-Engine BURST/AEB/timelapse admission and exact release.

### PERF55-03 — a throwing late-value cleanup skips pre-native retirement and leaks higher-level owners

- **Severity / confidence / classification:** Medium / High / confirmed exception path.
- **Evidence:** `RecordingPreNativeAllocationAttempt.retire` changes `ALLOCATED` to `RETIRED`, then
  invokes `onLateValue` before `completeRetirement`; unlike `completeRetirement` itself, that call is
  not protected by `try/finally`
  (`app/src/main/kotlin/me/hletrd/telecampro/camera/RecordingPreNativeAllocation.kt:239-267`). The new
  DNG owner takes exactly this path when allocation returned but accepted-session ownership was lost
  (`DngPreCaptureAllocation.kt:61-70`). Its `onRetired` is the only edge that removes the owner and
  calls `settleBeforeCamera`, which releases rejected cleanup, snapshot, DNG admission, and family
  producer (`CameraEngine.kt:4439-4462`, `:4502-4519`). The same generic attempt backs pending-video
  allocation, whose `onRetired` abandons the process recorder token and completes the optimistic REC
  attempt (`CameraEngine.kt:5630-5641`). Existing tests use non-throwing `onLateValue` callbacks and do
  not assert retirement after cleanup failure.
- **Concrete scenario:** allocation returns just as optics/pause/timeout retires it. If late-row
  dispatch or one of its observer callbacks throws, `retire` exits before `onRetired`. DNG can then
  remain permanently “in progress” with its family/snapshot leases; the recording variant can leave
  the pending process REC token and UI admission owned, blocking later native acquisition.
- **Competing hypothesis rejected:** the row remains durably REGISTERED and is recoverable, but that
  only protects storage. It does not release the in-memory DNG/REC/capture owners whose terminal is
  skipped. `DngPreCaptureAllocation` protects its special `onReady`-throws branch separately, but not
  this ordinary `attempt.retire()` branch.
- **Suggested fix:** make late-value handling part of the retirement `try/finally`, guaranteeing
  `completeRetirement` exactly once even when cleanup/observers throw. Preserve the cleanup failure
  for diagnostics without letting it suppress owner release. Add throwing-late-cleanup tests for
  DNG cancellation/session supersession and video timeout.

### TRACE55-04 — StartupTrace is global and accepts marks from stale controller/request generations

- **Severity / confidence / classification:** Low / High / confirmed ownership gap; the exact rapid
  lifecycle interleave is manual/fault-injection validation.
- **Evidence:** `StartupTrace` owns only one process-global `running` Boolean and accepts every mark
  while armed; `begin` returns no owner token and `mark`/`finish` accept none
  (`app/src/main/kotlin/me/hletrd/telecampro/camera/StartupTrace.kt:23-64`). `resume()` arms it on main
  before the setup lane has necessarily completed the old controller's asynchronous close
  (`CameraEngine.kt:7035-7049`, `:7053-7064`). Every repeating-request callback carries its own
  `firstDiagnosticResultPending` and finishes the global trace on its first result, regardless of
  controller, optics, session, or resume generation (`CameraController.kt:1029-1034`, `:1111-1119`).
  Tests prove only global idempotence/disarm formatting; none supplies two competing controller/request
  owners (`StartupTraceTest.kt:40-123`).
- **Concrete scenario:** a control rebuild installs a fresh repeating callback, then the app quickly
  backgrounds and foregrounds. `resume()` arms the next cold-start trace while the old controller is
  still closing on `setupExecutor`; a queued old first result can call `finish` before the replacement
  open. The emitted line may contain only `firstCameraResult` or mix old request marks with the new
  resume origin, reporting a fabricated fast startup and hiding the real one.
- **Competing hypothesis rejected:** `begin()` idempotence prevents clock reset, and early-return
  `disarm()` prevents a later ordinary rebuild from finishing a knowingly aborted trace, but neither
  identifies which controller/request owns an armed measurement. The old callback is still allowed
  to execute during ordered asynchronous teardown.
- **Suggested fix:** make `begin` return an opaque generation token and require that token on every
  mark/finish. Pass it only into the controller/open/request created for that resume attempt; stale
  controller callbacks become inert. Bound/deduplicate milestone storage as a secondary fence, and
  test old-first-result/new-resume and two-request interleavings.

## Verification and coverage

- Read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely. Inventoried all
  production, JVM/instrumented test, tool, build/config, and documentation paths before review.
- Examined the complete performance/tracing surface through repository-wide executor/thread/monitor,
  native-resource, Image/Bitmap/buffer, timeout/queue, logging, lifecycle, and cleanup searches. Deep
  traces covered CameraEngine/CameraController, GL and analysis generation ownership, still/DNG
  processing, recording/audio/native quarantine, process-wide provider dispatchers/recovery, review
  spooling/decoding/player setup, ViewModel tickers and lifecycle, and their focused tests/docs.
- Focused baseline tests passed:
  `DngPreCaptureAllocationTest`, `RecordingPreNativeAllocationTest`,
  `ProcessedSnapshotBudgetTest`, `StartupTraceTest`, and `DiagnosticTelemetryTest`. Their green result
  is consistent with the findings: the missing deadline, cross-Engine sequence admission,
  throwing-late-cleanup, and cross-controller trace interleaves are not covered.
- Final missed-issue sweep rechecked every production executor and recurring log producer. The
  process diagnostic budget, frame notification coalescer, GL analysis single-flight/FBO bound,
  preview/encoder checked detach, review source byte/spool owners, recording storage/recovery lanes,
  and standby AudioRecord quarantine paths were not refiled: their current bounded/identity-owned
  behavior contradicts the initial leak/saturation hypotheses.
- No implementation, plan, commit/push, deployment, device action, destructive operation, or
  browser automation was performed. Device-only A3/A4/A5/D1/E1/E2/E3 remain evidence obligations,
  not code findings.
