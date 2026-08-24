# Native Android designer review — cycle 36

Date: 2026-08-24

Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)

Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and method

This is a native Jetpack Compose app, so browser automation is not applicable. I inventoried all 486
tracked paths and examined every production/debug Compose surface, UI-facing state/action/policy
seam, resources, manifests/theme, UI/Compose/Robolectric/instrumented tests, deterministic snapshot
host, device-harness UI contracts, and the checked-in phone/tablet assets (which are historical
visual references, not current device proof). The pass covered information architecture,
affordances, focus/keyboard/D-pad/Switch Access, TalkBack semantics, 48 dp targets, WCAG 2.2 via
WCAG2ICT, phone/large-screen/freeform behavior, loading/empty/error/delete states, validation,
fixed-dark/system-bar behavior, EN/KO, RTL ownership, and perceived-performance boundaries.

## Finding

### DES36-01 — enabled custom-control outlines render at only 1.78:1 contrast

- **Severity / confidence / status:** Medium / High / Confirmed WCAG2ICT non-text-contrast gap.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt:62,117-126`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:203-219,340-362,369-400`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ManualDials.kt:431-458`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2832-2891`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:1715-1734`.
- **Problem:** The shared active `AffordanceEdge` is 18% white. Composited over the sheet's
  `#1C1C1E` Pill it becomes about `#454546`, a measured **1.78:1** contrast ratio. The token comment
  itself records roughly 1.8:1, but active unselected FilterChip boundaries, a close pill, focal-rail
  circles, and review action buttons depend on that edge. WCAG 2.2's non-web application guidance
  requires 3:1 for authored visual information needed to identify active controls or state
  ([W3C WCAG2ICT 1.4.11](https://www.w3.org/TR/wcag2ict-22/#non-text-contrast)); inactive controls
  are the exception, not these enabled choices.
- **Concrete failure scenario:** On the Shoot/Exposure/Image tabs, a low-vision photographer can
  distinguish the selected white-filled option but the other enabled choices lose their component
  boundary and read as free-floating labels. On the finder/review, the same token weakens the only
  circular edge around compact actions over a dark plate.
- **Suggested fix:** Keep the Sony-style 1 dp quiet geometry, but raise enabled edge contrast to
  at least 3:1 on each real surface (approximately 35% white on Pill), or introduce a separate
  high-contrast fill/shape cue. Preserve a quieter disabled token. Add palette and rendered tests
  for enabled/unselected, selected, disabled, focus, bright-frame, and dark-frame states rather than
  pinning `0.18f` as intrinsically correct.

## Confirmed strengths and missed-issue sweep

- The nine-tab IA, Fn/My Menu/MR model, quiet OSD, and explicit loading/empty/error/delete surfaces
  remain coherent with the Sony/Xperia reference.
- Keyboard/D-pad/manual-slider and review-pan paths, modal finder exclusion, initial close focus,
  stable pane titles, localized semantics, and 48 dp hit floors are comprehensively covered.
- Phone portrait lock and rotatable sw600dp+ layouts share one physical-bottom control layout;
  reading elements counter-rotate without moving controls. Large-font and narrow-window reflow,
  independent tab scroll state, insets, and RTL reading-vs-physical geometry are explicit and tested.
- The app is deliberately dark-only; both system bar icon sets are pinned for that surface. EN/KO
  resources are paired, while camera abbreviations and trademarks are declared exceptions.
- High-frequency audio/scopes/level semantics are coarse and non-live, zoom/control updates are
  coalesced, and review work is bounded/latest-wins. No additional source-proven UI defect survived
  the final sweep. Hardware pixels, actual TalkBack speech, gesture feel, and field behavior remain
  unclaimed without device evidence.

## Totals

- New findings: 1
- Severity: 1 Medium
- Confidence: 1 High
