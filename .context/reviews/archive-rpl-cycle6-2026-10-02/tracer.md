# Tracer review — RPL cycle 6 (TR6)

- Agent: tracer (c6), causal end-to-end tracing with competing hypotheses
- HEAD: `30970c9e` (cycle-5 range `ea7d4374..30970c9e`)
- Mode: read-only. No Gradle, no state-changing git.

## Scope inventory

Flows traced across threads (main, `setupExecutor`, GL thread, camera handler, `ioExecutor`, process
owners). The cycle-5 commits are the newest code on each flow, so each was traced first:

| Flow | Files examined | Cycle-5 commits traced |
|---|---|---|
| Lifecycle: `onStart/onStop` → `resume/pause`, GL-first cold start, input-ready callback, preview rebind | `camera/CameraEngine.kt` (1949-2420, 7701-7900, 7343-7407), `gl/GlPipeline.kt` (270-510) | `2d0855c6`, `8e329498` |
| Optics transactions and rollback, MR recall, settings restore → engine push | `ui/CameraViewModel.kt` (796-900, 1080-1200, 1590-1810, 3370-3520, 3915-3990), `camera/CameraEngine.kt` (2924-3100, 3555-3631, 4383-4470), `camera/OpticsConstraints.kt` | `4f605424`, `4635bee2`, `3489859e`, `dd3edc64`, `fdaadf32`, `a15ba927`, `d535ed28`, `4e3c971d` |
| Still capture → DNG/HEIF publish → deletion owner → review tracker | `camera/CameraEngine.kt` (5000-5160, 5504-5850, 5900-6150), `camera/CameraController.kt` (2080-2330), `camera/DngPreCaptureAllocation.kt`, `camera/RetainedStillDeletionOwner.kt`, `ui/CaptureOutputTracker.kt` (230-420) | `881e13af`, `daac4664`, `3d1bfe81`, `64c20327`, `06057385`, `3107c3f5`, `3ebf8d4f` |
| REC admission → stop → finalize, storage presentation | `camera/RecordingStorageDispatcher.kt`, `video/VideoRecorder.kt` (140-230, 460-480, 600-680, 800-830) | `2d452f23`, `0d35c299` |
| Status plate and Ready fold | `ui/CameraViewModel.kt` (960-1070, 1993-2110), `camera/CameraStatus.kt` | `88a1b928`, `009bf08f`, `7f260cc2` |
| Audio provenance | `AudioDenialReason.kt`, `MainActivity.kt` (940-965), `ui/CameraViewModel.kt` (2845-2871) | `a15ba927` |

