# Aggregated deep review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)
Workspace: clean detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Coverage and aggregation

Five parallel specialist groups covered code-reviewer, architect, performance, tracer, security,
debugger, verifier, test engineer, critic, document specialist, and native Android designer. No
additional repository-local reviewer agent was registered. Every group inventoried all 489 tracked
paths, read the complete committed authorities, examined its relevant implementation, tests,
tooling, resources, and cross-file interactions, and completed a final missed-issue sweep. The native
Compose UI was reviewed from source, semantics, resources, and tests; browser automation is not
applicable. No device behavior was run or inferred.

The 16 raw specialist findings deduplicate to seven current root causes. Optimized-Python host-gate
integrity and ZSL boundary drift each have cross-agent agreement and concrete reproductions. Gamma,
stabilization, disabled-rail styling, and privacy drift have cross-role critic/document/designer
agreement. The stale source comment is independently confirmed. Highest severity and confidence are
preserved.

## Findings

### AGG37-01 — optimized Python can false-green the authoritative host documentation gate

- **Severity / confidence:** Medium / High
- **Sources:** verifier, test-engineer, security-reviewer (**cross-agent agreement**)
- **Status:** confirmed with normal-versus-optimized evidence.
- **Evidence:** `tools/verify_host.py:50-98` accepts `-O` and inherited `PYTHONOPTIMIZE`, then invokes
  `tools/check_docs.py` through the same interpreter. `tools/check_docs.py:320,323,362,364,433`
  uses removable assertions for operational invariants, including exact-millisecond ZSL authority.
  With `ZSL_MAX_FRAME_AGE_NS` changed in memory to `400_000_001L`, normal execution fails while
  optimized execution reports all 112 checks green. The cycle-36 device-runner guard protects only
  `device-tests/run.py`.
- **Failure:** CI or a maintainer can receive a green authoritative host result after Python has
  deleted correctness-bearing documentation checks.
- **Plan direction:** reject optimized execution at the outer host verifier and documentation-tool
  entries, migrate operational assertions to always-on verdicts, and add `-O` plus environment-only
  optimization regressions.

### AGG37-02 — Gamma quick controls stay enabled for a singleton SDR projection

- **Severity / confidence:** Medium / High
- **Sources:** critic, document-specialist, designer (**cross-agent agreement**)
- **Status:** confirmed capability-projection and interaction defect.
- **Evidence:** `camera/CameraState.kt:1164-1187,1495-1501` projects no-Main10 HEVC to `[SDR]`, and
  `ui/controls/ProControls.kt:919-975` correctly filters the menu. In contrast,
  `ui/controls/ControlCycles.kt:230-244`, `FnQuickActions.kt:98-143`, and
  `ManualDials.kt:621-632` keep Gamma hot and cycle the global list; `CameraViewModel.kt:2283-2299`
  normalizes every unsupported request back to SDR. Existing quick-action tests encode that gap.
- **Failure:** on an 8-bit HEVC encoder or before inventory truth arrives, Fn overlay, My Menu, and
  expanded DISP provide tap feedback but never change Gamma, reading as a broken control.
- **Plan direction:** derive value, enablement, and cycling from one advertised transfer projection;
  disable singleton/loading states and test every quick surface.

### AGG37-03 — stabilization UI reports requested labels instead of the HAL mode available

- **Severity / confidence:** Medium / High
- **Sources:** critic, document-specialist, designer (**cross-agent agreement**)
- **Status:** confirmed capability/state/OSD truth defect.
- **Evidence:** `camera/CaptureCapabilities.kt:195-201,244-250,551-562` can resolve requested
  STANDARD/ENHANCED to OFF or ordinary ON, but `ui/controls/ProSheet.kt:1246-1269`,
  `FnQuickActions.kt:122`, `ManualDials.kt:574-587`, `CameraViewModel.kt:2943-2952`, and
  `ui/overlays/Overlays.kt:962-970` expose and retain the unfiltered request.
