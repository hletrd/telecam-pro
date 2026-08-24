# Critic review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27`

## Method

I performed the same 534-path inventory as the code review and challenged the repository's strongest
cycle-48 completion claims against the production source and the exact tests cited as evidence. The
skeptical sweep covered state/ownership consistency, asynchronous boundaries, error and rollback
paths, native-resource claims, keyboard/touch equivalence, documentation contradictions, and tests
whose names imply more than their assertions establish.

## Findings

### C49-CT-01 — viewfinder keyboard activation is level-triggered instead of press-triggered

- **Severity / confidence:** Medium / High
- **Status:** Confirmed; duplicate of `C49-CR-01`.
- **Evidence/failure:** `CameraScreen.kt:364-385` consumes every matching `KeyDown`, including repeat
  DOWNs, while `ViewfinderAccessibilityComposeTest.kt:171-215` covers only discrete `pressKey`
  pairs. Holding Enter/Space/DPAD-center can continuously re-trigger center AF.
- **Fix:** ignore repeats or implement an exact down/up owner; mutation-test repeated DOWN and
  cancellation before the next fresh press.

### C49-CT-02 — delete-dialog cancellation has neither an explicit focus-return owner nor the claimed assertion

- **Severity / confidence:** Medium / Medium
- **Classification:** Likely keyboard-accessibility defect plus confirmed evidence overclaim. The
  exact post-dialog landing can vary with Compose/platform behavior and needs a real keyboard pass.
- **Evidence:** `ui/review/MediaReview.kt:1053-1061,1727-1771` has a requester only for the review
  Close control. The Delete control has no requester, and dismissing its `AlertDialog` merely flips
  `confirmDelete=false`; no edge restores focus to Delete. The cycle-48 plan marks delete-dialog
  cancel covered at `docs/plans/2026-08-25-rpf-cycle48.md:77-82`, but
  `ModalFocusComposeTest.kt:218-253` asserts only that the dialog disappears, then invokes Back
  without asserting any underlying node is focused.
- **Failure scenario:** a keyboard/D-pad user opens Delete, cancels, and returns to a review whose
  focus is absent or lands on Close/another node. The next traversal/action no longer resumes at the
  control that opened the dialog. The green test cannot detect this because Back closes review even
  when nothing in it owns focus.
- **Concrete fix:** attach a `FocusRequester` to Delete, remember it as the nested-modal origin, and
  request it after dialog disposal on every dismiss route (Cancel, Back, outside click). Assert Delete
  is focused after each route and only then test continued traversal. Append a dated correction to
  the completed plan rather than rewriting its historical claim.

## Final skeptical sweep

No additional claimed race, data-loss path, shader-interface defect, or release-permission bypass
survived source verification. Current open field checks remain explicit validation limits rather
than defects.

---

## Archived prior review

# Critic review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`

Role: skeptical multi-perspective critique

## Coverage

Inventoried all 493 tracked paths: 101 production/debug Kotlin sources, 224 JVM/Compose/
instrumented test sources, Python and shell build/device tooling, Gradle configuration,
manifests/resources, privacy/store assets, and committed documentation/review history. Read the
complete clean-clone authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) before tracing implementation.

The cycle-38 change surface was checked line by line: stabilization label reconciliation from
`CameraViewModel.reconcileZoomToCaps` through `CameraEngine.setVideoStabMode`, accepted-route
capability fallback in `CaptureCapabilities`, live request and session publication in
`CameraController`, deterministic shared-pool ownership tests, focal-rail rendering, and the shared
GL/Compose finder geometry. The wider critique also swept route/session generations, exposure and
zoom remaps, capture-family durability and deletion, recording admission/native quarantine,
MediaProvider recovery, GL ownership, permissions/navigation, localization, and immutable build
evidence.

Host-side independent evidence was green: 120 documentation checks, 99 tooling tests, nine
coverage-tool tests, 184 device-harness self-tests, and `git diff --check`. No device action was
taken and no open field check was promoted to implementation evidence.

## Findings

No new confirmed findings.

The previous phantom finder input is gone: `finderRect` now exposes only `sideMargin`, `topAnchor`,
and measured `bottomClearance`, and `FinderGeometryTest` independently asserts their actual axes and
lower-bound behavior. The stabilization optimization also preserves accepted truth: before caps it
stores intent only; after caps it skips work only when the before/after labels resolve to the same
Camera2 value, while a real OFF/ON/PREVIEW transition retains request rebuild plus session reopen.
The selected-disabled focal chip now keeps the common `HudPlate` floor and the rendered bright/dark
matrix exercises all selected/enabled combinations.

## Final missed-issue sweep

Rechecked model-string boundaries, capability availability and normalization, Camera2/GL terminal
ownership, capture and recording exactly-once publication, late provider results, parser bounds,
UI action guards, EN/KO parity, privacy claims, and release/debug provenance. Potential concerns
without a reproducible invariant violation or concrete failure path were omitted.
