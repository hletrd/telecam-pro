# Perf reviewer: RPL cycle 5 (2026-10-02)

Role: performance, concurrency, threading, CPU/memory, UI responsiveness. ID prefix `PR5-`.
Scope: the cycle-4 delta (`887d39fb..ea7d4374`, 86 commits) plus the standing hot paths: `gl/*`,
`camera/CameraEngine.kt`, `camera/CameraController.kt`, `ui/CameraViewModel.kt`,
`ui/CameraScreen.kt`, `ui/controls/*`, `ui/overlays/*`, `video/*`, `storage/*`, and
`camera/StandbyAudioController.kt`.

Prior context I read: `CLAUDE.md`, `docs/plans/2026-10-02-rpl-cycle4.md` (later-cycle, carried and
Deferred lists), and `.context/reviews/archive-rpl-cycle{1..4}-2026-10-02/perf-reviewer.md` plus the
cycle-4 `_aggregate.md`. Nothing below re-reports a tracked item as new. Where a finding touches a
tracked ID, it says so.

## Summary

| ID | Severity | Confidence | Status | One line |
|---|---|---|---|---|
| PR5-1 | Medium | High | Confirmed (code) | `invalidateCameraReady` cancels DNG owners while the session still reads as current. The cancel's settle runs the BURST/AEB continuation, which dispatches a new DNG allocation that escapes the cancel |
| PR5-2 | Medium | Medium | Needs device validation | One poisoned EGL orphan makes every later preview/encoder detach throw. The current output is blamed, and a healthy recording is failed. AGG4-18 now routes more failures into this list |
| PR5-3 | Low | Medium | Confirmed (code) | `RejectedOutputCleanupCapacityOwner` has the same permit/queue mismatch AGG4-26 fixed in the family-deletion owner, so a reserved DNG-failure cleanup can be rejected and parked until the next launch |
| PR5-4 | Low | High | Confirmed (code) | The cycle-4 JPEG splice encodes into an unsized `ByteArrayOutputStream`: about 19 array doublings plus a full copy, beside the 50 MB rotated bitmap |
| PR5-5 | Low | High | Confirmed (code) | A HEIF+JPEG shot composes the identical EXIF APP1 twice: two cache temp files and two `ExifInterface` parse/save passes on `ioExecutor` |
| PR5-6 | Info | High | Confirmed (code) | `GyroEis` registers sensor listeners with no Handler, so accelerometer (~16 Hz) and gyro (50 Hz while motion-inversion tracking is on) callbacks run on the main looper |

Counts: Critical 0, High 0, Medium 2, Low 3, Info 1.

## Findings

### PR5-1: `invalidateCameraReady` cancels DNG owners before Ready is cleared, so the cancel's settle continues the chain onto the dying session and a fresh DNG allocation escapes the cancel

- **Severity / Confidence / Status:** Medium / High / Confirmed by code read. The provider churn and
  the admission hold are host-provable. The visible symptom needs a device.
- **Where:**
  - `camera/CameraEngine.kt:550-566`: `invalidateCameraReady()` calls
    `cancelDngPreCaptureAllocations()` as its first statement, outside and before the monitor block
    that sets `cameraReady = false`, `readyController = null`, and `acceptedCameraSession = null`.
  - `camera/CameraEngine.kt:5088-5099`: the list of owners to cancel is snapshotted once.
  - `camera/DngPreCaptureAllocation.kt:183-186` (`cancel` → `attempt.retire()`, synchronous) →
    `:247-258` `dngOwnerRetirement`: unregister, release the DNG admission / rejected-cleanup /
    snapshot reservations, then `settleRegisteredShot(settle)`. `settle` runs the chain's `onDone`
    on the invalidating thread.
  - `camera/CameraEngine.kt:5193-5208` (BURST `fire(shot + 1)`) and `:5220-5285` (AEB `fire(i + 1)`)
    gate only on `acceptedSessionIsCurrent(accepted)` (`:4879-4880`). That check is still TRUE at
    this point, because the monitor block has not run yet.
