# Performance, causal-tracing, and debugger review — cycle 20

Date: 2026-08-24
Reviewed revision: `0d1005dad5dfec6eb705e8a5070486d80be72775`

## Scope, inventory, and method

I read the repository authorities in their required order: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, `docs/UX_POLICY.md`, `.context/README.md`, then the applicable current
`README.md`, `docs/TESTING.md`, and `docs/FIELD_CHECKS.md`. Historical reviews and completed plans
were used only to avoid duplicating resolved findings; every conclusion below was reproduced from
the current source.

The tracked inventory is 404 files: 93 production Kotlin files (49,463 lines), 184 host-test Kotlin
files, four instrumented-test and two debug Kotlin files, and 32 Python/shell files under `tools/**`
and `device-tests/**`, plus Android resources, manifests, Gradle/release inputs, and current project
documentation. Generated output, `.claude/worktrees/**`, archived reviews, binary assets, and old
device captures were catalogued but not treated as executable current source.

The runtime sweep covered Camera2 route inventory and topology leases, optics/Ready generations,
request fast paths, capture correlation and watchdogs, pseudo-ZSL reader/ring ownership, processed
and RAW still save/publication, GL/EGL preview/encoder/analysis generations, recorder allocation and
native quarantine, standby-microphone handoff, post-native storage, MediaStore delete/recovery
journals, review bitmap/player lanes, ViewModel tickers and teardown, and every executor/thread,
monitor, delayed callback, bounded queue, and rejection path. The final sweep also checked build and
device-harness process/file cardinality. Existing device-only ZSL soaks, tap-AF YUV churn, deliberate
dark-shot ZSL refusal, same-stream Loupe limits, display-referred HDR/log boundaries, and closed HAL
facts were not re-filed.

## Findings

### PTD20-01 — one blocked publication holds global DISCARD authority for every media URI

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed process-liveness and failure-isolation defect; the triggering
  non-returning `ContentResolver.update` remains provider/device dependent.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:47-56,222` implements
    `withLookupAuthority` with one companion-object `databaseLock`, shared by every journal instance
    and every URI in the process.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:474-506` enters that monitor
    and keeps it held not only for the SQLite absence check, but across up to three synchronous
    `ContentResolver.update(IS_PENDING=0)` calls and their retry sleeps. A provider Binder call has
    no application cancellation or deadline, so this critical section is not actually bounded.
  - The same monitor is required by all exact-DISCARD `mark`, `lookup`, `remove`, and `page` calls
    (`PendingDiscardJournal.kt:24-133`). Those operations are reached from independent still
    publication/discard, process recording-storage tails, deleted-still discard workers, and the
    sole launch-recovery worker (`StillCapturePipeline.kt:384-390`, `VideoRecorder.kt:1639-1668`,
    `MediaStoreWriter.kt:661-836,912-936`).
  - Those lanes were deliberately made independent and finite: recording storage has two process
    workers plus eight backlog slots (`RecordingStorageDispatcher.kt:66-98,183-201`), retained-still
    discard has its own two plus eight (`RetainedStillDiscardDispatcher.kt:53-105`), and launch
    recovery has one worker with a 120-second terminal watchdog
    (`LaunchMediaRecoveryCoordinator.kt:214-242`). No test blocks publication for URI A while
    asserting that exact ownership for unrelated URI B can still progress.
- **Concrete causal trace:** a completed photo or video A enters `publish`, proves its SQLite
  DISCARD marker absent, then wedges in MediaProvider's update while still holding `databaseLock`.
  A rejected/deleted output B can no longer durably mark DISCARD; another completed output C cannot
  even check whether it may publish; recording-storage's second worker blocks behind the Java
  monitor; retained-still workers do the same; and launch recovery cannot query/page its journal.
  The recovery watchdog can report process exhaustion after 120 seconds, but it cannot release the
  monitor or restore any of those otherwise-independent lanes. One bad URI/provider operation has
  therefore become a process-wide media terminal rather than remaining inside its finite owner.
- **Competing hypotheses checked:** the three publish attempts bound only returned failures, not a
  Binder call that never returns. Fixed worker counts prevent unlimited threads within each owner,
  but the shared monitor couples every owner to the first blocked call. Some serialization is
  necessary to stop a same-URI DISCARD racing an absent-check publication; that requirement does
  not require unrelated URIs to share the same long-held monitor.
