# Architecture Review — find-x9-ultra-camera (Android tele camera app)

Reviewer: Architect (READ-ONLY). Scope: architectural/design risk of the app in
`app/src/main/kotlin/com/hletrd/findx9tele/`. 26 Kotlin files, ~3,740 LOC.
Classification key: **Confirmed** (proven by reading code), **Likely** (strong inference),
**Risk** (latent hazard that will bite under specific conditions).

---

## 1. File inventory & layer map

| Layer | Files (LOC) | Responsibility |
|-------|-------------|----------------|
| app root | `MainActivity.kt` (91), `TeleCameraApp.kt` (6) | Activity, permission gate, Compose host |
| ui | `CameraScreen.kt` (353), `CameraViewModel.kt` (179), `CameraActions.kt` (83) | Stateless Compose root, state holder, UI→engine command interface |
| ui/controls | `ProControls.kt` (606) | Pro panel + all sliders/selectors |
| ui/overlays | `Overlays.kt` (252) | Grid/level/histogram/status/timer/recording overlays |
| ui/theme | `Theme.kt` (41) | Compose theme |
| camera | `CameraEngine.kt` (250), `CameraController.kt` (236), `CameraState.kt` (76), `CaptureCapabilities.kt` (117), `ManualControls.kt` (195), `CameraSelector2.kt` (74), `VendorTagInspector.kt` (57) | Facade, Camera2 session, UI-state DTO + enums, caps reader, controls DTO + request builder, lens selection, RE dump |
| gl | `GlPipeline.kt` (188), `FlipRenderer.kt` (192), `EglCore.kt` (97), `Shaders.kt` (101) | GL render thread, flip/EIS renderer, EGL wrapper, shaders |
| capture | `DngCapture.kt` (30), `HeifCapture.kt` (25) | Still encoders |
| stab | `GyroEis.kt` (90) | Gyro→shake-residual signal |
| storage | `MediaStoreWriter.kt` (84) | MediaStore pending-file writer |
| video | `VideoRecorder.kt` (228), `ColorProfiles.kt` (52) | HEVC/AAC muxing, encoder formats |
| focus | `FocusMapping.kt` (35) | Pure slider↔diopter math (only unit-tested class) |

### Actual dependency / coupling graph (arrows = compile-time dependency)

```
MainActivity ──> CameraViewModel ──implements──> CameraActions
     │                  │
     └──> CameraScreen ─┤ (reads CameraUiState, calls CameraActions)
                        ├──> ProPanel/ProControls ──> CameraActions, CameraCaps, ManualControls, FocusMapping
                        └──> Overlays ──> GridType, HistogramData, ColorTransfer, PhotoFormats

CameraViewModel ──owns──> CameraEngine
                └──holds──> MutableStateFlow<CameraUiState>

CameraEngine (FACADE / God object) ──>
     GlPipeline ──> EglCore, FlipRenderer(──>Shaders)
     CameraController ──> Camera2, CameraCaps, ManualControls(applyManualControls), TeleSelection
     VideoRecorder ──> ColorProfiles, MediaStoreWriter
     GyroEis (also injected into GlPipeline via provider lambda)
     CameraSelector2 ──> TeleSelection
     CameraCaps.read
     MediaStoreWriter, DngCapture, HeifCapture   <-- encoding done INSIDE the facade
```

**Layering observation:** the `camera` package is the de-facto shared kernel — the `ui` layer
depends heavily on it (`CameraUiState`, `CameraCaps`, `ManualControls`, all enums). Data flows
one way (UI → ViewModel → Engine → components) and callbacks return via two `((…)->Unit)`
hooks (`onStatus`, `onCapsReady`). The high-level shape (stateless Compose + ViewModel +
facade) is sound. The problems are concentrated in the facade, the threading contract, and
error/lifecycle handling.

---

## 2. Findings (ranked by risk)

