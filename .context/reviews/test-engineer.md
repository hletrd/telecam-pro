# Cycle 34 test-engineer review

Date: 2026-08-24

Reviewed revision: `56602a2f` (`origin/main`)

Workspace: clean detached worktree `/tmp/find-x9-ultra-cycle34.EZe8ao`

Mode: host-only review; no deployment, device mutation, or physical-device claim

## Inventory and method

I read `CLAUDE.md`, all 1,351 lines of `docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, the public
README/privacy/Play/device-harness authorities, and every committed plan through cycle 33. The
private `docs/BACKLOG.md`, `docs/TESTING.md`, and `docs/UX_POLICY.md` files are absent, which the
committed clean-clone contract explicitly permits.

The current inventory has 469 tracked paths: 101 production Kotlin files (53,454 lines), 215 JVM/
Robolectric/Compose test files (42,203 lines), four instrumented Android test files (588 lines), 32
Python files spanning the device harness and build/release/coverage tools, three tracked shell
entry points, 54 Markdown documents, and 22 Gradle/TOML/properties/XML configuration files. I
inspected every file in those review-relevant inventories, mapped production owners to host,
Robolectric/Compose, instrumented, adb-harness, and field evidence, and swept for skipped tests,
diagnostic-only probes, timing oracles, weak assertions, coverage exclusions, warning suppression,
and documentation/code drift. Historical findings were checked against the current source before
being considered.

Evidence run from the exact clean checkout:

- `python3 tools/check_docs.py`: 106 checks passed; 24 optional-private checks skipped as designed.
- Tool/release tests: 90 passed.
- Coverage-tool tests: 9 passed.
- Device-harness self-tests: 183 passed.
- Kotlin JVM/Robolectric/Compose suite: passed when forcibly regenerated.
- `python3 tools/verify_host.py`: failed at `:app:verifyPartitionACoverage`.
- `:app:testDebugUnitTest :app:createDebugUnitTestCoverageReport :app:verifyPartitionACoverage
  --rerun-tasks`: reproduced the same failure from newly compiled classes and a newly generated
  report, ruling out stale or concurrently replaced coverage evidence.
- Python compilation, shell syntax checks, documentation checks, and `git diff --check`: passed.

## Findings

### TEST34-01 — current HEAD fails the repository's authoritative exact-coverage gate

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed quality-gate regression; not a stale build artifact.
- **Exact regions:** gate definition `app/build.gradle.kts:654-669`; authoritative runner
  `tools/verify_host.py:68-82`; reviewed residual authority
  `tools/coverage/partition-a-residuals.txt:1-12`; unreviewed misses in
  `app/src/main/kotlin/me/hletrd/telecampro/camera/FamilyDeletionMarkerDispatcher.kt:178-182`,
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:73-77`,
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:132-143`, and
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:1853-1958`.
- **Problem:** A fresh JaCoCo report measures Partition A at 7,922/7,947 lines (99.69%, above the
  numeric threshold) but finds 12 missed lines outside the exact reviewed residual manifest:
  `FamilyDeletionMarkerCapacityOwner` (1), `CameraScreenPolicyKt` (2),
  `OwnerlessMediaDeleteOverrides` (2), and `ReviewStillGeometry` (7). The contract intentionally
  rejects even above-threshold drift, because every miss must be either executed or explicitly
  justified as framework-bound, proven-unreachable, or race-only. Current HEAD therefore cannot
  pass `python3 tools/verify_host.py` or Gradle `check`.
- **Concrete failure scenario:** CI or a release operator follows the documented authoritative host
  command and stops before the Python/tool/harness/doc phases. If a maintainer instead runs only the
  ordinary assemble/test/lint trio, the build appears green while new untested branches bypass the
  repository's exact residual review.
- **Suggested fix:** Exercise the testable review geometry/position and default-argument paths.
  Review the framework-bound MediaStore default lambdas and the double-release invariant separately;
  either extract their pure/framework owners or record only genuinely unreachable/framework-bound
  lines in the exact residual manifest with valid rationales. Regenerate the full report and require
  `python3 tools/verify_host.py` to pass; do not merely lower the threshold or bulk-accept all misses.

### TEST34-02 — seven accessible still-review position outcomes lack an oracle

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed functional test gap; production behavior is likely but not fully
  verified.
- **Exact regions:** nine-way classifier
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:1915-1938`; pure test
  `app/src/test/kotlin/me/hletrd/telecampro/ui/review/MediaReviewGestureTest.kt:121-152`; Compose
  assertion `app/src/test/kotlin/me/hletrd/telecampro/ui/review/MediaReviewNonTouchComposeTest.kt:47-91`;
  EN/KO labels `app/src/main/res/values/strings.xml:444-452` and
  `app/src/main/res/values-ko/strings.xml:427-435`.
- **Problem:** `ReviewStillGeometry.position` has CENTER, four cardinal, and four corner outcomes.
  Tests assert only CENTER and TOP_LEFT. They move away from the top-left bound but never re-run the
  position classifier on those results, and the Compose test checks only TOP_LEFT. The regenerated
  class report confirms five entirely missed source lines in `position`, corresponding to unvisited
  result branches. Thus the cycle-33 claim that coarse non-live position state is tested does not
  cover most states users can hear.
- **Concrete failure scenario:** A sign inversion or enum-label wiring error makes rightward or
  downward D-pad navigation announce “Image left/top,” or a bottom-right edge fall through to the
  wrong corner. Pointer behavior and the current CENTER/TOP_LEFT tests remain green, but TalkBack or
  Switch Access receives misleading spatial state.
- **Suggested fix:** Table-test all nine positions from representative bounded offsets, include the
  one-third transition boundaries, and assert the Compose `stateDescription` after navigation in
  all four directions. Keep image geometry RTL-independent while separately checking EN/KO labels.

### TEST34-03 — the newly added queue fixture emits an actionable Kotlin compiler warning

- **Severity / confidence:** Low / High
- **Classification:** Confirmed test-code warning; no production behavior failure.
- **Exact region:**
  `app/src/test/kotlin/me/hletrd/telecampro/ui/OwnerlessMediaDeleteLifecycleTest.kt:121-124`.
- **Problem:** Recompiling the full test source reports `Expression is unused` at
  `dispatcher.dispatch(Runnable { Unit })`. The lambda's `Unit` expression has no effect; an empty
  `Runnable {}` expresses the queue-filler intent without warning. This warning was hidden when the
  task was up-to-date and is not lint-enforced.
- **Concrete failure scenario:** Normal test edits repeatedly surface a known warning, reducing the
  signal of new compiler diagnostics and contradicting the repository rule to fix actionable
  warnings before delivery.
- **Suggested fix:** Replace the body with `Runnable {}` (or an explicit synchronization side effect
  if the test needs execution evidence), then force a full test recompilation and keep the compiler
  output warning-free.

## Final missed-file and issue sweep

I re-inventoried every production/test/tool/config path, checked every cycle-33 change against its
new regression tests, inspected all coverage buckets and generated class reports, reconciled the adb
case registry with its exact README table, and reran every non-device suite that could proceed after
the authoritative gate failure. No additional current test defect met the evidence threshold.
Instrumented tests are correctly described as compiled/packaged rather than executed, deliberate
device/human gaps remain explicit in `device-tests/README.md` and `docs/FIELD_CHECKS.md`, and no
ignored/flaky test annotation silently converts required evidence to green.

**Finding count: 3 total — 2 Medium, 1 Low; all High confidence.**
