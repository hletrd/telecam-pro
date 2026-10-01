# Perf-reviewer — RPL cycle 3 (2026-10-02, HEAD e3a2bdd4)

Angle: performance, concurrency, CPU/memory, UI-thread responsiveness, GL thread, executors,
hot-path allocation, lock contention, blocking calls on main/camera threads, Compose recomposition.
READ-ONLY review; no source, test, doc or plan edited; Gradle not run.

Not re-reported (settled owner decisions or accepted design): ZSL dark refusal, FocusDetail
threshold, CameraUnit SDK, proprietary HDR, 16 ms zoom coalescer / no-submit gesture design,
synchronous `SettingsStore` commit on main, `DngCreator.writeImage` itself running on the camera
callback (the Image must be live), 300-row log budget. Cycle-1/2 perf items already fixed or
deferred (AGG-42, AGG-51, AGG-53, AGG2-32, AGG2-33) are not repeated unless new evidence is given.

## Findings

### PERF3-1 — While recording, every encoder frame waits behind a blocking preview `eglSwapBuffers`; UI jank turns into dropped frames in the FILE
- **Severity / Confidence / Status:** Medium / Medium / Needs-device
- **Where:** `gl/GlPipeline.kt:1044-1200` (preview `makeCurrent` → `renderer.draw` → finder →
  `core.swapBuffers(ownedPreview)`), then `:1242-1259` (encoder draw/swap). `gl/EglCore.kt:92-94`
  (`eglSwapBuffers`); no `eglSwapInterval` anywhere in `gl/` (grep), so the TextureView window keeps
  the default interval 1 (synchronous BufferQueue).
- **Why:** the preview surface is a TextureView. Its consumer only latches when the UI thread
  produces a frame and RenderThread syncs it. With swap interval 1 the producer's dequeue blocks once
  the non-acquired buffers are all queued. `drawFrame` always swaps the preview first, so a main-thread
  stall longer than about two camera frames (a heavy recomposition such as opening ProSheet or review
  during REC, or a GC pause) parks the GL thread inside the preview swap. The encoder draw for that
  frame waits too. Meanwhile `FrameNotificationCoalescer` (`gl/FrameNotificationCoalescer.kt:21-40`)
  collapses the pending SurfaceTexture notifications into one "latest frame" draw. The frames that
  arrived during the stall are therefore never drawn into the encoder. PTS comes from
  `st.timestamp`, so the clip keeps correct timing but has holes, which plays back as stutter.
- **Failure scenario:** 4K30 REC, the operator opens the Fn overlay or ProSheet mid-take, and the
  first composition of that sheet costs about 80–120 ms on main. Roughly three camera frames are
  coalesced away. The recorded MP4 shows a visible hitch even though the camera and encoder were
  healthy.
- **Fix:** with an active encoder owner, draw and swap the ENCODER first, then the preview. The
  encoder is the output that must not drop. The analysis readback is already ordered after the
  encoder for this reason. Alternatively call `EGL14.eglSwapInterval(display, 0)` once after the
  preview surface first becomes current in `applyPreviewOutput`. That puts the TextureView queue in
  async mode, so the preview drops a frame instead of blocking. A host-testable seam is a pure
  `frameOutputOrder(encoderActive: Boolean)` that the draw follows, with a unit test.
- **Device check:** during REC, inject a 150 ms main-thread stall (debug) or open ProSheet. Then
  compare `ffprobe -count_frames` against duration × fps, before and after the fix.

### PERF3-2 — The processed and hi-res JPEG lanes rewrite every saved JPEG twice through a full temp-file copy to re-stamp EXIF
- **Severity / Confidence / Status:** Medium / High / Confirmed (from code and library bytecode)
- **Where:** `capture/StillCapturePipeline.kt:322-334` (`rotated.compress` straight to the provider
  stream, then `writeJpegExif`), `:470-486` (`ExifInterface(pfd.fileDescriptor)` + `saveAttributes()`),
  `:355-368` (hi-res passthrough, same pattern). Library check: androidx.exifinterface 1.4.2
  `saveAttributes()` (javap of the cached AAR) does `File.createTempFile("temp","tmp")` and copies
  the WHOLE fd into it (`ExifInterfaceUtils.copy`). It then `lseek`s the fd to 0 and streams the
  whole image back through `saveJpegAttributes`. The constructor has already parsed the fd once.
