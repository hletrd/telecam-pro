# Performance & Concurrency Review (deep, independent re-verification) — find-x9-ultra-camera

Role: perf-reviewer. Scope: real-time camera / GL / sensor / encode / Compose paths for the OPPO
Find X9 Ultra teleconverter camera app. Package `com.hletrd.findx9tele`. Android 16 (API 36),
Kotlin 2.3.10 + Jetpack Compose (BOM 2026.06.00, strong-skipping on) + Camera2 + OpenGL ES 2.
Date: 2026-07-03. All findings validated against the **current** code, not comments.

Method: inventoried and read all 27 Kotlin files under `app/src/main/kotlin/**`; re-verified every
prior finding in the earlier `perf-reviewer.md` / `docs/plans/2026-07-02-review-fixes.md` against the
present source; then swept for new hot-path allocations, main-thread work, lock contention, GL
readback stalls, and Compose recomposition churn. Camera/GL runtime *timings* are device-only and are
flagged **needs-device-profiling** where I cannot measure them; the *mechanisms* are code-verified.

Confidence = High/Medium/Low. "New" = not in the prior review. "Residual" = prior finding only
partially fixed. "Re-opened" = prior finding the fix plan marked done that is still present.

---

## Summary table

| ID | Area | Severity | Confidence | Status vs prior |
|----|------|----------|------------|-----------------|
| PERF-1 | Full-preview-res `glReadPixels` + double ~18 MB copy + GPU sync on GL thread every 12th frame (scopes) | High | High (mech.) / device (ms) | New (prior F-analysis was generic) |
| PERF-2 | `setCameraOverride` runs 6–12 camera-service IPCs synchronously on the **UI thread** | Medium | High | Re-opened (F1 fix skipped this path) |
| PERF-3 | Per-frame `FloatArray` allocation on the GL render thread (EIS provider) | Medium | High | Residual (F8, half-fixed) |
| PERF-4 | `awaitMuxerStart()` busy-waits `Thread.sleep(2)` → couples audio-format arrival to video drain → GL stall at record start | Medium | Medium-High | Re-opened (F6 never fixed) |
| PERF-5 | `CameraUiState` is Compose-**unstable** (IntArray) + whole-state passed to children → non-skippable tree recomposition on every emission; level/record/audio tickers emit 5–20 Hz | Medium | High | New |
| PERF-6 | Muxer file-I/O held under `muxerLock`; encoder backpressure blocks the shared GL thread → preview fps collapse while recording | Medium | Medium | Re-confirmed (F7) |
| PERF-7 | Camera streams up to 4K into the preview SurfaceTexture even when only previewing (not recording) | Low-Medium | Medium / device | New |
| PERF-8 | `glReadPixels` runs **after** `eglSwapBuffers` (back buffer undefined) → expensive readback samples stale/garbage; redundant `makeCurrent` | Low-Medium | Medium | New |
| PERF-9 | Main-thread pollers (level 10 Hz unconditional, orientation/record 5 Hz) not tied to `onStop` → keep waking main thread while backgrounded | Low | High | New |
| PERF-10 | Unbounded `ioExecutor` queue in BURST/TIMELAPSE retains full compressed JPEGs + serialized full-bitmap decodes → transient memory/OOM | Low-Medium | Medium | New |
| PERF-11 | Gyro correction tuple read non-atomically (3 separate volatile reads) across sensor/GL threads | Low | Medium | Residual (F11) |
| PERF-12 | Static quad/texcoord uploaded from client memory every draw (no VBO); attrib enable/disable each frame | Low | Medium | Residual (F12) |
| PERF-13 | Repeating request fully rebuilt from `TEMPLATE_PREVIEW` on every (debounced) control change; WB blackbody `Math.pow`/`log` recomputed | Low | Medium | Mitigated (F2 debounce) |
| PERF-14 | `computeHistogram`/`computeWaveform` allocate fresh IntArrays each analysis pass (~40 KB/pass @ ~5 Hz) | Low | High | New |