Known items from the cycle-5 aggregate and plan are not re-reported (AGG5-6, 36, 38, 40, 42-44, 46-48,
53, 63-rest, 66, 68-70; AGG4-10's "trailing setters are not undone by rollback" class). TR6-1 is in
that class, but it is new: the merge fix MRG5-8 created a three-way split that did not exist before.

## Findings

| ID | Severity | Confidence | Status | Summary | Primary cite |
|---|---|---|---|---|---|
| TR6-1 | Medium | High | confirmed | A recall rollback restores the stab/fps REQUEST (MRG5-8) but not the Engine's `videoStabMode`/`videoFrameRate` or the displayed values. Wire, UI and persisted values split three ways, and re-picking the shown mode is a no-op | `ui/CameraViewModel.kt:1168-1177, 1728, 1748, 1784, 1804, 3435, 3515`; `camera/CameraEngine.kt:1802, 3561-3564, 6197` |
| TR6-2 | Low-Medium | High | confirmed | `producerTerminalIds` evicts oldest-first at 32. A delete whose marker fails for a capture older than 32 later terminal captures closes still admission for the process, the MRG5-5 shape through another eviction | `camera/RetainedStillDeletionOwner.kt:117-124, 129-134`; `camera/CameraEngine.kt:5561-5563` |
| TR6-3 | Low | Medium | likely | Cold-start task reads `paused` outside the monitor, then clears `starting`. A `resume()` landing between the two is refused by the still-true `starting`, and nothing restarts: black viewfinder until the next surface event | `camera/CameraEngine.kt:1994-1998, 1974-1982, 7838-7843` |
| TR6-4 | Low | High | confirmed | The grant reconciliation (`restoreRecordAudioFromGrant`) has no recording guard. A grant seen on `onResume` mid-take flips `recordAudio = true` over a silent take, and the meter shows "audio on" for a clip with no audio track | `ui/CameraViewModel.kt:2860-2865, 2871`; `MainActivity.kt:961`; `ui/CameraScreen.kt:1262` |
| TR6-5 | Low | Medium | likely | AGG5-23 is incomplete when the encoder inventory lands between a pre-inventory recall and its rollback. `applyEncoderInventory` consumes the armed bank codec/transfer/DNG and republishes the pipeline, so the rollback restores neither | `ui/CameraViewModel.kt:1083-1085, 1156-1166, 3058-3095` |
| TR6-6 | Low | Medium | likely | The DB5-13 worker recheck (`isRetired()`) is check-then-act. A cancel after the check still lets a provider insert run with no rejected-cleanup reservation (fails closed into launch recovery) | `camera/DngPreCaptureAllocation.kt:148-160` |
| TR6-7 | Info | High | confirmed | `audioCodecErrorLatched` is set by any audio-thread exit, including a negative `AudioRecord.read` on a healthy codec. A real native `stop()` failure of that codec is then skipped to `release()` instead of being quarantined | `video/VideoRecorder.kt:810-818, 606-607` |
| TR6-8 | Info | Low | needs manual validation | A user Stop that races an asynchronous codec error can reach `signalEndOfInputStream` before the drain thread latches `videoCodecErrorLatched`, so the AGG5-3 quarantine still happens on that interleaving | `video/VideoRecorder.kt:466-478, 668-673` |

---

## TR6-1 — Recall rollback splits the stab/fps request, wire and display three ways (Medium / High, confirmed)

**Trace.** `applyLoaded` (MR recall or settings restore) does the following:

1. `engine.setResolvedOptics(...)` begins optics generation G. The packet carries no stab mode and no
   video frame rate (`OpticsRollbackPublication`, `camera/OpticsConstraints.kt:24-50`).
2. It records `RecallRollbackRestore(armedStabMode = e.videoStabMode, priorStabMode, armedFrameRate,
   priorFrameRate)` and overwrites `requestedVideoStabMode` / `requestedVideoFrameRate`
   (`CameraViewModel.kt:1685-1708`).
3. Outside the transaction it pushes `engine.setVideoStabMode(e.videoStabMode)` (`:1728`) and
   `engine.setVideoFrameRate(safeFrameRate)` (`:1748`), and writes `videoStabMode = e.videoStabMode`
   and `videoFrameRate = safeFrameRate` into `_state` (`:1784`, `:1804`).

When generation G fails and the Engine rolls back, the VM handler runs (`:1080-1200`). It restores:

- `controls = rollback.controls`, the Engine's baseline. `controls.fps` is now the PRIOR fps.
- since MRG5-8, `requestedVideoStabMode` / `requestedVideoFrameRate` go back to the prior values
  (`:1168-1177`).

It does NOT restore:

- the Engine's `videoStabMode` (`CameraEngine.kt:1802`) or `videoFrameRate` (`:398`, written by
  `setVideoFrameRate` at `:3561-3564`). Neither is in the rollback baseline, and nothing re-pushes
  them;
- `_state.videoStabMode` / `_state.videoFrameRate`, which keep the bank's values.

**Resulting state.** All three values disagree:

| Value | Holds |
|---|---|
| Wire (Engine) | bank's stab mode; bank's encoder rate (`frozenFrameRate = videoFrameRate`, `:6197`) |
| Displayed | bank's stab mode and fps |
| AE target fps (`controls.fps`) | prior fps |
| Persisted (`currentExtras` reads the request, `:1864`, `:1891`) | prior stab mode and fps |

**Failure scenario.**

