# Code-reviewer report — cycle 36

Date: 2026-08-24
Reviewed revision: `1f45887` (`origin/main`)
Workspace: isolated detached worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and inventory

I read all 1,143 lines of `CLAUDE.md`, all 1,353 lines of `docs/ARCHITECTURE.md`, and all
262 lines of `docs/FIELD_CHECKS.md` before reviewing implementation. I inventoried all 486 tracked
paths: 101 production Kotlin files (53,706 lines), 218 host-test Kotlin files, four instrumented-test
files, three debug Kotlin files, 32 Python tool/harness files, four shell/wrapper scripts, 68 Markdown
authority/review/plan files, 33 build/resource/configuration text files, seven legal/profile text
files, and 16 binary assets/wrapper artifacts.

The complete production surface was examined by package and paired with its tests and governing
documentation. Cross-file tracing covered Activity/permission/hardware ingress, ViewModel state and
timers, Compose controls/review, Camera2 selection/session/capture/teardown, optics transactions,
GL/EGL ownership and analysis, exposure/focus/rotation, still and video encoding, audio ownership,
MediaStore publication/deletion/recovery, settings, release-evidence tooling, device harnesses,
resources, and build configuration. I also read the current cycle-35 aggregate and completed plan and
reviewed every cycle-35 implementation delta so closed findings were not reported again.

## Finding

### CODE36-01 — a null outgoing controller violates the new dual-open cleanup invariant

- **Severity:** High
- **Confidence:** High
- **Class:** Likely concurrency defect; the failing state is proved from current expressions and
  reachable callback ordering, but no device stress reproduction was performed in this review.
- **Exact regions:**
  - Nullable outgoing owner capture: `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3580`.
  - Candidate-refusal callback vacates the slot: `CameraEngine.kt:3667-3674`.
  - Supersession call site: `CameraEngine.kt:3746-3765`, especially
    `slotVacant = controller == null` at line 3751 and
    `outgoingOwnsSlot = controller === old` at line 3752.
  - Boolean exclusivity assertion: `CameraEngine.kt:7040-7049`, especially line 7045.
  - The tests pass only hand-authored mutually exclusive booleans and omit nullable-owner
    derivation: `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:102-135`.
- **Problem:** `old` is nullable. When a dual-open attempt began without an installed outgoing
  controller (`old == null`) and its candidate callback has also cleared `controller`, the call site
  computes both `slotVacant == true` and `outgoingOwnsSlot == true`, because `null === null` is true.
  `dualOpenSupersessionCleanup` explicitly requires at most one input to be true and throws
  `IllegalArgumentException`. The helper's state model therefore confuses “the nullable outgoing
  identity is absent” with “the outgoing native owner occupies the slot.”
- **Concrete failure scenario:** A cold/recovery reconfiguration starts with `controller == null`,
  installs `next`, and waits at the dual-open boundary. Native acquisition is transiently refused,
  so `onError` clears `controller`; while the process owner is released, a newer lens/mode/route
  intent supersedes the transaction. The setup task reaches the supersession branch with native
  admission open, `old == null`, and `controller == null`. The two true booleans trigger the
  `require`, producing an uncaught exception on the setup executor instead of converging the newest
  camera intent. On Android an uncaught worker exception is process-fatal; at minimum it also aborts
  this exact camera convergence and leaves Not-Ready state.
- **Suggested fix:** Preserve owner presence at the call boundary, e.g.
  `outgoingOwnsSlot = old != null && controller === old`, or better pass the nullable slot/outgoing
  identities into one typed cleanup reducer so mutually exclusive facts are derived in one place.
  Add a production-shaped matrix covering `old == null` with candidate-current, candidate-cleared,
  newer-controller, pause, and supersession states. The candidate-cleared/null-outgoing case must
  return `RESTORE_OUTGOING` (which restores the prior null/metadata snapshot) without throwing.

## Final missed-issue sweep

The final sweep rechecked all 101 production files and their 225 Kotlin test/debug counterparts for
nullable identity traps, silent catches, unsafe assertions, callback-under-lock effects, unbounded
native/provider work, stale generation publication, route/model hardcodes, clock and numeric-boundary
errors, resource terminals, suppression use, and authority/code drift. It also inspected every
tracked Python/shell tool, configuration/resource file, and binary asset type, and compared all active
plans/reviews against current source. No additional current code-quality or correctness issue met the
evidence threshold after excluding completed historical findings and explicit device-only field
checks.

## Totals

- New findings: 1
- Severity: 1 High
- Confidence: 1 High
