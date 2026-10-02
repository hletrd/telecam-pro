# Test-engineer review: RPL cycle 5 (HEAD ea7d4374, 2026-10-02)

Scope: whether the cycle-4 tests (`git log 887d39fb..ea7d4374 -- app/src/test tools/tests`, 60
commits that touch tests) would fail if their production fix were reverted; new coverage gaps;
vacuous or tautological assertions; flaky timing; and the Python tool and coverage suites
(`tools/tests`, `tools/coverage`). I also looked at `app/src/androidTest` (4 device probes).

This was a read-only pass. I ran no Gradle, edited no source, test, doc or plan file, and ran no
state-changing git command. Every claim below comes from reading the code and the git history.

Items already tracked in `docs/plans/2026-10-02-rpl-cycle4.md` (later-cycle, carried, deferred)
are not reported as new. Where I found new evidence on a tracked item, it is marked "already
tracked".

## Cycle-4 fix → regression-test audit

"Red" means that reverting ONLY the production fix line(s) makes at least one test fail.

### Lane A1 (engine/controller)

| Commit | Fix | Revert turns red? |
|---|---|---|
| b1f7869e | Bare-door preflight failure goes to `handlePreflightFailure` → BARE_RETRY | **Partial.** Red for the helper and the disposition table. **Green** if the two call sites `CameraEngine.kt:4375,4385` go back to the inline rollback (TE5-5). |
| 7f137b9b | Unschedulable bare retry → REOPEN status | Yes for no-input-surface. The recorder-active BLOCKED case is pure-table only. |
| 691943df | Same-camera Video recall with a new size → reconfigure | **Partial.** Pure predicate and private helper are covered. Passing `false` at `CameraEngine.kt:2988` stays green (TE5-13). |
| a36aa43b | Cancel-before-start latch | Owner: yes. `cancelDngPreCaptureAllocations` reverting to `forEach(::cancel)`: green. |
| c7953e52 | AGG2-3 settle line moved into `dngOwnerRetirement` | **Partial.** The engine lambda `settleRegisteredShot` (`CameraEngine.kt:5057`) can drop `continuation` and stay green (TE5-6). |
| 69d58550 | Re-band lens leaving FRONT | Yes (engine and VM). |
| cc23de76 | EV-only delta under manual AE → NO_OP | Partial. Pure plan only; the `CameraController.kt:~1444` dispatch is not driven (perf-only risk). |
| 36e93446 | One characteristics re-read per chain | **Partial.** `chars(shot = false)` / `chainHead = true` reverts stay green (TE5-9). |
| 49fc1307 | Fix-off defaults removed | Yes, by compile. |
| e9f39680 | YUV still size aspect-first | **No.** The test calls the pre-existing `pickStillSize`, and the `CaptureCapabilities.kt:347` call site is untested (call-site coverage is tracked as AGG4-79; new behaviour risk in TE5-19). |
| 54c90d3a | Route-inventory republish keeps zoom | Yes. |
| 85e1bef8 | Engine optical seed = VM placeholder | Yes. |
| 7797d0b3 | Orphan poisoned preview on acquisition detach failure | **No.** Only the two-line helper is tested; the test name over-claims (TE5-13; PENDING DEVICE acknowledged). |
| 2e10888c | Handheld rule from the effective focal | Partial. Pure helper only; the VM `driveProgram` and focus-gate call sites are not driven (TE5-13). |

### Lane A2 (ViewModel/UI)

| Commit | Fix | Revert turns red? |
|---|---|---|
| c1cd9bef | Drop VM `stillCaptureAdmissionAvailable=false` | Yes (real dispatcher, bounded spin). |
| 1218a635 | (a) store provenance from the store; (b) Activity `afterRecall` | (a) Yes. (b) **No.** `MainActivity.kt:532` is never executed by a test (TE5-4). |
| 6f29df98 | Rollback clears `activeMemorySlot` | Yes. |
| 53c0ac65 | Pending-inventory arming after acceptance | Yes for the first refusal exit; the `!opticsAccepted` exit (`CameraViewModel.kt:1509`) is untested (TE5-16). |
| 41ff4a53 | Shared rear-only refusal | Yes (EXTERNAL case). |
| a2665544 | `removeCallbacks(zoomEaseTicker)` | Yes (queued callback count). |
| 1ae24bdb | VM doors vs. Engine route law | Partial by design: the Engine is the oracle except for FRONT. |
| 9097cb95 | Chip/OSD/edge session truth | Chip and VM edge: yes. The `Overlays.kt:752,825,1000` swap to `osdPhotoFormats`: **no** (TE5-13). |
| 6e75d245 | Zoom ruler scale, ISO stops | ISO: yes. Ruler: pure helper only; `ManualDials.kt:298-305` revert is green (TE5-13). |
| 992ec720 / 7a2d6cb5 / 597c40df | Momentary holds | Yes (real key edges, `SettingsStore` read back). |
| 8887ae66 | Ruler semantics steps | Yes. |
| eba84af6 / dfb122f8 | Status plate rank; condition-ending events | Yes (pure reducer plus VM). |
| ec5de200 | Exposure-meter speech | Mapping and strings: yes. `CameraScreen` semantics wiring: no (TE5-13). |
| 79e1e2ea | Deslop: MainActivity denial writes via store | Behaviour-preserving, but it widens the untested Activity surface (TE5-4). |

