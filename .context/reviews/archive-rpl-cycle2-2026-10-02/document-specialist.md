# Document-Specialist Review — RPL cycle 2

Date: 2026-10-02 · HEAD `e5729ffd` · Read-only review (no edits, no commits).

## Scope and method

- Re-checked every doc and comment edit made in cycle 1 (`git diff ba5b16e7..HEAD` over `CLAUDE.md`,
  `README.md`, `docs/ARCHITECTURE.md`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, and the
  P5.1 KDoc commit `9bc67f08`) against the code it cites.
- Swept the backticked identifiers in `CLAUDE.md`, `ARCHITECTURE.md`, `FIELD_CHECKS.md`, and `README.md`
  against `app/src`, `tools`, `device-tests`, and the build files.
  - The only misses are names the docs deliberately describe as removed or historical
    (`VendorLogMode`, `setNativeLog`, `delogAssist`, `statusDisplayDurationMs`).
  - The rest are platform or framework names (`CameraCaptureSessionImpl`, `registerKeyEventInterceptor`,
    `pixelArraySizeMaximumResolution`, `ANDROID_SERIAL`, `sdkmanager`).
  - `AudioReadPolicy` is a file name and exists.
- Checked every `app/src/main/kotlin/**/*.kt` for a mention in `ARCHITECTURE.md`. All are listed, and no main files were added or removed in cycle 1.
- Queried the latest versions live:
  - Google Maven / Maven Central `maven-metadata.xml` for AGP, the Kotlin compose-compiler plugin,
    Compose BOM, core-ktx, activity, lifecycle, coroutines, heifwriter, exifinterface, profileinstaller,
    Robolectric, android-all-instrumented, androidx.test core/runner/ext-junit, and JaCoCo.
  - `services.gradle.org/versions/current`.
  - The AGP release-notes page on developer.android.com.
  - Robolectric 4.17 `DefaultSdkProvider.java` at tag `robolectric-4.17`.
  - The MediaMuxer API reference.
- Ran `python3 tools/check_docs.py`: **188 checks, 0 failed, 0 private checks skipped**.

## Toolchain vs registries: all current

| Component | Pinned | Latest stable (2026-10-02) | Status |
|---|---|---|---|
| AGP | 9.4.1 | 9.4.1 | current |
| Kotlin / Compose compiler plugin | 2.4.20 | 2.4.20 | current |
| Gradle wrapper | 9.8.0 (+ sha256) | 9.8.0 (`current: true`) | current |
| Compose BOM | 2026.09.00 | 2026.09.00 | current |
| core-ktx | 1.19.1 | 1.19.1 | current |
| Robolectric / android-all | 4.17 / `16-robolectric-13921718-i7` | 4.17 | current. The tag source confirms `BAKLAVA → "16","13921718"` and `PREINSTRUMENTED_VERSION = 7` |
| activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0, heifwriter 1.1.0, exifinterface 1.4.2, profileinstaller 1.4.1, androidx.test core/runner 1.7.0, ext-junit 1.3.0, junit 4.13.2 | — | same | current |

The cycle-1 toml comments were checked against the AGP release notes:
- "AGP 9.4 supports API 37 and requires Gradle 9.6+" is correct: minimum Gradle 9.6.0 and max API 37.
- The default Build Tools of 36.0.0 matches `README.md:141`, `ARCHITECTURE.md:1281`, and `tools/android_sdk.py`.

The CLAUDE.md table, README table and badges, and the ARCHITECTURE quick reference all agree with the catalog.

The `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` test-JVM flag (`app/build.gradle.kts:701-707`) matches the
publicly reported Robolectric 4.17 failure ("Failed to interact with raw FileDescriptor internals"). Dropping the specific
upstream issue reference in `8b10d407` was correct: issue #11434 is titled "SDK 37 throws IllegalAccessException", which
is not this failure.