### F1 — Unsynchronized shared mutable state in `CameraEngine` across UI thread and GL thread — CONFIRMED, High
**Where:** `camera/CameraEngine.kt`. Mutable non-volatile fields: `selection`, `caps`,
`videoSize`, `controls`, `transfer`, `overrideId`, `started`, `previewSurface`, `controller`,
`recorder`, `teleconverterMode`, `eisEnabled` (lines 32–46). `grep` confirms the file contains
**no** `@Volatile`, `synchronized`, or `@MainThread`.
**Problem:** `onPreviewSurfaceAvailable` runs on the UI thread (lines 53–76) and sets
`selection`, `caps`, `videoSize`, then calls `gl.start(tenBit) { input -> … openCamera(input) }`.
That trailing lambda (`onInputReady`) executes **later on the `gl-pipeline` HandlerThread**
(`GlPipeline.kt:58–65`, invoked at `GlPipeline.kt:89`). Inside it, the GL thread reads
`videoSize`, calls `applyStabilization()` (reads `caps`, `teleconverterMode`, `eisEnabled`,
lines 79–84) and `openCamera` (reads `selection`, `caps`, `controls`; writes `controller`,
lines 101–115). Meanwhile the UI thread can concurrently write `teleconverterMode`/`eisEnabled`
(`setTeleconverterMode`/`setEisEnabled`, lines 86–87), `controls` (`setControls`, 119–122), or
read `controller` (`capturePhoto`, 145; `startRecording`, 184). None of these crossings has a
happens-before edge.
**Why risky:** the JMM gives no visibility guarantee, so `capturePhoto()` on the UI thread may
observe `controller == null` (silent no-op capture) or a torn `caps`/`selection` while the GL
thread is mid-write. This is timing-dependent and will not reproduce in a debugger.
**Failure scenario:** user taps the shutter within a few hundred ms of the preview appearing;
the GL-thread write of `controller` is not yet visible to the UI thread → tap is silently
dropped (`capturePhoto` line 145 `?: return`). Or user toggles teleconverter during startup →
`applyStabilization` on the GL thread reads a half-updated combination.
**Refactor:** give `CameraEngine` a single confinement thread. Either (a) route every public
method through the GL `Handler` (make the engine GL-thread-confined and treat UI calls as
`post`), or (b) hold all engine state in one `@Volatile`-published immutable snapshot object
swapped atomically, or (c) convert the `onInputReady` continuation to hop back to the main
thread before touching engine fields. Minimal fix: mark the shared fields `@Volatile` and make
`openCamera`/`applyStabilization` read a captured local snapshot. Trade-off: option (a) is the
cleanest but reworks the call surface; `@Volatile` is cheap but only patches visibility, not
compound-update atomicity.

### F2 — Promised capture-session fallback does not exist; `onConfigureFailed` is a silent dead-end — CONFIRMED, High
**Where:** `camera/CameraController.kt:130–132`. `onConfigureFailed` body is a comment only:
"Fallback path (e.g. 10-bit + RAW combo unsupported) is handled by the caller." No caller
handles it: `CameraEngine.openCamera` (lines 101–115) passes only `onReady`/`onError`, and
`onConfigureFailed` invokes **neither**.
**Problem:** when the requested stream combo is unsupported (HLG10 preview + JPEG + RAW is a
real over-subscription on many HALs), the session never configures, `onReady` never fires,
`onStatus(null)` is never sent, and no error is surfaced. `grep` confirms no `fallback` logic
anywhere.
**Why risky:** the exact device this app targets (10-bit tele + RAW) is the most likely to hit
this combo limit. The user sees a permanently black preview with no message and no recovery.
**Failure scenario:** tele physical camera advertises HLG10 but cannot co-stream RAW at full
size → `onConfigureFailed` → black screen forever.
**Refactor:** implement a real fallback ladder in `CameraController` (drop RAW, then drop
HLG10, then drop to 8-bit preview-only) driven from `configureSession`, and wire
`onConfigureFailed` to either retry with a reduced config or call `onError`. Surface the
degraded mode to the ViewModel. Trade-off: adds branching/state to the controller; unavoidable
given Camera2's combinatorial stream rules.

### F3 — No Activity/lifecycle release path: camera + gyro + GL stay live in background — CONFIRMED, High
**Where:** release happens **only** in `CameraViewModel.onCleared()` (lines 174–178).
`grep` confirms there are **no** `onPause`/`onResume`/`onStop`/`LifecycleObserver` anywhere.
`MainActivity` sets `FLAG_KEEP_SCREEN_ON` (line 44) and never releases.
**Problem:** `onCleared` fires only when the ViewModel is destroyed (activity finishing), not on
background. On home-press with a `SurfaceView`, `surfaceDestroyed` fires →
`CameraEngine.onPreviewSurfaceDestroyed` (lines 95–99) drops the GL preview output **but keeps
the camera device open, the gyro registered (`SENSOR_DELAY_FASTEST`), and the GL thread alive**.
The comment "Portrait is locked, so this is app teardown" (line 96) is incorrect —
`surfaceDestroyed` also fires on backgrounding.
**Why risky:** holding an open `CameraDevice` blocks other camera apps and violates Android
camera etiquette; `SENSOR_DELAY_FASTEST` gyro + a spinning GL thread drain battery while the app
is not visible; and there is no reopen path, so behavior after returning from background is
undefined (the camera may already have been evicted by the framework, producing
`onDisconnected` → `close()` and a dead preview with no reopen).
**Failure scenario:** user backgrounds the app during recording; camera stays held, another app
cannot open the camera, and on return the preview is black.
**Refactor:** make `CameraViewModel` (or a dedicated lifecycle owner) observe
`Lifecycle.Event.ON_STOP`/`ON_START`: release camera + gyro + encoder on stop, reopen on start.
Keep the GL context if desired but stop the camera stream. Trade-off: reopen adds a
cold-start latency on resume; correct and expected for a camera app.