### Lane B (storage/capture/video)

| Commit | Fix | Revert turns red? |
|---|---|---|
| 5cf9c5e3 | Box walk plus fresh-descriptor parse | **Partial.** Pure classifier and walker: yes. The `MediaStoreWriter.kt:1751-1790` Android wiring and the recovery VIDEO branch: no (TE5-8). |
| ea1ff6ed | `keptRowReassertsPending` gate | Yes (`LaunchRecoveryPendingExpiryTest`). |
| 16e85a64 | Re-arm on PUBLISH_FAILED / journal UNAVAILABLE | Yes, both. |
| 9344c2fa | SIZE≤0 not INVALID; COMPLETE+SIZE>0 adopts | Yes for JPEG. It also opened TE5-1. |
| 257d5b7a | Splice EXIF, single write | **Partial.** Helpers: yes. Restoring the in-place `saveAttributes` rewrite in the private lanes: green. One test is tautological (TE5-7). |
| 9408fb35 | Mixed DNG direct when no sibling queued | Yes. |
| ac51a897 | OffsetTime `xxx` | Yes (UTC and Abidjan; TimeZone restored in `finally`). |
| 56bb2269 | `refresh(read)` under the signal lock | Primitive: yes, but only by a sleep (TE5-14). Call site `MediaStoreWriter.kt:218` reverting to `publish(...)`: green. |
| 25abb2f2 | Queue sized workers+backlog | Yes. |
| 93c43931 | PRESENT plus extractor throw → INDETERMINATE, no re-arm | Yes. |
| a41edd49 | Passthrough EXIF thumbnail tiering | Helper: yes. The lane call in `writePassthroughJpeg` (`StillCapturePipeline.kt:402`): green (dormant on PMA110). |
| 385093ae | `MICROPHONE_BUSY` on refused mic claim | Yes (`recordAudio = true` only; TE5-15). |
| d1c06597 | Bitrate from the frozen codec | Pure helper only; the call-site revert at `CameraEngine.kt:6599` is green. |
| 321a6f34 / 5528a958 | Deslop refactors | Behaviour pinned by the existing tests. |

### Lane C (tools/release/coverage)

| Commit | Fix | Revert turns red? |
|---|---|---|
| 42f450d2 / 9596c16a / d0303120 | Secret floor (Python), Kotlin port, parity | Yes. `test_upload_key_floor_gradle.py` executes the Kotlin port through a Gradle fixture task over named and seeded generated vectors, and asserts both verdicts occur. |
| 248801fe | Bundle-to-APK signers gated; alias/store in the gate input | Task-name set and gate input: yes, but as source-text assertions (the behavioural half is AGG4-52, already tracked). The AGP-jar prefix pin can **skip** (TE5-17). |
| 6d93afa6 / 3521e6fc | Sealed release child environment | Yes (fixture-driven). |
| c6598aae | Docs secret scan on unstaged and non-markdown sources | Yes (fixture repos). |
| 37504df5 | Residual fingerprint | Yes for substitution of the cited lines; it does not see uncited context (TE5-18). |
| b1c7bf0e / 8ae920d5 | Stacked-KDoc scan | Yes. |
| 0a22ffb2 | Release lint on a clean tree | Yes for task selection; the skip itself is silent to the exit code (TE5-20). |
| c09cd6c2 | Kotlin warnings fatal | Source-text assertion (acceptable for a build flag). |

## Findings

### TE5-1: A durably COMPLETE take with stale provider SIZE=0 is kept hidden and never re-armed, so MediaProvider expiry deletes it (Medium, Medium confidence). Likely.