Cycle-1 doc corrections that were verified against the code (DS-1, DS-2, DS-3, V3, V7, V8, A6, CR-18):
- `GlPipeline.setFrontMirrorConvention(front, streamPreMirrored)` is at `gl/GlPipeline.kt:544`, and `gl/FrontMirrorConvention.kt` exists.
- `CameraStatus.durationMs` and `CameraStatusLifecycle` are at `camera/CameraStatus.kt:75,96`.
- `LENS_MATCH_TOLERANCE = 1.35f` is at `camera/CameraState.kt:887`, used as a ratio at :912.
- The front RAW exclusion is `useRaw … && !frontRoute` (`CameraController.kt` `sessionAttemptPlan`) plus
  `rawSelectable(… frontFacing)` (`OpticsConstraints.kt:103-109`).
- `punchInResolved(enabled, frontFacing)` is at `CameraState.kt:414`.
- The token door `runRecorderWorkerNative(token)` is at `VideoRecorder.kt:1347,1711`. The owner-keyed door refuses while any
  token is pending (`recorderHoldsAdmissionAgainstLocked`, `:1334-1335`).
- The release key gate (`uploadKeyRotationApproved` / `uploadKeyCertificateSha256`, non-lint tasks only via
  `_NON_SIGNING_TASK`) is at `tools/build_immutable_release.py:535-538,658`.

## Findings

### DOC2-1 (MEDIUM, confidence HIGH): CLAUDE.md still says the program-line shutter slides ≤1 stop per tick; code is ±0.35 stop
- **Where:** `CLAUDE.md:482` ("shutter slides (≤1 stop/tick, brightness-neutral) only when ISO clamps").
- **Code truth:** `camera/AutoExposure.kt:128`
  `log2(pref / currentNs).coerceIn(-0.35f, 0.35f)`. Cycle-1 P5.1 (`9bc67f08`) corrected the KDoc
  (`AutoExposure.kt:106,128` now say "±0.35 stop") but did not touch the CLAUDE.md sentence. The plan
  (`docs/plans/2026-10-02-rpl-cycle1.md:99-103`) records "driveProgram step size" as done.
- **Impact:** the authority doc is wrong by ~3× on a user-tuned smoothness figure, and the plan marks it closed. An
  agent "restoring documented behaviour" would triple the step and bring back visible exposure snaps.
- **Fix:** replace "≤1 stop/tick" with "≤0.35 stop/tick". Optionally add a `check_docs.py` anchor that compares the
  CLAUDE.md figure with the `coerceIn(-0.35f, 0.35f)` literal (or a named constant).

