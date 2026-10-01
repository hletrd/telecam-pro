# Debugger review: latent failure modes and swallowed exceptions

Reviewer: debugger specialist (read-only). Date: 2026-09-30. HEAD `ba5b16e7`.
Scope: `app/src/main/kotlin/**`. I looked hardest at storage/, capture/, video/VideoRecorder.kt, camera/CameraController.kt and camera/CameraEngine.kt.
Method: I walked every `runCatching` (405 sites), every `catch (Throwable|Exception)` (49 sites), `!!`, `lateinit`, persisted-enum decoding, and the Cursor column-index sites, then traced the failure edge into the caller.

The settled decisions in CLAUDE.md are not reported as bugs. That covers the dark ZSL refusal, the fail-closed identity model, the per-process log quota policy, and deliberately DEBUG-gated recurring diagnostics.

The recurring theme is the lesson CLAUDE.md itself records: **"A save that fails with no app log line is the signature of this class of defect."** Several capture/record failure edges still end in a user-visible failure status with the root cause discarded. Anyone who hits them on a new device has to start from zero, which is exactly what happened with the 2026-09-09 union-volume bug.

Summary: **12 findings** (3 High, 5 Medium, 4 Low). None are verified on device, and all are static-analysis findings. Every finding's status is **OPEN**.

---

## D1. VideoRecorder.start throws away the encoder/muxer setup exception [High]

- **Where:** `video/VideoRecorder.kt:249-305` (`val videoOk = runCatching { ... }.isSuccess`) and `video/VideoRecorder.kt:245-247` (`openParcelFd(...) ?: return null`). The consumer is `camera/CameraEngine.kt:6280-6294`.
- **Why:** `.isSuccess` drops the Throwable, so it is never logged or stored. The engine then sees `surface == null` with `unsafeStartupFailure() == null`. It retires the row as `"native-setup-failed"`, and only DEBUG builds log even that. It then shows `RECORDING_FAILED`. The null-descriptor exit behaves the same way, and `openParcelFd` itself swallows the provider exception (see D3).
- **Failure scenario:** On a new handset every candidate in the encoder ladder refuses `configure`, or `MediaMuxer(fd)` throws (for example EACCES on a revoked row). The user gets a "Recording failed" toast every time. Release logcat has no app line naming the codec, size, or exception, which matches the CLAUDE.md signature exactly. The TB336ZU silent-clip and the TB331FC union-volume investigations both cost a day for this reason.
- **Fix:** Keep the failure: `val videoSetup = runCatching { ... }`, and on failure call `DiagnosticLog.w(TAG, "video setup failed codec=… size=…", videoSetup.exceptionOrNull())` through the reserved 120-row owner. Log the null-descriptor exit the same way. Optionally expose the cause (like `unsafeStartupFailure`) so the engine logs it next to `native-setup-failed`.
- **Confidence:** High.

## D2. Every audio-setup degradation to a silent clip is itself silent in release [High]

- **Where** (all in `video/VideoRecorder.kt`):
  - `:636-640`: `minBuf <= 0` makes it video-only with no log.
  - `:646-661`: the `AudioRecord.Builder().build()` failure is discarded by `.getOrNull()`.
  - `:665-671`: `STATE_UNINITIALIZED` makes it video-only with no log.
  - `:344-356`: an exception from `startAudio()` (AAC `createEncoderByType`/`configure`/`start`) goes to `audioStart.onFailure { ... }`, which never logs `it`.
  - `runAudio` `:749-767`: a `startRecording()` failure (`audioStart.isFailure`) makes it video-only with no log.
  - `:1031-1032`: the mid-REC `degradeAudioToVideoOnly` logs only under `BuildConfig.DEBUG`.
- **Why:** Each of these paths publishes a silent clip on purpose, which is correct behavior. The only trace is an `AudioRouteStatus(UNAVAILABLE)` UI signal. CLAUDE.md's newest bullet says "a silent clip with `AudioRecord: set/openRecord` but no `start` in logcat is this race, not a mic fault". That triage rule only works if the app's own degrade edges write something. Right now a busy mic, an unsupported channel mask, a refused AAC encoder, and the pending-token race all look the same in a release log.
- **Failure scenario:** On a third device a stereo CAMCORDER `AudioRecord` fails to initialize. Every clip is silent, the field report says "no sound", and the release logcat has no app line.
- **Fix:** Send one `DiagnosticLog.w` per degrade edge with the reason and cause, at most one per recording, so the reserved budget stays safe. Remove the DEBUG gate on `degradeAudioToVideoOnly` or route it through the reserved facade.
- **Confidence:** High.

