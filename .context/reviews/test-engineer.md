# Test-engineer review — cycle 51

Date: 2026-08-25
Reviewed revision: `7eb4ee951e769afe884f8115ffbde25c828028a3` (`origin/main`)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle51.WTu2dW`
Mode: review only; no production implementation, commit, push, deployment, device mutation, or
shared-main access

## Complete inventory and method

I inventoried all 538 tracked paths before reviewing. The complete executable/evidence inventory was
all 103 Kotlin/Java production files under `app/src/main`, all 240 JVM/Robolectric/Compose test
files (2,112 `@Test` methods), all four `androidTest` files (seven tests), all 14 device-harness
files, all 25 host/coverage/release tools, all 17 main resources/manifests, all 11 Gradle/version/
wrapper inputs, all 65 committed docs/assets, and the root legal/privacy/build plus 44 tracked
review-context paths. I read `CLAUDE.md` completely first, then `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md` completely, and cross-checked the current cycle-50 plan, prior provenance,
coverage manifests, README/device-harness authority, implementation, tests, tools, and docs rather
than trusting any one layer.

The exhaustive test pass inspected every test skip/early return, assertion-light probe,
source/reflection contract, latch/barrier, delayed callback, mutable packet, effect declaration,
coverage classification, and production caller. The source-to-test sweep covered lifecycle,
permissions/input, Camera2 routes/sessions/capture correlation, optics and rollback generations,
zoom/3A, GL/EGL preview/encoder/analysis, still/DNG/video/storage durability, review/delete/recovery,
UI/accessibility/localization, and build/release/device tooling. Only the two findings below survived
the final competing-hypothesis and mutation-sensitivity sweep.

The focused current tests passed:

`./gradlew :app:testDebugUnitTest --tests 'me.hletrd.telecampro.ui.ModeRollbackOwnershipRobolectricTest' --tests 'me.hletrd.telecampro.camera.CameraEngineRecordingPreNativeTest'`

with the documented JDK/SDK environment. `python3 tools/check_docs.py` also passed 153 checks with
24 declared optional-private skips. Those greens are evidence for the false-positive gaps below,
not evidence against them.

## Findings

### C51-TEST-01 — the “immutable REC snapshot” test stops before production native setup consumes the packet

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed false-green integration gap shared with `TRACE51-01`; current source
  has a real torn-transfer race.
- **Exact regions:** `app/src/test/kotlin/me/hletrd/telecampro/camera/CameraEngineRecordingPreNativeTest.kt:141-201`
  blocks `beforeEncoderAdmissionSnapshot` and asserts only the observed `RecordingAdmissionInputs`.
  The installed `RecordingPreNativeEngineOverrides` makes
  `continueRecordingAfterAllocation` take the injected `afterMicrophoneClaim` terminal at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5367-5386`; it never calls the
  production `startRecordingClaimed` branch at `:5387-5399`. The snapshot itself stores accepted
  session, observed inputs, filtered candidates, failure, and a session-only current predicate at
  `CameraEngine.kt:5027-5061`; it does not carry size, selected frame rate, or the accepted transfer
  into native setup. Production later re-reads `videoSize`/`videoFrameRate` and reads live `transfer`
  independently for `glTransfer` and `fileTransfer` at `:5462-5483`; the latter configures
  `VideoRecorder.start` at `:5580-5593`, while the former reaches GL at `:5659-5663`.
- **Concrete failure scenario:** REC snapshots HEVC+HLG and then waits on provider/mic setup. While
  the accepted HLG10 Camera2 session remains current, the operator selects S-Log3 (the same non-SDR
  source-precision class, so no session generation changes). Native setup can read HLG for the GL
  curve and S-Log3 for file tags, or the reverse. The test remains green because it proves only the
  earlier observation callback and short-circuits before either live read.
- **Suggested fix:** carry one immutable recording packet through `RecordingAdmissionSnapshot` into
  `startRecordingClaimed`: exact accepted session, size, frame-rate selection/capture rate, codec,
  accepted transfer, and ordered candidates. Derive GL and file transfer from that one value. Add a
  barrier between the former live-transfer reads, change HLG to S-Log3/LogC3 concurrently, and drive
  the real production setup composition through an injectable recorder/GL boundary; assert pixels,
  encoder format tags, candidate, size, and FPS all come from one packet.

### C51-TEST-02 — rollback supersession tests assert Engine/UI fields but never the GL command winner

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed mutation-insensitive coverage gap shared with `TRACE51-02`; current
  source posts a stale renderer curve.
