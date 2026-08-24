# Performance review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)
Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and inventory

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` first, then inventoried all
486 tracked paths. I reviewed the complete production/runtime surface under `app/src/main`, the
debug and instrumentation entry points, Gradle/release configuration, Python host/release tools,
device-harness code, tests, the current aggregate, and completed plans through cycle 35. Historical
reviews and plans were used to distinguish already-fixed findings from current behavior.

The performance pass covered every documented execution lane and its cross-file consumers:
Compose/root-StateFlow publication and main-handler tickers; Camera2 setup, callback, request, ZSL,
and teardown paths; GL notification coalescing, preview/encoder draws, analysis readback and its
generation executor; still snapshot/encoding/publication/deletion/recovery; recording allocation,
audio/codec drains, native finalization, and storage; review decode/player work; and all process-wide
finite dispatchers. A repository-wide sweep checked executor construction and submission, queues,
scheduled work, waits/joins, loops, provider/native calls, per-frame/per-buffer allocation, logging,
collection bounds, shutdown, and retry/backoff behavior. I separately reviewed every cycle-35
production delta so a newly introduced regression could not hide in previously reviewed code.

## Findings

No new performance finding survived validation.

## Verified behavior and final missed-issues sweep

- The cycle-35 audio change now quantizes only RMS geometry and reduces raw held peaks to
  threshold-preserving overload categories before broad `CameraUiState` equality. Sub-threshold
  peak jitter no longer republishes the whole state, while exact clipping boundaries remain intact.
- The complete EXIF-orientation path still decodes at a bounded sample size before allocating one
  transformed bitmap. Normal orientation stays allocation-free at this stage, unpublished results
  retain exact disposal ownership, and published bitmaps remain Compose/GC-owned by design.
- Frame delivery remains coalesced to one latest-frame drain; analysis is single-flight,
  generation-isolated, and capped to a 256 px long edge. Encoder and analysis continue to run only
  for real camera frames, never preview-only self-redraws.
- Pseudo-ZSL retains its measured three-frame bound and exact timestamp pairing. Burst, AEB, and
  timelapse continue save-completion chaining, while SINGLE processed capture and RAW-only
  publication retain their explicit finite admission paths.
- Camera setup, Camera2 close proof, GL retirement, recorder detach/finalization, standby mic
  recreation, launch recovery, retained-still retirement, recording storage, family-marker work,
  media deletion, and review work all retain bounded waits, worker counts, queues, or a deliberate
  terminal quarantine instead of multiplying resources after a native/provider wedge.
- Main-thread work remains limited to state/lifecycle/UI operations and the explicitly accepted tiny
  synchronous settings commit. CameraService, MediaProvider, bitmap/HEIF, codec finalization, and
  review acquisition work stay off main.
- The cycle-35 dual-open changes add no hot-loop cost; the pointer-state correctness defect found by
  the causal pass is recorded in `tracer.md`, not misclassified as a throughput issue.
- The final sweep found no new unbounded queue/collection, repeated executor creation, busy-spin,
  per-frame diagnostic flood, main-thread blocking native/provider call, or reproducible CPU/memory
  growth path. All relevant tracked files were accounted for; no production package, tool, or
  cross-file execution lane was sampled out.

## Totals

- New findings: 0
- Severity: none
- Confidence: High that no additional current performance defect was established from repository
  evidence; device-only cost claims remain limited to the field checks already recorded by the repo.
