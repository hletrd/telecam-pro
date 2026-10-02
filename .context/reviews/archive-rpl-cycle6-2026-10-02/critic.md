# RPL cycle 6 — critic review

- Agent: critic (CT6), RPL cycle 6
- HEAD: `30970c9e` (cycle-5 range `ea7d4374..30970c9e`, 65 commits)
- Angle: multi-perspective critique (user, operator, maintainer, Play reviewer): claims in code/docs not
  backed by the implementation, cycle-5 fixes that are incomplete, half-closed invariants, user-visible
  wrong behaviour.

## Scope inventory

Read: `CLAUDE.md`, the cycle-5 aggregate and plan, the shared brief. Examined every cycle-5 fix commit
on the app side and the code each one touches, plus the docs that describe them:

- Engine/controller: `2d0855c6` (cold-start replay, `completeGlInputReady`, `resumePreviewRebindWanted`),
  `8e329498` (bare door retry), `881e13af` (Ready-before-DNG-cancel, admission release order),
  `daac4664` + `3d1bfe81` (`RetainedStillDeletionOwner` per-id latch + `producerTerminalIds`),
  `3107c3f5` (chars read), `06057385` (frozen AF override), `64c20327` (buffer lost / sequence abort),
  `2d452f23` (stale recording presentation), `88a1b928` + `009bf08f` (RAW-loss notice),
  `3ebf8d4f` (BURST/AEB head).
- ViewModel/UI: `7f260cc2` (`StatusPlate` responses/resolutions, `cameraCondition`),
  `dd3edc64` + `3489859e` (stab/fps request split + rollback), `a15ba927` (recall audio provenance),
  `4f605424` + `4635bee2` (recall rollback), `d535ed28` + `4e3c971d` (punch-in assist / hold rebind),
  `63b8301d` (Output row caption), `14504891` + `665ab849` + `61c88986` (zoom display multiplier),
  `ee60e694` (stop windows), `fdaadf32` (inventory route change).
- Storage/video/log: `94984106`, `530ba47d`, `cda3d19a` (recovery probes), `ed385953` (EncoderCaps
  latch), `715ccb0a` (evidence reserve), `adda4175`.
- Docs: `docs/ARCHITECTURE.md` rows for `DiagnosticTelemetry.kt` and `PhotoFormatChips.kt`,
  `docs/FIELD_CHECKS.md` FrameGap protocol, `CLAUDE.md` log-quota bullet.

Final sweep: every caller of the new `requestedVideo*` fields, every publisher of the PROGRESS
conditions `cameraCondition` keys on, every `DiagnosticLog.evidence` / `evidenceDiagnosticAllowed`
user, and every `markCaptureDeleted` / `completeDeletionDurability` call site.

Verified and found sound (no finding): AGG5-1/26 replay split, AGG5-25 disposition table, AGG5-5
ordering, AGG5-28 identity check, AGG5-29, AGG5-39 stale presentation, AGG5-41, AGG5-45, AGG5-7,
AGG5-8, AGG5-71, AGG5-32, AGG5-20 ownership transfer, the MRG5-1 latch, the MRG5-4 divisor band.

## Findings

| ID | Sev | Conf | Status | Summary | Primary cite |
|---|---|---|---|---|---|
| CT6-1 | Medium | High | confirmed | AGG5-30 half-closed: an exhausted codec walk still returns EMPTY, the ViewModel applies it as device truth for its whole life, and HEIF→JPEG plus a log/HLG curve→SDR are persisted | `video/EncoderCaps.kt:123-150`; `ui/CameraViewModel.kt:3043-3093` |
| CT6-2 | Medium | High | confirmed | AGG5-55 regression: ordinary reopens (DNG, aspect, fps, lens, mode) publish no PROGRESS condition, so the Output row now says nothing during exactly the reopen the caption was written for; rail chips still say "reconfiguring" for any not-Ready; ARCHITECTURE row is stale | `ui/controls/PhotoFormatChips.kt:101-103`; `ui/controls/ProSheet.kt:885`; `camera/CameraStatus.kt:289-295` |
| CT6-3 | Low-Medium | Medium-High | confirmed (code) | The shutter refusal `CAMERA_RECONFIGURING` is a timer-less PROGRESS condition. After the terminal `*_REOPEN` error, a hardware-key press leaves "Camera reconfiguring…" on the plate indefinitely, and with AGG5-55 also lights the Output row in the terminal state that fix was meant to exclude | `camera/CameraEngine.kt:5202`; `camera/CameraStatus.kt:202-209` |
| CT6-4 | Low-Medium | Medium-High | confirmed (code) | MRG5-8 half-closed: a recall rollback restores only the VM stab/fps requests. The Engine and the displayed state keep the bank's values, and a later caps reconcile then shows the prior stab while the Engine keeps the bank's | `ui/CameraViewModel.kt:1165-1176, 1728, 1748, 3427-3470` |
| CT6-5 | Low | High | confirmed | The FrameGap evidence reserve does not back its claim. Periodic summaries the drained shared budget refuses still reset the accumulator, so the terminal line covers only the last window, or is absent at count 0 | `camera/DiagnosticTelemetry.kt:213-241`; `gl/GlPipeline.kt:902-920`; `docs/ARCHITECTURE.md:72` |
| CT6-6 | Low | Medium | needs manual validation | AGG5-2 residual: `producerTerminalIds` is capped at 32. A failed delete marker for an older live capture whose terminal id was evicted closes still admission until the process dies | `camera/RetainedStillDeletionOwner.kt:112-135`; `camera/CameraEngine.kt:8392` |

