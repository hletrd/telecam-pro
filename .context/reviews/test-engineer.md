# Test Coverage & Testability Review — find-x9-ultra-camera

Reviewer: test-engineer. Scope: all 29 Kotlin sources under `app/src/main/kotlin/com/hletrd/findx9tele/**`
plus `app/src/test/**`. Method: full read of every file, plus an empirical probe of the current
Gradle unit-test classpath's behavior toward `android.*` framework classes (see **Infra finding**
below — this changes several risk/priority calls versus assuming Robolectric/Mockito are available).
Review-only: no test code was added. A throwaway probe test was written, run, and deleted during this
review to establish ground truth for the infra finding (`git status` was clean afterward).

**Fresh baseline** (`./gradlew :app:testDebugUnitTest --rerun`, JDK 21 aarch64): **12 tests, 0
failures, 0.014 s total** — only `focus/FocusMappingTest.kt` exists today.

---

## Infra finding (reframes every priority below) — read this first

`app/build.gradle.kts` has **`testImplementation(libs.junit)` only** — no Mockito/mockk, no
Robolectric, no `testOptions.unitTests.isReturnDefaultValues`. I empirically verified what that means
by writing a scratch test (`StubProbeTest.kt`, deleted after) that constructed
`android.util.Range`, `android.util.Size`, `android.hardware.camera2.params.RggbChannelVector`, and
called `android.media.MediaFormat.createVideoFormat(...)`:

