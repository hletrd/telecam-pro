# Aggregated deep review — cycle 40

Date: 2026-08-24
Reviewed revision: `f7b1bd7f09278eda43737835fabb57874badf18a` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle40.2BfF76`

## Coverage and aggregation

Six parallel specialist groups covered code-reviewer, architect, performance, tracer, security,
debugger, critic, verifier, test engineer, document specialist, and native Android designer. The
designer also fanned out a focused controls audit; a second designer pass in the test/document group
provided an independent zero-finding check. No additional repository-local reviewer agent was
registered. The groups inventoried all 495 tracked paths, read the complete committed authorities,
examined their relevant implementation, tests, tooling, resources, and cross-file interactions, and
completed final missed-issue sweeps. The native Compose UI was reviewed from source, semantics,
resources, host tests, and the device-harness contracts; browser automation is not applicable. No
device behavior was run or inferred.

The 12 raw specialist findings deduplicate to eight current root causes. The broken host gate has
agreement across four specialist groups, and the stale Loupe consumer guidance has agreement across
critic/verifier and test/document specialists. Highest severity and confidence are preserved.

## Findings

### AGG40-01 — cycle-39 closeout makes the authoritative host gate deterministically red

- **Severity / confidence:** Medium / High
- **Sources:** code-reviewer/architect, security/debugger, critic/verifier,
  test-engineer/document-specialist (**cross-agent agreement**)
- **Status:** confirmed quality-gate and completion-evidence defect.
- **Evidence:** `docs/plans/2026-08-24-rpf-cycle39.md:5,39-48,54-73` marks the plan complete and
  claims every configured gate is green, but never names or records
  `python3 tools/verify_host.py`. `tools/check_docs.py:1093-1134` deliberately selects the newest
  completed dated cycle plan and requires that exact authoritative command. Current HEAD therefore
  fails `python3 tools/check_docs.py` (121 checks, one failure), and the red committed-export
  baseline causes three failures in `tools/tests/test_tool_contracts.py:337-389,500-527,554-563`.
- **Failure:** a clean-clone maintainer runs the documented authoritative gate after cycle 39; all
  expensive Android work can pass before the tooling/document phase rejects the checked-in closeout,
  contradicting the durable green claim and blocking a legitimate commit/push cycle.
- **Plan direction:** append a truthful correction to the cycle-39 plan naming the exact canonical
  command, run it after the corrected closeout is present, and record only the resulting evidence.
  Keep the checker and negative contracts intact.

### AGG40-02 — exclusive selectors and one-shot MR actions expose checkbox semantics

- **Severity / confidence:** Medium / High
- **Source:** independent designer/controls audit
- **Status:** confirmed accessibility interaction-model defect.
- **Evidence:** `ui/controls/ProControls.kt:281-365,901-978` declares selector groups but renders
  each exclusive option as Material3 `FilterChip`, whose composed role is Checkbox. The app adds a
  description but does not replace that role. `ui/controls/ProSheet.kt:730-745` likewise renders the
  one-shot MR Save/Update command as an always-unselected FilterChip. The true multi-select photo
  format chips are the interaction model that should remain checkbox-like.
- **Failure:** TalkBack or Switch Access presents P/S/ISO/M and other mutually exclusive choices as
  independent checkboxes, while Save/Update sounds like persistent unchecked state instead of an
  immediate button action.
- **Plan direction:** give exclusive options radio-button semantics, the MR command button
  semantics, retain checkbox semantics for genuine multi-select formats, and add composed-tree role
  regressions for all three models.

### AGG40-03 — manual rulers announce one changing value at two consecutive accessibility stops

- **Severity / confidence:** Low / High
- **Source:** independent designer/controls audit
- **Status:** confirmed accessibility-noise defect.
- **Evidence:** `ui/controls/ManualDials.kt:887-920,924-1268,1377-1389` emits a semantic
  `RulerReadout` immediately before a fully named/value-described adjustable `RulerSlider`. The
  settings slider already clears its mirrored visible header from semantics at
  `ui/controls/ProControls.kt:404-427`.
- **Failure:** every focus/shutter/ISO/WB/EV/zoom ruler makes assistive-technology users traverse and
  hear the same live value twice before reaching the control.
- **Plan direction:** make `RulerReadout` accessibility-decorative, leave the complete contract on
  `RulerSlider`, and assert one value-bearing adjustable node per open ruler.

### AGG40-04 — the debug snapshot host reintroduces the known system-bar contrast bug

- **Severity / confidence:** Low / High
- **Source:** independent designer
- **Status:** confirmed debug visual-evidence defect; production `MainActivity` is correct.
- **Evidence:** `app/src/debug/kotlin/me/hletrd/findx9tele/ui/UiSnapshotActivity.kt:59-72` calls bare
  `enableEdgeToEdge()` under the unconditionally dark app theme. Production explicitly passes dark
  status/navigation styles at `MainActivity.kt:245-258` after device evidence proved that the bare
  call follows system night mode and can produce black icons on the dark viewfinder. The snapshot
  device harness consumes this activity but does not check window appearance.
- **Failure:** the supposedly deterministic visual fixture produces different, potentially unreadable
  system chrome on system-light and system-dark devices, weakening screenshot evidence.
- **Plan direction:** share the explicit dark-system-bar setup with the snapshot activity and add a
  source contract rejecting a bare snapshot-host call.

### AGG40-05 — Loupe Overview's live Compose comment asserts retired visibility and RTL laws

- **Severity / confidence:** Low / High
- **Sources:** critic/verifier, test-engineer/document-specialist (**cross-agent agreement**)
- **Status:** confirmed maintainability/documentation-code mismatch; runtime behavior is correct.
- **Evidence:** `ui/CameraScreen.kt:896-905` says the exact gate is Photo + 4:3 + TELE + punch-in and
  says the border must not mirror to bottom-right. `camera/CameraState.kt:614-643` actually admits
  TELE or unified zoom >= 3x, applies 4:3 only to Photo, and ignores still aspect in Video. The
  right-inset geometry means RTL mirroring would incorrectly move the box to bottom-left. The
  obsolete-contract scan at `tools/check_docs.py:1493-1507` omits `CameraScreen.kt`.
- **Failure:** a maintainer following the nearest live-source guidance can remove valid Video or
  converterless overview behavior or invert the physical RTL law while the documentation gate stays
  green.
- **Plan direction:** correct the comment to the shared predicate and absolute right-inset law, add
  `CameraScreen.kt` to both Loupe contracts, and add a negative fixture for the stale wording.

### AGG40-06 — rotation/mirroring KDoc misstates closed versus open field evidence

- **Severity / confidence:** Low / High
- **Source:** code-reviewer/architect
- **Status:** confirmed source-authority drift; no runtime defect alleged.
- **Evidence:** `camera/RotationMath.kt:172-180` calls held-landscape external-player validation an
  open residual and points only to absent private `docs/BACKLOG.md`, while committed
  `docs/FIELD_CHECKS.md:101-139` records B1 passed and rotation closed end to end.
  `gl/FrontMirrorConvention.kt:38-58` correctly calls the rotated-window calibration open but again
  points only to the absent backlog; committed A4 at `docs/FIELD_CHECKS.md:47-90` is the runnable
  clean-clone authority.
- **Failure:** clean-clone maintainers are directed to repeat closed B1 work while the real open A4
  calibration is obscured next to sensitive sign/axis code.
- **Plan direction:** cite committed B1 as closed and A4 as open in live KDoc, replace private-only
  operational pointers, and contract-check the open/closed wording.

### AGG40-07 — the top-level REC-border authority mandates a rejected radius multiplier

- **Severity / confidence:** Low / High
- **Source:** independent designer
- **Status:** confirmed authority/source drift; current rendering follows the later device fix.
- **Evidence:** `CLAUDE.md:380-384` instructs maintainers to scale the rounded-corner radius by 1.2,
  while `ui/CameraScreen.kt:977-1001` deliberately uses the platform radius unscaled and records the
  later 2026-07-29 device result that 1.2 turns the arc too early and leaves side gaps. The nearby
  summary at `CLAUDE.md:389-391` no longer preserves the multiplier.
- **Failure:** following the top-level authority restores the exact device-rejected REC border
  geometry while host tests can stay green.
- **Plan direction:** correct the authority to the unscaled `RoundedCorner.radius`, preserve the
  device rationale, and add a narrow source/document contract rejecting the retired multiplier.

### AGG40-08 — seven lint warnings remain neither fixed nor strictly deferred

- **Severity / confidence:** Low / High
- **Source:** test-engineer/document-specialist
- **Status:** confirmed gate-hygiene and completion-evidence defect.
- **Evidence:** current `:app:lintDebug` reports seven warnings: Compose `ModifierParameter` at
  `ui/overlays/Overlays.kt:601-606`; `PluralsCandidate` at `res/values/strings.xml:74`;
  `NotShrinkingResources` at `app/build.gradle.kts:563-566`; `UseKtx` at
  `ui/review/MediaReview.kt:498-514`; and three `UseKtx` warnings at
  `storage/MediaStoreWriter.kt:429-437,1402-1412`. None appears in the latest plan's strict warning
  deferral record, while that plan claims no actionable warning appeared.
- **Failure:** permanent gate noise hides newly introduced warnings and leaves completion evidence
  inconsistent with the generated report.
- **Plan direction:** root-fix all behavior-preserving warnings, preserving synchronous durability
  and exact bitmap behavior; if any genuinely cannot be fixed, record its original lint identity,
  reason, and reopening criterion under the strict deferred rules instead of suppressing it.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 12
- Deduplicated current findings: 8
- Severity: 2 Medium, 6 Low
- Confidence: 8 High
- Deferred findings: none at review stage; Prompt 2 must schedule or explicitly defer every item.

---

<!-- Prior aggregate retained in place as non-destructive review history. -->

# Aggregated deep review — cycle 39

Date: 2026-08-24
Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224` (`origin/main`)
Workspace: clean detached worktree `/private/tmp/find-x9-cycle39.feeBBZ`

