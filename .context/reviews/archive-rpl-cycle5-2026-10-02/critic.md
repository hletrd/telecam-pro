# RPL cycle 5 — critic review (CT5-)

Reviewer: critic (multi-perspective challenge of the cycle-4 change surface, 887d39fb..ea7d4374).
Inputs: CLAUDE.md, docs/ARCHITECTURE.md, docs/plans/2026-10-02-rpl-cycle4.md (later-cycle,
carried, and Deferred lists consulted so tracked items are not re-reported as new), and the main-source
diffs of every cycle-4 `fix`/`perf`/`refactor` commit. Read-only; no Gradle run.

## Summary

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 0 |
| Medium | 2 |
| Low | 5 |
| Info | 2 |

Most cycle-4 fixes do what their plan items say. I found no fix that only touches a test or a comment
while claiming a behavior change. The two Medium findings come from cycle-4 storage changes that
interact: MRG4-4 combined with AGG4-4 (bounded re-arm), and AGG4-28 combined with AGG4-4. Each can
leave a complete take pending without re-arming it, so MediaProvider's expiry deletes it. That is the
loss class AGG3-4 and AGG4-5 set out to close.

## Findings

### CT5-1 — A moov-present take whose extractor throws once is no longer re-armed and can expire (MRG4-4 regression of AGG2-18)

- Severity: Medium. Confidence: Medium. Status: Likely (the transient class is documented in-tree).
  Whether the provider actually expires the row needs device validation (FIELD_CHECKS E4).
- Cites: `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt` `recoveryVideoVerdict`
  (the `Mp4MoovPresence.PRESENT -> PendingProbe.INDETERMINATE` arm added by 93c43931);
  `pendingProbeOutcome` (`failed = result.isFailure`, ~:2367); `keptRowReassertsPending`
  (`journalState != DISCARD && probeOutcome.failed`); the recovery loop's `KEEP_PENDING` arm (~:1450).
- Why: MRG4-4 calls "extractor threw, but the walk found a complete `moov`" a constant of the bytes.
  The same file says otherwise. The `classifyFinalizedVideoTrack` KDoc (AGG2-18) says
  `MediaExtractor.setDataSource` on a MediaProvider FUSE fd throws the same
  `IOException("Failed to instantiate extractor")` for a transient provider/FUSE failure (media scan,
  MediaProvider restart mid-read, revoked fd) as for a corrupt container. The walk runs afterwards and
  uses a different read path (`FileChannel` positional reads on the same descriptor). When it succeeds,
  that only shows the bytes were readable then. It says nothing about why the extractor failed a moment
  earlier.
  `recoveryVideoVerdict` now *returns* INDETERMINATE for this case instead of throwing. As a result
  `probeOutcome.failed == false`, `keptRowReassertsPending` is false, and the row is kept with no
  re-arm. The take is the best-formed one recovery ever sees (finalized `moov`), and it is now the
  one left to run down its original `DATE_EXPIRES`.
- Failure scenario: a clip whose `COMPLETE` commit failed (fail-closed retained take, REGISTERED;
  the UI promised a recoverable take) or a crash after the muxer stopped. The next launch's recovery
  runs while MediaProvider is busy (for example a post-boot media scan), and the extractor throws
  once. The walk then finds `moov` and the row is kept without a re-arm. If no later launch happens
  before the insert-time expiry, about a week after capture, or the same transient repeats on that
  launch, idle maintenance deletes a playable take. The user never saw it, because pending rows are
  hidden.
- Suggested fix (host-testable): a deterministic verdict must come from an extractor result that
  repeats. Before returning INDETERMINATE-not-failed, re-run `hasVideoTrack` once on a FRESH
  descriptor, as `classifyFinalizedVideoTrack` already does for the live tail. Only a second throw
  over a PRESENT walk is a byte constant. A simpler bound also works: keep re-arming
  `PRESENT`-and-throw rows, but cap it with a per-row re-arm counter in the discard journal (for
  example 3 launches). Then the immortal-row concern MRG4-4 addressed stays bounded without
  converting a transient into a death sentence. Pure test: extractor throws on the first descriptor
  and parses on the second; expect VALID/ADOPT.
