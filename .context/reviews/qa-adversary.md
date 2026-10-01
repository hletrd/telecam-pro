# RPL cycle 4 — QA-adversary (static rerun)

Scope: static only (no device, no source edits, no gate re-run). Baseline log read from the
orchestrator's `python3 tools/verify_host.py` run at HEAD `14767b0a`
(`scratchpad/gate-baseline.log`, 866 lines, `exit=0`).

## Gate verdict: PASS (all legs green)

| Leg | Result | Notes |
|---|---|---|
| Gradle `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:verifyPartitionACoverage` | PASS | `BUILD SUCCESSFUL in 6s`, 95 tasks: **3 executed, 92 up-to-date**, configuration cache reused |
| Partition coverage | PASS | A 9901/9915 = 99.86% (>= 99.5%); B 50.85%; overall 65.91%; Excluded 0/353; 14 reviewed A residual lines across 9 classes, manifest exact |
| `tools/tests` | PASS | 181 tests, OK (221.7 s) |
| `tools/coverage/tests` | PASS | 15 tests, OK |
| `device-tests/tests` | PASS | 195 tests, OK. The `FAIL: simulated failure after REC start` / `ERROR: AdbError: refusing force-stop while recording is active` / `*_runtime_skip` lines (log ~550-670) are harness self-test fixture output, not failures |
| `tools/check_docs.py` | PASS | 190 checks, 0 failed, 0 private checks skipped |
| `compileall` | PASS | silent |
| `git diff --check HEAD --` | PASS | silent |

### Warnings visible
- Compiler `w:` lines: **none in the log — but none could appear** (see QA4-1): every
  `compile*Kotlin` task was UP-TO-DATE.
- Python warnings / DeprecationWarning: none.
- Gradle deprecation notices: none printed.
- Lint debug (`app/build/reports/lint-results-debug.txt`, 2026-10-02 06:45): **0 errors, 1 warning** —
  `UsableSpace` at `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:456`
  (`location.directory.usableSpace`; lint suggests `StorageManager#getAllocatableBytes`).
- Lint release (`lint-results-release.txt`): 4 warnings, but the report is **stale (2026-08-24)** —
  see QA4-3.
- No lint baseline file, no `lint.xml`; `lint { disable += "OldTargetApi"; disable += "ObsoleteSdkInt" }`
  only (`app/build.gradle.kts:580-587`), both justified in comments.
- No `@Ignore`/`@Disabled`/`Assume`/`@SdkSuppress`/`@FlakyTest` in `app/src`. No Gradle test
  filters (`includeTestsMatching`/runner annotation args). Python skips: only two
  `skipUnless(hasattr(os, "mkfifo"))` (`tools/tests/test_release_source_gate.py:292`,
  `tools/tests/test_immutable_release.py:608`), which run on macOS; log shows no skipped tests.

## Findings (10)

### QA4-1 — Gate log cannot attest "zero compiler warnings"; warnings are never fatal
- Where: `tools/verify_host.py:123-133`; gate log lines 77/82/87 (`compileDebugKotlin`,
  `compileDebugAndroidTestKotlin`, `compileDebugUnitTestKotlin` all `UP-TO-DATE`);
  `app/build.gradle.kts` has no `allWarningsAsErrors` / lint `warningsAsErrors`.
- Severity: Medium. Confidence: High.
- Why: Kotlin `w:` diagnostics are only printed when the compile task executes; with 92/95 tasks
  up-to-date and config cache reused, a green log says nothing about deprecations or warnings
  introduced earlier in the cycle. Because nothing promotes warnings to errors, the "no deprecated
  APIs" project rule is enforced only by whoever reads a fresh compile. Fix direction: either
  `kotlin { compilerOptions { allWarningsAsErrors = true } }` (plus lint `warningsAsErrors`/
  `checkAllWarnings` with a curated `disable` list), or have the gate run the compile with
  `--rerun-tasks` scoped to the compile tasks when a warning audit is wanted.

### QA4-2 — Debug lint warning accepted silently (UsableSpace)
- Where: `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:456`.
- Severity: Low. Confidence: High.
- Why: the review-spool byte budget is derived from `File.usableSpace`, which ignores
  system-clearable cache; on a nearly full device it can under-budget and refuse a review decode
  that `getAllocatableBytes` would allow. Either switch to the allocatable query or suppress with a
  written reason; leaving the one warning un-triaged trains readers to ignore lint output.

### QA4-3 — Release lint is not in the default gate and its last report is stale
- Where: `tools/verify_host.py:141-151` (release lint only behind `--release`);
  `app/build/reports/lint-results-release.txt` dated 2026-08-24.
- Severity: Medium. Confidence: High.
- Why: the stale report cites `NotShrinkingResources` at `app/build.gradle.kts:566`, which today is
  `isShrinkResources = true`, and `UseKtx` at `MediaStoreWriter.kt:381/1277/1279`, which today are
  unrelated lines — proof nobody has re-run release lint in ~5 weeks. Release-only analysis (R8 +
  resource shrinking, release-variant manifest) and the R8 keep rule for name-persisted enums are
  therefore unchecked between releases. Consider adding `:app:lintRelease` (unsigned variant) or
  `lintVitalRelease` to the default host gate.