## Coverage and aggregation

Six parallel specialist groups covered code-reviewer, architect, performance, tracer, security,
debugger, critic, verifier, test engineer, document specialist, native Android designer,
feature-development code reviewer, and QA adversary. No additional repository-local reviewer agent
was registered. Every group inventoried all 493 tracked paths, read the complete committed
authorities, examined its relevant implementation, tests, tooling, resources, and cross-file
interactions, and completed a final missed-issue sweep. The native Compose UI was reviewed from
source, semantics, resources, and host tests; browser automation is not applicable. No device
behavior was run or inferred.

The three raw specialist findings deduplicate to two current root causes. The test engineer and
document specialist independently identified the stabilization evidence gap, so that finding carries
cross-agent agreement. Highest severity and confidence are preserved.

## Findings

### AGG39-01 — stabilization coverage does not exercise the Engine side-effect boundary

- **Severity / confidence:** Low / High
- **Sources:** test-engineer, document-specialist (**cross-agent agreement**)
- **Status:** confirmed test-coverage and completed-plan evidence gap.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1587-1600`
  owns the contract that stores the normalized label while skipping both `applyStabilization()` and
  `reopenForSession()` for a same-effective HAL mode, and performs both effects for a real HAL
  transition. The cycle-38 tests at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/CaptureCapabilitiesTest.kt:65-100` exercise only
  `videoStabModeChangeRequiresReconfigure`; no test invokes `CameraEngine.setVideoStabMode` or
  observes its state/effect ordering. `docs/plans/2026-08-24-rpf-cycle38.md:27-28,75-80`
  nevertheless records Engine-facing coverage as complete.