### F4 — `CameraController` leaks its `HandlerThread` on every `close()`; camera-override re-open races the old device — CONFIRMED, High
**Where:** `camera/CameraController.kt:42` (`bg = HandlerThread("camera").apply { start() }`),
`close()` at 217–228. `grep` confirms `close()` closes device/session/readers but **never**
calls `bg.quitSafely()`/`bg.quit()`. `CameraEngine.setCameraOverride` (lines 128–140) calls
`controller?.close()` then immediately `openCamera(input)` which constructs a **new**
`CameraController` (with a new HandlerThread) and opens the **same** logical camera id.
**Problem:** (a) every controller instance leaks one native thread — one initial + one per
override switch; (b) `close()` posts async teardown on the *old* controller's handler while the
new controller synchronously calls `manager.openCamera` on the same id, so two `CameraDevice`s
can be open on the same camera during the overlap.
**Why risky:** thread leak accumulates across override switches; the concurrent open can throw
`CAMERA_IN_USE`/`MAX_CAMERAS_IN_USE`, routed to the unhandled `onError` string.
**Failure scenario:** user flips camera override a few times → N leaked "camera" threads and an
intermittent "camera error" on switch because the previous device is still closing.
**Refactor:** reuse a single long-lived `CameraController` (re-configure session instead of
recreating), and `bg.quitSafely()` in `close()`. If recreation is kept, make `open()` wait for
the prior device's `onClosed` before opening the same id. Trade-off: sequencing adds a small
switch latency; required for correctness.

### F5 — `CameraEngine` is a God object: orchestration + encoding + policy in one class — CONFIRMED, Medium
**Where:** `camera/CameraEngine.kt` (250 LOC). Beyond wiring, it directly owns: HEIF pixel
decode/rotate/write (`saveHeif`, 158–171), DNG write (`saveDng`, 173–180), `Bitmap` 180° rotate
(`rotate180`, 217–220), video-size policy (`chooseVideoSize`, 222–234), bitrate policy
(`bitRateFor`, 236–237), filename policy (`fileName`, 239–242), stabilization policy
(`applyStabilization`, 79–84), and selection re-entry (`setCameraOverride`).
**Problem:** at least five responsibilities (lifecycle orchestration, still-encode pipeline,
capture policy, stabilization policy, selection) in one class. The `saveHeif`/`saveDng` logic is
domain work that belongs in `capture/`+`storage/`, not the facade — note `capture/HeifCapture`
already exists but the bitmap decode/rotate/MediaStore glue lives in the engine.
**Why risky:** the class becomes the change-magnet — every new format, policy, or lens rule
edits it, and its untestable threading (F1) means each edit is high-risk.
**Refactor:** extract a `StillCapturePipeline` (decode/rotate/encode/persist) taking the
`PhotoCallback` images; extract `VideoSizePolicy`/`BitRatePolicy` as pure functions (testable);
keep `CameraEngine` as a thin coordinator. Trade-off: more small classes; pays off in
testability and blast-radius.

### F6 — Triple state bookkeeping: `CameraUiState`, `CameraEngine` fields, and `GlPipeline` fields all shadow the same values — CONFIRMED, Medium
**Where:** `teleconverterMode`/`eisEnabled`/`transfer`/`controls`/`photoFormats` exist in
`CameraUiState` (`camera/CameraState.kt:40–68`), are **duplicated** as `CameraEngine` fields
(`CameraEngine.kt:38–46`), and several are duplicated **again** in `GlPipeline`
(`peaking`/`zebra`/`falseColor`/`transfer`/`eisEnabled`/`eisFocal`, `GlPipeline.kt:41–52`). The
ViewModel keeps them in sync by calling both `engine.setX(...)` **and** `_state.update{...}` for
each toggle (e.g. `onToggleEis` 102–105, `onToggleTeleconverter` 96–99).
**Problem:** three sources of truth kept coherent only by discipline. If the engine ever mutates
an internal value without echoing to the ViewModel (e.g. a future capability-driven downgrade),
the UI and hardware silently disagree. The single-source-of-truth principle holds for the *UI*
(StateFlow) but not for the *system*.
**Why risky:** divergence bugs are invisible until a user reports "the UI says HLG but the file
is SDR." Adds coupling: every new control touches three places.
**Refactor:** make `CameraUiState` the single source and have the engine derive its behavior
from an applied snapshot (engine holds no independent copies; it receives the immutable state or
a projection each time). `GlPipeline` can keep render-local copies but they should be fed from
that one snapshot. Trade-off: a slightly larger apply call per change vs. eliminating drift.

