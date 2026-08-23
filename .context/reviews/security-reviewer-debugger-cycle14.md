# Cycle 14 security-reviewer + debugger review

Reviewed revision: `fbe31d6` (`main`, 2026-08-24)

## Scope and inventory

I read the repository authority first: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, and `README.md`, then the cycle-10 through cycle-13 plans,
the current aggregate, and the cycle-13 security/debugger report. I inventoried all
371 tracked entries and examined the 355 tracked text inputs: Android manifests,
resources and build configuration; every production Kotlin file under
`app/src/main`; debug and instrumented source sets; JVM/Robolectric/Compose tests;
the complete Python device harness; release/debug/artifact/coverage/field tooling;
privacy policy sources; and current plans/reviews. Binary Play art, fonts, the
Gradle wrapper JAR, generated build outputs, caches, `.claude/worktrees`, and prior
device evidence were treated as non-executable artifacts rather than source to
review.

The security pass covered permissions and exported components, tapjacking and
intent boundaries, MediaStore ownership/deletion/recovery, privacy and network
claims, backup, secrets, shell/path/process construction, symlink/TOCTOU handling,
artifact/source/harness attestation, and native-resource quarantine. The debugger
pass re-traced camera/GL/REC/microphone lifecycle, the cycle-13 replay and standby
termination changes, late storage callbacks, retries, and process replacement.

Current release code still strips `INTERNET` and `ACCESS_NETWORK_STATE`, disables
backup, contains no tracked credential/private key/password, and exports no release
component beyond the launcher. The exported debug snapshot activity remains
`android.permission.DUMP`-protected and release-absent. The owner-cleared media-row
spoofing risk is already explicitly deferred in cycle 12; the accepted upload-key
risk, approved broad-media-permission product decision, and historical closed
CameraUnit/proprietary-HDR decisions are not re-filed below. Cycle-13's recorder
replay, standby-input stop owner, lifecycle telemetry coalescing, debug-source seal,
checkout descriptor walk, and attestation rollback fixes are present with their
tests. The findings below are distinct residual seams.

## Findings

### SECDBG14-01 — the immutable release build accepts transient source bytes that its provenance does not name

- **Severity / confidence:** High / High (confirmed deterministic release-integrity
  failure).
- **Classification:** Confirmed.
- **Evidence:** `tools/build_immutable_release.py:176-207,210-251`;
  `tools/tests/test_immutable_release.py:35-105`; compare the correctly sealed debug
  owner in `tools/build_immutable_debug.py:237-283,331-370`.
- **Problem:** the release wrapper exports committed source into an ordinary writable
  temporary worktree, records only its initial digests, runs Gradle against those
  paths, and checks final digests afterward. A source file can therefore be changed
  from A to B while the compiler reads it and restored to A before `run()` returns.
  `verify_export()` sees A and accepts, while the already-produced APK/AAB contains
  B. The Gradle provenance task has the same before/after shape and does not close
  this interval. Existing tests cover mutation that remains changed, and mutation
  of the original checkout after export; neither performs A -> B -> A on the actual
  release compile owner.
- **Concrete failure:** I reproduced the exact public function with a temporary Git
  fixture. The injected build callback changed the snapshot source A -> B, wrote the
  bundle from B, restored the source to A, and returned success. The wrapper printed
  no error and copied an artifact whose content was `B` (`published_artifact=B`,
  `snapshot_postcheck_accepted=true`) under A's commit/tree provenance. A compromised
  build step or concurrent same-user writer can therefore make a release artifact
  execute bytes not represented by its packaged source identity.
- **Suggested fix:** apply the cycle-13 `DebugSnapshotSeal` design to the release
  compiler owner (preferably one shared implementation): pre-create writable build
  outputs, make every tracked compiler/package input read-only, identity-pin every
  input and replacement-relevant ancestor by device/inode/type/mode/size/mtime/ctime,
  and verify the seal before exporting artifacts. Add a deterministic release
  snapshot A -> B -> A barrier test that proves no output is published.

### SECDBG14-02 — private APK/AAB inspection copies are writable, so inspectors can verify different artifacts

- **Severity / confidence:** High / High (confirmed evidence-boundary weakness;
  exploitation requires a concurrent same-user writer or a compromised invoked
  inspection tool).
- **Classification:** Confirmed mechanism; adversarial trigger needs manual/injected
  validation.
- **Evidence:** `device-tests/run.py:128-169,1473-1487`;
  `device-tests/tests/test_attestation.py:763-821`;
  `tools/check_release_artifact.py:94-131,375-466`.
- **Problem:** both artifact verifiers make a private path copy with mode `0600` and
  then hand that writable path to several independent readers. The device runner
  invokes aapt/aapt2, ZIP provenance inspection, and a final digest at different
  times; an A -> B -> A change between readers survives the final digest. The release
  checker snapshots A and records A's hash, but then runs ZIP, keytool, jarsigner and
  bundletool checks against the writable private path; its final identity check
  reopens the **original** AAB, not `verified.aab`, so even a lasting replacement of
  the private copy is not detected. The current device test asserts that the private
  file is `0600` and tests swapping the original input, not the copy inspectors use.
