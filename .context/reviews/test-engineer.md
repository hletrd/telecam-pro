# Test-engineer review — cycle 49

Date: 2026-08-25
Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27`
Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## Scope and method

I read the clean-clone authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`), inventoried all 534 tracked paths, and reviewed the complete test/build
surface against the production ownership boundaries it claims to prove: 120 production files under
`app/src/main`, 238 host unit/Robolectric/Compose test files, four instrumented tests, and 74 files
under `tools/` and `device-tests/`. I traced the cycle-48 implementation and tests through the
video-pipeline rollback, obscured-input cancellation, modal focus, viewfinder keyboard, shader,
release-permission, PNG, localization, gallery-thumbnail, Gradle, coverage-partition, and
authoritative-host-gate paths. I also searched the whole test inventory for skips, assumptions,
early-success returns, reflection-only failure injection, duplicated production predicates, and
missing concurrency/failure-path assertions.

The full non-device gate passed when run with the documented complete SDK authority:

`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools python3 tools/verify_host.py`

It ran 2,098 JVM/Robolectric/Compose tests with no skipped cases, assembled the debug and
instrumented APKs, passed debug lint, measured Partition A at 8,283/8,298 lines (99.82%, with the
15-line residual manifest exact), passed all 127 tooling tests, nine coverage-tool tests, 195 device-
harness self-tests, 151 documentation checks (24 explicitly optional private-context skips), Python
compilation, and `git diff --check`. The host-only overall result was 17,746/28,028 lines (63.32%);
Partition B was 9,463/19,377 (48.84%), correctly reported as device-bound rather than presented as
host proof. No physical device, Camera2 HAL, MediaProvider, microphone, external keyboard, or HDR
display was exercised. The six open manual checks remain accurately listed as A3/A4/A5/D1/E1/E2.

## Findings

### C49-TEST-01 — the production obscuration test can pass when cancellation fails to terminate the pinch owner

- **Severity / confidence:** Medium / High
- **Status:** Confirmed false-positive test shape; current production code appears correct. No
  device behavior is inferred.
- **Exact regions:** `app/src/test/kotlin/me/hletrd/telecampro/MainActivityTouchDispatchTest.kt:194-249`
  sends a clean pinch, an obscured move, and a tainted up, but records only `pinchTicks` at the
  cancellation boundary. It waits until after a second clean pinch to assert merely
  `pinchEnds >= 1`. Therefore an implementation changed to invoke `onPinchEnd()` only for clean
  pointer-up (`if (zoomed && !cancelled)`) would pass: the cancelled pinch would remain unterminated,
  and the later clean pinch would supply the one counted end. The slider sibling at `:287-302`
  similarly snapshots only the emission count and proves the next drag works; it does not assert
  that the cancelled stream never lands the hostile move coordinate. Production's required
  terminal edge is at `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:824-865`, and the
  slider's cancel-sensitive landing is at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:554-573`.
- **Failure scenario:** an overlay arrives during a live pinch. The Activity rejects the marked
  event, but a future Compose-loop regression suppresses `onPinchEnd()` for `ACTION_CANCEL`. The
  ViewModel retains interaction/boost-tail ownership, so the next pinch begins against stale state.
  The existing test then runs a clean pinch, observes one terminal callback from that second
  gesture, and passes. In the slider variant, a cancel-coordinate regression can apply the obscured
  right-side value before the next clean drag and still satisfy every assertion.
- **Concrete fix:** assert the exact per-stream trace before starting recovery: cancelled tap emits
  no tap action; cancelled pinch emits its already-admitted zoom tick followed by exactly one
  `onPinchEnd`; the rejected tail emits nothing else; cancelled slider emits only the admitted DOWN
  value and never the obscured MOVE/terminal coordinate. Then clear or snapshot the trace and assert
  the next clean gesture contributes exactly one independent terminal edge. Mutation-test
  `onPinchEnd` gated on `!cancelled` and slider landing from the cancel position.

### C49-TEST-02 — the claimed post-rollback REC admission test duplicates the predicate without entering REC admission

- **Severity / confidence:** Medium / High
- **Status:** Confirmed coverage/evidence gap; no current runtime defect reproduced.
- **Exact regions:** the cycle-48 plan explicitly closes “subsequent REC candidate admission” at
  `docs/plans/2026-08-25-rpf-cycle48.md:31-33`. Its only new assertion is
  `app/src/test/kotlin/me/hletrd/telecampro/ui/ModeRollbackOwnershipRobolectricTest.kt:202-241`.
  After reflectively invoking private `rollbackOptics` (`:81-101`), the test reads the private
  `videoEncoderCandidates` list and itself calls `encoderSelectionAdmitsTransfer` at `:234-237`.
  It never calls `CameraEngine.startRecording`, reaches `beginRecordingAllocation`, or observes the
  production admission filter/status at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5037-5057`. The numerous
  `CameraEngineRecordingPreNativeTest` cases install `recordingPreNativeOverrides`; that production
  branch intentionally supplies an empty candidate list and bypasses this real filter at
  `CameraEngine.kt:5030-5035`, so they do not close the gap.
- **Failure scenario:** rollback restores the correct private HEVC/Main10 tuple, so the test's
  duplicated filter succeeds, but a later edit to the real REC admission branch filters against the
  wrong transfer/codec, reads a stale tuple, or returns `SELECTED_CODEC_UNAVAILABLE`. Every current
  rollback test remains green even though the first REC press after a rejected AVC/SDR transition
  is refused or chooses the wrong encoder ladder.
- **Concrete fix:** expose a narrow Android-free `RecordingAdmissionSnapshot` decision seam that
  consumes the accepted session, codec, transfer, frame-rate and ordered candidates, and have
  `beginRecordingAllocation` call it. Test the actual seam after a public ViewModel
  HEVC/HLG→AVC/SDR→owned-failure rollback, asserting the exact ordered Main10 candidates and
  no unavailable status. Alternatively inject only the provider/native tail while leaving the
  production candidate-admission branch live, then call `startRecording` and observe the captured
  allocation snapshot. Add the inverse SDR rollback and superseded-failure case.

## Final missed-issue sweep

I rechecked the recently changed tests against their corresponding production branches and the
cycle-48 completion claims. Shader compilation/linking plus production binding lookup failures are
covered; PNG validation reaches chunk framing, CRC, zlib/scanline structure and IEND; release
permission tests distinguish the exact signature guard from privacy permissions; modal entry and
exact Menu/Fn/Gallery return focus are exercised on production composition; viewfinder keyboard
actions and visible focus paint have production tests; ready-video thumbnail pixels, copy,
disabled state and EN/KO resources are covered. I found no skipped host test, stale open-field claim,
new untracked device claim, or additional source-confirmed product defect. Hardware-only behavior
remains manual/field validation rather than being mislabeled as host proof.

## Totals

- Findings: **2**
- Severity: **2 Medium**
- Confidence: **2 High**
- Confirmed product failures: **0**
- Confirmed test/evidence gaps: **2**
