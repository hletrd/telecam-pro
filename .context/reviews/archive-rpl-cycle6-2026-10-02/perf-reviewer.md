# Perf reviewer: RPL cycle 6 (2026-10-02)

Agent: perf-reviewer (c6). HEAD `30970c9e`. ID prefix `PR6-`. Read-only review; no Gradle or gate run.

Scope: performance, concurrency, thread safety, and CPU/memory/allocation on hot paths. I covered the
cycle-5 delta (`ea7d4374..30970c9e`, 32 main-source files) plus these standing hot paths:

- `gl/GlPipeline.kt`: `drawFrame` and `runAnalysisReadback`, the analysis generation executor, and
  the FrameGap evidence path.
- `gl/FlipRenderer.kt`: the per-draw path.
- `camera/CameraController.kt`: the per-frame repeating-result callback, the still callbacks, and
  `applyControlsOnCamera`.
- `camera/CameraEngine.kt`: the AGG5-5 invalidation order, the AGG5-1/26 resume rebind,
  `completeGlInputReady`, still admission publication, and the BURST/AEB head result.
- `camera/RetainedStillDeletionOwner.kt`, `DngPreCaptureAllocation.kt`,
  `RecordingStorageDispatcher.kt`, `CameraStatus.kt` (StatusPlate), `DiagnosticTelemetry.kt`, and
  `VendorTagInspector.kt`.
- `ui/CameraViewModel.kt`: the analysis fan-out, the status plate and its timers, the Ready fold,
  the route-inventory fold, the stab/fps request split, and encoder inventory loading.
- `ui/ZoomMath.kt`, `ui/controls/ProSheet.kt`, `ManualDials.kt`, and `ui/overlays/Overlays.kt`:
  composition-time math and draw allocations.
- `video/VideoRecorder.kt`: the AGG5-3 cleanup classification and the audio and drain loops.
- `video/EncoderCaps.kt`: the AGG5-30 retry loader.
- `capture/StillCapturePipeline.kt` and `HeifExif.kt`: the AGG5-34/35 buffers and the single EXIF
  compose.
- `storage/MediaStoreWriter.kt`: the AGG5-7/8 probes, the JPEG structure probe, and the AGG5-27
  owner.
- The executor/thread inventory across `app/src/main/kotlin`.

I checked the AGG5 fixes in these areas against their findings: AGG5-5/27/30/34/35/37/9/1/26/3/4.
AGG5-5 (Ready cleared before the DNG cancel), AGG5-27 (the queue sized to the permits), and
AGG5-34/35 (pre-sized in-place JPEG buffer, one EXIF compose per shot) are correct as written. The
hot GL, renderer, and per-frame result paths have no new allocation or locking regressions. Earlier
cycles already removed the boxing, readback, and recomposition costs there, and nothing in cycle 5
reintroduced them. Nothing below repeats a tracked AGG5 item unless it adds new evidence. Two
findings (PR6-2, PR6-3) are incomplete edges of cycle-5 fixes (AGG5-30, AGG5-2/MRG5-5).

## Summary

| ID | Severity | Confidence | Status | One line |
|---|---|---|---|---|
| PR6-1 | Low | Medium | Confirmed (code) | `markStillProducersTerminal` decides whether to republish from a `canAdmitCapture()` read taken before the mark. If a failed delete marker for the same id lands in between, the shutter publication stays at `false` while live admission is `true` |
| PR6-2 | Low | High | Confirmed (code) | AGG5-30 incomplete. A load that exhausts its retries returns `CodecInventory.EMPTY`, and the ViewModel applies it as authoritative: it clears the pending request, sets `encoderInventoryLoaded = true`, and the next save persists HEIF→JPEG and HLG→SDR (the AGG-34 loss through a new door) |
| PR6-3 | Low | Low-Medium | Needs manual validation | `producerTerminalIds` evicts after 32 ids. A failed durable marker for a capture whose producer edge has already been evicted re-adds the AGG5-2 latch for that id, with no edge left to clear it. Still admission then stays closed until Engine release |
| PR6-4 | Low | Medium | Needs device validation | The AGG5-4 request/wire split makes `reconcileFrameRate` push a narrowed or restored fps through `engine.setControls` on every route change in both directions. Each push is a FULL_REBUILD, about 180 ms of repeating-request stall right after Ready, and caps then videoSize can flip it twice |
| PR6-5 | Info | Medium | Confirmed (code) | AGG5-37 moved the debug capability dump onto the SHARED recurring row owner. Every debug cold start now spends about 25–40 of the 168 shared rows, 5 s after launch, before any soak producer runs |
| PR6-6 | Info | Medium | Confirmed (code) | The AGG5-3 `*CodecErrorLatched` flags are set for ANY drain/audio thread exception, including a mic read failure or a muxer write throw on a healthy codec. The "errored codec" evidence is wider than its KDoc claims |