- **Where:** `storage/MediaStoreWriter.kt:1433-1441` (`COMPLETE && sizeBytes > 0L → VALID`, else
  `probePendingMedia`); `orphanDisposition` COMPLETE branch `:2942-2943`;
  `keptRowReassertsPending` `:2922-2925`.
- **Why:** 9344c2fa exists because provider SIZE may be stale after a process death. A row with a
  durable COMPLETE marker, real bytes and a stale SIZE of 0 now goes through the generic probe.
  That probe can return a non-failed INDETERMINATE: an undecidable HEIF layout, or (MRG4-4) a
  moov-present MP4 that the extractor rejects. `orphanDisposition` then gives KEEP_PENDING, and
  `keptRowReassertsPending` gives `false` because the probe did not *fail*.
  - The KDoc at `:2911-2919` even lists "a probed non-VALID zero-size COMPLETE row" as
    intentionally not re-armed.
  - That is correct for a truly empty file. It is wrong when the descriptor shows bytes, because
    the COMPLETE marker is the adoption proof and SIZE was the only thing missing.
  - Before cycle 4 this row was ADOPT.
- **Failure scenario:** the app is killed after a HEIF (grid/idat layout outside the probe's
  supported set) or a video is marked COMPLETE, before the provider's SIZE updated. Every launch
  keeps the row pending and invisible without re-arming it, so MediaProvider's pending expiry
  deletes a take the app durably recorded as complete.
- **Fix:** for COMPLETE rows, decide on the opened descriptor's length alone (`statSize > 0` →
  VALID/ADOPT, `0` → KEEP). Alternatively, make `keptRowReassertsPending` return true for any
  COMPLETE row that is not VALID.
- **Host test:** in `LaunchRecoveryPendingExpiryTest`, add a COMPLETE row with SIZE 0, MIME
  `image/heic`, and a non-empty file whose ISO-BMFF layout `probeHeifIsoBmff` calls INDETERMINATE.
  Expect ADOPT, or at least a `[1]` pending re-arm write. Reverting the fix must turn it red.
- **PMA110:** only after a crash with stale SIZE; storage only, no pixel change.

### TE5-2: Fix-off defaults remain on two safety-critical seams that AGG4-47 did not reach (Medium, High confidence). Confirmed.

- **Where:**
  - `camera/ManualControls.kt:672-682` `applyManualControls(..., pinAutoFps = false,
    previewExposureCap = false, enforceFrameRate = false)` and the private `applyExposure(...,
    previewExposureCap = false, ...)` at `:1024-1025`.
    - `previewExposureCap = true` is the only thing that keeps a multi-second AE-OFF exposure off
      the repeating request. CLAUDE.md records that HAL fact as a reproducible lost shot with
      `CAMERA_ERROR(3)`.
    - The two repeating builders pass it explicitly (`CameraController.kt:1045, 1751`); the still
      path relies on the default.
  - `camera/CameraController.kt:2795-2808` `sessionAttemptPlan(logicalMultiCamera /
    teleconverterMode / wantHiRes / tenBitVideoOnly / frontRoute = false, yuvStillRequired /
    rawStandaloneOnly = true)`. `tenBitVideoOnly` is the HLG10+JPEG+RAW crash guard; a HAL crash is
    not rescued by the ladder.
- **Why:** this is exactly the pattern 9cfd288a (cycle 3) and 49fc1307 (cycle 4) removed elsewhere:
  dropping an argument compiles, and every test stays green.
- **Failure scenario:** a third repeating builder (for example a new ZSL or burst repeating
  request) or a refactor of the existing one omits `previewExposureCap`. M-mode at 2 s in the dark
  then puts a 2 s exposure on the repeating request, and the next still is lost. Similarly, an
  omitted `tenBitVideoOnly` re-enables the crash combination.
- **Fix:** remove the defaults so omission fails to compile. The still passes `previewExposureCap =
  false` explicitly, and the ladder tests pass every flag. Optionally add one pure
  `previewRequestExposurePolicy()` used by both repeating builders.
- **PMA110:** none (signature only).

### TE5-3: An owned optics rollback leaves the pre-inventory codec/transfer request armed; the rolled-back bank's AVC/log curve is persisted and later applied (Low-Medium, Medium confidence). Likely.

