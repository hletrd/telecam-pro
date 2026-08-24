# Causal-tracing review — cycle 39

Date: 2026-08-24
Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle39.feeBBZ`

## Scope and method

I read the complete repository authorities and inventoried all 493 tracked paths before tracing the
production graph. The trace covered route inventory and identity epochs; optics generations,
rollback baselines and camera replacement; controller/session/preview/GL terminals; tap, AF,
custom-WB, ZSL and capture correlation; still-family production, publication, deletion and recovery;
REC allocation, process-native admission, microphone handoff, codec/muxer finalization and storage;
review/player and ownerless-delete identities; lifecycle replacement; UI publication; and host,
release and device-evidence tooling. Tests, resources, historical findings, and the complete cycle-38
change surface were included in the final sweep.

## Findings

No new causal correctness issue survived competing-hypothesis validation at current HEAD.

In particular, the prior duplicate-reconfiguration chain is closed rather than merely hidden in the
ViewModel: route capability reconciliation still calls the Engine with the normalized label
(`app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2878-2925`), but the Engine now
resolves both labels against the currently accepted capability set and returns before request rebuild
or reopen when their HAL values match
(`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1587-1597`; `camera/CaptureCapabilities.kt:586-604`).
Real OFF/ON/PREVIEW_STABILIZATION transitions still take the established apply-and-reopen path, so
the fix does not suppress an operator command that changes wire state.

## Competing hypotheses and final completeness sweep

1. **A capability-normalization callback can still authorize a second same-generation session:**
   rejected. The callback may update the stored label, but equal effective HAL modes now stop before
   either native side effect; genuine mode changes retain the original path.
2. **A late REC allocation or setup result can publish after Stop/pause/replacement:** rejected. The
   allocation attempt, deadline, process-admission token, exact accepted-session snapshot, setup
   finalization owner, and topology lease all have independent first-wins/current-owner checks before
   native publication (`camera/CameraEngine.kt:4880-5123,5136-5528`). A late provider URI is routed to
   durable pending-row retirement rather than recorder setup.
3. **A recorder terminal can release native owners while EGL still references the encoder surface:**
   rejected. Detach completion owns finalization; deadline expiry quarantines the exact graph and
   closes process-wide native acquisition. Storage publication is a later, separately bounded tail
   (`camera/CameraEngine.kt:5620-6237`; `video/VideoRecorder.kt:523-553`).
4. **A late still sibling can resurrect a deleted or evicted review family:** rejected. Capture ids,
   durable family tombstones, producer-terminal state, exact output ownership and bounded unresolved
   discards remain checked at publication and deletion terminals; launch recovery owns rows that
   cannot be resolved immediately.
5. **A retired review/provider result can publish into a replacement composition:** rejected. Each
   lane replaces publication authority immediately, applies a terminal timeout/capacity result, and
   disposes produced native/bitmap values whose exact request cannot be claimed
   (`ui/review/LatestHeavyWorkLane.kt:153-281`). Playback uses an exact handle plus deadline owner.
6. **Lifecycle teardown can leave recurring work or a stale native replay alive:** rejected. Named
   handler work is removed on stop/clear, process replay observations are generation-replaced and
   canceled, and every replay rechecks lifecycle, surface generation and process authority before
   acquisition.

No wrong-clock comparison, nullable-owner alias, stale rollback, lost completion, double terminal,
use-after-retire publication, or cross-file ownership gap survived the final sweep. Open entries in
`docs/FIELD_CHECKS.md` remain explicitly device/manual evidence gaps, not code findings.

## Totals

- New findings: 0
- Confirmed regressions: 0
