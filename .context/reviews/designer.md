# Native Android designer review — cycle 50

Date: 2026-08-25

Reviewed revision: `2388819d` (`origin/main`)

## Inventory and method

This is a native Jetpack Compose application, so browser automation is not an applicable UI
runtime. I inventoried and examined all 31 production files under `ui/` (screen, policy,
ViewModel/actions, controls, overlays, review, theme, focus/input owners), `MainActivity`, UI-facing
camera/storage/video state, all 93 UI tests, both string catalogs, the manifest/theme/resources,
debug snapshot host, and every checked-in phone/tablet screenshot plus its validity manifest.

The source-level pass covered Sony-style information architecture; control discoverability and
enabled/selected feedback; touch, stylus/mouse, keyboard/D-pad, TalkBack and Switch Access; modal
entry/containment/restoration; 48 dp targets; WCAG focus order/appearance, text and non-text
contrast; compact, phone, tablet, freeform, rotation, insets, 2x font and overflow behavior;
loading/empty/disabled/reconfiguring/recording/review/delete/error states; validation and retry;
deterministic dark appearance; EN/KO, shaping and RTL; and perceived-performance feedback.

## Findings

No new actionable design, accessibility, responsive-layout, localization, state-presentation, or
perceived-performance defect survived source and cross-file validation at this revision.

The two cycle-49 interaction findings are closed in production code, not only tests. Viewfinder
activation now fires once on the initial Enter/Space/DPAD-center DOWN and ignores repeat DOWN events
(`CameraScreen.kt:364-403`), with all key families exercised in
`ViewfinderAccessibilityComposeTest.kt`. Review Delete owns an exact focus requester and restores it
after Back/Cancel dismissal (`MediaReview.kt:1051-1069,1737-1782`), and
`ModalFocusComposeTest.kt:218-263` asserts the return before continued traversal.

The quiet finder hierarchy, stable physical control homes, Fn/My/settings split, capability-aware
disabled states, review/load/retry copy, restrained live regions, merged control semantics, input
blocking, two-tone focus indication, contrast tokens, overflow affordances, absolute camera
geometry under RTL, and window-following glyph policy otherwise remain coherent. The deterministic
dark theme and explicitly light system-bar icons agree regardless of system theme. No hardcoded
user prose lacking an EN/KO resource was found.

## Final missed-issue sweep and evidence boundary

The debug JVM/Robolectric/Compose suite passed. The documentation gate passed 152 checks; the full
host gate could not start because the local SDK lacks the stable Emulator `glslangValidator`.
Checked-in screenshot manifests intentionally block the stale phone captures and the unprovenanceable
tablet captures from Play submission. I did not run an emulator or device and do not claim visual
runtime, TalkBack speech, physical keyboard, camera pixels, or device performance evidence. Open
field checks A3/A4/A5/D1/E1/E2 remain manual/device validation work, not design passes or failures.

---

## Archived prior review

# Native Android designer review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27`

## Scope

This is a native Jetpack Compose app, so browser automation is not applicable. I reviewed the full
production UI inventory (`MainActivity`, all UI/control/overlay/review/theme modules, state and
resource authorities) plus all Compose/Robolectric UI tests and checked-in screenshot manifests.
The sweep covered Sony-style information hierarchy, affordances, touch/stylus/mouse, keyboard/D-pad,
TalkBack semantics, WCAG 2.2 keyboard/focus-order/focus-appearance concerns, 48 dp targets, contrast,
loading/empty/error states, deterministic dark appearance, reduced motion, EN/KO, RTL, 2x font and
compact/large-screen behavior, and perceived-performance feedback.

## Findings

### C49-DSN-01 — holding the viewfinder activation key repeatedly fires autofocus

- **Severity / confidence:** Medium / High
- **Status:** Confirmed; duplicate of `C49-CR-01`.
- **Region:** `ui/CameraScreen.kt:364-385`; test gap at
  `ui/ViewfinderAccessibilityComposeTest.kt:171-215`.
- **User impact:** Enter/Space/DPAD-center behaves unlike a button: holding it restarts the same AF
  action at repeat cadence. That is disruptive on TV remotes, keyboards, switch devices, and
  accessibility controllers where long presses are common.
- **Fix:** make activation one-shot per physical press and test repeat/cancel/fresh-press behavior.

### C49-DSN-02 — canceling review Delete does not explicitly restore focus to Delete

- **Severity / confidence:** Medium / Medium
- **Status:** Likely runtime focus-order defect; source-confirmed missing owner and test assertion.
- **Region:** `ui/review/MediaReview.kt:1053-1061,1727-1771` and
  `ui/ModalFocusComposeTest.kt:218-253`.
- **User impact:** after inspecting a destructive confirmation and canceling it, a keyboard/D-pad
  user may lose their place in review instead of returning to the Delete button. This breaks the
  spatial/operational continuity expected by WCAG focus order and by the app's new outer-modal focus
  restoration policy.
- **Fix:** add exact nested-modal origin restoration and assert it for Cancel, Back, and outside
  dismiss before continuing traversal.

## Full UI/UX sweep — no additional finding

- The quiet Sony-style finder hierarchy, stable physical control homes, Fn/My Menu/settings split,
  capability-aware disabled states, and review/loading/error copy remain coherent.
- TalkBack roles, state descriptions, live-region restraint, modal entry exclusion, 48 dp targets,
  HUD/destructive contrast, and two-tone keyboard focus outline are otherwise consistent.
- The deterministic dark theme and pinned light system-bar icons agree under light/dark system
  settings. Animations use platform-aware Compose primitives and no new looping motion was found.
