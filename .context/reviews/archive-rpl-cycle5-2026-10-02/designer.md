# Native Android designer review (RPL cycle 5)

Date: 2026-10-02
Reviewed revision: `ea7d4374` (`main`)
Method: static only. No device, emulator or TalkBack run. The UI is Compose, so browser tooling does
not apply. Finding prefix: UX5-.

## Scope and method

- Delta since the cycle-4 designer pass (`14767b0a..ea7d4374`). UI/res surface touched:
  `PhotoFormatChips.kt` (new), `ProControls.kt` / `ProSheet.kt` (Output row), `Overlays.kt`
  (`osdPhotoFormats`, `statusBarFocalLabel`), `CameraStatus.kt` + `CameraViewModel.kt`
  (`StatusPlate` rank arbitration), `ExposureMeterSpeech.kt` + `CameraScreen.kt` (meter semantics),
  `RulerAccessibility.kt` + `ManualDials.kt` (snapped-ruler steps, ISO ladder, `ZoomRuler` on the
  display scale), `ZoomMath.kt` (`zoomRulerScale`), `MomentaryHold.kt`, `MainActivity.kt`
  (denial-reason owner), and the 4 new EN/KO strings.
- I traced each new UI surface back to the engine paths that feed it (`CameraEngine.capturePhoto`
  at `camera/CameraEngine.kt:5102-5128`, `PhotoFormats.normalizedFor` / `effectiveFor` /
  `withEdit` at `camera/CameraState.kt:1300-1562`, and `rawSelectable` at
  `camera/OpticsConstraints.kt:97-103`) instead of reading the composables alone.
- I checked the plan's later-cycle, carried and deferred lists so that tracked items are not
  reported again as new (AGG4-72, AGG3-57, DES2-x and the others listed under "Carried").
- Mechanical checks:
  - **EN/KO parity of the delta.** All 4 new keys (`a11y_exposure_meter`, `_metered`, `_metering`,
    `a11y_ev_value`) exist in both locales with the same `%1$s` placeholders. The KO copy reads
    naturally (`노출계`, `측광값 %1$s EV`, `측광 중…`).
  - **Glyph coverage.** The new literals use only `± … ×`, all of which are in the covered Inter set.
  - **Touch targets.** None of the touched controls drop below 48 dp. The Fn button and the
    compact-dial close button keep their 48 dp boxes; the 36 dp and 32 dp sizes are only the
    visible plates.

## Findings

### UX5-1: Every front-camera shot with a DNG request raises a red, assertive 6 s "RAW unavailable" ERROR, and the new rank arbiter then holds the plate against everything below ERROR

