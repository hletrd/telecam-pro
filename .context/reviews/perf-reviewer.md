# Performance review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and inventory

I read `CLAUDE.md` completely, then the committed current authorities
`docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`. I inventoried all 471 repository paths and
reviewed the complete production/runtime surface under `app/src/main`, the Android/debug entry
points, build configuration, Python release/verification tools, and the host/instrumented tests
that define their contracts. I treated the historical `.context/reviews/*` and completed
`docs/plans/*` as resolved provenance, not a source of current findings.

The performance pass followed every standing thread and queue named by the architecture: main/UI
handlers and Compose state, Camera2 handler, setup and still-I/O executors, GL/frame-notification
and analysis generations, audio capture and codec drains, recorder allocation/finalization/storage
owners, launch recovery, family deletion/retirement, review decode/player lanes, timers, and
process-lifetime bounded dispatchers. I also swept the entire source inventory for executors,
blocking waits/joins, monitors, atomics, queues/collections, native/provider I/O, per-frame/per-sample
allocation, repeated logging, and self-rescheduling work. The cycle-34 diff was inspected
separately so a newly introduced hot-path regression could not hide behind the repository's
extensive resolved history.

## Finding

### PERF35-01 — raw held peaks defeat the audio meter's root-state dedup even when nothing visible or accessible changes

- **Severity / confidence:** Low / High.
- **Status:** Confirmed performance mechanism; device profiling would quantify the exact frame-time
  cost, but is not required to establish the redundant publications.
- **Exact evidence:**
  - Both producers deliberately emit at about 10 Hz and carry a new held peak snapshot:
    `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:494-541` and
    `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:771-812`.
  - `CameraViewModel` quantizes both RMS and peak arrays to 1/256, then includes both lists in the
    equality gate for the root `CameraUiState` at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1008-1027`.
  - The bar pixels consume only `audioLevels`; `audioPeakLevels` is passed alongside it at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:1173-1180`, and `AudioMeter`
    consumes peaks only to select the coarse accessibility categories at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:608-624,660-679`.
  - `CameraUiState` nevertheless stores the raw quantized peak list as ordinary root state at
    `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:1324-1332`.
- **Why this is a problem:** The preceding RMS optimization explicitly exists because sensor-rate
  float jitter caused ten whole-state publications/recompositions per second for an unchanged
  120×8 dp meter. The new peak list reopens the same door. Two frames can have identical quantized
  RMS and the same accessibility category while their sub-threshold peaks differ (for example RMS
  `0.50`, peaks `0.20` then `0.25`). The Canvas is bit-identical and both semantic results are
  `SIGNAL`, yet `audioPeakLevels` differs, so `_state.update` copies/publishes the entire
  `CameraUiState`. A maximum over thousands of live PCM samples naturally varies between emission
  windows, making this a standing 10 Hz invalidation source while the standby meter is visible and
  while recording.
- **Concrete failure scenario:** Arm Video with the detailed meter visible in a steady room. RMS
  remains inside one 1/256 bucket, but successive held maxima move among sub-threshold buckets.
  Compose observes a new root state and recomposes the camera screen even though neither the drawn
  RMS bar nor TalkBack's coarse state changed. Multi-channel inputs multiply the chance that at
  least one peak bucket changes, directly undoing the rationale documented at
  `CameraViewModel.kt:1009-1016`.
- **Suggested fix:** Reduce peak truth to the information the consumer actually renders before it
  enters root state: a per-channel overload enum/bitset (`NORMAL`, `NEAR_CLIPPING`, `CLIPPING`),
  with threshold-preserving classification performed on the unquantized producer peak. Equality-gate
  that coarse value together with quantized RMS. Alternatively keep exact peaks outside the broad
  `CameraUiState` and expose a separately scoped meter state whose equality is based on RMS buckets
  plus overload categories. Add a ViewModel-level test proving sub-threshold peak variation with
  stable RMS/category does not publish a replacement state, while threshold crossings do.

## Verified non-findings and final sweep

- The prior stale dual-open setup-lane wait is fixed: the new 20 ms ownership slices retain the
  absolute two-second HAL deadline and let newer optics/lifecycle state retire the wait. Late device
  cleanup remains in the existing exact-owner branches.
- Frame notifications remain latest-frame coalesced; analysis stays generation-owned, single-flight,
  and bounded to a 256 px long edge. Preview-only redraws still cannot enter encoder/analysis work.
- Process-wide provider/native lanes retain fixed worker and backlog ceilings; review decode/player
  work retains its two-worker latest-wins ownership and five-second first-wins UI terminals.
- Still SINGLE admission remains bounded, while burst/AEB/timelapse chain the next capture from the
  preceding terminal rather than enqueueing full-resolution work eagerly.
- Recorder joins, Camera2 close proof, GL stop, launch recovery, standby AudioRecord recreation,
  and retained-family retry paths remain bounded or deliberately quarantine a native owner rather
  than multiplying workers/resources.
- No new unbounded collection, per-frame diagnostic flood, main-thread CameraService/MediaProvider
  call, or unresolved CPU/memory growth path survived the missed-file sweep.

## Totals

- New findings: 1
- Severity: 1 Low
- Confidence: 1 High
