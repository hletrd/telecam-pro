# Aggregated deep review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Coverage and aggregation

Five parallel specialist groups covered code-reviewer, architect, performance, tracer, security,
debugger, verifier, test engineer, critic, document specialist, native Android designer, and the
repository-local QA-adversary role. Every group inventoried all 486 tracked paths, read the complete
committed project authorities, examined the relevant implementation/tests/tooling and cross-file
interactions, and performed a final missed-issue sweep. The Compose UI was reviewed from native
source, semantics, resources, and tests; browser automation is not applicable. Device-only behavior
was not run or inferred because deployment and device mutation were outside this invocation.

The 10 raw specialist findings deduplicate to three current root causes. The nullable dual-open
state defect had broad agreement across six roles; the optimized-Python evidence failure had verifier
and test-engineer agreement plus a concrete subprocess reproduction; the active-control contrast
gap had critic/designer agreement and exact palette math. Highest severity/confidence is preserved.

## Findings

### AGG36-01 — dual-open supersession is not total for absent or terminal outgoing owners

- **Severity / confidence:** High / High
- **Sources:** code-reviewer, architect, tracer, critic, document-specialist, QA-adversary
  (**broad cross-agent agreement**)
- **Status:** confirmed state-model defect; triggering interleavings are race-timed but legal.
- **Evidence:** `camera/CameraEngine.kt:3545,3580-3592,3667-3674,3746-3765,7037-7050` admits a
  controller-less dual-open attempt, derives `slotVacant = controller == null` and
  `outgoingOwnsSlot = controller === old` independently, then requires at most one flag. When
  `old == null && controller == null`, Kotlin makes both flags true and the helper throws.
  `camera/CameraController.kt:337-374` also begins closing an evicted outgoing device after its
  Engine callback; because the replaced controller's callback is identity-inert, the current
  pointer-only cleanup can restore that already-terminal owner. The Boolean-only matrix in
  `DualOpenWaitTest.kt:102-135` omits both production-shaped cases, while cycle 35's plan claims
  exhaustive coverage.
- **Failure:** a candidate open refusal plus a newer optics intent can crash/abort the setup lane;
  outgoing eviction during the same window can instead republish a closing CameraController and
  leave a black, permanently Not-Ready preview until another lifecycle reopen.
- **Plan direction:** make cleanup consume production identities plus explicit outgoing
  restorability, totalize the absent-owner state, refuse restoration after terminal failure/close,
  and add an exhaustive identity-derived matrix. Supersede the cycle-35 evidence claim without
  rewriting its history.

### AGG36-02 — optimized Python can attest failed device checks as PASS

- **Severity / confidence:** High / High
- **Sources:** verifier, test-engineer (**cross-agent agreement**)
- **Status:** confirmed with a focused optimized-interpreter reproduction.
- **Evidence:** `device-tests/cases.py` owns device verdicts with 315 plain `assert` statements;
  `device-tests/dtest/framework.py:132-168` treats normal return as PASS and only converts
  `AssertionError` to failure. `device-tests/run.py:547-566` forks and `runpy`-executes the immutable
  child in the same interpreter without rejecting `sys.flags.optimize != 0`, so `python -O` and
  `PYTHONOPTIMIZE=1` strip those checks in both outer and child execution. The current 183 harness
  self-tests have no optimized-mode contract. A smoke case containing only `assert False` returned
  PASS and exit 0 under `PYTHONOPTIMIZE=1`.
- **Failure:** CI/operator optimization can produce a green report and attestation for a frozen
  preview, fatal camera log, wrong container/codec/raster/FPS, or missing output because the checks
  vanish while report generation continues.
- **Plan direction:** fail closed before snapshot/APK/ADB work whenever Python optimization is
  enabled, repeat the guard at the inherited child boundary, and add subprocess regressions for both
  `python -O` and environment-only `PYTHONOPTIMIZE=1`. Keep the guard even if assertions are later
  migrated to an always-on check helper.

### AGG36-03 — enabled custom-control outlines miss the 3:1 non-text contrast floor

- **Severity / confidence:** Medium / High
- **Sources:** critic, designer (**cross-agent agreement**)
- **Status:** confirmed numeric WCAG2ICT non-text-contrast gap.
- **Evidence:** `ui/theme/Theme.kt:117-126` defines active `AffordanceEdge` as 18% white and
  explicitly records its approximately 1.8:1 result. Over opaque `Pill` (`#1C1C1E`) it composites
  to approximately `#454546`, only 1.78:1. Enabled unselected FilterChip boundaries and compact
  close/lens/review buttons consume this edge in `ui/controls/ProControls.kt:203-219,340-400`,
  `ui/controls/ManualDials.kt:431-458`, `ui/CameraScreen.kt:2827-2891`, and
  `ui/review/MediaReview.kt:1715-1734`. These authored active component boundaries are subject to
  the WCAG 2.2 / WCAG2ICT 1.4.11 3:1 floor; disabled controls remain a separate exception.
- **Failure:** low-vision operators can see selected white fills but lose the only boundary that
  identifies other enabled choices/actions, making live controls read as inert labels or glyphs.
- **Plan direction:** raise only the enabled affordance edge to at least 3:1 on its dark plates while
  preserving the quiet 1 dp Sony-style geometry and separate disabled styling. Add palette math and
  rendered/state coverage so enabled, selected, disabled, focus, bright-frame, and dark-frame
  presentations cannot regress.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 10
- Deduplicated current findings: 3
- Severity: 2 High, 1 Medium
- Confidence: 3 High
- Deferred findings: none at review stage; Prompt 2 must schedule or explicitly defer every item.
