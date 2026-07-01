# Performance & Concurrency Review — find-x9-ultra-camera

Role: perf-reviewer. Scope: real-time camera/GL/sensor/encode/UI paths.
Package: `com.hletrd.findx9tele`. Android 16 (API 36), Kotlin + Compose + Camera2 + OpenGL ES 10-bit.
Date: 2026-07-02.

Classification legend: **Confirmed** = verified from code with a concrete mechanism; **Likely** = strong evidence, one assumption; **Risk** = conditional / device-dependent. Confidence is separate (High/Medium/Low).

---

## 1. File inventory (performance-relevant, 25 .kt files)

Real-time render / stabilization (hottest paths):
- `gl/EglCore.kt` (97) — EGL 1.4 wrapper.
- `gl/GlPipeline.kt` (188) — GL render thread; `drawFrame()` per-frame loop.
- `gl/FlipRenderer.kt` (192) — per-frame draw, matrix math, uniform upload.
- `gl/Shaders.kt` (101) — GLSL (peaking/zebra/false-color/transfer).
- `stab/GyroEis.kt` (90) — gyro sampling + integration.

Camera2 capture / control:
- `camera/CameraEngine.kt` (250) — facade; capture encode; startup wiring.
- `camera/CameraController.kt` (236) — session, repeating request, ImageReaders.
- `camera/CameraSelector2.kt` (74) — lens selection (getCameraCharacteristics fan-out).
- `camera/CaptureCapabilities.kt` (117) — caps read.
- `camera/ManualControls.kt` (195) — CaptureRequest building.
- `camera/CameraState.kt` (76), `camera/VendorTagInspector.kt` (57).

Encode / storage:
- `video/VideoRecorder.kt` (228) — HEVC/AAC drain threads, muxer.
- `video/ColorProfiles.kt` (52).
- `capture/DngCapture.kt` (30), `capture/HeifCapture.kt` (25).
- `storage/MediaStoreWriter.kt` (84).

UI / Compose:
- `MainActivity.kt` (91), `ui/CameraViewModel.kt` (179), `ui/CameraScreen.kt` (353),
  `ui/CameraActions.kt` (83), `ui/controls/ProControls.kt` (606),
  `ui/overlays/Overlays.kt` (252), `ui/theme/Theme.kt` (41).

Other: `focus/FocusMapping.kt` (35), `TeleCameraApp.kt` (6).

---

## 2. Findings (ranked by real-world impact)

### F1 — Camera selection + caps read run on the MAIN THREAD during `surfaceCreated`
**Confirmed · Confidence High · Severity High (startup jank / near-ANR)**
File: `camera/CameraEngine.kt:53-76` (also `:128-140` for override), reached via `ui/CameraScreen.kt:84-90` → `ui/CameraViewModel.kt:50` → engine.

`SurfaceHolder.Callback.surfaceCreated` is delivered on the **main thread**. It calls (synchronously, still on main):
- `CameraSelector2.select(manager, overrideId)` — `CameraSelector2.kt:43-56` iterates **every** camera id and **every** physical sub-camera, calling `getCameraCharacteristics` per id (line 44, 68 `equivFocalOf`). On a multi-camera device that is 6-12 characteristics reads.
- `CameraCaps.read(...)` — `CaptureCapabilities.kt:57-115` does another `getCameraCharacteristics` + `getOutputSizes` (RAW/JPEG).
- `chooseVideoSize(sel)` — `CameraEngine.kt:222-234` does yet another `getCameraCharacteristics` + `getOutputSizes`.

`getCameraCharacteristics` is an IPC to the camera service and costs several ms each (more on cold start / busy service). Total 50-150 ms of blocking work on the UI thread during launch/resume.

Failure scenario: cold launch stalls the main thread → dropped first frames, visible jank; if the camera service is contended (e.g. another app just released the camera) this can approach the ANR window. No background dispatch exists (the app uses Handlers, not coroutines; there is no `viewModelScope`/`Dispatchers.IO` offload for setup).

Fix: perform selection + `CameraCaps.read` + `chooseVideoSize` on a background executor, then hop back to wire the GL/preview surface. `setCameraOverride` (`:128`) has the same problem when the user switches lenses.

---

### F2 — Manual-control changes rebuild & resubmit the repeating request on every slider tick
**Confirmed · Confidence High · Severity High (interactive preview stutter)**
Files: `ui/CameraViewModel.kt:168-172` (`updateControls`), `camera/CameraController.kt:149-152` (`updateControls`) + `:138-147` (`startPreview`).

