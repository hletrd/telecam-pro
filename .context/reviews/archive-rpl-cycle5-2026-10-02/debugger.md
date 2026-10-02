# Debugger review — RPL cycle 5 (2026-10-02)

Role: debugger — latent bug surface and failure modes (swallowed exceptions, resource leaks,
null/empty/NaN/overflow edges, persistence, off-by-one, exhaustiveness, lateinit, races, cycle-4
regressions). ID prefix `DB5-`. Read-only; no source, test, doc, or plan file was modified, and Gradle
was not run.

## Scope and method

- Read `CLAUDE.md`, the cycle-4 plan (`docs/plans/2026-10-02-rpl-cycle4.md`: later-cycle, carried,
  deferred lists) and the cycle-4 aggregate (`AGG4-1..83`) first, so nothing below re-reports a
  tracked item as new. Owner decisions in the CLAUDE.md "Hard-won device facts" were treated as
  authoritative.
- Inventory: the whole `app/src/main/kotlin/me/hletrd/telecampro` tree (~63k lines), split into
  five slices (camera core; GL/video/audio; storage/capture/review; UI/VM/Activity; `CameraEngine.kt`).
  Each slice started from `git diff 887d39fb..ea7d4374` (cycle-4 regressions), then examined the
  remaining code with the debugger checklist. Four slices were swept by read-only helper passes; every
  finding of Medium or Low-Medium severity below was then re-verified by me directly against the code
  (line numbers re-checked with `grep -n`/`sed -n`). Findings I could not substantiate were dropped.
- Cross-file interactions traced: recorder stop/cleanup ↔ Engine quarantine; still capture callback ↔
  watchdog; family-delete marker ↔ still admission; Focus-ruler assist ↔ punch-in ownership ↔ momentary
  holds ↔ MR recall; recall ↔ audio-denial reconciliation ↔ MR indicator; frozen shot packet ↔ AF
  override; HLG10 session ↔ shader source decode ↔ gamut matrices.

## Summary

| ID | Title | Sev | Conf | Status |
|---|---|---|---|---|
| DB5-1 | A codec error makes recorder cleanup throw, which quarantines the whole process (camera dead until restart) | Medium | Medium-High | Likely |
| DB5-2 | Every 10-bit (HLG10-source) video take converts BT.2020 camera colour with BT.709-input matrices | Medium | Medium | Needs device validation |
| DB5-3 | One failed family-delete durability latches still-capture admission closed for the Engine's lifetime | Low-Medium | High | Confirmed |
| DB5-4 | A lost still output buffer is not observed; the shutter is wedged for the full 8–12 s watchdog | Low-Medium | High | Confirmed (HAL trigger needs device) |
| DB5-5 | Focus-ruler close turns off an operator-owned or recalled punch-in (assist ownership not checked) | Low-Medium | High | Confirmed |
| DB5-6 | A still built from FROZEN controls takes its AF mode/lock override from the LIVE controls | Low | High | Confirmed |
| DB5-7 | Cycle-4 regression (AGG4-9): recalling a denial-silent bank with the mic granted clears the MR indicator in the same tick | Low | High | Confirmed |
| DB5-8 | AGG4-73 residual: the Shoot-tab Zoom slider still reads the lens-local scale on rear standalone routes | Low | High | Confirmed |
| DB5-9 | AGG4-28 change: a durable COMPLETE row with provider SIZE ≤ 0 and an INDETERMINATE probe is kept but never re-armed → expires | Low | Medium | Needs device validation |
| DB5-10 | One failed `MediaCodecList` scan latches an EMPTY codec inventory for the process (no video, no HEIF) | Low | High | Confirmed |
| DB5-11 | `VideoRecorder.start` refuses an empty/mixed candidate list with no app log line | Low | High | Confirmed |
| DB5-12 | `isoStops`/`shutterStops` loop ~2^31 times in composition when an advertised lower bound is 0 | Low | High | Confirmed (malformed-HAL trigger) |
| DB5-13 | DNG pre-capture `cancel()` racing `start()` dispatches an orphan provider insert after the admission lease was released | Low | Medium | Likely |
| DB5-14 | Route-inventory route change resets UI zoom to 1× but leaves the coalesced zoom base stale | Low | Medium | Likely |
| DB5-15 | Cycle-4 regression: processed JPEG lane buffers the whole encode in a default-sized `ByteArrayOutputStream` | Low | High | Confirmed |
| DB5-16 | Debug capability dump spends the reserved 120-row warning/error budget on every Engine start | Low (debug only) | Medium | Confirmed |
| DB5-17 | AGG4-24 aspect-first YUV still size can silently pick a quarter-resolution still on a non-4:3 array | Low | Medium | Needs device validation |
| DB5-18 | Info: stale `FinalizedRecordingValidation` KDoc; journal-UNAVAILABLE re-arm per retry; dormant gyro gap interpolation | Info | — | Confirmed |

