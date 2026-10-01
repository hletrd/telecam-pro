# Perf-reviewer — performance / concurrency / UI responsiveness (2026-09-30)

Scope: `app/src/main/kotlin/**` (59.3k LOC inventoried). Deep focus: `gl/*`, `camera/CameraEngine.kt`
threading, `camera/CameraController.kt` hot path, `video/VideoRecorder.kt`,
`camera/StandbyAudioController.kt`, `ui/review/*`, `ui/CameraScreen.kt`, `ui/overlays/Overlays.kt`,
`stab/GyroEis.kt`, process-wide `*Dispatcher.kt` owners. READ-ONLY review; no source edited.

Settled owner decisions NOT re-reported: ZSL dark refusal, FocusDetail threshold, declined CameraUnit
SDK, no proprietary HDR, 16 ms zoom coalescer / 25 Hz controls throttle / gesture no-submit design,
synchronous `SettingsStore` commit on main, synchronous camera-thread DNG write, 300-row log budget.

Method: GL layer reviewed directly; audio/video, UI/review and engine-threading lanes reviewed by
parallel sub-passes and the load-bearing claims (P1-P4 anchors) spot-checked against source.

## Findings (ordered by severity)

### P1 — Cold-start route resolution runs OFF `setupExecutor` (GL thread + timelapse thread), racing setupExecutor's own resolve → duplicate optics transaction / redundant reopen
- **Where:** `camera/CameraEngine.kt:1826` (`resolveInitialCameraRouteAvailability()` inside the
  `gl.start` `onInputReady` callback — runs on the `gl-pipeline` HandlerThread while holding the
  `terminalAcquisitionGate` monitor); `CameraEngine.kt:3537` (cold-start retry on the
  `timelapseScheduler` thread). Other callers (`:1470`, `:1494`, `:7355`) are on `setupExecutor`.
  `applyResolvedCameraRoute` (`:1339`, `:1348`) writes `activeCameraRoute`, `teleconverterMode`,
  `controls`, `overrideId`, `userCameraPin` outside `beginOpticsTransaction`.
- **Why:** the method mutates topology state (`knownCameraIds`, `knownCameraIdentityEpochs`,
  `pendingRouteTopologyRevision`, `cameraRouteInventoryResolved`, `routeInventoryRetryAttempts`,
  cache invalidation) with no lock, contrary to the resume() comment (`:7333`) that the GL-start
  continuation is serialized on `setupExecutor`.
- **Failure scenario:** `onPreviewSurfaceAvailable` registers route availability (`:1758`) →
  `onCameraAvailable` burst → `scheduleRouteAvailabilityRefresh` queues a forced resolve on
  setupExecutor. Concurrently the GL thread enters `onInputReady` and resolves too. setupExecutor wins
  the topology action and `convergeAfterRouteTopologyChange()` begins T1 and queues reopen A(T1); the
  GL thread then snapshots the same T1 via `currentOpticsReconfiguration()` and queues B(T1). Both own
  T1, so B passes `ownsOpticsTransaction` and replaces A's freshly opened controller — a full extra
  close/open during cold start (hundreds of ms of startup latency). The GL thread also sits on dozens
  of Binder calls while holding the gate monitor, stalling every setupExecutor `runIfOpen` task.
- **Fix:** in `onInputReady` and the retry lambda, hop to `setupExecutor` for resolve → snapshot →
  `reconfigureCamera`, rechecking `glOwners.owns`, `paused` and the gate there; move
  `applyResolvedCameraRoute`'s writes inside `beginOpticsTransaction`.
- **Confidence:** Medium. **Status:** likely (code-traced; confirm on device by two
  `Session configured` lines for one `resume`, or a revoked StartupTrace).

### P2 — `gyro.start()` on the GL thread can undo `pause()`'s `gyro.stop()` → sensors stay registered while backgrounded
- **Where:** `camera/CameraEngine.kt:1809` checks `paused`, then `:1814-1822` performs GL setters and
  `rendererAssists.replayAll`, then calls `gyro.start()` with no recheck. `pause()` sets
  `paused = true` (`:7223`) and later calls `gyro.stop()` (`:7261`) on main. `GyroEis.start()`
  (`stab/GyroEis.kt:~89`) re-registers the accelerometer and, if armed, the gyroscope.