- **Failure:** OFF-only hardware can show `STAB STD`/`STEADY`; OFF+ON hardware can claim Active and
  extra crop while only ordinary stabilization is applied.
- **Plan direction:** single-source exact advertised choices, normalize state when route capabilities
  arrive, and make menu, quick controls, captions, and OSD consume applied truth with capability
  matrix coverage.

### AGG37-04 — disabled focal-rail choices paint the full enabled-strength outline

- **Severity / confidence:** Low / High
- **Sources:** critic, designer (**cross-agent agreement**)
- **Status:** confirmed visual-state regression after the cycle-36 enabled-edge correction.
- **Evidence:** `ui/theme/Theme.kt:115-127` defines the 36%-white enabled edge, while
  `ui/CameraScreen.kt:2827-2905` paints it unconditionally for `RailChip`; disabled policy at
  `ui/CameraScreenPolicy.kt:644-680` dims only text. Existing Compose coverage exercises FilterChip,
  not the focal rail.
- **Failure:** recording/reconfiguration states visually advertise unavailable lens choices as
  strongly as tappable choices even though semantics correctly disable them.
- **Plan direction:** add a quiet disabled rail edge and coherent selected-disabled fill while
  retaining the enabled 3:1 edge; cover the full rail state matrix over bright and dark frames.

### AGG37-05 — ZSL freshness prose excludes a boundary production admits

- **Severity / confidence:** Low / High
- **Sources:** verifier, test-engineer (**cross-agent agreement**)
- **Status:** confirmed documentation/behavior mismatch.
- **Evidence:** `camera/ZslAdmission.kt:87-90` rejects only ages greater than 400 ms, and
  `camera/ZslAdmissionTest.kt:93-98` explicitly admits exactly 400 ms. `CLAUDE.md:219-221` and
  `docs/ARCHITECTURE.md:68` promise `< 400 ms`; `tools/check_docs.py:366-375` checks only the numeral,
  not comparator parity.
- **Failure:** the green contract checker permits code/tests and both top-level authorities to define
  different admissible sets.
- **Plan direction:** align the authorities with the inclusive code/test evidence and make the docs
  gate validate comparator as well as number.

### AGG37-06 — same-date privacy-policy presentations disclose different facts

- **Severity / confidence:** Low / High
- **Sources:** critic, document-specialist (**cross-agent agreement**)
- **Status:** confirmed current-policy drift.
- **Evidence:** `PRIVACY.md:1-38` omits capture metadata, the explicit no-location/GPS statement, and
  broad on-device library-read/no-transmission facts present in `privacy-policy/index.html:225-247`
  and the EN/KO in-app policy strings. All presentations carry the same update date, and
  `tools/check_docs.py` does not enforce these facts.
- **Failure:** repository readers receive a materially less complete policy than browser and in-app
  readers while every copy presents itself as current.
- **Plan direction:** synchronize all active copies and add parity checks for metadata, no location,
  and on-device library access.

### AGG37-07 — the MR-row comment falsely equates its 0.18 tint with `AffordanceEdge`

- **Severity / confidence:** Low / High
- **Sources:** code-reviewer
- **Status:** confirmed source-documentation drift.
- **Evidence:** `ui/controls/ProSheet.kt:695-698` calls the independent amber
  `ManualActive.copy(alpha = 0.18f)` wash “the white AffordanceEdge — same number,” but cycle 36
  raised `AffordanceEdge` to 0.36 in `ui/theme/Theme.kt:115-127`.
- **Failure:** a later palette cleanup can mistakenly couple the quiet amber MR selection wash to
  the stronger interactive-boundary token.
- **Plan direction:** correct the comment without changing either pixel value.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 16
- Deduplicated current findings: 7
- Severity: 3 Medium, 4 Low
- Confidence: 7 High
- Deferred findings: none at review stage; Prompt 2 must schedule or explicitly defer every item.
