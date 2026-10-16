# RPL cycle 4 — tracer review (HEAD 14767b0a, 2026-10-02)

Angle: causal tracing of five end-to-end flows (UI action → CameraViewModel → CameraEngine →
Controller/GL/StillCapturePipeline/VideoRecorder/MediaStoreWriter → callbacks → UI state), with
competing hypotheses resolved from the code. Read-only; no source edited. Known findings
(AGG3-1..63, cycle 1–3 plans) are excluded; cycle-3 fix commits were re-traced for regressions.

Paths are relative to `app/src/main/kotlin/me/hletrd/telecampro/` unless stated.

## Findings

### TR4-1 — A failed MR recall rolls back optics but leaves the slot lit, the bank's non-optics half applied, and the audio-denial reason rewritten

- **Flow 4 (MR recall, structural, async failure).** Hop chain:
  `MainActivity.onRecallMemorySlot` (`MainActivity.kt:530-548`) → `vm.recallMemorySlot` →
  `applyLoaded` (`ui/CameraViewModel.kt:1291`) → `engine.setResolvedOptics` returns true
  synchronously (`CameraViewModel.kt:1462`) → VM applies the trailing session setters
  (`engine.setVideoStabMode/setAspectRatio/setDriveMode/setHiResStill/setOpenGate/setAudio*/
  setVideoFrameRate`, `:1506-1528`) and writes the whole bank into state with
  `activeMemorySlot = activeSlot` (`:1596`) → Activity commits `AUDIO_OFF_BY_DENIAL_KEY` from the
  bank's provenance (`MainActivity.kt:538-546`) → later on `setupExecutor` the recall's
  transaction fails (`camera/CameraEngine.kt:2884` REC race, `:2894` camera unavailable, or a
  `reconfigureCamera` preflight/open failure) → `rollbackOptics` → `onOpticsRollback` →
  VM mirror (`CameraViewModel.kt:935-1015`).
- **Why wrong.** The rollback mirror restores only the optics packet (mode, lens, TC, facing,
  route, controls, declaration, transfer/codec, DNG). It never touches `activeMemorySlot`, and
  nothing reverts the non-optics fields the recall already applied in the VM and the engine
  (stab, aspect, drive, interval, peaking/zebra/scopes, Fn slots, open gate, bitrate, frame rate,
  audio gain/scene/input, `recordAudio`, hardware-key actions). The Activity also already rewrote
  the denial reason from the failed bank. The status text is "Camera unavailable; recalled optics
  unchanged", so it is literally true about optics, but the M-slot indicator says the bank is
  active and the rollback's `scheduleSettingsSave()` (`:1014`) persists the hybrid.
- **Failure scenario.** Photo/logical MAIN, slot M1 holds a Video + TELE + 16:9 + 24 fps +
  audio-off(denial) bank. Recall M1 while the 3× camera is briefly unavailable (another client,
  `CAMERA_UNAVAILABLE_RECALL_UNCHANGED`). Result: Photo/MAIN optics restored, but 16:9, 24 fps,
  the bank's Fn layout and `recordAudio=false` stay; "M1" stays highlighted; the denial key is now
  true; the hybrid is saved. A second tap on M1 is a normal recall again, but until then the OSD
  claims a bank that is not what is streaming.
- **Fix.** In `onOpticsRollback`, clear `activeMemorySlot` (no bank matches rolled-back optics).
  Better: make the recall's non-optics fields part of the same generation-owned packet (or
  snapshot the pre-recall `CameraUiState` non-optics fields in `applyLoaded` and restore them when
  `rollback.generation` is the recall's generation), and have the Activity commit the denial
  reason only after the recall's Ready (or restore the previous reason on rollback).
- Confidence: High (code). Status: **Confirmed** (code trace). Severity: **Low-Medium**.

### TR4-2 — Recalling a denial-silent bank while the microphone is already granted records silent clips until the next `onResume`