- **Where:** `ui/CameraViewModel.kt:963-1048` (`onOpticsRollback`). It restores
  `transfer`/`videoCodec` when `pipelineOwned`, and mirrors only
  `pendingPhotoFormatsUntilInventory.dngRaw` (`:1036`).
  - `pendingCodecUntilInventory` and `pendingTransferUntilInventory`, armed by an accepted recall
    at `:1518-1519`, are untouched.
  - The rollback's `scheduleSettingsSave()` persists them through `currentExtras` (`:1668, :1708`).
  - `applyEncoderInventory` (`:2819-2824`) replays them.
- **Failure scenario:**
  1. Right after launch, before the encoder inventory lands, the operator recalls MR2
     (VIDEO / AVC / S-Log3).
  2. The reopen fails and rolls back to HEVC/SDR, and the UI shows HEVC/SDR.
  3. The inventory lands and silently applies AVC/S-Log3, which is also what the next launch
     restores.

  This is the AGG2-9 class for the two other pending fields.
- **Fix:** in the rollback, when `pipelineOwned`, set both pending fields to `rollback.videoCodec`
  / `rollback.transfer` (or null them).
- **Test:** Robolectric, inventory not loaded → recall a VIDEO bank → invoke the rollback → assert
  both pending fields and the persisted extras equal the baseline; then load the inventory and
  assert the state stays at baseline.
- **PMA110:** fixes a wrong state only.

### TE5-4: The AGG4-9 recall-time grant reconciliation lives only in the Activity, and no test executes it (Low-Medium, High confidence). Confirmed; residual of tracked TE4-6 / AGG4-49.

- **Where:** `MainActivity.kt:521-537` (`memoryBankAudioProvenance.afterRecall`). The VM
  `onRecallMemorySlot` does not reconcile. 79e1e2ea then edited MainActivity's denial writes with
  no test coverage.
- **Why:** `MemoryBankAudioProvenanceTest` drives the class with fakes. The single production call
  is unexecuted. AGG4-49 moved the *store* half into the VM; the *recall* half still has the
  asymmetry that AGG4-49 removed.
- **Failure scenario:** the wrapper is deleted as redundant, or a new recall door (hardware MR
  binding, Fn tile) calls `vm.onRecallMemorySlot` directly. A denial-silent bank recalled while the
  mic is granted then records silent clips until the next `onResume`, and every test stays green.
- **Fix:** move `afterRecall` into the VM with `hasMicrophonePermission` injected. Add a VM
  Robolectric test: grant → store a denial-silent bank → recall → `recordAudio == true`.
- **PMA110:** none.

### TE5-5: The AGG4-2 / MRG4-2 "revert-detecting" engine test does not detect the revert at the call sites (Low-Medium, High confidence). Confirmed.

- **Where:** `camera/CameraEngine.kt:4375-4392` (both `handlePreflightFailure` calls inside
  `reconfigureCamera`). Tests: `BarePreflightRetryRobolectricTest`, and
  `OpticsRouteInputTransactionRobolectricTest` (bare-reopen case).
- **Why:** both tests reflect-invoke `handlePreflightFailure` directly. The AGG4-2 revert is the
  old inline `rollbackOpticsAfterPreflight(...)` at those two call sites. After that revert the
  helper is dead code and the suite stays green.
- **Failure scenario:** a bare door (stabilization, fps, aspect, video size, error recovery) parks
  Not-Ready again under "camera unchanged" copy, with the shutter and REC dead over a live preview.
- **Fix:** drive `currentOpticsReconfiguration()` → `reconfigureCamera(id, transaction)` on a
  Robolectric engine whose selection/caps read returns null (empty `ShadowCameraManager`). Assert
  that the retry gate was scheduled and the statuses are `[RETRYING]`.
- **PMA110:** none.

### TE5-6: The AGG2-3 bug line is still engine-owned after c7953e52 (Low-Medium, High confidence). Confirmed; residual of AGG4-46.

- **Where:** `camera/CameraEngine.kt:5057` `settleRegisteredShot = { continuation ->
  settleRegisteredStillShot(..., onDone = continuation) }`. Test:
  `DngPreCaptureAllocationTest.rejectedChainStep` supplies its own `settleRegisteredShot`, which
  calls `continuation()` itself.
- **Failure scenario:** the engine lambda writes `onDone = onDone` (the chain's raw `onDone` is in
  scope). Every test passes. A saturated allocator then forks timelapse ticks (2^n live tasks), and
  AEB resets controls mid-bracket.
- **Fix:** have `dngOwnerRetirement` call `settleRegisteredStillShot`'s pure core itself, with
  the engine passing data rather than a lambda that can drop `continuation`. Alternatively, add an
  engine Robolectric test that saturates `ProcessPreNativeMediaAllocator` and counts exactly one of
  {`onDone`, `false`} per tick.
