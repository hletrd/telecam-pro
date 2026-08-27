# Cycle 57 security-reviewer + debugger deep review

Date: 2026-08-27
Reviewed revision: `b44d5fce43b9a4910143133b6e6e280559704763`
Workspace: isolated clean clone
`/var/folders/kz/t1c9x6qj5zgb2sg_4lv0nh900000gn/T/find-x9-ultra-cycle57.XXXXXX.yRT92pSLwp/repo`

## Shared authority, inventory, and method

I read `CLAUDE.md` completely first, followed by the complete committed design authority
`docs/ARCHITECTURE.md` and the complete field-results ledger `docs/FIELD_CHECKS.md`. I then read the
Cycle 52 through Cycle 56 security/debugger provenance and the current aggregate so closed URI
identity, immutable-review-source, standby-native, DNG-allocation, diagnostic-budget, process-
admission, and release-key findings were challenged for regressions rather than recycled.

The reviewed revision contains 575 tracked paths: 568 regular mode-`100644` entries and seven
intentional mode-`100755` scripts, with no tracked symlink, submodule, FIFO, device, or credential.
The inventory includes 123 production-main paths, 257 test/debug paths, 41 tool/device-harness
paths, 360 Kotlin files, one Java file, 35 Python files, two shell scripts, the Gradle/dependency-
verification and manifest/resource surfaces, six principal authority/privacy-policy paths, and 16
binary font/image/wrapper inputs. Every tracked path participated in mode, credential, permission,
component, URI/path, native-resource, exception-suppression, executor/deadline, and dangerous-API
searches. Binary inputs were covered through Git mode/identity, build/package ownership, asset
manifests, dependency verification, and release checks rather than treated as source text.

The cross-file review traced manifests and merged debug exports, runtime permissions, obscured
input, launcher/debug IPC, settings/SQLite/backup state, MediaStore restore/publication/deletion and
system consent, exact allocation/family/DISCARD identities, review parser/spool bounds, Camera2 and
Image ownership, EGL/codec/muxer/AudioRecord quarantine, lifecycle and Engine replacement,
release-signing/source/artifact sealing, subprocess construction, device-harness attestation,
privacy/no-network/no-location claims, and the matching production tests. No device, provider
mutation, signing, deployment, credential read, or destructive action was performed.

## Security-reviewer provenance

The release manifest still removes `INTERNET` and `ACCESS_NETWORK_STATE`, requests no location,
legacy external-storage, all-files, overlay, package-install, or query-all-packages permission, and
exports only the launcher. Debug-only exported activities remain absent from release and protected
by signature-level `android.permission.DUMP`; dynamic debug receivers are not exported and ordinary
launcher extras are inert. Backup is disabled and both extraction-rule formats exclude preferences
and databases. Owner-null media remains format/path/MIME constrained, visibly unverified, and
limited to exact-file system-consent deletion. No WebView, dynamic-code loading, unsafe object
deserialization, shell-evaluated app input, network client, or plaintext production secret was
found. A content-only scan for common PEM/AWS/Google/GitHub/JWT secret signatures returned zero
matches without reading or printing any credential value.

The one finding below crosses security and debugging because it is a fail-closed storage-
availability boundary: it does not grant unauthorized media access, but it can strand an unbounded
number of private provider rows after a dual persistence/provider fault.

## Debugger provenance

I followed every `createPendingImageAllocation` and `createPendingVideoAllocation` terminal from
provider insert through REGISTERED durability, identity capture, finite retry ownership, write,
COMPLETE, publish/discard, launch recovery, and process-admission publication. The Cycle 56 identity
retry correctly bounds failures that occur *after* REGISTERED succeeds. The residual below is the
earlier commit-failure edge, which exits before that owner can retain the row. I also swept the
recent StartupTrace, diagnostic quota, DNG deadline/publication, process subscription, native
quarantine, review-source, and release-signing changes and found no second current defect.