- **Flow 4 → Flow 3.** `MainActivity.onRecallMemorySlot` → `audioDenialReasonAfterRecall`
  (`CameraPermissionPolicy.kt`, a36c5889) returns `true` for a bank saved with
  `recordAudioOffByDenial = true` → `AUDIO_OFF_BY_DENIAL_KEY = true` committed
  (`MainActivity.kt:538-546`); `recordAudio = false` is now live state. The only evaluator of
  `audioRestoredByMicrophoneGrant` is `refreshPermissionState()` (`MainActivity.kt:985-1004`),
  called from `onCreate` (`:388`), the CAMERA launcher result (`:423`) and `onResume` (`:681`).
- **Why wrong.** The provenance exists so a denial-silent bank does not lock audio off once the
  microphone is granted (AGG2-26). When the bank is recalled AFTER the grant, the condition
  `audioDisabledByDenial && !recordAudio && hasMicrophonePermission` is already true, but nothing
  evaluates it until the Activity is resumed again. Meanwhile `microphonePermissionRequired` is
  conditional on `recordAudio`, so the shutter does not prompt either.
- **Failure scenario.** M2 saved while the mic was denied (silent by denial). User later grants the
  mic (audio comes back on). User recalls M2 and immediately presses REC: the clip is silent and
  the level meter is absent, with the permission granted and no prompt. Leaving and returning to
  the app then flips audio on with "Microphone allowed — audio on", which reads as the app changing
  its mind.
- **Fix.** After writing the reason in `onRecallMemorySlot`, run the same reconciliation
  `refreshPermissionState()` performs for audio (extract the `audioRestoredByMicrophoneGrant`
  block into a helper and call it there), so a denial-silent recall onto a granted mic lands with
  audio on immediately. Add a host test: grant → recall denial-silent bank → `recordAudio == true`.
- Confidence: High. Status: **Confirmed** (code). Severity: **Low-Medium**.

### TR4-3 — The AGG3-6 delete verdict rests on a false premise: the tolerated `muxer.stop()` throw is exactly the case where the moov IS written

- **Flow 3 (REC stop → storage tail).** `VideoRecorder.stop` muxer block
  (`video/VideoRecorder.kt:450-474`): `muxerStopFailureIsTerminal(...) == false` only for "audio
  degraded mid-REC, zero audio samples, video samples written" → `finalizedValidation = SKIPPED` →
  `completeFrozenRecordingStorage` (`VideoRecorder.kt:~1970-1985`) →
  `validateVideoTrack = finalizedVideoTrackProbe(..., muxerStopThrew = true)`
  (`VideoRecorder.kt:~2052`) → `classifyFinalizedVideoTrack` (`storage/MediaStoreWriter.kt:2697`):
  after 01c42176 + dd91413c an opened file whose extractor throws twice (250 ms apart,
  `:1771`) is `INVALID` → FAILED → the take is deleted.
- **Why wrong.** Both commits justify the destructive verdict with "a `muxer.stop()` throw is
  independent evidence that the moov was never written". But the only stop throw that reaches this
  probe is the TOLERATED one, and the recorder's own KDoc (`VideoRecorder.kt:2394-2401`) says why it
  is tolerated: `MediaMuxer.stop()` throws over a registered-but-empty audio track "even though the
  video track is playable". In AOSP `MPEG4Writer::reset()` a sample-less track stops with
  `ERROR_MALFORMED`, and the writer deliberately still writes the movie header for that one error
  ("Do not write out movie header on error except malformed track"), so the stop throw in this case
  is evidence that the moov WAS written. The extractor throwing afterward is therefore no stronger
  evidence than in the non-throw case the code (rightly, AGG2-18) treats as INDETERMINATE.
- **Competing hypotheses.** (H1) The extractor throw means a corrupt container: possible, but the
  same throw is INDETERMINATE everywhere else. (H2) Transient FUSE/MediaProvider contention (the
  AGG2-18 class): two reads 250 ms apart during the post-stop media scan of a just-closed file can
  both fail. Neither can be told apart by the current evidence, and only H1 justifies deletion.
- **Failure scenario.** A Bluetooth mic drops right after REC starts (audio degraded, no audio
  sample). The take is good video. On stop, MediaProvider is busy (scan of the closing file, a
  parallel HEIF publish); `MediaExtractor.setDataSource(fd)` fails twice → the good take is
  deleted with "Recording failed". This is the exact loss the tolerance was added to prevent.
