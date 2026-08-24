# Cycle 53 security-reviewer + debugger review

Date: 2026-08-25  
Reviewed revision: `fcf7ba2ca856fe8885373eb75677c3057173e6d6` (`cycle53`, `origin/main`)  
Tree: `4df2f3b2ee8a7a378be43c932c1fbe1c2ee1168f`  
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle53.cJwfCJ`

## Scope, authority, and complete inventory

I read `CLAUDE.md` first, then the committed current authorities `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md`. I also read the Cycle 52 security, performance/concurrency, code/architecture,
aggregate, and completed implementation records so fixed findings were challenged as regressions
rather than blindly re-filed. Optional private maintainer authorities are absent, as the clean-clone
policy permits.

The revision contains 546 tracked paths: 539 regular mode-`100644` entries and seven intentional
mode-`100755` host scripts/wrappers. There is no tracked symlink, submodule, device, FIFO, private key,
or credential. The executable/source inventory includes 102 production Kotlin files, one production
Java file, three debug Kotlin files, four instrumented-test Kotlin files, 241 JVM/Robolectric/Compose
test files, 33 Python files, and two shell scripts; the remaining paths are manifests/resources,
Gradle and dependency-verification inputs, privacy/Play documentation, assets/binaries, licenses,
plans, and historical reviews.

Every tracked path participated in mode/credential/packageable-input searches. The direct source and
cross-file review covered every production package and all matching security/lifecycle tests:

- main/debug/merged-component intent, permission, obscured-input, dynamic receiver, external
  navigation, PendingIntent, backup/extraction, preferences, and SQLite boundaries;
- owner/package/name/path/MIME MediaStore restore admission; exact-file consent; pending publication,
  structural recovery, family and URI journals, delete/replay conditions, review input/decode
  limits, cache-spool cleanup, and every finite provider worker/queue;
- Camera2 callback/session teardown, GL/EGL surfaces and object names, Image/ImageReader ownership,
  retained stills, MediaCodec/MediaMuxer/Surface/AudioRecord setup and termination, process
  quarantine, watchdogs, Engine replacement, and late-callback suppression;
- immutable debug/release source export, signing-file and secret-value separation, artifact
  inspection, dependency verification, subprocess construction, device-harness attestation, and
  privacy/no-network/no-location claims;
- skipped/ignored tests, failure-injection gaps, swallowed cleanup, unbounded work, stale identities,
  check/use gaps, integer/size bounds, and Cycle 52's complete production delta.

Authentication accounts do not exist in this offline app. The relevant authorization surfaces are
Android component permissions, MediaStore row provenance, and system-owned deletion consent. The
release source declares no Internet, network-state, location, legacy external-storage, all-files,
overlay, package-install, or query-all-packages permission; it contains no WebView, dynamic code
loading, unsafe object deserialization, plaintext production secret, or app-input shell evaluation.
Backup is disabled and both backup-rule formats exclude preferences and databases. Debug exported
activities are absent from release and DUMP-protected; dynamic debug receivers are not exported.

## Findings

### C53-SEC-DBG-01 — standby native ownership still has close/publication gaps around quarantine

- **Severity / confidence:** **High / High**.
- **Classification:** **Confirmed synchronization/invariant defect.** A concrete vendor-native crash,
  leaked microphone indicator, or stuck camera still needs the losing interleave on a device; the
  unowned/late-cleanup paths themselves are source-provable.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:1211-1243` counts a general
    native call, executes it outside the gate lock, decrements the count, and then reports
    `RETURNED_CURRENT` if quarantine is not set at that instant. The returned enum is not a lease:
    `close()` may win immediately after the final lock is released.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:531-564` creates the
    `AudioRecord` through that call but binds the returned input to its termination owner only after
    `RETURNED_CURRENT` has escaped the gate. `:566-584` likewise publishes native start completion
    through `finishStart` after the typed call returns.
  - Ordinary worker cleanup remains at `StandbyAudioController.kt:642-648`. Thus quarantine between
    `RETURNED_CURRENT` and `bind`/`finishStart` is not classified as revoked; the input can later be
    stopped and released after the process native graph has already become terminal.
  - The new stop deadline has the inverse ordering hole. `StandbyAudioController.kt:238-251` first
    calls `abandon(value)` and only then invokes `onStopTimeout`. The production callback reaches
    `retainUnsafeInput` at `:659-672`, which sets only this controller's `nativeTerminal` before it
    calls the process-wide retention function. The global gate is not actually closed until
    `UnsafeRecorderQuarantine.quarantineNativeGraph` at `VideoRecorder.kt:1431-1437`. A different
    Engine/native caller can therefore acquire in the abandon-to-close interval.
- **Concrete failure:** Native create returns successfully and the gate reports current. Before the
  controller binds the input, an unrelated camera/recorder terminal closes quarantine. The
  controller then binds the now-post-terminal `AudioRecord`; its next native start may be rejected,
  and `finally` performs ordinary stop/release. Conversely, when standby `stop()` times out, the
  exact input becomes abandoned before process admission closes, so a replacement Camera2/GL/audio
  acquisition can enter while the old microphone lifetime is already uncertain. Both sequences
  violate the architecture's process-restart-only quarantine contract and can multiply or clean a
  native graph after the point at which cleanup was declared unsafe.
- **Why current tests miss it:**
  `StandbyAudioControllerTest.kt:845-906` injects `RETURNED_REVOKED` directly and proves that already
  classified result. `:909-1015` invokes the deadline and closes its fake process gate from the
  retention effect without pausing another acquisition in the abandon-before-close interval. No
  test returns `RETURNED_CURRENT`, pauses before `bind` or `finishStart`, closes the gate, and then
  resumes. The process-gate tests prove close during the native block, not close immediately after a
  current return and before caller publication.
- **Fix:** Make creation/start publication part of the process gate transaction. Return an exact
  native-acquisition token, then under the gate lock atomically either (a) commit the concrete input
  into a strongly retained termination owner, or (b) classify/retain it as revoked; a bare enum must
  not authorize later publication. For stop timeout, close process admission and install strong
  quarantine retention before exposing `abandoned` or releasing logical waiters, preferably through
  one process-gate operation. Add deterministic create-return and start-return latches plus an
  abandon-before-close competing-acquisition test; assert no post-close stop/release and no second
  native acquisition.

### C53-SEC-DBG-02 — DISCARD hardening protects replay but immediate deletion is still URI-only

- **Severity / confidence:** **Medium / High** for the unsafe mechanism; **Medium** confidence that a
  production MediaProvider will reassign the exact URI inside the narrow windows without the still-
  open E3 field setup.
- **Classification:** **Confirmed destructive check/use gap; provider reassignment is a manual-risk
  precondition.**
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:33-79` now reads the row
    identity and writes a version-2 marker, but it binds whatever row occupies the supplied URI. The
    API accepts no expected allocation/family identity from its caller.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:453-475` commits that strong
    marker and then calls ordinary `delete(context, uri)`. `:827-849` shows that ordinary delete
    passes `expectedIdentity = null`, producing `ContentResolver.delete(uri, null, null)`. The
    identity just persisted is not used for this destructive call.
  - Conditional identity is used only later during launch replay at `MediaStoreWriter.kt:1212-1267`.
    Even there, `discardDeleteCondition` at `:1315-1341` omits `OWNER_PACKAGE_NAME` and `DATE_TAKEN`
    whenever the expected value is null instead of requiring `IS NULL`; it also cannot include the
    stored provider version. Android documents that owner may legitimately be null and that a
    MediaStore version change resets the meaning of generation values.
  - The caller often has stronger truth but discards it. For example,
    `capture/StillCapturePipeline.kt:382-396` closes over the exact `familyKey`, yet passes only the
    output URI to `discardPendingOutput`; `camera/RetainedStillDeletionOwner.kt:64-84,230-270`
    retains capture/family ownership but its discard function is URI-only.
- **Concrete failure:** After a deleted late still is published, the family path starts DISCARD.
  The marker records row A, but a gallery/provider reset deletes A and reuses its URI for row B
  before line 467. The unconditional immediate delete removes B, then the absence probe clears A's
  marker. A second variant replaces the row before `mark`; because no caller-expected family is
  checked, the new identity is durably blessed and then deleted. The v2 replay fix does not cover
  either path. If a stored expected owner/date is null, a remap differing only in that field can also
  pass the supposedly exact replay predicate.
- **Why current tests miss it:** `PendingDiscardJournalTest.kt:517-541` remaps generation only
  between replay query and the *conditional replay* delete. `:277-303` exercises
  `discardPendingOutput`, but its provider keeps one row permanently attached to the URI; it neither
  checks the selection nor remaps after marker commit. No test supplies caller-expected family truth
  or changes the row before marker creation.
- **Fix:** Capture immutable allocation identity when the pending URI is created and carry it with
  the still/video publication packet. Require that identity/family at `mark`, return the exact
  committed record, and use its full null-safe predicate for the immediate delete. Express nulls as
  `IS NULL`, fail closed across provider-version change, and never let a fresh identity read by itself
  authorize a stale caller's destructive intent. Add remap-before-mark and remap-after-mark/
  before-immediate-delete tests. Preserve `docs/FIELD_CHECKS.md` E3 for real provider reset/reindex
  evidence; Android's current version/generation contract is documented in the
  [MediaStore API](https://developer.android.com/reference/android/provider/MediaStore).

### C53-SEC-DBG-03 — trusted review re-resolves one URI three times without snapshot identity

- **Severity / confidence:** **Medium / Medium**.
- **Classification:** **Likely denial-of-service/correctness risk with a confirmed TOCTOU shape.**
  Provider edit/reassignment timing and decoder memory behavior need manual/device validation.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:463-498` maps
    `MediaProvenance.APP_OWNED` to `FreshProviderReviewSource`, whose `openInputStream()` calls the
    provider anew every time and whose `close()` owns no stable handle.
  - `MediaReview.kt:500-528` performs bounds decode, pixel decode, and EXIF lookup through separate
    `openInputStream` calls. The sampling decision is based on the first stream; the decoded-size
    check happens only after the second stream's bitmap allocation. `:538-553` opens the third
    stream for orientation.
  - `storage/LatestCaptureReducer.kt:18-28,320-329` defines `APP_OWNED` only as MediaStore attribution
    to the current package. That is provenance, not a lease proving that three later URI resolutions
    name the same bytes. A system gallery, user-approved edit/delete, or provider reset can mutate or
    reassign the row after restore.
