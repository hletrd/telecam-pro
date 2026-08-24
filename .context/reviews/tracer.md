# Causal-tracing review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299562d52f6b4ddd200f6d410ebd00a54c1d`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope and method

I read the full committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) and inventoried all 490 tracked paths before tracing the production graph.
The trace covered route discovery and topology epochs; optics generations, rollback baselines, and
dual/sequential replacement; controller/session/preview/encoder/analysis terminals; tap/custom-WB/ZSL/
capture correlation; still-family producer/publication/delete/recovery ownership; REC allocation,
native teardown, storage and microphone handoff; review/player and ownerless-delete identities;
lifecycle replacement; final UI publication; and build/release/device-evidence tools. All production
packages, tests, resources, and the cycle-37 change surface were included in the final sweep.

## Findings

### TRACE38-01 — a caps-result label reconciliation feeds back into the session-reconfiguration command path

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed causal path; the precise visible delay is device-dependent.
- **Evidence:** The route transition publishes its candidate capabilities before starting the deferred
  Camera2 session (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3782-3810`). The
  generation-guarded ViewModel callback computes a resolved stabilization label and writes it to state,
  but then sends that derived result back through `engine.setVideoStabMode`
  (`app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2878-2925`). The setter is a command
  boundary for operator changes: it pushes `CameraController.setVideoStabMode` and calls
  `reopenForSession` (`CameraEngine.kt:1561-1564,1587-1594`). `reopenForSession` captures the same
  current optics generation, invalidates Ready, and queues `reconfigureCamera` on the serial setup lane
  (`CameraEngine.kt:2844-2885`). Because it reuses rather than supersedes the current generation, the
  original route setup and the queued second setup are both authorized to run.
- **Competing hypothesis rejected:** this second pass is required to change the wire mode. It is not.
  `videoStabControlModeFor` maps unsupported Active to `ON` on an OFF+ON route and unsupported
  Standard/Active to `OFF` on an OFF-only route; `normalizedForAvailableModes` then changes only the
  enum label to Standard/Off (`camera/CaptureCapabilities.kt:551-583`). Old and normalized states
  therefore resolve to the same exact HAL value in every branch that triggers this reconciliation.
- **Failure scenario:** an Active-capable route is followed by a route advertising only OFF+ON. The
  first generation configures ON correctly, publishes caps, and begins terminal convergence. The caps
  result relabels Active→Standard, then invalidates Ready and queues a second close/open under that
  same generation. The user sees an avoidable second reconfiguration/blackout, and any immediately
  queued optics action waits behind it on the sole setup executor.
- **Suggested fix:** make capability reconciliation part of the generation-owned desired packet before
  the first session is configured, then publish the applied enum to UI; alternatively use a distinct
  reconciliation operation that compares old/new resolved HAL modes and updates only the enum when
  they are identical. Test the exact sequence `caps published -> normalized label -> no Ready
  invalidation/no setup submission`, in addition to the existing pure mapping tests.

## Competing hypotheses and final completeness sweep

1. **The new projected choice list can itself grow or churn per frame:** rejected. It is built only at
   caps delivery and contains at most three enum values.
2. **A stale caps callback can normalize a newer optics generation:** rejected at the current entry
   boundary; the main-thread callback checks `isOpticsGenerationCurrent(generation)` before reconciling.
   The finding is instead that a current result is incorrectly treated as a new command.
3. **Cycle 36's dual-open cleanup can again orphan a nullable outgoing controller:** rejected. Current
   production still totalizes vacant/live/terminal/newer layouts and checks the blocking boundary before
   publication/session start.
4. **A late still/recording/provider result can publish through a replacement owner:** rejected. Exact
   controller/session/capture-family/generation identities remain checked at terminal publication, and
   process-wide provider lanes retain bounded recovery authority.
5. **A retired GL/review/audio owner can clear or publish into a replacement:** rejected. Generation or
   exact-handle terminals remain first-wins and stale work releases only its own resources.

No additional wrong-clock comparison, nullable-identity alias, stale rollback, double terminal, lost
completion, use-after-retire publication, or cross-file ownership gap survived the final repository-wide
sweep. Device behavior was not inferred beyond the committed field ledger.

## Totals

- New findings: 1
- Severity: 1 Medium
- Confidence: High in the duplicate authorized reconfiguration path.
