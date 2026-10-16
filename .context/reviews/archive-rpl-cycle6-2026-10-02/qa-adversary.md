# QA-Adversary — RPL cycle 6 static gate (HEAD 30970c9e, 2026-10-02)

No device available; static/host gate only. No tracked source/test/doc edits, no git state changes.

## Gate summary

Command: `python3 tools/verify_host.py` (JDK 21, foreground), log:
`/private/tmp/claude-501/-Users-hletrd-flash-shared-find-x9-ultra-camera/d3017935-05a2-4136-9030-3078fa0d6d49/scratchpad/gate-baseline.log`

| Item | Result |
|---|---|
| Exit code | **0** |
| Gradle (`assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug verifyPartitionACoverage`) | BUILD SUCCESSFUL in 50s (17 executed, 18 from cache, 60 up-to-date) |
| Kotlin unit tests | **2595 tests / 309 suites, 0 failures, 0 errors, 0 skipped** (from `app/build/test-results/testDebugUnitTest/*.xml`) |
| `:app:lintDebug` | **0 errors, 1 warning** — `UsableSpace`, `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:456` (pre-existing since 2026-08-25, deferred AGG4-77/AGG3-61) |
| `:app:lintRelease` | **SKIPPED (dirty tree)** — expected: staged `.context/reviews` archive renames. `app/build/reports/lint-results-release.*` on disk is stale (09:41, from an earlier run: 0 errors, 1 warning, same `UsableSpace`) and is NOT evidence for HEAD |
| Coverage | Overall 69.89%; **Partition A 10570/10584 = 99.87%** (target ≥ 99.5%); Partition B 56.01%; Excluded 0/353. Reviewed A residuals: 14 lines / 9 classes, manifest exact |
| Python `tools/tests` | 213 tests, OK (no skips) |
| Python `tools/coverage/tests` | 21 tests, OK |
| Python `device-tests/tests` | 195 tests, OK (the `SKIP:`/`FAIL:` lines in the log are harness fixture output under test, not skipped tests) |
| `tools/check_docs.py` | 195 checks, 0 failed, 0 private checks skipped |
| `compileall`, `git diff --check HEAD` | clean |

### Compiler / deprecation warnings

The gate's Kotlin compiles were `FROM-CACHE`, so they print no diagnostics. To close that blind spot I
force-recompiled (`:app:compileDebugKotlin :app:compileDebugUnitTestKotlin
:app:compileDebugAndroidTestKotlin --rerun-tasks --no-build-cache`, 33/33 tasks executed, build-dir
only): **0 `w:` Kotlin warnings, 0 `e:`**, no Kotlin deprecation warnings across main, unit-test and
androidTest sources.

Gradle prints "Deprecated Gradle features were used … incompatible with Gradle 10". The problems
report attributes **every** instance to `Configuration.setVisible(boolean)` from plugin
`com.android.internal.application` (AGP 9.4.1 internals; removal scheduled for Gradle 11). None come
from project build scripts.

## Adversarial checks beyond the gate

- Lint baseline / config: `git log ea7d4374..HEAD -- '*lint*' '*baseline*'` → no commits. No lint
  baseline file exists. `app/build.gradle.kts` `lint {}` disables only `OldTargetApi` and
  `ObsoleteSdkInt`, both with justification comments, and both unchanged in range.
- `@Suppress`/`@SuppressLint`/`tools:ignore` additions in `ea7d4374..HEAD`: exactly one,
  `@Suppress("UNCHECKED_CAST")` in
  `app/src/test/kotlin/me/hletrd/telecampro/camera/CameraEngineDngInvalidationTest.kt:56`. It is a
  test-only cast of a reflectively read private `dngPreCaptureAllocations` set. Justified, but see
  QA6-2. No suppressions were removed in range.
- `@Ignore` in `app/src`: none. JUnit skipped count: 0.
- Python skips: three conditional skips exist (`test_release_source_gate.py:292` and
  `test_immutable_release.py:927` `skipUnless(mkfifo)`, `test_upload_key_policy.py:176` `skipTest` if
  the AGP jar is not cached). None fired in this run (no `skipped=` in any unittest summary). Per
  TE5-17/AGG5-67, the gate is supposed to turn the third into a failure.
- Toolchain doc drift: the on-disk `CLAUDE.md` table (AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, BOM
  2026.09.00) matches `gradle/libs.versions.toml` and the wrapper (`gradle-9.8.0`). No drift.

## Findings

| ID | Severity / Confidence | Finding | Cite | Fix |
|---|---|---|---|---|
| QA6-1 | Medium / High | Release lint was not exercised for HEAD. The gate skipped it because the tree is dirty (staged archive renames), and the only release-lint report on disk predates this run. The cycle cannot claim a release-lint pass. | gate log L1, L916; `app/build/reports/lint-results-release.txt` mtime 09:41 | Commit the `.context/reviews` archive move, then rerun `python3 tools/verify_host.py` on the clean tree before closing cycle 6, and cite that run's release-lint verdict. |
| QA6-2 | Low / Medium | The new DNG invalidation test drives `CameraEngine` entirely through reflection: four private fields by string name plus a private method. A rename fails at runtime, not compile time, and the test bypasses the real path that produces the state it checks. This follows an established pattern (26 test files use `getDeclaredField`/`getDeclaredMethod`), so it is not a regression. | `CameraEngineDngInvalidationTest.kt:36-72` | Optional: add an `@VisibleForTesting internal` seam for registering a DNG owner and calling `invalidateCameraReady`, so renames break compilation. |
| QA6-3 | Info / High | The `UsableSpace` lint warning is unchanged and still deferred. | `ui/review/MediaReview.kt:456` | Keep it deferred under AGG4-77, or use `StorageManager.getAllocatableBytes` (API 26+, below minSdk 33), or suppress it with a written reason. |
| QA6-4 | Info / High | The Gradle-10 deprecation comes entirely from AGP's internal `Configuration.setVisible`. Project scripts emit none. | `build/reports/problems/problems-report.html` | No action. Recheck after the next AGP bump. |
| QA6-5 | Info / High | The gate cannot see Kotlin compiler warnings when compile tasks are `FROM-CACHE`, which is how they ran in this baseline. A forced recompile showed 0 warnings, so nothing is hidden today, but the gate is not proof of that by itself. | gate log L76-L95 | Optional: add `allWarningsAsErrors` (or a `-Werror`-style flag) to the Kotlin compiler options, so a warning makes the build fail and is never just replayed silently from cache. |

No blocking defects. The host gate is green apart from the expected release-lint skip.