## D3. MediaStoreWriter.openParcelFd / openOutputStream swallow provider exceptions [High]

- **Where:** `storage/MediaStoreWriter.kt:1018-1022`. Consumers:
  - `capture/StillCapturePipeline.kt:262-270` (HEIF) → `HEIF_SAVE_FAILED`, no log.
  - `capture/StillCapturePipeline.kt:295-303` (JPEG) → `JPEG_SAVE_FAILED`, no log.
  - `capture/StillCapturePipeline.kt:332-342` (passthrough JPEG) → no log.
  - `capture/StillCapturePipeline.kt:368-369` (DNG) → a synthetic `IllegalStateException("Failed to open output stream")` that loses the real cause.
  - `video/VideoRecorder.kt:245`.
- **Why:** `runCatching { contentResolver.openFileDescriptor(...) }.getOrNull()` turns FileNotFoundException, SecurityException, IllegalStateException (volume unmounted, row already deleted), and IllegalArgumentException into `null` with nothing logged. The insert path was fixed on 2026-09-09 to "log silent exits" (commit `2eb57e4e`). The very next provider call in the same chain was left silent.
- **Failure scenario:** Suppose a provider revision rejects `"rw"` on pending rows owned through the union URI, or the family-delete/discard race removes the row between insert and open. Every HEIF/JPEG shot then fails with a toast and no app log. That is the same diagnostic dead end the 2026-09-09 bug hit.
- **Fix:** On failure, log `DiagnosticLog.w(TAG, "open $mode failed for $uri", t)` inside both helpers. Better still, return a `Result` so the DNG path can attach the real cause.
- **Confidence:** High.

## D4. The degraded-audio stop path deletes a good video take on a transient provider/extractor error [Medium]

- **Where:** `storage/MediaStoreWriter.kt:1658-1659` (`hasReadableVideoTrack` = `runCatching { probe == VALID }.getOrDefault(false)`) and `video/VideoRecorder.kt:1872-1880` / `:1897-1905` (validation FAILED leads to delete).
- **Why:** The recorder sets validation to `SKIPPED` only in the tolerated case where `muxer.stop()` threw over a sample-less degraded audio track (`:439-448`). The clip is then published only if a reopen/extract proves a video track. Any exception in that probe collapses to `false`: `openReadableParcelFd` failing, a provider timeout, or `MediaExtractor.setDataSource` IOException. The result is `FAILED`, then `shouldPublishRecording == false`, then `discardPendingOutput`, and the take is deleted. Launch recovery classifies the same exception as `INDETERMINATE` (`pendingProbeOutcome`) and keeps the row. So the live path is stricter than recovery, and it is stricter in the destructive direction. This is exactly the "dead mic must not delete a good take" class that AGG3-2 and TR4-2 exist to prevent.
- **Failure scenario:** The mic drops mid-REC (BT headset disconnects), `muxer.stop()` throws over the empty AAC track, and the provider is briefly busy (the device is under media scan). The good video is deleted and the user sees "Recording failed".
- **Fix:** Make `hasReadableVideoTrack` tri-state. Only a successful open followed by an extractor that shows no video track counts as INVALID. An exception while opening the provider, or an indeterminate probe, should leave the row REGISTERED and pending for launch recovery (a `RETAINED_*` disposition) instead of deleting it. Log the cause.
- **Confidence:** Medium. The rule is clear from the code; the window is narrow.

## D5. MediaStoreWriter.publish drops every update() failure without a trace [Medium]

- **Where:** `storage/MediaStoreWriter.kt:1052-1068`. The video caller logs only in DEBUG (`video/VideoRecorder.kt:1936-1943`).
- **Why:** `runCatching { update(...) > 0 }.getOrDefault(false)` conflates three things: an exception (SecurityException, a provider crash), a zero-row update (the row vanished or its ownership changed), and success-with-no-change. After three attempts the capture is retained as "saved pending recovery". That is safe, but nothing records why. The post-publish preference `remove` (`:1059-1062`) is also silent. If it fails, a stale REGISTERED entry remains for a published row, and the next launch sweep has to handle it.
- **Failure scenario:** A provider policy change makes `IS_PENDING=0` updates throw for this app. Every shot ends as "retained for recovery", and the release log has no cause.
- **Fix:** Log the last failure (exception or `0 rows`) once after the attempts are used up, through the reserved facade.
- **Confidence:** Medium.

## D6. Still snapshot/encode failure has no log [Medium]

