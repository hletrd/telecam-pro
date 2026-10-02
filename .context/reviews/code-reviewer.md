# RPL cycle 5 — code-reviewer (CR5-)

Scope: logic correctness, races, state consistency, wrong-scale math, missed edge cases. Most of the
time went to the 86 cycle-4 commits (`887d39fb..ea7d4374`), because new code is where new defects
are most likely. The owner-decided items in CLAUDE.md (ZSL dark refusal, FocusDetail threshold,
CameraUnit, proprietary HDR, orientation, shader code 3, framework-internal `CAMERA_ERROR(3) -38`)
are not re-reported. Items already tracked in `docs/plans/2026-10-02-rpl-cycle4.md` are cited only
when there is new evidence.

Read-only review: no source, test, doc, or plan file was changed, and Gradle was not run.

## Inventory (review-relevant files for this angle)

- Engine/controller: `camera/CameraEngine.kt` (cycle-4 diff in full, plus `reconfigureCamera`,
  `reopenForSession`, `scheduleColdStartRetry`, `setResolvedOptics`, `setFrontCamera` return leg,
  the still dispatch chain, and the DNG pre-allocation wiring), `camera/CameraController.kt`
  (cycle-4 diff: `controlsApplyPlan`, `ChainCharacteristicsReread`, the vendor session-type seam,
  and `acceptedPhotoSessionOutputs`), `camera/DngPreCaptureAllocation.kt`,
  `camera/StillPublicationDispatcher.kt`, `camera/FamilyDeletionMarkerDispatcher.kt`,
  `ProcessAdmissionSignal.kt`, `camera/CameraStatus.kt` (`StatusPlate`).
- State/math: `camera/CameraState.kt` (`rearReturnZoom`/`rearReturnLens`, `opticalBaseFor`,
  `unifiedZoomOf`/`localZoomOf`, `effectiveEquivFocalMm`, `dngOnlySubstitution`,
  `backOpticsDoorRefusal`), `camera/ManualControls.kt` (`normalizedFor` aeLock/afLock,
  `effectiveExposureNs`, `withShutterMode`, `manualAeAdmitted`, `controlsApplyPlan`),
  `ui/ZoomMath.kt` (whole file).
- ViewModel: `ui/CameraViewModel.kt` (cycle-4 diff in full, `applyLoaded`, the optics-rollback
  leg, `applyEncoderInventory`, `currentExtras`, status arbitration and timers, hardware-key
  dispatch, momentary holds, the app-side AE loop, `onStop`), `ui/MomentaryHold.kt`,
  `AudioDenialReason.kt`, `ui/controls/ManualDials.kt` (ZoomRuler and the focus loupe assist).
- Capture/video/storage: `capture/HeifExif.kt`, `capture/StillCapturePipeline.kt` (JPEG splice
  lanes, passthrough, DNG write), `capture/StillSnapshot.kt`, `video/VideoRecorder.kt` (drain
  loops, the audio worker, muxer rendezvous, PCM helpers), `storage/MediaStoreWriter.kt` (moov
  walk, `classifyFinalizedVideoTrack`, `recoveryVideoVerdict`, the re-arm policy, orphan
  disposition, the recovery age gate), `storage/SettingsStore.kt` (`loadWithPrefix`),
  `storage/LatestCaptureReducer.kt` (header and rules).

## Findings

### CR5-1 — An asynchronously rolled-back pre-inventory recall still applies and persists its codec, transfer, and HEIF/JPEG choice

- Severity: Low–Medium (silent settings corruption; narrow window) · Confidence: High · Status:
  Confirmed (code path)
- Cites: `ui/CameraViewModel.kt:1516-1521` (arming), `ui/CameraViewModel.kt:963-1050` (the
  `onOpticsRollback` leg: only `dngRaw` is mirrored back into the pending formats at `:1036-1042`,
  then `scheduleSettingsSave()` at `:1047`), `ui/CameraViewModel.kt:1660-1708` (`currentExtras`
  persists the pending trio while `!encoderInventoryLoaded`), `ui/CameraViewModel.kt:2816-2846`
  (`applyEncoderInventory` replays them into the Engine).
