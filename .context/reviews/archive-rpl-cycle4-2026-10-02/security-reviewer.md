# Security review: TeleCam Pro, RPL cycle 4 (security-reviewer, 2026-10-02, HEAD 14767b0a)

Scope: OWASP Mobile Top 10 over the main and debug manifests, backup and data-extraction rules,
`app/src/main/**` (intents, receivers, permissions, MediaStore URI handling, logging, debug gates,
SettingsStore), the Gradle signing and release-gate wiring in `app/build.gradle.kts`,
`settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`,
`gradle/libs.versions.toml`, and all release/host tooling under `tools/` (`build_immutable_release.py`,
`run_scoped_signed_release.py`, `upload_key_policy.py`, `check_release_artifact.py`,
`build_immutable_debug.py`, `check_docs.py` password rule, `adb_proxy.py`, and the matching tests).
Read-only pass: no source edits, no Gradle run, no secret values printed (the redacted-password
comparison in SEC4-3 was done with digits masked and is not reproduced here).

Baseline: the cycle-3 review (`archive-rpl-cycle3-2026-10-02/security-reviewer.md`, SEC3-1..6),
`_aggregate.md` (AGG3-31..36), and `docs/plans/2026-10-02-rpl-cycle3.md` lane C. I re-checked every
cycle-3 commit touching `tools/` and `app/build.gradle.kts` in `e3a2bdd4..HEAD` (`638b666a`,
`b67e9ab5`, `7b8fbd0a`, `4e603375`, `086f4f76`, `5398acd8`, `1607bfeb`) for bypasses, plus the app
diff in that range (26 files; `MediaStoreWriter`, `SettingsStore`, `MainActivity` read in full diff).

Not re-reported (known / owner decisions / scheduled): AGG-67 and AGG2-36 (key custody and the
password in history; `telecampro-upload.jks` still mode 0644), AGG-69, SEC2-3 (daemon environment),
SEC2-5 (URIs in warnings; `reassertPending` adds one more change-gated URI warning), SEC3-3/AGG3-33
(self-attested signer), SEC3-4/AGG3-34 (password captured for configuration cache).

## Summary

| ID | Severity | Confidence | Status | Title |
|---|---|---|---|---|
| SEC4-1 | Low-Medium | Medium | Likely | The Gradle upload-key gate is a task-NAME allowlist; AGP's `makeApkFromBundleForRelease` also signs, is not gated, and the gate inputs omit the alias, so an alias switch signs an APK set with a blocked key |
| SEC4-2 | Medium | High | Confirmed | The generated-secret floor (AGG3-32) is still Python-only: plain `./gradlew bundleRelease` signs a Play-uploadable AAB with a weak rotated key, and the wrapper checks the live file before copying it (TOCTOU) |
| SEC4-3 | Low | High | Confirmed | A tracked test fixture restates the redacted real property of the blocked key's password, next to the real "SECURITY-BLOCKED" anchor; the docs gate scans only `.md` so it cannot see it |
| SEC4-4 | Low | Medium | Confirmed (channels exist) | The "sealed" release build still inherits ambient Gradle control channels (full environment, `$GRADLE_USER_HOME/init.d`, user `gradle.properties`, shared build cache); SEC3-1 closed only the argv form |
| SEC4-5 | Low | High | Confirmed | `meets_generated_secret_floor` accepts trivially low-entropy values (`"a"*18+"A1"`, `"Aa1"*7`, `Password1234Password`) |

Total: 5 (0 High, 1 Medium, 1 Low-Medium, 3 Low).

Cycle-3 fixes that hold (checked for bypasses):
- SEC3-1 / AGG3-31 (`638b666a`): `validate_gradle_tasks` (`tools/build_immutable_release.py:697-709`)
  rejects every element starting with `-` or outside `(?::?[A-Za-z][A-Za-z0-9_-]*)+`, and runs both in
  `main()` (`:947-951`) and in `build_immutable_release()` (`:846`), so `-- -P…` and the scoped
  helper's forwarded argv are covered. Argparse already refuses unknown options. The Gradle side
  (`app/build.gradle.kts:785-806`) refuses all four named signing tasks plus the two lifecycle tasks
  whenever any `android.injected.signing.*` Gradle property is present, and the names are a task
  input, so an UP-TO-DATE output cannot satisfy an injected run. AGP reads those options through
  the same Gradle-property providers, so `-P`, `gradle.properties`, `ORG_GRADLE_PROJECT_*`, and
  `-Dorg.gradle.project.*` sources are all covered. See SEC4-1 for the one signing task the name
  set misses.
