# Cycle 48 security-reviewer + debugger review

Date: 2026-08-25  
Reviewed revision: `ad64188a` (`cycle48`, `origin/main`)  
Workspace: isolated clone `/tmp/find-x9-ultra-cycle48.Gvbytf`

## Authority, inventory, and method

I read `CLAUDE.md` and `docs/ARCHITECTURE.md` completely before reviewing implementation. I also
read the committed `docs/FIELD_CHECKS.md` evidence ledger and the cycle-46/cycle-47 implementation
plans so that measured device behavior, accepted limitations, resolved findings, and current release
claims were not mistaken for new defects. Optional private maintainer documents are absent, as the
committed authority permits.

The revision contains 528 tracked paths, all regular Git files (521 mode `100644`, seven mode
`100755`; no symlink, submodule, FIFO, or device entry): 102 production Kotlin files, one production
Java file, three debug Kotlin files, four instrumented-test Kotlin files, 237 JVM/Robolectric/Compose
test Kotlin files, 33 Python files, two shell scripts, 87 Markdown files, and 59 build/resource/asset/
license/configuration inputs. I used `git ls-files` rather than an ignore-aware working-tree listing,
so the tracked `.context/reviews`, `docs/plans`, `docs/FIELD_CHECKS.md`, and Play/privacy authorities
were included. Binary fonts, PNGs, and the Gradle wrapper participated in mode, identity, manifest,
digest, and artifact-boundary checks; they were not treated as executable source text.

Every tracked path participated in the final Git-mode, credential/private-key, permission,
component, backup, network/location, dynamic-code/deserialization, command/process, URI/path,
logging, exception/suppression, thread/executor, and dangerous-API searches. The direct code review
then traced these cross-file surfaces:

- release/debug merged components and runtime permissions; launcher/debug intent ingress; DUMP
  protection; obscured pointer streams; hardware keys; camera/microphone/media permission owners;
  external navigation; backup/extraction rules; and private preferences/SQLite state;
- current-package versus owner-null MediaStore provenance; exact-file consent; family and DISCARD
  authorization; pending-row structural recovery; parser/decode bounds; review playback/bitmap
  ownership; and late save/delete/recovery continuations;
- Camera2 route/session generations, ZSL correlation, watchdogs, GL/EGL output ownership,
  MediaCodec/MediaMuxer/AudioRecord terminal paths, recording allocation/storage, lifecycle teardown,
  finite queues, timers, stale callback gates, and the complete cycle-47 transfer/microphone/input
  delta;
- dependency verification, signing inputs, immutable debug/release exports, private artifact seals,
  packaged permission verification, subprocess construction, ADB reconnect/attestation behavior,
  screenshot evidence, and documentation/privacy gates.

Authentication accounts do not exist in this offline camera app. Authorization is therefore the
Android component/permission boundary plus exact MediaStore ownership and system-consent routing;
those were reviewed as the applicable OWASP access-control surface. The merged release manifest has
no INTERNET, network-state, location, legacy external-storage, all-files, overlay, package-install,
or query-all-packages permission. Backup remains disabled and both backup rule formats exclude all
preferences and databases. No deployable secret, private key, plaintext credential, dynamic code
loading, WebView/JavaScript bridge, unsafe object deserialization, or shell-evaluated user input was
found.

## Findings

### SECDBG48-01 — the upload checker rejects the dependency-generated signature permission in every real release bundle

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed, deterministic release-verification failure (not a device-only or
  manual-validation risk).
- **Evidence:** `tools/release_permissions.py:8-16,19-25` defines the exact packaged set as only the
  five user-facing `android.permission.*` entries and extracts *every* `<uses-permission>` name;
  `tools/check_release_artifact.py:721-751` compares the bundletool dump to that set with exact
  equality. The release includes AndroidX Core/Profile Installer through
  `app/build.gradle.kts:710-719`. Processing the real release manifest at this revision produces
  `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml:58-62`:
  a signature-protected declaration and use of
  `me.hletrd.telecampro.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Directly running the production
  parser over that merged manifest produced `match=False` and exactly that one unexpected permission.
- **Concrete failure scenario:** A maintainer builds and attests an otherwise valid signed AAB, then
  follows the committed Play-upload flow through `check_release_artifact.py`. Bundletool reports the
  six effective permissions actually packaged. The checker treats AndroidX's app-private,
  signature-level receiver guard as an undisclosed extra and returns
  `packaged permission set does not match privacy authority`, so no real current AAB can pass the
  mandatory upload check. The synthetic test manifest hides the defect by defaulting its permissions
  to the same five-entry constant instead of exercising the real merged dependency manifest.
- **Suggested fix:** Separate the five privacy/runtime permissions from package-internal security
  permissions. For the packaged check, require those five plus the exact
  `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, and independently prove that the
  corresponding `<permission>` declaration has `protectionLevel="signature"`; do not generally
  ignore same-package permissions. Keep source/privacy prose checks bound only to the five user-facing
  permissions. Add an integration fixture from the real merged release manifest (or a synthetic dump
  containing both the declaration and use) so dependency-generated permissions cannot be omitted from
  the green-path test again.