- **Fix.** Replace "extractor threw" with a structural check that can actually prove absence:
  walk the top-level ISO-BMFF boxes from the opened fd (the HEIF probe already does this) and
  return INVALID only when no complete `moov` box exists or a box overruns the file; a present,
  well-sized `moov` with an extractor throw stays INDETERMINATE (retained for recovery, which now
  re-arms expiry per AGG3-4). Correct both KDocs (`MediaStoreWriter.kt` around
  `classifyFinalizedVideoTrack`, `VideoRecorder.kt:~1952-1956`) to state the real premise.
- Confidence: Medium (AOSP writer behaviour from source, not measured on PMA110/MediaTek).
  Status: **Likely**; **Needs-manual-validation** (device: force audio degrade before first
  sample, check `moov` presence with `ffprobe` after the stop throw). Severity: **Medium**
  (destructive on a good take, narrow trigger).

### TR4-4 — Since 6cae37ed the format chips show the request while every shot writes something else (FRONT / drop-RAW rung)

- **Flow 5 (FRONT) → Flow 2 (shutter).** `onToggleFrontCamera` → front session Ready with
  `PhotoSessionOutputs(processed = true, raw = false)` → VM Ready publication no longer writes
  `photoFormats = accepted.photoFormats` (6cae37ed, `CameraViewModel.kt:~921`) → ProSheet passes the
  REQUEST to `PhotoFormatToggles(formats = state.photoFormats, ...)` (`ui/controls/ProSheet.kt:878`)
  → chips render `selected = formats.heif/jpeg/dngRaw` (`ui/controls/ProControls.kt:~1125-1150`);
  the shutter writes `formats.normalizedFor(accepted.outputs)` (`camera/CameraEngine.kt:~5001`),
  and the OSD reads `effectivePhotoFormats` (`camera/CameraState.kt:1512`, 928745b3).
- **Why wrong.** With a DNG-only request (`{heif=false, jpeg=false, dng=true}`) on FRONT (or on a
  rear standalone session that landed on the drop-RAW rung), `normalizedFor` falls back to
  `{heif}`. The OSD says HEIF, every file is HEIF, but the chip row shows HEIF and JPEG OFF and only
  a disabled DNG lit, captioned "RAW unavailable". Before 6cae37ed the chip showed the normalized
  set (honest, but persisted — the AGG3-18 bug). The fix moved the honesty to the OSD only; the
  chip row (the control the operator edits) now contradicts both the OSD and the files.
- **Scenario.** DNG-only operator flips to the selfie camera: chips read "DNG (disabled)" with no
  processed format selected; shots save HEIFs the sheet says are off.
- **Fix.** Render chip `selected` from `effectivePhotoFormats` for the processed axes (keep the DNG
  chip on the request, since it is intent that moves the route), and route edits through the same
  `withEdit(displayed = effective, edited)` merge the pre-inventory path uses so tapping a displayed
  stand-in edits the request correctly. Add a Compose/host test for FRONT + DNG-only.
- Confidence: High. Status: **Confirmed** (code). Severity: **Low**.

### TR4-5 — The DNG-only Ready status is now repeated on every Ready, not once

- **Flow 1/2 (Ready publication).** `engine.onCameraReadyChange` → VM `formatStatus`
  (`CameraViewModel.kt:899-910`): `current.photoFormats.wantsProcessedStill &&
  !accepted.effectivePhotoFormats.wantsProcessedStill && accepted.effectivePhotoFormats.dngRaw`.
- **Why wrong.** Before 6cae37ed the first such Ready normalized `photoFormats` to DNG-only, so the
  left term was false on every later Ready and the status fired once. Now `photoFormats` stays the
  request (wants processed), so every Ready of a RAW-only accepted session (each fast commit, zoom
  remap commit, preview-health recovery, resume) re-announces "processed still unavailable; DNG
  only". The status is meant as a one-shot transition notice (the same text also fires at the
  shutter and in the sheet caption).