- Why: AGG4-11 moved the pending-inventory arming below the two SYNCHRONOUS refusal exits of
  `applyLoaded`. An accepted recall can still fail later, and the Engine then rolls back. The
  rollback leg restores `videoCodec`/`transfer` in `_state` when `pipelineOwned`, and mirrors only
  `dngRaw` into `pendingPhotoFormatsUntilInventory`. It never restores
  `pendingCodecUntilInventory`, `pendingTransferUntilInventory`, or the pending HEIF/JPEG flags.
  While the inventory is pending, `currentExtras()` prefers those pending fields over `_state`.
- Failure scenario: a launch, then an MR recall (codec AVC, transfer S-Log3, JPEG-only) before
  `EncoderCaps.load()` lands. The recall's optics transaction fails on `setupExecutor` (for example
  `CAMERA_UNAVAILABLE_RECALL_UNCHANGED`), and the rollback restores HEVC/HLG/HEIF in the UI. Then:
  (1) the rollback's own `scheduleSettingsSave()` persists AVC/S-Log3/JPEG-only. (2) When the
  inventory lands, `applyEncoderInventory` calls `engine.setVideoPipeline(... AVC, S-Log3)` and
  publishes JPEG-only. The bank the operator was told did not load now owns the live pipeline and
  the saved settings. This is the AGG4-11 symptom, reached through the asynchronous door instead of
  the synchronous one.
- Fix (host-testable): in `applyLoaded`, snapshot the three pending fields before arming them, and
  carry that snapshot with the recall's optics generation. In the rollback leg, when the rollback
  owns the generation, restore all three from the snapshot (keeping the existing live-`dngRaw`
  mirror on top). A simpler fix: when `pipelineOwned`, set `pendingCodecUntilInventory` and
  `pendingTransferUntilInventory` back to `rollback.videoCodec` and `rollback.transfer` (when they
  are non-null), and the pending formats back to the pre-recall pending value. Test: a
  pre-inventory recall, then an owned rollback, then the inventory landing. Assert that the Engine
  pipeline and the `currentExtras()` codec, transfer, and HEIF/JPEG equal the baseline.
- PMA110: changes only the failed-recall path. A successful recall is byte-identical.

### CR5-2 — A momentary PUNCH_IN hold across a Focus-ruler close leaves the loupe latched on and persisted

- Severity: Low · Confidence: High · Status: Confirmed (code path)
- Cites: `ui/CameraViewModel.kt:3443-3446` (press snapshots `_state.value.punchIn`),
  `ui/CameraViewModel.kt:3354-3365` (`onAutoPunchIn(false)` writes `punchIn = false` and does not
  touch the momentary hold), `ui/controls/ManualDials.kt:255-264` (the assist), and
  `ui/CameraViewModel.kt:1701-1705` (persistence once both owners are cleared).
- Why: two owners of the same `punchIn` field, the focus-ruler assist (`autoPunchInActive`/
  `punchInBeforeAuto`) and `momentaryPunchIn`, do not know about each other. The press snapshots
  the LIVE value, which during the assist is the assist's `true`, not the operator's
  `punchInBeforeAuto = false`.
- Failure scenario: with punch-in off, open the Focus ruler (the assist turns the loupe on). Hold a
  key bound to PUNCH_IN (snapshot = `true`). Close the ruler: `onAutoPunchIn(false)` turns the loupe
  OFF while the key is still held. Release the key: `release()` restores the snapshot `true`. The
  loupe is now latched on with neither owner active, so the next save writes `punchIn = true`. One
  light press during focusing turns a loupe the operator never enabled on, and it persists across
  launches. This is the AGG4-74 symptom class through a different owner.
- Fix (host-testable): snapshot the operator's value on press
  (`if (autoPunchInActive) punchInBeforeAuto else punchIn`). In `onAutoPunchIn(false)`, if
  `momentaryPunchIn` is holding, keep the loupe on and let the hold's release restore the operator
  value. Alternatively, have the assist's close cancel the hold and restore `punchInBeforeAuto`.
  Test: assist on, then press, then assist off, then release. Assert that `punchIn` and the
  persisted value equal the pre-assist operator value.
