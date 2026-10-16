# Verifier review — cycle 50

Date: 2026-08-25

Verified revision: `2388819d` (`origin/main`)

Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

Role: verifier; independent contract tracing and focused read-only execution; no implementation or commit

## Inventory before verification

Before testing claims, I enumerated all 535 tracked paths: 103 production Kotlin/Java files, 15 main
resources, eight debug/instrumentation sources, 238 JVM/Robolectric/Compose tests, 25 host/release
tools, 14 device-harness files, 64 documentation/assets paths, 44 prior-review records, and 24
build/root/remaining paths. Binary fonts, images, and wrapper JAR were treated as artifacts rather
than executable source; all executable/configuration owners and their tests were included.

The complete Cycle 49 behavior surface comprised the four changed production files
(`CameraEngine.kt`, `CameraState.kt`, `CameraScreen.kt`, `MediaReview.kt`), the changed PNG/docs
tooling and six focused test files. I verified their interactions with `CameraViewModel`,
`MainActivity`/`CameraActions`, optics rollback/Ready publication, `EncoderCaps` and
`VideoRecorder`, capture-family producer leases, Compose modal focus, screenshot manifests, and the
committed current documentation. Historical review/plan files were checked for provenance and false
assurance but were not treated as runtime authority.

## Verification result and finding

### C50-V-01 — PNG validity claim is disproved by a production-predicate mutation

- **Severity / confidence:** Low / High
- **Classification:** Confirmed validation gap; current assets remain valid under the checks run.
- **Exact region:** `tools/check_docs.py:111-185`; missing regression dimension in
  `tools/tests/test_tool_contracts.py:1270-1309`.
- **Independent reproduction:** Starting with
  `docs/assets/play/screenshots/01-main-viewfinder.png`, I inserted a CRC-correct truecolor `tRNS`
  chunk after the final IDAT and before IEND, without changing the raster or IHDR. Calling the exact
  checked-in `png_metadata()` implementation returned `(1440, 2880, 8, 2)`. A post-IDAT `tRNS` is
  not legal PNG ordering, so the predicate's “fully validate” contract is false even though its
  PLTE, CRC, IEND, decompression-bound, raster-size, and filter checks pass.
- **Concrete failure scenario:** A newly exported screenshot carries invalid late transparency or
  color metadata; its validity-manifest hash is intentionally refreshed. The release docs gate goes
  green despite a specification-invalid file, leaving decoder/Play acceptance to fail later.
- **Suggested fix:** Decode to completion with a maintained deterministic decoder and retain the
  current explicit geometry/mode assertions, or complete the parser's ancillary grammar/order/value
  tables. Mutation-test post-IDAT `tRNS`, bad/late `iCCP`/`sRGB`, and invalid chunk type grammar.

## Independently verified behavior

- **Pipeline/rollback:** `setVideoPipeline` holds the Engine monitor while reading mode/transfer,
  deriving the ten-bit boundary, snapshotting the rollback baseline, and publishing the packet.
  A queued command after owned Photo/SDR rollback retains Photo active SDR and only the requested
  next-Video HLG value. REC admission consumes the production `recordingEncoderAdmission` filter.
- **Capture traces:** Release admission yields neither trace edge, so payload construction/logging is
  unreachable while registration, completion, producer terminal publication, lease close, and
  deleted-family retirement remain unconditional.
- **Keyboard:** Initial Enter/Numpad Enter/Space/DPAD-center key-down invokes center focus once;
  repeat downs do not invoke it; a fresh zero-repeat press invokes it again.
- **Review focus:** Delete-dialog Back and Cancel remove the dialog and restore focus to the exact
  Delete control. Outside dismissal uses the same production callback; confirm intentionally closes
  review through the caller's deletion flow.
- **Obscured gestures:** The production activity delivers one clean cancel terminal, taints the
  remaining hostile stream, and a subsequent clean tap/pinch/slider stream is admitted.
- **AppOps docs:** The submission matrix now labels the silent behavior historical and points to the
  current AppOps-confirmed blocked-camera surface.

## Execution evidence and limits

- Focused Gradle suite for the five changed runtime/test owners: **BUILD SUCCESSFUL**.
- Tool contract suite: **55 tests, OK**.
- Documentation gate: **152 checks, 0 failed, 24 optional-private skips**.
- `git diff --check`: passed.
- The first Gradle attempt correctly failed because no SDK authority was exported; rerunning with
  `/opt/homebrew/share/android-commandlinetools` as both Android SDK variables passed. This is an
  environment setup observation, not a repository defect.
- Final missed-issue sweep rechecked rollback supersession, session identity, diagnostics-independent
  settlement, REC candidate filtering, focus lifecycle, key repeat edges, obscured cancel edges,
  localization/docs drift, and field-evidence boundaries. No additional confirmed runtime issue
  survived.
- A host review cannot validate current Camera2/HAL pixels, physical converter orientation, HDR
  appearance, acoustic Sound Focus, or MediaProvider consent semantics. A3/A4/A5/D1/E1/E2 remain
  explicitly open manual checks.

---

# Verifier review — cycle 51 (current)

Date/identity: 2026-08-25, `7eb4ee95`, isolated clone `/tmp/find-x9-ultra-cycle51.WTu2dW`; no device/deploy/source implementation.

## Complete inventory

Exhaustive `rg --files` inventory: 634 non-build files. Verified all 120 main files, 240 JVM/Robolectric/Compose test files, 4 androidTests, 4 debug-host files, 47 tooling files, 27 device-harness files, 65 docs/assets, and root build/legal/privacy authorities. Cross-checked each production package against tests, architecture, field ledger, resources, and release/tool contracts. Visually inspected every committed phone/tablet/Play bitmap.

## Evidence

- `python3 tools/check_docs.py`: 153 passed, 0 failed, 24 declared optional-private skips.
- coverage-tool tests: 9/9 passed; device-harness self-tests: 195/195 passed.
- tool tests: 125 passed, 7 errored solely at the explicit Android SDK preflight because Emulator `glslangValidator` is absent; `verify_host.py` stops at the same boundary.
- Gradle ran 2,112 tests and failed one race-sensitive assertion described below. The exact test passed 5/5 alone, confirming order sensitivity rather than a stable functional assertion.

## Findings

1. **C51-CV-01 — Medium / High / confirmed source-path.** `CameraEngine.rollbackOptics` preserves a newer `restoredVideoPipeline` on generation mismatch, then calls `gl.setTransfer(before.transfer)`. A failed Video/HLG→Photo transition with a newer AVC/SDR command can return UI/Engine/REC to AVC/SDR while GL is HLG. Fix the call to use the restored packet's active transfer and test the newer-before-rollback ordering.
2. **C51-CV-02 — Medium / High / confirmed.** `FamilyDeletionMarkerIntegrationRobolectricTest.kt:167-176` counts down inside the task but asserts semaphore release that occurs only after the task returns. Full-suite evidence produced `expected 0 but was 1`; use a bounded eventual post-release assertion/signal.
3. **C51-CV-03 — Low / High / confirmed.** Loupe comments in `FlipRenderer.kt` and `GlPipeline.kt` claim an upright same-stream overview although authoritative docs and runtime intentionally show raw inverted orientation; pipeline docs also omit conditional supersession. Correct comments/docs and harden the docs gate.

## Limit

Open A3/A4/A5/D1/E1/E2 checks remain manual. No host result was represented as device, optical, acoustic, HDR-display, or real-MediaProvider evidence. New findings: **3**.
