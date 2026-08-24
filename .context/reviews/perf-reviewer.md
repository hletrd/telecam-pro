# Performance review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and inventory

I read `CLAUDE.md`, the complete as-built authority in `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`, then inventoried all 489 tracked paths. The review covered all 101 production
Kotlin files, 220 JVM/Robolectric/Compose tests, Android instrumentation/debug sources, resources and
manifests, Gradle/release inputs, Python and shell tooling, the device harness and its tests, and the
committed documentation/review/plan history through completed cycle 36. Prior review findings were
used as regression oracles rather than repeated as current issues.

The runtime pass followed every performance-sensitive lane and cross-file consumer: Camera2 route
enumeration, dual/sequential open, request updates, pseudo-ZSL, capture delivery, and teardown; GL
frame notification coalescing, preview/encoder drawing, bounded analysis readback, and generation
retirement; processed/RAW still snapshot, encoding, publication, deletion, and recovery; recording
allocation, microphone/codec/muxer work, native finalization, and storage tails; ViewModel tickers,
StateFlow publication, zoom/control throttles, and Compose consumers; review decode/player lanes; and
every process-wide provider/native dispatcher. Repository-wide searches covered executor/thread
construction, queue cardinality, waits/joins, retry/backoff loops, per-frame/per-buffer allocation,
logging, caches/collections, lifecycle shutdown, subprocess timeouts, and long-lived helper tools.

## Findings

No new performance finding survived validation.

## Resolved findings distinguished from current behavior

- Cycle 34's stale dual-open wait is resolved: the sole setup lane polls ownership every 20 ms while
  retaining the absolute two-second HAL deadline, so a superseded attempt yields promptly.
- Cycle 35's audio-meter churn is resolved: post-gain peaks become threshold-preserving overload
  categories before root state, while RMS alone is quantized; sub-threshold peak jitter no longer
  republishes the whole `CameraUiState`.
- Cycle 32's retained-family retry/Engine-retention defect is resolved: accepted retryable retirement
  results arm one process-owned conflated exponential retry, live-row results wait for a mutation edge,
  and Engine release unregisters its listener.
- Cycle 36's dual-open correction adds only a lock-free monotonic liveness read at the supersession
  boundary; it introduces no hot-loop, queue, or recurring allocation cost.

## Final missed-issues sweep

- Camera frame delivery remains latest-edge coalesced. Analysis is single-flight per GL generation,
  reads at most a 256-pixel long edge, reuses its direct/byte buffers, and cannot queue work per frame.
- Live zoom submits no Camera2 requests while the gesture moves; ViewModel zoom updates are coalesced
  near 60 Hz, full controls are throttled, and the controller's sensor fast path retains one trailing
  request rather than a per-event queue.
- The pseudo-ZSL ring and result cache remain fixed at three images and six results. Processed SINGLE
  snapshots are capped at two process-wide; burst, AEB, and timelapse advance only after the preceding
  save completes; RAW-only publication uses a fixed two-worker/two-backlog owner.
- Camera setup/close proof, GL stop, recorder setup/detach/finalization, standby microphone recreation,
  launch recovery, family-marker work, retained-still cleanup, recording allocation/storage, review
  deletion, and media review all retain explicit worker/queue/deadline bounds or irreversible native
  quarantine. Engine/ViewModel replacement does not multiply those process owners.
- Main-thread work remains state/UI/lifecycle work plus the explicitly accepted small synchronous
  settings commit. CameraService, MediaProvider, bitmap/HEIF, codec finalization, recovery, and review
  acquisition remain off main in normal operation.
- No additional unbounded production queue or collection, repeated worker creation under a hot path,
  busy-spin, per-frame log flood, main-thread provider/native call, or reproducible CPU/memory growth
  path survived the final repository-wide check. Device-only cost claims remain limited to the
  measurements and open checks already recorded by the repository.

## Totals

- New findings: 0
- Severity: none
- Confidence: High that no additional current performance defect is established by repository
  evidence at this revision.
