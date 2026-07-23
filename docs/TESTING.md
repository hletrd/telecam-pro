# TESTING.md — test surfaces, coverage partition, and the honest-number contract

This document defines how this repo measures test coverage and what its coverage claims mean.
It exists because a single-device Camera2/GL/MediaCodec app has a large body of code that
**cannot execute on a host JVM** (android.jar unit-test stubs throw), and quoting one blended
number would misrepresent both halves.

## Test surfaces

| surface | where | runs on | gate |
|---|---|---|---|
| Host unit tests (JUnit4) | `app/src/test` | host JVM | `./gradlew :app:testDebugUnitTest` |
| On-device functional suite | `device-tests/` | PMA110 over adb | `python3 device-tests/run.py …` (see its README) |
| Lint | — | host | `./gradlew :app:lintDebug` |

Host unit-test line coverage is JaCoCo via AGP (`enableUnitTestCoverage = true`, debug build type):

```bash
./gradlew :app:createDebugUnitTestCoverageReport
# XML: app/build/reports/coverage/test/debug/report.xml
python3 tools/coverage/partition_report.py app/build/reports/coverage/test/debug/report.xml
```

## The coverage partition

Every JaCoCo class (and, for a handful of mixed classes, every method) is assigned to exactly one
bucket by the committed filters in `tools/coverage/`:

- **Partition A — host-executable logic** (everything not matched by a filter): pure decision
  logic, policy/state machines, math, formatting, byte/EXIF parsing, and seam classes designed
  with injected effects. **Target: ≥ 99.5% line coverage, enforced ad hoc via
  `partition_report.py --fail-under-a 99.5`.**
- **Partition B — device-bound glue** (`tools/coverage/partition-b.txt`): Camera2 session/HAL
  orchestration, EGL/GLES, MediaCodec/MediaMuxer/AudioRecord, ContentResolver/MediaStore,
  SensorManager, Activity/Application, ViewModel Handler/Looper glue, and Compose emission.
  This code is exercised by the `device-tests/` functional suite on the PMA110 (and by
  instrumented coverage runs when performed); it has **no host-coverage target** and its
  host-JVM number is reported only for transparency.
- **Excluded** (`tools/coverage/partition-excluded.txt`): debug/preview QA scaffolding
  (`PreviewCameraActions` @Preview stub, the debug-only `UiSnapshotActivity` harness, the
  debug-only `OcsProbe` vendor probe). Counted in neither partition, but the analyzer prints the
  excluded line count on every run so the exclusion is never invisible.

### The honest-number contract

1. **Two numbers, always.** Any coverage statement quotes BOTH the overall line coverage and the
   Partition A line coverage. "Coverage is 99.5%" without the partition qualifier is a
   misstatement: total-line 99.5% including Camera2/GL/MediaCodec glue is not reachable from a
   host JVM and is not claimed.
2. **The partition is committed and auditable.** The filters live in `tools/coverage/*.txt` with
   per-section rationale comments; the analyzer warns when a filter entry no longer matches
   anything (rename drift).
3. **Classification is by framework-boundedness only.** A class/method goes to B because it
   cannot execute against android.jar throwing stubs (constructor or unavoidable calls into
   framework types) — never because it is merely hard to test. Partition edits must carry that
   justification in the commit.
4. **Mixed classes err against Partition A.** Where one JaCoCo class mixes pure helpers with
   Compose emission and is not worth splitting (e.g. `ManualDialsKt`, `OverlaysKt`,
   `MediaReviewKt`), the whole class is surrendered to B even though its pure helpers ARE
   host-tested — the conservative direction can only understate A coverage, never inflate it.
   Where the pure core is large, the class is split at method level
   (`Class#method[#descSubstring]` entries — e.g. `ManualControlsKt`'s tested normalization logic
   stays A while its `CaptureRequest.Builder` extensions are B) or the pure block is extracted to
   a non-composable file (behavior-locked moves, each its own commit).
5. **Accepted residuals are documented.** A small set of lines is accepted inside Partition A's
   0.5% headroom rather than chased with contrived tests. The complete inventory as of the
   cycle-7 close (10 lines, A = 99.75%):
   - `gl/AnalysisGenerationOwner` 2 — tryAcquire's post-CAS retired double-check; a genuine race
     window covered only when the 100-iteration stress test happens to interleave, so its line
     coverage is nondeterministic run to run (A may read 99.70–99.75%). No deterministic host
     test exists without an injection seam.
   - `storage/HeifBoundedReader` 2 — `byteCount !in 0..8` guard unreachable through
     `probeHeifIsoBmff` (every call site passes a fixed or already-validated count; the class is
     private so no other entry exists).
   - `storage/CaptureFamilyKey$Companion` 2 — regex-unreachable `else`/ctor-throw branches.
   - `storage/LatestCaptureReducerKt` 2 — `?: return null` after a non-empty `maxWithOrNull` and
     the deepest tie-break `?: error(...)`.
   - `storage/SettingsStore` 1 — non-finite guard behind `safeFloat`, already guaranteed finite.
   - `ui/controls/FnQuickActionsKt` 1 — performQuickFn's shutter speed↔angle flip argument,
     reachable only via `availability.shutterDialEnabled`, which requires a real framework
     `CameraCaps` (unconstructable on host). A future seam could take `ControlAvailability`
     directly; deferred as a signature change.
   The partition report's gap list is the running inventory; additions require the same
   framework-bound or proven-unreachable justification.