- **Concrete failure:** Bounds open observes a small trusted JPEG and chooses `inSampleSize = 1`.
  Before pixel open, a system gallery edit or MediaStore delete/reindex makes the URI resolve to a
  much larger image. BitmapFactory attempts the large allocation before
  `reviewDecodedFitsBound` can recycle it, so a review tile can OOM or severely stall the process.
  A change only before the third open applies unrelated EXIF orientation to otherwise valid pixels.
  Package ownership prevents an ordinary unprivileged app from directly rewriting the row, but it
  does not make repeated provider opens an immutable snapshot.
- **Why current tests miss it:** `ReviewDecodeSourceTest.kt:28-60` explicitly asserts at least three
  fresh opens, but every open targets one unchanged local file. The owner-unverified tests mutate
  source bytes and correctly prove that their spool is immutable; there is no corresponding
  mutation/remap test for the new trusted path.
- **Fix:** Use one stable, seekable provider descriptor/snapshot per decode request and derive
  independently positioned streams from duplicated descriptors, or spool trusted input to a
  disk-budgeted private file without the former 64 MiB ceiling. If a provider cannot supply a stable
  seekable descriptor, identity-check every stage and fail before pixel decode on any change; do not
  rely on the post-allocation dimension check. Add deterministic small-bounds/large-pixels and
  pixels/changed-EXIF source tests, including exact disposal and latest-wins cancellation.