- **PMA110:** none.

### TE5-7: The single-write JPEG lanes are untested, and one "parity" test is tautological (Low-Medium, High confidence). Confirmed.

- **Where:** `capture/StillCapturePipeline.kt:313` (`writeProcessedJpeg`), `:351`
  (`writeSingleJpeg`), `:402` (`writePassthroughJpeg`), all private. Test:
  `capture/JpegExifSpliceTest.kt` `the HEIF payload and the JPEG payload are one composition`,
  which calls `composeStillExifApp1` twice with identical arguments and asserts the two are equal.
- **Why:** the AGG4-6 property is "one write-mode open, no `rw` reopen, EXIF spliced". No test
  checks it at the lane level. The parity test only proves determinism; its comment asserts the
  wiring it does not exercise.
- **Failure scenario:** the in-place `saveAttributes` rewrite comes back, or passthrough passes
  `sourceExifApp1 = null` (the MRG4-9 sideways save). The suite stays green, and the AGG4-6
  corrupt-adopt window returns.
- **Fix:** extract `writeJpegOnce(open, encoded, payload)`, or drive `saveProcessedStills` with a
  fake provider that records open modes and bytes. Assert one `w` open, no `rw`, exactly one EXIF
  APP1, and the expected orientation tag. Delete or rename the tautological test.
- **PMA110:** none.

### TE5-8: The recovery VIDEO branch that now DELETES moov-less takes has no end-to-end test (Low-Medium, High confidence). Confirmed.

- **Where:** `storage/MediaStoreWriter.kt:1751-1790` (`probeFinalizedVideo`,
  `parcelFdHasVideoTrack`, `parcelFdMoovPresence`) and the `finalizedVideoTrackProbe` lambdas. No
  `cleanupOrphanedPendingBatch` test uses a VIDEO row.
- **Why:** the risky part is the wiring, not the classifier:
  - descriptor reuse versus a fresh reopen;
  - `use` ordering (on Android, closing `FileInputStream(fd)` does not close the fd, while on the
    JVM it does);
  - the order of the two lambdas.
- **Failure scenario:**
  - The walk reads a closed descriptor. Every truncated take becomes UNKNOWN and is retained and
    re-armed forever, so the AGG4-4 hidden leak returns.
  - Or UNKNOWN is mapped to ABSENT, which deletes real clips.
- **Fix:** add a batch test with a VIDEO target and an `openFile` backed by temp files: a moov-less
  MP4 → DELETED, and a moov-present MP4 that the extractor rejects → retained and not re-armed.
- **PMA110:** none.

### TE5-9: The per-chain characteristics bypass (AGG4-37 / AGG3-9) has no wiring test (Low, High confidence). Confirmed.

- **Where:** `camera/CameraController.kt:2042` (`if (chainHead) chainCharsReread.arm()`) and
  `:2308` (`chars(shot = chainCharsReread.consume())`). Engine `chainHead` sites:
  `CameraEngine.kt:5162, 5205, 5244, 5274, 5326`.
- **Failure scenario:**
  - `chars(shot = false)` brings back AGG3-9: a metering build spends the gate, and the delivered
    shot fails with "Missing camera characteristics".
  - `chainHead = true` everywhere brings back the AGG4-37 per-completion cost.
  - Both stay green.
- **Fix:** extract the completion's characteristics choice into a pure function the controller
  calls, then test a head plus 19 continuations through it. Add one engine table test of the
  `chainHead` values per drive mode.
- **PMA110:** none.

### TE5-10: The rising-edge DNG-only notice latches even when the plate drops it (Low, Medium confidence). Likely.

- **Where:** `ui/CameraViewModel.kt:932, 955, 957-959`. `readyDngOnlyAnnounced = acceptedDngOnly`
  is set on every applied Ready, before `runIfOwned { showStatus }`. Since eba84af6 the plate can
  refuse a WARNING under an unexpired retained-take or ERROR line.
- **Failure scenario:** stopping REC shows `VIDEO_SAVE_DELAYED`. Switching to Photo lands a
  RAW-only session, and its notice is out-ranked and dropped. The flag is now true, so no later
  Ready of the same shape re-announces it. The shutter-time refusal remains the only signal.
- **Related (Info):** `STILL_CAPTURE_UNAVAILABLE` at `:926-927` has no edge latch, so every Ready
  of a still-less Photo session re-announces it. That is the noise AGG4-67 removed for the DNG-only
  case.
