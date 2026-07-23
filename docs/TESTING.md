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

## Robolectric (ADOPTED, coverage cycle 7 phase 3)

Robolectric hosts the tests that need a working Looper/Handler/SystemClock/SharedPreferences/
Application in one move — starting with `CameraViewModel`'s class body (the 2026-07-23 spike's
verdicts all held; the spike record lives in the cycle context).

**Build specifics** (all in `app/build.gradle.kts` / `gradle/libs.versions.toml`):

- **Robolectric 4.16.1**, `androidx.test:core` 1.7.0, `kotlinx-coroutines-test` (repo coroutines
  pin), plus BOM-managed `ui-test-junit4`/`ui-test-manifest` on the test configurations (the BOM is
  re-applied to `testImplementation`; `implementation(platform(...))` does not flow into it).
  Compose host tests are deliberately NOT written yet — the deps landed with the infra, usage is a
  separately gated decision.
- `testOptions.unitTests.isIncludeAndroidResources = true`, and the simulated SDK is pinned
  explicitly at 36 in `app/src/test/resources/robolectric.properties`.
- **The 0%-coverage trap is closed**: every `Test` task sets
  `JacocoTaskExtension.isIncludeNoLocationClasses = true` + `excludes = ["jdk.internal.*"]` —
  Robolectric's sandbox classloader strips code-source locations and the agent otherwise skips
  those classes SILENTLY. The `jacoco` plugin is applied explicitly in the plugins block because
  AGP's own deferred apply registers the extension after script-body `configureEach` actions.
  Verified working: the first Robolectric commit attributes 476 covered lines to the
  `CameraViewModel` class body.

**Offline android-all verification posture**: Robolectric normally fetches its ~40 MB simulated-
framework jar through its own Maven side channel (ignoring Gradle repos, caches, and
`verification-metadata.xml`). This repo instead declares the exact 4.16.1 pin
(`org.robolectric:android-all-instrumented:16-robolectric-13921718-i7`) in a `robolectricJars`
Gradle configuration, copies it into `build/robolectric-jars`, and runs unit tests with
`robolectric.offline=true` + `robolectric.dependency.dir` — so the runtime jar's sha256 sits in
`gradle/verification-metadata.xml` like every other dependency. The pin moves in lockstep with
Robolectric upgrades; on drift the test task fails with the expected coordinate in its message.

**Partition policy for Robolectric-driven classes**: a class Robolectric can drive REMAINS in
Partition B until it can genuinely hold ~100% under host tests — the Partition A claim is never
diluted by adding partially-covered classes to it. Robolectric coverage therefore contributes to
the OVERALL and Partition B numbers only (`CameraViewModel` stays in `partition-b.txt`; the
adoption run moved B 1.43% → 11.26% and OVERALL 25.07% → 32.34% while A's composition and
denominator were unchanged). Robolectric-based tests document their engine strategy in the test
header: `CameraEngine` is final, so they inject a REAL, never-resumed engine through
`CameraViewModel`'s constructor seam (the camera only opens on `resume()`; the pre-open engine is
inert by design). `RobolectricEglSentinels` backfills the `EGL14` sentinel statics the sandbox
leaves null.

## Instrumented coverage (ADOPTED as infra, coverage cycle 7 phase 4)

An `app/src/androidTest` **smoke tier** and property-gated instrumented coverage now exist. This
tier is deliberately shallow: **`device-tests/` remains the functional authority** — the
instrumented suite exists to drive real MainActivity → CameraViewModel → CameraEngine →
Camera2/GL code paths in-process so instrumented coverage attributes real lines, not to re-test
behavior.

**The property gate.** `enableAndroidTestCoverage` is bound to a Gradle property, NOT default-on:

```kotlin
enableAndroidTestCoverage = providers.gradleProperty("androidTestCoverage").orNull == "true"
```

The flag makes AGP JaCoCo-**instrument the debug APK bytecode**. The default debug build must stay
uninstrumented so `device-tests/` perf checks and any APK-sha attestation run against clean
bytecode. Verified: without `-PandroidTestCoverage=true`, `assembleDebug`'s task graph contains
**no jacoco task**; with it, `:app:generateDebugJacocoPropertiesFile` + `:app:jacocoDebug` enter
the graph and the per-variant `:app:createDebugAndroidTestCoverageReport` is registered. Always
build/install a CLEAN (no-property) debug APK when leaving the device at baseline.

