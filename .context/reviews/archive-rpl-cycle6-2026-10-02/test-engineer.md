# RPL cycle 6 — test-engineer review

- Agent: test-engineer (c6), finding prefix TE6-
- HEAD: 30970c9e (cycle-5 range ea7d4374..30970c9e)
- Mode: read-only. No Gradle or host-gate runs; every verdict below comes from reading code and diffs.

## Scope inventory

- All 37 `fix(...)` commits in the cycle-5 range. For each one I compared the production diff with
  the test it added, asking one question: "would this test stay green if the production hunk were
  reverted?"
- Test-side sweep:
  - `app/src/test` (sleep, negative-window `await`, and positive short-deadline patterns)
  - `app/src/androidTest` (probe and smoke sleeps only; no cycle-5 change)
  - `tools/tests` (skip conditions, and the AGP pin under `TELECAM_HOST_GATE`)
- Production files I read for these findings:
  - `CameraController.kt`: still capture callbacks, `failStill`, `tryComplete`, `applyAfOverrides`
  - `VideoRecorder.kt`: `codecCleanup` wiring
  - `RecordingStorageDispatcher.kt` and `CameraEngine.presentRecordingStorageResult`
  - `RetainedStillDeletionOwner.kt`
  - `StillCapturePipeline.kt`: passthrough EXIF lane and `writeJpegOnce`
  - `MediaStoreWriter.recoveryVideoVerdict`
  - `OpticsRouteInputTransactionRobolectricTest`, `CameraEngineDngInvalidationTest`, `ProcessAdmissionSignalTest`

Cycle-5 commits whose new tests do drive the fixed path and would fail on a revert:
- AGG5-5 (`CameraEngineDngInvalidationTest`): the settle-time `readyController` starts non-null, so
  a missing settle also fails.
- AGG5-8 (fresh-descriptor reparse)
- AGG5-51: the composed-payload strip only. See TE6-2 for the gap.
- MRG5-1/7 (RAW-loss fold), MRG5-5 (the first-order case only), AGG5-64, AGG5-19, AGG5-67

## Findings

| ID | Severity | Confidence | Status | Summary |
|---|---|---|---|---|
| TE6-1 | Medium | High | confirmed | The AGG5-3 codec-cleanup tests re-implement the production wiring in test code. Reverting any of the four production edits (both error latches, the `codecCleanup` call sites, the EOS mic stop) leaves every test green. |
| TE6-2 | Low (privacy; dormant on PMA110) | High | confirmed | The AGG5-51 strip covers only the composed payload. If EXIF composition fails or the splice is refused, the passthrough lane writes the HAL JPEG verbatim, including its own APP1 with GPS, serials and MakerNote. |
| TE6-3 | Low–Medium | Medium | likely | The MRG5-5 `producerTerminalIds` set is trimmed oldest-first by every capture's terminal edge. If a failed marker lands after 32 later terminal edges, still admission closes for the life of the process. |
| TE6-4 | Low | High | confirmed | The AGG5-29 tests never pass live controls, so reverting the still to `applyAfOverrides(this, controls)` stays green. The fix also leaves `touchAfActive` and `lastFocusDistance` live, so the freeze is only partial. |
| TE6-5 | Low | High | confirmed | AGG5-28 has only an identity-predicate test. `failStill`, `onCaptureBufferLost` and `onCaptureSequenceAborted` have no test, so deleting either override stays green. |
| TE6-6 | Low | High | confirmed | AGG5-41 has a truth-table test only. Inverting or removing the `tryComplete` call site stays green, and nothing tests that the Engine's `checkNotNull(rawChars)` is unreachable. |
| TE6-7 | Low | High | confirmed | AGG5-39 tests the reducer and policy only. Reverting the Engine to `publish(...)`, which drops stale terminals, stays green. |
| TE6-8 | Low | High | confirmed | The MRG5-6 test asserts that the returned value equals the current generation after return. That is equally true of the reverted read-back code, so the race is never exercised. |
| TE6-9 | Info | High | confirmed | 50–100 ms negative windows can pass vacuously under load. These remain after AGG5-64 fixed `ProcessAdmissionSignalTest`. |
| TE6-10 | Info | Medium | confirmed | `RejectedOutputCleanupDispatcherTest:30` uses a 250 ms positive deadline, which can flake on a loaded CI host. |

---

### TE6-1 — AGG5-3 codec cleanup: the tests model the wiring instead of exercising it

