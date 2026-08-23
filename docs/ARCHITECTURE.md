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
7. [Zoom & the Hybrid Camera Routes (2026-07-14)](#zoom--the-hybrid-camera-routes-2026-07-14)
8. [Stabilization and Orientation](#stabilization-and-orientation)
9. [Color & Video Pipeline](#color--video-pipeline)
10. [Capture & Storage](#capture--storage)
11. [Pro Controls Surface](#pro-controls-surface)
12. [Build & Toolchain](#build--toolchain)
13. [See Also](#see-also)

---

## Overview

A professional manual camera app installable from Android 13 (API 33) up, developed and device-measured on the **OPPO Find X9 Ultra**, that uses Camera2 to control the rear 3× periscope telephoto lens through a **Hasselblad "Earth Explorer" afocal 300 mm teleconverter** (≈4.286× magnification: 300 mm ÷ 70 mm). The app captures processed HEIF/JPEG stills, plus RAW/DNG on any rear lens that advertises it — wanting RAW is itself what routes photo onto a standalone camera, so DNG is not TELE-only — and HEVC video with HLG, log (S-Log3 / S-Log3.Cine / LogC3), or SDR profiles. Non-SDR video first requests an HLG10 Camera2 session with still readers removed; the accepted stream is still ISP-tone-mapped and display-referred. GL applies a source-aware decode and the selected HLG/log mapping through the release build's 8-bit EGL target, then HEVC writes Main10 output (SDR video uses the standard 8-bit session and Main output). This does not recover ISP-removed highlights and does not make the log profiles scene-referred camera log.

The UI/UX reference is **Sony Alpha / Sony Xperia Pro camera operation**. Use Fn access, My Menu, MR
banks, PASM-style exposure, compact OSD, peaking, zebra, histogram, waveform, and review zoom. Keep
the viewfinder quiet: no tutorial banners, warning chips, marketing cards, or helper overlays unless
the user asks. Important states belong in the OSD, Fn, or menu rows. See [`UX_POLICY.md`](UX_POLICY.md).

Two critical consequences of the afocal converter drive the entire design:
- **Image rotation**: The afocal telescope delivers light rotated 180° (no erecting prism). The main viewfinder and saved still/video results must be corrected. The same-stream [Loupe Overview is the deliberate exception](FIELD_CHECKS.md#loupe-overview-afocal-exception): it omits the afocal term and may show the raw, inverted field. Vertical flip + horizontal flip = 180° rotation (parity-preserving, not a mirror).
- **Near-infinity focus**: Exit light is approximately collimated, so the phone lens focuses near infinity. Manual focus with a nonlinear slider is essential for fine-tuning that critical zone.

---

## Module Map

| Package / File | Single Responsibility |
|---|---|
| **camera/** | |
| `CameraEngine.kt` | Facade orchestrating Camera2, GL, capture encoders, video recorder, sensors, and storage. Attempts BACK/FRONT/EXTERNAL route inventory before first open, independently retries incomplete classification to bounded eventual convergence, serializes camera reconfiguration, owns asynchronous save/finalization lanes, and publishes cross-thread state through volatile seams plus synchronized ownership gates. |
| `EngineCallbackSink.kt` | Atomic Engine→ViewModel callback lease. Every callback publication acquires the current sink identity; teardown closes admission and drains in-flight leases before clearing captured UI owners, so a late camera/save callback cannot escape through an individually stored lambda. |
| `CameraController.kt` | Camera2 session lifecycle, capability-safe request building, and fallback plans across stream sets and session operation modes. Sets a mode only when its exact value is advertised and applies AE/AF regions independently only when each maximum region count is positive. Callback-driven, runs on a camera HandlerThread; framework callback admission is serialized against `quitSafely` so late `onClosed` work never posts to a dead OPPO queue. |
| `CameraCallbackDispatchGate.kt` | Android-free close/admission gate ordering Camera2 executor posts before teardown, or rejecting them for the controller's inline late-callback cleanup after close begins. |
| `CameraSelector2.kt` | Detects the telephoto physical lens: finds the camera with focal length closest to 70 mm, prefers standalone ID over physical sub-camera routing. Its pre-open route-inventory attempt covers BACK/FRONT/EXTERNAL; a failed/unknown characteristics read publishes partial truth and schedules bounded independent retry rather than blocking a safe current/default open. Once complete, BACK remains preferred, front-only starts FRONT, and an external fallback opens plainly under GENERIC behavior. Front selection (`pickFront`/`pickFrontBest`) prefers a plain (non-logical) id and the largest active array on tie — never hardcoded. |
| `CameraState.kt` | Enums plus `CameraUiState` — the shared UI and runtime-state language. |
| `CameraStatus.kt` | Typed user-status identity, arguments, urgency/live-region policy, lifecycle, and duration. |
| `DeviceProfile.kt` | The second sanctioned model seam: resolves measured per-device HAL quirks only. `detectPhone` remains the separate catalog-preselection seam; capability availability otherwise comes from Camera2 enumeration. |
| `DeviceExifLabels.kt` | Derives truthful EXIF make/model/lens labels from the active enumerated camera and device identity; no vendor or camera-id catalog string is stamped onto another device. |
| `CaptureCapabilities.kt` | Flattens Camera2 characteristics into exact advertised mode sets plus maximum AE/AF region counts, alongside manual-sensor, RAW, HDR, focus, and stream capabilities — including the full-sensor hi-res still facts (`hiResJpegSize` from the standard ultra-high-res path or the `pickVendorHiResSize` vendor fallback; `hiResUsesMaxResolutionMode` records whether the still request must carry `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`). |
| `NumericBounds.kt` | Fail-open clamp against a finite, ordered vendor range: a transiently malformed HAL range returns the requested value instead of throwing on the camera thread. |
| `ProcessedSnapshotBudget.kt` | Android-free, thread-safe admission budget for retained processed still snapshots (one running SINGLE save plus one waiting full-resolution snapshot). |
| `StillPublicationDispatcher.kt` | Per-Engine closeable facade over one process-lifetime finite RAW-only SINGLE DNG publication owner: two daemon workers plus two queued tails. Accepted work retains its original capture-family/callback identity across Engine replacement; overflow or shutdown never publishes inline or deletes complete bytes, and launch recovery owns the private row. |
| `ControlAvailability.kt` | Projects those exact mode arrays, manual/range facts, and AE/AF region maxima into enum choices and admission flags shared by settings, top-bar/Fn cycles, and quick rulers. Sparse routes use a neutral singleton; before caps arrive, the current singleton remains visible but disabled. |
| `ManualControls.kt` | Immutable snapshot of all pro capture parameters (focus, ISO, shutter, white balance, metering, processing). `normalizeControlsForRoute` applies one exact capability/zoom boundary to live and recalled packets before accepted Engine/UI/request publication. Also owns the sensor fast-path admission predicate (`sensorFastPathAdmitted`, wrapping `sensorOnlyControlsDelta` — a live tap-AF/AF-lock override rides the fast path and is re-applied, not refused), the retained-optics exact-controls/boost-off plan, and the shared sensor-key request derivation (`applySensorValueControls`). |
| `RotationMath.kt` | Pure, unit-tested functions for preview/capture/EXIF rotation math and the video muxer orientation hint (extracted from CameraEngine). Capture rotation is facing-aware: BACK = sensor + afocal(tele) − device; FRONT = sensor + device, with the afocal term never applied. The device term is GyroEis CCW-POSITIVE, which is why it subtracts on the rear and adds on the front (device-bisected and verified 2026-07-25). |
| `RendererConfig.kt` | One immutable snapshot of every renderer-only assist (peaking, zebra, false color, punch-in, tele finder, …) with a store that replays the complete snapshot into each fresh GL generation. |
| `RendererAssists.kt` | Owns `RendererConfigStore`, resolves Loupe Overview intent, and is the single setter/replay facade between CameraEngine and GlPipeline. Every setter records state before posting so a dropped old-generation GL command is restored by `replayAll()` on the next generation. Motion arming, provider, and evidence epoch publish as one atomic replay record. |
| `StandbyAudioController.kt` | Owns the armed-video standby meter lifecycle, bounded AudioRecord recreation, and exact `StandbyMeterOwnership` handoff to REC. All engine dependencies are live lambdas so a retired meter generation cannot reclaim a newer intent. |
| `OpticsConstraints.kt` | Pure admission/rollback rules for optics transactions (mode/lens/TC transitions, structural-reconfigure decisions), unit-tested off-device. |
| `ZslAdmission.kt` | Pure serve/refuse predicate for the LOGICAL/FRONT-route pseudo-ZSL ring: a buffered frame is served only when its ACTUAL sensor values match the still's INTENDED values within 1/6 stop (plus zoom within 2%, age < 250 ms, app-side AE-OFF, processed-only, no AE-flash, no live gesture, SINGLE drive). Refusal in low light is the DESIGN — the fluidity cap deliberately diverges preview from intent there, so a real full-quality capture must run (see CLAUDE.md). |
| `StartupTrace.kt` | Debug-only cold-start stopwatch against a `resume`-origin clock. BUFFERS its marks and emits ONE line at `finish()` because ColorOS's 300-row per-process log quota silently eats per-mark logging; armed idempotently in `CameraEngine.resume` and disarmed on every path that returns without a real open. |
| `Teleconverter.kt` | The optics catalog as a PAIR: `PhoneModel` and compatible `TeleconverterProfile`s. `detectPhone(Build.MODEL)` is catalog preselection only; `DeviceProfile.resolve` is the other sanctioned model seam and gates measured quirks. Camera capabilities/routes remain enumerated. |
| `ZoomSubmitPlan.kt` | Pure HAL zoom-submit decision (throttle window + mid-gesture wide-aim clamp), extracted from `CameraEngine.setZoomRatio` and unit-tested. |
| `RecordingAdmissionLatch.kt` | Monitor-owning REC stop-during-start latch (`tryBeginAdmission`/`requestStop`/`completeAdmission`), extracted from CameraEngine and race-tested. |
| `RecordingPreNativeAllocation.kt` | Process-wide finite lane for pending-video MediaProvider allocation: two daemon workers plus four queued attempts, per-attempt first-wins retirement, and cancellation of queued work. Stop/pause/release/timeout free REC admission immediately; a late row is cleanup/recovery-owned and cannot enter native setup. |
| `RecordingStorageDispatcher.kt` | Process-lifetime bounded post-native storage owner: exactly two daemon workers plus eight FIFO backlog slots shared by every Engine generation. Each Engine holds only a closeable admission facade, so overflow or facade shutdown leaves the finalized pending row private for launch recovery while already accepted, callback-identity-bearing tails finish without interruption. |
| `RecordingTeardownCoordinator.kt` | Android-free terminal owner for encoder detach: arms independent recovery/hard deadlines before submission, admits recovery once, and selects exactly one strict finalization or quarantine while making rejection and late callbacks inert. |
| `LaunchMediaRecoveryCoordinator.kt` | Process-wide single-flight launch recovery. Engine generations hold cancellable identity-keyed subscribers rather than workers. Recovery runs one bounded family/rejected-output preflight, independent 64-row `_ID`-ordered Images and Video pages, then indexed 64-entry durable-DISCARD pages. A separate process watchdog terminally exhausts the one worker after 120 s without interrupting or replacing a wedged provider call; its late return is inert and every current/later subscriber receives the same typed failure until process restart. Returned per-row failures retain their markers and advance only after the ordinary retry budget. |
| `RetainedStillDeletionOwner.kt` | Engine-owned bounded deleted-capture tombstones for retained private HEIF/JPEG/DNG outputs. Only live still families enter this gate; video and restored families carry durable delete identity without claiming a late-still producer. The in-memory tombstone is synchronous, while family-journal durability runs on the ordered I/O lane. |
| `RetainedStillDiscardDispatcher.kt` | Per-Engine closeable admission facade over one process-lifetime finite provider lane (two daemon workers + eight backlog slots) for retained deleted-still rows. Accepted tasks keep their exact old-Engine deletion identity; overflow/shutdown never run ContentResolver inline, and the durable family tombstone leaves refused work owned by launch recovery. |
| `AutoExposure.kt` | Pure, unit-tested app-side AE math: SHUTTER/ISO-priority drive functions and the photo-P program line (`driveProgram`), metered off the GL luma histogram. |
| `VendorTagInspector.kt` | Debug-only Camera2 capability logger for device-specific request/session keys. |
| **gl/** | |
| `GlPipeline.kt` | One object owns one native GL generation and checked preview/encoder EGLSurface lifetimes. Outgoing outputs are unbound before destruction; preview and encoder readiness publish only after a real swap. Each object owns and retires its analysis executor, busy gate, FBO/buffer snapshot, callbacks, and native fields. `stop()` reports STOPPED only when the thread exits and checked native-output release succeeds; timeout or unsafe release permanently ABANDONS the object. `CameraEngine` compare-and-swaps a fresh object into `AtomicOwnerSlot`, restarts it from a live foreground preview, captures the exact owner/input for every preview/Camera2/recorder transaction, and identity-gates late callbacks. `RendererAssists` resolves/replays config into the current object once per operation. Thus a leaked old handler can touch only its own retired EGL state, never replacement state. |
| `FrameNotificationCoalescer.kt` | Latest-frame backpressure owner between `SurfaceTexture` notifications and GL draws. At most one scheduled drain represents any burst; a concurrent notification records one pending edge, and generation retirement makes every stale drain inert rather than posting one draw per producer callback. |
| `FlipRenderer.kt` | Low-level OpenGL ES fullscreen quad renderer with texture-coordinate rotation (inverse of image rotation) to flip the 180° afocal image. Applies the SDR-to-HLG mapping or a log-profile encoding (S-Log3 / S-Log3.Cine / LogC3) in the fragment shader and handles focus peaking/zebra. A per-draw `mirrorX` selects the x-inverted attribute texcoord quad (pure `texCoordQuad`); which draws set it derives from `DeviceProfile.frontStreamPreMirrored` — the PMA110 front HAL PRE-mirrors its stream (device-diagnosed 2026-07-23), so the PREVIEW draw never sets it and only the ENCODER/ANALYSIS draws do, un-mirroring files and scopes back to the true scene. |
| `FocusDetail.kt` | Pure curvature-ratio frame-detail metric (RMS second difference at a fine lag vs coarse lags {4,8,16,32}, per 16x16 tile, per axis). Rides the EXISTING scopes/AE analysis readback as a CPU rider — no second readback, no new GL pass. Takes NO `lut` parameter by design, so the digital-gain display simulation cannot move an optics verdict. |
| `FrontMirrorConvention.kt` | Derives preview, encoder/analysis, texture-tap, and active-array metering mirror roles from the active route plus `DeviceProfile.frontStreamPreMirrored`. PMA110 and GENERIC conventions therefore remain explicit and testable. |
| `MotionInversion.kt` | Pure sign-of-motion evidence classifier with conservative `UNJUDGEABLE` behavior and epoch-safe publication. |
| `AtomicOwnerSlot.kt` | Identity-owned atomic slot for replaceable native-resource facades: a late completion may replace only the exact object it stopped, so a stale generation cannot displace its replacement. |
| `EglCore.kt` | Checked EGL/GLES setup, binding, presentation, buffer swap, unbind, surface destruction, and display teardown. Supports an experimental 10-bit config, while release builds deliberately start the stable 8-bit config even when Camera2 supplied an HLG10 source. |
| `Shaders.kt` / `SdrToHlgMapping.kt` / `LogProfiles.kt` | Source-aware standard/HLG decode plus BT.2408-9 HLG and S-Log3/LogC3 mappings, peaking/zebra, and punch-in. O-Log2 is removed in both directions; shader code 3 stays vacant and Gamma Assist is a preview-only log-curve bypass. |
| **stab/** | |
| `GyroEis.kt` | Sensor helper for gravity-derived device orientation and the horizon overlay. It retains residual-shake math, but the shipping GL path disables app-side EIS in favor of HAL OIS+EIS. Its explicit motion-evidence reset clears timestamped gyro history without changing an already-armed sensor-registration intent. |
| **capture/** | |
| `StillSnapshot.kt` | YUV_420_888→NV21 repack (row-wise arraycopy fast paths + generic fallback) and lazy JPEG encode for logical-camera stills, which cannot use the HAL JPEG path. |
| `StillCapturePipeline.kt` | Owns processed-still decode/crop/rotation, isolated HEIF/JPEG encoders, shared shot EXIF composition, DNG write orchestration, and MediaStore write-state transitions. Processed and sequence/mixed-output work runs on ioExecutor; DNG bytes are written synchronously while the RAW Image is live, then RAW-only SINGLE publication crosses to the process-finite still-publication owner. Hi-res shots take the passthrough-JPEG lane (`writePassthroughJpeg`): HAL bytes go to disk verbatim with the capture rotation as an EXIF orientation TAG only — no decode/crop/pixel-rotate (a ~200MP pixel-upright pass is a guaranteed OOM); in-app review honors that EXIF orientation when decoding. |
| `HeifExif.kt` | Pure JPEG APP1 EXIF payload extraction (`Exif\0\0` + TIFF data) for `HeifWriter.addExifData`; the marker walk is host-tested independently of Android's ExifInterface. |
| `HeifCapture.kt` | Encodes HEIF from a Bitmap after crop and `captureRotationDegrees()` pixel rotation, injecting the same shot EXIF APP1 payload used for JPEG. Writes via the ioExecutor off the camera thread. |
| `DngCapture.kt` | Writes DNG (RAW sensor frame) using DngCreator. Sets EXIF orientation tag (cannot pixel-rotate Bayer CFA). Synchronous in the photo callback while the raw Image is live. |
| **video/** | |
| `AudioReadPolicy.kt` | Pure classification of `AudioRecord.read` return codes (PCM / transient retry / normal stop / terminal failure) shared by the recorder loop and the standby meter, plus the meter's bounded-recreate budget rule. |
| `VideoRecorder.kt` | MediaCodec HEVC/AVC encoder + AAC audio encoder + MediaMuxer. Exactly-once owner of the codec input Surface: clean release follows verified EGL detach and partial setup also releases. If detach cannot prove native release, an independent watchdog terminally quarantines the complete native graph process-long, ends Java/audio work without releasing codec/muxer/fd/Surface, and refuses another camera/REC graph until process restart. `stopNative` checks container/native finalization and freezes only provider-safe values into `RecordingStorageTail`; extractor validation, durable COMPLETE marking, publish, or invalid-row deletion run later without retaining a codec, muxer, fd, Surface, AudioRecord, or REC lease. Video input comes from GL already flipped; audio runs separately with normalized software PCM gain. A mid-REC negative `AudioRecord.read` degrades to video-only; only VIDEO faults delete. The one tolerated sample-less-audio `muxer.stop()` failure is published only after native owners close and MediaExtractor reopens an actual video track. The encoder buffer takes `RotationMath.encoderSurfaceSize` — swapped to the DISPLAYED portrait aspect for the 90° sensor so `coverScale` records exactly the viewfinder field. The vendor audio-HAL key `vendor_audiorecord_orientation` (which aims the Sound Focus beam / Sound Stage field) is a DIFFERENT DOMAIN from the muxer hint despite the shared input: it takes the RAW gravity device orientation, NOT `RotationMath.videoOrientationHint`. The two were the same function only while the hint was the identity; making the hint −dev on rear routes silently fed the audio HAL the mirrored landscape until they were split. |
| `AudioInputInspector.kt` | Resolves the preferred recording input (built-in / wired / USB / BT) against connected AudioDeviceInfo entries; provides the route labels shown in the UI. |
| `AudioLevels.kt` | Pure PCM peak/RMS aggregation, channel projection, and software-gain math shared by standby metering and recording. |
| `ColorProfiles.kt` | Builds MediaFormat specs for HEVC Main10 (Rec.2020 + HLG/Log), HEVC Main SDR, and AVC 8-bit SDR. Tags encoded-output profile, color space, range, and transfer; these tags do not describe the precision of every upstream stage. |
| `EncoderCaps.kt` | Scans exact AVC/HEVC components, ranks hardware first while retaining registry-stable software fallback, and carries accepted component identity into recording. |
| `EncoderSizeLadder.kt` | Same-aspect, even-dimension component/size configure-attempt ladder used when an advertised encoder rejects the requested portrait raster. |
| **storage/** | |
| `CaptureFamily.kt` | Versioned, timestamped capture-family identity embedded in every new output filename. HEIF/JPEG/DNG siblings reuse one exact key; video owns a one-file family. Legacy names are deliberately not inferred by timestamp proximity. |
| `LatestCaptureReducer.kt` | Android-free reducer for owned Images/Video rows. Selects the newest capture first, then a displayable sibling inside only that capture, and distinguishes proven capture-family deletion from legacy file-only deletion. |
| `MediaStoreWriter.kt` | Scoped-storage wrapper with durable per-URI `REGISTERED`/`COMPLETE`/`DISCARD` ownership and a bounded family-delete journal. Family deletion uses a process-wide reference-counted exact-family authority: provider absence queries hold only that family's owner, while marker capacity/commit/remove uses a short metadata monitor and retry sleeps run outside it. A same-family re-mark waits behind an old retirement and is therefore committed after the old marker is removed; a blocked family cannot prevent an unrelated durable tombstone. Rejected outputs always attempt typed DISCARD before deletion; double-failures stay in one finite process retry owner and close new output admission at capacity. COMPLETE exhaustion leaves structurally complete bytes private. Launch recovery adopts COMPLETE/valid rows, deletes only proven-invalid or durably rejected/deleted rows, and retains indeterminate/error rows through a bounded preflight, independent Images/Video pages, and a separately cursor-paged durable-DISCARD stage. An unreadable exact-DISCARD authority is a typed retry failure before probing/adoption/publication, never evidence of marker absence. |
| `PendingDiscardJournal.kt` | SQLite-backed exact-URI DISCARD owner. Lookup is tri-state (`PRESENT` / `ABSENT` / `UNAVAILABLE`) so database failure cannot resurrect delete-owned media. A process-wide reference-counted exact-URI authority serializes publication with marker creation/removal for only that URI; the separate SQLite monitor never spans MediaProvider Binder I/O, retry sleeps, legacy SharedPreferences loading, or bounded per-page preference cleanup, so a blocked URI or preference operation cannot freeze unrelated journal progress. `uri TEXT PRIMARY KEY` provides indexed `uri > cursor ORDER BY uri LIMIT 65` pages. First migration checks completion under the short database monitor, snapshots legacy DISCARD entries after releasing it, then rechecks and commits the frozen rows plus import-complete metadata in one transaction; concurrent first pages may snapshot redundantly but cannot import twice. Each frozen page releases database ownership before best-effort, idempotent preference cleanup, and a cleanup failure cannot trigger a full re-import. REGISTERED/COMPLETE remain preference-backed. Failed deletion retains the marker and successful/authoritatively absent deletion is terminal only after exact marker removal succeeds. |
| `SettingsStore.kt` | SharedPreferences persistence of ManualControls + ExtraSettings across launches, gated by a "Remember Settings" toggle (default ON); enums stored by name, defensive load. Lens and TELE restoration have separate default-on preserve toggles. |
| **focus/** | |
| `MacroProximity.kt` | Focus-confidence proofs and their OSD wording: `AF_LIMIT` (AF failed/hunting near the advertised minimum focus distance) may say `TOO CLOSE` with a closer-lens suffix; `FRAME_DETAIL` may only say `SOFT` with no suffix — it proves the frame resolves no fine detail, never *why*. 700 ms hold; any refusal resets it. |
| `FocusMapping.kt` | Maps the UI slider (0..1) bidirectionally to LENS_FOCUS_DISTANCE with `diopters = minFocusDiopters * slider^3`. There is no additive offset, preserving exact infinity at slider 0 while concentrating travel near it. |
| **ui/** | |
| `ZoomMath.kt` | Pure zoom-scale math shared by engine and UI: effective bounds, TELE magnetic-snap normalization, mode/restore scale remaps, the hardware-glide ease-step function, and the TELE rail's device-derived zoom marks (`teleZoomMarks` — lens bounds × converter magnification; an unreachable snap is ABSENT, never clamped). |
| `SwitchCoverPolicy.kt` | Pure fold for the camera-switch dip. Keys on a session-generation CHANGE, never `cameraReady` — every optics door clears that bit including the same-route fast path behind every photo lens preset, so a ready-keyed cover would flash black on the most-used control. Repeated Not-Ready inside one reopen is idempotent via an epoch; a self-owned release deadline bounds every cover because `rollbackOptics`, exhausted recovery, and doors returning early on `paused` deliver nothing further. |
| `CameraScreen.kt` | Compose root layout: preview TextureView, shutter button, mode toggle, gallery thumbnail, fixed settings panel, and capture overlays. Stateless, reads CameraUiState. |
| `CameraScreenPolicy.kt` | Host-testable status, layout, accessibility, rotation, and overlay policy used by the Compose root. |
| `ModalFocus.kt` | Shared focus-containment and restoration policy for Fn, settings, adjustment, review, permission, and dialog modal surfaces. |
| `CameraViewModel.kt` | StateFlow<CameraUiState> owner. Turns CameraActions into CameraEngine calls, publishes capability-normalized controls, applies gesture changes with a trailing throttle, and coordinates capture-id review ownership. |
| `LocalizedStatus.kt` / `LocalizedLabels.kt` / `controls/LocalizedControlLabels.kt` | Presentation-time English/Korean resource resolution for typed statuses and domain/control identities. |
| `CaptureOutputTracker.kt` | Bounded, synchronized ownership map for monotonic capture ids, canonical `CaptureFamilyKey`, media kind, delete scope, and every processed/RAW sibling. Family truth is registered before still callbacks or video allocation. It distinguishes live-still late producers from video/restored families, seeds canonical prior-process identity below live ids, and pins one open-review family outside ordinary history. |
| `CameraActions.kt` | Callback interface for stateless UI commands such as focus, exposure, tap AF, lens, recording, persistence, and review actions. |
| `ShutterPolicy.kt` | Pure photo-shutter activation resolution: countdown cancellation has first refusal, self-timer vs immediate fire, and the video-snapshot exemption from the Photo self-timer. |
| `ZoomGlideState.kt` | The Android-free half of the zoom-interaction lifecycle: coalesced `pendingRatio`, hardware-glide `easeTarget`, `interacting`, `flushScheduled`, plus `invalidateForRemap()` and the zoom-OUT `isLeadingEdgeToWide` decision. Every optics-scale remap door invalidates through the ViewModel's single `invalidateOpticsDerivedState()` owner (host-tested) — which also drops route-scoped focus evidence, since zoom glide and focus confidence share exactly one door set. |
| **ui/controls/** | |
| `ManualDials.kt` | Horizontal scrolling dials for quick access to focus, shutter, ISO, white balance, EV, and zoom — the "Fn" layer. Entry is admitted by `ControlAvailability`, and a ruler closes if a route change removes its required exact mode/range. The WB chip can open preset choices without a Kelvin ruler; MANUAL WB still requires that ruler. |
| `ProSheet.kt` | Fixed Sony-style settings panel with a 9-tab left rail: My, Shoot, Exposure, Focus, Lens, Video, Image, Assist, and Setup. The rail is one selectable group whose items expose selected state and `Role.Tab`. Capability-dependent selectors contain advertised choices when present; an empty set falls back to a disabled neutral singleton, and otherwise-invalid entry points are disabled. |
| `ProControls.kt` | Reusable Compose controls including rulers, segmented choices, toggles, sliders, and value rows. All are two-way bound to CameraUiState. |
| `ControlCycles.kt` | Shared tap-cycle and auto-exposure readout logic used by ManualDials, ProSheet, and CameraScreen. Capability-dependent cycles advance only through `ControlAvailability` choices (single copy — no drift). |
| `ControlLabels.kt` / `LocalizedControlLabels.kt` | Typed control-value identities plus presentation-time English/Korean labels shared by Fn tiles, rulers, settings, OSD, and accessibility state. |
| `FnIcons.kt` | Single icon mapping for configurable Fn slots so the editor and shooting tray cannot assign different symbols to one control. |
| `FnQuickActions.kt` | Shared quick-action enablement and dispatch for in-place Fn cycles/toggles, including the compact adaptive editor-row policy. |
| `SliderKeyPolicy.kt` | Pure keyboard/D-pad step, direction, and boundary policy used by focus, gain, and other slider controls. |
| **ui/overlays/** | |
| `Overlays.kt` | Compose overlays: reticle (tap-to-focus), histogram/waveform, grid, spirit level, peaking, zebra, punch-in zoom indicator, AE/AWB/AF lock tags. Stateless off CameraUiState. |
| **ui/review/** | |
| `ExactHandlePrepareOwner.kt` | Exact-generation deadline and exactly-once native release owner for asynchronous video preparation. Prepared/error/timeout/Back/disposal/replacement retire only the matching handle; a stale timer cannot release or fail a newer clip. |
| `LatestHeavyWorkLane.kt` | Identity-owned review-work boundaries. Production uses `ProgressiveLatestWorkLane`: two consumers per logical lane, one conflated pending request, and one shared four-daemon process pool. Its atomic QUEUED→STARTED→PRODUCED→TERMINAL owner distinguishes a retryable active-call timeout from a queued request that could not acquire poisoned capacity, while a completion racing the 5 s deadline is either published or disposed exactly once. The older serial `LatestHeavyWorkLane` remains a focused ownership seam rather than a production acquisition owner. |
| `MediaReview.kt` | In-app review of the last capture: zoomable processed photos (EXIF-orientation-honoring decode), rotating video playback, and a truthful non-decoding RAW/DNG metadata tile. Descriptor, thumbnail, full-screen still, and synchronous player setup use the process-finite progressive owner; Back/disposal invalidates exact publication identity, requests retain only application Context, active timeouts expose Retry, and genuine acquisition exhaustion exposes restart guidance. The later `prepareAsync()` stage has its own exact-handle 5 s deadline and releases to the same retryable timeout state if the platform emits neither prepared nor error. Video transport is explicitly Preparing/Playing/Paused: no tap or Pause claim exists until `MediaPlayer.start()` succeeds. At most two worker results exist per lane and four blocking calls process-wide. A decoded Bitmap is recycled while unpublished, but after transfer to Compose it is GC-owned because state replacement does not prove the previous draw node has retired. |
| **ui/theme/** | |
| `Theme.kt` | Material3 dark theme tuned for a Sony-style pro camera surface, typography, color palette, text field/button shapes. |
| **(app root — `me.hletrd.telecampro`)** | |
| `MainActivity.kt` | Entry point. Requests CAMERA/RECORD_AUDIO at runtime, and the READ_MEDIA trio contextually (ColorOS blocks pm grant). CAMERA request history distinguishes fresh/cancelled prompts from fixed denial before offering Settings. Hosts the Compose root and ViewModel. Lifecycle: `onStart` calls the ViewModel's `onStart`, which resumes the engine; `onStop` calls the ViewModel's `onStop`, which pauses it. |
| `CameraPermissionPolicy.kt` | Pure CAMERA-permission decision table: fresh install / cancelled prompt / genuine permanent denial, driven only by completed request history plus rationale state. |
| `HardwareInputPolicy.kt` | Pure camera-key EDGE ownership only (`cameraKeyDecision` / `updateAggregateCameraKeyOwnership`): pairs key-down claims with their key-ups across the aliased camera keycodes. The OEM keycode families and their dispatch to configurable actions live in `MainActivity.kt`; the `HardwareKeyAction` enum lives in `camera/CameraState.kt`. |
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
1. Camera2 → SurfaceTexture (backed by EGL texture) → `FrameNotificationCoalescer` records the latest
   notification and schedules at most one generation-owned `GlPipeline` drain/draw at a time.
2. FlipRenderer samples the camera texture with rotated texture coordinates (inverse of image rotation, so the visual result is 180°-flipped).
3. OETF and enabled monitoring overlays are applied in the fragment shader (passthrough for SDR).
4. Result rendered to:
   - **Preview Surface** (TextureView on main thread) → live on-screen.
   - **Encoder Surface** (MediaCodec input, if recording) → encoded into MP4.

**Still-photo journey (processed HEIF/JPEG):**
0. On the LOGICAL and FRONT photo routes, that same YUV reader ALSO streams on the repeating request into
   a 3-deep pseudo-ZSL ring (`camera/ZslAdmission.kt` is the pure serve/refuse predicate). An
   admitted shutter press serves the newest buffered frame INLINE through the same Pending/
   tryComplete machinery a real capture uses, and `takenAtMs` is backdated by the frame's age so
   EXIF and file times stay honest. A real capture on either route adopts its image from the ring by
   EXACT `SENSOR_TIMESTAMP`; TELE/other-standalone readers never see repeating frames and keep blind
   adoption. Admission is intentionally strict, so a dark shot refuses and falls through to step 1 —
   see CLAUDE.md for why that refusal is the design.
1. Camera2 → processed ImageReader: logical-camera photo sessions use `YUV_420_888`; FRONT uses YUV
   on its full/deep and shallow fallback rungs; other standalone sessions use HAL JPEG. The YUV
   reader is allocated DEEP (ring depth + 2) only on the full session rung. A configure-time
   rejection first degrades it to the proven 2-image reader (`SessionAttemptPlan.useDeepZslReader`).
   FRONT then falls back once more to its proven HAL-JPEG path before preview-only; PMA110's
   mandatory-YUV quirk applies only to the logical camera whose JPEG blob was measured broken.
2. The camera callback copies the short-lived `Image` into owned JPEG bytes or an owned YUV snapshot.
3. `ioExecutor` produces a Bitmap, center-crops 16:9 when selected, and pixel-rotates for sensor,
   device orientation, and the afocal 180° correction. It then encodes each requested HEIF/JPEG output;
   JPEG is re-encoded at the shot's frozen quality setting and is never a byte passthrough. Both
   formats receive the same shot-owned exposure/lens EXIF attributes.
4. MediaStore creates each output as pending plus durably `REGISTERED`, marks the closed output
   `COMPLETE`, then publishes only if that marker commit is durable. Exhausted marker commits leave
   the structurally complete row private for relaunch probing; neither publish nor delete runs. A
   later publication outage likewise leaves valuable complete bytes pending for relaunch adoption;
   only an interrupted, structurally invalid write is deleted.

**Still-photo journey (DNG/RAW):**
1. An eligible STANDALONE Camera2 session (TELE is one such route, not the requirement — wanting RAW is what selects a standalone lens) → RAW_SENSOR ImageReader → photoCallback on camera thread.
2. Synchronously (Image still live): DngCreator.writeDng → map `captureRotationDegrees()` to the corresponding EXIF orientation tag → MediaStore write → bounded durable `COMPLETE` marker attempt.
3. Cannot pixel-rotate Bayer CFA; EXIF tag is auto-applied by RAW renderers. RAW-only SINGLE
   publication crosses to the process-wide finite still-publication owner (two active + two queued),
   while mixed-output and sequence drives retain their existing processed-save/chained `ioExecutor`
   ordering. Publication still requires the marker to be durable. Marker exhaustion, capacity
   overflow, executor rejection, or a publish-only failure keeps the private row for structural
   recovery; no overflow runs provider work inline or deletes complete DNG bytes.

---

## Threading Model

**Threads / Executors:**

| Thread / Executor | Owner | Runs |
|---|---|---|
| **Main (UI)** | Android framework | Compose recomposition, ViewModel StateFlow updates, lifecycle callbacks (onStart/onStop). |
| **mainHandler work** (main-thread Handler) | CameraViewModel | Lifecycle-owned periodic record/level/orientation/info updates, bounded zoom easing, and transient countdown/reticle work. `onStart` owns recurring registration and `onStop` removes it. |
| **gl-pipeline** HandlerThread | GlPipeline | EGL operations, texture sampling, rendering, GL shader execution. |
| **camera** HandlerThread | CameraController | Camera2 lifecycle and capture callbacks. Copies JPEG/YUV data before cache-only EXIF composition while the Image is live, and invokes the synchronous DNG byte write while the RAW Image is valid. |
| **setupExecutor** (single-thread) | CameraEngine | Post-GL-input Camera2 route/capability preflight, lightweight physical-lens EXIF prefetch, and serialized generation-owned mode/lens/session reconfiguration. Debug diagnostics are queued behind the initial route/open work. |
| **ioExecutor** (single-thread) | CameraEngine / StillCapturePipeline | Deferred processed-still decoding, crop/rotation, shared HEIF/JPEG EXIF composition, encoding, processed publication, and mixed-output/sequence DNG publication after the live-Image write is complete. |
| **still-publication dispatcher** (process-wide two workers + two backlog slots) | `ProcessStillPublicationOwner`; CameraEngine owns one closeable admission facade | RAW-only SINGLE DNG publication after synchronous byte write and COMPLETE-marker attempt. Admission is non-blocking and finite across Engine generations. Accepted tails retain exact capture-family callback identity; overflow/facade shutdown reports delayed recovery, terminally releases the live family continuation, leaves complete bytes private, and never calls MediaProvider inline. |
| **media-recovery executor** (one process-wide daemon + one watchdog daemon) | `ProcessLaunchMediaRecovery`; Engines own cancellable subscribers | Single-flight launch-only recovery with bounded retry/backoff: one family/rejected-output preflight, independent 64-row monotonic Images and Video pages, then indexed SQLite DISCARD pages that read at most 65 rows. Legacy import commits once before paging; failed preference cleanup never repeats the full import. DISCARD-authority unavailability retains the row and retries as QUERY before any probe/adoption/publication. An exhausted returned-failure page retains its markers and advances. A separate 120 s watchdog terminally poisons a non-returning provider worker without interrupting or replacing it, makes late completion inert, and delivers the same typed failure to every current/later subscriber until process restart; that terminal still gates the safe latest-family query. |
| **retained-still discard dispatcher** (process-wide two workers + eight backlog slots) | `ProcessRetainedStillDiscardOwner`; CameraEngine owns one closeable admission facade | Provider retirement/discard for completed late stills after a deleted capture. Accepted old-Engine work finishes under its exact deletion owner. Overflow or facade shutdown performs no inline provider work; the durable family tombstone remains the launch-recovery continuation. |
| **recording-finalization executor** (single-thread) | CameraEngine | Serial accepted-session/process-token preflight, post-allocation mic/native setup, and checked recorder drain/muxer/native-owner finalization. It dispatches but never performs pending-row allocation; Engine release waits only for current native classification, never for pre/post-native provider work. |
| **recording pre-native allocation** (process-wide two workers + four backlog slots) | CameraEngine / `ProcessRecordingPreNativeAllocator` | Pending MediaStore video-row insert/registration before native setup. Its deadline is armed before dispatch; Stop, pause, release, or timeout retire admission without interrupting the uncancellable Binder call. Late results can only enter bounded cleanup/recovery. |
| **recording-storage dispatcher** (process-wide two workers + eight FIFO backlog slots) | `ProcessRecordingStorageOwner`; CameraEngine owns one admission facade | Frozen post-native extractor validation and MediaStore COMPLETE/publish/delete tails. Admission is non-blocking; overflow/facade shutdown leaves the finalized pending row private for launch recovery. A blocked provider call cannot occupy the REC/native lane, and Engine recreation cannot multiply active workers or queued tails. |
| **recording-teardown watchdog** (scheduled daemon) | CameraEngine | Independent detach-recovery and hard-quarantine deadlines; exactly one strict-finalize or quarantine terminal owner wins and late callbacks are inert. |
| **timelapseScheduler** (scheduled) | CameraEngine | Interval-driven timelapse capture trigger every N seconds. |
| **analysisExecutor** (one single-thread executor per GL generation) | GlPipeline | Histogram/waveform computation from that generation's isolated FBO/readback snapshot. Also runs the pure frame-detail (focus-confidence) metric over the SAME snapshot — a CPU rider, never a second readback, and deliberately WITHOUT the digital-gain display LUT its histogram siblings get, so an optics verdict cannot move with a brightness simulation. It therefore does not compute in modes where no readback runs (video-P, flash-metered P). Retirement invalidates callback authority without waiting indefinitely for old math. |
| **review latest-wins work** (one shared four-daemon pool; two consumers + one conflated pending request per lane) | `MediaReview` / `ProgressiveLatestWorkLane` | Descriptor, thumbnail, full-screen still decode, and synchronous player setup. New identity supersedes pending publication immediately; Back/disposal invalidates exact ownership; late results are released instead of published. The atomic request stage makes a 5 s started-call deadline retryable while only a still-queued request reports poisoned capacity; a completion on that boundary is claimed or disposed exactly once. After synchronous player setup returns, `ExactHandlePrepareOwner` independently bounds `prepareAsync()` on the composition scope and retires/releases only the matching handle. Requests retain application Context only. Published still bitmaps transfer to Compose/GC ownership rather than being recycled while a draw node may still reference them. |
| **audio-capture** (implicit thread) | VideoRecorder | AudioRecord polling loop and PCM-to-AAC encoding. |
| **video-drain** (implicit thread) | VideoRecorder | MediaCodec output buffer draining and MediaMuxer writes. |
| **StandbyAudioMeter** (thread) | CameraEngine | Levels-only AudioRecord tap only while the detailed armed-Video meter is visible, unobscured, and not rolling. A synchronized ownership gate reserves one immutable owner/release latch before thread start; REC opens the mic only after that exact owner releases. Invalid buffer, construction/state, start, thread-launch, and terminal-read failures consume one shared bounded generation budget with backed-off recreation; any successful PCM read resets it and clears unavailable UI. Retries recheck visible intent/paused/REC ownership and typed unavailability is surfaced only after exhaustion. |

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
- **Terminal native acquisition**: `TerminalAcquisitionGate` linearizes GL start, preview binding,
  Camera2 open/deferred-session start, and lifecycle recovery with both engine release and unsafe-
  recorder quarantine. Release or quarantine closes the gate before later acquisition can begin.
  The process-wide quarantine gate gives each actual EGL, Camera2, codec/muxer, or `AudioRecord`
  create/start block a counted admission lease. Native work runs outside the process monitor; close
  rejects new leases immediately and never waits for the native block whose hang caused quarantine.
  A racing admitted call may finish because it cannot be un-called, but its lease is reported
  revoked so the caller cannot publish or clean up after quarantine owns the graph. Drain observation
  is bounded and advisory; owners remain retained process-long if it fails. Recorder setup and
  post-detach finalization each own a separate hard deadline, so late returns are inert without
  restoring the historical process-lock/terminal-gate ABBA. The process-wide flag applies the same
  refusal to a newly created Engine and standby `AudioRecord`.
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
  later on `ioExecutor`; DNG is written synchronously while its RAW Image is still live. A RAW-only
  SINGLE publication tail then transfers to process-finite capacity; overflow settles its family
  continuation exactly once and leaves the complete row to launch recovery. Lightweight
  focal-length, aperture, and equivalent-focal-length metadata for selected physical members is
  prefetched on `setupExecutor`, so callback resolution is cache-only and falls back to selected-route
  metadata without a CameraService lookup.
- **Recording admission and failure**: VideoRecorder owns its video/audio threads and muxer lock; GL
  writes frames to its exactly-once-owned codec input Surface. The caller synchronously reserves the
  in-flight/topology owners and publishes optimistic Starting. The serial recorder executor snapshots
  the accepted controller/session plus process token, then dispatches pending-row insert/registration
  to the process-wide pre-native allocator under an already-armed deadline and yields; it does not
  execute provider work. Stop/pause/release/timeout retire that generation immediately, while a late
  provider return is cleanup/recovery-only. A claimed row re-enters the recorder executor for the
  bounded standby-mic handoff and MediaCodec/MediaMuxer/AudioRecord construction, rechecking exact
  session/process ownership at every edge. Recorder ownership is published atomically against camera
  failure and irreversible quarantine. A stop arriving mid-admission is latched and consumed when the
  attempt publishes or refuses, never raced against an unpublished owner. Publication precedes the asynchronous encoder handoff; EGL prepares a
  candidate surface privately, then installs its in-memory ownership through the process lease or
  destroys the revoked candidate. UI remains in a stoppable
  `isRecordingStarting` state until the first
  successful real encoder swap, so tally/timer never imply a phantom recording. Clean finalization
  releases the Surface only after checked EGL unbind/destroy, before codec release/ownership clear;
  partial setup also releases exactly once. An Android-free coordinator arms both watchdogs before
  submitting detach, starts identity-owned recovery once for a failure/missed callback, and accepts
  only strict resource release as fallback finalization. If detach reaches the hard deadline or GL
  recovery ends ABANDONED, it quarantines Surface, codec, muxer, and fd process-long rather than
  racing release against native code; all later GL/preview/Camera/microphone/REC admission is refused
  with restart status. A codec/audio drain still alive after the bounded join returns a typed
  quarantine-required result without clearing owners or the pending row; strict finalization never
  releases that process lease. After every native owner is checked released, first-wins native
  classification releases the process REC lease, `recorderTeardownInFlight`, and microphone handoff
  immediately. The recorder then hands an immutable capture-specific storage continuation (URI,
  capture id at the Engine callback boundary, container verdict, sample proof, and failure) to a
  process-lifetime bounded recording-storage owner: exactly two daemon workers plus eight FIFO
  backlog slots shared across every Engine generation. Each Engine owns only an admission facade.
  Admission from the serial REC/native lane is non-blocking. Saturation never performs provider work
  inline and never creates a fallback thread; the finalized row stays pending for launch recovery and
  the current capture receives a truthful delayed-save verdict. Facade shutdown rejects new tails
  from that Engine to the same recovery disposition while leaving the process owner alive; already
  accepted tails retain their capture/callback identity and finish without interruption. A
  replacement Engine uses the same worker and queue ceiling. An accepted tail
  validates the one tolerated muxer-stop corner, writes the durable COMPLETE marker before publish,
  fails closed without publish/delete when marker commits exhaust, retains a complete pending row
  when publication fails, and deletes only a structurally incomplete/failed row. The typed storage
  result distinguishes both recoverable pending cases from published success and destructive
  failure. Its latency cannot cause native quarantine or prevent another same-Engine
  recording. Scheduler rejection before detach submission still takes the native fail-closed path.
  Every still/recording admission advances a monotonic capture-id presentation reducer; recording
  storage terminal results carry that id through both review-media and status publication, so a
  completed obsolete tail remains logged/recoverable but cannot display either an old thumbnail or
  an old success/failure verdict over a newer take (including one still recording).
  An active
  Camera2 failure claims the matching recorder, orders GL detach before finalization, reports
  termination, then permits bounded camera recovery. A negative `AudioRecord.read` while running is
  audio-terminal: it stops empty AAC submission, zeroes the live meter, and degrades the take to
  video-only without entering the clip-level first-failure latch. A negative result after stop is
  normal EOS.
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
  recheck current intent and cannot override a newer disable or background transition. Compose DISP
  visibility and modal state explicitly own standby intent. A second process-level owner lease
  admits exactly one standby `AudioRecord` across overlapping Engine/ViewModel generations; only the
  same Engine may atomically hand its standby lease to pending REC, while foreign standby/REC retries
  until strict release. Thus clean/obscured viewfinder modes have
  no hidden 10 Hz microphone/StateFlow work. Exhaustion remains visible until successful PCM or an
  explicit audio-input selection starts a fresh budget.

---

## 180° Flip + Rotation Pipeline

**Why two different rotation approaches:**

The afocal teleconverter's 180° flip must be applied to BOTH the preview and captures. However:
- **Preview** uses texture-coordinate rotation (inverse of image rotation) because GL draws once and the sampled pixels appear rotated.
- **Captures** use pixel-level rotation (direct) because encoded bytes must be rotated in the image buffer itself.

Additionally, **device orientation** from gravity (GyroEis.currentDeviceOrientation) is applied to still captures — SUBTRACTED on the rear, ADDED on the front, because the gravity read is CCW-positive — so a photo framed in landscape saves landscape-correct, even though the UI is portrait-locked.

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
val total = (base - deviceOrientation) % 360   // dev is CCW-positive; FRONT uses (sensor + dev)
// Direct pixel rotation (Matrix.postRotate), no negation
// Example: phone held in COUNTER-CLOCKWISE (left) landscape, teleconverter ON
//   sensorOrientation=90, teleconverter ON, device orientation=90 (CCW-positive gravity read)
//   base  = 90 + 180 = 270°
//   total = 270 − 90 = 180°
// The pre-fix `base + dev` gave 0° here — i.e. every landscape-held rear still saved 180° rotated,
// which is exactly what the device bisect found. Portrait (dev=0) is sign-neutral and hid it.
```

Device orientation (from gravity via `GyroEis.currentDeviceOrientation()`) is applied so a photo framed
while tilting the phone into landscape saves with the correct pixel orientation, matching the visual intent
in the portrait-locked preview (which does not rotate). The rotation functions are unit-tested AND the
held matrix is device-verified (2026-07-25: rear portrait, rear landscape both directions, front
portrait, front landscape — all upright). The muxer orientation hint was the last item open here and
it CLOSED on 2026-07-29: the operator confirmed that clips held portrait and in both landscape
directions all play upright in an external player. Rotation is closed end to end — preview, stills,
and video container.

**Orientation moves no control (2026-08-05).** Handsets are portrait-locked at runtime from
`smallestScreenWidthDp` (`MainActivity.lockPortraitOnHandsets`); large screens are left free, because
Android ignores the lock at sw600dp+ and API 37 removes the opt-out. Either way the layout has ONE
shape — bar along the top, capture cluster along the bottom — and the shutter, gallery and Fn never
move. Only what must be read rotates, in place: text, chips, hints, and the histogram/waveform, via
`overlayRotation`. The angle is the RESIDUAL `deviceOrientation - windowRotation`
(`RotationMath.glyphRotationDegrees`), which is 0 exactly when the window already absorbed the turn,
so a large screen renders upright while a locked handset counter-rotates. `windowFollowsDevice` in
`CameraScreenPolicy` overrides it to 0 on large screens, because both terms hold their last confident
value on independent thresholds when a device lies flat, and two stale numbers subtract into a
confident-looking lie.

**Front camera:** the facing-aware overload `captureRotationDegrees(sensor, tele, device,
frontFacing = true)` uses `(sensorOrientation + deviceOrientation) % 360` — the front term ADDS while
the rear SUBTRACTS, because the GyroEis gravity read is CCW-positive — and the afocal term never
applies (the converter is a rear accessory; the facing door forces TC off). Preview rotation stays 0 on FRONT (the
SurfaceTexture transform carries the front sensor orientation). **Mirror roles are profile-dependent.**
PMA110's front HAL pre-mirrors its SurfaceTexture stream, so preview draws it as-is and
encoder/analysis apply the texcoord x-inversion
(`gl/texCoordQuad` via `mirrorX`) to write the TRUE scene into files and scopes. Every draw role
plus the tap display axis derives from ONE authority, `camera/DeviceProfile.kt`
(`DeviceProfile.frontStreamPreMirrored`), pushed as route state by `GlPipeline.setFrontStreamPreMirrored`
from `applyStabilization`. GENERIC assumes an unmirrored stream, adds the selfie mirror on preview,
and leaves encoder/analysis true-scene. The front capture-ROTATION sign is DEVICE-VERIFIED (2026-07-25): a
landscape-held front still saved upright, and a wrong sign would have rotated BOTH landscape
directions 180°, so one direction settles it. The mirror roles above were separately
device-diagnosed 2026-07-23.

**HEIF (pixel-rotated):**
1. Owned JPEG/YUV snapshot → decode/convert to Bitmap.
2. Bitmap.createBitmap(..., Matrix.postRotate(captureRotationDegrees), ...) → new rotated Bitmap.
3. Compose the shot EXIF APP1 payload and encode HEIF with `HeifWriter.addExifData`.

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
still watchdog all inherit one truth. Independently, the REPEATING (preview) request is capped by
`previewExposureTrade` at `min(PREVIEW_FLUIDITY_MAX_EXPOSURE_NS, PREVIEW_SAFE_MAX_EXPOSURE_NS)`,
because a long repeating exposure stalls the stream and starves session transitions.
`PREVIEW_SAFE_MAX_EXPOSURE_NS` (500 ms) is the outer HAL-safety invariant and always wins; the cap
previews actually ride is the tighter FLUIDITY one (1/15 s — a ≥15 fps finder and ~0.53 s pipeline
lag instead of seconds). The brightness ladder is: exposure up to that cap → ISO while the
advertised headroom lasts (brightness-neutral) → a GL PREVIEW digital gain of up to ×16
(`TradedPreviewExposure.digitalGain`, applied in linear light by the preview/finder draws and by a
256-entry CPU LUT on the analysis snapshot, so zebra/false-colour/peaking/scopes AND the app-side AE
meter all read the SIMULATED still exposure) → honestly darker. Files, the encoder draw, and the
STILL request never see the gain, and once the residual saturates at ×16 the app-side AE loop
freezes its UPWARD motion (`previewBrightnessSimulationSaturated`) because the metered frame can no
longer represent the intent. S/ISO/M previews are therefore brightness-accurate up to that bound but
deliberately NOT noise/motion-blur-WYSIWYG (the alternative is a sub-1 fps viewfinder). The 4 s ceiling was
bisected on the standalone TELE camera only and is applied to EVERY route as a conservative
assumption — a logical-camera bisect is a recorded residual (docs/BACKLOG.md).

**Telephoto detection (CameraSelector2.select):**
- Enumerates all cameras and picks the one with focal length **closest to 70 mm** (not the longest; the 230 mm 10× is ruled out).
- Returns both logical ID (for opening) and physical ID (if it's a sub-camera of a logical multicamera).
- **Key insight**: Prefer **physicalId == null** (standalone camera) over routing to a physical sub-camera via setPhysicalCameraId(). Routing crashes the QTI HAL.

**Shipping session fallback plan (`CameraController.configureSession`):**

For non-SDR VIDEO, `tenBitSessionWanted(videoMode, transfer)` requests HLG10. Attempt 0 is a
video-only rung: both processed and RAW still readers are absent because combining HLG10 with the
full-resolution still readers crashes this HAL. Photo and SDR video use the standard 8-bit session.
`hlgConfigured` records accepted session truth after fallback, and GL uses that truth to select the
HLG or BT.1886 source decode; requested transfer alone is not enough to describe the incoming buffer.

- A logical photo session uses preview + `YUV_420_888` processed stills. It never requests RAW because
  RAW and the logical-camera still configuration destabilize this HAL.
- A standalone rear-photo session starts with preview + HAL JPEG and adds RAW whenever DNG intent
  selected that route and the camera advertises it; DNG is not TELE-only. Fallback drops RAW before
  dropping the processed-still stream,
  and preview-only is the final stream plan.
- When the hi-res still is admitted (`hiResAdmitted` — capability-gated, standalone-only; dormant on
  PMA110), `sessionAttemptPlan` PREPENDS a hi-res rung: attempt 0 is the hi-res variant of the full
  plan with **RAW forced off** (a full-sensor blob plus RAW is exactly the over-demanding stream
  combo this HAL punishes), and every later attempt maps onto the ORDINARY ladder shifted by one —
  a rejected hi-res combo therefore falls back to full-WITH-RAW before anything else degrades.
  `maxSessionAttempt(tele, wantHiRes)` stretches the exhaustion bound by one when hi-res is wanted.
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
sessions, and keeps session-source precision separate from release EGL precision and encoded profile.

**Hi-res remosaic stills (capability-gated; DORMANT on PMA110):** `CaptureCapabilities` resolves a
full-sensor still size from the standard `ULTRA_HIGH_RESOLUTION_SENSOR` path (largest JPEG in the
maximum-resolution stream map, with `hiResUsesMaxResolutionMode` requiring
`SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` on the still request) or from a vendor-exposed remosaic size
in the regular map (`pickVendorHiResSize`). Admission is ONE predicate (`hiResAdmitted`,
re-resolved at every optics door); a ProSheet Shoot-tab toggle appears only when the route
advertises a size, and the `HR` OSD tag keys on ACCEPTED session truth —
`acceptedPhotoSessionOutputs(processed, raw, hiRes)` reports hi-res only when the processed reader
that survived configure is the full-sensor one. Same-route fast commits compare the resolved intent
against the CONFIGURED intent (`AcceptedCameraSession.hiResConfigured`), so a ladder-dropped hi-res
does not force a full ~0.5 s reconfigure on every later fast door. The saved still takes
`StillCapturePipeline`'s passthrough-JPEG lane (bytes verbatim, EXIF orientation TAG only — no
200MP decode); RAW is mutually exclusive with hi-res on the session plan. PMA110 exposes none of
this to third-party Camera2 (probed 2026-07-22 — see CLAUDE.md), so on the target device the
toggle never appears and the ladder runs without the hi-res rung.

**Auto-exposure frame-rate policy:**

Photo AUTO uses `CameraCaps.autoFpsRange()`, whose low floor lets AE extend exposure in dim scenes.
Video AUTO sets `pinAutoFps=true` so a selected 29.97 cadence cannot fall to 25 fps in low light.
App-side and manual exposure also pin the selected frame rate.

**Tap-to-focus (region AF):**

Continuous AF mode (`AF_MODE_CONTINUOUS_PICTURE`) with a bare trigger holds the current (often incorrect) 
focus distance. Instead, tapping a region sets a metering/AF region and forces a one-shot `AF_MODE_AUTO` 
scan that **locks** the focus on the tapped point (`touchAfActive` flag). `AF HOLD` is published only after
Camera2 accepts the replacement repeating request for the exact accepted session.
Rapid taps are mapped against the loupe center visible at input time and coalesced latest-wins. AF UI
telemetry comes from that repeating request, not its CANCEL/START frames; unlocking AF Lock re-arms one
scan when a tap point is still held. The lock and tap-owned loupe center are released by a replacing tap,
a focus-mode change, the explicit reset action, or any optics-remap door. Retained-route remaps fold the
reset into their single request update; structural remaps clear ownership without rebuilding a doomed
session. The 2 s reticle timer hides only the reticle and does not release the hold. AF state reaches
FOCUSED on device.

---

## Zoom & the Hybrid Camera Routes (2026-07-14)

Which camera is open depends on the resolved route inputs — including RAW intent — not merely mode
or the visible lens choice (`CameraEngine.resolveNonTeleId`):

| State | Camera | Zoom semantics |
|---|---|---|
| Photo, TC off, processed only | **Logical camera 0** (physIds 3/2/4/5) | Unified main-relative 0.6–20×; the HAL crosses physical lenses internally (seamless pinch, no reopen). Lens picks = zoom presets; chip highlight follows `LensChoice.forZoom`. |
| Photo, TC off, RAW/DNG wanted | **Standalone rear lens** matching the selected band | Lens-local zoom; wanting RAW is a route input because the logical path cannot safely carry it. DNG is not TELE-only. Lens changes reopen and route-scale conversion follows the standalone home. |
| Video, TC off | **Standalone lens** matching the band | Lens-local 1–10× digital; lens changes reopen. The logical camera's EIS (Standard AND Active) leaks its uncorrected warp margin (~6% of width) into the stream — preview AND recorded file — so video must not live there. |
| TC on (any mode) | **Standalone 3× (camera 4)** | Lens-local 1–10×; afocal 180° flip; RAW/DNG is offered only when that standalone session advertises RAW. |
| FRONT (any mode) | **Front camera** (`pickFront`, expected id 1 — never hardcoded) | Lens-local zoom; one camera in both modes. Entering forces TC off and rear recalls exit FRONT. `FrontMirrorConvention` derives preview/file/metering roles from the route's `DeviceProfile`: PMA110 pre-mirror and GENERIC unmirrored paths each keep selfie preview plus true-scene files. |

`setVideoMode` remaps the zoom value between the unified and lens-local scales so framing carries
across a mode flip (mirrored into UI state by `onModeChange`); while FRONT that remap is identity
(`remapModeOptics(frontFacing)` — front zoom is lens-local in both modes and the retained rear
band must survive the trip).

TELE and FRONT transitions also cross this boundary through shared pure policies. Engine and
ViewModel both consume `resolveTeleZoomTransition`, whose pre-TELE snapshot is canonical unified
framing and whose exit converts through the target non-TELE route plus the physical optical
inventory. FRONT likewise stores canonical unified rear framing rather than a raw ratio tagged as
Photo/Video; `rearReturnZoom` converts it when returning, so a mode change while FRONT — including
Video ↔ Photo+DNG — cannot reinterpret a main-relative preset as standalone digital zoom. A
crop-only 3× band therefore remains local 3× on its physical 1× lens, while PMA110's optical 3×
home remains local 1×.

**Zoom application pipeline** (why it's smooth): pinch/dial events are COALESCED in the ViewModel
(leading apply + 16 ms trailing flush of the newest value, ~60 Hz — per-event application recomposed the
whole tree at input rate). Every compounding input (pinch factor, hardware-key step, ease ticker)
bases itself on `currentZoomBase()` — the coalesced PENDING value, not UI state, which lags a
flush window; compounding against the stale state made zoom crawl-then-jump. The flushed value
takes the controller **fast path** (`CameraController.setZoomRatio`): the cached repeating-request
builder gets only its zoom keys mutated and resubmitted — no full request re-derivation.
Scale-remap invalidation covers BOTH pending inputs: every remap door calls
`invalidateOpticsDerivedState()` → `ZoomGlideState.invalidateForRemap()`, clearing
`ZoomGlideState.pendingRatio` AND nulling `ZoomGlideState.easeTarget` (a hardware-slider glide
target is an absolute number in the OLD scale; surviving a remap it eased toward an un-commanded
framing). Structural reopens start with a fresh boost-free controller; same-route commits call
`commitRetainedOpticsControls`, whose pure `retainedOpticsApplyPlan` folds the exact packet plus
boost removal into one camera-thread request update (a full rebuild only when the FPS pin must be
removed). Each zoom
gesture EDGE costs one repeating-request swap: `setZoomInteraction` folds the current/final exact
ratio into the fps-boost flip's own rebuild (`setSmoothPreviewBoost(active, finalZoom)`), instead
of the old rebuild-then-correct pair that transiently re-submitted the stale mid-gesture wide-aimed
ratio. The engine's zoom read-modify-write on `controls` shares the packet writers' monitor (as
does `setControls` — every wholesale `controls` writer holds the engine monitor), and
`onZoomResult → gl.setHalZoom` forwarding is change-gated with a per-rebuild reset. The
submit decision itself is the pure `resolveHalZoomSubmit` (`camera/ZoomSubmitPlan.kt`, unit-tested).
**Since 2026-07-27 a MOVING gesture submits NOTHING** (`submitNow = !interactionActive`): device
measurement showed each swap stalls this HAL 210–413 ms, and spacing them out did not help — submits
already ~400 ms apart stalled identically, because the stall belongs to the swap itself rather than
to how tightly swaps are packed. A gesture therefore costs TWO swaps, one per edge, and the START
edge carries the wide aim (it used to ride the mid-gesture submits and is the only thing pre-buying
the field the GL crop needs to zoom out). An injected two-finger pinch measured zero submits and
zero frame gaps while the fingers move. Two further additions keep captures WYSIWYG: the controller stores the EXACT requested
ratio for still requests (`setZoomRatio(halRatio, requestRatio)` — a still must never inherit the
mid-gesture ~1.2×-wide aim), and a QUIET-WINDOW landing (`landExactZoom`, ~250 ms after the last
flush) lands the exact ratio on the HAL well before the 700 ms fps-boost tail ends, so a recorded
clip stops carrying the wide framing after finger-up. Scale-remap invalidation of
`ZoomGlideState.pendingRatio`/`.easeTarget` (via `invalidateOpticsDerivedState()`) covers ALL the remap doors: `onModeChange`,
`onToggleTeleconverter`, `onLens`, `onToggleFrontCamera`, `onStop`, **`onOpticsRollback`,
`applyLoaded` (settings/MR recall), and the debug `onCameraOverride`** — the last three were the
doors 6affe20 originally missed. The glide's per-tick math is the pure `zoomEaseStep` (`ui/ZoomMath.kt`, unit-tested).

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

### Loupe Overview (same-stream assist)

The `Loupe Overview` Assist toggle (default OFF, persisted) draws a bottom-left corner viewport re-drawing the FULL
current camera frame while the main view is magnified, with an iPhone-style rectangle inside it
marking WHERE the magnified view is pointing (`loupeHintRect` in `CameraState.kt`, pure and
unit-tested; drawn as four scissored clears so the hint needs no shader, no VBO and no texture unit
and cannot perturb renderer state the preview and encoder share). Its size is the SAME
`(1 - crop) / zoomComp` the main draw received, so it cannot claim a framing the view does not have
— device-measured at 39.5% of the overview against the 40% `PUNCH_IN_CROP` implies. Its placement
sign was device-bisected on the converter route, where the afocal correction is 180°, so a centred
loupe is unaffected and only an off-centre tap exposes an error.
Since the row is a sub-option of the loupe, the Assist toggle is DISABLED while the loupe is off
(same shape as Zebra Level under Zebra) — ungated it reported "On" while provably drawing nothing,
with its parent switch under a different section header. **Single-stream honesty**: the HAL's
`CONTROL_ZOOM_RATIO` crop is baked into the one camera texture, so the PIP can only be wider than
the main view while GL zoom compensation (mid-gesture) or punch-in magnifies past the delivered
field. This is deliberately not labeled PIP or 1x; a true unzoomed/wide finder is a BACKLOG design
item (second stream or HAL-zoom-cap split).
Gating is ONE shared, unit-tested predicate (`teleFinderResolved`/`teleFinderVisible` in
`CameraState.kt`): toggle + ACTIVE punch-in + (TELE or unified zoom >=
`FINDER_MIN_ZOOM = 3f`). Photo additionally requires 4:3 because 16:9's AspectMask would
dim/misframe the corner box; Video ignores the unrelated still-aspect setting, which once made the
overlay appear or vanish mid-clip. A mounted converter qualifies at any zoom. Without one, the 3x
floor prevents a steady ordinary-zoom overview from duplicating the main view ~1:1; the single
stream means the PIP is only genuinely wider while the loupe magnifies past the delivered frame.
GL applies the same axis at draw via its own punch-in state. The engine resolves the flag in one place (`pushTeleFinder` —
re-pushed synchronously on toggle/aspect/lens-TC/mode/session-config AND on `rollbackOptics` via
`applyStabilization`, with self-contained pushes in `setVideoMode`/`setResolvedOptics`), stores it
in `RendererConfig` for GL-generation replay, and geometry flows from ONE pure seam (`finderRect`)
shared by the GL scissor box and the Compose border so both stay pixel-aligned (RTL-safe absolute
anchor). The GL draw is failure-isolated (`runCatching` + `try/finally { glDisable(GL_SCISSOR_TEST) }`
— scissor is CONTEXT state; a leak would clip the encoder/analysis draws, and a finder-only error
must never fail preview health). A compact `OVERVIEW` OSD tag appears only while the same-stream
viewport is actually visible.

This is not the requested always-on 1x finder for 3x/10x/TELE. PMA110 advertises only `[0,1]` as a
concurrent-camera set, so separate rear-device pairs `2+0`, `2+4`, and `2+5` are unavailable. The
API-35 metadata-only `CameraDeviceSetup` query reports that logical camera 0 can theoretically carry
deferred PRIVATE pairs `2+4` and `2+5` at 640x480+640x480 and 640x480+1920x1440. That query opens no
camera and configures no session; it does not overrule the device-observed QTI crash when routed
physical outputs reach `configureStreams`. A true 1x finder therefore remains gated behind an
explicit, isolated HAL-risk experiment rather than shipping on the optimistic query result.

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

The dormant motion-inversion detector owns explicit evidence epochs. Every optics door resets its
confidence and re-publishes the detector even for an `armed=true -> armed=true` transition. That
single boundary clears `GyroEis` timestamp history, advances the atomically replayed
`RendererAssists` epoch, and clears the GL predecessor-frame state. `GlPipeline` publishes the new
epoch synchronously before its handler command, so an old analysis-executor result cannot publish or
reseed history while that command is queued. A replacement GL generation receives the complete
armed/provider/epoch record from `replayAll`; no motion pair can span an optics or lifecycle epoch.

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

Supported codecs are scanned at runtime via `EncoderCaps.kt` (MediaCodecList). Exact components are
ranked hardware-first while registry order is preserved, with software fallback retained. Bitrate presets run Low → **Max** (`BitrateLevel`): the REQUESTED target at
Max computes to ~99 Mbps at 4K30 (0.40 bpp), hard-clamped at 120 Mbps (`videoBitRate`). A device
recording measured ~134 Mbps in the file — that is VBR encoder overshoot of the ~99 Mbps target (no
KEY_BITRATE_MODE is set), not a requested ceiling. The old High (0.16 bpp) left half the HW headroom
unused.

| Codec | Encoder profile | Color Space | Transfer | Container | Notes |
|---|---|---|---|---|---|
| HEVC (H.265) | Main10 profile (SDR: Main) | Rec.2020 (SDR: Rec.709) | HLG / S-Log3 / S-Log3.Cine / LogC3 / SDR | MP4 | Primary HW encoder. Non-SDR video first requests an HLG10 Camera2 source; release EGL remains 8-bit. Main/Main10 names the encoded output, not every upstream stage. |
| AVC (H.264) | 8-bit | Rec.709 | SDR | MP4 | Fallback; forces GL SDR (no HLG/Log); HW. |
| APV | — | — | — | — | HW `c2.qti.apv.encoder` (pro all-intra ≤2 Gbps) EXISTS but **gated out** — MediaMuxer rejects APV-in-MP4 (breaks the encoder mid-drain). |

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

**Main10 encoded-output profiles:**

HEVC Main10 profile → MediaCodec configured with:
```kotlin
ColorProfiles.videoFormat(
    codec=VideoCodec.HEVC,
    width, height, fps, bitRate,
    transfer=ColorTransfer.HLG or a log profile (SLOG3 / SLOG3_CINE / LOGC3)
)
// Sets:
//   MediaFormat.KEY_MIME = "video/hevc"
//   MediaFormat.KEY_PROFILE = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
//   MediaFormat.KEY_BIT_RATE = bitRate
//   MediaFormat.KEY_COLOR_STANDARD = MediaFormat.COLOR_STANDARD_BT2020  (Rec.2020)
//   MediaFormat.KEY_COLOR_TRANSFER = COLOR_TRANSFER_HLG or COLOR_TRANSFER_SDR_VIDEO (log profiles)
//   MediaFormat.KEY_COLOR_RANGE = LIMITED (HLG) or FULL (log profiles)
```

**Shipping pipeline, stage by stage:**

```kotlin
// Release CameraEngine intentionally calls gl.start(tenBit = false)
EGL_RED_SIZE = 8, EGL_GREEN_SIZE = 8, EGL_BLUE_SIZE = 8, EGL_ALPHA_SIZE = 8
EGL_RECORDABLE_ANDROID = EGL_TRUE
```

Color-profile rendering happens in the fragment shader:
- **Camera2 session**: non-SDR VIDEO first requests HLG10 with no still readers. Photo and SDR
  video request the standard 8-bit profile. The accepted fallback rung, not intent, is authoritative.
- **Input**: normalized [0, 1] RGBA sampled from the camera SurfaceTexture. It is HLG-encoded when
  the accepted session reports HLG and BT.1886/SDR-encoded otherwise. In both cases it is the ISP's
  display-referred, already tone-mapped rendition, not a RAW or scene-linear video source.
- **Source decode**: `uSourceHlg` follows accepted `hlgConfigured`. HLG input takes inverse HLG OETF
  plus inverse reference-white scaling; standard input takes BT.1886 2.4 decode. Both arrive at the
  common display-light scale used by the subsequent gamut matrix and OETF.
- **Transfer mapping / OETF**:
  - **HLG (Hybrid Log-Gamma)**: simplified display-referred mapping from ITU-R BT.2408-9
    §5.1.3.4: source-aware display-light decode → linear BT.709-to-BT.2020 conversion →
    normalized inverse-OOTF/reference-white scale → BT.2100 HLG OETF. SDR reference white maps to 75% HLG.
    This preserves a valid HLG signal but cannot recover highlights removed by the ISP's SDR tone map.
    CPU/shader anchors are host-tested; final appearance after this mapping still requires playback on
    a real HDR display and is not inferred from compilation or container tags.
  - **Log profiles (SLOG3 / SLOG3_CINE / LOGC3)**: standard curves applied to the display-referred,
    tone-mapped stream — source-aware decode → linear BT.709→gamut 3×3 matrix (S-Gamut3, S-Gamut3.Cine, or
    ARRI Wide Gamut 3) → defensive lower clamp → the S-Log3 or LogC3 EI800 OETF (constants
    single-sourced from `LogProfiles.kt`). Grades with standard S-Log3/LogC3 workflows, but the
    source is the ISP's tone-mapped display rendition, so there is no scene latitude to recover — this is NOT
    scene-referred camera log (HAL-native log is vendor-gated — see CLAUDE.md). Replaced the
    former GL O-Log2 option (persisted "LOG" migrates to SLOG3_CINE).
  - **SDR**: no shader curve; HEVC Main 8-bit BT.709 limited-range for zero-grading footage.
- **EGL output**: release builds render through an 8-bit RGBA EGL config. The optional RGBA1010102
  config remains debug/experimental and is not part of the release claim.
- **Encoded output**: HEVC uses Main10 with the selected Rec.2020 HLG/log tags for non-SDR, or Main
  with BT.709 SDR tags for SDR. Main10 is real file/codec output metadata and precision, but the
  8-bit release EGL stage means it is not an end-to-end 10-bit processing claim.

**Fragment shader (Shaders.kt):**
```glsl
// Pseudocode
vec3 color = dgain(texture(camera, uv).rgb);  // display-referred encoded source [0, 1]
// dgain = cycle-8 preview brightness simulation: BT.1886 decode -> ×uDigitalGain (linear) ->
// clamp -> re-encode, PREVIEW draw only (encoder/analysis pass 1.0). HONESTY: linear values past
// 1.0 CLIP at white — the simulation cannot show the highlight roll-off a true long exposure
// would produce (same class of honesty note as the HLG mapping: nothing above the ISP's SDR
// white exists to recover). Zebra/false-color/peaking and the CPU-LUT'd scopes/AE histogram read
// the same simulated signal; files and stills never do.
vec3 linear = sourceLinear(color);  // inverse HLG + scale, or BT.1886, from accepted session truth
if (transfer == HLG) {
    color = hlgOetf(inverseHlgOotf(toBt2020(linear) * referenceWhiteScale));
} else if (transfer == SLOG3) {
    color = slog3(gamutFloor(toSGamut3(linear)));
} else if (transfer == SLOG3_CINE) {
    color = slog3(gamutFloor(toSGamut3Cine(linear)));
} else if (transfer == LOGC3) {
    color = logc3(gamutFloor(toAwg3(linear)));
}
// Release EGL output precision remains 8-bit for stability
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

Captured via AudioRecord on a separate thread. Software PCM gain is normalized to 0×..2× at
persistence, engine, recorder-admission, and PCM boundaries (1× passthrough). AAC LC encoder. Live
RMS is throttled to ~10 Hz only while recording or while the visible detailed standby meter owns it.
Every AAC setup degradation publishes the selected route as unavailable before continuing video-only;
standby exhaustion does likewise until successful PCM or explicit route intent. The UI sets
`Starting...` before engine callbacks so that terminal label is not overwritten.

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
   - Compose one shot-owned EXIF snapshot, encode it into a cache-only JPEG seed, extract its APP1
     payload, and pass that payload to `HeifWriter.addExifData`.
   - HeifCapture.writeHeif(ParcelFileDescriptor, Bitmap, exifData) → HEIF-encoded bytes with
     exposure/lens metadata matching the JPEG sibling.
4. MediaStore: create `IS_PENDING` + journal `REGISTERED` → write/close → journal `COMPLETE`
   → publish only after durable commit. Marker exhaustion leaves `IS_PENDING=1` and skips both
   publish and delete; a publish-only failure also remains pending for recovery.

**JPEG (still photo):**

JPEG runs the SAME processed-pixel pipeline as HEIF (`StillCapturePipeline.saveProcessedStills`): decode the ImageReader bytes →
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
3. MediaStore: create `REGISTERED` pending row → write/close → attempt to mark `COMPLETE`; return
   a `PendingDngPublication` carrying marker durability from the camera callback. RAW-only SINGLE
   shots call `publishDng` through the process-wide two-worker/two-backlog still-publication owner;
   mixed-output and sequence drives preserve their existing `ioExecutor` ordering. If marking,
   admission, queueing, or publication fails, the row stays private and the live family owner is
   settled exactly once; launch recovery adopts it only after DNG structural validation instead of
   deleting it.

**Last-capture review ownership:**

At shutter/record admission, every capture receives a monotonic engine id plus one versioned,
millisecond-timestamped `CaptureFamilyKey`. Every HEIF/JPEG/DNG sibling reuses the same still-family
key and filename stem; video owns one canonical MP4 family. The Engine publishes that identity to
the tracker before Camera2 can return a still and before MediaStore video allocation begins. Within the running process,
`CaptureOutputTracker` groups URIs by the monotonic id and orders review ownership by newest id first,
then by displayability inside that capture. A newer DNG-only completion therefore owns a truthful RAW
metadata placeholder instead of leaving an older thumbnail visible. A processed sibling from the same
capture upgrades the placeholder; RAW arriving after processed, or any output from an older capture,
is tracked for deletion but cannot displace the review owner. A late sibling whose capture id the
tracker's own bounded trim evicts DURING its `record()` call is re-checked after the trim and
demoted to track-only — an evicted family must never become the review owner (its URI could not be
pinned and delete would silently degrade to file-only).

Recording storage applies one earlier capture-owned gate before entering that tracker: any admitted
newer capture (recording or still, including an in-REC snapshot) suppresses both review publication
and transient storage status from older recording tails. The old file remains published or pending
according to its own provider result and is logged by capture id; suppression is presentation-only.
This keeps the review surface and save verdict on the same take even when provider tails finish out
of order.

On relaunch, `MediaStoreWriter.latestOwnCapture` independently queries bounded sets of this package's
published rows under `DCIM/TeleCamPro` from Images and Video. A failure in one collection does not discard
valid rows from the other. The Android-free reducer chooses the newest capture
before applying sibling display preference; an exact, bounded filename query then reconstructs every
extant row for the winning canonical family. That family is seeded into the tracker with a synthetic
ordering id below every live id, so even a racing first capture wins. Names from older app versions do
not prove sibling identity and are never grouped by timestamp proximity: they restore with an explicit
file-only delete scope. Opening review pins the frozen URI's exact family outside ordinary bounded
history; if pinning fails, delete copy remains file-only. Closing releases the pin, while deletion
consumes it with the family. Capture-family copy promises all known formats, while legacy copy promises
only the displayed file. Whole-family deletion synchronously publishes only the in-memory tombstone;
the ordered I/O lane must durably commit the bounded family marker before UI acknowledgement or
provider deletion. Only a live still id enters the retained-late-sibling gate, so deleting video or
restored media cannot poison still admission. Delete then attempts every known sibling and immediately
rejects a late callback through durable DISCARD ownership. Provider disposal of that completed late
still enters one process-lifetime two-worker/eight-backlog dispatcher through the Engine's closeable
facade; overflow or Engine shutdown never falls back to inline ContentResolver work, because the
durable family tombstone already owns retry at the next launch. If only some resolver
deletions succeed, `restoreDeleteSurvivors` reconstructs the surviving subset under the original
capture id and restores its best review owner with explicit retry copy; only a survivor that cannot be
restored falls back to a Gallery retry message. RAW remains metadata-only in review; no Bayer decoding
is implied.

**Aspect ratio (processed stills):**

```kotlin
data class AspectRatio(val w: Int, val h: Int) {
    W4_3(4, 3),      // Full sensor (no crop, default, the no-crop sentinel)
    W16_9(16, 9)     // Center crop of 4:3 to 16:9 landscape
}
// centerCropBox(srcW, srcH, w, h)
// Computes the largest centered w:h source rectangle. StillCapturePipeline applies that rectangle
// and capture rotation together in one Bitmap.createBitmap call, avoiding a second full-size bitmap.
```

The sensor is 4:3-native; `W4_3` is full readout, and `W16_9` is its center crop. 
DNG always saves full-frame (crop not applied).

**MediaStore scoped storage (MediaStoreWriter):**

```kotlin
createPendingImage(context, fileName, mimeType) → Uri
// Creates DCIM/TeleCamPro IS_PENDING = 1, then durably journals REGISTERED
openParcelFd(context, uri, "rw") → ParcelFileDescriptor
// Caller writes to the FD
markWriteComplete(context, uri)
// Boundedly journals COMPLETE after bytes/container metadata close; returns durable result/attempts
publishCompletedOutput(markerDurable) { publish(context, uri) }
// Publishes only with durable COMPLETE; otherwise returns retained-marker-unavailable without delete
publish(context, uri)
// Updates IS_PENDING = 0 (visible in gallery), then clears the journal entry
delete(context, uri)
// Removes an incomplete/proven-invalid entry, then clears confirmed deletion from the journal
discardRejectedOutput(context, uri)
// Durable DISCARD + delete for a rejected output; unresolved double-failures are process-bounded
cleanupOrphanedPending(context)
// → RecoveryReport; family/discard batches are bounded, ADOPT valid, DELETE rejected/proven-invalid
latestOwnCapture(context) → RestoredCapture
// Bounded Images + Video scan, followed by an exact-family query when identity is proven
```

Canonical names also stamp `DATE_TAKEN` from admission time. Relaunch recovery probes JPEG, video,
DNG, and HEIF terminal structure while the row is still private. HEIF proof walks bounded top-level
ISO-BMFF headers and requires `ftyp`, one bounded `meta`, a matching primary item in supported
`pitm`/`iloc`, and every explicit nonzero extent wholly inside an `mdat` payload. Unknown versions,
external references, unbounded boxes, and parser-limit cases remain pending; malformed/missing/
out-of-range required metadata is invalid. A `COMPLETE` journal record always authorizes adoption; legacy or
`REGISTERED` rows are adopted only when structurally valid, deleted only when definitively invalid,
and otherwise retained with an explicit report. One process-wide recovery lane runs a bounded
family/rejected-output preflight once, advances Images and Video independently through 64-row
pending-only pages while retaining exact durable DISCARD-owned rows, then gives terminal deletion
to the durable DISCARD journal through independent 64-entry lexicographic pages. Provider failures
retry boundedly. Exhausting one DISCARD page retains its
markers and advances so a permanently bad URI cannot starve later deletion markers; the terminal
report preserves every exhausted failure class. Replacement Engines coalesce as subscribers rather
than starting more workers. Completion then queries the latest published family, preserving partial
bytes privately without turning a transient publication failure into data loss.

---

## Pro Controls Surface

**Overview:**
Core Camera2 capture parameters are housed in the immutable `ManualControls` data class. The ViewModel
copies it with updated fields on each interaction and re-applies it through
`CameraEngine.setControls()`. Restored and live controls pass through the same route normalization for
exact advertised modes, focus/ISO/exposure/EV bounds, region maxima, and zoom bounds. Same-route recall commits
that normalized packet before Ready; a route-changing recall waits for the target camera's caps.
The UI's `ControlAvailability` projection uses those same facts to filter settings and quick cycles,
and to admit or close manual rulers. AE/AF regions are omitted independently when the corresponding
maximum count is zero. Capture-mode, video, assist, hardware-key, and persistence options live in
`CameraUiState`/`ExtraSettings` rather than being forced into `ManualControls`.

Settings are persisted across app launches via `SettingsStore.kt` (SharedPreferences), gated by a 
"Remember Settings" toggle that **defaults ON**. On launch, saved pro settings are restored from storage 
and pushed to the engine before the camera starts. Fresh installs open on the 1× main lens with TELE
off; separate default-on Setup toggles decide whether the saved lens and TELE state are restored. Enums
are stored by name for forward compatibility. Loads are total: unknown enums revert to defaults;
non-finite/range-invalid shutter angle, custom-WB gains, zoom/focus, and audio gain are normalized;
Photo/Video/My Menu Fn lists are distinct, capped at eight, and use their mode fallback when empty.

**UI layout (ProSheet.kt):**

The fixed settings panel has nine left-rail tabs:

1. **My** — operator-selected shortcuts.
2. **Shoot** — HEIF/JPEG/DNG selection, aspect, zoom, Still Quality, drive/interval, self-timer,
   MR save/recall, and the hi-res still toggle (visible only when the route advertises a hi-res
   size; dormant on PMA110).
3. **Exposure** — PASM-like mode, AE lock, flicker, shutter mode/step, ISO, metering, WB/custom WB,
   and AWB lock. EV remains on the quick Fn surface.
4. **Focus** — AF/MF mode, tap-AF spot size/lock, and peaking level/color. Manual focus distance
   remains on the quick Fn dial rather than this tab.
5. **Lens** — 0.6x/1x/3x/10x selection, TELE mode, stabilization mode, and OIS.
6. **Video** — codec, transfer, resolution, FPS, bitrate, Open Gate, and audio.
7. **Image** — edge sharpness, noise reduction, and color-effect processing.
8. **Assist** — gamma display assist, frame lines, zebra, false color, scopes, grid, level, punch-in, and Loupe Overview.
9. **Setup** — privacy, persistence, Fn/My Menu customization, hardware-key assignments, and the
   diagnostic camera override reset when one is active.

The rail is a Compose `selectableGroup`; each category is a `selectable` `Role.Tab` with the current
category's selected state. Its existing 48 dp-plus geometry and visual treatment remain unchanged.

**Quick Fn controls (ManualDials.kt):**

Photo and video have separate configurable Fn bars with up to eight slots. The photo default exposes
exposure mode, focus, shutter, ISO, WB, and EV; the video default adds gamma, stabilization, and audio
scene choices. Capability-dependent taps cycle only through the selected route's advertised choices.
The shooting-screen Fn overlay renders only that active mode's distinct configured slots (maximum
eight, with its mode-specific default as the empty fallback); it never merges My Menu or Recent.
Portrait uses a raw bottom 4x2 grid. Held 90/270 use raw 2x4 Start/End trays whose slot reordering
produces the same physical bottom 4x2 reading order, with one-line visual ellipsis under constraint.
The portrait panel scrolls when window/font constraints require it. Every tile merges its full label,
value, enabled state, and click action into one accessibility node; the touch-only scrim is unnamed
and the explicit Close target is the sole close action.
The WB chip can open the preset sheet whenever more than one advertised mode exists; only MANUAL WB
requires the Kelvin ruler. Compact view keeps a 36 dp visual Fn button inside a 48 dp hit target on
the focal rail; `DISP` reveals the full configurable strip. In the Fn grid, numeric
focus/shutter/ISO/WB/EV/zoom tiles open their ruler instead of cycling or resetting a value. Those
numeric/sheet transitions dismiss Fn; quick cycles/toggles update in place for consecutive setup.
Only the requested ruler remains visible, with an explicit close control. A caps change closes an
invalid open ruler while keeping the normalized value applied.

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
- Kotlin / Compose compiler 2.4.10, AGP 9.3.1, Gradle 9.7.1
- Android SDK Platform / compileSdk 37; targetSdk 36 / minSdk 33 (API 36 is Android 16; API 33 is Android 13, the floor since the 2026-08-01 multi-device decision)
- SDK Build Tools 36.0.0 (the AGP 9.3 default); compile and runtime API levels are intentionally decoupled
- JDK 21 required; set JAVA_HOME for CLI builds
- Compose BOM 2026.08.00

**Build:**
```bash
python3 tools/verify_host.py
python3 tools/build_immutable_debug.py
python3 tools/build_immutable_release.py :app:lintRelease :app:assembleRelease :app:bundleRelease
```

`python3 tools/verify_host.py` is the authoritative non-device host gate. It owns the debug build,
JVM/Robolectric/Compose tests, lint, exact coverage contract, release/tool tests, coverage-tool
tests, device-harness self-tests, documentation contracts, Python compilation, and diff checks.
Individual Gradle tasks are focused developer subsets, not a repository-wide green result.

Device evidence must use the APK printed by `tools/build_immutable_debug.py`. The wrapper freezes
the current clean or dirty debug APK inputs into one private worktree, then both compilation and the
embedded `telecam-debug-provenance/source.manifest` consume that owner. Its manifest carries
`source_owner=immutable-debug-worktree-v1`. Ordinary `assembleDebug` remains the fast developer
build and carries `source_owner=mutable-development-worktree`; the device harness rejects that
marker as non-evidence-grade instead of trying to prove a post-hash mutable compile.

Release compilation, resources, shrinking, and packaging run inside a private checkout of the exact
clean HEAD recorded in `telecam-release-provenance/source.properties`. The generated asset namespace
is exact, and the upload checker rejects any extra member. Gradle records neutral clean-source
identity (`schema=2`, `evidence=external-wrapper-required`) but never authenticates its caller:
legacy `immutableRelease*` properties are rejected because an unsigned same-user path/nonce record
cannot prove wrapper origin. Direct Gradle release outputs remain developer-only.

The outer wrapper is the evidence boundary. It no-follow exports the exact clean commit, copies and
seals the normalized repository-relative signing inputs, verifies source and owner identity again
after Gradle returns, freezes every allowlisted output, and atomically publishes exactly one direct,
non-empty child of
`app/build/immutable-release`. Its `release-evidence.json` records the verified commit/tree and exact
output size/hash set. Upload attestation schema 2 must name that receipt; the checker requires the
candidate AAB to be the receipt's unique matching bundle output, so copying an ordinary mutable
Gradle artifact cannot accidentally become upload evidence. The checker no-follow snapshots the
attestation and its checksum sidecar once, hashes and parses those captured bytes, and revalidates
both source identities before success, matching the AAB/evidence mutation boundary. This is mutation
and operator-path protection, not authentication against a malicious process already running as the same OS user.
`TELECAMPRO_STORE_FILE` is cleared and unsupported; environment values remain valid only for
alias/password fields, which cannot redirect Gradle to another file.

**Focused Android test subset:** `app/src/test/` is the JVM/Robolectric/Compose source of truth. Run
`./gradlew :app:testDebugUnitTest` while iterating on that surface, then run the authoritative
`python3 tools/verify_host.py` gate before delivery. Do not copy a mutable test/class count into
documentation.

**Tracked QA behavior contract:** The ignored local `.claude/agents/qa-adversary.md` runbook implements
this contract. A device run must receive the current session's `ANDROID_SERIAL`, derive application id
and launch activity from the built APK, and respect any no-deployment directive. Default photo starts on
the logical back camera at 1× / 23 mm with TELE off; TELE pins the standalone 3× camera; captures publish
through MediaStore under `DCIM/TeleCamPro`; exposure modes are P, S, ISO, and M; and the settings rail has the
nine tabs documented above. A host-only run reports device behavior as not run, never as passed or failed.

**Device verification:**
```bash
# Capture the exact immutable path emitted by the wrapper; do not substitute the mutable
# app/build/outputs developer APK.
BUILD_RESULT="$(python3 tools/build_immutable_debug.py)"
printf '%s\n' "$BUILD_RESULT"
EVIDENCE_APK="${BUILD_RESULT##* apk=}"
test -n "$EVIDENCE_APK" && test -f "$EVIDENCE_APK"

export ANDROID_SERIAL=<current-authorized-session-serial>
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$SDK_ROOT"
AAPT="$SDK_ROOT/build-tools/36.0.0/aapt"
PACKAGE="$("$AAPT" dump badging "$EVIDENCE_APK" | sed -n "s/package: name='\([^']*\)'.*/\1/p")"
ACTIVITY="$("$AAPT" dump badging "$EVIDENCE_APK" | sed -n "s/launchable-activity: name='\([^']*\)'.*/\1/p")"
test -n "$PACKAGE" && test -n "$ACTIVITY"

adb -s "$ANDROID_SERIAL" install -r "$EVIDENCE_APK"
adb -s "$ANDROID_SERIAL" shell am start -n "$PACKAGE/$ACTIVITY"
```

**Permissions:** CAMERA + RECORD_AUDIO, plus the visual-media READ trio requested contextually at the first empty-gallery tap, at runtime (ColorOS blocks pm grant; user grants on device once).

---

## See Also

- `docs/BACKLOG.md` — release status, manual Play steps, residual checks, and deferred work.
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — preserved historical design
  snapshot; superseded by this current architecture/code wherever it differs.
- `CLAUDE.md` § **Hard-won device facts** — HAL crash workarounds and their signatures.