- SEC3-5 / AGG3-35 (`7b8fbd0a`): `load_upload_key_prerequisite` now resolves the alias through
  `_gradle_signing_value` (`:628-634`), properties-first, last-duplicate semantics refused as
  ambiguous. Consistent with Gradle `signingValue` (`app/build.gradle.kts:369-373`).
- SEC3-6 / AGG3-36 (`4e603375`): the no-signing refusal now uses the shared `releaseSigningTasks`
  set plus lifecycle tasks (`app/build.gradle.kts:813-824`), so `packageRelease` refuses before
  writing an APK.
- SEC3-2 / AGG3-32 wrapper half (`b67e9ab5`, `086f4f76`): `require_approved_upload_key`
  (`:734-776`) applies the floor to the effective store and key passwords (file over env,
  `keyPassword ?: storePassword`) before keytool, with value-free messages. The Gradle half is
  still missing (SEC4-2).
- DOC3-1 (`5398acd8`): the docs gate now scans `git ls-files` markdown in a work tree; an
  untracked note can no longer turn the gate red, and a staged note is still in the index and is
  still scanned. The narrowing to `.md` is the gap SEC4-3 exploits by accident.

---

### SEC4-1: The Gradle gate misses `makeApkFromBundleForRelease`, and its inputs omit the alias

- **Severity / Confidence / Status:** Low-Medium / Medium / Likely. AGP registration is confirmed
  from bytecode. The end-to-end alias-switch run needs one Gradle invocation, which this lane does
  not perform.
- **Where:**
  - `app/build.gradle.kts:787-792`: `releaseSigningTasks = {packageRelease, packageReleaseBundle,
    packageReleaseUniversalApk, signReleaseBundle}`; `:852-859`: the gate attaches only to that
    name set.
  - `app/build.gradle.kts:858`: gate input =
    `approval|approvedCertificate|denyList|injectedNames` plus the keystore file. It contains no
    `keyAlias`.
  - AGP 9.4.1 (`gradle-9.4.1.jar`):
    `ApplicationTaskManager.createDynamicBundleTask` registers `BundleToApkTask$CreationAction`
    ("makeApkFromBundleFor" + variant) unconditionally, right after `BundleIdeModelProducerTask`
    and next to `BundleToStandaloneApkTask` (the gated `packageReleaseUniversalApk`).
    `BundleToApkTask` is `@CacheableTask`, holds `signingConfigData`, and calls bundletool
    `BuildApksCommand.Builder.setSigningConfiguration`. Its input is `INTERMEDIARY_BUNDLE`, which
    the unsigned `packageReleaseBundle` produces.
- **Why:** the gate only runs when one of four named tasks actually EXECUTES. `makeApkFromBundleForRelease`
  signs with the variant's signing config but is not on that list. Its only gated ancestor is
  `packageReleaseBundle`, whose inputs are unsigned bundle content plus the gate string. Changing
  `keyAlias` changes neither, so that task stays UP-TO-DATE and its `doFirst` never runs. AGP's own
  `signingConfigData` input on `makeApkFromBundleForRelease` DOES include the alias, so that task
  re-executes and signs with the new alias.
- **Failure scenario:** the owner rotates by adding a new approved alias to the existing JKS, a
  common `keytool -genkeypair` practice. One approved build runs. Later `keyAlias` is pointed back
  at the old (blocked) alias, either by mistake or by a stale `keystore.properties` from a backup,
  and someone runs `./gradlew :app:makeApkFromBundleForRelease` (or Studio's "APKs from bundle"
  deploy path, which uses it). The result is
  `app/build/intermediates/apks_from_bundle/release/.../bundle.apks`, signed by the blocked upload
  key, with no refusal. This is a sideloadable artifact, not a Play AAB: `signReleaseBundle` would
  re-run and refuse, and the immutable wrapper does not export intermediates. Any task AGP adds
  later that consumes `signingConfigData` is uncovered in the same way.
- **Fix:**
  1. Turn the check into a dedicated task, for example `verifyReleaseUploadKey`, with
     `outputs.upToDateWhen { false }` and no cacheable outputs. Make every signing consumer
     `dependsOn` it, so the gate runs on every invocation rather than riding a doFirst that
     UP-TO-DATE skips.
  2. Select consumers by TYPE, not name: `tasks.withType<PackageAndroidArtifact>()`, the
     `FinalizeBundleTask`, `BundleToApkTask`, and `BundleToStandaloneApkTask` types, or any task
     exposing a `signingConfigData` property. At minimum add `makeApkFromBundleForRelease` to
     `releaseSigningTasks`, which also puts it under the injected-signing refusal.
  3. Add the resolved alias and store path to the gate input string. Both are non-secret values.
  4. Pin the coverage in a host test that lists `:app:tasks --all` (or reads the task graph through
     TestKit) and asserts that every release task of a signing type is gated.