- **Exact regions:** `app/src/test/kotlin/me/hletrd/telecampro/ui/ModeRollbackOwnershipRobolectricTest.kt:53-140`
  covers Photo/SDR rollback plus a newer AVC/SDR packet and asserts Engine/UI/candidate policy only.
  Its unstarted Robolectric `GlPipeline` drops posts, and no transfer sink is observed. In production,
  rollback correctly chooses the newer packet using `videoPipelinePublicationGeneration` at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:824-834`, but immediately posts
  `before.transfer` to GL at `:835`. A mutation that leaves this stale post intact while preserving
  all Engine/UI generation logic is invisible to the current tests.
- **Concrete failure scenario:** an in-flight Video lens/optics door began under HLG. Before it
  fails, the operator selects S-Log3, which is a newer pipeline publication but needs no Camera2
  precision reopen. Rollback preserves S-Log3 in Engine/UI, then queues the old HLG curve into the
  active GL generation; the finder/file-render path disagrees with the visible selection until a
  later transfer replay happens.
- **Suggested fix:** inject/observe the active GL transfer sink in the rollback test. Use a Video
  baseline with disjoint same-precision curves (HLG versus S-Log3), force the newer publication to
  win before owned rollback, drain both command lanes, and assert Engine, UI, persisted packet, GL
  renderer, and subsequent REC all retain the same curve. Mutation-test `before.transfer` versus
  `restoredVideoPipeline.activeTransfer` at the rollback post.

## Final missed-issue and file-coverage sweep

I re-ran the tracked inventory after the traces and revisited every executor/handler/scheduler,
atomic/volatile/monitor boundary, CameraAction, capture and recording terminal, GL/native owner,
provider durability decision, review setup/decode/delete owner, test skip/return/source assertion,
coverage residual, device case, and documentation claim. The assertion-light instrumentation tests
remain explicitly diagnostic and androidTest assembly remains truthfully described as compilation,
not device execution. Required device-harness skips remain non-green unless partial evidence is
explicitly attested. No additional flaky, false-green, or missing TDD seam survived validation.

No physical Camera2 HAL, GLES error injection, MediaProvider, microphone, HDR display, external
keyboard, converter, or device test ran. `docs/FIELD_CHECKS.md` remains truthful that A3/A4/A5/D1/
E1/E2 are open; none was inferred green from host coverage.

## Totals

- Findings: **2**
- Severity: **2 Medium**
- Confidence: **2 High**
- Confirmed product races exposed by test gaps: **2**

---

## Archived prior review

# Test-engineer review — cycle 50

Date: 2026-08-25
Reviewed revision: `2388819d981d32bc3c59b3e81f75fd4f49fab8bd`
Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`
Mode: review only; no production implementation, commit, deployment, or device mutation

## Complete inventory and method

I first inventoried all 535 tracked paths. The review-relevant inventory was complete rather than
sampled: all 120 production files under `app/src/main`, all 238 host JVM/Robolectric/Compose tests,
all four `androidTest` files, all 14 external device-harness files, all 25 host/coverage/release
tools, all 16 main resources/manifests, the eight Gradle/version/wrapper inputs, and the 64 committed
docs/assets. I read the clean-clone authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) in full, plus `README.md`, `device-tests/README.md`, the current completed
plans, prior provenance reviews, and current coverage manifests. I then examined the complete source
and test inventory by module and cross-checked every production async/ownership boundary, public UI
action, device-harness case, test skip/incomplete route, source-inspection assertion, reflection
fixture, delayed task, and Partition-A/B classification against its claimed evidence.

The authoritative non-device gate passed with the documented SDK authority:

`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools python3 tools/verify_host.py`

It assembled the debug and instrumented APKs, ran 2,103 JVM/Robolectric/Compose tests with no
failure or skip, passed lint, enforced Partition A at 8,295/8,310 lines (99.82%, with its exact
15-line reviewed residual manifest), and passed 130 tooling tests, nine coverage-tool tests, 195
device-harness self-tests, 152 documentation checks (24 explicitly optional private-context skips),
Python compilation, and `git diff --check`. Overall host coverage was 17,775/28,061 lines (63.34%);
Partition B was 9,480/19,398 (48.87%) and remains explicitly device-bound. No physical device,
Camera2 HAL, MediaProvider, microphone, HDR display, external keyboard, or system consent surface
was exercised. The manual ledger still truthfully lists A3/A4/A5/D1/E1/E2 as open.

## Findings

### C50-TEST-01 — the rollback interleave test uses the restored packet, so it cannot detect a stale codec/candidate overwrite

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed false-positive test shape; it masks the confirmed runtime race
  traced in `TRACE50-01`.
- **Exact regions:** `app/src/test/kotlin/me/hletrd/telecampro/ui/ModeRollbackOwnershipRobolectricTest.kt:53-87`
  freezes the engine's current HEVC/Main10 candidates and queues exactly that same HLG packet behind
  a rollback which restores HEVC/HLG. Its terminal assertions cover only `videoMode`, active
  `transfer`, and requested transfer. They never use a packet different from the rollback baseline,
  never assert `videoCodec`/ordered candidates on both sides, never drain the queued ViewModel
  rollback publication, and never enter the next mode/REC door. The actual callers freeze an
  independently selected packet before the engine monitor at
  `ui/CameraViewModel.kt:2336-2353,2588-2612,2855-2871`; the synchronized callee consumes those
  already-built arguments at `camera/CameraEngine.kt:2511-2556`. Rollback restores and posts the
  old tuple at `CameraEngine.kt:764-839`, while its ViewModel publication is delayed at
  `CameraViewModel.kt:911-960`.