### F7 — Error propagation is a single mutable status string; success is reported even on total failure — CONFIRMED, Medium
**Where:** `CameraEngine.onStatus: ((String?) -> Unit)?` (line 48) → `ViewModel` writes it into
`statusMessage` (line 45). In `capturePhoto` (144–156) each save is wrapped in
`runCatching{…}.onFailure{ onStatus("…failed") }`, then **unconditionally** `onStatus("saved")`
(line 152) runs afterward.
**Problem:** because the last write wins, a capture where **both** HEIF and DNG throw still ends
with `statusMessage = "saved"`. There is no error type, severity, id, or queue —
localized strings are the entire error channel, and rapid statuses overwrite each other.
**Why risky:** users are told files were saved when they were not; failures are unobservable in
logs (everything is `runCatching{}.getOrNull()` — `CameraSelector2`, `CameraCaps.read`,
`MediaStoreWriter`, `openCamera` all swallow silently).
**Failure scenario:** storage full → both writes fail → toast says "saved" → user believes the
shot is safe and it is lost.
**Refactor:** introduce a small sealed `CaptureResult`/`CameraEvent` type (Success/PartialFail/
Fail with cause) emitted on a channel; only report success when at least one write succeeded.
Log swallowed exceptions. Trade-off: a bit more plumbing than a string; essential for a camera.

### F8 — Dead / half-wired UI state: `level` (roll never fed), `punchIn` (no consumer), `histogramData` (no producer) — CONFIRMED, Medium
**Where & proof (grep):**
- `LevelOverlay(rollDegrees=…)` is declared with a roll parameter (`Overlays.kt:110`) but
  `CameraScreen.kt:112` calls `LevelOverlay(modifier = …)` with **no** roll → always `0f` →
  always shows "level/green." `GyroEis.corrRoll` exists (`stab/GyroEis.kt:73`) but is never
  routed to the UI.
- `punchIn`: written by `onTogglePunchIn` (`CameraViewModel.kt:123`), rendered as a toggle
  (`ProControls.kt:551`), stored in state (`CameraState.kt:57`) — but **no** code reads
  `state.punchIn` to actually punch-in the preview.
- `histogramData`: consumed by `HistogramOverlay` (`CameraScreen.kt:146`) and declared
  (`CameraState.kt:67`) but **never produced** (no GL readback path). (Histogram is a documented
  intentional placeholder; `level`/`punchIn` are not.)
**Problem:** three user-facing toggles that appear functional but do nothing (`level` actively
misleads by always reading level).
**Why risky:** erodes trust and hides that the gyro→UI and GL→UI feedback channels are missing;
future contributors assume the wiring exists.
**Refactor:** either wire them (surface `GyroEis` roll into `CameraUiState.levelRoll` and pass
it to `LevelOverlay`; implement punch-in as an EIS crop/zoom in `GlPipeline`; add a GL luma
readback for the histogram) or remove the controls until implemented. Trade-off: wiring the
level is cheap and high-value; punch-in/histogram need real GL work.

### F9 — No testability seams below the ViewModel: collaborators are `new`-ed inside the facade — CONFIRMED, Medium
**Where:** `CameraEngine` constructs `GlPipeline()`, `GyroEis(context)`, `CameraController(context)`,
`VideoRecorder(context)` directly (lines 31–44, 104, 188). Only `FocusMapping` and
`kelvinTintToRggbGains` are pure; only `FocusMapping` has a test
(`app/src/test/.../FocusMappingTest.kt`, 132 LOC). No `androidTest` exists.
**Problem:** the orchestration, policy (`chooseVideoSize`, `bitRateFor`, selection distance
math, WB math), and threading are all entangled with concrete Android types and construction, so
none can be unit-tested. Policy logic that is *inherently* pure is trapped behind hardware glue.
**Why risky:** the riskiest code (threading in F1, fallback in F2, selection in `CameraSelector2`)
has zero automated coverage; regressions are only findable on-device.
**Refactor:** extract pure policy to standalone functions/objects (already the pattern with
`FocusMapping`), and inject collaborators into `CameraEngine` behind small interfaces so a test
can substitute fakes. Trade-off: interfaces add indirection; justified for the policy seams even
if the GL/Camera2 glue stays integration-tested.

