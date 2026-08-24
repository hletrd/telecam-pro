# Debugger review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299562d52f6b4ddd200f6d410ebd00a54c1d`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope, inventory, and method

I reviewed the isolated `origin/main` tree only after reading `CLAUDE.md`,
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`. Retained reviews and the completed cycle-37 plan
were regression hypotheses, not current findings.

All 490 tracked paths were inventoried: 101 production Kotlin files, three debug Kotlin files, four
instrumented-test files, 220 JVM/Robolectric/Compose tests, 32 Python files, 70 Markdown files, and
60 other tracked build/resource/script/license/asset inputs. The complete tree participated in
file-type, Git-mode, digest, suspicious-token, exception, subprocess, threading, timeout, ownership,
and failure-handling searches. I then traced the cross-file graphs most likely to conceal latent
failures:

- Camera2 route inventory, optics generations, dual-open/sequential replacement, callback dispatch,
  teardown terminals, session fallback, request fast paths, capture correlation/ZSL, watchdogs,
  pause/release, and bounded recovery;
- GL/EGL generation ownership, frame coalescing, analysis/motion state, preview replacement, encoder
  attachment, output unbind/destroy ordering, and quarantine;
- processed/RAW still budgets and publication, capture-family leases, pending-video allocation,
  standby microphone handoff, codec/muxer finalization, and post-native storage tails;
- MediaStore restore, family/DISCARD journals, ownerless consent, review decode/playback, provider
  timeouts, settings restore, Activity/ViewModel lifecycle, and timers/tickers;
- the cycle-37 stabilization and Gamma capability projections, disabled focal-rail rendering,
  optimized-Python guards, privacy parity, and their tests/tooling.

## Finding

### DBG38-01 — shared-pool capacity test races its own latest-wins replacement

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed scheduler-dependent quality-gate failure; test defect, not a
  confirmed shipping-app defect.
- **Exact evidence:** `app/src/test/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLaneTest.kt:418-450`
  creates two `ProgressiveLatestWorkLane`s and submits `A`, `B`, `C`, and `D` back-to-back, two per
  lane, then requires all four blocking callbacks to have started within two seconds. But
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLane.kt:163-171,242-254`
  deliberately gives each lane one latest request: every submit atomically replaces and retires the
  predecessor, and the channel is conflated. There is no barrier ensuring `A` entered
  `executeOwned()` before `B` replaces it, or that `C` started before `D` replaces it. The
  authoritative `python3 tools/verify_host.py` run failed after 2,011 tests on exactly
  `shared four-thread pool gives a healthy lane a bounded exhaustion terminal`, reporting an
  `AssertionError` from the coroutine block at line 439. A subsequent full 2,011-test rerun and
  eight focused reruns passed, which is the expected signature of this unsynchronized scheduling
  race rather than a deterministic implementation failure.
- **Failure scenario:** under CPU/Gradle contention, the fixed-pool consumer does not start `A`
  before the caller submits `B`. `B` legally retires `A`, so the `started` latch can reach at most
  three (and similarly fewer if `D` wins before `C` starts). The two-second assertion fails and the
  repository's authoritative host gate turns red despite the production latest-wins behavior doing
  exactly what its contract requires. This blocks an otherwise valid commit and makes gate results
  load-dependent.
- **Suggested fix:** make pool saturation independent of latest-wins replacement. Prefer four
  independent lanes with one blocking request each, then submit through a fifth healthy lane and
  assert its bounded `CapacityExhausted` result. Alternatively, give each of `A` and `C` an exact
  start handshake before submitting its same-lane successor. Do not merely extend the two-second
  timeout; the missing happens-before edge, not elapsed time, is the defect. Keep a repeated or
  deliberately delayed-dispatch regression so the test remains deterministic under loaded CI.

## Verification evidence

- The initial authoritative host run built debug and androidTest APKs and passed lint before the
  scheduler-dependent JVM test failed. A clean full JVM rerun passed all 2,011 tests; eight focused
  reruns of DBG38-01 also passed, confirming nondeterminism.
- The 99 tool tests, nine coverage tests, 184 device-harness self-tests, all 120 applicable
  documentation checks, Python compilation, and optimized-runtime rejection checks passed.
- Current Git modes contain only regular tracked files. No device behavior was run or inferred, and
  no source, plan, Git history, deployment, or external state was changed.

## Final missed-issue sweep and count

The final sweep replayed every native terminal, callback/generation edge, queue/admission owner,
provider mutation and recovery state, capture family, review worker, bitmap/media parser bound,
settings corruption seam, Activity/ViewModel lifecycle transition, and build/device-evidence
boundary. It separately traced every cycle-37 implementation delta and rechecked older resolved
findings for regression. No additional actionable production bug, correctness regression,
deadlock, race, resource leak, data-loss path, or error-handling defect survived validation. The five
open physical checks in `docs/FIELD_CHECKS.md` remain evidence obligations, not host-proven defects.

**New debugger finding count: 1 — one Medium (High confidence).**