- PMA110 impact: changes only which kept rows get `IS_PENDING=1` re-written. Capture behavior is
  unchanged.

### CT5-2 — A durably COMPLETE row with provider SIZE <= 0 can now expire instead of being adopted (AGG4-28 + AGG4-4 interaction)

- Severity: Medium. Confidence: Medium. Status: Needs device validation (whether OEM MediaProvider
  leaves SIZE at 0 for a pending row after process death is unverified, which the code comment itself
  admits).
- Cites: `MediaStoreWriter.kt` recovery loop
  `journalState == PendingJournalState.COMPLETE && sizeBytes > 0L -> PendingProbeOutcome(VALID)`
  (9344c2fa); `orphanDisposition` COMPLETE arm `if (probe == VALID) ADOPT else KEEP_PENDING`;
  `keptRowReassertsPending` KDoc, which explicitly lists "a probed non-VALID zero-size COMPLETE row"
  as kept but NOT re-armed.
- Why: `COMPLETE` is the app's own durable proof that the encoder/muxer output was fully closed.
  CLAUDE.md: "Recovery also adopts durable `COMPLETE` rows". Before cycle 4 a COMPLETE row was adopted
  whatever its SIZE. Now provider SIZE <= 0 sends it through the format probe. Any deterministic
  INDETERMINATE answer then leaves the take kept and never re-armed: a HEIF layout outside the probe's
  supported `meta`/`pitm`/`iloc` variants, an unknown MIME, or the CT5-1 moov-present throw. The doubt
  here is only provider metadata, not the bytes, so the app's strongest completeness evidence is
  overridden by its weakest. CLAUDE.md's sentence about adopting durable COMPLETE rows is now only
  conditionally true.
- Failure scenario: suppose a provider that does not refresh SIZE for pending rows reports 0 for
  every COMPLETE row. Then every COMPLETE HEIF/JPEG/DNG/MP4 depends on its structural probe. Any
  undecidable-but-complete file expires silently about a week after capture.
- Suggested fix: decide on the descriptor's real length (`statSize`, which the DNG probe already
  reads) rather than provider SIZE. A COMPLETE row whose opened descriptor has a positive length is
  adopted on the marker, as before. A COMPLETE row the probe keeps should always re-arm, since the
  marker is a strong adoptability signal: change `keptRowReassertsPending` to
  `journalState == COMPLETE || probeOutcome.failed` (minus DISCARD). Fake-resolver test: COMPLETE,
  SIZE = 0, descriptor length > 0, HEIF probe INDETERMINATE; expect ADOPT, or at minimum a re-arm.
- PMA110 impact: none on capture. Recovery only.

### CT5-3 — The AGG4-9 recall restore of denial-silenced audio switches off the lit memory slot and forces an immediate save

- Severity: Low. Confidence: High. Status: Confirmed (code path).
- Cites: `app/src/main/kotlin/me/hletrd/telecampro/AudioDenialReason.kt` `MemoryBankAudioProvenance.afterRecall`
  → `restoreIfGranted(announce = false)` → `restoreAudio`;
  `MainActivity.kt` `memoryBankAudioProvenance.restoreAudio = { vm.onToggleRecordAudio(true) ... }`;
  `ui/CameraViewModel.kt:2624-2629`, `onToggleRecordAudio`:
  `_state.update { it.copy(recordAudio = enabled, activeMemorySlot = null) }` plus `saveSettingsIfEnabled()`.
- Why: A.4 (1218a635) reconciles the grant right after a recall. It does so through the operator's
  ordinary audio toggle, which clears `activeMemorySlot`. Recalling a bank whose silence was a denial
  consequence, while the microphone is now granted, therefore lights MRn and then switches it off in
  the same call stack. The "MRn loaded" status is shown with no slot lit. Yet restoring audio is
  exactly what the bank's provenance says the bank means. The host test uses a fake `restoreAudio`,
  so it cannot see this. This is a user-visible behavior change introduced by the fix.
- Failure scenario: bank stored after a mic denial, mic later granted in Settings, bank recalled.
  The status says "MR1 loaded", MR1 is not marked active, and settings are saved synchronously on that
  press.