Counts: Critical 0, High 0, Medium 2, Low-Medium 3, Low 12, Info 1 (DB5-18 groups three notes).

---

## DB5-1. A codec error makes recorder cleanup throw, which quarantines the whole process [Medium]

- **Confidence:** Medium-High. **Status:** Likely (the MediaCodec state machine is documented; the
  exact exception on this QTI encoder needs one device repro).
- **Cites:**
  - `video/VideoRecorder.kt:190-200` `nativeCleanup` — ANY throw from the block is recorded as
    `nativeCleanupFailure` ("native release unproven") and every later cleanup phase is skipped.
  - `video/VideoRecorder.kt:405-410` `stopNative`: `nativeCleanup { videoCodec?.signalEndOfInputStream() }`
    is the FIRST step; a false return goes straight to `quarantinedNativeStopResult()` — before
    `AUDIO_INPUT_STOP` (`:414`).
  - `video/VideoRecorder.kt:532-541` `releaseRecorderNativeOwners`: `VIDEO_CODEC_STOP` /
    `AUDIO_CODEC_STOP` run `codec.stop()` under the same rule.
  - `video/VideoRecorder.kt:308-314` `releaseRejected` (encoder size ladder): `owner.codec.stop()` under
    `nativeCleanup`; a throw makes `codecReleased` false and sets `unsafeStartupFailure()`.
  - Engine: `camera/CameraEngine.kt` `NativeGraphDisposition.QUARANTINE_REQUIRED` handling →
    `enterUnsafeRecorderQuarantine` (process-long recorder retention, admission closed,
    `UNSAFE_RECORDER_RESTART` status).
- **Why:** after a fatal `MediaCodec` error (`onError`/`CodecException` from `dequeueOutputBuffer`,
  which `drainVideo` explicitly anticipates and routes to `recordFailure`) the codec is in the Error
  state, where only `reset()`/`release()` are legal; `signalEndOfInputStream()` and `stop()` throw
  `IllegalStateException`/`CodecException`. Those are ordinary Java-level throws, not evidence that a
  native owner is still running, yet `nativeCleanup` treats them exactly like an unproven release.
- **Failure scenarios:**
  1. Video: an encoder error fires mid-take → `recordFailure` → owner calls `stopNative` →
     `signalEndOfInputStream` throws → quarantine. The camera/REC is unusable until the process is
     killed, and because the return happens before `AUDIO_INPUT_STOP`, the `AudioRecord` is never
     stopped: the mic stays held (privacy indicator on) for the life of the process.
  2. Audio: an AAC codec throw is deliberately degraded to video-only (AGG3-2) to save the take; at
     stop, `AUDIO_CODEC_STOP` on the errored AAC codec throws → quarantine → the cleanly-muxed video is
     never handed to the storage tail (no publication), and a restart is required.
  3. Ladder: a candidate whose `start()` failed with a `CodecException` leaves that codec errored;
     `releaseRejected` → `stop()` throws → the next rung is never tried and startup quarantines.
- **Fix (host-testable):** a pure classifier for cleanup throws: an `IllegalStateException` /
  `MediaCodec.CodecException` from a NON-release call (`signalEndOfInputStream`, `stop`) on a codec
  that already latched an error (`firstFailure` set, or the drain thread exited normally) is
  "skip to `release()`", not "unproven". Keep quarantine for hangs, for a refused/failed `release()`,
  and for a live drain thread. Always run `AUDIO_INPUT_STOP` before giving up on later phases. Test with a
  fake native graph whose `signalEndOfInputStream`/`stop` throw after `recordFailure`.
- **PMA110:** changes only the codec-error paths (healthy takes byte-identical).

## DB5-2. Every 10-bit video take converts BT.2020 camera colour with BT.709-input matrices [Medium]

- **Confidence:** Medium. **Status:** Needs device validation (colour-chart A/B).
- **Cites:** `gl/Shaders.kt:133-150` `sourceLinear` (HLG branch undoes only the transfer); `:153-158`
  `toRec2020` (BT.709→BT.2020); `:161-183` `toSGamut3`/`toSGamut3Cine`/`toAwg3` (all "BT.709 ->");
  `:228-250` every non-SDR branch feeds `sourceLinear(color)` straight into those 709-input matrices.
  CPU anchors share the assumption: `gl/LogProfiles.kt` `encode(..., sourceHlg)` and
  `gl/SdrToHlgMapping.kt`.
