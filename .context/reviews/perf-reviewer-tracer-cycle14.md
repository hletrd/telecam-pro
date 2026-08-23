# Performance + causal-tracing review — cycle 14

Date: 2026-08-24
Reviewed HEAD: `fbe31d6`
Mode: read-only host review; no device, deployment, source, plan, aggregate, or git work

## Scope and inventory

I read the required authorities first: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, and `README.md`. I inventoried all 357 tracked review-relevant paths outside
generated build output: 86 production Kotlin files (47,458 lines), 176 JVM/Robolectric/Compose
tests, four instrumented tests, 30 Python/shell tool and device-harness files, manifests/resources,
Gradle/release inputs, and current documentation. Historical reviews and completed plans were used
only to locate claims that needed reconfirmation.

The specialist trace covered every production executor, thread, Handler ticker, delayed task,
blocking provider/native boundary, process owner, and shutdown path; Camera2/ImageReader/pseudo-ZSL
buffer ownership; still snapshot/bitmap/RAW save backpressure; GL/EGL preview, encoder, analysis and
abandoned-generation lifetimes; recorder allocation, mic handoff, codec drains, storage tails and
quarantine; ViewModel recomposition/coalescing and review media loading; and the tests that claim
those seams. I rechecked all cycle-13 performance findings against current source before looking for
new work.

## Findings

### PERF-TR14-01 — launch media recovery is unbounded across rows and Engine generations

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed process-lifetime/backpressure gap. A provider wedge or large pending
  set is needed to expose the resource impact, but neither bound exists in current code.