Counts: Critical 0, High 0, Medium 0, Low 4, Info 2.

## Findings

### PR6-1: A TOCTOU on the producer-terminal republish gate can leave the shutter disabled while admission is open

- **Severity / Confidence / Status:** Low / Medium / Confirmed by code read. The interleaving is
  narrow but legal.
- **Where:**
  - `camera/CameraEngine.kt:5839-5845`: `markStillProducersTerminal`.
  - `camera/CameraEngine.kt:5580-5589`: the marker task, which runs
    `completeDeletionDurability(liveStillId, false)` and then `publishProcessStillAdmission()` in
    `finally`.
  - `camera/RetainedStillDeletionOwner.kt:112-123` and `:129-134`.
  - `camera/DngPreCaptureAllocation.kt:26-38`: `StillAdmissionPublication.publish`, which is
    change-gated on the delivered value.
  - `camera/CameraState.kt:1894-1899`: `stillCaptureReady` and `primaryShutterHealthy` read the
    delivered `stillCaptureAdmissionAvailable`.
- **Why:** `wasAdmitting` is read in one owner-lock acquisition, and the mark happens in a second.
  The family-deletion-marker worker (T2) can run its whole close-and-publish sequence between the
  two:
  1. T1 (still save lane, producer terminal for capture X) reads `wasAdmitting = true`.
  2. T2 (marker worker) runs `completeDeletionDurability(X, false)`. X is not yet in
     `producerTerminalIds`, so it adds X to `nonDurableLiveDeletions`.
  3. T2's `finally` publishes. The snapshot is `false`, so `false` is delivered and the shutter
     closes.
  4. T1's `markCaptureProducersTerminal(X)` removes X, so admission is live `true` again.
  5. `!wasAdmitting` is false, so T1 never publishes.

  The publication now holds `false` while the owners say `true`. Later `markStillProducersTerminal`
  calls read `wasAdmitting = true` and stay silent too. The only things that repair the state are an
  unrelated admission edge (a DNG slot, storage capacity, or a family retirement task) or Engine
  re-creation.
- **Failure scenario:** The operator deletes the just-shot photo from review while its DNG/HEIF tail
  is finishing, on a nearly full disk, so the durable marker fails. Both shutter surfaces then render
  disabled (`primaryShutterEnabled`) until some unrelated admission edge fires. That can be
  indefinite on a photo-only session, because no capture can run to cause one.
- **Fix:** Drop the `wasAdmitting` gate and always call `publishProcessStillAdmission()` after the
  mark. `StillAdmissionPublication` already re-reads live state under its own lock and is
  change-gated, so an unconditional publish costs one lock and a three-owner read, and nothing is
  delivered when the value did not change. Alternatively, have `markCaptureProducersTerminal` return
  "admission reopened" computed inside the owner lock. Add a two-latch interleaving test.

### PR6-2: An exhausted codec scan is applied as the real inventory and persists degraded HEIF/HLG choices (AGG5-30 incomplete)

- **Severity / Confidence / Status:** Low / High / Confirmed by code read. Rare trigger (three failed
  `MediaCodecList` walks within about 750 ms), permanent effect.
