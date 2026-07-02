# Deep Code-Quality Review — find-x9-ultra-camera

Reviewer: code-reviewer (Opus)
Scope: all 27 Kotlin sources under `app/src` (26 main + 1 test), plus cross-file interaction analysis.
Method: full read of every file (no sampling), cross-file trace of CameraEngine ↔ CameraController ↔ ViewModel ↔ Screen, GL pipeline, capture, storage, stab, video.

Known-intentional limitations excluded per brief: histogram data placeholder, 10-bit EGL/Log on-device tuning, gated native stabilization (gyro EIS used instead). These are NOT reported below.

---

## File inventory (27 .kt)

| # | File | LOC |
|---|------|-----|
| 1 | camera/CameraEngine.kt | 250 |
| 2 | camera/CameraController.kt | 236 |
| 3 | camera/CameraSelector2.kt | 74 |
| 4 | camera/CameraState.kt | 76 |
| 5 | camera/CaptureCapabilities.kt | 117 |
| 6 | camera/ManualControls.kt | 195 |
| 7 | camera/VendorTagInspector.kt | 57 |
| 8 | capture/DngCapture.kt | 30 |
| 9 | capture/HeifCapture.kt | 25 |
| 10 | focus/FocusMapping.kt | 35 |
| 11 | gl/EglCore.kt | 97 |
| 12 | gl/FlipRenderer.kt | 192 |
| 13 | gl/GlPipeline.kt | 188 |
| 14 | gl/Shaders.kt | 101 |
| 15 | MainActivity.kt | 91 |
| 16 | stab/GyroEis.kt | 90 |
| 17 | storage/MediaStoreWriter.kt | 84 |
| 18 | TeleCameraApp.kt | 6 |
| 19 | ui/CameraActions.kt | 83 |
| 20 | ui/CameraScreen.kt | 353 |
| 21 | ui/CameraViewModel.kt | 179 |
| 22 | ui/controls/ProControls.kt | 606 |
| 23 | ui/overlays/Overlays.kt | 252 |
| 24 | ui/theme/Theme.kt | 41 |
| 25 | video/ColorProfiles.kt | 52 |
| 26 | video/VideoRecorder.kt | 228 |
| 27 | test/focus/FocusMappingTest.kt | 132 |

Every file above was reviewed. No file skipped.

---

## Severity summary

- CRITICAL: 0
- HIGH: 4  (C1 config-fail silent death, C2 EGLSurface double-create/leak, C3 video EOS truncation/muxer crash, C4 muxer-never-starts crash)
- MEDIUM: 11
- LOW: 8

Verdict: **REQUEST CHANGES** (multiple HIGH-confidence functional defects and resource leaks in the camera/GL/video core).

---

## HIGH severity

### H1 — `onConfigureFailed` silently dead-ends; the "handled by the caller" comment is false
File: `camera/CameraController.kt:130-132` (also engine wiring `camera/CameraEngine.kt:106-114`)
Confidence: High — Confirmed.

`configureSession` installs:
```kotlin
override fun onConfigureFailed(s: CameraCaptureSession) {
    // Fallback path (e.g. 10-bit + RAW combo unsupported) is handled by the caller.
}
```
There is NO caller fallback anywhere (grep-verified). When the session fails to configure (a very real case on this exact device: HLG10 preview + RAW_SENSOR + JPEG is a demanding combo, and mixing an HLG10 dynamic-range preview with SDR still-capture streams is a known Camera2 configuration hazard), neither `onReady` nor `onError` fires. The ViewModel status stays whatever it was, the preview surface never receives frames, and the user sees a permanent black screen with no message.
Failure scenario: On a unit where `supportsHlg10()` is true but the HLG10+RAW+JPEG stream combination is rejected, the app launches to a black viewfinder forever; capture does nothing.
Fix: Invoke `onError.onError(IllegalStateException("session configure failed"))` from `onConfigureFailed`, and/or implement the promised fallback: retry `configureSession` with `tenBitHlg=false` and/or without the RAW reader. Because `onError` currently only sets a status string, at minimum add a real retry path (drop 10-bit, then drop RAW) before surfacing the error. Delete or correct the misleading comment.

### H2 — `setPreviewOutput` creates a new EGLWindowSurface without releasing the prior one (double-create on same native window)
File: `gl/GlPipeline.kt:67-91` (and re-entry via `onPreviewSurfaceChanged` → `camera/CameraEngine.kt:90-93`, `ui/CameraScreen.kt:92-99`)
Confidence: Medium-High — Confirmed code defect; runtime symptom is driver-dependent (risk-needs-validation on device).