0 Critical, 0 High, 2 Medium, 2 Low-Medium, 2 Low.

---

## CT6-1 — An exhausted codec walk is applied as device truth and persisted (AGG5-30 half-closed)

**Severity** Medium. **Confidence** High. **Status** confirmed from code.

**Cites:** `video/EncoderCaps.kt:123-150` (`CodecInventoryLoader.load`), `:155-175` (`EncoderCaps`);
`ui/CameraViewModel.kt:3043-3056` (`loadEncoderInventoryAsync`), `:3058-3093`
(`applyEncoderInventory`); `camera/CameraState.kt:1282-1299` (`normalizedForEncoder`).

**Why it is a problem.** The AGG5-30 commit's own premise is that a `MediaCodecList` walk can throw
transiently (mediaserver restart, Binder hiccup). The loader now declines to latch that failure: "a
load that exhausts them returns EMPTY WITHOUT latching, so the next load (the next ViewModel) walks
again". The only consumer, however, cannot tell EMPTY-because-failed from EMPTY-because-this-device-
has-no-encoders:

- `loadEncoderInventoryAsync` calls `EncoderCaps.load()` once, from `init` (line 1423), and posts the
  result to `applyEncoderInventory` with no failure signal.
- `applyEncoderInventory(EMPTY)` sets `encoderInventoryLoaded = true` and `heifAvailable = false`.
  It also clears all three `pending*UntilInventory` requests and normalizes:
  - `PhotoFormats.normalizedForEncoder(false)` turns the default HEIF into JPEG (`heif=false, jpeg=true`).
  - `ColorTransfer.normalizedForEncoder(codec, tenBit=false)` turns an S-Log3/S-Log3.Cine/LogC3/HLG
    request into SDR.
  - `engine.setVideoPipeline(emptyCandidates, …)` arms REC with no encoder.
- The pending requests that protected the operator's choice (the AGG2-8 discipline) are discarded,
  and the normalized values are the new state that `currentExtras` persists on the next save or
  background.
- Nothing in this ViewModel's life loads again, so "the next load walks again" only helps after a
  process relaunch. By then the HEIF→JPEG and log→SDR rewrite is already stored in prefs.

**Failure scenario.** Cold start while mediaserver is restarting. All three walks throw within ~750 ms.
The operator's persisted HEIF+DNG / S-Log3 setup comes back as JPEG+DNG / SDR. Every video REC is
refused with empty candidates for the rest of the process. After relaunch the walk succeeds, but the
prefs already say JPEG/SDR, so the operator's choices are lost for good and the cause was logged once.

**Suggested fix.** Make the loader's result three-valued, for example `CodecInventoryResult.Loaded(inv)`
or `Failed`. On `Failed`, the ViewModel keeps `encoderInventoryLoaded = false` and keeps the
`pending*UntilInventory` requests, so nothing is normalized or persisted. It then schedules a bounded
re-load (on resume, or a few backed-off attempts), and REC/HEIF show a "temporarily unavailable"
state instead of a structural one. Add a host test where `EncoderCaps` fails and then succeeds within
one ViewModel, and assert that the persisted `ExtraSettings` keep HEIF and the log transfer.

---

## CT6-2 — The Output row lost "Camera reconfiguring…" for ordinary reopens (AGG5-55 over-narrowed)

**Severity** Medium. **Confidence** High. **Status** confirmed from code.