- **Why:** `tenBitSessionWanted(videoMode, transfer)` configures an HLG10 session for every non-SDR
  curve, and `sourceHlg = 1` then. HLG10 buffers carry BT.2020 primaries; an OES external sampler
  converts YCbCr→RGB but performs no gamut mapping, so the sampled RGB is in BT.2020 primaries. The
  shader linearises the HLG transfer correctly, then treats the result as BT.709-primary light:
  - HLG output: `toRec2020` is applied to data that is already BT.2020, so the HLG→HLG path, which
    should be an identity on primaries, compresses saturation (a BT.2020 red (1,0,0) becomes
    ≈(0.627, 0.069, 0.016) before the OETF).
  - S-Log3 / S-Log3.Cine / LogC3: the 709→S-Gamut3/S-Gamut3.Cine/AWG3 matrix is applied to
    BT.2020-primary data, so every recorded log file carries the wrong gamut.
- **Not tracked:** AGG-11 (deferred) is the preview/meter code-domain question in 10-bit sessions; it
  does not cover the encode-branch source gamut. The 2026-07-29 device check judged brightness and
  flatness, which this error leaves plausible.
- **Failure scenario:** Video with HLG (the default `ExtraSettings.transfer`) or any log curve →
  desaturated / wrong-gamut footage in every take, on every device that grants HLG10.
- **Fix (host-testable):** add a source-gamut term. When `uSourceHlg == 1`, convert with
  BT.2020→target (identity for HLG output; `M(target←709)·M(709←2020)` for the log gamuts), single-
  sourced from `LogProfiles`/`SdrToHlgMapping` constants. Host test: HLG-source → HLG output is the
  identity on saturated primaries; HLG-source → S-Gamut3 equals the composed matrix. Then do a device
  colour-chart A/B to confirm the sampler keeps BT.2020 primaries on PMA110.
- **PMA110:** yes, it changes the colour of non-SDR video (that change is the point); SDR video and
  photo are untouched.

## DB5-3. One failed family-delete durability latches still capture closed for the Engine's lifetime [Low-Medium]

- **Confidence:** High. **Status:** Confirmed.
- **Cites:** `camera/RetainedStillDeletionOwner.kt:56` (`deletionJournalUnavailable`), `:101-111`
  (`completeDeletionDurability(durable = false)` sets it), `:244-246` (`canAdmitCapture()` reads it);
  no reset site exists. Producers of `durable = false`: `camera/CameraEngine.kt:5434-5436` (marker
  commit not DURABLE or retirement registry CAPACITY_EXHAUSTED), `:5453-5457` (any throw), `:5471`
  (submit failure). Consumer: `stillOutputAdmissionAvailable()` (`CameraEngine.kt:5476+`).
- **Why:** the comment says admission closes "until process recovery", but nothing ever re-opens it,
  and the Engine lives as long as the ViewModel, so it survives configuration changes and
  background/foreground. `SettingsStore`-style commit failures are transient by the project's own
  account (AGG-32), and the ViewModel already returns FAILED so the user can retry the delete.
- **Failure scenario:** with storage nearly full the user deletes the latest photo in review; the
  marker commit fails 3× → `durable = false` → the photo and hardware shutters grey out. The user frees
  space and retries the delete, which succeeds, but the flag stays `true`: still capture is dead until
  the Activity is finished or the process dies.
- **Fix (host-testable):** track the specific capture ids whose durability failed. Clear an id when a
  later marker for that family becomes durable, or when the family is retired by a rescan, and make
  `canAdmitCapture()` read "set is empty". Test: fail one durability, succeed a retry, assert
  `canAdmitCapture()`.
- **PMA110:** failure path only.

## DB5-4. A lost still output buffer is not observed; the shutter is wedged for the whole watchdog [Low-Medium]

- **Confidence:** High (code). **Status:** Confirmed; whether this HAL reports buffer loss needs device.
- **Cites:** `camera/CameraController.kt:2146-2218` (the still `CaptureCallback` overrides only
  `onCaptureStarted`/`onCaptureCompleted`/`onCaptureFailed`); `grep -rn onCaptureBufferLost` and
  `onCaptureSequenceAborted` → no hits in `app/src/main`. Watchdog `:2058-2098`; `tryComplete`
  `:2274-2280` waits for result AND image.
