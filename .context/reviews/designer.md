# Native Android designer review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope and method

This is native Android Compose, so browser automation is not applicable. I inventoried all 490
tracked paths and reviewed production/debug Compose, semantics, resources, manifests/theme, UI
state/actions, responsive geometry, relevant unit/native-Compose tests, and current screenshot
validity metadata. Coverage included IA, touch/non-touch/TalkBack/Switch Access, target size and
contrast, phone/large-screen/freeform layouts, font scale/insets, EN/KO/RTL, the dark-only theme,
loading/empty/error/delete/recording states, and perceived performance. No device was connected.

## Finding

### DES38-01 — the selected lens/zoom chip can disappear on a bright viewfinder while the rail is locked

- **Severity / confidence / status:** Low / High / Confirmed compositing defect; visual validation
  on the target device remains appropriate after the fix.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:659-708`
  produces `selected=true, enabled=false` for the current lens/zoom during camera reconfiguration or
  recording. `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2855` resolves that
  state to a 12%-white translucent container, 12%-white border, and 38%-white label;
  `CameraScreen.kt:2912-2924` composites them directly over the live preview.
- **Problem:** every other idle/disabled branch retains `HudPlate`, the dark contrast floor designed
  for live imagery. Selected-disabled uniquely drops it. Over sky, snow, a white wall, or an
  overexposed frame, all three white layers composite toward the same white background, so the
  current focal choice loses both its text and boundary. This is common rather than theoretical:
  the active chip is disabled for the entire recording and during each optics reconfiguration.
- **Test evidence:** `AffordanceEdgeComposeTest.kt:65-107` renders enabled and unselected-disabled
  only, over one dark `CameraColors.Pill` background. Selected-disabled is reduced to alpha-token
  assertions, so the test blesses the disappearing recipe without observing it.
- **Suggested fix:** keep a dark `HudPlate` foundation for selected-disabled and layer the quiet
  selection wash above it (or use another composited color with a measured bright/dark floor).
  Render the full four-state matrix over near-white and near-black frame fixtures, preserving the
  existing disabled semantics and 48 dp target.

## Confirmed strengths and final sweep

The nine-tab IA, quiet preview-first chrome, mode/Fn/MR organization, typed loading/error/delete
surfaces, modal focus, review keyboard support, primary 48 dp targets, orientation policy,
large-screen geometry, EN/KO pairing, RTL-absolute finder placement, and bounded heavy review work
remain coherent. Apart from DES38-01, no additional source-proven accessibility, responsive,
localization, state, or perceived-performance defect survived.

## Totals

- New findings: 1
- Severity: 1 Low
- Confidence: 1 High
