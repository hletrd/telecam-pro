# Debugger review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518`
Workspace: clean detached review worktree `/private/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope, inventory, and method

I read `CLAUDE.md` completely, then `docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, the current
aggregate and specialist provenance, and completed cycle 30-34 plans. I inventoried all 471 tracked
paths (101 production Kotlin files, 216 JVM/Robolectric/Compose tests, four instrumented tests, 38
host/device tool and harness paths, plus build inputs/resources/docs/assets). Fixed history was used
only to define regression hypotheses; this report contains current-HEAD conclusions.

The latent-failure pass traced the complete production graph and its corresponding tests:

- route inventory, dual-open and sequential camera replacement, optics generation ownership,
  Camera2 callback dispatch, request normalization, capture correlation/watchdogs, pseudo-ZSL, and
  pause/release quarantine;
- GL/EGL input, preview, encoder, analysis, frame-coalescing and output-generation ownership;
- processed still, DNG, recording pre-native allocation, native recorder setup/drain/finalization,
  standby-microphone handoff, audio route degradation, and post-gain RMS/peak publication;
- capture-family tracking, pre-marker reservation, durable family veto, retained-still discard,
  exact-family retirement/retry, ownerless system-consent lifecycle, launch recovery, and provider
  mutation terminals;
- ViewModel/Activity lifecycle, permission and modal ownership, hardware keys, tickers, settings
  restore/recall, review bitmap/player acquisition, gestures, native handle deadlines, and teardown;
- host build/release wrappers, dependency/source/output attestation, device harness parsing, retries,
  timeouts, report paths, and subprocess/file boundaries.

Cycle-34 fixes received competing-hypothesis review. In particular, the dual-open polling boundary
retains the absolute two-second HAL deadline while refusing stale generation publication; the finite
family-marker semaphore matches worker-plus-queue cardinality and releases on every submission/task
terminal; accepted callbacks are detachable by numeric token; the debug mailbox cannot be populated
from the ordinary launcher; ownerless consent reconciles canceled/failed launches before restoring
the exact frozen file; audio peaks cover every PCM buffer between emissions and are cleared together
with RMS; and missing legacy lens-policy keys now resolve to the same current default as fresh state.

## Verification evidence

- Focused current-HEAD tests passed for `DebugCameraControlSecurityTest`,
  `OwnerlessMediaDeleteActivityResultTest`, `OwnerlessMediaDeleteLifecycleTest`,
  `FamilyDeletionMarkerDispatcherTest`, `DualOpenWaitTest`, and `AudioGainTest`.
- The merged debug manifest confirms the component assumptions used by the debug-command and
  lifecycle traces: only MainActivity is ordinarily exported; every debug/tooling ingress is
  `DUMP`-protected or unexported.
- The current worktree had no source, documentation, or plan mutation from this review; another
  specialist's in-progress `.context/reviews/test-engineer.md` change was left untouched.

## Findings

No actionable latent bug, failure-mode regression, or current testable correctness defect survives
the causal and competing-hypothesis checks.

Open device-only measurements documented in `docs/FIELD_CHECKS.md` remain manual-validation risks,
not inferred successes. They are already explicit and do not constitute missing current code tasks.

## Final missed-issue sweep

The final sweep revisited lifecycle races at every newly changed cycle-34 seam, then rechecked the
older high-risk ownership boundaries: Camera2/Image closure, native acquisition quarantine, GL
generation replacement, recorder setup/finalization, bounded provider lanes, deletion durability,
launch recovery, review decode/playback, settings corruption, and tool/evidence path safety. No
reproduction, invariant break, silent data-loss path, stale callback publication, unbounded owner,
or user-visible false terminal remained after current tests and source contracts were considered.

**New finding count: 0.**
