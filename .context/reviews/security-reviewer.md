# Security review: TeleCam Pro, RPL cycle 5 (security-reviewer, 2026-10-02, HEAD ea7d4374)

Scope: OWASP Mobile Top 10 over the main and debug manifests, backup and data-extraction rules, the
cycle-4 app diff (`887d39fb..HEAD`, 40 files under `app/src/main`), the Gradle release gate in
`app/build.gradle.kts`, `gradle.properties`, the wrapper and version catalog, and all release and
host tooling changed in cycle 4 (`build_immutable_release.py`, `run_scoped_signed_release.py`,
`upload_key_policy.py`, `verify_host.py`, `check_docs.py` password rule). This was a read-only pass.
I made no source edits, ran no Gradle, and opened no credential file. The only file check on build
intermediates confirmed that no password KEYS are present in AGP's `signing_config_versions` JSON.
No values were read.

Baseline: cycle-4 review `archive-rpl-cycle4-2026-10-02/security-reviewer.md` (SEC4-1..5 →
AGG4-38..42) and plan `docs/plans/2026-10-02-rpl-cycle4.md` lane C (C.1-C.6, M.5, M.8). The
cycle-4 security commits are `42f450d2`, `d0303120`, `248801fe`, `6d93afa6`, `c6598aae`,
`9596c16a`, and `3521e6fc`. I re-checked each one for bypasses.

Not re-reported (owner decisions or already tracked): AGG-67 / AGG2-36 (key custody, the password in
history), AGG-69, AGG3-33 (self-attested signer), AGG3-34 (configuration-cache capture), SEC2-3,
SEC2-5, AGG4-44 (needs real `keystore.properties`), AGG4-52 (behavioural Gradle refusal run).

## Summary

| ID | Severity | Confidence | Status | Title |
|---|---|---|---|---|
| SR5-1 | Low | High | Confirmed | The sealed-run init-script refusal checks only `$GRADLE_USER_HOME/init.d`. Gradle also auto-applies `$GRADLE_USER_HOME/init.gradle(.kts)` and the distribution's `$GRADLE_HOME/init.d`, and reads `$GRADLE_HOME/gradle.properties` |
| SR5-2 | Low | High | Confirmed | The user `jvmargs` injection filter is a short denylist. `-Xbootclasspath/a:` + `-Djava.system.class.loader=`, `-XX:OnOutOfMemoryError=`, and `@argfile` all pass it and run code inside the sealed Gradle/Kotlin daemons |
| SR5-3 | Low | High | Confirmed | The AGG4-41 environment allowlist covers only the inner Gradle child. The keytool child that receives the store password (`-storepass:env`) and both Python wrapper processes still inherit the full ambient environment (`JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS` agents, `PYTHONPATH` `sitecustomize`) |
| SR5-4 | Low | High | Confirmed | The password-property scan (AGG4-40) adds `.py/.kts/.txt/.toml/.properties.example` but not the published `privacy-policy/index.html`, `_config.yml`, `.kt` (400 files), `.xml` resources, `.sh`, or `.json` |
| SR5-5 | Info | Medium | Likely (dormant on PMA110) | The hi-res passthrough lane keeps the HAL's own EXIF under ours, with no explicit strip of GPS, serial, or maker-note tags. The "captures carry no GPS" claim was verified on HEIF only |

Total: 5 (0 Critical, 0 High, 0 Medium, 4 Low, 1 Info). Every finding is in tooling or a dormant
lane. None changes PMA110 runtime behaviour.

### Cycle-4 fixes that hold (checked for bypasses)

- **AGG4-38 / SEC4-2 (`d0303120`, `9596c16a`).**
  - The Gradle gate computes `meetsGeneratedSecretFloor` for `storePassword` and the effective
    `releaseKeyPassword` (`app/build.gradle.kts:404-498`, `:988-989`). Only the two booleans enter
    the task input (`:1001-1005`) and the doFirst (`:1029-1034`), so no value reaches the
    configuration-cache task graph beyond the pre-existing AGG3-34 capture.
  - The floor runs after the approval and fingerprint checks and before `KeyStore.getInstance`, the
    same order as the Python wrappers.
  - The wrapper's TOCTOU is closed. `require_frozen_secret_floor` (`tools/build_immutable_release.py:918-944`)
    parses the exact bytes `copy_local_build_inputs` wrote, and it runs after
    `seal_release_snapshot`, so the bytes checked are the bytes kept immutable (`:1009-1012`).
  - Python and Kotlin now agree outside ASCII. Both count code points, apply ASCII-only class
    tests, and use the same edge-whitespace set.
