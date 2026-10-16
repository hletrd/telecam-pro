# Native Android designer review (RPL cycle 4)

Date: 2026-10-02
Reviewed revision: `14767b0a` (`main`)
Method: static only. No device, emulator or TalkBack run. Compose UI, so browser tooling does not apply.
Finding prefix: DES4-.

## Scope and method

- Delta since the cycle-3 designer pass (`e3a2bdd4`): 43 commits. UI/res surface touched:
  `CameraStatus.kt` (`VIDEO_KEPT_UNVERIFIED`, `RETAINED_TAKE_MESSAGES` → 6 s),
  `RecordingStorageDispatcher.kt` (`RETAINED_UNVALIDATED`), the retained-take copy in EN/KO,
  `ProControls.kt`/`ProSheet.kt` (`noStillOutputCaption(hlgSessionAccepted)`), `Overlays.kt` and
  `CameraScreen.kt` (`effectivePhotoFormats` in the OSD and the shots-remaining pill),
  `LocalizedStatus.kt`, `ZoomMath.kt`, `MainActivity.kt` (MR audio provenance).
- I also swept the parts of `ui/` earlier designer passes did not reach: the viewfinder
  ruler accessibility contract, status-slot arbitration, still-admission presentation, and the
  Output row during reconfiguration.
- Mechanical checks re-run on this revision:
  - **EN/KO parity.** 501 EN / 483 KO resources. Every translatable key has a KO entry, there are
    no extra KO keys, and positional placeholders match on every `<string>`. EN==KO identities are
    only abbreviations and format joins (`USB`, `OIS+EIS`, `50 Hz`, `A%1$s`, `TL`, `%1$dmm`,
    `RAW`, `DNG`, joins).
  - **Glyph coverage.** fontTools over every non-ASCII, non-Hangul character in Kotlin literals
    (comments stripped) and both `strings.xml`: `© ° ± · × — ’ … ↑ → ↓ ∞`. All present in
    `inter_regular`, `inter_medium`, `inter_semibold`.
  - **Hardcoded prose.** No English prose literal reaches Compose (the only hits are KDoc, comments,
    and `checkNotNull`/`error` messages).
  - **KO particles in the new copy.** `%1$s 파일을 임시 보관했습니다` and `S-Log은` / `Hasselblad은`
    are correct for their arguments' final sounds.
- Compose behaviour cited in DES4-1 was checked against the shipped library rather than recalled:
  `androidx.compose.ui:ui-android:1.12.1` `AndroidComposeViewAccessibilityDelegateCompat`
  (javap, bytecode offsets 903-972): for `ACTION_SCROLL_FORWARD/BACKWARD` on a node with
  `ProgressBarRangeInfo` + `SetProgress`, `increment = steps > 0 ? range/(steps+1) : range/20`,
  then `setProgress(current + increment)`.

## Findings

### DES4-1: TalkBack cannot step the snapped viewfinder rulers; the full-stop ISO ruler does not move at all

- **Severity:** Medium (accessibility blocker for manual exposure). **Confidence:** High.
  **Status:** Confirmed (source plus library bytecode). An on-device TalkBack run would confirm the
  exact gesture.
- **Region:** `ui/controls/ManualDials.kt:1389` (`RulerSlider`:
  `.progressSemantics(value = fraction, valueRange = 0f..1f)`, no `steps`) and its `setProgress`
  at `:1394-1398`. Snapped callers: ISO `:1173-1183` (`totalUnits = n - 1`, mapping
  `stops[(f * (n - 1)).roundToInt()]`), shutter `:999-1010`, EV `:1221-1234`.
- **Why:** with `steps = 0`, TalkBack's swipe up/down (and volume keys in adjust mode) asks Compose
  for `setProgress(current + 0.05)`. Each caller then rounds that fraction back onto its own detent
  grid. Whenever one detent is wider than 5 % (`totalUnits < 10`), `idx + 0.05·(n-1)` rounds back
  to `idx`. The value republishes unchanged and the next swipe starts from the same place.
  Examples:
  - ISO at `ExposureStep.FULL`. A 50–6400 range gives 8 stops, so 7 units and +0.35 unit per
    swipe: stuck in both directions.
  - Shutter at FULL in video, where the upper bound is 1/fps: roughly 1/8000…1/30 s, about 8
    stops. Stuck.
  - Where the ruler does move, it skips detents it cannot address. With 1/3-stop shutter over
    about 45 units, each swipe moves 2.25 units, so most third-stops are unreachable.

  Keyboard arrows are fine: `sliderKeyTargetFraction` steps one unit. Only the accessibility
  action path is broken. The settings-sheet `CameraSlider` (`ProControls.kt:524`) has the same
  5 % granularity. Its domains (interval 1–30 s, JPEG quality, gain) still move, only coarsely.
