# RPL cycle 6: cycle-5 regression review

- Agent: c5-regression-reviewer (cycle 6). Finding prefix `RG6-`.
- HEAD: `30970c9e`. Scope: every commit in `ea7d4374..30970c9e` (65 commits, read with `git show`, checked against the
  current code at HEAD).
- Read-only. No Gradle and no `verify_host.py` were run. Two forked read-only sub-lanes covered the storage/capture/video
  commits and the log/tools/release commits. I checked their key claims against the code: S1 against `EncoderCaps.kt`
  and `CameraViewModel.kt`. Lane-sourced rows are marked "(lane)".

## Scope inventory

| Area | Commits | Files examined at HEAD |
|---|---|---|
| Cold start, pause and GL replay (special attention) | 2d0855c6, 8e329498, 16325bf7 | `camera/CameraEngine.kt`: `onPreviewSurfaceAvailable`, `completeGlInputReady`, `bindPreviewSurface`, `markPreviewPending`, `handlePreviewReady`/`Failure`, `pause`, `resume`, `release`, `reconfigureCamera`, `restartGlAfterPreviewSurfaceLoss`, `stopGlOwner`, `publishLensInventoryOnce`, `resolveInitialCameraRouteAvailability`. Also `gl/GlPipeline.kt` (`start`, `setPreviewOutput`, `applyPreviewOutput`, `dispatchWithResult`), `TerminalAcquisitionGate`, `dispatchGenerationOwnedPreviewBind`, and `CameraEngineInterruptedColdStartTest` |
| Engine still/DNG lane | 881e13af, daac4664, 3107c3f5, 06057385, 64c20327, 2d452f23, 58a73b1d, 88a1b928, 3ebf8d4f, 148fbd15, dc8ff38d, 3d1bfe81 (MRG5-5), bb1382e8 | `CameraEngine.kt` (DNG admission/cancel, `markCaptureDeleted`, BURST/AEB head, recall decision), `CameraController.kt` (`failStill`, AF override, chars), `DngPreCaptureAllocation.kt`, `RetainedStillDeletionOwner.kt`, `RecordingStorageDispatcher.kt`, `ManualControls.kt` |
| UI / ViewModel | ee60e694, 612fe276, 14504891, 61c88986, 64eefbe6, 7f260cc2, 63b8301d, d535ed28, 4e3c971d, a15ba927, 4f605424, fdaadf32, dd3edc64, c8f838d9, 009bf08f (MRG5-1/7), effc6e6f (MRG5-2), 665ab849 (MRG5-4), 4635bee2 (MRG5-6), 3489859e (MRG5-8) | `ui/CameraViewModel.kt`, `ui/ZoomMath.kt`, `ui/controls/{ProSheet,ManualDials,FnQuickActions,PhotoFormatChips}.kt`, `camera/CameraStatus.kt`, `camera/CameraState.kt`, `MainActivity.kt`, `AudioDenialReason.kt`, `MomentaryHold.kt`, `CaptureCapabilities.kt` (stab resolution) |
| Storage / capture / video (lane) | de3bb4bc, 94984106, 530ba47d, cda3d19a, 6e308a34, da3de2e1, 35ccc225, ed385953, cefa3bb5, 0d35c299, b1318fc3, 825fd87b | `storage/MediaStoreWriter.kt`, `capture/{StillCapturePipeline,HeifExif}.kt`, `video/{EncoderCaps,VideoRecorder}.kt`, the matching tests |
| Log / tools / release / docs (lane) | 715ccb0a, adda4175, 8e65456f, 7f47c8c6, ae7dc669, 1839998d, 9a8b4014, 7057154c, dcb296de, 47da5d79, 340157e9, ea8273d3, 5cfd6d34, 4a754a4d, plus the docs/plan commits | `DiagnosticTelemetry.kt`, `StartupTrace.kt`, `VendorTagInspector.kt`, `tools/{build_immutable_release,run_scoped_signed_release,check_docs,verify_host}.py`, `tools/coverage/partition-a-residuals.txt`, `CLAUDE.md`, `README.md`, `docs/FIELD_CHECKS.md` |

## Findings

