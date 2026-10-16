# Critic, verifier, and test-engineer review — cycle 42

Date: 2026-08-24

Reviewed revision: `70ebb875` (`origin/main`)

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle42.rPLjyN`

## Scope and evidence

- Read `CLAUDE.md` and `docs/ARCHITECTURE.md` completely before review. The requested private
  `docs/BACKLOG.md` is absent; `CLAUDE.md` explicitly makes it optional in clean clones, so the
  committed architecture and `docs/FIELD_CHECKS.md` were used as the behavior/evidence authority.
- Inventoried all 504 tracked paths, including production/debug Kotlin and Java, manifests,
  resources, Gradle configuration, JVM/Compose/Robolectric and instrumented tests, Python/shell
  tooling, device-harness code, documentation, plans, and prior review provenance. Traced the main
  cross-file contracts for route/session ownership, zoom/exposure/rotation, Camera2/GL/native
  teardown, still/video durability and recovery, review/delete ownership, permission/lifecycle
  behavior, Compose semantics, localization, and release/device evidence.
- Rechecked the complete cycle-41 change surface and swept for disabled/ignored tests, false-positive
  assertions, warning leakage, stale test authority, timer-driven gaps, and documentation claims
  that do not match executable behavior.
- Host evidence: all 2,022 JVM/Robolectric/Compose tests passed with zero failures, errors, or skips;
  the focused zoom/command-semantics tests passed; 106 tooling tests passed under `python3 -W error`;
  nine coverage-tool tests, 184 device-harness self-tests, and all 126 available documentation checks
  passed (24 optional-private checks skipped). No device was connected or exercised, and no field
  behavior is promoted from host evidence.

## Findings

### CVT42-01 — the command-chip regression tests an impossible state and misses disabled paint

- **Severity / confidence / class:** Medium / High / Confirmed UI-state and false-positive-test
  defect.
- **Exact evidence:** `ImmediateActionChip` passes `enabled` only to clickable `Surface` and to the
  unselected border, while its background and content colors depend exclusively on `active`
  (`app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:755-785`). Therefore a disabled
  inactive MR command keeps full-white enabled-looking text and merely dims its border; more
  importantly, `active=true, enabled=false` is painted exactly like the enabled active state (solid
  white, black text, no border). That second combination is a normal production state: Custom WB
  uses `active = controls.wbMode == CUSTOM` at `ProSheet.kt:1052-1056`, while its enablement requires
  `wbMode == AUTO`, unlocked AWB, manual-WB support, and camera Ready
  (`camera/ControlAvailability.kt:144-150`, `ProSheet.kt:1037-1038`). Active and enabled are thus
  mutually exclusive for this command. Yet the only new regression constructs the unreachable
  `active=true, enabled=true` combination and checks semantics only
  (`app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:86-113`);
  its disabled case is the inactive MR Update command. The cycle-41 completion record consequently
  overclaims “active-paint regressions” at `docs/plans/2026-08-24-rpf-cycle41.md:72-74`.
- **Concrete failure:** after Custom WB is applied, “Capture Custom WB” is correctly non-clickable
  until the operator returns to unlocked Auto WB, but it still renders with the exact fully enabled
  filled treatment. A locked MR Update likewise retains full-strength label ink. Sighted users get a
  contradictory affordance even though TalkBack correctly receives Disabled; the current green test
  cannot observe either reachable visual state.
- **Suggested fix:** define enabled/disabled colors for both active states (preserve active identity
  while visibly dimming a disabled command), then add a rendered/pixel or screenshot regression for
  the reachable matrix: inactive+enabled, inactive+disabled, and active+disabled. Keep the existing
  role/click/no-selection assertions, but do not use active+enabled as Custom-WB evidence.

### CVT42-02 — a quiet zoom gesture performs a third HAL swap that the tests and authority deny

- **Severity / confidence / class:** Medium / High / Confirmed performance/call-sequence defect;
  the documented 180–413 ms device stall per repeating-request swap was not remeasured this cycle.
- **Exact evidence:** a fresh one-flush gesture submits at the start edge through
  `setZoomInteraction(true)` (`CameraViewModel.kt:2078-2107`), schedules an exact quiet landing at
  250 ms and an interaction end at 700 ms (`CameraViewModel.kt:2108-2111`), and both callbacks call
  the engine (`CameraViewModel.kt:368-383`). `landExactZoom()` unconditionally submits the exact ratio
  while interaction remains active (`camera/CameraEngine.kt:3966-3977`). At 700 ms,
  `setZoomInteraction(false)` calls `setSmoothPreviewBoost` again (`CameraEngine.kt:3875-3894`); when
  the boost changes no FPS request key, that controller path still calls `submitZoomFastPath(wire)`
  (`camera/CameraController.kt:1747-1760`), resubmitting the same exact ratio the quiet landing
  already put on the wire. Routes where the boost really changes FPS rebuild instead, so they also
  have a third request, although their end request has a separate purpose.
- **False assurance:** `ZoomSubmitPlanTest` covers only the isolated moving-tick predicate
  (`app/src/test/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlanTest.kt:23-109`); no test references
  `landExactZoom`, `setZoomInteraction`, `setSmoothPreviewBoost`, or the two ViewModel timers as one
  lifecycle. The source KDoc now contains a malformed contradictory splice — “costs exactly TWO
  swaps … instead of one per / A gesture therefore costs edge/landing swaps” — at
  `camera/ZoomSubmitPlan.kt:28-37`. `CLAUDE.md:295-318` and `docs/ARCHITECTURE.md:718-727` likewise
  assert two edge swaps while separately describing the intervening quiet-window swap.
- **Concrete failure:** in the common no-FPS-change path, finger-up is followed by an exact HAL
  landing around 250 ms and then another identical Camera2 request around 700 ms. The repository's
  own measured premise says every such swap can stall preview for roughly 180–413 ms, so the extra
  request produces a late hitch after framing has already converged. All current zoom tests remain
  green because they prove only that moving ticks do not submit.
- **Suggested fix:** extract and test the complete zoom-transition plan (fresh start, moving ticks,
  quiet landing, repinch, and end) with an explicit wire-submit count. Record that the quiet landing
  already sent the exact ratio so a no-FPS-change boost-off can clear interaction state without an
  identical fast-path submit; retain the end rebuild where FPS restoration genuinely requires it.
  Then state the route-specific request count consistently in KDoc, `CLAUDE.md`, and architecture.

### CVT42-03 — cycle-41's archived gate count was stale when committed

- **Severity / confidence / class:** Low / High / Confirmed evidence-record mismatch.
- **Exact evidence:** cycle 41 added two documentation-gate tests
  (`tools/tests/test_tool_contracts.py:423-475`) before its final closeout, bringing the tooling suite
  to 106 tests. A clean `python3 -W error -m unittest discover -s tools/tests -p 'test_*.py' -v` run
  at current HEAD reports 106/106. The later completion record nevertheless says “all 104 tooling
  tests passed” at `docs/plans/2026-08-24-rpf-cycle41.md:77-78`.
- **Concrete failure:** the durable plan is presented as exact completion evidence but names the
  pre-addition suite size, so a reviewer cannot reconcile its claimed gate with the committed test
  inventory. The host/documentation gate validates that some evidence text exists but does not
  validate this count, so it remains green.
- **Suggested fix:** correct the archived completion evidence to the count actually present at the
  closeout commit, or avoid mutable suite totals in completion prose and record the exact command plus
  successful exit instead.

## Final missed-issue and skipped-file sweep

No additional confirmed correctness, security, data-loss, race, flake, or evidence-overclaim defect
survived the final implementation/test/tooling/documentation sweep. There are no ignored or disabled
JVM tests; the two POSIX-only Python skips are explicit platform guards. Binary fonts/images and
prebuilt Gradle artifacts were inventoried but not treated as behavioral source. Existing device-only
items remain correctly open in `docs/FIELD_CHECKS.md`; this host review does not close them.

## Totals

- New findings: 3
- Severity: 2 Medium, 1 Low
- Confidence: 3 High