- PMA110: changes only this key/ruler interleaving.

### CR5-3 — The GL-input-missing branch of `reconfigureCamera` still parks a bare door Not-Ready (sibling of AGG4-2)

- Severity: Low · Confidence: Medium · Status: Likely (reachability needs a GL-replacement timing
  window)
- Cites: `camera/CameraEngine.kt:4331-4346` (input-null branch: `rollbackOptics(...
  PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED)` when `!startup && controller != null`),
  `camera/CameraEngine.kt:3535-3536` (`reopenForSession` checks the input, then calls
  `invalidateCameraReady()` before queueing), `camera/CameraEngine.kt:1026-1070`
  (`handlePreflightFailure` BARE_RETRY covers only the selection/caps preflight failures).
- Why: AGG4-2 recognised that a bare `reopenForSession()` transaction
  (`baselinePrecedesMutation = false`) cannot be honestly rolled back to Ready. It routed the
  selection/caps preflight failures through the bounded retry, and MRG4-3 added a REOPEN terminal.
  The input-surface branch a few lines above still takes the plain `rollbackOptics`. For a bare door,
  `reopenForSession` has already invalidated Ready, so the rollback cannot restore Ready. It
  publishes a condition-ENDING "preview unavailable, camera unchanged" status. No retry is
  scheduled, so the app sits Not-Ready over a still-streaming session until an unrelated door
  reopens it. That is exactly the park AGG4-2 set out to remove.
- Failure scenario: a stabilization or frame-rate change (a bare door) is queued. Before the
  `setupExecutor` task runs, the GL generation's input surface is gone, and no new input is pending
  yet (a GL replacement window). The door rolls back Not-Ready with no convergence path.
- Fix (host-testable): route this branch through the same disposition table
  (`preflightFailureDisposition(recoverColdPreflight = startup || controller == null,
  transaction.baselinePrecedesMutation)`). Map BARE_RETRY to `scheduleColdStartRetry(...
  PREVIEW_UNAVAILABLE_RETRYING)` with the MRG4-3 BLOCKED→REOPEN fallback. Extend the existing
  disposition test to cover the input-missing case.
- PMA110: no change on any path that currently reaches Ready.

### CR5-4 — `ChainCharacteristicsReread` is one controller-wide flag; a chain head that never reaches the completion read leaves it armed for a later continuation

- Severity: Info · Confidence: Medium · Status: Confirmed (benign today)
- Cites: `camera/CameraController.kt:2042` (`if (chainHead) chainCharsReread.arm()`),
  `camera/CameraController.kt:2308` (`chars(shot = chainCharsReread.consume())`),
  `camera/CameraController.kt:2569-2575` (class).
- Why: a ZSL-served SINGLE head, or a head that fails before its completion, arms the flag and never
  consumes it. The next chain's first completion consumes it anyway, and a head always re-arms, so
  the only effect is that a later continuation frame may spend the bypass instead of the head. With
  one `pending` capture slot this is harmless, but the "exactly the first completion of each chain"
  wording in the KDoc is not literally true.
- Fix: consume (disarm) on the serve and failure exits too, or reword the KDoc. No behaviour change
  is needed.

### Already tracked (new evidence only)

- AGG-12 (app-side AE lock): `ManualControls.normalizedFor` (`camera/ManualControls.kt:263-265`)
  drops `aeLock` unless the session is HAL-AE PROGRAM. The new AGG4-74 momentary AEL binding is
  therefore a silent no-op in photo PROGRAM on PMA110 (app-side by default), and in S, ISO, and M.
  The hold mechanics are correct; the lock they hold does not exist on those routes. Still tracked
  under AGG-12.
- MRG4-9 residual: confirmed that `writePassthroughJpeg` →
  `heifExifDimensionAttributes` `require(width > 0)` throws when `inJustDecodeBounds` fails, so the
  frame saves without EXIF (`capture/StillCapturePipeline.kt:398-420`, `capture/HeifExif.kt:58`).
  Still tracked.

## Checked and found correct (no finding)