**Cites**
- `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt`:
  - :466 (EOS `codecCleanup`)
  - :472 area (`stopAudioInputBeforeQuarantine()`)
  - :604/:607 (STOP via `codecCleanup`)
  - :672 (`videoCodecErrorLatched.set(true)`)
  - :813 (`audioCodecErrorLatched.set(true)`)
  - the configure catch `owner.errorLatched = true` in the attempt ladder
- `app/src/test/kotlin/me/hletrd/telecampro/video/RecorderQuarantineAdmissionGateTest.kt`:
  - `only a state throw from a non-release call on an errored codec skips to release`
  - `an errored codec's stop throw lets the fake graph finish releasing every owner`

**Why it is a problem**
- The first test covers the pure `codecCleanupDecision`. That part is fine.
- The "fake graph" test builds its own `when (owner)` mapping, from `VIDEO_CODEC_STOP` to `CodecCleanupCall.STOP`. It also passes `videoErrorLatched` as a literal.
- As a result, none of the five production edits that make up the fix is executed:
  - the two latch sets in the drain catch blocks
  - the configure/start catch
  - the switch of `VIDEO_CODEC_STOP`/`AUDIO_CODEC_STOP` and EOS to `codecCleanup`
  - `stopAudioInputBeforeQuarantine()`

**Failure scenario:** someone deletes `videoCodecErrorLatched.set(true)` or `stopAudioInputBeforeQuarantine()`.
- The whole suite stays green.
- On device, an Error-state codec quarantines the process again, and the mic stays held with the
  privacy indicator lit. AGG5-3 regresses silently.

**Tests to add** (`VideoRecorderCodecCleanupTest`, Robolectric or a JVM test with an injected codec seam):
1. Drive `finishNativeStop` through a recorder whose fake video `MediaCodec` throws a `CodecException`
   from `dequeueOutputBuffer` in the drain loop. Then:
   - `signalEndOfInputStream()`/`stop()` throw `IllegalStateException`
   - `release()` succeeds

   Assert:
   - `unsafeStartupFailure() == null`
   - not terminally quarantined
   - `release()` was called once
2. The same fixture, but the drain completed cleanly (no latch). Assert that the stop throw does quarantine.
3. EOS throw with no latch. Assert that the fake `AudioRecord.stop()` was called before
   `quarantinedNativeStopResult()` returned. This test pins AGG5-3's mic-release half.
4. Make `configure()` throw. Assert that the `releaseRejected` path reaches `codec.release()` even
   when `stop()` throws.

If the production `MediaCodec` is not injectable, the minimum change is a narrow
`CodecHandle`/`AudioInputHandle` seam. A copy of the switch in test code cannot detect a regression.

### TE6-2 — The passthrough privacy strip is bypassed on every fallback lane

**Cites**
- `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt`:
  - :400–410 (`writePassthroughJpeg`: `bestEffortHeifExif` returns null on a compose failure)
  - :360–376 (`writeSingleJpeg`)
  - `writeJpegOnce` :~795–809 (`out.write(encoded, 0, length)` when `exifPayload == null`, or when
    `exifSplicePlan(...)` returns null)

**Why it is a problem**
- AGG5-51 blanks the identifying tags only inside `composeStillExifApp1`. In the passthrough lane,
  `encoded` IS the HAL JPEG, still carrying its original APP1.
- Two conditions make the lane write those bytes unchanged:
  - Any EXIF composition failure, such as a full cache or an `ExifInterface` save error. The last
    tier `compose(null)` can also throw.
  - Any splice refusal, such as a HAL JPEG whose header walk `exifSplicePlan` rejects.
- In both cases the file goes out with the HAL's GPS directory, body/lens serials, owner name,
  unique ID and MakerNote. Those are exactly the tags the fix exists to remove.

**Failure scenario:** on a hi-res-capable device whose HAL embeds a cached GPS fix, a passthrough
shot taken with a nearly full cache saves the JPEG with the HAL's GPS tags. The app holds no
location permission.

**Fix**
- On the fallback, never write `encoded` verbatim when it carries an Exif APP1.
- Instead, splice the APP1 out with no replacement. `exifSplicePlan` already yields the kept ranges.
- If the plan itself is null, write an APP1-stripped copy, or refuse and retain the take for recovery.

**Tests to add** (`JpegExifSpliceTest`):
- `a passthrough whose EXIF composition fails still drops the HAL APP1`: call `writeJpegOnce` with
  `exifPayload = null` over the AGG5-51 GPS/serial fixture. Assert that the output has no Exif APP1,
  or at least that `ExifInterface(out).latLong == null` and the serial tag is null.
