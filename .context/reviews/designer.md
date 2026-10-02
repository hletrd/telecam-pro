# Native Android designer review (RPL cycle 6)

- Agent: designer (UI/UX), finding prefix `UX6-`
- Reviewed revision: `30970c9e` (`main`); cycle-5 delta `ea7d4374..30970c9e`
- Method: static only. No device, emulator or TalkBack run (Compose UI; no browser tooling applies).

## Scope inventory

- **Cycle-5 UI delta, read in full:** `ui/controls/PhotoFormatChips.kt` (AGG5-52/54/55),
  `ui/controls/ProSheet.kt` (Shoot-tab Zoom slider on `zoomRulerScale`, `reopenInProgress`),
  `ui/ZoomMath.kt` (`mainRelativeZoomMultiplier`, `standaloneMainDivisorMm`, logical route 1:1),
  `ui/controls/ManualDials.kt`, `ui/controls/FnQuickActions.kt`, `ui/CameraScreen.kt` (pill),
  `ui/MomentaryHold.kt`, `camera/CameraStatus.kt` (responses, resolutions, deferred events,
  ERROR-ranked conditions, `condition`), and the VM plate plumbing
  (`ui/CameraViewModel.kt:1811-2107`, Ready fold `:950-1080`), plus the removed EN/KO string.
- **Traced to engine feeders:** `CameraEngine.capturePhoto` (`:5195-5230`), recorder admission
  refusals (`:6295-6640`), `onCaps` fold (`ui/CameraViewModel.kt:3440-3475`), DNG door
  (`:2749-2823`), mode door (`:2662ff`).
- **Rest of `ui/**` swept** for the review angle: `CameraScreenPolicy.kt` (rail state), `ProSheet.kt`
  Custom WB caption, status plate composable (`CameraScreen.kt:1582-1602`), touch targets
  (every sub-48 dp `size()` is a visual plate inside a 48 dp owner), hard-coded literals (none
  user-facing beyond the pre-existing `T{n}s` timer glyph).
- **Mechanical checks:** EN/KO parity — 0 missing, 0 extra keys, all placeholders match (script over
  both `strings.xml`); the only identical pair is `fn_short_focal_mm` (`%1$dmm`), which is correct.
  `output_switching_single_lens` removed from both locales together; no remaining reference.
- Known items not re-raised: AGG5-53 (HEIF stand-in without HEVC), AGG5-56 (meter chatter), AGG5-38.

## Findings

| ID | Sev | Conf | Status | Summary | Cite |
|---|---|---|---|---|---|
| UX6-1 | Medium | High | Confirmed (code) | REC refusal `MICROPHONE_BUSY` and the mic-outcome lines are not RESPONSES, so they are dropped under the very failure/retained-take line that makes them fire; the REC press looks inert | `camera/CameraStatus.kt:314-333,459-462`; `camera/CameraEngine.kt:6547,6619` |
| UX6-2 | Low | Medium | Likely | `CAMERA_RECONFIGURING` is used as a one-shot refusal but has PROGRESS lifecycle (no timer); a late recorder refusal after Ready sticks on the plate | `camera/CameraEngine.kt:6308,6535,6636`; `camera/CameraStatus.kt:203-210` |
| UX6-3 | Low | High | Confirmed (code) | Zoom readouts show "1.0×" for the whole reopen after Photo→Video or DNG-on: the multiplier mixes the NEW route intent with the OUTGOING camera's caps | `ui/ZoomMath.kt:91-104`; `ui/CameraViewModel.kt:2816,3467` |
| UX6-4 | Low | High | Confirmed (code) | On a drop-RAW rung with a DNG-only request the HEIF stand-in chip is enabled and checked but a tap is a no-op | `ui/controls/PhotoFormatChips.kt:96-97`; `camera/CameraState.kt:1317-1333` |
| UX6-5 | Low | Medium | Confirmed (code) | AGG5-55 fixed only the Output row: the focal rail's TalkBack state and the Custom WB caption still say "Camera reconfiguring…" for cold start and the terminal "reopen the app" state | `ui/CameraScreenPolicy.kt:695`; `ui/CameraScreen.kt:2986`; `ui/controls/ProSheet.kt:1116-1119` |
| UX6-6 | Low | Medium | Confirmed (code); reachability needs device | Per-press `PROCESSED_STILL_UNAVAILABLE_DNG_ONLY` still fires on every shot although the Ready edge already announces it (the AGG5-10 rationale applies) | `camera/CameraEngine.kt:5210-5213` |

