# Security review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle36.TOpdQ8`

## Scope and complete inventory

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` before the
review, then checked the current aggregate/specialist reports and completed cycle-35 plan so fixed
history was used as regression guidance rather than re-filed as new work.

The complete current inventory is 486 tracked paths. It contains 101 production Kotlin files,
three debug Kotlin files, four instrumented-test files, 218 JVM/Robolectric/Compose tests, 32 Python
files, 68 Markdown documents, 38 configuration/resource/script files, 16 binary assets, and the
remaining repository metadata/license/wrapper inputs. Every tracked path was included in the file
census, type/no-follow review, and SHA-256 inventory; all text/code/configuration paths were included
in repository-wide source and policy searches. Binary assets were checked by type, dimensions or
embedded identity as applicable, and provenance/license metadata.

The security review traced the complete shipping and tooling trust surface across files:

- merged debug/release components, permissions, profile-installer and debug-only activities;
- launcher/debug intent ingress, process-local command mailbox, external navigation, obscured-touch
  rejection, hardware input, ActivityResult and PendingIntent ownership;
- camera/microphone/visual-media runtime permission state, full versus selected-media access, and
  permission persistence/backup exclusion;
- MediaStore insert/query/publish/delete operations, owner-null restore grammar and provenance,
  exact-file system consent, app-owned family deletion, durable tombstones, pending/discard journals,
  orphan recovery, provider timeouts, and late sibling handling;
- review decode/EXIF/video paths for selected or owner-unverified media, including allocation and
  parser bounds;
- tracked secrets, signing inputs, Gradle dependency verification, release/debug immutable-source
  boundaries, no-follow path handling, subprocess construction, device evidence, and attestation;
- network, WebView, dynamic-code, arbitrary-file, unsafe deserialization, cryptographic-key, and
  native-loading surfaces (none exists in the shipping app beyond fixed platform/media/GL APIs).

The final cycle-35 change surface received a separate regression review: outgoing dual-open owner
cleanup, capture-refusal status, audio overload classification, mirrored EXIF transforms, and host
evidence checks.

## Verification evidence

- The manifest keeps `INTERNET` and `ACCESS_NETWORK_STATE` removed. Release exposes only the launcher;
  debug/tooling exported activities remain `android.permission.DUMP` protected, with the Compose
  test host unexported.
- MainActivity ignores launcher extras. Debug camera commands enter only through the debug variant's
  DUMP-protected activity and process-local atomic mailbox; release has no exported producer and the
  consumer is also gated by `BuildConfig.DEBUG`.
- Owner-null media is admitted only through exact collection, directory, published state, null
  owner, filename grammar, extension, and MIME constraints. It remains provenance-unverified and
  file-only, and deletion uses `MediaStore.createDeleteRequest` rather than direct resolver deletion.
- App-owned capture-family deletion is durably marked before acknowledgement, publication and
  producer leases share exact-family authority, and uncertainty fails closed instead of adopting or
  publishing deleted media.
- No tracked credential/private key, plaintext production secret, shipping network permission,
  WebView, dynamic loader, or arbitrary filesystem ingress was found. App preferences/databases are
  excluded from both backup rule formats and `allowBackup` is false.
- Forty-two focused current-HEAD tests passed with zero failures/errors, covering debug component
  security, ownerless delete lifecycle/operation, dual-open ownership, still-admission status, audio
  peak truth, and all EXIF orientation transforms.

## Findings

No actionable security finding survives current-HEAD verification.

The open checks in `docs/FIELD_CHECKS.md` are correctly classified as device/manual-validation
obligations. This host-only review did not touch device state and does not convert those unmeasured
claims into passes or code defects.

## Final missed-issue sweep and coverage confirmation

The final sweep rechecked every exported component and permission, all intent and URI boundaries,
overlay/touch defenses, provider mutation and owner-null classification, parser/allocation bounds,
private durable state, secrets/logging/network surfaces, build/signing inputs, immutable artifact
ownership, subprocess construction, and device-evidence contracts. It also replayed the complete
cycle-35 delta against the relevant tests and older fixed findings. No Critical, High, Medium, or
Low security finding remains in this specialist scope, and no review-relevant tracked path was
skipped.

**New finding count: 0.**
