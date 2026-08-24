# Feature-development code review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Coverage

Inventoried all 490 tracked files and examined every production subsystem, its relevant tests,
resources, build/release tooling, and committed authority from the feature-integrator perspective.
The final sweep emphasized the Cycle 37 implementation surface and cross-file UI-policy/test
contracts. No source, plan, or build file was modified.

## Findings

### FDEV38-01 — selected-disabled focal-rail colors discard the shared live-preview contrast floor

- **Severity / confidence / status:** Low / High / Confirmed.
- **Exact regions:** state production at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:659-708`; color resolution and
  draw at `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2855,2912-2924`; missing
  render matrix at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/AffordanceEdgeComposeTest.kt:65-107`.
- **Problem:** the selected-disabled branch uses translucent white as the container itself, while
  the unselected-disabled branch uses `HudPlate`. Because `background()` composites that color
  directly onto the camera frame, bright pixels erase the container, border, and label together.
- **Failure scenario:** start recording while aimed at sky/snow. The active lens chip becomes
  disabled and can visually disappear, even though sibling unavailable choices retain their dark
  plates.
- **Suggested fix:** express selected-disabled as a composition of the shared plate and a selection
  tint, not a replacement plate. Add bright/dark rendered tests for all four state combinations.

### FDEV38-02 — `finderRect` carries a dead parameter whose documented contract survives in tests

- **Severity / confidence / status:** Low / High / Confirmed maintainability/API defect.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:671-719` and
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:8-35,70-96`.
- **Problem:** the function says `bottomMargin` controls the vertical inset, but suppresses the
  parameter as unused after the geometry migrated to `topAnchor`/`bottomClearance`. Tests still pass
  custom bottom-margin values yet assert only size or the unrelated clearance floor.
- **Failure scenario:** a feature author adjusts the advertised parameter to make room for a new
  bottom control; nothing moves and all tests pass.
- **Suggested fix:** delete the parameter and stale contract if it is obsolete, updating call sites
  and tests, or restore a well-defined vertical effect with an axis-isolation test.

## Final sweep

Cycle 37's Gamma and stabilization projections, ZSL comparator, privacy parity, optimized-Python
guards, and MR comment are internally consistent. No further feature-integration defect survived.

## Totals

- New findings: 2
- Severity: 2 Low
- Confidence: 2 High
