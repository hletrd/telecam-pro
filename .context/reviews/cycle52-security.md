# Cycle 52 security-reviewer + test-engineer review

Date: 2026-08-25  
Reviewed revision: `96732cc9` (`cycle52`, `origin/main`)  
Workspace: isolated clone `/tmp/find-x9-ultra-cycle52.868ovy/repo`

## Authority, inventory, and method

I read `CLAUDE.md` completely first, followed by `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md` completely. I then read the current and prior security/test aggregate history
and the cycle-48 through cycle-51 implementation records so resolved findings, accepted product
decisions, and explicit field-only evidence were not re-filed as current defects. Optional private
maintainer documents are absent in this clean clone, as the committed authority permits.

The revision has 540 tracked paths, all regular Git entries (533 mode `100644`, seven mode
`100755`; no tracked symlink, submodule, FIFO, device, private key, or credential): 102 production
Kotlin files, one production Java file, 15 main resources, three debug Kotlin files, four
instrumented-test files, 240 JVM/Robolectric/Compose test files plus one test resource, 33 Python
files, two shell scripts, 96 Markdown files, 16 tracked binary font/image/wrapper inputs, and the
remaining build, dependency-verification, privacy, license, configuration, and asset metadata.
Every tracked path participated in the Git-mode, credential, permission/component, backup, network,
location, intent/IPC, URI/path, dangerous API, native/platform, logging, exception/suppression,
thread/executor, timeout, and test-risk searches. All production and test source also passed through
the compiler or language/static gates described below. Binary files were inspected through Git
identity/mode, LFS/asset manifests, digests, parser checks, and build/artifact ownership rather than
treated as source text.

The direct cross-file review covered:

- source and merged manifests; runtime CAMERA, RECORD_AUDIO, and visual-media permission flows;
  launcher/debug intent ingress; DUMP-protected debug surfaces and dependency receivers; obscured
  input; external navigation; private preferences/SQLite state; and backup/extraction rules;
- current-package and owner-null MediaStore restore authorization; exact-file system consent;
  family and per-URI journals; pending publication, deletion, structural recovery, URI/path/input
  validation, review decode/spooling, and every process-finite provider lane;
- Camera2 route/session and callback authority, still/RAW correlation, capture watchdogs, GL/EGL
  ownership, MediaCodec/MediaMuxer/AudioRecord terminal handling, recording allocation/storage,
  process quarantine, lifecycle replacement, and stale-callback suppression;
- immutable debug/release source exports, signing inputs, dependency verification, frozen artifact
  inspection, packaged permissions, subprocess construction, device-harness attestation, secrets,
  privacy statements, and no-network/no-location claims;
- the complete test inventory for skipped/disabled/tautological tests, timing sleeps, false
  assurances, unexercised destructive transitions, failure injection, and field-only boundaries.

Authentication accounts do not exist in this offline app. The applicable authentication/
authorization surface is Android component permissions plus exact MediaStore ownership and system
consent. The release source contains no INTERNET, network-state, location, legacy external-storage,
all-files, overlay, install-package, or query-all-packages permission; no WebView, dynamic code
loading, unsafe object deserialization, plaintext production secret, or shell-evaluated app input
was found. Backup is disabled and both backup-rule formats exclude preferences and databases.

## Finding

### C52-SEC-TDD-01 — a durable DISCARD marker can delete a different media row after MediaStore identity reset