- **Failure scenario:** launch behind the keyguard (CLAUDE.md: `onStop` lands mid session-config).
  GL thread passes the `paused` check → main runs `pause()` incl. `gyro.stop()` → GL thread calls
  `gyro.start()`. Accelerometer (and ~200 Hz gyroscope when motion inversion is armed) now deliver to
  the main looper for the entire background period — battery drain until the next resume/pause pair.
  Related: `GyroEis.start/stop/reset` are called from main, GL, and `camera-engine-release`
  (`release()` `:7598`) and write non-volatile integrator fields (`lastTimestamp`, `ang*`,
  `smooth*`) that `onSensorChanged` reads on main — an unsynchronized reset race.
- **Fix:** confine gyro start/stop to main (post to `context.mainExecutor` and recheck `paused`
  there), or give `GyroEis` a locked run-intent flag so a late `start()` after `stop()`-intent
  refuses.
- **Confidence:** Medium. **Status:** likely (narrow window, real interleaving).

### P3 — Orientation animation recomposes the entire `CameraScreen` every frame
- **Where:** `ui/CameraScreen.kt:619` (`val overlayRotation by animateFloatAsState(...)` read at the
  top of `CameraScreen`, passed by value to ~27 sites incl. `rotateLayout(overlayRotation)` in the
  scopes/REC column `:1263-1284`, `ExposureMeter`, `FnOverlay`, `MediaReviewOverlay` `:1559/:1570`);
  `ui/CameraScreen.kt:1617` (`Modifier.rotateLayout(degrees)` builds a new `layout {}` lambda each
  call, so the modifier never compares equal and children cannot skip).
- **Failure scenario:** each portrait↔landscape turn runs a ~300-500 ms spring = ~20-60 full-screen
  recompositions (plus the review overlay if open) on the main thread, competing with pinch input and
  the TextureView composition — visible jank exactly when the user is re-framing.
- **Fix:** keep the `State<Float>`, pass `() -> Float` into `rotateLayout` and read it inside the
  `layout {}`/`placeWithLayer {}` block (layout/draw-phase read), and pass the provider rather than
  the value to the overlays.
- **Confidence:** High. **Status:** confirmed by code (frame count estimated).

### P4 — 10 Hz level ticker and REC audio levels recompose open modal sheets (ProSheet / FnOverlay)
- **Where:** `ui/CameraViewModel.kt:463-476` (level ticker, gated only on `lifecycleStarted && level`),
  `ui/CameraViewModel.kt:1066-1085` (audio levels during REC, no consumer gate),
  `ui/CameraScreen.kt:1536-1560` (`ProSheet(state = state, …)`, `FnOverlay(state = state, …)` take
  the whole `CameraUiState`).
- **Why:** every tick is a full `CameraUiState` copy; the histogram/waveform/meter already have
  "publish only when drawn" gating, but level and REC audio do not. Scope accessibility helpers
  `histogramAccessibilityState` / `waveformAccessibilityRange` (`ui/overlays/Overlays.kt:1100`,
  `:1194`) rescan full bins on every root recomposition without `remember(data)`.
- **Failure scenario:** level enabled + handheld → 0.2° quantum changes almost every 100 ms → the
  open Settings sheet (all seven ProSheet tabs) recomposes up to 10×/s; an Fn overlay opened during
  REC gets another ~10 Hz from audio.
- **Fix:** split high-rate telemetry (`levelRoll`, audio levels/overload, histogram/waveform,
  `recordElapsedMs`) into a separate StateFlow collected only by the leaf drawers; minimally gate the
  level ticker on `!modalVisible` and wrap the two accessibility scans in `remember(data)`.
- **Confidence:** High. **Status:** confirmed by code (jank magnitude needs a device trace).

### P5 — Review pinch/pan/swipe recomposes the whole review overlay per touch event
- **Where:** `ui/review/MediaReview.kt` — gesture writes `scale`/`offset`/`dismissDrag` at
  `:1359-1368`; composition-phase reads at `:1177`, `:1264`, `:1660-1686`, `:1776`, `:1815-1826`;
  value-form `graphicsLayer(...)` at `:1469-1472` (video) and `:2131-2137` (`ReviewStillImage`);
  `LaunchedEffect(stillGeometry, scale)` at `:1267` restarts a coroutine per pinch frame.
- **Failure scenario:** 120 Hz touch → full `BoxWithConstraints` recomposition per event, ~10
  `stringResource` lookups, new `CustomAccessibilityAction` lists, and coroutine cancel/relaunch;
  frame drops on long-press 8× then drag of a 4096×3072 still; TalkBack state churn.