**Verified-FIXED prior findings** (independently re-confirmed resolved): F1 startup path (setup on
`setupExecutor`), F3 HEIF encode offloaded to `ioExecutor` + recycle + OOM guard, F4 `CameraController`
HandlerThread `bg.quitSafely()` in `close()`, F5 gyro at 200 Hz (`SAMPLING_PERIOD_US=5000`), F8
re-post Runnable removed (`setOnFrameAvailableListener({ drawFrame() }, handler)`), F9
`VendorTagInspector` gated `BuildConfig.DEBUG` + off GL thread, F10 `inputSurface` `@Volatile`, F12
peaking reuses `base` sample, F13 audio post-EOS blocking timeout, F14 `onShutter` `remember(mode)`.

Highest leverage: **PERF-1** (readback stall when scopes on — the backlog's own "profile it" item,
confirmed), then **PERF-4 / PERF-6** (record-start and sustained-record preview hitches), then
**PERF-2 / PERF-5** (lens-switch jank and idle recomposition churn).

---

## High

### PERF-1 — Scope readback reads the FULL preview resolution and does two ~18 MB copies + a GPU sync on the render thread every 12th frame
**Severity High (conditional: histogram or waveform enabled) · Confidence High for the mechanism, needs-device-profiling for the ms cost**
`gl/GlPipeline.kt:248-288` (`runAnalysisReadback`), readback at `:269`, second copy at `:271`,
throttle at `:227-232`. Sizes come from `previewW/previewH`, which are the on-screen preview
**view** size set in `setPreviewOutput` (`:121-122`).

`previewW×previewH` on this device is the full-screen preview ≈ **1440×3168**. Each pass does:
```
GLES20.glReadPixels(0,0,w,h, GL_RGBA, GL_UNSIGNED_BYTE, buf)  // ~18.2 MB, forces a full GPU flush/sync
buf.get(bytes, 0, size)                                       // second ~18.2 MB memcpy into a ByteArray
```
`glReadPixels` is a synchronous GPU→CPU transfer: it stalls the GL thread until the GPU has finished
rendering the frame, then transfers the whole framebuffer. That happens every 12th frame while a
scope is on — at 60 fps that is ~5 stalls/s and ~180 MB/s of copying (18 MB × 2 × 5). The `step=6`
subsampling in `computeHistogram`/`computeWaveform` only reduces the *CPU compute*, not the readback
size or the sync. Because preview and encoder share this one GL thread, each stall directly delays
the next preview (and encoder) frame.

Scenario: user enables Histogram and Waveform for exposure work → preview develops a periodic ~5 Hz
hitch (and worse while also recording, since the encoder draw competes on the same thread). This is
exactly the backlog's "verify they don't stall rendering… profile it" item, now code-confirmed.

Fix: read back at a small fixed analysis resolution instead of the full preview. Render (or blit) the
frame once into a small FBO (e.g. 256×512) and `glReadPixels` that, or use `glReadPixels` over a
downscaled viewport; the histogram/waveform are already heavily subsampled so a small buffer loses
nothing. That cuts the transfer ~150× and the copy cost with it. Optionally use a PBO for an async
readback so the GL thread never blocks on the transfer.

---

## Medium

### PERF-2 — `setCameraOverride` issues 6–12 camera-service IPCs synchronously on the UI thread
**Severity Medium · Confidence High · Re-opened (the F1/H-MAIN fix moved only the startup path off-main)**
`camera/CameraEngine.kt:255-271`, invoked from `ui/CameraViewModel.kt:282-285` (`onCameraOverride`,
UI thread). It runs, all on the caller's (main) thread:
- `CameraSelector2.select(manager, id)` — iterates every camera id and every physical sub-camera,
  calling `getCameraCharacteristics` per id (`camera/CameraSelector2.kt:43-52`, `equivFocalOf` at
  `:65-71`): 6–12 IPCs on a multi-camera device.
- `CameraCaps.read(...)` — another `getCameraCharacteristics` + `getOutputSizes` (`CaptureCapabilities.kt:69-133`).
- `chooseVideoSize(sel)` — a third `getCameraCharacteristics` + `getOutputSizes` (`CameraEngine.kt:549-561`).

The startup path (`onPreviewSurfaceAvailable`, `:94`) was correctly moved onto `setupExecutor`, but
this override path was not — it is the identical anti-pattern the prior review's F1 explicitly called
out ("`setCameraOverride` has the same problem when the user switches lenses"). Each IPC is several
ms; total 50–150 ms of blocked UI thread → visible jank.

