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
