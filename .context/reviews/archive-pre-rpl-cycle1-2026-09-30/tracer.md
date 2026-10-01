# Causal-tracing review — cycle 51

Date: 2026-08-25
Reviewed revision: `7eb4ee951e769afe884f8115ffbde25c828028a3` (`origin/main`)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle51.WTu2dW`
Mode: review only; no implementation, commit, push, deployment, device mutation, or shared-main
access

## Complete inventory and trace method

I inventoried all 538 tracked paths and traced the complete runtime/evidence graph rather than a
sample: 103 production modules, 240 host test files, four instrumented test files, 14 device-harness
files, 25 tools, 17 resources/manifests, 11 build inputs, 65 docs/assets, root authorities, and 44
tracked review-context paths. I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md` completely before tracing Activity/permission/input, ViewModel state/timers,
optics intent/commit/rollback, route/session/capture correlation, GL preview/encoder/analysis,
stills/DNG/storage, REC allocation/mic/native teardown, review/delete/recovery, UI, tests, coverage,
release tools, and device evidence.

The final causal sweep revisited every executor, Handler post, delayed task, retry/backoff,
volatile/multi-field packet, atomic generation, monitor boundary, callback identity, native owner,
provider call, and requested-versus-accepted truth. Focused pipeline/REC tests passed and the docs
gate passed 153 checks (24 declared private skips); no device behavior was run or inferred.

## Findings

### TRACE51-01 — REC’s claimed immutable packet is discarded before setup, allowing torn GL and file transfers

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed data race and encoded-output truth violation; manifestation needs a
  same-source-precision curve edit during the bounded REC start window.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5027-5061` snapshots
    `frameRateAvailable`, codec, transfer, and candidates, but the resulting
    `RecordingAdmissionSnapshot` retains only filtered candidates plus a session-only
    `isCurrent` predicate. Size, selected frame rate, and transfer are not carried forward.
  - `continueRecordingAfterAllocation` forwards only `admission.encoderCandidates` at
    `CameraEngine.kt:5387-5399`.
  - `startRecordingClaimed` then reads live `videoSize`, live `videoFrameRate`, and live `transfer`
    twice at `CameraEngine.kt:5462-5483`: once for `glTransfer`, once for `fileTransfer`.
    `fileTransfer` configures `VideoRecorder.start` at `:5580-5593`, while the independently frozen
    `glTransfer` is posted to the admitted GL owner at `:5659-5663`.
  - `setVideoPipeline` at `CameraEngine.kt:2571-2611` treats HLG/S-Log3/S-Log3.Cine/LogC3 changes as
    the same HLG10 source-precision class, so those edits update the live transfer without
    invalidating the accepted Camera2 session used by `admission.isCurrent`.
- **Concrete causal sequence:**
  1. Video is Ready under HEVC Main10 + HLG; REC snapshots that accepted session and packet.
  2. Pending-row allocation or mic handoff delays native setup while `recorder` is still null.
  3. The operator selects S-Log3. Both HLG and S-Log3 require the already-accepted HLG10 source, so
     no optics/session generation changes; the old admission remains current.
  4. Native setup reads `transfer` as HLG for `glTransfer`.
  5. The main-thread pipeline command publishes S-Log3 before the second volatile read.
  6. Native setup reads S-Log3 for `fileTransfer`. The shader bakes HLG while the MediaFormat/file
     path is tagged S-Log3 (the reverse interleave is also possible).
  7. The take can publish successfully: session identity, codec, candidate, muxer, and storage
     ownership are all otherwise valid, so no later terminal repairs the semantic mismatch.
- **Competing hypotheses checked:** session-current checks stop SDR/non-SDR or route/size/FPS
  reconfiguration races, but not same-precision log-curve changes. Volatile reads guarantee
  visibility, not equality across two reads. `recorder == null` remains true during the relevant
  pre-publication window, so transfer changes are allowed and pushed to GL. The new snapshot test
  observes only the early packet then deliberately takes an injected terminal before
  `startRecordingClaimed`, so it does not refute the trace.
- **Suggested fix:** make `RecordingAdmissionSnapshot` the actual native setup contract. Carry exact
  size, frame-rate/capture-rate, codec, accepted transfer, and ordered candidates and derive both GL
  and file transfer from its single transfer field. Decide and test an explicit linearization policy
  for a same-precision edit after REC admission (old complete packet or abort/retry), never live
  mixing. Add a forced interleave between the former reads through the production setup seam.

### TRACE51-02 — rollback preserves a newer same-precision pipeline in Engine/UI but overwrites GL with the old curve

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed cross-owner divergence; manifestation needs an owned optics failure
  overlapping a newer HLG/log pipeline command.
- **Exact regions:**
  - `CameraEngine.publishVideoPipelineLocked` at
    `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:505-520` advances one complete
    codec/candidate/requested/active-transfer publication.
  - `beginOpticsTransaction` records the pipeline generation owned by that optics intent at
    `CameraEngine.kt:614-641`.
  - `rollbackOptics` correctly preserves a newer publication at `CameraEngine.kt:824-834`; its
    `restoredVideoPipeline` then describes the actual winning Engine/UI packet.
  - The next statement nevertheless posts `before.transfer` to GL at `CameraEngine.kt:835` rather
    than `restoredVideoPipeline.activeTransfer`. `GlPipeline.setTransfer` is an asynchronous ordered
    handler post at `app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:550`, so the rollback's
    later stale post wins over the newer curve command on the live GL lane.
  - ViewModel generation handling at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:911-955` correctly withholds old
    codec/transfer fields when the pipeline generation is newer, leaving UI and Engine agreeing
    while GL alone diverges.
