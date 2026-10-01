# Perf-reviewer — RPL cycle 4 (2026-10-02, HEAD 14767b0a)

Angle: performance, concurrency, CPU/memory, UI-thread responsiveness, GL thread, executors,
camera-thread work, lock ordering, publication/state ownership, log quota. READ-ONLY review: no
source, test, doc or plan edited; Gradle not run.

Not re-reported (settled owner decisions or accepted design): ZSL dark refusal, FocusDetail
threshold, CameraUnit SDK, proprietary HDR, 16 ms zoom coalescer / no-submit gesture design,
synchronous `SettingsStore` commit, `DngCreator.writeImage` on the camera callback, the 300-row log
budget. Still-open earlier items are NOT repeated: AGG3-23 (encoder waits behind preview swap),
AGG3-24 (JPEG EXIF rewrite), AGG3-25 (DNG Binder open + marker on camera thread), AGG3-26 (gallery
thumbnail spool), AGG3-27 (journal connection churn), AGG3-28 (NV21 intermediate JPEG), AGG3-29 /
AGG-50 (GL-thread route enumeration under the gate).

## Findings

### PERF4-1 — WB / tint / EV drags take an UNPACED full request rebuild at 25 Hz; EV in app-side modes rebuilds for a wire no-op
- **Severity / Confidence / Status:** Medium / High (code path) / Confirmed in code; stall magnitude
  Needs-manual-validation (device)
- **Where:**
  - `ui/CameraViewModel.kt:2064` (`onExposureCompensation`), `:2106-2113` (`onWbKelvin`/`onWbTint`)
    → `updateControls` `:4087-4122` (40 ms trailing throttle) → `applyControlsRunnable` `:311-315`
    → `CameraEngine.setControls` `camera/CameraEngine.kt:2571-2606` → `controller.updateControls`.
  - `camera/CameraController.kt:1358-1434` `applyControlsOnCamera`: only `sensorFastPathAdmitted`
    deltas take the paced path (`submitSensorFastPath` `:1704-1722`, `SENSOR_SUBMIT_MIN_INTERVAL_MS`
    = 200 `:2459`). Every other delta calls `startPreview()` (`:1432`) immediately, which is a fresh
    `createCaptureRequest` + `setRepeatingRequest` (`:1020-1219`).
  - `camera/ManualControls.kt:709-714`: `sensorOnlyControlsDelta` admits only focus distance, ISO
    and exposure time. `:998-1055`: in `manualAe` (every AE-OFF mode, including photo PROGRAM when
    `programAppSide`, the PMA110 default) `CONTROL_AE_EXPOSURE_COMPENSATION` is not written at all.
- **Why:** CLAUDE.md records that every repeating-request swap on this HAL gaps the stream
  170-250 ms, and that the swap itself costs the stall regardless of spacing. The project paced the
  zoom and sensor paths for exactly this reason. WB Kelvin, WB tint and EV compensation are
  continuous ruler drags too, but they still reach the camera thread as one full rebuild per 40 ms
  tick. EV in app-side modes is worse: the field has no wire key there, so the rebuild changes
  nothing on the request and pays the stall anyway. The app-side AE loop then follows with an ISO
  change on the paced sensor path, so two swaps land per EV step.
- **Failure scenario:** Default Photo PROGRAM on PMA110 (app-side). The operator drags the EV ruler
  for about one second while framing at 300 mm. About 25 `setRepeatingRequest` swaps reach the HAL
  back to back, each documented to gap the stream about 180 ms. The finder stays on its last frame
  (or redraws a cached frame) for the whole drag, which is exactly when the operator is judging the
  exposure. MANUAL WB Kelvin/tint drags behave the same way.
