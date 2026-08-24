# Security reviewer + verifier review — cycle 33

Date: 2026-08-24
Reviewed revision: `984424cb36ebe409e67bb2e69c8605ea6f41c4bb`
Workspace: clean isolated worktree `/private/tmp/find-x9-cycle33-latest.Vc7rke`

## Scope and inventory

I read the repository authority first: `CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`, then the public README/privacy authorities and the prior aggregate/security
review so fixed findings would not be re-filed. The current inventory contains 447 tracked paths:
115 production Android paths (98 production Kotlin files), 209 host/instrumented Kotlin tests, and
36 tool/device-harness paths, with documentation/resources completing the census.

The security and verification pass covered the entire tracked inventory through a file census,
full production compilation, and systematic component/API/data-flow sweeps. I traced the release and
debug manifest merges; exported components; launcher/debug intent inputs; external browser/settings
launches; CAMERA/RECORD_AUDIO/visual-media permission policy; obscured-touch rejection; current-owner
and owner-null MediaStore restore/provenance/review/delete/recovery flows; exact-family and per-URI
delete journals; pending-row parsers; review decoders and native playback; settings and backup rules;
EXIF/location behavior; logs; network/dynamic-code/WebView absence; tracked secrets and file modes;
Gradle dependency verification/signing; immutable build tooling; subprocess construction; device-test
attestation; and the tests/documents asserting those behaviors. Cycle-32 deltas received a separate
review because they added system delete consent, recording-finalization review admission, storage
retirement retry ownership, and new review transformations.

Confirmed positive evidence:

- The merged release manifest has no `INTERNET`, `ACCESS_NETWORK_STATE`, or location permission;
  release exposes only the launcher plus the `DUMP`-protected AndroidX profile receiver, and its
  provider is not exported. Backup is disabled and both extraction-rule formats exclude preferences
  and databases.
- No tracked credential/private key was found. All tracked Git entries are regular files; dependency
  artifacts are checksum-verified, release signing accepts only a normalized repository-relative
  keystore path, and subprocess calls use argument vectors rather than a shell.
- Owner-null media remains narrowly admitted by exact path, filename grammar, collection,
  extension, and MIME, is labeled `LEGACY_FORMAT_UNVERIFIED`, and is restricted to one-file deletion.
  The new `MediaStore.createDeleteRequest` route correctly avoids a redundant direct delete after
  `RESULT_OK`; Android documents that the requested operation is complete before that result is
  delivered.
- Focused verification passed for `CameraViewModelRobolectricTest`, `ExternalNavigationTest`,
  `LatestCaptureReducerTest`, `ObscuredTouchPolicyTest`, and `CameraPermissionPolicyTest`.
  `:app:processReleaseMainManifest` and all 96 available `tools/check_docs.py` checks also passed
  (21 explicitly private-document checks were skipped because those optional files are absent).

## Findings

### SECVER33-01 — a failed delete `IntentSender` is reported as user cancellation

- **Severity / confidence:** Low / High
- **Classification:** Confirmed platform-result classification defect; no authorization bypass or
  data loss.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:302-311` maps every non-`RESULT_OK`
    `StartIntentSenderForResult` callback to `OwnerlessMediaDeleteConsentResult.CANCELED`.
  - `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:361-377` separately maps synchronous
    construction/launcher throws to `LAUNCH_FAILED`, implying launch failure and cancellation are
    intentionally different UI facts.
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3930-3950` renders `CANCELED` as
    `DELETE_CANCELED` but renders `LAUNCH_FAILED` as `DELETE_AUTHORIZATION_UNAVAILABLE`.
  - AndroidX's current `StartIntentSenderForResult` contract catches
    `IntentSender.SendIntentException` itself and returns `RESULT_CANCELED` with action
    `ACTION_INTENT_SENDER_REQUEST` and extra `EXTRA_SEND_INTENT_EXCEPTION`; therefore the outer
    `runCatching { launcher.launch(...) }` cannot classify that failure. No source or test reads
    those result markers. See the official
    [AndroidX contract](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.StartIntentSenderForResult).
  - `docs/FIELD_CHECKS.md:226-230` explicitly distinguishes cancellation from launch failure in the
    E2 pass contract.