## Finding

### C57-SEC-DBG-01 — REGISTERED commit + delete failure escapes all process-finite row ownership

- **Severity / confidence:** Medium / High. The ownership escape is source-confirmed; simultaneously
  failing the SharedPreferences commit and MediaProvider delete is a fault-injection/device
  precondition, not claimed field evidence.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:430-443` inserts an
    `IS_PENDING=1` image and delegates to `registerPending`. `:1471-1479` returns null when the
    synchronous REGISTERED commit fails, attempts one resolver delete under `runCatching`, but
    ignores both a false result and an exception.
  - The allocation wrapper at `MediaStoreWriter.kt:454-458` treats that null exactly like a failed
    insert: it cancels the already-acquired pending-identity reservation and forgets the URI. The
    video path repeats the same terminal at `:491-510`.
  - The Cycle 56 finite identity owner starts only after a durable REGISTERED row reaches
    `captureAllocationResult` (`:460-473`, `:512-525`). It therefore cannot retain, retry, or close
    admission for the dual-failure row. `cleanupOrphanedPendingBatch` is launch recovery and
    explicitly sweeps only rows older than the current process (`:1161-1182`), so it provides no
    live-process bound or retry edge.
  - `SharedPreferencesDurableEditTest` covers a false commit in isolation, and
    `PendingAllocationIdentityRecoveryTest` covers uncertainty after REGISTERED succeeds. No
    factory-level test combines successful insert, failed REGISTERED commit, and failed/throwing
    delete for either Images or Video.
- **Failure scenario:** storage or preference corruption makes REGISTERED commit return false while
  MediaProvider is also unavailable and rejects the immediate delete. The factory reports ordinary
  allocation failure, releases the finite reservation, and leaves the inserted pending row with no
  durable journal entry or in-memory exact owner. Each retry can repeat that sequence, so one
  process can accumulate unbounded hidden `IS_PENDING=1` rows and consume provider/storage capacity
  while the visible still/REC admission gate continues to reopen. A later clean launch may discover
  old rows, but that does not bound the active process and cannot help while the same dual fault
  persists.
- **Suggested fix:** make post-insert registration failure a typed terminal that retains the row in
  finite process ownership until deletion/absence is authoritative. Freeze exact provider identity
  when possible and use the existing non-destructive retry principles; if identity is unavailable,
  retain a bounded URI/family claim, close new output admission at capacity, and never authorize a
  destructive delete from a fresh URI read alone. Release the pre-insert reservation only after
  confirmed provider absence/delete or transfer to that retained owner. Add Images and Video tests
  for false/throwing REGISTERED commit, false/throwing delete, repeated admission saturation,
  later exact identity/absence recovery, Engine replacement, and process-admission close/reopen.

## Validation and final missed-issue sweep

The focused existing suites `PendingAllocationIdentityRecoveryTest`,
`SharedPreferencesDurableEditTest`, and `OrphanSweepTest` passed. The first invocation stopped before
task execution because the isolated clone lacked an SDK locator; rerunning with the documented
`/Users/hletrd/Library/Android/sdk` environment completed successfully. Their green result is
consistent with the missing composed dual-failure test above.

The final sweep revisited every exported/dynamic component, permission and privacy statement,
owner-null/system-consent boundary, pending/COMPLETE/DISCARD and family transition, provider
identity and parser limit, private file/database/cache owner, camera/GL/Image/codec/muxer/audio
terminal, process-finite worker, lifecycle replacement edge, log/exception surface, release-
signing/subprocess boundary, dependency input, and prior closed finding. `git diff --check` and the
tracked-file/mode inventory were clean before this provenance file was written. Open field checks
A3/A4/A5/D1/E1/E2/E3 remain manual evidence obligations, not code findings.

**New finding count: 1 — Medium severity, High confidence in the confirmed ownership gap; the dual
fault occurrence remains a fault-injection/device boundary.**