- **Failure scenario:** a TalkBack user in M mode sets the step to 1 stop (Exposure tab), opens the
  ISO ruler and swipes up. TalkBack re-announces "ISO, ISO 400" every time and the value never
  changes. The only escape is to change the global step, which the user cannot discover from this
  control.
- **Fix:** pass `steps = (totalUnits - 1).coerceAtLeast(0)` to `progressSemantics` when `snap` is
  true, so one swipe is one detent. For the continuous rulers (focus, WB, zoom, angle), either pass
  a domain step or add explicit `customActions`. Example: "finer/coarser" for focus near ∞, where
  5 % is about 5 of the 100 relative units. Host test: a Robolectric
  `performSemanticsAction(ScrollForward)` on an ISO ruler built with 8 stops must advance the index
  by exactly one.

### DES4-2: The status plate is last-writer-wins, so a 6 s retained-take instruction or an error can be replaced within milliseconds by a lower-priority line

- **Severity:** Medium. **Confidence:** High (mechanism). **Status:** Confirmed (source-traced).
- **Region:** `ui/CameraViewModel.kt:1755-1786` (`publishStatus` → `copy(status = status)`;
  `armStatusTimer` cancels the previous timer unconditionally); severities and durations at
  `camera/CameraStatus.kt:111-205`.
- **Why:** cycle 3 extended `RETAINED_TAKE_MESSAGES` to 6 s because that copy is the one status
  that tells the operator what to do to get a file back (AGG3-5 / DES3-1). But there is a single
  slot with no severity or priority arbitration. Any later status replaces it immediately: INFO
  `FINISHING_PREVIOUS_PHOTO` (2.5 s), SUCCESS `MEMORY_SLOT_LOADED`/`DELETED` (1.5 s), the
  Ready-publication `STILL_CAPTURE_UNAVAILABLE`/`PROCESSED_STILL_UNAVAILABLE_DNG_ONLY`, any
  refusal warning, or PROGRESS `CAMERA_RECONFIGURING`/`STARTING_CAMERA`. A PROGRESS status is then
  wiped by `clearProgressStatus` on Ready, so the original line never returns. Errors are exposed
  the same way. `ASSERTIVE` only changes how TalkBack interrupts; it does not protect the plate.
- **Failure scenarios:**
  1. AEB or burst with DNG: frame 1's DNG write fails ("DNG save failed", ERROR, assertive), and
     ~300 ms later frame 2's DNG is retained. "DNG kept privately…" replaces the error, so the
     operator never learns a frame was lost.
  2. A 4K take ends with "Video kept privately. It is saved after the app is fully closed and
     opened again." The operator immediately taps MR1 to set up the next shot. "MR1 loaded" (1.5 s)
     replaces the instruction, and the plate is then empty.
- **Fix:** make `publishStatus` priority-aware. Rank PROGRESS < SUCCESS < INFO < WARNING <
  retained-take < ERROR. While a higher-ranked, unexpired EVENT is on the plate, drop or defer a
  lower-ranked EVENT, and keep a PROGRESS status behind it until it expires. A newer status of
  equal or higher rank still wins. Pure reducer, host-testable: given an unexpired
  `VIDEO_SAVE_DELAYED`, publishing `MEMORY_SLOT_LOADED` leaves the message unchanged.

### DES4-3: After a late-output discard that the provider cannot resolve, the photo shutter can stay dead for the rest of the process with no explanation

- **Severity:** Medium (when triggered). **Confidence:** Medium-High (source). **Status:** Likely.
  The trigger is a provider failure, so it needs device or fault-injection confirmation. Please
  cross-check with code-reviewer, because the root cause is a publication protocol.
- **Region:** `ui/CameraViewModel.kt:4059-4072` (`deleteLateCaptureOutput`: on
  `PendingOutputDiscardResult.UNRESOLVED` it writes `stillCaptureAdmissionAvailable = false`
  straight into `_state` and shows `COULD_NOT_DELETE_FILE`);
  `camera/DngPreCaptureAllocation.kt:33-38` (`StillAdmissionPublication.publish` de-duplicates
  against its own `delivered`); `camera/CameraEngine.kt:1823-1834` (`delivered` is reset only when
  the callback is replaced).
