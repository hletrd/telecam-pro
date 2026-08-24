# Cycle 32 document-specialist + native Android designer review

Date: 2026-08-24

Reviewed revision: `64eff08e22f856b42f70be7f2a63581c30e265a9`

Workspace: isolated clean clone `/tmp/find-x9-rpf32.SEkU6E/repo`

Mode: static/host-only Android Compose and documentation review; no deployment or device claim

## Scope and inventory

I read the complete committed clean-clone authorities first: `CLAUDE.md`,
`docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, `README.md`, `PRIVACY.md`, the bundled privacy
policy, Play submission/data-safety material, and `device-tests/README.md`. The optional private
maintainer documents named by `CLAUDE.md` are absent from this clean clone, as permitted. I also
checked the current cycle-31 aggregate/plan so already-fixed findings were not re-filed.

The repository inventory contains 440 tracked paths. From the document/designer angle I examined
all production/debug Compose surfaces and their UI-facing state/policy/action seams (98 production
Kotlin files), both EN/KO resource trees, manifest/theme/locale generation, every UI/Compose/
Robolectric test among the 202 host-test files, all four Android tests, the native device-harness
contract, the 45 committed documentation/assets files, and the documentation checker. Browser
automation is not applicable to this native Android application; no current emulator or physical-
device screenshot was treated as available evidence. The checked-in Play phone screenshots are
correctly marked historical/not submission-ready by their manifest and submission sheet.

`python3 tools/check_docs.py` passed all 94 public checks (21 private-context checks skipped by
design). EN/KO resources are structurally paired: the 18 default-only entries are exactly the
declared `translatable="false"` app/product/camera-abbreviation exceptions. The fixed-dark theme,
system-bar policy, 48 dp targets, compact/large-font adaptations, RTL ownership, modal Back/focus
boundaries, loading/empty/error/delete states, and recent cycle-31 review/ToggleRow/AF/focal-rail
fixes were all rechecked before the findings below.

## Findings

### DD32-01 — the enabled horizon level has no accessibility representation

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed accessibility defect (non-text information has no programmatic
  equivalent)
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:275-320`;
  caller `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:798-809`.
- **Problem:** `LevelOverlay` communicates a live deviation solely through Canvas geometry and a
  yellow-at-level colour change. The Canvas has no semantics at all. The Assist toggle that enables
  it is accessible, but once enabled a TalkBack or Switch Access user cannot inspect whether the
  camera is level or which direction it is tilted. This is distinct from making a live region fire
  every sensor tick; a quiet, focus-inspectable state is sufficient.
- **Concrete failure scenario:** A screen-reader user enables **Level**, returns to the finder, and
  traverses the viewfinder. The accessibility tree exposes the viewfinder and controls but no
  `Level`, `level`, or signed-deviation state, so the feature they enabled is unusable even though
  the same operator can access focus and exposure controls.
- **Suggested fix:** Add EN/KO level-state resources and one stable semantic projection (preferably
  on the existing viewfinder node) that reports a coarsened state such as `Level`, `Tilt left N°`,
  or `Tilt right N°`. Do not make it a live region and change-gate/coarsen the value so sensor-rate
  telemetry does not chatter. Add Compose tests for disabled, level, left, right, and locale cases.

### DD32-02 — zoomed review panning is unbounded and can move the entire photo off-screen

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed interaction/correctness defect
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:814-816,
  929-960,978-981,1227-1242`.
- **Problem:** Review scale is clamped to 1x..12x, but every zoomed drag performs
  `offset += pan` with no bound derived from the fitted bitmap, viewport, or scale. Long-press and
  double-tap also install an unchecked point-centering offset. `graphicsLayer` then applies that
  arbitrary translation directly. Nothing prevents all image pixels from leaving the viewport.
- **Concrete failure scenario:** At 4x or 8x, a user repeatedly drags toward an edge while checking
  focus. The image can be translated completely beyond the black review viewport. Further panning
  gives no positional cue, so the screen appears blank until the user discovers the separate reset-
  zoom action or closes review; pinch/drag alone does not guarantee recovery.
- **Suggested fix:** Single-source fitted-content geometry and clamp translation after every pan,
  scale, long-press, double-tap, bitmap/container-size, and orientation change. Where scaled content
  is smaller than the viewport on an axis, keep it centred; where larger, allow only the range that
  keeps an edge in view. Extract a pure bounds helper and cover portrait/landscape images,
  letterboxing, 1x/4x/12x, corner taps, and excessive pans.

### DD32-03 — review treats two rapid taps anywhere in the image as a double-tap

- **Severity / confidence:** Low / High
- **Classification:** Confirmed gesture-recognition defect
- **Exact region:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:904-915,
  966-985`.
- **Problem:** The hand-written recognizer remembers only the first tap's uptime. A second clean tap
  is accepted when it lands before `doubleTapTimeoutMillis`; the first position is never retained or
  compared, and the minimum inter-tap interval is not applied. The adjacent comment says the logic
  matches `detectTapGestures`, but it implements only one part of that contract.
- **Concrete failure scenario:** A user taps one detail, then quickly taps a different, distant
  detail while inspecting a still. The second tap unexpectedly jumps review to 4x/8x centred on the
  new point, even though the two contacts were not one spatial double-tap gesture. Synthetic or
  accessibility-generated taps with an unrealistically short interval can trigger the same branch.
