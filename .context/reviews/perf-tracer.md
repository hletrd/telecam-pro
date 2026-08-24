# Performance and causal-tracing review — cycle 41

Date: 2026-08-24
Reviewed revision: `4e4c9dfbce294fb2965a56ea63d74d6096744836` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle41.nWoiMj`

## Scope, inventory, and method

I read the repository authority in `CLAUDE.md`, the complete as-built map in
`docs/ARCHITECTURE.md`, and the committed device-evidence boundary in `docs/FIELD_CHECKS.md`.
`docs/BACKLOG.md` is absent from this clean clone and is explicitly optional under the repository
rules. I inventoried all 499 tracked paths, including all 102 production Kotlin/Java files, 232
test/debug/instrumentation files, Android resources and build inputs, host/device tooling, current
plans, and retained review provenance. Historical reviews were used as regression oracles rather
than copied forward.

The performance pass traced the complete runtime graph: Camera2 enumeration/open/configure,
repeating-result and pseudo-ZSL traffic, controls/zoom fast paths, capture watchdog and still
snapshot ownership, GL frame coalescing/preview/encoder/analysis work, gyro ingestion, image
encoding and publication, recorder allocation/codec/audio/muxer/finalization, process-wide provider
lanes, launch recovery, review decode/playback, ViewModel input/ticker/persistence work, Compose
publication, and lifecycle teardown/replacement. Repository-wide searches enumerated every
executor, Handler, thread, delayed task, blocking wait, retry loop, lock/atomic owner, queue and
mutable collection; their admission, cardinality, timeout, retirement, and shutdown paths were then
checked against callers and tests.

The causal pass followed optics/session generations and rollback, preview-window/GL identity,
Camera2 capture-result-to-image correlation, ZSL image/result pairing, still-family producer and
delete-marker ownership, recorder admission and native quarantine, provider recovery, exact review
handle publication, standby-microphone handoff, and lifecycle callback detachment. The cycle-40
change surface was separately inspected for hot-path or ownership regressions.

## Findings

No new performance, concurrency, lifecycle/resource-leak, or causal state-consistency defect
survived validation at current HEAD.

The cycle-40 warning cleanup does not add runtime work to the camera/GL/encoder paths. The new
`SharedPreferencesDurableEdit` bridge preserves the existing synchronous Boolean-returning commits
on their established provider/storage workers (`storage/MediaStoreWriter.kt:430-440,1225-1236,
1409-1413`); the KTX bitmap construction remains one review-only allocation; the accessibility and
system-bar changes are event/composition setup work; and release resource shrinking affects only
packaging.

## Competing hypotheses checked

1. **A Camera2 or GL producer can outpace consumers and grow work without bound:** rejected. Frame
   notifications retain one scheduled draw plus one latest dirty follow-up
   (`gl/FrameNotificationCoalescer.kt:17-50`); analysis is single-flight per immutable GL generation
   with reused direct/heap buffers and a bounded 256-pixel target
   (`gl/GlPipeline.kt:213-261,1410-1545`); logical/front ZSL owns a three-image ring and six recent
   results (`camera/CameraController.kt:1360-1475`). Zoom and controls keep their 16 ms/40 ms/200 ms
   leading-plus-trailing pacing rather than enqueueing each input event.
2. **Provider or native wedges multiply workers across Engine/ViewModel recreation:** rejected.
   Recording pre-allocation, still publication, deletion marking/discard, recording storage,
   ViewModel deletion, launch recovery, and review-media blocking work all use fixed process-wide
   capacity. Per-generation facades shut only their admission; accepted tasks retain exact compact
   ownership. Overflow either leaves a durable pending row/tombstone for recovery or keeps one
   conflated rescan signal. Retryable family-retirement results arm the constant-memory delayed
   process scan (`camera/RetainedStillDiscardDispatcher.kt:292-478`).
3. **A late capture/save/delete result can resurrect stale UI or deleted media:** rejected. Still
   output identity is capture-id/family-key owned, producer leases and durable tombstones precede
   asynchronous deletion, registry reconciliation is exact-family and idempotent, and released
   Engine listeners are unregistered. Review descriptor/decode/playback lanes replace publication
   identity immediately and eagerly dispose unpublished `Bitmap`, `MediaPlayer`, and `Surface`
   results (`ui/review/LatestHeavyWorkLane.kt:27-281`; `ui/review/MediaReview.kt:187-362`).
4. **REC stop/release can race EGL or vendor-native owners:** rejected. Encoder detach precedes
   native recorder release, setup and finalization each have first-wins terminal owners plus hard
   deadlines, process admission is closed before quarantine publication, and uncertain graphs are
   retained rather than released concurrently (`camera/CameraEngine.kt:5136-6237`;
   `camera/RecordingTeardownCoordinator.kt:31-291`; `video/VideoRecorder.kt:307-553`).
5. **Lifecycle teardown leaves recurrent work or blocks the main thread:** rejected. ViewModel stop
   invalidates generations and removes all level/orientation/info/record/zoom/countdown work; Engine
   pause serializes Camera2 close off-main; `onCleared` detaches callback graphs and transfers the
   bounded Engine release to a dedicated thread (`ui/CameraViewModel.kt:3888-3974`). Activity-owned
   timelapse dimming is lifecycle-collected and always restores brightness in its `finally`
   (`MainActivity.kt:156-208,278-293`).
6. **Standby audio can overlap REC, leak an AudioRecord, or retry-spin:** rejected. One ownership
   generation publishes its release latch before thread launch, REC claims the process microphone
   before native setup, stop is exactly-once and off-main, release follows stop, failure retries are
   bounded, and process-busy retries are delayed and recheck live intent
   (`camera/StandbyAudioController.kt:83-231,376-654`).
7. **The current open field checks hide a code defect:** rejected. A3, A4, D1, E1, and E2 require
   scene/device/provider evidence by contract. None supplies contradictory current-code evidence;
   no device success was inferred from host structure.

## Final commonly missed sweep

- Every production queue or retained collection on an asynchronous boundary is fixed-size,
  conflated, sequence-paced, or process-terminal by explicit policy. No growing listener registry,
  uncancelled recurring timer, per-frame thread creation, busy-spin, or release-missing native owner
  survived the sweep.
- Blocking joins and waits have finite deadlines, preserve interruption where applicable, and avoid
  self-thread waits. Timeouts classify uncertain Camera2/GL/codec resources as quarantined rather
  than authorizing replacement from elapsed time alone.
- High-cost full-resolution work remains off main/camera/GL threads. Logical YUV packing uses row
  copies on the camera callback and defers JPEG/HEIF encode; sequence drives pace the next capture
  from save completion; RAW-only single publication and post-native video storage use finite lanes.
- Tests cover the pure admission, terminal, generation, queue-overflow, timeout, and race seams for
  these flows. Comments and tests were not treated as proof where a platform call remains
  framework- or device-bound.

## Coverage confirmation

All production files relevant to performance, concurrency, Camera2/GL/codec ownership, storage,
review, lifecycle, and UI publication were inventoried and examined, together with their direct
tests and cross-file callers. The final sweep found no skipped runtime subsystem.

## Totals

- New findings: 0
- Confirmed regressions: 0
- Manual/device-only residuals: the five explicitly open checks in `docs/FIELD_CHECKS.md`; none is
  reclassified as a code finding here.