- **Reachability.** A RAW-only session needs a processed reader that failed to materialize while
  RAW did (`CameraController.kt:~689-737`: `size == null` for the JPEG/YUV reader on a rung with
  `useJpeg`). Not observed on PMA110.
- **Fix.** Fire only on the edge: compare against the PREVIOUS accepted outputs
  (`current.photoSessionOutputs`) instead of the request, i.e. announce when the effective set
  transitions into DNG-only.
- Confidence: High (code), Low (reach). Status: **Confirmed** code / **Needs-manual-validation**
  reach. Severity: **Low**.

### TR4-6 — Before the lens inventory lands, VM and Engine use different optical sets for every scale conversion

- **Flow 1 (cold restore) and Flow 5 (TC/FRONT inside the window).** VM state default
  `lensInventory = LensInventory.ALL` (`camera/CameraState.kt:885, 1714`: every preset "optical");
  Engine `acceptedOpticalPresets = emptySet()` (`camera/CameraEngine.kt:1346`) until
  `onLensInventory` (`:1376`). `opticalBaseFor(x, ALL)` returns the preset itself;
  `opticalBaseFor(x, empty)` returns MAIN (divisor 1).
- **Why wrong.** Every conversion pair (`unifiedZoomOf`/`localZoomOf`/`rearReturnZoom`/
  `resolveTeleZoomTransition`) is computed on both sides with different divisors until the
  inventory publishes. Concretely:
  1. `applyLoaded`'s preserve branch (`CameraViewModel.kt:1359`) runs in VM `init`, always inside
     the window. Saved TELE + "preserve TC" off + "preserve lens" on + Video (or Photo+DNG): the VM
     computes `3 / opticalBaseFor(3, ALL) = 1.0` local on TELE3X. On a one-camera device whose
     "3×" is a crop of the main lens (the tablets this code was fixed for), the route opens the main
     camera at local 1.0 = 1× framing while the rail says 3×; after the inventory lands the
     readout (`unifiedZoom`) reads 1× with the 3× band highlighted.
  2. A TC tap or FRONT flip in the first few hundred ms: VM baseline `unifiedZoomOf(TELE3X, 2, ALL)
     = 6`, Engine baseline `unifiedZoomOf(TELE3X, 2, empty) = 2`; the later TC-off/front-return
     restores different framings on the two sides (OSD vs wire).
- **Fix.** Use one pre-inventory answer on both sides: either seed the Engine with
  `LensInventory.ALL.optical` until enumeration (matching the VM, PMA110-identical), or make the VM
  conversions read the Engine's accepted optical set (one getter, like `rawForcesStandalone`).
  For (1), defer the preserve-branch zoom computation to the first `onLensInventory` when the
  inventory is still the placeholder.
- Confidence: High (code), Medium (impact). Status: **Confirmed** code;
  **Needs-manual-validation** on TB336ZU/TB331FC for (1). Severity: **Low**.

### TR4-7 — Cold launch in Video with a remembered non-SDR curve configures an SDR session first, then reopens for 10-bit

- **Flow 1 (settings restore → first Ready).** VM `init`: `restoreSettingsIfEnabled` →
  `applyLoaded` normalizes the transfer with `tenBitEncodeAvailable = false` because the encoder
  inventory is not loaded (`CameraViewModel.kt:1330-1333`) → `setResolvedOptics(resolvedTransfer =
  SDR)` → `loadEncoderInventoryAsync` (`:2734`, `ioExecutor`, posts to main) → `onStart` →
  `engine.resume` → GL input → cold `reconfigureCamera` configures the SDR session →
  `applyEncoderInventory` (`:2749-2774`) → `engine.setVideoPipeline(..., HLG/S-Log3)` →
  `tenBitChanged && recorder == null` (`camera/CameraEngine.kt:3002`) → new optics transaction +
  `reopenForSession(transaction)` → close + reconfigure with HLG10.