- **Concrete causal sequence:** Video/HLG starts a lens or other optics transaction. The operator
  selects S-Log3 while that transaction is pending; because both need HLG10 source precision, the
  pipeline command publishes without a replacement optics generation and queues S-Log3 to GL. The
  optics attempt fails. Rollback sees the newer pipeline generation and retains S-Log3 in Engine/UI,
  then queues its baseline HLG to GL after the S-Log3 command. The viewfinder/encoder renderer ends
  on HLG despite S-Log3 remaining selected and persisted. No convergence callback is required, so
  the divergence can persist until another explicit GL replay/restart.
- **Competing hypotheses checked:** the bug is sign-neutral in Photo because both active transfers
  are SDR, which is why the existing AVC/SDR test misses it. The pipeline-generation check protects
  Engine and delayed UI publication only; it does not alter the literal GL argument. `applyStabilization`
  later in rollback does not reapply transfer. A future GL generation would seed from the correct
  Engine field, but the active generation is not restarted on a retained-session rollback and is
  precisely where the stale handler post lands.
- **Suggested fix:** post `restoredVideoPipeline.activeTransfer` (or one complete winning pipeline
  packet) to the exact current GL owner after rollback selection. Add a Video-mode HLG versus
  S-Log3 same-precision interleave with an observable GL sink and assert Engine, UI, persisted
  settings, GL, and next REC converge on one packet.

## Confirmed flows, limits, and final missed-issue sweep

I separately rechecked Ready/controller/session identity, nested/superseded optics rollback,
front/rear/DNG route scales, zoom landing/fast paths, ZSL correlation, tap-AF/custom-WB owners,
preview/EGL replacement, processed/DNG family publication, durable deletion/recovery, recorder
allocation/stop/quarantine, microphone degradation, post-native storage, latest-capture ordering,
review deadlines/bitmap ownership, system-delete modality, lifecycle teardown, input security,
localization, and release provenance. No third causal defect survived competing-hypothesis
validation.

No Camera2 HAL, GLES fault injection, MediaProvider, microphone, HDR display, physical control,
converter, or deployment ran. A3/A4/A5/D1/E1/E2 remain explicit field evidence gaps.

## Totals

- Findings: **2**
- Severity: **2 Medium**
- Confidence: **2 High**
- Confirmed causal product defects: **2**

---

## Archived prior review

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
