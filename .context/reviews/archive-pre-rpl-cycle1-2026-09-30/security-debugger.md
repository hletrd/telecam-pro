# Security-reviewer and debugger review — cycle 42

Date: 2026-08-24
Reviewed revision: `70ebb8759b567dcd2ee13bd51b226da2568ff6d7` (`origin/main`)
Workspace: isolated cycle clone `/tmp/find-x9-ultra-cycle42.rPLjyN`

## Authority and complete inventory

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely before reviewing
code. `docs/BACKLOG.md` is absent in this clean clone; the committed policy explicitly makes it
optional and designates those three files as the fallback authority. I also inspected the completed
cycle plans, including the one surviving explicit deferral (cycle 35's broad `CameraEngine` facade
decomposition), so resolved findings, accepted limitations, and closed device decisions were not
raised again.

The current revision contains 504 tracked paths: 101 production Kotlin files, one production Java
file, three debug Kotlin files, four instrumented-test Kotlin files, 223 JVM/Robolectric/Compose
test Kotlin files, 32 Python files, 80 Markdown files, two shell scripts, and the remaining build,
resource, asset, license, and configuration inputs. All 504 paths participated in the Git-mode,
credential, permission/component, network/IPC, URI/path, dangerous API, exception, suppression,
thread/executor, timeout, and ownership searches. Relevant implementation and tests were then
traced across their callers and state transitions rather than sampled.

The security/debugger pass covered:

- release and debug manifests; CAMERA, RECORD_AUDIO, and granular/selected visual-media permission
  state; the exported launcher; DUMP-protected debug activities and AndroidX receiver; the
  process-local debug mailbox; obscured-touch rejection; hardware input; backup/data-extraction
  exclusions; and external browser/settings navigation;
- current-package versus owner-null MediaStore restore provenance, system delete consent,
  exact-file versus capture-family authorization, pending/discard/family journals, late-output
  handling, partial deletion recovery, and bounded provider-IPC workers;
- JPEG/HEIF/DNG/video structural probes, review bitmap/thumbnail/playback bounds, EXIF handling,
  settings corruption, private preferences/SQLite state, logs, secrets, dynamic-code/WebView
  absence, and the no-network/no-location privacy boundary;
- Camera2 route and accepted-session generations, callback admission, session fallback, ZSL and
  capture correlation, still watchdogs, processed/RAW continuation ownership, lifecycle pause and
  release, camera recovery, and stale-callback rejection;
- GL/EGL preview and encoder ownership, analysis-generation retirement, MediaCodec/MediaMuxer and
  AudioRecord terminality, recording allocation/finalization, native quarantine, timers/tickers,
  and finite queue failure behavior;
- immutable debug/release source and artifact boundaries, dependency verification, signing-input
  handling, subprocess construction, ADB/device-evidence guards, and every cycle-41 change. The
  cycle-41 production delta is confined to removing the retired moving-zoom throttle model, adding
  click-only MR/Custom-WB controls, and comment/token alignment; none widens an external trust
  boundary.

## Validation evidence

- Git reports 504 regular tracked files only (`497` mode `100644`, `7` mode `100755`), with no
  symlink, submodule, private key, deployable credential, or plaintext production secret found.
- The generated debug manifest declares CAMERA, RECORD_AUDIO, the three scoped visual-media
  permissions, and AndroidX's app-signature dynamic-receiver permission. It has no INTERNET,
  network-state, location, legacy external-storage, or all-files permission. `allowBackup=false`
  remains set; preferences and databases are also excluded from both backup-rule formats. All
  debug-only exported activities and the exported AndroidX receiver are protected by
  `android.permission.DUMP`; the ordinary exported launcher still treats extras as inert.
- Focused current-HEAD tests passed for obscured-touch policy, camera/microphone/visual-media
  permission policy, ownerless-delete activity result and lifecycle/operation ownership, external
  navigation, all storage tests, zoom submission/glide behavior, and the cycle-41 selector/action
  semantics. Gradle completed successfully.
- `python3 tools/check_docs.py` passed 126 applicable checks with zero failures (24 optional-private
  checks skipped). This includes permission/privacy parity, no-network claims, backup authority,
  module inventory, release evidence, field-check membership, and current zoom/UI authority.
- All 106 tooling unit tests passed under `python3 -W error`, and Python compilation of `tools/`
  and `device-tests/` passed. `git diff --check` was clean.
- The attempted combined release/debug manifest task correctly refused the release half because
  other cycle-42 specialists were concurrently writing their assigned `.context/reviews/*.md`
  files in this shared clone. That is the release clean-tree gate behaving as designed, not a
  repository failure; release exposure was therefore validated from the reviewed manifest/build
  source while the generated debug manifest supplied the dependency-merge check.

## Findings

No actionable security, privacy, authentication/authorization, component-exposure, injection,
unsafe-deserialization, path-traversal, secret-management, MediaStore-authority, data-loss, crash,
deadlock, race, resource-leak, stale-callback, lifecycle, native-terminal, or release-evidence
defect survives current-HEAD validation.

One candidate was explicitly falsified rather than reported: ownerless still review computes a
power-of-two `BitmapFactory.inSampleSize`, but with a 3000-pixel target and `Int`-bounded decoded
dimensions the required sample is reached far below integer overflow. The platform rule that values
at or below one decode unsampled therefore cannot be reached through overflow on this path.

The five unresolved physical checks in `docs/FIELD_CHECKS.md` remain evidence obligations, not
host-confirmed defects: A3 requires a lit rear-camera scene; A4 a rotatable large-screen front
route; D1 an off-axis acoustic A/B; and E1/E2 real MediaProvider owner-null and system-consent
behavior. C3 is explicitly closed as no observable profile difference, while B1 and C1 close the
rotation path; none was reopened.

## Final missed-issue sweep and count

The final sweep revisited every exported component and runtime permission, incoming/outgoing
intent, overlay and hardware ingress, owner-null admission/consent route, family and DISCARD marker
transition, provider mutation/recovery outcome, parser/allocation bound, private state store,
camera/GL/codec/audio terminal, finite worker owner, Activity/ViewModel lifecycle edge,
logging/network/secret surface, subprocess/signing input, immutable artifact boundary, and each
cycle-41 production change. No review-relevant current path was skipped, and resolved or explicitly
accepted history was not re-filed.

**New security/debugger finding count: 0.**

<!-- Archived cycle-41 provenance follows; it is resolved history, not a cycle-42 finding.

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
-->