- **Where:**
  - `video/EncoderCaps.kt:124-143`: an exhausted `load()` returns `CodecInventory.EMPTY` without
    latching, and `isLoaded()` stays false.
  - `ui/CameraViewModel.kt:3042-3055`: `loadEncoderInventoryAsync` applies whatever `load()` returned,
    without checking `isLoaded()`.
  - `ui/CameraViewModel.kt:3058-3096`: `applyEncoderInventory`. It sets
    `pending*UntilInventory = null` (`:3064-3066`), normalizes the transfer against
    `tenBitEncodeAvailable = false` and the formats against `heifEncodeAvailable = false`, pushes
    `engine.setVideoPipeline`/`setRawWanted`, and sets `encoderInventoryLoaded = true` (`:3086`).
  - `ui/CameraViewModel.kt:1841-1850`: `currentExtras` protects the pending request only while
    `!encoderInventoryLoaded`.
- **Why:** AGG5-30's stated contract is that a failed walk does not latch, so a later load walks
  again. The loader keeps that contract, but its only consumer cannot tell "no encoders on this
  device" from "the walk failed". It consumes the operator's pending request, writes the degraded
  placeholders into state, and marks the inventory loaded. The next `saveSettingsIfEnabled` or
  background save then persists them. This is the AGG-34 loss (HEIF→JPEG, HLG/10-bit→SDR) through
  the retry-exhausted door. The next ViewModel's successful walk cannot restore those choices,
  because the request is gone from disk.
- **Failure scenario:** mediaserver restarts during the cold-start scan, for example right after an
  update. The operator's persisted HEIF + HLG setup comes back as JPEG + SDR on every later launch,
  with no status message. Only one `EncoderCaps` warning row exists, and only in the reserved log.
- **Fix:** After `load()`, check `EncoderCaps.isLoaded()`. If it is false, leave `pending*` and
  `encoderInventoryLoaded` untouched and schedule a bounded retry (on the next `onStart`, or a single
  delayed retry on `ioExecutor`). Alternatively, return a typed `Loaded(inventory)` / `Failed` from
  the loader so the ViewModel cannot treat a failure as inventory. Add a ViewModel test that drives
  a failing scan and asserts that `currentExtras()` still carries the pending HEIF/HLG request.

### PR6-3: The bounded producer-terminal memory can re-close still admission for an old pinned capture (AGG5-2/MRG5-5 edge)

- **Severity / Confidence / Status:** Low / Low-Medium / Needs manual validation. Whether review can
  keep an old capture pinned while 32 or more newer captures complete depends on the UI flow, for
  example timelapse or BURST while review is open.
- **Where:**
  - `camera/RetainedStillDeletionOwner.kt:129-134`: `producerTerminalIds` is trimmed oldest-first to
    `maxTombstones = 32` (`camera/CameraEngine.kt:8392`).
  - `camera/RetainedStillDeletionOwner.kt:112-123`: a failed marker for an id that is not in
    `producerTerminalIds` adds that id to `nonDurableLiveDeletions`.
  - `camera/RetainedStillDeletionOwner.kt:264-265`: `canAdmitCapture()` requires
    `nonDurableLiveDeletions.isEmpty()`.
- **Why:** MRG5-5 records producer terminality per id so that a failed marker arriving AFTER the
  terminal edge does not close admission. That memory is capped at 32 ids. For a capture whose
  terminal edge is older than the last 32 terminal ids, the original AGG5-2 behavior returns: the
  failed marker adds the id, no producer edge for it will ever come again, and only a later
  successful marker for the same id clears it. There is no retry for that id.
  `BURST_COUNT = 5` means seven bursts are enough to evict an id.
- **Failure scenario:** Review is open and pinned on capture N. While it is open, 32 or more newer
  shots complete, for example a timelapse running. The operator then deletes N on a full disk, so
  the marker fails. Every later still is refused for the Engine's lifetime, which is the AGG5-2
  symptom.
