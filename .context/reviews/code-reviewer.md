# RPL cycle 4 — code-reviewer (CR4), HEAD 14767b0a, 2026-10-02

Angle: code quality, logic, SOLID, maintainability, prioritising real correctness bugs.
Method: inventory of `app/src/main/kotlin/**` (61k lines) split into four slices (engine;
controller/capture; storage/video/audio; ViewModel/Activity/UI/GL), each traced from code. Every
finding below was re-checked by me against the source at HEAD before it was written down. Prior
findings (AGG3-1..63, cycle 1–3 plans) were excluded unless still open or regressed.

## Cycle-3 fix verification

These cycle-3 fixes read as correct and complete, with no regression found: 58eb4f10 (AGG3-2),
6ba15217 (AGG3-1), f32a5bbd (AGG3-9), f603a208 (AGG3-10), 04fad2e8 (AGG3-17), 176e2199 (AGG3-13),
9cfd288a (AGG3-41), 0b2a6e99 (AGG3-39), ce60c1a8 (AGG3-30), 928745b3, 6cae37ed (AGG3-18),
6abb57ac (AGG3-62), 2295ea79 (AGG3-3), 5ebe8b07 (AGG3-4; the expiry effect still needs a device),
a36c5889 (AGG3-8; persistence round-trips, but see CR4-8 for the recall edge), cb62f4ea (AGG3-20)
and 9ddf41bf (AGG3-43).

Three cycle-3 fixes are incomplete:
- c5bfd1c8 (AGG3-7) removed the false Ready but leaves the bare doors with no way back to Ready
  (CR4-3).
- dd91413c (MRG3-2) retries the parse on the same descriptor (CR4-7).
- The DNG-cancel half of AGG3-22 is still open (`CameraEngine.kt:549-551`, KNOWN/still open).

AGG3-24, AGG3-25 and AGG3-28 are still open (KNOWN). CR4-4 adds a data-integrity consequence to
AGG3-24.

---

## Findings

### CR4-1 — The ViewModel latches the shutter off through a field the engine owns, and nothing turns it back on
- **Severity:** Medium. **Confidence:** High. **Classification:** Confirmed.
- **Where:** `ui/CameraViewModel.kt:4059-4080` (`deleteLateCaptureOutput`), `ui/CameraViewModel.kt:1229-1234`, `camera/DngPreCaptureAllocation.kt:26-43` (`StillAdmissionPublication`), `camera/CameraState.kt:1798-1802`.
- **Problem:** on an `UNRESOLVED` discard the ViewModel writes the engine-projected field directly:
  ```kotlin
  _state.update { it.copy(stillCaptureAdmissionAvailable = false) }
  ```
  `stillCaptureAdmissionAvailable` is otherwise written only by `engine.onStillCaptureAdmissionChanged`. That callback is change-gated inside `StillAdmissionPublication.publish()`:
  ```kotlin
  if (delivered == current) return@synchronized
  ```
  The engine's own snapshot (`stillOutputAdmissionAvailable()`) is not lowered by this path. `discardPendingOutput` deliberately does not consume rejected-output headroom (its comment says so; the original 69754851 used `discardRejectedOutput`, which did lower it). So the engine still thinks it delivered `true`, and it will never deliver `true` again while its own value stays `true`.
- **Failure scenario:**
  1. The user deletes a capture family from review.
  2. A late sibling arrives, and its `discardPendingOutput` returns `UNRESOLVED` (the allocation was evicted from the bounded map, or the journal write failed).
  3. `stillCaptureReady` and `primaryShutterEnabled` go false. The touch shutter and the hardware SHUTTER, half-press and quick-button bindings all stop working.
  4. The engine would admit a capture. The only recoveries are an unrelated admission edge (a constituent going false and then true), or replacing the observer (process or ViewModel recreation).
- **Fix:** delete the `_state.update` and keep the status line. If a fail-closed shutter really is wanted here, route it through an engine-owned admission constituent that has a release edge, so the projection stays single-writer.