- **Where:** `camera/CameraEngine.kt:5519` (`runCatching { StillSnapshot.from(jpeg) }.getOrNull()`) and `:5531` (`runCatching { processedSnapshot.jpegBytes() }.getOrNull()`).
- **Why:** An OOM on the ~19 MB NV21 copy, an `IllegalArgumentException("Unsupported still format")` from a new device's reader format, or `check(ok) { "YUV→JPEG compress failed" }` all become `PHOTO_SAVE_FAILED` with no cause logged. Every neighboring failure edge (`Photo save dispatch failed`, `DNG write failed`, `Photo processing failed`) does log.
- **Failure scenario:** On a non-PMA110 logical route the HAL delivers a format other than JPEG or YUV_420_888. Every photo fails with a toast and no app log line.
- **Fix:** Add `.onFailure { Log.e("CameraEngine", "Still snapshot failed", it) }` on both, through the reserved facade.
- **Confidence:** High for the missing log. The trigger is Medium.

## D7. A null rawChars fails every still, including JPEG/HEIF-only shots [Medium]

- **Where:** `camera/CameraController.kt:320-322` (`rawChars = runCatching { getCameraCharacteristics(...) }.getOrNull()`, not logged) and `:2262-2264` (`if (chars != null) onPhoto(...) else onError("Missing camera characteristics")`).
- **Why:** Only `DngCreator` needs `CameraCharacteristics`. A transient `CameraAccessException`/`IllegalArgumentException` during `open()` leaves `rawChars` null for the controller's whole lifetime. Every processed still is then rejected after the HAL has already delivered its image, so the captured frame is thrown away.
- **Failure scenario:** A lifecycle race at resume (the same class as the documented `CAMERA_DISABLED` synchronous throw) makes `getCameraCharacteristics` fail once. The preview works, but every shutter press says "Photo capture failed" until the next reopen.
- **Fix:** Log the failure. Require `chars` only when `p.raw != null`: pass a nullable value, or have the engine demand it only in the DNG branch. Alternatively, retry the read lazily on the first capture.
- **Confidence:** Medium.

## D8. tryComplete can deliver onPhoto and then onError for the same shot, and a throwing onError crashes the camera thread [Low]

- **Where:** `camera/CameraController.kt:2262-2266`.
- **Why:** `try { cb.onPhoto(...) } catch (t: Throwable) { cb.onError(t) }` runs `onError` after `onPhoto` may already have queued the processed save and dispatched DNG publication. The engine callback is mostly idempotent: the lane counters use CAS, and `RejectedOutputCleanupReservation.submit` after `cancel` returns false. The user still gets `PHOTO_CAPTURE_FAILED` for a shot that was actually saved. The trigger is a throw from the `finally`-reached `settleRegisteredStillShot → onDone` continuation (BURST/AEB/timelapse). If `onError` itself throws inside the catch, the exception escapes `onImage` on the Camera2 handler thread, which crashes the process.
- **Fix:** Track `photoDelivered` and call `onError` only if `onPhoto` never started. Wrap the catch-branch `onError` in `runCatching` + log.
- **Confidence:** Low. It needs a throwing continuation.

## D9. `runCatching { Thread.sleep(...) }` swallows InterruptedException and clears the interrupt flag [Low]

- **Where:**
  - `storage/MediaStoreWriter.kt:647`, `:670`, `:754`, `:1066`.
  - `camera/CameraEngine.kt:7384` (launch-recovery backoff).
  - `camera/CameraEngine.kt:6099` (mic-release `await`).
  - `gl/GlPipeline.kt:1659`, `:1664` (`await`/`join`).
- **Why:** `runCatching` catches `InterruptedException`, and the thread's interrupt status is lost. A `shutdownNow()` on these executors cannot stop a retry loop that is mid-backoff. The loop keeps doing provider/SQLite work after its owner is retired. Elsewhere the codebase restores the flag correctly (`CameraTeardownTerminal.kt:45`, `RecordingTeardownCoordinator.kt:61`), so this is inconsistent.
- **Fix:** Use a small helper, `sleepOrInterrupt(ms): Boolean`, that catches `InterruptedException`, calls `Thread.currentThread().interrupt()`, and returns false so the loop can exit.
- **Confidence:** Low. It only matters on shutdown/replacement.

## D10. DngWriteResult.Failed reports a complete, recoverable DNG as "save failed" [Low]