- **Fix:** latch only when the status was actually shown (have `showStatus` report acceptance), and
  apply the same edge rule to `STILL_CAPTURE_UNAVAILABLE`.
- **Test:** retained-take status → RAW-only Ready → expiry → second RAW-only Ready announces.
- **PMA110:** status copy only.

### TE5-11: A momentary AEL / punch-in hold leaks if the key is rebound mid-hold, and two keys bound to AEL share one hold (Low, Medium confidence). Likely.

- **Where:** `ui/CameraViewModel.kt:228-229` (`momentaryAeLock`, `momentaryPunchIn`). The release
  edge is dispatched by the action bound *at release time* (`performHardwareAction` reads
  `_state.value.*Action`).
- **Failure scenario:**
  - Holding the half-press (AEL) while changing its binding in Setup routes the release to the new
    action. AEL stays applied until `onStop`, and the next AEL press keeps the stale snapshot.
  - With both half-press and quick button bound to AEL, releasing either one ends the hold that
    the other still holds.
- **Fix:** have the binding setters (`onVolumeKeyAction`, `onHalfPressAction`,
  `onQuickButtonAction`) end any hold owned by the old action. Key the hold by key source. Add VM
  tests for both cases.
- **PMA110:** none.

### TE5-12: `DngPreCaptureAdmission` publishes outside the signal lock, the same race AGG4-35 fixed elsewhere (Low, Medium confidence). Likely.

- **Where:** `camera/DngPreCaptureAllocation.kt:50-51, 63-64` (a CAS, then `publish(...)` with no
  monitor).
- **Failure scenario:**
  1. A releases (CAS to free) and is preempted before publishing.
  2. B acquires, and its `publish(false)` is change-gated away.
  3. A publishes `true` while B holds the lease.

  The shutter reads open, and the next press is refused by `tryAcquire`. At rest the signal is
  correct again; this is a transient wrong-open state.
- **Fix:** `admissionSignal.refresh { !occupied.get() }` in both places, plus an interleaving test
  modelled on the AGG4-35 one (with the TE5-14 latch fix).
- **PMA110:** none at rest.

### TE5-13: Cycle-4 fixes proven only in pure helpers; reverting the fix line keeps the suite green (Low, High confidence). Confirmed.

This is the TE4-2 pattern, restated per fix line.

| Fix line | Revert that stays green | Suggested test |
|---|---|---|
| `CameraEngine.kt:2988` (AGG4-8) | Recall size-change argument → `false` | Robolectric `setResolvedOptics` (Video, 1080p recall on a 4K session) → assert reconfigure, not fast commit |
| `GlPipeline.kt` ~1000 (AGG4-18) | Delete the acquisition-branch `orphanPoisonedPreviewOutput()` call | Rename the test (it claims "owner cleared"); EGL seam or PENDING DEVICE |
| `CameraViewModel.kt` ~4198 and ~579 (AGG4-14) | `driveProgram` / focus gate given nominal focal | One `runAppSideAe` step in PROGRAM at TC 4.29× → shutter target ≈ 1/1200 s |
| `Overlays.kt:752,825,1000` (AGG4-70) | Back to `effectivePhotoFormats` | Compose: Ready with no still target renders "--" |
| `ManualDials.kt:298-305` (AGG4-73) | Ruler base back to TC-only | Compose: a standalone 69 mm route ruler reads "3.0×" |
| `CameraScreen.kt` meter semantics (AGG4-71) | Drop `clearAndSetSemantics` | Compose semantics assertion |
| `MediaStoreWriter.kt:218` (AGG4-35) | Back to `publish(read())` | Covered once TE5-14's latch test drives the call site |
| `StillCapturePipeline.kt:402` (MRG4-9) | Lane calls `composeStillExifApp1` directly | See TE5-7 |
| `CameraEngine.kt:6599` (AGG4-30) | `bitRateFor(liveCodec)` | Engine REC diagnostic test with the codec changed after admission |

PMA110: none (tests only).

### TE5-14: The AGG4-35 interleaving test proves its negative with `Thread.sleep(100)` and can pass vacuously (Low, High confidence). Confirmed.

- **Where:** `app/src/test/kotlin/me/hletrd/telecampro/ProcessAdmissionSignalTest.kt:108-111`. The
  same shape appears at `:51-52` (`closer.join(50L); assertFalse(closeReturned)`).