- **Why:** At this moment the cancelled owner has just released the process-wide single DNG
  admission slot and its rejected-cleanup reservation. The continuation therefore passes every
  gate in `dispatchStillCapture` (`:4935-4955`): it acquires the DNG slot again, reserves cleanup,
  registers a NEW owner (missed by the snapshot already taken at `:5095-5097`), and `start()`
  dispatches a real `MediaStore` insert to the pre-native pool. When that insert returns,
  `isCurrent` is false (`DngPreCaptureAllocation.kt:148-150`). The attempt retires, the
  just-created pending row goes through `onLateValue` → `cleanLateAllocation` → the rejected-output
  owner (a second provider round trip), and the retirement settles once more. Only then does the
  next `fire()` see the cleared session and stop.
- **Failure scenarios:**
  1. BURST or AEB with DNG on, and the operator backgrounds the app mid-chain (`pause()`,
     `:7574`, main thread) or any optics door reopens (`reconfigureCamera`, `:4347`). Each
     invalidation costs one wasted pending insert + delete pair. The process DNG slot also stays
     held for the length of that insert. If the provider is slow (the condition this owner exists
     for), the slot is held for up to `DNG_PRE_CAPTURE_ALLOCATION_TIMEOUT_MS` = 8 s. After resume,
     every still on every route is refused with `FINISHING_PREVIOUS_PHOTO`, even though nothing
     the operator asked for is in flight.
  2. Manual-exposure AEB: the continuation also calls `ctrl.updateControls(stepControls)` on the
     controller being torn down (`:5236`), and later `ctrl.updateControls(controls)`. These are
     wasted camera-thread posts at best, and a bracket step pushed into the outgoing session's
     request during its close.
  3. The new owner's insert happens while the app is backgrounded. MediaProvider observers (the
     gallery) see a pending row appear and vanish.
- **Fix:** Clear Ready first, then cancel. Inside `invalidateCameraReady`, run the existing monitor
  block (session generation, `cameraReady = false`, `readyController = null`,
  `acceptedCameraSession = null`) before calling `cancelDngPreCaptureAllocations()`. Then publish
  the tap and Ready edges. The per-owner `cancelEachSealed` (AGG4-21) already guarantees a cancel
  failure cannot skip the invalidation, so the "first statement" placement is no longer needed for
  safety. With Ready cleared first, the settle's `fire()` takes its `!acceptedSessionIsCurrent`
  exit, and no owner can register after the snapshot.
  Host test: register a DNG owner for a BURST chain, call the invalidation, and assert that the
  allocator received no second dispatch and that the DNG admission is free afterwards.
  **PMA110 behaviour:** unchanged for single shots. Only multi-shot DNG chains interrupted by an
  invalidation change, and they stop issuing the wasted allocation.

### PR5-2: A poisoned EGL orphan that still cannot be destroyed makes every later preview/encoder detach throw, and the failure is attributed to the CURRENT output

- **Severity / Confidence / Status:** Medium / Medium / Needs device validation. Whether a
  surface whose detach failed keeps failing `eglDestroySurface` is driver-dependent. The control
  flow below is certain.
- **Where:**
  - `gl/GlPipeline.kt:495-510`: `clearPreviewOutput` calls `clearOrphanedOutputs(core)` BEFORE it
    touches the preview it was asked to detach.
  - `:526-535` `clearOrphanedOutputs` → `RetainedOutputs.releaseAll` (`:2086-2091`). This
    propagates the first throw and keeps that entry at the head of the list (`removeAt(0)` runs
    only after a successful release).
  - `:836-856`: `clearEncoderOutput` sweeps the same list first.
  - The callers that treat any throw from these as "the current output could not relinquish its
    native window": the texture-acquisition branch at `:997-1015` (since AGG4-18 it also
    orphans), the preview draw/swap branch at `:1230-1247`, the post-encoder preview branch at
    `:1294-1302`, `applyPreviewOutput` at `:414-429`, and `failEncoderOutput` at `:858-865`.
- **Why:** After a single containment, the poisoned EGLSurface sits in `orphanedEglOutputs`. From
  then on, every preview transition first retries that orphan. If its destroy still fails:
  - Any later preview fault, even a transient one the current preview could have detached
    cleanly, takes the "detach failed" branch. That branch calls `failEncoderOutput` on a healthy
    active recording and orphans another surface. The orphan list grows, and each new entry sits
    behind the stuck head.
  - `applyPreviewOutput(surface = …)` throws at `:429` before creating the new window surface. The
    engine's bounded three-retry budget is spent on the old orphan, which ends in
    `PREVIEW_UNAVAILABLE_REOPEN` while the new TextureView surface itself is fine.
  - A clean REC stop's `clearEncoderOutput` cannot prove detach, which forces the terminal EGL
    reset fallback for an encoder surface that was never at fault.
  The AGG4-18 KDoc (`:512-518`) promises that the next bind "creates a fresh EGLSurface instead of
  … the poisoned one". That is true only when the deferred sweep succeeds. When it does not, the
  sweep makes the next bind fail, so the containment converts one fault into a sticky fault that
  spans outputs.
