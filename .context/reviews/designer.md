# Native Android designer review (RPL cycle 2)

Date: 2026-10-02
Reviewed revision: `e5729ffd` (`main`; working tree holds only the cycle-1 review-archive renames)
Method: static only. No device, emulator or TalkBack run. The UI is Jetpack Compose, so browser
tooling does not apply.

## Scope and method

- Delta since the cycle-1 designer pass (`ba5b16e7`): 51 commits. Only four touch `ui/` layout code,
  and all four are parameter reorders (`settingsModifier`, `galleryModifier`, `fnButtonModifier`,
  `timelapseRunning`) or a comment edit. The one behavioural UI change is the tap-focus bottom
  clearance (`CameraScreen.kt:893`). `MainActivity.onRecallMemorySlot` is new, but it handles the
  permission preference and draws nothing. So this pass re-validated cycle 1, then swept surfaces
  that earlier passes covered less: review, status copy truth, and KO wording.
- Mechanical checks (re-run on this revision):
  - **EN/KO parity.** 496 EN and 478 KO resources. Every translatable key has a KO entry, KO has no
    extra or duplicated `translatable="false"` keys, and positional placeholders match on every
    shared key. Twelve KO values are byte-identical to EN, and each is a unit, an abbreviation or a
    pure format string (`USB`, `50 Hz`, `A%1$s`, `%1$s, %2$s`, ...). That is correct.
  - **Glyph coverage.** fontTools over every non-ASCII, non-Hangul character in UI Kotlin literals
    (comments stripped) and both `strings.xml` files found `© ° ± · × — ’ … ↑ → ↓ ∞`. All are present
    in all three bundled Inter faces.
  - **Hardcoded prose.** The English-only helpers in `ControlLabels.kt` (`wbModeLabel`,
    `flashModeLabel`, ...) are reached only as `context == null` fallbacks in `fnSlotValue`. Both
    production call sites pass a Context (`CameraScreen.kt:2562`, `ProSheet.kt:682`). No English prose
    reaches Compose.
  - **Touch targets.** Every `clickable`/`selectable`/`toggleable` has a 48 dp floor (`size(48.dp)`,
    `sizeIn(min…)`, or a 56 dp or weighted tile), except three sites. Those three are the 76 dp
    shutter, the 52 dp gallery thumb, and the full-width MR row. All are compliant.
  - **Font-scale overrides.** The only dp-pinned text is the 8 dp chrome badge
    (`CameraScreen.kt:1929`). It is deliberate, and its state is exposed through the button's
    accessibility value.

## Findings

### DES2-1: The review Delete button's TalkBack name is a question in Korean

- **Region:** `ui/review/MediaReview.kt:1882`
  (`contentDescription = deleteTitle.removeSuffix("?")`). `deleteTitle` is the dialog title from
  `mediaDeleteConfirmationCopy` (`MediaReview.kt:1165-1166`).
- **Why:** the code builds the accessible name by cutting the question mark off a localized
  question. That works for English ("Delete capture?" becomes "Delete capture"). The Korean titles
  are interrogative verb endings, not noun phrases plus a mark:
  `review_delete_capture_title` = "촬영 결과를 삭제할까요?" (`values-ko/strings.xml:420`). Stripping
  the mark leaves "촬영 결과를 삭제할까요", which still asks "shall I delete the capture?". The same
  happens with all four variants: RAW capture, file, RAW file, and capture. This is the most
  dangerous control on the review screen, and it is the only accessible name in the app built from
  string surgery (`grep removeSuffix` over `ui/` finds only this site). The EN-only test
  `ModalFocusComposeTest.kt:408` copies the same `removeSuffix`, so it cannot catch the KO wording.
- **Scenario:** a Korean TalkBack user swipes to the top-end review control. They hear a question,
  "촬영 결과를 삭제할까요, 버튼", instead of an action name, before any confirmation dialog exists.
  The confirmation dialog then opens and asks the same question again.