- EN/KO parity and intended camera abbreviations are enforced; absolute camera geometry remains
  stable under RTL while localized text keeps shaping. Existing 2x-font and compact-wide snapshot
  coverage addresses the highest-risk reflow surfaces.
- Current phone/tablet screenshots remain intentionally blocked from Play submission; no stale asset
  was treated as present UI evidence.

---

## Archived prior review

# Native Android designer review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`

Workspace: isolated worktree `/private/tmp/find-x9-cycle39.feeBBZ`

## Scope and method

This is a native Android/Compose application, so browser automation is not applicable. I read the
committed design and behavior authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`), inventoried all 493 tracked paths, and reviewed the complete production UI
surface and its cross-file state/actions/resources/tests. The inventory included `CameraScreen`,
`CameraScreenPolicy`, `CameraViewModel`, all `ui/controls`, `ui/overlays`, `ui/review`, theme,
permission and external-navigation surfaces, EN/KO resources, manifests, debug snapshot host, phone
and tablet screenshot assets, and the UI-facing camera/storage/video policies that decide what the
operator may see or activate.

The review covered information architecture; quiet Sony Alpha/Xperia-style affordances; touch,
keyboard/D-pad, TalkBack, and Switch Access; focus containment and restoration; WCAG 2.2 target,
contrast, naming, role, state, and live-region behavior; phone, tablet, freeform, insets, rotation,
font-scale, overflow, and scroll behavior; loading, empty, disabled, reconfiguring, permission,
recording, review, deletion, retry, and terminal-error states; dark-theme/system-bar consistency;
EN/KO parity and layout-direction-sensitive placement; and perceived-performance ownership. I also
visually inspected the checked-in phone/tablet captures while treating their validity manifest as
authoritative rather than mistaking historical screenshots for current app evidence. No device was
connected and no screenshot was represented as a current target-device validation.

## Findings

No new actionable design, accessibility, responsive-layout, localization, state-presentation, or
perceived-performance defect survived source and cross-file validation at the reviewed revision.

The cycle-38 selected-disabled focal-rail defect is closed: the active but locked chip now retains
the shared dark live-frame plate and layers the quiet selection wash above it
(`app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2859,2916-2937`), with a rendered
bright/dark four-state regression matrix in
`app/src/test/kotlin/me/hletrd/telecampro/ui/controls/AffordanceEdgeComposeTest.kt:110-147`.
The finder overlay continues to share one geometry seam between GL and Compose after removal of the
misleading dead margin input. The settings rail remains a selectable tab group with one merged,
named 48 dp-plus action per category; modal surfaces contain traversal and expose an explicit close
owner; the viewfinder and review expose capability-dependent non-touch actions without duplicating
touch-only gesture nodes; disabled controls preserve both semantic and visual state; and scrolling
surfaces retain explicit overflow/fade or per-tab position behavior.

## Evidence boundaries and final missed-issues sweep

- `python3 tools/check_docs.py` passed all 120 applicable committed checks with zero failures; 24
  checks for intentionally absent private maintainer files were skipped.
- The two stale phone screenshots remain explicitly blocked by
  `docs/assets/play/screenshots/asset-validity.json` and `docs/play-console-submit.md`. This is an
  already-owned immutable-device recapture task, not a new UI finding; the assets were not modified
  or treated as release-ready.
- Open field checks A3, A4, D1, E1, and E2 remain accurately scoped to a real scene, rotatable
  large-screen front route, acoustic comparison, or real MediaProvider consent/provenance. Host
  inspection cannot close or fail those checks.
- The final sweep rechecked small permanent text, 48 dp interaction ownership, compound semantics,
  selected/disabled combinations, focus order, Back/scrim behavior, live-region urgency, timer and
  review modals, permission denial/recovery, ownerless-delete cancellation, color tokens over live
  bright/dark content, horizontal option overflow, window-following rotation, absolute finder
  anchoring under RTL, bilingual resource parity, and lifecycle gating of expensive meters/scopes.
  No additional confirmed defect remained.

## Totals

- New findings: 0
- Confirmed regressions: 0

---

# Native Android / Compose design review — cycle 51 (current)

Date/HEAD: 2026-08-25, `7eb4ee95`; isolated clone; no device/deploy/source changes.

## Complete design inventory

Reviewed all UI implementation files in `ui`, `ui/controls`, `ui/overlays`, `ui/review`, `MainActivity`, theme/resources, English/Korean strings, manifests, debug snapshot hosts, all 67 UI/controls/overlay/review Compose test files, and every committed phone/tablet/Play bitmap. Covered information architecture, Sony-style quiet-viewfinder policy, touch/keyboard/stylus/TalkBack semantics, modal focus and traversal, 48 dp interaction floors, selected/disabled paint, live regions, status/loading/error/empty/restart states, contrast tokens, font scaling, narrow/large-screen layouts, rotation, RTL ownership, dark system bars, bilingual parity, and perceived-performance transitions.

## Result

No new user-visible design regression survived the full pass. Existing automated evidence covers bilingual presentation, dropdown/selector semantics, modal focus/timers, self-timer and viewfinder accessibility, non-touch review controls, contrast, status scrolling, responsive rows, and focal overflow. The committed stale screenshots are explicitly blocked from submission by their validity manifests, not mistaken for current UI evidence.

The stale “upright” Loupe implementation comments are a design-rationale risk but duplicate C51-CV-03 in the document report; the executable/UI contract remains the raw inverted same-stream exception. Open field checks remain manual. New designer findings: **0**.