- **AGG4-42 / SEC4-5 (`42f450d2`).** The distinct-character (≥12), repeat-run (>3), and
  short-period rules reject all three cycle-4 synthetic values on both sides. `_has_short_period`
  and the Kotlin twin iterate over the same code-point sequence.
- **AGG4-39 / SEC4-1 (`248801fe`).**
  - `makeApkFromBundleForRelease` and `extractApksFromBundleForRelease` are now in
    `releaseSigningTasks` (`app/build.gradle.kts:918-925`). That puts them under both the
    upload-key gate and the injected-signing refusal.
  - The gate input now carries the alias and the store path, so re-pointing `keyAlias` re-executes
    every gated task.
  - The recommended always-run dedicated task was not adopted. The remaining name-list risk is
    covered by the AGP-jar prefix test.
  - I confirmed in the AGP 9.4.1 jar that `SigningConfigWriterTask` and
    `SigningConfigVersionsWriterTask` exist. The release `signing-config-versions.json` in
    `app/build/intermediates` carries no password keys, and no `signing_config/` (password-bearing)
    intermediate is produced for this app.
- **AGG4-41 / SEC4-4 (`6d93afa6`, `3521e6fc`).**
  - The child environment is allowlisted.
  - `--no-build-cache --no-configuration-cache --no-daemon` are prepended by the wrapper.
    `validate_gradle_tasks` still refuses any caller `-` element, so the caller cannot undo them.
  - `require_sealed_gradle_user_home` uses the same `GRADLE_USER_HOME` the child receives.
  - Argv and environment NAMES are recorded in evidence.
  - `parse_java_properties` decodes `\uXXXX` escapes before the key allowlist and the jvmargs
    regex, so an escaped key or agent flag cannot slip past.
  - SR5-1, SR5-2, and SR5-3 are residual channels that this fix did not reach.
- **AGG4-40 / AGG4-43 (`c6598aae`).** The fixtures are synthetic and carry `SYNTHETIC-FIXTURE`.
  The marker exemption is confined to `tools/tests/test_tool_contracts.py`. The listing includes
  non-ignored untracked files. The scan now reaches `.py`/`.kts`/`.txt`/`.toml`; SR5-4 covers the
  suffixes it still misses. All five `PRIVATE_DOCS` paths are gitignored and untracked, so
  skipping them hides nothing that is published today.
- **`signing_capable` fails closed.** Every task except a `lint*` task is treated as signing, so a
  wrapper invocation of `:app:makeApkFromBundleForRelease` still runs the Python approval and floor
  gate.

---

### SR5-1: Init-script and properties channels outside `$GRADLE_USER_HOME/init.d` are not refused

- **Severity / Confidence / Status:** Low / High / Confirmed. The documentation was checked on
  2026-10-02 at docs.gradle.org/current/userguide/init_scripts.html and build_environment.html.
- **Where:**
  - `tools/build_immutable_release.py:201-232` (`require_sealed_gradle_user_home`) inspects only
    `home / "init.d"` and `home / "gradle.properties"`.
  - Its test, `tools/tests/test_immutable_release.py:533-563`, covers only `init.d`.
- **Why:** Gradle's documented init-script discovery has four sources, applied in this order:
  1. the `-I` command-line option, closed by SEC3-1;
  2. **`$GRADLE_USER_HOME/init.gradle(.kts)`**;
  3. `$GRADLE_USER_HOME/init.d/*`;
  4. **`$GRADLE_HOME/init.d/*`**.

  For a wrapper build, `GRADLE_HOME` is the unpacked distribution under
  `$GRADLE_USER_HOME/wrapper/dists/gradle-9.8.0-bin/<hash>/gradle-9.8.0/`. On this machine its
  `init.d` directory exists. `distributionSha256Sum` is verified only on download, not on later
  runs. Gradle also reads `$GRADLE_HOME/gradle.properties`, at the lowest precedence, which still
  supplies any key the project file does not set.