The non-null branch does:
```kotlin
previewW = width; previewH = height
previewEgl = core.createWindowSurface(surface)   // never releases an existing previewEgl
core.makeCurrent(previewEgl)
```
`SurfaceView` always delivers `surfaceCreated` immediately followed by `surfaceChanged` (and again on every size change / foreground cycle). `surfaceChanged` → `onPreviewSurfaceChanged` → `setPreviewOutput(sameSurface, w, h)` calls `createWindowSurface` again on the *same* `ANativeWindow` while the first `EGLSurface` is still alive. EGL forbids two window surfaces on one native window: `eglCreateWindowSurface` returns `EGL_BAD_ALLOC`, and `EglCore.checkEglError`/`check(...)` throws on the GL HandlerThread. That exception is uncaught (see also H-note R2) → process crash. On drivers that tolerate it, you instead get a leaked `EGLSurface` every call.
Failure scenario: Normal launch — `surfaceCreated` then `surfaceChanged` fire back-to-back; the second `createWindowSurface` on the same window throws `EGL_BAD_ALLOC` → app crash, or (best case) an EGLSurface leaks on every preview (re)configuration and every app resume.
Fix: At the top of the non-null branch, release the existing surface first:
```kotlin
if (previewEgl != EGL14.EGL_NO_SURFACE) { core.releaseSurface(previewEgl); previewEgl = EGL14.EGL_NO_SURFACE }
```
Also skip recreation when width/height are unchanged (`onPreviewSurfaceChanged` only needs to update `previewW/H`, not rebuild the window surface).

### H3 — Video drain breaks on `!running` before EOS, truncating output / racing muxer stop
File: `video/VideoRecorder.kt:105-130`, stop sequence `73-103`
Confidence: Medium — Likely.

`stop()` sets `running = false` THEN calls `signalEndOfInputStream()`. `drainVideo` does:
```kotlin
idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!running) break
```
Between `running=false` and the encoder actually emitting its final EOS-flagged buffers, a `TRY_AGAIN_LATER` can occur; the loop then `break`s early, so trailing encoded frames are dropped and the `BUFFER_FLAG_END_OF_STREAM` handling (the intended exit) is skipped. The last GOP and clean stream finalization can be lost.
Failure scenario: Stop a recording; the tail (up to a full I-frame interval) is missing, occasionally producing a file that players report as truncated.
Fix: Do not break on `!running`; only break on `BUFFER_FLAG_END_OF_STREAM`. Order stop as: `signalEndOfInputStream()` first, keep draining until the EOS buffer is seen (bounded by `videoThread.join(timeout)`), and set `running=false` only for the audio path.

### H4 — Muxer may never start (audio track never added) → `writeSampleData` on an unstarted muxer crashes the drain thread
File: `video/VideoRecorder.kt:210-219`, `132-154`, `105-130`
Confidence: Medium — Likely / risk-needs-validation.

`maybeStartMuxer()` requires `videoTrack>=0 && (expectedTracks==1 || audioTrack>=0)`. If audio was requested (`expectedTracks=2`) but the audio encoder never emits `INFO_OUTPUT_FORMAT_CHANGED` (e.g. `AudioRecord.startRecording()` throws/stays uninitialized, or the AAC encoder stalls), `muxerStarted` stays false. `awaitMuxerStart()` busy-waits `while (running && !muxerStarted)`; on `stop()` `running` flips false, the wait returns, and the very next line executes `muxer?.writeSampleData(videoTrack, buf, info)` with a muxer that was never `start()`ed → `IllegalStateException` on the `video-drain` thread. That exception is uncaught (the write is inside `synchronized`, not `runCatching`) → app crash.
Additionally, if `startAudio()` throws after `videoCodec.start()` (e.g. `MediaCodec.createEncoderByType` failure at line 148), it propagates out of the unguarded `start()` (line 66) up through `CameraEngine.startRecording`/`VM.onToggleRecording` (also unguarded) → crash, leaving `videoCodec`/`inputSurface` half-initialized.
Failure scenario: Mic contended by another app or AAC init hiccup → recording either crashes on stop or throws at start.
Fix: In `awaitMuxerStart`, if it exits because `running` went false while `!muxerStarted`, abort the write (return a flag / check `muxerStarted` before `writeSampleData`). Wrap the muxer writes in `runCatching`. Guard `startAudio()` failures so audio simply degrades to `expectedTracks=1` instead of aborting the whole recording; wrap `start()` internals so a failure returns null cleanly.

