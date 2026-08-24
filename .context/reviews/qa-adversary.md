# QA adversary review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle38.FKvYBP`

Mode: host-only by directive; no install, launch, ADB, capture, deployment, or device mutation

## Host evidence

- `tools/check_docs.py`: **PASS**, 120 checks, 24 optional-private skips.
- Python tool suites: **PASS**, 99 tests.
- Device-harness self-tests with fake ADB: **PASS**, 184 tests. These validate the harness, not the
  app on hardware.
- A combined `tools/verify_host.py` invocation collided with other Cycle 38 reviewers concurrently
  replacing the shared Gradle test-results directory and ended at a missing transient
  `in-progress-results-generic.bin`. This is not treated as app evidence or a repository failure;
  the orchestrating implementation phase must run the authoritative gate once without concurrent
  Gradle writers.

## Findings

### QA38-01 — bright-scene recording hides the active focal-rail state by construction

- **Severity / confidence / status:** Low / High / Confirmed static UI failure.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:659-708` and
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2855,2912-2924`.
- **Adversarial scenario:** aim at a white wall/sky, select a lens, and begin recording. Recording
  makes the selected chip disabled. Its branch discards `HudPlate` and paints white at 12% (fill and
  edge) plus white at 38% (label) directly over the white frame, making the active choice
  effectively absent. Unselected disabled siblings keep the dark plate and remain visible.
- **Why tests miss it:** `AffordanceEdgeComposeTest.kt:65-107` does not render selected-disabled and
  never uses a bright frame; its alpha assertions merely confirm the faulty constants.
- **Suggested fix/proof:** retain a dark contrast foundation, then run a native Compose bright/dark
  four-state matrix and a target-device recording check before claiming closure.

### QA38-02 — the advertised finder bottom-margin knob is a no-op that green tests exercise without verifying

- **Severity / confidence / status:** Low / High / Confirmed static contract failure.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:671-719` and
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:18-35,70-96`.
- **Adversarial scenario:** set `bottomMargin` to 0 and then an extreme value while leaving
  `topAnchor`/`bottomClearance` fixed. The box is bit-identical because the parameter is explicitly
  unused, although the KDoc promises a different bottom inset. Existing tests stay green because
  they never compare `y` across bottom-margin values.
- **Suggested fix/proof:** remove the false knob and stale docs/tests, or make it affect only `y` and
  add a metamorphic assertion that would fail on the current code.

## Feature matrix

| Surface | Result | Evidence |
|---|---|---|
| Documentation, privacy, release contracts | PASS | 120 available checks pass; optional private docs absent by policy. |
| Python release/tool logic | PASS | 99 host tests pass. |
| Device harness safety/contracts | PASS (harness only) | 184 self-tests pass with fake ADB. |
| Selected-disabled focal rail over bright scene | FAIL (static) | QA38-01. |
| Finder `bottomMargin` behavior | FAIL (static contract) | QA38-02. |
| Real camera, recording, storage, orientation, field checks | BLOCKED BY DIRECTIVE | No device use was authorized or attempted. |

## Final verdict

**HOST EVIDENCE PARTIAL; STATIC QA NOT PASSED.** The independently runnable Python gates are green,
but QA38-01 and QA38-02 are current failures, and hardware behavior remains untested by directive.

## Totals

- New repository findings: 2
- Severity: 2 Low
- Confidence: 2 High