- **Failure scenario:** a same-user process, such as a compromised dev tool or editor plugin,
  drops `~/.gradle/init.gradle.kts` or a script into the distribution's `init.d`. Either script
  can, for example, add a doLast to `bundleRelease` that rewrites the signed AAB, or log signing
  values. The wrapper refuses only `init.d`, so the "sealed" build runs it. The evidence then
  claims `sealed-wrapper-export-v1` for outputs shaped by unsealed logic. The threat model is the
  same as SEC4-4 (same-user write); this finding is about the evidence claim exceeding what the
  wrapper controls.
- **Fix:**
  - Also refuse an existing `home / "init.gradle"` and `home / "init.gradle.kts"`.
  - Resolve the wrapper distribution directory from the snapshot's
    `gradle/wrapper/gradle-wrapper.properties`, using the same `distributionUrl` hash Gradle
    computes. Refuse a non-empty `init.d` there. The stock distribution ships only a `readme.txt`,
    so allowlist exactly that file.
  - Apply the same key allowlist to `<dist>/gradle.properties`.
  - A stronger option: run the sealed build with a fresh `GRADLE_USER_HOME` (a temp dir) plus a
    read-only `--offline` dependency cache. That makes all of these channels moot and also
    re-verifies the distribution checksum on unpack.
  - Host tests: one fixture each for the user `init.gradle.kts`, the distribution `init.d` script,
    and the distribution `gradle.properties` key.
- **PMA110 impact:** none (tooling only).

### SR5-2: The `jvmargs` code-injection filter is a denylist that common JVM options bypass

- **Severity / Confidence / Status:** Low / High / Confirmed. I ran the shipped regex over
  synthetic strings.
- **Where:**
  - `tools/build_immutable_release.py:177`:
    `_JVM_ARGUMENT_INJECTION = -javaagent|-agentpath|-agentlib|-Dorg\.gradle\.project\.|-Dandroid\.`
  - `:217-221`, which applies the filter to user `org.gradle.jvmargs` and `kotlin.daemon.jvmargs`.
    Both keys are allowlisted at `:144`/`:149`.
- **Why:** with `--no-daemon`, Gradle still forks a single-use daemon with `org.gradle.jvmargs`.
  The user-home value overrides the project's `-Xmx2g -Dfile.encoding=UTF-8`. The Kotlin compile
  daemon takes `kotlin.daemon.jvmargs` and produces the compiled bytes. Each of the following
  returned `False` from the regex and runs arbitrary code at JVM start or on demand:
  - `-Xbootclasspath/a:/x/e.jar -Djava.system.class.loader=E` replaces the system class loader;
  - `-XX:OnOutOfMemoryError=/x/e.sh`;
  - `@/x/args.txt`, a JVM argument file that can carry `-javaagent:` and so hides it from the
    regex entirely.

  `-Djava.security.manager=E` and `--patch-module` are similar.
- **Failure scenario:** the same same-user threat as SR5-1, through a property key that the
  wrapper explicitly admits and claims to have screened.
- **Fix:**
  - Invert the filter to an allowlist of token shapes: split on whitespace and accept only
    `-Xmx<n>[kmg]`, `-Xms…`, `-Xss…`, `-XX:MaxMetaspaceSize=…`, `-XX:+UseParallelGC`-style boolean
    GC flags, `-Dfile.encoding=…`, and `-Duser.*`. Refuse everything else, including any `@`
    token.
  - Test the three bypass strings above as refusals.
- **PMA110 impact:** none.

### SR5-3: The keytool child and both Python wrappers still inherit the full ambient environment

- **Severity / Confidence / Status:** Low / High / Confirmed (static).
- **Where:**
  - `tools/build_immutable_release.py:1117`: `require_approved_upload_key(args.root, args.tasks,
    os.environ)`. That function builds `gate_environment` from it and then calls
    `verify_upload_key_certificate(..., gate_environment, run)` (`:881-891`). Lines `:781-795`
    launch keytool with `env=environment` and `-storepass:env`.
  - `tools/run_scoped_signed_release.py:82-98`:
    - `child_environment = dict(base_environment)`, which is the full `os.environ`.
    - The STDIN-only store and key passwords are added to it.
    - That environment is passed to keytool and to the inner `sys.executable
      build_immutable_release.py`.