## Verified non-findings and validation limits

- The Cycle 52 fixes for policy StateFlow ordering, rollback callbacks outside the Engine monitor,
  stale review-spool reclamation, focus ownership, PNG `tRNS` range, and v1 DISCARD migration are
  present. The findings above are residual boundaries not duplicates of the fixed cases.
- The dedicated review-spool cleanup rejects symlinks and unexpected entry types without following
  them, limits scanning, keeps current-generation files, and confines deletion to the private
  schema directory. No additional path traversal survived review.
- Current-package and owner-null restore remain separated: owner-null candidates require exact
  directory/name/collection/extension/MIME grammar, are labeled unverified, are spooled before
  decode, and receive exact-file/system-consent deletion rather than family deletion.
- Camera, microphone, media access, exported launcher/debug surfaces, obscured touch, hardware input,
  dynamic receivers, external URLs/settings intents, private stores, backup exclusion, and privacy/
  Play declarations agree with current source. No OWASP-style network, WebView, credential,
  deserialization, path traversal, or broad exported-component issue was found.
- Camera2, GL, still/RAW, recorder/muxer, storage, callback-sink, executor/queue, watchdog, and
  teardown owners were swept for a second concrete leak or post-terminal publication. No additional
  issue survived beyond C53-SEC-DBG-01.
- `git diff --check` passed. No build/test command was run because this review task permits writing
  only the review artifact; the repository's Cycle 52 plan records the full green host gate for the
  exact implementation delta. The focused tests cited above establish why their covered cases do
  not force these missing interleavings.
- No device, deploy, MediaProvider reset, microphone HAL fault, Camera2 fault injection, signing,
  credential mutation, external communication, or destructive operation was performed. Field checks
  A3, A4, A5, D1, E1, E2, and E3 remain manual evidence obligations and are not claimed passed.

## Final missed-issue sweep

The final pass re-inventoried all 546 tracked paths and revisited every exported or dynamically
registered component, dangerous permission, incoming/outgoing intent, touch/hardware ingress,
owner-null restore/consent path, family/URI marker transition, provider mutation and parser bound,
private file/database/cache owner, Camera2/GL/Image/codec/muxer/audio terminal, process-finite worker,
lifecycle replacement edge, release-signing/subprocess boundary, secret/log/network/location
surface, test skip/failure-injection seam, and every Cycle 52 production change. Binary assets were
covered through Git identity/mode, committed digests/parsers, package ownership, and release gates
rather than treated as source text. No review-relevant file or prior unresolved policy was skipped.

**New finding count: 3 — one High/High confirmed native-ownership race; one Medium destructive
check/use gap with High-confidence mechanism and manual reassignment precondition; one Medium/Medium
trusted-review TOCTOU risk.**