Real-world trigger is currently narrow: the only UI entry is `AdvancedTab`'s "Camera Override" reset,
which calls `onCameraOverride(null)` (`ui/controls/ProSheet.kt:561-568`) — but `select(manager, null)`
still runs the full fan-out, and any future "set a specific lens" UI would hit it too.

Fix: dispatch the whole `select + CameraCaps.read + chooseVideoSize` block onto `setupExecutor`
(as the startup path does), then hop back to wire GL/preview and `openCamera`.

### PERF-3 — Per-frame `FloatArray` allocation on the GL render thread (EIS provider)
**Severity Medium · Confidence High · Residual (F8 half-fixed: the Runnable re-post was removed, this was not)**
`gl/GlPipeline.kt:209` calls `eisProvider?.invoke()` every frame while EIS is on (EIS defaults ON,
`CameraEngine.kt:66`). The provider is `{ gyro.currentCorrection() }` (`CameraEngine.kt:112`) and
`GyroEis.currentCorrection()` returns `floatArrayOf(corrYaw, corrPitch, corrRoll)`
(`stab/GyroEis.kt:63`) — a **new 3-element FloatArray every rendered frame** (30–60/s). ~32 B/frame →
low individually, but it is the only per-frame heap allocation left in the single hottest loop, so it
sets the young-gen GC cadence and will compound with any future per-frame work. The in-code comment
at `GlPipeline.kt:205-208` acknowledges it and defers it as "outside this file's scope."

Fix: change the provider contract to write into a GL-thread-owned reusable `FloatArray` (e.g.
`currentCorrection(out: FloatArray)`), or expose `corrYaw/corrPitch/corrRoll` as three reads. Folds
cleanly together with PERF-11 (publish the three values as one snapshot).