- Suggested fix: give the recall leg a non-clearing write. Add a VM method that sets
  `recordAudio = true` and refreshes the standby meter while keeping `activeMemorySlot`. Use it for
  `announce = false`, and keep `onToggleRecordAudio` for the resume/grant announcement leg.
  Robolectric test: recall a denial-silent bank with permission granted; `activeMemorySlot == slot`
  and `recordAudio == true`.
- PMA110 impact: UI state only.

### CT5-4 — Recall-side audio provenance still lives only in the Activity wrapper, the failure class AGG4-49 fixed for the store side

- Severity: Low. Confidence: High. Status: Confirmed (structure).
- Cites: `ui/CameraViewModel.kt:3705` (`override fun onRecallMemorySlot`, public `CameraActions` entry);
  `MainActivity.kt:521` (the wrapper that alone runs `memoryBankAudioProvenance.afterRecall`).
- Why: AGG4-49's own rationale says that any caller reaching the ViewModel directly, or removal of a
  "redundant" wrapper, silently reverts recall to the provenance-blind rule. Cycle 4 moved the store
  side into the VM. The recall side still writes the denial reason, and reconciles the grant, only in
  the Activity's `CameraActions` override. Any other `CameraActions` binding misses both, for example
  a preview/test harness or a future Fn/hardware recall path that calls the VM. The production-dead
  `MemoryBankAudioProvenance.bankAudioOffByDenialNow` is already tracked (cycle-4 "later cycle"
  deslop note). The asymmetry is not tracked.
- Suggested fix: move `afterRecall` into the VM's `onRecallMemorySlot`. The VM already owns an
  `AudioDenialReasonStore`. Pass a permission-check lambda into the VM factory. Then delete the
  Activity override, as was done for store.

### CT5-5 — EXIF ExposureBiasValue is read from a result key the app-side AE request never carries

- Severity: Low. Confidence: Medium. Status: Needs device validation (depends on whether the HAL echoes
  the template default).
- Cites: `camera/CameraEngine.kt` (~:8142) `evBiasStops = (result.get(CONTROL_AE_EXPOSURE_COMPENSATION) ?: c.exposureCompensation) * evStep`;
  `camera/ManualControls.kt` (~:1076-1086) writes `CONTROL_AE_EXPOSURE_COMPENSATION` only in the HAL-AE branch;
  cycle-4 `controlsApplyPlan` KDoc (cc23de76) states that under manual AE the key is never written.
- Why: Under every AE-OFF mode, including app-side photo PROGRAM (the PMA110 default), EV only moves
  the loop's target. A.12 confirms the wire never carries it. A capture result normally echoes the
  request's value, which is the template default 0, not null. So the `?:` fallback to the operator's
  EV never fires, and every app-side still records ExposureBias 0 whatever EV was dialed. This predates
  cycle 4, but A.12 now documents the premise that makes it wrong.
- Suggested fix: when `manualAeAdmitted(c, caps)` held for the shot, take EV from the frozen shot
  controls instead of the result. Pure test on the ExifShot builder inputs.
- PMA110 impact: EXIF metadata only, no pixels.

### CT5-6 — The Quick Zoom ruler now inherits the display multiplier's nominal 23 mm divisor

- Severity: Low. Confidence: Medium. Status: Likely (the display path is pure; tablet readout unverified).
- Cites: `ui/ZoomMath.kt` `zoomDisplayMultiplier` (`equivalentFocalMm / LensChoice.MAIN.targetEquivMm`);
  `zoomRulerScale` (6e75d245); `ui/controls/ManualDials.kt` ZoomRuler call site.
- Why: A.23 aligns the ruler with the chip, which is right for PMA110. The shared multiplier divides
  by the nominal PMA110 main focal (23 mm), not the measured main lens. On a standalone route of a
  device whose main is 26–27 mm (TB336ZU/TB331FC per CLAUDE.md), the chip and now the ruler read
  "1.1×"/"1.2×" at the main lens's native position, while the lens rail calls that lens "1×".
  CLAUDE.md says the honest conversion divides by the optical lens the route reaches.
  `unifiedZoomOf`/`localZoomOf` do that; this multiplier does not. Pre-existing for the chip (DES4-1).
  Cycle 4 extended it to the edit surface, where a drag now writes `display / base` with the
  nominal base.
