# Aggregated deep review — cycle 42

Date: 2026-08-24
Reviewed revision: `70ebb8759b567dcd2ee13bd51b226da2568ff6d7` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle42.rPLjyN`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, critic, architect,
performance-reviewer, tracer, security-reviewer, debugger, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Each group inventoried all 504 tracked paths and examined relevant production, test,
tooling, resource, documentation, and cross-file interactions. Browser automation was inapplicable
to this native Compose app. No device behavior was run or inferred.

Eight raw specialist findings deduplicate to five current root causes. The disabled command paint
defect has agreement across code/architecture, verifier/testing, and document/design. The zoom
authority defect has agreement across code/architecture and document/design and intersects with the
verifier's independently confirmed redundant end submission; these remain separate findings because
one is a current runtime request defect and the other is a test/ownership defect that would survive
fixing that request. Highest severity and confidence are preserved. Performance/tracing and
security/debugging found no additional actionable defect.

## Findings

### AGG42-01 — disabled immediate commands retain enabled-strength paint

- **Severity / confidence:** Medium / High
- **Sources:** code-reviewer/critic/architect, verifier/test-engineer,
  document-specialist/designer (**cross-agent agreement**)
- **Status:** confirmed visual-affordance and false-positive-test defect.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:755-785` passes
  `enabled` to click admission and the inactive border, but derives container/content paint only
  from `active`. Consequently a disabled inactive MR write keeps full-strength label ink, while an
  active disabled Custom-WB command is pixel-identical to an enabled active command. The latter is
  a normal state because Custom WB is active only for `wbMode == CUSTOM` but measurement admission
  requires `wbMode == AUTO` (`camera/ControlAvailability.kt:144-150`; `ProSheet.kt:1037-1057`).
  `SelectorRoleSemanticsComposeTest.kt:68-115` checks semantics, constructs the production-impossible
  `active=true, enabled=true` Custom-WB combination, and never validates reachable disabled paint.
- **Failure:** after capturing Custom WB, the strongest filled-white command is unavailable and
  silently ignores taps; locked MR writes likewise look enabled even though accessibility correctly
  reports Disabled.
- **Plan direction:** resolve container/content/border colors from both `active` and `enabled`, keep
  click-only semantics, and test the reachable inactive-enabled, inactive-disabled, and
  active-disabled visual/token states without treating active-enabled as Custom-WB evidence.

### AGG42-02 — dropdown radio options lack selectable-group semantics

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Status:** confirmed accessibility-structure defect.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:785-875` exports
  `Role.RadioButton` and `Selected` on each Phone/Converter option, but the containing
  `DropdownMenu` has no `selectableGroup()` modifier. `DropdownSemanticsComposeTest.kt:66-104`
  proves radio leaves but never requires their shared group.
- **Failure:** TalkBack/Switch Access receives individually selected/not-selected radio buttons
  without the semantic relationship and reliable position/count context that explains one choice
  replaces another.
- **Plan direction:** apply `selectableGroup()` to the popup option container while retaining
  bounded scrolling and leaf semantics, then assert exactly one selectable-group ancestor for the
  radio options before and after selection.

### AGG42-03 — quiet zoom completion redundantly submits a third Camera2 request

- **Severity / confidence:** Medium / High
- **Source:** verifier/test-engineer
- **Status:** confirmed runtime call-sequence/performance defect; device stall duration was not
  remeasured this cycle.
- **Evidence:** a gesture submits its wide start edge, schedules `landExactZoom()` at 250 ms, and
  ends interaction at 700 ms (`ui/CameraViewModel.kt:368-383,2078-2111`). The quiet landing
  unconditionally submits exact framing (`camera/CameraEngine.kt:3966-3977`). Boost-off then calls
  `setSmoothPreviewBoost` again (`CameraEngine.kt:3875-3894`), whose no-FPS-change branch still
  calls `submitZoomFastPath(wire)` (`camera/CameraController.kt:1747-1760`) with the same exact
  ratio; routes that restore FPS also issue an end rebuild for a distinct reason.
- **Failure:** on the common no-FPS-change route, the already-landed exact framing is resubmitted
  roughly 450 ms later, causing an avoidable late request swap/hitch under the repository's measured
  Camera2 swap behavior.
- **Plan direction:** make the complete zoom transition explicitly track whether quiet landing
  already put exact framing on the wire; clear interaction state without an identical end fast-path
  submit when FPS is unchanged, retain end rebuilds that genuinely restore FPS, and test full
  start/move/quiet/repinch/end request sequences.

### AGG42-04 — zoom tests and authority model a dead wide-aim owner and retired throttle

- **Severity / confidence:** Low / High
- **Sources:** code-reviewer/critic/architect, document-specialist/designer
  (**cross-agent agreement**)
- **Status:** confirmed test-authority and documentation defect; current edge arithmetic is correct.
- **Evidence:** the actual start-edge wide target is independently calculated and submitted by
  `CameraEngine.setZoomInteraction` (`camera/CameraEngine.kt:3875-3894`). The parallel wide target
  in `ZoomSubmitPlan.kt:39-53` is produced only for moving `setZoomRatio` calls whose
  `submitNow=false`, so runtime discards it. `ZoomSubmitPlanTest.kt:23-109` tests that dead value and
  never exercises the real edge submission. `docs/ARCHITECTURE.md:71,718-727`,
  `camera/CameraController.kt:530-535`, `ui/ZoomGlideState.kt:41-50`, and nearby ViewModel/KDoc text
  still describe a removed throttle, malformed fixed swap count, or ownership the pure plan does
  not have.
- **Failure:** the real start-edge clamp can regress while all wide-aim tests stay green, or a
  maintainer can restore periodic submissions/remove quiet landing by following stale authority.
- **Plan direction:** extract one pure start-edge target resolver used by the real submission path,
  remove dead plan outputs/inputs, directly test edge margin/clamping, and align source/docs with
  moving-tick suppression plus route-specific start, optional quiet landing, and end behavior.

### AGG42-05 — cycle-41 completion evidence records the wrong tooling-test count

- **Severity / confidence:** Low / High
- **Source:** verifier/test-engineer
- **Status:** confirmed archived evidence mismatch.
- **Evidence:** cycle 41 added two documentation-gate tests in
  `tools/tests/test_tool_contracts.py:423-475`, making the committed suite 106 tests. Its later
  completion record still says 104 at `docs/plans/2026-08-24-rpf-cycle41.md:77-78`; a current
  warnings-as-errors run passes 106/106.
- **Failure:** the durable closeout cannot be reconciled with the test inventory present in the
  commit it describes, while the documentation gate remains green.
- **Plan direction:** correct the archived count to 106, or record the exact successful command and
  exit status without a mutable total.

## Agent failures

None.

## Totals

- Raw specialist findings: 8
- Deduplicated new findings: 5
- Severity: 3 Medium, 2 Low
- Confidence: 5 High
- Device/manual-only residuals: A3, A4, D1, E1, and E2 remain correctly open in
  `docs/FIELD_CHECKS.md`; none was reclassified as a code defect.
