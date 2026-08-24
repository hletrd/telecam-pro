# Code review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299` (`origin/main`)
Workspace: `/private/tmp/find-x9-cycle38.FKvYBP`

## Coverage

Inventoried all 490 tracked paths: 101 production Kotlin files, 220 JVM/Robolectric/Compose test
files, four instrumented-test files, three debug Kotlin files, 32 Python files across host tooling
and the device harness, build/version/signing configuration, Android resources/manifests, and the
committed documentation/review/plan corpus. I read the complete `CLAUDE.md`, current
`docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, and `README.md`; `docs/BACKLOG.md` is absent, as the
clean-clone policy permits. Review emphasis covered state normalization, Camera2/GL/native-owner
lifetimes, recording and still-publication terminals, MediaStore recovery/delete ownership,
capability projections, UI action admission, persistence, release wrappers, and test/tool contracts.
Historical reviews were used only as leads and every retained claim below was checked against the
current source.

## Finding

### CR38-01 — `finderRect` advertises a vertical-margin input that is guaranteed to do nothing

- **Severity:** Low
- **Confidence:** High
- **Status:** Confirmed
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:363-369` says
  `FINDER_BOTTOM_MARGIN` was replaced and is retained only as an unused default. Nevertheless,
  `CameraState.kt:671-686` still documents `bottomMargin` as the bottom inset and exposes it as a
  normal argument, while the returned y coordinate at `CameraState.kt:722-730` consults only
  `topAnchor`, `FINDER_MIN_BOTTOM_CLEARANCE`, and `bottomClearance`. The parameter requires an
  `UNUSED_PARAMETER` suppression. `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:17-35`
  passes a non-default value under a test whose description promises independent short-edge insets,
  but never checks that value's effect; lines 70-88 vary the value only to prove size is unchanged,
  so the inert position control is not exposed by the suite.
- **Why this is a problem:** The public shape and KDoc lie about the function's behavior and keep two
  mutually exclusive vertical-placement concepts alive. A maintainer adapting the overview for a
  new device can reasonably tune `bottomMargin`, get a green test/build, and ship no geometry change.
- **Concrete failure scenario:** A device's focal rail overlaps the overview. The maintainer raises
  `bottomMargin` from `0.10f` to `0.16f`, or passes `bottomMargin = 0.16f` at a caller, and verifies
  the existing geometry tests. Nothing moves because the argument is discarded; the overlap remains
  while source and tests imply that the attempted fix is active.
- **Suggested fix:** Remove `FINDER_BOTTOM_MARGIN` and the `bottomMargin` parameter (there are no
  production call sites that pass it), rewrite the KDoc and geometry-test names around the actual
  `topAnchor` plus measured/fallback `bottomClearance` policy, and add assertions that each live
  vertical input moves or bounds `y` as documented. If binary compatibility is genuinely required,
  retain a deprecated forwarding overload whose documentation explicitly says the old argument is
  ignored rather than presenting it as an effective control.

## Final missed-issue sweep

I rechecked suppressed/ignored parameters, dormant/legacy compatibility seams, capability lists
against every quick-control consumer, callback-lock boundaries, error-swallowing sites, asynchronous
owner retirement, TODO/FIXME markers, and the cycle-37 implementation diff. No additional current,
actionable code-quality or correctness finding survived evidence checking. The owner-approved
owner-null media provenance behavior and previously recorded broad `CameraEngine` decomposition debt
remain existing policy/deferred items, not new cycle-38 findings.
