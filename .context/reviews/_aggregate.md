# Aggregate deep review — cycle 22

Date: 2026-08-24
Reviewed HEAD: `0c6d056b587d9b390688010cbd63fe6011da551a`

## Review provenance

Three fresh concurrent review agents covered every required specialist perspective plus the
repository's registered QA adversary. The environment exposed only three child-agent slots, so
closely related roles were grouped without dropping a perspective:

- `code-reviewer.md`, `critic.md`, `verifier.md`, `feature-dev-code-reviewer.md`,
  `test-engineer.md`, and `qa-adversary.md`
- `perf-reviewer.md`, `architect.md`, and `tracer.md`
- `security-reviewer.md`, `debugger.md`, `document-specialist.md`, and `designer.md`

The reviewers inventoried all current production Kotlin, host and instrumented tests, resources,
build/release tools, device harnesses, and active documentation. Historical reports and completed
plans were treated only as leads. Debug assembly and the complete host unit task passed; the broader
release-tool suite passed 69 tests, focused artifact tests passed 27/27, documentation checks passed
111/111, and `git diff --check` passed. Native-device checks were not run because deployment is
disabled and no current device serial was supplied. There were no agent failures.

## Deduplicated findings

### AGG22-01 — camera teardown timeout is treated as proof that the old graph released ownership

- **Severity / confidence:** High / High
- **Classification:** Confirmed architecture/correctness gap; device manifestation requires a
  camera-handler or native close stall longer than 1.5 seconds
- **Cross-agent agreement:** architect and tracer; performance confirmed at Medium/High
- **Evidence:** `CameraController.close()` queues teardown at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:2149-2183`, performs a timed
  `join` at `:2184-2191`, and returns without checking thread liveness or publishing a terminal
  result. `CameraEngine.reconfigureCamera` then starts the replacement session after `old?.close()`
  at `CameraEngine.kt:3627-3647`.
- **Failure:** if a Camera2/DNG/framework callback occupies the handler beyond the timeout, the old
  controller may still own its device, session, readers, ring, and surface while the replacement
  starts. Late teardown can then overlap the new topology, causing `CAMERA_IN_USE`, broken-pipe HAL
  failures, preview loss, or teardown of replacement-adjacent resources.
- **Required fix:** expose and consume an identity-owned strict-release versus timeout/quarantine
  terminal result. A timeout must not authorize replacement acquisition. Add deterministic handler-
  latch and reconfiguration tests.

### AGG22-02 — family delete and late publication are not serialized across Engine replacement

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness, privacy, and data-loss-adjacent ownership defect
- **Cross-agent agreement:** architect and tracer
- **Evidence:** family mark/retirement use `withFamilyJournalAuthority` at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:381-450`, but publication
  performs a bare family-marker check at `:483-486`. `StillCapturePipeline` checks once at
  `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt:382-390` and later enters
  only its Engine-local publication owner at `:481-506`.
- **Failure:** old Engine A observes no family marker; replacement Engine B marks and deletes that
  family; A then publishes its already-pending sibling. The retained marker hides it from in-app
  restore but cannot undo `IS_PENDING=0`, so system Gallery can expose media after Delete completed.
- **Required fix:** serialize the family-live check and provider publication with the exact-family
  authority used by mark/retire, or guarantee an authoritative post-publication recheck and exact-
  URI discard. Add a deterministic two-Engine check → mark/delete → publish test while preserving
  unrelated-family progress.

### AGG22-03 — first DISCARD migration reads SharedPreferences under the global database lock

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed lock-scope and process-liveness defect
- **Source:** performance reviewer
- **Evidence:** `PendingDiscardJournal.page` holds the static `databaseLock` at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:110-138` while the first
  migration evaluates filesystem-backed `legacyPreferences.all` at `:151-157`. Cycle 21 moved the
  cleanup commit outside this lock, but not the initial read. Every unrelated exact-URI mark,
  lookup, and remove still needs the same database lock.
- **Failure:** a slow or blocked first preference load during launch recovery stalls unrelated
  current media publication/deletion ownership, coupling live save latency to legacy compatibility
  data even when it contains no entries.
- **Required fix:** snapshot legacy entries outside `databaseLock`, then recheck migration completion
  inside the lock and transactionally insert the frozen snapshot plus completion metadata. Add a
  latch-controlled first-migration test proving unrelated mark/lookup/remove progress.

### AGG22-04 — sequential terminal Git probes cannot attest one coherent repository state

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-integrity and operator-contract defect
- **Cross-agent agreement:** every non-designer role
- **Evidence:** `tools/check_release_artifact.py:732-762` observes final ordinary status, ignored
  packageable sources, and HEAD in separate processes, followed by temporary-artifact cleanup.
  Reviewers independently reproduced both a tracked edit after final status and an ignored protected
  input created during final HEAD while the checker returned `failures=[]`. Existing tests at
  `tools/tests/test_release_artifact.py:595-698` cover earlier boundaries but not these schedules.
- **Failure:** tracked/untracked worktree mutations after status or ignored package inputs after the
  ignored scan leave HEAD unchanged and can receive an `upload-ready` verdict, contradicting
  `docs/play-console-submit.md:694-700`.
- **Required fix:** make terminal repository truth one Git-owned/coordinated snapshot or verify an
  immutable sealed source owner; move cleanup before that boundary. Add deterministic regressions
  for tracked, untracked, ignored, and HEAD mutations at every terminal edge.

## Accounting

- New review findings: **4**
- Deduplicated root causes: **4**
- Agent failures: **0**
- Deferred findings: **0** (all findings require implementation this cycle)
