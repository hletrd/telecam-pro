# Security review: TeleCam Pro, RPL cycle 6 (security-reviewer, 2026-10-02, HEAD 30970c9e)

Agent: security-reviewer (SR6). Read-only pass. I made no source edits, ran no Gradle, and opened
no credential file.

Scope inventory (all examined):

- Manifests: `app/src/main/AndroidManifest.xml` and `app/src/debug/AndroidManifest.xml`, covering
  exported components, permissions, network-permission removal, and backup flags.
- Intents and receivers: `MainActivity.onNewIntent`, `ui/ExternalNavigation.kt`, and the debug
  receivers in `ui/CameraViewModel.kt:1203-1237`.
- MediaStore exposure: the restore and recovery query filters in `storage/MediaStoreWriter.kt` and
  `storage/PendingDiscardJournal.kt`, plus URI and display-name logging.
- EXIF privacy: `capture/StillCapturePipeline.kt` (all still lanes, including the cycle-5
  `35ccc225` strip) and `capture/HeifExif.kt` (extract and splice).
- Release tooling:
  - `tools/build_immutable_release.py`, all of it, including the cycle-5 `8e65456f` seal changes;
  - `tools/run_scoped_signed_release.py`, `upload_key_policy.py`, `verify_host.py` (`1839998d`),
    `check_docs.py` (`7f47c8c6` secret-fact scan), `check_release_artifact.py`, and
    `adb_proxy.py`;
  - `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `.gitattributes`, and
    `.gitignore`.
- Subprocess and environment handling plus path handling, across `tools/*.py`.

Baseline: cycle-5 `SR5-1..5` became AGG5-15/16/17/18/51 and were fixed in C.3, C.4, and B.9. I
re-checked each fix for bypasses below. Not re-raised:

- the leaked credential in history, which awaits the owner;
- AGG-67, AGG-69, AGG3-33, AGG3-34, and AGG4-44;
- every item the cycle-5 plan lists for a later cycle.

## Cycle-5 fixes re-checked

- **AGG5-15 (init scripts).** The fix holds for the channels it names. `require_sealed_gradle_user_home`
  now refuses `init.gradle` and `init.gradle.kts`, and it uses `os.path.lexists`, so a dangling
  symlink is refused too. `require_sealed_gradle_distribution` computes the wrapper unpack
  directory with Gradle's base-36 MD5 of the URL. I checked it on this machine: the computed
  `…/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm` is the real directory, so the check does not
  silently miss. The residuals are in SR6-2 (Kotlin daemon) and SR6-5 (distribution jars).
- **AGG5-16 (jvmargs).** The allowlist holds:
  - `shlex` tokenisation fails closed on a backslash or quote mismatch;
  - the merged-token forms either fail `fullmatch` or are harmless;
  - the `--add-opens` value class (`[A-Za-z0-9_.,-]`) cannot spell any `:`/`/`/`=` agent flag,
    even if KGP split `kotlin.daemon.jvmargs` on commas.
- **AGG5-17 (keytool and Python environment).** The fix holds:
  - `keytool_environment` refuses `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS`;
  - the scoped helper builds the inner environment from the allowlist, and the wrapper runs
    `-I -S`;
  - the three tool modules import only the standard library, so isolated mode does not break the
    wrapper.

  The git children are a separate channel (SR6-1).
- **AGG5-18 (secret-fact scan).** It now scans every published file that decodes as UTF-8. No
  tracked text file currently fails that decode, so nothing is skipped today (SR6-7 is latent).
- **AGG5-51 (passthrough EXIF).** The success path blanks the listed tags. The fallback paths do
  not strip anything (SR6-3).

## Findings

| ID | Severity | Confidence | Status | Title |
|---|---|---|---|---|
| SR6-1 | Low | High | Confirmed (static) | The release export trusts ambient git config and environment. Filters, hooks, the attributes file, and `GIT_*` variables can alter the checked-out source, and `expected` is hashed from the altered disk, not from the commit's blobs |
| SR6-2 | Low | Medium | Likely | `--no-daemon` does not cover the Kotlin compile daemon. The sealed release compiles through a shared, long-lived Kotlin daemon that an unsealed build may have started |
| SR6-3 | Low | High | Confirmed (static); dormant on PMA110, live on hi-res-capable devices | The passthrough-JPEG privacy strip fails open. If EXIF composition fails or the splice is refused, the HAL bytes are written verbatim with the HAL APP1. Non-Exif APPn segments such as XMP are always kept |
| SR6-4 | Low | Medium | Needs manual validation | The user `gradle.properties` allowlist admits `org.gradle.logging.level` with any value, including `debug`, during a run whose environment carries the signing values |
| SR6-5 | Info | High | Confirmed | The distribution check covers `init.d` and `gradle.properties` only. The unpacked distribution's `lib/` jars are never re-verified after download, so the "sealed" evidence claim still exceeds what the wrapper controls |
| SR6-6 | Info | Medium | Needs manual validation | No output-file check has proven that a saved DNG carries no serial or unique-id tag. The "no identifying metadata" claim was verified on HEIF only |
| SR6-7 | Info | High | Confirmed (latent) | The secret-fact scan skips any published file that is not valid UTF-8, such as a Latin-1 `.properties` file or a stray byte, rather than scanning it lossily |

Total: 7 (0 Critical, 0 High, 0 Medium, 4 Low, 3 Info). None changes PMA110 runtime behaviour
except SR6-3 on hi-res-capable devices.

---

### SR6-1: The release export runs git under ambient config and environment, and hashes the result instead of the commit

- **Severity / Confidence / Status:** Low / High / Confirmed (static).
- **Where:**
  - `tools/build_immutable_release.py:561-575` (`export_commit`): `git clone --shared
    --no-checkout` and `git checkout --detach` run with no `env=` and no `-c` overrides, so they
    inherit the full ambient environment and the system, global, and template config.
  - `:358-366` (`git_value`) and `:369-388` (`require_clean_commit`) also run with no `env=`.
  - `:572-575`: `expected` is `sha256_regular_beneath(destination, …)` over the files as they sit
    on disk after checkout.
  - `:1083-1115` (`verify_export`) compares later bytes against that `expected`. Nothing compares
    the checkout to the blob ids in `tree`.
- **Why:** git runs user-configured code during clone and checkout:
  - smudge, clean, and process filter drivers, selected through `core.attributesFile`, a template
    `info/attributes`, or `$GIT_DIR/info/attributes`;
  - the `post-checkout` hook, from `init.templateDir` or `core.hooksPath`;
  - environment variables such as `GIT_CONFIG_COUNT/KEY_n/VALUE_n`, `GIT_CONFIG_PARAMETERS`,
    `GIT_ALTERNATE_OBJECT_DIRECTORIES`, and `GIT_TEMPLATE_DIR`.

  This machine already configures a global filter driver (`filter.lfs.*`), so the mechanism is
  live, not hypothetical. The scoped helper's inner run gets an allowlisted environment, but
  global config still applies through `HOME`. The direct `build_immutable_release.py` invocation
  documented in CLAUDE.md inherits every `GIT_*` variable as well.
- **Failure scenario:** the same same-user threat model as SR5-1/2/3.
  1. Something adds `* filter=x` through `core.attributesFile`, plus a `filter.x.smudge` that
     injects code into a `.kt` file and a matching `filter.x.clean` that strips it again.
  2. The checkout writes the injected source, and `expected` hashes it.
  3. The final `git status` in the snapshot passes the file through the clean filter and reports
     it clean.
  4. The evidence records the commit/tree ids of source the APK was not built from.

  A `post-checkout` hook that edits files and sets `skip-worktree` reaches the same outcome.
- **Fix:**
  - Run every git child with an isolated configuration:
    - the allowlisted environment, with `GIT_*` removed;
    - `GIT_CONFIG_NOSYSTEM=1`, `GIT_CONFIG_GLOBAL=/dev/null`, and `GIT_ATTR_NOSYSTEM=1`;
    - `-c core.hooksPath=/dev/null -c core.attributesFile=/dev/null -c core.fsmonitor=false`;
    - `clone --template=` (empty).
  - After checkout, verify each tracked file against the commit itself. Compute the git blob id of
    the bytes on disk (`git hash-object --no-filters`, or `blob <len>\0` hashed in Python with the
    repository's object format) and require it to equal the id from `git ls-tree -r -z <commit>`.
  - The LFS-tracked Play screenshots stay pointer files under this scheme. They are not build
    inputs.
  - Host tests:
    - a fixture `core.attributesFile` plus a smudge filter, refused or mismatched;
    - a `post-checkout` hook template, inert;
    - `GIT_CONFIG_COUNT` injection, ignored.
- **PMA110 impact:** none (tooling).

### SR6-2: The Kotlin compile daemon is reused across sealed and unsealed builds

- **Severity / Confidence / Status:** Low / Medium / Likely.
  - Gradle's `--no-daemon` governs the Gradle daemon only.
  - KGP's default `kotlin.compiler.execution.strategy` is `daemon`, and nothing in
    `gradle.properties`, `app/build.gradle.kts`, or `SEALED_GRADLE_FLAGS` overrides it. I grepped
    all three.
  - The Kotlin daemon run directory exists on this host
    (`~/Library/Application Support/kotlin/daemon`).
  - `archive-cycle2-2026-07-17/verifier.md:39` observed a separate Kotlin compile daemon running
    beside Gradle daemons.
- **Where:**
  - `tools/build_immutable_release.py:152` sets
    `SEALED_GRADLE_FLAGS = ("--no-build-cache", "--no-configuration-cache", "--no-daemon")`.
  - Its own comment at `:149-151` says a reused daemon "carries JVM state (agents, system
    properties) from whichever unsealed build started it".
- **Why:**
  - A Kotlin daemon is discovered through run files and reused by any later build whose compiler
    classpath and memory request it satisfies.
  - The `JAVA_TOOL_OPTIONS` agent or the `kotlin.daemon.jvmargs` that the sealed run now refuses is
    part of the daemon's process state, not of its reuse key. A daemon started by an ordinary
    unsealed `./gradlew assembleDebug` in a shell that carried such an agent stays resident and
    then compiles the release's Kotlin into the signed APK.
  - So the cycle-5 refusal of those channels at the sealed run's own launch is bypassed through
    an earlier launch.
- **Failure scenario:**
  1. A developer shell with a profiler or injected `JAVA_TOOL_OPTIONS=-javaagent:…` runs a debug
     build, which spawns a Kotlin daemon with the agent attached.
  2. Within the daemon's idle window, the operator runs the sealed release from a clean shell. The
     wrapper's environment and `jvmargs` refusals pass.
  3. `compileReleaseKotlin` connects to the agent-carrying daemon.
- **Fix:**
  - Add `-Pkotlin.compiler.execution.strategy=in-process` to `SEALED_GRADLE_FLAGS`. The wrapper
    owns that argv, and `validate_gradle_tasks` still forbids caller `-P`.
  - Record it in the evidence `gradle_command`, as the existing flags are.
  - Alternatively, give the sealed run a private `kotlin.daemon` run directory. In-process
    compilation is simpler and makes `kotlin.daemon.jvmargs` moot.
  - Add a host test that the flag is present.
- **PMA110 impact:** none.

### SR6-3: The passthrough-JPEG privacy strip fails open

- **Severity / Confidence / Status:** Low / High / Confirmed (static).
  - The lane is capability-gated and dormant on PMA110.
  - It is live on any multi-device install whose camera advertises a hi-res size, which is the
    multi-device decision of 2026-08-01.
- **Where:**
  - `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt:400-411`
    (`writePassthroughJpeg`): `bestEffortHeifExif` turns any composition failure into
    `exifPayload = null`.
  - `:360-376` (`writeSingleJpeg`) leads into `writeJpegOnce`. With a null payload, or a payload
    that cannot be spliced, `writeJpegOnce` writes `encoded` verbatim (`:789-815`).
  - The verbatim bytes are the HAL JPEG, still carrying the HAL's own Exif APP1. The AGG5-51 strip
    (`:743-746`, the list at `:866`) is applied only inside the successful composition.
  - `composePassthroughExifApp1` (`:915-934`) ends with `compose(null)`. If that last tier throws,
    the whole payload is null.
  - Even on success, `exifSplicePlan`
    (`app/src/main/kotlin/me/hletrd/telecampro/capture/HeifExif.kt:90-129`) drops only Exif APP1
    segments and keeps every other APPn and COM segment verbatim. A vendor XMP APP1
    (`http://ns.adobe.com/xap/1.0/`) can carry `exif:GPS*`, `aux:SerialNumber`, or
    `xmpMM:DocumentID`.
- **Why:**
  - AGG5-51 exists because a vendor HAL APP1 may embed cached GPS, a body or lens serial, or a
    MakerNote.
  - The fix guarantees removal only when everything else succeeds. The failure modes are the
    ordinary ones: a full cache dir for the scratch file, or an ExifInterface parse failure on a
    vendor APP1. The latter is exactly the non-conforming HAL the strip targets.
  - In both cases the output is the unstripped original.
  - CLAUDE.md states that captures carry no GPS tags, and the privacy policy and Data Safety form
    rest on that statement.
- **Failure scenario:** a hi-res-capable non-PMA110 device with a HAL that writes cached GPS into
  its JPEG APP1 has a nearly full cache partition. `File.createTempFile` throws, and the still is
  published with the HAL's GPS directory and serial intact.
- **Fix:**
  - Make the strip a property of the bytes written, not of the composer. When the payload is null
    or cannot be spliced, the passthrough lane writes the JPEG with every Exif APP1 removed: the
    `exifSplicePlan` ranges with no payload inserted.
  - Optionally insert a minimal orientation-only APP1 built purely, without ExifInterface. A
    sideways image is an acceptable degradation; leaking location is not.
  - Also drop XMP APP1 (and any APPn other than JFIF APP0, ICC APP2, and MPF APP2) from the kept
    ranges in the passthrough lane.
  - Host tests:
    - a HAL APP1 with GPS plus a forced compose failure, so the output carries no `Exif\0\0`
      segment;
    - an XMP APP1 that is stripped.
- **PMA110 impact:** none (lane dormant).

### SR6-4: The user `gradle.properties` allowlist admits debug-level logging in the signing run

- **Severity / Confidence / Status:** Low / Medium / Needs manual validation.
- **Where:**
  - `tools/build_immutable_release.py:159` admits `org.gradle.logging.level` with any value.
  - `run_checked` (`:352-355`) streams Gradle's console output straight to the terminal. The
    environment it passes carries `TELECAMPRO_STORE_PASSWORD`, `TELECAMPRO_KEY_PASSWORD`, and
    `TELECAMPRO_KEY_ALIAS`.
- **Why:**
  - Gradle's own documentation warns that the DEBUG log level can expose security-sensitive
    information on the console.
  - The sealed run otherwise treats the signing values as never printed. A `debug` level in the
    user home's properties is ambient, persistent, and easy to leave set from an unrelated
    debugging session.
  - Scrollback, terminal logging, or a CI log then becomes a channel for those values.
  - I did not run a debug-level release build, so whether this AGP/Gradle pair actually emits a
    signing value at DEBUG is unproven.
- **Failure scenario:** an operator set `org.gradle.logging.level=debug` weeks earlier and runs
  the scoped signed release, and the console log is captured by `script`, tmux logging, or an
  agent transcript.
- **Fix:**
  - Admit the key only with `quiet`, `warn`, or `lifecycle`.
  - Or add an explicit `--info`/`--warn` level flag to `SEALED_GRADLE_FLAGS`. Command-line flags
    win over properties.
  - Host test: a `debug` value is refused.
- **PMA110 impact:** none.

### SR6-5: Distribution `lib/` jars are not re-verified

- **Severity / Confidence / Status:** Info / High / Confirmed.
- **Where:** `tools/build_immutable_release.py:340-349` checks only `init.d` and
  `gradle.properties` in each unpacked installation.
- **Why:** `distributionSha256Sum` is verified only on download. The same same-user writer that
  SR5-1 considered can replace `gradle-core-*.jar` or a bundled plugin jar under
  `wrapper/dists/…/lib/`, and every check passes.
  - `gradle/verification-metadata.xml` covers resolved dependencies, not the distribution.
- **Fix (pick one):**
  - The cycle-5 "stronger option": run the sealed build with a fresh temporary
    `GRADLE_USER_HOME`, so the wrapper re-downloads and re-verifies the distribution, while
    dependency verification guards the module cache.
  - Re-hash the original distribution zip, which is kept beside the unpack directory, against
    `distributionSha256Sum` and compare the unpacked tree against the zip's entries.
  - Or narrow the evidence wording so it does not claim the distribution is sealed.
- **PMA110 impact:** none.

### SR6-6: DNG identifying-tag absence is unverified on an output file

- **Severity / Confidence / Status:** Info / Medium / Needs manual validation.
- **Where:** `capture/StillCapturePipeline.kt` `saveDng` uses `DngCapture.writeDng` and
  `DngCreator`. CLAUDE.md's "no GPS" proof parsed a HEIF only.
- **Why:** `DngCreator` writes its own TIFF tags (Make, Model, UniqueCameraModel, and others)
  from platform properties. Nobody has checked whether a camera-serial or unique-id tag lands in
  a saved DNG on PMA110 or on the tablets. `setLocation` is never called, so GPS is not expected.
- **Fix:** add a FIELD_CHECKS row: pull one DNG per device and list its TIFF/EXIF tags with
  `exiftool -a -G1` (or the repository's TIFF parser). Record the absence of
  `CameraSerialNumber`, `BodySerialNumber`, `ImageUniqueID`, and GPS.

### SR6-7: The secret-fact scan skips non-UTF-8 published text

- **Severity / Confidence / Status:** Info / High / Confirmed (latent). I ran the same decode over
  every tracked file: none fails today.
- **Where:** `tools/check_docs.py:1697-1708` (`published_text`) returns `None`, which means skip,
  on a `UnicodeDecodeError`.
- **Why:** this is a classification by content that fails open. A Latin-1 `.properties` file,
  which is the Java default encoding for that format, or one stray non-UTF-8 byte in a markdown
  file removes the whole file from the scan with no diagnostic.
- **Fix:**
  - Treat only a NUL-bearing payload as binary.
  - Decode everything else with `errors="replace"`, which keeps every ASCII pattern matchable.
  - Or fail the check on an undecodable published text file.
  - Add a fixture test.