- **Why:** Camera2 reports a dropped output via `onCaptureBufferLost` while `onCaptureCompleted` still
  fires. `p.result` is then set, but the image never arrives, so the shot stays pending until
  `CAPTURE_WATCHDOG_FLOOR_MS` (8 s), or exposure + 8 s under app-owned exposure (≈12 s at the 4 s
  ceiling). Every shutter press in that window is refused.
- **Fix:** override `onCaptureBufferLost` (target ∈ this shot's wanted surfaces and
  `captureTokenIsCurrent`) and `onCaptureSequenceAborted` to run the same terminal as
  `onCaptureFailed`; extract that terminal into a pure helper for a host test. For a RAW+processed shot,
  losing only one target should fail only that sibling if the pipeline supports it; otherwise fail the
  shot.
- **PMA110:** fail-fast on the failure path only.

## DB5-5. Focus-ruler close turns off an operator-owned or recalled punch-in [Low-Medium]

- **Confidence:** High. **Status:** Confirmed.
- **Cites:** `ui/controls/ManualDials.kt:255-263` (on close: `if (state.punchIn) actions.onAutoPunchIn(false)`
  keyed only on the composable's `loupeAutoOn`); `ui/CameraViewModel.kt:3354-3366` `onAutoPunchIn(false)`
  writes `punchIn = false` unconditionally; `:3332-3342` `onTogglePunchIn` clears `autoPunchInActive`
  but cannot clear the composable's `loupeAutoOn`; `applyLoaded` (`~:1561`, `~:1619`) writes `punchIn`
  without resetting `autoPunchInActive`/`punchInBeforeAuto`.
- **Scenarios:**
  - A: open the Focus ruler (assist turns the loupe on), toggle Punch-In off then on (now
    operator-owned and persisted on), close the ruler → loupe off; the next save persists `false`.
  - B: with the ruler open, recall an MR bank with `punchIn = true`. If the bank's focus mode closes
    the ruler, the bank's loupe is switched off at once. If it stays open, saves keep writing the stale
    `punchInBeforeAuto`.
  - C: a PUNCH_IN momentary hold across the ruler close restores its pre-assist snapshot (`true`) after
    the assist turned it off (`onAutoPunchIn` neither cancels nor rebases `momentaryPunchIn`).
- **Fix (host-testable on the VM):** make `onAutoPunchIn(false)` a no-op unless `autoPunchInActive`.
  Clear `autoPunchInActive` in `applyLoaded`. Cancel `momentaryPunchIn` in `onAutoPunchIn`.
- **PMA110:** only these interleavings.

## DB5-6. A still built from frozen controls takes its AF override from the live controls [Low]

- **Confidence:** High. **Status:** Confirmed.
- **Cites:** `camera/CameraController.kt:2049` `requestControls = frozenControls ?: controls`;
  `applyManualControls(requestControls, …)` `:2109`; `applyMetering(this, requestControls)` `:2118`; but
  `applyAfOverrides(this)` `:2126` → `:1780-1793` reads the LIVE `controls.focusMode`/`controls.afLock`.
  The Engine always passes `frozenControls = shotControls` (`CameraEngine.kt:4928-4935`, `:5023-5030`),
  and the DNG path dispatches after an asynchronous pre-allocation of up to 8 s.
- **Scenario:** shutter with AF-Lock on and DNG enabled; the lock is released before the preallocated
  dispatch → the still goes out in CONTINUOUS AF. The reverse also happens: a lock engaged after the
  press overrides a frozen MANUAL focus with AF_MODE_OFF at `lastFocusDistance`.
- **Fix:** `applyAfOverrides(builder, controls: ManualControls)`; pass `requestControls` from
  `capturePhoto`, `controls` from the repeating paths. Pure test on `afOverrideFor` inputs.
- **PMA110:** none for immediate captures (frozen == live).

## DB5-7. Cycle-4 regression (AGG4-9): recall of a denial-silent bank with the mic granted clears the MR indicator [Low]

- **Confidence:** High. **Status:** Confirmed.
- **Cites:** `AudioDenialReason.kt:61-68` (`afterRecall` → `restoreIfGranted(announce = false)`), `:74-86`;
  `MainActivity.kt:1059-1066` (`restoreAudio = { vm.onToggleRecordAudio(true) … }`);
  `ui/CameraViewModel.kt:2624-2629` (`onToggleRecordAudio` writes `activeMemorySlot = null` and saves).
- **Scenario:** save MR1 while the mic is denied (audio off by denial), grant the mic, recall MR1 →
  audio correctly comes back on, but in the same tick the MR1 indicator goes dark (and the settings
  blob is saved with no active slot), contradicting AGG4-9's own "restored audio is what the operator
  recalled".
- **Fix:** route the recall leg's restore through a non-clearing audio write (or re-assert the slot
  after it). Test: grant, recall a denial bank, assert `activeMemorySlot == slot` and
  `recordAudio == true`.
