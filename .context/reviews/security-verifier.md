# Security + verifier + test + documentation review — cycle 10

Date: 2026-08-23  
Reviewed HEAD: `a714d56` (`main`, equal to `origin/main` at review start)

## Scope and coverage

I read the project authorities in their required order: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, `.context/README.md`, then the testing, field-check, UX, privacy, Play,
release, and public README authorities. The cycle-9 aggregate and completed plan were checked first
so that closed historical findings were not re-filed without new current-source evidence.

The repository inventory contained 351 relevant files outside `.git`, Gradle/build output, and
generated caches. Every production Kotlin source, manifest/resource/configuration/build input,
host/instrumented/device-test source, Python/shell tool, and active documentation file was included
in the inventory and systematic searches. Deep traces covered permissions and exported components,
intent/URI boundaries, MediaStore publication/recovery/deletion, EXIF identity, Camera2 removable
routes, native codec/muxer/audio/EGL ownership, release signing/provenance/dependency verification,
device-evidence locking/attestation, secrets/network/backup posture, and the tests that claim those
contracts. Cross-file review concentrated on the 39 files changed since the cycle-9 review baseline
`a552d9f`, then swept the unchanged security-sensitive surfaces for missed interactions.

No release-manifest network path, committed secret, unprotected release component, backup exposure,
location/GPS lane, or direct path/command-injection defect was found. The release manifest removes
`INTERNET`/`ACCESS_NETWORK_STATE`, disables backup, and exports only the launcher; the debug snapshot
activity is release-absent and protected by signature-level `DUMP`. The app also installs the
view-level obscured-touch filter before presenting camera/settings/delete surfaces.

Verification performed during this review:

- `python3 -m unittest discover -s device-tests/tests -v`: **147/147 passed**.
- Focused Gradle tests for retained-still deletion, still publication durability, device EXIF
  labels, and route selection: **passed** (`:app:testDebugUnitTest`).
- The full configured host/release/device gates were not re-run by this specialist; cycle 9 records
  the last full green gate, and Prompt 3 must run the repository's authoritative gates again.

## Findings

### SECVER10-01 — A deleted still can lose its only tombstone after the Engine's last retry

- **Severity / confidence:** High / High
- **Classification:** Confirmed privacy/data-integrity defect; the failure branch needs a
  post-release producer barrier test.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:290-311` returns
    `UNRESOLVED` when both the durable `DISCARD` commit and immediate row deletion fail. The prior
    journal state remains `COMPLETE` in that case.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDeletionOwner.kt:46-49,161-196`
    keeps that outcome only in the Engine-local `unresolvedDiscards` map. It performs bounded inline
    attempts but owns no timer, process-global handoff, or separate durable family tombstone.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5746-5759` makes one final retry
    **before** calling `shutdown()` on `ioExecutor` and `mediaRecoveryExecutor`; it neither prevents
    already-accepted still jobs from completing afterward nor awaits those jobs before the retry.
    `ExecutorService.shutdown()` allows accepted/queued work to continue.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:518-539` treats a surviving
    `COMPLETE` row as valid and publishes it on launch recovery when no `DISCARD` marker exists.
  - `RetainedStillDeletionOwnerTest.kt:179-188,202-209,229-255` proves recovery only by explicitly
    calling `retryUnresolvedDiscards()`; no test lets a still producer reach `UNRESOLVED` after the
    release retry or kills/replaces the Engine while the state is memory-only.
- **Failure scenario:** the user deletes a capture while its late JPEG/HEIF/DNG save is queued. Engine
  release reaches its one retry before that save hands over the row. The late job then exhausts the
  `DISCARD` commit and provider delete attempts, adds the URI to an Engine that has already finished
  its last retry, and exits. On process death or replacement Engine startup, the row still says
  `COMPLETE`, so launch recovery adopts and publishes media the user explicitly deleted. The same
  resurrection is possible without the ordering race if the process dies after an ordinary
  `UNRESOLVED` result but before `release()`.