---

### UX6-1: The REC refusal "Microphone busy" and the microphone-outcome lines are not responses, so the plate drops them in exactly the situation that produces them

- **Severity / confidence / status:** Medium / High / Confirmed (from code).
- **Cites:** `camera/CameraStatus.kt:314-333` (`RESPONSE_MESSAGES`), `:459-462` (an event that is not a
  response and ranks below the guard is dropped); `camera/CameraEngine.kt:6547` (`mic-claim-refused`)
  and `:6619` (`mic-release-timeout`) publish `MICROPHONE_BUSY` (WARNING) as the only feedback for a
  refused REC press; `MainActivity.kt:712,801,840,937,947` publish `RECORDING_WITHOUT_AUDIO` (INFO),
  `MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO` / `MICROPHONE_DENIED_AUDIO_OFF` (WARNING);
  `ui/CameraViewModel.kt:2864` (`MICROPHONE_ALLOWED_AUDIO_ON`, INFO) and `:3826-3829`
  (`AUDIO_INPUT_USING_DEFAULT`, WARNING, at REC start).
- **Why:** AGG5-11 made the operator's own answers RESPONSES that always take the plate, citing
  AGG4-29 ("a silent refusal ... the REC press appeared to do nothing"). The set lists the
  `STOP_RECORDING_*`, MR, delete and `VIDEO_SAVED` lines but leaves out every REC-press answer that
  comes from the microphone path. `MICROPHONE_BUSY` fires when "the claim is held by an earlier,
  unfinished recording owner", i.e. right after a previous take that did not finish cleanly — and a
  take that does not finish cleanly is precisely what puts a 6 s `VIDEO_SAVE_FAILED` (ERROR),
  `VIDEO_SAVE_DELAYED` or `VIDEO_KEPT_UNVERIFIED` (RETAINED_TAKE) on the plate. A WARNING ranks
  below both, is not a response, and is dropped (`publish` returns `shownChanged = false`). The
  optimistic "starting" state clears and nothing explains why. The same gap silences the "this take
  has no audio" truth: `RECORDING_WITHOUT_AUDIO` / `MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO` are
  dropped under any unexpired WARNING-or-higher line, so a silent take starts with no notice (the
  hidden meter is the only remaining cue, and only in the details view).
- **Failure scenario:** A clip stops with a provider hiccup and shows "Video kept — reopen the app to
  recover" (6 s). The operator immediately presses REC again; the recorder's mic release has not
  finished, the start is refused with `MICROPHONE_BUSY`, the plate keeps the retained-take line, and
  the REC button returns to idle with no explanation. TalkBack users hear nothing new.
- **Fix:** Add `MICROPHONE_BUSY`, `RECORDING_WITHOUT_AUDIO`, `MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO`,
  `MICROPHONE_DENIED_AUDIO_OFF`, `MICROPHONE_ALLOWED_AUDIO_ON` and `AUDIO_INPUT_USING_DEFAULT` to
  `RESPONSE_MESSAGES` (the covered retained-take/error returns for its remaining time, so AGG4-65 is
  preserved). Extend `StatusPlateArbitrationTest` with "MICROPHONE_BUSY over an unexpired
  VIDEO_SAVE_DELAYED is shown, and the retained line returns". A cheaper guard: a test that every
  status published from a refusal branch of `startRecording`/`capturePhoto`/VM door handlers is in
  the set. No PMA110 pipeline change; plate presentation only.

### UX6-2: `CAMERA_RECONFIGURING` doubles as a one-shot refusal but is a timer-less condition; a refusal that lands after Ready sticks on the plate