**Cites:** `ui/controls/PhotoFormatChips.kt:80, 101-103`; `ui/controls/ProSheet.kt:885`;
`camera/CameraStatus.kt:289-295` (`CAMERA_REOPEN_CONDITION_MESSAGES`); `ui/CameraScreenPolicy.kt:690-697`
(`railChipState`); `docs/ARCHITECTURE.md:150`.

**Why it is a problem.** After `63b8301d`, the caption reads "Camera reconfiguring…" only when
`state.cameraCondition` is one of `CAMERA_RECONFIGURING`, `CAMERA_UNAVAILABLE_RETRYING`,
`CAMERA_ERROR_RECOVERING`, `PREVIEW_UNAVAILABLE_RETRYING`, or `PREVIEW_INTERRUPTED_RECOVERING`. Those
conditions come from different places:

- The four retry/recovery messages are published only by the retry, recovery, and preview-health
  paths (`CameraEngine.kt:1067, 2471, 3739, 4422, 7279`).
- `CAMERA_RECONFIGURING` is published only as a refusal to an operator action. That covers the
  shutter while not Ready (`CameraEngine.kt:5202`), REC admission (`:6308, 6535, 6636, 6840`), and
  Custom WB (`CameraViewModel.kt:2406`).
- No optics door publishes a condition when it starts an ordinary reopen. That includes `setRawWanted`,
  aspect, fps, hi-res, lens preset, mode switch, and TC.

So during an ordinary reopen, `cameraCondition` is null, `reopenInProgress` is false, and the caption
is null. The old KDoc this commit rewrote named that case as the reason the caption exists: "says
'Camera reconfiguring…' … for the length of every aspect/fps/lens reopen". The row now gives no
explanation for a not-Ready window in the common case and shows the caption only in the rare one.
That is the reverse of AGG5-55's intent, which was to drop the false claim in cold start, pause, and
terminal failure and keep the true one.

Two more inconsistencies follow:

- `railChipState` still reports `CAMERA_RECONFIGURING` for any `!cameraReady`. That includes cold
  start and the terminal error, which are the states AGG5-55 called false. Two surfaces now disagree
  about the same state.
- `docs/ARCHITECTURE.md:150` still documents the pre-fix behaviour: "a reopen (`!cameraReady`) keeps the
  request live under 'Camera reconfiguring…'".

**Failure scenario.** In Photo, the operator taps DNG. `setRawWanted` re-resolves the route and the
session goes Not-Ready for ~1 s. Under the format chips the row is blank while the chips stay enabled
over cleared outputs. If the operator happens to press the shutter in that window, the refusal makes
"Camera reconfiguring…" appear, and it stays until Ready.

**Suggested fix.** Publish an optics-family PROGRESS condition (`CAMERA_RECONFIGURING`) at the start
of each optics transaction that clears Ready. It should end through the existing Ready/rollback
`clearProgress` path and stay ranked below events, so it does not chatter on the plate. Alternatively,
derive `reopenInProgress` from Engine truth: a pending optics generation newer than the last Ready
publication. Use the same predicate for `railChipState`, and update the ARCHITECTURE row. Add a test:
optics door → Not-Ready publication → caption is `status_camera_reconfiguring` without any shutter
press.

---

## CT6-3 — A shutter refusal is a timer-less condition that outlives a dead camera

**Severity** Low-Medium. **Confidence** Medium-High. **Status** confirmed from code (plate behaviour is
older; the Output-row half is new with AGG5-55).

**Cites:** `camera/CameraEngine.kt:5197-5203` (`capturePhoto` → `CAMERA_RECONFIGURING` when
`currentAcceptedCameraSession() == null`); `camera/CameraStatus.kt:202-209` (`CAMERA_RECONFIGURING` is
`CameraStatusLifecycle.PROGRESS`, `durationMs = null`); `camera/CameraStatus.kt` `StatusPlate.publish`
/ `expire`; `ui/CameraViewModel.kt:3685-3690` (`fireShutterWithFeedback`, no ready guard; hardware
keys reach it).