- **Constructors succeed** (`Range(1L, 2L)`, `Size(10, 20)`, `RggbChannelVector(1f,1f,1f,1f)` don't throw).
- **Every method/getter call throws**, even trivial ones (`range.lower`, `size.width`,
  `rggb.red`): `java.lang.RuntimeException: Method getLower in android.util.Range not mocked.` Same
  for `MediaFormat.createVideoFormat` itself (throws immediately, it's a static factory).

This is the stock AGP "mockable android.jar" behavior for local (JVM) unit tests. Two consequences:
1. **Any function that reads a field of an `android.*` object (even `Range.lower`) cannot be unit
   tested today, at all**, regardless of how "pure" its math looks in the source.
2. Turning on `testOptions.unitTests.isReturnDefaultValues = true` would stop the throw, but every
   `android.*` getter would then return `0`/`false`/`null` **regardless of what was constructed with**
   — useless for value-preserving assertions (e.g. `Range(10,100).lower` would read back `0`, not `10`).
   It only helps for "doesn't crash" smoke tests, not correctness tests.

So the real fix is one of two things, decided **per finding** below:
- **(A) Extract the pure math into a function/object with zero `android.*` types in its signature**
  (primitives/Kotlin types only) — this is exactly the pattern `FocusMapping.kt` already establishes
  (its docstring literally says "no Android framework dependencies... pure and unit-tested") and is
  the cheapest, lowest-risk option wherever the function's inputs/outputs don't *need* to be an
  Android type.
- **(B) Add Robolectric** (`org.robolectric:robolectric`, latest stable — look up the current version
  at implementation time per repo convention, don't pin from memory) as a `testImplementation`, which
  properly shadows simple POJO-like classes (`Range`, `Size`, `Rational`, `MediaFormat`,
  `RggbChannelVector`) with real field-preserving behavior. Needed only where (A) is impractical
  because the Android type genuinely is the public contract (e.g. `CameraCaps`'s public fields).

I flag which option applies to each finding below. This infra gap is why the review skews toward
"needs a small refactor first" rather than "just add a test file" — that refactor is itself the
main actionable recommendation, and it's low-risk (pure-function extraction, same shape as the
existing `FocusMapping` precedent).

---

## Summary

| ID | Unit / File | Gap | Priority | Confidence |
|----|---|---|---|---|
| TEST-1 | `CameraSelector2.select` — closest-to-70mm tie-break | No test; tie-break logic entangled with `CameraManager` I/O | HIGH | High |
| TEST-2 | `CameraEngine.previewRotationDegrees` | No test; trivial pure fn trapped in a `Context`-requiring class | HIGH | High |
| TEST-3 | `CameraEngine.captureRotationDegrees` | No test; combines sensor+afocal+gyro orientation, mod-360 | HIGH | High |
| TEST-4 | `CameraEngine.exifOrientationFor` | No test; degrees→EXIF tag mapping, non-90-multiple inputs untested | HIGH | High |
| TEST-5 | `GyroEis.currentDeviceOrientation` | No test; round-to-90 + normalize, boundary values (45/135/-45 etc.) untested | HIGH | High |
| TEST-6 | `ManualControls.kelvinTintToRggbGains` | No test; blackbody approx + tint + normalization untested; return type currently blocks testing (infra finding) | HIGH | High |
| TEST-7 | `ManualDials.kt`: `isoStops`/`shutterStops`/`roundToSignificant` | No test; stop-snapping math is `private`, recently touched (task #1), feeds every ISO/shutter drag | HIGH | High |
| TEST-8 | `ManualControls.effectiveExposureNs()` | No test; ANGLE-mode formula + `fps<=0` guard untested | MEDIUM-HIGH | High |
| TEST-9 | `VideoRecorder.applyGainAndLevel` | No test; PCM gain clamp + RMS is pure `java.nio` math (no Android dep at all) but `private` and untested | MEDIUM | High |
| TEST-10 | `CameraSelector2.equivFocalOf` 35mm-equiv formula | No test; diagonal=0 fallback branch untested | MEDIUM | Medium |
| TEST-11 | `CaptureCapabilities`: `clampFpsRange`/`availableFps`/`hasEffect`/`supportsHlg10` | No test; blocked by infra finding (needs Robolectric) | MEDIUM | High |
| TEST-12 | `video/ColorProfiles.kt` format builders | No test; blocked by infra finding (needs Robolectric); HLG/LOG/AVC tag differences are correctness-relevant (backlog 🟡) | MEDIUM | Medium |
| TEST-13 | `CameraEngine.fileName()` timestamp format + collision risk | No test; hardcodes `Date()`, not injectable; BURST can collide within the same wall-clock second | MEDIUM | Medium |
| TEST-14 | `CameraEngine.captureAeb` EV bracket step list | No test; `[-2,0,2]` clamp-to-range logic trapped in a `Context`-requiring class | LOW-MEDIUM | High |
| TEST-15 | `FocusMappingTest.kt` adequacy | Existing test is solid; minor gaps (gamma=1/gamma<1 cases, no exact-value regression anchor, NaN input unspecified) | LOW | High |
| TEST-16 | `ProControls.kt` enum→label functions | Pure, but compiler-exhaustive `when` already guards against missed cases; low value | SKIP | — |

Counts: **14 testable gaps** (7 HIGH, 1 MEDIUM-HIGH, 5 MEDIUM, 2 LOW/LOW-MEDIUM), **1 existing-test
adequacy note**, **1 explicit skip recommendation**.

---

## HIGH priority

### TEST-1 — `CameraSelector2.select`: closest-to-70mm + standalone tie-break
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraSelector2.kt:59-62`
**Gap**: This is the single most safety-critical piece of pure logic in the app — CLAUDE.md's "Hard-won
device facts" section exists *because* getting this wrong crashes the HAL (`ChiMulticameraBase`
SIGSEGV) or silently picks the wrong lens (230mm 10x instead of the 70mm 3x). The selection algorithm
itself is pure (`List<TeleSelection>` in, `TeleSelection?` out — `TeleSelection` is a plain data class,
zero Android types) but is inlined inside `select()`, which also does the `CameraManager` enumeration.
Not unit-testable as written (needs a real/mocked `CameraManager`+`CameraCharacteristics`, and per the
infra finding, `CameraCharacteristics.get(...)` calls will throw "not mocked").
**Fix (A, preferred)**: extract the tail of `select()` into a standalone pure function:
```kotlin
fun pickBest(candidates: List<TeleSelection>): TeleSelection? =
    candidates.filter { it.equivFocalMm > 0f }
        .minWithOrNull(compareBy({ abs(it.equivFocalMm - TARGET_EQUIV_MM) }, { if (it.physicalId == null) 0 else 1 }))
        ?: candidates.firstOrNull()
```
`select()` becomes a thin wrapper that builds `candidates` from `CameraManager` then calls `pickBest`.
**Test proposal** (`CameraSelector2Test.kt`):
- `picks the candidate closest to 70mm, not the longest` — candidates at 24/70/230mm equiv → expect 70mm one (regression guard against the exact bug this class's docstring warns about).
- `on an exact tie, prefers physicalId == null (standalone) over a physical sub-camera` — two candidates both at 70mm equiv, one `physicalId=null` one `physicalId="4"` → expect the standalone one (the exact HAL-crash-avoidance behavior CLAUDE.md documents).
- `ties broken by physicalId preference regardless of list order` — same as above with the list reversed, to prove the tie-break isn't an accidental artifact of iteration/insertion order.
- `filters out candidates with non-positive equivFocalMm` (unreadable focal length) — candidate with `equivFocalMm = 0f` must never be picked even if it's numerically "closest" by an unfiltered comparator bug.
- `returns null for an empty candidate list`.
- `returns the sole candidate when only one exists`, even if far from 70mm (single-camera device fallback).
Priority: HIGH. Confidence: High (code and rationale both directly readable, zero ambiguity in intended behavior).

### TEST-2 — `CameraEngine.previewRotationDegrees()`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:152`
**Gap**: `private fun previewRotationDegrees(): Int = if (teleconverterMode) 180 else 0`. Trivial today
(note: this simplified since the version described in `docs/ARCHITECTURE.md:190-201`, which still
documents a `-sensorOrientation + 180` formula — **the doc is stale**, worth a doc fix but out of this
review's scope). Trivial or not, it's `private` inside `CameraEngine`, whose constructor calls
`context.getSystemService(CameraManager::class.java)` — cannot be instantiated in a JVM unit test.
Backlog marks preview rotation 🟡 **unverified on device**; a unit test can't replace that device
check, but it locks the *formula* so a future edit doesn't silently reintroduce the old sensor-term
bug (CLAUDE.md explicitly documents that the sign of this term was already flipped once from a bug report).
**Fix (A)**: extract to a small pure object, e.g. `camera/RotationMath.kt` (mirrors `FocusMapping`'s
shape/doc style):
```kotlin
object RotationMath {
    fun previewRotationDegrees(teleconverterMode: Boolean): Int = if (teleconverterMode) 180 else 0
    fun captureRotationDegrees(sensorOrientation: Int, teleconverterMode: Boolean, deviceOrientationDeg: Int): Int = ...
    fun exifOrientationFor(degrees: Int): Int = ...
}
```
`CameraEngine`'s three private methods become one-line delegates (keeps call sites unchanged).
**Test proposal**: `previewRotationDegrees(teleconverterMode=true) == 180`,
`previewRotationDegrees(teleconverterMode=false) == 0`. Trivial but cheap, and it's the kind of
"obviously correct" function that's exactly the kind that regresses silently when someone "simplifies"
it later (as already happened once per the architecture-doc drift noted above).
Priority: HIGH (cheap + directly guards a documented history of sign bugs). Confidence: High.

### TEST-3 — `CameraEngine.captureRotationDegrees()`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:514-518`
**Gap**: `((sensorOrientation + (180 if tele) + gyro.currentDeviceOrientation()) % 360 + 360) % 360`.
Backlog flags stills/video orientation as 🟡/🔴 unverified/broken (video ignores device orientation
entirely — a real correctness bug, see TEST-3 note below). The modulo-normalization
(`(x % 360 + 360) % 360`) is exactly the kind of thing that's easy to get subtly wrong for negative
inputs (Kotlin's `%` returns a negative result for negative operands, unlike Python) — worth locking
with explicit negative-sum cases.
**Fix**: same `RotationMath.captureRotationDegrees(...)` extraction as TEST-2.
**Test proposal** (`RotationMathTest.kt`):
- `sensorOrientation=90, teleconverter on, deviceOrientation=0` → `270` (matches the worked example in `docs/ARCHITECTURE.md:211-216`; use it as the anchor/regression value since it's already documented and presumably eyeballed once).
- `sensorOrientation=90, teleconverter off, deviceOrientation=90` → confirms teleconverter-off path and device-orientation addition both contribute.
- `sensorOrientation=0, teleconverter on, deviceOrientation=270` → sum is exactly 450, must normalize to `90` (regression guard for the double-modulo — a single `% 360` without the `+360` would break here only if the pre-mod value were negative, so also add:
- a case whose raw sum is negative before normalization is impossible with `sensorOrientation∈{0,90,180,270}` and `deviceOrientation∈{0,90,180,270}` today (both always ≥0), so note in the test comment that the `+360` guard is defensive/future-proofing rather than reachable with current inputs — still worth asserting the function never returns a value outside `[0,360)` across the full `{0,90,180,270}×{true,false}×{0,90,180,270}` cross product (16 cases) as a property test.
Priority: HIGH. Confidence: High.

### TEST-4 — `CameraEngine.exifOrientationFor()`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:527-532`
**Gap**: `when (((degrees % 360) + 360) % 360) { 90 -> ...; 180 -> ...; 270 -> ...; else -> NORMAL }`.
Backlog explicitly calls out DNG orientation as 🟡 unverified — pull a real DNG and check it's upright.
A unit test won't replace that device check, but it removes one axis of uncertainty (is the tag
math right?) so the device pass only needs to confirm the *photographed subject* looks right, not
debug the tag math too. Untested edge case: **any degrees value not exactly 0/90/180/270** (e.g. a
future device orientation source that isn't exactly quantized, or an input like `-90` or `450`)
silently falls into `NORMAL` — is that the intended fallback, or should it round to the nearest 90?
Worth a test that pins down the current (silent-fallback) behavior explicitly, so it can't drift
without someone noticing the test.
**Fix**: part of the `RotationMath` extraction (TEST-2/3). Note `android.media.ExifInterface.ORIENTATION_ROTATE_90` etc. are `public static final int` constants — Kotlin inlines these at compile time, so referencing them does **not** trigger the "not mocked" stub-jar problem (unlike calling a method/getter). Safe to keep using the real `ExifInterface` constants in the extracted object rather than hardcoding magic numbers.
**Test proposal**: `exifOrientationFor(0) == ORIENTATION_NORMAL`, `90 == ROTATE_90`, `180 == ROTATE_180`,
`270 == ROTATE_270`, `360 == ORIENTATION_NORMAL` (wraps to 0), `450 == ROTATE_90` (wraps to 90),
`-90 == ROTATE_270` (negative wrap), `45 == ORIENTATION_NORMAL` (documents the silent-fallback choice).
Priority: HIGH. Confidence: High.

### TEST-5 — `GyroEis.currentDeviceOrientation()`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/stab/GyroEis.kt:76-79`
**Gap**: `Math.round(rollDegrees / 90f) * 90` then `((d % 360) + 360) % 360`. Feeds directly into
`captureRotationDegrees()` (TEST-3) — if this rounds wrong, every still photo saves in the wrong
orientation. It's pure math over a `Float` but is an instance method reading a private field on a
class whose constructor calls `context.getSystemService(SensorManager::class.java)` — not
instantiable in a JVM test. Boundary values are exactly where `Math.round`'s half-up tie-breaking
matters: `rollDegrees=45f` rounds to `90` (not `0`), `rollDegrees=-45f` rounds to `0` per
`Math.round`'s away-from-negative-infinity... actually toward-positive behavior — this is precisely
the kind of tie-breaking detail that should be pinned by a test rather than assumed.
**Fix (A)**: extract as a pure companion/top-level function, e.g. `GyroEis.orientationFromRoll(rollDegrees: Float): Int` (can live as a `companion object` function on `GyroEis` itself, doesn't need a new file — it needs no instance state) so `currentDeviceOrientation()` becomes `orientationFromRoll(rollDegrees)`.
**Test proposal** (`GyroEisOrientationTest.kt`, or inline if kept as a companion function tested via `GyroEis.Companion`):
- `0f → 0`, `90f → 90`, `180f → 180`, `-90f → 270` (or `-90`? — pin down and assert whichever the current mod-normalization actually produces; this is exactly the ambiguity worth resolving with a test rather than reading the formula each time).
- Boundary/tie cases: `44.9f → 0`, `45.1f → 90`, `45f → ?` (document `Math.round`'s tie-break behavior explicitly since Kotlin/Java round-half-up differs from round-half-even).
- `359f → 0` (wraps past 360 back to 0, not 360).
- `-1f → 0`, `-179f → 180` or `-180`(pin the exact wrap direction near the ±180 seam, since that's the "upside-down" boundary and getting it wrong there means upside-down photos save as if upright).
Priority: HIGH (directly gates still-photo orientation correctness, which is an open backlog item). Confidence: High.

### TEST-6 — `ManualControls.kelvinTintToRggbGains`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/ManualControls.kt:242-271`
**Gap**: Backlog 🟡 flags this explicitly ("Verify neutral grey looks neutral at 5200K... may need
per-device calibration"). A unit test can't verify perceptual neutrality on real glass, but it CAN
lock the *formula* (Tanner-Helland approximation + tint blend + gain normalization) so it can't
silently drift, and it CAN catch algebra bugs today: I read the function closely and the tint
application looks suspicious — `tintFactor` multiplies `gainG` (line 265: `gainG = (1.0/gg) * tintFactor`)
where `tintFactor = 1.0 - tint/100.0`. For `tint > 0` ("magenta", per the doc comment "less green
gain"), `tintFactor < 1`, so `gainG` decreases — consistent with the comment. This looks correct on
inspection, but the interaction with the subsequent `minGain` renormalization (lines 267-268, which
divides ALL THREE gains by whichever is smallest) means the *visible* magenta/green shift also
depends on how `gainG` compares to `gainR`/`gainB` at that Kelvin — i.e., tint's effect is not simply
"green channel scales by tintFactor" once you look at post-normalization output. This is exactly the
kind of coupled-normalization logic that benefits from a table of known input→output pairs rather
than trusting a read-through. Currently untestable per the infra finding (returns `RggbChannelVector`,
whose getters throw "not mocked").
**Fix (A, preferred over Robolectric)**: extract the math to return a plain Kotlin type before the
Android wrapper, e.g.:
```kotlin
internal fun kelvinTintToRggbGainsRaw(kelvin: Int, tint: Int): FloatArray /* [r,g,g,b] */ { ... }
fun kelvinTintToRggbGains(kelvin: Int, tint: Int): RggbChannelVector =
    kelvinTintToRggbGainsRaw(kelvin, tint).let { RggbChannelVector(it[0], it[1], it[2], it[3]) }
```
**Test proposal** (`ManualControlsTest.kt` or new `WhiteBalanceMathTest.kt`):
- `at tint=0, the smallest of the 3 gains is exactly 1.0` (the documented normalization invariant — this alone would catch a broken renormalization).
- `both green channels (index 1 and 2) are always equal` (RggbChannelVector is R/G-even/G-odd/B; the function always passes the same `gainG` for both — assert that invariant holds for a spread of kelvin/tint inputs).
- `increasing tint (toward magenta) never increases the green gain relative to tint=0 at the same kelvin` (monotonicity property, doesn't need to hardcode the exact float).
- `kelvin is clamped to [1000, 40000]` — `kelvinTintToRggbGainsRaw(500, 0)` produces the same result as `kelvinTintToRggbGainsRaw(1000, 0)`, and `50000` same as `40000`.
- `tint is clamped to [-50, 50]`.
- `at a warm kelvin (e.g. 2000K), the blue gain exceeds the red gain` and `at a cool kelvin (e.g. 9000K), the red gain exceeds the blue gain` — a coarse "sanity" property test that would catch a sign flip in the r/b formula without needing to hand-derive exact expected floats.
- Exact-value regression anchor at the default `wbKelvin = 5200` (the `ManualControls()` default), tint=0 — compute once, hardcode as the expected value, so any future edit to the formula shows a diff instead of silently changing default-launch color.
Priority: HIGH (explicit backlog item + genuine algebra complexity + zero current coverage on a
formula that directly affects every manual-WB photo). Confidence: High that this is worth testing;
Medium on my read of the tint/normalization interaction being correct — that's exactly what the
monotonicity + invariant tests above are designed to catch either way.

### TEST-7 — `ManualDials.kt`: `isoStops` / `shutterStops` / `roundToSignificant`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/ui/controls/ManualDials.kt:315-350`
**Gap**: Zero tests. This is the stop-snapping math for the ISO/shutter "Fn" dials — task list shows
"Exposure dials: AE toggle + ISO/shutter EV-step snapping" as **recently completed** (task #1), which
is exactly when regressions get introduced without a safety net. `roundToSignificant` (lines 315-320)
is 100% pure (`Double, Int → Double`, `Math.log10`/`Math.pow`/`Math.round` only) and has **zero
Android dependency** — trivially testable today if made `internal`. `isoStops`/`shutterStops` take
`android.util.Range<Int>`/`Range<Long>` and call `.lower`/`.upper` — blocked by the infra finding as
written.
**Fix**: two-part.
1. `roundToSignificant`: change `private` → `internal`, no other change needed — testable immediately.
2. `isoStops`/`shutterStops`: add primitive-parameter overloads (`isoStops(lo: Int, hi: Int, stepEv: Float): IntArray`) that the `Range`-based versions delegate to; test the primitive overloads. This is the same extraction pattern as TEST-1/2/3 — consistent, low-risk, matches `FocusMapping`'s existing precedent. (Given the amount of pure logic accumulating in `ManualDials.kt`, also consider moving these three functions out of the Compose UI file into a new `camera/ExposureStops.kt` — keeps the UI file Compose-only and the math file test-only, mirroring the existing `focus/` package split.)
**Test proposal** (`ExposureStopsTest.kt`):
- `roundToSignificant(123.456, 2) == 120.0`, `roundToSignificant(0.0034, 2) == 0.0034`, `roundToSignificant(0.0, 2) == 0.0` (guard the `v <= 0.0` early return).
- `isoStops` includes both hardware bounds even when they don't land on a "nice" 1/3-stop value — e.g. `lo=50, hi=6400, stepEv=1/3` → first element `50`, last element `6400`.
- `isoStops` at THIRD/HALF/FULL step produces conventional camera values near the anchor — e.g. THIRD near 100 includes `100, 125, 160, 200` (the classic 1/3-stop ISO ladder); FULL near 100 includes `100, 200, 400` (doubling).
- `isoStops` is strictly increasing (no duplicate/out-of-order stops) across the generated set — this also implicitly tests the `sortedSetOf` dedup behavior when a generated "nice" value collides with a bound.
- `isoStops` returns `[lo]` only when `lo >= hi` or `stepEv <= 0` (degenerate-range guard, currently untested).
- `shutterStops` similarly includes both bounds; anchor at 1s so e.g. THIRD step near 1s includes values that correspond to the conventional `1s, 0.8s(1/1.25), ...` ladder — test against `formatShutterSpeed`-adjacent nice denominators is optional/lower value; a numeric-value assertion (in ns) is sufficient and more robust to that formatting function changing independently.
- `shutterStops` degenerate-range guard (`lo>=hi` or `stepEv<=0` → `[lo]`).
Priority: HIGH (zero coverage on recently-touched, drag-gesture-facing math that's easy to get subtly
wrong — e.g. an off-by-one in the `kLo..kHi` range, or a `>` vs `>=` at the bounds that silently drops
or duplicates the first/last stop). Confidence: High.

---

## MEDIUM-HIGH priority

### TEST-8 — `ManualControls.effectiveExposureNs()`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/ManualControls.kt:57-63`
**Gap**: Pure extension function on `ManualControls` (all-primitive/enum fields, zero Android
dependency at the class level) — directly testable today with **no refactor needed**. It's the
cine-style shutter-ANGLE→exposure-time conversion (`(angle/360)/fps` in ns), which feeds
`applyExposure()`'s `SENSOR_EXPOSURE_TIME` — get this wrong and manual video exposure is silently off
by whatever factor. Also guards the `fps <= 0` fallback (falls back to `exposureTimeNs` rather than
dividing by zero) — a real crash-avoidance path that's currently unverified.
**Test proposal** (add to `ManualControlsTest.kt`):
- `SPEED mode ignores shutterAngle and returns exposureTimeNs unchanged`.
- `ANGLE mode at angle=180, fps=30 → ~16,666,667 ns` (180° is the classic "normal" cine shutter; exact expected value = `(180.0/360.0)/30.0*1e9`, compute once and hardcode as the regression anchor).
- `ANGLE mode at angle=360, fps=24 → exactly 1/24 s in ns` (full-open shutter sanity check).
- `ANGLE mode clamps shutterAngle to [1,360]` — e.g. `shutterAngle=0` behaves as `angle=1`, `shutterAngle=400` behaves as `angle=360`.
- `ANGLE mode with fps=0 falls back to exposureTimeNs` (the div-by-zero guard — currently exercised nowhere, including in `ManualDials.kt`'s own `effectiveExposureNsForDisplay()` duplicate of this same logic at `ManualDials.kt:303-308`, which is a near-exact copy — worth flagging as a small DRY gap for the executor, not this review's job to fix, but the test should cover both if both stay).
Priority: MEDIUM-HIGH (correctness-relevant, cheap — no refactor blocker at all). Confidence: High.

---

## MEDIUM priority

### TEST-9 — `VideoRecorder.applyGainAndLevel`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/video/VideoRecorder.kt:277-294`
**Gap**: PCM gain-apply + RMS-level computation. Operates entirely on `java.nio.ByteBuffer` /
`ByteOrder` / `Short` — **plain JDK types, zero Android dependency**, despite living inside a
`VideoRecorder(context: Context)` instance method. It doesn't reference `this`/`context` at all, so
it's a pure function trapped only by visibility (`private`) and location, not by any real hardware
coupling. Untested today; correctness risk is real — the in-place short-buffer write with
`coerceIn(Short.MIN_VALUE, Short.MAX_VALUE)` clamp is exactly the kind of code where an off-by-one in
the clamp bounds causes audible clipping/wraparound artifacts in every recorded video.
**Fix (A)**: extract to a top-level `internal fun applyGainAndLevel(buf: ByteBuffer, byteCount: Int, gain: Float): Float` — either a companion-object function on `VideoRecorder` or a small new `video/PcmGain.kt` object (latter is cleaner if the plan is to keep growing pure video-math helpers, mirroring `focus/FocusMapping.kt`).
**Test proposal** (`PcmGainTest.kt`):
- `gain=1.0 leaves samples unchanged` — build a `ByteBuffer` with known 16-bit LE samples, apply gain 1.0, assert buffer bytes unchanged.
- `gain=2.0 doubles a sample that doesn't clip` (e.g. input `1000` → output `2000`).
- `gain that would overflow Short.MAX_VALUE clamps instead of wrapping` — e.g. sample `30000`, gain `2.0` → expected raw `60000` clamps to `32767`, NOT wraps to a negative/garbage value (this is the actual bug class the clamp exists to prevent — a regression here is silent audio corruption).
- Same clamp check at the negative end (`Short.MIN_VALUE`).
- `RMS of a silent (all-zero) buffer is 0.0`.
- `RMS of a full-scale square wave (alternating ±32767) is close to 1.0` (√(mean of squares)/32768 ≈ 1.0 for constant-magnitude samples — a clean closed-form expected value).
- `byteCount=0 returns 0f` without dividing by zero (the `count == 0` early return, currently unexercised).
Priority: MEDIUM (no backlog item calls this out explicitly, but it's a genuine zero-cost win — no
refactor blocker beyond visibility, and it protects a real audio-corruption failure mode).
Confidence: High.

### TEST-10 — `CameraSelector2.equivFocalOf` (35mm-equivalent focal-length formula)
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraSelector2.kt:65-71`
**Gap**: `focalMm * FULL_FRAME_DIAGONAL_MM / sensorDiagonalMm`, with a `diag <= 0f → return focalMm`
(un-scaled) fallback. This formula's correctness is a precondition for TEST-1's tie-break even
mattering (if the equiv-mm computation is wrong, "closest to 70mm" picks the wrong lens regardless of
how correct the tie-break comparator is). Currently entangled with `CameraManager`/`CameraCharacteristics`
I/O.
**Fix (A)**: extract the arithmetic itself: `fun equivFocalMm(focalMm: Float, sensorDiagMm: Float): Float`.
**Test proposal**: `equivFocalMm(focalMm=8.0f, sensorDiagMm=8.0f*43.2666f/70f)` (constructed so the
answer is exactly 70) `≈ 70f`; `equivFocalMm(focalMm=X, sensorDiagMm=0f) == X` (the fallback branch —
currently only reachable, and untested, when `SENSOR_INFO_PHYSICAL_SIZE` is unavailable — a real
device-absence path, not just a defensive no-op); `equivFocalMm(0f, anySize) == 0f` (feeds TEST-1's
"filters non-positive equivFocalMm" case).
Priority: MEDIUM (foundational to TEST-1 but the formula itself is low-complexity — one division —
so the marginal risk it's *wrong* is lower than TEST-1's comparator logic; still worth locking since
it's a silent-failure-mode formula: a wrong diagonal doesn't crash, it just silently mis-selects a
lens with no error, in production, on the one device this app supports). Confidence: Medium.

### TEST-11 — `CaptureCapabilities`: `clampFpsRange` / `availableFps` / `hasEffect` / `supportsHlg10`
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CaptureCapabilities.kt:52-64`
**Gap**: These are genuinely pure instance methods on the `CameraCaps` data class (no I/O, just
filtering/mapping over already-read fields), but `CameraCaps`'s constructor takes `Range<Int>?`,
`Range<Long>?`, `Rational`, `Array<Range<Int>>` as part of its **public shape** (used throughout
`ManualControls.kt`, `CameraController.kt`, UI code) — extracting these fields to primitives would be
a much larger, more invasive refactor than TEST-1 through TEST-10 (touches a widely-shared data class
signature). Per the infra finding, constructing a `CameraCaps` with real `Range`/`Rational` values
succeeds, but calling `clampFpsRange`/`availableFps` (which read `.lower`/`.upper` on those `Range`
objects) throws "not mocked" today.
**Fix (B, Robolectric)**: this is the clearest case in the review for adding Robolectric rather than
refactoring — `Range`/`Rational`/`Size` are simple, fully-shadowed POJOs under Robolectric (verify
shadow coverage for `Rational` specifically at implementation time; `Range`/`Size` shadow support is
well-established). No production code changes needed once Robolectric is on the classpath.
**Test proposal** (`CaptureCapabilitiesTest.kt`, Robolectric): construct `CameraCaps` instances with
hand-picked `availableFpsRanges` (e.g. `[Range(24,24), Range(30,30), Range(24,60)]`) and assert:
- `clampFpsRange(30)` prefers the exact fixed range `[30,30]` over the variable `[24,60]` range that also covers 30 (tests the documented "prefer a fixed range" precedence).
- `clampFpsRange(50)` with no exact match falls back to a covering variable range (`[24,60]`).
- `clampFpsRange(120)` with no covering range at all returns `null`.
- `availableFps` de-duplicates and sorts (`[Range(24,24), Range(30,30), Range(24,60)]` → `[24,30,60]`, not `[24,30,24,60]` or unsorted).
- `hasEffect`/`supportsHlg10` against a constructed `effectModes`/`supportedDynamicRangeProfiles` set.
Priority: MEDIUM (real logic, but lower blast radius than TEST-1 through TEST-8 — a wrong
`clampFpsRange` degrades to "AE target fps hint not applied," not a crash or wrong-lens/wrong-orientation
failure). Confidence: High that the gap exists; the Robolectric-shadow-coverage caveat is the only
uncertainty, hence not HIGH.

### TEST-12 — `video/ColorProfiles.kt` format builders
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/video/ColorProfiles.kt`
**Gap**: Backlog explicitly flags HLG/Log color tagging as 🟡 ("verify... document the gamut/curve we
actually emit"). The KEY/VALUE pairs set per codec/transfer combination are exactly the kind of thing
a test should pin down (e.g. "HEVC+HLG sets `COLOR_TRANSFER_HLG` + `COLOR_RANGE_LIMITED`"; "HEVC+LOG
sets `COLOR_RANGE_FULL` and intentionally does NOT set `KEY_COLOR_TRANSFER`" per the docstring) so a
future edit can't silently swap the HLG/LOG branches or drop a key. Blocked by the infra finding
(`MediaFormat.createVideoFormat` throws immediately, before any of the class's own logic even runs).
**Fix (B, Robolectric)**: Robolectric's `ShadowMediaFormat` backs `MediaFormat` with a real
`HashMap`-like store, so `setInteger`/`getInteger` round-trip correctly — this should fully unlock
testing without any production refactor.
**Test proposal** (`ColorProfilesTest.kt`, Robolectric):
- `hevcFormat(..., HLG)` sets `KEY_COLOR_TRANSFER = COLOR_TRANSFER_HLG` and `KEY_COLOR_RANGE = COLOR_RANGE_LIMITED`.
- `hevcFormat(..., LOG)` sets `KEY_COLOR_RANGE = COLOR_RANGE_FULL` and does **not** set `KEY_COLOR_TRANSFER` (assert `containsKey` is false, or catch the `getInteger` exception `MediaFormat` throws for a missing key — pin down whichever `MediaFormat`'s real behavior is, that itself is useful documentation).
- both HEVC variants set `KEY_PROFILE = HEVCProfileMain10` and `KEY_COLOR_STANDARD = COLOR_STANDARD_BT2020`.
- `avcFormat` sets `COLOR_STANDARD_BT709` + `COLOR_TRANSFER_SDR_VIDEO` + `COLOR_RANGE_LIMITED` (SDR/Rec.709 contract) and `KEY_PROFILE = AVCProfileHigh`.
- `videoFormat(codec=AVC, ...)` dispatches to `avcFormat` (not `hevcFormat`) — a simple dispatch test that also guards against a copy-paste mixup between the two branches.
- `mimeFor(HEVC) == MIMETYPE_VIDEO_HEVC`, `mimeFor(AVC) == MIMETYPE_VIDEO_AVC`.
- `aacFormat()` sets the documented sample rate/channels/bitrate/profile.
Priority: MEDIUM (explicit backlog ask, but the actual playback-correctness verification still needs
a real HDR display per the backlog note — the unit test locks the *tagging*, not the perceptual
result). Confidence: Medium (same Robolectric-shadow caveat as TEST-11, though `MediaFormat` shadow
support is generally more mature/certain than `Rational`).

### TEST-13 — `CameraEngine.fileName()` — timestamp format + BURST collision risk
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:567-570`
**Gap**: `"${prefix}_X9TELE_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"`.
Second-granularity timestamp, no injected clock, no sequence/uniqueness suffix. `captureBurst()`
(same file, lines 290-296) chains `BURST_COUNT = 5` shots, each only starting after the previous
*completes* (not fired in a tight loop), so a same-second collision needs a very fast capture
pipeline — plausible on this device, not the common case, but the filename generator provides **no
protection at all** if it happens; correctness then depends entirely on
`MediaStoreWriter.createPendingImage`'s underlying `ContentResolver.insert` auto-renaming a duplicate
`DISPLAY_NAME` (standard Android behavior, but not verified anywhere in this codebase — no test,
no comment documenting the reliance). `SimpleDateFormat`/`Date` are plain `java.text`/`java.util` —
zero Android dependency, so this is trivially testable **once the current time is injectable**.
**Fix (A)**: add a `clock: () -> Date = ::Date` (or `java.time.Clock`) default parameter to `fileName()`, matching the existing `FocusMapping`-style "pure function with a sensible default parameter" idiom already used elsewhere in this codebase (e.g. `gamma: Float = DEFAULT_GAMMA`).
**Test proposal** (`MediaFileNamingTest.kt`, or inline once extracted — note `fileName` is currently `private` inside a `Context`-requiring class, so extraction to a standalone pure function, e.g. `storage/MediaFileNaming.kt`, is required regardless of the clock-injection fix):
- format regex match: `IMG_X9TELE_\d{8}_\d{6}\.heic`.
- two calls with the **same** injected instant produce **identical** names (documents/locks the current same-second-collision behavior — a deliberate "known limitation, relies on MediaStore dedup" test rather than a silent gap).
- two calls one second apart produce different names.
- `prefix`/`ext` are both threaded through unchanged (`"VID"`/`"mp4"` vs `"IMG"`/`"heic"`/`"dng"`).
Priority: MEDIUM (low-probability real-world trigger, but a genuine correctness gap with zero current
documentation of the MediaStore-dedup reliance — worth at minimum making the reliance an explicit,
tested assumption rather than an implicit one). Confidence: Medium (I have not verified
`ContentResolver.insert`'s auto-rename behavior against this exact `RELATIVE_PATH`+`DISPLAY_NAME`
combination on this device/OS version — that part stays a device-verify item, noted in the test name/comment).

---

## LOW / LOW-MEDIUM priority

### TEST-14 — `CameraEngine.captureAeb` EV bracket step list
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:304-314`
**Gap**: `listOf(-2, 0, 2).map { it.coerceIn(range.lower, range.upper) }` — clamps the AEB bracket to
the device's actual EV-compensation range (`caps.evRange`, an `android.util.Range<Int>`). Pure once
you have `lower`/`upper` as plain `Int`s (blocked by the infra finding same as everything reading a
`Range` getter), trapped in a `Context`-requiring class besides.
**Fix (A)**: extract `fun aebSteps(lo: Int, hi: Int): List<Int> = listOf(-2, 0, 2).map { it.coerceIn(lo, hi) }`.
**Test proposal**: `aebSteps(lo=-6, hi=6) == [-2, 0, 2]` (unclamped case); `aebSteps(lo=-1, hi=1) == [-1, 0, 1]` (clamped case — also documents that a narrow-range device gets a *degenerate* (non-symmetric-looking but still 3-shot) bracket rather than fewer shots); `aebSteps(lo=0, hi=0) == [0, 0, 0]` (all three clamp to the same value — worth asserting this doesn't crash or dedupe, since `captureAeb`'s `fire()` loop fires exactly `steps.size` shots regardless of duplicate values, so a degenerate device range means 3 *identical* exposures, which is worth being an intentional, tested behavior rather than a surprise).
Priority: LOW-MEDIUM (the clamp is simple and the risk is "wastes 2 duplicate AEB shots on a
narrow-EV-range device," not a crash or data-loss). Confidence: High.

### TEST-15 — `FocusMappingTest.kt` adequacy assessment
**File**: `app/src/test/kotlin/com/hletrd/findx9tele/focus/FocusMappingTest.kt`
**Verdict: ADEQUATE.** 12 tests, all pass, 0.014s total, no flakiness risk (pure math, no timing/I/O/shared
state), one behavior per test, descriptive backtick names, appropriate float delta (`1e-3f`) used
consistently. Covers: `0`/`1` slider boundaries for a spread of `minFocusDiopters` values, monotonicity
(101-point sweep), round-trip accuracy (9 sample points at default gamma), the `minFocusDiopters <= 0`
fixed-focus branch, `gamma > 1` bias direction, `dioptersToMeters` infinity/negative/positive cases,
and slider/diopter clamping in both directions. This is a good model for the new tests recommended
above — match its style (backtick names, `delta` constant, boundary+monotonicity+round-trip pattern).
**Minor gaps** (not blocking, LOW priority to add):
- No test at `gamma = 1` (should be linear: `sliderToDiopters(0.5, min, gamma=1) == 0.5 * min` exactly) or `gamma < 1` (should bias toward the *far* end, the mirror case of the existing `gamma > 1` test) — the existing test only exercises one side of the gamma contract.
- No exact-value regression anchor — every assertion is boundary/monotonic/round-trip/relational; there's no `assertEquals(expectedPrecomputedValue, sliderToDiopters(0.5f, 8f), delta)` pinning the *actual* curve shape at the default gamma. A future accidental change to `DEFAULT_GAMMA` or the formula would pass every existing test (monotonic, round-trips, boundaries still hold) while silently changing the felt slider response on-device.
- `Float.NaN` / `Float.POSITIVE_INFINITY` as `slider` input is unspecified — Kotlin's `Float.coerceIn` has well-known quirky behavior with `NaN` (comparisons involving NaN are always false, so `coerceIn` can pass NaN through unclamped). Given this feeds a UI drag gesture (`ManualDials.kt`'s `RulerSlider`), a NaN input isn't purely theoretical (a `0/0` in the gesture math would produce one) and its current handling is untested. LOW priority, but worth one `assertTrue(sliderToDiopters(Float.NaN, min).isNaN() || ...)` test that at minimum pins down (doesn't necessarily "fix") the current behavior.

### SKIP — `ui/controls/ProControls.kt` enum→label functions
**File**: `app/src/main/kotlin/com/hletrd/findx9tele/ui/controls/ProControls.kt:246-355`
(`focusModeLabel`, `antibandingLabel`, `processingLevelLabel`, ... ~13 functions total)
**Assessment**: These are pure (`enum → String`, zero Android dependency) and trivially testable, but
I recommend **not** adding tests for them: every one is an exhaustive `when` over a closed enum with
no `else` branch, so the Kotlin compiler already refuses to build if a new enum case is added without
updating the label function — the exact failure mode a test would catch is already caught at compile
time. A test here would only catch a *wrong* string (a typo), which is low-stakes UI copy, changes
often, and is equally well caught by a device smoke-test glance at the settings sheet. Spending test
budget here has negative ROI relative to TEST-1 through TEST-14.

---

## Not unit-testable — stays manual/device-verify

Per CLAUDE.md's own framing ("Hardware behavior... needs a real screenshot or a pulled capture"),
these remain out of scope for unit tests regardless of the infra fix, because their correctness is
either genuinely hardware-dependent or requires a real Android runtime (Looper/SurfaceTexture/EGL/
MediaCodec drain loop) that no local JVM unit test — Robolectric included, in most cases — meaningfully
exercises:

- **`gl/EglCore.kt`, `gl/FlipRenderer.kt`, `gl/GlPipeline.kt`, `gl/Shaders.kt`** — real EGL context, GPU shader compilation/execution, GL thread. No JVM equivalent exists; stays 100% manual/device-verify (matches backlog's own EIS/HLG-preview items already marked 🟡/🔴 for on-device verification).
- **`camera/CameraController.kt`** — Camera2 session lifecycle, `HandlerThread`, real `CameraDevice`/`CameraCaptureSession` callbacks. The fallback-ladder *shape* (attempt 0→3, which streams drop at which attempt) is worth eyeballing in a design/architecture review (already covered in `.context/reviews/architect.md`), but exercising `configureSession`'s actual behavior needs either a real device or a very heavy Robolectric+shadow-camera2 investment I wouldn't recommend prioritizing over TEST-1 through TEST-9.
- **`stab/GyroEis.kt`** sensor-listener wiring (`onSensorChanged`, `start`/`stop`, the low-pass integration loop's *time-domain* behavior) — the low-pass corner frequency and shake-vs-pan separation are explicitly flagged in `docs/ARCHITECTURE.md` as needing on-device tuning; a unit test could exercise the low-pass math with synthetic `SensorEvent`-shaped input, but `SensorEvent` itself isn't independently constructable outside a real/Robolectric sensor stack, and the *tuning correctness* (is 0.1 the right alpha?) isn't something a unit test can answer regardless — it can only lock the arithmetic once given raw floats, which has lower value than the boundary math in TEST-5. Deprioritized versus TEST-5.
- **`video/VideoRecorder.kt`** encode/mux drain loop, `MediaCodec`/`MediaMuxer`/`AudioRecord` orchestration — needs a real or heavily-shadowed media pipeline. Only `applyGainAndLevel` (TEST-9) is extractable as pure math; the rest stays manual/device-verify.
- **`ui/CameraScreen.kt`, `ui/overlays/Overlays.kt`, `ui/theme/Theme.kt`, most of `ui/controls/ProSheet.kt`/`ManualDials.kt`/`ProControls.kt`** — Compose UI. Out of scope for JVM unit tests (would need Compose UI testing / instrumented tests, a different test type from what this review was scoped to assess); the pure non-`@Composable` helper functions embedded in these files (stop-snapping, label mappings, formatting) are the parts already broken out above (TEST-7, SKIP item).
- **`MainActivity.kt`, `TeleCameraApp.kt`** — Activity lifecycle / permission-request wiring, `Application` boilerplate. No pure logic to extract; stays manual/device-verify (matches backlog's crash/ANR-hardening item, which is explicitly a device-pass activity, not a unit-test target).
- **`camera/VendorTagInspector.kt`** — debug-only vendor-tag dump via reflection over `CameraCharacteristics`. Explicitly debug/diagnostic tooling, not production behavior; not worth unit-test investment.
- **`storage/MediaStoreWriter.kt`** — every method is a thin `ContentResolver` wrapper (`insert`/`openFileDescriptor`/`update`/`delete`) with a `runCatching` around real I/O. Nothing pure to extract beyond the filename *string* itself (TEST-13, which actually lives in `CameraEngine.fileName()`, not this file). The MediaStore dedup-on-collision behavior TEST-13 relies on is real OS behavior, not app code — stays a device-verify assumption, documented (not proven) by TEST-13's test.
- **`CameraViewModel.kt`'s countdown/timer/recording-elapsed logic** (`startCountdown`, `recordTicker`, `orientationTicker`) — all `Handler`/`SystemClock`-driven, and `CameraViewModel` extends `AndroidViewModel(app: Application)`, requiring a real/Robolectric `Application` to instantiate at all. The *policy* (ignore re-tap while a countdown is in progress; countdown decrements to 0 then fires; drive-mode change away from TIMELAPSE cancels the interval future) is worth locking eventually, but doing so needs Robolectric's shadow `Looper`/`Handler` (`ShadowLooper.idleFor(...)`) — a bigger lift than anything in this review's HIGH/MEDIUM list. If Robolectric gets added for TEST-11/TEST-12, revisit this as a follow-up rather than doing it as the reason to add Robolectric in the first place.

---

## Recommended new test files (in priority order)

1. `app/src/main/kotlin/com/hletrd/findx9tele/camera/RotationMath.kt` (new pure object, extracted from `CameraEngine`) + `app/src/test/kotlin/com/hletrd/findx9tele/camera/RotationMathTest.kt` — TEST-2, TEST-3, TEST-4.
2. `app/src/test/kotlin/com/hletrd/findx9tele/camera/CameraSelector2Test.kt` (after adding `CameraSelector2.pickBest(...)` + `equivFocalMm(...)` pure overloads) — TEST-1, TEST-10.
3. `app/src/test/kotlin/com/hletrd/findx9tele/stab/GyroEisOrientationTest.kt` (after extracting a companion `orientationFromRoll(Float): Int`) — TEST-5.
4. `app/src/test/kotlin/com/hletrd/findx9tele/camera/ManualControlsTest.kt` (after adding `kelvinTintToRggbGainsRaw(...)`; `effectiveExposureNs()` needs no refactor) — TEST-6, TEST-8.
5. `app/src/main/kotlin/com/hletrd/findx9tele/camera/ExposureStops.kt` (new file, moved out of `ManualDials.kt`) + `app/src/test/kotlin/com/hletrd/findx9tele/camera/ExposureStopsTest.kt` — TEST-7.
6. `app/src/main/kotlin/com/hletrd/findx9tele/video/PcmGain.kt` (new pure object, extracted from `VideoRecorder`) + `app/src/test/kotlin/com/hletrd/findx9tele/video/PcmGainTest.kt` — TEST-9.
7. `app/src/main/kotlin/com/hletrd/findx9tele/storage/MediaFileNaming.kt` (new pure object, extracted from `CameraEngine.fileName()`, clock-injectable) + `app/src/test/kotlin/com/hletrd/findx9tele/storage/MediaFileNamingTest.kt` — TEST-13.
8. `app/src/test/kotlin/com/hletrd/findx9tele/camera/CameraEngineHelpersTest.kt` (after extracting `aebSteps(...)`) — TEST-14.
9. *(needs Robolectric — separate track)* `app/src/test/kotlin/com/hletrd/findx9tele/camera/CaptureCapabilitiesTest.kt` — TEST-11.
10. *(needs Robolectric — separate track)* `app/src/test/kotlin/com/hletrd/findx9tele/video/ColorProfilesTest.kt` — TEST-12.

Items 1-8 need **no new test dependency** — pure-function extraction only, same pattern as
`FocusMapping.kt`. Items 9-10 need Robolectric added to `app/build.gradle.kts` first
(`testOptions { unitTests { isIncludeAndroidResources = true } }` is also typically required
alongside the Robolectric dependency — confirm the exact config needed for the specific classes
being shadowed when this track is picked up).

## Existing test file

`app/src/test/kotlin/com/hletrd/findx9tele/focus/FocusMappingTest.kt` — see TEST-15. Adequate as-is;
optional low-priority additions noted, not blocking.
