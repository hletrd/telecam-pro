# Architecture review — cycle 50

Date: 2026-08-25

Reviewed revision: `2388819d` (`origin/main`)

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

## Inventory and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely, then inventoried
all 536 repository files. The architecture inventory contains all 103 production Kotlin/Java files,
241 JVM/instrumented tests, manifests/resources/build inputs, the device harness, and the durability,
release, and documentation gates. I traced every production module in the architecture map and its
relevant tests rather than sampling only the recently changed files.

The cross-file pass followed Activity/ViewModel input and lifecycle ownership; Engine optics,
accepted-session, callback, capture, and REC state machines; Controller fallback and Camera2 thread
ownership; renderer/EGL generations; processed/RAW/video save lanes; process-finite dispatchers;
MediaStore marker/family recovery; review workers; capability-normalized UI state; and immutable
build evidence. The final sweep specifically rechecked the cycle-49 changes against rollback,
recording admission, release builds, modal focus, and obscured-input terminals.

## Findings

### A50-01 — REC snapshots the session under the packet lock, then reads the packet after unlocking

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed source/JVM-memory-model race; runtime manifestation not device-observed.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:2511-2556`
  publishes codec, ordered candidates, requested transfer, and active transfer under the Engine
  monitor; rollback restores the same tuple under that monitor at `:764-793`. In contrast,
  `currentAcceptedRecordingSession()` locks only while returning the accepted Camera2 identity at
  `:4947-4958`; `beginRecordingAllocation()` then reads `videoFrameRate`, `caps`, `videoSize`,
  `videoCodec`, `transfer`, and `videoEncoderCandidates` independently after that lock has ended at
  `:5043-5065`.

`@Volatile` makes each field visible but does not make that multi-field selection atomic. A
concurrent pipeline commit or owned optics rollback can therefore interleave between those reads.
For example, REC can read the old HEVC codec, then a setter publishes an AVC/SDR packet, then REC
reads the new AVC candidates. `recordingEncoderAdmission` rejects that impossible hybrid as
`SELECTED_CODEC_UNAVAILABLE` even though both the before and after packets are individually valid.
The helper's exact codec/transfer filter fails closed, so I found no path from this race to an
incompatible encoder start; the confirmed user-visible failure is a spurious refused REC attempt.
`setVideoPipeline` also gates only on `recorder`, not the separate start-admission latch, so the
pre-native window is not a mutation exclusion boundary.

This directly defeats the packet invariant recorded in `docs/ARCHITECTURE.md:308-314` and
`CLAUDE.md:867-871`. The cycle-49 rollback test proves that the writer is synchronized and tests the
pure admission decision with a preassembled list; it never forces the production reader across a
packet publication, so it cannot detect this race.

**Suggested fix:** under one Engine-monitor section, obtain the accepted session and freeze every
REC decision input (caps/size/rate, codec, transfer, candidates) into `RecordingAdmissionSnapshot`.
Keep slow capability filtering and native work outside only if they consume that immutable snapshot.
Add a latch-controlled production-path test that blocks the reader between old/new packet states and
asserts that it observes either complete tuple, never a hybrid. Recheck session identity again at the
existing later boundaries.

### A50-02 — the Ready terminal invokes a UI callback while the optics monitor is held

- **Severity / confidence:** Low / High
- **Classification:** Confirmed ownership-contract violation; no production deadlock observed.
- **Exact regions:** `CameraEngine.commitOpticsReady()` explicitly says external callbacks must not
  run under the optics monitor at `CameraEngine.kt:628-630`, but its `OpticsCommitGate.commit`
  terminal calls `onCameraPolicyBlocked(false)` inside the locked mutation at `:633-677` (the
  callback is at `:675`). The ordinary caps/Ready callbacks are correctly deferred until after the
  commit at `:678-684`. `OpticsCommitGate.commit` itself holds the Engine monitor around the whole
  terminal mutation at `:7265-7285`.

The current ViewModel callback happens to perform only a `StateFlow.update`, but the Engine callback
boundary is replaceable and guarded by `EngineCallbackSink`; invoking it under the state-machine
lock permits a future/UI callback or an unconfined state collector to re-enter Engine operations
halfway through Ready publication, and it extends the monitor hold across callback-sink locking and
arbitrary consumer work. It also makes the authoritative statement that external callbacks run
after unlock (`docs/ARCHITECTURE.md:301-307`) false.

**Suggested fix:** capture a `policyUnblocked` publication flag inside the terminal mutation, then
invoke `onCameraPolicyBlocked(false)` beside `beforeReadyPublication` / `onCameraReadyChange` after
`OpticsCommitGate.commit` returns. Add a callback that attempts a competing optics operation and
asserts it cannot execute inside the terminal critical section.

## Final missed-issue sweep and validation boundary

The complete debug JVM/Robolectric/Compose suite passed (`:app:testDebugUnitTest`). The authoritative
host gate could not start because this clone's conventional Android SDK lacks the stable Emulator
`glslangValidator`; that is an environment limitation, not a test failure. `tools/check_docs.py`
passed 152 checks with 24 declared optional-private skips. No emulator/device run was performed, so
no camera, pixel, audio, orientation, or visual-runtime claim is made here.

I rechecked accepted-vs-requested session truth, release-safe capture tracing, rollback sequencing,
finite provider lanes, family retirement, EGL teardown, and callback retirement after identifying
the two findings. No additional independent architectural defect survived the final sweep.

---

## Archived prior review

# Architecture review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27` (`origin/main`)

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## System inventory and architectural sweep