- **Fix:** Make the orphan sweep best-effort and isolated from the output being detached:
  1. Sweep each orphan individually and catch its failure. Keep failed entries retained, but do
     not let them throw into the current output's detach. Change-gate one `DiagnosticLog.w` per
     orphan so the 300-row quota is not spent.
  2. In `clearPreviewOutput`, detach the CURRENT preview first, then sweep.
  3. Keep the sweep strict only where a codec Surface can depend on it: an orphaned ENCODER
     EGLSurface of the same codec input must block that codec's Surface release. To support
     that, tag orphans with their role (preview or encoder) so a stuck preview orphan cannot veto
     encoder teardown.

  Host-testable through the existing `detachEglOutput` / `RetainedOutputs` seams, with a
  `releaseAll` that sees one permanently failing entry.
  **PMA110 behaviour:** identical on the normal path, where the orphan list is empty.

### PR5-3: `RejectedOutputCleanupCapacityOwner` has the AGG4-26 permit/queue mismatch: a reserved cleanup can be rejected at exactly the moment an outage drains

- **Severity / Confidence / Status:** Low / Medium / Confirmed by code read. This is the sibling
  of AGG4-26, which cycle 4 fixed only in `FamilyDeletionMarkerCapacityOwner`.
- **Where:**
  - `storage/MediaStoreWriter.kt:2133-2142`: queue `ArrayBlockingQueue(backlogCapacity)` (8), but
    `Semaphore(workerCount + backlogCapacity)` (10).
  - `:2188-2201` `runAttempt` releases the permit in its `finally`, inside the task, so the worker
    is still busy.
  - `:2174-2186` `submitReserved` turns `RejectedExecutionException` into an UNRESOLVED result.
  - `:2296-2298` `canAdmit` counts permits.
  - Both process owners use this class: `rejectedOutputOwner` (`:220-231`, no retry scheduler) and
    `pendingIdentityRecoveryOwner` (`:238-250`). A grep for `Semaphore(` finds no other
    semaphore-plus-executor pair; the other dispatchers bound by the executor alone and are fine.
- **Why:** Under a provider stall, both workers block and all 8 queue slots fill, so 10 permits are
  taken. When the provider recovers, a worker finishes and releases its permit while still inside
  the task. A `reserve()` (DNG admission at `CameraEngine.kt:4949`/`:5756`) succeeds in that
  window. Its later `submit` then meets a full queue with both workers busy, and is rejected.
  - For `rejectedOutputOwner`, the incomplete DNG's pending row is marked UNRESOLVED with no
    retry scheduler. It counts toward `MAX_REJECTED_OUTPUTS` and is retried only by the next
    launch recovery's `retryRejectedOutputs()` (`:1315`).
  - For the identity owner, the row waits a backoff it did not need.
  The window opens precisely as an outage drains, which is the one time this owner has a backlog.
- **Fix:** Use the AGG4-26 remedy: size the queue at `workerCount + backlogCapacity` so every
  permit's task fits in the queue alone. Pin it with a test seam like
  `FamilyDeletionMarkerCapacityOwner.afterTaskReleased`. Alternatively, release the permit from
  `ThreadPoolExecutor.afterExecute`. **PMA110:** no change outside the saturation window.

### PR5-4: The cycle-4 JPEG splice encodes into an unsized `ByteArrayOutputStream`, which costs about 19 doublings plus a full copy while the 50 MB bitmap is live

- **Severity / Confidence / Status:** Low / High / Confirmed by code read. This is a memory
  regression introduced by B.4 (AGG4-6).
- **Where:** `capture/StillCapturePipeline.kt:313-325` (`ByteArrayOutputStream()` at its default
  32-byte capacity, then `toByteArray()`), and `writeSingleJpeg` at `:351-392`.