| ID | Severity | Confidence | Status | Summary |
|---|---|---|---|---|
| RG6-1 | Medium | High | confirmed | (lane S1) A failed codec-inventory walk is still consumed as an authoritative empty inventory: HEIF and HLG/10-bit are narrowed away and persisted. The AGG5-30 fix is incomplete. |
| RG6-2 | Low | High | confirmed | AGG5-1 paused branch enumerates the lens inventory BEFORE route resolution. An external-only device pins `LensInventory.ALL` for the whole process. |
| RG6-3 | Low | Medium | likely | The cold start replayed by the resume rebind loses its startup trace. The resume's armed owner leaks at the `glInputPending` return, and the GL continuation carries the owner that pause revoked. |
| RG6-4 | Low | Low | needs manual validation | Resume rebind (missing input) plus resume's own reopen task can both converge the same generation and double-open the camera if the GL input lands before the reopen task dequeues. |
| RG6-5 | Low | Medium | needs manual validation | MRG5-5 terminal-id set is bounded at 32. A failed delete marker for an id evicted from both sets (or never registered) still closes still admission for the process. |
| RG6-6 | Low | Medium | needs manual validation | AGG5-23 restore is skipped when the encoder inventory lands between the recall and its rollback, and the replayed bank then owns the pipeline. |
| RG6-7 | Low | High | confirmed | (lane T1) The 12-row evidence reserve is shared by StartupTrace and the terminal FrameGap summary, so repeated cold starts can starve the soak's terminal FrameGap row. |
| RG6-8 | Low | High | confirmed | (lane T2) The once-per-process capability dump (~38 rows on PMA110) now spends the shared 168-row class and latches before admission. |
| RG6-9 | Low | Medium | likely | (lane S2) Passthrough privacy strip (35ccc225) keeps non-Exif APP1 (XMP) and EXIF `TAG_XMP`. Dormant on PMA110. |
| RG6-10 | Low | High | confirmed | (lane S3, S4) The AGG5-3 stop-throw fix is pinned only by a test-local driver, and the audio "codec errored" latch is also set by mic-read faults. |
| RG6-11 | Info | Medium | likely | `restoreRecordAudioFromGrant` dropped the `rejectIfRecording` guard that `onToggleRecordAudio` had, so a grant reconcile can flip `recordAudio` mid-REC. |
| RG6-12 | Info | High | confirmed | (lane T3) The AGG5-9 log tests would stay green if `processDiagnosticLogBudget` went back to 180. |
| RG6-13 | Info | Medium | likely | (lane T4, T5) The secret-fact scan skips non-UTF-8 text (latin-1 `.properties`, UTF-16). Release sealing hard-fails on a common `JAVA_TOOL_OPTIONS=-Dfile.encoding`, with no remedy named. |

No Critical or High regressions were found.

The special-attention AGG5-1 / AGG5-26 fix is sound on every interleaving I traced. Specifically:

- There is no camera acquisition while paused.
- No replay is skipped in the ordinary paths.
- A same-surface double bind is harmless.
- `release()` is safe.

RG6-2 through RG6-4 are edge residues of that fix, not breakages of PMA110's ordinary path. The ordinary foreground return (`previewReady && input present`) takes neither new branch, so it stays byte-identical, and `previewReady` is cleared only by `markPreviewPending` and `handlePreviewFailure`, never by `pause`/`invalidateCameraReady`.

---

## Special attention: AGG5-1 / AGG5-26 interleaving trace (2d0855c6, 8e329498)

Threads involved:

- main: surface callbacks, `pause`, `resume`, `release`.
- `setupExecutor`, serial: T1 is the GL start task, T2 the bind task, T3 resume's reopen, R the reopen tasks.
- the GL HandlerThread: `applyPreviewOutput` creates the input Surface and calls `onInputReady`, which leads to `completeGlInputReady` inside `terminalAcquisitionGate.runIfOpen`.