- A second test for the splice-refused branch.

Both tests fail today.

### TE6-3 — `producerTerminalIds` evicts a tombstoned id before its failed marker lands

**Cites**
- `app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDeletionOwner.kt`:
  - :129–134 (every terminal edge appends, then trims oldest-first at `maxTombstones`)
  - :117 (`captureId !in producerTerminalIds` decides fail-closed)
- `CameraEngine.kt:5841`: `markStillProducersTerminal` runs for EVERY still capture, not only
  tombstoned ones.
- `CameraEngine.kt:8392`: `MAX_RETAINED_STILL_DELETE_TOMBSTONES = 32`.

**Why it is a problem**
- MRG5-5 records terminality in a set bounded by the count of ALL terminal captures.
- A deleted id's failed durable marker can arrive after the marker dispatcher's bounded retries.
  If 32 or more later captures (a BURST, AEB, or timelapse run) have reached terminal by then, the
  deleted id has already left `producerTerminalIds`. The id is still in `tombstones`, which trims
  only on deletions.
- `completeDeletionDurability(id, durable = false)` then adds the id to `nonDurableLiveDeletions`.
  No further producer edge will ever come for it, so `canAdmitCapture()` stays false for the rest
  of the process. This is the same "admission closed forever" class MRG5-5 fixed, reached by a
  different ordering.

**Fix**
- Retain the terminal bit for as long as the id is in `tombstones`. Two options:
  - Evict only non-tombstoned ids first.
  - Store terminality only for tombstoned ids, and record it at `markCaptureDeletedInMemory` time
    when the id is already terminal.

**Test to add** (`RetainedStillDeletionOwnerTest`): `a failed marker after more than maxTombstones later terminal edges leaves admission open`.
1. Create the owner with `maxTombstones = 4`.
2. Call `markCaptureDeletedInMemory(601)`, then `markCaptureProducersTerminal(601)`.
3. Call `markCaptureProducersTerminal(602..606)`.
4. Call `completeDeletionDurability(601, durable = false)`.
5. Assert `canAdmitCapture()`. This fails today.

Device validation needs the marker commit to be slowed, for example with a debug fault hook.

### TE6-4 — The AGG5-29 test never involves live controls, and the freeze is partial

**Cites**
- `CameraController.kt:2129`: `applyAfOverrides(this, requestControls)`
- `CameraController.kt:~1785–1792`: the override still reads LIVE `touchAfActive` and `lastFocusDistance`
- `AfOverrideTest`:
  - `a frozen AF-locked packet keeps its lock whatever the live controls became`
  - `a frozen MANUAL packet ...`

**Why it is a problem**
- Both tests call the pure `afOverrideForRequest` with one packet. Nothing distinguishes "frozen"
  from "live", so reverting line 2129 to `controls` stays green. The first test's name claims a
  property that it never sets up.
- The fix also freezes only `afLock` and `focusMode`. Two inputs are still live:
  - A tap landing during the up-to-8 s DNG pre-allocation flips `touchAfActive`. The press then
    fires in `AF_MODE_AUTO`, although it was taken in CONTINUOUS.
  - A lock released and re-engaged at a new distance makes `LockAt` use the newer `lastFocusDistance`.

