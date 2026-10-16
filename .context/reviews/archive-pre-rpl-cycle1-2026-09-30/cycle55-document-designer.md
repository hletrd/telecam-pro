# Document-specialist and native Android designer deep review — cycle 55

Date: 2026-08-27
Reviewed revision: `121fcdf09265262ea1c5d2710bddb61b12c3a38f`

## Inventory and UI method

I inventoried all tracked authorities, resources, Compose surfaces, semantics/focus/layout policy,
UI tests, screenshots/runbooks, and all production modules referenced by the documentation map. I
read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely and reviewed all 459
current review-relevant files. This is a native Jetpack Compose app, so browser automation is not
applicable; UI/UX evidence came from production Compose code, accessibility semantics, EN/KO
resources, responsive/window policy, focus tests, state reducers, and the passing host gate.

## Findings

### DD55-01 — DNG allocation can leave the shutter indefinitely disabled without a terminal state

- **Severity / confidence:** Medium / High.
- **Evidence:** a DNG press acquires process admission and rejected-output cleanup before provider
  allocation at `CameraEngine.kt:4411-4423`, and UI admission is republished only through the return,
  failure, cancellation, or later callback paths at `:4439-4450,4502-4514`. The allocation owner at
  `DngPreCaptureAllocation.kt:53-96` has no timeout. The cycle-54 completed plan claims the blocked
  identity path is “prompt and finite” and that all five findings are fully closed
  (`docs/plans/2026-08-27-rpf-cycle54.md`, P2 and Completion evidence), but its test requires an
  explicit cancellation to regain admission (`DngPreCaptureAllocationTest.kt:23-60`).
- **Failure scenario:** while the current MediaProvider call is wedged, the operator sees a shutter
  that remains unavailable with no timeout/failure status and no recovery affordance short of an
  unrelated optics/lifecycle transition or process restart. The completion record can make a future
  maintainer reject this as already solved.
- **Suggested fix:** add a bounded timeout that publishes localized failure/retry truth and restores
  admission while preserving late-row recovery; append a dated correction to the completed cycle-54
  evidence rather than rewriting history.

### DD55-02 — diagnostic authority overstates a process-wide budget that capture logs bypass

- **Severity / confidence:** Low / High documentation/test-contract drift; the lost debug evidence is
  the Medium runtime-observability consequence described by the verifier.
- **Evidence:** `CLAUDE.md:1053-1072` says diagnostics must preserve the 300-row ColorOS quota, and
  `docs/ARCHITECTURE.md` describes `DiagnosticTelemetry` as the shared process owner for recurring
  camera/focus/motion/zoom/hardware evidence. `DiagnosticTelemetryTest.kt:68-107` likewise calls its
  partial simulation “everyRecurringProducer”. Yet the per-press ZSL/ShutterLag logs in
  `CameraController.kt:1558-1624,2106-2127,2228-2232` and the two Single capture-family logs in
  `CameraEngine.kt:4433-4438,5048-5053,5185-5192` bypass the shared budget.
- **Failure scenario:** maintainers rely on a false closed-world contract and add or retain
  action-repeatable diagnostics outside the owner; later field evidence silently disappears despite
  the docs and tests claiming a reserved fault allowance.
- **Suggested fix:** make one executable production wrapper the only recurring-debug emission door,
  test the complete call-site inventory, and update architecture/plan evidence to name its actual
  scope. Do not weaken the 120-row fault reserve.

## UI/accessibility final sweep

Review loading/error/restart states, gallery semantics, modal focus containment/restoration,
keyboard activation, obscured input cancellation, RTL absolute finder placement, large-screen
layout, localized status copy, dark theme/system bars, reduced-animation-sensitive transitions, and
recording/review mutual exclusion were all rechecked. No additional current native UI defect or
EN/KO drift survived. Every relevant Compose/resource/test/doc file was covered; no device or visual
claim was inferred from the host suite.