| # | Interleaving | Outcome at HEAD |
|---|---|---|
| a | Pause before T1 runs | T1 sees `paused`, sets `starting=false`, returns. `resume` (`!started`) calls `onPreviewSurfaceAvailable` again. OK (unchanged). |
| b | Pause then resume before T1 runs | `resume` re-dispatch is refused by `starting=true`. T1 then runs unpaused. OK. |
| c | Pause after T1 sets `started`, before T2 runs | T2's `isCurrent` (`!paused`) drops the bind. No preview output, so no input Surface and no input-ready callback, and `glInputPending` stays true. `resume`: `inputSurface == null` triggers the rebind T2', then T3. T3 normally runs before the GL thread finishes EGL window and renderer init, hits `if (glInputPending) return` (`CameraEngine.kt:4409`) and does nothing. The input-ready callback then runs `completeGlInputReady` unpaused (re-seed, gyro, route resolve, reopen). One open. OK, with residues RG6-3 and RG6-4. |
| d | Pause after T2 posted `setPreviewOutput`, before input-ready | `completeGlInputReady` re-seeds EIS, analysis callback, transfer, gain and assists, enqueues the inventory, and returns at `paused` (`:2077-2084`). `resume`: input present but `previewReady=false` (no camera frame yet), so it rebinds the same surface. `GlPipeline.applyPreviewOutput`'s same-surface/same-size path cancels the old signal and installs the new one. Then T3 opens (`controller == null`). OK. RG6-2 applies to the inventory ordering. |
| e | Preview-recovery rebind whose 200 ms delay spans a pause | `previewReady=false`, so `resume` rebinds. OK. The recovery budget is not reset by resume, so an exhausted surface gets exactly one more attempt and then the terminal status again. Acceptable. |
| f | Ordinary background with a Ready preview | `previewReady` and the input are kept across `pause`, so there is no rebind. `pause` nulls `controller`, so T3 re-resolves and reopens exactly as before the commit. Byte-identical to pre-cycle-5. OK. |
| g | Pause+resume quickly while T2 is still queued (cold start) | T2 runs unpaused and T2' also binds. `setPreviewOutput` bumps `previewOutputGeneration` on the setup thread, so the older block is stale (`applied=false`, `signal.cancel()`), or the second takes the same-surface path. No double EGL surface. OK. |
| h | `release()` racing any of the above | `release` closes the gate (it waits for an in-flight `runIfOpen` block, including `completeGlInputReady`), sets `paused=true`, `started=false`, and clears `glInputPending`. A queued T2' fails `isCurrent` (`started`/`paused`) and the gate. A late `completeGlInputReady` cannot start once the gate is closed. The one post-release side effect is the paused branch's queued `publishLensInventoryOnce`, which may still run on the executor. That matches the pre-existing unpaused enqueue: enumeration and a VM callback only, no native acquisition. OK. |
| i | Pause between `completeGlInputReady`'s `paused` read (false) and its reopen | `reconfigureCamera`'s setup task rechecks `paused` and the dual-open admission rechecks it inside the monitor. No open. A `gyro.start()` landing after `pause`'s `gyro.stop()` can leave sensors registered while backgrounded. That race predates this commit (same order as before). Not raised. |

Tests: `CameraEngineInterruptedColdStartTest` drives the real `completeGlInputReady` and `resume` bodies. Reverting either half of the fix fails a test: the analysis callback would be null, or the preview-output generation would not move. What the tests do not cover is the route-resolution ordering in RG6-2; the paused test asserts that the inventory was published without resolving the route first.

---

## RG6-1 (lane S1): codec inventory failure still narrows and persists (incomplete AGG5-30, ed385953)

- Severity Medium. Confidence High. Confirmed against code.
- Where:
  - `video/EncoderCaps.kt:123-142`: `load()` returns `CodecInventory.EMPTY` without latching.
  - `ui/CameraViewModel.kt:3043-3090`: `loadEncoderInventoryAsync` / `applyEncoderInventory`.
- Why it is a problem: the process latch was removed, but the ViewModel cannot tell a failed walk from a real empty inventory. `applyEncoderInventory(EMPTY)` then:
  - sets `encoderInventoryLoaded = true`;
  - drops HEIF (`heifEncodeAvailable=false`);
  - normalizes the transfer to SDR (`tenBitEncodeAvailable=false`);
  - publishes `setVideoPipeline` with no candidates;
  - lets the debounced save persist the narrowed formats and transfer (Remember Settings defaults ON).
- Nothing in that ViewModel retries.
- Failure scenario: one mediaserver hiccup at launch leaves the session with no encoder and no HEIF, and the operator's HEIF/HLG selection is permanently overwritten even after a later process walks successfully.
- Fix: have `load()` return a typed `Loaded`/`Failed` result. On `Failed`:
  - keep `encoderInventoryLoaded=false` and the `pending*UntilInventory` intent;
  - skip normalization and persistence;
  - schedule a bounded VM retry.