### CR4-2 — A same-camera Video memory recall publishes the recalled resolution but keeps streaming and recording the old one
- **Severity:** Medium. **Confidence:** High. **Classification:** Confirmed.
- **Where:**
  - `camera/CameraEngine.kt:2860` (`recalledVideoSize?.let { requestedVideoSize = it }`)
  - `camera/CameraEngine.kt:2899-2945` (structural check, then fast commit)
  - `camera/CameraEngine.kt:899-944` (`commitFastPathOrReconfigure`)
  - `camera/CameraEngine.kt:7887-7894` (`chooseVideoSize`)
  - `ui/CameraViewModel.kt:1484-1485, 1579` (`videoResolution = restoredVideoSize ?: it.videoResolution`)
- **Problem:** the recall only writes `requestedVideoSize`. Nothing in the fast path calls `chooseVideoSize`, `applyVideoSize` or `onVideoSizeChosen`, or updates `previewStreamSize`. Three checks all miss it:
  - `resolvedOpticsRequiresReconfigure` compares mode, TC, camera id and ready only.
  - `commitFastPathOrReconfigure` escalates only on a hi-res or 10-bit boundary change.
  - The VM's trailing `engine.setOpenGate` / `setVideoFrameRate` / `setVideoStabMode` setters are change-gated, so they do nothing when those values match.
- **Failure scenario:**
  1. Video on the 1× standalone lens at 3840×2160.
  2. Recall an MR bank saved as Video, 1×, 1920×1080, with the same Open Gate, rate and stab.
  3. Same camera id, so the fast commit runs. The session stays 4K while the UI shows 1080p.
  4. REC freezes `videoSize` (4K) in `currentRecordingAdmissionSnapshot`, so the file is 4K. The bitrate and the file size the operator expects are both wrong.
  5. This persists until an unrelated door reopens the session.
- **Fix:** in `setResolvedOptics`'s setup-thread block, when `enabledVideo && selection != null && chooseVideoSize(selection) != videoSize`, take `reconfigureCamera(id, transaction)` (`reconfigureCamera` already re-picks `videoSize` at `:4353`). Alternatively, add a "stream size changes" term to `resolvedOpticsRequiresReconfigure`. Add a test where the recalled packet differs only in video size.

### CR4-3 — Bare `reopenForSession()` doors: a transient preflight failure leaves the camera Not-Ready with no convergence path (AGG3-7 residual, AGG2-4 re-opened for these doors)
- **Severity:** Medium. **Confidence:** Medium. **Classification:** Likely.
- **Where:** `camera/CameraEngine.kt:963-971` (`currentOpticsReconfiguration`, `baselinePrecedesMutation = false`), `:988-1005` (`rollbackOpticsAfterPreflight`), `:4255-4295` (preflight branches), `:3817-3830` (`applyVideoSize`).
- **Problem:** after c5bfd1c8, a preflight failure on a bare door goes like this:
  - `selectCurrentLens()` or `cachedCaps()` returns null.
  - `recoverColdPreflight` is false because `controller != null`.
  - `rollbackOpticsAfterPreflight` commits a Not-Ready rollback, because `preflightRestorableSessionGeneration` returns null for bare doors.
  - The status says `CAMERA_UNAVAILABLE_CAMERA_UNCHANGED`, but no retry is scheduled.
  - `invalidateCameraReady()` already nulled `acceptedCameraSession`, so a later preview-ready edge cannot republish Ready either.

  Also, because `before` is snapshotted after the door's own write, rollback "restores" the new size. `applyVideoSize` had already pushed `gl.setCameraPreviewSize(new)` and `emitPreviewAspect()`, so the still-streaming old session renders into a SurfaceTexture sized for the new stream. That is the distortion `reconfigureCamera`'s own comment warns against.
