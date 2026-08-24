# Test-engineer review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope and evidence

- Inventoried all 490 tracked paths, including 101 production Kotlin files, 220 JVM/Robolectric/
  Compose test files, four androidTest probes, and 38 Python tooling/device-harness files. Reviewed
  the matching production seams rather than treating coverage or test names as behavioral proof.
- Read the complete committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
  `docs/FIELD_CHECKS.md`), the current plan/review history, resources/manifests, and release/privacy
  material. Optional private `docs/BACKLOG.md` and `docs/UX_POLICY.md` are absent in this clean
  worktree, as the committed authority permits.
- `tools/check_docs.py` passed all 120 available checks (24 optional-private skips); 99 Python tool
  tests and 184 device-harness self-tests passed. No device was connected or used.
- A whole `tools/verify_host.py` attempt overlapped other Cycle 38 reviewers using the same Gradle
  output directory and hit a transient missing `in-progress-results-generic.bin` while Gradle was
  replacing test results. That concurrent-run artifact is not counted as a repository failure; the
  Python portions were rerun independently as above.

## Findings

### TEST38-01 — the focal-rail test never renders the vulnerable selected-disabled state or a bright frame

- **Severity / confidence / status:** Low / High / Confirmed coverage gap over a current visual defect.
- **Exact evidence:** production selects a translucent-white-only treatment at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2855`, and applies it directly
  over the live preview at `CameraScreen.kt:2912-2924`. Selected-disabled is a normal state during
  reconfiguration/recording by `CameraScreenPolicy.kt:659-708`. The Cycle 37 test merely checks
  three alpha numbers for that state at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/AffordanceEdgeComposeTest.kt:65-72`; its
  rendered fixture at `:74-107` includes only enabled and **unselected** disabled chips, both over
  the single dark `CameraColors.Pill` background.
- **Why the green test is insufficient:** unlike the unselected-disabled branch, selected-disabled
  replaces `HudPlate` with `Color.White.copy(alpha = 0.12f)`. On a white/sky/snow frame, that fill,
  its 12%-white border, and its 38%-white label all composite back to white. The selected lens/zoom
  mark can therefore become visually absent precisely while controls are locked. The test's token
  equality assertions encode the faulty recipe rather than testing its rendered outcome.
- **Suggested TDD fix:** first render all four `(selected, enabled)` states over opaque near-black and
  near-white frame fixtures and assert the selected-disabled label/boundary remains distinguishable.
  Then preserve `HudPlate` as the contrast floor and layer a quiet selected tint instead of replacing
  the plate with translucent white.

### TEST38-02 — finder geometry tests pass an inert `bottomMargin` and never assert that it moves the box

- **Severity / confidence / status:** Low / High / Confirmed contract-test gap.
- **Exact evidence:** `finderRect` documents `bottomMargin` as the bottom inset at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:671-678`, but marks the parameter
  unused at `:680-691`; vertical placement is owned by `topAnchor`/`bottomClearance`. The first test
  passes `bottomMargin = 0.10f` at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:18-35` without comparing it
  with another value. The margin-pair test at `:70-88` asserts only width/height equality, so it also
  passes whether the documented bottom inset works or not.
- **Failure scenario:** a future caller tunes the apparently supported `bottomMargin` to clear new
  chrome and receives bit-identical geometry; the suite remains green and the overlay can overlap.
- **Suggested TDD fix:** choose one contract. If the obsolete parameter is removed, update KDoc and
  tests to name `topAnchor`/`bottomClearance` as the only vertical controls. If it remains supported,
  add a metamorphic test proving changing it changes only `y`, then implement that behavior.

## Final coverage/flake sweep

No ignored/disabled tests, vacuous always-true assertions, unseeded behavioral randomness, or new
device-evidence overclaims survived the final sweep. The Android test APK remains packaging evidence
only. Apart from the two gaps above, the current host suites and documentation contracts align with
their production seams.

## Totals

- New findings: 2
- Severity: 2 Low
- Confidence: 2 High