- **PMA110:** indicator only.

## DB5-8. AGG4-73 residual: the Shoot-tab Zoom slider still uses the lens-local scale [Low]

- **Confidence:** High. **Status:** Confirmed (new evidence for AGG4-73, which A.23 marked done).
- **Cites:** `ui/controls/ProSheet.kt:934-963`: `zBase = 1f` whenever TELE is off. Cycle 4 (6e75d245)
  moved only `ZoomRuler` (`ManualDials.kt:295-306`) to `zoomDisplayMultiplier`/`zoomRulerScale`.
- **Scenario:** Video, or Photo with DNG, on the 3× lens: chip, HUD and ruler read "3.0×"; the sheet's
  Zoom row reads "1.0×" for the same framing (written value is still correctly lens-local).
- **Fix:** build the slider from `zoomRulerScale(range.lower, range.upper, zoomDisplayMultiplier(…,
  activeRoute), teleconverterMode)`, exactly as `ZoomRuler`.
- **PMA110:** label/scale on standalone routes.

## DB5-9. COMPLETE + provider SIZE ≤ 0 + INDETERMINATE probe is kept but never re-armed [Low]

- **Confidence:** Medium. **Status:** Needs device validation (whether SIZE can read 0 for a fully
  written COMPLETE row after abrupt death).
- **Cites:** `storage/MediaStoreWriter.kt:1428-1434` (COMPLETE adopts without probe only when
  `sizeBytes > 0`); `:2922-2925` `keptRowReassertsPending` re-arms only when `probeOutcome.failed`;
  `:2942-2943` COMPLETE + non-VALID → KEEP_PENDING.
- **Why:** before AGG4-28, COMPLETE meant ADOPT. Now the take's fate depends on a column the same change
  calls untrustworthy: identical bytes are adopted with SIZE > 0, but with SIZE = 0 they are probed.
  An undecidable HEIF layout, or a moov-present MP4 whose extractor throws, then yields a non-failed
  INDETERMINATE that is kept without re-arm, and MediaProvider expires it (~7 days).
- **Fix:** in `keptRowReassertsPending`, also re-arm `journalState == COMPLETE` rows whose probe is not
  INVALID; or ADOPT COMPLETE + INDETERMINATE and keep only a proven INVALID. Both functions are pure and
  have tests.
- **PMA110:** edge only.

## DB5-10. One failed codec scan latches an EMPTY inventory for the process [Low]

- **Confidence:** High. **Status:** Confirmed.
- **Cites:** `video/EncoderCaps.kt:96-97` (`runCatching { buildCodecInventory(load()) }.getOrDefault(EMPTY)`),
  `:106-116` (`loaded = true` regardless of outcome).
- **Scenario:** `MediaCodecList` throws once (for example, mediaserver restarting during the cold-start
  walk) → every later `load()` returns EMPTY → no video codecs and no HEIF until the process dies, with
  no log line.
- **Fix:** latch only a successful scan; allow a bounded retry after an EMPTY-from-throw; log the throw
  once. Host test with a throwing loader.
- **PMA110:** none.

## DB5-11. `VideoRecorder.start` refuses with no app log line [Low]

- **Confidence:** High. **Status:** Confirmed.
- **Cites:** `video/VideoRecorder.kt:233-237` (empty transfer-admitted candidate list, or mixed codecs,
  `return null` silently); Engine turns it into `RECORDING_FAILED` (`camera/CameraEngine.kt:~6645`).
- **Why:** this is the "save fails with no app log line" signature CLAUDE.md names (MediaStore volume
  bullet) — for example HLG with a non-Main10 HEVC selection, or a stale encoder token.
- **Fix:** one `DiagnosticLog.w` with codec, transfer and candidate count before each `return null`.

## DB5-12. `isoStops`/`shutterStops` loop ~2^31 times when an advertised lower bound is 0 [Low]

