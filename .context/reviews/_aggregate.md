# Aggregated review — cycle 12

Date: 2026-08-23
Reviewed HEAD: `0ab3593`
Inputs: all 12 current root review reports. Every required specialist plus the repository's
`qa-adversary` inventoried the complete tracked review surface and performed a final missed-issue
sweep. Historical findings were excluded unless still present on current HEAD.

## Aggregated findings

### AGG12-01 — REC native setup is invisible after allocation claim and before publication

- **Severity / confidence:** High / High; confirmed ownership gap, device symptom not remeasured.
- **Agreement:** code-reviewer, architect, critic.
- **Evidence:** `RecordingPreNativeAllocation.kt:161-188`; `CameraEngine.kt:4519-4523,
  4652-4874,5030-5046,5600-5636,5891-5970`; `VideoRecorder.kt:1112-1166`.
- **Failure/fix:** pause/release can return while vendor setup runs and a replacement Engine can
  acquire Camera2/GL. Install a release-visible setup owner before claim, bind the exact recorder,
  and await terminal release or quarantine; add a blocked-setup/release/replacement test.

### AGG12-02 — incomplete topology refresh retains a stale resolved flag

- **Severity / confidence:** Medium / High; confirmed. **Agreement:** code-reviewer, architect, critic.
- **Evidence:** `CameraEngine.kt:855-870,919-945,964-972,1002-1031`.
- **Failure/fix:** after retry exhaustion, a recovered same-id callback is ignored. Publish every
  incomplete snapshot with `resolved=false`; cover complete -> incomplete/exhaustion -> recovery.

### AGG12-03 — successful same-process family deletes hit a 64-delete cliff

- **Severity / confidence:** Medium / High; confirmed. **Agreement:** perf-reviewer, tracer, debugger.
- **Evidence:** `MediaStoreWriter.kt:48-55,341-365,634-703`; `CameraEngine.kt:3956-3984`;
  `RetainedStillDeletionOwner.kt:99-108`; `DeletedFamilyJournalTest.kt:59-81`.
- **Failure/fix:** resolved markers never retire, so delete 65 closes still admission. Retire only
  after producer terminality and authoritative family absence; prove >64 healthy cycles.

### AGG12-04 — rejected-output overflow forgets an already-admitted sibling

- **Severity / confidence:** High / High; confirmed. **Agreement:** perf-reviewer, tracer, debugger.
- **Evidence:** `MediaStoreWriter.kt:53-61,309-330,376-382,902-935`;
  `StillCapturePipeline.kt:133-135,228-379`; `VideoRecorder.kt:1546-1552`.
- **Failure/fix:** an overflow URI has neither retry nor durable DISCARD and can be adopted after
  restart. Reserve family capacity or durably transfer every overflow identity; test recreation.

### AGG12-05 — harness internal child authority is caller-forgeable

- **Severity / confidence:** High / High; confirmed. **Agreement:** tracer, debugger.
- **Evidence:** `device-tests/run.py:36-48,314-397,463-485,543-578`;
  `device-tests/tests/test_attestation.py:480-518`.
- **Failure/fix:** caller-created proof/FIFO/private tree can authorize a mutable child. Bind child
  mode to non-self-issued outer authority and reject a correct-digest forged direct child.

### AGG12-06 — debug provenance can describe bytes different from those compiled

- **Severity / confidence:** High / High; confirmed provenance TOCTOU.
- **Evidence:** `app/build.gradle.kts:246-365,449-453`; `device-tests/dtest/contracts.py:244-259`.
- **Failure/fix:** A can be hashed, B compiled, and A restored before harness comparison. Make debug
  compilation and provenance consume one immutable input owner; add a barrier integration test.

### AGG12-07 — device preflight repeatedly opens a mutable APK path

- **Severity / confidence:** Medium / High; confirmed attestation TOCTOU.
- **Evidence:** `device-tests/run.py:1266-1272`; `device-tests/dtest/contracts.py:156-176,322-343`.
- **Failure/fix:** A -> B -> A can join A's hash/source with B's aapt facts. Snapshot one no-follow
  regular APK inode and run every inspector on the private copy; test boundary swaps.