### SEC4-2: The secret floor is still not enforced by plain Gradle; the wrapper check is TOCTOU

- **Severity / Confidence / Status:** Medium / High / Confirmed (static).
- **Where:**
  - `app/build.gradle.kts:860-899`: the release doFirst checks the deny-list, the approval, the
    approved fingerprint, and the in-process `KeyStore` certificate. It applies no length, class,
    or structure rule to `signingStorePassword` or to the key password.
  - `tools/upload_key_policy.py:6-10` says the floor "is therefore applied by both wrappers". That
    is accurate, but plain Gradle is the path SEC2-2 set out to close.
  - `tools/build_immutable_release.py:744-776` (`require_approved_upload_key`) reads
    `root/keystore.properties` from the LIVE work tree. `copy_local_build_inputs` (`:779-798`)
    re-reads it later, inside `build_immutable_release`, to build the sealed copy that Gradle signs
    from. Nothing proves the two reads saw the same bytes, and the in-snapshot Gradle gate does not
    re-check the floor.
- **Why:** cycle-3 plan item C.3 fixed only the wrapper. The AGG3-32 failure mode is unchanged for
  `./gradlew bundleRelease` (which `CLAUDE.md` lists as the "normal implementation gate" family)
  and for Android Studio's Build menu: generate a new JKS with a short or one-class password, set
  `uploadKeyRotationApproved=true` and the new fingerprint, and Gradle signs a Play-uploadable AAB
  under a key with the same offline-guessing exposure that triggered AGG-67. Separately, in the
  wrapper, a `keystore.properties` swap between the gate read and the snapshot copy (a concurrent
  editor or sync tool) lets the floor approve one file while Gradle signs with another that has a
  weak password. The approval and fingerprint are re-checked in Gradle, but the floor is not.
- **Fix:**
  - Port the floor into the Gradle doFirst (or the SEC4-1 gate task) for both `storePassword` and
    the effective `keyPassword`, with value-free messages. Keep one rule: have `check_docs.py`
    assert that the Kotlin constants equal `MIN_STRONG_PASSWORD_LENGTH`/`_CLASSES`/
    `MAX_MONOTONIC_RUN`, the same way it already pins the Python ones (`check_docs.py:1490-1495`).
  - In the wrapper, run the gate against the bytes that were actually copied. Either parse the
    `frozen_properties` payload returned by `copy_local_build_inputs`, or re-verify a SHA-256 of
    `keystore.properties` and the JKS taken at gate time against the sealed copy before running
    `./gradlew`.
  - Test: approval plus a non-blocked fingerprint plus a weak password must be refused by the Gradle
    gate (TestKit fixture or a Kotlin-script unit seam).

### SEC4-3: A tracked test fixture republishes the redacted password property

- **Severity / Confidence / Status:** Low / High / Confirmed.
- **Where:** `tools/tests/test_tool_contracts.py:1694-1696` and `:1714` (added in `c10acf91`,
  extended in `5398acd8`). `tools/check_docs.py:1559` (`tracked_markdown` keeps `.md` only) and
  `:1562-1574` (`password_property_scan_paths`).
- **Why:** SEC2-1/AGG2-36 removed the blocked upload key's password length and character class
  from the tracked tree, because those properties shrink an offline guess. The fixtures that prove
  the docs gate catches such phrasing use the real property, not a synthetic one. I compared them
  against the lines `c10acf91` removed from `docs/play-console-submit.md`, with digits masked
  locally, and they match. They are also spliced onto the real anchor sentence `upload key is
  **SECURITY-BLOCKED**`, so the HEAD tree once again states the fact the redaction removed, in a
  file type the gate never scans. `git grep` at HEAD finds it twice. The value is still in history
  (an owner decision, not re-litigated). The regression is that the current tree, which is what
  clones, archives, and reviewers read, republishes it, and the gate cannot notice because it scans
  only markdown.
- **Failure scenario:** someone who reads only the current tree (a public mirror, a code-search
  index, a shared review bundle) learns the key space of the blocked upload key. If the 0644 JKS
  ever leaves the machine (AGG-67), offline brute force is immediate.
