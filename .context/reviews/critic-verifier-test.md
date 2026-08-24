# Critic, verifier, and test-engineer review — cycle 41

Date: 2026-08-24

Reviewed revision: `4e4c9dfbce294fb2965a56ea63d74d6096744836` (`origin/main`)

Workspace: isolated cycle-41 worktree `/tmp/find-x9-ultra-cycle41.nWoiMj`

## Scope and evidence

- Inventoried the complete tracked tree and every production/debug Kotlin/Java, JVM/Compose/
  Robolectric/instrumented test, Gradle/resource/manifest, Python/shell tool, device-harness, and
  committed documentation/review/plan path. Read the repository instructions first, then used
  `docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, and the completed cycle-40 plan as the current
  clean-clone behavior/evidence authorities; the optional private backlog/testing/UX files are absent.
- Traced the major cross-file contracts from stated behavior through implementation and tests:
  camera-route/session generations, zoom/exposure/rotation, GL/native ownership, still-family and
  recording durability, recovery/deletion, review latest-wins lanes, lifecycle/permissions, Compose
  controls and accessibility semantics, localization, device-evidence boundaries, and immutable
  build/release provenance. The final sweep also checked ignored/disabled tests, suppressions,
  assertion quality, time-based concurrency tests, warning output, current coverage residuals, and
  cycle-40's changed surfaces.
- Host evidence: all 2,022 JVM/Robolectric/Compose unit tests passed with zero failures, errors, or
  skips. The focused cycle-40 accessibility/durability suites also passed. Documentation contracts
  passed 125/125 available checks (24 optional-private skips); 104 tooling tests, nine coverage-tool
  tests, and 184 device-harness self-tests passed. No device was connected or exercised, and no open
  field check is promoted to host evidence.

## Findings

### CVT41-01 — the MR write “button” still publishes an always-unselected state

- **Severity / confidence / status:** Medium / High / Confirmed accessibility semantics defect.
- **Exact evidence:** `MemoryPresetAction` remains a `FilterChip(selected = false)` and changes only
  its role at `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:736-758`. The repository
  itself records that a FilterChip's selectable node supplies selected/not-selected state at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:328-339`. Consequently changing
  `Role` to `Button` does not remove `SemanticsProperties.Selected=false`. The new regression at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:65-94`
  asserts only `Role.Button`; it never rejects `Selected`, verifies the click action, or covers the
  disabled action. This misses the exact persistent unchecked/not-selected state that cycle 40
  intended to eliminate.
- **Concrete failure:** TalkBack/Switch Access reaches Save or Update as a button that also carries a
  false selection state, so a one-shot command can still be announced or modeled as “not selected.”
  The role-only test stays green because both properties coexist on the same semantics node.
- **Root fix and test:** render the action with a genuinely button-semantic primitive (for example an
  `AssistChip`/button styled to retain the current visuals), rather than relabeling a selectable
  FilterChip. In the composed-tree regression assert `Role.Button`, `OnClick`, and label, assert that
  `SemanticsProperties.Selected` is undefined, invoke the action once, and cover enabled and disabled
  variants.

### CVT41-02 — zoom tests and live guidance still model a retired throttle path through dead inputs

- **Severity / confidence / status:** Low / High / Confirmed test-authority and maintainability defect;
  current runtime suppression is correct.
- **Exact evidence:** executable truth is explicit: a moving gesture submits nothing at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlan.kt:20-36,48-57`, and the edge/quiet
  owners live at `CameraEngine.kt:3876-3898,3977-3989` plus
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:366-384,2072-2117`. Nevertheless the
  nearest `setZoomRatio` comment says the HAL follows “at a throttled pace” and is aimed wide
  mid-gesture (`CameraEngine.kt:3946-3954`), its constants still say mid-gesture submits are merely
  spaced (`CameraEngine.kt:6958-6965`), and ViewModel comments say the engine throttles ticks / every
  non-leading tick submits (`CameraViewModel.kt:2063-2068,2080-2085,2112-2117`). The pure seam still
  accepts `nowMs`, `lastSubmitMs`, and `throttleMs` but uses none of them
  (`ZoomSubmitPlan.kt:38-57`); `lastHalZoomSubmitMs` is correspondingly write-only except for being
  passed to that inert argument (`CameraEngine.kt:3886,3955-3967,3985-3988,4023-4024`). Tests continue
  to vary those dead values and call the cases “throttled” at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlanTest.kt:8-30,65-81,91-109`, providing
  false threshold-coverage signal.
- **Concrete failure:** a maintainer following the live consumer comments can restore periodic
  mid-gesture Camera2 submits or tune a dead “throttle” input, reintroducing the device-measured
  180–413 ms repeating-request stalls while all threshold-looking unit tests remain green.
- **Root fix and test:** remove the dead timing parameters/state from the per-tick decision (or move a
  real shared timing decision into the actual quiet-landing owner), rename the tests around
  suppression/edge ownership, and align every live comment with “start edge, zero moving submits,
  quiet landing, end edge.” Retain the elapsed-time-independent negative test as the runtime fence;
  add a narrow source/document contract if comment drift is to be mechanically rejected.

### CVT41-03 — intentional duplicate-ZIP fixtures leak unasserted warnings from the authoritative gate

- **Severity / confidence / status:** Low / High / Confirmed test-hygiene defect.
- **Exact evidence:** the release-artifact tests append a second packaged source-revision member at
  `tools/tests/test_release_artifact.py:466-478` and a second provenance member at
  `tools/tests/test_release_artifact.py:498-518`. Python 3.14's `zipfile` emits `UserWarning: Duplicate
  name` for both writes; the current 104-test run printed both warnings while passing. The
  authoritative host runner invokes this suite directly without warning handling at
  `tools/verify_host.py:83-85`, and neither test asserts that the warning is the intentional fixture
  side effect.
- **Concrete failure:** genuine new Python warnings are easier to miss in permanently noisy gate
  output, and running the same otherwise-green suite with warnings promoted to errors fails in the
  two deliberately duplicated-member cases.
- **Root fix and test:** wrap each deliberate duplicate `writestr` call in
  `self.assertWarnsRegex(UserWarning, "Duplicate name")` (or construct the malformed archive through
  a warning-free explicit fixture helper). This consumes and verifies the expected warning rather
  than globally suppressing warnings; then assert the full tooling suite emits no unowned warnings.

## Final missed-issues sweep and coverage conclusion

No additional confirmed correctness, security, data-loss, race, flake, or evidence-overclaim defect
survived the final cross-file sweep. There are no ignored/disabled JVM tests, and the maintained
Partition-A ledger exposes exactly 15 reviewed line misses rather than hiding them. The remaining
device-only questions are already explicit in `docs/FIELD_CHECKS.md` (A3, A4, D1, E1, E2) and cannot
be closed by host assertions. The three findings above are all current and independently actionable;
none is a request to infer unmeasured camera behavior.

## Totals

- New findings: 3
- Severity: 1 Medium, 2 Low
- Confidence: 3 High