- **Severity / confidence / status:** Medium / High / Confirmed (from code).
- **Cites:** `camera/CameraEngine.kt:5116-5127`; `camera/CameraStatus.kt:181` (RAW_UNAVAILABLE in
  the ERROR group) and its 6 000 ms ERROR duration; `camera/CameraStatus.kt` `StatusPlateRank` /
  `StatusPlate.publish`; `ui/CameraViewModel.kt:2543` (the shutter passes the request with
  `dngRaw` intact); `ui/controls/PhotoFormatChips.kt:86-87` (the Output row already says "RAW
  unavailable" as a standing caption).
- **Why:** CLAUDE.md DNG rules 3 and 4, plus the FRONT entry, make a DNG request *intent* that
  survives on FRONT, hi-res and drop-RAW routes, where RAW is structurally excluded. The OSD and the
  Output row already show that truthfully (`osdPhotoFormats` drops DNG, and the caption says "RAW
  unavailable"). However, `capturePhoto` raises `formats.dngRaw && !effFormats.dngRaw` as
  `RAW_UNAVAILABLE`, whose severity is ERROR, on every press. The shot succeeds. The status is still
  a 6 s red plate with an assertive live region, so TalkBack interrupts on every selfie. That breaks
  the quiet-viewfinder policy for a state the session cannot change. Since AGG4-65 the plate is also
  rank-arbitrated, and an unexpired ERROR drops every later SUCCESS, INFO and WARNING event (see
  UX5-2). For 6 s after each front shot, refusals such as `FINISHING_PREVIOUS_PHOTO`,
  `SWITCH_TO_REAR_FIRST` and `MEMORY_SLOT_EMPTY` vanish, and so does the review's `DELETED`
  confirmation.
- **Failure scenario:** On PMA110, the operator shoots HEIF+DNG on the rear tele, flips to FRONT and
  takes three selfies. Each press flashes "RAW unavailable" in red for 6 s, even though every HEIF
  saved. A tap on a lens chip during that window shows nothing, because `SWITCH_TO_REAR_FIRST` is a
  WARNING and is dropped, so the chip looks dead.
- **Fix:** Announce RAW loss only on the edge where it is news, the same way AGG4-67 handles
  `dngOnlySubstitution`. Announce it once per accepted session shape at Ready, and only when the
  route *could* carry RAW (`rawSelectable == true`, i.e. a drop-RAW rung). On structurally RAW-less
  routes (FRONT, hi-res, 10-bit) the press should stay silent, because the Output caption and the
  OSD already carry the state. If a per-press line is kept, it should be INFO rather than ERROR. A
  pure host test fits the existing shape: predicate (request, outputs, rawSelectable, previously
  announced) → announce?. PMA110 changes: FRONT presses with a DNG request stop showing the error.
  That is a deliberate correction.

### UX5-2: Rank arbitration silently drops the direct response to an operator action (refusals, "Deleted", "Video saved")

- **Severity / confidence / status:** Medium / High / Confirmed (from code).
- **Cites:** `camera/CameraStatus.kt` `StatusPlateRank { PROGRESS, SUCCESS, INFO, WARNING,
  RETAINED_TAKE, ERROR }` and `StatusPlate.publish` (`else -> Publication(this, shownChanged =
  false)`); `ui/CameraViewModel.kt:1811-1824` (`publishStatus` writes nothing when the incoming status
  is dropped); `ui/CameraViewModel.kt:1880-1907` (`rejectIfRecording`, `rejectBackOnlyOpticsDoor`);
  the severities in `camera/CameraStatus.kt:118-160` (`STOP_RECORDING_*`, `SWITCH_TO_REAR_FIRST`,
  `MEMORY_SLOT_EMPTY` = WARNING; `FINISHING_PREVIOUS_*` = INFO; `VIDEO_SAVED`, `DELETED`,
  `MEMORY_SLOT_LOADED` = SUCCESS).
- **Why:** AGG4-65 correctly stops a low-value line from erasing a retained-take instruction or an
  error. The arbiter has no notion of *who asked*, though. A refusal is the only feedback for a tap
  the app declined. When it is dropped, the control appears inert. That is the affordance failure
  DES4-4 objected to for the shutter. The order also puts SUCCESS below INFO, so the confirmation of
  the operator's own action ranks lowest. A `RECORDING_WITHOUT_AUDIO` INFO (2.5 s) on a short clip
  drops the `VIDEO_SAVED` that follows it, and any WARNING or ERROR on the plate drops "Deleted"
  after a review delete.
- **Failure scenario:** A 6 s `VIDEO_SAVE_DELAYED` retained-take line is on the plate. The operator
  taps MR2, which is empty. `MEMORY_SLOT_EMPTY` (WARNING) ranks below RETAINED_TAKE and is dropped,
  so the tap does nothing visible. Separately, the operator records a 2 s clip with the mic denied.
  `RECORDING_WITHOUT_AUDIO` is still up at stop, so `VIDEO_SAVED` is dropped and the take ends with
  no confirmation.
- **Fix:** Classify statuses as *responses* (the synchronous answer to an input: refusals,
  `MEMORY_SLOT_*`, `DELETED`/`DELETE_CANCELED`, `VIDEO_SAVED`) or *ambient*. A response should
  always take the plate and defer the higher-ranked unexpired event, which returns for its remaining
  time when the response expires. This mirrors what `deferredProgress` already does for PROGRESS. A
  cheaper alternative is to rank responses at least WARNING and move SUCCESS above INFO. Pure
  reducer tests in `StatusPlateArbitrationTest` cover either option. There is no PMA110 pipeline
  change; only plate presentation changes.

### UX5-3: The Shooting-tab Zoom slider still reads lens-local "1.0×" on the 70 mm standalone route while the chip, the ruler and the HUD pill read "3.0×"

- **Severity / confidence / status:** Medium / High / Confirmed (from code).
- **Cites:** `ui/controls/ProSheet.kt:934-963` (`zBase = if (teleconverterMode) teleDisplayBase(...)
  else 1f`); compare `ui/ZoomMath.kt:36-49, 51-89` (`zoomDisplayMultiplier`, `zoomRulerScale`),
  `ui/controls/ManualDials.kt:295-306` and `ui/CameraScreen.kt:1202-1216`.
- **Why:** AGG4-73 put the Quick Zoom ruler on the shared display multiplier, citing "a '1.0×' ruler
  under a '3.0×' chip was two magnifications for one framing". The same commit deleted the
  `ZoomMath` KDoc sentence that excused the Shooting-tab slider ("EDIT surfaces on the lens-local
  scale outside TELE ... deliberately keep their own base"). The slider itself was never migrated.
  On every rear standalone route (all of Video, and Photo with DNG on PMA110) its label and value
  are the raw lens-local ratio. With the 70 mm lens at native framing the sheet says "1.0×" while
  the ZOOM Fn tile, the ruler and the pill say "3.0×". The slider's range (1–10×) also disagrees with
  the ruler's (≈3–30×).
- **Failure scenario:** In Video on PMA110, the operator opens the menu and the Shoot tab shows Zoom
  "1.0×". They close it, and the HUD pill reads "3.0×" for the same framing.
- **Fix:** Build the slider from `zoomRulerScale(range.lower, range.upper, zoomDisplayMultiplier(
  teleconverterMode, teleconverterMagnification, caps?.equivalentFocalMm, frontFacing,
  activeCameraRoute), teleconverterMode)`. Read the value and label from `scale.display(...)` and
  write through `display / scale.base`, with the existing `zHi > loDisplay` guard becoming
  `scale.enabled`. A host test can pin slider, ruler and pill to one value per route. On PMA110 only
  the displayed value changes; the wire zoom is unchanged.

### UX5-4: The Ready-state Output caption "Switching to a single lens for RAW" is a permanent false claim on a session that settled without RAW

- **Severity / confidence / status:** Low / Medium / Confirmed (from code; the drop-RAW rung is rare
  on PMA110).
- **Cites:** `ui/controls/PhotoFormatChips.kt:78-90` (`!outputs.raw && request.dngRaw ->
  output_switching_single_lens`); `camera/OpticsConstraints.kt:97-103` (`rawSelectable` is true on
  any rear photo non-hi-res route); `res/values/strings.xml:347`.
- **Why:** Since AGG4-66/69 the reopen window is handled by the `reconfiguring` branch, so this
  branch runs only when `cameraReady` is true. In that state the route move that DNG triggers has
  already finished. `!outputs.raw` therefore means the accepted session (the ladder's drop-RAW rung,
  or a standalone lens with no RAW output) does not carry RAW. Nothing is switching. The caption
  promises a change that will never happen, and every shot meanwhile raises `RAW_UNAVAILABLE`.
- **Fix:** When `cameraReady && rawAvailable && !outputs.raw && request.dngRaw`, show
  `output_raw_unavailable`, or a new "RAW not available on this session" string with a KO
  counterpart. Keep "switching" only for a pre-Ready reopen that is genuinely caused by the DNG
  request, if the reconfiguring caption is not enough there. This is a pure model test. No PMA110
  pipeline change.

### UX5-5: A HEIF stand-in is shown, and written, on a device that cannot encode HEIF

- **Severity / confidence / status:** Low (rare device class) / High (code path) / Confirmed (from
  code); needs a HEVC-less API 33+ device to observe.
- **Cites:** `camera/CameraState.kt:1517-1534` (`normalizedFor`: `outputs.processed ->
  PhotoFormats(heif = true)`); `camera/CameraEngine.kt:5116` (capture uses it);
  `ui/CameraViewModel.kt:2543` (`normalizedForEncoder` is applied only to the request);
  `ui/controls/PhotoFormatChips.kt:64-68` (chip and OSD display `effectiveFor`).
- **Why:** On a DNG-only request whose session has no RAW (FRONT, hi-res, the drop-RAW rung), the
  substitute processed format is hard-coded to HEIF. It ignores the encoder inventory that
  `PhotoFormats.normalizedForEncoder` exists for, and that inventory fact is not plumbed into the
  engine. On a handset without a platform HEVC encoder, the Output row lights HEIF (disabled), the OSD
  says "HEIF", and the shot runs `HeifWriter`, which cannot encode. Every such shot then fails with
  `HEIF_SAVE_FAILED`.
- **Fix:** Pass the HEIF-encoder fact into `normalizedFor` and `effectiveFor`, and substitute JPEG
  when it is false. A pure test can pin this. PMA110 is unchanged, because HEIF is available there.

### UX5-6: Tapping JPEG beside a HEIF stand-in moves the selection instead of adding to it

- **Severity / confidence / status:** Low / High / Confirmed (from code).
- **Cites:** `ui/controls/PhotoFormatChips.kt:43-50` (`toggled` → `withEdit`);
  `camera/CameraState.kt:1314-1330` (`withEdit` keeps unchanged axes from the REQUEST).
- **Why:** The chips are pick-many: the leading check marks say so. On FRONT with a DNG-only
  request, the row shows HEIF lit (a session stand-in, and disabled) and JPEG unlit. Tapping JPEG
  produces the request `{jpeg, dng}`. The HEIF axis was unchanged against `displayed`, so it keeps
  the request's `false`. The row then re-renders with HEIF unlit and JPEG lit, so a tap that looked
  like "add JPEG" acted like a radio button. The operator never touched HEIF, yet HEIF disappears
  from what is written.
- **Fix:** In `toggled`, when the request has no processed axis but `displayed` shows a stand-in
  processed axis, carry the displayed processed axes into the request together with the tapped one
  (`{heif, jpeg, dng}` here). Alternatively, render the stand-in with a distinct "substituted" style,
  not as a selected chip. Either way it is a pure model test. No PMA110 pipeline change.

### UX5-7: The Output row says "Camera reconfiguring…" for any not-Ready state, including a terminal failure and cold start

- **Severity / confidence / status:** Low / Medium / Confirmed (from code).
- **Cites:** `ui/controls/PhotoFormatChips.kt:60-62, 79` (`reconfiguring = !cameraReady` →
  `status_camera_reconfiguring`).
- **Why:** `cameraReady` is also false after the retry budget is exhausted
  (`CAMERA_UNAVAILABLE_REOPEN`, which tells the operator to reopen the app), after pause, and during
  cold start, when the plate says "Starting camera…". In the terminal case the sheet keeps claiming
  that a reconfigure is in progress, next to an error that says the camera is gone. The KDoc calls
  this "the app's one name for that state", but the state it actually keys on is broader than a
  reopen.
- **Fix:** Key the caption on the same PROGRESS condition the plate owns: show "reconfiguring" only
  while a reopen or recovery PROGRESS is active, and otherwise drop the caption or show the terminal
  status. Alternatively, pass a small `StillRowState { READY, OPENING, UNAVAILABLE }` into
  `photoFormatChipModel`. This is a pure test. No PMA110 pipeline change.

### UX5-8: The exposure meter's `stateDescription` changes at analysis rate, so a TalkBack-focused meter may chatter

- **Severity / confidence / status:** Low / Low / Needs device validation (TalkBack).
- **Cites:** `ui/CameraScreen.kt:2758-2787` (`stateDescription = spokenState`, recomputed from
  `histogramData` on each analysis tick); `ui/ExposureMeterSpeech.kt:30-35` (0.1 EV resolution).
- **Why:** The KDoc says "No live region: the meter updates at analysis rate." TalkBack still speaks
  `CONTENT_CHANGE_TYPE_STATE_DESCRIPTION` changes on the accessibility-focused node. A metered value
  that flickers between, say, +0.3 and +0.4 can therefore be re-read continuously while the meter
  has focus.
- **Fix:** If a device check confirms it, quantize the spoken value to 1/3 EV with hysteresis, or
  publish `stateDescription` only when it moves by at least 1/3 EV. A pure helper test fits. No
  PMA110 pipeline change.

## Already tracked, re-validated

- **AGG4-72 / DES4-8** (KO Fn tile ellipsis): no change in the delta, so still open.
- **AGG3-57 / DES3-3** (OSD status-row spoken form), **DES2-3** (statuses under open review),
  **DES2-5 / AGG-63** (no `getRecommendedTimeoutMillis` caller): unchanged. UX5-2 makes DES2-5's
  concern sharper, because a dropped status gets no timeout at all.

## Checked with no finding

- `PhotoFormatChipModel` fixes DES4-5/6: during a reopen the request stays shown with live chips, and
  a Ready preview-only session shows "--" in the OSD through `osdPhotoFormats`.
- Hi-res sessions: HEIF is disabled rather than accepting a no-op tap, JPEG is the only lane, and the
  DNG chip is off through `rawSelectable`.
- `rulerSemanticSteps`: `steps = totalUnits - 1` gives an increment of 1/totalUnits, so one swipe
  moves exactly one detent across the `0..totalUnits` grid (DES4-1 closed). The `setProgress`
  handler still refuses while disabled.
- `zoomRulerScale` handles `hi <= lo` without calling `coerceIn` (disabled ruler; `localFor` is not
  reachable then). The TC cap applies to total magnification only.
- `MomentaryHold`: release restores the snapshot. An explicit toggle cancels the hold, and
  onStop, recall and restore end it. A save made while the key is held writes the operator's own
  value. `hardwareActionAdmitted` gates only SHUTTER, so a release edge is never filtered out.
- The `ExposureMeter` semantics are a single `clearAndSetSemantics` leaf with name plus state
  (DES4-7 closed). EN and KO strings are correct.
- `statusBarFocalLabel` and the program handheld rule now read one effective focal.
- The quiet-viewfinder policy holds: the delta adds no banners, coach marks or helper overlays.
  UX5-1 is a per-press status, not a standing overlay.

## Files examined

- UI: `ui/controls/PhotoFormatChips.kt`, `ui/controls/ProControls.kt` (PhotoFormatToggles),
  `ui/controls/ProSheet.kt` (ShootingTab Output and Zoom rows, MR summary),
  `ui/controls/ManualDials.kt` (ZoomRuler, RulerSlider, ISO ladder, Fn/close buttons),
  `ui/controls/RulerAccessibility.kt`, `ui/controls/LocalizedControlLabels.kt`,
  `ui/controls/ControlCycles.kt`, `ui/ZoomMath.kt`, `ui/ExposureMeterSpeech.kt`,
  `ui/MomentaryHold.kt`, `ui/CameraScreen.kt` (ExposureMeter, zoom pill, status plate host,
  StatusInfoPill), `ui/overlays/Overlays.kt` (StatusBar, format labels, compact gate),
  `ui/CameraViewModel.kt` (status publication/arbitration/expiry, hardware actions, momentary
  holds, photo-format edit, onStop), `ui/CameraScreenPolicy.kt`, `ui/SwitchCoverPolicy.kt`,
  `MainActivity.kt` (denial-reason owner, recall provenance), `HardwareInputPolicy.kt`.
- Engine/state for UI truth: `camera/CameraStatus.kt`, `camera/CameraState.kt` (PhotoFormats,
  normalizedFor/effectiveFor/withEdit, PhotoSessionOutputs, osdPhotoFormats, CameraRoute),
  `camera/OpticsConstraints.kt` (rawSelectable), `camera/CameraEngine.kt:5100-5135, 5300-5320`.
- Resources: `res/values/strings.xml`, `res/values-ko/strings.xml` (delta keys and Output captions).
- Prior context: `.context/reviews/archive-rpl-cycle4-2026-10-02/designer.md`, `_aggregate.md`,
  `docs/plans/2026-10-02-rpl-cycle4.md`.
