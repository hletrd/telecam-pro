# Cycle 57 deep review — critic, verifier, test-engineer

Date: 2026-08-27
Reviewed revision: `b44d5fce43b9a4910143133b6e6e280559704763` (`origin/main`)
Workspace: isolated clean clone
`/var/folders/kz/t1c9x6qj5zgb2sg_4lv0nh900000gn/T/find-x9-ultra-cycle57.XXXXXX.yRT92pSLwp/repo`

## Provenance: critic

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely before review,
then inventoried all 459 review-relevant paths. I reviewed the complete current module map and the
cycle-56 aggregate/plan before examining the process-admission, pending-identity, DNG-publication,
StartupTrace, diagnostic, release-tool, UI, storage, Camera2, test, device-harness, and documentation
surfaces. The three findings below are current at the reviewed HEAD; none merely repeats a closed
cycle-56 defect.

### C57-CVT-01 — the combined still-admission projection can deliver an older value last

- **File + region:**
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:205-245,1723-1730,4609-4622,5444-5460`;
  `app/src/main/kotlin/me/hletrd/telecampro/ProcessAdmissionSignal.kt:16-71`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/EngineCallbackSink.kt:44-46,71-80`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1180-1185`.
- **Severity / confidence:** Medium / High.
- **Classification:** confirmed ordering defect in the mechanism; the user-visible interleave is
  likely under concurrent DNG/storage terminals and is host-fault-injectable, not device-dependent.
- **Evidence:** each `ProcessAdmissionSignal` serializes only its own listener. CameraEngine discards
  those per-signal sequences, recomputes the conjunction before taking
  `processStillAdmissionSubscriptionLock`, updates `lastProcessStillAdmission` under that lock, then
  invokes the Boolean UI callback after unlocking. `EngineCallbackSink` uses shared read leases, so
  two admitted callback invocations may run concurrently. Several DNG/storage terminals also invoke
  `onStillCaptureAdmissionChanged` directly, bypassing the aggregate change gate entirely.
- **Failure scenario:** storage's `false` listener computes/records false and pauses before invoking
  UI. The storage owner becomes available, but that signal's next callback remains serialized behind
  the paused listener. DNG then becomes available on its independent listener, observes the current
  all-true conjunction, records/emits true, and the older storage callback finally emits false. The
  queued storage-true callback now sees `lastProcessStillAdmission == true` and suppresses itself.
  `CameraUiState.stillCaptureAdmissionAvailable` remains false although every owner can admit. The
  inverse ordering can transiently re-enable the shutter while an owner is occupied; Engine's own
  gate still refuses the press, but the UI is untruthful. The direct emissions at 4611/4617/4622 and
  5446/5459 also make deterministic duplicate edges and widen the stale-write window.
- **Fix:** own the conjunction in one process-global, monotonically sequenced coordinator updated by
  every constituent owner, or carry one Engine publication sequence through the callback and reject
  stale writes in the ViewModel reducer (as Camera Ready already does). Remove direct Boolean
  emissions; every local/process owner edge must enter that same sequenced publication path. Add a
  cross-signal barrier test that pauses an older callback, completes the other owner, then releases
  the older callback and asserts the final UI truth and sequence.

### C57-CVT-02 — the claimed 120-row diagnostic reserve is not actually admission-controlled

- **File + region:** `tools/check_docs.py:75-143`;
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DiagnosticTelemetryTest.kt:68-123`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:834-850,6428-6440,7081-7087`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:762-769`.
- **Severity / confidence:** Medium / High.
- **Classification:** confirmed source/test-assurance defect; exhausting ColorOS's process quota is
  runtime/device-observable, while the bypass itself is host-verifiable.
- **Evidence:** the executable inventory automatically labels every `Log.w`/`Log.e` as reserved and
  blesses informational rows when a nearby string matches a `one_shot_session` anchor. Neither class
  consumes a finite owner. The unit test establishes only that the 180-row *recurring* budget leaves
  `300 - 180 == 120`; it never spends session/fault rows from the same 300-row process ceiling.
  `CameraSessionAccepted` and `Session configured` run on every accepted reconfiguration, and
  `RecordingSpec`/`RecordingStored` run for every clip, so these are not process-one-shot events.
- **Failure scenario:** roughly 60 ordinary mode/lens session acceptances produce at least the two
  unbudgeted accepted/configured information rows and consume the entire nominal 120-row reserve;
  repeated clips and warning paths consume it faster. Later `FrameGap`, recovery, StartupTrace, or
  actual fault evidence is then dropped by ColorOS despite both the diagnostic test and documentation
  gate claiming the reserve is preserved.