Every manual control funnels through the ViewModel `updateControls` → `engine.setControls` → `controller.updateControls` → `handler.post { startPreview() }`, and `startPreview()` **creates a brand-new `CaptureRequest` from `TEMPLATE_PREVIEW`**, re-applies ~15 keys via `applyManualControls`, and calls `setRepeatingRequest`.

Compose `Slider.onValueChange` (ISO/shutter/EV/WB kelvin/tint/zoom/JPEG quality/focus — `ui/controls/ProControls.kt` and `FocusSlider`) fires continuously during a drag (tens of events/sec). There is **no debounce/throttle**. Each event:
1. Rebuilds a full request (Builder alloc + template fetch + 15 `set()` calls; manual WB path also recomputes `kelvinTintToRggbGains` with `Math.pow`/`log` every time — `ManualControls.kt:166-195`), and
2. Re-submits the repeating request to the HAL.

Failure scenario: dragging the ISO/WB/zoom slider floods the camera HandlerThread with `startPreview` posts and forces the HAL to re-latch the repeating request ~30-60×/s. Many HALs momentarily re-run 3A or drop a frame on repeating-request replacement → visible exposure/WB flicker and preview stutter while the user interacts, plus control lag as posts queue.

Fix: debounce control updates (~60-100 ms) and/or keep a single retained `CaptureRequest.Builder`, mutate only the changed keys, and resubmit — instead of rebuilding from template each time.

---

### F3 — Still-photo encoding (JPEG→Bitmap→rotate→HEIF) runs synchronously on the camera control thread; unbounded uncompressed-bitmap memory
**Confirmed · Confidence High · Severity High (memory/OOM + control-thread stall)**
Files: `camera/CameraEngine.kt:144-180` (`capturePhoto`/`saveHeif`/`saveDng`), `camera/CameraController.kt:195-215` (`tryComplete` calls `cb.onPhoto` on the camera handler), `capture/HeifCapture.kt`.

`tryComplete` invokes `p.cb.onPhoto(...)` **on the "camera" HandlerThread**, and `saveHeif` does, synchronously:
`BitmapFactory.decodeByteArray(jpeg)` → `rotate180` (`Bitmap.createBitmap(..., matrix, true)`) → MediaStore insert → `HeifWriter` re-encode → publish.

Two problems:
1. **Memory**: `decodeByteArray` produces a full ARGB_8888 bitmap. On this device class (50MP / 200MP sensors) that is ~200 MB for 50MP and outright OOM at 200MP; `rotate180` allocates a **second** full bitmap of the same size (peak ~2×). This is a decode→rotate→re-encode round-trip of the already-compressed JPEG.
2. **Thread stall**: this can take hundreds of ms to seconds, and it runs on the single camera control thread that also services `updateControls`/`capturePhoto`/`onImage`. During the encode, all manual-control changes and subsequent captures are blocked, and the JPEG/RAW `Image` objects are held open (`finally` at `:210-214`), pinning ImageReader buffers.

Not a UI-thread ANR (it is a background HandlerThread), but it freezes manual-control responsiveness and buffer availability for the encode duration.

Fix: hand the encode to a dedicated IO thread/executor (not the camera control handler); for HEIF avoid the decode+rotate+re-encode — capture via a HEIC-capable `ImageReader` or use `HeifWriter` INPUT_MODE_BUFFER with the sensor YUV, and bake the 180° rotation into the GL/EXIF path as the DNG path already does (`DngCapture.kt:24`).

---

### F4 — `CameraController` HandlerThread ("camera") is never quit → thread leak on every override/teardown
**Confirmed · Confidence High · Severity Medium**
File: `camera/CameraController.kt:42-43` (`bg = HandlerThread("camera").apply { start() }`) vs `:217-228` (`close()`).

`close()` posts `session/device/reader` cleanup but never calls `bg.quitSafely()`. A new `CameraController` is created on the initial open **and on every `setCameraOverride`** (`CameraEngine.kt:104,139`), each with its own "camera" HandlerThread. Old instances' threads never terminate.

Failure scenario: repeatedly switching the camera override (or engine restarts) accumulates live "camera" HandlerThreads for the process lifetime — leaked threads, wasted memory/scheduler slots, and lingering looper references. `GlPipeline` does this correctly (`GlPipeline.kt:180 thread?.quitSafely()`); `CameraController` should mirror it.