- **Why wrong.** When the inventory posts after the GL input surface exists (cold process: the
  MediaCodecList scan is first-use, `EncoderCaps.load()` has no other caller), the cold start
  pays two full configures; if it lands mid-configure, the first session's terminal commit is
  superseded and discarded. Either way an operator who left the app in HLG/log video sees an
  extra black dip and a longer `resume → Ready`, and any measured cold-start budget for Video
  includes a wasted session. Photo mode is unaffected (`tenBitChanged` is false).
- **Competing hypotheses.** (H1) Inventory always wins the race so the first configure already
  uses HLG: not guaranteed — nothing orders `loadEncoderInventoryAsync` before the GL input
  callback. (H2) The second configure is required anyway: no — the persisted transfer is known
  at restore; only the 10-bit *encode* availability is unknown, and a wrong guess is recoverable
  by the same transaction path in the rare device without Main10.
- **Fix.** Start `EncoderCaps.load()` earlier (Application/Activity `onCreate` prewarm) and make
  the cold-start `reconfigureCamera` wait on the inventory only when the restored mode is Video
  with a non-SDR request (bounded wait), or optimistically restore the persisted transfer for the
  session decision and fall back through the existing transaction if the inventory says no.
- Confidence: Medium. Status: **Likely**; **Needs-manual-validation** (two `Session configured`
  lines on a cold launch with Video + HLG remembered). Severity: **Low** (perf/UX).

## Hypotheses examined and refuted (no finding)

| Flow | Suspicion | Resolution |
|---|---|---|
| 5 FRONT return (6ba15217) | Lens-based divisor wrong for routes that change between entry and return (mode or DNG flipped while FRONT; TC on at entry) | Refuted. Entry snapshot is `opticalBaseFor(lens)*local`; return divides by `opticalBaseFor(lens)`; the returning standalone route opens `resolveNonTeleId(lens)`, the same lens, in both Engine (`CameraEngine.kt:4003-4030`) and VM (`CameraViewModel.kt:2960-3015`). TC-at-entry snapshots `3*local`, and TC is off on return, so 3× lens local or unified 3×local is the right framing. |
| 4 recall same-camera fast path | DNG or transfer changes skip the reopen on a same-id recall (TELE) | Refuted. `commitFastPathOrReconfigure` reconfigures on a 10-bit boundary change (`CameraEngine.kt:916-925`); standalone sessions carry RAW by route regardless of `rawWanted` (`setRawWanted` KDoc, `sessionAttemptPlan`). |
| 3 REC UI callbacks | `onRecordingStarted`/`onRecordingTerminated` carry no attempt generation and could clear a newer take's UI | Refuted for human timing. Started is gated by recorder ownership immediately before invoke (`CameraEngine.kt:6430-6441`); Terminated fires only for an owned failure; a new start while finalizing is refused by `recorderTeardownInFlight` (`:6044`), and that refusal is generation-owned in the VM. |
| 2 shutter lease | Double release of the processed-snapshot lease on the SINGLE path's failure branch | Refuted. `ProcessedSnapshotBudget.Lease.release()` is CAS-idempotent. |
| 2 ZSL serve | Served frame could bypass the pending slot or watchdog | Refuted. `pending != null` refusal precedes serve; serve completes synchronously through `tryComplete`; in-REC snapshot passes `allowZsl = false`. |
| 1 restore DNG | Restored DNG could reach the engine after the route resolved | Refuted. `resolvedRawWanted` rides `setResolvedOptics`; inventory's `setRawWanted` replays the same value (no-op). |
| 3 launch recovery | `reassertPending` (5ebe8b07) re-arms DISCARD rows | Refuted. DISCARD rows are excluded explicitly. |
| 4 recall audio (a36c5889) | Main settings blob writes stale provenance | Refuted. `currentExtras()` leaves it null and the store removes the key. |

## Summary

7 new findings: TR4-1 (Low-Medium), TR4-2 (Low-Medium), TR4-3 (Medium), TR4-4 (Low),
TR4-5 (Low), TR4-6 (Low), TR4-7 (Low). Three of them are regressions or residues of cycle-3 fixes
(TR4-3 from 01c42176/dd91413c, TR4-4 and TR4-5 from 6cae37ed); TR4-2 is a gap left by a36c5889.