- **Fix:** Ask the right question instead of keeping a bounded history. Treat an id below the
  owner's lowest retained producer-terminal id, and not in the live family registry, as terminal.
  Capture ids are monotonic, so "evicted from `producerTerminalIds`" implies "older than its oldest
  member". Alternatively, record the newest evicted id as a low-water mark and treat
  `captureId <= lowWater` as terminal. Add a test: 33 terminal edges, then a failed marker for the
  first id, then assert that `canAdmitCapture()` is true.

### PR6-4: The fps request/wire split costs a full repeating-request rebuild per route switch, in both directions

- **Severity / Confidence / Status:** Low / Medium / Needs device validation. Whether the Photo
  logical route or FRONT excludes the requested rate at the chosen size on PMA110 is unmeasured.
- **Where:**
  - `ui/CameraViewModel.kt:3415-3426`: `reconcileFrameRate` targets `requestedVideoFrameRate` or the
    nearest allowed rate.
  - `ui/CameraViewModel.kt:3384-3398`: `applyVideoFrameRate` calls `engine.setControls(controls)`
    with the new `fps`.
  - Callers: `onCapsReady` (`:914-918`) and `onVideoSizeChosen` (`:926-930`).
  - `camera/CameraController.kt:1446-1455`: an fps delta is not a sensor-only delta, so
    `controlsApplyPlan` takes FULL_REBUILD (`startPreview`, the 170–250 ms repeating-request swap
    CLAUDE.md measured).
- **Why:** Before AGG5-4, narrowing ratcheted the stored value once, and later trips found it
  already deliverable, so nothing was pushed. Now the request survives (which is correct), so every
  trip to a route that cannot deliver it pushes the narrowed fps, and every trip back pushes the
  request again. Both pushes land on the main queue after the new route's caps, normally after the
  route's own first `startPreview`, so each one is a second repeating-request swap right after
  Ready. Caps arrive with the OLD `videoResolution`, and `onVideoSizeChosen` follows with the new
  size. When the allowed set differs between those two sizes, one route change pushes twice.
- **Failure scenario:** The operator records 60 fps on the tele and checks framing on FRONT (or a
  route whose size × codec lacks 60 fps). Every switch shows a visible ~180–250 ms preview hitch
  just after the new route comes up, in each direction.
- **Fix:** Keep the request/wire split, but defer the push. Have `reconcileFrameRate` update only
  the displayed value and the engine's `videoFrameRate` while the route is not recording-capable or
  `!cameraReady`. Let the Engine's own route commit carry `fps` inside the reconfigure packet: it
  already normalizes controls per route at `setControls`/commit, so the first `startPreview` builds
  with the right range. Also coalesce `onCapsReady` + `onVideoSizeChosen` into one reconcile per
  optics generation. Device check: count `startPreview` rebuilds (3A `requestGeneration` steps) on
  one Video tele→FRONT→tele trip at 60 fps, before and after.

### PR6-5: The debug capability dump now spends about 20% of the shared recurring log rows at every cold start

- **Severity / Confidence / Status:** Info / Medium / Confirmed by code read. Debug builds only, but
  this budget is the evidence channel FIELD_CHECKS soaks read.
- **Where:**
  - `camera/VendorTagInspector.kt:46-63` and `:268-276`: two rows per camera id plus two per physical
    sub-camera, plus `ConcurrentMandatory`/`ConcurrentProbe` rows for each PIP id and profile, all
    through `DiagnosticLog.i`, which charges the shared recurring owner.
  - `camera/CameraEngine.kt:2113-2124`: the dump runs 5 s after every debug cold start.
  - `camera/DiagnosticTelemetry.kt:36-46`: the shared owner holds 168 rows.
