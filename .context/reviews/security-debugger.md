# Security-reviewer and debugger review — cycle 41

Date: 2026-08-24
Reviewed revision: `4e4c9dfbce294fb2965a56ea63d74d6096744836` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle41.nWoiMj`

## Scope and inventory

I reviewed the isolated clone only after reading the committed authorities in `CLAUDE.md`,
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`. Prior reviews and completed plans were used as
resolved-history ledgers rather than copied forward as findings. The current revision contains 499
tracked paths: 101 production Kotlin files, one production Java file, 228 JVM/instrumented test
files, 32 Python files, 75 Markdown files, and 62 remaining resource, configuration, script,
license, font, and image inputs. Every tracked path participated in Git-mode, credential, dangerous
API, component-exposure, permission, process, URI/path, network, exception, threading, ownership,
and failure-handling searches. Relevant production modules, authorities, tests, manifests, and
cross-file callers were then traced directly rather than sampled.

The security pass covered:

- the source and generated release/debug manifests; CAMERA, RECORD_AUDIO, and granular/selected
  visual-media grants; launcher/debug intent ingress; `DUMP` boundaries; obscured-touch rejection;
  hardware input; external browser/settings navigation; and backup/data-extraction rules;
- current-package versus owner-null MediaStore provenance, exact-file system delete consent,
  capture-family and per-URI discard journals, pending-row structural recovery, late-output
  disposal, provider uncertainty, and bounded process-owned IPC lanes;
- JPEG/HEIF/DNG/video inspection bounds, review decode/playback ownership, settings corruption,
  private preference/SQLite state, EXIF/location behavior, logging, secrets, WebView/dynamic-code
  absence, and the no-network privacy boundary;
- camera route/session generations, Camera2 callback dispatch, ZSL/capture watchdogs, processed and
  RAW continuation ownership, GL/EGL and MediaCodec quarantine, recording allocation/finalization,
  standby microphone lifecycle, timers/tickers, Activity/ViewModel teardown, and stale callback
  suppression;
- Gradle dependency verification, release signing inputs, immutable source/artifact ownership,
  subprocess construction, ADB/device-harness attestation, and the complete cycle-40 delta,
  especially resource shrinking, the synchronous preference durability bridge, and the
  stabilization observation seam.

## Validation evidence

- All 499 tracked Git entries are regular files (`100644` or `100755`); the credential/key scan
  found no tracked private key, API credential, or deployable plaintext secret.
- Generated manifests were rebuilt with
  `:app:processReleaseMainManifest :app:processDebugMainManifest`. Release declares only CAMERA,
  RECORD_AUDIO, the three scoped visual-media permissions, and AndroidX's app-signature dynamic
  receiver permission. It contains no INTERNET, network-state, location, legacy external-storage,
  or all-files permission. The only exported release components are the inert launcher and the
  `android.permission.DUMP`-protected AndroidX Profile Installer receiver. All three debug-only
  exported activities are likewise `DUMP`-protected; the ordinary launcher still ignores command
  extras.
- The manifest keeps `allowBackup=false`; both extraction-rule formats independently exclude the
  preference and database domains that hold settings and exact MediaStore recovery/delete identity.
- Ten focused security, storage-durability, ownerless-consent, overlay-input, orphan-recovery, and
  cycle-40 stabilization/Java-bridge test classes passed under `:app:testDebugUnitTest`. The only
  diagnostic was Robolectric FakeMediaProvider's known SQLite CloseGuard message; Gradle completed
  successfully and no application-owned failed assertion or resource terminal was reported.
- `python3 tools/check_docs.py` passed all 125 applicable checks with zero failures (24 optional
  private-document checks skipped), including privacy/permission parity, manifest/network claims,
  backup/release authority, current field-check membership, and cycle-40 source-authority contracts.
- `git status --short` remained empty after validation. No source, plan, Git history, device,
  deployment, credential, or external service was modified.

## Findings

No actionable security, privacy, authentication/authorization, component-exposure, injection,
unsafe-deserialization, path-traversal, secret-management, MediaStore authority, data-loss,
deadlock, race, resource-leak, stale-callback, lifecycle, native-terminal, or release-evidence defect
survives current-HEAD validation.

The five unresolved physical checks in `docs/FIELD_CHECKS.md` remain correctly classified as
evidence obligations, not host-confirmed bugs: A3 needs a lit rear-camera scene, A4 needs a rotatable
front-camera device, D1 needs an off-axis acoustic A/B, and E1/E2 need real MediaProvider
owner-null/consent behavior. In particular, the host code is deliberately fail-closed around E1/E2,
but it cannot establish an OEM provider's ownership transition or system-consent UI semantics.

## Final missed-issue sweep and count

The final sweep revisited every exported component and permission, incoming/outgoing intent,
overlay/hardware ingress, owner-null admission and consent route, family/DISCARD marker transition,
provider mutation/recovery outcome, parser/allocation bound, private state store, camera/GL/codec
terminal, finite worker owner, Activity/ViewModel lifecycle edge, logging/network/secret surface,
subprocess/signing input, immutable artifact boundary, and every cycle-40 production change. No
review-relevant current path was skipped in the inventory, and resolved prior findings were not
re-filed.

**New security/debugger finding count: 0.**