- **Severity / confidence / status:** Low / Medium / Likely (race; needs device timing to observe).
- **Cites:** `camera/CameraStatus.kt:203-210` (PROGRESS lifecycle, `durationMs = null`);
  `camera/CameraEngine.kt:6308` (no admission snapshot), `:6535` (`!admission.isCurrent()` after the
  MediaStore allocation), `:6636` (`session-superseded-before-native`), `:5202` (shutter);
  `ui/CameraViewModel.kt:2406` (Custom WB); `ui/CameraViewModel.kt:861` (`onStatus = ::publishStatus`,
  no generation/ownership check); `:972` (only a Ready publication or `onStop` clears a condition).
- **Why:** As a condition, "Camera reconfiguring…" is correct only while a reopen holds, and the
  plate relies on the NEXT Ready to end it. The recorder admission refusals fire from the recorder
  executor after the asynchronous MediaStore insert (bounded by an 8 s deadline), so they can arrive
  after the superseding session's Ready has already called `clearProgressStatus`. Nothing then ends
  the condition: the pill reads "Camera reconfiguring…" over a Ready, live viewfinder until the next
  optics door or backgrounding, and `cameraCondition` stays `CAMERA_RECONFIGURING`. It is also the
  wrong arbitration class for a refusal: as PROGRESS it waits behind any unexpired event, so the
  refused tap looks inert (the UX6-1 failure again).
- **Failure scenario:** The operator presses REC; a camera-health fault supersedes the session while
  the pending row is being inserted; recovery reaches Ready (~1.3 s measured) before the insert
  returns; the recorder then refuses with `CAMERA_RECONFIGURING`. The viewfinder is live and Ready,
  but the pill says "Camera reconfiguring…" indefinitely.
- **Fix:** Add an EVENT twin for the refusal (for example `CAMERA_NOT_READY_TRY_AGAIN`, or publish
  `CAMERA_RECONFIGURING` as an EVENT with the 2.5 s duration and list it in `RESPONSE_MESSAGES`) and use
  it at all five refusal sites; keep the PROGRESS form only for the engine's own reopen publications.
  Pure plate test: refusal after Ready expires on its own. New EN/KO string if a new message is added.

### UX6-3: Every zoom readout says "1.0×" for the length of the reopen after Photo→Video or DNG-on

- **Severity / confidence / status:** Low / High / Confirmed (from code; duration is the reopen time,
  ~1 s on PMA110).
- **Cites:** `ui/ZoomMath.kt:91-104` (`mainRelativeZoomMultiplier` reads `standaloneRouteWanted(...)`
  from the REQUEST — `mode`, `photoFormats.dngRaw` — but `equivalentFocalMm` from `caps`, i.e. the
  ACCEPTED camera); `ui/CameraViewModel.kt:2808-2817` (the DNG door writes the lens-local packet and
  the request at once), `:2662ff` (mode door, same); `:3467` (`caps` changes only when the new route's
  caps arrive).
- **Why:** AGG5-12/AGG5-49 put the pill, Fn ZOOM value, Quick Zoom ruler and Shoot-tab slider on one
  multiplier. On the PMA110 logical route at the 3× preset, turning DNG on (in the Shoot tab, two rows
  above the Zoom slider) or switching to Video writes local `1.0` and flips the standalone answer
  immediately, while `caps.equivalentFocalMm` is still the logical camera's 23.4 mm; the multiplier is
  `23.4 / 23 ≈ 1.02`, so every readout says "1.0×" until the tele caps land and it snaps back to
  "3.0×". The Shoot slider's range in that window is also the logical camera's (0.6–20) on a
  lens-local write path. Before cycle 5 the opposite flip (Video→Photo) showed "9.1×" for the same
  reason; the new `!standaloneRoute -> 1f` branch fixed that direction only.
- **Failure scenario:** PMA110, Photo at 3×, Shoot tab open. Tapping DNG makes the Zoom row jump from
  "3.0×" to "1.0×" and back a second later; the operator reads it as the DNG toggle having changed the
  framing.
- **Fix:** Resolve the numerator from the route the multiplier is answering for: when the standalone
  intent disagrees with the accepted route (or `!cameraReady`), use
  `lensInventory.presetEquivMm[state.lens]` (the lens the remap targeted) instead of `caps`; or hold
  the last published multiplier until the new caps arrive. Pure test over `CameraUiState`: DNG-on with
  outgoing logical caps reads 3.0×. PMA110 steady-state readouts unchanged.