### QA4-4 — `CameraCaps.read` (128 lines) has 0% host coverage and carries a safety invariant
- Where: `tools/coverage/partition-b.txt:24-25` (`CameraCaps`, `CameraCaps$*`);
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CaptureCapabilities.kt:267-467`.
- Severity: Medium. Confidence: High (measured from `report.xml`: `CameraCaps$Companion`
  0/128 lines covered).
- Why: `read()` is not just framework flattening — it applies the `HAL_SAFE_MAX_STILL_EXPOSURE_NS`
  still-exposure clamp at the caps seam (the CLAUDE.md "still ceiling is 4 s" device fact, lines
  ~407-417), filters JPEG/YUV sizes against the active array, caps SurfaceTexture sizes at 7680,
  builds aspect-filtered stream lists, and defaults dynamic-range profiles. The pure helpers it
  calls (`clampStillExposureRange`, `matchesStreamAspect`, `pickStillSize`) are tested, but the
  wiring is not: a regression that skips the clamp or passes the wrong range would keep every gate
  green and reproduce the device-fatal >4 s still. Robolectric's `ShadowCameraCharacteristics` can
  construct characteristics, or the glue can be split so the decision part moves to Partition A.

### QA4-5 — `$*` globs auto-surrender every future nested class to Partition B
- Where: `tools/coverage/partition-b.txt` (18 `Class$*` globs: `CameraEngine$*`,
  `CameraController$*`, `CameraViewModel$*`, `VideoRecorder$*`, `GlPipeline$*`, ...); only one
  `!` force-A exception (`VideoRecorder$StopResult`, line 81).
- Severity: Medium. Confidence: High.
- Why: the analyzer fails on patterns that match nothing, but not on new classes a glob newly
  matches. 59 named (non-lambda) nested types are currently swept into B this way, including
  state/value owners such as `CameraEngine$OpticsTransaction`, `$OpticsSnapshot`,
  `$RecordingAdmissionSnapshot`, `$VideoPipelineSelection`, `CameraViewModel$AppliedMemoryRecall`,
  `CameraController$ZslRingEntry`. Any pure logic added as a nested class of a hot-path file escapes
  the 99.5% contract silently, and B itself has no floor (50.85%, can fall without failing).
  Suggest: replace globs with an enumerated nested-class list (drift fails both ways), or add a
  B floor / a "new class matched by glob" drift check.

### QA4-6 — Function-wide `WrongConstant` suppression on `configureSession`
- Where: `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:628`.
- Severity: Low. Confidence: Medium.
- Why: the comment justifies suppressing only the vendor session type `0x80b4`, but the annotation
  covers the whole ladder function, which also builds templates, output configs, and dynamic-range
  profiles — any other mis-typed `@IntDef` constant there is now invisible to lint. Narrow it to a
  local `val sessionType` declaration (or a tiny helper) so the suppression scope matches the
  justification.

### QA4-7 — Misplaced `@Suppress("MissingPermission")` inside a lambda
- Where: `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:679`.
- Severity: Low. Confidence: Medium.
- Why: the annotation sits at the wrong indentation, annotating the `AudioRecord.Builder()...`
  expression inside `nativeOperation { }` with no comment pointing to the dominating
  `hasRecordPermission()` guard (contrast `StandbyAudioController.kt:420-422` and
  `CameraController.kt:266`, which explain theirs). It works for lint but reads like an accident
  and lacks the "why" the project's comment-density convention requires.

### QA4-8 — `assembleDebugAndroidTest` in the host gate proves compilation only
- Where: `tools/verify_host.py:126`; acknowledged in `CLAUDE.md` build section.
- Severity: Info. Confidence: High.
- Why: noting for reviewers who read "androidTest" in a green gate as evidence; it executes zero
  instrumented tests, and Partition B's 10,229 missed lines rely on device-tests/ runs that this
  static cycle did not perform.

### QA4-9 — Expected FAIL/ERROR fixture lines in a green log
- Where: gate log ~lines 550-670 (`device-tests/tests` harness self-tests print to stdout).
- Severity: Info. Confidence: High.
- Why: `grep FAIL` / `grep ERROR` over the gate log produces hits on every green run
  (`simulated failure after REC start`, `refusing force-stop while recording is active`), which
  makes ad-hoc log triage unreliable and could hide a genuine failure line among fixtures.
  Capturing the harness's child output inside those tests (or tagging it `[fixture]`) would make
  the log greppable.

### QA4-10 — Debug-only `MissingPermission` suppressions rely on an unchecked caller contract
- Where: `app/src/main/kotlin/me/hletrd/telecampro/camera/VendorTagInspector.kt:58,128`.
- Severity: Info. Confidence: Medium.
- Why: justified by "CameraEngine invokes diagnostics only after CAMERA grant", and failures are
  isolated per query; acceptable for debug diagnostics, but there is no assertion or test pinning
  that ordering. Low risk because the inspector is debug-only.

## Positive checks (no finding)
- No lint baseline, no `lint.xml`, no `tools:ignore` beyond two justified manifest entries
  (`AndroidManifest.xml:74` DiscouragedApi; debug manifest `:16`).
- No ignored/disabled Kotlin tests; no Gradle test filters; 30-minute Test timeout backstop present.
- Partition-A residual manifest is exact (drift fails), stale partition patterns fail, empty buckets fail.
- Excluded partition is 7 debug/preview scaffolding classes only (353 lines), none production.
