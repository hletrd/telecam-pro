# Performance, causal-tracing, and debugger review — cycle 33

Date: 2026-08-24
Reviewed revision: `984424cb36ebe409e67bb2e69c8605ea6f41c4bb`
Workspace: clean isolated worktree `/private/tmp/find-x9-cycle33-latest.Vc7rke`

## Scope, inventory, and method

I read `CLAUDE.md` completely, then the committed as-built authority in
`docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, the cycle-32 aggregate and completed plan, and the
retained specialist reviews. The optional private maintainer files named by `CLAUDE.md` are absent
from this clean worktree, as the committed fallback policy permits. Historical findings were leads
only; the finding below was reproduced from current HEAD and is distinct from cycle 20's now-fixed
RAW-only publication-queue defect.

The complete tracked inventory contains 447 paths: all 98 production Kotlin files, 205 JVM/
Robolectric/Compose test files, four instrumented-test files, 30 Python host/device-harness files,
two shell tools, Android manifests/resources/build inputs, and current documentation/assets. The
runtime sweep traced Camera2 route/session/request ownership, capture correlation/watchdogs and ZSL
images, processed and RAW save/publication, deleted-family durability and recovery, every GL/EGL
preview/encoder/analysis generation, recorder/microphone/muxer quarantine, ViewModel lifecycle and
provider lanes, review bitmap/player ownership, and all executors, handlers, timers, retry owners,
queues, monitors, atomic seams, and shutdown paths. Tool and device-harness subprocess, file, retry,
timeout, and cardinality behavior were also inventoried. The cycle-32 changes were separately traced
through every caller and terminal edge.

## Finding

### PTD33-01 — deleted-family durability still queues without a bound behind the still-encoding lane

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed unbounded-queue and lifecycle-retention defect. A blocked still
  encode/provider/preferences call is required to expose growth, but the independent RAW-only
  producer and unconditional queue admission are deterministic in current source.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:130-137` creates
    `ioExecutor` with `Executors.newSingleThreadExecutor()`, whose `LinkedBlockingQueue` is
    unbounded. The nearby comment explicitly moved RAW-only SINGLE publication off that queue, but
    the queue still owns deletion-intent persistence.
  - Every app-owned capture-family Delete first tombstones in memory, then unconditionally submits
    one durable-marker task to that executor at `CameraEngine.kt:4238-4289`. Because the executor's
    queue is unbounded, `execute` cannot report saturation; its failure branch covers shutdown only.
    The queued task retains the family intent, already-published outputs, and `onComplete` callback.
  - The worker ahead of those tasks may block in full-resolution decode/HEIF/JPEG/provider work
    submitted at `CameraEngine.kt:4591-4618` and implemented synchronously in
    `capture/StillCapturePipeline.kt:180-290`, or the first deletion task itself may block in the
    synchronous family-marker `SharedPreferences.commit()` at
    `storage/MediaStoreWriter.kt:491-516,1402-1412`. None of those platform/native calls has an
    application cancellation or deadline.
  - RAW-only SINGLE captures remain independently admissible while that Engine I/O worker is
    blocked: processed-snapshot admission is acquired only for processed formats
    (`CameraEngine.kt:3995-4023`), while DNG publication uses the finite process owner
    (`CameraEngine.kt:4627-4661`). After each DNG's camera callback and finite publication tail
    settle, the Camera2 pending slot and family producer lease are terminal, so another DNG can be
    taken without waiting for `ioExecutor`.
  - The review closes immediately after invoking Delete
    (`ui/CameraScreen.kt:1461-1476`), while provider deletion starts only from the delayed
    durability callback (`ui/CameraViewModel.kt:3482-3518`). Capture admission checks only retained
    discard/journal failure state, not queued delete-intent count
    (`CameraEngine.kt:4292-4293`). The 32-entry in-memory tombstone window
    (`CameraEngine.kt:6840-6843`; `camera/RetainedStillDeletionOwner.kt:285-300`) bounds local keys,
    not executor tasks or captured callbacks. `release()` merely calls `ioExecutor.shutdown()`
    (`CameraEngine.kt:6624-6626`), which preserves accepted queued work and cannot recover a wedged
    worker.
- **Concrete causal trace:** a HEIF encode or MediaProvider call for photo A never returns on the
  Engine I/O worker. The operator changes to DNG-only; shot B writes and publishes through the
  camera callback plus the separate finite still-publication owner. Deleting B closes review and
  enqueues its family-marker/callback task behind A. Shots C, D, and onward can repeat the same
  independent DNG path, and every Delete appends another task. No marker or provider deletion for
  those families has begun, no saturation signal reaches the UI, and the queue retains an
  unbounded number of family/output and ViewModel callback graphs. Engine release does not discard
  them; if A stays wedged, the old non-daemon worker and its entire queue remain process-reachable.
  After enough pending deletes, tombstone trimming can additionally discard old local bookkeeping
  without reducing the queued work.
- **Competing hypotheses checked:** the processed-snapshot budget correctly caps snapshots, but it
  does not cover RAW-only shots or deletion tasks. The two-worker/two-backlog still-publication
  dispatcher fixes cycle 20's DNG-publication queue, but it is a different executor and therefore
  enables, rather than bounds, this reproduction. The 64-family durable journal cap is consulted
  only when a queued marker task eventually runs; it cannot bound tasks waiting before that check.
  The process-finite retained-discard and ViewModel-delete lanes begin only after marker durability
  or handle already-durable late rows, so neither owns this pre-marker backlog.
- **Suggested fix:** move family-delete marker/callback work off the still-encoding executor and
  behind a process-wide finite admission owner, or add a separately bounded deletion-intent lane
  whose unit is one family. A refused pre-marker Delete must complete promptly as failed and restore
  the frozen review handle; it cannot claim launch recovery because no durable tombstone exists yet.
  Preserve exact-family producer/publication ordering, and add a deterministic test that blocks the
  first encode/marker task, performs more RAW-only captures and Deletes than capacity, and proves a
  fixed active+queued ceiling, prompt overflow ownership, no inline provider work, and no retained
  stale ViewModel/Engine callback graph.

## Final missed-file sweep

No second current-HEAD performance, concurrency, ownership, or latent-failure issue survived
competing-hypothesis checks. In particular, cycle 32's recorder-finalization bit covers admission,
active REC, strict release, and quarantine terminals; retained-family retry work is process-owned,
conflated, backed off, and separated from authoritative surviving rows; ownerless deletion freezes
one exact file and never performs an unauthorized direct delete; preview diagnostics observe the
existing producer-fed Ready owner without changing it; and the review transform/double-tap changes
remain bounded and generation-local. Camera callback/ImageReader ownership, GL frame coalescing and
analysis retirement, recorder drains/native quarantine, process-wide provider capacities, review
heavy-work disposal, ViewModel tickers, and host/device tooling all remained coherent in the final
sweep. No build, device mutation, source edit, plan edit, commit, or deployment was performed.

**New finding count: 1.**
