# Cycle 32 test-engineer review

Date: 2026-08-24
Reviewed revision: `64eff08e22f856b42f70be7f2a63581c30e265a9`
Workspace: isolated clean clone `/private/tmp/find-x9-rpf32.SEkU6E/repo`
Mode: host-only test review; no deployment or physical-device claim

## Inventory and method

I read the repository authorities first (`CLAUDE.md`, all of `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`), then inventoried all 440 tracked paths. The review surface comprised 98
production Kotlin files (51,718 lines) across Activity/Application, Camera2/session ownership,
capture/storage, GL, focus/stabilization, ViewModel/Compose UI, and audio/video; 201 Kotlin unit,
Robolectric, and Compose test files plus their test resource (39,650 lines); four Android
instrumentation files (547 lines); the 13-file Python device harness and its README (14,246 Python
lines); 17 Python build/release/coverage tools and their fixtures (7,163 lines); Gradle/manifest/
resource configuration; the English/Korean resource trees; and current architecture, field,
release, Play, privacy, plan, and prior-review documentation.

I mapped production owners to their host, Robolectric/Compose, instrumentation, external-device,
and human-field evidence; inspected every test for skipped/diagnostic-only behavior, sleeps/polling,
weak state-only assertions, coverage exclusions, and framework-fidelity limits; traced the cycle-31
production fixes through their new regression tests; and performed a final sweep over lifecycle,
native ownership, capture durability, recording races, visual accessibility, localization, tooling,
and harness attestation. The existing Partition-A manifest claims 7,558/7,571 executable lines, but
the report XML is not present in this clean clone, so that number was treated as repository history,
not re-proven evidence.

A focused Gradle invocation was attempted for the affected Robolectric/Compose classes. It stopped
at configuration because this isolated clone has neither `ANDROID_HOME`/`ANDROID_SDK_ROOT` nor a
`local.properties` SDK path; no test executed and no source file changed. The findings below are
therefore source-proven test weaknesses, not test-run failures.

## Findings

### TEST32-01 — recreate smoke can pass on a stale retained Ready bit without proving a new preview

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed false-positive test gap; current production lifecycle code appears
  correct; physical-device validation was not run.
- **Exact region:** `app/src/androidTest/kotlin/me/hletrd/telecampro/MainActivitySmokeTest.kt:63-75`
  and `:118-131`; production contract in
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1567-1585` and `:1683-1738`.
- **Problem:** `recreateCycleReturnsToReady` waits for Ready before `scenario.recreate()`, then checks
  only that the retained ViewModel's Boolean is true afterward. `awaitCameraReady` returns
  immediately on an already-true value. The test never observes the required Not-Ready transition,
  a newer Ready publication, the replacement TextureView generation, or a producer-fed frame on
  that replacement. Its comment claims all four facts, but none is asserted.
- **Concrete failure scenario:** A regression drops `onStop -> engine.pause`, fails to invalidate
  Ready on surface destruction, or leaves a dead old GL owner marked Ready. The retained ViewModel
  keeps `cameraReady=true`; recreation produces a black replacement TextureView with stale shutter
  enablement, while this test passes immediately. This is the same failure class the production
  comment says was previously device-reproduced after configuration change.
- **Suggested fix:** Start a bounded StateFlow/publication observer before recreation and require an
  ordered `ready=true -> ready=false -> ready=true` transition, with the terminal Ready carrying a
  strictly newer publication/surface generation. Add a real-frame oracle for the replacement
  surface (frame-change or the existing first-producer-swap diagnostic) so stale state alone cannot
  satisfy the test.

### TEST32-02 — focal-rail regression test does not test the modifier order it was added to protect

- **Severity / confidence:** Low / High
- **Classification:** Confirmed false-positive visual regression test; current production modifier
  order is correct.
- **Exact region:** `app/src/test/kotlin/me/hletrd/telecampro/ui/FocalRailOverflowComposeTest.kt:31-62`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2669-2676`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:244-279`.
- **Problem:** The cycle-31 bug was specifically the relative order of
  `trailingEdgeFadeScrollHint` and `horizontalScroll`: reversing them moves the fade with content
  instead of anchoring it to viewport coordinates. The new test asserts only overflow range and the
  test-only `TrailingEdgeFadeVisible = scrollState.canScrollForward` semantics value. That value is
  identical for both modifier orders, so reverting the two production lines leaves every assertion
  green.
- **Concrete failure scenario:** A cleanup restores
  `.horizontalScroll(...).trailingEdgeFadeScrollHint(...)`. The trailing chip again hard-clips instead of fading at the
  viewport edge, yet the fixture still overflows, `canScrollForward` still flips at the end, and the
  purported regression passes.
- **Suggested fix:** Assert rendered output, not the mirrored condition: capture the constrained row
  at two nonterminal offsets and prove the fade pixels stay in a fixed trailing-edge band, then prove
  they disappear at the end. If reliable bitmap capture is unavailable on Robolectric, extract a
  small viewport/content coordinate transform seam and test the actual mask coordinates while
  retaining one device/snapshot check for draw fidelity.

### TEST32-03 — autofocus accessibility tests can pass after the check/cross is no longer drawn

- **Severity / confidence:** Low / High
- **Classification:** Confirmed false-positive accessibility presentation test; current production
  draw block appears correct.
- **Exact region:**
  `app/src/test/kotlin/me/hletrd/telecampro/ui/overlays/FocusReticlePresentationTest.kt:8-24`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:326-337` and `:339-417`.
