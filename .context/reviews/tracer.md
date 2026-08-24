# Causal-tracing review — cycle 50

Date: 2026-08-25
Reviewed revision: `2388819d981d32bc3c59b3e81f75fd4f49fab8bd`
Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`
Mode: review only; no production implementation, commit, deployment, or device mutation

## Complete inventory and trace method

I first inventoried all 535 tracked paths, then examined the complete runtime and evidence graph:
MainActivity/permissions/hardware input; every CameraAction and ViewModel reducer/timer; optics route
inventory, intent, commit, fast-path and rollback generations; Camera2 controller/session/fallback/
capture correlation; GL/EGL preview, encoder, analysis and assist owners; still snapshot, HEIF/JPEG/
DNG publication, exact-family deletion and launch recovery; REC allocation, microphone handoff,
MediaCodec/muxer teardown/quarantine and storage; review/player/ownerless-delete deadlines; settings,
resources and localization; plus every host/instrumented/device test and build/release/attestation
tool. The authority, source, tests, comments, coverage and device ledger were cross-checked rather
than trusted independently. A final sweep revisited every executor, handler post, delayed callback,
retry, mutable packet, process owner, and cross-thread read/write seam.

The full non-device host gate passed (2,103 JVM/Robolectric/Compose tests, debug/androidTest APK
assembly, lint, 99.82% Partition A, 130 tool tests, nine coverage-tool tests, 195 harness self-tests,
152 documentation checks, compilation, and diff checks). No device behavior was run or inferred.

## Finding

### TRACE50-01 — a pipeline command can linearize after rollback with pre-rollback arguments, restoring engine/UI divergence and a refused next REC

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed data race and atomic-packet violation; manifestation requires an
  operator or late inventory pipeline change overlapping an owned optics failure.
- **Exact regions:**
  - ViewModel freezes codec/transfer/candidate arguments before entering the Engine at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2336-2353` (transfer),
    `:2588-2612` (late codec inventory), and `:2855-2871` (codec selection).
  - `CameraEngine.setVideoPipeline` is synchronized only on entry at
    `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:2511-2556`; it correctly derives
    active transfer from current mode under that monitor, but treats the already-frozen external
    codec/candidate/requested-transfer packet as current.
  - Owned rollback restores HEVC/candidates/requested/active transfer and posts the matching UI
    packet under the same Engine monitor at `CameraEngine.kt:764-839`. The ViewModel applies it
    later on main, guarded only by optics generation, at `CameraViewModel.kt:911-960`.
  - When rollback has restored Photo, the queued pipeline command computes
    `tenBitChanged=false` and takes the plain publish branch (`CameraEngine.kt:2541-2556`), so it
    advances no optics generation and cannot supersede the queued rollback publication.
  - The next Video transition updates mode/transfer but not codec/candidates
    (`CameraEngine.kt:2197-2256`), and REC consumes those live fields through
    `recordingEncoderAdmission` at `CameraEngine.kt:5046-5065` /
    `CameraState.kt:993-1016`.
- **Concrete failure scenario and causal sequence:**
  1. Accepted state is Photo/SDR with next-Video HEVC/Main10/HLG.
  2. Photo→Video publishes optimistic Video/HLG and starts optics generation *g*.
  3. Before *g* settles, the operator selects AVC. `onVideoCodec` freezes AVC/Main/SDR candidates
     on main and calls the synchronized Engine method.
  4. The setup thread owns the Engine monitor first, fails *g*, restores Photo/SDR plus the accepted
     HEVC/Main10/HLG tuple, and queues rollback *g* to main.
  5. The blocked codec call then enters `setVideoPipeline`. It sees current Photo, so active transfer
     is SDR and no Camera2 precision boundary changed; it publishes the stale AVC/Main/SDR next-video
     tuple without a new generation.
  6. The call returns and the ViewModel briefly publishes AVC/SDR. The already-queued rollback still
     sees *g* as current and overwrites UI with Photo + HEVC/HLG. Engine remains Photo/SDR but owns
     AVC/SDR for the next Video pipeline.
  7. The next Video tap takes the UI's HLG transfer but leaves the Engine's stale AVC codec/candidates
     untouched. Camera2 may reopen for HLG, while REC admission filters AVC against HLG and returns
     `SELECTED_CODEC_UNAVAILABLE`; the operator sees HEVC/HLG but cannot start the take.
- **Competing hypotheses checked:**
  - `@Synchronized` does not close this race: Kotlin evaluates method arguments before monitor entry,
    and the problematic packet is produced in CameraViewModel, not read inside the synchronized body.
  - Main-thread serialization is insufficient: rollback runs on `setupExecutor` and posts its UI
    repair to main. Main can be blocked at Engine monitor entry after it already froze the arguments.
  - Volatile fields provide visibility only; they do not atomically join caller-built candidates to
    rollback ownership.
  - The optics generation guard cannot reject the rollback post because the Photo fast branch of
    `setVideoPipeline` deliberately creates no generation.
  - Eventual encoder-inventory reconciliation is not guaranteed for a live codec selection after
    inventory is already loaded, and the next mode/REC door consumes the divergence before any
    unrelated reconciliation is required.
  - The new interleave test does not refute the trace: it queues HEVC/HLG behind a rollback that
    restores HEVC/HLG (`ModeRollbackOwnershipRobolectricTest.kt:53-87`), so the stale and restored
    packet owners are indistinguishable and it asserts neither codec nor candidates.
- **Suggested fix:** give video-pipeline mutations a monotonic publication identity independent of
  Camera2 reconfiguration. Linearize the complete external command packet under the Engine monitor,
  and have rollback restore/publish its baseline only if no newer pipeline identity won; alternatively
  advance an ownership generation even for Photo-only next-video changes without falsely clearing
  Camera Ready. Publish the winning packet back to ViewModel so UI and Engine share that same
  identity. Add a disjoint HEVC/HLG rollback versus AVC/SDR queued-command interleave and continue it
  through the next public Video + REC admission.

## Confirmed flows and residual validation

I separately traced the release capture-family diagnostic fix through registration, producer lease,
processed/DNG terminal lanes and deletion retirement; the nullable-safe branch is currently correct.
I rechecked Ready/controller/session ownership, front/rear/DNG route scales, ZSL admission/correlation,
tap-AF/custom-WB owners, preview/EGL retries, recorder allocation/stop/quarantine, microphone
handoff/degradation, post-native storage, latest-capture ordering, whole-family deletion, recovery,
review setup/player deadlines, modal/input ownership, lifecycle teardown, and release provenance.
No second current product defect survived competing-hypothesis validation. A3/A4/A5/D1/E1/E2
remain explicit manual/field evidence gaps, not causal failures established from host source.

## Totals

- Findings: **1**
- Severity: **1 Medium**
- Confidence: **High**
- Confirmed causal product defects: **1**