I read the complete committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) and inventoried all 534 tracked paths. The architecture pass traced the
Activity/ViewModel unidirectional boundary; CameraEngine optics and capture orchestration;
CameraController accepted-session/fallback ownership; RendererAssists/GlPipeline/EGL generations;
processed and RAW save lanes; video pre-native/native/storage owners; exact-family MediaStore
durability, deletion, and recovery; review work pools; settings/MR normalization; capability-driven
UI projection; and immutable build/release evidence.

The final cross-boundary sweep rechecked the system's load-bearing rules: requested state versus
accepted session truth, generation-owned rollback, exact native identities, finite process queues,
producer termination before family retirement, debug diagnostics as non-functional observers, and
release behavior matching debug behavior apart from explicitly absent instrumentation.

## Finding

### A49-01 — a debug evidence payload is a required release capture dependency

- **Severity / confidence:** High / High
- **Classification:** Confirmed architecture and implementation defect.

`CaptureFamilyTraceAdmission` is modeled as runtime capture state even though its contract says it is
debug harness evidence. `CameraEngine.photoCallback` then creates the corresponding payload only
under `BuildConfig.DEBUG` (`CameraEngine.kt:4713-4725`) but consumes admission outside that boundary
(`CameraEngine.kt:4727-4743`). Because `captureFamilyTraceAdmission` admits every ordinary Single
shot and every in-REC snapshot settlement (`CameraState.kt:971-986`), release Single callback
construction dereferences a null debug payload. The enclosing public path catches the exception and
returns `PHOTO_CAPTURE_FAILED` before Camera2 dispatch (`CameraEngine.kt:4164-4183`). The settlement
variant can throw before producer-terminal publication and family-lease release
(`CameraEngine.kt:4737-4751`).

This violates two explicit architecture laws at once:

- diagnostics must be observational and absent from release without changing product control flow;
- every still-family producer must reach exactly one terminal edge before deletion/recovery may
  retire its authority.

The layering error is that build policy, diagnostic admission, diagnostic payload creation, and
capture-family lifecycle are four separate decisions. Their invalid combination is representable:
`DEBUG=false + admitted=true + payload=null`, and production uses that state on the default shutter
path. The cycle-48 test covers only the admission reducer under the debug variant, while release
assembly proves bytecode construction but executes no shutter behavior. The green authoritative
host gate therefore gives false assurance about the release architecture.

**Fix direction:** Collapse those decisions into one build-aware optional trace object (or a logger
whose release implementation is a no-op) and let capture lifecycle consume only that interface.
Registration/settlement observation must wrap no ownership mutation and cleanup must remain
unconditional. Add an explicit build-mode matrix around callback creation and producer settlement,
plus a release-variant public capture smoke test. The invariant should be structural: disabling a
diagnostic must remove output only, never data or control-flow dependencies.

## Architectural debt and final sweep

The 7,000-plus-line CameraEngine facade remains the previously recorded deferred decomposition item.
A49-01 does not justify a wholesale rewrite; it identifies a narrow extraction boundary for
capture-family tracing that should become an optional observer beside, not inside, producer
lifecycle ownership.

I also rechecked the new video-pipeline transaction packet, rollback publication, shader binding
authority, obscured gesture cancellation, modal focus ownership, release permission allowlist, and
screenshot validation against their consumers and tests. No second independent architecture
finding survived the final sweep. Open field checks remain validation risks rather than architecture
defects, and no device-only claim was promoted.