### PERF-4 — `awaitMuxerStart()` busy-waits with `Thread.sleep(2)`, coupling audio-format arrival to the video drain → GL/preview stall at record start
**Severity Medium · Confidence Medium-High · Re-opened (F6 was never in the fix plan's Fixed list)**
`video/VideoRecorder.kt:317-320`:
```
private fun awaitMuxerStart(): Boolean { while (running && !muxerStarted) Thread.sleep(2); return muxerStarted }
```
consumed by the video drain at `:172` and audio drain at `:255`. The muxer only starts once **all
expected tracks** are added (`maybeStartMuxer`, `:304-309`); with audio enabled `expectedTracks == 2`,
so the **video** drain thread parks in this spin until the **audio** encoder emits its
`INFO_OUTPUT_FORMAT_CHANGED`. While parked it stops calling `releaseOutputBuffer`, the HEVC encoder's
output buffers fill, and because the encoder input is the GL `encoderEgl` surface,
`eglSwapBuffers(encoderEgl)` in `GlPipeline.drawFrame()` (`gl/GlPipeline.kt:238`) blocks — which
stalls the single GL thread and therefore the **preview** (drawn on the same thread). Result: dropped
preview frames at the start of every audio-enabled recording until the audio format arrives, plus a
2 ms CPU spin.

Fix: replace the spin with a `CountDownLatch` (or `wait/notify`); start the muxer on the video track
with a bounded timeout for audio so a slow audio start cannot block video drain (and hence GL).

### PERF-5 — `CameraUiState` is Compose-unstable, so every composable that takes it is non-skippable; unconditional tickers emit new state 5–20 Hz
**Severity Medium · Confidence High · New**
`camera/CameraState.kt:113-129`: `HistogramData` and `WaveformData` hold `IntArray` fields, which the
Compose compiler treats as **unstable**; that makes both data classes unstable, and therefore
`CameraUiState` (which holds `histogramData`/`waveformData`, `:109-110`) is **unstable**. There is no
stability-config file and no `@Stable`/`@Immutable` annotations (verified). Strong-skipping (default
in Kotlin 2.3.10's Compose compiler) falls back to *instance* equality for unstable params, but every
StateFlow emission is a fresh `it.copy(...)` instance, so the check always fails.

Consequence: `CameraScreen(state=…)` (`ui/CameraScreen.kt:100`), `TopBar(state=…)` (`:329`),
`ManualDialCluster(state=…)` (`ui/controls/ManualDials.kt:65`) and `ProSheet(state=…)`
(`ui/controls/ProSheet.kt:86`) all recompose on **every** emission, regardless of which field
changed. The dial cluster then rebuilds all 6 `DialChip`s and their `.format()`/`formatShutterSpeed`/
`formatFocusRelative` strings each time (`ManualDials.kt:141-187`).

Emission cadence makes this bite during recording/assist use:
- `levelTicker` (`ui/CameraViewModel.kt:65-70`) emits `it.copy(levelRoll=…)` every 100 ms
  **unconditionally** (no change check) while Level is on → 10 Hz full-tree recomposition.
- `recordTicker` (`:47-52`) emits every 200 ms during recording → 5 Hz.
- `onAudioLevel` (`:86`) emits ~10 Hz during audio recording.
- analysis callback (`:85`) emits ~5 Hz when scopes on.
Combined that is ~15–25 Hz of whole-`CameraScreen`-tree recomposition during recording, none of it
skippable at the `state`-taking boundaries. (At idle it is fine: `orientationTicker`, `:74-80`, only
emits on an actual orientation change.)

Fix: (1) make the scope data stable — annotate `HistogramData`/`WaveformData` `@Immutable`, or move
them out of `CameraUiState` into their own StateFlow so the main UI state stops depending on arrays;
(2) pass children the granular fields they read (`controls`, `caps`, `mode`, …) instead of the whole
`state`, so a `levelRoll`/`audioLevel` tick can't force the dial cluster to recompose; (3) give
`levelTicker` a change threshold so it doesn't emit identical rolls at 10 Hz.

### PERF-6 — Muxer writes hold `muxerLock` across file I/O; encoder backpressure blocks the shared GL thread
**Severity Medium · Confidence Medium · Re-confirmed (F7)**
`video/VideoRecorder.kt:175` (`muxer?.writeSampleData(videoTrack, …)` inside `synchronized(muxerLock)`)
and `:258` (audio, same lock). `MediaMuxer.writeSampleData` writes into the MediaStore-backed
`ParcelFileDescriptor` and can block on I/O; both drain threads contend on the one lock (the muxer is
not thread-safe, so serialization is required). If the audio thread holds the lock during a slow
write, the video thread's `writeSampleData` waits → video output buffers aren't released → the HEVC
encoder input fills → `eglSwapBuffers(encoderEgl)` on the GL thread (`gl/GlPipeline.kt:234-239`)
blocks → preview cadence collapses to encoder/muxer throughput. Preview-before-encoder ordering
(`:221-223` then `:234-239`) means the *current* shown frame isn't gated, but the *next* frame can't
start until the encoder swap returns.

This is inherent to one-thread-draws-both plus one-lock-serializes-both-writes. Mitigations: keep the
critical section minimal (already just the write), guarantee the drain never parks (PERF-4), and
consider a separate EGL-shared thread/context for the encoder surface so preview cadence is decoupled
from encoder/muxer backpressure. Needs-device-profiling to quantify the fps drop.

### PERF-7 — Camera streams up to 4K into the preview SurfaceTexture even when only previewing
**Severity Low-Medium · Confidence Medium · needs-device-profiling · New**
`camera/CameraEngine.kt:105` sets `videoSize = chooseVideoSize(sel)` (largest 16:9 up to **3840**
wide, `:557`), and `:111` / `gl/GlPipeline.kt:142-147` push that into the SurfaceTexture default
buffer size (`setDefaultBufferSize(cameraW, cameraH)`, `:129/:145`). The repeating preview request
targets this SurfaceTexture, so the camera/ISP produces **up to 4K frames continuously**, even when
the user is merely framing (not recording) — the GL stage then downsamples to the ~1440-wide preview.
Streaming 4K continuously costs meaningfully more ISP/bus/power/heat than a preview-sized stream on a
telephoto app where users hold a framing for a long time.

Fix: feed the preview SurfaceTexture at a preview-appropriate size (e.g. ≤1080p) until recording
starts; raise to the record resolution on `startRecording` (a session reconfig is already implied by
the "streams the old size until the next open" note at `:249-253`). Verify thermals on device.

---

## Low / hardening

### PERF-8 — `glReadPixels` executes after `eglSwapBuffers`, reading an undefined back buffer
**Severity Low-Medium · Confidence Medium · New (perf-waste + correctness)**
In `drawFrame` the order is: `swapBuffers(previewEgl)` (`gl/GlPipeline.kt:223`) **then** the analysis
block (`:227-232`) → `runAnalysisReadback` → `makeCurrent(previewEgl)` again (`:267`) → `glReadPixels`
(`:269`). By default EGL window surfaces use `EGL_SWAP_BEHAVIOR = EGL_BUFFER_DESTROYED`, so after
`eglSwapBuffers` the back-buffer contents are **undefined**. The (expensive, per PERF-1) readback may
therefore sample garbage or a stale frame — wasted work at best, wrong scopes at worst — and it also
issues a redundant `makeCurrent` for a surface that is already current post-swap.

Fix: move the readback **before** `swapBuffers(previewEgl)` (right after the preview `draw`), which
makes the sampled pixels valid and drops the redundant `makeCurrent`. (Combine with PERF-1's small-FBO
readback.)

### PERF-9 — Main-thread pollers aren't tied to the `onStop` lifecycle
**Severity Low · Confidence High · New**
`ui/CameraViewModel.kt`: `levelTicker` (10 Hz, `:65-70`), `orientationTicker` (5 Hz, always posted in
`init`, `:88`), `recordTicker` (5 Hz, `:47-52`). `onStop()` (`:297`) only calls `engine.pause()`; it
does not stop these `mainHandler` loops (they're only cleared in `onCleared`, `:299-303`). So while
backgrounded the app keeps waking the main thread 5–10×/s (orientation poll reads the now-reset gyro;
level keeps emitting). Worse, if the app is backgrounded **while recording**, `pause()` stops the
recorder but `recordTicker` keeps firing and `isRecording` stays true in UI state — a leaked timer
plus stale state. Low battery/correctness cost.

Fix: stop `levelTicker`/`orientationTicker`/`recordTicker` in `onStop` (and restart the appropriate
ones in `onStart`); reconcile `isRecording`/`recordElapsedMs` when `pause()` tears the recorder down.

### PERF-10 — Unbounded `ioExecutor` queue during BURST/TIMELAPSE holds full JPEGs + serialized full-bitmap decodes
**Severity Low-Medium · Confidence Medium · New**
`camera/CameraEngine.kt:352` posts `saveHeifAsync(bytes, rotation)` to the single-thread `ioExecutor`
(`:35`). Each queued task retains the shot's full compressed JPEG `bytes` (`:348-352`) and, when it
runs, decodes to a full ARGB_8888 bitmap (~4 bytes/px; ~200 MB at 50 MP, more at 200 MP) plus a
rotated copy (`saveHeifAsync`, `:376-416`). BURST fires 5 shots (`BURST_COUNT`, `:575`, chained but
each queues an encode) and TIMELAPSE (`:320-326`) re-fires on a fixed **delay** where `capturePhoto`
returns immediately (async), so captures can enqueue faster than `ioExecutor` drains. Under a fast
interval or slow big-sensor encode the queue and its retained compressed buffers grow unboundedly →
transient memory growth / OOM pressure (the per-task `OutOfMemoryError` guard at `:402` catches the
decode, but not queue growth). The single-thread executor correctly prevents *parallel* OOM.

Fix: bound the in-flight/queued encode count (drop or coalesce when saturated), and free the retained
`bytes` promptly. Longer-term this disappears with direct-HEIC capture (deferred L-HEIF-TRANSCODE).

### PERF-11 — Gyro correction tuple read non-atomically across sensor and GL threads
**Severity Low · Confidence Medium · Residual (F11)**
`stab/GyroEis.kt:40-42` (three separate `@Volatile` floats), written together in `onSensorChanged`
(`:97-99`) and read separately in `currentCorrection()` (`:63`). The GL thread can observe a mix of
axes from adjacent samples (torn tuple). Visually negligible (≤1 sample, smoothed by the low-pass),
but it is a genuine data race — fold into the PERF-3 fix by publishing all three into one reused array
under a single volatile store.

### PERF-12 — Static geometry uploaded from client memory every draw (no VBO)
**Severity Low · Confidence Medium · Residual (F12)**
`gl/FlipRenderer.kt:155-163` binds `quad`/`texCoords` client `FloatBuffer`s via
`glVertexAttribPointer` and enables/disables the two attrib arrays on every `draw`. Trivial for 4
vertices, but it is per-frame CPU→driver copying and redundant state churn in the hottest loop. Fix:
upload the static quad+texcoords into a VBO once in `init()` and bind once. Low priority.

### PERF-13 — Repeating request fully rebuilt from `TEMPLATE_PREVIEW` on every control change
**Severity Low · Confidence Medium · Mitigated (F2 debounce present)**
`camera/CameraController.kt:199-242` (`startPreview`) allocates a new `CaptureRequest.Builder` from
`TEMPLATE_PREVIEW`, re-applies ~20 keys via `applyManualControls`/`applyMetering`, and calls
`setRepeatingRequest` — on every `updateControls` (`:287-290`). In MANUAL WB it also recomputes
`kelvinTintToRggbGains` with `Math.pow`/`Math.log` each time (`camera/ManualControls.kt:143`,
`:242-271`). The 80 ms debounce in `ui/CameraViewModel.kt:287-293` caps this at ~12/s during a drag
(down from per-tick), so it is no longer a stutter driver, but it still rebuilds the whole request and
re-latches the repeating request each time rather than mutating a retained builder. Fix (optional):
keep one retained `CaptureRequest.Builder`, mutate only changed keys, resubmit; cache WB gains keyed
on `(kelvin,tint)`.

### PERF-14 — Analysis compute allocates fresh IntArrays each pass
**Severity Low · Confidence High · New**
`gl/GlPipeline.kt:291-316` (`computeHistogram`: four `IntArray(256)`) and `:319-342`
(`computeWaveform`: `IntArray(128*64)` = 8192 ints) allocate on every analysis pass (~5 Hz when scopes
on), plus the `HistogramData`/`WaveformData` wrappers, all retained in UI state until the next update.
~40 KB/pass → ~200 KB/s of garbage on the `analysisExecutor` thread. Off the GL thread, so no render
stall, but avoidable GC churn. Fix: reuse per-scope IntArray buffers (double-buffered against the UI
read) instead of allocating each pass.

---

## Non-issues verified (to bound the review)

- **No per-frame shader/program recompile**: program + uniform locations built once in
  `FlipRenderer.init()` (`gl/FlipRenderer.kt:57-79`); `draw()` only sets uniforms.
- **Draw matrices reuse preallocated arrays** (`FlipRenderer.kt:43-45`, `GlPipeline.kt:85 stMatrix`),
  and `getTransformMatrix(stMatrix)` reuses the buffer — no per-frame matrix allocation.
- **Rendering is camera-cadence driven** (`SurfaceTexture.onFrameAvailable` → `drawFrame()` directly
  on the GL handler, `GlPipeline.kt:132`) and vsync-paced by `eglSwapBuffers` — no free-running loop,
  and the F8 Runnable re-post is gone.
- **Preview stream doesn't target the JPEG/RAW ImageReaders** (`CameraController.startPreview` adds
  only the GL surface, `:209-210`), so no continuous ImageReader backpressure; readers are `maxImages=2`
  and used only on capture.
- **Thread lifecycle is clean now**: `CameraController.bg` quits (`:397`), `GlPipeline.thread`
  quits (`GlPipeline.kt:362`), `analysisExecutor`/`setupExecutor`/`ioExecutor`/`timelapseScheduler`
  shut down (`GlPipeline.kt:345`, `CameraEngine.kt:501-503`), VideoRecorder video/audio threads are
  `join`ed (`VideoRecorder.kt:127-128`), gyro unregisters (`GyroEis.kt:57-60`). No leaked threads found.
- **Gyro is 200 Hz** (`GyroEis.kt:127`) — F5 resolved; consumed once per frame, no longer oversampled.
- **`MutableStateFlow.update` from background threads** (`onAnalysis`/`onAudioLevel`/`onStatus`) is
  thread-safe; `collectAsState` observes on main.
- **`orientationTicker` only emits on an actual orientation change** (`CameraViewModel.kt:77`), so it
  does not churn recomposition at rest (contrast with `levelTicker`, PERF-5/PERF-9).
- **`onShutter` is stable** (`remember(state.mode)`, `CameraScreen.kt:262-270`) — F14 resolved; the
  Canvas chrome buttons take primitive params and skip.