- **Why:** the ViewModel overrides the visible admission without going through the engine's
  publication owner. The engine's `delivered` is still `true`, so every later `publish()` that
  computes `true` returns early. The ViewModel's `false` is never corrected. `stillCaptureReady`
  and `primaryShutterEnabled`/`primaryShutterHealthy` (`camera/CameraState.kt:1797-1813`) then
  hold the photo shutter and the in-REC snapshot dot disabled at 0.35 alpha. This survives
  `pause`/`resume`, because those do not replace the callback. Only a new ViewModel (process
  restart) re-seeds it. The only user-facing explanation is a 6 s "Could not delete file" toast
  about a different action (a delete), which DES4-2 can also hide.
- **Failure scenario:** the operator deletes a RAW+HEIF capture from review. The late DNG sibling
  arrives, and its discard hits a MediaProvider hiccup. "Could not delete file" flashes, and from
  then on the shutter is grey in front of a live preview. TalkBack says "Take photo, Unavailable,
  disabled". Backgrounding and returning does not help.
- **Fix:** do not write the admission bit from the ViewModel. Route the unresolved row through
  the same process owner the engine already subscribes to (reserve or transfer into the
  rejected-output owner, so `rejectedOutputAdmissionAvailable()` itself reports the closure). If
  fail-closed is truly intended, publish it through `StillAdmissionPublication` so a later reopen
  can reach the UI, and give it a persistent explanation, such as a Setup/Storage row or a
  `STILL_CAPTURE_UNAVAILABLE` status on shutter tap. A silent dim is not enough. Host test:
  `UNRESOLVED`, then the engine publishes `true` again, and `stillCaptureReady` returns.

### DES4-4: Every DNG shot greys the shutter as if the camera were down, and a tap during that window is silently swallowed

- **Severity:** Low-Medium. **Confidence:** High (source); Medium (on-screen duration).
  **Status:** Confirmed (source); duration Needs-manual-validation.
- **Region:** `camera/CameraEngine.kt:4834-4848`. The process-wide single DNG lease is acquired
  per DNG shot and published immediately. `camera/CameraEngine.kt:5354-5359`:
  `stillOutputAdmissionAvailable()` ANDs that lease into the still admission.
  `camera/CameraState.kt:1797-1813`. `ui/CameraScreen.kt:3239,3284,3303-3307`: alpha 0.35 plus
  `a11y_state_unavailable`.
- **Why:** the lease spans provider allocation, the Camera2 still and the DNG write. On TELE at a
  4 s exposure that is several seconds. For that whole window the shutter uses the exact paint the
  code reserves for "camera down (opening, reconfiguring, or recovery exhausted)" (`:3277-3283`),
  and TalkBack reads "Unavailable". A screen tap is swallowed with no status, because a disabled
  `clickable` never reaches `dispatchPhotoShutter`. The hardware shutter in the same window
  reaches the engine and gets "Finishing previous photo". So one state gives two different
  feedbacks depending on which shutter the operator used, and the on-screen one reads as a fault.
  Sony bodies show this as "Writing…" or a buffer indicator, not a dead button.
- **Fix:** separate "busy writing" from "camera unhealthy" in presentation. Keep the shutter at
  full paint but `enabled`, and let a tap go through the engine path so it publishes
  `FINISHING_PREVIOUS_PHOTO`, the same feedback the hardware key gets. Alternatively, add a
  distinct busy treatment (a ring sweep) and a `stateDescription` such as "Saving previous photo"
  (EN + KO). Host test: with the DNG lease held, the shutter is not `disabled()` and its state
  description is the busy string.

### DES4-5: The Output row says "HEIF/JPEG unavailable · DNG only" (or "Still capture unavailable") and disables HEIF/JPEG during every reconfigure

- **Severity:** Low-Medium. **Confidence:** High (source). **Status:** Likely (visible for the
  reopen duration; Needs-manual-validation for how long).
- **Region:** `ui/controls/ProControls.kt:1141-1183` (caption cascade) fed from
  `ui/controls/ProSheet.kt:877-899` (`processedAvailable = state.photoSessionOutputs.processed`,
  `rawAvailable = rawSelectable(...)`). Not-Ready publications clear the outputs
  (`ui/CameraViewModel.kt:851-855`, "clear accepted reader truth until a new owned Ready arrives").
