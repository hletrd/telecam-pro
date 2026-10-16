# qa-adversary review — 2026-10-02

Scope: **STATIC GATES ONLY** per operator directive. No `adb`, no device install, no emulator. All
work done against the working tree at commit `ba5b16e7` (branch `main`).

```
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/verify_host.py
```

## Result: PASS

`tools/verify_host.py` completed with exit code 0 after ~6 minutes. Every sub-gate it runs passed:

| Sub-gate | Result | Detail |
|---|---|---|
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:verifyPartitionACoverage` | PASS | `BUILD SUCCESSFUL in 4m 5s` |
| Kotlin unit tests (`testDebugUnitTest`) | PASS | 2271 tests across 269 test classes, **0 failures, 0 errors, 0 skipped** (summed from `app/build/test-results/testDebugUnitTest/*.xml`) |
| Lint (`lintDebug`) | PASS (0 errors) | 9 warnings, see below |
| Partition-A coverage (`verifyPartitionACoverage`) | PASS | Overall 19847/30449 = 65.18%; **Partition A (host-executable logic) 9552/9567 = 99.84%**, target ≥99.5%; Partition B (device-bound) 10295/20529 = 50.15%; 0/353 excluded |
| `tools/tests` (Python) | PASS | 143 tests, `Ran 143 tests in 209.832s` / `OK` |
| `tools/coverage/tests` (Python) | PASS | 9 tests, `OK` |
| `device-tests/tests` (Python) | PASS | 195 tests, `OK` (the "ERROR: unsafe state — REC state unknown" and "SKIP: requires explicit approval" lines in the log are fixtures *exercised by* this suite to prove the harness's own fault/approval-gating behavior, not a failure of the suite itself — final tally is `Ran 195 tests in 9.235s` / `OK`) |
| `tools/check_docs.py` | PASS | 188 checks, 0 failed, 0 private checks skipped |
| `python3 -m compileall -q device-tests tools` | PASS | no output (clean compile) |
| `git diff --check HEAD --` | PASS | no output (no whitespace errors in the current diff/index) |

No flaky test was observed — every suite is a single deterministic run with 0 failures/errors, and
the unit-test XML results confirm zero retries recorded.

### Compiler warnings
Because `compileDebugKotlin`, `compileDebugJavaWithJavac`, etc. were all reported **UP-TO-DATE**
(Gradle build cache hit — nothing in `app/src/main` changed since the last build), the Kotlin
compiler did not re-emit `w:` diagnostics into this run's log; none were printed. This run cannot
positively confirm the *current* compiler-warning count from this log alone — if a fresh warning
inventory is required, re-run with `--rerun-tasks` (expensive: full recompile of ~360 Kotlin files
across main/test/debug/androidTest). The lint pass (which does its own analysis independent of the
Kotlin daemon cache) is a trustworthy proxy and is reported in full below.

### Lint findings — `app/build/reports/lint-results-debug.{txt,xml,html,sarif}`
**0 errors, 9 warnings**, all pre-existing/benign, none touching capture/storage/recording logic:

1. `gradle/libs.versions.toml:6` — `AndroidGradlePluginVersion`: AGP 9.3.2 → 9.4.0 available.
2. `gradle/libs.versions.toml:8` — `NewerVersionAvailable`: Kotlin/Compose-compiler 2.4.10 → 2.4.20 available.
3. `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:1693` — `ModifierParameter`: `settingsModifier` param should be named `modifier`.
4. `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:3161` — `ModifierParameter`: `galleryModifier` param should be named `modifier`.
5. `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:3248` — `ModifierParameter`: `modifier` should be the first optional parameter.
6. `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ManualDials.kt:222` — `ModifierParameter`: `fnButtonModifier` param should be named `modifier`.
7. `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ManualDials.kt:332` — `ModifierParameter`: `fnButtonModifier` param should be named `modifier`.
8. `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:456` — `UsableSpace`: consider `StorageManager#getAllocatableBytes/allocateBytes` instead of `File.usableSpace` for the trusted-review-source size budget.
9. `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:575` — `UseKtx`: `Uri.parse(uri)` could be `uri.toUri()`.

None of these 9 are new regressions introduced by recent commits (`ba5b16e7`..`6b2f07dd`); they are
stable, low-severity style/version-currency notices. `grep` across `app/src/main/kotlin` found **no
`TODO`/`FIXME`/`XXX`** markers (the one `XXX` hit is `SimpleDateFormat("XXX", ...)`, a timezone
pattern literal, not a marker).

## Adversarial static review — capture / storage / recording admission

I read, with an adversarial/skeptical lens, the files that own the highest-risk edge cases per
`CLAUDE.md`'s own "hard-won device facts" (durable-pending writes, family tombstone/delete races,
DNG route/publication ownership, REC admission, audio degrade-to-video-only):

- `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt` (2992 lines)
- `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/storage/LatestCaptureReducer.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/RecordingAdmissionLatch.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/RecordingPreNativeAllocation.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/RecordingStorageDispatcher.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/StillPublicationDispatcher.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/FamilyDeletionMarkerDispatcher.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/DngPreCaptureAllocation.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/camera/ZslAdmission.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/capture/DngCapture.kt`, `HeifCapture.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/ui/CaptureOutputTracker.kt`
- `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt` (2380 lines)
- `app/src/main/kotlin/me/hletrd/telecampro/camera/ManualControls.kt` (the shutter-unit-switch fix)

### Finding 1 (LOW confidence — structural risk, not a reproducible defect today)

**File:** `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:587` and `:859`
**Scenario:** Both the video-drain loop (`drainVideoLoop`, line 587) and the audio-encode loop
(`runAudio`, line 859) handle `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED` with a **hard non-null
assertion**: `videoTrack = muxer!!.addTrack(codec.outputFormat)` / `audioTrack =
muxer!!.addTrack(codec.outputFormat)`. Three lines later (874), the sample-write path in the same
audio loop uses the **null-safe** form instead: `muxer?.writeSampleData(audioTrack, buf, info)`,
immediately followed by an *unconditional* `wroteAudioSample = true` regardless of whether the
write actually executed.

I traced every place `muxer` is set to `null` (`VideoRecorder.kt:328`, `:476`) and found both occur
only in `stopNative()`'s clean path, which runs `videoThread?.join(3000)` / `audioThread?.join(3000)`
**before** nulling `muxer` — and the wedge/quarantine path (`quarantine()`) deliberately never nulls
`muxer` at all ("Keep every graph owner ... intact"). So under the *current* lifecycle invariants,
`muxer` cannot legitimately be null while either drain loop is still executing, and even if that
invariant were ever violated, the resulting `KotlinNullPointerException` would be caught by the
enclosing `catch (t: Exception)` in `drainVideo()` (→ `recordFailure`) or the `audioThread` wrapper
(→ `degradeAudioToVideoOnly`) — i.e. it would NOT crash the app, only misreport which failure path
fired.

**Why I'm flagging it anyway:** the asymmetric `!!` vs `?.` on two code paths that are otherwise
treated identically (both are "muxer may not exist yet/anymore" guards) is the kind of inconsistency
that survives a refactor badly — e.g., if a future change reorders `muxer = null` to run *before*
`videoThread?.join()`/`audioThread?.join()` (plausible if someone "simplifies" `stopNative()`), the
video path's `muxer!!` would go from "theoretically dead code" to "live NPE on every stop race,"
while the audio path's `muxer?.` would silently no-op and skip muxing a sample instead (also wrong,
but silently so — `wroteAudioSample` is set `true` even when the write was skipped, which would
misreport a successful audio mux that didn't happen).
**Confidence: LOW** — not reproducible today; flagging as a maintenance hazard worth a style pass
(use the same null-handling idiom in both call sites), not a shippable bug.

### Reviewed and found correct (no defect)

- **`ManualControls.withShutterMode` (`ManualControls.kt:447-468`)** — the just-landed fix (commit
  `f67023d5`) for the Speed↔Angle shutter unit toggle. The one edge case I probed (`fps <= 0` falls
  back to the stale `shutterAngle` rather than converting) is **already covered and intentional** —
  pinned by `ShutterModeConversionTest.kt`'s `"the same unit is a no-op and an unknown frame rate
  keeps the stored angle"` test. `fps` defaults to `30` and is always a positive, user-selected video
  rate in practice, so this fallback is not reachable in normal operation. Not re-reported as new.
- **`RecordingAdmissionLatch`** — the exactly-once stop-latch/admission state machine is correct: all
  three transitions (`tryBeginAdmission`/`requestStop`/`completeAdmission`) share one monitor, so a
  stop cannot interleave between an admission's completion check and latch consumption.
- **`RecordingPreNativeAllocationAttempt`** (`RecordingPreNativeAllocation.kt`) — the
  WAITING→ALLOCATED→CLAIMED/RETIRED state machine correctly routes a late-arriving allocation to
  `onLateValue` exactly once whether it arrives before or after `retire()`, and `claim()` is
  exact-once (`state != ALLOCATED` returns `null`).
- **`CaptureOutputTracker.record`/`beginDelete`/`restoreDeleteSurvivors`** — traced the documented
  "trimming can evict the capture that was just added" race (a late sibling of an old id arriving
  while ordinary history is full): the post-insert `captureByOutput[output] != captureId` recheck
  after `trimCaptures()` correctly demotes that case to `TRACK_ONLY` rather than letting an evicted
  capture become the review owner.
- **`ZslAdmission.zslFrameAdmissible`** — pure predicate; all five admission checks (age, exposure,
  ISO, zoom, intent eligibility) are independently guarded against zero/negative/null inputs before
  the stop-ratio comparisons; no divide-by-zero or NaN propagation path found.
- **`StillPublicationDispatcher.dispatchRecoverable`** — the `AtomicBoolean` `terminalDelivered` CAS
  guard correctly makes `onTerminal()` exactly-once even though both the `Runnable`'s `finally` block
  and the synchronous `onRejected` path can reach `terminalOnce()`.
- **`FamilyDeletionMarkerCapacityOwner`** — the `Semaphore(workerCount + backlogCapacity)` admission
  count and the `ThreadPoolExecutor`'s own bounded queue are kept in lockstep via
  `releaseReservation()` in a `finally` around `task.run()`, including the `RejectedExecutionException`
  path; permit overflow is guarded by an explicit `check()`.
- **`DngCapture.writeDng` / `HeifCapture.writeHeif`** — both correctly release their native
  `DngCreator`/`HeifWriter` resources in a `finally` block on every exit path, including mid-write
  exceptions.
- **`PendingDiscardJournal`'s `resolveVolumeName`** (the subject of commit `6b2f07dd`) — the
  hard-coded `VOLUME_EXTERNAL → VOLUME_EXTERNAL_PRIMARY` mapping is correct: every insert in this
  codebase goes through the non-volume-specific `MediaStore.{Images,Video}.Media.EXTERNAL_CONTENT_URI`
  (confirmed by `grep` — `insertPendingImage`/`createPendingVideoAllocation` both use it exclusively),
  and per documented `MediaProvider` behavior that always resolves to primary external storage, never
  a secondary/adopted volume. Not a residual bug from the recent fix.
- **`exifAttributeList` (`StillCapturePipeline.kt:608-689`)** — `TAG_EXPOSURE_TIME` is written as a
  plain decimal string rather than a `"num/den"` rational; confirmed this is accepted by
  `androidx.exifinterface` (which parses decimal strings for RATIONAL tags) and is pinned byte-exact
  against the stock-camera reference sample in `ExifAttributeListTest.kt`. Not a defect.

### Net adversarial-pass verdict

No medium- or high-confidence correctness bugs were found in the capture/storage/recording
admission paths reviewed. This codebase's own commit history (`6b2f07dd`, `2eb57e4e`, `0ab5c1ba`,
`4979a09a`, `b73dc0d0`, etc.) shows an active, fast-iterating bug-fixing cadence against
device-found races in exactly this area, each landing with a host-executable regression test — the
2271-test unit suite and 99.84% Partition-A coverage are consistent with that pattern holding. The
one LOW-confidence item above (Finding 1) is a style/consistency note, not a blocking defect.

## Verdict

**GATE 1: PASS.** Build, lint (0 errors), full Kotlin unit suite (2271/2271), Python tool/coverage/
device-tests suites (347/347 combined), docs-consistency gate (188/188), Partition-A coverage
(99.84% ≥ 99.5% target), and the repo whitespace-diff check all pass cleanly on commit `ba5b16e7`.

**Device work (Gate 2, Gate 3, Gate 4): BLOCKED BY DIRECTIVE** — this run was explicitly scoped to
static gates only; no `ANDROID_SERIAL` was supplied and no `adb`/install/launch was attempted, per
the operator's instruction. No device evidence is fabricated or recycled from a prior session.