1. The operator is on TELE Video with Active (ENHANCED) stabilization at 30 fps.
2. They recall a bank stored with stabilization OFF at 60 fps, and that recall's reopen fails, so the
   Engine rolls back.
3. The camera returns to the prior TELE session, but that session is built with the bank's OFF:
   `applyStabilization` reads the Engine's `videoStabMode`.
4. The next REC encodes at 60 fps while the AE loop pins the 30 fps range from the restored
   `controls.fps`.
5. On the next caps publication, `reconcileZoomToCaps` shows `normalized(request = ENHANCED)`
   (`:3435`). It no longer pushes the Engine (that push was removed in `dd3edc64`), so the UI reads
   "Active" while the HAL runs OFF. The OFF state is the AGG5-4 user impact: 300 mm clips without the
   OIS+EIS profile.
6. Re-picking "Active" hits the early return
   `if (mode == requestedVideoStabMode && normalized == current.videoStabMode) return` (`:3515`), so
   the Engine is never corrected. The operator has to pick a different mode and then pick Active
   again.

The frame rate converges only when a later caps, resolution or codec change runs
`reconcileFrameRate()`.

**Why this is new.** Before MRG5-8 a rolled-back bank's stab/fps simply persisted. That was wrong,
but wire, display and persistence agreed. MRG5-8 restored only the request field.

**Suggested fix.** In the rollback handler, after the request restore:

- call `engine.setVideoStabMode(requestedVideoStabMode)` and set `_state.videoStabMode` to the
  normalized request;
- run `reconcileFrameRate()` (or `applyVideoFrameRate(...)`) against the restored request so that
  `controls.fps`, the Engine rate and `_state.videoFrameRate` agree.

The cleaner alternative is to carry stab mode and frame rate in the Engine's generation-owned baseline
next to `controls`. Add a Robolectric test: rollback of a recall that changed stab and fps, then
assert Engine `videoStabControlMode`, the Engine frame rate, the displayed values and `currentExtras`
all agree.

## TR6-2 — Bounded `producerTerminalIds` re-opens the MRG5-5 process-lifetime closure (Low-Medium / High, confirmed)

**Trace.**

- `completeDeletionDurability(id, durable = false)` adds `id` to `nonDurableLiveDeletions` unless
  `id in producerTerminalIds` (`RetainedStillDeletionOwner.kt:117-124`).
- `producerTerminalIds` is trimmed oldest-first to `maxTombstones = 32` (`:132-134`;
  `MAX_RETAINED_STILL_DELETE_TOMBSTONES = 32`, `CameraEngine.kt:8392`).
- An entry in `nonDurableLiveDeletions` is cleared only by that id's producer-terminal edge or by a
  later durable marker for it (`:116`, `:131`). A capture whose producers settled long ago never emits
  another edge.

**Failure scenario.**