- **Fix:** add a `@StringRes action` field to `MediaDeleteConfirmationCopy`. Back it with four new
  resources, for example `a11y_delete_capture` "Delete capture" / "촬영 결과 삭제",
  `a11y_delete_raw_capture` "Delete RAW capture" / "RAW 촬영 결과 삭제", `a11y_delete_file`
  "Delete file" / "파일 삭제" and `a11y_delete_raw_file` "Delete RAW file" / "RAW 파일 삭제". Use
  that field at `:1882`. Update `ModalFocusComposeTest` to resolve the new key, and add a KO
  Robolectric assertion that the label does not end in "까요".
- **Confidence:** High. Source-confirmed and deterministic. Severity is Medium: it is an
  accessibility-copy defect on a destructive control.

### DES2-2: "Save delayed. Will retry." promises a retry that happens only on the next app launch

- **Region:** the copy is `values/strings.xml:223,225,233` and `values-ko/strings.xml:214,216,224`
  (`status_dng_save_delayed`, `status_output_saved_pending`, `status_video_save_delayed`, KO "다시
  시도합니다"). It is emitted at `camera/CameraEngine.kt:5561-5562` (DNG retained), at
  `capture/StillCapturePipeline.kt:606-609` (`retainedSaveStatus`, marker-durable branch) and at
  `CameraEngine.kt:7252-7253` (video `RETAINED_PENDING`).
- **Why:** nothing in the running process retries these takes. The retained row stays private
  (`IS_PENDING=1`). The only thing that publishes it is launch recovery: `cleanupOrphans` is called
  exactly once, from `CameraViewModel.kt:1214` during ViewModel construction, and
  `ProcessLaunchMediaRecovery` is a single-flight owner for "prior-process pending media"
  (`CameraEngine.kt:7443-7455`). The code says so itself: "launch recovery publishes it later"
  (`CameraEngine.kt:5553-5555`). The copy says "Will retry", which reads as an automatic, imminent
  retry. On a phone where the camera process survives in the background for hours, that retry never
  arrives in-session.
  - A second, related inconsistency: stills distinguish "save retained. Recovery marker failed."
    (`OUTPUT_SAVED_PENDING_RECOVERY`). Video does not. `RecordingStorageDispatcher.kt:121-125` folds
    `RETAINED_MARKER_UNAVAILABLE`, `RETAINED_PUBLICATION_UNAVAILABLE` and
    `RETAINED_VALIDATION_UNAVAILABLE` into the same "Video save delayed. Will retry." That is the
    exact fail-closed REGISTERED case where adoption depends on a structural probe. For stills, the
    app treats that difference as worth saying.
- **Scenario:** a provider hiccup at the end of a 10-minute 4K take shows "Video save delayed. Will
  retry." for 2.5 s. The operator opens Google Photos and finds no clip, waits, checks again, and
  still finds no clip. The clip appears only after the app process is killed and relaunched. In the
  meantime the operator may conclude it was lost and re-shoot, or clear app data, which is worse.
- **Fix:** say when the retry happens, not just that one exists. For example EN "Video kept. It will
  be saved when the app reopens." / KO "동영상을 보존했습니다. 앱을 다시 열면 저장됩니다.", and the
  same pattern for `%1$s` and DNG. Then either add a video marker-failed variant to match
  `OUTPUT_SAVED_PENDING_RECOVERY`, or state in the dispatcher why video does not need one. A
  stronger alternative, if the engine owners agree: run one bounded in-process retry of
  `ProcessLaunchMediaRecovery`, for example on the next `onStart`, so the existing copy becomes true.
  That is an engine decision, not a copy fix, so route it through architect.
- **Confidence:** Medium-High on the mismatch, which is source-traced end to end. Severity is
  Medium: it is a state-truthfulness issue on the app's data-safety channel.

### DES2-3: Statuses published while review is open are drawn underneath it

- **Region:** the status plate is composed at `ui/CameraScreen.kt:1307-1316`, then the opaque review
  is composed later at `:1567-1583` (`MediaReviewOverlay` paints `CameraColors.Background` full
  screen, `MediaReview.kt:1239-1243`).
- **Why:** composition order is z-order. Any status that lands while review is open renders under a
  black full-screen layer and auto-clears on its timer (6 s error, 2.5 s warning) before the
  operator closes review. The live region may still speak to TalkBack users, but sighted users
  never see it. Reachable producers include:
  - the asynchronous DNG tail of a shot taken just before tapping the gallery (`DNG save
    failed/delayed` reaches the camera status path through `CameraEngine.kt:5561`),
  - a late `COULD_NOT_DELETE_FILE` from `deleteLateCaptureOutput` (`CameraViewModel.kt:3962-3985`),
  - `MICROPHONE_ALLOWED_AUDIO_ON` from `refreshPermissionState` when the operator returns from
    Android Settings with review open (`MainActivity.kt:976-985`).
- **Scenario:** the operator shoots RAW+HEIF and immediately taps the thumbnail to check focus. The
  DNG publish fails. "DNG save failed" shows for 6 s behind the review, and the operator leaves
  review believing the RAW exists.
- **Fix:** hoist `CriticalCameraStatusPlate` below the `MediaReviewOverlay` block (keep it last in
  the root Box), or pass `state.status` into `MediaReviewOverlay` and render the same plate there.
  Either way, keep the plate non-focusable so it does not break the review modal focus boundary.
  An alternative is to pause the auto-clear timer while `openReview != null`, so the message is
  still on screen when review closes.
- **Confidence:** Medium. Layering is source-certain. How often a status arrives during review is
  timing-dependent.

### DES2-4 (re-validates AGG-62 / DSN-R1-01): The tab rail still breaks words at large font scale

- **Region:** `ui/controls/ProSheet.kt:452` (`width(76.dp)`) and `:511-522` (label
  `Modifier.width(68.dp)`, no `maxLines` or `fontScale` fallback, and the comment still says
  "nothing wraps" at 1.0x).
- **Status:** unchanged since cycle 1. The scenario and fix in the archived `DSN-R1-01` still apply.
  Note for the fix: "Setup" in KO is "설정", which already fits. The tight KO case is "보조 기능",
  which wraps at the space and is fine. "Exposure" in EN is the label that breaks mid-word.
- **Confidence:** Medium. Not rendered.

### DES2-5 (re-validates AGG-63 / DSN-R1-02): Status auto-dismiss still ignores the accessibility timeout

- **Region:** `camera/CameraStatus.kt` duration table (`6_000L / 1_500L / 2_500L`, shown in the
  `duration` block around `:186-191`) and `ui/CameraViewModel.kt:1734-1744` (`postDelayed(runnable,
  durationMs)`). No caller of `getRecommendedTimeoutMillis` or `calculateRecommendedTimeoutMillis`
  exists anywhere in the source tree.
- **Status:** unchanged. DES2-2 raises the stakes, because the corrected retained-take copy is
  longer and is exactly the message a slower reader needs to finish.
- **Confidence:** Medium.

### DES2-6 (re-validates AGG-64 / DSN-R1-03): The critical status plate still has no horizontal margin

- **Region:** `ui/CameraScreen.kt:1587-1609`. The plate draws `rotateLayout → background → padding`
  with no outer inset or `widthIn`. The call site is `:1310-1315`.
- **Status:** unchanged. If DES2-3 is fixed by moving the plate, apply the inset in the same edit.
- **Confidence:** Medium on geometry, Low on severity.

### DES2-7: Korean picker values mix parts of speech inside one control

- **Region:** `values-ko/strings.xml:346` (`value_fast` "빠르게", an adverb) shown beside
  `value_high_quality` "고화질" and `value_off` "꺼짐" (nouns) in the Sharpness/NR picker.
  `values-ko/strings.xml:389-390` (`value_small` "작게", `value_large` "크게", adverbs) are shown
  beside `value_medium` "중간" (noun) in the AF Spot Size picker (`ProSheet.kt:1166`).
- **Why:** each segmented control reads as one set. "작게 / 중간 / 크게" and "꺼짐 / 빠르게 / 고화질"
  each mix two grammatical forms. Korean camera menus use noun forms: 소 / 중 / 대 or 작음 / 중간 /
  큼, and 꺼짐 / 고속 / 고화질. `value_medium` is shared with Bitrate and Peaking Level
  ("낮음 / 중간 / 높음"), which are already all nouns, so only the outliers need to move.
- **Scenario:** KO operator, Focus tab, AF Spot Size: the chips read "작게 · 중간 · 크게".
- **Fix:** `value_small` "작음", `value_large` "큼" (or "소/중/대" if width demands it, which would
  also mean changing `value_medium` and its other users), and `value_fast` "고속". Pin the change
  with `KoreanLocalizationRobolectricTest`.
- **Confidence:** High that the forms are mixed. Low severity (polish).

### DES2-8: Read-only settings rows give TalkBack two separate stops

- **Region:** `ui/controls/ProControls.kt:677-747` (`LabelValueRow`). When `onClick == null`, the
  root gets only `.semantics { if (!enabled) disabled() }`, with no `mergeDescendants`. The clickable
  branch merges implicitly through `clickable`.
- **Why:** pure readout rows ("Recording / Settings locked", "Route / …", "Encoder / …", the
  comment's own list at `:731-733`) expose the label and the value as two unrelated nodes. A TalkBack
  user hears "Recording", swipes, then hears "Settings locked", and the value is separated from what
  it describes. Every interactive sibling (ToggleRow, DropdownRow) merges into one node with a
  `stateDescription`.
- **Fix:** in the `onClick == null` branch, use `semantics(mergeDescendants = true) {}` or reuse
  `sliderSettingSemantics(label, value)` with `clearAndSetSemantics`, so the row reads "Recording,
  Settings locked".
- **Confidence:** Medium. Compose merge behaviour is well defined, but this was not run under
  TalkBack.

### DES2-9: The DISP button renames its TalkBack node on every toggle

- **Region:** `ui/CameraScreen.kt:2252-2258` (`contentDescription` switches between
  `a11y_show_shooting_info` and `a11y_hide_shooting_info`).
- **Why:** the Flash button's comment (`CameraScreen.kt:1942-1944`) states the app rule: "Name
  constant, value in the state", because renaming the node is what TalkBack tracks focus by.
  GridButton and FlipCameraButton follow it. DISP does not. After a double-tap, TalkBack re-announces
  a node with a different name, and some TalkBack versions drop or move accessibility focus.
- **Fix:** use a constant name (for example "Shooting info", which would need a new string key in EN
  and KO) plus `stateDescription` = On/Off, matching the sibling chrome buttons.
- **Confidence:** Medium on inconsistency, Low on severity. Show/Hide action naming is also a
  recognised Android pattern, so this is a consistency fix, not a WCAG failure.

## Checked with no finding

- **Status copy argument grammar (KO).** Every `%1$s` followed by a particle resolves to an argument
  whose spoken form ends in a way that the particle matches: lens labels "N×" read as "…배" plus 를,
  MR1-3 plus 에, and the `HEIF`/`JPEG`/`DNG` kinds with no particle. The trademark lines ("S-Log은",
  "Hasselblad은", "LogC는") also use the correct particles.
- **Fn overlay.** Localized labels, compact aliases resolved from typed state (not English
  matching), disabled tiles expose `disabled()` and refuse `onClick`, and the icon is decorative.
- **Microphone revocation truth.** A permission revoked in Settings while `recordAudio = true` still
  re-prompts at REC (`microphonePermissionRequired`), so MUTE absence does not lie about a silent
  take.
- **Review gestures and alternatives.** Zoom 4x/8x/reset and directional pan are exposed as custom
  actions. Video playback has a labelled button and live state. Close receives initial focus, and
  Delete gets focus back after the dialog is dismissed.
- **Dialogs.** All dialogs have 48 dp buttons. Delete uses `Alert` red on solid `Pill`.

## Evidence boundary

Static source review only. I made no claims about rendered pixels, TalkBack speech order or timing.
DES2-1 and DES2-7 are deterministic from resources. DES2-2 is traced from emit site to the single
recovery entry point. DES2-3 depends on a status arriving during an open review. DES2-4 and DES2-6
geometry is estimated from type metrics, as in cycle 1.
