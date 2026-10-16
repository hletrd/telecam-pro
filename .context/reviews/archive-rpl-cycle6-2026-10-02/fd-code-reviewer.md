# FD-CODE-REVIEWER: RPL cycle 6

- Agent: fd-code-reviewer (feature-dev style, confidence-filtered: only issues at 80% confidence or higher)
- HEAD: 30970c9e (cycle 5 = ea7d4374..30970c9e)
- Scope inventory: every cycle-5 change in `capture/` (HeifExif, StillCapturePipeline), `storage/`
  (MediaStoreWriter: AGG5-7 COMPLETE length verdict, AGG5-8 fresh-descriptor reparse, AGG5-27 queue
  sizing, AGG5-71 JPEG structure probe), `video/` (VideoRecorder AGG5-3/AGG5-31, EncoderCaps AGG5-30),
  `camera/CameraController.kt` (AGG5-28 buffer-lost/aborted, AGG5-29 frozen AF override, AGG5-41
  processed-only chars, AGG5-50 no-default plan), plus the still-lane owners they feed:
  RetainedStillDeletionOwner (AGG5-2), RecordingStorageDispatcher (AGG5-39), DngPreCaptureAllocation
  (DB5-13/TE5-12), and the CameraEngine/CameraViewModel/CaptureOutputTracker call sites.
- Checked and found sound: the EXIF splice with a `length` prefix, the lazy one-time EXIF compose, the
  passthrough privacy strip, `failStill` (Surface identity holds because the request targets are
  the same `ImageReader.getSurface()` objects), `stillCompletionMissingCharacteristics` against the
  Engine's `checkNotNull(rawChars)`, `codecCleanupDecision`, the queue/permit match, the
  `probeCompleteJpegStructure` marker grammar, the stale recording presentation, and the DNG
  allocation cancel-after-arm.

## Findings

| ID | Severity | Confidence | Status | Summary | Cite |
|---|---|---|---|---|---|
| FD6-1 | Medium | High | confirmed | AGG5-30 is incomplete: an exhausted codec walk still hands `CodecInventory.EMPTY` to the ViewModel as authoritative. That consumes the pending-until-inventory intent and normalizes HEIF→JPEG and HLG/log→SDR, and the next save persists the change. The "next load walks again" retry never restores it | `video/EncoderCaps.kt:435-459`; `ui/CameraViewModel.kt:3043-3096, 1841-1849` |
| FD6-2 | Medium | High (mechanism) / Medium (reach) | confirmed in code; reach needs manual validation | AGG5-2 fix: `producerTerminalIds` is a bounded set (32) used as proof that a capture's producers have finished. When an old capture is evicted from it, the capture reads as still live, so a failed delete marker for it closes still admission for the Engine's life, which is the defect AGG5-2 was meant to fix | `camera/RetainedStillDeletionOwner.kt:67, 114-124, 128-134` |

## FD6-1: an exhausted encoder walk still overwrites the operator's HEIF and transfer choices

**Where.** `CodecInventoryLoader.load()` (`video/EncoderCaps.kt:435-459`) returns `CodecInventory.EMPTY`
without latching after `CODEC_SCAN_MAX_ATTEMPTS` failed walks. The only caller,
`CameraViewModel.loadEncoderInventoryAsync` (`ui/CameraViewModel.kt:3043-3055`), passes that value
straight to `applyEncoderInventory` (`:3057-3096`). That function:

- clears `pendingCodecUntilInventory`, `pendingTransferUntilInventory`, and
  `pendingPhotoFormatsUntilInventory`;
- normalizes `photoFormats` with `heifEncodeAvailable = false`, which turns HEIF into JPEG;
- normalizes `transfer` with `tenBitEncodeAvailable = false`, which turns HLG/S-Log3/LogC3 into SDR;
- publishes `encoderInventoryLoaded = true`.