- **Concrete failure scenario:** The app freezes an owner-null file and launches the freshly created
  system delete request, but the framework cannot send the `IntentSender` (for example because the
  system owner invalidated it or policy blocked the launch). AndroidX converts the
  `SendIntentException` into a canceled activity result. The app restores the file safely, but tells
  the operator they canceled deletion even though no consent surface successfully launched.
- **Suggested fix:** Classify the callback before reducing its result: when the returned intent has
  `ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST` plus
  `EXTRA_SEND_INTENT_EXCEPTION`, send `LAUNCH_FAILED`; otherwise map non-OK to `CANCELED`. Extract a
  pure result-classifier and test OK, genuine cancel, and converted `SendIntentException` cases.

### SECVER33-02 — the exported debug launcher accepts camera-control extras from any app

- **Severity / confidence:** Low / High
- **Classification:** Confirmed debug-build untrusted-intent surface; release builds are inert.
- **Evidence:**
  - `app/src/main/AndroidManifest.xml:69-105` exports `MainActivity` as the launcher, and the debug
    manifest does not narrow that component's permission.
  - `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:163-181` consumes `zsl_spike` and
    `debug_zoom` extras from both the initial intent and `onNewIntent` whenever `BuildConfig.DEBUG` is
    true. It verifies neither sender identity nor a permission-protected entry component.
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1994-2006` forwards those values
    into live camera state. By contrast, the dynamic debug broadcast receivers at
    `CameraViewModel.kt:884-916` are deliberately `RECEIVER_NOT_EXPORTED`, and the separate debug
    snapshot activity is protected by signature-level `android.permission.DUMP`.
- **Concrete failure scenario:** On a test handset where the debug package has been granted CAMERA,
  another installed app explicitly starts its exported launcher with `FLAG_ACTIVITY_SINGLE_TOP` and
  `debug_zoom` or `zsl_spike`. It can alter the running debug camera's framing or measurement session
  without user input, invalidating device evidence or temporarily disrupting debug capture behavior.
  The Play/release binary is unaffected because both handlers recheck `BuildConfig.DEBUG`.
- **Suggested fix:** Move shell-only extras behind a debug-only, `android.permission.DUMP`-protected
  activity/alias (or an equivalently signature-protected debug component) which then invokes the
  internal hook. Keep the ordinary launcher free of command extras. Add a merged-debug-manifest test
  proving the hook component is protected and a test proving arbitrary launcher extras are inert.

### SECVER33-03 — the field-check summary omits the newly open E2 gate

- **Severity / confidence:** Low / High
- **Classification:** Confirmed verification-status/documentation contradiction.
- **Evidence:**
  - `docs/FIELD_CHECKS.md:9-12` says three checks remain and lists only A3, D1, and E1.
  - `docs/FIELD_CHECKS.md:212-230` defines E2 as explicitly open on the same date and states that
    host/Robolectric coverage cannot close it.
  - `tools/check_docs.py` passes because it checks document/link/privacy contracts but does not
    reconcile the field-check summary against open section headings.
- **Concrete failure scenario:** A release reviewer reads the authoritative top status and concludes
  only three physical checks remain, overlooking the API-33/API-36 system delete-consent validation
  that the same file says is mandatory and host-inprovable.
- **Suggested fix:** Add E2 to the status line and change the count to four. Extend the docs checker to
  derive every open/partial check heading and require the summary to enumerate the same set, so a new
  field gate cannot be added without updating release status.

## Final missed-issue sweep

The final sweep rechecked all 447 tracked paths and current merged manifests after the findings above.
It revisited every dangerous permission and exported component, debug-only boundary, incoming/outgoing
intent, URI and MediaStore mutation, owner-null spoof boundary, parser/native review entry, permission
and overlay gate, settings/backup location, secret/log/network surface, dependency and signing input,
immutable artifact boundary, subprocess invocation, and device-evidence assertion. Prior-cycle fixed
findings, documented product decisions, and device-only facts were not re-filed.

Three actionable findings survive: three Low, all High confidence. No Critical, High, or Medium
security/correctness finding remains in this specialist scope.