## RG6-2: the paused input-ready branch enumerates the lens inventory before route resolution (2d0855c6)

- Severity Low. Confidence High. Confirmed.
- Where: `camera/CameraEngine.kt:2077-2084` (paused branch of `completeGlInputReady`), `:1454-1500` (`publishLensInventoryOnce`), `:1585-1610` (`resolveInitialCameraRouteAvailability`).
- What happens in the unpaused order: `resolveInitialCameraRouteAvailability()` runs first, and only then is the inventory enqueued (`:2089`, `:2101`).
- What happens in the new paused branch:
  1. The inventory is enqueued while `cameraRouteInventory` is still `CameraRouteInventory.UNKNOWN` (`back = true`).
  2. `publishLensInventoryOnce` therefore enumerates BACK candidates.
  3. On an external-only device (a supported route: "admits a plain GENERIC external camera when it is the sole route") that yields no lenses and no range, so `lensInventoryOf(empty, null)` returns `LensInventory.ALL`.
  4. That result is published and latched, and `acceptedOpticalPresets` becomes the four PMA110 presets.
  5. Resume's first `resolveInitialCameraRouteAvailability` sees every id as changed (`knownCameraIds` starts empty), calls `invalidateCameraTopologyCaches()`, and clears `lensInventoryPublished`.
  6. Nothing on the resume path enqueues the inventory again: T3 only reconfigures, and `convergeAfterRouteTopologyChange` is not reached on an ordinary start.
- Failure scenario: an external-camera-only device backgrounded during cold-start GL init comes back with a 0.6/1/3/10× rail and finder gate it cannot reach for the whole process. That is the 2026-08-02 `LensInventory` defect, reopened through this path. PMA110 is unaffected: back=true is the truth there, and the enumeration is identical.
- The commit's own comment ("the unpaused order below is unchanged and this paused branch simply cannot lose it") is true of the unpaused order but not of what the paused branch enumerates.
- Fix, either of:
  - drop the paused-branch enqueue and have resume's T3 enqueue `publishLensInventoryOnce()` after its `resolveInitialCameraRouteAvailability()` (mirroring the startup order); or
  - make `publishLensInventoryOnce` return early while `!cameraRouteInventoryResolved`.
- Add a test that asserts the inventory sees a resolved route.

## RG6-3: the replayed cold start loses its startup trace (2d0855c6)

- Severity Low (debug diagnostic only). Confidence Medium. Likely.
- Where: `CameraEngine.kt:7793-7899` (`resume`), `:4409` (`if (glInputPending) return`, no `revoke`), `:2064-2097`.
- In interleaving (c):
  - `resume` begins a NEW owner (`startupTraceOwnership.begin()`) and passes it only to T3. T3 returns at the `glInputPending` guard without revoking it.
  - The real open is then driven by `completeGlInputReady`, using the owner captured in the ORIGINAL `onPreviewSurfaceAvailable`, which `pause()` already revoked.
  - Result: the cold start that actually happens has no `StartupTrace` line, and the resume's owner stays armed with zero marks until the next pause.
- This is inert today, because finish requires the exact claimed owner. But it is the "armed zero-mark owner" shape CLAUDE.md warns about, and it hides exactly the interrupted-cold-start latency the field check A9 asks to measure.
- Fix: in the guard, revoke the passed owner (`if (glInputPending) { startupTraceOwnership.revoke(startupTraceOwner); return }`). Optionally stash the resume owner so `completeGlInputReady` uses the current owner, not the captured one, when the captured one was revoked.

## RG6-4: possible double open on the missing-input resume path (2d0855c6)

- Severity Low. Confidence Low. Needs manual validation.
- Where: `CameraEngine.kt:7855-7899`.
- Mechanism: `resume` enqueues the rebind T2' and then T3. `setPreviewOutput` is asynchronous (`dispatchWithResult` posts to the GL handler), so T3 normally sees `glInputPending` and returns. Now suppose the GL thread finishes the window surface, `renderer.init`, the SurfaceTexture and `onInputReady` before the setup thread dequeues T3 (a preempted setup thread on a loaded device):
  1. `completeGlInputReady` enqueues reopen R1, so the queue is `[T3, R1]`.
  2. T3 sees `controller == null` (R1 has not run) and calls `reconfigureCamera` with the input present: `invalidateCameraReady()` plus R2, so the queue is `[R1, R2]`.
  3. Both snapshots carry the same optics generation (`currentOpticsReconfiguration` does not bump), so R2 owns the transaction. It dual-opens the id R1 just opened, falls back to sequential, then closes and reopens.