- **Failure scenario:**
  1. In Video, the operator picks a new resolution (or stab, aspect, hi-res, or a camera-error recovery via `reopenForSession(failedController)`).
  2. A Binder hiccup makes `cachedCaps` null once.
  3. The status reads "camera unchanged", but shutter and REC stay disabled and the preview is aspect-distorted until another door runs or the app goes through pause/resume.
- **Fix (either):**
  - Route the non-cold bare-door preflight failure into the bounded `scheduleColdStartRetry(transaction, CAMERA_UNAVAILABLE_RETRYING)`.
  - Preferable: give bare doors a true pre-mutation baseline (snapshot via `beginOpticsTransaction` before writing `videoSize`/stab/aspect/rate) so the AGG2-4 restore is valid for them too.

  Test: a bare video-size door whose caps read fails once ends Ready, with the size it actually streams.

### CR4-4 — A kill during the in-place JPEG EXIF rewrite leaves a misaligned file that launch recovery adopts as VALID (data-integrity extension of AGG3-24)
- **Severity:** Medium. **Confidence:** Medium. **Classification:** Likely (the ExifInterface fd-rewrite behaviour is from the library source; the kill timing needs device reproduction).
- **Where:** `capture/StillCapturePipeline.kt:333-334, 371-373` (`runCatching { writeJpegExif(...) }` after the bytes are REGISTERED), `:470-486` (`writeJpegExif`, `"rw"` fd plus `saveAttributes()`), `storage/MediaStoreWriter.kt:1791-1807` (`probeCompleteJpeg`).
- **Problem:**
  - `saveAttributes()` on a file descriptor copies the original to a temp file, `lseek`s the fd to 0, and rewrites the whole file through a `FileOutputStream`. It does not truncate.
  - The rewritten file is longer than the `Bitmap.compress` output (it gains an APP1 segment).
  - A process death mid-rewrite therefore leaves a new prefix followed by the old bytes shifted by the APP1 length. The file still ends in the original `FF D9`.
  - `probeCompleteJpeg` checks only the last two bytes. Launch recovery (journal `REGISTERED`) classifies the file VALID and publishes it.