### AGG12-08 — final report attestation silently omits or races evidence

- **Severity / confidence:** Medium / High; confirmed. **Agreement:** security, verifier, test engineer.
- **Evidence:** `device-tests/run.py:990-1007,1121-1232`; `test_attestation.py:861-886`.
- **Failure/fix:** symlink/special/missing required evidence can be omitted while PASS remains. Freeze
  the case-owned artifact set, descriptor-walk regular files, reject mismatch, and test swaps/FIFOs.

### AGG12-09 — authoritative host gate misses staged-only whitespace defects

- **Severity / confidence:** Medium / High; confirmed. **Agreement:** verifier, test engineer.
- **Evidence:** `tools/verify_host.py:53-67`; `tools/tests/test_tool_contracts.py:86-104`.
- **Failure/fix:** bare `git diff --check` ignores index-versus-HEAD. Check the complete HEAD patch
  and add temporary-repository behavioral tests for staged and unstaged failures.

### AGG12-10 — owner-cleared rows can spoof first-party last-capture ownership

- **Severity / confidence:** Medium / High; confirmed local cross-app spoofing risk.
- **Evidence:** `LatestCaptureReducer.kt:101-157,179-208,275-327`; `MediaStoreWriter.kt:164-230`.
- **Disposition:** repository policy records the owner's decision to surface previous-install
  owner-cleared captures (`docs/BACKLOG.md:213-216`). Preserve severity in an explicit deferral until
  the owner reverses that choice or Android exposes authenticated historical provenance.

### AGG12-11 — fail-closed output admission leaves a fully bright disabled shutter

- **Severity / confidence:** Medium / High; confirmed. **Agreement:** designer, qa-adversary.
- **Evidence:** `CameraState.kt:1493-1507`; `CameraScreen.kt:1280-1293,2965-3030`.
- **Fix:** include output admission in photo shutter presentation while preserving Timer Cancel and
  REC Stop; add real composition semantics/alpha coverage.

### AGG12-12 — viewfinder exposes successful focus actions when they are no-ops

- **Severity / confidence:** Medium / High; confirmed. **Agreement:** designer, qa-adversary.
- **Evidence:** `CameraScreen.kt:646-658,1056-1064`; `CameraEngine.kt:2321-2344,6421-6438`;
  `ManualControls.kt:93-100`; `CameraState.kt:1402-1407`.
- **Fix:** derive actions from the real admission predicate and held-state truth; cover capable,
  not-ready, Manual, unsupported, held, and unheld states in Compose tests.

### AGG12-13 — architecture names a removed aspect-ratio implementation

- **Severity / confidence:** Low / High; confirmed.
- **Evidence:** `docs/ARCHITECTURE.md:1011-1023`; `CameraEngine.kt:6711-6736`;
  `StillCapturePipeline.kt:172-190`.
- **Fix:** document `centerCropBox` plus one-pass pipeline application and pin both source owners.

### AGG12-14 — architecture TOC omits the zoom-routing section

- **Severity / confidence:** Low / High; confirmed. **Evidence:** `docs/ARCHITECTURE.md:7-18,623+`.
- **Fix:** add the missing TOC entry, renumber later entries, and validate TOC/H2 completeness.

## Agent failures

None. The seven-thread cap required grouping named perspectives across five parallel workers, but
all required and repository-registered roles returned separate provenance reports.

## Disposition requirement

AGG12-01, AGG12-04 through AGG12-06, and AGG12-08 are native-ownership, correctness, or
evidence-integrity findings and are not deferrable. AGG12-10 must retain Medium/High severity in a
plan-directory deferral quoting the explicit owner decision and a reopening criterion. Every other
finding has a bounded implementation and must be scheduled this cycle.

---

## Archived cycle 11 aggregate — provenance only; do not aggregate below as cycle 12