- **Suggested fix:** serialize the check-to-provider transition per exact URI (for example, a fixed
  stripe set keyed by canonical URI), while retaining only short database-open/schema/migration
  critical sections globally. `mark`, `remove`, and publication for the same URI must take the same
  stripe; URI B must not wait for a blocked URI A. Add a two-URI latch test proving (1) A's blocked
  publication still serializes a racing A discard, and (2) B can mark/lookup/remove and recovery can
  page while A remains blocked.

### PTD20-02 — RAW-only single shots bypass the only still-tail budget and queue without a bound

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed unbounded-queue/resource-retention defect; visible growth requires a
  slow or wedged publication lane while MediaStore insert/write remains usable.
- **Exact evidence:**
  - Every Engine owns `Executors.newSingleThreadExecutor()` for still I/O
    (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:117-120`), whose work queue is
    unbounded.
  - SINGLE-shot admission acquires the process-wide two-slot budget only when
    `effFormats.wantsProcessedStill` is true (`CameraEngine.kt:3860-3883`). RAW-only is a supported,
    normalized request (`PhotoFormatsTest.kt:37-44,92-97`) and therefore acquires no lease.
  - For every successful DNG, the camera callback synchronously writes the RAW bytes and durable
    COMPLETE marker, then unconditionally submits a publication continuation to the unbounded
    executor (`CameraEngine.kt:4414-4446`). The DNG lane is not terminal, and its capture-family
    `onDone` does not run, until that queued task executes (`CameraEngine.kt:4317-4350,4429-4434`).
  - CameraController nevertheless closes the live `Image`, clears its reusable `pending` slot, and
    returns from the callback (`CameraController.kt:2094-2138`), so the next SINGLE shutter is
    admitted. `stillOutputAdmissionAvailable()` gates only failed deletion/rejected-output
    ownership, not pending DNG-publication cardinality (`CameraEngine.kt:4130-4132`). Existing
    `ProcessedSnapshotBudgetTest` covers only the processed lease and no test saturates the DNG
    publication queue.
- **Concrete causal trace:** MediaProvider allows insert/open/write but its first
  `IS_PENDING=0` update blocks. RAW-only shot A finishes a valid private DNG and wedges the Engine
  I/O worker in publication. Shots B, C, ... still synchronously write complete DNGs on the camera
  callback and each enqueue another continuation behind A. The shutter stays available, the queue
  retains an unbounded number of capture-family completion closures, completed rows accumulate
  without their live terminal/status edge, and `Engine.release()` merely calls `shutdown()` on the
  executor (`CameraEngine.kt:6343-6349`)—it neither removes accepted work nor resolves a worker that
  never returns. BURST/AEB/timelapse avoid this because they chain on `onDone`; repeated SINGLE RAW
  presses do not.
- **Competing hypotheses checked:** the processed-snapshot budget cannot help a RAW-only request.
  Rejected-output headroom is untouched because these DNGs are structurally complete, not rejected.
  The queued objects do not retain live Camera2 `Image`s or full Bayer buffers, but their number and
  producer-family ownership are still unlimited, and Engine recreation can leave an old blocked
  executor/queue alive while creating another.
- **Suggested fix:** put DNG publication behind a finite process-wide still-tail owner, or add a
  unified still-publication admission budget that covers processed and RAW-only SINGLE captures.
  Because the DNG bytes and COMPLETE decision are already durable before enqueue, saturation can
  fail closed without deleting data: publish a delayed-save result, terminally release the live
  family/continuation owner, and leave the private row for launch recovery. Add active+queued
  saturation tests, overflow-to-recovery tests, repeated-Engine tests, and a RAW-only shutter test
  proving queue cardinality never exceeds the documented bound.

## Final sweep and accounting

No additional current-HEAD issue survived competing-hypothesis checks. In particular, the cycle-19
tri-state DISCARD lookup, one-time legacy import, exact asynchronous MediaPlayer prepare deadline,
process-wide recording-storage capacity, topology REC lease, GL analysis owner, and launch-recovery
watchdog are coherent with their focused tests. No build or device mutation was performed in this
PROMPT-1 review-only task.

**New finding count: 2.**
