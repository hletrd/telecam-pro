# Security review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299562d52f6b4ddd200f6d410ebd00a54c1d`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope and complete inventory

I reviewed the isolated `origin/main` tree only. I read `CLAUDE.md`,
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` as current authority before examining code.
The retained cycle-37 reviews were used only as a resolved-history ledger; in particular, optimized
Python rejection, the DUMP-protected debug surfaces, ownerless-media consent, overlay filtering,
and immutable build inputs were revalidated on current HEAD rather than re-filed.

The current revision contains 490 tracked paths: 101 production Kotlin files, three debug Kotlin
files, four instrumented-test files, 220 JVM/Robolectric/Compose test files, 32 Python files, 70
Markdown files, and 60 remaining resource, configuration, script, license, metadata, font, and image
files. Every tracked path participated in the file-type, Git-mode/no-symlink, secret, dangerous-API,
component-exposure, process, path, and policy searches. The security trace covered:

- the release/debug merged manifests, permissions, exported launcher, DUMP-protected debug
  activities, process-local command mailbox, external navigation, obscured-touch rejection, and
  hardware-key input;
- CAMERA, RECORD_AUDIO, and visual-media permission state, app-owned versus owner-null provenance,
  exact-file system delete consent, durable family/DISCARD authority, and late-output disposal;
- MediaStore URI and filename grammar, bounded JPEG/HEIF/DNG/video inspection, preferences and
  SQLite ownership, backup exclusions, provider IPC timeouts, and process-lifetime finite queues;
- release signing inputs, Gradle dependency verification, immutable debug/release source snapshots,
  artifact attestation, subprocess construction, ADB tooling, and device-evidence guards;
- network, WebView, dynamic-code loading, unsafe deserialization, arbitrary-file ingress, logs, and
  tracked credentials. The shipping manifest removes Internet and network-state permissions; no
  WebView, dynamic loader, plaintext secret, private key, unsafe shell construction, or unbounded
  untrusted archive extraction was found.

## Verification evidence

- The current optimized-runtime regression is closed: both `python3 -O tools/verify_host.py` and
  `python3 -O tools/check_docs.py` reject execution with exit 2 and the expected diagnostic.
- The 99 tool tests, nine coverage-tool tests, and 184 device-harness self-tests passed. The
  documentation gate passed all 120 applicable checks, and Python compilation passed.
- Debug component exposure remains restricted by the signature-level `android.permission.DUMP`;
  the ordinary exported launcher does not parse command extras. Production still declares no
  network capability after manifest removal rules.
- Git modes contain only regular tracked files, and the credential/key scan found no tracked secret
  material. No device, network service, deployment, source, plan, or Git state was changed by this
  review.

## Findings

No actionable security, privacy, authentication/authorization, secret-management, injection,
unsafe-deserialization, component-exposure, path-traversal, or evidence-integrity defect survives
current-HEAD validation.

The one observed host-gate failure belongs to a scheduler-dependent concurrency test and is recorded
only in `debugger.md`; it does not create a shipping security boundary failure.

## Final missed-issue sweep and count

The final sweep revisited every exported component and permission, external intent and URI boundary,
overlay/hardware ingress, owner-null provenance and delete-consent path, provider mutation and
recovery state, durable marker, parser/allocation bound, private state store, secret/log/network
surface, subprocess, signing input, immutable artifact owner, and every cycle-37 security-relevant
delta. No review-relevant current path was skipped or sampled in place of the repository inventory.

**New security finding count: 0.**