- **Fix:**
  1. Classify by wire effect, not by field: when `manualAeAdmitted(next, caps)` is true, ignore
     `exposureCompensation` in the delta, so an app-side EV change becomes `NO_OP`. The AE loop's
     follow-up ISO/exposure write already takes the paced sensor path.
  2. Extend the cached-builder fast path to the remaining value-only keys (AWB mode/OFF +
     `COLOR_CORRECTION_MODE`/`GAINS`, `CONTROL_AE_EXPOSURE_COMPENSATION` under HAL-AE). Pace them
     with the same 200 ms leading + trailing, latest-wins gate. Alternatively, pace every
     non-structural `startPreview()` reached from `applyControlsOnCamera` through that gate, and keep
     structural deltas (focus mode, flash, AF lock, tap reset) immediate.
  3. Side effect to handle in the same change: a trailing sensor task queued before a full rebuild
     fires 200 ms later and swaps the newly cached builder again (`:1712-1717`). Cancel it when a
     full rebuild has already carried the newest values.
  - Host test: a pure `controlsApplyPlan(previous, next, caps)` table (EV under manualAe → NO_OP,
    Kelvin → VALUE_FAST_PATH, focus mode → FULL_REBUILD), plus a fake-session controller test where
    25 Kelvin ticks inside 1 s produce at most 1 + ceil(1000/200) repeating submits.
  - Device check: count `FrameGap` buckets during a 2 s EV drag and a 2 s Kelvin drag, before and
    after.

### PERF4-2 — A failed late-sibling delete latches the engine-owned still-admission flag false in the ViewModel; nothing can clear it until the ViewModel is recreated
- **Severity / Confidence / Status:** Medium / High / Confirmed (code and history); trigger
  frequency Needs-manual-validation
- **Where:** `ui/CameraViewModel.kt:4059-4076` (`deleteLateCaptureOutput`: on
  `discardPendingOutput(...) == UNRESOLVED` it posts
  `_state.update { it.copy(stillCaptureAdmissionAvailable = false) }`). The flag is otherwise
  published only by the engine: `CameraViewModel.kt:1229-1234` ← `onStillCaptureAdmissionChanged`
  ← `StillAdmissionPublication` (`camera/DngPreCaptureAllocation.kt:26-44`, change-gated on its
  own `delivered` cache) with snapshot `stillOutputAdmissionAvailable()`
  (`camera/CameraEngine.kt:5354-5359`: DNG lease, retained-family owner, rejected-output owners).
  `delivered` is reset only when the callback is reinstalled (`CameraEngine.kt:1823-1834`) or on
  detach/release (`:234-243`, `:7736`, `:7746`).
- **Why:** In 69754851 this path called `discardRejectedOutput`, whose owner was part of the engine
  snapshot, so the UI write had a matching engine edge that later re-opened admission. 9c579199
  switched the call to `discardPendingOutput`, which is outside every admission owner, and kept the
  UI write. The engine's live snapshot therefore stays `true`, its `delivered` cache stays `true`,
  and every later `publishProcessStillAdmission()` is a no-op. The UI value is now a latch that no
  owner can release. `stillCaptureReady`/`primaryShutterEnabled` (`camera/CameraState.kt:1797-1810`)
  gate the on-screen Photo shutter (`ui/CameraScreen.kt:1483-1484`) and the hardware shutter
  (`CameraViewModel.kt:3343`, `MainActivity.kt:731/822/863`).
- **Failure scenario:** The user deletes a capture from review while its DNG sibling is still being
  written. The late DNG arrives and `recordCaptureOutput` returns `DELETE`. MediaProvider is briefly
  unavailable (busy provider, the Lenovo `external` volume class, or a journal write failure), so
  `discardPendingOutput` returns `UNRESOLVED`. The Photo shutter and the camera key go dead for the
  rest of the ViewModel's life. Pause/resume does not help. Only finishing the Activity or killing
  the process restores stills, and nothing tells the user that.
- **Fix:** Never write an engine-projected flag from the ViewModel. Either route this discard
  through an engine-owned admission owner whose `canAdmit()` reflects the unresolved row and whose
  bounded retry re-opens it (for example `MediaStoreWriter.dispatchRejectedOutput` with its own
  headroom, or the retained-family discard owner), or drop the flag write and keep only the
  `COULD_NOT_DELETE_FILE` status. The durable family marker already owns restart recovery for that
  URI. Robolectric test: a fake discard returns `UNRESOLVED`; assert that `primaryShutterEnabled`
  either never latches or recovers once the owner's retry succeeds.

### PERF4-3 — `ProcessAdmissionSignal` publishes a value computed outside its lock, so racing reserve/release edges can leave the change-gated signal stale and swallow the last notification
- **Severity / Confidence / Status:** Low / Medium / Likely (interleaving constructed from code;
  needs capacity near exhaustion)
