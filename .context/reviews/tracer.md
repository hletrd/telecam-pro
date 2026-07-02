# Tracer Report — Find X9 Ultra Teleconverter Camera

Evidence-driven causal tracing of five fragile flows, done by reading the actual code paths (no
device access this session — runtime pixel/timing claims are marked **needs-device-repro**).
Baseline read: `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/BACKLOG.md`, and the full source of
`CameraEngine.kt`, `CameraController.kt`, `MainActivity.kt`, `CameraViewModel.kt`, `VideoRecorder.kt`,
`GlPipeline.kt`, `GyroEis.kt`, `FlipRenderer.kt`, `HeifCapture.kt`, `DngCapture.kt`,
`CameraScreen.kt` (touch/mode/shutter sections), plus `git log -p` on `CameraEngine.kt` for
rotation-history provenance.

## Summary

| ID | Flow | Verdict | Confidence |
|---|---|---|---|
| TRACE-1 | Launch behind keyguard → onStop mid-open | **Likely bug** (camera-handle leak) | Medium — race is code-confirmed via Handler/Looper semantics; observable impact needs device |
| TRACE-2a | Record stop blocks UI thread | **Confirmed bug** (ANR risk) | High — pure code fact, no timing ambiguity |
| TRACE-2b | Record stop races GL encoder draw | **Likely bug** (unsynchronized teardown) | Medium — needs-device-repro for actual failure mode |
| TRACE-3a | Switching to PHOTO mode while recording | **Confirmed bug** (orphaned recording, no stop affordance) | High — deterministic UI/state logic |
| TRACE-3b | Camera-override switch while recording | **Likely bug** (stale encoder size/aspect) | Medium — needs-device-repro for visual severity |
| TRACE-4 | Rapid shutter taps in SINGLE drive mode | **Confirmed bug** (single `pending` slot race → dropped photo, misattributed image, leaked `Image`) | High — pure code/control-flow fact |
| TRACE-5a | ARCHITECTURE.md/BACKLOG.md preview-rotation formula | **Confirmed stale docs** | High — git history proves it |
| TRACE-5b | Tap-to-focus mapping ignores "cover" aspect-crop scale | **Likely bug**, explains open backlog item | High — code gap + independent user report converge |
| TRACE-5c | Video orientation ignores gyro device-tilt | **Confirmed gap** (already in BACKLOG, traced to exact code) | High |

---

## TRACE-1: Launch behind keyguard → onStop mid-camera-open

### Observation
`docs/BACKLOG.md` marks "Session-lifecycle disconnect crash fixed" ✅, verified "on an awake device."
The specific keyguard-interrupt path (launch → `onStop` fires while `CameraDevice.openCamera()` is
still in flight, i.e. before `onOpened`/`onError`/`onDisconnected`) is not called out as separately
verified. Question: does the existing `closed`/`paused` guard fully close this window, or only the
*later* window (after the session starts configuring)?

### Hypotheses

**H1 — Guard is sufficient; no leak.** The `closed` flag (`CameraController.kt:55`) plus
`paused` (`CameraEngine.kt:61`) fully cover every interleaving.

**H2 — Callback can be silently dropped, leaking the camera handle.** If `pause()` → `close()` →
`bg.quitSafely()` (`CameraController.kt:383-398`) finishes draining the `bg` HandlerThread's queue
*before* the async `manager.openCamera()` binder callback is ready to post, the posted `onOpened`
runnable is silently dropped by a dead `Looper` (Android `MessageQueue.enqueueMessage` returns
`false` and only logs a warning once `mQuitting` is set — no crash, no retry, no notification to the
caller). `device` never gets set, so `close()`'s own `runCatching { device?.close() }` is a no-op —
the framework-level `CameraDevice` handle opened by the OS is never explicitly released by app code.

### Evidence

Walking the sequence:
1. `MainActivity.onCreate` mounts `CameraScreen`; the `TextureView` fires `onSurfaceTextureAvailable`
   → `CameraViewModel.onPreviewSurfaceAvailable` → `CameraEngine.onPreviewSurfaceAvailable`
   (`CameraEngine.kt:84-122`), which dispatches camera-characteristic IPCs to `setupExecutor` (a
   **background** thread, explicitly to avoid "startup jank / near-ANR" per its own comment,
   `CameraEngine.kt:90-93`).
2. That executor block ends with `gl.start(tenBit) { input -> ...; openCamera(input) }` followed by
   `gl.setPreviewOutput(surface, width, height)`. Both just **post** to the GL `HandlerThread`
   (`GlPipeline.kt:88-95`, `97-139`) and return immediately — they do not block the executor thread.
3. On the **GL thread**, `setPreviewOutput`'s posted block creates the EGL surface + texture +
   `SurfaceTexture` + input `Surface`, and — since `!inited` — synchronously invokes
   `onInputReady(input)` (`GlPipeline.kt:126-138`), i.e. `openCamera(input)` runs **on the GL
   thread**, not the caller's thread.