- **Problem:** The tests verify only `AfIndication -> FocusReticleCue` enum mapping. The non-color
  channel actually exists in a separate Canvas branch at lines 395-416. Removing that branch,
  making its coordinates zero-length/off-canvas, or drawing foreground and keyline with an
  invisible width leaves the pure mapping tests green. Thus the tests do not protect the user-facing
  check-versus-cross geometry or the claimed arbitrary-background keyline.
- **Concrete failure scenario:** A draw refactor keeps `focusReticleCue(FOCUSED)=CHECK` and
  `focusReticleCue(FAILED)=CROSS` for coverage but omits the terminal-glyph drawing. Focused and
  failed return to identical bracket geometry distinguished only by green/red, recreating the
  color-vision accessibility defect while both tests pass.
- **Suggested fix:** Extract cue line segments and widths into a pure geometry function consumed by
  the Canvas, then assert distinct nonempty CHECK/CROSS segment sets, in-bounds coordinates, and
  positive keyline/ink widths. Add a Compose image assertion over bright and dark backgrounds to
  verify both the center glyph and black outline are actually rendered.

### TEST32-04 — ViewModel review-defense test omits the starting-only state

- **Severity / confidence:** Low / High
- **Classification:** Confirmed missing truth-table case; current production guard is correct.
- **Exact region:**
  `app/src/test/kotlin/me/hletrd/telecampro/ui/CameraViewModelRobolectricTest.kt:597-613`; production boundary at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3307-3320` and policy at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:68-78`.
- **Problem:** The test destructures `(recording, starting)` from `listOf(true to true, true to
  false)`, covering active recording and both-true, but never the ordinary admission state
  `recording=false, starting=true`. The Compose policy test covers that state for the disabled
  thumbnail, but this Robolectric test is the only direct regression for the separate defensive
  `onReviewOpenChange` boundary used by non-Compose callers.
- **Concrete failure scenario:** A future refactor leaves the Compose thumbnail gated by
  `reviewTargetEnabled` but simplifies the ViewModel defense to `if (isRecording)`. During codec/
  microphone admission, a hardware or stale non-Compose caller can open opaque review before REC
  publishes, removing the only visible Stop control; the direct ViewModel suite remains green.
- **Suggested fix:** Table-test all four `(recordingStarting, recording)` combinations at the
  ViewModel boundary, asserting refusal/no modal ownership/status for either true axis and the
  existing idle pin behavior only for both false.

## Final missed-issue and file sweep

I re-swept every production package against its unit/Compose/instrumentation/device evidence,
reviewed all diagnostic probes that intentionally never fail, checked polling and synchronization
tests for false scheduling inference, compared the external harness case matrix with
`FIELD_CHECKS.md`, and revisited every cycle-31 implementation/test pair. No additional current-HEAD
test weakness met the evidence threshold. In particular, the new preview-bind test does exercise
both pre/post-gate ownership checks, the toggle-row tests inspect actual child bounds and activation,
and the deletion/retirement tests distinguish provider truth from durable retry ownership.

**Finding count: 4 total — 1 Medium, 3 Low.**
