# Aggregated deep review — cycle 41

Date: 2026-08-24
Reviewed revision: `4e4c9dfbce294fb2965a56ea63d74d6096744836` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle41.nWoiMj`

## Coverage and aggregation

Five parallel specialist groups covered all required roles: code-reviewer, architect,
performance-reviewer, tracer, security-reviewer, debugger, critic, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Every group inventoried the full 499-path repository, examined relevant production,
test, tooling, resource, documentation, and cross-file interactions, and completed a final
missed-issue sweep. Browser review was inapplicable to this native Compose app. No device behavior
was run or inferred.

Seven raw specialist findings deduplicate to five current root causes. The immediate-command
semantics defect has agreement across code/architecture, critic/verifier/test, and
document/design reviewers. Highest severity and confidence are preserved. Performance/tracing and
security/debugging found no additional actionable defects after focused validation.

## Findings

### AGG41-01 — immediate command chips retain selectable state semantics

- **Severity / confidence:** Medium / High
- **Sources:** code-reviewer/architect, critic/verifier/test-engineer, document-specialist/designer
  (**cross-agent agreement**)
- **Status:** confirmed accessibility interaction-model defect.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:734-759` renders MR
  Save/Update as `FilterChip(selected = false)` and overlays only `Role.Button`; the underlying
  selectable surface still exports `Selected=false`. The adjacent Custom WB measurement action at
  `ProSheet.kt:1019-1034` also uses a FilterChip whose selected state reflects WB mode even though
  activation performs a fresh one-shot measurement. The role-only regression at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:65-94`
  does not reject selected/toggle state or cover the Custom WB command.
- **Failure:** TalkBack/Switch Access can model Save/Update as an always-unselected button and
  Custom WB capture as a checked/unchecked choice, misrepresenting immediate writes as persistent
  selection.
- **Plan direction:** use a genuinely click-only chip/button primitive, keep visual active tint
  independent from accessibility state, and test Button role, click, disabled behavior, and absence
  of Selected/toggle state for both commands.

### AGG41-02 — zoom authority and tests still describe a retired throttle path

- **Severity / confidence:** Low / High
- **Source:** critic/verifier/test-engineer
- **Status:** confirmed test-authority and maintainability defect; runtime suppression is correct.
- **Evidence:** `camera/ZoomSubmitPlan.kt:20-57` suppresses every moving-gesture HAL submit, while
  live comments in `camera/CameraEngine.kt:3946-3954,6958-6965` and
  `ui/CameraViewModel.kt:2063-2117` still describe throttled periodic ticks. `ZoomSubmitPlan` accepts
  dead `nowMs`, `lastSubmitMs`, and `throttleMs` inputs; `lastHalZoomSubmitMs` is write-only except
  for that inert call. `ZoomSubmitPlanTest.kt:8-109` varies those dead inputs under "throttled"
  names, creating false threshold-coverage signal.
- **Failure:** a maintainer can tune or restore periodic mid-gesture Camera2 submissions, reviving
  measured stalls while the misleading threshold tests stay green.
- **Plan direction:** remove dead timing inputs/state, rename tests around start-edge/moving
  suppression/quiet landing/end-edge ownership, and align all live comments with executable truth.

### AGG41-03 — duplicate-ZIP fixtures leak unowned Python warnings

- **Severity / confidence:** Low / High
- **Source:** critic/verifier/test-engineer
- **Status:** confirmed test-hygiene defect.
- **Evidence:** `tools/tests/test_release_artifact.py:466-478,498-518` deliberately writes duplicate
  ZIP members. Python 3.14 emits `UserWarning: Duplicate name` for both, but the tests neither own
  nor assert the warnings; `tools/verify_host.py:83-85` runs the noisy suite directly.
- **Failure:** permanent expected-warning noise obscures new warnings, and warning-as-error runs
  fail despite otherwise-correct tests.
- **Plan direction:** wrap each deliberate duplicate write with `assertWarnsRegex` and verify the
  complete suite has no unowned warnings without global suppression.

### AGG41-04 — architecture inventory omits the Java durability owner

- **Severity / confidence:** Low / High
- **Source:** document-specialist/designer
- **Status:** confirmed current-authority/code mismatch.
- **Evidence:** `app/src/main/java/me/hletrd/telecampro/storage/SharedPreferencesDurableEdit.java:5-21`
  now owns Boolean-returning synchronous preference commits used by
  `storage/MediaStoreWriter.kt:429-438,1228-1233,1407-1415`, but the storage map in
  `docs/ARCHITECTURE.md:107-112` omits it. `tools/check_docs.py:862-875` inventories only
  `app/src/main/kotlin/**/*.kt`, so the completeness gate cannot detect the omission.
- **Failure:** a maintainer can remove or incorrectly replace the seemingly undocumented Java
  bridge and lose fail-closed commit results while the architecture gate remains green.
- **Plan direction:** document the Java owner and make production module-map checking cover both
  Kotlin and Java roots, with a negative Java omission fixture.

### AGG41-05 — live UI comments cite the retired 0.55 guide alpha

- **Severity / confidence:** Low / High
- **Source:** document-specialist/designer
- **Status:** confirmed source-documentation drift; executable token/tests are correct.
- **Evidence:** `ui/theme/Theme.kt:128-162`, `ui/CameraScreen.kt:919-924`, and
  `ui/overlays/Overlays.kt:943-954` compare visual roles against a 0.55 GuideLine even though the
  token and `HudContrastTest.kt:258-271` deliberately pin 0.40. The theme comment also calls the
  0.30-to-0.35 difference one hundredth instead of five hundredths.
- **Failure:** future tuning can restore the rejected heavier guide hierarchy by following the
  nearest live rationale even though the token regression currently remains green.
- **Plan direction:** correct every comparison and arithmetic claim, and add a source/doc contract
  rejecting active 0.55 GuideLine guidance while the token stays 0.40.

## Agent failures

None.

## Totals

- Raw specialist findings: 7
- Deduplicated new findings: 5
- Severity: 1 Medium, 4 Low
- Confidence: 5 High
- Device/manual-only residuals: A3, A4, D1, E1, and E2 remain correctly open in
  `docs/FIELD_CHECKS.md`; none was reclassified as a code defect.