- Result: an extra close/open blackout and a same-device double open on the HAL.
- Fix: when `resume` issued the rebind because the input was missing, let the input-ready continuation own the open (skip T3). Or have T3 also skip while an open for the current generation is already queued or installed.

## RG6-5: a failed delete marker can still close still admission for the process (daac4664, 3d1bfe81 / MRG5-5)

- Severity Low. Confidence Low. Needs manual validation.
- Where: `camera/RetainedStillDeletionOwner.kt:117`, `:131-134`; `CameraEngine.kt:5536-5598`.
- How a capture closes admission: `producerTerminalIds` is trimmed oldest-first to `maxTombstones` (`MAX_RETAINED_STILL_DELETE_TOMBSTONES = 32`), and `familiesByCapture` is bounded at 32 too. A family evicted from the registry makes `markCaptureDeleted` non-durable unconditionally (`family != null && ...`). So a live-still delete for a capture whose id has fallen out of both sets lands in `nonDurableLiveDeletions` with no producer edge left to clear it. Admission then stays closed until process death: the exact AGG5-2 symptom.
- Condition for the failure scenario: review stays pinned on an older live capture while 32 or more newer stills reach terminal (a long BURST/timelapse run under an open review, if the UI allows it), and then Delete is pressed.
- Fix, either of:
  - treat an id below the oldest retained terminal id (capture ids are monotonic) as terminal; or
  - record terminality as a high-water mark plus the bounded set, so eviction cannot turn "terminal" back into "may still produce".

## RG6-6: recall rollback misses the inventory-landed interleaving (4f605424, 4635bee2, 3489859e)

- Severity Low. Confidence Medium. Needs manual validation.
- Where: `ui/CameraViewModel.kt:1153-1170` (restore guarded by `!_state.value.encoderInventoryLoaded`), `:3058-3090` (`applyEncoderInventory` consumes and clears the pending trio and publishes `setVideoPipeline` under a NEW pipeline generation).
- Sequence:
  1. A post-start MR recall arms the pending codec/transfer/formats.
  2. The inventory lands before the recall's asynchronous reopen fails.
  3. `applyEncoderInventory` applies the bank's codec/transfer/formats and moves the pipeline generation, so the Engine's rollback no longer owns the pipeline packet and does not restore it.
  4. The VM's restore is skipped because the inventory is now loaded.
- Result: the rolled-back bank's AVC/S-Log3/JPEG-only owns the pipeline and is persisted, which is the AGG5-23 symptom through a second door.
- How narrow it is: the window is a recall tapped within the first walk, before the inventory loads. The settings restore runs before `started`, so it never rolls back.
- MRG5-6 itself is correct: the restore is keyed on the returned generation. MRG5-8 is correct too.
- Fix: when a rollback for the recall's generation arrives after the inventory consumed `armed`, re-apply `prior` through the same normalization (`applyEncoderInventory`-style). Or remember that the inventory replay used the recall's values and undo that replay.

## RG6-7 (lane T1): the evidence reserve is shared by unequal producers (715ccb0a)

- Severity Low. Confidence High. Confirmed.
- Where: `camera/StartupTrace.kt:39`, `gl/GlPipeline.kt:906-915`, `camera/DiagnosticTelemetry.kt:69-72`.
- `StartupTrace` emits one evidence row per resume/open. The terminal FrameGap summary emits one per GL stop. Both draw from the same 12-row reserve.
- Failure scenario: after the 168 shared rows are spent in a long debug soak, about 12 background/foreground cycles exhaust the reserve, and the soak's terminal FrameGap row is dropped. "No FrameGap line" then reads as "no stall", which is the false pass AGG5-9 targeted.
- Fix: give each producer its own reserve (for example 6 + 6), or let StartupTrace spend the reserve once per process.

## RG6-8 (lane T2): the capability dump displaces the shared budget (adda4175)