4. `CameraEngine.openCamera` (`CameraEngine.kt:170-186`) checks `if (paused) return`, then calls
   `ctrl.open(...)` → `CameraController.open` (`CameraController.kt:78-109`) →
   `manager.openCamera(selection.logicalId, executor, callback)`. This is an **async binder call**;
   `onOpened`/`onError`/`onDisconnected` fire later, dispatched via `executor = Executor {
   handler.post(it) }` (`CameraController.kt:46`) onto the controller's own fresh `bg` HandlerThread
   (`CameraController.kt:44-46`).
5. Meanwhile, if the keyguard interrupts the launch, `MainActivity.onStop` (`MainActivity.kt:75-78`)
   fires on the **main thread**, calling `vm.onStop()` → `CameraViewModel.onStop` (line 297) →
   `engine.pause()` (`CameraEngine.kt:466-473`): `paused = true; ...; controller?.close(); controller
   = null`.
6. `CameraController.close()` (`CameraController.kt:383-398`): sets `closed = true` synchronously,
   posts a cleanup `Runnable` to `handler` (device/session/reader teardown), **then immediately calls
   `bg.quitSafely()`**.

The race is step 4 vs step 5-6: nothing serializes "camera-service finishes the open() IPC and posts
`onOpened`" against "main thread's `pause()` → `close()` → `quitSafely()`". If the keyguard interrupt
is fast relative to the camera-service round trip (plausible — `manager.openCamera()` for a
multi-camera-capable device routing to a specific standalone lens id is not guaranteed fast, and the
interrupt can arrive within the same event-loop tick as the surface becoming available), `quitSafely()`
can finish draining the `bg` queue and mark it quitting **before** the framework ever attempts to post
`onOpened`. Once `mQuitting` is set, later `handler.post()` calls fail silently (Android
`MessageQueue` logs a warning, does not throw, does not retry). Consequence: `device` in this
(orphaned) `CameraController` instance stays `null` forever; the actual OS-level camera handle opened
by `manager.openCamera()` is never explicitly `.close()`d by app code.

### Evidence against H2 / mitigating factors
- Android's `CameraDeviceImpl` (framework-internal) carries a `finalize()` safety net that force-closes
  an un-closed device and logs an error — so this is not a *permanent* leak, only a **delayed** one,
  bounded by GC timing rather than app logic. This softens "leak" to "resource held longer than
  intended," which mostly matters if the user quickly relaunches/foregrounds again before GC runs
  (possible transient `ERROR_CAMERA_IN_USE` on the next open).
- `docs/BACKLOG.md` already claims "no `CAMERA_DISCONNECTED` crash after relaunch on an awake device" —
  that verification targeted the **crash** failure mode (a `closed` flag stopping `createCaptureRequest`
  from throwing on a stale device), not this **leak/hold** failure mode, which wouldn't crash — it would
  surface (if at all) as a transient `ERROR_CAMERA_IN_USE` on a rapid re-open, which the existing test
  pass would not have exercised (it was "an awake device," not the keyguard-interrupt path specifically).

### Verdict
**Likely bug**, medium confidence. The race is real and code-confirmed (Android Handler/Looper
semantics are well-defined, not speculative), but its *severity* is graded by the framework's own
finalizer safety net. Rank: worth fixing, not urgent-crash-level.

### Fix direction
Don't call `bg.quitSafely()` synchronously inside `close()`. Either: (a) delay the `quitSafely()` call
until after a short grace period / after the open callback has definitely fired or failed, or (b) keep
a small shared thread pool for camera callbacks instead of a per-`CameraController` `HandlerThread` that
gets torn down on every `close()`, or (c) have `open()`'s callback registration check `closed` in a way
that guarantees `camera.close()` is called by *some* path even if the intended handler is dead (e.g.
register a lightweight thread-agnostic listener that closes on any thread if `closed` was already true
at post time, rather than relying on `onOpened` actually running).

### Critical unknown
Whether `manager.openCamera()`'s round-trip time on this SoC/HAL (standalone camera id 4, teleconverter
routing) is typically fast enough that the race window rarely matters in practice, vs. slow enough
(e.g. cold HAL init) that this fires routinely on keyguard-interrupted launches.

### Discriminating probe
On-device: `adb shell am start` while locked, then immediately re-launch/foreground repeatedly, and
watch `adb logcat` for `MessageQueue: Handler ... sending message to a Handler on a dead thread` (the
exact framework log line for a dropped post) correlated with `Camera` service logs
(`ERROR_CAMERA_IN_USE` on the next open attempt). That single log line is the direct discriminator
between H1 (guard sufficient) and H2 (drop occurs).

---

## TRACE-2: Record start → stop → restart

### Observation
Video recording is orchestrated by `CameraEngine.startRecording`/`stopRecording`
(`CameraEngine.kt:435-463`) wrapping `VideoRecorder.start`/`stop` (`VideoRecorder.kt:62-153`), called
directly from `CameraViewModel.onToggleRecording` (line 269-280) on the **main/UI thread** with no
dispatch to a background thread anywhere in the call chain.