- **Why:** while `cameraReady == false`, `processed == raw == false`. On a RAW-capable rear photo
  route `rawSelectable(...)` is still true, so the cascade falls to its last branch and prints the
  accepted-mask copy "HEIF/JPEG unavailable · DNG only". The HEIF and JPEG chips also go disabled.
  Without RAW, the first branch prints "Still capture unavailable". After cycle 3 this also covers
  10-bit video: `hlg` is false while reopening, so the designed trade copy is replaced by the fault
  copy until Ready. None of these states is true. The camera is reconfiguring, and the app's single
  name for `!cameraReady` is "Camera reconfiguring…" (the same file uses it for Custom WB,
  `ProSheet.kt:1116-1120`).
- **Failure scenario:** on PMA110, the operator opens Shoot and taps Aspect 16:9, which sits
  directly under the Output row. The aspect reopen greys HEIF and JPEG, and the row briefly
  claims the camera can only shoot DNG. A TalkBack user focused on the HEIF chip hears it become
  disabled.
- **Fix:** check `!state.cameraReady` first. Show `status_camera_reconfiguring` and keep the chips'
  enabled state as last accepted (or simply enabled, since the selection is intent and is
  normalized at Ready). Host test: Not-Ready + RAW-capable caps → the reconfiguring caption, not
  the DNG-only one.

### DES4-6: On a preview-only photo session the OSD still lists the requested formats; the "--" token its comment promises is unreachable

