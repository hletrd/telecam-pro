# Debugger Review — Latent Bug Surface (2026-07-03)

Scope: all 29 Kotlin files under `app/src/main/kotlin/com/hletrd/findx9tele/**` at HEAD
(`3cd60c1 fix(focus): more reliable tap-to-focus`). This is a fresh pass on top of the four prior
reviews in `.context/reviews/{architect,code-reviewer,perf-reviewer,security-reviewer}.md`, whose
findings were already resolved per `docs/plans/2026-07-02-review-fixes.md`. Everything below is a
**new** finding not covered by that plan, found by static reading — no device available to this
agent, so device-only items are marked accordingly.

Method: read every file in the module map (camera/, gl/, stab/, capture/, video/, storage/, ui/**);
traced null-safety, thread ownership vs the documented threading model, exception paths around
Camera2/EGL/MediaCodec/AudioRecord calls (which the codebase's own conventions wrap in
`runCatching` — checked every call for consistency with that pattern), and state machines
(capture pending, muxer start, session fallback ladder, async startup).

## Summary

| ID | Severity | Area | One-line |
|----|----------|------|----------|
| BUG-1 | High | camera/CameraController.kt, camera/CameraEngine.kt, ui/controls/ProControls.kt | Shutter silently does nothing (no error, breaks BURST/AEB chains) when both HEIF and DNG output are toggled off, or the session fell back to preview-only |
| BUG-2 | Medium-High | camera/CaptureCapabilities.kt, camera/CameraEngine.kt | `CameraCaps.read()`'s characteristics lookup can throw uncaught on two call paths (async startup thread, and the **main thread** via camera-override) — crashes the whole process |
| BUG-3 | Medium | video/VideoRecorder.kt | `stop()` releases `videoCodec`/`muxer` without guaranteeing the drain threads have exited; a stalled EOS races a concurrent `MediaCodec`/`muxer!!` access → crash |
| BUG-4 | Medium-High | video/VideoRecorder.kt | `AudioRecord` state is never checked before `startRecording()`; an unusable mic throws uncaught on the audio-encode thread → crash |
| BUG-5 | Medium | camera/CameraEngine.kt | `onPreviewSurfaceAvailable`'s `started`/`starting` guards can bind the GL pipeline to a stale `Surface` if the TextureView surface is recreated during the async startup window |
| L-1 | Low | ui/CameraScreen.kt | Overlay counter-rotation spins the long way around at the 270°→0° device-orientation wraparound |
| L-2 | Low | ui/CameraViewModel.kt | `orientationTicker`/level polling keep running (200ms/100ms Handler wakeups) while the app is backgrounded |
| L-3 | Low | ui/controls/ProSheet.kt, camera/CameraEngine.kt | `CameraUiState.videoResolution` UI default (3840×2160) is never synced with the size `CameraEngine.chooseVideoSize()` actually auto-selects at startup |

---

## High

### BUG-1 — Shutter silently no-ops; breaks BURST/AEB; can strand exposure comp

**Root cause**: `CameraController.capturePhoto` (`camera/CameraController.kt:309-314`):
```kotlin
fun capturePhoto(wantJpeg: Boolean, wantRaw: Boolean, cb: PhotoCallback) = handler.post {
    val camera = device ?: return@post
    val s = session ?: return@post
    val jpeg = jpegReader?.surface?.takeIf { wantJpeg }
    val raw = rawReader?.surface?.takeIf { wantRaw && caps.supportsRaw }
    if (jpeg == null && raw == null) return@post   // <-- cb is NEVER invoked
    ...
```
When both `jpeg` and `raw` resolve to null, the function returns without ever calling `cb.onPhoto`
or `cb.onError`. The caller-supplied `PhotoCallback` (built in
`CameraEngine.photoCallback()`, `camera/CameraEngine.kt:338-369`) is simply dropped.

**Trigger 1 (always reachable via UI, no special device state needed)**: the Shooting tab's
`PhotoFormatToggles` (`ui/controls/ProControls.kt:441-466`) lets the user turn off both `HEIF` and
`DNG` independently — `PhotoFormats` has no mutual-exclusion guard (`camera/CameraState.kt:57-60`).
With both off, `wantJpeg=false` and `wantRaw=false`, so `jpeg` and `raw` are both null and the
early return fires.

**Trigger 2 (device-dependent)**: the session fallback ladder's attempt 3
(`camera/CameraController.kt:112-137`, "preview only") never builds a `jpegReader`/`rawReader`, so
the same early return fires for every shutter press once the HAL has forced the session down to
preview-only.

**Failure — misbehave, not crash**: pressing the shutter does nothing. Specifically:
- The status message meant for exactly this case,
  `if (!formats.heif && !formats.dngRaw) onStatus?.invoke("No output format selected")`
  (`camera/CameraEngine.kt:364`), is **dead code** — it lives inside the `PhotoCallback.onPhoto`
  that is never invoked, so the user gets no feedback at all.
- `CameraEngine.captureBurst` (`camera/CameraEngine.kt:290-296`) chains `fire(shot+1)` from inside
  the `onDone` callback passed to `photoCallback(...)`; since the callback never fires, a BURST
  shot silently captures zero frames and stops.
- `CameraEngine.captureAeb` (`camera/CameraEngine.kt:304-314`) is worse: `fire(0)` calls
  `ctrl.updateControls(controls.copy(exposureCompensation = steps[0]))` (sets EV to -2) **before**
  calling `capturePhoto`; since the callback chain never reaches `fire(steps.size)` (the branch
  that restores `controls` unmodified), the camera's exposure compensation is left stuck at -2 EV
  until the user manually touches an exposure control again.

**Fix**: in `CameraController.capturePhoto`, call `cb.onError(...)` (not a silent `return@post`)
when both `jpeg` and `raw` are null, so `CameraEngine`'s existing "No output format selected"
message actually fires and BURST/AEB chains terminate/reset correctly. Optionally also disallow
turning off both formats in `PhotoFormatToggles` (keep at least one selected).

**Confidence**: High — confirmed by static reading alone (Trigger 1 requires no device, no race,
no HAL quirk; just toggling two switches in the Shooting tab).

---

## Medium-High

### BUG-2 — Unguarded `CameraCaps.read()` characteristics lookup can crash the app (one call site on the main thread)

**Root cause**: `CameraCaps.read` (`camera/CaptureCapabilities.kt:69-72`):
```kotlin
val chars: CameraCharacteristics = runCatching {
    manager.getCameraCharacteristics(physicalId ?: logicalId)
}.getOrElse { manager.getCameraCharacteristics(logicalId) }
```
The `getOrElse` fallback lambda calls `getCameraCharacteristics` again **outside** any
`runCatching`. `CameraManager.getCameraCharacteristics()` can throw `CameraAccessException`
(service unreachable/disconnected) or `IllegalArgumentException` (bad id); on this device, the
selected tele camera is normally opened as the standalone id (per `CameraSelector2`'s
prefer-`physicalId==null` tie-break, documented in `CLAUDE.md`), so `physicalId ?: logicalId`
already equals `logicalId` — meaning the "fallback" is often the exact same call repeated, so a
transient camera-service hiccup fails both attempts identically and the exception propagates out
of `CameraCaps.read()` uncaught. Every other camera-characteristics lookup in this codebase
(`CameraSelector2.equivFocalOf`, `CameraEngine.chooseVideoSize`, `CameraController.open`'s
`rawChars`) is wrapped in `runCatching { }.getOrNull()` with a safe fallback — this is the one
call site that isn't, breaking the codebase's own established pattern.

Two call sites are affected:
1. `CameraEngine.onPreviewSurfaceAvailable` (`camera/CameraEngine.kt:102`), inside
   `setupExecutor.execute { ... }` — an uncaught exception here crashes the app on Android (an
   uncaught exception on **any** thread, not just main, terminates the process by default), but
   additionally leaves `starting=true` permanently set with no reset, so even a successful retry of
   `onPreviewSurfaceAvailable` (e.g. on TextureView recreation) would short-circuit at the
   `if (starting) return` guard on `camera/CameraEngine.kt:87` forever — except the crash happens
   first.
2. `CameraEngine.setCameraOverride` (`camera/CameraEngine.kt:255-271`, call at line 262) — this
   runs synchronously on whatever thread calls it, which is the **main/UI thread** (the Advanced
   tab's "Camera Override" row calls straight through `CameraViewModel.onCameraOverride` →
   `engine.setCameraOverride(id)`). An exception here is a guaranteed, immediate main-thread crash.

**Trigger**: a `CameraAccessException` from the camera service — plausible on this device given
the codebase's own documented HAL fragility (`CLAUDE.md` "Hard-won device facts": HAL SIGSEGVs,
"Broken pipe -32", multicamera crashes) — or simply picking an invalid id via the free-text Camera
Override field (format `"logicalId:physicalId"`, `camera/CameraSelector2.kt:33-40`) that doesn't
exist on the device.

**Fix**: wrap the `getOrElse` fallback body in its own `runCatching`, and/or wrap the whole
`CameraCaps.read()` call at both call sites in `runCatching { }.onFailure { onStatus?.invoke(...) }`
so a bad id or a transient service error degrades to a status message instead of a crash; also
reset `starting = false` on that failure path in `onPreviewSurfaceAvailable`.

**Confidence**: Medium-High — the code gap is unambiguous and inconsistent with the rest of the
file; actually triggering it needs a real device condition (service hiccup or bad override id),
so full reproduction is needs-device, but the missing guard itself is confirmed by reading alone.

### BUG-4 — `AudioRecord` state never checked before `startRecording()`; unguarded on a fire-and-forget thread

**Root cause**: `VideoRecorder.startAudio()` (`video/VideoRecorder.kt:184-206`) constructs an
`AudioRecord` and, as long as the constructor doesn't throw, proceeds to configure the AAC encoder
and launch the `audio-encode` thread — without ever checking
`record.state == AudioRecord.STATE_INITIALIZED`:
```kotlin
val record = runCatching { AudioRecord(MediaRecorder.AudioSource.CAMCORDER, ...) }.getOrNull()
    ?: run { expectedTracks = 1; return }
audioRecord = record
...
audioThread = thread(name = "audio-encode") { runAudio(record, codec) }
```
Per the Android `AudioRecord` docs, the constructor can complete successfully (no exception) while
leaving the object in `STATE_UNINITIALIZED` if the requested audio source/config isn't actually
available (e.g. `CAMCORDER` source busy or unsupported in the current camera routing). The first
call `runAudio()` makes is `record.startRecording()` (`video/VideoRecorder.kt:209`), completely
unguarded — `IllegalStateException: startRecording() called on an uninitialized AudioRecord.` The
caller's `runCatching { startAudio() }` in `start()` (`video/VideoRecorder.kt:106`) does **not**
catch this: `startAudio()` itself returns successfully after merely *launching* the thread: the
exception happens later, on a separate thread, outside that `runCatching`'s stack.

**Failure**: uncaught `IllegalStateException` on the `audio-encode` thread → crashes the whole app
process (per Android's default uncaught-exception-kills-process behavior on any thread).

**Trigger**: starting a video recording while the CAMCORDER audio source is unavailable/busy
(another app holding the mic, or a HAL routing quirk given this device's already-documented audio
HAL touchiness is unknown but plausible) with "Record Audio" enabled.

**Fix**: after constructing `AudioRecord`, check `record.state == AudioRecord.STATE_INITIALIZED`;
if not, release it and degrade to video-only (`expectedTracks = 1`) exactly like the existing
`getOrNull()` failure path already does. Also wrap the body of `runAudio()`/`drainVideo()` in a
top-level `try/catch` so a later, unrelated `MediaCodec` failure degrades instead of crashing (see
BUG-3, same file).

**Confidence**: Medium-High — the missing state check is unambiguous by reading the Android
`AudioRecord` contract; needs a real device/mic-contention scenario to actually fire.

---

## Medium

### BUG-3 — `VideoRecorder.stop()` can release `MediaCodec`/`MediaMuxer` while the drain threads are still using them

**Root cause**: `stop()` (`video/VideoRecorder.kt:123-153`):
```kotlin
fun stop() {
    running = false
    runCatching { videoCodec?.signalEndOfInputStream() }
    videoThread?.join(3000)
    audioThread?.join(3000)
    ...
    runCatching { videoCodec?.stop() }
    runCatching { videoCodec?.release() }
    ...
    runCatching { muxer?.release() }
    ...
}
```
`drainVideo()` (`video/VideoRecorder.kt:155-182`) intentionally loops until it sees the
`BUFFER_FLAG_END_OF_STREAM` output buffer (this is the H-EOS fix from the prior review pass — good,
it prevents tail-frame truncation) — but nothing bounds that loop except the encoder eventually
emitting EOS. If `signalEndOfInputStream()` silently fails (its exception is swallowed by
`runCatching`) or the encoder simply stalls and never emits the EOS buffer, `videoThread?.join(3000)`
times out after 3s and **`stop()` proceeds anyway**, calling `videoCodec?.stop()` /
`videoCodec?.release()` / `muxer?.release()` on the same `MediaCodec`/`MediaMuxer` objects the
still-alive `drainVideo()` thread is concurrently calling `dequeueOutputBuffer` /
`getOutputBuffer` / `releaseOutputBuffer` on. `MediaCodec` is not safe for one thread to
stop/release while another thread is mid-call on it — the drain thread's next call throws
`IllegalStateException` uncaught → crashes the app. The same race exists for `audioThread` /
`audioCodec`. The `muxer!!.addTrack(...)` non-null assertions at `video/VideoRecorder.kt:166` and
`:249` are a second, more direct crash symptom of the exact same race: `muxer` is nulled at the end
of `stop()` (`video/VideoRecorder.kt:147`), so a drain thread still alive past the join timeout that
happens to hit an `INFO_OUTPUT_FORMAT_CHANGED` event at that moment NPEs on `muxer!!`.

**Trigger**: any condition where the encoder doesn't promptly flush its EOS buffer after
`signalEndOfInputStream()` — a HAL stall, or `signalEndOfInputStream()` itself throwing (encoder
already in an error state from some earlier hiccup) so EOS is never even requested. Given this
codebase's own documentation of a touchy vendor HAL, this is plausible but needs a real device to
confirm the timing.

**Fix**: don't proceed to release codec/muxer objects if `join()` timed out — either extend the
wait with a hard upper bound and then abandon (leak) rather than release the still-in-use object,
or have `drainVideo`/`runAudio` check `codec`/`muxer` liveness via a shared "torn down" flag before
each call and exit cleanly instead of throwing. At minimum, wrap the body of `drainVideo()`/
`runAudio()` in a top-level `try/catch (t: Throwable)` so any such race degrades to "drain thread
exits early" rather than crashing the process — consistent with the "best-effort, never crash the
render/record path" philosophy already applied elsewhere (e.g. `GlPipeline.runAnalysisReadback`).

**Confidence**: Medium — the race is real and reachable through the code's own control flow (a
join timeout that changes nothing about the release sequence), but needs a genuinely stalled
encoder/muxer on real hardware to manifest — needs-device to confirm frequency.

### BUG-5 — Async camera-startup window can bind the GL pipeline to a stale `Surface`

**Root cause**: `CameraEngine.onPreviewSurfaceAvailable` (`camera/CameraEngine.kt:84-121`):
```kotlin
fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
    previewSurface = surface // published synchronously, unconditionally
    if (started) { gl.setPreviewOutput(surface, width, height); return }
    if (starting) return // <-- a second call with a NEWER surface is dropped here
    starting = true
    setupExecutor.execute {
        ...
        gl.setPreviewOutput(surface, width, height) // <-- captures the FIRST call's `surface` param
        started = true
    }
}
```
`previewSurface` (the field, read later by `onPreviewSurfaceChanged`/`resume()`) is updated on
*every* call, but the `setupExecutor.execute { }` closure captures the **method parameter**
`surface` from whichever call actually dispatched it — i.e. only the first call, since every
subsequent call while `starting == true` returns immediately without dispatching anything. If the
TextureView's `SurfaceTexture` is recreated (a second `onSurfaceTextureAvailable` fires with a new
`Surface`) while the first `setupExecutor` task — which does several `getCameraCharacteristics` IPCs
plus `gl.start()` — is still in flight, `gl.setPreviewOutput` ends up bound to the **stale** first
`Surface` once setup finally completes, while `previewSurface` (used by future resize/resume calls)
already points at the newer one. `TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed`
(`ui/CameraScreen.kt:163-168`) releases the Surface object it locally tracks when the surface is
torn down, so if that happens to the stale surface before the async setup completes,
`EglCore.createWindowSurface` (`gl/EglCore.kt:58-64`) would be creating an EGL window surface
against an already-released `Surface` — throws (`checkEglError`/`check(eglSurface != NO_SURFACE)`)
on the GL thread, uncaught → crash. Short of a crash, the preview simply never appears (bound to a
Surface no one is compositing anymore) — a black-screen regression of exactly the class this
codebase has hit before (`CLAUDE.md`: "Lifecycle races crash the camera").

**Trigger**: TextureView surface recreation during the ~tens-of-ms cold-start setup window — e.g.
rapid backgrounding/foregrounding right at launch, a config-change-driven view recreation, or an
OEM (ColorOS) window-surface churn quirk. Narrow window, not guaranteed on every launch.

**Fix**: don't drop the newer surface — either re-dispatch setup with the latest `previewSurface`
once the in-flight one completes (check `previewSurface` inside the executor task instead of the
captured parameter, or re-post `onPreviewSurfaceAvailable`'s tail once `starting` clears), or track
a generation counter so a stale in-flight setup can detect it's outdated and re-bind to the current
`previewSurface` before calling `gl.setPreviewOutput`.

**Confidence**: Medium — logic gap is confirmed by reading; needs-device to establish how often the
window is actually hit in practice.

---

## Low

### L-1 — Overlay counter-rotation spins the long way at the 270°→0° wraparound

`ui/CameraScreen.kt:117-120`:
```kotlin
val overlayRotation by animateFloatAsState(
    targetValue = -state.deviceOrientation.toFloat(),
    label = "overlayRotation",
)
```
`state.deviceOrientation` is discrete `{0, 90, 180, 270}`. Rotating the phone from the 270°
landscape orientation onward to 0° (upright) makes `targetValue` jump from `-270` to `-0`, animating
through a full +270° sweep instead of the visually-expected -90° short way round — a one-frame-ish
glitch where the status bar/histogram/waveform overlays visibly spin a full three-quarter turn.
Purely cosmetic (`Box(Modifier.rotate(overlayRotation))` on `StatusBar`/`HistogramOverlay`/
`WaveformOverlay`), not a crash. Fix: normalize the animated delta to the shortest signed angular
path (e.g. animate a wrapped delta, or snap instead of animate across the 270/0 boundary).

### L-2 — Orientation/level tickers keep polling while the app is backgrounded

`ui/CameraViewModel.kt`'s `orientationTicker` (`:74-80`, unconditionally started in `init`, every
200ms forever) and `levelTicker` (`:65-70`, every 100ms while the Level overlay is on) are Handler
loops on `mainHandler` that are never paused by `onStop()`/`engine.pause()` — only
`mainHandler.removeCallbacksAndMessages(null)` in `onCleared()` stops them, which only fires when
the ViewModel itself is destroyed, not when the Activity backgrounds. Not a correctness bug (the
values they read are harmless when the gyro is stopped — see `GyroEis.stop()`'s `reset()`), just a
minor battery/wakeup cost while backgrounded.

### L-3 — `videoResolution` UI state not synced with the engine's actual auto-selected size

`CameraUiState.videoResolution` defaults to `Size(3840, 2160)` (`camera/CameraState.kt:83`).
`CameraEngine.chooseVideoSize()` (`camera/CameraEngine.kt:549-561`) independently picks the actual
streamed size at startup (falling back to a smaller size if the device doesn't support a 16:9 ≤3840
mode) but never publishes that choice back to the ViewModel — there is no `onVideoSizeChosen`-style
callback. Until the user explicitly touches the Resolution selector in `VideoTab`
(`ui/controls/ProSheet.kt:479-487`), the Video tab can show "4K" selected while the engine is
actually recording at a different resolution. Cosmetic/state-sync only, no crash.

---

## References

- `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraController.kt:309-314` — BUG-1 root cause
- `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:290-296,304-314,338-369` — BUG-1 consequences (BURST/AEB, dead status message)
- `app/src/main/kotlin/com/hletrd/findx9tele/ui/controls/ProControls.kt:441-466` — BUG-1 UI trigger (both formats can be off)
- `app/src/main/kotlin/com/hletrd/findx9tele/camera/CaptureCapabilities.kt:69-72` — BUG-2 root cause
- `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:94-121` (line 102), `:255-271` (line 262) — BUG-2 call sites (background thread + main thread)
- `app/src/main/kotlin/com/hletrd/findx9tele/video/VideoRecorder.kt:123-153` — BUG-3 stop() release-vs-drain race
- `app/src/main/kotlin/com/hletrd/findx9tele/video/VideoRecorder.kt:166,249` — BUG-3 `muxer!!` NPE symptom
- `app/src/main/kotlin/com/hletrd/findx9tele/video/VideoRecorder.kt:184-209` — BUG-4 missing AudioRecord state check
- `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:84-121` — BUG-5 stale-surface race
- `app/src/main/kotlin/com/hletrd/findx9tele/ui/CameraScreen.kt:117-120,143-168` — BUG-5 TextureView listener + L-1
- `app/src/main/kotlin/com/hletrd/findx9tele/ui/CameraViewModel.kt:65-80` — L-2
- `app/src/main/kotlin/com/hletrd/findx9tele/ui/controls/ProSheet.kt:479-487` — L-3