- Severity Low. Confidence High. Confirmed.
- Where: `camera/VendorTagInspector.kt:46-62`.
- The dump costs about 38 rows on PMA110 (11 characteristics sets × 2, plus the ConcurrentProbe, PhysicalProbe and header rows). It now spends the 168-row shared class about 5 s into every debug process, roughly 23% of the budget before any soak starts.
- `claimDump()` latches before the first row is admitted, so a dump that meets an exhausted budget is lost for the process.
- Fix: one row per camera or a capped dump (or its own small owner), with the 168/12/120 arithmetic updated, and latch after the first admitted row.

## RG6-9 (lane S2): passthrough privacy strip is incomplete (35ccc225)

- Severity Low. Confidence Medium. Likely. Dormant on PMA110 (hi-res lane only).
- Where: `capture/StillCapturePipeline.kt:745`, `:903`; `capture/HeifExif.kt:121`.
- `exifSplicePlan` replaces only `Exif\0\0` APP1 and keeps any other APP1 verbatim. A vendor XMP APP1 (which can carry `exif:GPS*` or serials) and IFD0 `TAG_XMP` (0x02BC) both survive.
- Fix: in the passthrough lane, drop XMP and other non-allow-listed APPn segments (keep APP0, ICC APP2 and MPF), and add `TAG_XMP` to `PASSTHROUGH_PRIVACY_STRIPPED_TAGS`.

## RG6-10 (lane S3/S4): AGG5-3 test and latch scope (0d35c299)

- Severity Low. Confidence High. Confirmed.
- Where: `video/RecorderQuarantineAdmissionGateTest.kt:160`, `video/VideoRecorder.kt:672`, `:813`.
- The "fake graph" test re-implements the step order in the test. Each of these reverts keeps every test green:
  - restoring the `signalEndOfInputStream`/`stop` call sites;
  - removing the latch writes;
  - removing `stopAudioInputBeforeQuarantine()`.
- Separately, `audioCodecErrorLatched` is set for every terminal audio fault, including a negative `AudioRecord.read` from a dead mic route. A later `audioCodec.stop()` `IllegalStateException` on a healthy codec is then treated as an Error-state skip. `release()` still runs and still quarantines on failure, so the impact is small.
- Fix:
  - add one seam-level test that drives `VideoRecorder`'s stop sequence with a throwing codec stub;
  - latch only on throws from `MediaCodec` calls.
- Lane note S5 (Info): a user stop can still race an in-progress video encoder error before the latch is set. That is pre-existing and narrowed by this commit; worth naming in the AGG5-3 field check.

## RG6-11: grant restore can flip `recordAudio` mid-REC (a15ba927)

- Severity Info. Confidence Medium. Likely.
- Where: `ui/CameraViewModel.kt:2860-2865`.
- `restoreRecordAudioFromGrant` replaced `onToggleRecordAudio(true)` but lost its `rejectIfRecording()` guard. `MainActivity.refreshPermissionState` calls `vm.reconcileMicrophoneGrant()` on every refresh. A refresh during a take (multi-window Settings grant, or any `onResume` while still rolling) now sets `recordAudio=true` and schedules a save while the take stays video-only (`doAudio` is fixed at start).
- The standby meter is correctly gated on `recording`, so there is no second mic owner. The only effect is UI/persisted state disagreeing with the clip.
- The old path was arguably worse: the toggle refused, but the reason store still cleared.
- Fix: defer the restore to REC end (or return false while recording so the reason is not cleared).

## RG6-12 (lane T3): log-budget tests would survive a revert (715ccb0a, ea8273d3)

- Severity Info. Confidence High. Confirmed.
- Where: `camera/DiagnosticLogTest.kt`.
- "a gated emission is charged exactly once" gates and logs inside the test body, so it exercises no production producer. "the evidence reserve is carved out…" asserts `usedRows() <= 168`, which stays true at 180. The real protection is the `check_docs.py` double-charge inventory.
- Fix: assert the configured `maxRows == SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET`.

## RG6-13 (lane T4/T5): secret scan encoding gap; release-seal operator friction (7f47c8c6, 8e65456f)