- **Why:** AGG5-37 correctly stopped the dump from draining the 120-row warning reserve on every
  Engine start, and `claimDump` makes it run once per process. But each new process (every
  `am start` in a field check) now pays roughly 25–40 shared rows before the 3A heartbeat, ZoomTrace,
  periodic FrameGap summaries, ZSL, and focus traces run. Those producers were budgeted against the
  full 168 rows; the ten-minute A5 soak alone plans 41 3A rows. The terminal FrameGap and StartupTrace
  rows keep their 12-row evidence reserve, but the periodic evidence does not.
- **Fix:** Gate the dump behind an explicit debug switch, such as the existing
  `getExternalFilesDir()/…` flag-file idiom or a system property, instead of running it on every
  debug launch. Alternatively, give it its own small owner outside the 168 shared rows and lower the
  shared allowance by the same amount, so the 300-row total stays exact.

### PR6-6: The AGG5-3 "codec errored" evidence is wider than "the codec failed"

- **Severity / Confidence / Status:** Info / Medium / Confirmed by code read. No device consequence
  is known.
- **Where:** `video/VideoRecorder.kt:668-673` (video drain `catch (t: Exception)` →
  `videoCodecErrorLatched.set(true)`) and `:810-814` (audio worker `catch` →
  `audioCodecErrorLatched.set(true)`). Compare the KDoc at `:209-216` and `:2172-2180` ("its drain
  thread ended on a thrown codec/muxer call").
- **Why:** The audio worker also throws on a mid-REC negative `AudioRecord.read` (the AGG3-2
  video-only degrade) while the AAC codec is healthy and Executing. The video drain catch also covers
  a muxer `writeSampleData` throw, where the codec is healthy too. Both set the latch. If a later
  `stop()` on such a codec throws an `IllegalStateException` for some other reason, it is classified
  `SkippedToRelease` instead of `Failed`. That is the only classification branch AGG5-3 meant to
  open only for a codec in the documented Error state. Today the consequence is benign, because a
  healthy codec's `stop()` does not throw. But the latch no longer means what the decision table and
  its tests assume.
- **Fix:** Latch only on a throw from a `MediaCodec` call. Either wrap the
  `dequeueOutputBuffer`/`queueInputBuffer`/`getOutputBuffer`/`releaseOutputBuffer` calls, or catch
  `MediaCodec.CodecException` / an `IllegalStateException` raised by those calls specifically. Leave
  `audioReadFailure` and muxer throws unlatched. Alternatively, amend the KDoc to state the wider
  rule.

## Final sweep (no new finding)

- **Per-frame callback** (`CameraController.kt:1072-1213`): unchanged apart from the `android.util.Log`
  aliasing. Every row is gated before string building. ZoomTrace and 3A are change-gated and charged
  once (AGG5-9 holds).
- **GL draw/readback:** the per-readback allocations are the `digitalGainDisplayLut` 256-int LUT
  (only when gain > 1) and four 256-bin histogram arrays, about 5 KB at about 6 Hz. That is
  negligible and unchanged. The busy-gate ordering of `bytes` holds.
- **StatusPlate** (`CameraViewModel.kt:1992-2063`): `publishStatus` now always calls
  `_state.update`. `CameraUiState` equality suppresses no-op emissions, and status traffic is
  event-rate, not frame-rate. Timers use `postAtTime` under the status monitor, and a re-arm happens
  only on restore. No leak.
- **Executor inventory:** no new executor or thread in cycle 5. The `CodecInventoryLoader` retry sleeps
  (250 + 500 ms) run on `vm-io` ahead of the latest-capture restore. That only matters on the
  already-failing path (see PR6-2).
- **AGG5-5 order:** `cancelDngPreCaptureAllocations` now runs after the monitor clears
  `acceptedCameraSession`, so a settle's `fire()` sees `acceptedSessionIsCurrent == false`.
  `DngPreCaptureAllocation.start` returns SHUTDOWN for a cancelled attempt, and the dispatched body
  returns for a retired one. Closed.
- **AGG5-1/26 resume rebind:** `previewReady` is not cleared by `pause()`, so the ordinary foreground
  return takes neither branch, and the "byte-identical" claim holds. A rebind racing an in-flight
  bind is the documented same-surface swap.