- Suggested fix: divide by `lensInventory`'s measured main equivalent when available, falling back
  to the nominal one. Table test with a 26 mm main.
- PMA110 impact: none (the measured main is about 23 mm).

### CT5-7 — The status plate can hold back an ASSERTIVE camera-error condition behind a lower-severity event

- Severity: Low. Confidence: High. Status: Confirmed (pure reducer); the impact is a design question.
- Cites: `camera/CameraStatus.kt` `plateRank` (`lifecycle == PROGRESS -> StatusPlateRank.PROGRESS` is
  evaluated before severity) and `StatusPlate.publish` (an incoming PROGRESS under any unexpired event
  becomes `deferredProgress`, `shownChanged = false`).
- Why: `CAMERA_ERROR_RECOVERING`, `CAMERA_UNAVAILABLE_RETRYING` and `PREVIEW_UNAVAILABLE_RETRYING`
  are severity ERROR with ASSERTIVE live priority, but lifecycle PROGRESS. So they rank lowest and
  wait behind any unexpired event, even a 1.5 s SUCCESS ("MR1 loaded") or a 2.5 s INFO. TalkBack's
  assertive announcement of a camera fault is delayed, and it is dropped entirely if Ready or a
  condition-ending event arrives first. Before C.9 a newer status always took the plate. This is a
  user-visible behavior change that the C.9 plan item (AGG4-65 was about events displacing errors)
  did not discuss.
- Suggested fix: rank a PROGRESS of ERROR severity at ERROR for arbitration purposes. It still has
  no timer and is still cleared by Ready. Alternatively, let PROGRESS displace events below WARNING.
  Reducer table test.
- PMA110 impact: status presentation only.

### CT5-8 — Info: the YUV aspect-first pick (A.17) uses exact equality against the active array, so it is a no-op on arrays that are not an exact ratio

- Severity: Info. Confidence: High. Status: Confirmed (pure).
- Cites: `camera/CaptureCapabilities.kt:655-672` `pickStillSize`; the cycle-4 YUV call site (e9f39680).
- Why: The "native" filter keeps only sizes whose ratio exactly equals the active array's. PMA110's
  logical array 4080×3064 is not exactly 4:3, so unless 4080×3064 itself is an advertised YUV size,
  `native` is empty and the pick falls back to largest-by-area, the AGG4-24 behavior. A device with a
  square YUV size larger than its 4:3 sizes and an odd array (for example 4000×3002) keeps the square
  still the fix targeted. For PMA110 this is good: behavior is likely byte-identical. It does mean the
  fix is narrower than its commit message suggests.
- Suggested fix (optional): also accept an exact 4:3 candidate when no array-exact one exists. Table
  test with an odd array plus a larger square.

### CT5-9 — Info: CLAUDE.md "Recovery also adopts durable `COMPLETE` rows" no longer holds unconditionally

- Severity: Info. Confidence: High. Status: Confirmed (doc vs code).
- Cites: CLAUDE.md, pending-MediaStore-rows bullet; `MediaStoreWriter.kt` `orphanDisposition` COMPLETE arm.
- Why: Since AGG4-28, a COMPLETE row with provider SIZE <= 0 is adopted only when the probe says
  VALID (see CT5-2). The authority document should state the condition, or CT5-2's fix should make
  the sentence true again.

## Cycle-4 claims checked and found to hold (no finding)

- A.1 (c1cd9bef): the VM no longer writes the engine-owned `stillCaptureAdmissionAvailable`. This is a
  behavior fix, not just a comment.
- A.2/M.2/M.3 (b1f7869e, 7f137b9b): the bare-door preflight failure now converges through the bounded
  retry, which re-enters `reconfigureCamera(startup = true)`. A refusal nothing else owns publishes
  `CAMERA_UNAVAILABLE_REOPEN`.