- **Why:** if T2 is not scheduled within 100 ms (a loaded CI JVM), an *unlocked* `refresh` still
  yields `[false, true, false]` and `t2Reads == 1` at the end. The test then passes without having
  tested anything. It never fails spuriously, so the decay is silent.
- **Fix:** have T2 count down a latch immediately before calling `refresh`, then poll
  `t2.state == Thread.State.BLOCKED` (bounded) before asserting `t2Reads == 0`. Do the same for
  the close-drain test.

### TE5-15: `MICROPHONE_BUSY` is reported for a take with audio off; that negative case is untested (Low, Medium confidence). Likely.

- **Where:** `camera/CameraEngine.kt:6383-6390`. `standbyAudioController.beginRecording()` is
  claimed whether or not `recordAudio` is set. The 385093ae test covers `recordAudio = true` only.
- **Fix:** add a `recordAudio = false` case and choose the copy deliberately.
- **PMA110:** copy only.

### TE5-16: The second `applyLoaded` refusal exit has no AGG4-11 test (Low, High confidence). Confirmed.

- **Where:** `ui/CameraViewModel.kt:1509` (`if (!opticsAccepted)`). Moving the arming block
  (`:1517-1520`) between the two exits stays green.
- **Fix:** add a REC-refused recall case to `refused recall before the encoder inventory arms no
  pending request`.

### TE5-17: The AGP task-prefix pin skips instead of failing when the jar is not where it looks (Low, Medium confidence). Confirmed.

- **Where:** `tools/tests/test_upload_key_policy.py:166-171` (`skipTest` when the pinned AGP jar is
  not under `$GRADLE_USER_HOME/caches/modules-2/...`).
- **Why:** under `verify_host.py`, Gradle runs first, so the jar is normally present. But the skip
  also triggers on any cache-layout change, a relocated `GRADLE_USER_HOME`, or an AGP bump whose
  coordinates differ. In every one of those cases the SEC4-1 pin silently stops guarding new
  signing task names. The regex also only finds prefixes ending in `For`.
- **Fix:** when an environment flag set by `verify_host.py` is present, fail instead of skipping.
  Assert a minimum number of discovered prefixes, so a regex that matches nothing cannot pass.

### TE5-18: The residual fingerprint covers only the cited lines, while several rationales depend on uncited context (Low, Medium confidence). Confirmed; residual of TE4-5 / AGG4-45.

- **Where:** `tools/coverage/partition_report.py:93-100` (`region_fingerprint`) and
  `tools/coverage/partition-a-residuals.txt`.
