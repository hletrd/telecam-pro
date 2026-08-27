# Cycle 55 — code reviewer / architect / critic

Reviewed current HEAD `121fcdf09265262ea1c5d2710bddb61b12c3a38f` only. Historical review
notes and plans were checked for already-addressed context, but were not used as current finding
authority.

## Inventory and coverage

The cycle prompt's 453-file count is stale: cycle 54 added six current review-relevant paths. HEAD
contains 562 tracked paths; excluding 56 historical `.context/reviews/**` outputs and 47 completed
`docs/plans/**` records leaves **459 current review-relevant files** (443 text files / 147,637 lines
and 16 binary assets). All 459 were inventoried and read or validated. The current surface comprises
105 production Kotlin/Java files, 246 JVM/Robolectric/Compose tests, four `androidTest` sources, four
debug sources, 15 resources, 14 device-harness files, 25 tools, 11 build files, 27 current
documentation/legal/assets files, and eight other configuration files. The latest cycle-54 plan was
also read separately.

`CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` were read completely before review.
The final sweep traced every production package and its tests, with particular attention to the
cycle-54 DNG preallocation, standby-audio quarantine, diagnostic-budget, and immutable-review-source
changes; cross-file camera/session ownership; processed/RAW family settlement; provider allocation,
journaling, publication, rejection, deletion, and launch recovery; lifecycle cancellation; GL/video
native ownership; settings/UI admission; build/release tooling; manifests, privacy, and localization.
No file was sampled or skipped. Binary fonts, PNGs, and the Gradle wrapper were covered by the
repository's existing digest/format validators rather than treated as source text.

Verification run during this review: all 155 documentation checks passed (24 optional-private skips),
`git diff --check` passed, Python compilation passed, all 136 tool tests passed, and all 195 device-
harness self-tests passed. No device action was performed and no hardware behavior is claimed.

## Findings

### 1. Medium — a rapid second DNG press can leave the shutter disabled after the first shot finishes

- **Location:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4411-4415`,
  `:4902-4903`, `:5391-5397`, and `:5401-5411`.
- **Confidence:** High.
- **Status:** Confirmed code behavior; timing is easiest to reproduce by delaying the preallocation
  worker, but no device-specific behavior is required.
- **Failure scenario:** A RAW-only SINGLE shot acquires the process-wide
  `DngPreCaptureAdmission`. The UI is not told that admission became unavailable, and
  `stillOutputAdmissionAvailable()` does not include the DNG owner. A second shutter press while the
  first allocation/capture is active therefore passes the initial availability check, loses
  `tryAcquire()`, and publishes `onStillCaptureAdmissionChanged(false)` at lines 4411-4415. When the
  first shot later succeeds, its callback releases `dngPreCaptureLease` at line 5394 but never
  republishes availability. The error terminal has the same missing publication after line 5407.
  `CameraUiState.stillCaptureAdmissionAvailable` consequently stays false and the primary shutter
  remains disabled even though the global DNG lease is free. Cancellation before Camera2 happens to
  republish at lines 4439-4449, so tests of cancellation do not cover the successful claimed path.
- **Suggested fix:** Make the DNG singleton part of the authoritative still-admission projection (or
  publish a false edge immediately after acquiring it), and publish the recomputed availability in
  one exactly-once DNG terminal helper after every lease release: successful photo callback, capture
  error, synchronous callback failure, and pre-camera retirement. Add an Engine/ViewModel integration
  test that blocks allocation, issues a second press, completes the first shot, and proves the shutter
  returns to enabled without an unrelated recording/recovery event.

### 2. Medium — post-allocation Camera2 failures retain empty DNG rows until process restart

- **Location:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4417-4423`,
  `:4466-4474`, `:5285-5386`, and `:5401-5411`; allocation durability originates at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:390-421`.
- **Confidence:** High.
- **Status:** Confirmed code behavior; an actual Camera2 failure after allocation is a likely runtime
  path and is already modeled by the controller's not-ready, pending, watchdog, and capture-failure
  callbacks.
- **Failure scenario:** Before Camera2 dispatch, the Engine reserves rejected-output cleanup and
  creates a durably `REGISTERED`, exact-identity pending DNG row. If Camera2 then reports an error
  (session closes, capture is refused/failed, watchdog fires) no DNG byte write occurs. Nevertheless
  `photoCallback.onError` cancels the cleanup reservation at line 5403 instead of submitting the
  preallocated row; the defensive `raw == null` branch does the same at lines 5383-5386. The shot
  producer settles and the DNG admission reopens, but the empty `IS_PENDING=1` row remains for launch
  recovery. Because launch recovery is single-flight at startup, repeated capture failures during
  one process can create arbitrarily many private rows and journal entries with no current-process
  cleanup or admission backpressure.
- **Suggested fix:** Route every terminal that did not produce a complete DNG through the already-
  reserved rejected-output cleanup using the frozen `PendingOutputAllocation`; cancel the reservation
  only after complete-byte ownership transfers to publication/recovery. Centralize this in an
  exactly-once DNG terminal owner so `onPhoto`, `onError`, synchronous callback throws, and unexpected
  no-RAW delivery cannot double-submit or leak. Test controller refusal, watchdog/error, raw-null, and
  cleanup saturation, proving the camera callback remains provider-free while a finite process lane
  owns every exact row.

### 3. Medium — identity-freeze failure drops a durably registered image/video row from live ownership

- **Location:** `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:390-453`,
  especially `:420-421` and `:448-453`; identity failure is returned by
  `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:32-41`.
- **Confidence:** High.
- **Status:** Confirmed code behavior; the trigger is a transient/ambiguous MediaProvider identity
  read after a successful insert and durable REGISTERED commit.
- **Failure scenario:** `createPendingImageAllocation` and `createPendingVideoAllocation` first insert
  an `IS_PENDING=1` row and durably register its URI. They then call `captureAllocation`. If that
  identity query is unavailable, ambiguous, or does not yet expose the expected family, the factory
  returns null and discards the URI/REGISTERED result without scheduling any live recovery. DNG and
  REC callers see a generic allocation failure and release their admission, so the user can retry;
  every retry can add another pending row. The durable preference entry makes eventual next-launch
  recovery possible, but the already-running launch recovery cannot see these later rows, and the
  pending journal has no capacity bound. A provider outage can therefore grow both hidden MediaStore
  rows and recovery work without limit during one process.
- **Suggested fix:** Return a typed allocation outcome that retains the registered URI when exact
  identity capture fails. Submit it to a finite process recovery/rescan owner (non-destructive until
  identity is authoritative), and keep output admission fail-closed at that owner's bounded capacity;
  do not collapse `REGISTERED_WITHOUT_IDENTITY` into null. Apply the same contract to image and video
  factories. Add factory-level tests where insert and REGISTERED commit succeed but identity reads
  return unavailable/ambiguous/mismatched, including repeated retries and later recovery, and prove
  that every row remains bounded and process-owned.

## Final missed-issue sweep

The final pass rechecked all current production modules, tests, debug and instrumented sources,
device harness, tools, manifests, resources, build/release evidence boundaries, privacy/localization,
all TODO/error/exception and native-resource terminals, every process-finite executor, and the open
field-check ledger. No additional substantive current code/architecture finding survived cross-file
validation. Open field checks A3/A4/A5/D1/E1/E2/E3 remain explicit manual-evidence gaps, not code
defects.