- **Suggested fix:** make deleted-family intent durable before acknowledging family deletion and
  have launch recovery veto every matching family output, or transfer unresolved outputs to a
  process-wide retry owner that remains open to late still completions and cannot finish before a
  durable `DISCARD`/authoritative absence. Do not rely on an Engine-local final sweep. Add a real
  Engine/pipeline barrier test for `release → late output → commit+delete failure → new Engine
  recovery`, plus abrupt-process-loss modeling at the unresolved edge.

### SECVER10-02 — Harness attestation has an import-time TOCTOU and silently excludes symlinked code

- **Severity / confidence:** High / High
- **Classification:** Confirmed evidence-integrity defect; exploitable by concurrent/local source
  mutation, not by the Android app.
- **Evidence:**
  - `device-tests/run.py:37-62` hashes the live harness tree, then `run.py:64-78` imports `dtest` and
    `cases` from that same mutable tree. There is no atomic read/snapshot binding the bytes hashed at
    line 62 to the bytes Python opens during those imports.
  - `device-tests/run.py:683-687,713-720` compares the filesystem only immediately before dispatch
    and after execution. A file changed after line 62, imported, then restored before line 683 passes
    both comparisons while different code is resident in `sys.modules`.
  - Both manifest walkers silently skip symlinks (`device-tests/run.py:40-42` and
    `device-tests/dtest/contracts.py:356-364`). Python can follow a symlinked `cases.py` or `dtest`
    module and execute bytes that are absent from the attested manifest.
  - `device-tests/tests/test_attestation.py:301-363` mutates a fixture only after `run` is already
    imported and after the expected manifest is captured. It tests the two later checkpoints, not
    the capture-to-import window or the symlink omission.
  - `device-tests/README.md:74-79` and `docs/plans/2026-08-23-rpf-cycle9.md:124-127` claim a green
    attestation names the bytes that registered/executed cases; the current ordering cannot prove
    that claim.
- **Failure scenario:** an editor/build process rewrites `cases.py` between the bootstrap walk and
  import, then restores it before pre-dispatch verification. The modified module registers and runs
  cases, while the final green attestation names the original bytes. A symlinked executable module
  bypasses the manifest without even needing the timing window.
- **Suggested fix:** copy the complete accepted harness into a private digest-qualified snapshot,
  reject every symlink/special file, import and run only from that snapshot, and attest that snapshot
  manifest. A single-process open/hash/import protocol is still race-prone. Add subprocess tests that
  pause between bootstrap and import and that replace an executable module with a symlink.

### SECVER10-03 — External-camera captures are stamped as if the host handset made them

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed metadata-correctness defect; external-device identity itself may
  require a deliberately conservative omission policy.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5895-5905,5937-5938` always builds
    `LensModel`, `Make`, and `Model` from host `Build.MANUFACTURER` / `Build.MODEL`. `ShotOptics`
    carries the new first-class `CameraRoute`, but `ExifShot` carries only the old `frontFacing`
    Boolean (`CameraEngine.kt:3900-3926`; `StillCapturePipeline.kt:35-59`).
  - For an EXTERNAL route `frontFacing=false`, so `DeviceExifLabels.kt:55-61,70-94` labels the USB/
    external optic as a host wide/tele camera and prefixes it with the handset identity.
  - `docs/ARCHITECTURE.md:49` says EXIF labels derive from the active enumerated camera and device
    identity and do not stamp one device's identity onto another. That is true for another handset's
    built-in camera but false for the newly supported external route.
  - `DeviceExifLabelsTest.kt:43-83` covers rear/front and blank host identity only; none of the new
    external-route tests reaches EXIF composition.
- **Failure scenario:** a PMA110 user connects a USB webcam and captures a still. The file records
  `Make=OPPO`, `Model=PMA110`, and a LensModel such as `OPPO PMA110 wide camera ...`, falsely
  identifying the host phone as the imaging device even though the first-class route exists
  precisely because the camera is external.
- **Suggested fix:** carry `CameraRoute` into shot/EXIF composition. For EXTERNAL, omit host Make/
  Model and the host prefix in LensModel unless Camera2 exposes trustworthy external-device identity;
  keep measured focal/aperture tokens only when they are advertised. Add JPEG/HEIF EXIF tests for
  BACK, FRONT, and EXTERNAL route matrices.

### SECVER10-04 — Removable-camera lifecycle claims are protected only by a pure set-diff test

- **Severity / confidence:** Medium / Medium
- **Classification:** Likely hot-plug correctness gap requiring an actual rapid reconnect or an
  injected CameraManager lifecycle test.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:951-960` coalesces all
    available/unavailable/removed events and returns early whenever the final `cameraIdList` equals
    `knownCameraIds`.
  - Cache invalidation and route convergence occur only after an ID-set difference
    (`CameraEngine.kt:878-893,924-931`). If remove+reconnect events coalesce before the setup task
    reads the list and the external provider reuses the same ID, the set is unchanged: old external
    selection/caps/EXIF caches survive and no generation-owned convergence runs.
  - The only lifecycle coverage is `CameraSelector2Test.kt:223-250`, which calls the pure
    `cameraRouteTopologyDecision` directly and models replacement as `usb-old` → `usb-new`. No test
    invokes the Engine callback, coalescer, cache invalidation, registration/unregistration, or
    reconfiguration ownership, despite the cycle-9 completion claim of attach/detach/replacement
    coverage.
