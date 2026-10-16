# Cycle 54 performance, concurrency, and causal-tracing review

Date: 2026-08-27
Reviewed revision: `bf40ae2c56c154072691815f83b7090a31f0c424`
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle54.7ZqPtj/repo`

## Authority, complete inventory, and method

I read `CLAUDE.md` completely first, then the complete current authorities in
`docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`. I also reviewed `README.md`, the current Play/privacy
documents, the completed cycle plans, and the current/historical review records needed to avoid
re-reporting already-fixed findings. The optional private maintainer documents named by the committed
authorities are absent, as the clean-clone fallback policy permits.

I inventoried all 554 tracked paths before tracing behavior: all 104 production Kotlin/Java modules
(36 camera, 31 UI, 11 GL, seven video, five capture, five storage, two focus, one stabilization, and
the six app-root modules), all 244 JVM/Robolectric/Compose test sources, four instrumented tests,
three debug sources, all 39 `tools/**` and `device-tests/**` paths, all 105 Markdown paths, 15 main
resource paths, and the remaining manifests, Gradle/provenance inputs, wrapper files, fonts, images,
licenses, privacy assets, and site configuration.

The runtime pass followed every Camera2 callback/session/ImageReader/watchdog edge; Engine optics,
preview, capture, REC, lifecycle, rollback, recovery, and native-quarantine generation; GL/EGL frame
coalescing, draw, readback, analysis, and output ownership path; processed/RAW/video save,
MediaStore publication/deletion/recovery, and exact-family/exact-URI authority; standby and recording
AudioRecord handoff; ViewModel tickers/throttles/debounces and StateFlow writes; review
decoder/player/bitmap/spool ownership; and every executor, handler, scheduler, queue, monitor,
atomic, latch, retry, deadline, and bounded collection. I separately traced every cycle-53 runtime
change through its callers and terminal paths. Comments and passing tests were treated as hypotheses,
not as execution truth.

## Findings

### C54-PT-01 — DNG allocation identity can still wedge the Camera2 callback while the RAW Image is live

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed unbounded camera-thread provider path; an actual MediaProvider wedge
  remains device/fault-injection dependent.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5113-5118` calls
    `StillCapturePipeline.saveDng` synchronously from `PhotoCallback.onPhoto`; the enclosing
    `CameraController` callback cannot close the RAW `Image` until this call returns.
  - `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt:361-384` allocates the
    pending row and freezes its identity before `DngCreator.writeDng` and the COMPLETE-marker attempt.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:390-421` performs a
    `ContentResolver.insert`, synchronous REGISTERED persistence, and then
    `PendingDiscardJournal.captureAllocation` inline.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:32-42,541-584` shows
    that identity capture is not compact bookkeeping: it resolves mounted volumes/provider version,
    issues an exact pending-row `ContentResolver.query`, validates cardinality/row id, and re-reads
    provider version. Binder/provider calls have no deadline.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:2176-2220` invokes the app
    callback on the camera HandlerThread and closes/clears acquired Images only in the surrounding
    completion `finally`, after `onPhoto` returns.
  - The cycle-53 regression test at
    `app/src/test/kotlin/me/hletrd/telecampro/storage/RejectedOutputCleanupDispatcherTest.kt:13-42`
    blocks only the *post-write rejected-output worker*. The identity tests at
    `ImmediateDiscardIdentityTest.kt:28-145` use an in-memory reader and do not block allocation-time
    MediaProvider identity acquisition.
- **Concrete failure scenario:** A RAW shutter completes while MediaProvider is slow or wedged. The
  Camera2 callback enters the pending-row insert or the newly added allocation-identity query and
  cannot return. The acquired full-resolution RAW Image remains open, the Camera2 handler cannot run
  later image/result/watchdog/3A work, and the next capture cannot progress. The cycle-53 dispatcher
  correctly moved failed-write DISCARD/query/delete work off-camera, but the success/common path now
  performs the creation-time identity query on that same callback, so the original causal stall class
  remains reachable before the dispatcher can help.
- **Competing hypotheses checked:** The cleanup reservation at `CameraEngine.kt:4976-4983` limits
  later rejected-output work but owns no pending row and cannot bound `createPendingImageAllocation`.
  `runCatching` converts exceptions, not a non-returning Binder call. DNG byte writing genuinely needs
  the live RAW Image; MediaStore allocation and identity capture do not. The single `ioExecutor` and
  process cleanup workers are entered only after this synchronous allocation/write boundary returns.
- **Concrete fix:** Preallocate and freeze the DNG `PendingOutputAllocation` on a finite pre-capture
  provider lane before submitting Camera2 (analogous to recording pre-native allocation), then pass
  that immutable allocation into the photo callback so its live-Image interval contains only the DNG
  byte write. Cancellation/session supersession must hand a late allocation to the existing bounded
  cleanup/recovery owner without waiting. Add a deterministic test whose allocation identity reader
  blocks: Camera2 dispatch should not begin until an allocation is claimed, cancellation must remain
  prompt, and once the RAW callback begins it/Image-close must not wait on provider identity work.

### C54-PT-02 — the new log-quota test omits continuous producers that still exhaust ColorOS's 300-row budget

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed debug tracing/evidence failure; no release-runtime cost. The exact
  device drop point depends on other startup/fault rows but is strictly earlier than the computed
  producer totals.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:547-590` runs
    `refreshFocusConfidence` for analysis/AF events and emits a `FocusConfidence` heartbeat whenever
    two seconds have elapsed, even when the candidate is unchanged.
  - `CameraViewModel.kt:1026-1039` posts that refresh for every non-null frame-detail result. The
    default non-manual photo route admits the rider (`CameraViewModel.kt:4516-4521`), and app-side
    Photo-P/visible scopes already supply the approximately 6 Hz readback that drives it.
  - `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:743-750,869-872` says it traces
    non-standard keys but excludes only volume/camera/back. Standard `KEYCODE_ZOOM_IN/OUT` therefore
    log every DOWN/repeat edge; the committed hardware contract says the capacitive slide repeats at
    about 20 Hz.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/DiagnosticTelemetry.kt:26-51,118-120` bounds only
    the 3A producer (41 stable or at most 201 changing rows over ten minutes) and the ZSL accumulator.
    `app/src/test/kotlin/me/hletrd/telecampro/camera/DiagnosticTelemetryTest.kt:27-48` then calls
    `rows + 2 <= 210` a quota reserve without including FocusConfidence, button, motion, frame-gap,
    startup, or fault producers.
  - `CLAUDE.md:1054-1067` records the measured 300-row per-process ceiling and explicitly requires
    every continuous diagnostic to be change-gated or thresholded; `docs/FIELD_CHECKS.md:106-127`
    requires a ten-minute immutable-debug A5 soak whose late gaps/errors must remain observable.
- **Concrete failure scenario:** Leave default front/rear Photo-P on a scene that continuously
  supplies analysis. An unchanged focus candidate emits about 300 heartbeat rows in ten minutes by
  itself; even the stable 3A producer adds about 41, before startup, ZSL enable/summary, frame-gap,
  recovery, or errors. ColorOS therefore drops diagnostics before the A5 soak ends. Independently,
  holding the physical zoom slide produces roughly 20 `BtnDbg` rows/s and can spend the entire quota
  in about 15 seconds, hiding the ZoomTrace/frame-gap evidence the input trace is meant to support.
- **Competing hypotheses checked:** Release builds are guarded, but every committed field-evidence
  workflow uses the immutable debug APK. Focus logging is change-gated only until its two-second
  heartbeat expires; the 6 Hz callback guarantees that expiry is observed. `MOTION_SIGNS_VERIFIED`
  being false suppresses the dormant motion producer but does not suppress focus. Standard zoom keys
  are not included by `isShutterKey`, so the logger's “non-standard” comment does not match its gate.
  The cycle-53 3A fix is valid in isolation; the defect is the unsupported process-wide reserve claim.
- **Concrete fix:** Put every recurring debug producer behind one shared quota owner or, minimally,
  lengthen FocusConfidence's stable heartbeat to at least 15 seconds and change-gate/pace hardware
  key logging (for example, log one start/terminal summary plus bounded direction changes, not every
  repeat). Extend the ten-minute budget test to instantiate the actual gates for 3A, focus, motion,
  hardware input, and the ZSL summary while reserving explicit rows for startup, gaps, recovery, and
  faults. A mutation restoring the two-second heartbeat or per-repeat key log must fail it.

## Verification and evidence limits

- Focused JVM/Robolectric suites for `DiagnosticTelemetryTest`,
  `RejectedOutputCleanupDispatcherTest`, `ImmediateDiscardIdentityTest`, and
  `CameraEngineRecordingPreNativeTest`: **BUILD SUCCESSFUL**. These passing tests establish the
  isolated policies above; they do not exercise the two omitted blocking/budget compositions.
- `python3 tools/check_docs.py`: **155 checks, 0 failed, 24 optional-private checks skipped**.
- No device, emulator, deployment, Camera2/GL/audio fault injection, MediaProvider mutation, browser,
  or destructive operation was performed. Open physical obligations A3/A4/A5/D1/E1/E2/E3 remain
  open exactly as `docs/FIELD_CHECKS.md` records them.

## Final missed-file and competing-hypothesis sweep

I reconciled the tracked inventory against the architecture/module map and rechecked every source of
recurring work, unbounded wait, provider/native call, per-frame allocation/log, main-thread I/O, late
publication, lifecycle restart, queue rejection, capacity overflow, and partial cleanup. I also
traced the current cycle-53 standby publication/quarantine transaction, immediate DISCARD identity,
rejected-output reservation/worker capacity, immutable review spools and byte budgets, diagnostic
gates, process recovery, all executor shutdown paths, and comments/tests that claim those terminals.

The process-finite provider lanes, GL frame coalescer and downsampled single-flight analysis,
processed-snapshot budget, latest-wins review pool, recorder drain/quarantine deadlines, standby
AudioRecord handoff, camera teardown terminal, zoom/control throttles, and StateFlow change gates did
not yield another current-HEAD performance or liveness finding. Binary fonts/images/wrapper artifacts
were checked at their glyph, manifest, packaging, and provenance boundaries rather than treated as
executable concurrency surfaces. Resolved historical findings were not repeated; C54-PT-01 is the
remaining pre-dispatch/allocation side of the newly fixed cleanup stall, and C54-PT-02 is the omitted
cross-producer composition that the new isolated 3A test incorrectly labels process-wide.

**Finding count: 2 — both Medium severity and High confidence.**