- **Fix:** lambda-form `Modifier.graphicsLayer { scaleX = scale; translationX = offset.x … }`;
  derive a11y strings/actions via `derivedStateOf` bucketed on coarse scale/position; replace the
  scale-keyed `LaunchedEffect` with one `snapshotFlow` collector (the pattern `CameraScreen:625`
  already uses for zoom).
- **Confidence:** Medium-High. **Status:** confirmed by code; smoothness needs device check.

### P6 — Audio PTS derived from a sample counter; dropped mic samples shift audio early for the rest of the take
- **Where:** `video/VideoRecorder.kt:795`, `:820` (PTS from `totalSamples`), `:652` (mic buffer
  `max(minBuf*2, 8192)` ≈ 43 ms @48 kHz stereo), `:865-876` (audio thread blocks on muxer-start
  rendezvous and `muxerLock`), `:597-606` (video writes under the same lock); video PTS base at
  `gl/GlPipeline.kt:1251` (first encoder frame).
- **Why:** audio t=0 is `startRecording()` on the audio thread; video t=0 is the first encoder swap —
  two clocks. Any audio-thread stall > ~43 ms (1 s startup handshake while holding an output buffer,
  `writeSampleData` holding `muxerLock` on slow storage, GC) overruns the AudioRecord buffer; the lost
  samples are never counted, so every later audio PTS is early by the cumulative loss.
- **Failure scenario:** slow first encoder frame (TB336ZU) or long 4K take on slow storage → A/V
  drift that accumulates over the clip.
- **Fix:** timestamp from `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` (or detect gaps via
  `framePosition` vs `totalSamples` and advance PTS), rebase both tracks on one clock, enlarge the mic
  buffer to 250-500 ms.
- **Confidence:** Medium. **Status:** needs-manual-validation (clap / long-take sync test).

### P7 — Drain loops have no self-deadline after stop; a missing EOS turns a healthy polling thread into a process-level quarantine
- **Where:** `video/VideoRecorder.kt:576-612` (video drain waits for EOS), `:854-886` (audio output
  drain after EOS input, unbounded), classification at `:393-404` (`join(3000)` alive ⇒ wedged).
- **Failure scenario:** REC→Stop with zero frames (`stopUnattachedRecording`) on an encoder that never
  emits the EOS buffer: threads are still polling `dequeueOutputBuffer(10 ms)` — not stuck in native —
  yet `stopNative` classifies them as wedged and quarantines, forcing an app restart instead of a
  normal discard.
- **Fix:** once `running == false`, give each drain loop a deadline shorter than the join timeout;
  on expiry record a clip-level failure and return, so only genuine native wedges reach quarantine.
- **Confidence:** Medium. **Status:** needs-manual-validation (how often QTI omits EOS at 0 frames).

### P8 — `applyResolvedCameraRoute` RMW of `controls` outside the Engine monitor
- **Where:** `camera/CameraEngine.kt:1339`, `:1348` (`controls = controls.copy(zoomRatio = 1f)`), run
  from P1's threads and `convergeAfterRouteTopologyChange` (`:1564`) before the transaction begins.
- **Why:** CLAUDE.md records that `@Volatile` alone lost whole rollback packets; every other writer
  (`setZoomRatio` `:4475`, packet writers) uses `synchronized(this)`.
- **Failure scenario:** a main-thread pinch / settings-restore packet committed between this read and
  write-back is silently overwritten. Only on FRONT/EXTERNAL resolved routes → low frequency.
- **Fix:** `synchronized(this)` or fold into the `beginOpticsTransaction` block (with P1).
- **Confidence:** Medium. **Status:** confirmed at code level.

### P9 (Low) — Stop/failure paths take `muxerLock` unbounded
- **Where:** `video/VideoRecorder.kt:381` (in `stopNative`, before join timeouts), `:425`, `:1009`
  (`recordFailure`). `quarantineUnsafeNativeGraph` (`:546`) deliberately avoids this lock.
- **Scenario:** drain thread wedged inside `writeSampleData` holding `muxerLock` → `stopNative` blocks
  the serial recorder executor forever before its 3 s wedge check; engine watchdog still quarantines,
  so the cost is a permanently blocked executor rather than data loss.
- **Fix:** `tryLock(timeout)` or the daemon-helper pattern already used at `:551`.
- **Confidence:** Medium mechanism / Low impact. **Status:** likely.

### P10 (Low) — Scope DrawScope allocations per redraw
- **Where:** `ui/overlays/Overlays.kt:1173` (new `Path()` ×4 per histogram draw),
  `:1265-1294` (`drawWaveform`: new `android.graphics.Paint`, `IntArray`, 8 `FloatArray`s per draw).
