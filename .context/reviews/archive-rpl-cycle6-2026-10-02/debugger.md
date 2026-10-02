# RPL cycle 6 — debugger review (DB6)

- Agent: debugger (latent bug surface / failure modes)
- HEAD: 30970c9e (cycle-5 range ea7d4374..30970c9e, 65 commits)
- Mode: read-only. No Gradle, no state-changing git.

## Scope inventory

My angle covers exception paths, edge-case math, resource leaks, watchdog and timeout arithmetic, and
state machines that can wedge. I read every cycle-5 diff in that surface first, then went back over
the long-standing hot spots:

| Area | Files examined |
|---|---|
| Video / codec teardown | `video/VideoRecorder.kt` (setup ladder, `stopNative`, `codecCleanup`, drain/audio workers), `video/EncoderCaps.kt` (new `CodecInventoryLoader`) |
| Storage / recovery | `storage/MediaStoreWriter.kt` (COMPLETE length verdict, `recoveryVideoVerdict` re-parse, `probeCompleteJpegStructure`, `RejectedOutputCleanupCapacityOwner` queue sizing) |
| Still capture | `camera/CameraController.kt` (`failStill`, buffer-lost/sequence-aborted, `tryComplete`, `onImage`, watchdog), `camera/CameraEngine.kt` photo callback / DNG dispatch / BURST-AEB head, `capture/StillCapturePipeline.kt` (single EXIF composition, in-place JPEG buffer, passthrough strip), `capture/HeifExif.kt` (`exifSplicePlan(length)`), `capture/StillSnapshot.kt` |
| Ownership state machines | `camera/RetainedStillDeletionOwner.kt` (per-id latch), `camera/DngPreCaptureAllocation.kt` (retired-attempt dispatch, admission refresh), `camera/RecordingStorageDispatcher.kt` (stale presentation), `ui/MomentaryHold.kt` |
| Math | `ui/ZoomMath.kt` (`zoomDisplayMultiplier`, `ZoomRulerScale`), `ui/controls/ManualDials.kt` (`isoStops`/`shutterStops`), `camera/AutoExposure.kt`, `camera/ManualControls.kt` (`captureWatchdogTimeoutMs`) |
| GL | `gl/GlPipeline.kt` (analysis readback containment, terminal FrameGap evidence row) |
| Consumers | `ui/CameraViewModel.kt` (`loadEncoderInventoryAsync`, `applyEncoderInventory`, `currentExtras`), `ui/CaptureOutputTracker.kt` (pinned review, `liveStillCaptureId`) |

Refuted while tracing:

- **A late sibling Image after a buffer-lost failure is adopted into the next shot.** `failStill`
  releases the shutter while the surviving target's Image is still in flight. If that Image lands
  before the next shot's `onCaptureStarted`, the next shot does adopt it blind
  (`expected == null`). But `onCaptureStarted` then closes any adopted Image whose timestamp does
  not match (`CameraController.kt:2160-2171`), and `tryComplete` checks timestamps again. Safe.
- **`ZoomRulerScale.localForDisplay` can throw from `coerceIn` when `lowerLocal > hi/base`.** Every
  caller is gated on `scale.enabled`. Safe.
- **`processedJpegInitialCapacity` overflows.** It computes in `Long` and caps below the array
  limit. Safe.
- **`probeCompleteJpegStructure` loops.** Each iteration advances by at least 1 byte, and the loop
  is bounded by `MAX_JPEG_HEADER_SEGMENTS`. Safe.

## Findings