- **Confidence:** High. **Status:** Confirmed in code; needs a malformed HAL to trigger.
- **Cites:** `ui/controls/ManualDials.kt:1127-1139` and `:1146-1158`. `log(0 / 100.0) = -Infinity` →
  `ceil(...).toInt() = Int.MIN_VALUE` → `for (k in kLo..kHi)` runs about 2^31 iterations inside
  composition (each one a 37-entry `minByOrNull` for ISO), which is an ANR. Inputs are the raw
  `SENSOR_INFO_SENSITIVITY_RANGE` / `EXPOSURE_TIME_RANGE` (`camera/CaptureCapabilities.kt:401`, `:410`),
  with no positivity normalization.
- **Fix:** `if (lower <= 0) return intArrayOf(…bounds…)`, or clamp `kLo`/`kHi` to a finite window.
  Pure host test.
- **PMA110:** none (positive ranges).

## DB5-13. DNG pre-capture cancel/start window dispatches an orphan insert after the lease is released [Low]

- **Confidence:** Medium. **Status:** Likely.
- **Cites:** `camera/DngPreCaptureAllocation.kt:122` checks `cancelRequested` once, then `:123-135` arms
  the deadline and dispatches `allocate`. `cancel()` (`:183-186`) can run from `invalidateCameraReady`
  on another thread in between. `onRetired` (`camera/CameraEngine.kt:5045-5066`) releases the process
  DNG admission and the rejected-cleanup reservation. `attachCancellation`
  (`RecordingPreNativeAllocation.kt:187-199`) only cancels a not-yet-dequeued task.
- **Scenario:** the worker has already dequeued → `createPendingImageAllocation` inserts a row →
  delivery is STALE → `cleanLateAllocation` needs a rejected-output slot that is no longer reserved
  (on overflow the row stays REGISTERED for launch recovery). Meanwhile the next shot can take the
  released process DNG admission, contradicting the file's own "provider preallocation cannot reorder
  RAW requests" invariant. The post-retirement deadline fires 8 s later as a no-op.
- **Fix:** first statement of the dispatched runnable: `if (attempt.isRetired()) return`; complete the
  deadline when `cancelRequested` is observed after `deadline.set(...)`. Seam test running `cancel()`
  between the check and the task body.

## DB5-14. A route change from the inventory publication leaves the coalesced zoom base stale [Low]

- **Confidence:** Medium. **Status:** Likely.
- **Cites:** `ui/CameraViewModel.kt:795-803` (inventory callback), `:4537` (`routeChanged &&
  lensLocalZoom → zoomRatio = 1f`); `ZoomGlideState.pendingRatio` (`:419-422`) is never cleared there,
  unlike every VM optics door (`invalidateOpticsDerivedState`) and `reconcileZoomToCaps`.
- **Scenario:** the Engine publishes BACK→EXTERNAL/FRONT from inventory (not from a VM door). UI zoom
  shows 1× but `currentZoomBase()` still returns the rear `pendingRatio` (for example 3.0), so the next
  pinch jumps.
- **Fix:** when `routeChanged`, call `invalidateOpticsDerivedState()` from the callback (main thread).

## DB5-15. Cycle-4 regression: processed JPEG encode buffered in a default-sized stream [Low]

- **Confidence:** High. **Status:** Confirmed (from 5528a958 / B.4).
- **Cites:** `capture/StillCapturePipeline.kt:314-320`: `java.io.ByteArrayOutputStream()` (32 B initial)
  then `toByteArray()`. A 5–12 MB JPEG goes through ~19 grow-copies plus one final full copy, all
  live beside the ~50 MB rotated ARGB bitmap (and HEIF work on HEIF+JPEG shots). `StillSnapshot.kt:57-59`
  already pre-sizes for this reason.
- **Fix:** pre-size (`rotated.width * rotated.height / 2`, for example), or write a subclass that
  exposes the internal buffer to avoid the `toByteArray` copy. Memory only; PMA110 output
  byte-identical.

## DB5-16. Debug capability dump spends the reserved warning/error log budget [Low, debug only]

- **Confidence:** Medium. **Status:** Confirmed.
- **Cites:** `camera/VendorTagInspector.kt` (~20 `Log.w` sites, for example `:37`) routed through the
  120-row reserved door (`camera/DiagnosticTelemetry.kt:73-78`); invoked per Engine start
  (`CameraEngine.kt:~2019`, `~2062-2072`) with no once-per-process guard.
- **Scenario:** a debug soak or bisect build with a few Engine re-creations spends ~15–20 reserved rows
  each, and later real `onError`/configure/teardown warnings are dropped — the evidence loss the quota
  rules exist to prevent. Distinct from AGG4-83, which only notes that the inspector is debug-only.
