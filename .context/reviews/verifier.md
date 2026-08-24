# Verifier review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299`

Role: evidence-based contract verification

## Verification scope

Built a complete tracked-file inventory and verified the committed behavioral authorities against
production call sites, tests, resources, and tooling. Particular attention went to Camera2/GL
generation ownership, accepted output and capability truth, exposure/zoom/rotation invariants,
capture-family durability, recording admission/finalization, UI action guards, localization, and
release/debug provenance. Python evidence was green: 99 tool tests, nine coverage-tool tests, 184
device-harness tests, and 120 documentation checks. Android compile/lint tasks reached green cached
outputs, but a concurrent review build removed the shared unit-test class directory while Gradle
considered compilation up to date; that workspace collision was not treated as repository evidence.
No device behavior was run or inferred.

## Finding

### VER38-01 — tests do not verify the advertised `bottomMargin` postcondition because it is inert

- **Severity:** Low
- **Confidence:** High
- **Status:** Confirmed
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:680-731` accepts
  `bottomMargin` but never reads it; `y` is computed exclusively from `topAnchor`, the minimum
  frame-height clearance, and the measured `bottomClearance`. In
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:17-35`, the test passes
  `bottomMargin = 0.10f` but expects the unrelated minimum-clearance floor. The margin-variation test
  at lines 70-88 changes `bottomMargin` from `0f` to `0.14f` and asserts only width/height, never
  `y`; therefore it passes whether the margin works or not. Runtime consumers at
  `gl/GlPipeline.kt:855-862` and `ui/CameraScreen.kt:908-912` use the separate measured
  `bottomClearance`, confirming that the named margin is legacy surface rather than hidden runtime
  input.
- **Why this is a problem:** The test suite appears to pin “independent side and bottom clearances”
  while proving no bottom-margin behavior. This creates a false verification claim and permits a
  documented parameter plus its constant to remain dead indefinitely.
- **Concrete failure scenario:** Mutating or deleting every use of the `bottomMargin` argument does
  not fail `FinderGeometryTest`; a future caller can rely on the parameter to clear bottom chrome,
  receive unchanged geometry, and still see a green geometry suite.
- **Suggested fix:** Choose one truthful contract. Prefer deleting the superseded parameter/default
  and updating the test names/comments to the actual top-anchor/minimum/measured-clearance model. If
  the parameter is retained, assert that changing only `bottomMargin` changes `y` by the documented
  amount while leaving `x`, width, and height unchanged, and implement that lower bound in
  `finderRect`.

## Final verification sweep

Verified that the latest stabilization projection normalizes requested state to advertised HAL
modes and that Gamma quick actions consume encoder capability truth across menu/Fn/DISP paths.
Checked the remaining authorities and high-risk cross-file flows for contradictory claims or an
evidence-backed failure. Apart from VER38-01, no new confirmed correctness, security, data-loss, or
maintainability defect was found.