**Why it is a problem.** A PROGRESS status has no timer by design, and "an EVENT ends it": Ready,
rollback, pause, or a terminal. The `CAMERA_RECONFIGURING` *refusal*, however, can be published when no
such event will follow. After the bounded retry is spent, the Engine publishes
`CAMERA_UNAVAILABLE_REOPEN` / `PREVIEW_UNAVAILABLE_REOPEN`, which is a 6 s ERROR event and the
terminal. If the operator then presses the hardware shutter (KEYCODE_CAMERA, half-press, or quick
button bound to SHUTTER), `capturePhoto` finds no accepted session and publishes the PROGRESS refusal.
`StatusPlate.publish` defers it behind the unexpired ERROR, and `expire` hands it the plate when the
6 s run out. From then on "Camera reconfiguring…" stays indefinitely over a camera that will not come
back until the app is reopened. With AGG5-55, `cameraCondition` now holds it too, so the Output row
also says "Camera reconfiguring…". That is the terminal state AGG5-55's KDoc says the row must not
claim.

**Failure scenario.** The camera is held by another app, and retries exhaust with "Camera unavailable.
Reopen the app." The operator presses the camera key twice. Six seconds later the plate and the
Output row both read "Camera reconfiguring…", which contradicts the terminal instruction.

**Suggested fix.** Publish the refusal as a short EVENT, for example a dedicated
`CAMERA_NOT_READY` response in `RESPONSE_MESSAGES` with the 2.5 s default, instead of reusing the
condition message. Alternatively, drop a deferred `CAMERA_RECONFIGURING` when the plate holds a
`CAMERA_TERMINAL_MESSAGES` event. Add a plate test: terminal REOPEN → refusal → expire → plate is
empty and `condition` is null.

---

## CT6-4 — A recall rollback splits the stab/fps request from the wire and the screen (MRG5-8 half-closed)

**Severity** Low-Medium. **Confidence** Medium-High. **Status** confirmed from code.

**Cites:** `ui/CameraViewModel.kt:1165-1176` (rollback restores `requestedVideoStabMode` /
`requestedVideoFrameRate` only); `:1728` `engine.setVideoStabMode(e.videoStabMode)` and `:1748`
`engine.setVideoFrameRate(safeFrameRate)` (pushed by `applyLoaded` outside the optics transaction, so
the Engine rollback packet does not carry them); `:1784, :1804` (displayed `videoStabMode` /
`videoFrameRate` set to the bank's); `:3427-3470` (`reconcileZoomToCaps` now derives the DISPLAYED
stab from the request and no longer pushes the Engine — `dd3edc64` removed that push).

**Why it is a problem.** Before MRG5-8, a rolled-back recall left the bank's stab/fps in all three
places: request, display, and Engine. That was consistent, and the known AGG4-10 "non-optics fields
not undone" behaviour. MRG5-8 restores only the request. After a rollback:

- The Engine keeps the bank's stab mode and frame rate, and the HAL gets them.
- `_state.videoStabMode` / `videoFrameRate` keep the bank's values, so the UI shows them.
- `requestedVideo*` holds the prior values, which `currentExtras` persists.

The next caps publication then makes it worse. `reconcileZoomToCaps` sets the *displayed* stab from
the prior request but does not push the Engine any more. The screen shows the prior mode (for example
"Active") while the Engine and HAL keep the bank's mode (for example OFF). For fps,
`reconcileFrameRate` does push `applyVideoFrameRate(prior)`, so the wire jumps silently at an
unrelated caps change. Until one arrives, clips record at the bank's rate while the persisted request
says otherwise.

**Failure scenario.** The operator is in Video, TELE, Active, 60 fps. They recall MR2 (OFF, 30 fps),
whose recalled lens/route is refused, so the optics roll back. The plate says the camera is unchanged.
The Fn tile shows OFF/30 and the next clip records OFF/30, but a relaunch restores Active/60. If a lens
round-trip happens first, the Fn tile reads Active while the HAL stays on OFF, and the 300 mm clip is
recorded without OIS+EIS while the UI claims it.

**Suggested fix.** When the rollback restores a request, also re-apply it to the wire and the screen:
call `engine.setVideoStabMode(requestedVideoStabMode)` and set the displayed value through
`normalizedForAvailableModes(caps)`, then call `reconcileFrameRate()`. Better, make the
`reconcileZoomToCaps` display write and an Engine push one helper, so the display can never derive
from a request the Engine was not given. Add a Robolectric test: recall → rollback → assert Engine
stab/fps == displayed == persisted, before and after a caps republish.

---

## CT6-5 — The FrameGap evidence reserve does not preserve the evidence it exists for

**Severity** Low (debug diagnostics; but FIELD_CHECKS A6 and the S4a soak read these rows as
pass/fail). **Confidence** High. **Status** confirmed from code.