- **Fix:** log inspector output at info level (recurring door) behind an `AtomicBoolean` once-per-process
  guard.

## DB5-17. AGG4-24 aspect-first YUV still size can silently pick a much smaller still [Low]

- **Confidence:** Medium. **Status:** Needs device validation.
- **Cites:** `camera/CaptureCapabilities.kt:347-351` (YUV now through `pickStillSize`), `:668-669`
  (native = exact cross-multiply match with the array). PMA110's logical array 4080×3064 is 510:383,
  so only exact scaled copies count as native. The host test (`DeviceRouteLawsTest.kt:501`) assumes the
  logical YUV list contains 4080×3064; that list is not a recorded device measurement.
- **Scenario:** the HAL lists YUV 4000×3000 and 2040×1532 but not 4080×3064 → the new rule returns
  2040×1532 (a quarter-resolution default-route still) with no error.
- **Fix:** accept a near-native match within ~1%, or prefer a native size only when its area is ≥ ~50% of
  the largest in-array size. First confirm with a `dumpsys media.camera` YUV list.

## DB5-18. Info notes

- `video/VideoRecorder.kt:2380-2383`: the `FinalizedRecordingValidation` KDoc still says an extractor
  throw after the tolerated `muxer.stop()` throw is FAILED; after B.1 (AGG4-3) it is INDETERMINATE
  unless the box walk proves no moov (as `:1953-1960`, `:1982-1985` now say).
- `storage/MediaStoreWriter.kt:1404-1416`: the AGG4-5 `reassertPending` on journal-UNAVAILABLE runs on
  every backoff retry of the same page, renaming the backing file each time. Re-arm once per URI per
  run.
- `stab/GyroEis.kt:210-213` + `:417-456`: a gap (`dt > 0.1 s`) is dropped without breaking history, so
  `rotationBetweenSamples` interpolates ≈0 rotation across it instead of null. Dormant while
  `MOTION_SIGNS_VERIFIED = false`.
- `DeviceProfile.resolve` matches only "PMA110" while `detectPhone` also maps the global model
  (CPH2841) to the Find X9 Ultra, so the measured HAL workarounds do not apply there. That is
  consistent with the owner's measure-first rule and not a defect; it is the first thing to check if a
  CPH2841 shot-loss report arrives.

---

## Cycle-4 regression verdict

Diffed 887d39fb..ea7d4374 for every slice. Regressions found: DB5-7 (AGG4-9 recall reconciliation
clears the MR indicator), DB5-8 (AGG4-73 fix incomplete), DB5-9 (AGG4-28 SIZE gate lets a COMPLETE take
expire), DB5-15 (B.4 buffer sizing), DB5-17 (AGG4-24 sizing edge), and a stale KDoc (DB5-18). Checked
and clean:
- the EXIF splice (`exifSplicePlan` bounds/fill bytes, `writeJpegWithExifApp1` SOI handling,
  `exifApp1WithoutThumbnailIfd` bounds, tiered passthrough composer) and `OffsetTime` `xxx`;
