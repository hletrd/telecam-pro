# Native Android designer review (RPL cycle 3)

Date: 2026-10-02
Reviewed revision: `e3a2bdd4` (`main`)
Method: static only. No device, emulator or TalkBack run. Compose UI, so browser tooling does not apply.
Finding prefix: DES3-.

## Scope and method

- Delta since the cycle-2 designer pass (`e5729ffd`): 42 commits. UI/res surface touched:
  `MediaReview.kt` (DES2-1 fix: dedicated `a11y_delete_*` resources), `ProControls.kt`/`ProSheet.kt`
  (AGG2-35: `noStillOutputCaption(tenBitVideoWanted)`), `MainActivity.onRecallMemorySlot`
  (AGG2-26), the three retained-take status strings (DES2-2 fix), and `CameraViewModel.kt`
  (exposure ownership helpers, `rejectIfRecording` on aspect, `recallMemorySlot`).
- DES2-1 fix verified: `MediaReview.kt:1166-1169,1885` now uses `deleteCopy.action`; all four new
  keys exist in EN and KO as imperative noun phrases ("촬영 결과 삭제", "RAW 파일 삭제", ...). No
  `removeSuffix` left in `ui/`.
- Mechanical checks re-run on this revision:
  - **EN/KO parity.** 500 EN / 482 KO resources. Every translatable key has a KO entry, KO carries no
    `translatable="false"` duplicates, no extra KO keys, positional placeholders match on every
    `<string>` (the script's "mismatches" are only the KO single-`other` plurals, which is correct for
    Korean).
  - **Glyph coverage.** fontTools over every non-ASCII, non-Hangul character in UI/engine Kotlin
    literals (comments stripped) and both `strings.xml`: `© ° ± · × — ’ … ↑ → ↓ ∞`. All present in
    `inter_regular`, `inter_medium`, `inter_semibold`. No new glyphs since cycle 2.
  - **Hardcoded prose.** No English prose literal reaches Compose in `ui/` (only a KDoc and a
    `checkNotNull` message matched). Literal OSD tags (`T3s`, `TL5s`, `AEB±2`, `4:3`) remain
    camera-standard abbreviations visually; see DES3-3 for their spoken form.
  - **Touch targets.** All new/changed interactive nodes keep the 48 dp floor; dialogs use
    `MinTouchTarget48` or `heightIn(min = 48.dp)`.

## Findings

### DES3-1: The new retained-take copy still over-promises: "the next time the app starts" means a new PROCESS, not the next time the user opens the app