- **Concrete failure scenario:** while a Photo→Video HLG attempt is pending, the operator selects
  AVC/SDR. Setup rollback restores accepted Photo + HEVC/HLG and queues its UI publication. The
  already-frozen AVC/SDR command then acquires the engine monitor, observes Photo, takes the
  no-generation path, and overwrites the restored next-video codec/candidates. The queued rollback
  still passes its generation check and paints HEVC/HLG in the UI. The current test substitutes
  HEVC/HLG for AVC/SDR, so both possible packet owners are bit-identical and it stays green.
- **Suggested fix:** make the deterministic interleave use disjoint packets: accepted/restored
  HEVC+Main10+HLG versus queued AVC+Main+SDR. Drain the real ViewModel callback and assert one
  explicitly selected linearization policy across engine codec, ordered candidates, requested and
  active transfer, UI state, subsequent Video transition, and production REC admission. Mutation-
  test removal of the pipeline generation/sequence edge, not only removal of `synchronized`.

### C50-TEST-02 — the new REC rollback test still does not execute production REC wiring

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed integration/TDD gap; current production wiring is source-correct,
  but the rollback-specific host evidence remains mutation-insensitive.
- **Exact regions:** after forced rollback,
  `ModeRollbackOwnershipRobolectricTest.kt:257-304` reads private engine fields and manually calls
  `recordingEncoderAdmission`; `camera/CameraStateTest.kt:215-250` separately tests that pure seam.
  Neither calls `CameraEngine.startRecording` or `beginRecordingAllocation`. The production wiring
  at `camera/CameraEngine.kt:5046-5065` passes live frame-rate/codec/transfer/candidates and converts
  the seam's failure to status, but those lines are all missed in the current JaCoCo report. The
  host recorder tests install `RecordingPreNativeEngineOverrides`; that branch deliberately creates
  an empty candidate snapshot at `CameraEngine.kt:5029-5035` and bypasses this admission decision.
  The external device cases start real recordings, but none forces an owned pipeline rollback and
  then presses REC.
- **Concrete failure scenario:** a future edit wires `requestedVideoTransfer` instead of accepted
  `transfer`, supplies stale candidates, reverses the FPS boolean, or stops calling the shared seam.
  Both pure tests and the rollback test remain green because they invoke the correct policy directly;
  the first REC after rollback is nevertheless refused with the wrong unavailable status.
- **Suggested fix:** add a narrow recorder-allocation injection that leaves the production candidate
  admission branch live, invoke public `startRecording` after each forced HLG and SDR rollback, and
  capture the exact `RecordingAdmissionSnapshot`/status. Cover accepted ordered candidates, FPS
  refusal, codec refusal, superseded rollback, and the disjoint-packet race from C50-TEST-01.

### C50-TEST-03 — the completed release-trace plan claims a source contract that does not exist

- **Severity / confidence:** Low / High
- **Classification:** Confirmed evidence overclaim and mutation gap; current production code is
  release-safe.
- **Exact regions:** the completed cycle-49 plan promises “a release-source contract proving no
  debug-only payload can be force-unwrapped” at `docs/plans/2026-08-25-rpf-cycle49.md:25-30`.
  The only added coverage is the pure debug/release matrix at
  `camera/CameraStateTest.kt:167-213`; repository-wide test search finds no source/variant contract
  around the production call at `camera/CameraEngine.kt:4170-4183` or nullable trace consumption at
  `:4723-4751`. Unit tests compile with debug `BuildConfig.DEBUG=true`, and the authoritative host
  gate assembles but does not execute a release test variant.
- **Concrete failure scenario:** the production caller is changed to pass `true`, or trace
  consumption reintroduces `traceText!!` under the build-independent admission flags. The pure
  matrix remains green because it still proves only that `captureFamilyTraceAdmission(..., false)`
  is inert; it does not prove production supplies false in release or consumes the payload safely.
- **Suggested fix:** either add the promised source/bytecode invariant (production call must use
  `BuildConfig.DEBUG`, no force unwrap of the nullable trace payload), or inject build admission into
  an executable `photoCallback` owner test and run it under both debug and release build constants.
  Append a dated correction to the completed plan if the narrower pure-seam evidence is intentional.

## Final missed-issue sweep

I re-ran the complete inventory after tracing these findings and rechecked all test early returns,
skips/incomplete results, reflection/source-text assertions, concurrency latches, device-case effect
declarations, report/attestation exit semantics, production callback owners, capture/storage/video
families, Camera2/GL seams, Compose modality/input, release/debug gates, and open field claims. The
pinch probe is deliberately diagnostic and accurately says it never fails; androidTest assembly is
accurately described as compilation rather than execution; full/reliability device skips remain
non-green unless partial evidence is explicitly attested. No additional false-positive or flaky
test survived source validation. Hardware-only behavior remains manual/device evidence rather than
being inferred from the green host gate.

## Totals

- Findings: **3**
- Severity: **2 Medium, 1 Low**
- Confidence: **3 High**
- Confirmed product failures: **1 race shared with `TRACE50-01`**
- Confirmed test/evidence gaps: **3**