- **Where:** `ProcessAdmissionSignal.kt:44-56` (`publish(value)` is change-gated on the caller's
  value). `storage/MediaStoreWriter.kt:213-217` (`publishStillStorageAdmission` reads
  `rejectedOutputAdmissionAvailable()` and then publishes). `:2107-2114`, `:2256-2263`
  (`RejectedOutputCleanupCapacityOwner.reserve`/`releaseReservation` → `notifyAvailability()`
  after the owner lock is released). The engine re-snapshots only when the signal fires
  (`CameraEngine.kt:219-231`).
- **Why / interleaving:** Capacity starts exhausted, with signal `false` and engine delivered
  `false`. T1 releases and computes `true`, but is preempted before publishing. T2 reserves the
  freed permit, computes `false` and publishes a no-op. T1 publishes `true`: the signal flips and the
  engine re-snapshots live state, which is `false`, so delivered stays `false`. T3 then releases
  (live `true`) and publishes `true`, which is a no-op because the signal is already `true`. The
  engine never re-snapshots, so the shutter stays disabled while capacity is open, until some
  unrelated engine-local `publishProcessStillAdmission()` happens. That is unlikely while the
  shutter is disabled. The same stale-order pattern exists in `DngPreCaptureAdmission`
  (`DngPreCaptureAllocation.kt:50-68`). There the engine's own lease release re-publishes directly
  (`CameraEngine.kt:5680-5684`, `:4840-4844`), so the impact is cosmetic.
- **Fix:** Add `ProcessAdmissionSignal.refresh(read: () -> Boolean)` that evaluates `read()` inside
  the signal lock. Lock order would be signal lock → owner lock; owners already notify outside
  their own lock, so no inversion arises. Use it from `publishStillStorageAdmission`. A simpler
  alternative: because every subscriber re-snapshots live state, deliver every notification without
  change-gating. Unit test: a latch-injected `read` reproduces the interleaving above and asserts
  that the last subscriber callback observes the final live value.

### PERF4-4 — Warm relaunch in the same process re-runs the whole launch recovery and, since cycle 3, re-writes every kept pending row
- **Severity / Confidence / Status:** Low / Medium / Confirmed (placement); cost Needs-manual-validation
- **Where:** `camera/LaunchMediaRecoveryCoordinator.kt:93-117` is single-flight only while a run is
  active. After completion, a new request starts a fresh run. The callers are `CameraViewModel.kt:1243`
  (every ViewModel init) → `CameraEngine.cleanupOrphans` (`:7593-7611`). Cycle 3 added
  `reassertPending` for every KEEP_PENDING row (`storage/MediaStoreWriter.kt:1436-1443` →
  `:1128-1157`).
- **Why:** Back-then-reopen (a new Activity and ViewModel in the same process) repeats the
  preflight, the deleted-family journal pass, the Images and Video paging, and the structural
  probes: HEIF bounded reads, plus a video `MediaExtractor` parse with the new 250 ms confirm
  sleep where that path applies. It also now issues one `IS_PENDING=1` provider update per
  retained row. Only rows with `DATE_ADDED < processStart` are eligible, and this process already
  judged them, so a rerun cannot change a verdict except after transient failures. Each update also
  fires MediaProvider change notifications to gallery observers. All of this is on the recovery
  daemon, not the camera path, so the cost is battery, I/O and Binder load rather than jank.
- **Fix:** Have the coordinator retain a COMPLETE result for the process and replay it to later
  subscribers. Re-run only after EXHAUSTED or failure, or after a minimum interval. Unit test: a
  second `request` after a COMPLETE does not dispatch, while one after EXHAUSTED does.

### PERF4-5 — (doc regression, cycle 3) `sleepPreservingInterrupt` lost its KDoc to a stacked constant
- **Severity / Confidence / Status:** Low / High / Confirmed
- **Where:** `storage/MediaStoreWriter.kt:2400-2416`. dd91413c inserted the KDoc for
  `FINALIZED_VIDEO_PARSE_RETRY_MS` and the constant itself between the interrupt-contract KDoc and
  `internal fun sleepPreservingInterrupt`. The function is now undocumented, and the constant
  carries two stacked KDocs. This is the same defect class as AGG3-56. The interrupt contract is
  load-bearing for AGG-30/AGG3-30.
- **Fix:** Move the constant and its one-line KDoc above the "Retry backoff that keeps the
  interrupt contract" block.

## Requested inventory — new blocking/threading observations this cycle