- **Failure:** a refactor can keep the pure predicate correct while moving either Engine effect
  before the guard, omitting the stored label update, or dropping one real-transition effect; every
  existing stabilization regression remains green.
- **Plan direction:** add a narrow Engine-level observation seam and regression coverage that drives
  same-effective and real-effective transitions through `setVideoStabMode`, asserting stored intent
  and exact apply/reopen counts; append a dated correction to the cycle-38 completion record.

### AGG39-02 — Loupe Overview authority and renderer comment name the wrong corner

- **Severity / confidence:** Low / High
- **Source:** document-specialist
- **Status:** confirmed documentation/code mismatch.
- **Evidence:** `CLAUDE.md:251-253`, `docs/ARCHITECTURE.md:745-749`, and
  `app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:1067-1073` call the viewport
  bottom-left. `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:665-691` explicitly
  right-insets it to avoid the left exposure/zoom ruler, while the Compose consumer and
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:18-33` pin the resulting
  bottom-right placement.
- **Failure:** a maintainer following either current authority can move or test the overview into the
  persistent left ruler, restoring the user-reported overlap that the right-inset law fixed.
- **Plan direction:** correct both current authorities and the renderer comment to bottom-right, and
  extend the documentation contract checker to bind the wording to the right-edge geometry law.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 3
- Deduplicated current findings: 2
- Severity: 2 Low
- Confidence: 2 High
- Deferred findings: none at review stage; Prompt 2 must schedule or explicitly defer every item.

---

<!-- Prior aggregate retained in place as non-destructive review history. -->

# Aggregated deep review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299562d52f6b4ddd200f6d410ebd00a54c1d` (`origin/main`)
Workspace: clean detached worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Coverage and aggregation

Five parallel specialist groups covered code-reviewer, architect, performance, tracer, security,
debugger, critic, verifier, test engineer, document specialist, native Android designer,
feature-development code reviewer, and QA adversary. No additional repository-local reviewer agent
was registered. Every group inventoried all 490 tracked paths, read the complete committed
authorities, examined its relevant implementation, tests, tooling, resources, and cross-file
interactions, and completed a final missed-issue sweep. The native Compose UI was reviewed from
source, semantics, resources, and host tests; browser automation is not applicable. No device
behavior was run or inferred.

The 15 raw specialist findings deduplicate to four current root causes. The dead finder-geometry
input has agreement across eight roles; the selected-disabled focal-chip contrast loss has agreement
across five roles. Performance and tracing independently confirmed the redundant stabilization
reconfiguration, while the debugger reproduced the scheduler-dependent gate flake. Highest
severity and confidence are preserved.