- **Fix:**
  - Replace the fixture properties with clearly synthetic ones that match the rule's patterns but
    not the real facts (for example a length in the hundreds, an implausible class, and a fictional
    channel), and do not reuse the production anchor sentence verbatim.
  - Extend the scan to tracked text sources outside markdown (`*.py`, `*.kts`, `*.txt`,
    `*.example`, `*.toml`), with an explicit fixture allowlist that requires a synthetic marker, so
    the next fixture cannot repeat this.

### SEC4-4: The sealed release build still inherits ambient Gradle control channels

- **Severity / Confidence / Status:** Low / Medium / Confirmed that the channels exist. None is
  populated on this machine today: no `~/.gradle/init.d`, no user `gradle.properties`, and no
  `ORG_GRADLE_PROJECT_*`/`GRADLE_OPTS`/`JAVA_TOOL_OPTIONS` in the environment.
- **Where:** `tools/build_immutable_release.py:119-125` (`run_checked` forwards `dict(os.environ)`,
  minus only `TELECAMPRO_STORE_FILE`) and `:868` (`["./gradlew", *tasks]`). `gradle.properties`
  sets `org.gradle.caching=true` and `org.gradle.configuration-cache=true`.
  `write_release_evidence` (`:296-322`) stamps `source_authority=sealed-wrapper-export-v1`.
- **Why:** SEC3-1 noted that an `--init-script` outside the snapshot runs unsealed build logic
  inside the "sealed" build, and the fix closed only the argv form. Gradle still auto-applies
  `$GRADLE_USER_HOME/init.d/*.gradle(.kts)` and reads `$GRADLE_USER_HOME/gradle.properties`,
  `ORG_GRADLE_PROJECT_*`, and `GRADLE_OPTS=-Dorg.gradle.project.*`. `GRADLE_USER_HOME` can itself be
  redirected through the inherited environment. With `org.gradle.caching=true`, cacheable release
  tasks (compile, dex, R8, and `makeApkFromBundle…`) can be served FROM-CACHE from the shared local
  cache that every unsealed developer build writes. A FROM-CACHE result skips the task action, so
  the bytes inside the "sealed" outputs did not come from the sealed inputs. The seal and the
  output freeze check the snapshot files, not these channels, so the evidence claim is stronger
  than what the wrapper controls. Exploiting this needs write access as the same user, so this is
  integrity of the evidence claim rather than a remote vector.
- **Fix:** in `run_checked` for the release wrapper:
  - Build the child environment from an allowlist: `PATH`, `HOME`, `JAVA_HOME`, `ANDROID_*`,
    `LANG`/`LC_*`, and the `TELECAMPRO_*` password and alias values.
  - Refuse when the effective `$GRADLE_USER_HOME/init.d` is non-empty, or when the user
    `gradle.properties` defines keys outside a small allowlist (JVM args and similar).
  - Prepend wrapper-owned fixed flags, which `validate_gradle_tasks` does not need to accept from
    the caller: `--no-build-cache --no-configuration-cache` (this also retires SEC3-4 for signing
    runs) and `--no-daemon` (SEC2-3).
  - Record the flags in `release-evidence.json`.

### SEC4-5: The generated-secret floor accepts trivially low-entropy values

- **Severity / Confidence / Status:** Low / High / Confirmed (I called
  `upload_key_policy.meets_generated_secret_floor` with synthetic values only).
- **Where:** `tools/upload_key_policy.py:89-108`. The only structure checks are
  `len(set(value)) > 1` and `_has_monotonic_run` (alphabetic or numeric walks longer than 5).
- **Why:** each of these synthetic values returns `True`: 18×`a` + `A1` (20 characters, 3 distinct
  characters), `Aa1` repeated seven times, and `Password1234Password`. The docstring calls this a
  "generated-looking secret" floor, but repetition and dictionary words pass, so the rule a weak
  rotation must clear (SEC4-2) adds little against an offline guess.