- **Severity:** Low. **Confidence:** High. **Status:** Confirmed.
- **Region:** `ui/overlays/Overlays.kt:988-999` (`photoFormatLabel(state.effectivePhotoFormats)`,
  changed in cycle 3); `camera/CameraState.kt:1512-1513` (`effectiveFor` returns the REQUEST when
  `!outputs.hasStillTarget`); comment `Overlays.kt:753-756` ("this slot and that focal readout land
  in the SAME StatusBar row on a preview-only session" → "--").
- **Why:** cycle 3 moved the OSD to `effectivePhotoFormats` so it reports accepted truth, but
  `effectiveFor` falls back to the request when there is no still target. The one session with
  nothing to save therefore shows "HEIF" (full DISP) beside a dimmed shutter. The
  "Still capture unavailable" status is gone after 6 s, so the OSD keeps asserting an output that
  cannot happen.
- **Fix:** in the OSD (not in `effectiveFor`, which capture normalization also uses), show "--"
  when `cameraReady && !photoSessionOutputs.hasStillTarget`, and keep the request while
  reopening. Unit-test the label function.

### DES4-7: The exposure meter has no spoken form; TalkBack reads "M --" / "M +0.3" and nothing of the scale

- **Severity:** Low. **Confidence:** High (source); Medium (exact verbalisation).
  **Status:** Confirmed.
- **Region:** `ui/CameraScreen.kt:2738-2813` (`ExposureMeter`: a `Column` with a bare `Text(label)`
  and an unlabelled `Canvas`; no `semantics`).
- **Why:** in M mode this is the only exposure feedback, and it stays on screen even in compact
  DISP. The leaf is read as raw glyphs ("M", "M plus zero point three"), with no name, no unit and
  no over/under meaning. The sibling scopes already have localized descriptions
  (`a11y_histogram_*`, `a11y_waveform_luma_range`). This is the same class as DES3-3 / AGG3-57,
  but on a different and more important instrument.
- **Fix:** `clearAndSetSemantics { contentDescription = "Exposure meter"; stateDescription =
  "Metered +0.3 EV" | "Metering…" }` (EN + KO). In P/S/ISO modes, use the existing
  `a11y_exposure_compensation` name plus the EV value. Do not add a live region (the meter updates
  at analysis rate).

### DES4-8: Korean Fn tile values likely ellipsize in portrait (Sound Focus / Sound Stage, Custom)

- **Severity:** Low. **Confidence:** Medium. **Status:** Needs-manual-validation (font metrics on
  device).
- **Region:** `ui/CameraScreen.kt:2722-2731` (`FnOverlayTileValue`: `labelMedium` SemiBold,
  `maxLines = 1`, ellipsis); `fnOverlayVisualValue` (`:2667-2685`) shortens values only in held
  landscape. Tile text width in portrait on a 411 dp phone is about (411 − 28 − 20 − 24)/4 − 18 ≈
  67 dp.
- **Why:** full-width Hangul at 12 sp is about 12 dp per syllable. `사운드 스테이지` (≈ 87 dp),
  `사운드 포커스` (≈ 75 dp) and `사용자 지정` (≈ 63 dp, borderline) exceed or approach that
  width. The KO short forms (`fn_short_sound_focus` 포커스, `fn_short_sound_stage` 스테이지)
  already exist but are used only when held sideways. The EN "Sound Stage" is also near the limit.
  Accessibility is unaffected (full value in `stateDescription`).
- **Fix:** use the compact value whenever the full one does not fit (`TextMeasurer` or
  `onTextLayout` with `hasVisualOverflow`), not only in held landscape. Device screenshot in KO with
  the Audio Fn slot.

## Carried items re-validated

- **DES2-5 / AGG-63 (accessibility timeout):** partially mitigated, because the retained trio is
  now 6 s. There is still no `AccessibilityManager.getRecommendedTimeoutMillis` caller anywhere in
  `app/src/main`. DES4-2 makes this worse in practice: even a long-enough timeout does not help a
  message that a later status overwrites.
- **DES2-3** (statuses drawn under open review), **DES2-4 / AGG-62** (tab rail at large font),
  **DES2-6 / AGG-64** (plate without horizontal margin, `CameraScreen.kt:1587-1609`),
  **DES2-7** (KO `value_fast`/`value_small`/`value_large` part-of-speech mix), **DES2-8**,
  **DES2-9** (DISP renames its node, `CameraScreen.kt:2255-2257`), **DES3-3 / AGG3-57** (OSD status
  row spoken form), **DES3-5** (dropdown selection by colour only): unchanged.

## Checked with no finding

- Cycle-3 retained-take copy: "kept privately … fully closed and opened again" names the real
  trigger. `VIDEO_KEPT_UNVERIFIED` says "checked", not "saved", and is WARNING with 6 s. The KO
  copy is natural and particle-correct. `status_output_saved_pending_recovery` no longer says
  "Recovery marker".
- `StatusInfoPill` now estimates shots from `effectivePhotoFormats`, so a route without RAW no
  longer counts 26 MB per shot for a DNG it will not write.
- `noStillOutputCaption(hlgSessionAccepted)`: correct once Ready (DES4-5 covers the reopen window).
- `PermissionGate` policy-blocked branch routes to app settings (`permanentlyDenied = true`), so
  the CTA is never a dead re-request.
- Shutter blink (90 ms) fires once per accepted press, not per burst frame, so there is no
  strobing.
- Touch targets in everything touched or newly read: rail chips, mode labels, chrome buttons,
  Fn tiles (≥56 dp), review close/delete, snapshot dot and dialogs all meet the 48 dp floor.
- Quiet-viewfinder policy: no banners, coach marks or helper overlays added in the delta.

## Files examined

- UI: `ui/CameraScreen.kt` (whole viewfinder composition, TopBar, StatusInfoPill, ZoomIndicator,
  FnOverlay, ExposureMeter, FocalRail, ModeCarousel, ShutterRow/Button, SnapshotButton),
  `ui/overlays/Overlays.kt` (StatusBar, photo-format labels), `ui/controls/ManualDials.kt`
  (rulers, `RulerSlider`), `ui/controls/ProControls.kt` (`CameraSlider`, `LabeledSlider`,
  `SegmentedSelector`, `PhotoFormatToggles`), `ui/controls/ProSheet.kt` (TabRail, ShootingTab,
  slider call sites), `ui/review/MediaReview.kt` (close/delete/transport buttons),
  `ui/CameraViewModel.kt` (status publication, admission callback, late-output delete, shutter
  dispatch, countdown), `ui/LocalizedStatus.kt`, `MainActivity.kt` (PermissionGate, mic
  rationale).
- Engine/state (for UI truth): `camera/CameraStatus.kt`, `camera/CameraState.kt`
  (`effectiveFor`, `PhotoSessionOutputs`, shutter predicates), `camera/OpticsConstraints.kt`
  (`rawSelectable`), `camera/DngPreCaptureAllocation.kt`, `camera/CameraEngine.kt:200-245,
  1820-1835, 4800-4860, 5354-5359`, `camera/RecordingStorageDispatcher.kt`.
- Resources: `res/values/strings.xml`, `res/values-ko/strings.xml`, `res/font/*.ttf`.
- Library: `androidx.compose.ui:ui-android:1.12.1` (`AndroidComposeViewAccessibilityDelegateCompat`,
  javap).
- Prior context: `.context/reviews/archive-rpl-cycle{1,2,3}-2026-10-02/designer.md`,
  `archive-rpl-cycle3-2026-10-02/_aggregate.md`, `docs/plans/2026-10-02-rpl-cycle3.md`,
  `docs/UX_POLICY.md`.