**The smoke suite** (`app/src/androidTest/kotlin/.../MainActivitySmokeTest.kt`, 4 tests): launch
reaches RESUMED; `cameraReady` after cold launch; a recreate cycle (the portrait-locked activity's
rotation analog); a background/foreground `moveToState` cycle through `vm.onStop/onStart →
engine.pause/resume`. Readiness is observed through the SAME `CameraViewModel` instance
`MainActivity`'s `viewModels()` holds (shared `ViewModelStore`, `ViewModelProvider(activity)` —
**no production seam added**). No captures, no control changes, no settings mutations; a `@Before`
wake + dismiss-keyguard keeps the suite independent of operator screen state. `GrantPermissionRule`
is deliberately absent — CAMERA/RECORD_AUDIO are hand-managed on ColorOS (`pm grant` fails there)
and must never be dropped. Deps are lean: `androidx.test:runner` 1.7.0 + `androidx.test.ext:junit`
1.3.0 + `androidx.test:core` 1.7.0 + `junit`, no compose BOM (the suite reads the StateFlow
directly), all through `gradle/verification-metadata.xml`.

**Task names (AGP 9.3, variant = debug).**

- Per-leg instrumented (connected): `:app:createDebugAndroidTestCoverageReport`
  (`-PandroidTestCoverage=true`) — runs `connectedDebugAndroidTest`, writes `.ec` under
  `app/build/outputs/code_coverage/debugAndroidTest/connected/<device>/` and HTML under
  `app/build/reports/coverage/androidTest/debug/connected/`.
- Per-leg unit (host): `:app:createDebugUnitTestCoverageReport` → `.exec` at
  `app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`, XML at
  `app/build/reports/coverage/test/debug/report.xml` (what `partition_report.py` consumes).
- Merged: AGP 9.3's experimental **`createCoverageReport`** (per-module; `createAggregatedCoverageReport`
  adds project deps — same content for this single-module app), gated behind
  `android.experimental.reportAggregationSupport=true` (set in `gradle.properties`). It emits **HTML
  only** at `app/build/reports/code_coverage_html_report/` and, per the AGP docs, is **NOT generated
  if any relevant test fails**. The classic XML fallback (spike recipe) is a manual `JacocoReport`
  over the unit `*.exec` + connected `**/*.ec`; it must reproduce AGP's own class-exclusion set or
  its OVERALL denominator will not match the per-leg reports.

**The merged-report basis-labeling rule (honest numbers).** The merged OVERALL is a **different
measurement basis** than the host-only OVERALL — it mixes host-JVM unit coverage with on-device
instrumented smoke coverage. Always report it clearly labeled as
**"merged (host unit + instrumented smoke)"** and NEVER substitute it for the host-only OVERALL /
Partition A numbers (the two-numbers contract above still governs the host-only report). An
instrumented smoke run raises the OVERALL and the Partition B numbers only; it never dilutes the
Partition A claim (device-drivable classes stay in Partition B until they can genuinely hold ~100%
under host tests — same policy as the Robolectric classes).

**PMA110 device caveat (connected leg currently BLOCKED, 2026-07-24).** The connected instrumented
run cannot complete on this device: the instrumented test APK `me.hletrd.telecampro.debug.test` is
a new package with **no launcher activity**, so ColorOS's `OPlusPackageInstallerActivity` flags it
"No Home screen icon" and offers only **"Exit installation"** — there is no automatable
continue/install-anyway action, and even `adb install` funnels through the same confirmation (which
also bounces the wireless-adb TLS transport, surfacing to UTP as
`AndroidTestApkInstallerPlugin: device offline`, 0 tests run). Disabling `package_verifier_enable`
does NOT help — it is an OPlus-specific risk gate. The `install -r` UPDATE path for the already-
trusted **app** package is unaffected (that is how `device-tests/` drives the device). Consequence:
the build infra + smoke suite are committed and host-green, but no `.ec` files and therefore **no
merged number** can be produced until this gate is cleared (likely needs an OPPO/HeyTap-account
"Install via USB" enrollment or a device with the risk gate off). Until then, quote only the
host-only numbers; do not fabricate a merged figure.

## Future seam work (identified, deliberately deferred)

- `camera/RendererAssists`: a ~12-method renderer-sink interface implemented by `GlPipeline`
  would convert its 63 device-bound lines into testable Partition A state-replay logic without
  moving any logic. Deferred — GL glue signature change.
- `gl/GlPipeline` preview-failure containment branch and `storage/MediaStoreWriter`
  `publishWithRetry`/`dngStructureComplete` pattern-parity seams — small extractions, listed in
  the cycle audit, not yet scheduled.