1. The operator opens review on capture X. Review pins exactly that family (CLAUDE.md "Opening review
   pins that exact family").
2. A timelapse keeps firing, and 33 or more later captures settle. X is evicted from
   `producerTerminalIds`; its family is also trimmed from `familiesByCapture`.
3. The operator deletes X to free space on the disk the timelapse just filled. This is the AGG5-2
   motivating case.
4. `MediaStoreWriter.markFamilyDeletedResult` fails for lack of space (`CameraEngine.kt:5550-5563`).
5. `completeDeletionDurability(X, false)` sees X absent from `producerTerminalIds` and adds it to
   `nonDurableLiveDeletions`.
6. `canAdmitCapture()` is false for the life of the Engine. Every still is refused until the process
   restarts. This is exactly the AGG5-2 symptom, which MRG5-5 fixed only for the family-evicted shape.

**Suggested fix.** Invert the bookkeeping: track the set of LIVE ids (registered in `shotSpec`, removed
at `markCaptureProducersTerminal`). That set is naturally bounded by in-flight work. Treat any id
`<= captureSeq` that is not live as terminal. Alternatively, record a monotonic "lowest still-live
id" watermark. Add a test with 40 settled captures, then a failed marker on the first one:
`canAdmitCapture()` must stay true.

## TR6-3 — Cold-start `paused` check races `resume()` (Low / Medium, likely)

**Trace.** The cold-start task on `setupExecutor` reads `paused` outside the engine monitor, and only
then takes the monitor to clear `starting` (`CameraEngine.kt:1995-1998`):

```kotlin
if (paused || UnsafeRecorderQuarantine.isActive()) {
    startupTraceOwnership.revoke(startupTraceOwner)
    synchronized(this) { starting = false }
    return@runIfOpen
}
```

`resume()` on main sets `paused = false`, sees `!started`, and calls `onPreviewSurfaceAvailable`
(`:7803`, `:7838-7843`). Under the monitor, that call refuses while `starting` is true (`:1974-1982`).

**Interleaving.**

1. Setup task reads `paused == true`.
2. Main: `resume()` sets `paused = false`.
3. Main: `onPreviewSurfaceAvailable` sees `starting == true`, so `dispatchStart = false`, and returns.
4. Setup task: `starting = false`, then returns.

Nobody starts GL or the camera. Backgrounding keeps the TextureView surface alive, so no availability
callback follows: the viewfinder is black until a surface size/destroy event or the next
`onStop`/`onStart`. The window is a few instructions wide. The trigger, a fast stop/start such as a
keyguard launch, is a documented hazard of this app.

**Suggested fix.** Decide and retire under one monitor:
`val refused = synchronized(this) { if (paused || quarantined) { starting = false; true } else false }`.
Alternatively, have `resume()` re-dispatch after `starting` clears (for example, set a "resume wanted"
flag that the refusing branch replays).

## TR6-4 — Grant reconciliation flips audio on mid-take (Low / High, confirmed)

**Trace.** `a15ba927` replaced `vm.onToggleRecordAudio(true)` with `restoreRecordAudioFromGrant`
(`CameraViewModel.kt:2860-2865`). The old door started with `rejectIfRecording()`; the new one has no
recording check. It is reached from `MainActivity.refreshPermissionState()`, which `onResume` runs
(`MainActivity.kt:961`), through `restoreIfGranted`.

A take keeps rolling across `onPause`/`onResume` without an `onStop`, for example in multi-window
while the operator grants the microphone in Settings in the other pane. In that case:

1. `recordAudio` becomes true over a take whose recorder was built with `doAudio = false`.
2. The REC meter gate `mode == VIDEO && recordAudio && (detailsVisible || isRecording)`
   (`CameraScreen.kt:1262`) shows a flat level meter for a silent clip.
3. The plate announces "Microphone allowed — audio on".

Neither is true for the clip being recorded. The next take is correct.

**Suggested fix.** While `isRecording`, defer the restore to the REC terminal. Keep the denial reason
and re-run `restoreIfGranted` when recording ends. Do not write `recordAudio` mid-take.

## TR6-5 — Inventory landing between a recall and its rollback defeats AGG5-23 (Low / Medium, likely)

**Trace.**

1. A pre-inventory recall arms `pending*UntilInventory` with the bank's AVC, S-Log3 and DNG
   (`:1670-1681`).
2. `loadEncoderInventoryAsync` completes on main before the recall's rollback is posted.
   `applyEncoderInventory` consumes the armed values and nulls them (`:3058-3066`). It then publishes
   the bank's codec/transfer through `engine.setVideoPipeline`, which is a new pipeline publication
   generation, and calls `engine.setRawWanted(bank DNG)`, which is a direct write (`:3078-3083`).
3. The recall's rollback arrives:
   - `pipelineOwned` is false (`:1083`), so `transfer` and `videoCodec` keep the bank's values;
   - the AGG5-23 restore is skipped because `encoderInventoryLoaded` is now true (`:1156`);
   - `liveRawWanted` keeps the bank's DNG.
4. The rollback's `scheduleSettingsSave()` persists the pipeline of a bank the operator was told did
   not load. This is the AGG4-11 / AGG5-23 symptom.

The window is the first few hundred ms after launch, and it needs a failing recall in that window.

**Suggested fix.** When `applyEncoderInventory` consumes values that a live `recallRollbackRestore`
armed, move `recallRollbackRestore.armed` onto the post-inventory fields. The rollback then restores
the prior codec/transfer/formats through `setVideoPipeline` and `setRawWanted` with prior values.
Alternatively, treat that consumption as part of the recall's transaction.

## TR6-6 — DNG worker retired-check is check-then-act (Low / Medium, likely)

**Trace.** The DB5-13 guard `if (attempt.isRetired()) return@dispatch`
(`DngPreCaptureAllocation.kt:152`) runs before the provider call. A `cancel()` (from
`invalidateCameraReady`) can land just after the check. The retirement releases the process DNG slot
and the rejected-cleanup reservation, and then `allocate()` inserts a REGISTERED row anyway. Its
delivery is STALE, so it goes to `onLateValue`, now with no reserved cleanup capacity.

This fails closed: the row stays REGISTERED for launch recovery. It is still the exact window the
DB5-13 comment says was closed, so either narrow the claim in the comment or keep the reservation
until a dequeued worker finishes.

## TR6-7 — Audio "codec error" latch also set by a mic read failure (Info / High, confirmed)

**Trace.** The audio thread's catch-all sets `audioCodecErrorLatched` for every exception
(`VideoRecorder.kt:810-818`). That includes the negative-read throw on a dead mic route, where the
AAC codec itself is healthy.

At finalize, `AUDIO_CODEC_STOP` goes through `codecCleanup(..., audioCodecErrorLatched.get())`
(`:606-607`). An unexpected native `IllegalStateException` from `stop()` on that healthy codec is then
classified `SkippedToRelease`, not `Failed`. That weakens the "quarantine on unproven release"
contract for a case AGG5-3 did not intend.

**Suggested fix.** Latch only for throws that came from a `MediaCodec` call. Keep the read-failure
degrade separate.

## TR6-8 — User Stop vs asynchronous codec error ordering (Info / Low, needs manual validation)

**Trace.** `videoCodecErrorLatched` is set only when the drain thread observes the error
(`:668-673`). The finalizer's `signalEndOfInputStream` (`:466`) can run first: the user presses Stop
in the interval between the codec entering the Error state and the drain thread's next
`dequeueOutputBuffer`. The state-check throw is then classified `Failed`, and the process is
quarantined, which is the AGG5-3 outcome on that interleaving.

This needs a device trace to see whether the window is reachable. A possible fix: on an ISE from EOS,
join the drain briefly and reclassify if it latched.

---

## Traced and found consistent (no finding)

- **AGG5-1/26 resume rebind.** Rebinding a surface the GL thread already owns takes GlPipeline's
  same-surface path, and the replaced signal only cancels. A paused input-ready callback now re-seeds
  the generation, and `resume()`'s queued reconfigure either finds the input or returns at
  `glInputPending` while the rebind's callback converges it. I found no double open: both paths take
  the same transaction, and the second sees a controller or is superseded.
- **AGG5-5 order** (Ready cleared, then DNG owners cancelled). A BURST/AEB settle from the cancel now
  stops at `acceptedSessionIsCurrent`. The `onPhoto` early `releaseDngAdmission()` is exactly-once
  through `Lease.released`.
- **AGG5-28 buffer-lost/aborted.** All callbacks run on one handler, `failStill` is gated by
  `captureTokenIsCurrent`, and close-time failure plus a late abort is inert.
- **AGG5-2 admission republish.** The `wasAdmitting` check in `markStillProducersTerminal` can miss a
  concurrent marker add. The settle path's `scheduleDeletedFamilyRetirement` task always ends in
  `publishProcessStillAdmission()`, which backstops it.
- **MRG5-1 RAW-loss latch** writes inside `_state.update`. It is idempotent across CAS retries, because
  the same facts give the same verdict.
- **MRG5-6.** `setResolvedOptics` returns the generation it began. `reopenForSession` from the trailing
  `setVideoStabMode` reuses that generation, so a failure of that reopen still keys the VM restore.