| Site | Observation | Verdict |
|---|---|---|
| `CameraController.kt:2282` `chars(shot = true)` (AGG3-9 fix) | Binder `getCameraCharacteristics` on the camera HandlerThread once per completing shot, only while `rawChars` is null | Acceptable: bounded by the shutter, and Images are already held |
| `MediaStoreWriter.kt:1764-1767` `beforeParseRetry` 250 ms sleep | Runs on the recording-storage worker, not on camera/main/recorder-native | OK |
| `MediaStoreWriter.kt:1436-1443` `reassertPending` | Provider update while the recovery cursor is open; the keyset is `_ID`, so pagination is unaffected | OK (cost noted in PERF4-4) |
| `StandbyAudioController.kt:670, 802` unbound-input release | Released once in `finally`, never started, so no stop wait | OK |
| `CameraEngine.kt:6529` lock order | process lease → engine monitor → recorder lock; consistent with gate → process → recorder (`:6595`), and no engine → process/gate nesting found | No inversion found |

## Final sweep (commonly missed) — clean or already tracked

- Hot camera callback (`CameraController.kt:1054-1198`): zoom forwarding change-gated, 3A/exposure/
  focus/AF published at about 3 Hz and only on change, debug 3A trace bounded. No new per-frame
  allocation class.
- GL: shader locations resolved at init, scratch arrays reused, PIP hint uses no arrays, analysis
  single-flight with a 256 px FBO. Coalescer + producer-side drop keeps the SurfaceTexture queue
  shallow.
- ViewModel publications: level, orientation, record timer, audio levels, focus-confidence and
  analysis are all quantized or change-gated. `RendererAssists` setters are change-gated, so the
  25 Hz `setAeMetering`/`setFocusDetail` calls are free.
- Raw `android.util.Log` sites (`MainActivity.kt:796`, `CameraEngine.kt:4866/5508/5645/6661/7314/7372`,
  `GlPipeline.kt:553/895`, `FlipRenderer.kt:342`, `CameraViewModel.kt:1127`) are all behind
  `recurringDiagnosticAllowed`/`processDiagnosticLogBudget` or debug gates.
- `RetainedStillDeletionOwner`, the rejected-output owners and `ProcessAdmissionSignal` invoke
  listeners outside their own locks, so there is no deadlock through `StillAdmissionPublication`.
- The availability callback runs on `mainExecutor` and does only map writes
  (`ConcurrentHashMap`) and a coalesced setupExecutor post.

## Files examined

- camera: `CameraEngine.kt` (startup/GL continuation, availability callback, pause/resume, recovery
  scheduling, still dispatch + photoCallback + DNG transfer, REC setup/publication lock order,
  still-admission publication, launch recovery), `CameraController.kt` (capture callback,
  applyControlsOnCamera, sensor/zoom fast paths, capturePhoto, chars), `ManualControls.kt`
  (delta classifiers, applyExposure/applyWhiteBalance), `StandbyAudioController.kt`,
  `StillPublicationDispatcher.kt`, `DngPreCaptureAllocation.kt`, `LaunchMediaRecoveryCoordinator.kt`,
  `RetainedStillDeletionOwner.kt`, `RendererAssists.kt`, `CameraState.kt` (shutter predicates)
- gl: `GlPipeline.kt` (start, preview/encoder output, drawFrame, analysis readback, stop/release),
  `FrameNotificationCoalescer.kt`, `FlipRenderer.kt`
- video: `VideoRecorder.kt` (stopNative, drain loops, audio worker, rendezvous, quarantine gate),
  `ColorProfiles.kt` (AAC input size)
- storage: `MediaStoreWriter.kt` (recovery batch, reassertPending, rejected/identity owners,
  admission signal, retry helpers)
- ui: `CameraViewModel.kt` (tickers, engine callbacks, updateControls throttle, hardware gating,
  late-output delete, onCleared), `CameraScreen.kt` (root composition, gesture loop),
  `review/LatestHeavyWorkLane.kt`, `review/MediaReview.kt` (threading only)
- root: `ProcessAdmissionSignal.kt`, `MainActivity.kt` (key diagnostics)
- stab: `GyroEis.kt` (listener registration)
- Prior context: `.context/reviews/archive-rpl-cycle3-2026-10-02/{_aggregate,perf-reviewer}.md`,
  `docs/plans/2026-10-02-rpl-cycle3.md`, `git diff e3a2bdd4..HEAD -- app/src/main`