- **Suggested fix:** Reuse the platform/Compose double-tap recognizer within the unified gesture
  owner, or extract an equivalent predicate that checks both the supported temporal window and
  spatial proximity while retaining the current pinch/pan ownership. Add tests for near/valid,
  far/invalid, too-fast, timed-out, intervening drag, and intervening pinch sequences.

### DD32-04 — tap-AF creates two overlapping full-viewfinder accessibility identities

- **Severity / confidence:** Low / High
- **Classification:** Confirmed accessibility-tree duplication
- **Exact regions:** viewfinder node
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:667-684`; reticle/live-region callers
  `CameraScreen.kt:791-796`; reticle semantics and terminal announcer
  `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:339-362,420-438`.
- **Problem:** The `AndroidView` already exports the full preview as **Camera viewfinder** with the
  focus/reset custom actions. While a tap point exists, `FocusReticle` receives `fillMaxSize()` and
  adds another semantic node whose bounds are the complete preview, named `Autofocus searching`,
  `Focus locked`, or `Autofocus failed`. Terminal outcomes are separately emitted again by the
  1 dp `FocusResultLiveRegion`. The visible reticle is only a 64 dp bracket, but its accessibility
  identity covers the whole frame and duplicates the terminal copy.
- **Concrete failure scenario:** During the reticle's two-second visual lifetime, TalkBack traversal
  encounters a second preview-sized focus stop over the existing viewfinder; on success/failure the
  same phrase is also announced by the live region. When the reticle disappears, that large node
  vanishes from under traversal, making focus order unstable.
- **Suggested fix:** Keep one durable viewfinder identity. Put the current AF state in that node's
  `stateDescription`, retain the small terminal live region only for change announcement, and make
  the Canvas decorative. If a separate reticle node is retained, bound it to the actual reticle and
  remove duplicate terminal ownership. Add a composed-tree test asserting one viewfinder focus stop
  across idle/scanning/focused/failed transitions.

### DD32-05 — the architecture reintroduces the fixed PMA110 lens set as the generic Lens-tab contract

- **Severity / confidence:** Low / High
- **Classification:** Confirmed current-authority/code mismatch
- **Exact regions:** `docs/ARCHITECTURE.md:1164-1178` (especially line 1174); production
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:1141-1153`; governing rule
  `CLAUDE.md:568-579`.
- **Problem:** The current architecture says the Lens tab contains `0.6x/1x/3x/10x selection`
  without scoping that list to PMA110. Production deliberately filters `LensChoice.entries` through
  `state.lensInventory.available`, and `CLAUDE.md` records the former unconditional four-choice UI
  as a real multi-device bug. This one-line layout inventory therefore teaches the exact behavior
  the as-built implementation removed.
- **Concrete failure scenario:** A QA author or UI maintainer uses the current design authority to
  assert four Lens-tab choices on a single-camera API-33 phone/tablet, or "restores" a missing 0.6x/
  10x chip. The resulting test/change conflicts with capability enumeration and again offers
  unreachable framings.
- **Suggested fix:** Change the entry to `device-enumerated lens presets (0.6x/1x/3x/10x on
  PMA110)` and add a documentation contract rejecting an unqualified fixed-list Lens-tab claim.

### DD32-06 — the clean-clone architecture presents an absent optional UX policy as a normal link

- **Severity / confidence:** Low / High
- **Classification:** Confirmed clean-clone documentation defect
- **Exact regions:** `docs/ARCHITECTURE.md:28-31`; clean-clone precedence/fallback rule
  `CLAUDE.md:3-10`; absent path `docs/UX_POLICY.md`.
- **Problem:** The overview ends its normative UI paragraph with `See UX_POLICY.md`, but that file
  is private/optional and absent from this permitted clean clone. `CLAUDE.md` explicitly says its
  absence must not block work and provides the committed fallback policy, while the current
  architecture neither marks the link optional nor points back to that fallback. The rendered
  public/current authority therefore contains a dead link at the exact point a new contributor is
  told where to learn the UX rules.
- **Concrete failure scenario:** A clean-clone contributor follows the architecture link, gets a
  missing file, and either assumes required policy was omitted or blocks work looking for private
  context even though the repository claims to be self-contained.
- **Suggested fix:** Mark it explicitly as optional maintainer context (`when present`) and state
  that the preceding paragraph plus `CLAUDE.md` are the clean-clone authority. Extend
  `tools/check_docs.py` so allowed-private links in current public docs must carry that qualifier.

## Final missed-issue and file sweep

I re-swept every production UI composable/helper, resource identity, UI-focused unit/Compose/
Robolectric test, Android UI probe, current operator/public document, privacy/Play declaration,
and the cycle-31 changed surface. No further current-HEAD issue survived source-level evidence
checking. In particular, I did not infer visual pixels, physical contrast, native focus/rotation,
camera output, OIS, audio directionality, TalkBack speech, or subjective gesture feel from host
code. `docs/FIELD_CHECKS.md` truthfully keeps A3, D1, and E1 open, and the paused-video overlay's
source comment correctly leaves its optional visual-consistency look as device/manual validation
rather than claiming a defect.

**Finding count: 6 total — 2 Medium, 4 Low.**
