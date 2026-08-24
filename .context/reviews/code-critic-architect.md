# Cycle 42 specialist review — code reviewer, critic, architect

Reviewed revision: `70ebb875141cce802946d26373d94eeb9f8632f9` (`origin/main`)

## Guidance and coverage

I read `CLAUDE.md` and `docs/ARCHITECTURE.md` as the committed design authority, then read
`docs/FIELD_CHECKS.md` for the device/manual evidence boundary. The requested private
`docs/BACKLOG.md` is absent in this clean clone; `CLAUDE.md` explicitly says that its absence must
not block work. There is no `AGENTS.md`, `.cursorrules`, `CONTRIBUTING.md`, or other project-level
instruction file in this clone.

I inventoried all 504 tracked paths: 102 production Kotlin/Java modules, 224 JVM/Robolectric/
Compose tests, 4 instrumented tests, 15 main-resource paths, 24 host-tool paths, 14 device-harness
paths, 55 documentation/assets/plan paths, 38 review artifacts, 10 build/configuration paths, and
18 repository/legal/metadata paths. I examined the complete production module map and its
cross-file ownership descriptions, the build/manifest/dependency boundaries, current and historical
review/plan status, the full test/tool inventory, and the cycle-41 implementation surface. I then
performed repository-wide sweeps over exception swallowing, executor/Handler admission, monitor
ownership, unsafe assertions, suppressions, TODO/FIXME markers, localization/resource authority,
and source/document drift. `python3 tools/check_docs.py` passes all 126 committed checks (24 optional
private checks skipped), and `git diff --check` is clean. No device behavior was run or inferred.

The broad `CameraEngine` decomposition remains the explicitly deferred `AGG35-08` item in
`docs/plans/2026-08-24-rpf-cycle35.md:82-99`; I did not duplicate that accepted structural residual.
The open manual-only A3/A4/D1/E1/E2 checks remain correctly owned by `docs/FIELD_CHECKS.md` and are
not code findings.

## Findings

### CCA42-01 — click-only command chips keep enabled paint when disabled

- **Severity / confidence / status:** Medium / High / Confirmed user-facing affordance regression.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:755-785` replaces the
  selectable chip with a clickable `Surface`, but `enabled` affects only click admission and the
  unselected border. Both `color` and `contentColor` are derived solely from `active`, so disabled
  commands retain enabled foreground/container paint. This conflicts with the sheet's shared
  disabled-state contract at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:135-160,203-219`, which dims
  disabled labels through `DISABLED_ROW_ALPHA` and explicitly says a disabled control must not look
  tappable. The two callers make the defect observable in both branches: a locked MR Save/Update
  passes `enabled = false` at `ProSheet.kt:732-751` but leaves its label full white, while Custom WB
  passes `active = wbMode == CUSTOM` at `ProSheet.kt:1037-1057`. Its admission predicate requires
  `wbMode == AUTO` at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/ControlAvailability.kt:144-150`, so the filled-white
  `active` appearance is necessarily disabled whenever it is shown. AndroidX Material3's clickable
  `Surface` contract only makes a false-enabled surface non-clickable; unlike `FilterChipColors` or
  `ButtonColors`, it has no disabled container/content color state. The regression at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:68-115`
  validates role, click, and enabled semantics but never validates disabled paint.
- **Concrete failure scenario:** select a captured Custom WB preset. The “Capture custom WB” command
  is correctly unavailable until AUTO WB is restored, yet it renders as a full-strength white filled
  control with black text. The nearby caption says to use AUTO while the strongest command affordance
  still looks active; tapping does nothing. A locked MR write similarly retains a full-strength label
  despite being unavailable.
- **Suggested fix:** make command-chip container, content, and border colors functions of both
  `active` and `enabled` (using the existing disabled-alpha/token authority), or use a genuine button
  primitive with explicit enabled/disabled `ButtonColors` while preserving click-only semantics.
  Add a focused color resolver or image/pixel regression for enabled, disabled, active, and
  active-disabled states; keep the existing semantics assertions.

### CCA42-02 — wide-aim regression tests are disconnected from the code that submits the edge

- **Severity / confidence / status:** Low / High / Confirmed test-authority and maintainability
  defect; current runtime arithmetic is correct.
- **Evidence:** the actual gesture-start HAL target is calculated and submitted independently in
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3875-3894`
  (`exact / ZOOM_GESTURE_MARGIN`, clamp, then `setSmoothPreviewBoost(..., halZoom = wideAim)`). The
  similarly named calculation in
  `app/src/main/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlan.kt:39-53` runs only from
  `CameraEngine.setZoomRatio` at `CameraEngine.kt:3949-3963`; while a gesture is active it returns
  `submitNow = false`, so runtime discards its `halTarget` and uses only `controlsZoomRatio`. Thus
  the active branch's `gestureMargin`, range inputs, and wide target are a dead parallel model of
  the real edge path. Every wide-aim/clamp assertion in
  `app/src/test/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlanTest.kt:31-59,71-75,90-95` exercises
  that ignored value, and no test references `setZoomInteraction`, `setSmoothPreviewBoost`, or the
  edge's actual `halZoom` argument. The architecture reinforces the false ownership claim at
  `docs/ARCHITECTURE.md:71` by saying `ZoomSubmitPlan.kt` owns the wide-aim clamp. It also says a
  gesture costs exactly two swaps at `docs/ARCHITECTURE.md:718-727` while the same paragraph and
  runtime include an additional quiet-window landing; stale throttle wording remains in
  `app/src/main/kotlin/me/hletrd/telecampro/ui/ZoomGlideState.kt:41-50` and
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:374-380,2098-2101` after the
  cycle-41 throttle model was removed.
- **Concrete failure scenario:** a future edit accidentally passes the exact ratio (or `null`) as
  `halZoom` in `setZoomInteraction(true)`, or changes its clamp independently. All
  `ZoomSubmitPlanTest` wide-aim tests remain green because the value they assert is never submitted.
  On device, the first outward gesture no longer pre-buys field, so GL cannot widen and the preview
  freezes/crops until the quiet landing. Conversely, a maintainer following the “exactly two swaps”
  architecture claim can remove the required quiet landing and restore wide-framed recorded tails.
- **Suggested fix:** extract one pure gesture-edge target resolver and call it from
  `setZoomInteraction`; test that exact resolver's margin and range behavior. Reduce
  `resolveHalZoomSubmit` to the values its caller actually consumes (or remove the now-trivial plan),
  and update the module map, swap-count description, and remaining “throttle window/restamp” comments
  to the edge + optional quiet landing + end-edge model.

## Final missed-issue sweep

I rechecked every production filename against the Architecture Module Map, every cycle-41 changed
source/test/tool path against its plan claims, every live zoom-throttle reference, all command-chip
callers, production suppressions/non-null assertions, and the process-wide dispatcher/storage owner
inventory. I found no additional confirmed correctness, architecture, or maintainability issue that
was not already fixed, explicitly deferred, or correctly left for manual device evidence.

**Finding count: 2 total — 1 Medium, 1 Low; both High confidence.**
