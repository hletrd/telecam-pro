# Performance review — cycle 49

Date: 2026-08-25
Reviewed revision: `69c9c64af89e57ce98408a0a16c8545bfabf69d8`
Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`
Mode: Prompt 1 review; no production source, plan, shared-main, device, deployment, or git mutation

## Scope, inventory, and method

I read `CLAUDE.md` first, then the complete current authority in `docs/ARCHITECTURE.md` and the
complete manual-evidence ledger in `docs/FIELD_CHECKS.md`. I inventoried all 534 tracked paths: 103
production Kotlin/Java files, 255 JVM/Robolectric/Compose/instrumentation/host tests, 39 tool and
device-harness paths, manifests/resources/build inputs, and the documentation/review/plan history.
Historical reviews were treated only as regression oracles and every current conclusion below was
rechecked against current source and callers.

The performance pass traced camera enumeration/open/configure/repeating/capture/teardown, zoom and
sensor fast paths, pseudo-ZSL and full-resolution snapshot budgets, still/DNG encode/publication,
GL producer coalescing and preview/encoder/analysis draws, audio/codec/muxer work, every finite
provider/recovery/delete/review lane, ViewModel tickers and input throttles, Compose publication,
lifecycle replacement, and release/debug variant boundaries. Repository-wide sweeps covered every
thread/executor/scheduler, wait/retry loop, retained collection and queue, per-frame/per-buffer
allocation, diagnostic producer, `BuildConfig.DEBUG` gate, and all changes since the cycle-48
performance baseline.

## Finding

### PERF49-01 — the bounded debug trace dereferences a debug-only payload in release, disabling ordinary still capture

- **Severity / confidence:** High / High.
- **Classification:** **Confirmed release correctness regression caused by performance/trace
  gating.** No device-specific assumption is involved.
- **Exact evidence:**
  - `captureFamilyTraceAdmission` returns `registration=true, settlement=true` for every ordinary
    `DriveMode.SINGLE` capture and `settlement=true` for every in-recording snapshot, independent of
    build variant (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:971-986`).
  - `photoCallback` creates `traceText` only under `BuildConfig.DEBUG`; release therefore stores
    `null` (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4713-4725`).
  - The two consumers are gated only by `traceAdmission.registration` / `.settlement`, not by
    `BuildConfig.DEBUG`, and both force `traceText!!` (`CameraEngine.kt:4727-4743`).
  - The ordinary SINGLE path constructs this callback inside `runCatching`; the immediate
    registration dereference is converted into `PHOTO_CAPTURE_FAILED`, and Camera2 dispatch never
    occurs (`CameraEngine.kt:4152-4178`). The recording-snapshot path skips registration but reaches
    the same null dereference at its terminal settlement edge.
  - The new test asserts only the build-agnostic admission values under the debug unit-test variant;
    it neither constructs the callback with `BuildConfig.DEBUG=false` nor exercises release capture
    (`app/src/test/kotlin/me/hletrd/telecampro/camera/CameraStateTest.kt:165-183`). The authoritative
    host gate is explicitly debug-unit-test based (`app/build.gradle.kts:643-668`), while release
    assembly/lint cannot execute this path.
- **Concrete failure scenario / causal chain:** Install the release app, leave the default SINGLE
  drive selected, and press the shutter. Admission succeeds and a capture family/producer lease is
  registered, but callback construction sees `DEBUG=false`, leaves `traceText=null`, then executes
  the build-independent registration branch and throws on `traceText!!`. `capturePhoto` reports a
  failed photo and returns before `CameraController.capturePhoto`. Thus the Play build cannot take
  its normal still, even though every debug test and debug device harness remains green. An
  in-recording snapshot instead reaches the null dereference after its save lanes settle, interrupting
  producer-terminal cleanup and sequence completion.
- **Competing hypotheses checked:**
  - R8 constant propagation cannot make the path safe: with `DEBUG=false`, it can prove
    `traceText=null`, but `traceAdmission` is runtime data and the dereference remains a required
    throw for admitted SINGLE paths.
  - Sequence drives avoid the problem only because their admission object is all false; this does
    not help the shipping default SINGLE path.
  - `runCatching` prevents a process crash for the immediate registration case, but deliberately
    turns it into a user-visible capture refusal; it is not recovery.
  - Debug device evidence cannot detect the defect because the payload exists exactly in that
    variant.
- **Suggested fix:** derive one effective admission that includes the build gate, or keep each log
  and its payload construction in the same `BuildConfig.DEBUG && admission` branch. Remove both
  non-null assertions. Add a variant-independent pure trace-plan seam (for example, accept an
  explicit `debugEnabled`) and tests for debug/release × ordinary SINGLE/in-REC/sequence cases.
  Add a release-variant JVM or Robolectric regression that constructs/adopts the production callback
  far enough to prove ordinary release capture reaches Camera2 dispatch and terminal cleanup.

## Final missed-issues sweep

I revalidated the coalesced SurfaceTexture producer, fixed-size pseudo-ZSL ring/result windows,
processed-snapshot budget, 256-pixel single-flight analysis readback and CPU riders, GL generation
retirement, preview/encoder ownership, zoom/control throttles, recorder native quarantine, bounded
pre-native/storage/publication/delete/recovery/review workers, microphone ownership/recreation,
settings persistence cadence, Compose focus additions, shader-binding initialization, and host/tool
subprocess deadlines. The cycle-48 timelapse log flood is otherwise closed: sequence admissions are
now false and trace string construction is lazy in debug. No second current-HEAD CPU, memory,
queue-growth, hot-spin, frame-loop, main-thread I/O, or UI-responsiveness defect survived validation.

Open device-only performance evidence remains exactly the front pseudo-ZSL sustained idle and
memory-pressure soak in `docs/FIELD_CHECKS.md` A5; it is not promoted to a code finding here.

**New finding count: 1.**
