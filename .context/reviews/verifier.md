# Verifier review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27` (`origin/main`)

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

Role: evidence-based behavioral correctness verification

## Coverage and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely before reviewing
implementation claims. I inventoried all 534 tracked paths: 103 production Kotlin/Java modules, 237
JVM/Robolectric/Compose tests, four instrumented tests, three debug hosts, Android resources and
manifests, build/release configuration, 22 host/device Python modules plus their tests, and the
committed documentation/review history. Tests, comments, completed plans, and green gates were
treated as claims to verify rather than proof.

The source-level pass covered Activity permission/input/lifecycle ownership; ViewModel state and
rollback; Engine/Controller optics and accepted-session generations; GL/EGL replay; processed/RAW
capture; recording admission/finalization; exact-family MediaStore publication/delete/recovery;
review work lanes; persistence; localization; and immutable debug/release tooling. The final sweep
specifically compared debug-only diagnostics with release control flow because cycle 48 changed that
boundary.

## Finding

### V49-01 — every ordinary Single still is rejected in release before Camera2 dispatch

- **Severity / confidence:** High / High
- **Classification:** Confirmed release-only source defect. No device behavior is needed to prove
  the branch; observing the user-facing failure on a packaged release remains device validation.

**Evidence**

1. `CameraState.kt:971-986` defines `captureFamilyTraceAdmission` independently of build type. It
   returns `registration=true, settlement=true` for every ordinary `DriveMode.SINGLE`, and
   `settlement=true` for every in-recording snapshot.
2. `CameraEngine.kt:4713-4725` constructs `traceText` only when `BuildConfig.DEBUG` is true. In a
   release build this value is therefore unconditionally null, even for an admitted trace.
3. `CameraEngine.kt:4727-4731` checks only `traceAdmission.registration`, then dereferences
   `traceText!!`. Release Single capture therefore throws before the callback is returned.
4. `CameraEngine.kt:4164-4178` catches that callback-construction exception, releases the processed
   snapshot lease, publishes `PHOTO_CAPTURE_FAILED`, returns false, and never calls
   `CameraController.capturePhoto`. The default release shutter cannot take a still.
5. The second dereference at `CameraEngine.kt:4737-4751` affects the in-REC Single path: settlement
   throws before `markCaptureProducersTerminal` and `familyProducerLease.close`, so a snapshot that
   reaches a save terminal can strand capture-family ownership and its continuation.
6. `CameraStateTest.kt:165-183` verifies only which trace flags are selected under the debug test
   variant. It never evaluates the consumer with release-equivalent `DEBUG=false`, which is why the
   completed cycle-48 release assembly and the authoritative host gate both remain green.

**Concrete scenario**

Install the release build, leave the default Photo drive at Single, and press the shutter with any
accepted processed/RAW format. `capturePhoto` asks for the ordinary Single trace, `photoCallback`
sets `traceText=null` because the build is release, the registration log expression throws on
`traceText!!`, and the app reports photo failure without submitting a Camera2 request. Burst, AEB,
and Timelapse avoid this exact registration branch, which can obscure the breadth of the default-path
regression during source inspection.

**Fix direction**

Make the diagnostic admission and its payload one nullable, build-aware value, or guard both log
branches with `BuildConfig.DEBUG`/`traceText != null`; no release path may dereference debug-only
state. Keep cleanup outside all diagnostic branches. Add a host-testable consumer seam taking an
explicit `debugBuild` flag and assert ordinary Single plus in-REC settlement under both true and
false, including that release creates the callback, dispatches capture, and always closes the family
producer. A release-variant smoke/Robolectric test should exercise the public shutter path so future
debug-only diagnostics cannot pass release assembly while breaking runtime capture.

## Verification evidence and limits

With the repository's complete SDK authority
(`/opt/homebrew/share/android-commandlinetools`), `python3 tools/verify_host.py` passed: Android
debug/androidTest assembly, all JVM/Robolectric/Compose tests, debug lint, Partition A 99.82%, 127
tooling tests, nine coverage-tool tests, 195 device-harness self-tests, 151 documentation checks
with 24 optional-private skips, Python compilation, and `git diff --check`. This green result is
important negative evidence: the configured gate has no release-runtime execution capable of
exposing V49-01. A direct release compile attempt was blocked by concurrent specialist review file
mutations in this shared isolated clone; compilation is unnecessary to establish Kotlin's explicit
null dereference and the standard release `BuildConfig.DEBUG=false` branch.

No camera device, physical converter, HDR display, microphone scene, or real MediaProvider consent
flow was used. Field checks A3/A4/A5/D1/E1/E2 remain open exactly as documented and were not inferred
from host success.

## Final missed-issue sweep

After confirming V49-01, I rechecked the rest of the cycle-48 change surface and adjacent production
consumers for build-type splits, stale optics rollback, failed-session hybrids, shader-interface
false greens, obscured-input cancellation, focus restoration, release-permission exceptions, PNG
validation, and documentation drift. No second independent current defect survived cross-file
validation. The broad `CameraEngine` decomposition remains previously recorded debt; this review
does not duplicate it.