**Cites:** `camera/DiagnosticTelemetry.kt:213-241` (`FrameGapAccumulator.record` / `takeSummary` reset
the window whether or not the row is admitted); `gl/GlPipeline.kt:902-920` (periodic summaries use
`recurringDiagnosticAllowed`, so a refusal drops the row after the reset); `:1805`
(`finish()` → terminal summary); `docs/ARCHITECTURE.md:72`, `CLAUDE.md` (log-quota bullet:
"a missing terminal FrameGap line reads as 'no stall above the threshold'").

**Why it is a problem.** `715ccb0a` carved a 12-row evidence reserve so that "a chatty soak must not
be able to silence" the terminal FrameGap line. The accumulator, however, is windowed:

- Each due `record()` calls `takeSummary()`, which zeroes `count` / `maximumMs` / the buckets, and
  then `emitFrameGapSummary` is refused once the shared 168 rows are gone. That window's stalls are
  lost for good.
- `finish()` reports only stalls since the last periodic boundary, and returns `null` when that tail
  window has none, so no terminal line is emitted at all.

So in exactly the case the reserve was built for (shared rows drained), a session with real ≥1 s
wedges can end with no terminal line, or with one that shows only the last ≤15 s. A missing or small
line is then read as "no stall", which is the false pass the KDoc says the reserve prevents.

**Failure scenario.** A ten-minute A5/A6 soak with change-gated producers spends the shared rows by
minute 6. A 1.4 s wedge at minute 8 is accumulated, taken at the next 15 s boundary, and refused. At
GL stop the tail window is empty, so `finish()` returns null. The operator finds no FrameGap line and
records "zero stalls > 200 ms".

**Suggested fix.** Reset the window only after the row is admitted. If a periodic emission is
refused, keep accumulating (or fold it into a cumulative total), so the terminal summary carries
every unreported stall. Optionally emit `count=0` explicitly at terminal, so absence never means
"zero". Pin this with a test: shared budget exhausted → periodic refused → terminal reports the
refused window's count and maximum.

---

## CT6-6 — A failed delete marker can still close still admission for the process (AGG5-2 residual)

**Severity** Low. **Confidence** Medium. **Status** needs manual validation (reachability of an
older live capture after >32 newer terminals).

**Cites:** `camera/RetainedStillDeletionOwner.kt:112-135` (`completeDeletionDurability` closes
admission for an id not in `producerTerminalIds`; `markCaptureProducersTerminal` caps that set at
`maxTombstones` oldest-first); `camera/CameraEngine.kt:8392` (`MAX_RETAINED_STILL_DELETE_TOMBSTONES =
32`); `ui/CaptureOutputTracker.kt:253-264` (a pinned review capture keeps `liveStillCaptureId`
outside the 8-entry history).

**Why it is a problem.** MRG5-5 introduced `producerTerminalIds` to answer "can this id still produce?"
independently of the family registry. But it is bounded at 32 and evicted oldest-first. Take an id
whose producers finished long ago and whose terminal record has been pushed out by 32 newer terminal
captures. If that id's durable marker fails, `completeDeletionDurability(id, false)` adds it to
`nonDurableLiveDeletions`. Its producer edge already fired and will never fire again, and no later
durable marker exists for it. `canAdmitCapture()` is then false until process death, which is the
AGG5-2 symptom ("still capture dead until process restart") through a different door. The tracker
can hand the Engine such an id: a review-pinned capture keeps `liveStillFamilies` membership beyond
the ordinary history while timelapse or repeated BURSTs (5 ids each) produce more than 32 newer
captures.

**Failure scenario.** The operator opens review on capture N while a timelapse keeps running, or
returns to a pinned review after several bursts. The disk is near full, so the family marker commit
fails. Every later still is refused for the rest of the process.

**Suggested fix.** Treat "older than the oldest retained `producerTerminalIds` entry and not
registered as live" as terminal, since ids are monotonic. Alternatively, keep a monotonic
`highestTerminalWatermark` alongside the set. Or have the ViewModel pass the tracker's knowledge that
the capture's outputs are all recorded. Add a test with 33 terminal ids and then a failed marker on
the first one: admission stays open.

---

## Info (no ID; for the doc lane)

- `docs/ARCHITECTURE.md:150` describes pre-AGG5-55 Output-row behaviour (covered in CT6-2).
- The `CAMERA_REOPEN_CONDITION_MESSAGES` KDoc says it is "the reopen/recovery conditions the Output row
  calls reconfiguring". As CT6-2 shows, no plain reopen publishes one of them.