### F10 — Module-boundary incoherence: a UI-state DTO lives in `camera/`, and a data class is fused with framework code — CONFIRMED, Low
**Where:** `CameraUiState` + `HistogramData` (UI concerns) are defined in
`camera/CameraState.kt`. `camera/ManualControls.kt` holds the pure `ManualControls` data class
**and** the `CaptureRequest.Builder.applyManualControls` extension (hard Camera2 dependency) in
one file.
**Problem:** `CameraUiState` is really a `ui`-layer type placed in `camera`, making `camera`
depend conceptually on presentation concerns; and `ManualControls` (a plain DTO the UI reads)
cannot be referenced without pulling Camera2 into the compile unit because it shares a file with
the request builder.
**Why risky:** blurs the boundary that keeps the UI hardware-independent (the stated goal in the
`CameraUiState` doc comment, `CameraState.kt:37`).
**Refactor:** move `CameraUiState`/`HistogramData` to the `ui` package; split
`ManualControls.kt` into the pure DTO (shared) and `ManualControlsRequest.kt` (the Camera2
extension). Trade-off: churn only; improves the dependency direction.

---

## 3. Threading model summary (who owns what)

| Thread | Owner | Touches | Hazard |
|--------|-------|---------|--------|
| Main/UI | `CameraViewModel` (doc: "UI-thread only"), Compose | `_state`, all `engine.setX` | writes engine fields read by GL thread (F1) |
| `gl-pipeline` | `GlPipeline` | EGL, `FlipRenderer`, `drawFrame`, `onInputReady` continuation | runs `openCamera`/`applyStabilization` reading engine fields (F1) |
| `camera` (per `CameraController`) | `CameraController` | Camera2 callbacks, `Pending` completion | thread leaked on `close()` (F4) |
| gyro sensor callback | `GyroEis` | writes `@Volatile corr*` | correctly published; read via provider on GL thread (OK) |
| `video-drain`, `audio-encode` | `VideoRecorder` | muxer under `muxerLock` | correctly guarded (OK) |

The GL and camera components are individually thread-disciplined; the **seam that is not
disciplined is `CameraEngine` itself** (F1), which is called from the UI thread but has one
callback body executing on the GL thread.

## 4. What is done well (to preserve during refactors)
- Stateless Compose UI driven by one `StateFlow<CameraUiState>` + a single `CameraActions`
  command interface (`CameraScreen.kt`, `CameraActions.kt`) — clean unidirectional UI.
- `CameraCaps` flattening hardware into an immutable DTO read once on open
  (`CaptureCapabilities.kt`) — good capability gating.
- `VideoRecorder` muxer synchronization (`muxerLock`, `maybeStartMuxer`, `awaitMuxerStart`) and
  `GyroEis` `@Volatile` publication are correct concurrency.
- Pure, tested `FocusMapping`; pure `kelvinTintToRggbGains` — right instinct, under-applied.

## 5. Top risks, prioritized
1. **F1** unsynchronized engine state across UI/GL threads — dropped captures, torn reads (High).
2. **F2** non-existent session fallback → permanent black preview on the target's likely
   stream-combo limit (High).
3. **F3** no background release → camera held, battery drain, undefined resume (High).
4. **F4** HandlerThread leak + double-open race on camera override (High).
5. **F5–F7** God object, triple state bookkeeping, lossy error channel reporting false success
   (Medium).
6. **F8–F10** dead `level`/`punchIn` wiring, no test seams, boundary placement (Medium/Low).

## 6. Suggested sequencing
1. Fix F3 (lifecycle release) and F2 (fallback) first — they cause hard user-visible failures.
2. Fix F1 by confining `CameraEngine` to one thread (or snapshot-publishing state); this also
   de-risks F4's re-open sequencing.
3. Fix F7 (success-only-on-real-success + real error type), then F4 thread quit + controller reuse.
4. Refactor F5/F6 (extract still pipeline + collapse to single state source) once threading is
   safe, unlocking F9 test seams.
5. Wire or remove F8 features; tidy F10 boundaries opportunistically.