- **Why:** AGG4-41 allowlisted only the Gradle child, but it is not the only JVM that sees the
  secret:
  - keytool holds the decrypted store password in-process. `JAVA_TOOL_OPTIONS` and
    `JDK_JAVA_OPTIONS` are honoured by every JDK launcher, keytool included, so an agent there
    reads `TELECAMPRO_STORE_PASSWORD` from the environment or the `KeyStore` call.
  - The inner Python wrapper, which is the evidence boundary, starts with the caller's
    `PYTHONPATH`. A `sitecustomize.py` on it runs before `build_immutable_release` and can patch
    `release_child_environment`, `require_sealed_gradle_user_home`, or the output freeze.
  - The scoped helper's stated property is that no secret is written to a file, placed in argv, or
    exported into the caller's shell. That property is weaker than it reads, because the secret
    shares a process environment with arbitrary injected code.
- **Failure scenario:** the scoped helper exists so the password never touches disk (it arrives on
  STDIN). An ambient `JAVA_TOOL_OPTIONS=-javaagent:/x/a.jar` in the operator's shell, from a
  profiler or a malicious dotfile, captures it inside keytool. A file-based attacker could not
  have read it.
- **Fix:**
  - Build the keytool environment from the same allowlist: reuse `release_child_environment`
    plus the store-password variable, and refuse when `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, or
    `_JAVA_OPTIONS` are set rather than silently dropping them.
  - Have the scoped helper launch the inner wrapper as `sys.executable -I -S` (isolated mode
    ignores `PYTHON*` variables and the user site), with an allowlisted environment.
  - Unit tests: assert the `env=` passed to the fake `run` contains no `JAVA_TOOL_OPTIONS`/
    `PYTHONPATH`, and that the inner command carries `-I`.
- **PMA110 impact:** none.

### SR5-4: The secret-fact scan still skips several published text types

- **Severity / Confidence / Status:** Low / High / Confirmed.
- **Where:** `tools/check_docs.py:1585`:
  `SCANNED_TEXT_SUFFIXES = (".md", ".py", ".kts", ".txt", ".toml", ".properties.example")`.
  `:1663-1679` uses it to filter `published_files((".",))`.
- **Why:** the tracked tree also publishes:
  - `privacy-policy/index.html`, the GitHub Pages privacy policy. It is the most public file in
    the repository, and the closest in subject to signing and privacy prose;
  - `_config.yml` (Pages);
  - 400 `.kt` files, whose dense KDoc is where release and key history tends to be narrated;
  - 14 `.xml` files, including `strings.xml` in both locales;
  - `.sh` (2), `.json` (2), `.properties` (4), and `.pro` (1).

  A future comment or page that restates the blocked key's password properties passes the gate.
  This is the same class of gap that SEC4-3/AGG4-40 found for `.py`; the fix widened the suffix
  set without making it exhaustive.
- **Fix:**
  - Scan every published file that decodes as UTF-8 text, skipping binaries by a NUL-byte or
    decode check, instead of a suffix allowlist.
  - Keep the `SYNTHETIC-FIXTURE` exemption confined to the one test file.
  - Add a fixture `.kt` and a `.html` file that carry a synthetic violation, and assert both are
    reported.
- **PMA110 impact:** none.

### SR5-5: The passthrough JPEG keeps the HAL's EXIF without an explicit privacy strip

- **Severity / Confidence / Status:** Info / Medium / Likely. The lane is dormant on PMA110
  because hi-res is capability-gated (CLAUDE.md 200 MP bullet).
- **Where:**
  - `capture/StillCapturePipeline.kt:402-424` (`writePassthroughJpeg`) seeds the composer with
    `extractExifApp1(bytes)`.
  - `:732-764` (`composeStillExifApp1`) only ADDS `exifAttributeList`, orientation, and
    dimensions over the source tags.
  - `:776-797` (`composePassthroughExifApp1`) also keeps them. This is the cycle-4 AGG4-6 rework.
    The behaviour pre-dates it, and the new splice keeps it by design.
- **Why:** the app never sets `JPEG_GPS_LOCATION` (grep finds no `JPEG_GPS`/`setLocation`), so a
  spec HAL writes no GPS IFD. However, a vendor HAL's own APP1 can carry other data the app does
  not control:
  - a `BodySerialNumber` or `ImageUniqueID`;
  - a MakerNote with device identifiers;
  - on a non-conforming HAL, cached GPS.

  CLAUDE.md's "captures carry no GPS tags" claim was verified by parsing a HEIF. HEIF never takes
  the HAL EXIF, so this lane is unverified. On a capable non-PMA110 device this could put a device
  identifier into a user's shared photo, contrary to the app's no-location and Data Safety
  posture.
- **Fix:** in `composeStillExifApp1`, when `sourceExifApp1 != null`, blank the GPS tags
  (`TAG_GPS_*`), `TAG_BODY_SERIAL_NUMBER`, `TAG_CAMERA_OWNER_NAME`, `TAG_IMAGE_UNIQUE_ID`, and
  `TAG_MAKER_NOTE` before `saveAttributes()`. Add a JVM test that composes over a synthetic source
  APP1 carrying a GPS IFD and a serial, and asserts both are absent from the result.
- **PMA110 impact:** none (lane dormant; HEIF/JPEG/DNG lanes unchanged).

---

## Final sweep (commonly missed items)

- **Exported components:**
  - Manifests are unchanged in cycle 4.
  - Release exports only the launcher `MainActivity`.
  - The debug exported activities require `android.permission.DUMP`, and the Compose test host
    stays `exported=false`.
  - There are no providers, services, manifest receivers, or FileProvider.
  - Both dynamic debug receivers (`CameraViewModel.kt:1065-1081`) remain `RECEIVER_NOT_EXPORTED`
    and DEBUG-gated.
- **Network and privacy:** `INTERNET` and `ACCESS_NETWORK_STATE` are `tools:node="remove"`. There is
  no location permission or code (see SR5-5 for the HAL-EXIF residue).
- **Backup:** `allowBackup=false`, with `data_extraction_rules` and `backup_rules` unchanged.
- **Temp files:** the new `still-exif-*.jpg` scratch files are created in app-private `cacheDir`
  and deleted in `finally`. `LatestHeavyWorkLane` likewise uses the private cache. There is no
  world-readable path.
- **Logging:** new cycle-4 warnings go through `DiagnosticLog` (StillCapturePipeline aliases it as
  `Log`). The one new URI-bearing warning (`finalized video container unreadable for $uri`) is in
  the SEC2-5 class, already tracked.
- **Gradle seams:**
  - The `uploadKeyFloorFixtureVectors` / `verifyUploadKeySecretFloorFixture` seam reads and writes
    paths named by Gradle properties. Under the sealed run those properties cannot be supplied:
    argv `-P` is refused, `ORG_GRADLE_PROJECT_*` is dropped by the allowlist, and user
    `gradle.properties` keys are allowlisted.
  - In plain developer Gradle the seam is caller-controlled by definition. No issue.
- **`verify_host.py` release lint (`0a22ffb2`):** `lintRelease` runs only on a clean tree and
  touches no signing task. Configuration still evaluates signing values, which is the
  pre-existing AGG3-34 capture.
- **Supply chain:**
  - The wrapper is pinned with `distributionSha256Sum` and `validateDistributionUrl=true` (but see
    SR5-1: the checksum is not re-verified after unpack).
  - `verification-metadata.xml` is tracked.
  - Repositories are unchanged (`google`, `mavenCentral`, and `gradlePluginPortal` for plugins
    only, with `FAIL_ON_PROJECT_REPOS`).
- **Subprocess:** there is no `shell=True`, `eval`, or `pickle` in the changed tools. keytool still
  receives the password only through `-storepass:env` (but see SR5-3).
- **Secrets in tree:** no tracked keystore or credential files. The cycle-4 fixtures are synthetic.

## Files examined

- `app/build.gradle.kts` (cycle-4 diff in full: `:399-517`, `:756-767`, `:906-1040`),
  `gradle.properties`, `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`.
- `tools/build_immutable_release.py` (cycle-4 diff in full; `:119-232`, `:580-640`, `:759-944`,
  `:995-1120`), `tools/upload_key_policy.py` (diff in full), `tools/run_scoped_signed_release.py`
  (`:40-135`), `tools/verify_host.py` (diff), `tools/check_docs.py` (`:45-51`, `:1585-1695`), and
  `tools/tests/test_immutable_release.py` (`:525-570`).
- `capture/StillCapturePipeline.kt` (`:300-430`, `:715-800`), `ui/CameraViewModel.kt`
  (`:1060-1085`), plus a risky-API and logging grep over the whole cycle-4 `app/src/main` diff.
- AGP 9.4.1 jar class listing (`SigningConfigWriterTask`, `SigningConfigVersionsWriterTask`), and
  key-name-only checks of `app/build/intermediates/signing_config_versions/*`.
- `~/.gradle` layout listing (no `init.gradle*`, no user `init.d`; distribution `init.d` present).
- Gradle docs: init-script discovery and `gradle.properties` locations (fetched 2026-10-02).