- **Why:** each processed JPEG (about 6–12 MB at 12.5 MP q95+) crosses the MediaProvider FUSE path
  about three times: compress-write, full read into the cache copy, full rewrite from the copy. It
  also writes the same number of bytes again to app cache. All of this runs on the single-thread
  `ioExecutor` that serializes every processed still and DNG transfer. In BURST with HEIF+JPEG, the
  JPEG I/O roughly triples and backs up the queue that `ProcessedSnapshotBudget` bounds, so the
  shutter refuses sooner. The hi-res passthrough lane (dormant on PMA110) would copy about 40 MB per
  shot the same way. Secondary: `writeJpegExif` sits in `runCatching`. If `saveAttributes` fails
  after it began rewriting the fd and its own restore copy also fails ("Failed to save new file.
  Original file is stored in …"), the lane still marks COMPLETE and publishes whatever bytes are
  left. A low-space cache also silently drops all EXIF, because the temp copy is what fails first.
- **Fix:** the HEIF lane already builds an APP1 payload without touching the output
  (`buildHeifExifData` + `extractExifApp1`, `:488-515`). Reuse it for JPEG. Compress into memory (the
  rotated bitmap is already resident), then write `FFD8` + APP1 segment + compressed[2..] to the
  provider stream in one pass. The passthrough lane can splice the same way. This removes the temp
  file, the extra FUSE round trips and the partial-rewrite failure mode. Host test: the spliced
  output parses with `ExifInterface(ByteArrayInputStream)` and carries ISO, exposure, make/model and
  orientation, and its decoded pixels equal the un-spliced encode.

### PERF3-3 — The DNG camera callback also does Binder `openOutputStream` and the fsync'd COMPLETE marker with sleep backoff, and neither needs the live Image
- **Severity / Confidence / Status:** Low-Medium / High / Confirmed (thread placement); latency Needs-device
- **Where:** `capture/StillCapturePipeline.kt:388-407` (`saveDng`: `MediaStoreWriter.openOutputStream`
  → `DngCapture.writeDng` → `MediaStoreWriter.markWriteComplete`), called from
  `camera/CameraEngine.kt:5752` inside the photo callback on the controller's single `"camera"`
  HandlerThread (`camera/CameraController.kt:80-81`, `:730`, `:745`).
  `storage/MediaStoreWriter.kt:636-647` (marker: `SharedPreferencesDurableEdit.putString` with
  `commit`, up to `COMPLETION_MARK_ATTEMPTS = 3` with `sleepPreservingInterrupt(25 ms × attempt)`,
  so at most 75 ms of sleep), and `:1056-1064` (`contentResolver.openOutputStream`, no deadline).
- **Why:** only `DngCreator.writeImage` needs the RAW `Image` alive; that part is the accepted design.
  The provider `openOutputStream` Binder call (MediaProvider/FUSE open, unbounded under provider
  contention, e.g. during launch recovery or another app's scan) and the durable marker do not need
  it. That HandlerThread is the only thread for capture results (`onCaptureCompleted` → zoom-result
  GL compensation, AF/AE publication, ZSL ring pairing) and every ImageReader listener. Each
  millisecond spent here delays those callbacks and holds the RAW buffer, and with it the
  `ProcessedSnapshotBudget`/DNG admission, for longer. In AEB/BURST with DNG this repeats per frame.
- **Failure scenario:** a DNG BURST while MediaProvider is busy. Each callback waits on the open,
  then up to three commits plus 75 ms of sleep when the journal write hiccups. Zoom-result
  forwarding and AF indication lag visibly, and the next RAW image waits in the reader.
- **Fix:** open the `ParcelFileDescriptor` during the pre-capture DNG allocation. That allocation
  already runs off-thread under its 8 s first-wins deadline, so carry the fd in
  `PendingOutputAllocation` and keep the camera callback down to `writeImage` + close. Move
  `markWriteComplete` into `publishDng`, which already runs on the process owner. Launch recovery
  still adopts a REGISTERED DNG only after the structural probe, so a crash between the write and
  the marker keeps today's outcome. The CLAUDE.md/ARCHITECTURE sentence "durable COMPLETE marker
  attempt remain synchronous while the RAW Image is valid" then needs the matching edit. Host test:
  with a blocking fake writer and a blocking marker, the callback returns as soon as the fake
  `writeImage` returns.

### PERF3-4 — Every capture's gallery thumbnail copies the whole still file into cache and decodes it three times for a 240 px image
- **Severity / Confidence / Status:** Low-Medium / High / Confirmed
- **Where:** `ui/review/MediaReview.kt:830-841` (`loadGalleryThumb` STILL → `decodeReviewBitmap(...,
  GALLERY_THUMB_MAX_DIM = 240, ...)`), `:472-500` (`openReviewDecodeSource` spools the provider
  stream; APP_OWNED max is `trustedReviewSourceMaxBytes`, up to 512 MiB), then three
  `source.openInputStream()` passes (bounds, sampled decode, EXIF orientation).
  `ui/review/LatestHeavyWorkLane.kt:391-440` (64 KiB copy loop into `review-sources-v1`).
  Keyed by `LaunchedEffect(uri, provenance)` at `:878`, so it runs for every new capture.
- **Why:** each shot pays an extra full read from FUSE, a full write to app cache (about 6–12 MB of
  extra flash writes per JPEG/HEIF) and a sampled decode. For HEIF a sampled `BitmapFactory` decode
  still decodes the full HEVC image before it subsamples (Medium confidence on that platform detail).
  This runs on the shared review dispatcher, but it competes for storage bandwidth and big-core CPU
  with the `ioExecutor` saves of the next burst frames. The video path already uses the bounded
  provider thumbnail (`loadVideoThumbnail`, `:408-423`) and needs none of this.
- **Fix:** for APP_OWNED stills, request `contentResolver.loadThumbnail(uri, Size(240,240), null)`
  (MediaProvider's cached thumbnail honors EXIF orientation), with the same
  `providerThumbnailFitsRequest` bound check. Keep the frozen spool for the full review open, where
  identity freezing matters. If the spool must stay, at least skip the third pass for processed
  lanes, which stamp `ORIENTATION_NORMAL`. Host test: the STILL thumbnail lane never calls the spool
  for APP_OWNED rows.

### PERF3-5 — `PendingDiscardJournal` opens and closes a fresh SQLite connection for every operation while holding a process-wide monitor
- **Severity / Confidence / Status:** Low / High / Confirmed
- **Where:** `storage/PendingDiscardJournal.kt:380-389` (`withReadableDatabase`/`withWritableDatabase`
  construct a new `Helper` (SQLiteOpenHelper) and `.use{}` it, so every call opens and closes the
  database file), wrapped by `synchronized(databaseLock)` (static, `:481`) at `:97`, `:138`, `:148`,
  `:220`, `:256`, `:339-340`. Callers: `MediaStoreWriter.publish` (every HEIF/JPEG/DNG/video,
  `storage/MediaStoreWriter.kt:1076-1084` → `withLookupAuthority` → `lookupLocked`), `clearPending`,
  `discardPendingOutput`, and launch-recovery paging (one lookup per row).
- **Why:** each open repeats file open, header and `user_version` validation, connection setup and
  close. That is a few ms of file I/O done while holding the one process-wide `databaseLock`, so the
  ioExecutor publish, the DNG process owner, the rejected-output workers, recording storage and
  launch recovery all serialize on connection churn. The cost is not large per op, but it lands on
  every saved output and grows linearly with recovery size.
- **Fix:** keep one process-lifetime `Helper` (lazy, per name and version) and reuse its
  `SQLiteDatabase`, which is thread-safe with its own connection pool. Keep `databaseLock` only where
  a multi-statement sequence needs it. Make the helper factory injectable so a host test can assert
  one construction across N lookups.

### PERF3-6 — Logical-route (default 1×) stills pay an intermediate full-resolution JPEG encode and decode before the real encode
- **Severity / Confidence / Status:** Low / Medium / Confirmed (cost magnitude Needs-device)
- **Where:** `capture/StillSnapshot.kt:44-55` (`YuvImage.compressToJpeg` at q97 into a
  `width*height` buffer), then `capture/StillCapturePipeline.kt:200` (`BitmapFactory.decodeByteArray`
  of those bytes), then crop/rotate and the HEIF/JPEG encode.
- **Why:** on the LOGICAL route (the default photo route, plus FRONT's YUV rungs) every still
  round-trips through a 12.5 MP q97 JPEG encode (about 12 MB) and a full decode on `ioExecutor`
  before the real encoder runs. That is roughly double the CPU and transient memory of the pixel
  pipeline per shot, plus one more lossy generation. In BURST on the logical route it is the main
  throughput limiter behind `ProcessedSnapshotBudget`. The comment "changes nothing structurally"
  is true for correctness but not for cost.
- **Fix:** convert NV21 to an ARGB `Bitmap` directly. That is a bounded per-row Kotlin loop
  (host-testable against `YuvImage`'s colour math at a tolerance), or a GL/HardwareBuffer path,
  feeding the existing crop/rotate. Alternatively feed HEIF through `HeifWriter.INPUT_MODE_BUFFER`
  with YUV after a plane rotate. Either removes one encode and one decode per logical-route shot.
  Measure first with a debug `ShutterLag`-style timing row (bounded per the log-budget rules).

### PERF3-7 — (carried AGG-50, new evidence) The GL-thread input-ready continuation holds `TerminalAcquisitionGate` across Binder route enumeration
- **Severity / Confidence / Status:** Low-Medium / Medium / Confirmed (placement); impact Needs-device
- **Where:** `camera/CameraEngine.kt:1880-1914`: `terminalAcquisitionGate.runIfOpen inputReady@{ ...
  resolveInitialCameraRouteAvailability() ... }` runs on the `gl-pipeline` HandlerThread, invoked
  from `GlPipeline.applyPreviewOutput` (`gl/GlPipeline.kt:462`). `resolveInitialCameraRouteAvailability`
  calls `CameraSelector2.routeInventory(manager)` (`camera/CameraSelector2.kt:268-290`:
  `cameraIdList` + one `getCameraCharacteristics` per id) and `manager.cameraIdList` again
  (`CameraEngine.kt:1457-1458`). `TerminalAcquisitionGate.runIfOpen`/`close` are `@Synchronized`
  (`:8657-8671`). Every other Camera2 acquisition on `setupExecutor` also enters that monitor
  (`:2472`, `:4349`, `:4538`, `:6558`), and so does `enterUnsafeRecorderQuarantine` → `close()`
  (`:7386`).
- **New evidence beyond AGG-50:** the same `registerAvailabilityCallback` that
  `onPreviewSurfaceAvailable` performs first (`:1835`, `:1578-1582`) delivers an initial
  `onCameraAvailable` per id. That queues a forced resolve on `setupExecutor`
  (`:1556-1571`; `routeAvailabilityRefreshRequired` is true while `cameraRouteInventoryResolved` is
  false). On cold start there are therefore two concurrent full Binder enumerations: one on the GL
  thread holding the gate monitor, one on setupExecutor. setupExecutor's `openCamera` /
  session-start `runIfOpen` then waits for the GL thread's enumeration. The resolve itself is still
  unsynchronized across the two threads (`pendingRouteTopologyRevision` RMW, `knownCameraIds`,
  `routeInventoryRetryAttempts`).
- **Fix (host-testable):** make the input-ready callback only post to `setupExecutor`, so the
  resolve + snapshot + reconfigure run there serially with the availability refresh. Gate the
  refresh with `cameraRouteInventoryResolved` so the second enumeration becomes a no-op. A unit test
  can assert the continuation runs on the setup executor. A device cold-start trace should then show
  one `Session configured` per resume, the AGG-50 exit criterion.

## Requested inventory — remaining `Thread.sleep` and blocking waits (thread placement)

| Site | Call | Thread | Main/camera? |
|---|---|---|---|
| `camera/CameraEngine.kt:7573` | `runCatching { Thread.sleep(MEDIA_RECOVERY_RETRY_BACKOFF_MS * attempt) }` (launch-recovery backoff) | `media-recovery` daemon (`LaunchMediaRecoveryCoordinator.kt:216-217`) | No. The interrupt flag is swallowed (AGG-30 tail), but nothing in the recovery coordinator ever interrupts (no `cancel(true)`/`shutdownNow`), so this is harmless today. Use `sleepPreservingInterrupt` for consistency. |
| `camera/StandbyAudioController.kt:399` | `Thread.sleep(delayMs)` in `threadBackedStandbyRetryScheduler` | its own `StandbyAudioRetryFallback` thread, used only when a main-loop post was rejected | No; restores the interrupt flag correctly. |
| `storage/MediaStoreWriter.kt:2399` `sleepPreservingInterrupt` | via `markWriteComplete` (`:646`, 25/50 ms), `discardPendingOutput` (`:669`), `:754`, `publish` (`:1112`, 50/100 ms) | `ioExecutor` (processed stills), DNG process owner (`publishDng`), recorder finalization (`VideoRecorder.kt:2049`), rejected-output workers | **One camera-thread caller:** `StillCapturePipeline.saveDng` → `markWriteComplete` (`:407`) on the controller `"camera"` HandlerThread, worst case 75 ms sleep plus 3 fsync commits (PERF3-3). No main-thread caller. |
| `camera/CameraEngine.kt:4435` | `deviceUp.await` slices (2 s absolute) | `setupExecutor` | No |
| `camera/CameraEngine.kt:6288` | mic release `await(400 ms)` | serial recorder executor | No |
| `camera/CameraEngine.kt:7751`, `:7759` | recorder setup / finalization classification (≤14 s each) | `camera-engine-release` thread (`CameraViewModel.kt:4255`) | No |
| `camera/CameraController.kt:2393` | `close()` → `terminal.await(1.5 s)`; returns immediately when called on its own camera thread (`:2387`) | setupExecutor, release thread, `egl-reset-fallback`; **main only** in `restartGlAfterPreviewSurfaceLoss`'s fallback when `setupExecutor.execute` is rejected (`CameraEngine.kt:2155`, after shutdown) | Main only in the post-shutdown fallback (≤1.5 s); acceptable but notable. |
| `gl/GlPipeline.kt:1671-1676` | `stop()` await + join (1.5 s total) | setupExecutor (surface-loss/reset) and release thread | No |
| `video/VideoRecorder.kt:418-419` | `join(3000)` ×2 | recorder executor (`stopNative`) | No |
| `camera/StandbyAudioController.kt:257` | `stopCompleted.await` slices | standby worker | No |
| `camera/RecordingPreNativeAllocation.kt:145`, `CameraTeardownTerminal.kt:44`, `RecordingTeardownCoordinator.kt:60/90` | bounded latch awaits | release/recorder/setup lanes | No |
| `ui/review/LatestHeavyWorkLane.kt:655/688` | coroutine `await()` | review dispatcher coroutines | No (suspending) |

No `runBlocking`, `SystemClock.sleep`, or `LockSupport.park` in `app/src/main/kotlin`.
`CameraEngine.pause()` (main) does no blocking wait: controller close moves to setupExecutor and
recorder finalization to the recorder executor.

## Final sweep (commonly missed) — clean or already tracked

- Per-frame GL path: `coverScaleInto` scratch, static VBO texcoord offsets, finder hint without
  arrays. The EIS provider allocation is inert because GL EIS is disabled. The analysis readback is
  single-flight and capped at a 256 px long edge, with buffers reused per generation.
- Capture-result callback: zoom forwarding change-gated, 3A publication every 10th frame and only
  on change, custom-WB gains fetched only while sampling, debug 3A trace bounded.
- ViewModel tickers: level/orientation/record/info all change-gated and `cleared`-guarded. Analysis
  publication is gated by `scopesVisible`/`exposureMeterVisible`. AE feeds main only in driving
  modes. Audio levels quantized before the compare.
- Encoder inventory loads once, off main (`EncoderCaps.load` on the VM `ioExecutor`). Release runs
  on a dedicated thread. Launch recovery runs on its own daemon lane.
- Still path memory: decode is recycled once rotated, one combined crop+rotate allocation, NV21
  pixels dropped after the intermediate encode, `ProcessedSnapshotBudget` bounds in-flight shots.
- Scopes Canvas: waveform uses primitive `FloatArray` points. Histogram `Path` allocations per redraw
  were already reported (cycle-1 P10).

## Files examined (grouped)

- camera: `CameraEngine.kt` (threading, lifecycle, route resolution, open/dual-open, DNG callback,
  release/pause, recovery), `CameraController.kt` (HandlerThread, capture callback, close),
  `CameraSelector2.kt`, `StandbyAudioController.kt`, `LaunchMediaRecoveryCoordinator.kt`,
  `RecordingTeardownCoordinator.kt`, `RecordingPreNativeAllocation.kt`, `CameraTeardownTerminal.kt`,
  `CameraState.kt` (`VideoFrameRate.availableFor`)
- gl: `GlPipeline.kt` (start, preview output, drawFrame, analysis readback, stop, histogram/waveform),
  `FlipRenderer.kt`, `FrameNotificationCoalescer.kt`, `EglCore.kt`
- capture: `StillSnapshot.kt`, `StillCapturePipeline.kt`, `HeifCapture.kt`
- storage: `MediaStoreWriter.kt` (marker, publish, open, sleeps), `PendingDiscardJournal.kt`
- video: `VideoRecorder.kt` (drain loops, stopNative), `EncoderCaps.kt`
- stab: `GyroEis.kt`
- ui: `CameraViewModel.kt` (engine callbacks, tickers, AE, settings save, encoder inventory, delete
  dispatch), `CameraScreen.kt` (gesture loops, overview), `MainActivity.kt` (state collection),
  `overlays/Overlays.kt` (scopes), `review/MediaReview.kt`, `review/LatestHeavyWorkLane.kt`
- Library check: androidx.exifinterface 1.4.2 `saveAttributes()` bytecode (cached AAR, javap)
- Prior context: `.context/reviews/archive-rpl-cycle1-2026-10-02/perf-reviewer.md`,
  `archive-rpl-cycle2-2026-10-02/{_aggregate,perf-reviewer}.md`, `docs/plans/2026-10-02-rpl-cycle{1,2}.md`