---

## MEDIUM severity

### M1 — `saveHeif` leaks Bitmaps and orphans a pending MediaStore entry on the error path
File: `camera/CameraEngine.kt:158-171`
Confidence: High — Confirmed.

```kotlin
val decoded = BitmapFactory.decodeByteArray(...) ?: return
val rotated = rotate180(decoded)
val uri = MediaStoreWriter.createPendingImage(...) ?: return   // <-- leaks decoded + rotated
MediaStoreWriter.openParcelFd(...)?.use { HeifCapture.writeHeif(...) } // if fd null, writeHeif skipped
MediaStoreWriter.publish(context, uri)                          // published even if write skipped/failed
if (rotated != decoded) decoded.recycle(); rotated.recycle()
```
Two defects: (1) the `createPendingImage ?: return` path never recycles `decoded`/`rotated` → native bitmap leak per failed save; over a session this accumulates. (2) If `openParcelFd` returns null or `writeHeif` throws, `publish()` still runs (or the pending row is left dangling on throw), producing an empty/half-written HEIC that becomes visible in the gallery, or an orphaned `IS_PENDING` row that never gets cleaned. `saveDng` (`173-180`) has the same publish-on-empty / orphan-on-throw problem.
Fix: Wrap the body so bitmaps are recycled in a `finally`; only `publish` after a confirmed successful write; on failure call `MediaStoreWriter.delete(context, uri)` (which already exists and is used by `savePhotoBytes`).

### M2 — Partial capture leaks `Image`s and can wedge the ImageReader; `onCaptureFailed` doesn't close acquired images
File: `camera/CameraController.kt:183-215`, `176-179`
Confidence: Medium — Likely.

`tryComplete` only closes images once ALL of {result, jpeg?, raw?} are present. If a requested image never arrives (RAW silently dropped, or `onCaptureFailed` fires after a JPEG was already acquired), the acquired `Image` is never closed. `onCaptureFailed` sets `pending = null` without closing `pending.jpeg/raw`. With `ImageReader` maxImages=2, two such events exhaust the reader and subsequent `acquireNextImage` returns null → capture stops working until session recreation.
Failure scenario: Enable HEIF+DNG; a capture where RAW is dropped by the HAL leaves the JPEG image unclosed; after two occurrences, photo capture goes dead.
Fix: In `onCaptureFailed`, close any already-stored images before nulling `pending`. Add a timeout/cleanup that closes partial images if all parts don't arrive within N ms.

### M3 — `setCameraOverride` reopens the camera while the previous device is still closing, and never refreshes `videoSize` / GL camera size
File: `camera/CameraEngine.kt:128-140`
Confidence: Medium — Likely.