Fix: quit `bg` in `close()` (post the cleanup, then `bg.quitSafely()`), or reuse one controller instance across overrides.

---

### F5 — Gyro sampled at `SENSOR_DELAY_FASTEST` (~500-1000 Hz) but consumed at frame rate
**Confirmed · Confidence High · Severity Medium (CPU/battery)**
File: `stab/GyroEis.kt:46`.

`registerListener(..., SENSOR_DELAY_FASTEST)` requests the maximum hardware rate (commonly 500 Hz, up to ~1 kHz). `onSensorChanged` (`:57-74`) runs float integration + low-pass + 3 volatile writes on every event. The GL loop only reads the result once per rendered frame (30-60 Hz), so the signal is oversampled ~10-30×.

Failure scenario: sustained high-rate sensor callbacks burn CPU and prevent the app processor from idling → measurable battery drain and extra scheduling load during recording, with no stabilization-quality benefit (the low-pass is time-constant-based, `LOW_PASS_ALPHA = 0.02` is tuned per-sample so a rate change also shifts the effective cutoff — retune alongside).

Fix: use `SENSOR_DELAY_GAME` or an explicit `samplingPeriodUs` of ~5000 µs (200 Hz), which is ample for shake integration; recompute `LOW_PASS_ALPHA` for the chosen rate.

---

### F6 — `VideoRecorder.awaitMuxerStart()` busy-waits with `Thread.sleep(2)`, coupling audio startup to video drain → potential GL backpressure
**Confirmed · Confidence Medium-High · Severity Medium**
File: `video/VideoRecorder.kt:217-219`, consumed at `:120` (video) and `:194` (audio).

`awaitMuxerStart()` is a spin-poll: `while (running && !muxerStarted) Thread.sleep(2)`. The muxer starts only once **all expected tracks** are added (`maybeStartMuxer`, `:210-215`); with audio enabled `expectedTracks == 2`, so the **video** drain thread blocks in this loop until the **audio** encoder emits its `INFO_OUTPUT_FORMAT_CHANGED`.

Failure scenario (chained to GL): while the video drain thread is parked here, it stops calling `releaseOutputBuffer`; the HEVC encoder's output buffers fill; because the encoder input is the GL `encoderEgl` surface, `eglSwapBuffers(encoderEgl)` in `GlPipeline.drawFrame()` (`GlPipeline.kt:159`) blocks, which stalls the single GL thread and therefore the **preview** (drawn on the same thread) — dropped preview frames at recording start until audio's format arrives. The 2 ms poll also spins CPU.

Fix: replace the spin with a `CountDownLatch`/`wait`/`notify`; start the muxer on the video track with a bounded timeout for the audio track so a slow audio start cannot block video drain.

---

### F7 — Video encoder backpressure blocks the shared GL thread (preview + encode on one thread)
**Confirmed (architectural) · Confidence Medium · Severity Medium**
File: `gl/GlPipeline.kt:151-160`.

`drawFrame()` renders preview then, if recording, makes the encoder surface current and `swapBuffers(encoderEgl)`. `eglSwapBuffers` into a `MediaCodec` input surface blocks when encoder input buffers are full. Since both preview and encoder draw on the single "gl-pipeline" thread, any sustained drain slowdown (slow MediaStore fd writes under `muxerLock`, `:123`; or F6) throttles the whole loop, so preview framerate collapses to encoder throughput during recording.

