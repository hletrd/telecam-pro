# Performance, tracer, debugger, and QA-adversary review — cycle 10

Date: 2026-08-23  
Reviewed revision: `a714d56` (`main`)  
Execution mode: host-only; no current `ANDROID_SERIAL`, install, launch, capture, or device mutation

## Scope, inventory, and method

I read the repository authorities in their required order: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, `.context/README.md`, `docs/TESTING.md`, `docs/FIELD_CHECKS.md`,
`docs/UX_POLICY.md`, the completed cycle-9 plan, and the custom `.claude/agents/qa-adversary.md`.
Historical reviews were used only as an anti-duplication index; every conclusion below was
reproduced from current source.

The executable inventory is 86 production Kotlin files (45,497 lines), 170 host-test Kotlin
files, four instrumented tests, two debug Kotlin files, and 28 Python/shell files under
`device-tests/**` and `tools/**`, plus manifests, resources, Gradle/release configuration, and the
current operating documentation. I inventoried every production owner and its tests/configuration.
The detailed causal sweeps covered:

- Camera2 route inventory/availability, selector caches, optics generations, initial/dual open,
  session fallback, error recovery, request fast paths, capture correlation, ImageReader/ZSL ring
  ownership, watchdogs, and close/post ordering;
- EGL/GL generation replacement, preview/encoder outputs, frame acquisition, scope readback,
  analysis single-flight and retirement, CPU/GPU allocation hot paths, and checked resource release;
- processed/RAW still copying and publication, snapshot backpressure, MediaStore durability,
  recovery, capture-family deletion, review bitmap/player lifetime, and settings persistence;
- REC pre-native allocation, process admission, standby-mic handoff, codec/muxer startup and drains,
  detach/finalization/quarantine, post-native storage dispatch, and repeated-Engine shutdown;
- ViewModel handler tickers, zoom/control coalescers, StateFlow publication, modal/lifecycle cleanup,
  Activity coroutine ownership, and the device/release test harnesses.

The final missed-issue sweep rechecked every executor/thread construction and shutdown site,
`@Volatile`/atomic/monitor boundary, delayed callback, blocking Binder/native call, per-frame
allocation/readback, queue rejection path, and current-vs-accepted state transition. The known
full-resolution ZSL memory/thermal soak, tap-AF churn, zero-submit zoom softness, same-stream loupe,
display-referred HDR/log, and native quarantine costs remain explicitly accepted/deferred facts and
were not re-filed without new evidence.

## Findings

### PTD10-01 — A topology event during REC permanently clears accepted-session readiness

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed code-level lifecycle/correctness defect; physical USB-camera event
  timing remains manual validation.