- **Failure scenario:** the user shoots a JPEG and swipe-kills the app (or the low-memory killer fires) during the multi-MB FUSE rewrite. On the next launch the gallery gains a JPEG whose scan data is garbage from the cut point down. The intact original existed only in ExifInterface's cache temp file.
- **Fix:** build the EXIF before the single write: encode to memory, splice the APP1 after SOI (this also fixes AGG3-24's cost), then write the pending row once. If the rewrite must stay, durably mark the row "rewrite in progress" before `writeJpegExif` and have recovery treat that state as INDETERMINATE. Tightening the tail probe alone cannot detect the splice.

### CR4-5 — Pseudo-ZSL admission does not check focus, so a buffered frame from before a manual-focus change, or from before a torch frame actually lit, can be served
- **Severity:** Medium. **Confidence:** Medium. **Classification:** Needs-manual-validation.
- **Where:** `camera/ZslAdmission.kt:46-100` (`ZslFrameFacts`, `ZslStillIntent`, `zslFrameAdmissible`); the ring pairing in `camera/CameraController.kt` (~1600-1645).
- **Problem:** `ZslFrameFacts` carries only timestamp, exposure, ISO and zoom. The predicate never compares:
  - `LENS_FOCUS_DISTANCE` against the intended MF or AF-lock distance;
  - `LENS_STATE == MOVING`;
  - the AF state;
  - `FLASH_STATE` or torch for a just-enabled TORCH.

  Frames up to 400 ms old are eligible. This does not relitigate the dark refusal or widen any tolerance. It adds a missing dimension to the same "frame IS the requested still" contract.
- **Failure scenario:**
  1. Photo, SINGLE drive, M exposure, MF, in good light (LOGICAL route).
  2. The operator drags the focus ruler and presses within ~200 ms.
  3. The ring frames still carry the old lens position, but exposure, ISO and zoom match, so a defocused frame is served. A real capture would have carried the new `LENS_FOCUS_DISTANCE`.

  The same applies to a press during a tap-AF scan (`touchAfActive`, lens moving).
- **Fix:** add `focusDistanceDiopters` and `lensMoving` (and `afState` when AF is locked) to `ZslFrameFacts`. Refuse when MF or AF-lock intent differs by more than a small diopter epsilon, or when the lens is MOVING. Pure test for each axis.

### CR4-6 — The YUV still size ignores the aspect-first rule `pickStillSize` already applies to JPEG
- **Severity:** Low-Medium. **Confidence:** Medium. **Classification:** Needs-manual-validation (depends on what a device advertises).
- **Where:** `camera/CaptureCapabilities.kt:341-345` versus `pickStillSize` at `:653-670`.
  ```kotlin
  val yuvSize = yuvCandidates
      .filter { activeArray == null || (it.width <= activeArray.width() && it.height <= activeArray.height()) }
      .maxByOrNull { it.width.toLong() * it.height }
  ```
- **Problem:** JPEG selection was fixed to prefer the array's native aspect, after TB331FC advertised a larger square size. YUV, which feeds every FRONT still and every LOGICAL-route still plus the deep-ZSL gate (`largestYuvMinFrameDurationNs`), still picks the largest area.
- **Failure scenario:** a device advertises a square YUV size with more area than its 4:3 one. Every 4:3 front or logical photo saves square, losing field the finder showed, and the ZSL frame-duration gate is measured on the wrong size.
- **Fix:** `pickStillSize(yuvCandidates.map { it.width to it.height }, arrayW, arrayH)`, mapped back to a `Size`. Add a table test alongside the JPEG one.

### CR4-7 — dd91413c's confirming re-parse reuses the same descriptor, so the transient case it protects against still deletes the take
- **Severity:** Low-Medium. **Confidence:** High on the mechanism, Medium on impact. **Classification:** Confirmed (code); impact needs the rare coincidence.
- **Where:** `storage/MediaStoreWriter.kt:2697-2727` (`classifyFinalizedVideoTrack`), caller `:1747-1772`.
  ```kotlin
  } catch (first: Exception) {
      if (!muxerStopThrew) throw first
      beforeParseRetry()
      hasVideoTrack(descriptor)
  }
  ```
- **Problem:** the KDoc names a MediaProvider restart or a revoked FUSE fd as the transient failure to ride out. A revoked descriptor stays revoked, so re-parsing it after 250 ms fails the same way, and the result is INVALID, which deletes the take. The retry therefore only covers in-process extractor flakiness, not the case it cites.
- **Failure scenario:** the tolerated `muxer.stop()` throw (audio dropped before its first sample; MPEG4Writer still writes moov) coincides with a MediaProvider restart. Both parses fail on the dead fd, and `deleteAllocation` removes a playable clip.
- **Fix:** close the first descriptor and re-`open()` before the retry. A failed reopen returns INDETERMINATE (retain for recovery), never INVALID. Add a pure test where the first descriptor's parse throws and a fresh descriptor parses.

### CR4-8 — Recalling a denial-silent MR bank while the mic is already granted records silently until some later `onResume`
- **Severity:** Low-Medium. **Confidence:** High. **Classification:** Confirmed.
- **Where:** `MainActivity.kt:530-548` (`onRecallMemorySlot`), `MainActivity.kt:985-1003` (`refreshPermissionState` → `audioRestoredByMicrophoneGrant`), `CameraPermissionPolicy.kt:95`.
- **Problem:**
  - The recall writes `AUDIO_OFF_BY_DENIAL_KEY = true` with `recordAudio = false`.
  - The grant-restore rule only runs in `refreshPermissionState()` (onCreate, onResume, camera-permission result).
  - The MR sheet is an in-window Compose modal, so no `onResume` follows the recall.
- **Failure scenario:**
  1. The bank was stored while the mic was denied.
  2. The user later grants the mic, then recalls the bank.
  3. Every clip is silent and the level meter is hidden.
  4. At an unrelated later `onResume`, audio flips back on by itself with an "audio on" status. That is surprising, and the clips in between are silent.
- **Fix:** after persisting `reason == true`, evaluate `audioRestoredByMicrophoneGrant(...)` immediately with the current permission (or call `refreshPermissionState()`). Test the recall-while-granted sequence.

### CR4-9 — A 10-bit-boundary transfer change that lands during REC is published but its session reopen is never owed
- **Severity:** Low. **Confidence:** Medium. **Classification:** Likely (the reachable trigger is narrow).
- **Where:** `camera/CameraEngine.kt:2999-3009` (`setVideoPipeline`), REC freeze at `:5915` (`val frozenTransfer = transfer`), and the stop path, which only re-pushes the GL curve.
  ```kotlin
  if (tenBitChanged && recorder == null) { ...reopenForSession(transaction) }
  else { publish(); if (recorder == null) postGlTransfer(activeTransfer) }
  ```
- **Problem:** while recording, `transfer` changes across the HLG10/SDR session boundary, but nothing records that a reopen is owed. REC admission freezes the desired `transfer`, not the accepted session's HLG fact.
- **Failure scenario:**
  1. Recording HLG.
  2. The encoder inventory callback (`CameraViewModel.kt:~2769`, not `rejectIfRecording`-gated) normalizes the transfer to SDR.
  3. After Stop, the next take is SDR-tagged over an HLG10 still-less session, and stills stay off. The reverse direction gives an 8-bit session tagged HLG.
  4. This lasts until another door reopens the session.
- **Fix:** latch `sessionReopenOwed` when `tenBitChanged && recorder != null` and run it from the recorder-finalized edge. Alternatively, have REC admission compare the frozen transfer against the accepted session's HLG fact and refuse or reopen.

### CR4-10 — `applyLoaded` arms the pending encoder-inventory requests before its refusal exits
- **Severity:** Low. **Confidence:** Medium. **Classification:** Confirmed (code); the window is "recall before inventory loads".
- **Where:** `ui/CameraViewModel.kt:1332-1337` (assignments) versus `:1389` (`recalledCameraRoute(...) ?: return`) and `:1480-1483` (`if (!opticsAccepted) { ...; return }`).
- **Problem:** `pendingCodecUntilInventory`, `pendingTransferUntilInventory` and `pendingPhotoFormatsUntilInventory` are assigned from the bank before the recall can refuse, and the refusal path restores only `photoExposureTimeNs`.
- **Failure scenario:** the recall is refused (no route yet, or the engine still owns a recorder) before the inventory loads. When the inventory arrives, `applyEncoderInventory` applies and persists the refused bank's codec, transfer and formats, including `setRawWanted(bank.dngRaw)`.
- **Fix:** compute the values into locals and assign the pending fields only after `opticsAccepted`.

### CR4-11 — The hardware-zoom ease ticker can be double-posted, producing two concurrent 30 Hz glide chains
- **Severity:** Low. **Confidence:** Medium. **Classification:** Confirmed (code).
- **Where:** `ui/CameraViewModel.kt:2302-2304` (`if (wasIdle) mainHandler.post(zoomEaseTicker)`), `:2172` (`onZoomRatio`) and `:2945` (`onTeleZoomMark`), which null `easeTarget` without `removeCallbacks`. Ticker body at `:439-463`.
- **Failure scenario:**
  1. During a key glide, a dial or pinch input nulls the target.
  2. Within 33 ms the key slides again. `wasIdle` is true, so the Runnable is posted again while its previous `postDelayed` copy is still queued.
  3. The old copy sees a non-null target and keeps reposting, so two chains run (about 60 Hz). The glide moves at double speed, with double the submits.
- **Fix:** `mainHandler.removeCallbacks(zoomEaseTicker)` before the post, or in every place that nulls `easeTarget`.

### CR4-12 — `DngPreCaptureAllocation.cancel()` can read the `lateinit attempt` before it is assigned, and the throw escapes `invalidateCameraReady()` before Ready is cleared
- **Severity:** Low. **Confidence:** Low-Medium. **Classification:** Likely (narrow window).
- **Where:** `camera/DngPreCaptureAllocation.kt:98-104, 167` (`check(started.compareAndSet(false, true))`, then `attempt = ...`; `fun cancel() = started.get() && attempt.retire()`), `camera/CameraEngine.kt:549-551, 4978-4983` (`cancelDngPreCaptureAllocations` has no `runCatching`, and is the first statement of `invalidateCameraReady`).
- **Problem:** the owner is registered before `start()` (`:4879`). A cancel from another thread between the CAS and the assignment hits `UninitializedPropertyAccessException`; `attempt` is also non-volatile, so it may not be visible even after assignment. The exception aborts `invalidateCameraReady()` before `cameraReady = false` / `readyController = null`. A cancel before the CAS returns false and the allocation is never cancelled.
- **Fix:** construct `attempt` in the initializer (or hold it in an `AtomicReference`) and make `cancel()` latch a "cancel requested" flag that `start()` honours. Wrap the per-owner cancel in `runCatching` inside `cancelDngPreCaptureAllocations`.

### CR4-13 — EXIF `OffsetTime` / `OffsetTimeOriginal` is written as `"Z"` in UTC+0 zones
- **Severity:** Low. **Confidence:** High. **Classification:** Confirmed.
- **Where:** `capture/StillCapturePipeline.kt:709-715`.
  ```kotlin
  val offset = java.text.SimpleDateFormat("XXX", java.util.Locale.US).format(java.util.Date(shot.takenAtMs))
  ```
- **Problem:** the ISO-8601 `X` pattern emits `"Z"` for a zero offset. EXIF 2.31 requires `"+HH:MM"` (7 ASCII bytes including the NUL), and ExifInterface does not validate it.
- **Failure scenario:** UK, Portugal or Iceland in winter: every JPEG and HEIF carries `OffsetTime="Z"`, which some readers reject or misparse.
- **Fix:** use the pattern `"xxx"` (always `+00:00`). Add a unit test with a UTC `TimeZone`.

### CR4-14 — Leaving FRONT onto the logical photo route keeps a stale lens band
- **Severity:** Low. **Confidence:** Medium. **Classification:** Likely.
- **Where:** `ui/CameraViewModel.kt:2988-3010` and `camera/CameraEngine.kt:4013-4030`. Both write the `rearReturnZoom` result but never re-band `lens` / `lensChoice` when the target is not standalone.
- **Failure scenario:**
  1. Video, ultra-wide standalone, local zoom 3.0. Enter FRONT.
  2. Switch to Photo while on FRONT, then flip back.
  3. The zoom returns as unified 1.8, but `lens` is still `ULTRAWIDE`. The rail highlights UW, and app-side Program's handheld rule (`preferredProgramShutterNs` reads `lens.targetEquivMm`) uses the UW focal.
  4. This lasts until `onCapsReady` → `reconcileZoomToCaps` re-bands it, and longer if those caps are refused as stale.
- **Fix:** when the return target is the logical route, set `lens = LensChoice.forZoom(returned)` in both the ViewModel and the engine (same `lensBandFollowsZoom` rule the recall fast path uses).

### CR4-15 — Audio and video PTS start from different origins, and audio overruns during the muxer-start wait are not reflected in PTS
- **Severity:** Medium if audible. **Confidence:** Low-Medium. **Classification:** Needs-manual-validation (on-device clap/slate test).
- **Where:** `video/VideoRecorder.kt:364-368` (AudioRecord starts inside `start()`), the audio loop at `~:830-901` (`audioPtsUs(totalSamples, …)`, `awaitMuxerStart()` in the same loop as `record.read`), `gl/GlPipeline.kt:1253-1254` (video rebased to the first encoder frame).
- **Problem:**
  - Audio PTS 0 is the instant AudioRecord started. Video PTS 0 is the first encoder swap, which comes later (up to 1 s on the slow-first-swap MediaTek tablets).
  - While the audio thread blocks in `awaitMuxerStart()`, the ~40 ms AudioRecord buffer overruns. Sample-count PTS stays continuous, so the dropped interval silently shifts the rest of the audio track.
- **Failure scenario:** every clip carries a device- and latency-dependent A/V offset. A late overrun shifts audio for the rest of the take.
- **Fix:** timestamp audio from `AudioRecord.getTimestamp` (same clock base as the camera frames), rebase both tracks to one origin, drop pre-first-video PCM, and keep reading PCM (queued) while the muxer has not started.

### CR4-16 — Family-deletion dispatcher releases its permit before the worker dequeues, so a reserved submit can be rejected as an infrastructure failure
- **Severity:** Low. **Confidence:** Low-Medium. **Classification:** Needs-manual-validation.
- **Where:** `camera/FamilyDeletionMarkerDispatcher.kt` (`submitReserved` / `releaseReservation`; the permit is released in the task wrapper's `finally`), `camera/CameraEngine.kt:5343-5350` (a rejection is mapped to `CaptureFamilyDeleteDurability.FAILED`).
- **Problem:** with the 31-slot queue full, the permit frees before the single worker takes the next task, so `reserve()` succeeds and then `execute` is rejected.
- **Failure scenario:** rapid deletes under a full backlog report "could not delete" on a delete that capacity accounting had admitted. `RejectedOutputCleanupCapacityOwner` has the same shape but retries.
- **Fix:** release the permit in `ThreadPoolExecutor.afterExecute`, or size the queue as workers + backlog so a reserved submit cannot be rejected.

### CR4-17 — The dual-open supersession restore leaves `controls` / `photoExposureTimeNs` / `lensChoice` normalized against the abandoned candidate's caps
- **Severity:** Low. **Confidence:** Low. **Classification:** Needs-manual-validation.
- **Where:** `camera/CameraEngine.kt:4371-4378` (`reconcileControlsWithCaps(c, outgoingCaps)` at candidate install) versus `:4515-4535` (supersession restores `selection`, `caps`, `videoSize` and `previewStreamSize` from `transaction.before` only).
- **Problem and scenario:** the superseding door normally republishes its own controls, but any field it does not own keeps the abandoned candidate's normalization (for example a clamped ISO, shutter or focus). I found no concrete door that leaves the clamp visible.
- **Fix:** restore those three fields from `transaction.before` in the same branch, unless a newer direct write (counter) owns them.

---

## Top 5

1. CR4-1: the ViewModel writes the engine-owned `stillCaptureAdmissionAvailable = false`, and the change-gated engine publication never sends `true` again, so the shutter stays disabled for the life of the observer.
2. CR4-2: a same-camera Video MR recall shows the recalled resolution, but the fast commit never re-picks `videoSize`, so the session and the file stay at the old size.
3. CR4-3: c5bfd1c8 leaves bare `reopenForSession` doors Not-Ready after a transient preflight failure (no retry, no Ready restore), with the preview sized for a stream it never configured.
4. CR4-4: a kill during the in-place JPEG EXIF rewrite leaves a misaligned file ending in FF D9, which launch recovery adopts and publishes as a corrupt JPEG.
5. CR4-5: pseudo-ZSL admission ignores focus and lens motion, so a frame from before an MF change or during an AF scan can be served as the still.

Totals: 17 findings. Medium: 5 (CR4-1..5) plus CR4-15 if confirmed audible. Low-Medium: 3. Low: 8.
Confirmed: 7 (CR4-1, 2, 7, 8, 10, 11, 13; CR4-7's impact needs a rare coincidence). Likely: 5 (CR4-3, 4,
9, 12, 14). Needs-manual-validation: 5 (CR4-5, 6, 15, 16, 17).
