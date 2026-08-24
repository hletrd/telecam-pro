# Code-reviewer report — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and inventory

I read the committed project authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`), inventoried all 489 tracked paths, and examined the production, test,
tooling, resource, build, privacy, and device-harness surfaces. The tracked implementation includes
101 production Kotlin files (53,757 lines), 220 host-test Kotlin files (42,824 lines), four
instrumented-test files, three debug Kotlin files, and 32 Python files (22,268 lines). Cross-file
tracing covered Activity/permission/hardware ingress, ViewModel and Compose state, Camera2 selection,
session/capture/reconfiguration/teardown, GL/EGL, still/video/audio processing, MediaStore durability
and review deletion, settings, build provenance, and immutable device evidence.

I compared current HEAD with the cycle-36 review and every cycle-36 implementation delta. The prior
nullable/terminal dual-open defect is resolved: terminality is published before external failure
callbacks, close is monotonic, and the cleanup reducer now derives its answer from nullable owner
identities. The optimized-Python harness guard and enabled-edge contrast correction also work as
implemented. I did not re-report those completed findings or the already-deferred broad
`CameraEngine` decomposition.

## Finding

### CODE37-01 — the MR-row comment still says its 0.18 tint equals `AffordanceEdge`

- **Severity:** Low
- **Confidence:** High
- **Exact region:**
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:695-698`; changed token at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt:115-127`.
- **Problem:** Cycle 36 raised `CameraColors.AffordanceEdge` from 0.18 to 0.36, but the MR-bank row
  comment still calls its independent amber `ManualActive.copy(alpha = 0.18f)` tint “the white
  AffordanceEdge — same number.” The two values are no longer the same. This is current source
  documentation attached directly to the styling expression, not a preserved historical plan.
- **Failure scenario:** A later palette cleanup following the comment can incorrectly couple the
  deliberately quiet amber selection wash to the 0.36 interactive-boundary token, doubling the MR
  row tint, or can mistake the intentional 0.18 amber value for a missed cycle-36 contrast fix.
  Either path changes the active-row visual hierarchy the adjacent comment says is deliberate.
- **Suggested fix:** Reword the comment to state that 0.18 is an independent amber active-row wash
  and is unrelated to the current white `AffordanceEdge` alpha; do not change either pixel value.

## Verification and final sweep

`git diff --check` passed. All 184 device-harness host tests passed, and all 112 committed
documentation checks passed (24 optional private-context checks skipped because those files are not
present in the clean clone). The final sweep rechecked all production modules and their test seams
for nullable identity mistakes, callback/close ordering, stale-generation publication, resource
terminal ownership, unbounded work, blocking UI/provider calls, route/profile leakage, unsafe
numeric and clock boundaries, hardcoded user text, and current-authority drift. No other current
code-review finding met the evidence threshold.

## Totals

- New findings: 1
- Severity: 1 Low
- Confidence: 1 High
