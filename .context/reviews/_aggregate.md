# Aggregated review — cycle 10

Date: 2026-08-23  
Reviewed HEAD: `a714d56`  
Inputs: `code-architect-critic.md`, `perf-tracer-debugger.md`, `security-verifier.md`

All available review capacity was used in one parallel fan-out. The generic review agents covered
code-reviewer, architect, critic, designer, perf-reviewer, tracer, debugger, the repository's
qa-adversary, security-reviewer, verifier, test-engineer, and document-specialist. Every reviewer
inventoried the repository and performed a final missed-issue sweep. There were no agent failures.

## Findings

### AGG10-01 — Camera topology changes during REC can permanently strand readiness

- **Severity / confidence:** High / High
- **Agreement:** Independently confirmed by code/architecture and performance/tracing reviewers.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:878-984,
  3127-3191,4859-4891`; `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:1476-1486`.
- **Problem:** a changed camera-ID set opens an optics transaction and clears the accepted session
  before the queued reconfigure checks `recorder != null`. The task then exits without rollback or
  a deferred owner, and normal Stop does not replay convergence.
- **Failure:** attach an irrelevant external camera while recording; recording can stop, but the
  shutter and session-owned actions remain Not Ready until some later reopen.
- **Fix:** keep inventory/cache updates immediate, but make topology convergence recorder-aware and
  replay one generation-owned latest-wins action after recorder teardown; test attach, active-route
  removal, Starting, Recording, and teardown composition.

### AGG10-02 — Same-ID external-camera replacement is lost by set-only coalescing

- **Severity / confidence:** Medium / Medium
- **Agreement:** Independently identified by code/architecture and security/verification reviewers.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:878-968`;
  `app/src/test/kotlin/me/hletrd/telecampro/camera/CameraSelector2Test.kt:222-268`.
- **Problem:** availability refresh collapses identity to `cameraIdList.toSet()` and discards a
  removed→available epoch when the provider reuses the same ID. Selection, characteristics, caps,
  sizes, orientation, and EXIF caches may remain from the removed device.
- **Failure:** replace or rapidly reconnect a UVC camera on the same port/provider ID; the app can
  configure the new device with stale facts until process restart.
- **Fix:** carry a per-ID removal/identity epoch through serialized refresh, invalidate on definite
  removal even if final membership matches, and add an injected same-ID A→B lifecycle test.

### AGG10-03 — Deleted-still ownership is neither durable nor bounded across teardown

- **Severity / confidence:** High / High
- **Agreement:** Independently found by code/architecture and security/verification reviewers; the
  latter additionally proved the release-ordering window.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDeletionOwner.kt:43-74,
  161-196`; `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5738-5759,5967-5970`;
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:290-311,518-539`.
- **Problem:** `UNRESOLVED` discard state lives only in an unbounded Engine-local map. The Engine's
  sole final retry runs before still executors drain, so a late output can become unresolved after
  that sweep; process death then loses the veto while the durable row can still say COMPLETE.
- **Failure:** durable DISCARD and provider delete both fail after a family deletion. Repeated
  failures grow ownership beyond 32; restart can adopt and publish a still the user deleted.
- **Fix:** persist family deletion intent before acknowledgement or hand it to a process-wide owner
  that remains open to late completions; enforce bounded fail-closed capture admission and test
  persistent >32 failure plus release→late-output→new-Engine recovery.

### AGG10-04 — Post-native recording storage is bounded per Engine, not process-wide

- **Severity / confidence:** Medium / High
- **Agreement:** Performance/tracing finding; corroborated by the architecture's contrary claim.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:104-110,5757-5763`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/RecordingStorageDispatcher.kt:24-56,136-137`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3521-3541`;
  `docs/ARCHITECTURE.md:243`.
- **Problem:** each Engine creates two workers and an eight-task queue. Non-interrupting shutdown
  preserves blocked tasks, while a replacement Engine creates another full pool.
- **Failure:** blocked provider/extractor tails plus repeated ViewModel recreation accumulate daemon
  threads, native/Binder state, closures, and retired Engine graphs without a process ceiling.
- **Fix:** use one process-lifetime dispatcher or a shared global worker permit budget; preserve
  per-Engine callback identity and overflow-to-recovery behavior; add a two-Engine barrier test.

### AGG10-05 — Harness attestation has an import TOCTOU and excludes symlinked executable code

- **Severity / confidence:** High / High
- **Agreement:** Security/verifier finding backed by 147 passing existing harness tests that do not
  exercise this window.
- **Evidence:** `device-tests/run.py:37-78,683-720`;
  `device-tests/dtest/contracts.py:356-364`; `device-tests/tests/test_attestation.py:301-363`.
- **Problem:** the live harness is hashed, then imported from the same mutable tree. Restoring a
  changed file before pre-dispatch verification attests old bytes while changed code remains in
  `sys.modules`; both walkers silently omit symlinks Python can execute.
- **Failure:** concurrent local mutation or a symlinked module registers/runs unmanifested code but
  produces a green evidence manifest.
- **Fix:** reject symlinks/special files, snapshot accepted harness bytes to a private
  digest-qualified tree, import/run only that snapshot, and add subprocess pause/symlink tests.

### AGG10-06 — External captures falsely stamp the host handset as camera maker/model

- **Severity / confidence:** Medium / High
- **Agreement:** Security/verifier finding; architecture documentation confirms the intended
  metadata contract.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3900-3926,
  5895-5905,5937-5938`; `app/src/main/kotlin/me/hletrd/telecampro/camera/DeviceExifLabels.kt:55-94`;
  `docs/ARCHITECTURE.md:49`.
- **Problem:** `ShotOptics` knows `CameraRoute`, but `ExifShot` retains only `frontFacing`; EXTERNAL
  therefore follows the rear-host branch and writes host Make/Model/LensModel.
- **Failure:** a USB-camera still on PMA110 claims OPPO/PMA110 made the image.
- **Fix:** carry route into EXIF composition; for EXTERNAL omit host Make/Model and host lens prefix
  unless Camera2 provides trustworthy device identity, retaining only advertised optical facts.

### AGG10-07 — Release docs promise a logs directory the immutable wrapper never exports

- **Severity / confidence:** Low / High
- **Agreement:** Documentation-specialist finding confirmed against tool behavior.
- **Evidence:** `README.md:132-139`; `docs/BACKLOG.md:1368-1379`;
  `docs/play-console-submit.md:776`; `tools/build_immutable_release.py:174-177`.
- **Problem:** three authorities direct operators to `$release_root/logs/`, but the wrapper copies
  only `app/build/outputs`; Gradle lint reports live under `app/build/reports`.
- **Failure:** operators cannot find promised immutable lint evidence and may consult a mutable
  worktree report.
- **Fix:** export lint reports to the promised directory with an output-layout test, or correct all
  three authorities to describe lint as exit-status-only evidence.

## Disposition requirement

All seven findings are current and must be scheduled. AGG10-01, AGG10-03, and AGG10-05 are
correctness/security/data-integrity findings and are not deferrable under the skill rules. The
remaining findings also have bounded implementations and should be fixed in this cycle. No finding
is silently dropped or severity-downgraded.

## Verification already performed during review

- `./gradlew :app:assembleDebug :app:testDebugUnitTest` — passed.
- `python3 -m unittest discover -s device-tests/tests -v` — 147/147 passed.
- Focused retained-still, publication, EXIF, and route tests — passed.

Prompt 3 must still run every configured debug and release gate against the implemented result.
