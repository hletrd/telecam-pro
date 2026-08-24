# Test-engineer review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b21` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle39.feeBBZ`

## Scope and evidence

- Inventoried all 493 tracked paths: 101 production Kotlin files, 220 JVM/Robolectric/Compose test
  files, four androidTest probes, and 34 Python/shell tooling and device-harness files. Examined the
  production seams behind the tests, the cycle-38 change surface, the committed authorities, the
  field/device-evidence boundary, resources/manifests, release tooling, and prior review/plan history.
- `tools/check_docs.py` passed all 120 available checks (24 optional-private skips), all 99 Python
  tool tests passed, and all 184 device-harness self-tests passed. The five focused JVM/Compose
  suites touched by cycle 38 also passed with the repository's documented JDK/SDK environment.
  No device was connected, so no device-only behavior is claimed.
- Checked current stable toolchain claims against primary repositories on 2026-08-24: Google Maven
  still makes AGP 9.3.2, Compose BOM 2026.08.00, and HeifWriter 1.1.0 the newest stable releases;
  JetBrains' plugin metadata makes Kotlin 2.4.10 the newest stable release; Gradle's current-release
  service reports 9.7.1. No version drift was found.

## Finding

### TEST39-01 — the stabilization regression never exercises the Engine side effects it claims to protect

- **Severity / confidence / status:** Low / High / Confirmed coverage and completion-evidence gap.
- **Exact evidence:** `CameraEngine.setVideoStabMode` owns the observable contract at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1587-1600`: it must store the new
  label while skipping both `applyStabilization()` and `reopenForSession()` for a same-effective HAL
  mode, but must perform both for a real HAL transition. The new tests at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/CaptureCapabilitiesTest.kt:65-100` call only the
  pure `videoStabModeChangeRequiresReconfigure` predicate and never construct an Engine, invoke
  `setVideoStabMode`, or observe request/reopen effects. No other test references
  `CameraEngine.setVideoStabMode`. Nevertheless,
  `docs/plans/2026-08-24-rpf-cycle38.md:27-28` marks “Engine-facing regression coverage” complete,
  and `:75-80` presents no-rebuild/no-reopen as covered completion evidence.
- **Failure scenario:** a later refactor can keep the predicate correct while moving
  `applyStabilization()` or `reopenForSession()` before the guard, omitting the label assignment, or
  applying only one of the two effects for a real transition. Every cycle-38 stabilization test
  stays green even though capability reconciliation again interrupts preview or a user-selected HAL
  mode is not installed.
- **Suggested TDD fix:** add an Engine-level test seam/collaborator that records stabilization apply
  and session-reopen calls. Drive Enhanced→Standard with `[OFF, ON]`, Enhanced→Off with `[OFF]`, and
  Off→Standard/Standard→Enhanced with fully advertised modes; assert stored intent plus exact zero
  versus one side-effect counts. Then add a dated correction to the completed cycle-38 plan rather
  than treating its existing pure-predicate test as Engine-facing.

## Final missed-issues sweep

No ignored/disabled tests, new unseeded randomness, device-evidence overclaim, or additional
cycle-38 flake survived the final sweep. The two scheduler-sensitive capacity tests now establish
their worker-start happens-before edges before submitting the exhaustion probe. The remaining
device-only gaps are explicitly owned by `docs/FIELD_CHECKS.md` and were not misclassified as host
test failures.

## Totals

- New findings: 1
- Severity: 1 Low
- Confidence: 1 High
