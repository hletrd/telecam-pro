# QA adversary review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`

Workspace: isolated worktree `/private/tmp/find-x9-cycle39.feeBBZ`

Mode: host-only review; no install, launch, ADB, capture, deployment, provider mutation, or device
claim

## Coverage and adversarial model

I read the committed repository authorities, inventoried all 493 tracked paths, and challenged the
whole production graph rather than sampling only the latest edits. Coverage included startup and
permission denial; foreground/background and surface replacement; physical/logical/front routing;
requested versus accepted session truth; restore/MR normalization; rapid mode/lens/TELE/DNG/zoom
changes; tap-AF and manual controls; ZSL versus long-exposure capture; burst/AEB/timer/timelapse;
record start/stop/snapshot and microphone handoff; GL/Camera2/codec native failures; storage
capacity, provider timeouts and partial publication; capture-family review/deletion; ownerless-media
consent; keyboard, obscured-touch, modal, responsive and localization behavior; and build/release/
device-evidence tooling. I also rechecked all cycle-38 implementation surfaces and their focused
tests for tests that could remain green while behavior stayed broken.

## Findings

No new actionable QA failure survived competing-scenario validation at the reviewed revision.

Cycle 38's two adversarial UI/API failures are closed rather than masked. The selected-disabled
focal chip retains a dark compositing foundation over arbitrary live imagery and the native Compose
test renders every selected/enabled combination on bright and dark frames
(`app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2859,2916-2937`;
`app/src/test/kotlin/me/hletrd/telecampro/ui/controls/AffordanceEdgeComposeTest.kt:110-147`). The
phantom `finderRect.bottomMargin` contract and constant are removed, while position-sensitive tests
now independently vary side inset, top anchor, fractional floor, and measured bottom clearance
(`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:663-711`;
`app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:8-121`). The stabilization
fast path also compares effective HAL values without suppressing a genuine wire-mode transition,
and the two shared-pool regressions now establish exact worker-start order rather than relying on
scheduler timing.

## Host evidence and feature matrix

| Surface | Result | Evidence |
|---|---|---|
| Committed documentation/privacy/release contracts | PASS | `tools/check_docs.py`: 120 applicable checks, 0 failures, 24 optional-private skips. |
| Cycle-38 focal/finder/stabilization/test fixes | PASS (static review) | Current production paths and focused regressions agree with the plans and architecture. |
| EN/KO resource key coverage | PASS | Every absent Korean key is an explicitly non-translatable app/camera/company/trademark label; no prose key is missing. |
| Phone screenshot submission state | PASS (fail-closed) | Manifest names both stale frames, pins their bytes/copy, and keeps `submission_ready=false`. |
| Real camera/HAL, visual target-device and provider behavior | NOT RUN | Host-only review cannot replace field checks A3/A4/D1/E1/E2 or current immutable-device screenshots. |

I did not run the full Gradle/host suite concurrently with other cycle-39 reviewers because the
repository documents one authoritative implementation-phase gate and concurrent Gradle writers can
corrupt transient test-result ownership. Cycle 38's closeout records the green whole-repository gate;
this report claims only the independent documentation run and static evidence above.

## Final adversarial sweep

The final sweep tried stale callbacks after lifecycle replacement; repeated taps during
reconfiguration; modal hardware-key leakage; countdown cancellation races; recording allocation
followed immediately by Stop/pause; provider work that blocks beyond its UI deadline; delete versus
late-sibling publication; review identity replacement during decode/player preparation; exact-bound
zoom/slider keyboard input; disabled selected states over white and black frames; settings at narrow
width/high font scale; Korean label expansion; RTL absolute overlay placement; and stale artifact or
screenshot promotion. Existing identity, capacity, normalization, modal, responsive, and fail-closed
guards cover these scenarios, and no new failure path remained.

## Verdict and totals

**HOST STATIC QA PASSED WITH EXPLICIT DEVICE EVIDENCE GAPS.**

- New repository findings: 0
- Confirmed regressions: 0
