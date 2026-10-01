# Native Android designer review (RPL cycle 1)

Date: 2026-09-30
Reviewed revision: `ba5b16e7` (`main`, working tree has only review-archive renames)
Method: static only. No device, emulator or TalkBack run. Compose UI is native, so browser tooling
does not apply.

## Scope and method

- Inventory: every file under `app/src/main/kotlin/me/hletrd/telecampro/ui/**` (31 files, about
  21.3k lines), `MainActivity.kt` dialogs and the permission gate, and all of `app/src/main/res/**`.
- Delta since the last designer pass (cycle 50, `2388819d`, 2026-08-25): seven commits touch UI
  files. Only `f67023d5` (Speed/Angle unit switch) changes user-visible behaviour. The rest are
  diagnostics and ownership changes.
- Mechanical checks:
  - **EN/KO parity.** 496 EN and 478 KO resources. Every translatable key has a KO entry, KO has no
    extra keys, and no `translatable="false"` key is duplicated in KO. Format placeholders match on
    every shared key.
  - **Glyph coverage.** I extracted every non-ASCII, non-Hangul character from Kotlin string literals
    and `strings.xml`: `© ° ± · × — ’ … ↑ → ↓ ∞`. fontTools confirmed all of them are in all three
    bundled Inter faces (regular, medium, semibold), including `↑ ↓ ’`, which are outside the list
    CLAUDE.md names.
  - **Hardcoded prose.** No English prose literal reaches Compose. Status messages go through the
    `CameraStatusMessage` enum mapped to resources (`LocalizedStatus.kt`). The remaining literals
    (`T3s`, `TL5s`, `AEB±2`, `4:3`, `mm`) are camera-standard abbreviations, which fits the committed
    abbreviation policy.
  - **Icon semantics.** All three `contentDescription = null` sites are decorative children of a
    parent node that is already named (`CameraScreen.kt:2705`, `MediaReview.kt:976`,
    `ProControls.kt:400`). That is correct.
  - **Touch targets.** Custom buttons use the outer-48 dp or inner-visual pattern (`MiniTextButton`,
    `CloseButton`, the Fn close control, `MinTouchTarget48` dialog buttons).
- Areas swept by reading the code: the OSD status row, the status plate lifecycle, Fn overlay
  anchoring and held-landscape behaviour, ProSheet side and bottom layouts, the tab rail, dialogs,
  review delete, the Speed/Angle conversion readout, and font-scale handling.

## Findings

### DSN-R1-01: Settings tab-rail labels break mid-word at large font scales

- **Region:** `ui/controls/ProSheet.kt:512-523` (`TabRailItem` Text with `Modifier.width(68.dp)`, no
  `maxLines`, no scale-aware fallback). The rail itself is fixed at `width(76.dp)` (`ProSheet.kt:452`).