- **Exact evidence:**
  - Every changed camera-ID set reaches `resolveInitialCameraRouteAvailability(force = true)` from
    the ordered availability callback (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:951-968`).
    A complete changed topology invokes `convergeAfterRouteTopologyChange()` while the engine is
    foregrounded (`CameraEngine.kt:878-931`).
  - Convergence unconditionally starts a new optics transaction (`CameraEngine.kt:976-984`).
    `beginOpticsTransaction` immediately sets `cameraReady = false`, clears `readyController` and
    `acceptedCameraSession`, and publishes Not-Ready (`CameraEngine.kt:413-439`). This occurs before
    any recording guard.
  - `reconfigureCamera` repeats the Not-Ready invalidation, queues setup, and only inside the queued
    task checks `recorder != null`; an active take therefore makes it return without reopening or
    rolling back (`CameraEngine.kt:3127-3164`).
  - Ordinary Stop owns only recorder detach/finalization and preview transfer restoration; it does
    not replay a skipped topology transaction (`CameraEngine.kt:4859-4891`). The ViewModel accepts
    the Not-Ready publication and clears its readiness bit (`ui/CameraViewModel.kt:707-724`), so
    shutter/custom-WB/session-owned actions remain unavailable after the clip.
  - Current topology tests exercise attach/detach/replacement and a same-ID busy event only as a pure
    decision (`app/src/test/kotlin/me/hletrd/telecampro/camera/CameraSelector2Test.kt:222-268`). They
    do not compose an active recorder, the Not-Ready publication, the queued `recorder != null`
    refusal, Stop, and eventual Ready convergence.
- **Concrete causal trace:** record on the built-in rear route -> attach any external camera -> the
  callback observes a larger ID set -> the Engine keeps BACK as the target but opens a new optics
  generation and publishes Not-Ready -> setup sees the active recorder and silently exits -> the
  take can still be stopped and finalized, but no callback restores the cleared accepted session.
  The preview may continue drawing the old stream while the UI correctly believes no accepted
  session exists; later shutter/REC attempts refuse as “reconfiguring” until another lifecycle or
  optics event happens to reopen it. Detaching/replacing the active external route has the same
  skipped-convergence window, with Camera2 failure recovery as a competing but timing-dependent
  recovery owner.
- **Competing hypotheses checked:** same-ID availability noise is filtered at
  `CameraEngine.kt:954-959`, so merely opening the app's own camera does not trigger this. A Camera2
  error can independently schedule recovery, but attach of an unused external camera causes no
  active-controller error. Stop has no route-convergence hook, and `reconfigureCamera` has no
  rollback on its recorder guard. The state is therefore not self-healing in the harmless-attach
  scenario.
- **Suggested fix:** decide topology convergence under the recorder owner before opening an optics
  transaction. While REC is active, publish the new inventory/caches but queue one latest-wins
  topology generation; replay it after native recorder finalization releases ownership. If a route
  must be lost immediately, claim/finalize that recorder through the existing active-camera-failure
  owner and then reconfigure. Add a deterministic Engine seam test for BACK recording + external
  attach, active EXTERNAL recording + detach/replacement, Stop/finalization, and exactly one final
  Ready publication.

### PTD10-02 — Post-native storage is bounded per Engine, not process-wide across blocked old Engines

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed resource-bound defect; manifestation requires blocked
  MediaProvider/MediaExtractor tails plus repeated Engine recreation.
- **Exact evidence:**
  - Every `CameraEngine` constructs a fresh `RecordingStorageDispatcher` with two workers and an
    eight-item queue (`CameraEngine.kt:104-110`; `camera/RecordingStorageDispatcher.kt:24-43,
    136-137`). Unlike pre-native allocation, there is no process-lifetime singleton owner.
  - `CameraEngine.release()` calls only `recordingStorageDispatcher.shutdown()`
    (`CameraEngine.kt:5757-5763`). The dispatcher intentionally uses `shutdown()`, not interruption,
    so accepted synchronous provider/extractor tasks keep running and queued tasks remain owned
    (`RecordingStorageDispatcher.kt:52-56`). This is correct for durability inside one Engine but
    does not enforce a process cap after a replacement Engine is created.
  - ViewModel teardown launches Engine release asynchronously and a later ViewModel may construct a
    replacement Engine in the same process (`ui/CameraViewModel.kt:3521-3541`). Two indefinitely
    blocked workers from each retired Engine can therefore accumulate; each replacement also gains
    two fresh workers and eight fresh backlog slots.
  - The architecture currently claims that this dispatcher “bounds provider work process-wide”
    (`docs/ARCHITECTURE.md:243`), but the implementation provides only a per-instance bound.
    `RecordingStorageDispatcherTest` creates and saturates one dispatcher at a time
    (`app/src/test/kotlin/me/hletrd/telecampro/camera/RecordingStorageDispatcherTest.kt:16-169`);
    it has no two-Engine/recreation assertion.
- **Concrete causal trace:** two finalized tails in Engine A wedge in synchronous provider work ->
  A is cleared/released, whose non-interrupting shutdown preserves both threads -> Engine B starts
  in the same process and creates two more storage workers -> repeat relaunches to grow daemon
  threads, Binder/native extractor state, queued tail closures, and retained old-Engine graphs
  without a process-wide ceiling. The app's same-Engine next-REC guarantee still holds, but repeated
  lifecycle recreation can eventually produce memory/thread pressure or process death.
- **Competing hypotheses checked:** `shutdown()` refuses new submissions to the old instance but does
  not stop active tasks or prevent construction of the next instance. The process-wide
  `ProcessRecordingPreNativeAllocator` bounds only pending-row creation and cannot accept frozen
  post-native tails. Launch recovery owns overflow rows, but already accepted blocked tasks stay in
  the old executor rather than being converted back to recovery work.
- **Suggested fix:** move post-native dispatch capacity behind a process-lifetime bounded owner, as
  pre-native allocation already does, or introduce one global semaphore/worker budget shared by all
  dispatcher generations. Keep per-Engine callback/presentation identity inside each task and retain
  the existing overflow-to-pending recovery behavior. Add a two-Engine barrier test: saturate A,
  release it without unblocking provider calls, construct B, and assert total active workers never
  exceeds the documented process cap.

## QA-adversary gate

The mandatory command passed:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest
BUILD SUCCESSFUL in 2m 59s
52 actionable tasks: 7 executed, 45 up-to-date
```

No TODO/FIXME/HACK regression or unexplained ignored/disabled test was found in current executable
sources. Existing Android lint suppressions are narrow framework/permission boundaries already
documented in source; none masks either finding above.

| Feature | Result | Evidence |
|---|---|---|
| Debug assembly and host unit tests | PASS | Mandatory Gate 1 completed successfully. |
| Authorized install, launch, crash scan | BLOCKED BY DIRECTIVE | No current `ANDROID_SERIAL`; no prior identity may be reused. |
| Mode-aware BACK/FRONT/EXTERNAL selection | BLOCKED BY DIRECTIVE | Device/deployment execution was not authorized; PTD10-01 is static evidence. |
| Preview render and upright orientation | BLOCKED BY DIRECTIVE | Requires a live, oriented scene. |
| Program exposure default | BLOCKED BY DIRECTIVE | Requires live Camera2/scene evidence. |
| PASM behavior | BLOCKED BY DIRECTIVE | No device interaction authorized. |
| ISO/shutter snapping | BLOCKED BY DIRECTIVE | No device interaction authorized. |
| Continuous/manual focus | BLOCKED BY DIRECTIVE | Requires an optical target and device. |
| Tap-to-focus hold/reset | BLOCKED BY DIRECTIVE | Requires device interaction and Camera2 result evidence. |
| HEIF/JPEG/DNG format gating | BLOCKED BY DIRECTIVE | No device session/capture authorized. |
| Photo capture and pulled-file validation | BLOCKED BY DIRECTIVE | No MediaStore mutation or file pull authorized. |
| Whole-shot delete and late siblings | BLOCKED BY DIRECTIVE | Destructive media exercise was not authorized. |
| Video record/save/audio/container | BLOCKED BY DIRECTIVE | No recording or artifact pull authorized. |
| OIS/EIS comparison | BLOCKED BY DIRECTIVE | Requires a handheld physical scene. |
| Overlay rendering and update cadence | BLOCKED BY DIRECTIVE | Requires live GL/device observation. |
| Nine-tab settings sheet | BLOCKED BY DIRECTIVE | Static sources retain nine tabs; interactive gate requires device execution. |
| MR/settings restore | BLOCKED BY DIRECTIVE | Requires state mutation and restart/device evidence. |
| Rapid TELE/mode/exposure transitions | BLOCKED BY DIRECTIVE | Adversarial device input not authorized. |
| Background/foreground preview and REC | BLOCKED BY DIRECTIVE | Device lifecycle exercise not authorized. |
| Keyguard launch | BLOCKED BY DIRECTIVE | Device lifecycle exercise not authorized. |
| Photo-format invariant | BLOCKED BY DIRECTIVE | Device interaction not authorized. |
| TELE zoom snap/cap reversal | BLOCKED BY DIRECTIVE | Physical/instrumented multitouch not authorized. |
| Delete during multi-output save | BLOCKED BY DIRECTIVE | Capture/delete mutation not authorized. |
| External attach/detach during recording | BLOCKED BY DIRECTIVE | No authorized external-camera session; PTD10-01 provides confirmed static trace. |

**QA verdict: GATE BLOCKED — Gate 1 passed; Gates 2–4 are BLOCKED BY DIRECTIVE because no current
authorized device serial was supplied.**

## Coverage statement

All production owners and relevant host/instrumented tests, Android build/resources, Python device
harnesses, release/coverage tooling, and current authority documents were inventoried. Binary fonts,
marketing images, historical device captures, and archived reports were catalogued but were not
treated as executable code or fresh device evidence. No relevant executable file or current
performance/lifecycle authority was intentionally skipped. The two findings above are the only new
confirmed issues from this combined review.
