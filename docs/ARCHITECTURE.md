# TeleCam Pro — Architecture

> **Current design authority.** This document describes the as-built system. The preserved
> `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` snapshot is historical and
> superseded wherever it differs; do not use it as the current implementation contract.

**Table of Contents**
1. [Overview](#overview)
2. [Module Map](#module-map)
3. [Data Flow](#data-flow)
4. [Threading Model](#threading-model)
5. [180° Flip + Rotation Pipeline](#180-flip--rotation-pipeline)
6. [Camera Selection & HAL Workarounds](#camera-selection--hal-workarounds)
7. [Stabilization and Orientation](#stabilization-and-orientation)
8. [Color & Video Pipeline](#color--video-pipeline)
9. [Capture & Storage](#capture--storage)
10. [Pro Controls Surface](#pro-controls-surface)
11. [Build & Toolchain](#build--toolchain)

---

## Overview

A professional single-device camera app for the **OPPO Find X9 Ultra** (Android 16 / API 36) that uses Camera2 to control the rear 3× periscope telephoto lens through a **Hasselblad "Earth Explorer" afocal 300 mm teleconverter** (≈4.286× magnification: 300 mm ÷ 70 mm). The app captures processed HEIF/JPEG stills, plus RAW/DNG when TELE uses a RAW-capable standalone 3× camera, and HEVC video with HLG, O-Log2, or SDR profiles. For HAL stability, the shipping Camera2 and EGL input path is display-referred SDR/8-bit. HLG maps that SDR signal according to ITU-R BT.2408-9 and cannot recover ISP-removed highlights; HLG/O-Log2 uses an HEVC Main10 container profile but is not an end-to-end 10-bit source pipeline.

The UI/UX reference is **Sony Alpha / Sony Xperia Pro camera operation**. Use Fn access, My Menu, MR
banks, PASM-style exposure, compact OSD, peaking, zebra, histogram, waveform, and review zoom. Keep
the viewfinder quiet: no tutorial banners, warning chips, marketing cards, or helper overlays unless
the user asks. Important states belong in the OSD, Fn, or menu rows. See [`UX_POLICY.md`](UX_POLICY.md).

Two critical consequences of the afocal converter drive the entire design:
- **Image rotation**: The afocal telescope delivers light rotated 180° (no erecting prism). Both the live preview and saved results must be corrected. Vertical flip + horizontal flip = 180° rotation (parity-preserving, not a mirror).
- **Near-infinity focus**: Exit light is approximately collimated, so the phone lens focuses near infinity. Manual focus with a nonlinear slider is essential for fine-tuning that critical zone.

---

## Module Map

| Package / File | Single Responsibility |
|---|---|
| **camera/** | |
| `CameraEngine.kt` | Facade orchestrating Camera2, GL, capture encoders, video recorder, sensors, and storage. Serializes camera reconfiguration, owns asynchronous save/finalization lanes, and publishes cross-thread state through volatile seams plus synchronized ownership gates. |
| `CameraController.kt` | Camera2 session lifecycle, capability-safe request building, and fallback plans across stream sets and session operation modes. Sets a mode only when its exact value is advertised and applies AE/AF regions independently only when each maximum region count is positive. Callback-driven, runs on a camera HandlerThread. |
| `CameraSelector2.kt` | Detects the telephoto physical lens: finds the camera with focal length closest to 70 mm, prefers standalone ID over physical sub-camera routing. |
| `CameraState.kt` | Enums plus `CameraUiState` — the shared UI and runtime-state language. |
| `CaptureCapabilities.kt` | Flattens Camera2 characteristics into exact advertised mode sets plus maximum AE/AF region counts, alongside manual-sensor, RAW, HDR, focus, and stream capabilities. |
| `ControlAvailability.kt` | Projects those exact mode arrays, manual/range facts, and AE/AF region maxima into enum choices and admission flags shared by settings, top-bar/Fn cycles, and quick rulers. Sparse routes use a neutral singleton; before caps arrive, the current singleton remains visible but disabled. |
| `ManualControls.kt` | Immutable snapshot of all pro capture parameters (focus, ISO, shutter, white balance, metering, processing). `normalizeControlsForRoute` applies one exact capability/zoom boundary to live and recalled packets before accepted Engine/UI/request publication. Also owns the sensor fast-path admission predicate (`sensorFastPathAdmitted`, wrapping `sensorOnlyControlsDelta` — a live tap-AF/AF-lock override rides the fast path and is re-applied, not refused) and the shared sensor-key request derivation (`applySensorValueControls`). |
| `RotationMath.kt` | Pure, unit-tested functions for preview/capture/EXIF rotation math and the video muxer orientation hint (extracted from CameraEngine). |
| `RendererConfig.kt` | One immutable snapshot of every renderer-only assist (peaking, zebra, false color, punch-in, tele finder, …) with a store that replays the complete snapshot into each fresh GL generation. |
| `OpticsConstraints.kt` | Pure admission/rollback rules for optics transactions (mode/lens/TC transitions, structural-reconfigure decisions), unit-tested off-device. |
| `ZoomSubmitPlan.kt` | Pure HAL zoom-submit decision (throttle window + mid-gesture wide-aim clamp), extracted from `CameraEngine.setZoomRatio` and unit-tested. |
| `RecordingAdmissionLatch.kt` | Monitor-owning REC stop-during-start latch (`tryBeginAdmission`/`requestStop`/`completeAdmission`), extracted from CameraEngine and race-tested. |
| `AutoExposure.kt` | Pure, unit-tested app-side AE math: SHUTTER/ISO-priority drive functions and the photo-P program line (`driveProgram`), metered off the GL luma histogram. |
| `OcsProbe.kt` | Debug-source-set-only OPPO CameraUnit/OCS availability probe (release builds compile a no-op stub and do not link the OEM SDK). |
| `VendorTagInspector.kt` | Debug-only Camera2 capability logger for device-specific request/session keys. |
| **gl/** | |
| `GlPipeline.kt` | Owns the GL render thread and checked preview/encoder EGLSurface lifetimes. Outgoing outputs are unbound before destruction; preview and encoder readiness are each published only after the first real frame swaps successfully. Before texture acquisition it binds the live preview owner, otherwise the active encoder owner, and contains acquisition/output failures in identity-owned exactly-once paths. Each GL generation owns and retires its analysis executor, busy gate, FBO/buffer snapshot, and callback authority. `CameraEngine` replays its complete `RendererConfigStore` after every `gl.start`. EGL-init failure is CONTAINED: `start()` leaves `egl` null (no GL-thread crash, no eglTerminate of the process-shared default display) and the next preview bind routes the failure through the one preview-health path. `start()` is idempotent under double-call, and a GL thread wedged past `stop()`'s bounded join is deliberately ABANDONED (ownership nulled, thread leaked — the VideoRecorder drain-wedge pattern) so a later `start()` can spawn a fresh generation instead of a permanently dead viewfinder. |
| `FlipRenderer.kt` | Low-level OpenGL ES fullscreen quad renderer with texture-coordinate rotation (inverse of image rotation) to flip the 180° afocal image. Applies the SDR-to-HLG mapping or O-Log2 encoding in the fragment shader and handles focus peaking/zebra. |
| `EglCore.kt` | Checked EGL/GLES setup, binding, presentation, buffer swap, unbind, surface destruction, and display teardown. Supports a 10-bit config, while v1 deliberately starts the stable 8-bit config. |
| `Shaders.kt` / `SdrToHlgMapping.kt` | Shader source plus Android-free reference constants for the BT.2408-9 display-referred SDR-to-HLG sequence; also owns O-Log2, peaking/zebra, and punch-in shader paths. |
| **stab/** | |
| `GyroEis.kt` | Sensor helper for gravity-derived device orientation and the horizon overlay. It retains residual-shake math, but the shipping GL path disables app-side EIS in favor of HAL OIS+EIS. |
| **capture/** | |
| `StillSnapshot.kt` | YUV_420_888→NV21 repack (row-wise arraycopy fast paths + generic fallback) and lazy JPEG encode for logical-camera stills, which cannot use the HAL JPEG path. |
| `HeifCapture.kt` | Encodes HEIF from a Bitmap after crop and `captureRotationDegrees()` pixel rotation. Writes via the ioExecutor off the camera thread. |
| `DngCapture.kt` | Writes DNG (RAW sensor frame) using DngCreator. Sets EXIF orientation tag (cannot pixel-rotate Bayer CFA). Synchronous in the photo callback while the raw Image is live. |
| **video/** | |
| `AudioReadPolicy.kt` | Pure classification of `AudioRecord.read` return codes (PCM / transient retry / normal stop / terminal failure) shared by the recorder loop and the standby meter, plus the meter's bounded-recreate budget rule. |
| `VideoRecorder.kt` | MediaCodec HEVC/AVC encoder + AAC audio encoder + MediaMuxer. Exactly-once owner of the codec input Surface: clean release follows verified EGL detach and partial setup also releases; a still-live drain takes the documented no-release abandon path. Video input comes from GL already flipped; audio runs separately with software PCM gain. A mid-REC negative `AudioRecord.read` degrades to a PUBLISHED video-only file (`degradeAudioToVideoOnly`); only VIDEO faults delete; after stop a negative read is normal EOS. The encoder buffer takes `RotationMath.encoderSurfaceSize` — swapped to the DISPLAYED portrait aspect for the 90° sensor so `coverScale` records exactly the viewfinder field (the stream-shaped landscape buffer recorded a ~3.16× center band; device-measured and fixed cycle 4). |
| `AudioInputInspector.kt` | Resolves the preferred recording input (built-in / wired / USB / BT) against connected AudioDeviceInfo entries; provides the route labels shown in the UI. |
| `ColorProfiles.kt` | Builds MediaFormat specs for HEVC Main10 (Rec.2020 + HLG/Log) and AVC 8-bit SDR. Tags dynamic range, color space, transfer function. |
| `EncoderCaps.kt` | Scans MediaCodecList and exposes the hardware AVC/HEVC encoders that are stable with MediaMuxer. |
| **storage/** | |
| `CaptureFamily.kt` | Versioned, timestamped capture-family identity embedded in every new output filename. HEIF/JPEG/DNG siblings reuse one exact key; video owns a one-file family. Legacy names are deliberately not inferred by timestamp proximity. |
| `LatestCaptureReducer.kt` | Android-free reducer for owned Images/Video rows. Selects the newest capture first, then a displayable sibling inside only that capture, and distinguishes proven capture-family deletion from legacy file-only deletion. |
| `MediaStoreWriter.kt` | Scoped-storage wrapper: creates pending DCIM/X9Tele entries (IS_PENDING), publishes on success, deletes on failure, and stamps canonical outputs with DATE_TAKEN. Relaunch recovery independently retains successful bounded Images/Video query rows, then performs an exact-family follow-up. Opened files are backed by ParcelFileDescriptor. |
| `SettingsStore.kt` | SharedPreferences persistence of ManualControls + ExtraSettings across launches, gated by a "Remember Settings" toggle (default ON); enums stored by name, defensive load. Lens and TELE restoration have separate default-on preserve toggles. |
| **focus/** | |
| `FocusMapping.kt` | Maps the UI slider (0..1) bidirectionally to LENS_FOCUS_DISTANCE with `diopters = minFocusDiopters * slider^3`. There is no additive offset, preserving exact infinity at slider 0 while concentrating travel near it. |
| **ui/** | |
| `ZoomMath.kt` | Pure zoom-scale math shared by engine and UI: effective bounds, TELE magnetic-snap normalization, mode/restore scale remaps, and the hardware-glide ease-step function. |
| `CameraScreen.kt` | Compose root layout: preview TextureView, shutter button, mode toggle, gallery thumbnail, fixed settings panel, and capture overlays. Stateless, reads CameraUiState. |
| `CameraViewModel.kt` | StateFlow<CameraUiState> owner. Turns CameraActions into CameraEngine calls, publishes capability-normalized controls, applies gesture changes with a trailing throttle, and coordinates capture-id review ownership. |
| `CaptureOutputTracker.kt` | Bounded, synchronized ownership map for monotonic capture ids and every processed/RAW sibling. Selects the truthful review owner, upgrades RAW placeholders, tombstones whole captures before deletion, and seeds a reconstructed prior-process family below every live capture id. One open-review family can be pinned outside ordinary bounded history until close/delete. |
| `CameraActions.kt` | Callback interface for stateless UI commands such as focus, exposure, tap AF, lens, recording, persistence, and review actions. |
| **ui/controls/** | |
| `ManualDials.kt` | Horizontal scrolling dials for quick access to focus, shutter, ISO, white balance, EV, and zoom — the "Fn" layer. Entry is admitted by `ControlAvailability`, and a ruler closes if a route change removes its required exact mode/range. The WB chip can open preset choices without a Kelvin ruler; MANUAL WB still requires that ruler. |
| `ProSheet.kt` | Fixed Sony-style settings panel with a 9-tab left rail: My, Shoot, Exposure, Focus, Lens, Video, Image, Assist, and Setup. The rail is one selectable group whose items expose selected state and `Role.Tab`. Capability-dependent selectors contain advertised choices when present; an empty set falls back to a disabled neutral singleton, and otherwise-invalid entry points are disabled. |
| `ProControls.kt` | Reusable Compose controls including rulers, segmented choices, toggles, sliders, and value rows. All are two-way bound to CameraUiState. |
| **ui/overlays/** | |
| `Overlays.kt` | Compose overlays: reticle (tap-to-focus), histogram/waveform, grid, spirit level, peaking, zebra, punch-in zoom indicator, AE/AWB/AF lock tags. Stateless off CameraUiState. |
| `MediaReview.kt` | In-app review of the last capture: zoomable processed photos, rotating video playback, and a truthful non-decoding RAW/DNG metadata tile. Delete copy promises all saved formats only for a proven canonical family; legacy rows explicitly delete one file. Visible semantic Play/Pause and zoom-cycle controls remain available where applicable. |
| `ControlCycles.kt` | Shared tap-cycle and auto-exposure readout logic used by ManualDials, ProSheet, and CameraScreen. Capability-dependent cycles advance only through `ControlAvailability` choices (single copy — no drift). |
| `ZoomGlideState.kt` | The Android-free half of the zoom-interaction lifecycle: coalesced `pendingRatio`, hardware-glide `easeTarget`, `interacting`, `flushScheduled`, plus `invalidateForRemap()` and the zoom-OUT `isLeadingEdgeToWide` decision. Every optics-scale remap door invalidates through the ViewModel's single `invalidateZoomGlide()` wrapper (host-tested). |
| **ui/theme/** | |
| `Theme.kt` | Material3 dark theme tuned for a Sony-style pro camera surface, typography, color palette, text field/button shapes. |
| **(app root — `com.hletrd.findx9tele`)** | |
| `MainActivity.kt` | Entry point. Requests CAMERA/RECORD_AUDIO permissions at runtime (ColorOS blocks pm grant). CAMERA request history distinguishes fresh/cancelled prompts from fixed denial before offering Settings. Hosts the Compose root and ViewModel. Lifecycle: `onStart` calls the ViewModel's `onStart`, which resumes the engine; `onStop` calls the ViewModel's `onStop`, which pauses it. |
| `CameraPermissionPolicy.kt` | Pure CAMERA-permission decision table: fresh install / cancelled prompt / genuine permanent denial, driven only by completed request history plus rationale state. |
| `HardwareInputPolicy.kt` | Pure mapping of the camera-control button's key events (full press, capacitive zoom slides in both OEM code families, half-press if ever delivered) to configurable `HardwareKeyAction`s. |
| `TeleCameraApp.kt` | Application class, kept minimal. No wiring needed; all setup in MainActivity/ViewModel. |

---

## Data Flow

**Unidirectional pipeline:**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           COMPOSE UI (Stateless)                         │
│                    Reads CameraUiState, calls CameraActions              │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ UI tap/slide → action (e.g., onIso, onTapFocus)
                           ▼
                ┌──────────────────────┐
                │   CameraViewModel    │
                │   (StateFlow Owner)  │
                │ Implements           │
                │ CameraActions        │
                └──────────┬───────────┘
                           │ setControls(), setTransfer(), capturePhoto(), etc.
                           ▼
        ┌──────────────────────────────────────────┐
        │        CameraEngine (Facade)             │
        │  Orchestrates components' background     │
        │  threads; volatile visibility seams +   │
        │  atomic ownership gates                 │
        └──┬───────┬────────────┬──────────┬───────┘
           │       │            │          │
    ┌──────▼─┐ ┌──▼───────┐ ┌─▼────────┐ │
    │ Camera │ │GlPipeline│ │ Capture  │ │
    │Control │ │(GL th.)  │ │Encoders  │ │
    │(camera │ │          │ │          │ │
    │thread) │ │          │ │          │ │
    └────────┘ └──────────┘ └──────────┘ │
                                         ▼
                                  ┌─────────────┐
                                  │ VideoRecord │
                                  │ (audio/vid  │
                                  │  threads)   │
                                  └─────────────┘
                                         │
                                         ▼
                              ┌────────────────────┐
                              │ MediaStore Writer  │
                              │ (scoped storage)   │
                              └────────────────────┘
```

**Frame journey (preview):**
1. Camera2 → SurfaceTexture (backed by EGL texture) → GlPipeline.onFrameAvailable callback (GL thread).
2. FlipRenderer samples the camera texture with rotated texture coordinates (inverse of image rotation, so the visual result is 180°-flipped).
3. OETF and enabled monitoring overlays are applied in the fragment shader (passthrough for SDR).
4. Result rendered to:
   - **Preview Surface** (TextureView on main thread) → live on-screen.
   - **Encoder Surface** (MediaCodec input, if recording) → encoded into MP4.

**Still-photo journey (processed HEIF/JPEG):**
1. Camera2 → processed ImageReader: logical-camera photo sessions use `YUV_420_888`; standalone
   sessions use the HAL JPEG stream.
2. The camera callback copies the short-lived `Image` into owned JPEG bytes or an owned YUV snapshot.
3. `ioExecutor` produces a Bitmap, center-crops 16:9 when selected, and pixel-rotates for sensor,
   device orientation, and the afocal 180° correction. It then encodes each requested HEIF/JPEG output;
   JPEG is re-encoded at the shot's frozen quality setting and is never a byte passthrough.
4. MediaStore creates each output as pending, publishes on success, and deletes it on failure.

**Still-photo journey (DNG/RAW):**
1. An eligible TELE/standalone Camera2 session → RAW_SENSOR ImageReader → photoCallback on camera thread.
2. Synchronously (Image still live): DngCreator.writeDng → map `captureRotationDegrees()` to the corresponding EXIF orientation tag → MediaStore write.
3. Cannot pixel-rotate Bayer CFA; EXIF tag is auto-applied by RAW renderers.

---

## Threading Model

**Threads / Executors:**

| Thread / Executor | Owner | Runs |
|---|---|---|
| **Main (UI)** | Android framework | Compose recomposition, ViewModel StateFlow updates, lifecycle callbacks (onStart/onStop). |
| **mainHandler work** (main-thread Handler) | CameraViewModel | Lifecycle-owned periodic record/level/orientation/info updates, bounded zoom easing, and transient countdown/reticle work. `onStart` owns recurring registration and `onStop` removes it. |
| **gl-pipeline** HandlerThread | GlPipeline | EGL operations, texture sampling, rendering, GL shader execution. |
| **camera** HandlerThread | CameraController | Camera2 lifecycle and capture callbacks. Copies JPEG/YUV data before cache-only EXIF composition while the Image is live, and writes DNG synchronously while the RAW Image is valid. |
| **setupExecutor** (single-thread) | CameraEngine | Post-GL-input Camera2 route/capability preflight, lightweight physical-lens EXIF prefetch, serialized generation-owned mode/lens/session reconfiguration, and bounded recovery work. Debug diagnostics are queued behind the initial route/open work. |
| **ioExecutor** (single-thread) | CameraEngine | Deferred processed-still decoding, crop/rotation, HEIF/JPEG encoding, and publication. |
| **recording-finalization executor** (single-thread) | CameraEngine | Dedicated, rejection-safe recorder stop/muxer finalization so still encoding cannot delay clip completion. Release waits a bounded interval for this lane before GL/executor teardown. |
| **timelapseScheduler** (scheduled) | CameraEngine | Interval-driven timelapse capture trigger every N seconds. |
| **analysisExecutor** (one single-thread executor per GL generation) | GlPipeline | Histogram/waveform computation from that generation's isolated FBO/readback snapshot. Retirement invalidates callback authority without waiting indefinitely for old math. |
| **audio-capture** (implicit thread) | VideoRecorder | AudioRecord polling loop and PCM-to-AAC encoding. |
| **video-drain** (implicit thread) | VideoRecorder | MediaCodec output buffer draining and MediaMuxer writes. |
| **StandbyAudioMeter** (thread) | CameraEngine | Levels-only AudioRecord tap while video is armed but not rolling. A synchronized ownership gate reserves one immutable owner/release latch before thread start; REC opens the mic only after that exact owner releases. Reads are classified via `AudioReadPolicy`: zero retries, a negative (terminal framework error) ends the generation with an exactly-once release, then a bounded backed-off recreation (≤3 failed generations, reset by any successful PCM read) re-arms only while the standby intent still wants a meter. |

**Cross-thread visibility and ownership seams:**

Engine state published across worker boundaries:
- CameraEngine publishes mutable runtime configuration such as selection, capabilities, controls,
  video format, transfer, lens/TELE mode, stabilization mode, audio, and aspect ratio through
  `@Volatile` fields. Multi-field ownership transitions use the synchronized gates described below;
  a set of independently volatile fields is not treated as an atomic transaction. Treat the
  declarations in `CameraEngine.kt` as the authoritative visibility list.
- `GlPipeline.inputSurface` — published once after EGL texture creation, then read (safely) on setup thread.

Accessed from camera + UI threads:
- `CameraEngine.previewSurface` — set on UI thread, read on camera thread (idempotent: just used to check if it's been set).

Accessed from GL + audio/video threads:
- `VideoRecorder.running`, `inputSurface` — coordination flags and surface for encoder setup.

**Race-safety patterns:**
- **setupExecutor serialization**: desired optics intents publish synchronously through the engine
  ownership monitor. The executor performs blocking selection/capability preflight and applies the
  current generation's mode/lens/recovery work in one ordered lane.
- **Optics transaction commit**: `OpticsCommitGate` publishes desired generation plus Not-Ready
  together. Ready can return only through the same monitor after the expected generation, controller
  identity, pause state, and same-camera session generation still match; rollback clearing, the Ready
  bit, the Ready controller, exact accepted session generation, and actual processed/RAW reader mask
  commit as one state. A rejected same-route terminal commit queues reconfiguration only when its
  optics intent still owns convergence; superseded work remains a no-op. External callbacks run after
  unlocking.
- **Ready publication ordering**: every Ready/Not-Ready event carries a monotonic publication sequence.
  The ViewModel compares it again inside the StateFlow reducer, closing the check-to-write race so an
  older Ready event cannot overwrite newer Not-Ready state.
- **Cold startup convergence**: GL input ownership is established before blocking Camera2 preflight.
  The input callback snapshots the latest desired route, stale generations cannot publish, and a bounded
  `ColdStartRetryGate` lets a transient selection/capability failure recover without recreating the
  preview surface.
- **Terminal GL acquisition**: `TerminalAcquisitionGate` linearizes cold `gl.start` and its input
  callback with engine release. Release closes the gate before its final `gl.stop`; it either waits
  for an in-flight acquisition and owns that generation's stop, or prevents the acquisition entirely.
- **Output-surface ownership and preview health**: every preview bind/detach synchronously increments
  a generation before it queues GL work. A stale native window is rejected before EGL mutation.
  Create/bind/init and runtime draw/swap failures complete the exact preview signal once; the Engine
  accepts only the current Surface/generation, publishes Not-Ready, and retries that owner at most
  three times before reporting terminal preview failure. Bind success leaves attachment pending;
  Ready returns and its recovery budget resets only after that owner completes its first successful
  producer-fed swap; cached-frame zoom redraws cannot publish Ready. Each real camera texture
  acquisition first selects and binds the live preview, otherwise the active encoder, with
  make-current/update/transform failure contained by that identity-owned health path. Preview and
  encoder rendering remain sibling branches, so a broken preview detaches without
  starving an otherwise healthy active recorder. Every outgoing EGLSurface first binds a surviving
  output or makes nothing current, then destroys the surface. Codec teardown requires verified
  current-ownership release plus
  either destroyed outputs or checked terminal EGL display teardown; an individual destroy failure
  cannot authorize successful completion by itself. Encoder create/bind/restore remains pending until
  the first real camera frame presents and swaps successfully. Before that point failure completes
  attachment; afterward failure belongs to the active recorder. Normal detach cancels a pending attach
  without manufacturing a runtime fault.
- **Analysis-generation isolation**: every `gl.start` creates an immutable analysis generation owning
  its executor, single-flight gate, FBO/texture, direct buffer, byte snapshot, and callback authority.
  `stop` retires that owner synchronously before executor shutdown and clears its snapshots during
  release. Retired work can neither publish into the replacement generation nor clear its busy gate.
- **Controller-health ownership**: Camera2 error/disconnect callbacks are authorized by installed
  controller identity for that controller's complete lifetime, not by the optics generation captured
  when it opened. The same controller remains authoritative through fast commits; callbacks from a
  replaced controller are inert. An owned failure atomically invalidates Ready/outputs, advances the
  session generation, claims any recorder, reports termination, and enters bounded recovery.
- **Lifecycle guards**: `MainActivity.onStart` → `CameraViewModel.onStart` → `CameraEngine.resume`, and
  `MainActivity.onStop` → `CameraViewModel.onStop` → `CameraEngine.pause`. The paused flag prevents
  reconfigure/open work during app backgrounding; every queued boundary rechecks ownership, and
  `CameraController.closed` gates late-arriving open callbacks.
- **Session reopen ownership**: every session-key reopen snapshots one complete
  `OpticsReconfiguration` before invalidating Ready. The setup lane rechecks its generation and
  expected controller, then uses normal complete reconfiguration; no transaction-less close/open path
  can install stale selection or capabilities under a newer intent.
- **Photo callback**: Image objects are valid only during `CameraController.PhotoCallback.onPhoto()`.
  Processed JPEG/YUV data is copied into owned memory before ancillary EXIF composition, then encoded
  later on `ioExecutor`; DNG is written synchronously while its RAW Image is still live. Lightweight
  focal-length, aperture, and equivalent-focal-length metadata for selected physical members is
  prefetched on `setupExecutor`, so callback resolution is cache-only and falls back to selected-route
  metadata without a CameraService lookup.
- **Recording admission and failure**: VideoRecorder owns its video/audio threads and muxer lock; GL
  writes frames to its exactly-once-owned codec input Surface. REC snapshots the accepted
  controller/session, rechecks it after mic handoff, and publishes recorder ownership atomically
  against camera failure. Admission runs on the serial **recorder executor**, never main (the
  bounded mic-release wait, MediaStore pending insert, and codec/muxer/AudioRecord construction
  cost ~100-700 ms); only the in-flight gate is synchronous, the UI publishes optimistic starting
  state that the result callback resets on refusal, and a stop arriving mid-admission is latched
  and executed on the same executor the moment the recorder publishes (never raced against an
  unpublished owner). Encoder attach is queued before publication; UI remains in a stoppable
  `isRecordingStarting` state until the first
  successful real encoder swap, so tally/timer never imply a phantom recording. Clean finalization
  releases the Surface only after checked EGL unbind/destroy, before codec release/ownership clear;
  partial setup also releases exactly once. A timed-out live drain deliberately abandons Surface,
  codec, muxer, and fd native resources rather than racing release against native code. An active
  Camera2 failure claims the matching recorder, orders GL detach before finalization, reports
  termination, then permits bounded camera recovery. A negative `AudioRecord.read` while running
  records the first failure and stops empty AAC submission; a negative result after stop is normal EOS.
- **Capability-safe controls and recall**: `normalizeControlsForRoute` applies exact mode arrays,
  manual capabilities, metering-region maxima, and the accepted route's zoom range as one packet.
  A same-route settings/MR recall normalizes against the installed caps before its terminal fast
  commit, uses that packet for Engine/controller/zoom state, and queues the generation-owned caps
  reconciliation before Ready. A structural recall waits for the target route's caps and normalizes
  there; outgoing caps never clamp a different target route. Superseded callbacks are rejected by
  optics generation. Request builders still set only advertised values and omit AE/AF regions when
  the corresponding maximum is zero.
- **Capability-driven control admission**: one `ControlAvailability` projection derives visible enum
  choices and enablement from the same exact AE/AF/AWB, antibanding, edge, noise-reduction, effect,
  manual/range, flash, and region facts used by normalization. ProSheet filters its selectors;
  top-bar/Fn cycles advance only through the projection; manual focus/shutter/ISO/WB/EV/zoom rulers
  require their exact modes and ranges. WB preset choices can still open their sheet without a Kelvin
  ruler. Custom WB requires advertised unlocked AUTO and accepts only a later converged result from
  the exact tagged request owned by one accepted Ready session. The same owner is atomically rechecked
  after crossing to main; timeout, supersession, close, preview loss, and route replacement cannot
  apply cached gains. If route caps invalidate an open ruler, Compose closes it and retains the
  normalized applied value rather than leaving an inert editor open.
- **Microphone admission**: `StandbyMeterOwnership` keeps reservation, intent, owner identity, and
  release-latch handoff on one monitor. Late meter threads recheck ownership before opening
  AudioRecord; REC fails a bounded release wait instead of creating a second owner; finalizer retries
  recheck current intent and cannot override a newer disable or background transition.

---

## 180° Flip + Rotation Pipeline

**Why two different rotation approaches:**

The afocal teleconverter's 180° flip must be applied to BOTH the preview and captures. However:
- **Preview** uses texture-coordinate rotation (inverse of image rotation) because GL draws once and the sampled pixels appear rotated.
- **Captures** use pixel-level rotation (direct) because encoded bytes must be rotated in the image buffer itself.

Additionally, **device orientation** from gravity (GyroEis.currentDeviceOrientation) is added to still captures so a photo framed in landscape saves landscape-correct, even though the UI is portrait-locked.

**Preview (GL):**

```kotlin
// RotationMath.previewRotationDegrees(teleconverterMode)
val rotation = if (teleconverterMode) 180 else 0   // afocal 180° only; sensor already applied by SurfaceTexture
// Then pass to FlipRenderer.setRotationDegrees(rotation)
// Example: sensorOrientation=90, teleconverter ON -> preview rotation = 180° (sensor term NOT added)
```

**Key insight**: The camera SurfaceTexture transform (applied via `stMatrix` in `FlipRenderer.draw`) 
**already rotates the sampled image by the sensor orientation**. The GL renderer adds **only the afocal 180°** 
in tele mode (and 0° otherwise). The sensor orientation is still passed to the renderer, but **only to pick the 
preview aspect ratio** (a ~90° rotation swaps displayed width/height). On-device testing confirmed: preview is 
upright when using 180° afocal correction alone, with no sensor-orientation term added. `FlipRenderer` still 
receives sensorOrientation for aspect calculation, not for image rotation.

**Captures (pixel rotation + device orientation):**

```kotlin
// RotationMath.captureRotationDegrees(sensorOrientation, teleconverterMode, deviceOrientation)
val base = sensorOrientation + (if (teleconverterMode) 180 else 0)
val total = (base + deviceOrientation) % 360
// Direct pixel rotation (Matrix.postRotate), no negation
// Example: phone held upright, teleconverter ON
//   sensorOrientation=90, teleconverter ON, device orientation=0
//   base = 90 + 180 = 270°
//   total = 270° (saves landscape-oriented)
```

Device orientation (from gravity via `GyroEis.currentDeviceOrientation()`) is added so a photo framed
while tilting the phone into landscape saves with the correct pixel orientation, matching the visual intent
in the portrait-locked preview (which does not rotate). The rotation functions are unit-tested; a lit,
deliberately held portrait/landscape saved-file check remains useful field verification.

**HEIF (pixel-rotated):**
1. JPEG → decode to Bitmap.
2. Bitmap.createBitmap(..., Matrix.postRotate(captureRotationDegrees), ...) → new rotated Bitmap.
3. Encode HEIF.

**DNG (EXIF orientation tag):**
1. RAW_SENSOR Image → DngCreator.
2. DngCreator.setOrientation(exifOrientationFor(captureRotationDegrees)) — tag set, Bayer pixels untouched.
3. RAW renderers auto-apply the orientation tag on playback.

**Mapping: degrees → EXIF tag**

```kotlin
// RotationMath.exifOrientationFor(degrees): degrees (0/90/180/270)
// 0   → ORIENTATION_NORMAL
// 90  → ORIENTATION_ROTATE_90
// 180 → ORIENTATION_ROTATE_180
// 270 → ORIENTATION_ROTATE_270
```

All rotation math (preview, capture, EXIF orientation mapping) is pure and unit-tested in `camera/RotationMath.kt`.

---

## Camera Selection & HAL Workarounds

**Two-stage exposure safety (cycle-3 P1.1, device-bisected).** The advertised exposure upper
(≥20 s) is a lie on this HAL: a STILL request above 4 s errors the whole camera device
(`CAMERA_ERROR(3)`) and silently loses the shot. `HAL_SAFE_MAX_STILL_EXPOSURE_NS` (4 s) is applied
at the single caps seam (`clampStillExposureRange`, host-tested), so the shutter ruler ladder,
request clamps, AEB brackets, numeric normalization (`normalizedFor`), and the exposure-aware
still watchdog all inherit one truth. Independently, the REPEATING (preview) request is capped at
`PREVIEW_SAFE_MAX_EXPOSURE_NS` (500 ms) via the brightness-neutral `previewExposureTrade`
(exposure→ISO), because a long repeating exposure stalls the stream and starves session
transitions; S/ISO/M previews above 500 ms are brightness-accurate but deliberately NOT
noise/motion-blur-WYSIWYG (the alternative is a sub-1 fps viewfinder). The 4 s ceiling was
bisected on the standalone TELE camera only and is applied to EVERY route as a conservative
assumption — a logical-camera bisect is a recorded residual (docs/BACKLOG.md).

**Telephoto detection (CameraSelector2.select):**
- Enumerates all cameras and picks the one with focal length **closest to 70 mm** (not the longest; the 230 mm 10× is ruled out).
- Returns both logical ID (for opening) and physical ID (if it's a sub-camera of a logical multicamera).
- **Key insight**: Prefer **physicalId == null** (standalone camera) over routing to a physical sub-camera via setPhysicalCameraId(). Routing crashes the QTI HAL.

**Shipping session fallback plan (`CameraController.configureSession`):**

The Camera2 input is deliberately SDR/8-bit (`tenBitHlg=false`). HLG/O-Log2 is applied later in GL and
tagged in the encoder container; the historical HLG10 input-stream branch remains dormant and must not
be described as the shipping source pipeline.

- A logical photo session uses preview + `YUV_420_888` processed stills. It never requests RAW because
  RAW and the logical-camera still configuration destabilize this HAL.
- A standalone session starts with preview + HAL JPEG and adds RAW only when the camera advertises it
  and the current TELE capture is eligible. Fallback drops RAW before dropping the processed-still stream,
  and preview-only is the final stream plan.
- TELE first tries the stock-camera operation mode `0x80b4` with full/degraded capture streams, then
  tries `SESSION_REGULAR` with full/degraded capture streams. Vendor and regular preview-only plans
  are the two terminal attempts. Non-TELE sessions use `SESSION_REGULAR` directly.
- The accepted still-output truth is the reader presence of the plan whose repeating preview request
  succeeded, not the plan that was attempted. Generation-owned Ready publication carries that exact
  processed/RAW mask with the accepted controller and session identity.
- A preview-only Ready session disables PHOTO and in-REC snapshots but does not disable video REC/Stop.
  Format normalization retains requested accepted outputs, otherwise selects an available processed
  or RAW fallback, and yields an empty still set when neither reader exists.
- Only after both the stream and operation-mode plans are exhausted is failure surfaced to the engine.

This ordering preserves a processed capture whenever possible, keeps unsupported DNG out of logical
sessions, and avoids implying that a Main10 container originated as a 10-bit Camera2 preview stream.

**Auto-exposure frame-rate policy:**

Photo AUTO uses `CameraCaps.autoFpsRange()`, whose low floor lets AE extend exposure in dim scenes.
Video AUTO sets `pinAutoFps=true` so a selected 29.97 cadence cannot fall to 25 fps in low light.
App-side and manual exposure also pin the selected frame rate.

**Tap-to-focus (region AF):**

Continuous AF mode (`AF_MODE_CONTINUOUS_PICTURE`) with a bare trigger holds the current (often incorrect) 
focus distance. Instead, tapping a region sets a metering/AF region and forces a one-shot `AF_MODE_AUTO` 
scan that **locks** the focus on the tapped point (`touchAfActive` flag). The lock — together with the 
tap-owned loupe center — is released by a replacing tap, a focus-mode change, the explicit reset action, 
or any optics-remap door (mode/lens/TC/camera-override via `CameraViewModel.clearTapFocus()` → 
`CameraEngine.clearTapPoint()`); the 2 s reticle auto-hide is visual-only and does NOT release the hold. 
AF state reaches FOCUSED on device.

---

## Zoom & the Hybrid Camera Homes (2026-07-14)

Which camera is open depends on MODE, not just lens (`CameraEngine.resolveNonTeleId`):

| State | Camera | Zoom semantics |
|---|---|---|
| Photo, TC off | **Logical camera 0** (physIds 3/2/4/5) | Unified main-relative 0.6–20×; the HAL crosses physical lenses internally (seamless pinch, no reopen). Lens picks = zoom presets; chip highlight follows `LensChoice.forZoom`. |
| Video, TC off | **Standalone lens** matching the band | Lens-local 1–10× digital; lens changes reopen. The logical camera's EIS (Standard AND Active) leaks its uncorrected warp margin (~6% of width) into the stream — preview AND recorded file — so video must not live there. |
| TC on (any mode) | **Standalone 3× (camera 4)** | Lens-local 1–10×; afocal 180° flip; RAW/DNG is offered only when that standalone session advertises RAW. |

`setVideoMode` remaps the zoom value between the unified and lens-local scales so framing carries
across a mode flip (mirrored into UI state by `onModeChange`).

**Zoom application pipeline** (why it's smooth): pinch/dial events are COALESCED in the ViewModel
(leading apply + 16 ms trailing flush of the newest value, ~60 Hz — per-event application recomposed the
whole tree at input rate). Every compounding input (pinch factor, hardware-key step, ease ticker)
bases itself on `currentZoomBase()` — the coalesced PENDING value, not UI state, which lags a
flush window; compounding against the stale state made zoom crawl-then-jump. The flushed value
takes the controller **fast path** (`CameraController.setZoomRatio`): the cached repeating-request
builder gets only its zoom keys mutated and resubmitted — no full request re-derivation.
Scale-remap invalidation covers BOTH pending inputs: every remap door calls
`invalidateZoomGlide()` → `ZoomGlideState.invalidateForRemap()`, clearing
`ZoomGlideState.pendingRatio` AND nulling `ZoomGlideState.easeTarget` (a hardware-slider glide
target is an absolute number in the OLD scale; surviving a remap it eased toward an un-commanded
framing). Each zoom
gesture EDGE costs one repeating-request swap: `setZoomInteraction` folds the current/final exact
ratio into the fps-boost flip's own rebuild (`setSmoothPreviewBoost(active, finalZoom)`), instead
of the old rebuild-then-correct pair that transiently re-submitted the stale mid-gesture wide-aimed
ratio. The engine's zoom read-modify-write on `controls` shares the packet writers' monitor (as
does `setControls` — every wholesale `controls` writer holds the engine monitor), and
`onZoomResult → gl.setHalZoom` forwarding is change-gated with a per-rebuild reset. The
throttle/wide-aim decision itself is the pure `resolveHalZoomSubmit` (`camera/ZoomSubmitPlan.kt`,
unit-tested), and two additions keep captures WYSIWYG: the controller stores the EXACT requested
ratio for still requests (`setZoomRatio(halRatio, requestRatio)` — a still must never inherit the
mid-gesture ~1.2×-wide aim), and a QUIET-WINDOW landing (`landExactZoom`, ~250 ms after the last
flush) lands the exact ratio on the HAL well before the 700 ms fps-boost tail ends, so a recorded
clip stops carrying the wide framing after finger-up. Scale-remap invalidation of
`ZoomGlideState.pendingRatio`/`.easeTarget` (via `invalidateZoomGlide()`) covers ALL the remap doors: `onModeChange`,
`onToggleTeleconverter`, `onLens`, `onStop`, **`onOpticsRollback`, `applyLoaded` (settings/MR
recall), and the debug `onCameraOverride`** — the last three were the doors 6affe20 originally
missed. The glide's per-tick math is the pure `zoomEaseStep` (`ui/ZoomMath.kt`, unit-tested).

This zoom coalescer is separate from the general `ManualControls` packet throttle, which applies the
newest full-control snapshot every 40 ms (25 Hz) during continuous dial input. The controller pairs
it with a **sensor fast path** mirroring the zoom one: when an `updateControls` delta touches ONLY
the high-churn sensor scalars (manual focus distance; ISO + exposure time — the app-side AE pair),
admission is the pure `sensorFastPathAdmitted` (wrapping `sensorOnlyControlsDelta`; a live
tap-AF/AF-lock override no longer refuses the fast path — the controller re-applies the override
keys onto the cached builder through the SAME `applyAfOverrides` the full rebuild uses) and the
cached repeating builder gets only its sensor keys re-derived via the same
`applySensorValueControls` the full rebuild uses, paced ≥200 ms with a trailing exact landing. Anything else still takes the full `startPreview` rebuild. Ruler
drags are additionally frame-gated at the source (`RulerSlider` publishes ≤60 Hz with an exact
landing on drag end; the ruler's own canvas still follows the finger per event).

### Tele Finder PIP (opt-in, photo-only)

An Assist toggle (default OFF, persisted) draws a bottom-left corner viewport re-drawing the FULL
current camera frame while the main view is magnified. **Single-stream honesty**: the HAL's
`CONTROL_ZOOM_RATIO` crop is baked into the one camera texture, so the PIP can only be wider than
the main view while GL zoom compensation (mid-gesture) or punch-in magnifies past the delivered
field — a true unzoomed/wide finder is a BACKLOG design item (second stream or HAL-zoom-cap split).
Gating is ONE shared, unit-tested predicate (`teleFinderResolved`/`teleFinderVisible` in
`CameraState.kt`): toggle && TELE && **photo mode** && 4:3, plus an ACTIVE punch-in loupe — the
honest gate axis since cycle 4 (the old raw `FINDER_MIN_ZOOM` floor showed a corner box that
duplicated the main view ~1:1 at steady zoom; the single stream means the PIP is only genuinely
wider while the loupe magnifies past the delivered frame). Photo-only because it is a
still-composition aid and 4:3 is the STILL aspect; 16:9's AspectMask would dim/misframe the
corner box. GL applies the same axis at draw via its own punch-in state. The engine resolves the flag in one place (`pushTeleFinder` —
re-pushed synchronously on toggle/aspect/lens-TC/mode/session-config AND on `rollbackOptics` via
`applyStabilization`, with self-contained pushes in `setVideoMode`/`setResolvedOptics`), stores it
in `RendererConfig` for GL-generation replay, and geometry flows from ONE pure seam (`finderRect`)
shared by the GL scissor box and the Compose border so both stay pixel-aligned (RTL-safe absolute
anchor). The GL draw is failure-isolated (`runCatching` + `try/finally { glDisable(GL_SCISSOR_TEST) }`
— scissor is CONTEXT state; a leak would clip the encoder/analysis draws, and a finder-only error
must never fail preview health). A compact `PIP` OSD tag shows whenever the toggle is ON,
independent of the current gate, so "on but gated off" is distinguishable from "off".

**Stills** (`StillSnapshot`): the logical camera cannot allocate the HAL-JPEG blob (gralloc
rejects it) and a RAW target errors the whole camera device, so logical stills arrive as
YUV_420_888 (NV21-repacked on the camera thread via row-wise `System.arraycopy` fast paths — the
fully elementwise pack was ~19M bounds-checked ops per still and stalled 3A/zoom during bursts —
with a generic elementwise fallback; JPEG-encoded lazily on the io thread) and RAW is
gated standalone-only. A capture watchdog fails any shot whose image never arrives so the shutter
can never wedge: HAL-auto captures retain the 8 s floor, while manual/app-side requests use their
exact sensor-clamped exposure plus an 8 s delivery margin with saturating arithmetic. The scopes/AE
readback re-draws capture/EIS framing into an aspect-matched
FBO whose long edge is at most 256 px (≤256 KiB RGBA) instead of glReadPixels on the full preview
framebuffer (~33 MB at 4K — a periodic GL stall that read as preview stutter). Preview-only
punch-in/loupe framing is excluded from scopes and AE.

## Stabilization and Orientation

**Shipping stabilization path:**

Video uses the device HAL's OIS and video stabilization. `VideoStabMode` maps the UI's Off,
"Standard", and "Active" choices to the supported Camera2 request mode and mirrors the device's
`com.oplus.video.stabilization.mode` value. PMA110 result metadata verified `ois=1, vstab=2` for the
enhanced path. The app does not claim Explorer-only stabilization parameters that raw Camera2 cannot
access.

App-side GL gyro warping is disabled by `CameraEngine` with `gl.setEis(false, 0f, 0f)`. The renderer
and `GyroEis` retain dormant correction support, but it is not a user-facing or shipping stabilization
mode. This matters because whole-frame warping cannot remove motion blur accumulated during exposure;
the active HAL path can engage the physical lens OIS.

**Gravity-derived orientation:**

`GyroEis` remains active as a sensor-orientation provider:

```kotlin
// Used by the horizon and saved-still rotation paths.
val roll = gyroEis.currentRollDegrees()
val deviceOrientation = gyroEis.currentDeviceOrientation() // 0/90/180/270
```

The discrete orientation updates only when the phone is clearly held: in-plane gravity
`hypot(x, y)` must exceed `FLAT_GRAVITY_THRESHOLD`. When the phone lies flat, the last confident
orientation is retained instead of deriving a random quadrant from near-zero x/y values. The horizon
roll uses a similar confidence threshold when the phone points steeply up or down.

---

## Color & Video Pipeline

**Video codec and color profiles:**

Supported codecs are scanned at runtime via `EncoderCaps.kt` (MediaCodecList). Only hardware HEVC and
AVC encoders are exposed. Bitrate presets run Low → **Max** (`BitrateLevel`): the REQUESTED target at
Max computes to ~99 Mbps at 4K30 (0.40 bpp), hard-clamped at 120 Mbps (`videoBitRate`). A device
recording measured ~134 Mbps in the file — that is VBR encoder overshoot of the ~99 Mbps target (no
KEY_BITRATE_MODE is set), not a requested ceiling. The old High (0.16 bpp) left half the HW headroom
unused.

| Codec | Encoder profile | Color Space | Transfer | Container | Notes |
|---|---|---|---|---|---|
| HEVC (H.265) | Main10 profile (SDR: Main) | Rec.2020 (SDR: Rec.709) | HLG / O-Log2 / SDR | MP4 | Primary HW encoder. Shipping source/EGL is 8-bit; Main10 is the output profile, not an end-to-end 10-bit claim. |
| AVC (H.264) | 8-bit | Rec.709 | SDR | MP4 | Fallback; forces GL SDR (no HLG/Log); HW. |
| APV | — | — | — | — | HW `c2.qti.apv.encoder` (pro all-intra ≤2 Gbps) EXISTS but **gated out** — MediaMuxer rejects APV-in-MP4 (breaks the encoder mid-drain). |
| Dolby Vision | 10-bit | Rec.2020 | Dolby Vision | MP4 | HW `c2.qti.dv.encoder` detected (`hasDolbyVision`); not wired (clean DV-in-MP4 muxing non-trivial). |

**Vendor HAL features:** HAL OIS+EIS and directional-audio parameters are used where the device accepts
them. Native vendor log is inert for third-party Camera2; Auto HDR and in-sensor zoom were removed after
HAL stability testing. See CLAUDE.md for the per-key notes.

**Video resolution and frame rates:**

Resolutions come from the selected camera's `StreamConfigurationMap`, then the shipping selector caps
recording width at 3840. PMA110 exposes 4K UHD as the largest selected 16:9 mode. Frame rates include
standard rates (24/25/30/60 fps) and drop-frame equivalents (23.976/29.97/59.94). The UI deliberately
excludes 120 fps because the constrained high-speed session crashes this HAL.

Open-Gate (4:3-aspect recording; device-verified 2560×1920 on the tele — the recording surface is
capped at 3840 wide, so this is NOT the full 4096×3072 still readout) is available alongside the
standard 16:9 sizes.

Exact bitrate is displayed in Mbps and user-selectable per codec and resolution.

**Main10 output profiles (v1):**

HEVC Main10 profile → MediaCodec configured with:
```kotlin
ColorProfiles.videoFormat(
    codec=VideoCodec.HEVC,
    width, height, fps, bitRate,
    transfer=ColorTransfer.HLG or LOG
)
// Sets:
//   MediaFormat.KEY_MIME = "video/hevc"
//   MediaFormat.KEY_PROFILE = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
//   MediaFormat.KEY_BIT_RATE = bitRate
//   MediaFormat.KEY_COLOR_STANDARD = MediaFormat.COLOR_STANDARD_BT2020  (Rec.2020)
//   MediaFormat.KEY_COLOR_TRANSFER = COLOR_TRANSFER_HLG or COLOR_TRANSFER_SDR_VIDEO (O-Log2)
//   MediaFormat.KEY_COLOR_RANGE = LIMITED (HLG) or FULL (O-Log2)
```

**Shipping GL pipeline:**

```kotlin
// CameraEngine intentionally calls gl.start(tenBit = false)
EGL_RED_SIZE = 8, EGL_GREEN_SIZE = 8, EGL_BLUE_SIZE = 8, EGL_ALPHA_SIZE = 8
EGL_RECORDABLE_ANDROID = EGL_TRUE
```

Color-profile rendering happens in the fragment shader:
- **Input**: normalized [0, 1] display-referred SDR RGBA from camera SurfaceTexture sampling.
- **Transfer mapping / OETF**:
  - **HLG (Hybrid Log-Gamma)**: simplified display-referred SDR-to-HLG mapping from ITU-R
    BT.2408-9 §5.1.3.4: BT.1886 2.4 decode → linear BT.709-to-BT.2020 conversion → normalized
    inverse-OOTF/reference-white scale → BT.2100 HLG OETF. SDR reference white maps to 75% HLG.
    This preserves a valid HLG signal but cannot recover highlights removed by the ISP's SDR tone map.
    CPU/shader anchors are host-tested; final appearance after this mapping still requires playback on
    a real HDR display and is not inferred from compilation or container tags.
  - **O-Log2 (LOG)**: OPPO's official O-Log2 curve (white-paper constants), applied after γ2.2
    linearization of the SDR stream + Rec.709→BT.2020 matrix (O-Gamut). Grades with OPPO's public
    O-Log2 LUTs; no above-white headroom (HAL-native log is vendor-gated — see CLAUDE.md).
  - **SDR**: no shader curve; HEVC Main 8-bit BT.709 limited-range for zero-grading footage.
- **Output**: 8-bit EGL surface to a Main10-profile encoder for HLG/O-Log2.

**Fragment shader (Shaders.kt):**
```glsl
// Pseudocode
vec3 color = texture(camera, uv).rgb;  // display-referred SDR [0, 1]
if (transfer == HLG) {
    color = hlgOetf(inverseHlgOotf(toBt2020(bt1886Decode(color)) * referenceWhiteScale));
} else if (transfer == LOG) {
    color = olog2(toBt2020(gamma22Decode(color)));
}
// v1 output precision remains 8-bit for HAL stability
```

**AVC 8-bit fallback:**

When user selects AVC (H.264), the GL pipeline is forced to SDR:
```kotlin
// CameraEngine.startRecording()
val glTransfer = if (codec == VideoCodec.AVC) null else transfer
gl.setTransfer(glTransfer)  // null = no OETF in shader (linear passthrough)
```

Result: 8-bit SDR MP4, which AVC can encode natively.

**Audio (AAC, 192 kbps):**

Captured via AudioRecord on a separate thread. Software PCM gain applied (user-settable, 0.5× to 2.0× × post-gain). AAC LC encoder. Live RMS level throttled to ~10 Hz for the UI level meter. Every AAC setup degradation publishes the selected route as unavailable before continuing video-only; the UI sets `Starting...` before engine callbacks so that terminal label is not overwritten.

---

## Capture & Storage

**Photo formats:**

HEIF and JPEG are processed outputs and can be selected separately or together. DNG can be selected
alone or combined when the accepted session exposes a RAW reader. Capture requests are normalized
against the immutable `PhotoSessionOutputs` published by the session that actually reached Ready:
available requested outputs are retained, an available processed or RAW fallback is selected when
necessary, and preview-only produces an empty still set. The UI disables unavailable formats, PHOTO,
and in-REC snapshots from that same truth while video REC/Stop remains independent; the engine also
binds capture admission to the accepted controller/session identity.

**HEIF (still photo):**

1. Camera2 → logical `YUV_420_888` or standalone JPEG ImageReader (full resolution).
2. photoCallback on camera thread: copy the short-lived Image into owned YUV/JPEG data.
3. ioExecutor (off-camera thread):
   - Convert/decode the owned input → Bitmap.
   - Center-crop (if AspectRatio != W4_3).
   - Matrix.postRotate(captureRotationDegrees) → new Bitmap.
   - HeifCapture.writeHeif(ParcelFileDescriptor, Bitmap) → HEIF-encoded bytes.
4. MediaStore: create pending entry with IS_PENDING flag → write → publish on success; delete on failure.

**JPEG (still photo):**

JPEG runs the SAME processed-pixel pipeline as HEIF (`saveJpegAsync`): decode the ImageReader bytes →
center-crop to the selected aspect → rotate (afocal 180° + device) → re-encode at
`ManualControls.jpegQuality`. The mandatory pixel rotation means it is NOT a byte passthrough — the
output is a second lossy JPEG generation (accepted; keeping HEIF/JPEG framing identical wins). The
exposure EXIF is re-stamped after `Bitmap.compress` from the shot's own TotalCaptureResult. Physical
lens focal/aperture metadata comes from a setup-thread-prefetched immutable cache; the camera callback
does not query CameraService and copies the processed Image before resolving it.

**DNG (RAW, full-frame):**

1. Camera2 → RAW_SENSOR ImageReader.
2. photoCallback on camera thread (synchronous, Image still live):
   - DngCapture.writeDng(OutputStream, raw Image, CameraCharacteristics, TotalCaptureResult, exifOrientation).
   - DngCreator sets EXIF orientation tag (cannot rotate Bayer pixels).
3. MediaStore: create pending → write → publish.

**Last-capture review ownership:**

At shutter/record admission, every capture receives a monotonic engine id plus one versioned,
millisecond-timestamped `CaptureFamilyKey`. Every HEIF/JPEG/DNG sibling reuses the same still-family
key and filename stem; video owns one canonical MP4 family. Within the running process,
`CaptureOutputTracker` groups URIs by the monotonic id and orders review ownership by newest id first,
then by displayability inside that capture. A newer DNG-only completion therefore owns a truthful RAW
metadata placeholder instead of leaving an older thumbnail visible. A processed sibling from the same
capture upgrades the placeholder; RAW arriving after processed, or any output from an older capture,
is tracked for deletion but cannot displace the review owner. A late sibling whose capture id the
tracker's own bounded trim evicts DURING its `record()` call is re-checked after the trim and
demoted to track-only — an evicted family must never become the review owner (its URI could not be
pinned and delete would silently degrade to file-only).

On relaunch, `MediaStoreWriter.latestOwnCapture` independently queries bounded sets of this package's
published rows under `DCIM/X9Tele` from Images and Video. A failure in one collection does not discard
valid rows from the other. The Android-free reducer chooses the newest capture
before applying sibling display preference; an exact, bounded filename query then reconstructs every
extant row for the winning canonical family. That family is seeded into the tracker with a synthetic
ordering id below every live id, so even a racing first capture wins. Names from older app versions do
not prove sibling identity and are never grouped by timestamp proximity: they restore with an explicit
file-only delete scope. Opening review pins the frozen URI's exact family outside ordinary bounded
history; if pinning fails, delete copy remains file-only. Closing releases the pin, while deletion
consumes it with the family. Capture-family copy promises all known formats, while legacy copy promises
only the displayed file. Delete tombstones a tracked capture before asynchronous MediaStore calls,
attempts every known sibling, immediately rejects a late sibling callback, and reports a failure if any
attempted row could not be removed. RAW remains metadata-only in review; no Bayer decoding is implied.

**Aspect ratio (processed stills):**

```kotlin
data class AspectRatio(val w: Int, val h: Int) {
    W4_3(4, 3),      // Full sensor (no crop, default, the no-crop sentinel)
    W16_9(16, 9)     // Center crop of 4:3 to 16:9 landscape
}
// CameraEngine.centerCrop(bitmap, w, h)
// Computes largest w:h rect centered in the bitmap, crops it out.
```

The sensor is 4:3-native; `W4_3` is full readout, and `W16_9` is its center crop. 
DNG always saves full-frame (crop not applied).

**MediaStore scoped storage (MediaStoreWriter):**

```kotlin
createPendingImage(context, fileName, mimeType) → Uri
// Creates entry in DCIM/X9Tele with IS_PENDING = 1
openParcelFd(context, uri, "rw") → ParcelFileDescriptor
// Caller writes to the FD
publish(context, uri)
// Updates IS_PENDING = 0 (visible in gallery)
delete(context, uri)
// Removes the entry (if write failed)
latestOwnCapture(context) → RestoredCapture
// Bounded Images + Video scan, followed by an exact-family query when identity is proven
```

Canonical names also stamp `DATE_TAKEN` from admission time. On failure (OOM, disk full, etc.), the
pending entry is deleted → no partial files in gallery.

---

## Pro Controls Surface

**Overview:**
Core Camera2 capture parameters are housed in the immutable `ManualControls` data class. The ViewModel
copies it with updated fields on each interaction and re-applies it through
`CameraEngine.setControls()`. Restored and live controls pass through the same route normalization for
exact advertised modes, manual capabilities, region maxima, and zoom bounds. Same-route recall commits
that normalized packet before Ready; a route-changing recall waits for the target camera's caps.
The UI's `ControlAvailability` projection uses those same facts to filter settings and quick cycles,
and to admit or close manual rulers. AE/AF regions are omitted independently when the corresponding
maximum count is zero. Capture-mode, video, assist, hardware-key, and persistence options live in
`CameraUiState`/`ExtraSettings` rather than being forced into `ManualControls`.

Settings are persisted across app launches via `SettingsStore.kt` (SharedPreferences), gated by a 
"Remember Settings" toggle that **defaults ON**. On launch, saved pro settings are restored from storage 
and pushed to the engine before the camera starts. Fresh installs open on the 1× main lens with TELE
off; separate default-on Setup toggles decide whether the saved lens and TELE state are restored. Enums
are stored by name for forward compatibility, and loads are defensive (unknown values revert to defaults).

**UI layout (ProSheet.kt):**

The fixed settings panel has nine left-rail tabs:

1. **My** — operator-selected shortcuts.
2. **Shooting** — HEIF/JPEG/DNG selection, aspect, zoom, JPEG quality, drive/interval, self-timer,
   and MR save/recall.
3. **Exposure** — PASM-like mode, AE lock, flicker, shutter mode/step, ISO, metering, WB/custom WB,
   and AWB lock. EV remains on the quick Fn surface.
4. **Focus** — AF/MF mode, tap-AF spot size/lock, and peaking level/color. Manual focus distance
   remains on the quick Fn dial rather than this tab.
5. **Lens** — 0.6x/1x/3x/10x selection, TELE mode, stabilization mode, and OIS.
6. **Video** — codec, transfer, resolution, FPS, bitrate, Open Gate, and audio.
7. **Image** — edge sharpness, noise reduction, and color-effect processing.
8. **Assist** — gamma display assist, frame lines, zebra, false color, scopes, grid, level, punch-in, and the Tele Finder PIP toggle.
9. **Setup** — privacy, persistence, Fn/My Menu customization, hardware-key assignments, and the
   diagnostic camera override reset when one is active.

The rail is a Compose `selectableGroup`; each category is a `selectable` `Role.Tab` with the current
category's selected state. Its existing 48 dp-plus geometry and visual treatment remain unchanged.

**Quick Fn controls (ManualDials.kt):**

Photo and video have separate configurable Fn bars with up to eight slots. The photo default exposes
exposure mode, focus, shutter, ISO, WB, and EV; the video default adds gamma, stabilization, and audio
scene choices. Capability-dependent taps cycle only through the selected route's advertised choices.
The WB chip can open the preset sheet whenever more than one advertised mode exists; only MANUAL WB
requires the Kelvin ruler. Numeric focus/shutter/ISO/WB/EV/zoom controls use the compact dial/ruler
surface only when their exact manual modes and scalar ranges exist. A caps change closes an invalid
open ruler while keeping the normalized value applied.

**Control application:**

```kotlin
// ViewModel.onIso(iso), simplified: taking ownership of an auto-driven ISO enters Manual.
updateControls { it.copy(iso = iso, exposureMode = ExposureMode.MANUAL) }
// → engine.setControls(updated)
// → CameraController.updateControls(updated)
// → CameraController builds new CaptureRequest, applies ManualControls via applyManualControls()

fun CaptureRequest.Builder.applyManualControls(c: ManualControls, caps: CameraCaps) {
    applyFocus(c, caps)
    applyExposure(c, caps)
    applyWhiteBalance(c, caps)
    applyProcessing(c, caps)
    applyFlash(c, caps)
    applyZoom(c, caps)
    // OIS per toggle here; HAL video stabilization (CONTROL_VIDEO_STABILIZATION_MODE) is owned by
    // CameraController and set per the selected VideoStabMode on the repeating request (not forced OFF).
    if (caps.oisAvailable)
        set(LENS_OPTICAL_STABILIZATION_MODE, if (c.oisEnabled) ON else OFF)
}
```

All values clamped to hardware ranges (CameraCaps gates what's supported).

---

## Build & Toolchain

See `CLAUDE.md` § **Toolchain** for complete toolchain versions and build setup details.

**Quick reference:**
- Kotlin / Compose compiler 2.4.10, AGP 9.3.0, Gradle 9.6.1
- Android SDK Platform / compileSdk 37; targetSdk 36 / minSdk 36 (API 36 is Android 16)
- SDK Build Tools 36.0.0 (the AGP 9.3 default); compile and runtime API levels are intentionally decoupled
- JDK 21 required; set JAVA_HOME for CLI builds
- Compose BOM 2026.06.01

**Build:**
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:lintRelease :app:assembleRelease :app:bundleRelease  # signing credentials required
```

**Test suite:** `app/src/test/` is the source of truth. Run `./gradlew :app:testDebugUnitTest` for the
current result; do not copy a mutable test/class count into documentation.

**Tracked QA behavior contract:** The ignored local `.claude/agents/qa-adversary.md` runbook implements
this contract. A device run must receive the current session's `ANDROID_SERIAL`, derive application id
and launch activity from the built APK, and respect any no-deployment directive. Default photo starts on
the logical back camera at 1× / 23 mm with TELE off; TELE pins the standalone 3× camera; captures publish
through MediaStore under `DCIM/X9Tele`; exposure modes are P, S, ISO, and M; and the settings rail has the
nine tabs documented above. A host-only run reports device behavior as not run, never as passed or failed.

**Device verification:**
```bash
export ANDROID_SERIAL=<current-authorized-session-serial>
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
# Derive PACKAGE and ACTIVITY from the APK as documented in .claude/agents/qa-adversary.md.
adb -s "$ANDROID_SERIAL" shell am start -n "$PACKAGE/$ACTIVITY"
```

**Permissions:** CAMERA + RECORD_AUDIO requested at runtime (ColorOS blocks pm grant; user grants on device once).

---

## See Also

- `docs/BACKLOG.md` — release status, manual Play steps, residual checks, and deferred work.
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — preserved historical design
  snapshot; superseded by this current architecture/code wherever it differs.
- `CLAUDE.md` § **Hard-won device facts** — HAL crash workarounds and their signatures.
