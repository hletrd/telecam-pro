# Performance, concurrency, tracer, and debugger review — cycle 52

Date: 2026-08-25  
Reviewed revision: `96732cc97ce8ff7f333478084eb365333ac505b6`  
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle52.868ovy/repo`

## Scope, inventory, and method

I read `CLAUDE.md` completely first, then the complete current authorities in
`docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`. I built the inventory before reviewing behavior:
540 tracked paths, including all 103 production Kotlin/Java modules, all 240 JVM/Robolectric/Compose
test files, all four instrumented-test files, all 39 `tools/**` and `device-tests/**` paths, and the
remaining manifests, resources, build/provenance inputs, documentation, and assets. The optional
private maintainer documents named by `CLAUDE.md` are absent from this clean clone, as the committed
fallback policy permits.

The runtime pass traced every Camera2 handler/session/image/watchdog path; Engine optics, Ready,
preview, REC, and callback generations; GL/EGL outputs, frame coalescing, analysis snapshots, and
renderer initialization; processed/RAW/video encode and publication; every bounded provider lane,
durable family/URI authority, recovery, and review worker; standby and recording `AudioRecord`
ownership; ViewModel/Compose tickers, throttles, bitmap/player lifetimes, and lifecycle teardown; and
all executors, queues, monitors, atomics, latches, deadlines, retry schedulers, and retained native
owners. I separately traced the cycle-51 renderer, review-spool, REC-packet, camera-policy, and
family-marker changes through their callers and terminal paths. Comments and passing tests were
treated as hypotheses rather than authority.

## Findings

### PC52-01 — process-quarantine revocation can drop or clean up the wrong standby `AudioRecord` owner

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed native-lifetime state-machine defect; the exact revoke-after-return
  interleave requires fault injection or a concurrent native quarantine on device.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:454-460` executes
    `audioSetup.create()` inside a Boolean `runNativeAcquisition`. If the process gate admitted the
    call, `AudioRecord` construction returned `Ready`, and quarantine closed before the lease return,
    the Boolean is false and the function returns before copying the new input into `audioInput` or
    binding it to `terminationOwner`. The `Ready` input then loses every deliberate strong owner.
  - The analogous start edge at `StandbyAudioController.kt:476-491` calls `input.start()` through the
    same Boolean seam. A false result cannot distinguish “never entered” from “native start returned
    after revocation.” In both cases the ordinary `finally` at `:543-548` enters
    `finishAndRelease`, whose `:140-185` path calls `stop` and `release` rather than retaining the
    revoked native graph.
  - The actual process gate documents and implements that distinction in
    `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:1170-1213`: an admitted native
    block runs outside the gate, and a concurrent close makes its eventual return false. Recorder
    code uses a typed local result to reject cleanup after that boundary (`VideoRecorder.kt:167-189`),
    while standby audio collapses both outcomes to Boolean.
  - Process quarantine retains only the owner explicitly supplied to it
    (`VideoRecorder.kt:1392-1412`). A concurrently created standby input is not that camera/recorder
    owner and is not added to either process retention list. The focused standby tests cover intent
    changing after setup and pre-entry refusal, but none injects `runNativeAcquisition { block();
    false }`; see `StandbyAudioControllerTest.kt:721-761` and the fixture default at `:52-84`.
- **Failure scenario:** the armed Video meter begins constructing or starting `AudioRecord` while a
  Camera2 teardown timeout quarantines an unrelated uncertain camera graph. Construction/start
  returns, but the process lease is now revoked. On the construction branch the initialized input is
  dropped and can later be finalized outside the deliberate quarantine owner; on the start branch the
  app calls `stop/release` after quarantine won. Either outcome violates the process rule that a late
  admitted native return may neither publish nor be ordinarily cleaned up, making native lifetime
  depend on GC or a post-terminal cleanup race.
- **Competing hypotheses checked:** a standby process token does not retain the concrete input; it is
  cleared by quarantine and its release callback carries no resource. The meter closure retains a
  value only after `audioInput` is assigned, which the setup-revoked branch deliberately skips.
  `canStart()` changing after a normally admitted setup is a different, safely released path and is
  the only nearby test case.
- **Concrete fix:** replace the Boolean standby acquisition seam with a typed result distinguishing
  pre-entry rejection, returned-and-current, and returned-but-revoked. On revoked create/start, move
  the exact input/termination owner into process-long quarantine retention and prohibit ordinary
  stop/release. Add deterministic tests for revocation immediately after successful create and
  immediately after successful start, asserting no publication, no cleanup, strong retention, exact
  generation completion, and restart-required process truth.

### PC52-02 — a blocked standby `AudioRecord.stop()` has no deadline or quarantine terminal

- **Severity / confidence:** High / High for the code-level liveness gap; triggering the vendor
  `AudioRecord.stop()` hang remains a manual/fault-injection risk.
- **Classification:** Confirmed unbounded wait and native-owner lockout path.
- **Exact evidence:**
  - Disable and REC handoff dispatch the exact input's `stop()` off-caller through
    `StandbyInputTerminationOwner.requestStop` at
    `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:127-134,188-210`; the
    production dispatcher creates one daemon `StandbyAudioStop` thread at `:320-323`.
  - The meter worker then loops on `stopCompleted` in `finishAndRelease` at `:140-177`. Its 25 ms
    slice is only an interrupt/rejected-dispatch fallback; there is no elapsed deadline, terminal
    classification, or quarantine action. Once the stop task has been accepted, `stopClaimed` never
    returns to false, so a blocked native stop makes the loop permanent.
  - Until that loop finishes, input release, `liveInputTermination` clearing, process-standby-token
    release, `ownership.complete`, and the owner release latch are all stranded behind
    `StandbyAudioController.kt:543-566`.
  - REC waits only 400 ms for that latch in
    `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5522-5529`, reports
    `MICROPHONE_BUSY`, and aborts. Because no process quarantine was published and the old standby
    token/input remain live, every later REC attempt and every replacement Engine's standby claim can
    repeat the refusal indefinitely. Existing blocked-read tests make `stop()` return and explicitly
    unblock the read (`StandbyAudioControllerTest.kt:300-409`); no test blocks the stop call itself.
- **Failure scenario:** the standby meter is reading while the app backgrounds or the operator presses
  REC. `AudioRecord.stop()` enters a wedged vendor audio service and never returns. The caller stays
  responsive, but the mic/native owner, worker, stop thread, process token, and release latch are
  retained forever; recording is permanently unavailable and background microphone ownership may
  remain visible until the user happens to kill the process. Unlike every Camera2/GL/codec uncertain
  release path, the UI never receives restart-required truth.
- **Competing hypotheses checked:** the bounded 400 ms REC wait protects the recorder executor only;
  it does not terminate or reclassify the standby owner. Repeated disable is exactly-once and therefore
  cannot start a replacement stop. Thread interruption only causes another wait slice. The process
  quarantine gate cannot help because this path never closes it.
- **Concrete fix:** give the exact standby termination owner an independent scheduled hard deadline.
  If stop has not returned, atomically terminally abandon the input, strongly retain the input and
  termination owner through `UnsafeRecorderQuarantine`, close process native admission, release the
  handoff latch without authorizing REC, and make any late stop/worker return inert so it cannot call
  `release`. Surface the existing restart-required status. Tests should block stop across REC, pause,
  Engine release/replacement, deadline, and late return, proving bounded caller/work-queue behavior,
  one quarantine terminal, no native release, and no second microphone.

## Tracer/debugger competing-hypothesis results

- The new review spool does ignore a false `File.delete()` result before releasing its byte lease,
  but no accumulating production path survived validation: all owned streams close first, internal
  cache files remain removable through parent-directory authority despite `setReadOnly`, Unix permits
  unlink with open descriptors, an already-absent/renamed file consumes no space, and a persistent
  cache-directory refusal also prevents later temp-file creation. This is not counted as a finding.
- The claimed atomic REC packet is not torn by interactive size/FPS changes. UI setters and REC are
  main-confined; optimistic REC state is published before Engine admission and rejects later setters.
  A size change ordered first synchronously invalidates Ready through `reopenForSession`, so REC
  refuses the old stream. Normal FPS does not change session topology and its Camera2 controls post
  before a subsequently ordered REC action. Initial codec reconciliation cannot supply a stale late
  packet: without inventory the encoder candidate list is empty and REC refuses, while a cached
  inventory applies synchronously during ViewModel initialization.
- The cycle-51 family-deletion fix now reserves one of 32 process-wide family units before publishing
  the Engine tombstone, stores at most one compact callback per reservation, performs no overflow I/O
  inline, and releases capacity in the executor wrapper's `finally`. It no longer queues behind the
  unbounded still-encoding executor.
- Frame notifications are latest-coalesced; analysis is generation-local/single-flight; ZSL rings,
  provider lanes, review workers/source bytes, retained delete families, rejected outputs, and
  recording storage are finite. Main tickers are lifecycle-removed and change-gated. No second
  unbounded queue, busy-spin, per-frame allocation/log flood, monitor-order cycle, or stale native
  publication survived the final causal sweep.

## Verification and limits

Focused host tests passed:

```text
:app:testDebugUnitTest
  StandbyAudioControllerTest
  RecorderQuarantineAdmissionGateTest
  CameraEngineRecordingPreNativeTest
BUILD SUCCESSFUL
```

Those tests confirm the current documented cases but do not exercise either finding's missing
interleave. No device, deployment, Camera2/Audio HAL fault injection, microphone route change,
converter, or MediaProvider mutation was performed. Field checks A3, A4, A5, D1, E1, and E2 remain
manual evidence obligations; A5's front full-resolution repeating-YUV memory/thermal soak is an open
validation risk, not a host-confirmed defect.

## Final missed-file and skipped-file sweep

I re-ran the tracked inventory against the reviewed subsystem map and direct tests: no production
module, asynchronous owner, build/runtime manifest, or host/device tool was skipped. Binary fonts,
PNG/SVG store assets, the Gradle wrapper JAR, and license/privacy prose were checked at their
packaging/provenance boundaries rather than decoded as executable concurrency surfaces. The final
cross-check covered cleanup after partial native acquisition, shutdown/rejection after reservation,
late callbacks after owner replacement, timeout-vs-completion races, lock ordering, and bounded
collection units. Only the two standby-audio terminals above survived.

**Finding count: 2.**