- **Severity / confidence:** Medium / Medium.
- **Status:** **Likely data-loss risk; the destructive mechanism is confirmed.** Actual `_ID`/URI
  reassignment after a radical MediaStore change is provider/device behavior needing manual
  validation, but the implementation has no identity or version check if it occurs.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:9-46` describes an
    “exact MediaStore URI” owner and persists only the raw URI string.
  - `PendingDiscardJournal.kt:107-149` pages those strings without any accompanying collection,
    filename, family, owner, path, MIME, or provider-version identity.
  - `PendingDiscardJournal.kt:215-244` defines schema v1 as
    `pending_discards(uri TEXT PRIMARY KEY)`; the metadata table records only legacy-migration
    completion.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:1200-1224` converts each
    persisted string back to a URI and calls `delete(context, uri)` directly. That path reaches
    `MediaStoreWriter.kt:823-852`, where `ContentResolver.delete(uri, null, null)` and a same-URI
    existence probe are the only checks. It never re-queries `DISPLAY_NAME`, `RELATIVE_PATH`,
    `OWNER_PACKAGE_NAME`, MIME, capture-family identity, or MediaStore version before deletion.
  - There is no `MediaStore.getVersion(...)` use anywhere in production or tests. Android's
    [MediaStore authority](https://developer.android.com/reference/android/provider/MediaStore#getVersion(android.content.Context,java.lang.String))
    says cached MediaStore data must be fully resynchronized after the opaque version changes
    because it indicates a more radical state change and generation identities have reset.
- **Failure scenario:** TeleCam durably marks capture URI
  `content://media/external/images/media/417` for deletion, but provider deletion fails, so the
  marker correctly survives a restart. MediaStore is then rebuilt/reset and its opaque version
  changes; row ID 417 is later assigned to another current-package capture. Launch recovery pages
  the old marker, deletes whatever row 417 now names, observes that URI absent, and clears the
  marker. The replacement capture is irreversibly deleted even though the user deleted a different
  capture. Because the replacement can be app-owned, scoped-storage authorization does not require
  a system consent surface to stop the mistake.
- **Why current tests give false assurance:**
  `app/src/test/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournalTest.kt:34-60` proves only
  stable lexical paging of arbitrary URI strings; `:275-297` proves retry against the same provider
  identity; and `:299-423` proves exact-string publication/discard serialization. The recovery tests
  keep one fixture row permanently attached to one URI. No test changes the row behind a persisted
  URI, changes MediaStore version, or asserts that filename/path/owner/family still match before the
  destructive replay.
- **Suggested fix:** make a DISCARD record a versioned expected-media identity, not a URI string:
  persist collection/volume, MediaStore version at capture, canonical display name or
  `CaptureFamilyKey`, relative path, MIME, and expected owner alongside the URI. Before replaying a
  delete, query the current row and require every stored identity field to match. If the MediaStore
  version changed, resynchronize by the stored canonical identity/family and never delete solely by
  the stale `_ID`; unreadable or ambiguous identity must remain pending/reported, not be treated as
  authorization. Migrate URI-only v1/legacy markers conservatively. Add a fake-provider test that
  maps a URI to the expected row when marked, then remaps it to a replacement row before recovery;
  the replacement must survive and the marker must remain unresolved or be safely reconciled. A
  disposable-device MediaProvider reset/reindex check should establish the real OEM reassignment
  behavior without risking operator media.

## Validation evidence

- `python3 tools/check_docs.py` passed all 154 applicable checks with zero failures; 24 explicitly
  optional private-document checks were skipped.
- Focused JVM/Robolectric storage and review suites passed:
  `PendingDiscardJournalTest`, `OrphanSweepTest`, and `ReviewSourceSpoolTest`.
- Sixty-four immutable release, artifact, output-freezing, and release-source-gate Python tests
  passed under `python3 -W error`.
- The focused Gradle invocation initially failed before task execution because the clean clone had
  neither `local.properties` nor SDK environment variables. Re-running with the repository's
  documented `/Users/hletrd/Library/Android/sdk` environment completed successfully; this was an
  environment preflight, not a test failure.
- The test-risk sweep found no ignored/disabled tests or production TODO/FIXME implementation
  stubs. The only one-millisecond sleeps are bounded eventual assertions in family-deletion and
  standby-audio tests, not fixed-duration success assumptions.
- No device, deployment, production signing, credential mutation, external communication, or
  source change was performed. A3, A4, A5, D1, E1, and E2 remain the six explicit physical evidence
  obligations in `docs/FIELD_CHECKS.md`; none is claimed passed here.

## Final missed-issue and skipped-file sweep

The final sweep revisited every exported component and merged permission, runtime permission owner,
incoming/outgoing intent, overlay/hardware ingress, owner-null restore/consent boundary, family and
per-URI marker transition, provider mutation/recovery result, parser/allocation limit, private data
store, camera/GL/codec/audio terminal, process-finite worker, lifecycle replacement edge, logging/
network/location/secret surface, dependency/signing/subprocess boundary, immutable artifact seal,
test skip/flakiness signal, and every cycle-51 production change. It also rechecked all 540 tracked
paths against the inventory and found no skipped review-relevant file. Prior resolved findings and
documented field-only evidence were not re-filed.

**New security/test finding count: 1 — Medium severity, Medium confidence; likely data-loss risk with
confirmed mechanism and manual provider-reassignment validation outstanding.**