- `StatusPlate.publish/expire/clearProgress` and their ViewModel sequence-gated timers;
- `MomentaryHold` cancel/release at recall, toggle, and onStop;
- `handlePreflightFailure`/`coldStartRetryRefusal`, `applyResolvedCameraRoute` same-route
  short-circuit, `recallVideoStreamSizeChanges`, `cancelEachSealed`, `frozenRecordingBitRate`,
  `rearReturnLens`, `zoomRulerScale` (cannot throw while enabled), `programHandheldShutterNs` (NaN is
  unreachable through `effectiveEquivFocalMm`'s `measured <= 0` guard), and the AGG4-37 once-per-chain
  re-read.

## Final sweep (commonly missed)

- `coerceIn(min, max)` with min > max: none reachable in the slices (`driveProgram`'s `slowCapNs`,
  `clampInRange`, `clampExposureNs`, and `effectiveZoomBounds` are guarded).
- NaN/Infinity: `SettingsStore` guards floats; `AutoExposure.driveProgram` produces NaN only with
  `expMinNs == 0 && currentNs == 0` (persisted bound ≥ 1, caps range positive). `isoStops` with a
  0 bound is DB5-12.
- `jpegQuality` is persisted raw but clamped at both consumers (`CameraEngine.kt:5676`,
  `ManualControls.kt:689`), so no `Bitmap.compress` IAE.
- `lateinit`: `CameraController.selection/caps` pre-open accesses are `isInitialized`-guarded; the
  Engine's local `lateinit` attempt/owner/teardown are assigned before any callback can run.
- YUV→NV21 repack strides/bounds (`StillSnapshot.kt`) are correct for planar and semi-planar layouts.
- Enum persistence: `enumOr`/`enumListOr` fall back per field; the "LOG" alias migrates; the converter
  pair is reconciled.

## Files covered

All under `app/src/main/kotlin/me/hletrd/telecampro/`:
- camera: `CameraEngine.kt` (cycle-4 diff in full; 2900-3000, 4990-5090, 5375-5480, 6200-6240, 8015-8045
  plus grep sweeps), `CameraController.kt`, `CaptureCapabilities.kt`, `ManualControls.kt`,
  `CameraState.kt` (major sections), `CameraSelector2.kt`, `ControlAvailability.kt`,
  `OpticsConstraints.kt`, `Teleconverter.kt`, `ZslAdmission.kt`, `ZoomSubmitPlan.kt`, `RotationMath.kt`,
  `DeviceProfile.kt`, `DeviceExifLabels.kt`, `AutoExposure.kt`, `CameraTeardownTerminal.kt`,
  `StartupTrace.kt`, `DiagnosticTelemetry.kt`, `VendorTagInspector.kt`, `CameraStatus.kt`,
  `StandbyAudioController.kt`, `RendererAssists.kt`, `RecordingTeardownCoordinator.kt`,
  `RecordingPreNativeAllocation.kt`, `RecordingStorageDispatcher.kt`, `StillPublicationDispatcher.kt`,
  `FamilyDeletionMarkerDispatcher.kt`, `RetainedStillDeletionOwner.kt`,
  `RetainedStillDiscardDispatcher.kt`, `DngPreCaptureAllocation.kt`, `LaunchMediaRecoveryCoordinator.kt`.
- gl: `GlPipeline.kt`, `FlipRenderer.kt`, `EglCore.kt`, `Shaders.kt`, `LogProfiles.kt`,
  `SdrToHlgMapping.kt`, `FocusDetail.kt`, `MotionInversion.kt`, `FrameNotificationCoalescer.kt`,
  `AtomicOwnerSlot.kt`, `FrontMirrorConvention.kt`.
- stab/focus/video: `GyroEis.kt`, `FocusMapping.kt`, `MacroProximity.kt`, `VideoRecorder.kt`,
  `EncoderCaps.kt`, `ColorProfiles.kt`, `AudioLevels.kt`, `AudioInputInspector.kt`, `AudioReadPolicy.kt`,
  `EncoderSizeLadder.kt`.
- storage/capture: `MediaStoreWriter.kt` (cycle-4 diff + recovery/journal sections),
  `PendingDiscardJournal.kt` (1-400), `LatestCaptureReducer.kt`, `SettingsStore.kt`, `CaptureFamily.kt`,
  `StillCapturePipeline.kt`, `StillSnapshot.kt`, `HeifExif.kt`, `HeifCapture.kt`, `DngCapture.kt`.
- ui: `CameraViewModel.kt` (cycle-4 diff + major sections), `CameraScreen.kt` (sections),
  `CameraScreenPolicy.kt` (sections), `ZoomMath.kt`, `ZoomGlideState.kt`, `MomentaryHold.kt`,
  `ExposureMeterSpeech.kt`, `ExternalNavigation.kt`, `ViewModelMediaDeleteDispatcher.kt`,
  `CaptureOutputTracker.kt`, `controls/ManualDials.kt` (sections), `controls/ProSheet.kt` (920-1120),
  `controls/PhotoFormatChips.kt`, `controls/RulerAccessibility.kt`, `review/ExactHandlePrepareOwner.kt`,
  `review/LatestHeavyWorkLane.kt` (1-480), `review/MediaReview.kt` (380-600 + greps); diffs only for
  `overlays/Overlays.kt`, `controls/ProControls.kt`, `controls/ControlCycles.kt`, `SwitchCoverPolicy.kt`.
- root: `MainActivity.kt`, `CameraPermissionPolicy.kt`, `AudioDenialReason.kt`, `ProcessAdmissionSignal.kt`.
- Not covered in depth: `ui/theme/Theme.kt`, `ui/controls/ControlLabels.kt`, `LocalizedControlLabels.kt`,
  `FnIcons.kt`, `SliderKeyPolicy.kt`, and the gesture/UI-only remainder of `MediaReview.kt`,
  `ProControls.kt`, `Overlays.kt` (presentation code with a low debugger yield).