`controller?.close()` posts an async close to the old camera thread; `openCamera(input)` then immediately opens a new device — potentially the same logical camera — before the old `device.close()` completes, which Camera2 handles inconsistently (open error / onDisconnected on the old one). Also, `videoSize` and `gl.setCameraPreviewSize(...)` are computed only once in `onPreviewSurfaceAvailable`; an override to a camera with different output sizes keeps the stale `videoSize`, so preview aspect ("cover" scale in `FlipRenderer`), EIS focal scaling, and video encoder size are wrong for the new lens. `applyStabilization()` is called but it uses `caps.nativeFocalInImageWidths` (updated) yet `gl.setCameraPreviewSize` is not re-invoked.
Fix: Recompute `videoSize = chooseVideoSize(sel)` and call `gl.setCameraPreviewSize(...)` inside `setCameraOverride`; serialize reopen after close (open the new device from the old controller's close-completion, or await close).

### M4 — Level (horizon) overlay is never fed roll → always shows perfectly level
File: `ui/CameraScreen.kt:111-113`, consumer `ui/overlays/Overlays.kt:110-131`, available source `stab/GyroEis.kt:55`
Confidence: High — Confirmed.

`LevelOverlay(modifier = Modifier.fillMaxSize())` is called with the default `rollDegrees = 0f`. So `isLevel` is always true (green) and the moving line never tilts. The gyro already computes roll (`corrRoll`, and integrated `angRoll`), but no roll value is exposed through `CameraUiState`/`CameraActions` to the UI. The level gauge toggle therefore renders a decorative, non-functional indicator.
Fix: Expose device roll (an absolute roll from the accelerometer/gravity vector is more appropriate than the high-pass `corrRoll`, which is tuned for shake) via `CameraUiState`, update it on a cadence, and pass it into `LevelOverlay(rollDegrees = state.levelRollDeg)`.

### M5 — `punchIn` toggle is inert (dead feature)
File: state `camera/CameraState.kt:57`, toggle `ui/controls/ProControls.kt:551`, VM `ui/CameraViewModel.kt:123`
Confidence: High — Confirmed.

`punchIn` is stored in state and toggled, but grep shows no consumer: nothing zooms/crops the preview when it's on. For a manual-focus tele app, focus punch-in is a headline assist; shipping a toggle that does nothing is misleading.
Fix: Either wire `punchIn` into the GL preview (a temporary center crop-zoom in `FlipRenderer.draw`, independent of the encoder path) or remove the toggle until implemented.

### M6 — Self-timer countdown is re-entrant and not cancelled by mode/record changes
File: `ui/CameraViewModel.kt:129-149, 151-161`
Confidence: Medium — Likely.

`startCountdown` posts a new anonymous `Runnable` to `mainHandler` each time `onCapturePhoto` is called. Tapping the shutter twice during a countdown creates two concurrent tickers reading/writing the same `timerCountdownSec`, double-decrementing and firing two captures. Switching to VIDEO mode or starting a recording mid-countdown does not cancel the pending tick, so a photo capture still fires later in the wrong mode.
Failure scenario: Double-tap the 10s timer → two shutter fires / an erratic countdown; or switch to video during countdown → a stray photo is taken.
Fix: Guard against re-entry (ignore taps while `timerCountdownSec > 0`, or `removeCallbacks` the prior tick), and cancel the countdown in `onModeChange`/`onToggleRecording`.

### M7 — Flash: AUTO is a no-op; ON uses `FLASH_MODE_SINGLE` on the repeating preview request
File: `camera/ManualControls.kt:125-131`, applied in preview `camera/CameraController.kt:142-146`
Confidence: Medium — Likely.

`FlashMode.AUTO` maps to `FLASH_MODE_OFF` — auto flash is simply not implemented (auto flash requires `CONTROL_AE_MODE_ON_AUTO_FLASH`, not `FLASH_MODE`). `FlashMode.ON` maps to `FLASH_MODE_SINGLE`, which is applied to BOTH the repeating preview request and the still request; on a repeating request `FLASH_MODE_SINGLE` behaves inconsistently (may attempt to strobe on preview) and won't reliably fire synchronized with the still. With `CONTROL_AE_MODE_ON`, `FLASH_MODE` is also frequently overridden by the AE routine.
Fix: Implement flash via AE modes: AUTO → `CONTROL_AE_MODE_ON_AUTO_FLASH`, ON → `CONTROL_AE_MODE_ON_ALWAYS_FLASH` (with a precapture AE trigger for stills), TORCH → `FLASH_MODE_TORCH`, OFF → `CONTROL_AE_MODE_ON` + `FLASH_MODE_OFF`. Do not set `FLASH_MODE_SINGLE` on the repeating request.

### M8 — App never releases the camera when backgrounded; `onDisconnected` doesn't notify the engine
File: `MainActivity.kt:41-68` (no onStop/onPause), `camera/CameraController.kt:84`, `camera/CameraEngine.kt:95-99`
Confidence: Medium — Likely.

There is no `onStop`/`onPause` teardown; `engine.release()` runs only in `CameraViewModel.onCleared()` (activity finishing). While backgrounded the app holds the camera open (plus `FLAG_KEEP_SCREEN_ON`). When another app grabs the camera, `CameraDevice.StateCallback.onDisconnected` calls `controller.close()`, but `CameraEngine.controller` still points at the now-dead controller; a later `capturePhoto`/`updateControls` posts to the handler, finds `device == null`, and silently `return@post` — capture appears frozen with no user feedback, and there is no auto-reopen on return to foreground.
Fix: Stop the repeating request / release on `onStop` and reopen on `onStart` (or move lifecycle into the ViewModel with a proper reopen path). Surface a status message and trigger reopen on `onDisconnected`.

### M9 — GL teardown can release the input Surface while the camera session still targets it
File: `camera/CameraEngine.kt:205-213`, `gl/GlPipeline.kt:163-183`
Confidence: Medium — Risk-needs-validation.

`release()` calls `controller?.close()` (async post to the camera thread) and then `gl.stop()` (async post to the GL thread, which calls `surfaceTexture.release()` and `inputSurface.release()`). These two teardown posts are unordered across threads; the GL thread can release the `Surface`/`SurfaceTexture` that the still-closing capture session is using as an output target, which can trigger native `BufferQueue abandoned` errors or a crash.
Fix: Sequence teardown: stop the repeating request and fully close the session/device first (await completion), then release GL resources. At minimum, have `CameraController.close()` complete before `gl.stop()` releases the shared surface.

### M10 — `onPreviewSurfaceChanged` rebuilds the EGL window surface on every size callback (ties into H2)
File: `gl/GlPipeline.kt:94-99` vs `67-91`, caller `camera/CameraEngine.kt:90-93`
Confidence: Medium — Confirmed (same root as H2, separate symptom).

Every `surfaceChanged` re-enters `setPreviewOutput` and rebuilds the window surface even when only the reported size changed and the underlying window is identical. Besides the H2 leak/crash, this is wasteful and drops a frame each time. `setCameraPreviewSize` (the method that actually needs new dims) is separate and correct; the preview-output path should just update `previewW/previewH`.
Fix: If `surface` is the same and already has a live `previewEgl`, only update `previewW/previewH` (and the renderer viewport is derived at draw time), do not recreate the EGLSurface.

### M11 — Manual WB sets gains in TRANSFORM_MATRIX mode without a color-correction transform
File: `camera/ManualControls.kt:104-113`
Confidence: Medium — Risk-needs-validation.

`applyWhiteBalance` sets `COLOR_CORRECTION_MODE = TRANSFORM_MATRIX` and `COLOR_CORRECTION_GAINS`, but never sets `COLOR_CORRECTION_TRANSFORM`. In `TRANSFORM_MATRIX` mode the HAL applies both the gains and the transform; leaving the transform unspecified yields an undefined/last-used or identity matrix, so manual white balance may produce a color cast that doesn't match the intended CCT. `FAST`/`HIGH_QUALITY` correction modes apply gains without a required transform and are the safer choice when you only intend to steer channel gains.
Fix: Use `COLOR_CORRECTION_MODE_FAST` (or `HIGH_QUALITY`) together with `COLOR_CORRECTION_GAINS`, or additionally supply a sane `COLOR_CORRECTION_TRANSFORM` (e.g. identity in sensor space) when using `TRANSFORM_MATRIX`.

---

## LOW severity

### L1 — Degenerate Slider ranges before caps are ready
File: `ui/controls/ProControls.kt:357-392` (ISO/shutter/EV)
Confidence: Medium.
When `caps == null` (panel expanded before camera opens), `isoRange`/`evRange` collapse to `Range(x, x)` producing `valueRange` where start == endInclusive. Material3 `Slider` computes a fraction with a zero-width range (NaN, coerced); it typically renders oddly rather than crashing, but it's fragile. Guard against equal-bound ranges (disable the slider or widen by 1).

### L2 — `CameraSelector2.pickCloser` can keep a zero-focal placeholder over a valid far lens
File: `camera/CameraSelector2.kt:59-65`
Confidence: Low.
If the first enumerated back camera reports focal length 0 (unknown), it becomes `best` with `equivFocalMm = 0`; a later valid lens only replaces it if `abs(equiv-70) < abs(0-70)=70`, i.e. only lenses within 0–140mm-equiv. A valid 230mm lens (`abs=160`) would NOT displace the bogus 0-focal entry. Rare in practice. Fix: skip candidates with `equivFocalMm <= 0` from ever becoming `best` unless nothing else exists.

### L3 — Focus peaking samples axis-aligned texels after texcoord rotation
File: `gl/Shaders.kt:80-88`, `gl/FlipRenderer.kt:139`
Confidence: Low.
`uTexel = (1/previewW, 1/previewH)` is in unrotated camera-texel space, but `vTexCoord` has been rotated (sensor + 180°). On 90°/270° content the neighbor-sampling axes are swapped, so gradient detection is slightly anisotropic. Cosmetic. Fix: rotate the texel offsets by the same rotation, or compute gradients in screen space.

### L4 — HEIF path double-compresses and ignores `jpegQuality`
File: `camera/CameraEngine.kt:158-167`, `capture/HeifCapture.kt:13`
Confidence: Medium.
The still is captured as JPEG, decoded to a Bitmap, then re-encoded to HEIF at a hardcoded quality 95 — a JPEG→bitmap→HEIF round trip that discards detail and ignores `controls.jpegQuality`. Consider capturing HEIC directly (or `YUV_420_888`/`ImageFormat.HEIC` reader) to avoid the transcode, and thread the user quality through.

### L5 — GL thread exceptions are uncaught (EglCore init, shader link/compile, draw)
File: `gl/GlPipeline.kt:64,185-187`, `gl/EglCore.kt:25-38`, `gl/FlipRenderer.kt:168-190`
Confidence: Medium.
Every GL op runs via `handler.post { ... }` with no try/catch; `EglCore`'s `check(...)`/`error(...)` and `FlipRenderer` `check(link/compile)` throw on the GL HandlerThread, which crashes the process with no user-facing status. Wrap posted GL work and surface failures through `onStatus` instead of crashing.

### L6 — `onSensorChanged` integrates without a magnitude deadband; slow drift can accumulate into the correction
File: `stab/GyroEis.kt:57-74`
Confidence: Low.
`angYaw/Pitch/Roll` integrate raw gyro indefinitely; only a high-pass (`smooth*`) removes low frequency. Gyro bias makes `ang*` drift unbounded, and while the high-pass removes steady drift, near the `LOW_PASS_ALPHA` corner a slow bias still leaks into `corr*`. Consider a small angular-rate deadband and/or bias estimation. (Axis/sign tuning is already flagged as intended on-device work.)

### L7 — `bitRateFor` / video size assume 16:9; non-16:9 sensors fall back to largest arbitrary size
File: `camera/CameraEngine.kt:222-237`
Confidence: Low.
`chooseVideoSize` filters strict `height*16 == width*9`; if the tele physical camera exposes only non-16:9 sizes for `SurfaceTexture`, it falls back to the single largest size (any aspect), which the "cover" scaler will crop. Acceptable, but worth documenting/clamping to a max area to bound encoder bitrate.

### L8 — Minor: `release()` leaves `caps`/`selection`/`previewSurface` non-null; `MediaStoreWriter.savePhotoBytes` unused
File: `camera/CameraEngine.kt:205-213`, `storage/MediaStoreWriter.kt:16-33`
Confidence: Low.
`savePhotoBytes` (with its correct delete-on-failure pattern) is dead code — the live HEIF/DNG paths in CameraEngine reimplement saving WITHOUT that cleanup (see M1). Either route saves through the safe helper or delete the unused one to avoid drift.

---

## Cross-file interaction notes (verified, not additional defects)

- CameraController pending state is mutated exclusively on the single camera `handler` thread (capture callbacks and ImageReader listener both posted to `handler`), so the `synchronized(p)`/`@Volatile` are belt-and-suspenders, not load-bearing — no data race found there. The real gaps are lifecycle/close (M2, M8), not concurrency.
- GL commands are correctly funneled through one HandlerThread; the pipeline's thread-affinity is sound. The defects are surface-lifecycle (H2, M9, M10) and error handling (L5), not cross-thread state.
- FocusMapping ↔ FocusSlider ↔ ViewModel round-trip is clean and unit-tested; `dioptersToSlider`/`sliderToDiopters` are inverses and well covered. No issues.
- `applyManualControls` clamps every value to caps ranges and gates on capability flags — good defensive style; the exceptions are WB transform (M11) and flash semantics (M7).

## Positive observations

- Clear layering: stateless Compose UI → `CameraActions` → `CameraViewModel` → `CameraEngine` facade → components. UI never touches Camera2/GL directly. Strong SRP adherence.
- `CameraCaps` flattens hardware queries once and gates the UI/controls off it; `applyManualControls` consistently coerces to hardware ranges.
- `FocusMapping` is pure, documented, and thoroughly unit-tested (edge cases: clamping, monotonicity, round-trip, fixed-focus).
- `MediaStoreWriter` uses the IS_PENDING convention correctly and even provides delete-on-failure (`savePhotoBytes`) — the pattern just isn't reused by the live capture paths (L8/M1).
- Immutable `ManualControls`/`CameraUiState` snapshots make the state flow easy to reason about and preview.
- Honest inline documentation of approximations (HLG/Log look, EIS tuning) sets correct expectations.

## Recommendation

REQUEST CHANGES. Address H1 (silent config-fail black screen) and H2 (EGL double-create) first — both hit the normal launch path. Then H3/H4 (recording integrity/crash) and the resource-leak/lifecycle MEDIUMs (M1, M2, M8, M9). M4/M5 (level, punch-in) are user-visible dead features that should be wired up or removed to avoid shipping non-functional pro controls.