# Aggregate review — cycle 11

Reviewed revision: `4d1dbdac31288a138741aee7c391652a7e0edba5` (`main`, 2026-08-23)
Reviewers: code-reviewer, perf-reviewer, security-reviewer, critic, verifier,
test-engineer, tracer, architect, debugger, document-specialist, designer, and the
repository's custom qa-adversary.

All twelve roles inventoried and examined the current repository from their own specialist angles.
The qa-adversary's permitted static Gate 1 (`assembleDebug` plus the complete debug unit suite)
passed. Deployment was disabled and no current device serial was supplied, so no device result is
claimed. The 33 raw findings deduplicate to the 15 actionable items below; duplicates preserve the
highest reported severity/confidence.

## Aggregated findings

### AGG11-01 — video/restored-family deletion poisons still-capture admission

- **Severity / confidence:** High / High; confirmed.
- **Agreement:** critic, verifier, tracer, architect, debugger, designer; test-engineer also found
  missing production-composition coverage.
- **Evidence:** every family delete enters the retained-still owner (`CameraViewModel.kt:3286-3316`),
  but only a live still `ShotSpec` registers its family (`CameraEngine.kt:4028-4055`). Live video and
  restored canonical ids therefore have no family; `RetainedStillDeletionOwner.kt:76-98,197-199`
  treats that as journal failure and permanently closes `canAdmitCapture()`. The UI still advertises
  a Ready shutter and later announces an unrelated delete error.
- **Failure:** delete a saved video or restored family, switch to Photo, and all shutter presses are
  refused until Engine/process recreation.
- **Required fix:** carry `CaptureFamilyKey` plus media/scope through a typed delete contract;
  register video identity before allocation and seed restored canonical identity. Cover live/restored
  still and video, FILE_ONLY, late sibling, provider failure, EN/KO UI, and continued admission.
- **Raw:** CRIT11-02, VER11-01, TEST11-01, TRACE11-01, ARCH11-01, DBG11-01, DES11-01.

### AGG11-02 — documented harness commands resolve the default APK inside the temp snapshot

- **Severity / confidence:** High / High; confirmed and locally reproduced.
- **Agreement:** code-reviewer, critic, verifier, tracer, architect, debugger, document-specialist.
- **Evidence:** `DEFAULT_APK` derives from snapshot `__file__` rather than
  `SOURCE_HARNESS_ROOT.parent` (`device-tests/run.py:35-41,102-168,897-924`), so every documented
  no-`--apk` invocation exits 2 before ADB.
- **Required fix:** derive repository artifacts from the source checkout while retaining snapshot
  imports/execution; test the real outer→child no-`--apk` entrypoint.
- **Raw:** CODE11-01, CRIT11-01, VER11-02, TRACE11-02, ARCH11-02, DBG11-03, DOC11-01.

### AGG11-03 — post-publication REC failure releases topology isolation before native teardown

- **Severity / confidence:** High / High; code-reachable, device symptom not newly measured.
- **Agreement:** critic, debugger; test-engineer found the missing Engine-level coverage.
- **Evidence:** `startRecording` releases the lease on every false result even after recorder
  publication and asynchronous EGL/native teardown begin
  (`CameraEngine.kt:4282-4308,4782-4903,5004-5058`). Deferred convergence does not gate on native
  finalization and can reopen Camera2/replace GL under the old recorder.
- **Required fix:** transfer lease ownership at recorder publication; afterward only checked native
  finalization/quarantine releases it. Add a publish→attach-failure→topology→release barrier test.
- **Raw:** CRIT11-03, TEST11-02, DBG11-02.

### AGG11-04 — topology invalidation corrupts accepted optics during REC

- **Severity / confidence:** Medium / High; confirmed static state divergence.
- **Agreement:** qa-adversary; test-engineer found absent CameraEngine composition tests.
- **Evidence:** cache invalidation empties live `opticalPresets` while convergence is deferred
  (`CameraEngine.kt:894-965`). A 3× standalone REC zoom tick then disables the Engine/GL finder gate
  while Compose retains accepted inventory, yielding a border/tag with no PIP.