- `StatusPlate.publish/expire/clearProgress` and their VM call sites: ranks, the deferral, the
  condition-ending set, timer re-arming on `shownChanged`, and the expiry handing back the deferred
  PROGRESS. No caller publishes `null` except the launch restore, which ran before cycle 4 too.
- The `DngPreCaptureAllocation` cancel-before-start latch with `StillContinuationHandoff`: exactly
  one continuation in both orders; `Lease.release` and `releaseDngAdmission` are idempotent.
- `transferCompletedDngFromCameraCallback` DIRECT re-mapping. The queue sizing in
  `FamilyDeletionMarkerCapacityOwner` matches the semaphore. `ProcessAdmissionSignal.refresh` lock
  order is signal → owner.
- `probeMp4MoovPresence`: the size 0, size 1/largesize 0, and overrun placeholders of a killed
  `MPEG4Writer` all prove ABSENT. A half-written `moov` (size 0 → "to EOF") reads PRESENT, which is
  the conservative direction (retain). The live-row exposure of recovery is closed by the
  process-start age gate (`storage/MediaStoreWriter.kt:1300-1307`).
- `exifSplicePlan`/`writeJpegWithExifApp1`: writing APP1 before APP0 matches what ExifInterface's
  own save produces. Fill bytes and RST/TEM are handled. `exifApp1WithoutThumbnailIfd` bounds
  checks are sound.
- `rearReturnZoom`/`rearReturnLens`: Engine and VM agree on the logical, standalone, UW-local, and
  one-camera cases. `remapModeOptics`/`remapRouteScaleOptics`/`retainedRearWireZoom` round-trip.
- `zoomRulerScale`: display, fraction, and local round-trip, the TELE cap, and degenerate ranges.
  `zoomDisplayMultiplier` is 1 on lens-local routes.
- `controlsApplyPlan` NO_OP: under admitted manual AE no wire key carries EV, and the stored
  controls still feed the next rebuild and every still.
- `cameraRoutePublishedState` / `applyResolvedCameraRoute` same-route early return. The zoom-ease
  double-chain fix (`removeCallbacks` before `post`).
- `packYuv420ToNv21` bulk paths. `audioPtsUs`, `applyPcmGain`, peak and RMS helpers.
  `SettingsStore` per-field clamps (`jpegQuality` is unclamped at load but clamped at both uses,
  `ManualControls.kt:689` and `CameraEngine.kt:5676`).

## Final sweep (commonly missed)

- Float equality and NaN: zoom inputs are finite-guarded at load, ease, restore, and ruler.
  `effectiveEquivFocalMm` guards a non-positive equiv.
- Integer fps in `effectiveExposureNs` for NTSC rates: an error of about 0.1%, negligible, so no
  finding.
- Unbalanced edges: hardware keys dispatch both edges, `hardwareActionAdmitted` gates only SHUTTER,
  and `onStop` ends both momentary holds. Remaining gap: CR5-2.
- Lock order: no new monitor nesting beyond the documented signal → owner order.

## Files covered

`camera/CameraEngine.kt`, `camera/CameraController.kt`, `camera/CameraState.kt`,
`camera/ManualControls.kt`, `camera/CameraStatus.kt`, `camera/DngPreCaptureAllocation.kt`,
`camera/StillPublicationDispatcher.kt`, `camera/FamilyDeletionMarkerDispatcher.kt`,
`camera/CaptureCapabilities.kt` (focal derivation), `ProcessAdmissionSignal.kt`,
`AudioDenialReason.kt`, `HardwareInputPolicy.kt`, `MainActivity.kt` (deslop diff),
`ui/CameraViewModel.kt`, `ui/MomentaryHold.kt`, `ui/ZoomMath.kt`, `ui/controls/ManualDials.kt`
(ZoomRuler and the loupe assist), `ui/CameraScreen.kt` (zoom indicator), `capture/HeifExif.kt`,
`capture/StillCapturePipeline.kt`, `capture/StillSnapshot.kt`, `video/VideoRecorder.kt`,
`storage/MediaStoreWriter.kt`, `storage/SettingsStore.kt`, `storage/LatestCaptureReducer.kt`
(header and rules).