- **Example:** the `ZoomMathKt` row (`ZoomMath.kt:161`) is justified by "teleZoomMarks passes
  literal true". That literal is on the uncited line `:160`. Change `:160` to a variable and the
  missed line at `:161` becomes reachable. The fingerprint and miss count are unchanged, so the
  stale rationale is carried. The `CameraStateKt` row ("`LensChoice.entries` is statically
  nonempty") has the same shape.
- **Fix:** let a row cite the dominating lines as well (an optional `context:` region hashed into
  the fingerprint). Alternatively, hash the whole enclosing function.

### TE5-19: The aspect-first YUV pick can change the PMA110 FRONT still size, and the device probe does not report the YUV pick (Low, Low confidence). Needs device validation.

- **Where:** `camera/CaptureCapabilities.kt:347` (e9f39680) and
  `app/src/androidTest/.../camera/StillSizeProbeTest.kt:47-51`, which logs the JPEG pick only.
- **Why:** FRONT stills are YUV ("FRONT takes deep YUV on its full rung"). The commit shows
  logical-route identity but makes no claim for the PMA110 front. If the front's largest YUV is not
  an exact array-ratio size, the saved front still shrinks. The unit test fixture is synthetic, and
  it only exercises the pre-existing `pickStillSize`. Call-site coverage itself is tracked under
  AGG4-79.
- **Fix:** have the probe log `pickStillSize` over the YUV list as well. Run it on PMA110 and pin
  the measured front YUV list in a table test.
- **PMA110:** possible change on FRONT.

### TE5-20: Release lint is skipped silently (exit 0) on a dirty tree (Info, High confidence). Confirmed.

- **Where:** `tools/verify_host.py:159-167`.
- **Why:** the skip prints a NOTE, but the gate's exit code and evidence do not record it. During a
  review cycle the tree is routinely dirty (this cycle's starting state had 16 deleted review
  files), so every mid-cycle gate run attests no release lint while still exiting 0.
- **Fix:** print a terminal "release lint: SKIPPED (dirty tree)" summary line, or fail when
  `--require-release-lint` is passed. The plan's progress log should cite only runs from a clean
  tree.

### TE5-21: Exposure-meter speech says "+0.0" while the compensation path says "±0.0" (Info, High confidence). Confirmed.

- **Where:** `exposureMeterSpeech` uses `%+.1f`. `formatEvComp` produces "±0.0".
- **Fix:** use one formatter and add a test for the zero case.

### TE5-22: `isoStops` snaps candidates outside the new ladder ends onto the ends (Info, Low confidence).

- **Example:** a lower bound of 10 jumps to 25.
- **Fix:** fall back to the raw rounded value outside the ladder span, with a table test.

### TE5-23: `frozenRecordingBitRate` still reads the live `bitrateLevel` (Info, Medium confidence).

- **Where:** `camera/CameraEngine.kt:6599`. The encoder and the diagnostic agree, and
  `bitrateLevel` is not part of CLAUDE.md's frozen REC packet. However, the helper's KDoc phrase
  "never the live Engine fields" is inaccurate.

### TE5-24: `ViewModelTestAccess.queuedCallbackCount` reflects on `MessageQueue.mMessages` without the queue lock (Info, Medium confidence).

- **Why:** it works under PAUSED looper mode, but is brittle across Robolectric `android-all`
  upgrades. It is used by the a2665544 regression test.
- **Fix:** prefer `shadowOf(looper).scheduler`-style APIs or a counting `Handler` seam.

## Flakiness sweep (all of `app/src/test`)

- `Thread.sleep` appears 3 times:
  - `ProcessAdmissionSignalTest.kt:110` (TE5-14);
  - `StandbyAudioControllerTest.kt:1618` and `FamilyDeletionMarkerIntegrationRobolectricTest.kt:181`,
    which are bounded deadline polls (acceptable).
  - `findFallbackThread` matches threads process-wide by name, so a leaked same-named thread from
    another test could satisfy it (low risk).
- No `@Ignore`, no `LooperMode.LEGACY`, and no `assertTrue(true)`.
- The cycle-4 Robolectric retry tests keep delayed retries inert through `paused`. `idle()` does not
  advance the clock.
- Reflection uses `getDeclaredField` / `single {}`, so a rename fails loudly.

## Final sweep: commonly missed items

- **Defaults:** no new defaulted fix seam was added in cycle 4 beyond those in TE5-2.
  `orphanDisposition(familyDeleted = false)` is defaulted but is a pure table, and every
  production call passes it (verified).
- **Time zones:** EXIF time-zone tests restore `TimeZone.setDefault` in `finally`.
- **Gradle in tools tests:** `test_upload_key_floor_gradle.py` invokes Gradle from the tools suite.
  It is deterministic (seeded generator), but adds a Gradle configuration to every tools run.
- **androidTest:** the four probes never fail the build by design (CLAUDE.md:
  `assembleDebugAndroidTest` is not device evidence). They give no regression protection; only
  `StillSizeProbeTest` touches a cycle-4 path (TE5-19).

## Files covered

- Every commit in `887d39fb..ea7d4374` touching `app/src/test` or `tools/tests` (60).
- The production hunks those commits fix, in:
  - `CameraEngine.kt`, `CameraController.kt`, `ManualControls.kt`, `CaptureCapabilities.kt`,
    `DngPreCaptureAllocation.kt`, `GlPipeline.kt`;
  - `CameraViewModel.kt`, `MainActivity.kt`, `Overlays.kt`, `ManualDials.kt`, `ZoomMath.kt`;
  - `StillCapturePipeline.kt`, `MediaStoreWriter.kt`.
- Tests: `ProcessAdmissionSignalTest`, `JpegExifSpliceTest`, `DngPreCaptureAllocationTest`,
  `DeviceRouteLawsTest` (`StillSizePickerTest`), `LaunchRecoveryPendingExpiryTest`,
  `BarePreflightRetryRobolectricTest`, `MomentaryHoldRobolectricTest`,
  `StandbyAudioControllerTest`, `FamilyDeletionMarkerIntegrationRobolectricTest`.
- Tools: `tools/tests/test_upload_key_floor_gradle.py`, `test_upload_key_policy.py`,
  `test_tool_contracts.py`, `tools/verify_host.py`, `tools/coverage/partition_report.py`,
  `partition-a-residuals.txt`.
- All four `app/src/androidTest` files.