- **Why:** the code comment sizes the box at 1.0x only ("Exposure measures ~46 dp at 11 sp inside
  the fixed 68 dp box, so nothing wraps"). `labelSmall` is 11 sp and scales with the system font
  setting. At 1.5x (16.5 sp) "Exposure" is about 69 dp. At 2.0x, the top of the Android 13+ range,
  it is about 92 dp. Nonlinear scaling barely damps text this small. When one word is wider than the
  line, Compose's line breaker splits it by character, so the rail shows "Exposu / re". "Setup",
  "Assist" and "Shoot" also overflow at 2x. Korean is less affected because "보조 기능" breaks at the
  space.
- **Scenario:** a user with Settings > Display > Font size at maximum opens Menu. This is the sheet's
  primary navigation and it reads as broken words. Other ProSheet surfaces already switch layout by
  `fontScale`: `labelValueUsesStackedLayout` and `fnSlotOrderUsesCompactLayout`. The tab rail is the
  only fixed-width text slot in the sheet with no such fallback, and no responsive test covers it.
  The `*ResponsiveComposeTest` suite covers rows, dropdowns, toggles and formats, but not the tab
  rail.
- **Fix:** pick one of these:
  1. Keep `softWrap = true` but make the rail width grow with `fontScale`, for example
     `76.dp * fontScale.coerceIn(1f, 1.6f)`, and add `maxLines = 2` with ellipsis.
  2. Pin the label's line breaking to word boundaries and scale down with `autoSize`
     (`TextAutoSize.StepBased`, available in current Compose) with a floor near 11 sp.

  Add a 2x EN and KO Compose test asserting no label line ends mid-word, or at least that the text
  layout's `lineCount` is at most 2 and has no hyphenless split.
- **Confidence:** Medium. The width arithmetic comes from the comment's own 46 dp figure. I have not
  seen it rendered.
- **Status:** new, open.

### DSN-R1-02: Status plate auto-dismiss ignores the Android accessibility timeout setting

- **Region:** `camera/CameraStatus.kt:186-191` (fixed durations: error 6000 ms, success 1500 ms,
  other 2500 ms). `ui/CameraViewModel.kt:1690-1708` posts the clear on `mainHandler` with that raw
  duration. The plate renders at `ui/CameraScreen.kt:1302-1314` and `:1586-1609`. Nothing in the
  codebase calls `AccessibilityManager.getRecommendedTimeoutMillis` or Compose's
  `LocalAccessibilityManager.calculateRecommendedTimeoutMillis` (a grep of `ui/` and `MainActivity`
  finds nothing).
- **Why:** this plate is the only channel for capture, save, delete and permission errors. Examples
  are "Some files could not be deleted. Retry in Gallery." and "%s save retained. Recovery marker
  failed." Android exposes a user setting, Accessibility > Time to take action, for users who need
  longer to read transient UI. Material Snackbar and Toast honour it. This plate does not. TalkBack
  users still hear the assertive or polite live-region announcement, so the gap affects low-vision
  and cognitive-load users who read rather than listen. The Korean error strings run to about 45
  syllables over two lines, and success messages vanish after 1.5 s. This also relates to WCAG 2.2.1
  (Timing Adjustable).
- **Scenario:** a user with a 1-minute accessibility timeout deletes a capture from review, and the
  partial-delete error appears and disappears in 6 s before they have finished reading the
  two-line Korean text.
- **Fix:** keep the ViewModel's sequencing, but resolve the delay through the accessibility manager.
  Either:
  - pass `CameraStatus.durationMs` through `calculateRecommendedTimeoutMillis(duration,
    containsIcons = false, containsText = true, containsControls = false)` before `postDelayed`
    (inject an `(Long) -> Long` from the Activity or Application), or
  - move the clear into a keyed `LaunchedEffect(status)` in `CameraScreen` that uses
    `LocalAccessibilityManager`.

  PROGRESS statuses stay timer-less as they are now. Add a unit test that injects a larger
  recommended timeout.
- **Confidence:** Medium. The absence is source-confirmed. The user impact depends on the setting.
- **Status:** new, open.

### DSN-R1-03: The critical status plate has no horizontal margin, so long messages run edge to edge

- **Region:** `ui/CameraScreen.kt:1586-1609` (`CriticalCameraStatusPlate`: background plus 12/6
  inner padding, with no outer `padding`, `widthIn` or max width). The call site at
  `CameraScreen.kt:1309-1314` passes only `align(Center)`.
- **Why:** the Text is measured against the full window width. KO error strings of about 40-45
  syllables at `bodyMedium` 14 sp are about 560-630 dp and wrap on the PMA110's 411 dp window, so
  the dark plate spans the full width with its rounded 8 dp corners meeting the panel edge. At 2x
  font size every error is multi-line and full-bleed. On an sw600dp landscape tablet the opposite
  happens: a single line up to about 1000 dp wide stretches across the viewfinder centre. Every
  sibling pill (the OSD row and HUD chips) keeps an inset from the window edge. This one does not.
- **Scenario:** Korean locale, "카메라를 사용할 수 없습니다. 불러온 광학 설정은 적용되지 않았습니다."
  renders as a full-width black band with its text touching the screen edge, next to the
  curved-glass edge of the Find X9 Ultra.
- **Fix:** add an outer `padding(horizontal = 24.dp)` or `widthIn(max = 360.dp)` before `background`,
  applied inside `rotateLayout` so the rotated measurement axis still works. Also set
  `textAlign = TextAlign.Center` for multi-line messages. Extend `CriticalStatusRotationTest` with a
  long KO string at 2x font, asserting the plate bounds sit inside the window inset.
- **Confidence:** Medium on geometry, Low on severity. This is cosmetic, but it is the app's most
  important error surface.
- **Status:** new, open.

## Checked with no finding

- **`f67023d5` Speed to Angle conversion.** The dial readout (`ManualDials.kt:970`) shows both the
  angle and the equivalent speed, so any exposure change caused by clamping to 1°-360° is visible,
  not silent. The Fn value (`FnQuickActions.kt:60`) and the sheet readout agree because both use
  `%.0f°`.
- **OSD row.** The focal label cache is keyed on the localized TELE suffix. Tags are resource-backed
  or approved abbreviations. The row scrolls horizontally, with a trailing-edge fade hint and a
  priority reset.
- **Fn overlay.** Physical layout is absolute under RTL, and each Text still gets bidi shaping. The
  2x KO held-landscape case is tested. Disabled tiles publish `disabled()` and refuse `onClick`.
- **ProSheet.** It has a pane title, a focus boundary, a Close control that receives initial focus,
  and a scrim excluded from traversal. The side-panel corner shape resolves correctly under RTL.
- **Dialogs.** Microphone rationale, privacy fallback and review delete all use 48 dp minimum
  buttons, and all copy is from resources.
- **Status lifecycle.** PROGRESS messages carry no timer and are cleared by an owned Ready event,
  matching the CLAUDE.md rule.

## Evidence boundary

This was a static source review only. I made no claims about rendered pixels, TalkBack speech,
physical keyboard behaviour or the tablet window. DSN-R1-01 and DSN-R1-03 geometry is estimated
from type metrics (Inter at 11 and 14 sp) and should be confirmed with a Robolectric or Compose
screenshot at `fontScale = 2f` in EN and KO before fixing.