| ID | Severity | Confidence | Status | Summary | Primary cite |
|---|---|---|---|---|---|
| DB6-1 | Medium | High | confirmed | A codec walk that fails all retries returns `CodecInventory.EMPTY`, and the ViewModel applies it as the device's real answer. HEIF→JPEG and HLG/log→SDR are persisted for good, and REC has no candidates for the rest of the ViewModel. The AGG5-30 "does not latch" fix is defeated one layer up. | `video/EncoderCaps.kt:123-143`; `ui/CameraViewModel.kt:3043-3096, 1841-1849` |
| DB6-2 | Low | Medium | likely | `producerTerminalIds` is capped at 32 entries. A failed marker for an id that was already evicted, with no family entry, closes still admission until the Engine dies. The AGG5-2 latch comes back on the eviction edge. | `camera/RetainedStillDeletionOwner.kt:67, 113-135` |
| DB6-3 | Low | Medium | needs manual validation | AGG5-3 is still open on one race. The codec error is latched only by the drain thread, so a Stop whose `signalEndOfInputStream()` throws `CodecException` before the drain thread sees the error still quarantines the process. | `video/VideoRecorder.kt:218-232, 466-476, 668-673, 2181-2200` |
| DB6-4 | Low | Medium | likely (latent) | A throw inside the Engine `onPhoto` after the processed save was queued runs `onError` after `onPhoto`'s own `finally`. `onError` calls `finishProcessed()` unconditionally, so the family settles while the HEIF/JPEG save is still running. That marks producers terminal early and fires the next BURST/AEB shot early. | `camera/CameraController.kt:2341-2352`; `camera/CameraEngine.kt:6120-6145` |
| DB6-5 | Low | Medium | likely | AGG5-45 is only partly fixed. A BURST/AEB head with DNG on that the shared allocator refuses synchronously (OVERFLOW/SHUTDOWN) still returns `true` through `StillContinuationHandoff.dispatchResult`. The shutter blinks for a refused head, and the chain recursively walks every remaining shot. | `camera/DngPreCaptureAllocation.kt:229-235`; `camera/CameraEngine.kt:5271-5285` |
| DB6-6 | Info | High | confirmed | The YUV still path still grows a `ByteArrayOutputStream` and copies it with `toByteArray()`. AGG5-34 fixed only the processed lane: about 2× the encoded size is live on the io thread per logical-route still. | `capture/StillSnapshot.kt:55-63` |

---

### DB6-1 — A failed codec walk is applied and persisted as "this device has no encoders" (Medium / High, confirmed)

**Code.** Since AGG5-30, `CodecInventoryLoader.load()` (`video/EncoderCaps.kt:123-143`) does not
latch a failed walk. But after `maxAttempts` throws it still *returns* `CodecInventory.EMPTY`. The
caller cannot tell that apart from a real empty inventory. `loadEncoderInventoryAsync`
(`ui/CameraViewModel.kt:3043-3055`) hands the result straight to `applyEncoderInventory`
(`:3058-3096`), which:

1. clears `pendingCodecUntilInventory`, `pendingTransferUntilInventory` and
   `pendingPhotoFormatsUntilInventory` (`:3064-3066`);
2. normalizes the operator's request against EMPTY: `PhotoFormats.normalizedForEncoder(false)` turns
   HEIF into JPEG (`CameraState.kt:1296-1299`), and `ColorTransfer.normalizedForEncoder(..., false)`
   turns HLG or any log curve into SDR (`CameraState.kt:1282-1289`);
3. sets `encoderInventoryLoaded = true` and publishes `availableVideoCodecs = []`. It also pushes
   `engine.setVideoPipeline(inventory.candidatesFor(...))`, which carries no candidates.

From then on `currentExtras()` (`:1841-1849`) no longer reads the pending fields, because
`inventoryPending` is false. The next background or immediate save therefore persists JPEG/SDR. That
is the AGG-34 loss class, which the pending fields exist to prevent.

**Failure scenario.** mediaserver restarts during the cold-start scan, or a Binder call fails, and
all three walks throw within ~750 ms. The take-home photo format silently becomes JPEG. A saved
S-Log3/HLG curve becomes SDR. Every REC press fails, because `VideoRecorder.start` refuses with "no
encoder candidate". All of this lasts until the ViewModel is recreated, and the format and curve
changes are permanent because they were persisted. The loader's KDoc says "the next load (the next
ViewModel) walks again", but by then the narrowed values are what gets restored.

**Fix.** Make the failure visible to the caller, for example `load(): CodecInventory?` or a sealed
`Loaded/Failed` result. On failure, leave `encoderInventoryLoaded = false` and keep the three pending
fields. Schedule a bounded retry, for example on the next `onStart` or with backoff on `ioExecutor`.
Add a VM test: a throwing scanner must keep `currentExtras()` returning HEIF/HLG.