### SECDBG48-02 — a full-obscuration edge causes the synthetic gesture cancel to be filtered out

- **Severity / confidence:** Low / High.
- **Classification:** Confirmed correctness/availability regression. It does not admit the hostile
  event or trigger a sensitive action; it fails to terminate state already admitted before the
  overlay appeared.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:247-271` clones the obscured
  event, changes only its action to `ACTION_CANCEL`, and calls `super.dispatchTouchEvent(cancel)`;
  the clone therefore retains `FLAG_WINDOW_IS_OBSCURED`. The same Activity sets
  `window.decorView.filterTouchesWhenObscured = true` at `MainActivity.kt:351-354`. Android 16's
  framework implementation (`View.onFilterTouchEventForSecurity`, reached at the start of
  `ViewGroup.dispatchTouchEvent`) returns false whenever that view flag and
  `MotionEvent.FLAG_WINDOW_IS_OBSCURED` are both present, before dispatch to its existing touch
  target. Thus the DecorView drops this cancel. Partial obscuration does not exercise that independent
  platform filter, which is why the currently exercised partial-obscuration transition succeeds.
- **Concrete failure scenario:** A slider, pinch, or long-running Compose gesture consumes a clean
  DOWN. A fully obscuring overlay appears and the next MOVE carries
  `FLAG_WINDOW_IS_OBSCURED`. MainActivity rejects the hostile MOVE, but its same-flag synthetic
  CANCEL is rejected again by DecorView. `touchStreamTainted` then drops the real UP. The child
  gesture remains suspended/active until a later clean DOWN or teardown makes the framework clear
  the old target, potentially leaving drag/zoom interaction state and its associated UI policy live
  after the overlay edge that was supposed to retire it immediately.
- **Suggested fix:** Reconstruct the termination event with the original timing, source, device, and
  complete pointer properties/coordinates but with the two obscuration bits cleared and action set to
  `ACTION_CANCEL`; a cancel cannot activate a control, and clearing only those bits lets it pass the
  defense-in-depth DecorView filter. Retain rejection of the original obscured event and tainted
  remainder. Cover both `FLAG_WINDOW_IS_OBSCURED` and
  `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` as mid-stream transitions in the real Activity test, asserting
  exactly one child CANCEL, no action, remainder rejection, and a normal next clean gesture.

## Validation evidence and limitations

- `:app:processReleaseMainManifest :app:processDebugMainManifest` passed and supplied the real merged
  component/permission evidence above. Focused production tests for obscured input, mode/transfer and
  standby ownership, ownerless delete lifecycle, storage, camera teardown, and recorder quarantine,
  plus `:app:lintDebug`, passed (`BUILD SUCCESSFUL`).
- The 104 runnable release/artifact/documentation/immutable-output tooling tests passed, as did all
  nine coverage-tool tests and all 195 device-harness self-tests. `tools/check_docs.py` passed 151
  checks with zero failures (24 optional-private checks skipped); Python compilation and
  `git diff --check` were clean.
- The complete 120-test tooling discovery ran 113 tests successfully but reported seven environment
  errors because this host's Android SDK lacks the newly required stable Emulator
  `glslangValidator`. `tools/verify_host.py` refused at the same explicit preflight. This is missing
  host tooling, not evidence of an application failure; the focused suites not requiring that binary
  were run separately. No device, deployment, production signing, or external service action was
  performed.
- The six open physical checks in `docs/FIELD_CHECKS.md` remain manual evidence obligations, not
  host-confirmed bugs: A3, A4, A5, D1, E1, and E2. Nothing in this review closes or reclassifies them.

## Final missed-issue sweep

The final sweep revisited every exported component and permission, incoming/outgoing intent,
obscured and hardware input path, camera/microphone/media permission owner, owner-null consent route,
family/DISCARD transition, provider mutation and structural probe outcome, parser/allocation bound,
private state store and backup rule, camera/GL/codec/audio terminal, finite worker owner,
Activity/ViewModel lifecycle edge, network/location/log/secret surface, signing and dependency input,
subprocess boundary, immutable source/artifact seal, ADB evidence path, and each cycle-47 production
change. Prior resolved findings and explicit field-only evidence were not re-filed.

**New security/debugger finding count: 2 — one Medium, one Low; both High confidence and confirmed.**