- **Exact evidence:**
  - Every `CameraEngine` constructs its own daemon single-thread recovery executor
    (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:127-131`), and every new
    `CameraViewModel` immediately starts a cleanup pass (`app/src/main/kotlin/me/hletrd/telecampro/ui/
    CameraViewModel.kt:1065-1070`).
  - One cleanup task can run three complete sweeps (`CameraEngine.kt:6041-6059`). The retry count and
    sleeps are bounded, but each sweep is not.
  - `MediaStoreWriter.cleanupOrphanedPending` queries both collections with no
    `QUERY_ARG_LIMIT`, then walks and probes every matching pending row
    (`app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:606-703`). Valid complete
    rows retained by earlier marker/publication outages have no process-wide count ceiling, so the
    cursor cardinality is not bounded by the 64-row *published restore* limit or the rejected-output
    budget.
  - `release()` only calls `mediaRecoveryExecutor.shutdown()`
    (`CameraEngine.kt:6290-6296`). `shutdown()` does not cancel an accepted/running task, and the
    underlying `ContentResolver.query`/probe/publish/delete calls have no cancellation or deadline.
    A replacement Engine therefore creates another worker and starts the same prior-process sweep
    while the old one can still be blocked.
  - The focused storage tests exercise bounded parsers, journal batches, and retry reducers, but no
    test blocks recovery in Engine A, replaces it with Engines B/C, and asserts a process-wide
    worker/task bound.
- **Concrete failure scenario / causal chain:** MediaProvider blocks Engine A's launch query -> the
  Activity/ViewModel is finished and relaunched in the still-live process -> Engine A's daemon
  worker survives executor shutdown -> Engine B creates a second recovery worker and submits the
  same scan -> repeated relaunches multiply blocked Binder callers without a ceiling. If the
  provider later recovers, every survivor can scan/probe/publish the same unbounded prior-process
  row set up to three times, causing an I/O/CPU burst and racing redundant restore completions. The
  camera startup lane stays responsive, which hides the accumulating process cost.
- **Competing hypotheses checked:** ordinary configuration changes retain the Activity ViewModel,
  but finishing/relaunching the single Activity can create a replacement ViewModel in the same
  process; daemon status prevents JVM-exit retention but does not cap threads while the Android
  process remains alive; the process-wide recording-storage and pre-native allocators do not own
  this separate recovery executor; per-row structural parser limits do not cap row count or Binder
  duration.
- **Suggested fix:** make launch recovery a process-wide, single-flight coordinator with one finite
  worker and per-Engine closeable subscribers, like the recording-storage capacity owner. Process
  pending rows in bounded batches with durable continuation/rescan state rather than one all-row
  cursor, and coalesce replacement-Engine requests onto the active pass. Add a deterministic blocked
  provider + repeated Engine replacement test and a large-row-set batching test.
- **Historical distinction:** cycle-2 `PERF-02` correctly moved recovery off `setupExecutor`, closing
  its cold-camera-start delay. Its separate recommendations to cap rows/wall time were not
  implemented or retained as active work; this finding is the remaining process-wide resource and
  cardinality failure, not a re-report of startup serialization.

### PERF-TR14-02 — canceled review overlays can accumulate concurrent full bitmap decodes

- **Severity / confidence:** Medium / High for the concurrency/memory mechanism; exact OOM/latency
  threshold needs a stress run on representative devices.
- **Classification:** Confirmed missing decode ownership/backpressure, with device-dependent impact.
- **Exact evidence:**
  - `loadBitmap` performs two blocking stream opens/decodes plus optional EXIF-driven
    `Bitmap.createBitmap` rotation inside `withContext(Dispatchers.IO)`
    (`app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:254-303`). The final preview
    is individually capped at a 3,000 px long edge (`MediaReview.kt:187,318-321`), but one 4-byte
    bitmap can still occupy tens of MiB and rotation temporarily owns a second bitmap.
  - Each `MediaReviewOverlay` owns a `LaunchedEffect(uri, loadAttempt)` that starts that work
    (`MediaReview.kt:499-504`). Closing/re-keying the overlay cancels the coroutine, but
    `BitmapFactory.decodeStream`, `ExifInterface` stream reads, and `Bitmap.createBitmap` are
    synchronous and do not observe coroutine cancellation once entered.
  - There is no process/overlay decode gate, semaphore, dedicated serial executor, or stale-result
    recycle boundary. Rapid close/reopen (or new-URI replacement) can therefore start another
    `Dispatchers.IO` decode while canceled predecessors still allocate and rotate pixels. A result
    completed after prompt cancellation can be discarded at the coroutine boundary without an
    explicit `Bitmap.recycle()` owner.
  - `MediaReviewSizingTest` covers geometry/size policy only; no test holds one decoder, cancels its
    overlay, starts successors, and asserts a one-decode memory/concurrency ceiling.
- **Concrete failure scenario / causal chain:** open a large still review -> close it while the
  provider/decode is running -> immediately reopen and repeat -> every canceled effect stops owning
  publication but its blocking decode continues on the shared I/O pool -> several 12-36 MiB decoded
  bitmaps (and rotation siblings) coexist -> GC/native bitmap pressure produces capture UI jank or
  an OOM even though the documented *per-image* size cap is respected.
- **Competing hypotheses checked:** `LaunchedEffect` correctly prevents a canceled result from
  updating a disposed composition, but cancellation is not an interrupt/cancellation signal for the
  synchronous decoder; URI keying fixes stale publication, not resource admission; normal success
  eventually lets GC reclaim the bitmap, but that does not bound concurrent peak memory.
- **Suggested fix:** put review still loading behind an identity-owned finite decode lane (normally
  one active decode process-wide), coalesce the newest URI/request, and explicitly recycle a bitmap
  whose generation lost publication. Keep provider/bounds work off main. Add a forced-interleaving
  test for load A -> cancel -> load B/C proving at most one heavy decode and no stale bitmap owner.

### PERF-TR14-03 — `SurfaceTexture` notifications still execute one full draw per queued callback

- **Severity / confidence:** Low / Medium, preserving the original historical finding's rating.
- **Classification:** Reopened historical performance risk; code mechanism confirmed, user impact
  still needs GL/encoder-backpressure profiling.
- **Exact evidence:**
  - The listener runs directly on the GL Handler and calls the complete draw synchronously for every
    callback (`app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:434-445`). There is no dirty
    bit, scheduled-draw guard, pending counter, or callback-batch drain anywhere in the class.
  - `drawFrame` latches the newest texture, draws/swaps preview, may draw/swap the encoder, and every
    fifth real frame can perform FBO readback/analysis dispatch (`GlPipeline.kt:908-1311`). Thus a
    stale queued notification is not cheap bookkeeping.
  - Current AOSP `SurfaceTexture` posts a Handler message for each native frame notification and its
    Handler calls the listener; it does not remove/coalesce an earlier message. `updateTexImage()`
    itself selects the most recent image, so multiple older notification messages can remain after
    the first callback already skipped to the latest buffer. See the official AOSP source:
    https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/SurfaceTexture.java#231
    and `#476`.
  - `docs/BACKLOG.md:1431` says “GL frame coalescing” landed, but the current source and test suite
    contain no coalescing owner or scheduling test. Historical cycle-1 `PERF-10` identified the same
    code path; it was archived without the requested mechanism appearing in git history.
- **Concrete failure scenario / causal chain:** encoder `swapBuffers` or GPU/readback work makes the
  GL thread fall behind the camera producer -> frame-available messages accumulate -> the first
  callback's `updateTexImage` jumps to the latest available buffer -> remaining queued callbacks
  still repeat preview/encoder draws of already-obsolete/current texture state -> extra GPU/codec
  work delays control tasks and worsens latency/thermal load exactly while the pipeline is under
  backpressure.
- **Competing hypotheses checked:** BufferQueue bounds retained image buffers, not the Handler message
  queue or duplicate draw work; invoking `drawFrame` directly avoids one app-posted Runnable but is
  not coalescing; the 16 ms zoom self-redraw throttle governs only `setZoomTarget`, not camera-frame
  callbacks.
- **Suggested fix:** make the framework listener a cheap notifier that sets one pending/dirty flag
  and posts at most one named draw runnable; that runnable should latch the most recent texture and
  draw once, then re-arm if a notification arrived while it ran. Preserve the existing rule that
  only real camera-frame work reaches encoder/analysis and keep zoom self-redraw preview-only. Add a
  fake-scheduler test that delivers N notifications behind a blocked draw and proves one catch-up
  render rather than N full renders.

## Verified prior findings and accepted facts

- Cycle-13's standby input owner now requests stop off lifecycle/REC callers and orders release
  behind that exact stop. Native `AudioRecord.stop()` latency remains device-validation territory;
  I did not re-file the closed blocking-read ownership finding.
- Cycle-13 lifecycle info sampling is bounded to one in-flight request plus one coalesced active
  generation. Stop/start churn cannot recreate its previous unbounded ViewModel-I/O queue.
- The recorder pre-native allocator and post-native storage dispatcher remain process-wide and
  finite; strict native wedges deliberately quarantine rather than race release. That accepted
  process-long retention is not treated as a leak regression.
- The logical/front pseudo-ZSL full-resolution YUV bandwidth, tap-AF extra frames, and front idle
  memory-pressure soak remain explicit device-measurement items in `docs/BACKLOG.md`; no new device
  evidence exists, so they were not re-filed.
- Dark-shot ZSL refusal, synchronous live-RAW DNG writing, two-swap gesture zoom policy, and
  single-stream Loupe limits remain measured/accepted design constraints rather than performance
  defects.

## Final missed-issue sweep

I re-swept every production executor/thread creation and shutdown site; recurring Handler task;
Camera2 Image/reader close; pseudo-ZSL ring/result ownership; processed snapshot budget; EGL output,
orphan and abandoned generation; analysis FBO/buffer/single-flight gate; recorder allocation,
native setup, drain, muxer, mic and storage continuation; MediaStore recovery/delete journals;
review bitmap/player lifetime; Compose hot-state publication; Python subprocess/thread creation;
and the focused tests for those boundaries. No additional current-HEAD performance/tracing issue
survived causal validation.

**New current-HEAD finding count:** 3 (two new Medium findings; one Low historical finding reopened
because the claimed implementation is absent).