### DB6-2 — Bounded `producerTerminalIds` can re-latch still admission closed for the Engine (Low / Medium, likely)

**Code.** `completeDeletionDurability(id, durable = false)` adds the id to `nonDurableLiveDeletions`
unless `id in producerTerminalIds` (`RetainedStillDeletionOwner.kt:113-124`). Only a later
`markCaptureProducersTerminal(id)` or a durable marker for that id ever removes it. But
`producerTerminalIds` is trimmed oldest-first to `maxTombstones` (32, `CameraEngine.kt:8392`) at
`:131-134`, and `canAdmitCapture()` requires `nonDurableLiveDeletions.isEmpty()`.

**Failure scenario.** The operator opens review on still X, and review pins X outside the 8-entry
ordinary history. A timelapse or a burst run keeps shooting, and more than 32 further stills reach
producer-terminal, so X falls out of `producerTerminalIds`. The disk is near full, which is exactly
when people delete. The operator deletes X: the tracker still holds `liveStillCaptureId = X`, and
`markFamilyDeletedResult` returns non-durable. X is added to `nonDurableLiveDeletions`, and its
terminal edge already happened, so nothing will ever remove it. Every still is refused until the
Engine is released. That is AGG5-2's symptom on the eviction edge MRG5-5 was meant to close.

**Fix.** Invert the set. Track ids that can still produce, meaning registered and not yet terminal.
That set is naturally bounded by in-flight captures and needs no eviction. Close admission only when
`id in liveProducerIds`. A host test: 33 terminal ids, then a failed marker for the first one, and
`canAdmitCapture()` must stay true.

### DB6-3 — Stop racing a codec error still quarantines the process (Low / Medium, needs manual validation)

**Code.** `codecCleanupDecision` (`VideoRecorder.kt:2181-2200`) skips to `release()` only when
`errorLatched` is true. For the video codec that flag is set only in `drainVideo`'s catch (`:668-673`)
once `dequeueOutputBuffer` throws. The finalizer reads it once, at `:466`.

**Failure scenario.** The encoder fails asynchronously while the drain thread is waiting inside
`dequeueOutputBuffer(TIMEOUT_US)`, and the operator presses Stop, or the engine stops for pause or
thermal reasons. `signalEndOfInputStream()` throws `MediaCodec.CodecException`, which is an
`IllegalStateException`, before the drain thread wakes and latches. The decision is `Failed`, and
`quarantinedNativeStopResult()` makes the process camera-dead until restart, with no live native
owner involved. That is the AGG5-3 outcome. The new `stopAudioInputBeforeQuarantine` does release
the microphone in this path.

**Fix.** Treat a `MediaCodec.CodecException` from `signalEndOfInputStream()`/`stop()` as its own
evidence of the Error state, so set `errorLatched || cause is MediaCodec.CodecException`.
Alternatively, on a non-latched ISE, join the drain thread briefly, for example for one `TIMEOUT_US`
period, and re-read the latch before choosing `Failed`. Device check: inject a codec error with a
concurrent Stop.

### DB6-4 — `onError` after a thrown `onPhoto` settles the family before its queued processed save finishes (Low / Medium, likely, latent)

**Code.** `CameraController.tryComplete` calls `p.cb.onPhoto(...)` and, if that throws,
`p.cb.onError(t)` (`CameraController.kt:2341-2352`). The Engine's `onPhoto` has its own `finally`
(`CameraEngine.kt:6117-6127`) that already does terminal bookkeeping. It calls `finishProcessed()`
only when `!processedQueued`, because the queued ioExecutor job calls `finishProcessed()` itself at
`:6030`. `onError` (`:6130-6145`) then calls `finishProcessed()` unconditionally. Its
`processedFinished` CAS wins, `remainingSaveLanes` reaches 0, and `finishSequence` →
`settleRegisteredStillShot` runs while the HEIF/JPEG save is still on `ioExecutor`.

**Reachability.** Every throw site after `processedQueued = true` is guarded by an invariant today:
the new `checkNotNull(rawChars)` (`:6058-6060`), `checkNotNull(dngAllocation)`,
`check(write.allocation == dngAllocation)`, and the `transferCompletedDngFromCameraCallback`
plumbing. So this is latent. Any future throw, or a broken invariant, turns it into:

- producer-terminal marked and `producerLease.close()` called while a sibling is still being
  written. A concurrent delete then retires the durable family marker early, and the HEIF can be
  adopted by launch recovery after the user deleted it;
- `onDone` firing the next BURST/AEB shot while this processed save still holds its ~50 MB bitmap.
  That breaks the "at most one full processed snapshot queued" bound;
- a duplicate `PHOTO_CAPTURE_FAILED`, and a DNG cleanup submitted for a row that the throw may have
  already transferred.

**Fix.** Let the controller deliver exactly one terminal. Either `tryComplete` does not call
`onError` once `onPhoto` was entered, and the Engine contains its own throws, or `onError` checks a
per-callback "delivered" latch and honours `processedQueued`/`dngPublishQueued` exactly as
`onPhoto`'s `finally` does.

### DB6-5 — A BURST/AEB head with DNG on that the allocator refuses synchronously still answers "dispatched" (Low / Medium, likely)

**Code.** AGG5-45 made `capturePhoto` return the head's dispatch result
(`CameraEngine.kt:5271-5285`). With DNG on, the head goes through `dispatchDngPreCaptureAllocation`.
On a synchronous `OVERFLOW`/`SHUTDOWN`, `start()` retires the attempt. The retirement settles the
shot, `settle()` claims and runs `onDone` (which is `fire(1)`), and `dispatchResult` returns
`!claimed.compareAndSet(false, true)` = **true** (`DngPreCaptureAllocation.kt:229-235`). That is
correct for AGG2-3's continuation ownership, but it is not an answer to "was the head shot taken?".

**Failure scenario.** The shared pre-native allocator is saturated: two recording or DNG workers
plus four backlog tasks. A BURST press with DNG on refuses the head, the shutter still animates
success, and `fire(1..n)` recursively tries each remaining shot on the same stack. On the non-DNG
side the head returns `true` before the controller's asynchronous "Capture already in progress"
refusal. That case is the posted-dispatch contract and is listed here only for completeness.

**Fix.** Return a richer result from the handoff, `(continuationOwned, headDispatched)`. Chains use
the first value and `capturePhoto` uses the second.

### DB6-6 — The YUV still encode still double-buffers (Info / High)

`StillSnapshot.Nv21.jpegBytes()` (`capture/StillSnapshot.kt:55-63`) encodes into
`ByteArrayOutputStream(width * height)` and returns `out.toByteArray()`. That is a second full copy
of a 6–12 MB JPEG on every logical-route or FRONT still, next to the 19 MB NV21 that is released
just before. AGG5-34 fixed only `writeProcessedJpeg`. The same `EncodedJpegBuffer` plus length
pattern, threaded through `saveProcessedStills`, removes the copy. This is perf-adjacent and noted
for the perf lane.

## Final sweep (commonly missed)

- **Watchdog arithmetic** (`captureWatchdogTimeoutMs`): saturating, ceil to ms, and a negative
  exposure is clamped. OK.
- **`isoStops`/`shutterStops`** after AGG5-32: both are bounded for non-positive bounds. The raw `0`
  bound stays a selectable stop, and the request clamp covers it. OK.
- **`AutoExposure.driveProgram`** with `currentNs <= 0`: it degrades to the minimum exposure and
  minimum ISO, with no throw. That only matters with a corrupt persisted value. Not raised.
- **`EncoderCaps`** retry pauses happen while holding `loadLock` (up to ~750 ms). Only `ioExecutor`
  calls `load()`, so this is acceptable.
- **`recoveryVideoVerdict`** re-parse: an `IOException` from a FUSE read on the fresh descriptor is
  classified INDETERMINATE (not re-armed). Each launch probes again, so the only cost is a missed
  expiry re-arm. Not raised.
- **`codecCleanupDecision`** also latches the audio codec on a dead-mic read, where the codec itself
  is healthy. The only effect is that an ISE from `stop()` skips to `release()`, which runs next
  anyway, and a `release()` failure still quarantines. Benign.