- **Where:** `capture/StillCapturePipeline.kt:389-396` and `camera/CameraEngine.kt:5600-5604`.
- **Why:** `Failed` means the bytes were fully written (`outputComplete = true`) and `markWriteComplete` then threw. The engine cancels the cleanup reservation and shows `DNG_SAVE_FAILED`. It does not call `retainCompletedDngForRecovery`, which is the path that handles a tombstoned family and reports `OUTPUT_SAVED_PENDING_RECOVERY`. The row stays REGISTERED and complete, and the next launch adopts and publishes it. The user was told it failed, and a family that was deleted in the meantime gets no immediate DISCARD dispatch.
- **Fix:** Handle `Failed` like marker exhaustion. Route it to `retainCompletedDngForRecovery` with `completionMarkerDurable = false`.
- **Confidence:** Low. `markWriteComplete` normally returns a result rather than throwing.

## D11. SettingsStore ignores the commit result [Low]

- **Where:** `storage/SettingsStore.kt:128`, `:135`, `:143`, `:160` (`prefs.edit(commit = true) { ... }` returns Unit, and `commit()`'s Boolean is dropped).
- **Why:** The synchronous-commit design exists so that a swipe-kill cannot lose a change. A commit that returns false (ENOSPC, I/O error) still loses it silently. MR preset save (`savePreset`) will show the preset as saved in the same session and lose it on relaunch.
- **Fix:** Use `SharedPreferencesDurableEdit` (already used by storage/) or check `edit().…commit()`. Log on false, and for preset save surface a status.
- **Confidence:** Low.

## D12. Persisted `exposureTimeNs` / `fps` / `wbKelvin` are restored unbounded [Low]

- **Where:** `storage/SettingsStore.kt:228`, `:235`, `:238` (`safeLong`/`safeInt` with no clamp). Compare ISO, EV, zoom, and focus, which are clamped at the same seam with the comment "Cap untrusted/corrupt preference numbers before route capabilities are available".
- **Why:** A zero or negative `exposureTimeNs` feeds `ManualControls.withShutterMode` (new in `f67023d5`: `carried <= 0` silently keeps a stale angle) and `previewExposureTrade` before a caps clamp applies. On a route without `supportsManualSensor` the clamp never runs. `fps <= 0` disables ANGLE math silently (`effectiveExposureNs` falls back to `exposureTimeNs`).
- **Fix:** Clamp at load, the same way as the neighboring fields: `exposureTimeNs.coerceIn(1, HAL_SAFE_MAX…)`, `fps.coerceIn(1, 240)`, and `wbKelvin` to its UI range.
- **Confidence:** Low. It needs a corrupt or hand-edited prefs file.

---

## Checked and clean (no finding)

- `SettingsStore.enumOr` / list decode (`:470`, `:475`) and `CaptureFamilyMedia.valueOf` (`MediaStoreWriter.kt:1615`) all fall back safely on unknown persisted names.
- Cursor column access is safe:
  - `getColumnIndexOrThrow` sits inside `runCatching`/`Result` (`MediaStoreWriter.kt:388-395`, `:1312-1316`).
  - `MediaReview.kt:608-616` and `PendingDiscardJournal.kt:696` guard `index < 0`.
- `captureWatchdogTimeoutMs` (`ManualControls.kt:492`) uses saturating arithmetic. `audioPtsUs` cannot overflow at realistic durations. `effectiveExposureNs` guards `fps > 0`.
- `CameraController.capturePhoto`/`tryComplete`/`onCaptureFailed` close Images and cancel the watchdog on every edge. `HeifCapture` closes its writer in `finally`. The `saveProcessedStills` bitmaps are recycled in `finally`.
- The recent union-volume fix (`6b2f07dd`): `resolveVolumeName` is the only producer of `identity.volumeName`, and every replay compares against the same reader, so there is no union/primary mismatch on the replay path.
- The pending-token gate fix (`0ab5c1ba`): `UnsafeRecorderAdmissionToken.owner` is non-null `Any`, so an anonymous `null` owner cannot be admitted as the pending token's owner.
- `LatestHeavyWorkLane` rethrows `CancellationException` correctly. None of the `runCatching` sites reviewed wraps a suspend call, so coroutine cancellation is not swallowed.

## Top 5 by expected field impact

1. **D1:** REC setup failure cause discarded (`VideoRecorder.kt:249`).
2. **D2:** every silent-audio degrade edge is unlogged in release.
3. **D3:** `openParcelFd`/`openOutputStream` swallow provider exceptions, so still saves fail with no log.
4. **D4:** the degraded-audio stop path deletes a good take on a transient probe exception.
5. **D7:** a null `rawChars` fails every processed still for the controller's lifetime.