## Findings

### AGG38-01 — stabilization label normalization triggers redundant camera reconfiguration

- **Severity / confidence:** Medium / High
- **Sources:** perf-reviewer, tracer (**cross-agent agreement**)
- **Status:** confirmed control-flow defect; device timing intentionally unclaimed.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2878-2925`
  normalizes a requested stabilization label after capability arrival and calls
  `CameraEngine.setVideoStabMode`. `camera/CaptureCapabilities.kt:551-583` can map both the old and
  normalized labels to the same HAL mode. `camera/CameraEngine.kt:1561-1594,2844-2885,3782-3810`
  nevertheless performs an unchanged request rebuild and then a full reopen under the same optics
  generation. Existing capability and quick-control tests validate projected labels but not Engine
  side effects.
- **Failure:** capability-only state reconciliation such as Active→Standard or Active→Off can pay
  two camera disruptions without changing the accepted Camera2 request, producing avoidable preview
  interruption and thermal/battery work.
- **Plan direction:** separate requested-label reconciliation from effective HAL changes, avoid the
  Engine command when old and new resolve identically, and add a side-effect regression that proves
  capability-only normalization does not rebuild/reopen the camera.

### AGG38-02 — shared-pool capacity test races the latest-wins contract it is testing

- **Severity / confidence:** Medium / High
- **Source:** debugger
- **Status:** confirmed scheduler-dependent quality-gate failure; not a production-app defect.
- **Evidence:** `app/src/test/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLaneTest.kt:418-450`
  submits two requests back-to-back on each of two latest-wins lanes and then expects all four
  callbacks to start. `app/src/main/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLane.kt:163-171,242-254`
  intentionally replaces a lane's predecessor through a conflated latest-request channel. The
  authoritative host gate failed once after 2,011 tests at the start latch; a clean full rerun and
  eight focused reruns passed.
- **Failure:** under host load, a successor legally retires its predecessor before the predecessor
  starts, so the four-start latch can never complete and a correct tree intermittently fails its
  blocking quality gate.
- **Plan direction:** saturate the pool with one blocking request on each of four independent lanes
  (or add exact predecessor-start handshakes) before testing the healthy lane's bounded exhaustion;
  do not mask the missing happens-before edge with a longer timeout.

### AGG38-03 — selected-disabled focal chips lose their contrast floor on bright preview content

- **Severity / confidence:** Low / High
- **Sources:** test-engineer, document-specialist, designer, feature-dev-code-reviewer, QA adversary
  (**cross-agent agreement**)
- **Status:** confirmed compositing and coverage defect; target-device visual confirmation remains
  appropriate after the fix.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:659-708` produces
  `selected=true, enabled=false` for the current focal choice throughout recording and optics
  reconfiguration. `ui/CameraScreen.kt:2835-2855,2912-2924` uniquely replaces the dark `HudPlate`
  foundation with translucent white container, border, and label layers. Over sky, snow, a white
  wall, or an overexposed frame, those layers converge toward the frame and can erase the active
  label and boundary. `AffordanceEdgeComposeTest.kt:65-107` renders neither selected-disabled nor a
  bright background, although `docs/plans/2026-08-24-rpf-cycle37.md:47-52` claims that matrix.
- **Failure:** while the focal rail is locked during REC or camera reconfiguration, the operator can
  lose the only visible indication of the active lens/zoom on common bright scenes.
- **Plan direction:** retain a dark live-preview contrast foundation beneath the quiet disabled
  selection treatment, cover all four selected/enabled states on near-white and near-black frame
  fixtures, and correct the completed-plan evidence without rewriting history.

### AGG38-04 — `finderRect.bottomMargin` is a documented and tested no-op

- **Severity / confidence:** Low / High
- **Sources:** code-reviewer, architect, critic, verifier, test-engineer, document-specialist,
  feature-dev-code-reviewer, QA adversary (**cross-agent agreement**)
- **Status:** confirmed API/documentation/test-contract defect.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:363-369,671-730`
  exposes `FINDER_BOTTOM_MARGIN` and documents `bottomMargin`, then suppresses the unused parameter;
  vertical placement is derived only from `topAnchor` and `bottomClearance`.
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:8-35,70-96` passes the
  phantom input without asserting that it affects position.
- **Failure:** callers and maintainers see two apparent vertical-placement models, and future code
  can tune the inert margin with green tests while the overlay does not move.
- **Plan direction:** remove the obsolete parameter and constant, update every caller/KDoc, and pin
  the real top-anchor/bottom-clearance laws with position-sensitive tests.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 15
- Deduplicated current findings: 4
- Severity: 2 Medium, 2 Low
- Confidence: 4 High
- Deferred findings: none at review stage; Prompt 2 must schedule or explicitly defer every item.
