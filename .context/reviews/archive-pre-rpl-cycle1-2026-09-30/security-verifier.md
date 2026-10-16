# Security reviewer + verifier review — cycle 32

Date: 2026-08-24
Reviewed revision: `64eff08e` (`main`, equal to `origin/main` at review start)
Workspace: isolated clean clone `/private/tmp/find-x9-rpf32.SEkU6E/repo`

## Scope and inventory

I read the repository authority first: `CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`, followed by the public README/privacy/Data Safety authorities, the current
aggregate, and completed plans through cycle 31. The inventory contained 440 tracked paths: 115
production Android paths, 209 debug/instrumented/host-test paths, 36 tool/device-harness paths, and
63 documentation/privacy/site paths (the categories overlap where Git pathspecs do). The production
inventory contains 98 Kotlin files; the host/instrumented inventory contains 205 Kotlin/Java tests;
and the tool/harness inventory contains 32 Python/shell programs.

The review covered every production source/config/resource through a complete file census and
systematic API/pattern sweeps, then traced the security-sensitive flows across their concrete
callers and tests: release/debug manifests and exported components; CAMERA/RECORD_AUDIO/visual-media
permission decisions; obscured-touch handling; external Intent and URI launches; current-package and
owner-null MediaStore restore, provenance, review, deletion, pending-row recovery, and exact-family
tombstones; EXIF/privacy claims; backup/network/secrets posture; Camera2/GL/MediaCodec/AudioRecord
foreground and terminal ownership; preference/SQLite bounds; Gradle dependency verification and
signing; immutable debug/release artifacts; device-harness attestation; shell/subprocess/path/symlink
boundaries; and the tests and documentation that claim those behaviors. I also reviewed every
cycle-31 source delta separately because it changed deletion, retirement, preview binding, recording
review admission, and UI state.

Current source still has no tracked credential/private key, no app network client or release
`INTERNET`/`ACCESS_NETWORK_STATE` permission, no location permission or GPS-write lane, and no
release-exported component beyond the launcher. Backup is disabled and both extraction rule sets
exclude preferences/databases. The debug snapshot activity is release-absent and protected by the
signature-level `android.permission.DUMP`. The Activity rejects both fully and partially obscured
touches before Compose. Owner-null media is narrowly filtered by exact directory, filename grammar,
collection, extension, and MIME and is visibly labeled origin-unverified; the finding below concerns
only whether the advertised delete action can complete under Android's authorization contract, not
whether those rows are safely attributed.

Focused verification completed during this pass:

- `CameraPermissionPolicyTest`, `LatestCaptureReducerTest`, `CameraViewModelRobolectricTest`, and
  `CaptureOutputTrackerTest`: passed under `:app:testDebugUnitTest`.
- Static secret, symlink/special-file, exported-component, dangerous-permission, network API,
  WebView/dynamic-code, URI, SQL, subprocess/shell, and native-owner sweeps: no additional actionable
  result.
- The full repository gate is intentionally left to Prompt 3; this specialist made no source change.

## Finding

### SECVER32-01 — restored ownerless media has no platform-authorized delete path

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed correctness/platform-authorization defect; the exact system prompt
  appearance should be device-validated on API 33 and the target API 36 device.
- **Evidence:**
  - `storage/LatestCaptureReducer.kt:288-345` admits a matching owner-null row as
    `LEGACY_FORMAT_UNVERIFIED` and deliberately assigns `FILE_ONLY`; its own comment at lines 331-334
    acknowledges that, after reinstall, MediaStore cleared ownership and ordinary per-row deletion
    fails.
  - `ui/review/MediaReview.kt:1445-1503` nevertheless exposes an enabled destructive confirmation;
    `values/strings.xml:412-414` and `values-ko/strings.xml:395-397` promise that the file is deleted.
    `PRIVACY.md:19` and the bundled EN/KO policy likewise describe file-only deletion for these rows.
  - `ui/CameraViewModel.kt:3348-3471` sends every `FILE_ONLY` plan directly to its background provider
    delete. It has no Activity result / `IntentSender` boundary and therefore cannot obtain write
    authorization for an ownerless row.
  - `storage/MediaStoreWriter.kt:808-812,1786-1825` calls
    `ContentResolver.delete(uri, null, null)` directly. A thrown `SecurityException` is reduced to a
    present/unknown survivor and a retry status, so retrying Gallery repeats the same unauthorized
    operation indefinitely.
  - No source or test references `RecoverableSecurityException`, `MediaStore.createDeleteRequest`,
    or an `IntentSender` result. Existing legacy/provenance tests prove `FILE_ONLY` grouping and
    survivor restoration, not Android write consent.
  - Android's current scoped-storage contract says apps need user consent to remove media they do
    not own, and Android 11+ provides `MediaStore.createDeleteRequest()` for the mandatory system
    confirmation: <https://developer.android.com/training/data-storage/shared/media> and
    <https://developer.android.com/reference/android/provider/MediaStore#createDeleteRequest(android.content.ContentResolver,%20java.util.Collection)>.
- **Concrete failure scenario:** A user reinstalls TeleCam Pro, grants the contextual photo/video
  read permission, and opens a prior-install capture whose `OWNER_PACKAGE_NAME` is now null. Review
  truthfully labels it unverified and offers **Delete file**. After the app's confirmation, the direct
  resolver delete is rejected because the new installation does not own the row. The file is restored
  into review with “retry in Gallery”; every retry follows the same path, so the advertised in-app
  deletion can never succeed on the spec path.
- **Suggested fix:** Keep the current provenance and one-file scope, but route
  `LEGACY_FORMAT_UNVERIFIED` deletion through an Activity-owned
  `MediaStore.createDeleteRequest(contentResolver, listOf(uri))` launcher. Acquire the existing
  modal/input owner before the system prompt; on `RESULT_OK`, consume the system-completed deletion
  and reconcile tracker/UI/provider truth without issuing an unauthorized second delete; on cancel
  or launch/security failure, restore the exact unverified survivor and show truthful localized
  status. Leave `APP_OWNED` capture-family deletion on the existing durable background path. Add
  pure routing tests, Activity-result/Robolectric coverage for approve/cancel/unresolved outcomes,
  and API-33/API-36 device checks using an actual prior-install/owner-null row.

## Final missed-issue sweep

The final sweep rechecked all 440 tracked paths and the ignored-but-present review provenance against
the current source after tracing SECVER32-01. It revisited release and debug manifest merges,
permission combinations, app-ops/foreground teardown, every MediaStore query/update/delete entry,
owner-null spoof boundaries, exact-family deletion races, native resource quarantine, external
navigation, EXIF/location, logs, backup, secrets, dependency hashes, immutable release/debug seals,
artifact inspection, device attestation/report ownership, and cycle-31's changed deletion and
retirement paths. Previously fixed findings and explicit product decisions were not re-filed.

No additional actionable security or verification finding survived evidence checking. The platform
currently prevents unauthorized ownerless-row deletion; the defect is the missing consent-capable
route and the resulting false user-facing behavior, not an authorization bypass or data exposure.

**Finding count:** 1 total — 1 Medium. No Critical or High finding.