### TRACE-2a — `stop()` blocks the UI thread

**Hypothesis:** Tapping "stop record" can visibly freeze the UI (jank/possible ANR), because the
call chain is fully synchronous down to a blocking `Thread.join()`.

**Evidence:**
```kotlin
// VideoRecorder.kt:123-127
fun stop() {
    running = false
    runCatching { videoCodec?.signalEndOfInputStream() }
    videoThread?.join(3000)
    audioThread?.join(3000)
    ...
```
`stop()` is called synchronously from `CameraEngine.stopRecording()` (`CameraEngine.kt:457-463`),
itself called synchronously from `CameraViewModel.onToggleRecording()` (`CameraViewModel.kt:269-280`),
which is wired straight to the shutter `onClick` in Compose (`CameraScreen.kt:262-269`) — i.e. this
executes **on the main thread inside a click handler**. `videoThread.join(3000)` plus (if audio was
recording) `audioThread.join(3000)` can block the main thread for up to **6 seconds** in the worst
case. Even the common case — waiting for `drainVideo()`'s loop to actually see the
`BUFFER_FLAG_END_OF_STREAM` output buffer after `signalEndOfInputStream()` — is a real encoder-flush
delay (tens to low-hundreds of ms for HEVC Main10 at the app's chosen resolution/bitrate), which is
still an unconditional UI stall on every stop.

**Evidence against:** none found — this is a direct, unambiguous code fact; there's no dispatcher,
`withContext(Dispatchers.IO)`, coroutine, or executor anywhere between the click handler and the
`join()` calls.

**Verdict: Confirmed bug.** High confidence — no timing ambiguity about *whether* the block happens,
only about its *duration* (needs-device-repro for exact ms and whether it crosses the ANR threshold
in practice).

**Fix direction:** Move `VideoRecorder.stop()` off the main thread (e.g. dispatch through
`ioExecutor`/a dedicated executor), have `CameraEngine.stopRecording()` return immediately and report
completion via the existing `onStatus` callback once the background stop finishes, matching the
pattern already used for HEIF encoding (`ioExecutor.execute { saveHeifAsync(...) }`,
`CameraEngine.kt:352`).

### TRACE-2b — `stop()` races the GL thread's encoder draw

**Hypothesis:** `signalEndOfInputStream()` (main thread) and `GlPipeline`'s encoder-surface teardown
(`setEncoderOutput(null, 0, 0)`, GL thread) are not synchronized against the GL thread's *in-flight*
draw calls to that same encoder `Surface`, risking a lost/corrupt tail frame or an exception.

**Evidence:**
```kotlin
// CameraEngine.kt:457-463
fun stopRecording() {
    val rec = recorder ?: return
    gl.setEncoderOutput(null, 0, 0)   // posts async release to the GL HandlerThread, does not block
    rec.stop()                          // runs synchronously on the CALLING (main) thread right away
    recorder = null
    onStatus?.invoke("Video saved")
}
```
`gl.setEncoderOutput(...)`'s body only runs later, whenever the GL `HandlerThread` gets to it
(`GlPipeline.kt:178-189`, `post{}` = `handler?.post{...}`). There is no wait/acknowledgment before
`rec.stop()` calls `videoCodec?.signalEndOfInputStream()` on the same thread that called
`setEncoderOutput`. If a camera frame arrives and `drawFrame()` (`GlPipeline.kt:191-240`) is *already
queued or mid-execution* on the GL thread at that moment, it can still `core.swapBuffers(encoderEgl)`
targeting the encoder's input `Surface` concurrently with (or immediately after)
`signalEndOfInputStream()` executing on the main thread — a use pattern Android's MediaCodec docs
treat as illegal ("MUST happen after the last frame has been submitted... it is illegal to submit any
more input to it").

**Evidence against / mitigating:** All `GlPipeline` GL-thread work (both `drawFrame()` triggered via
`SurfaceTexture.OnFrameAvailableListener` and explicit `post{}` calls like `setEncoderOutput`) is
serialized on the **same** `handler`/`HandlerThread` (`GlPipeline.kt:132`, `367-369`), so
`setEncoderOutput(null,...)`'s release is guaranteed to run *before* any `drawFrame()` that is enqueued
*after* it — the internal GL-thread ordering is safe. The only surviving race window is: a
`drawFrame()` that was already enqueued *before* `setEncoderOutput(null,...)` was posted, executing
concurrently with (or racing) the main thread's `signalEndOfInputStream()` call, which is not gated by
anything. This window is narrow (roughly one frame interval, ~16-33 ms) but not zero, and is widened by
system load / frame-rate jitter — exactly the kind of condition more likely under the "rapid
toggle"/stress scenario asked about.

**Verdict: Likely bug**, medium confidence — the lack of synchronization is code-confirmed; whether it
manifests as a dropped tail frame (benign), a logged exception (cosmetic), or a hard crash is
HAL/encoder-implementation-dependent and needs device observation.

**Fix direction:** Have `stopRecording()` wait for the GL thread to actually process the encoder-output
release (e.g. `gl.setEncoderOutput` taking a completion callback, or CameraEngine posting a "quiesce"
token through the GL handler and awaiting it) before calling `rec.stop()`/`signalEndOfInputStream()`.

### Restart (rapid stop→start)
No reentrancy bug found for immediate restart: `stop()`'s blocking `join()` calls (the TRACE-2a bug)
have the side effect of fully draining and tearing down the old `VideoRecorder` before
`CameraViewModel.onToggleRecording()` returns, and Android delivers touch events serially through the
same main-thread queue, so a second tap physically cannot execute concurrently with the first's
`stop()` call — the UI is simply frozen, not racing. Fixing TRACE-2a (making `stop()` async) will
**reintroduce** a restart-reentrancy risk that doesn't currently exist, and must be paired with an
explicit "stopping" state guard in `CameraViewModel`/`CameraEngine` (there is none today because it's
currently masked by the synchronous block).

### Critical unknown
Actual measured `stop()` duration on-device (with/without audio, at the app's HEVC Main10 4K settings)
— determines whether TRACE-2a is "minor jank" or "hits the ANR watchdog."

### Discriminating probe
On-device: start a recording, tap stop, and watch `adb logcat` for `Choreographer: Skipped N frames`
or `ActivityManager: ANR in ...` correlated with a `System.currentTimeMillis()` bracket around the tap.
Also deliberately record with audio enabled and immediately stop, to hit the compounded
video+audio `join()` path.

---

## TRACE-3: Mode / camera-override switch while recording

### TRACE-3a — Switching to PHOTO mode mid-recording orphans the recording

**Hypothesis:** The Photo/Video mode tabs are not gated by `isRecording`, so switching modes while
recording changes what the shutter button *does* without stopping the recording, and without leaving
any other affordance to stop it.

**Evidence:**
```kotlin
// CameraViewModel.kt:149-152
override fun onModeChange(mode: CaptureMode) {
    cancelCountdown()
    _state.update { it.copy(mode = mode) }   // no recording-state interaction at all
}
```
```kotlin
// CameraScreen.kt:566-567 — mode tabs, always clickable, no `enabled=` gate on isRecording
ModeLabel(text = "Photo", active = mode == CaptureMode.PHOTO, onClick = { onModeChange(CaptureMode.PHOTO) })
ModeLabel(text = "Video", active = mode == CaptureMode.VIDEO, onClick = { onModeChange(CaptureMode.VIDEO) })
```
```kotlin
// CameraScreen.kt:262-269 — shutter dispatch keys ONLY on state.mode, not isRecording
val onShutter = remember(state.mode) {
    { if (state.mode == CaptureMode.PHOTO) currentActions.value.onCapturePhoto()
      else currentActions.value.onToggleRecording() }
}
```
```kotlin
// CameraScreen.kt:614 — the dedicated stop-adjacent "snapshot" dot only shows in VIDEO mode
if (mode == CaptureMode.VIDEO && isRecording) { SnapshotButton(onClick = onSnapshot) }
```
Trace: start recording (mode=VIDEO, isRecording=true) → tap "Photo" tab → `onModeChange(PHOTO)` only
updates `_state.mode`; `engine.recorder` is untouched, recording continues. The shutter button now
renders/dispatches as the PHOTO button (`onCapturePhoto()`), so **the primary shutter can no longer
stop the recording**. `RecordingIndicator` (`CameraScreen.kt:220`, gated only on `state.isRecording`,
not `mode`) still displays, so the user sees they're recording but has no direct control to stop it
except switching the mode tab back to "Video" (not obviously the "stop recording" action from a user's
perspective) or backgrounding the app (`MainActivity.onStop` → `pause()` → `recorder?.stop()`,
`CameraEngine.kt:466-473`).

**Evidence against:** The state is recoverable (switching back to "Video" restores the stop-capable
shutter), and `pause()` will eventually stop+save the recording if the app backgrounds. So this is not
a permanent hang, "just" a confusing, easily-triggered UX/state gap.

**Verdict: Confirmed bug**, high confidence — pure deterministic UI/state logic, no timing/race
involved, reproducible every time by tapping "Photo" during a video recording.

**Fix direction:** Either disable the "Photo" tab while `isRecording`, or route `onModeChange` through
a guard that stops the recording first (with confirmation), or keep a persistent stop-record affordance
independent of `mode`.

### TRACE-3b — Camera-override switch while recording

**Hypothesis:** `setCameraOverride` tears down and rebuilds the entire Camera2 session for a
*different* physical/logical camera while a recording is active, without updating the
already-configured `VideoRecorder`/`GlPipeline` encoder surface size, producing a size/aspect
mismatch mid-clip.

**Evidence:**
```kotlin
// CameraEngine.kt:255-271
fun setCameraOverride(id: String?) {
    overrideId = id
    if (!started) return
    val input = gl.inputSurface ?: return
    controller?.close()                                  // tears down the live Camera2 session
    val sel = CameraSelector2.select(manager, id) ?: run { ...; return }
    selection = sel
    val c = CameraCaps.read(manager, sel.logicalId, sel.physicalId)
    caps = c
    videoSize = chooseVideoSize(sel)                      // NEW camera's native size
    gl.setCameraPreviewSize(videoSize.width, videoSize.height)  // changes SurfaceTexture buffer size
    applyStabilization()
    openCamera(input)                                      // opens the NEW camera
}
```
Nothing here checks `recorder != null`. `startRecording()` captured the *old* `videoSize` into a local
`val size` at record-start time (`CameraEngine.kt:439`) and configured `VideoRecorder`/the encoder
`Surface`/`GlPipeline.encoderW/encoderH` (`gl.setEncoderOutput(surface, size.width, size.height)`,
`CameraEngine.kt:452`) to that fixed size — none of that is revisited by `setCameraOverride`. After the
switch, `GlPipeline.drawFrame()`'s encoder draw path (`GlPipeline.kt:234-239`) keeps rendering into the
same, now-stale-sized `encoderEgl`, sourced from a **different** camera's frames (different native
aspect/resolution/sensor orientation), scaled by `FlipRenderer`'s "cover" aspect logic
(`FlipRenderer.kt:115-125`) against the old target dimensions.

**Evidence against / uncertainty:** This won't crash outright — the cover-fit logic degrades
gracefully to a differently-cropped/scaled frame rather than throwing, so the failure mode is a visual
artifact (a framing/resolution jump mid-recording), not a hard failure. Severity depends on how
different the two cameras' native aspect ratios actually are, which needs device data
(`CameraSelector2`/`chooseVideoSize` output for the actual override targets available on this
device).

**Verdict: Likely bug**, medium confidence.

**Fix direction:** Guard `setCameraOverride` (and, by extension, other engine-level reconfiguration
entry points like `setAspectRatio`/`setTeleconverterMode` if they have similar gaps) behind an
`isRecording` check — either block the switch with a status message, or stop the recording first.

### Critical unknown
Whether any *other* live-reconfiguration entry point (`setTeleconverterMode`, `setVideoResolution`,
`setEisStrength`) shares the same "no recording guard" gap — I traced `setCameraOverride` in depth as
the most consequential (full session teardown), but did not exhaustively re-verify every setter against
an active `recorder`.

### Discriminating probe
On-device: start recording, tap the camera-override selector (or the teleconverter lens-flip button) if
the UI exposes one during recording, then inspect the resulting MP4 for a mid-clip
resolution/framing discontinuity (`ffprobe` frame-by-frame, or just visual playback).

---

## TRACE-4: Capture under rapid shutter / low memory

### Hypothesis
`CameraController` tracks exactly **one** in-flight still capture via a single `@Volatile pending:
Pending?` field (`CameraController.kt:65`). `BURST`/`AEB` drive modes correctly *chain* shots (each
next shot fired only from the previous shot's completion callback — `CameraEngine.kt:290-296`,
`304-314`), but **SINGLE** drive mode has no such chaining, and the UI has no debounce beyond the
timer-countdown case. Two rapid taps in SINGLE mode should therefore be able to issue two overlapping
`capturePhoto()` calls that stomp on the same `pending` slot.

### Evidence — the race is reachable
```kotlin
// CameraViewModel.kt:238-242 — no guard against a capture already in flight, only against
// a countdown already in progress
override fun onCapturePhoto() {
    if (_state.value.timerCountdownSec > 0) return
    val seconds = _state.value.timer.seconds
    if (seconds <= 0) engine.capturePhoto(_state.value.photoFormats) else startCountdown(seconds)
}
```
```kotlin
// CameraEngine.kt:275-283 — SINGLE fires exactly one ctrl.capturePhoto() with no in-flight check
DriveMode.SINGLE -> ctrl.capturePhoto(formats.heif, formats.dngRaw, photoCallback(formats))
```
A second concrete trigger for the same race, independent of double-tapping the main shutter: the
"snapshot during recording" button also calls straight into `onCapturePhoto()`
(`CameraScreen.kt:304`, comment at `691-694` confirms "Calls straight into
`CameraActions.onCapturePhoto`") and has no in-flight guard either.

### Evidence — the consequence if it fires
```kotlin
// CameraController.kt:309-325
fun capturePhoto(wantJpeg: Boolean, wantRaw: Boolean, cb: PhotoCallback) = handler.post {
    ...
    pending = Pending(jpeg != null, raw != null, cb)   // OVERWRITES any existing in-flight Pending
    ...
    s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() { ... }, handler)
}
```
```kotlin
// CameraController.kt:349-359 — onImage always attributes to whatever `pending` IS NOW,
// not to the request the image actually belongs to
private fun onImage(reader: ImageReader, isRaw: Boolean) {
    val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
    val p = pending
    if (p == null) { image.close(); return }
    synchronized(p) {
        if (isRaw && p.wantRaw && p.raw == null) p.raw = image
        else if (!isRaw && p.wantJpeg && p.jpeg == null) p.jpeg = image
        else { image.close(); return }
    }
    tryComplete(p)
}
```
Both `capturePhoto()`'s per-request `onCaptureCompleted` callback (`CameraController.kt:326-331`) and
`onImage()` read the **current** `pending` field rather than a reference captured at request-issue
time. If tap #2 arrives (posted to the same `handler`) before tap #1's HAL round trip (real sensor
readout + JPEG/RAW encode — plausibly tens of ms for a full-resolution multi-stream capture) has
delivered its result/images:
1. `pending` is reassigned from `Pending1` to `Pending2` when tap #2's post executes.
2. `Pending1` is now unreferenced by the `pending` field — orphaned. It is *not* explicitly closed
   anywhere; nothing calls `tryComplete` on it again since `pending` no longer points to it.
3. When tap #1's HAL results eventually arrive, `onImage`/`onCaptureCompleted` write them into
   `Pending2` instead (since they only ever read the current `pending`) — either filling a slot
   `Pending2` was still waiting on (misattributing tap #1's photo to tap #2's callback) or finding the
   slot already filled by tap #2's own image and taking the `else { image.close(); return }` branch,
   discarding tap #2's own legitimate image.
4. **Net effect:** tap #1's `PhotoCallback.onPhoto()`/`onError()` never fires at all (silently dropped
   — no save, no status message, no error surfaced to the user), and/or tap #2's own image gets
   discarded and replaced by tap #1's.
5. If `Pending1` was holding an *acquired but unclosed* `Image` at the moment it was orphaned (e.g. it
   had received its `jpeg` but was still waiting on `raw`+`result`), that `Image` is **never
   `.close()`d** — a permanent leak against the `ImageReader`'s `maxImages = 2` budget
   (`CameraController.kt:148`, `157`, both readers built with `maxImages=2`). Losing even one slot to
   this halves the reader's effective buffer; losing two (repeated rapid-shutter taps) makes
   `reader.acquireNextImage()` start throwing `IllegalStateException` ("maxImages already acquired"),
   which is swallowed by `runCatching { ... }.getOrNull() ?: return` in `onImage`
   (`CameraController.kt:350`) — **all future captures on that reader silently stop delivering images**
   until the session/app is torn down and rebuilt.

### Evidence against
`BURST`/`AEB`/`TIMELAPSE` are all correctly serialized (chained via completion callbacks or an
interval scheduler slower than a single capture is expected to take), so this is specifically a
SINGLE-drive-mode + rapid-manual-tap (or rapid snapshot-during-recording) issue, not a systemic
capture-pipeline flaw.

### Verdict
**Confirmed bug**, high confidence. This is deterministic Kotlin control flow (single mutable slot,
last-writer-wins, no request-identity check) — no hardware timing ambiguity about *whether* the race
is structurally possible, only about how easily a human can actually land two taps within one HAL
round-trip window (very plausible for "rapid shutter" use).

### Fix direction
Either (a) ignore/reject a new `capturePhoto()` call while `pending != null` (simplest — a "not ready"
status message, mirroring the countdown guard), or (b) make `Pending` self-identifying (capture request
tag / sequence id) so `onImage`/`onCaptureCompleted` route results to the *originating* request instead
of "whatever `pending` currently is," and explicitly close+release any orphaned `Pending`'s partially
acquired images when it's superseded.

### Critical unknown
Exact HAL round-trip latency for a full-resolution JPEG+RAW still capture on this device/lens
combination — determines how fast a human (or a scripted `adb shell input tap` loop) needs to
double-tap to reliably trigger it.

### Discriminating probe
On-device: script `adb shell input tap` on the shutter region at ~100-150 ms intervals in SINGLE mode
with both HEIF+RAW enabled, and check `adb logcat` for `IllegalStateException` / "maxImages" from
`ImageReader`, plus confirm whether every tap produced a corresponding MediaStore entry (a dropped tap
would show as a "missing" file with no error status ever shown in the UI).

---

## TRACE-5: Orientation — preview vs still vs video vs EXIF

### TRACE-5a — Docs are stale relative to the actual (already-fixed) preview rotation formula

**Observation:** `docs/ARCHITECTURE.md:190-201` and `docs/BACKLOG.md:17-21` both describe
`previewRotationDegrees() = -sensorOrientation + (afocal 180 if tele)`. The **current** code is:
```kotlin
// CameraEngine.kt:152
private fun previewRotationDegrees(): Int = if (teleconverterMode) 180 else 0
```
**Evidence (git provenance, not speculation):**
```
$ git log -1 --format="%ai" 653e871   →  2026-07-03 00:13:52 +0900
$ git log -1 --format="%ai" eaa241a   →  2026-07-03 00:35:45 +0900 (latest commit on the branch)
$ git log --oneline -- docs/ARCHITECTURE.md docs/BACKLOG.md
  1a9deeb docs: add ARCHITECTURE.md ...
  b1c8c86 docs: add project CLAUDE.md + BACKLOG ...
```
Commit `653e871` ("fix(preview): correct upright rotation...") post-dates both doc commits and its
message states: *"Verified on device: preview is now upright. The camera SurfaceTexture transform
already applies the sensor orientation to the sampled image, so the renderer adds ONLY the afocal
180°... not ±sensorOrientation (both 270° and 90° were 90° off)."* Neither doc file was updated
afterward. `docs/BACKLOG.md`'s "Preview rotation sign 🟡 ... final upright result is not yet visually
confirmed" line is now factually superseded by a later, on-device-verified commit.

**Verdict: Confirmed** — this is a documentation-freshness defect, not a code defect. High confidence
(git timestamps are unambiguous).

**A genuine second-order uncertainty worth flagging, not resolving:** the commit's own justification
— "the camera SurfaceTexture transform already applies the sensor orientation" — is a strong,
device-specific empirical claim that runs against the general Camera2 convention (frames delivered via
`SurfaceTexture` are normally in *native sensor orientation*; `getTransformMatrix()` typically only
carries buffer-crop/Y-flip correction, not device/sensor rotation — this is why apps conventionally
need `CameraCharacteristics.SENSOR_ORIENTATION` math on top). I cannot verify on this device which is
true; I can only note the "verified on device" claim is Tier-1 evidence (a human looked at the actual
screen) and outranks my Tier-6 general-Camera2-convention prior, **but** "looked upright" alone doesn't
positively rule out a compensating error elsewhere (e.g. TRACE-5b's uncorrected aspect-cover-crop)
happening to look plausible for one specific test framing. Not raising this to a "likely bug" — just
flagging it as the one place in the rotation pipeline I'd want a second, more deliberate on-device
check (e.g. shoot a frame with obvious up/down/left/right asymmetry, not just "looks roughly right").

**Fix direction:** Update `docs/ARCHITECTURE.md` §"180° Flip + Rotation Pipeline" and
`docs/BACKLOG.md`'s "Preview rotation sign" item to match the current, verified formula and mark it ✅.

### TRACE-5b — Tap-to-focus mapping ignores the preview's "cover" aspect-crop scale

**Hypothesis:** `CameraEngine.setTapPoint` (`CameraEngine.kt:212-226`) maps a view-normalized tap to a
sensor-normalized AF/AE point using **only** a centered rotation; it does not account for
`FlipRenderer`'s per-axis "cover" scale (`ex`/`ey`, `FlipRenderer.kt:117-125`), which is applied
whenever the camera stream's displayed aspect doesn't match the view's aspect — which it structurally
never does here (16:9-ish camera stream vs. a `fillMaxSize()` tall-portrait `TextureView`,
`CameraScreen.kt:127-129`). This directly explains the still-open backlog/task item "Fix tap-to-focus
(not working on device)."

**Evidence:**
```kotlin
// CameraEngine.kt:212-226 — rotation only, no scale correction
fun setTapPoint(nx: Float, ny: Float) {
    val c = caps ?: return
    val total = ((c.sensorOrientation + if (teleconverterMode) 180 else 0) % 360 + 360) % 360
    val px = nx - 0.5f
    val py = ny - 0.5f
    val rad = Math.toRadians(-total.toDouble())
    val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
    val rx = px * cos - py * sin
    val ry = px * sin + py * cos
    controller?.setMeteringPoint((rx + 0.5f).coerceIn(0f, 1f), (ry + 0.5f).coerceIn(0f, 1f))
}
```
The function's own doc comment (`CameraEngine.kt:202-211`) already admits it "ignores the EIS/punch-in
crop and offset" — but says nothing about the **cover-crop aspect scale**, which is structurally always
active here (not a conditional edge case like EIS), and is the larger of the two effects:
```kotlin
// FlipRenderer.kt:113-125 — this scale is applied to the GEOMETRY (mvp), so a tap near a view edge
// maps to a content point closer to center than a pure rotation would predict, once ex or ey != 1
val rotated = (sensorOrientationDeg + rotationDeg) % 180 == 90
val displayedAspect = if (rotated) previewH.toFloat() / previewW else previewW.toFloat() / previewH
val viewAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
var ex = 1f; var ey = 1f
if (displayedAspect > viewAspect) ex = displayedAspect / viewAspect else ey = viewAspect / displayedAspect
```
`videoSize`/the camera stream is chosen by `chooseVideoSize` as "largest 16:9 up to 3840 wide"
(`CameraEngine.kt:549-560`) while the `TextureView` fills the entire (tall, portrait, roughly 20:9)
screen (`CameraScreen.kt:127-129`). That mismatch guarantees `ex` or `ey` departs meaningfully from 1
(illustratively, for a 16:9 stream rotated 90° into a ~0.45-aspect portrait view: `displayedAspect ≈
0.5625`, `viewAspect ≈ 0.45` → `ex ≈ 1.25`), which `setTapPoint`'s pure-rotation math never divides
out. The resulting AF/AE spot (already a small ~10% window, `CameraController.kt:263-266`) lands
progressively further from the tapped screen location as the tap moves away from center — exactly the
symptom a user would describe as "tap-to-focus not working."

**Corroboration (independent-source evidence, not just my own code reading):** the live task list
already carries `#2 [pending] Fix tap-to-focus (not working on device)` as an open, user-reported item
— an independent behavioral report converging with this code-level gap.

**Evidence against:** I have not measured the device's actual screen aspect or confirmed
`chooseVideoSize`'s real output on-device this session, so the *magnitude* of `ex`/`ey` above is
illustrative, not measured. It's also possible the EIS-crop gap (already self-documented) is the
dominant contributor in practice rather than the cover-scale gap — both are real omissions in the same
function, and I can't rank their relative contribution without a device.

**Verdict: Likely bug**, high confidence that *a* real geometric gap exists (two, in fact — cover-scale
and EIS-crop), medium confidence on exact magnitude/whether it's the *sole* explanation for the
reported failure.

**Fix direction:** Route the tap point through the same transform `FlipRenderer.draw()` actually uses
(rotation + cover-scale + EIS shift/crop), inverted, rather than re-deriving a partial version of it in
`CameraEngine`. Concretely: divide `(px, py)` by `(ex, ey)` before the rotation, and after the rotation
also invert the EIS `crop`/`(sx, sy)` shift, mirroring `FlipRenderer.kt:130-134`'s texcoord chain.

### TRACE-5c — Video orientation ignores gyro device-tilt (already tracked, traced to exact code)

Already flagged 🔴 in `docs/BACKLOG.md` §1; tracing to precise code for completeness since the team
asked for "where do the signs disagree":
```kotlin
// CameraEngine.kt:130-141 — ONE shared rotation value feeds BOTH preview and encoder targets
private fun applyStabilization() {
    ...
    gl.setRotationDegrees(previewRotationDegrees())   // afocal-180-only, NO device-tilt term
    ...
}
```
```kotlin
// GlPipeline.kt:234-239 — encoder draw uses the exact same rotationDeg as the preview draw
if (encoderEgl != EGL14.EGL_NO_SURFACE) {
    core.makeCurrent(encoderEgl)
    renderer.draw(stMatrix, encoderW, encoderH, transfer, false, false, false, sx, sy, roll, crop)
    ...
}
```
versus stills, which separately add `gyro.currentDeviceOrientation()`:
```kotlin
// CameraEngine.kt:514-518
private fun captureRotationDegrees(): Int {
    val c = caps ?: return 0
    val base = c.sensorOrientation + if (teleconverterMode) 180 else 0
    return ((base + gyro.currentDeviceOrientation()) % 360 + 360) % 360
}
```
There is no per-encoder-target rotation input anywhere in `GlPipeline`/`FlipRenderer` — `rotationDeg`
is a single field (`FlipRenderer.kt:49`, set via `GlPipeline.setRotationDegrees`) shared by both draw
calls in `drawFrame()`. Confirmed: a clip recorded while physically tilting the phone into landscape
will encode with the same fixed (portrait-framed) rotation as the preview, unlike a still shot at the
same moment, which would bake in the device tilt. **Verdict: Confirmed gap** (already correctly
triaged in BACKLOG; this trace adds the precise `file:line` mechanism — one shared `rotationDeg`, no
device-orientation term ever reaches the encoder path).

### TRACE-5d — EXIF/HEIF cross-check (no bug found)

Checked for a double-rotation risk between pixel-rotation and EXIF tagging: `HeifCapture.writeHeif`
(`HeifCapture.kt:13-24`) takes an already-rotated `Bitmap` and sets no orientation tag (implicit
`NORMAL`) — correct, since `CameraEngine.saveHeifAsync` already applies
`Matrix.postRotate(captureRotationDegrees)` before encoding (`CameraEngine.kt:391`). `DngCapture.writeDng`
(`DngCapture.kt:20-34`) does the opposite — sets `creator.setOrientation(orientation)` and leaves the
Bayer pixels untouched, matching the documented "can't pixel-rotate RAW" constraint. No sign
disagreement found between the two still-capture paths.

### Critical unknown (orientation, overall)
Whether `previewRotationDegrees()`'s "SurfaceTexture already applies sensor orientation" premise is
actually true on this HAL, or whether the Jul-3 "verified on device" pass happened to look correct for
a reason other than the stated one (e.g. masked by the TRACE-5b cover-scale gap, or a test framing that
wouldn't reveal a residual 90° rotation). This is the one place across all five flows where the
strongest available evidence (a human on-device check) and my strongest prior (general Camera2
`SurfaceTexture` convention) point in different directions, and I have no way to adjudicate further
without the device.

### Discriminating probe
Shoot a preview frame of a scene with an unambiguous orientation cue (e.g. a printed arrow or visible
horizon/text) at both `teleconverterMode = true` and `false`, and independently verify: (a) net
rotation, (b) that a tap near a view edge (not center) lands the AF box close to the intended subject
(directly tests TRACE-5b), and (c) record a short clip while tilting the phone 90° mid-recording and
confirm it does NOT reorient (directly confirms TRACE-5c, and rules out an accidental partial fix).