### UX6-4: On a drop-RAW rung, the HEIF stand-in chip is enabled and checked, but tapping it does nothing

- **Severity / confidence / status:** Low / High / Confirmed (from code; drop-RAW rung is rare).
- **Cites:** `ui/controls/PhotoFormatChips.kt:96-97` (`heifEnabled` is true because `rawSelected` is
  true on a RAW-capable route); `:50-60` (`toggled`); `camera/CameraState.kt:1317-1333`
  (`processedCleared`).
- **Why:** With request `{dng}` and a session that kept processed but lost RAW, `displayed` is
  `{heif, dng}`. A HEIF tap edits to `{dng}`; `withEdit` sees every displayed processed axis cleared
  and returns `{dng}` — the unchanged request — so the row re-renders HEIF lit. TalkBack announces
  "HEIF, checked", the double-tap does nothing. On FRONT the same chip is disabled
  (`rawAvailable = false`), so the two RAW-less routes disagree about whether the stand-in is editable.
- **Fix:** Disable the processed chip that is a pure stand-in (`!request.wantsProcessedStill &&
  displayed.heif`) on every route, matching FRONT; the Output caption "RAW unavailable" already
  explains why. Pure model test.

### UX6-5: AGG5-55 is incomplete — the focal rail and the Custom WB caption still call every not-Ready state "Camera reconfiguring…"

- **Severity / confidence / status:** Low / Medium / Confirmed (from code).
- **Cites:** `ui/CameraScreenPolicy.kt:695` (`!cameraReady -> CAMERA_RECONFIGURING`);
  `ui/CameraScreen.kt:2986` (rail chip `stateDescription`); `ui/controls/ProSheet.kt:1116-1119`
  (Custom WB caption, whose comment calls it "the app's single name for !cameraReady").
- **Why:** Cycle 5 decided that cold start, pause and the exhausted-retry terminal are not a
  reconfigure and gated the Output row on `CAMERA_REOPEN_CONDITION_MESSAGES`. Two other surfaces still
  key on bare `!cameraReady`. After "Camera unavailable — reopen the app", TalkBack reads each disabled
  lens chip as "1×, Camera reconfiguring…" and the WB tab promises the same, for a camera that will not
  come back without a relaunch.
- **Fix:** Pass the same `reopenInProgress` fact into `focalRailState`/`teleZoomMarkState` (fall back to
  plain "unavailable" — `a11y_unavailable` or a new EN/KO pair — when not reopening) and into the Custom
  WB caption (omit the caption otherwise). Extend the existing rail-state test.

### UX6-6: The per-press "HEIF/JPEG unavailable · DNG only" warning survives although its Ready edge already announces it

- **Severity / confidence / status:** Low / Medium / Confirmed (from code); whether a PMA110 rung
  accepts RAW without a processed reader needs a device ladder log.
- **Cites:** `camera/CameraEngine.kt:5210-5213`; compare `ui/CameraViewModel.kt:1031-1038` (AGG4-67
  rising-edge announcement) and the AGG5-10 removal of the per-press `RAW_UNAVAILABLE` at `:5214-5218`.
- **Why:** The shot succeeds (DNG written), the Output caption and OSD show the state, and the Ready
  fold already announces it once per session shape. The per-press WARNING repeats it for 2.5 s on
  every shutter press and, as a non-response WARNING, drops every INFO/SUCCESS line that arrives in
  that window. This is the same pattern AGG5-10 removed for RAW.
- **Fix:** Delete the per-press branch (keep the Ready edge), or demote it to a response-free INFO.
  Engine test: two presses on a DNG-only session publish nothing.

## Final sweep (no new finding)

- New Shoot-tab Zoom slider: range guard `scale.enabled`, TELE cap, and write path `localForDisplay`
  are consistent with the ruler; TalkBack value is the formatted display value.
- Status plate: response covering / remaining-time return / resolution drop / ERROR-ranked condition
  paths traced; the timer re-arm on `expire` uses the remaining time correctly. Re-publishing an
  identical status does not re-announce under TalkBack (pre-existing, Compose live region diffs text).
- RTL, contrast, and 48 dp targets: no change in this delta that regresses them.