- **Why:** Before B.4, `Bitmap.compress` streamed straight into the provider stream. Now a
  12.5 MP JPEG (roughly 4 to 12 MB at the user's quality) grows from 32 bytes by doubling. That
  allocates about 2× the final size in discarded arrays, then `toByteArray()` adds a third full
  copy. Meanwhile the about 50 MB rotated ARGB bitmap and the input JPEG `bytes` are still
  reachable on `ioExecutor`. A peak of about 3× the JPEG size, piled onto a working set that
  perf review #3 had worked to shrink, raises GC pressure during BURST/timelapse.
  `StillSnapshot.Yuv.jpegBytes` (`capture/StillSnapshot.kt:55-60`) documents and avoids exactly
  this by pre-sizing to `width * height`.
- **Fix:**
  - Pre-size the stream (`width * height / 2` matches typical q90 to q97 output). Better, use a
    small `ByteArrayOutputStream` subclass that exposes `buf`/`count`, so `exifSplicePlan` and
    `writeJpegWithExifApp1` read the internal buffer with no `toByteArray()` copy.
  - The JPEG lane is the last reader of `rotated`, so it can be recycled after `compress` and
    before the Binder `openOutputStream` and write.

  Host test: assert that the splice output is byte-identical from the exposed buffer.
  **PMA110 file output:** identical.

### PR5-5: A HEIF+JPEG shot composes the identical EXIF APP1 twice through cache temp files

- **Severity / Confidence / Status:** Low / High / Confirmed by code read.
- **Where:** `capture/StillCapturePipeline.kt:273` (HEIF) and `:323` (JPEG) each call
  `uprightStillExif(rotated, exifShot, …)` → `composeStillExifApp1` (`:732-768`). Each call does a
  1×1 `Bitmap.compress`, `File.createTempFile`, a write, an `ExifInterface` parse and
  `saveAttributes()` (a file rewrite), a `readBytes`, and a delete.
- **Why:** Both calls receive the same `rotated` dimensions, `ORIENTATION_NORMAL`, and no source
  EXIF, so the two payloads are byte-identical. The second call is pure cache-directory I/O on the
  serial `ioExecutor`, and it delays the JPEG publication (and the next timelapse tick) for every
  dual-format shot.
- **Fix:** Compose the payload once in `saveProcessedStills` (lazily, only if either format is
  wanted) and pass it to both writers. Keep the per-lane best-effort null semantics. A test can
  count compositions per dual-format shot. **PMA110 file output:** identical.

### PR5-6 (Info): `GyroEis` sensor callbacks run on the main looper

- **Where:** `stab/GyroEis.kt:91` and `:146`: `registerListener(this, sensor, period)` with no
  `Handler`. `SAMPLING_PERIOD_US = 60_000` (`:302`); `GYRO_SAMPLING_PERIOD_US = 20_000` (`:317`).
- **Why:** The work is tiny (a few float operations, plus one short `rotationLock` section shared
  with the analysis executor). It is still a 50 Hz main-thread wakeup source whenever
  motion-inversion tracking is armed, on top of the Compose frame loop. This is noted for
  completeness only, and is not a defect today.
- **Fix (optional):** register on a dedicated `HandlerThread` or on the GL handler. The fields are
  already `@Volatile` or lock-guarded for cross-thread readers.

## Cycle-4 delta: threading and perf observations (clean)

| Site | Observation | Verdict |
|---|---|---|
| `ProcessAdmissionSignal.refresh` (AGG4-35), `ProcessAdmissionSignal.kt:44-75` | `read()` runs under the signal lock and takes both owners' locks. Lock order is signal → owner. Every `notifyAvailability` runs outside the owner lock (`MediaStoreWriter.kt:2154-2158, 2245-2252, 2275-2290`), and listeners run outside the signal lock | No inversion |
| `ChainCharacteristicsReread` (AGG4-37), `CameraController.kt:2042, 2308` | Armed on the camera handler and consumed in `tryComplete`. It is an `AtomicBoolean`. A head whose completion never arrives leaves it armed for the next continuation (one extra Binder read at most) | OK |
| `controlsApplyPlan` NO_OP (AGG4-34), `ManualControls.kt:737-745` | An EV-only delta under admitted manual AE changes no wire key. `controls` is still stored, so the next rebuild and stills carry it | OK; saves about 180 ms per 40 ms tick |
| `zoomEaseTicker` (AGG4-20), `CameraViewModel.kt:2362-2371` | `removeCallbacks` before `post` ends the double 30 Hz chain | OK |
| `FamilyDeletionMarkerCapacityOwner` (AGG4-26) | Queue sized workers + backlog | Fixed. Sibling left open: PR5-3 |
| Status plate (AGG4-65), `CameraViewModel.kt:1810-1886` | Every `deferredProgressStatus` read and write happens under the gate monitor, including the timer expiry and `clearProgressStatus` | OK |
| `recallVideoStreamSizeChanges` (AGG4-8), `CameraEngine.kt:3038-3042` | Pure in-memory caps lookup on `setupExecutor`; no Binder call | OK |
| `probeMp4MoovPresence` (AGG4-3/4), `MediaStoreWriter.kt:2827-2867` | Reads 8- or 16-byte headers only, bounded at 4096 boxes, on recovery or the recording-storage worker | OK |
| `ZoomRuler` (AGG4-73), `ManualDials.kt:1255-1280` | Pure `zoomRulerScale` per recomposition; drag events still go through the VM coalescer | OK |

## Final sweep (commonly missed): clean or already tracked

- Log quota: every raw `android.util.Log` site (`MainActivity.kt:781`; `CameraEngine.kt:4967, 5631,
  5768, 6788, 7441, 7499`; `GlPipeline.kt:567, 909`; `FlipRenderer.kt:342`;
  `CameraViewModel.kt:1161`) is still behind `recurringDiagnosticAllowed`,
  `processDiagnosticLogBudget`, a change gate, or `DEBUG`. The new `StillCapturePipeline`
  warnings go through `DiagnosticLog` and fire once per shot at most.
- GL hot path: no new per-frame allocation in `drawFrame`. The AGG4-18 helper allocates nothing.
  The FrameGap and finder-failure logs remain change-gated.
- ViewModel publications: the analysis, audio-level, AF and focus-distance paths are unchanged.
  Quantization and consumer gates are intact.
- Engine monitor: the new cycle-4 code (`handlePreflightFailure`, `scheduleColdStartRetry`
  outcome, `applyResolvedCameraRoute` early return) makes no Binder or callback calls under
  `synchronized(this)`. The `cameraOpWithheld` AppOps query still runs outside the monitor
  (`CameraEngine.kt:3628-3645`).
- Already tracked, not re-reported: PERF3-1 (encoder waits behind preview swap), PERF4-4 /
  AGG4-36 (warm relaunch reruns recovery), AGG4-34 WB/tint pacing half, MRG4-5 (program target
  during gesture), PERF3-6 (YUV intermediate JPEG), P13 (audio-loop allocations).

## Files covered

- camera: `CameraEngine.kt` (invalidation, DNG dispatch and owner retirement, BURST/AEB/timelapse
  chains, preflight retry, recall fast path, route apply, failure path, engine-monitor sites),
  `CameraController.kt` (cycle-4 diff: chain chars reread, controls apply plan, session config),
  `DngPreCaptureAllocation.kt`, `RecordingPreNativeAllocation.kt` (attempt retire/late value,
  dispatcher), `FamilyDeletionMarkerDispatcher.kt`, `StillPublicationDispatcher.kt`,
  `RetainedStillDiscardDispatcher.kt`, `RecordingStorageDispatcher.kt`, `ManualControls.kt`
  (apply-plan classifiers), `CameraState.kt` (publication gate, computed format getters),
  `LaunchMediaRecoveryCoordinator.kt` (diff)
- gl: `GlPipeline.kt` (drawFrame, preview/encoder output lifecycle, orphan containment,
  `RetainedOutputs`), `FlipRenderer.kt` / `FocusDetail.kt` / `MotionInversion.kt` (diffs)
- storage: `MediaStoreWriter.kt` (admission signal, rejected/identity owners, moov walk, video
  verdicts)
- capture: `StillCapturePipeline.kt`, `HeifExif.kt`, `StillSnapshot.kt`
- video: `VideoRecorder.kt` (diff)
- ui: `CameraViewModel.kt` (engine callbacks, status plate, momentary holds, zoom ease),
  `MomentaryHold.kt`, `CameraScreen.kt` (diff, state collection), `controls/ManualDials.kt`,
  `controls/RulerAccessibility.kt`, `ZoomMath.kt`, `overlays/Overlays.kt` (diff),
  `ViewModelMediaDeleteDispatcher.kt`
- root/other: `ProcessAdmissionSignal.kt`, `AudioDenialReason.kt`, `MainActivity.kt` (key
  diagnostics), `stab/GyroEis.kt`