- **Failure scenario:** a USB camera disconnects and reconnects quickly on the same provider ID
  before the queued refresh samples `cameraIdList`. The callback pair collapses to an unchanged set,
  so stale characteristics/session assumptions remain installed for a physically new camera and
  reopening may fail or publish wrong capabilities until process restart.
- **Suggested fix:** treat `onCameraRemoved` as an identity-invalidating topology epoch even when the
  later ID set matches, and carry the epoch through the serialized refresh; invalidate removable
  route caches and reconverge after a removed→available pair. Add an injectable manager/availability
  seam covering same-ID rapid reconnect, event coalescing, release during callback, and incomplete
  inventory retries. Device-validate on a real UVC camera if this route is release-supported.

### SECVER10-05 — Three release authorities point to a lint directory the immutable wrapper never copies

- **Severity / confidence:** Low / High
- **Classification:** Confirmed documentation/tool mismatch.
- **Evidence:** `README.md:132-139`, `docs/BACKLOG.md:1368-1379`, and
  `docs/play-console-submit.md:776` direct the operator to `$release_root/logs/` for lint output.
  `tools/build_immutable_release.py:174-177` copies only `snapshot/app/build/outputs` to the immutable
  root; Gradle lint reports live under `app/build/reports`, so no `logs/` member is exported.
- **Failure scenario:** a release gate passes or fails, but the operator follows all three
  authorities and cannot find the promised immutable lint evidence. This encourages consulting a
  mutable worktree report, undermining the otherwise careful release-evidence boundary.
- **Suggested fix:** either export the exact lint report directory into `$release_root/logs` and test
  its contents, or correct all three authorities to state that lint is an exit-status gate and its
  report is not preserved by the current wrapper. Add a wrapper output-layout contract test.

## Final missed-issue sweep

The final sweep rechecked every release/debug component, permission and privacy declaration,
MediaStore owner/path/name filter, external route branch, native-resource terminal owner, build
credential input, dependency-verification boundary, shell/subprocess call, report-path allocation,
and current test/plan claim. Historical accepted decisions (no network/telemetry/location,
contextual visual-media access, no CameraUnit/proprietary HDR, dark ZSL refusal, focus detector
conservatism) were not reopened. Findings above are current-source residuals: SECVER10-01 and -02
invalidate cycle-9 completion claims under precise failure interleavings, SECVER10-03 and -04 are
missed consequences of first-class EXTERNAL support, and SECVER10-05 is an active three-authority
release-tool mismatch.

**Finding count:** 5 total — 2 High, 2 Medium, 1 Low. No Critical finding.
