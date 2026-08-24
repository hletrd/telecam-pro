# Cycle 49 debugger review

Date: 2026-08-25
Reviewed revision: `69c9c64a` (`origin/main` at review start)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## Coverage and method

I read the complete committed authority (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`), inventoried all 534 tracked files, and traced failure and ownership paths
across Camera2 session generations, optics transactions, pseudo-ZSL, still-family registration and
publication, GL/EGL, MediaCodec/MediaMuxer/AudioRecord, review/delete/recovery, Activity/ViewModel
lifecycle, finite executors, release tooling, and the entire cycle-48 implementation delta. Tests,
comments, plans, and historical reviews were checked against production behavior rather than
treated as proof. A final sweep covered stale callbacks, release/debug variant differences,
exception cleanup, resource leases, state divergence, timeouts, bounds, and false-green tests.

## Finding

### DBG49-01 — every default release Single photo fails before Camera2 dispatch and leaks its family producer lease

- **Severity / confidence:** High / High.
- **Classification:** Confirmed source-level release regression. Observing the UI/file symptom on a
  signed device build is manual validation, but the variant-dependent null dereference and missing
  cleanup are deterministic.
- **Evidence:** `CameraState.kt:971-986` admits registration and settlement traces for an ordinary
  `DriveMode.SINGLE`, independent of build type. `CameraEngine.kt:4710-4726` first calls `shotSpec`,
  which registers the capture family and returns its process-wide producer lease, then makes
  `traceText` non-null only when `BuildConfig.DEBUG` is true. The actual branches at
  `CameraEngine.kt:4727-4743` test only `traceAdmission.registration/settlement` and dereference
  `traceText!!`. In release, `BuildConfig.DEBUG == false`, so the default Single path necessarily
  dereferences null at line 4730. `capturePhoto` catches callback construction at lines 4164-4178,
  releases only the processed-snapshot budget lease, reports `PHOTO_CAPTURE_FAILED`, and never sees
  or closes `registeredShot.producerLease`; the family registration has already occurred. No
  Camera2 request reaches lines 4180-4194. For an in-REC snapshot, registration is false but
  settlement is true, so the equivalent null dereference occurs at line 4742 after save completion,
  before terminal ownership, producer-lease close, deleted-family retirement, and `onDone`.
- **Concrete scenario:** Install the Play/release APK, leave the default drive on Single, and press
  the photo shutter. Callback construction creates a capture-family identity and producer lease,
  then throws before `CameraController.capturePhoto`; the user gets “Photo capture failed” and no
  file. Repeated presses repeat the leak. A still snapshot during an SDR video can save its bytes
  but fail terminal family cleanup at settlement. Burst/AEB/timelapse avoid the trace admission,
  which can obscure the variant-specific defect during broader testing.
- **Test gap:** `CameraStateTest` verifies only the build-independent admission values, while all
  runnable JVM/Robolectric camera tests use the debug variant where `traceText` is populated.
  `assembleRelease`/`bundleRelease` compile this branch but do not execute a release Single shutter,
  so both authoritative host gates can remain green.
- **Suggested fix:** Couple admission and payload in one nullable trace packet, or guard both log
  sites with `BuildConfig.DEBUG && admission` / `traceText != null`. More defensively, transfer the
  registered producer lease into a close-on-failure owner immediately after `shotSpec` so any later
  callback-construction exception seals the local family and closes process authority. Add a
  release-variant test that invokes ordinary Single callback construction with DEBUG false and
  asserts successful Camera2 dispatch plus exactly-once producer cleanup; cover the in-REC
  settlement edge separately.

## Additional confirmed tooling edge

The security report records one distinct Low/High debugger-relevant issue in
`tools/check_docs.py:111-196`: the PNG validator accepts illegal post-IDAT `PLTE` ordering and raises
an uncaught `ValueError` for an exactly-one-byte-overlong decoded raster. It is not duplicated here
as a second debugger finding.

## Final missed-issue sweep and limitations

The final sweep rechecked all cycle-48 changes (video-pipeline rollback, obscured cancel, shader
binding checks, modal/viewfinder focus, trace admission, packaged permissions, and PNG validation),
then revisited still/video terminal ownership, stale generations, delete/recovery consistency,
Camera2/GL/codec/audio exception paths, bounded workers, timers, lifecycle transitions, and
release/debug divergence. No further current debugger finding survived validation. No physical
device behavior or open field check was inferred.

**New debugger finding count: 1 — High severity, High confidence, confirmed.**