- **Concrete failure:** a local probe opened `apk_inspection_snapshot`, wrote B
  directly to `snapshot.private_path`, observed B as an inspector would, restored A,
  and passed the same final SHA comparison (`private_mode=0o600`, `inspector_saw=B`,
  `final_hash_matches=True`). In the release checker, this can make A supply the
  attested pathname/hash/provenance while B supplies signer, strict-JAR, bundle
  validation, and manifest results. A green result then does not prove those checks
  ran against the artifact the operator will upload or install.
- **Suggested fix:** seal each private artifact immediately after copying and retain
  a metadata seal through the whole reader sequence. Verify device/inode/type/mode/
  size/mtime/ctime plus digest after every external inspector and once before the
  result is consumable; refuse if any check attempts mutation. Add tests that mutate
  the **private copy** permanently and A -> B -> A between two inspector calls for
  both device APK and release AAB paths.

### SECDBG14-03 — the device harness reopens mutable source after proving the APK/source identity

- **Severity / confidence:** Medium / High (confirmed attestation TOCTOU; impact is
  incorrect device-test scope/evidence rather than shipped app code).
- **Classification:** Confirmed.
- **Evidence:** `device-tests/run.py:1473-1494`;
  `device-tests/dtest/contracts.py:452-525,612-620`;
  `device-tests/README.md:82-100`.
- **Problem:** `_run_snapshotted_cli()` first calls `require_apk_source_match()`, which
  descriptor-walks the scoped checkout and proves it matches the APK. It then calls
  `production_capture_subdir(REPO_ROOT)`, which reopens
  `MediaStoreWriter.kt` by pathname and parses `CAPTURE_SUBDIR` from a second mutable
  read. There is no later checkout-source recheck. Thus the harness's media query,
  pull, and mutation scope is not derived from the source snapshot that was just
  attested, despite the runbook claiming that it is mechanically bound to production.
- **Concrete failure:** change only the `CAPTURE_SUBDIR` literal while line 1486 runs,
  then restore it before the run proceeds. The APK and attested source remain A, but
  the `Adb` facade operates on B's `DCIM/<subdir>/`. Depending on the cases/device
  state, the run can miss the app's new captures, compare a different app-owned
  history, or produce misleading absence/delta evidence while its attestation names
  A.
- **Suggested fix:** extract `CAPTURE_SUBDIR` from the same descriptor-owned byte set
  used to construct `current_debug_source_identity`, and return that typed contract
  alongside the proven identity. Do not reopen the checkout after the APK/source
  match. Add a barrier test that changes the production file between identity proof
  and subdirectory resolution and requires refusal or continued use of the frozen A
  value.

### SECDBG14-04 — one granular media grant is misclassified as full access to both collections

- **Severity / confidence:** Medium / High (confirmed policy/state bug; the exact OEM
  permission-settings route needs device validation).
- **Classification:** Confirmed logic bug with a platform-supported failure scenario.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/CameraPermissionPolicy.kt:132-195`;
  `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:313-337,703-715`;
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:81-106`;
  `app/src/test/kotlin/me/hletrd/telecampro/CameraPermissionPolicyTest.kt:181-213`.
  Android documents `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` as granular permissions
  for their respective media types and says requesting both together merely combines
  the system dialog; it does not make either permission authorize the other:
  <https://developer.android.com/about/versions/13/behavior-changes-13#granular-media-permissions>.
- **Problem:** `visualMediaAccessLevel()` returns `FULL` when **either** broad
  permission is granted. `shouldRequestVisualMediaAccess(FULL)` then permanently
  refuses another request, even though restore queries Images and Video independently.
  The unit test explicitly pins the incorrect one-of-two behavior as intended.
- **Concrete failure:** after reinstall or a Settings change, grant Images but not
  Video (or the reverse). A newer previous-install video is invisible. If an older
  image is visible, it wins the in-app review and there is no empty-gallery path to
  request the missing Video permission; if no image is visible, the empty-gallery
  tap still sees `FULL` and only repeats the doomed restore. The symmetric case hides
  stills behind a Video-only grant.
- **Suggested fix:** keep `hasVisualMediaAccess` as an any-access predicate, but make
  request completeness collection-aware: `FULL` only when Images **and** Video are
  granted. Represent Images-only/Video-only separately (or compute the exact missing
  permission set), preserve Android 14 selected-media access as partial, and request
  only the missing broad type plus the selected-media permission where applicable.
  Add all eight grant-combination tests against both the access predicate and the
  exact permissions/re-request decision.

## Final missed-issue sweep

I re-walked the complete tracked text inventory and repeated focused searches for
credentials, network APIs, exported components, implicit intents, unsafe deserialization,
WebView/dynamic code, shell execution, path traversal, symlinks, broad file access,
unbounded resource ownership, swallowed terminal errors, and privacy-policy drift. I
also traced every cycle-13 changed production/tool file against its tests and reran 60
targeted immutable-release/device-attestation/source-contract tests; they all passed,
which confirms the four gaps are missing adversarial/state cases rather than existing
gate failures. The two transient-mutation probes above used temporary directories only.
No source, plan, aggregate, Git, device, deployment, or production state was modified.

The four findings above are the only new evidence-backed current-HEAD issues found in
this specialist pass. Previously fixed or explicitly owner-deferred findings were not
duplicated.