Preview-before-encoder ordering (`:153` then `:159`) is good (the shown frame isn't gated by the encoder swap), but the **next** frame cannot start until the encoder swap returns. This is inherent to one-thread-draws-both; the mitigation is ensuring the drain path never blocks (F6) and keeping muxer writes off the critical section where possible.

Fix: guarantee non-blocking drain; consider a separate EGL-shared thread/context for the encoder surface so preview cadence is decoupled from encoder backpressure.

---

### F8 — Per-frame allocations in the GL render loop
**Confirmed · Confidence High · Severity Low-Medium (steady GC pressure in hottest loop)**
Files: `gl/GlPipeline.kt:142` + `stab/GyroEis.kt:55`; `gl/GlPipeline.kt:84` + `:185-187`.

1. `eisProvider?.invoke()` (`:142`) calls `GyroEis.currentCorrection()` which returns `floatArrayOf(corrYaw, corrPitch, corrRoll)` (`GyroEis.kt:55`) — a **new FloatArray every frame** while EIS is on (EIS defaults ON). ~32 B/frame → ~2 KB/s at 60 fps.
2. `st.setOnFrameAvailableListener({ post { drawFrame() } }, handler)` (`:84`): the frame-available callback already runs on the GL handler thread, yet it **re-posts** `drawFrame` through the inline `post` (`:185`), allocating a fresh `Runnable` per frame and adding one message-queue round-trip of latency. The re-post is unnecessary — `drawFrame()` can be called directly since the listener handler is the GL thread.

Failure scenario: not individually large, but these are in the single hottest loop and combine with any future per-frame work to raise young-gen GC frequency and add a frame of input-to-display latency (#2). Cheap to remove.

Fix: give `GyroEis` a `currentCorrection(out: FloatArray)` that writes into a GL-thread-owned reusable array (or expose three floats); call `drawFrame()` directly from the frame-available callback instead of re-posting.

---

### F9 — `VendorTagInspector.dumpAll` runs on the GL thread and gates first preview
**Confirmed · Confidence Medium · Severity Low-Medium (startup latency on a real-time thread)**
File: `camera/CameraEngine.kt:71` (inside the `gl.start{...}` onInputReady callback, which runs on the GL thread), impl `camera/VendorTagInspector.kt:18-48`.

`dumpAll` iterates all cameras + all physical sub-cameras, calling `getCameraCharacteristics` for each and `Log.i` for potentially hundreds of characteristic/request/session keys — all **before** `openCamera(input)` and **on the GL render thread**. This delays camera open / first frame and blocks the thread that must also service `setPreviewOutput`/`drawFrame`. Heavy Logcat spam per launch.

Fix: run it once on a background thread (or gate behind `BuildConfig.DEBUG`); do not call it on the GL thread ahead of `openCamera`.

---

### F10 — `GlPipeline.inputSurface` written on GL thread, read on main thread without synchronization
**Confirmed · Confidence Medium · Severity Low-Medium (visibility race)**
File: `gl/GlPipeline.kt:28-29` (plain `var inputSurface`, set at `:87` on GL thread) read at `camera/CameraEngine.kt:131` (`setCameraOverride`, main thread).

`inputSurface` is a non-volatile `var` published from the GL thread and read from the main thread in `setCameraOverride`. Without a happens-before edge the main thread may observe a stale value (null before init completes, or a stale surface during teardown), leading to a missed override (`return`) or use of a released surface.

The initial open path is safe (it flows through the Handler post in `onInputReady`, which carries the memory edge), but the main-thread read in `setCameraOverride` does not.

Fix: mark `inputSurface` `@Volatile`, or route override through the GL handler so the read is confined to the GL thread.

---

### F11 — Gyro correction tuple read non-atomically across sensor and GL threads
**Confirmed · Confidence Medium · Severity Low (visually negligible)**
File: `stab/GyroEis.kt:38-40, 55, 71-73`.

`corrPitch/corrYaw/corrRoll` are individually `@Volatile`, but `currentCorrection()` reads the three separately while `onSensorChanged` writes them; the GL thread can observe a mix of axes from adjacent samples (torn tuple). Practical impact is at most a ~1-sample (1-2 ms) cross-axis mismatch, smoothed by the low-pass — negligible visually, but it is a genuine data race worth folding into the F8 fix (write all three into one reused array under a single publish).

---

### F12 — Redundant fragment work / client-side vertex arrays in the draw path
**Confirmed · Confidence Medium · Severity Low (GPU micro-inefficiency)**
Files: `gl/Shaders.kt:59,81`; `gl/FlipRenderer.kt:35-41,144-147`.

- Shader samples `texture2D(uTexture, vTexCoord)` at `:59` and again at `:81` when peaking is on — a duplicated texel fetch per fragment while peaking is enabled (3 extra samples total for the edge test). Reuse the `color`/base sample.
- The static quad/texcoord `FloatBuffer`s are re-bound with `glVertexAttribPointer` from client memory every draw (`:144-147`) rather than via a VBO. Trivial for 4 vertices, but it is per-frame CPU→driver copying and repeated attrib enable/disable.

Fix: reuse the base sample in the peaking branch; optionally move the static geometry into a VBO and bind once. Low priority.

---

### F13 — Audio encode drains in a hot spin after EOS
**Confirmed · Confidence Medium · Severity Low**
File: `video/VideoRecorder.kt:163-207`.

After `sentEos` is set, the outer `while(true)` skips the input branch and calls `dequeueOutputBuffer(info, 0)` (non-blocking); when no output is ready the inner while exits immediately and the outer loop re-spins at 100% CPU until the EOS-flagged output buffer appears. Brief (a few ms at stop), but a busy spin.

Fix: use a small blocking timeout (e.g. `dequeueOutputBuffer(info, TIMEOUT_US)`) on the post-EOS drain.

---

### F14 — Recording ticker / recomposition scope (minor)
**Confirmed · Confidence Medium · Severity Low**
Files: `ui/CameraViewModel.kt:37-42,158`; `ui/CameraScreen.kt:196-206`.

The 200 ms `recordTicker` copies the whole `CameraUiState` 5×/s; `collectAsState` re-emits and `CameraScreen` recomposes. Most children skip (stable params / method-reference lambdas), so cost is low, but `onShutter = { ... }` (`CameraScreen.kt:201`) allocates a fresh unstable lambda each recomposition, forcing `BottomBar`/`ShutterButton` to recompose on every state change. Wrap `onShutter` in `remember`/`rememberUpdatedState` (mode + actions) to keep `BottomBar` skippable, or scope the elapsed-time read to the indicator only. Low priority.

---

## 3. Non-issues verified (to bound the review)

- **No per-frame shader/program recompile**: program + uniform locations built once in `FlipRenderer.init()`; `draw()` only sets uniforms. Good.
- **Draw matrices reuse preallocated arrays** (`FlipRenderer.kt:43-45 mvp/rot/texMatrix`, `GlPipeline.kt:55 stMatrix`) — no per-frame matrix allocation. Good.
- **No free-running render loop / Choreographer misuse**: rendering is driven by `SurfaceTexture.onFrameAvailable` (camera cadence) and paced by vsync on `eglSwapBuffers`. No busy loop.
- **Preview stream does not target the JPEG/RAW ImageReaders** (`CameraController.startPreview` adds only the GL surface), so there is no continuous ImageReader backpressure; readers are used only on still capture with `maxImages=2`. Acceptable.
- **`CameraController.pending` state machine is thread-confined**: `capturePhoto`, the capture callback, and both `onImage` listeners are all bound to the single "camera" handler, so the `synchronized(p)`/`@Volatile` guards are effectively redundant (harmless). NOTE: if F3's encode is offloaded to another thread, these guards become load-bearing — keep them.
- **`MutableStateFlow.update` from background threads** (`engine.onStatus`/`onCapsReady`) is safe; `collectAsState` observes on main.
- **`GlPipeline` config setters** all `post` to the GL handler and are read in `drawFrame` on the same thread — thread-confined, no race (except the public `inputSurface`, F10).
- **VideoRecorder video/audio threads** are created via `thread{}` and `join`ed in `stop()` — no leak (unlike F4).

---

## 4. Priority summary

| # | Area | Impact | Severity | Confidence |
|---|------|--------|----------|-----------|
| F1 | Main-thread camera-service I/O on surfaceCreated | startup jank / near-ANR | High | High |
| F2 | Repeating request rebuilt per slider tick (no debounce) | interactive preview stutter/flicker | High | High |
| F3 | Synchronous HEIF decode+rotate+encode on camera thread; 2× full bitmaps | OOM on big sensors + control stall | High | High |
| F4 | CameraController HandlerThread never quit | thread leak per override | Medium | High |
| F5 | Gyro at SENSOR_DELAY_FASTEST | CPU/battery | Medium | High |
| F6 | awaitMuxerStart busy-wait couples audio→video→GL | preview drop at record start | Medium | Med-High |
| F7 | Encoder backpressure blocks shared GL thread | preview fps collapse while recording | Medium | Medium |
| F8 | Per-frame FloatArray + Runnable in GL loop | GC pressure + 1 frame latency | Low-Med | High |
| F9 | VendorTagInspector.dumpAll on GL thread pre-open | first-frame latency | Low-Med | Medium |
| F10 | inputSurface cross-thread non-volatile read | override race | Low-Med | Medium |
| F11 | Gyro tuple torn read | negligible | Low | Medium |
| F12 | Duplicate texel fetch / client vertex arrays | GPU micro-cost | Low | Medium |
| F13 | Audio post-EOS spin | brief CPU spin | Low | Medium |
| F14 | Unstable onShutter lambda / recompose scope | minor | Low | Medium |

Highest leverage: **F1, F2, F3** (each independently causes user-visible jank, flicker, or an OOM). **F4/F5/F6** are the next tier (leaks, battery, record-start hitch).