- A.3 (691943df): a same-camera Video recall with a different `chooseVideoSize` answer is structural.
  The SDR-to-10-bit transfer change is already covered by `commitFastPathOrReconfigure`'s
  `sessionTransferChanged`.
- A.5, A.6, A.7, A.8, A.10, A.18, A.19, A.24/M.6: the diffs match their plan text. The momentary
  holds snapshot on press, restore on release, are cancelled by an explicit toggle and by recall,
  and are released at onStop. The persisted value and MR store use the operator's value.
- B.1/B.2 walker (`probeMp4MoovPresence`): MPEG4Writer's crash layouts (size-0 or zero-largesize
  `mdat`, overrun) map to ABSENT, and a finalized front or tail `moov` maps to PRESENT. The only
  concern found is the PRESENT-and-throw disposition in CT5-1.
- B.4/M.7 splice: SOI + new APP1 + every non-Exif segment in original order. This matches
  ExifInterface's own ordering, so APP0-after-APP1 is not a regression. The thumbnail-tier fallback is
  correct.
- B.5 (9408fb35), B.6 (ac51a897), B.8 (56bb2269), B.9 (25abb2f2), A.9 (a36aa43b), A.11, A.13: the
  logic matches the stated invariant.
- C.9/M.1: every `rollbackOptics` status call site's message is in `CAMERA_CONDITION_ENDING_MESSAGES`.
  The two `scheduleColdStartRetry` terminals use `CAMERA_UNAVAILABLE_REOPEN`, which is also in the set.
- No cycle-4 `fix` commit was found to be test-only or comment-only while claiming a behavior change.
  c7953e52 is typed `test` but carries a 44-line production refactor of the engine's DNG
  settle-before-camera line. It is behavior-preserving by inspection, but the commit type understates
  it (Info, not filed).

## Final sweep (commonly missed)

- Threading: `readyDngOnlyAnnounced` is written only inside the main-posted Ready branch. The
  `deferredProgressStatus` writes all sit under `cameraReadyPublicationGate`'s status monitor. Holds.
- Lock order: `ProcessAdmissionSignal.refresh` reads owner state under the signal lock (signal →
  owner). Owners notify after releasing their own lock. No inversion found.
- Edge: `ChainCharacteristicsReread` stays armed after a chain head fails before completion. It is
  harmless, because the next head re-arms anyway.
- Edge: `exifApp1WithoutThumbnailIfd` bounds-checks the IFD0 entry count against the payload before
  zeroing the link.

## Files covered

`camera/CameraStatus.kt`, `camera/CameraEngine.kt` (preflight disposition, cold retry, recall
structural check, front leave, route resolution, DNG pre-capture, chain re-read call sites, mic-claim
status, EXIF builder), `camera/CameraController.kt` (controls apply plan, chars gate),
`camera/ManualControls.kt`, `camera/CameraState.kt` (effective focal, rearReturnLens, dngOnlySubstitution,
osdPhotoFormats, backOpticsDoorRefusal), `camera/CaptureCapabilities.kt`, `camera/DngPreCaptureAllocation.kt`,
`camera/StillPublicationDispatcher.kt`, `camera/FamilyDeletionMarkerDispatcher.kt`,
`storage/MediaStoreWriter.kt` (recovery loop, probes, walker, re-arm), `ProcessAdmissionSignal.kt`,
`capture/HeifExif.kt`, `capture/StillCapturePipeline.kt`, `gl/GlPipeline.kt` (orphan containment),
`video/VideoRecorder.kt` (validation docs), `ui/CameraViewModel.kt` (status plate, momentary holds,
recall/rollback, audio toggle, route fold, zoom ease, handheld rule), `ui/MomentaryHold.kt`,
`ui/ZoomMath.kt`, `ui/controls/ManualDials.kt`, `ui/controls/PhotoFormatChips.kt`,
`ui/controls/ProControls.kt`, `ui/overlays/Overlays.kt` (focal label), `AudioDenialReason.kt`,
`MainActivity.kt` (provenance wiring), `HardwareInputPolicy.kt`, CLAUDE.md cycle-4 edits,
docs/plans/2026-10-02-rpl-cycle4.md.