### Method-level split inventory (mixed classes)

`partition-b.txt` splits these classes at method level; everything not listed per class stays A:

- `camera/ManualControlsKt` — B: `apply*` `CaptureRequest.Builder` extensions,
  `kelvinTintToRggbGains` (RggbChannelVector), `normalizedFor(CameraCaps)` overload.
- `camera/CaptureCapabilitiesKt` — B: `readLensExifMetadata` (CameraManager),
  `controlCapabilities` (CameraCaps receiver).
- `camera/CameraSelector2` — B: enumeration/characteristics methods; A: `pickBest`,
  `pickClosest`, `pickFrontBest` (the tested pure cores).
- `camera/VideoFrameRate$Companion` — B: the `availableFor(CameraCaps?, Size, …)` overload
  (android.util.Size param); A: the pure `availableFor(Set<Int>, …)` core.
- `camera/StandbyAudioController` — B: the Context secondary constructor; A: the fully-injected
  primary-constructor state machine.
- `camera/StandbyAudioControllerKt` — B: `createAndroidStandbyAudioInput` (AudioRecord factory).
- `video/AudioInputInspector` — B: the Context/AudioManager wrappers (`status`,
  `preferredDevice`, `routeLabel`, `inputDevices`, `matches`); A: the pure projections.
- `video/UnsafeRecorderQuarantine` — B: `retain` (needs a real VideoRecorder); A: the
  delegation facade over the tested admission gate.
- `storage/SettingsStore` — B: the Context secondary constructor; A: everything else via the
  in-memory SharedPreferences seam.
- `video/VideoRecorder$StopResult` — force-A exception (`!` entry): pure data class nested in
  the otherwise device-bound recorder.

### What "binning works" means for coverage/verification

200 MP remosaic is NOT exposed to third-party Camera2 on PMA110 (probed 2026-07-22, CLAUDE.md):
every HAL camera advertises only the binned ~12.5 MP arrays. Verifying "binning works properly"
therefore means device-side data-validity checks, not host coverage: stills match the advertised
binned geometry exactly (4080×3064 / 4096×3072 per lens), DNG dimensions/bit depth are truthful,
EXIF matches request values, and video dimensions/fps/codec/container tags are what was selected.
Those live in `device-tests/` validators. The capability-gated Hi-Res feature stays dormant by
design with tests.

## Robolectric and instrumented coverage (spike verdicts, 2026-07-23)

Researched for moving `CameraViewModel`/Compose policy into host-testable range and for a merged
overall number; adoption is tracked in the cycle backlog:

- **Robolectric 4.16.1** works on this toolchain (AGP 9.x built-in Kotlin, Gradle 9.6, JDK 21,
  simulated SDK 36). Two mandatory build details: `JacocoTaskExtension.isIncludeNoLocationClasses
  = true` + `excludes = ["jdk.internal.*"]` (otherwise Robolectric-driven coverage silently reads
  0%), and `testOptions.unitTests.isIncludeAndroidResources = true`. The runtime-fetched
  `android-all-instrumented` jar bypasses Gradle dependency verification unless pinned as a
  Gradle configuration with `robolectric.offline` (the hardening this repo's
  verification-metadata posture requires). `CameraViewModel` needs one constructor-injection
  seam for the engine before Robolectric can drive it (it constructs the real `CameraEngine`
  in a field initializer today).
- **Instrumented coverage**: `enableAndroidTestCoverage = true` +
  `:app:createDebugAndroidTestCoverageReport` on the PMA110; AGP 9.3's experimental
  `createCoverageReport` (behind `android.experimental.reportAggregationSupport=true`) can merge
  unit + instrumented into one report. Requires adding the `androidTest` source set + runner
  deps, all through `gradle/verification-metadata.xml`.

## Future seam work (identified, deliberately deferred)

- `camera/RendererAssists`: a ~12-method renderer-sink interface implemented by `GlPipeline`
  would convert its 63 device-bound lines into testable Partition A state-replay logic without
  moving any logic. Deferred — GL glue signature change.
- `gl/GlPipeline` preview-failure containment branch and `storage/MediaStoreWriter`
  `publishWithRetry`/`dngStructureComplete` pattern-parity seams — small extractions, listed in
  the cycle audit, not yet scheduled.
