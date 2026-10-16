# Performance and causal-tracing review — cycle 48

Date: 2026-08-25  
Reviewed revision: `ad64188a020000833d653d27e3ae40840868f44a`  
Workspace: isolated clone `/tmp/find-x9-ultra-cycle48.Gvbytf`  
Mode: Prompt 1 read-only review; no source, plan, shared-main, device, deployment, or git mutation

## Scope, inventory, and method

I read `CLAUDE.md` first and then the complete as-built authority in
`docs/ARCHITECTURE.md`. I also checked `docs/FIELD_CHECKS.md` to keep source-confirmed defects
separate from device-only validation. The tracked inventory contains 528 paths: all 103 production
Kotlin/Java files, 246 test/debug/instrumentation paths, 39 host/device-tool paths, Android
resources/manifests/build inputs, and the complete documentation/review/plan history. Historical
reviews were used only as regression oracles; every conclusion below was rechecked against current
production code and its current callers.

The performance pass traced Camera2 enumeration/open/configure/repeating callbacks, zoom/control
fast paths, pseudo-ZSL and capture correlation, full-resolution snapshot ownership, still/DNG
encoding and publication, GL frame coalescing/preview/encoder/analysis work, gyro and audio input,
recorder codec/muxer/finalization, MediaStore recovery/delete dispatchers, review decode/playback,
ViewModel tickers/input coalescing/settings persistence, Compose publication, and lifecycle
replacement. Repository-wide sweeps enumerated every executor, HandlerThread, raw Thread,
scheduled/delayed task, blocking wait, retry loop, queue, mutable retained collection, per-frame or
per-buffer allocation/log, and native/provider ownership boundary. I separately traced all changes
since the last zero-finding performance baseline and all cycle-47 fixes through their terminal
edges and harness consumers.

## Finding

### PTR48-01 — the capture-trace fix gates registration but still logs every timelapse completion

- **Severity / confidence:** Medium / High.
- **Classification:** **Confirmed** tracing/performance regression in debug/device-evidence builds.
  The unbounded producer and unconditional terminal log are both present in current code; observing
  the exact ColorOS drop point is device validation, but the repository already records the
  device-measured 300-row process quota.
- **Exact evidence:**
  - The cycle-47 fix computes `traceRegistration` from the selected drive and recording-snapshot
    flag and passes it only through the direct SINGLE branch
    (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4112-4133`). Its pure gate
    rejects `DriveMode.TIMELAPSE`
    (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:967-973`).
  - `photoCallback` correctly uses that flag for `CaptureFamily: registered`
    (`CameraEngine.kt:4649-4685`), but `finishSequence` ignores it and emits
    `CaptureFamily: settled` for every completed callback whenever `BuildConfig.DEBUG` is true
    (`CameraEngine.kt:4689-4698`).
  - Timelapse constructs `photoCallback` without any trace gate on every tick and schedules the next
    tick after completion for as long as the run owns its generation
    (`CameraEngine.kt:4258-4262,4275-4317`). The accepted interval domain includes one second
    (`CameraState.kt:957-965`). There is no capture-count, time, sampling, or log-budget ceiling.
  - The repository's measured platform constraint is explicit: ColorOS drops process logs after 300
    rows, and new per-frame/per-tick diagnostics must be change-gated or thresholded
    (`CLAUDE.md:1045-1056`).
  - The remaining completion line has legitimate bounded harness consumers: ordinary single-shot
    capture waits for it (`device-tests/cases.py:1173-1193`), and the one-shot in-REC snapshot uses
    it to account each observed sensor start (`device-tests/cases.py:977-1040,4515-4558`). That
    explains why deleting the line globally is not a valid fix, but it does not justify emitting it
    for an unattended, unbounded timelapse.
- **Concrete failure scenario / causal chain:** Run a debug one-second timelapse for five minutes.
  Each tick completes its save lanes, invokes `finishSequence`, and emits one `settled` row even
  though the new registration gate correctly suppresses its sibling. At 300 completed frames this
  trace alone can consume the recorded process quota; the always-live camera diagnostics consume
  additional rows, so loss can occur sooner. Later `StartupTrace`, `FrameGap`, focus, camera-error,
  or teardown evidence is then silently dropped. A real preview/encoder stall or lifecycle race can
  appear uninstrumented or be misdiagnosed from an incomplete causal trace. The recent fix therefore
  halves the previous two-lines-per-tick rate but does not bound it.
- **Competing hypotheses checked:**
  - Sequence chaining bounds full-resolution memory and I/O backlog to one shot, but that is not a
    bound on the number of successfully completed callbacks or log rows over a run.
  - `captureRegistrationTraceAdmitted` cannot protect settlement because the settlement condition
    never reads it.
  - Human-triggered SINGLE and the in-REC snapshot are bounded harness actions, but timelapse is
    explicitly unattended and has no terminal frame count.
  - Release builds compile the branch away through `BuildConfig.DEBUG`; that limits end-user cost
    but does not make the device-evidence build reliable. Debug log reliability is a stated
    architecture requirement because this app's HAL behavior is established on-device.
- **Suggested fix:** give settlement its own explicit admission policy and thread that immutable
  decision into `photoCallback`. Preserve completion evidence for ordinary SINGLE and the one-shot
  in-REC snapshot, while suppressing or explicitly sampling automatic sequence ticks—at minimum
  TIMELAPSE. A stronger design is a debug-only, harness-armed finite trace session with a fixed row
  budget, so BURST/AEB evidence can opt in without creating a permanent producer. Lazily build the
  family stem/extension rendering only when either admitted trace line will be emitted. Add tests
  proving TIMELAPSE cannot emit settlement indefinitely and that SINGLE plus in-REC snapshot still
  expose the exact terminal evidence their harness paths consume.

## Classification summary

- **Confirmed findings:** 1 (`PTR48-01`).
- **Likely but unconfirmed code risks:** none survived competing-hypothesis validation.
- **Manual/device-validation risks (not code findings):** the sustained front pseudo-ZSL idle and
  memory-pressure soak remains explicitly open at `docs/FIELD_CHECKS.md:106-127` (A5). The other
  open field checks concern P-mode scene behavior, rotated-window tap-AF, directional audio, and
  owner-null MediaProvider behavior; this host review found no contradictory code evidence and did
  not infer device success.

## Final missed-issues sweep

I rechecked the frame-notification coalescer, 256-pixel single-flight analysis readback, fixed ZSL
ring/result windows, processed-snapshot budget, Camera2 callback shutdown gate, GL-generation
retirement, preview/encoder output identity, recorder native quarantine, standby-microphone
handoff, finite pre-native/storage/still-publication/delete/recovery lanes, review-media shared
worker ceiling and unpublished-result disposal, latest-capture restore ownership, settings and
sensor/zoom coalescers, lifecycle ticker cancellation, and every process-retained registry. I also
re-swept host/device-tool subprocesses, retries, polling deadlines, and cardinality. No second
current-HEAD performance, concurrency, CPU/memory, UI-responsiveness, or causal-state finding
survived validation.

**New finding count: 1.**