**Fix**
- Snapshot `touchAfActive` and `lastFocusDistance` with the frozen packet at shutter time (in the
  `Pending`, or in `frozenControls`' companion).

**Test to add:** a test-only `stillAfOverrideInputs(frozen, live)` seam, or a Robolectric
`CameraController.takePhoto(frozenControls = locked)` that runs after
`updateControls(live = unlocked)`. Read the built `CaptureRequest` through a capturing fake
session, and assert `CONTROL_AF_MODE == OFF` and `LENS_FOCUS_DISTANCE == the press-time distance`.

### TE6-5 — The AGG5-28 buffer-loss and sequence-abort terminals are unexercised

**Cites**
- `CameraController.kt:2208–2224` (the two new overrides) and `failStill` :2283
- `AfOverrideTest`: `buffer loss fails the shot only for one of its own targets`

**Why it is a problem**
- The only test checks `any { it === target }`. Three changes would all stay green:
  - deleting either new override
  - calling `failStill` without the `done` recheck
  - firing `cb.onError` twice (both the abort and the watchdog)

**Test to add** (Robolectric `CameraControllerStillTerminalTest`):
- Capture the `CaptureCallback` from a fake `CameraCaptureSession.capture`, then invoke
  `onCaptureBufferLost(jpegSurface)`.
- Assert exactly one `onError("Capture buffer lost")`, `pending == null`, and that the next
  `takePhoto` is admitted, not refused with "Capture already in progress".
- Repeat with `onCaptureSequenceAborted`.
- Add a negative case: buffer loss on a preview surface leaves the shot pending.

### TE6-6 — The AGG5-41 call site is unpinned

**Cites**
- `CameraController.kt:2345–2350`
- `CameraEngine.kt:6061` (`checkNotNull(rawChars)`)
- `AfOverrideTest`: `missing characteristics fail only a shot that wants RAW`

**Why it is a problem:** the truth table passes even if `tryComplete` still calls `onError` for
every null `chars`, which is the pre-fix behavior.

**Test to add:** with the same capturing-session harness as TE6-5, make the characteristics reader
return null. Assert that a JPEG-only shot reaches `onPhoto(jpeg, null, result, null, …)`, and that
a RAW shot reaches `onError("Missing camera characteristics")`.

### TE6-7 — The AGG5-39 Engine wiring is untested

**Cites:** `CameraEngine.kt:~7676–7695` (`presentRecordingStorageResult` → `publishOrStale`), and the
two new tests in `RecordingStorageDispatcherTest`.

**Why it is a problem:** reverting the Engine to `recordingStoragePresentation.publish(terminal, present = …)`
drops every stale clip terminal again, with no FAILED status and no review registration, and all
tests stay green.

**Test to add:** an Engine-level test.
1. `observeCapture(10)`, then an in-REC snapshot that calls `observeCapture(11)`.
2. Invoke `presentRecordingStorageResult(terminal(10, FAILED))` by reflection, the same way
   `CameraEngineDngInvalidationTest` does.
3. Assert `onStatus` received `VIDEO_SAVE_FAILED`.
4. For a SAVED terminal 10, assert `onMediaSaved(uri, 10)` fired and no status was sent.

### TE6-8 — The MRG5-6 race is not exercised

**Cites**
- `OpticsRouteInputTransactionRobolectricTest`: the `setResolvedOptics` helper asserts
  `began == opticsIntentGeneration.get()`.
- `CameraViewModel` recall rollback keying (4635bee2)

**Why it is a problem**
- The defect was an intent bump between `setResolvedOptics` returning and the ViewModel reading the
  generation back.
- The new assertion runs with no interleaving, so it also holds for the reverted read-back. The
  ViewModel side has no test where a generation bump separates the two.

**Test to add:** a ViewModel Robolectric test with an engine hook, for example
`afterResolvedOpticsForTest`, that bumps `opticsIntentGeneration`. A lens tap from that hook would
work. Then fail that recall's transaction, and assert that the AGG5-23/24 restores still run for
the recall's own generation.

### TE6-9 — Negative-window waits can still pass vacuously

**Cites**
- `CameraEngineRecordingPreNativeTest.kt`: :670, :712, :741, :780, :823, :909
- `StatusPublicationOwnershipTest.kt:45`
- `ReconfigurationGenerationTest.kt:839`
- `RecordingTeardownTerminalGateTest.kt:197`
- `RecorderQuarantineAdmissionGateTest.kt`: :640, :692, :740
- `StandbyAudioControllerTest.kt`: :383, :384, :1236

**Why it is a problem**
- Each test asserts `assertFalse(latch.await(25–100 ms))` and then later asserts `assertTrue(latch.await(…))`.
- If the guarded event fires too early but only after the window (a slow scheduler), both asserts
  pass. The ordering bug goes undetected.
- AGG5-64 fixed exactly this pattern in `ProcessAdmissionSignalTest` only.

**Fix:** record the guard at event time and assert it afterwards. For example:
`onReplay = { replayedAfterRelease.set(setupReleased.get()); replayed.countDown() }`, then
`assertTrue(replayedAfterRelease.get())`. Where the blocked party is a thread, use the
`awaitBlocked` helper from 825fd87b.

### TE6-10 — A short positive deadline

**Cite:** `RejectedOutputCleanupDispatcherTest.kt:30` (`returned.get(250, TimeUnit.MILLISECONDS)`).

**Why it is a problem:** the test is about a non-blocking return. 250 ms on a loaded CI JVM, during
first class-load of the executor path, can time out and fail a correct build.

**Fix:** use a generous bound such as 5 s. Prove the non-blocking property by ordering instead:
`assertTrue(returned.get(5, SECONDS))` while `release` is still held, which already proves it
returned before the provider work finished.
