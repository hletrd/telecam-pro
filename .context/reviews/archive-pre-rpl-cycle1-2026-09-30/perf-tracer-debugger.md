# Performance, causal-tracing, and debugger review — cycle 32

Date: 2026-08-24
Reviewed revision: `64eff08e22f856b42f70be7f2a63581c30e265a9`

## Scope, inventory, and method

I read `CLAUDE.md`, the committed as-built authority in `docs/ARCHITECTURE.md`,
`docs/FIELD_CHECKS.md`, the current completed plan, and the retained review provenance before
reviewing source. Historical findings were used only to avoid re-filing resolved defects; the
finding below was reproduced from current HEAD and is distinct from cycle 30's executor-overflow
retirement bug.

The complete tracked inventory contains 440 paths: all 98 production Kotlin files, 207 Kotlin test/
debug/instrumented files, 30 Python files, two shell files, Android resources/manifests and Gradle
inputs, privacy/release surfaces, and 45 documentation/assets paths. The runtime sweep traced every
Camera2 route and callback owner, optics and preview generation, ZSL/capture/watchdog path, processed
and RAW save/publication lane, exact-family deletion and recovery authority, GL/EGL output and
analysis generation, recorder/microphone/muxer quarantine, bounded provider dispatcher, ViewModel
lifecycle/ticker/delete owner, and review bitmap/player lane. Tooling and device-harness process,
file, timeout, and bounded-cardinality behavior were also inventoried. A final sweep checked every
executor/Handler/thread, monitor/atomic owner, retry/backoff, queue-overflow path, shutdown edge, and
process-lifetime registry.

## Findings

### PTD32-01 — an accepted family-retirement attempt that returns `RETAINED` has no retry owner

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed in-process liveness and resource-retention defect. The code path is
  deterministic; the one-shot `RETAINED` trigger can be a transient MediaProvider/query or marker
  failure, while reproducing that provider fault on a particular handset needs manual injection or
  a test seam.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4245-4263` submits one exact
    retirement task, calls `retireFamilyDeletionIfAbsent`, and only passes its result to
    `reconcileFamilyRetirement`; it does not re-arm when the result is `RETAINED`.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:555-565,589-596` returns
    `RETAINED` for an unavailable marker read, any non-authoritative/failed exact-family provider
    query, a live row, publication contention, or synchronous marker-removal failure. Several of
    these are explicitly transient outcomes.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDiscardDispatcher.kt:110-121`
    arms the process rescan only when executor submission itself returns `OVERFLOW`. An accepted
    task that runs and returns `RETAINED` never sets `pendingRetirementRescan`.
  - `RetainedStillDiscardDispatcher.kt:242-255` deliberately keeps the family/listener registration
    for `RETAINED`; the registry is capped at 64 registrations (`:198-227,282-284`). The registered
    listener is the Engine-capturing lambda at `CameraEngine.kt:137-143`, and `CameraEngine.release`
    at `:6459-6563` shuts the Engine down without unregistering or demoting that listener.
  - Once registration capacity is exhausted, `CameraEngine.kt:4199-4212` reports deletion
    durability false, and `RetainedStillDeletionOwner.kt:100-110` permanently flips that Engine's
    `deletionJournalUnavailable` admission gate.
  - Launch recovery cannot supply the missing same-process retry: `MediaStoreWriter.kt:1103-1108,
    1168-1169` intentionally refuses to clear a family marker owned by the current process. Current
    production call sites for `retireCurrentProcessFamilyDeletions` are only the overflow-rescan
    closure at `CameraEngine.kt:4264-4268`; there is no foreground, recovery-completion, or delayed
    returned-result retry. Existing tests prove overflow recovery and assert that `RETAINED` keeps
    the registration, but no test advances a returned `RETAINED` result to a later terminal result.
- **Concrete causal trace:** the user deletes a producer-terminal still family and its known rows
  are removed. The accepted retirement worker reaches the exact absence query, but MediaProvider
  transiently returns no cursor or throws, so the task returns `RETAINED`. Because dispatch was
  accepted, the overflow rescan is not armed; because this process owns the marker, launch recovery
  will delete rows but will not retire that marker. No later edge is guaranteed for this family.
  The durable marker and registry entry therefore remain until process death even after the
  provider recovers. The strong listener also retains the released `CameraEngine` graph. Repeating
  the same one-shot fault across families consumes the 64-entry registry/marker ceiling; the next
  delete fails durability, `completeDeletionDurability(..., false)` closes still admission, and
  captures remain disabled until process restart.
- **Competing hypotheses checked:** producer completion does schedule one final exact recheck at
  `CameraEngine.kt:4472-4487`, which repairs an earlier `PRODUCERS_ACTIVE` result, but there is no
  successor after that final task itself returns `RETAINED`. A future unrelated deletion submits
  only its own exact family; it does not scan existing entries unless it happens to overflow the
  finite executor. Waiting for process death preserves data safety but does not satisfy the stated
  same-process capacity-recovery contract, and it leaves obsolete Engine graphs strongly reachable.
- **Suggested fix:** give returned non-terminal results a process-owned, constant-memory retry
  path, not just rejected submissions. Preserve the exact distinction between permanent row
  presence/contention and retryable query/marker uncertainty if necessary, then arm one conflated,
  backed-off bounded rescan on retryable `RETAINED` results and recheck retained entries at a safe
  lifecycle/provider-mutation edge. Do not spin indefinitely on a genuinely surviving row. Also
  prevent the registry from strongly owning a whole released Engine (for example, register a
  compact local-owner token and demote/remove UI/Engine reachability at release). Add deterministic
  tests for accepted-task query failure -> later success, marker-removal failure -> later success,
  no tight retry loop while a row really survives, registry capacity recovery, and garbage-
  collectible released Engine ownership.

## Final sweep and accounting

No second actionable performance, concurrency, lifecycle, or latent-failure finding survived
competing-hypothesis checks. In particular, the cycle-31 asynchronous preview binding rechecks both
Surface and GL generation around the blocking terminal gate; current GL frame coalescing and
analysis ownership remain finite; still/recording/review provider lanes have fixed process-wide
worker and queue ceilings; recorder drain/setup quarantine preserves native ownership; ZSL frames
and watchdogs close their exact images; and lifecycle tickers and callback drains retire cleanly.
No build, device mutation, source edit, plan edit, commit, or deployment was performed in this
review-only lane.

**New finding count: 1.**