- **Fix:**
  - Require at least about 12 distinct characters.
  - Reject any character repeated more than 3 times in a row, and reject values with a repeating
    period of at most `len/2`.
  - Better: make the documented rotation generate the secret (`secrets.token_urlsafe(32)` or
    keytool's own prompt fed from `openssl rand`) and raise the floor to 32 characters, which such
    output always meets.
  - Keep the messages value-free, and add the three synthetic values above as negative test cases.

---

## Final sweep (commonly missed items)

- **Exported components:** release has only the launcher `MainActivity`, and `onNewIntent`
  deliberately ignores extras (`MainActivity.kt:327-332`). The debug-only exported activities
  require `android.permission.DUMP`, and the Compose test host is forced `exported=false`. There
  are no providers, services, manifest receivers, or FileProvider. The two dynamic debug receivers
  (`CameraViewModel.kt:1023-1050`) are `BuildConfig.DEBUG`-gated and `RECEIVER_NOT_EXPORTED`.
- **Network and privacy:** `INTERNET` and `ACCESS_NETWORK_STATE` are `tools:node="remove"`. There is
  no location permission or code. External launches are constant targets only
  (`ui/ExternalNavigation.kt`).
- **Backup:** `allowBackup=false`, and both cloud backup and device transfer exclude `database` and
  `sharedpref`.
- **SettingsStore:** the new `recordAudioOffByDenial` key is read through `contains` + `getBoolean`
  inside `runCatching`, and absent maps to `null`. There is no deserialization surface.
- **MediaStore:** the new `reassertPending` (`MediaStoreWriter.kt:1142-1156`) updates only a row that
  recovery already selected as our own pending row, and skips DISCARD rows. The new HEIF
  `idat`/lenient-iloc probe parses bounded metadata of our own rows only. Overflow is recorded as
  `unrepresentable` (INVALID), and every extent check is subtraction-based, so it cannot overflow.
- **Logging:** new warnings go through `DiagnosticLog` and the change-gated `warnStorageOnce`.
  Tool error messages remain value-free (the injected-signing refusal echoes property NAMES only).
- **Supply chain:** the Gradle wrapper has a pinned `distributionSha256Sum` with
  `validateDistributionUrl=true`. `gradle/verification-metadata.xml` is tracked. Repositories are
  only `google()` (content-filtered for plugins), `mavenCentral()`, and `gradlePluginPortal()`
  (plugins only), with `FAIL_ON_PROJECT_REPOS`. No vendor maven repository remains.
- **Subprocess and tooling:** there is no `shell=True`, `eval`, or `pickle`. keytool gets the store
  password only through `-storepass:env`. `adb_proxy.py` binds `127.0.0.1` only, and wireless ADB
  still requires the paired key. The checker's temporary truststore password is a public constant
  for a public certificate.
- **Secrets in tree:** no tracked `.jks`, `.keystore`, `keystore.properties`, `local.properties`,
  `.gpg`, `.p12`, or `.pem` files. `keystore.properties` and `telecampro-upload-passwords.txt.gpg`
  are 0600 and gitignored, while the JKS is 0644 (AGG-67, known). See SEC4-3 for the one tracked
  restatement of a password property.
- **Docs drift noted in passing (not security):** `CLAUDE.md`'s toolchain table says AGP 9.3.2 and
  Gradle 9.7.1, but the catalog and wrapper are AGP 9.4.1 and Gradle 9.8.0.

## Files examined

- Manifests and resources: `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`,
  `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`.
- Build: `app/build.gradle.kts` (`:20-215`, `:355-500`, `:520-640`, `:772-902`), `build.gradle.kts`,
  `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`,
  `gradle/wrapper/gradle-wrapper.properties`, and the AGP 9.4.1 jar (`ApplicationTaskManager`,
  `BundleToApkTask`, `BundleToStandaloneApkTask`, `FinalizeBundleTask`, `PackageApplication`
  annotations and registration).
- Tools: `build_immutable_release.py` (full), `run_scoped_signed_release.py` (full),
  `upload_key_policy.py` (full), `check_release_artifact.py` (`:285-400`, `:690-760`),
  `build_immutable_debug.py` (`:340-433`), `check_docs.py` (`:44-60`, `:1440-1590`), `adb_proxy.py`,
  `tools/tests/test_tool_contracts.py` (`:1680-1730`), and the cycle-3 diffs of
  `test_upload_key_gate.py`, `test_upload_key_policy.py`, and `test_immutable_release.py` (via
  commit stats).
- App: diffs `e3a2bdd4..HEAD` of `MediaStoreWriter.kt`, `SettingsStore.kt`, and `MainActivity.kt`;
  `CameraViewModel.kt:1015-1060`; a risky-API grep sweep of `app/src/main/kotlin` (exec/WebView/
  PendingIntent/receivers/external storage/temp files/direct `android.util.Log`).
- History: `c10acf91` (redaction; removed lines compared with digits masked), and the commits
  listed in the baseline.