- **Fix:** allocate finite process budgets for repeatable session information and non-terminal
  warnings (with a separately protected terminal-fault allowance), and require an executable guard
  at each classified call site. Do not classify a row as protected merely from its log level or an
  anchor string. Test long mode/lens/record loops against the shared 300-row accounting and
  mutation-test removal of each guard.

## Provenance: verifier

I traced each claimed cycle-56 owner from production entry through terminal release and checked the
corresponding tests rather than accepting comments or plan completion as evidence. Pending-allocation
identity retry is finite, preserves exact/absent/uncertain truth, and has a live backed-off process
edge. Completed DNG publication reaches the process dispatcher in the current source. StartupTrace
uses exact owners and rejects stale owner identities in its pure seam. Those closed cycle-56 findings
were not refiled.

The focused JVM/Robolectric suite for `ProcessAdmissionSignalTest`, `DngPreCaptureAllocationTest`,
`ProcessStillAdmissionEngineTest`, `StartupTraceTest`, `StillPublicationDispatcherTest`, and
`PendingAllocationIdentityRecoveryTest` passed. `python3 tools/check_docs.py` also passed 158 checks
with 24 explicitly optional-private skips. Those green results are consistent with, but do not close,
the cross-signal ordering and quota-accounting gaps above. No device, deployment, MediaProvider
mutation, credential use, or destructive action ran.

## Provenance: test-engineer

### C57-CVT-03 — DNG-tail tests do not execute the production CameraEngine transfer wiring

- **File + region:**
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5551-5584`;
  `app/src/test/kotlin/me/hletrd/telecampro/camera/StillPublicationDispatcherTest.kt:15-73,267-333`;
  `tools/coverage/partition-b.txt:20-21`.
- **Severity / confidence:** Low / High.
- **Classification:** confirmed coverage/false-assurance gap; no current runtime failure is asserted.
- **Evidence:** tests separately prove the pure `dngPublicationTransfer` decision, the generic
  enqueue helper, and process-dispatcher capacity using arbitrary labels such as `mixed-single`,
  `burst`, `aeb`, and `timelapse`. No test invokes `CameraEngine.photoCallback` or the production
  complete-DNG branch that computes `processedQueued`, selects the transfer, enqueues behind the
  processed sibling, and calls `dispatchCompletedDng`. `CameraEngine` is wholly Partition B, so the
  mandatory 99.84% Partition-A figure cannot reveal this missing integration coverage.
- **Failure scenario:** deleting the production helper call, flipping the `processedQueued` check,
  dispatching mixed DNG inline, or routing only SINGLE through the process owner leaves all current
  dispatcher/policy tests and the Partition-A threshold green while restoring the cross-Engine
  unbounded-tail/order defect cycle 56 intended to close.
- **Fix:** extract the complete production transfer decision into an injectable owner that receives
  the actual processed-queue result and dispatcher facade, then drive RAW-only SINGLE, mixed SINGLE,
  BURST, AEB, timelapse, rejection, shutdown, and old-Engine replacement through that exact entry.
  Prefer a mutation test that removes/bypasses the process dispatch at the CameraEngine call site.

## Final sweep and coverage attestation

- Inventory: 106 production Kotlin/Java modules, 249 JVM/Robolectric/Compose test files, four
  instrumented tests, 41 tool/device-harness files, and eight committed Markdown authorities; every
  production module is named in the architecture map.
- Cross-file sweep covered Camera2/Engine/GL ownership, MediaStore durability and recovery, review
  deletion, process-finite dispatchers, diagnostics, permissions/security, release tools, UI policy,
  EN/KO resources, unit/instrumented/device-test boundaries, and current field-evidence limits.
- `git diff --check` passed and the review workspace remained clean. The first focused Gradle attempt
  failed only because this isolated clone lacked SDK discovery; after using the documented
  `/Users/hletrd/Library/Android/sdk` environment, the focused suite passed. The Gradle run emitted
  existing debug/test manifest-merge warnings for redundant permission-removal directives; I did not
  treat those benign warnings as a separate correctness finding.
- Open field checks A3/A4/A5/D1/E1/E2/E3 remain manual evidence obligations. Host tests cannot close
  them, and this review makes no new device-behavior claim.

Total current findings: **3** (2 Medium, 1 Low; all High confidence). No agent failures.