### DOC2-2 (MEDIUM, confidence MEDIUM): the APV exclusion is documented as a platform rule, but the official MediaMuxer reference says APV-in-MP4 is supported from SDK 36
- **Where:** `CLAUDE.md:712-714` ("**APV** … is defined but **intentionally EXCLUDED**: Android's MediaMuxer
  (API 36) rejects APV in an MP4 container (device-verified — it errors the encoder mid-drain)").
- **Authoritative source:** the MediaMuxer API reference codec table lists **"APV ✓ (MP4) — Supported From SDK
  version 36"** (https://developer.android.com/reference/android/media/MediaMuxer).
- **Code truth:** APV is hard-refused at `video/VideoRecorder.kt:1863` (`VideoCodec.APV -> false`).
  The enum KDoc at `camera/CameraState.kt:1024-1031` still promotes APV ("the closest thing to ProRes /
  XAVC-I on this device") without mentioning that it is never admitted.
- **Impact:** the device observation is probably real, but the doc attributes it to Android itself. That is false
  per the platform docs. On a multi-device build it would wrongly rule APV out on every other API-36+ handset
  (for example Samsung, whose APV encoder the codec was built for). It also hides the more likely causes:
  the PMA110 firmware's muxer/C2 integration, or a missing CSD/format key in `ColorProfiles.apvFormat`.
- **Fix:** reword as "PMA110 (ColorOS, API 36) errored the APV encoder mid-drain when muxed to MP4. The platform
  documents APV-in-MP4 from API 36, so this is a device/firmware observation, not a platform rule."
  Keep the exclusion. Add a one-line note to the `VideoCodec` KDoc that APV is defined but never admitted
  (`encoderSelectionAdmitsTransfer`). Any future multi-device re-enable must be measured, per the
  "quirks only with measurements" rule.

### DOC2-3 (LOW, confidence HIGH): `robolectric.properties` comment is stale after the 4.17 bump
- **Where:** `app/src/test/resources/robolectric.properties:3` ("SDK 36 is the newest simulation Robolectric
  4.16.x supports").
- **Truth:** the repo is on 4.17. Its `DefaultSdkProvider` registers `CINNAMON_BUN → "17","15733970"`, so
  SDK 37 is simulatable, as `gradle/libs.versions.toml:18-19` itself says. The pin stays at 36 because
  targetSdk is 36, not because of a Robolectric limit.
- **Fix:** "SDK 36 matches targetSdk. Robolectric 4.17 can also simulate 37, which stays unused while
  targetSdk is 36. Simulated SDK 36+ requires JDK 21." (`RobolectricEglSentinels.kt:11` is already
  correct: "4.16.1 and 4.17".)

### DOC2-4 (LOW, confidence HIGH): private `docs/TESTING.md` is stale on toolchain and a removed Gradle property (untracked; optional doc)
- `docs/TESTING.md:119` and `:138` still say "Robolectric 4.16.1" and "the exact 4.16.1 pin". It should be 4.17;
  the android-all coordinate is unchanged.
- `docs/TESTING.md:229` and `:238` say "AGP 9.3". It should be 9.4.
- `docs/TESTING.md:240` claims `android.experimental.reportAggregationSupport=true` is "set in `gradle.properties`".
  It was removed in `47b797d1` (2026-08-24). `gradle.properties` no longer carries it, so the documented
  `createCoverageReport` merged path is not enabled as described.
- The doc does not mention the two cycle-1 test-task changes: the test-JVM `--add-exports` flag and the 30-minute
  `Test.timeout` backstop (`app/build.gradle.kts:673-680,701-707`).
- **Impact:** low. The file is private and optional in clean clones, but it is what the maintainer reads for the coverage recipe.
- **Fix:** update the four version/property statements and add the two test-task facts to the Robolectric "Build specifics" list.

### DOC2-5 (LOW, confidence HIGH on presence, MEDIUM on "unused"): `gradle/verification-metadata.xml` still trusts every superseded pin
- **Where:** `gradle/verification-metadata.xml`. AGP `9.3.0`, `9.3.1`, and `9.3.2` remain (:2510, :2518, :2526) next to 9.4.1 (:2534). The same applies to
  compose-bom `2026.06.01`/`2026.08.00` (:138, :143), kotlin-compose-compiler-plugin-embeddable `2.4.10` (:5351), core-ktx
  `1.19.0` (:1072), robolectric `4.16.1` (:6292), and the JaCoCo `0.8.14` family next to 0.8.15. In total there are 888 components.
- **Truth:** `--write-verification-metadata` appends and never prunes. After the cycle-1 bump nothing in the catalog
  resolves these versions, unless a transitive edge still does; JaCoCo 0.8.14 is the one worth confirming.
- **Impact:** checksums for artifacts the build no longer uses stay trusted. That enlarges the trust surface AGG-72 is about,
  and it obscures what the build actually depends on.
- **Fix:** with the AGG-72 work, regenerate from an empty `<components/>` section
  (`./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` plus the
  release and androidTest configurations), then diff. Only versions still resolved should remain.

### DOC2-6 (LOW, confidence HIGH): the docs gate pins only AGP and Compose BOM, not Kotlin or Gradle, and its fixture hardcodes the current AGP
- **Where:** `tools/check_docs.py:894-926` compares the README/CLAUDE/ARCHITECTURE AGP and BOM rows with the catalog.
  Nothing compares:
  - the Kotlin rows: `CLAUDE.md:66`, `README.md:150`, the badge at `README.md:12`, and `ARCHITECTURE.md:1279`;
  - the Gradle rows (`CLAUDE.md:67`, `README.md:149`) with `gradle-wrapper.properties`.
  Separately, `tools/tests/test_tool_contracts.py:589-590` asserts that the literal `"AGP 9.4.1"` appears in ARCHITECTURE.md.
- **Impact:** cycle 1 updated these rows correctly by hand, but the next Kotlin/Gradle bump can drift silently. Also, the
  next AGP bump will fail an unrelated contract test until someone edits the fixture string.
- **Fix:**
  - Add catalog/wrapper equality checks for Kotlin (tables plus badge) and Gradle, mirroring the AGP block.
  - Derive the fixture's `current` value from `libs.versions.toml` instead of the literal.

### DOC2-7 (LOW, confidence HIGH): ARCHITECTURE.md lacks the token-scoped recorder-worker door that CLAUDE.md now documents
- **Where:** `CLAUDE.md:938-943` documents `runRecorderWorkerNative(token)` and the rule that the owner-keyed door
  refuses every caller while a token is pending. `docs/ARCHITECTURE.md` (the "full map … in depth" authority)
  has no mention of `runRecorderWorkerNative`, the pending-token audio-worker race, or the owner-vs-token split. The only
  related rows are `:275` and `:399`, which cover pre-native allocation.
- **Impact:** a clean-clone reader of the as-built design authority cannot learn why recorder workers bypass
  the general native gate. Fixes `4e57fff2` and `0ab5c1ba` went back and forth on exactly this.
- **Fix:** add 2–3 sentences to the VideoRecorder/recording-admission section of ARCHITECTURE.md, mirroring
  `VideoRecorder.kt:1328-1346`.

### DOC2-8 (LOW, confidence MEDIUM): "zero retries" in the standby-meter sentence reads as the opposite of the policy
- **Where:** `CLAUDE.md:1023-1024` ("The STANDBY meter classifies reads through the same `AudioReadPolicy`: zero
  retries, a negative read ends that AudioRecord generation").
- **Code truth:** `video/AudioReadPolicy.kt:17` maps `byteCount == 0 → AudioReadOutcome.Retry`, and
  `StandbyAudioController.kt:725` handles it with `Retry -> continue`. A zero-byte read **is retried**. Only negative reads are terminal.
- **Impact:** the phrase can be read as "no retries at all", so a reader might "fix" `Retry` into a terminal outcome. The sentence
  also cites the old `n <= 0 → continue` hot-spin as the bug, which makes the distinction between zero and negative reads load-bearing.
- **Fix:** "a zero-byte read retries; a negative read ends that AudioRecord generation …".

### DOC2-9 (INFO, confidence HIGH): the documented Play-release gate cannot currently pass, and the docs present it as routine
- **Where:** the `CLAUDE.md:92-93` heading reads "Play-release gate (requires clean committed source + local signing credentials)".
  The `tools/verify_host.py:110` help text reads "also run signed release lint/APK/AAB gates (requires a clean committed tree)".
- **Truth:** `--release` calls `build_immutable_release.py :app:lintRelease :app:assembleRelease :app:bundleRelease`.
  Since `8c3b09bd`, any non-lint task is refused unless `uploadKeyRotationApproved=true` and the certificate
  fingerprint matches. Per AGG-67 that approval is owner-blocked, so `verify_host.py --release` fails today.
  The sentence added at CLAUDE.md:98-101 says so; the heading line and `--help` do not.
- **Fix:** append "+ owner-approved upload key (currently blocked, AGG-67)" to the heading comment and the `--help` string.

## Carried forward (still open, scheduled for later cycles; re-verified at HEAD)

- **AGG-97 / DS-5:** `focusConfidenceLabel` (`focus/MacroProximity.kt:193`) is still unused in main. It is pinned only by
  `FocusConfidenceTest.kt:232-242` and `CameraUiPolicyTest.kt:317`, while the OSD renders string resources.
- **AGG-98 / DS-6:** the English fallbacks on localized paths are unchanged.
- **AGG-99 / DS-7:** the privacy contact mailbox still needs owner confirmation.
- **AGG-100 / DS-8:** the documented 16/40/250/700 ms timings are still unnamed literals.

## Counts

9 findings: 0 HIGH, 2 MEDIUM (DOC2-1, DOC2-2), 6 LOW (DOC2-3 … DOC2-8), 1 INFO (DOC2-9). Toolchain: every pin is at
the latest stable; nothing is outdated.
