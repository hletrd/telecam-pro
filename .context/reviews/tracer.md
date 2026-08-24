# Causal-tracing review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)
Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` first and inventoried all
486 tracked paths. I traced the full production graph rather than isolated functions: route
enumeration and optics generations, cold start and dual/sequential Camera2 replacement, controller
and session terminals, preview/encoder/analysis generations, ZSL and still correlation, still
publication/deletion/recovery, recording admission/native/storage ownership, standby/recording mic
handoff, review work, lifecycle/topology transitions, and main-thread state publication. Tests,
current reviews, and completed plans through cycle 35 were checked as competing oracles so resolved
findings were not repeated. The final sweep covered every tracked production package plus the
build/release tools and device harness that assert cross-file behavior.

## Finding

### TRACE36-01 — dual-open supersession confuses a null outgoing owner with slot ownership and can restore a closed owner

- **Severity / confidence:** High / High.
- **Status:** Confirmed state-model defect; activation is race-timed but requires no undocumented HAL
  behavior. The null-alias failure follows directly from Kotlin reference equality and the
  production call arguments.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3545,3580-3592` explicitly admits
    cold-start/recovery with `controller == null`, snapshots that nullable value as `old`, then
    installs `next`.
  - A candidate native refusal clears the shared slot at
    `CameraEngine.kt:3667-3674` before the setup task evaluates supersession.
  - The supersession call derives `slotVacant = controller == null` and
    `outgoingOwnsSlot = controller === old` independently at `CameraEngine.kt:3746-3753`.
    When the attempt started with `old == null` and the candidate cleared the slot, both expressions
    are true because `null === null` is true.
  - `dualOpenSupersessionCleanup` then rejects more than one true predicate with `require(...)` at
    `CameraEngine.kt:7037-7050`, so the setup executor throws `IllegalArgumentException` instead of
    completing cleanup. An uncaught executor-task exception is process-fatal under Android's default
    uncaught-exception handling and, at minimum, abandons the exact setup continuation.
  - `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:101-133` tests only manually
    supplied mutually exclusive booleans. It never derives them from nullable `old`/`controller`
    identities and therefore misses the production `old=null, controller=null` alias.
  - Supersession while Not-Ready is a supported production input, not only a synthetic debug call:
    the Lens settings rows gate on rear route and recording, not `cameraReady`, at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:1104-1155`, and route/mode
    setters intentionally advance optics generations while older setup work is pending.
  - The same model omits outgoing liveness. `CameraController.kt:331-370` begins closing a device on
    disconnect/error. During dual-open, the old callback is intentionally identity-inert because the
    slot contains `next` (`CameraEngine.kt:2913-2921`), yet the supersession branch restores `old`
    solely from pointer layout at `CameraEngine.kt:3753-3760`. The comment at
    `CameraEngine.kt:3578-3579` acknowledges that concurrent old-device eviction is legal, but no
    closed/terminal state reaches the cleanup decision.
- **Causal trace — null alias:** Start a cold recovery with no installed controller (`old=null`). The
  task publishes `next`; its open fails and `onError` sets the shared controller to null. Before the
  setup task reaches its post-wait boundary, a newer optics intent advances the generation. The stale
  task enters supersession with `candidateOwnsSlot=false`, `slotVacant=true`, and
  `outgoingOwnsSlot=true`; the helper's invariant throws. The newer intent is left behind a failed
  setup edge rather than receiving a clean vacant baseline.
- **Causal trace — closed outgoing competing hypothesis:** Start from a Ready `old`, install `next`,
  and let the OS disconnect `old` while the candidate is opening. The old controller begins close,
  while its Engine failure callback no-ops because it no longer owns the slot. If a newer optics
  generation then supersedes the attempt, the cleanup sees `next` in the slot and restores the
  already-closing/closed `old` plus its old selection/caps. A later preflight failure now sees a
  non-null controller and takes rollback rather than cold-start recovery, which can leave a dead
  controller as the only engine pointer and a permanently Not-Ready/black preview until another
  lifecycle reopen.
- **Concrete failure scenario:** Open after a transient CameraService refusal while an asynchronous
  setting/debug optics command supersedes the cold attempt, or rapidly change route while another
  camera client evicts the outgoing device. Depending on which case wins, the app either crashes on
  the cleanup `require` or republishes a controller that has already entered teardown and can fail to
  schedule the cold-recovery path.
- **Suggested fix:** Stop encoding exact-owner state as three independently derived nullable-pointer
  booleans. At minimum compute outgoing slot ownership only when an outgoing owner exists
  (`old != null && controller === old`) and make the vacant/no-outgoing case a tested total outcome.
  Prefer a typed dual-open terminal that carries candidate identity, optional outgoing identity, and
  outgoing close/liveness state, so `RESTORE_OUTGOING` is impossible once that owner has begun
  teardown. Add an exhaustive production-derived matrix including `old=null/controller=null`,
  candidate self-clear, outgoing disconnect/close, supersession, pause/release, and a newer
  controller occupying the slot. Prove every local controller is either installed live or closed
  exactly once and that no state combination throws.

## Competing hypotheses checked

1. **Cold paths never enter dual-open:** rejected. `reconfigureCamera` computes
   `recoverColdPreflight = startup || controller == null` but continues into the same dual-open body;
   startup and topology convergence call it with `startup = true` or `controller == null` at
   `CameraEngine.kt:1226-1233,1469-1479`.
2. **The UI cannot supersede a Not-Ready cold attempt:** rejected. Lens settings remain enabled while
   the camera is reconfiguring as long as the route is rear and REC is idle.
3. **The candidate error cannot clear before supersession:** rejected. Its callback clears the slot
   and counts down the exact wait latch before the setup task's ownership checks.
4. **Nullable reference equality distinguishes absence from ownership:** rejected by language
   semantics; two null references are referentially equal, which is exactly why both production
   predicates become true.
5. **A disconnected outgoing controller remains restorable:** rejected. `CameraController` begins
   close after emitting the error, while the Engine's identity gate deliberately ignores a replaced
   controller. No later code reopens that same controller object.

## Final causal sweep and coverage confirmation

- The cycle-35 audio path now preserves raw peak thresholds before RMS quantization and coarse root
  state, with no stale producer/consumer representation crossing left.
- All eight EXIF transforms are applied after bounded decode and their disposal/publication ownership
  remains exact; no late bitmap from a retired review identity can publish.
- Ready/Not-Ready publication sequences, accepted-session generation checks, tap/custom-WB ownership,
  ZSL timestamp correlation, still-family leases, deletion tombstones, recording setup/finalization,
  ownerless system-delete terminals, launch recovery, and review player/decode owners retain their
  identity rechecks at final publication boundaries.
- Preview, encoder, and analysis failures remain separated by exact output ownership. Provider/native
  wedges retain finite queues or quarantine and cannot silently authorize replacement resources.
- No additional current wrong-clock correlation, stale rollback, double terminal, lost callback,
  or cross-file invariant failure survived the missed-issues sweep. All 486 tracked paths were
  inventoried and every relevant runtime/tooling subsystem was examined.

## Totals

- New findings: 1
- Severity: 1 High
- Confidence: 1 High
