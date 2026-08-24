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