- Severity Info. Confidence Medium. Likely.
- Secret scan: `tools/check_docs.py` `published_text` skips any file that fails UTF-8 decoding as binary, which includes latin-1 `.properties` with one non-ASCII byte and UTF-16 text. Fix: fall back to latin-1 for NUL-free payloads, or fail on undecodable text-suffixed files.
- Release seal: `tools/build_immutable_release.py` hard-fails the keytool step on a common `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` (the Gradle child silently drops it). That behavior is deliberate. Fix: name the offending variable/token and the remedy in the error and in README's release section.
- Lane verification: no init-script or jvmargs channel is left open; the PathAssembler-derived wrapper dist path was resolved on this machine.

---

## Commits checked clean (beyond the special-attention pair)

- 881e13af (AGG5-5):
  - Ready/session are retired before DNG owners are cancelled.
  - Every early `releaseDngAdmission()` is idempotent: the `Lease.release` CAS, with the `finally` as backstop.
  - `refresh { !occupied.get() }` closes the AGG4-35 publish race.
  - A cancel racing `arm()` completes the deadline.
- 3107c3f5, 06057385, 64c20327:
  - `rawChars` is nullable only for processed-only shots.
  - The still's AF override reads the frozen packet, and the repeating paths pass the live packet (byte-identical for PMA110 repeating requests).
  - `failStill` is guarded by `captureTokenIsCurrent` and `done`; buffer loss is matched by Surface identity.
- 2d452f23: a stale recording terminal forwards SAVED media only to the id-ordered tracker (TRACK_ONLY) and suppresses only the stale "saved" status. FAILED/RETAINED/KEPT_UNVERIFIED still announce.
- 58a73b1d / 64eefbe6: every call site now passes the former default explicitly. The still passes `previewExposureCap=false` (unchanged), and the repeating build passes `true` (unchanged). There is no wire change.
- 88a1b928 + 009bf08f (MRG5-1/7):
  - The latch moved into the VM fold.
  - A non-announcing verdict moves the latch at once; an announcing one only after `publishStatus` reports it shown.
  - DNG-only takes precedence and does not latch RAW loss, so it re-announces later. Correct.
- 3ebf8d4f:
  - The BURST/AEB head result answers the press, and a throwing head is contained like SINGLE.
  - `dispatchAebStep` restores base controls on refusal or throw.
  - Continuation return values are discarded safely.
- 148fbd15: a pure extraction with identical inputs.
- 14504891 / 61c88986 / 665ab849 (AGG5-49/12, MRG5-4):
  - Logical route 1:1 is a justified PMA110 change (3.1× → 3.0× at the 3× preset).
  - The standalone divisor stays nominal 23 mm within the 10% band (PMA110 byte-identical), and the measured main is used outside it.
  - The Shoot-tab slider now shares `zoomRulerScale`.
- 7f260cc2 (status plate):
  - Responses cover and defer a higher event, and the covered event returns for its remaining time via `shownExpiresAtMs` + `postAtTime` (uptime clock, consistent).
  - Resolutions drop stale events.
  - ERROR-severity conditions rank at ERROR.
  - `*_UNCHANGED` ends only optics-family conditions.
  - I found no lost-timer or resurrection path.
- d535ed28 / 4e3c971d: punch-in assist and hold ownership are exclusive. Rebinding ends the old action's hold and restores the operator value.
- fdaadf32: invalidation fires only on an actual route change, posted to main.
- dd3edc64 (AGG5-4):
  - The request is persisted and the display is narrowed.
  - The Engine resolves the stab request per route with `videoStabControlModeFor`, which maps exactly like `normalizedForAvailableModes`. The HAL mode is therefore identical to the old narrowed push on every route; `applyStabilization` runs on every reconfigure.
  - `reconcileFrameRate` no longer mutates the request or the MR slot.
- 4635bee2 / 3489859e (MRG5-6/8): keyed on the generation `setResolvedOptics` returned. Stab/fps are restored only while they still hold the recall's value.
- Lane-verified clean:
  - de3bb4bc, 94984106, 530ba47d, cda3d19a, 6e308a34 (EXIF splice bytes, the >64 KB refusal, HEIF/JPEG parity), cefa3bb5, b1318fc3, 825fd87b;
  - 1839998d, 9a8b4014, 7057154c, dcb296de, 47da5d79, 340157e9, 5cfd6d34, 4a754a4d (every re-cited residual line lands on its described line at HEAD).