- **Impact:** continuous main-thread garbage at ~6 Hz while scopes are visible, multiplied by P3/P4
  non-skipping recompositions. **Fix:** `drawWithCache` / remembered `Path`/`Paint` with `reset()`.
- **Confidence:** High. **Status:** confirmed.

### P11 (Low) — `rawWanted` is neither `@Volatile` nor monitor-guarded
- **Where:** `camera/CameraEngine.kt:3205`; written on main (`:3962`), read on `setupExecutor`
  (`resolveNonTeleId`/`selectCurrentLens`) and under the monitor in `pushTeleFinder`.
- **Impact:** a queued reopen may read a stale value; mitigated because `setRawWanted` starts its own
  superseding transaction. **Fix:** `@Volatile` or write inside `beginOpticsTransaction`.
- **Confidence:** Medium. **Status:** confirmed (low impact).

### P12 (Low) — `GlPipeline.thread`/`handler` are plain fields read cross-thread by `post()`
- **Where:** `gl/GlPipeline.kt:79-80` (written in `start()` on `setupExecutor`, cleared in `stop()`),
  `gl/GlPipeline.kt:1781-1783` (`post` = `handler?.post`), `setPreviewOutput` `:354`; callers such as
  `setZoomTarget`/`setHalZoom`/`setPreviewDigitalGain` run on main / camera threads.
- **Why:** no happens-before between the setupExecutor write and a main-thread read; a stale `null`
  silently drops a live command (CLAUDE.md: "GlPipeline drops anything posted before start()"). The
  start-callback replay covers renderer assists/transfer/gain but not `setZoomTarget`/`setHalZoom`.
- **Scenario:** theoretical on ARM (stale read window is tiny); worst case one dropped zoom-comp update
  until the next tick. **Fix:** mark `handler` (and `thread`) `@Volatile`.
- **Confidence:** Low. **Status:** needs-manual-validation (JMM-level, not observed).

### P13 (Low) — Per-read allocations / zero-read spin in audio loops
- `video/AudioReadPolicy.kt:17` allocates `AudioReadOutcome.Pcm(byteCount)` per read (both REC and
  standby loops); `camera/StandbyAudioController.kt:736`, `:750` box a `Float` per `audioGain()`
  call → ~50-150 small objects/s. Return an int code / cache gain per pass. Confirmed.
- `video/VideoRecorder.kt:822` and `camera/StandbyAudioController.kt:725`: a read returning 0 loops
  with no backoff; a persistent 0 while running would spin a core. Add a consecutive-zero counter +
  5-10 ms sleep. Low / needs-manual-validation.

### P14 (hygiene) — `LatestHeavyWorkLane` class is test-only
- `ui/review/LatestHeavyWorkLane.kt:470-544` is referenced only by `LatestHeavyWorkLaneTest.kt`;
  production uses `ProgressiveLatestWorkLane`/`LatestReviewSetupLane`. Delete or document.

## Checked and clean (final sweep)
- **GL frame loop** (`GlPipeline.drawFrame`, `FlipRenderer.draw`): no per-frame allocations (matrices,
  cover scratch, finder rect cached); scissor restored in `finally`; FrameGap logging accumulated and
  DEBUG-gated; FinderDrawFailed change-gated. EIS provider allocation is dormant (EIS disabled).
- **Analysis readback:** ≤256 px FBO, single-flight owner gate, generation-owned executor retired on
  stop/start, rejected `execute` after retire caught and gate released; per-run `IntArray(256)` LUT
  only while boosted, histogram/waveform arrays are the published immutable payload (~6 Hz).
- **Lock ordering:** gate → process admission lock → recorder-ownership lock with Engine monitor
  nested; no `synchronized(this)` reaches a latch, `controller.close()`, or the process lock.
- **Main-thread blocking:** `release()` on its own thread, `pause()` defers close to setupExecutor;
  review decode/thumbnail/MediaMetadataRetriever/provider queries on review workers.
- **CameraController hot path:** logs budget/change-gated; ZSL ring (3) and recent results (6)
  bounded; 3 Hz change-gated exposure/focus/AF publications.
- **Process dispatchers:** `ArrayBlockingQueue` + fixed daemon workers + AbortPolicy; engine executors
  shut down in `release()`.
- **VideoRecorder:** muxer start handshake, startup-proof executor, admission latch, and joins (off
  main) are correct; no per-buffer logging.
