# Critic review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299`

Role: skeptical multi-perspective critique

## Coverage

Inventoried all 490 tracked paths, including 101 production/debug Kotlin sources, 225 JVM/
Robolectric/Compose/instrumented test sources, the Python/shell device and release tooling, Gradle
configuration, manifests/resources, privacy/store material, and the committed documentation and
review history. Read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` in full before
reviewing implementation. Cross-checked the latest stabilization/Gamma/capability changes against
ViewModel restore, CameraEngine generation ownership, CameraController request publication, all
three quick-control surfaces, settings, OSD, and their tests. Also swept capture-family durability,
recording teardown, route selection, zoom/focus remaps, GL ownership, localization, and immutable
build/evidence boundaries. The 99 tool tests, nine coverage-tool tests, 184 device-harness self-tests,
and 120 documentation checks passed. No device action was taken.

## Finding

### CRIT38-01 — Loupe geometry exposes a documented bottom-margin control that has no effect

- **Severity:** Low
- **Confidence:** High
- **Status:** Confirmed
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:671-691`
  documents `bottomMargin` as the short-edge-fraction inset that keeps the overview above the Fn/lens
  rail, and `finderRect` exposes it with default `FINDER_BOTTOM_MARGIN`. The parameter is explicitly
  suppressed as unused at line 685. The returned `y` at lines 722-728 depends only on `topAnchor`,
  `FINDER_MIN_BOTTOM_CLEARANCE`, and measured `bottomClearance`. Repository-wide search finds
  `FINDER_BOTTOM_MARGIN` only at its declaration (`CameraState.kt:369`) and this inert default.
- **Why this is a problem:** The API and its KDoc describe a geometry knob that cannot change
  geometry. A maintainer responding to another rail-overlap report can adjust the named constant or
  pass a route-specific margin and obtain no movement, while both GL and Compose continue rendering
  the old rectangle. The deliberate `@Suppress("UNUSED_PARAMETER")` also hides the compiler signal
  that would expose this drift.
- **Concrete scenario:** A new device needs 14% short-edge bottom clearance, so its caller changes
  `bottomMargin` from `0.10f` to `0.14f`. `finderRect(...).y` remains bit-identical; the overview can
  still overlap the focal rail even though the call and documentation claim the margin was applied.
- **Suggested fix:** Preserve the newer top-anchor/measured-clearance design and remove the obsolete
  `bottomMargin` parameter plus `FINDER_BOTTOM_MARGIN`, then rewrite the KDoc/tests around the three
  actual lower bounds. If a separately tunable fractional margin is still intended, include
  `shortEdge * bottomMargin` in the `y` lower-bound calculation and add a vertical-placement
  assertion. Do not silently restore the old bottom-anchor behavior that the device measurements
  rejected.

## Final missed-issue sweep

Rechecked model-string seams, accepted-vs-requested session truth, caps-generation ordering,
recording/native quarantine, late MediaStore publication/delete ownership, parser bounds, UI
capability singleton states, English/Korean parity, and tool fail-closed behavior. No additional
confirmed issue survived evidence checking; speculative concerns without a concrete failing path
were omitted.
