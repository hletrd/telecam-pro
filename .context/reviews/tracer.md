# Causal-tracing review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and method

I read the full committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) and inventoried all 489 tracked paths before tracing the production graph.
The trace covered route discovery/topology epochs, optics generations and rollback baselines,
dual/sequential camera replacement, controller/session terminals, preview/encoder/analysis owners,
tap/custom-WB/result correlation, ZSL and still-capture timestamps, still-family producer/publication/
delete/recovery authority, REC allocation/native/storage ownership, standby/recording microphone
handoff, review decode/player identities, ownerless delete consent, lifecycle replacement, and final
UI publication. Tests, historical reviews, and completed plans through cycle 36 were checked as
competing oracles. Build/release tools and device-harness evidence flow were included in the final
cross-file sweep.

## Findings

No new causal-tracing finding survived validation.

## Cycle 36 regression trace

The prior `TRACE36-01`/`AGG36-01` dual-open defect is resolved in current HEAD and is not re-filed.
The production branch now passes the actual nullable candidate/outgoing/current identities to
`dualOpenSupersessionCleanup` and carries `CameraControllerRestorability` as monotonic terminal truth.
The resulting state machine totalizes all relevant layouts:

- no outgoing owner plus a candidate-cleared vacant slot restores a truthful null baseline;
- a live outgoing owner is restored or kept only when no genuinely newer controller occupies the
  shared slot;
- terminal outgoing owners produce a vacant baseline and are closed, never republished;
- a different newer controller keeps the slot and forces release of the stale outgoing owner.

Terminality is published before the controller's external failure callback and also when close begins.
The Engine's monitor serializes the shared-slot mutation against identity-owned failure handling, so
the liveness read cannot create the old callback-no-op/restore gap. The focused tests now consume
production-shaped identities and cover absent, live, terminal, candidate, vacant, outgoing, and newer
slot states.

## Competing hypotheses and final completeness sweep

1. **A second rapid optics intent can snapshot an in-flight candidate as rollback truth:** rejected.
   `beginOpticsTransaction` retains one `opticsRollbackBaseline` from the last Ready state, so later
   intents do not adopt candidate selection/caps/controls as their rollback baseline.
2. **A candidate/open timeout can orphan either CameraController:** rejected. Every post-wait branch
   restores a live outgoing owner or closes local candidate/outgoing handles; sequential fallback is
   admitted only after strict candidate/outgoing release proof.
3. **REC topology change can reopen under a live recorder:** rejected. One lease spans provider
   allocation, recorder publication, native teardown, and quarantine; route convergence can claim its
   latest revision only after the exact admission/recorder lease terminates.
4. **A late still sibling can escape a family delete:** rejected. Producer leases are registered before
   Camera2 dispatch, publication claims share exact-family authority, delete intent is durable before
   provider mutation, and the terminal producer edge always rechecks retirement. Retryable uncertainty
   transfers to process-owned bounded rescan rather than disappearing with an Engine.
5. **Old worker results can publish through replacement UI/native owners:** rejected. Ready events carry
   monotonic publication sequences; optics/session/controller identities are rechecked at commit;
   Engine callbacks share one close-and-drain lease; GL analysis, review decode/player, ownerless delete,
   and provider tasks each use exact generation or owner checks at their terminal publication boundary.
6. **Optimized Python can still turn assertion failures into green device evidence:** rejected as a
   resolved Cycle 36 finding. Both outer and immutable-child entry boundaries fail closed before
   snapshot/APK/ADB/report work when `sys.flags.optimize != 0`.

The final sweep found no additional current wrong-clock comparison, nullable-identity alias, stale
rollback, double terminal, lost completion, use-after-retire publication, or cross-file ownership gap.
All production packages and repository evidence/tooling lanes were accounted for. Device-only behavior
was not inferred beyond the committed field ledger.

## Totals

- New findings: 0
- Severity: none
- Confidence: High that no additional current causal/ownership defect is established by repository
  evidence at this revision.