Once `encoderInventoryLoaded` is true, `currentExtras()` (`:1841-1849`) stops preferring the pending
request and persists `s.photoFormats` / `s.transfer`, the degraded values. This is the exact window
AGG-34 closed for the pre-inventory placeholder.

**Why it matters.** The cycle-5 KDoc claims the failure leaves "no video/HEIF encoders until a later
load", meaning the next ViewModel walks again. By then, the first background (or any immediate-save
door such as a mode switch) has already committed JPEG/SDR to SharedPreferences. The later successful
walk then restores those degraded values rather than the operator's HEIF + HLG/log selection. In the
same ViewModel there is no retry at all.

**Failure scenario.** Cold start during a mediaserver restart: all three walks throw. The VM applies
EMPTY, the operator backgrounds the app, and the save writes `heif=false, jpeg=true, transfer=SDR`.
Every later launch, with a healthy codec list, shoots JPEG and records SDR. The operator chose
HEIF + S-Log3.Cine and never touched either.

**Fix.** Make the failure distinguishable from a real empty inventory, for example with a
`load(): CodecInventory?` / `Result` that is null on exhaustion. On a failed load:

- do not call `applyEncoderInventory`;
- keep `encoderInventoryLoaded = false`, so the pending fields keep winning in `currentExtras()`;
- reschedule `loadEncoderInventoryAsync` with a bounded backoff, or retry at the next `onStart`.

Add a VM test: a throwing scanner followed by a save must persist the restored HEIF/transfer request.

## FD6-2: `producerTerminalIds` eviction reopens the AGG5-2 process-lifetime admission close

**Where.** `markCaptureProducersTerminal` (`camera/RetainedStillDeletionOwner.kt:128-134`) adds every
finished still id to `producerTerminalIds` and trims it to `maxTombstones` (32,
`CameraEngine.kt:8392`), dropping the oldest first. `completeDeletionDurability(id, durable = false)`
(`:114-124`) adds the id to `nonDurableLiveDeletions` whenever `id !in producerTerminalIds`.
`canAdmitCapture()` requires that set to be empty. The only thing that removes an id from it is a
later `markCaptureProducersTerminal(id)` or a durable marker for the same id. A capture whose producers
finished before it was evicted never gets another terminal edge.

**Why it matters.** Membership in a bounded "terminal" set is used as proof that producers are still
live, and eviction inverts that proof. The deleted capture produces no further output, yet still
admission (shutter, BURST, AEB, timelapse) stays closed for the Engine's life. That is the AGG5-2
symptom ("still capture dead until process restart") returning through a different door. MRG5-5
covered only the family-registry eviction, not this set's own eviction.

**Reachability.** `CaptureOutputTracker` holds a pinned review family outside its 8-entry ordinary
history (`ui/CaptureOutputTracker.kt:213-221, 427-447`), and that id stays in `liveStillFamilies`.
Opening review blocks *input* only: `onCameraInputBlockOwnerChange` (`ui/CameraViewModel.kt:4123-4139`)
cancels a countdown but not a running timelapse. Steps:

1. Start a 1 s timelapse.
2. Open review on the newest frame.
3. After 32 or more ticks (about 35 s), the reviewed id has been evicted from `producerTerminalIds`.
4. Delete it while the family marker write fails. A full disk, which a long timelapse is the likeliest
   way to reach, is the case AGG5-2 itself names.

Result: the id lands in `nonDurableLiveDeletions` permanently. Device timing still needs manual
validation; the code path does not.

**Fix.** Invert the set so it is bounded by what is actually in flight. Track
`liveProducerIds`: add in `registerCaptureFamily` (and wherever a still id is admitted), remove in
`markCaptureProducersTerminal`. Then close only when `durable == false && id in liveProducerIds`. An
evicted or unknown id is then correctly treated as terminal. Equivalently, keep a monotonic
`oldestPossiblyLiveId` watermark. Add a unit test: 33 terminal ids, then a non-durable delete of the
first, must leave `canAdmitCapture()` true.