- **Required fix:** separate discovery caches from accepted-session optical truth and swap truth only
  with the deferred optics transaction. Test attach/removal/replacement during REC plus a zoom tick.
- **Raw:** QA11-01, TEST11-02.

### AGG11-05 — durable delete intent blocks the UI thread

- **Severity / confidence:** Medium / High; confirmed.
- **Agreement:** perf-reviewer, qa-adversary.
- **Evidence:** the main-thread delete callback synchronously performs `SharedPreferences.commit()`
  and 25/50 ms retry sleeps (`CameraViewModel.kt:3286-3304`,
  `RetainedStillDeletionOwner.kt:76-95`, `MediaStoreWriter.kt:332-344`).
- **Required fix:** synchronously tombstone, durably commit/retry on an ordered I/O owner, and
  acknowledge only after durable success/truthful failure. Do not weaken durability with `apply()`.
- **Raw:** PERF11-01, QA11-02, TEST11-01.

### AGG11-06 — deleted-family journal and restart work are unbounded

- **Severity / confidence:** Medium / High; confirmed.
- **Agreement:** perf-reviewer, qa-adversary.
- **Evidence:** every delete adds a persistent key, same-process keys are not retired, and restart
  issues one MediaStore query per marker (`MediaStoreWriter.kt:48-52,332-349,600-672`). The
  in-memory 32-entry limits do not bound persistence or queries.
- **Required fix:** finite process-wide persistent owner/admission ceiling; retire only after producer
  terminality and authoritative absence; batch/bound recovery. Test hundreds of deletes, restart,
  cardinality/query count, and partial provider failure.
- **Raw:** PERF11-02, QA11-02, TEST11-01.

### AGG11-07 — rejected output cleanup can resurrect failed media on restart

- **Severity / confidence:** High / High; confirmed privacy/correctness defect.
- **Agreement:** security-reviewer; test-engineer found the adjacent recovery-composition gap.
- **Evidence:** failed HEIF/JPEG/DNG/video paths call bare delete and discard failure
  (`StillCapturePipeline.kt:235-351`, `VideoRecorder.kt:1470-1551`). REGISTERED rows can later
  structurally validate and be adopted despite an operator-visible failure.
- **Required fix:** use typed durable `discardPendingOutput`, retain bounded UNRESOLVED ownership,
  and block adoption until delete or DISCARD durability. Test valid terminal bytes plus provider and
  journal failure for every media kind.
- **Raw:** SEC11-01, TEST11-01.

### AGG11-08 — release artifact verification reopens a mutable path across authorities

- **Severity / confidence:** High / High; confirmed release-integrity TOCTOU.
- **Evidence:** `tools/check_release_artifact.py:244-333` hashes once, then independently reopens the
  AAB for ZIP, keytool, jarsigner, and bundletool, without final identity/digest verification.
- **Required fix:** no-follow open one regular inode, snapshot it privately, run every authority on
  that snapshot, then fail if source identity changed or emit an exclusive verified upload copy.
  Inject swaps after early and late external-tool calls.
- **Raw:** SEC11-02.

### AGG11-09 — immutable release accepts tracked symlinks/external source bytes

- **Severity / confidence:** Medium / High; confirmed gate omission; none exists at HEAD.
- **Evidence:** `tools/build_immutable_release.py:59-142` and the Gradle release guard do not reject
  tracked symlink/non-regular packageable inputs, so one commit can build different external bytes
  on different hosts.
- **Required fix:** reject these inputs in wrapper and Gradle guard; use no-follow descriptor/fstat
  copying and cover relative/absolute links, swaps, and special files.
- **Raw:** SEC11-03.

### AGG11-10 — harness regular-file validation has pathname races

