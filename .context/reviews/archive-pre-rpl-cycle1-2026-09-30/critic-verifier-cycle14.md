# Critic + verifier review — cycle 14

Date: 2026-08-24
Reviewed HEAD: `fbe31d6`
Role: adversarial critic plus evidence-based verifier

## Scope and inventory

I read the required authorities first: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, and `README.md`. I then inventoried the 352 review-relevant tracked inputs
across the production/debug/instrumented/test source sets, resources and manifest, Gradle/release
configuration, `tools/**`, `device-tests/**`, privacy/Play documentation, current plans, and prior
reviews. Generated build output, caches, historical report evidence, and the archived
`.claude/worktrees` checkout were excluded from executable-source review.

The implementation trace covered Activity/ViewModel lifecycle, Camera2 route and session ownership,
preview/GL acquisition, still and recording publication, microphone handoff, process quarantine,
storage/review deletion, settings and localization, debug/release source provenance, device-harness
snapshot execution, report attestation, and the tests that claim those boundaries. I specifically
re-verified every cycle-13 aggregate item against its current implementation rather than accepting
the completed plan or comments as proof. The focused Python suites for the changed provenance and
attestation code pass (58 tests), but the two races below are outside their current fixtures.

## Findings

### CV14-01 — failed attestation finalization can still leave a valid green pair after report-root relocation

- **Severity / confidence:** High / High.
- **Classification:** Confirmed current-HEAD evidence-integrity defect. This is an incomplete closure
  of cycle-13 `AGG13-05`, but the report-root relocation variant is not recorded in the completed
  cycle-13 plan or backlog as remaining work.
- **Evidence:**
  - `device-tests/run.py:1044-1151` pins a report-root descriptor only for one
    `artifact_manifest` call, then closes it. `write_attestation` invokes that operation before and
    after publication as separate pathname-rooted transactions (`device-tests/run.py:1218-1253`).
  - The JSON and sidecar are likewise created through two separately reopened report paths
    (`device-tests/run.py:1180-1198,1231-1240`).
  - On a failed final check, rollback opens `report_dir` yet again and treats missing names as a
    successful cleanup (`device-tests/run.py:1201-1215,1255-1262`). It neither retains the directory
    that actually received the files nor verifies the inodes it created.
  - I reproduced the failure without modifying the repository: after the sidecar write, rename the
    report root and create a replacement directory at its old pathname before the final manifest
    call. `write_attestation` correctly raises `ContractError`, but rollback visits the empty
    replacement and reports no errors; the renamed original still contains
    `run-attestation.json` plus a checksum-valid `run-attestation.json.sha256`.
  - `device-tests/tests/test_attestation.py:1079-1111` tests a late file added inside a stable root
    only. It does not relocate/replace the root or either reserved leaf between publication and
    rollback.
- **Concrete failure scenario:** a concurrent evidence collector, cleanup/archival task, or
  adversarial same-user process moves `reports/<run-id>` after both reserved outputs are written.
  The final pathname-based recheck fails, the CLI exits nonzero, yet the moved directory contains an
  independently consumable green attestation pair. That is the exact forbidden outcome the
  cycle-13 fix claims to eliminate.
- **Fix:** own one no-follow report-root descriptor and its identity across the complete freeze,
  output creation, verification, and rollback transaction; verify the published pathname still
  names that descriptor before reporting success, and roll back only the exact created inodes via
  that retained owner. Prefer making the sidecar the final commit marker after every other
  failure-producing check, so no authoritative pair exists before finalization. Add deterministic
  root-rename/root-replacement and reserved-leaf relocation tests that assert no valid pair survives
  anywhere under the original allocation owner.

### CV14-02 — checkout matching freezes source bytes but not the Git identity it attaches to them

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed provenance race. This is a remaining authority gap adjacent to
  cycle-13 `AGG13-04`; it is not currently tracked as deferred or open.
- **Evidence:**
  - `current_debug_source_identity` freezes scoped bytes once, then runs one `rev-parse`, one
    `status`, and one ignored-file query before freezing the scoped bytes a second time
    (`device-tests/dtest/contracts.py:452-503`). Only the two source-entry tuples are compared.
    `HEAD`, the index, and the exact Git-query results are never re-read or identity-pinned.
  - `require_apk_source_match` treats the resulting one-shot commit and dirty flag as current
    checkout authority and allows device execution when they equal the APK manifest
    (`device-tests/dtest/contracts.py:506-525`).
  - The new adversarial coverage exercises leaf, parent, and member-set source mutations only
    (`device-tests/tests/test_contracts.py:242-340`); it has no commit/ref/index transition barrier.
- **Concrete failure scenario:** the harness reads commit A from `rev-parse`; while its Git queries
  run, another process advances the checkout to commit B whose changes are outside the debug source
  scopes (for example, a documentation-only commit). The second scoped byte freeze still equals the
  first, so the function returns commit A and can accept an A-provenance APK even though the checkout
  is now at B. A transient index change can similarly make the returned `dirty` bit disagree with
  the index state at the acceptance boundary while both source freezes remain identical. The later
  workspace field may expose the disagreement in the final JSON, but it does not stop the stale APK
  from being installed or the device run from proceeding.
- **Fix:** freeze the Git authority together with the file authority. At minimum, bracket the source
  reads with identical full `HEAD`/index/status/ignored results and fail on any transition; for the
  repository's stated A→B→A threat model, identity-pin or privately copy the relevant ref/index/config
  inputs and derive commit/dirty truth from that immutable owner rather than from separate live Git
  subprocesses. Add deterministic HEAD advance, index replacement, and A→B→A Git-state tests whose
  scoped file content remains byte-identical.

## Verified older findings and non-findings

- Cycle-13 recorder-setup recovery now retains the preview surface before refusal, registers an
  atomic process-token observer, distinguishes the setup Engine from a foreign replacement Engine,
  and rechecks lifecycle/surface/generation authority on the serial setup lane. I found no remaining
  normal pending-to-published/abandoned edge that consumes the only preview callback.
- Standby audio now publishes an exact-generation live-input owner before start, dispatches stop off
  lifecycle/REC callers, and orders release after that stop. The prior unowned blocking-read handoff
  is closed; device-specific native `stop()` behavior remains manual-validation territory rather
  than a new code finding.
- Lifecycle information sampling is bounded to one in-flight plus one coalesced active-generation
  intent, with a second main-thread publication check. Stop/start churn does not build the old
  unbounded queue.
- Immutable debug compilation seals source inputs and validates inode/type/mode/size/mtime/ctime
  after compilation, so the reviewed source A→B→A mutation no longer exports evidence. The gap in
  CV14-02 is the separate live Git identity attached during later checkout matching, not compiler
  source ownership.
- Imported `run.main()` refuses without inherited snapshot authority before APK/device preflight;
  Loupe Overview and immutable-debug runbook authorities match current executable behavior.

## Final missed-issue sweep

I repeated the sweep over lifecycle/replay cancellation, controller and GL identity, native-owner
publication, standby mic terminal ordering, MediaStore family ownership, settings/localization,
release/debug provenance, harness imports, attestation publication, and current authority links.
No additional evidence-backed current-HEAD issue survived. Device-only residuals and explicit owner
decisions already recorded in `docs/BACKLOG.md` were not re-filed.

**Finding count:** 2 total — 1 High, 1 Medium. Both confirmed; neither is currently deferred.