- **Severity:** Medium. **Confidence:** High. **Status:** Confirmed (source-traced).
- **Region:** `res/values/strings.xml:223,225,233` and `res/values-ko/strings.xml:214,216,224`
  (`status_dng_save_delayed`, `status_output_saved_pending`, `status_video_save_delayed`, changed in
  the DES2-2 fix to "… It will be saved the next time the app starts." / "앱을 다음에 시작할 때
  저장됩니다."). Emitted at `camera/CameraEngine.kt:7365` (video), `:8564` (DNG),
  `capture/StillCapturePipeline.kt:634`.
- **Why:** the only publisher of a retained row is `engine.cleanupOrphans`, called once from the
  `CameraViewModel` init (`ui/CameraViewModel.kt:1236`). Its sweep selects only rows with
  `DATE_ADDED < processStartSecs` (`storage/MediaStoreWriter.kt:1291-1302`,
  `orphanSweepSelection` at `:3126-3140`), deliberately, so it cannot race the current process's own
  in-flight writes. Consequently a row retained in THIS process is never adopted by ANY sweep in this
  process, including a sweep from a freshly constructed ViewModel. What users call "starting the
  app" (tap the launcher icon, return from Recents, back out and reopen) almost always reuses the
  cached process on Android, so none of those publish the take. Only a cold process start (kill,
  reboot, LMK eviction) does.
- **Failure scenario:** a provider hiccup at the end of a 4K take shows "Video retained. It will be
  saved the next time the app starts." The operator presses Home, re-opens the app from the launcher
  a minute later, and checks Gallery: no clip. They reopen again: still no clip. The copy has now
  been "falsified" twice, so they conclude the take is lost, exactly the outcome DES2-2 was filed to
  prevent.
- **Fix (pick one, both host-testable):**
  1. Copy that names the real trigger, e.g. EN "Video kept privately. It is saved after the app is
     fully closed and reopened." / KO "동영상을 임시 보관했습니다. 앱을 완전히 종료한 뒤 다시 열면
     저장됩니다." Pin with `KoreanLocalizationRobolectricTest` plus an EN resource assertion.
  2. Make the existing copy true: on `onStart` (or `MainActivity.onStart`), run one bounded
     same-process adoption pass for rows this process itself retained (exact URIs are already known
     to the process: the retained dispositions carry them), separate from the prior-process sweep so
     the race guard stays intact. That is an engine/storage decision; route through architect.
- Relates to DES3-2 (expiry) and DES3-4 (marker-unavailable variants).

### DES3-2: A retained (IS_PENDING) take silently expires after ~7 days; nothing in the copy or code accounts for it

- **Severity:** Medium. **Confidence:** Medium. **Status:** Needs-device (platform behaviour
  documented; app-side absence confirmed).
- **Region:** retained-row paths behind the statuses in DES3-1; `grep -rn DATE_EXPIRES
  app/src/main/kotlin` returns nothing.
- **Why:** MediaProvider stamps `DATE_EXPIRES` automatically when `IS_PENDING` is set, with a default
  pending lifetime of 7 days, and deletes expired items during device idle maintenance
  (`MediaStore.MediaColumns.DATE_EXPIRES` / `IS_PENDING` docs). The app neither extends the expiry on
  retained rows nor mentions a deadline. Combined with DES3-1 (adoption needs a new process), a phone
  that keeps the camera process cached, or an operator who simply does not reopen the app for a week
  (a travel shoot, then editing on a computer), loses the take to the platform with no error at all.
  The status copy promises "will be saved" unconditionally.
- **Failure scenario:** a RAW+HEIF shoot on day 1 retains two DNGs after a provider failure. The
  user does not open TeleCam again until day 9. Idle maintenance has already deleted both rows;
  launch recovery finds nothing; no status is ever shown.
- **Fix:** storage owners decide; the copy depends on it. Options: (a) on retention, update the row
  with a long `DATE_EXPIRES` (or re-touch it on each launch's recovery pass) so the promise holds;
  (b) bound the copy ("…within 7 days"); (c) DES3-1 option 2 (same-process adoption) shrinks the
  window to minutes. Host test: a pure helper that computes the expiry written on retention.

### DES3-3: The OSD status row has no spoken form; TalkBack reads raw finder codes that the sibling pill's own comment calls a defect

- **Severity:** Low-Medium. **Confidence:** High (source); Medium (exact TalkBack verbalisation).
  **Status:** Confirmed.
- **Region:** `ui/overlays/Overlays.kt:882-1095` (`StatusBar`): a scrolling `Row` of bare `Text`
  leaves with no `semantics`, e.g. the video spec `"$res ${fps}p $codec ${mbps}M"` (`:931-944`),
  `"4:3"` (`:961`), `"AEB±2"`, `"TL${n}s"` (`:1006-1009`), `"T${n}s"` (`:1036`). Call site
  `ui/CameraScreen.kt:1149`.
- **Why:** the neighbouring `StatusInfoPill` (`CameraScreen.kt:2313-2326`) clears its leaves and
  speaks one localized description precisely because, in its own words, "unmerged, TalkBack read the
  raw glyphs — '45m' (which is a distance aloud) and a bare '1234' that names nothing". The status row
  has the same defect at larger scale: `"100M"` is read as a quantity or metres, `T3s`/`TL5s` as
  letter-digit-letter strings, and each tag is a separate swipe stop inside a horizontal scroller, so
  a TalkBack user cannot get the shooting state (FRONT, MUTE, HR, OIS OFF, metering, locks) as one
  readout. Visual tags can stay terse (abbreviation policy is fine); only the accessibility tree is
  wrong.
- **Failure scenario:** KO TalkBack user in video, timelapse armed: swipes through "4K 29.97p HEVC
  100M", "S-Log3", "MUTE", "TL5s" — no Korean, no units, and no indication which are warnings.
- **Fix:** mirror the pill: build a `localizedStatusBarDescription(state)` (pure, host-testable,
  EN+KO resources such as "Self-timer 3 seconds", "Interval 5 seconds", "100 megabits per second",
  "Audio muted") and apply `clearAndSetSemantics { contentDescription = … }` on the Row. Keep the
  scroll behaviour for sighted users. Add a Robolectric test that the row exposes one node.

### DES3-4: Video and still "marker unavailable" retentions still diverge, and the still variant speaks internal jargon

- **Severity:** Low. **Confidence:** High. **Status:** Confirmed. (Second half of DES2-2, not
  addressed by the cycle-2 fix; re-reported with the current state.)
- **Region:** `video/RecordingStorageDispatcher.kt:122-125` folds `RETAINED_MARKER_UNAVAILABLE`,
  `RETAINED_PUBLICATION_UNAVAILABLE`, `RETAINED_VALIDATION_UNAVAILABLE` into one `RETAINED_PENDING`
  → `VIDEO_SAVE_DELAYED` (WARNING, 2.5 s, now the "will be saved" promise). Stills split the same fact
  into `OUTPUT_SAVED_PENDING_RECOVERY` (ERROR, 6 s; `StillCapturePipeline.kt:634-635`,
  `CameraEngine.kt:8566`) with EN copy "%1$s save retained. Recovery marker failed."
  (`values/strings.xml:226`).
- **Why:** (1) for video the fail-closed REGISTERED case (adoption only if the structural probe
  passes) now gets the strongest promise in the app, while the equivalent still case is an error.
  (2) "Recovery marker" is an implementation term; users cannot act on it. KO already says it more
  plainly ("복구 표식 저장에 실패했습니다"), but still names the mechanism.
- **Fix:** add `VIDEO_SAVE_PENDING_RECOVERY` mapped from `RETAINED_MARKER_UNAVAILABLE` (keep the
  other two on the delayed copy), and reword the shared marker-failed string around the outcome, e.g.
  EN "%1$s kept privately. It may not be recoverable." / KO "%1$s 파일을 임시 보관했지만 복구되지 않을
  수 있습니다." Pin the dispatcher mapping with the existing dispatcher unit test.

### DES3-5: Dropdown menus mark the selected option by colour alone

- **Severity:** Low. **Confidence:** High. **Status:** Confirmed.
- **Region:** `ui/controls/ProControls.kt:870-880` (`DropdownRow` item text:
  `color = if (isSelected) CameraColors.Accent else CameraColors.TextPrimary`).
- **Why:** semantics are correct (RadioButton role, `selected`, stateDescription), but visually the
  only cue is #8AB4F8 vs white text in a 320 dp-high scrolling list. For colour-vision-deficient users,
  and in bright outdoor light (the app's main use: a 300 mm telephoto outside), light-blue vs white is a
  weak distinction (WCAG 1.4.1 Use of Color). Segmented chips elsewhere add a fill; the menu does not.
- **Fix:** pass `leadingIcon`/`trailingIcon` with a check glyph (Canvas-drawn like the other HUD
  glyphs, so no font dependency) for the selected item, or a 2 dp accent bar. Compose UI test: the
  selected item has an extra child node.

### Carried items re-validated (still open, unchanged code)

- **DES2-3 (statuses drawn under open review):** plate still composed at `CameraScreen.kt:1307-1316`,
  before the opaque review overlay. DES3-1/2 raise the stakes: the retained-take message, the one with
  instructions in it, is exactly the asynchronous DNG tail most likely to land while the operator is
  reviewing the shot.
- **DES2-5 / AGG-63 (auto-dismiss ignores the accessibility timeout):** still no
  `getRecommendedTimeoutMillis` caller. New evidence: the DES2-2 fix roughly doubled the retained
  copy (EN 61 chars / 11 words: "DNG retained. It will be saved the next time the app starts.") while
  it stays WARNING → 2_500 ms (`camera/CameraStatus.kt:186-191`). That is under typical reading time
  for 11 words before accounting for the glance-up delay of a shooter looking through the finder.
  Minimum fix even without the a11y API: give the retained trio the 6 s duration, or classify by
  copy length.
- **DES2-4 / AGG-62** (tab rail word breaks at large font scale), **DES2-6 / AGG-64** (plate has no
  horizontal margin, `CameraScreen.kt:1587-1609`), **DES2-7** (KO `value_fast` 빠르게,
  `value_small` 작게, `value_large` 크게 at `values-ko/strings.xml:346,389,390`), **DES2-8**
  (read-only `LabelValueRow` two TalkBack stops), **DES2-9** (DISP renames its node,
  `CameraScreen.kt:2256`): all unchanged.

## Checked with no finding

- AGG2-35 caption: `noStillOutputCaption` keys on `tenBitSessionWanted`, so SDR video on the
  preview-only rung now shows "Still capture unavailable" rather than the 10-bit trade copy; residual
  documented in KDoc. EN/KO both present.
- KO particles on the new strings: `%1$s 파일을 보존했습니다` with `%1$s` ∈ {HEIF, JPEG, DNG}; no
  particle attaches to the argument directly, so no 을/를 mismatch.
- `CriticalCameraStatusPlate` has no `maxLines`, so the longer retained copy wraps rather than
  truncating the actionable second sentence (rotated: measured on the swapped axis via
  `rotateLayout`).
- Dialogs (`MicrophonePermissionRationale`, `ReviewDeleteConfirmationDialog`,
  `PrivacyPolicyFallbackDialog`): resource-backed copy, 48 dp buttons, destructive action in
  `CameraColors.Alert`.
- `PermissionGate`: compact/large-font branch scrolls (`fontScale >= 1.5f`), no clipped CTA.
- Quiet-viewfinder policy: no tutorial banners, coach marks or marketing copy added in the delta.

## Files examined

- UI: `ui/CameraScreen.kt` (StatusBar call site, status plate, StatusInfoPill, DISP),
  `ui/overlays/Overlays.kt` (StatusBar), `ui/controls/ProControls.kt` (DropdownRow, LabelValueRow,
  PhotoFormatToggles, noStillOutputCaption), `ui/controls/ProSheet.kt` (ShootingTab delta),
  `ui/controls/ManualDials.kt` (Fn/close targets), `ui/review/MediaReview.kt` (delete copy, dialog,
  RAW placeholder), `ui/ExternalNavigationUi.kt`, `ui/LocalizedStatus.kt`, `ui/CameraViewModel.kt`
  (delta, `cleanupOrphans` call), `MainActivity.kt` (delta, rationale dialog, PermissionGate).
- Engine/storage (for copy truth): `camera/CameraStatus.kt`, `camera/CameraEngine.kt:7365,7555-7600,
  8560-8570`, `camera/LaunchMediaRecoveryCoordinator.kt`, `storage/MediaStoreWriter.kt:1285-1302,
  3126-3141`, `capture/StillCapturePipeline.kt:630-636`, `video/RecordingStorageDispatcher.kt`.
- Resources: `res/values/strings.xml`, `res/values-ko/strings.xml`, `res/font/*.ttf`.
- Prior context: `.context/reviews/archive-rpl-cycle1-2026-10-02/designer.md`,
  `archive-rpl-cycle2-2026-10-02/{designer,_aggregate}.md`, `docs/plans/2026-10-02-rpl-cycle2.md`.

Source for DES3-2 platform behaviour:
[MediaStore.MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)
(`DATE_EXPIRES`, `IS_PENDING`).