- **Severity / confidence:** High / High; confirmed evidence-integrity defect.
- **Agreement:** qa-adversary; test-engineer found the missing adversarial test.
- **Evidence:** snapshot copy/manifest run `lstat()` and later `read_bytes()` by pathname
  (`device-tests/run.py:44-99`); file or parent replacement can inject outside bytes.
- **Required fix:** descriptor-relative no-follow open, `fstat` and read the same descriptor. Test
  deterministic file/parent swaps through the real outer→child CLI.
- **Raw:** QA11-03, TEST11-04.

### AGG11-11 — caller-controlled environment skips private harness execution

- **Severity / confidence:** High / High; confirmed evidence-integrity bypass.
- **Agreement:** debugger; test-engineer found the entrypoint test gap.
- **Evidence:** caller-set `TELECAM_HARNESS_SNAPSHOT` skips the outer snapshot; digest comparison does
  not prove a private parent-created execution root (`device-tests/run.py:102-163`).
- **Required fix:** bind child mode with a one-shot descriptor/nonce or dedicated entrypoint, require
  execution/source roots to differ, and test preset-env spoofing plus import mutation.
- **Raw:** DBG11-04, TEST11-04.

### AGG11-12 — external-camera identity epochs retain departed ids forever

- **Severity / confidence:** Low / Medium; confirmed bound omission, churn impact device-dependent.
- **Evidence:** each removal epoch remains forever and every refresh clones/scans the maps
  (`CameraEngine.kt:845-1020`, `CameraSelector2.kt:96-117`).
- **Required fix:** after complete inventory consumption, prune absent ids while retaining current
  plus bounded pending removal. Prove same-id replacement and bounded unique-id churn.
- **Raw:** PERF11-03.

### AGG11-13 — topology/storage guarantees lack Engine-level integration tests

- **Severity / confidence:** Medium / High; confirmed evidence gap.
- **Evidence:** topology tests drive pure helpers, and the process-wide post-native storage test
  creates dispatcher facades rather than CameraEngines. Real callbacks, cache/lease wiring,
  storage-tail mapping, release, overflow, and old-Engine callback gates are uncomposed.
- **Required fix:** add injectable topology and post-native-storage seams and drive real Engines
  through callback/REC/teardown/overflow/recreation interleavings.
- **Raw:** TEST11-02, TEST11-03.

### AGG11-14 — reducer concurrency test can pass without proving contention

- **Severity / confidence:** Low / High; confirmed false-positive test.
- **Evidence:** `RecordingStorageDispatcherTest.kt:340-375` treats a 25 ms non-event as proof that
  thread B reached the synchronized publish boundary, without a positive attempted latch.
- **Required fix:** replace scheduler timing with handshakes/barriers proving contention.
- **Raw:** TEST11-05.

### AGG11-15 — authoritative threading and route-inventory docs overstate behavior

- **Severity / confidence:** Medium / High; confirmed documentation mismatch.
- **Agreement:** document-specialist; verifier confirmed inventory mismatch.
- **Evidence:** `CLAUDE.md`, `docs/ARCHITECTURE.md`, and ViewModel comments still place REC
  allocation/refusal on recorder/main despite the process allocator/watchdog split. Architecture
  also claims complete route inventory before first open, while partial inventory deliberately opens
  the current/default route and retries.
- **Required fix:** document the real admission→process allocator→recorder sequence and qualify
  inventory as attempted before first open with bounded later convergence; pin with docs checks.
- **Raw:** VER11-03, DOC11-02, DOC11-03.

## Agent failures

None. The environment's seven-thread cap required serial reuse of completed reviewer agents for
later specialist roles, but every specified and repository-registered reviewer role returned and
produced its own provenance file.

## Disposition requirement

No item may be silently dropped. AGG11-01, AGG11-03, and AGG11-07 through AGG11-11 are correctness,
data-loss/privacy, native-ownership, or evidence-integrity findings and are not deferrable under repo
policy. All remaining items must be scheduled or receive a compliant plan-directory deferral record.

---

## Archived prior aggregate (cycle 10; retained to avoid destructive deletion)

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
