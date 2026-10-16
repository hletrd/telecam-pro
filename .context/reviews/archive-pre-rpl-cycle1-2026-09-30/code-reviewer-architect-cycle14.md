# Code reviewer + architect — cycle 14

Reviewed HEAD: `fbe31d6`
Mode: read-only host review; no deployment, ADB, device work, source edits, plan edits, or git mutation

## Scope and inventory

I read the current authorities in the required order: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, and `README.md`, then the applicable testing/UX/field policy and the cycle-13
aggregate/plan. Historical reviews and completed plans were used only to detect already-tracked or
already-fixed items; every retained conclusion was rechecked against current source and tests.

The repository inventory contained 343 review-relevant paths after excluding generated build output,
the large Play bitmap set, and archived binary device dumps. The code/architecture pass covered:

- all 86 production Kotlin files and their package interactions: Activity/permission and hardware
  input, `CameraViewModel`/actions/state, Camera2 selection/session/request/capture, Engine routing and
  generation ownership, GL/EGL renderer ownership, still/RAW/HEIF/JPEG pipelines, video/audio/codec
  ownership, MediaStore recovery/deletion, settings, focus/stabilization, and Compose controls/review;
- manifests, resources, Gradle/build/release configuration, R8/baseline profile, debug-only UI and
  instrumentation seams;
- the complete host/instrumented test inventory (176 JVM/Robolectric/Compose paths and four Android
  instrumentation paths), focusing especially on cross-thread ownership, teardown, late callbacks,
  storage durability, recorder admission, route transactions, and architecture-contract tests;
- all top-level Python/shell build, verification, immutable-source, release-artifact, and device-test
  tooling at the module/authority boundary, plus current documentation claims that describe those
  components.

I traced the current dependency directions and the high-risk end-to-end flows rather than treating
files independently: UI -> ViewModel -> Engine -> Camera2/GL/capture/video/storage; still-family
publish/delete/recovery; REC pre-native -> native admission -> EGL attach -> teardown/storage tail;
preview Surface/lifecycle replay; route/capability normalization; and immutable build/evidence
publication. I also reviewed the cycle-13 changes and their focused tests, then performed final sweeps
for executor rejection, callback teardown, unchecked native/resource ownership, stale-generation
publication, package cycles, model-string seams, and documentation/source drift.

## New finding

### CODEARCH14-01 — `detachCallbacks()` is exhaustive but is not a teardown barrier

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed lifecycle/concurrency defect. The exact user-visible timing needs a
  deterministic lifecycle test, but the check/use race and rejected submission are present in current
  source.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4123-4137` gives the long-lived
    still pipeline lambdas direct safe-call reads of the mutable Engine callback fields, including
    `onMediaSaved` and `onRawSaved`. A worker may fetch a non-null function immediately before
    teardown clears the field and invoke that already-fetched function afterward.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:6171-6199` clears every current
    callback field one by one, but has no generation/lease, lock, in-flight count, or terminal callback
    dispatcher. It therefore prevents future reads, not invocations whose field read already won.
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3395-3429` routes a late output for
    an already-deleted capture to `deleteLateCaptureOutput`, whose `ioExecutor.execute` is the one
    ViewModel I/O submission in this area not protected against rejection.
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3586-3607` sets `cleared`, purges the
    main Handler, detaches callback fields, starts Engine release asynchronously, and immediately
    shuts down the ViewModel executor. Contrary to the nearby ownership comment, it does not wait for
    Engine release or for callback invocations that already acquired their function object.
  - `app/src/test/kotlin/me/hletrd/telecampro/ui/CameraViewModelRobolectricTest.kt:100-103,180-195`
    usefully enumerates every callback and proves all fields are null *after* `onCleared`, but does not
    acquire a callback before teardown and invoke it after executor shutdown. Thus the test proves
    list completeness, not the required happens-before boundary.
- **Concrete failure scenario:** a multi-output still has already surfaced one sibling. A second
  processed/RAW publication reaches `emitSaved` and fetches the ViewModel callback, then is descheduled.
  The operator deletes the visible family and the Activity/ViewModel is destroyed. `onCleared()` clears
  the field and shuts down `vm-io`; the already-fetched callback resumes, the capture tracker correctly
  returns `DELETE`, and `deleteLateCaptureOutput()` throws `RejectedExecutionException`. That exception
  escapes the saved callback and the Engine still-save worker. Other already-fetched callbacks can post
  work after `removeCallbacksAndMessages(null)` or mutate the cleared ViewModel's StateFlow, extending
  the same ownership violation even when they do not hit the executor.
- **Suggested fix:** replace the set of independently mutable callbacks with one close-aware callback
  sink/dispatcher that is atomically retired and either (a) generation-refuses every invocation after
  close or (b) drains already-admitted invocations before their dependent executor is shut down. Keep
  late deleted-output disposal Engine-owned as it is now. Independently make every remaining ViewModel
  executor submission rejection-safe so teardown cannot throw. Add a forced-interleaving test that
  acquires a saved-output callback, runs `onCleared`, then releases the callback and proves no rejected
  submission, post-purge Handler work, or cleared-state mutation occurs.
- **Historical distinction:** cycle 8's `SECDBG8-1` found callback fields omitted from
  `detachCallbacks`; the current reflection test and current clear list have fixed that omission. This
  finding is the residual atomicity problem after the list became exhaustive, not a re-count of the
  closed missing-field item.

## Already tracked / not new

- The bidirectional package coupling around `camera` and the size of `CameraEngine.kt` (7,189 lines),
  `CameraViewModel.kt` (3,839), and `CameraScreen.kt` (3,130) remain real maintainability pressure, but
  are explicitly recorded in `docs/BACKLOG.md` D-4 and prior completed plans. I did not re-count that
  known structural debt as a cycle-14 finding.
- The open PMA110/device checks, ZSL memory/thermal measurements, focus-confidence limitation,
  proprietary HDR/CameraUnit decisions, wide finder design item, and production-owner/manual Play
  steps are explicitly accepted, deferred, or owner-gated in `docs/BACKLOG.md`; none was reframed as a
  code defect.
- Cycle-13's native-acquisition replay, standby AudioRecord stop owner, lifecycle info coalescer, and
  immutable evidence changes are represented in current source and focused tests. No second confirmed
  code/architecture defect survived causal validation in those changes.

## Final missed-issue sweep

The final sweep rechecked every production file against the inventory and revisited lifecycle doors,
executor shutdown/rejection, Engine replacement, recorder/standby process tokens, GL owner replacement,
Camera2 callback admission, still-family deletion/publication, persisted route inputs, route-scale
normalization, and docs/source authority. Candidate observations that reduced to measured HAL facts,
owner decisions, deliberately conservative behavior, already-tracked debt, or tests that accurately
pin the current contract were discarded rather than inflated into findings.

**New finding count: 1.**
