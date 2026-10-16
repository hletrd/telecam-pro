# RPL cycle 6 merge review (`f1654e12..009305fe`, 43 commits)

Reviewer: c6-merge-reviewer (read-only verification pass). Inputs: `CLAUDE.md`,
`docs/plans/2026-10-02-rpl-cycle6.md`, `.context/reviews/_aggregate.md`, and `git show` of every commit
in the range. Gradle/verify_host were not re-run. The orchestrator's gate result is taken as given:
exit 0, 2664 tests / 0 failures, lint 0 errors / 1 deferred warning, release lint RAN.

## Verdict: APPROVE-WITH-FIXES

Every plan item has a root-cause change, and most of them have a test that fails if the change is
reverted. One new lifecycle hole came in with A1.5 (MRG6-1, Medium). It is a black viewfinder in the
same keyguard/Home-during-cold-start window that CLAUDE.md treats as a hard-won fact. It should be
closed before the cycle is called done. The other findings are Low or Info.

Commit hygiene: every header matches `<type>(<scope>): <gitmoji> <description>`. All headers are
shorter than 72 characters. Every commit is GPG-signed (`G`), and no Co-Authored-By or other AI
attribution appears. EN+KO: the cycle adds one new string, `a11y_unavailable`, and it is present in
both `values/` and `values-ko/`. PMA110: no measured route, HAL workaround, or capture default
changed. The HEIF stand-in stays HEIF on PMA110 because HEVC is present, and the passthrough strip is
dormant there. The AF freeze changes only *when* two volatile values are read (press time instead of
post time).

## Findings

| ID | Sev / Conf | Summary |
|---|---|---|
| MRG6-1 | Medium / Medium | A1.5 marker can name an input-ready open that a pause abandoned; resume then skips its own open → black viewfinder |
| MRG6-2 | Low / Medium | A2.5 `settleRouteFold` cancels the throttled packet wholesale and drops non-zoom edits (wire/UI split) |
| MRG6-3 | Low / Medium | A2.3 × A2.4: stacked pre-inventory recalls + inventory + rollback restore the OLDER bank's codec/curve/formats, not the pre-recall value |
| MRG6-4 | Low / High | A1.13 marked done, but the MRG5-6 race test (TE6-8) was not added |
| MRG6-5 | Low / High | A1.8 is not revert-sensitive at the Engine call site (only `StillSaveLanes` is tested) |
| MRG6-6 | Low / High | Stale comments left by cross-lane changes (DNG-only "capture-time refusal", "immediate captures read live controls", FIELD_CHECKS A9 "replays") |
| MRG6-7 | Low / Medium | FIELD_CHECKS has no device check for the A1.3/A1.4/A1.5 open-path changes (AGG6-5 recurrence guard) |
| MRG6-8 | Info / High | Slop / inaccurate comments in new code |
| MRG6-9 | Info / Medium | FrameGap "carries into the next generation's terminal row" is not true across a GlPipeline replacement |
| MRG6-10 | Info / Medium | check_docs structural gate accepts a gate call inside an arbitrary lambda argument |
| MRG6-11 | Info / High | Probe commit flip-flop (`14d8c9bc` then `009305fe`) |

### MRG6-1 — An abandoned input-ready open suppresses resume's open (Medium / Medium)

**Cite:** `camera/CameraEngine.kt:2102` (paused check in `completeGlInputReady`), `:2126`
(`inputReadyOpenGeneration.set(G)`), `:4497` (the queued startup task refuses on `paused` and returns
without touching the marker), `:7840` (`pause()` clears the marker), `:8003` (resume's task returns
when the marker equals the desired generation).

**Problem.** `completeGlInputReady` runs on the GL callback. It reads `paused == false`, then calls
`gyro.start()` and `resolveInitialCameraRouteAvailability()` (CameraManager Binder enumeration), then
`currentOpticsReconfiguration()`, and only after all of that publishes the marker. A `pause()` on main
inside that window clears the marker *before* the GL thread sets it. Because `pause`/`resume` do not
bump `opticsIntentGeneration`, the marker is left naming the current generation. Then:

1. The input-ready startup task, queued first on `setupExecutor`, runs while paused. It hits
   `if (paused || recorder != null) return@execute` and opens nothing.
2. `resume()` queues its task. That task finds `inputReadyOpenGeneration == desired.generation` and
   returns at `:8003`.

Nothing opens the camera. The TextureView surface survives backgrounding, so no availability callback
replays the start. The result is a black viewfinder until some unrelated optics door or surface event
fires. This is the same failure class AGG6-20 just closed, in the window CLAUDE.md names explicitly
("Launching behind the keyguard delivers `onStop` mid-session-config"). Before A1.5 the same
interleaving produced at worst a redundant converge. The skip is new.

The A1.5 test injects the marker directly (`CameraEngineInterruptedColdStartTest`, "resume does not
re-converge…"), so it never exercises the abandoned case.

**Fix.** The marker should mean "an input-ready open is still pending", not "one was queued once".
In `reconfigureCamera`'s queued task, wherever a `startup` attempt returns without opening (the
`paused || recorder != null` refusal, `!ownsOpticsTransaction`, and the
`nativeAcquisitionMayProceed`/`glInputTransactionMayProceed` refusals), call
`inputReadyOpenGeneration.compareAndSet(transaction.generation, NO_INPUT_READY_OPEN)`.
`setupExecutor` is serial, and the startup task was queued before resume's task, so this clear is
ordered ahead of resume's check. An alternative is to publish the marker under `synchronized(this)`
together with a re-read of `paused`, and have `pause()` clear it under the same monitor.

**Test.** Set `paused = true`, set the marker to the current generation, then run the input-ready
startup task. It must refuse. Then call `resume()`, drain, and assert that a reconfigure ran: the
session generation advanced or `cameraReady` was invalidated by resume's task.

### MRG6-2 — Route-fold settle drops non-zoom control edits (Low / Medium)

**Cite:** `ui/CameraViewModel.kt:329` (`applyControlsRunnable` calls `settleRouteFold()` first),
`:2707` (`settleRouteFold` → `cancelPendingControls()`), and `updateControls` (does not settle first).

**Problem.** `cancelPendingControls()` discards the whole throttled packet, not only its zoom. Two
cases lose a real edit:

- An ISO, shutter, WB, or focus edit staged within 40 ms before a FRONT/BACK/external fold.
- An edit made after the setup-thread fold wrote `routeFoldEpoch` but before main settled it.
  `updateControls` builds that packet from the NEW state, and the runnable then cancels it anyway.

In both cases `_state` shows the new value while the Engine keeps the old one until the next edit.
This is the display/wire split the cycle closes elsewhere (AGG6-2). The plan asked to cancel the
packet because of its OLD-scale zoom, not because of its other fields.

**Fix.** Rebase instead of cancel. In `settleRouteFold`, replace the cancel with
`pendingControls = pendingControls?.copy(zoomRatio = _state.value.controls.zoomRatio)` and keep the
schedule. Also call `settleRouteFold()` at the top of `updateControls`.

**Test.** Stage an ISO edit through the VM, bump the epoch through the route-inventory callback, run
the runnable, and assert that the fake Engine received the new ISO with the new-scale zoom.

### MRG6-3 — Stacked pre-inventory recalls do not roll back to the pre-recall request (Low / Medium)

**Cite:** `ui/CameraViewModel.kt:3214` (`recallRollbackRestores.replaceAll`), `:3251`
(`restoreInventoryAppliedRecall`).

**Problem.** Take recall R1 (gen 5, armed A1), then recall R2 (gen 6, armed A2, prior = A1), both
before the codec walk lands. When the inventory applies, `consumedPending` is A2. Only R2 gets an
`inventoryApplied` value for each field; R1's is null, because `consumedPending.codec != A1.codec`.
The superseding rollback reaches both records and walks newest-first:

- R2 restores its `priorRequest`, which is **A1**, the first bank's value.
- R1 then has nothing to undo.

The final codec/curve/formats are bank 1's, not the operator's. The pending-mirror branch handles
this same chain correctly (each step compares against the previous record's armed value). Only the
inventory-applied branch breaks the chain. AGG6-11 and AGG6-16 were each fixed in isolation, and this
is their combination.

**Fix.** When recording `inventoryApplied`, give an older record whose armed field was superseded the
value that the newer record's restore will write. That value is the newer record's resolved
`priorRequest` field, so the walk continues. Alternatively, have `restoreInventoryAppliedRecall`
compare against the record's resolved armed request when `applied` for that field is null.

**Test.** Two pre-inventory recalls with different codecs, then the inventory, then rollback of the
second door. Assert that the codec/transfer/formats equal the pre-recall values.

### MRG6-4 — A1.13's MRG5-6 sub-item is not implemented (Low / High)

**Cite:** plan A1.13 lists "MRG5-6 generation race". `OpticsRouteInputTransactionRobolectricTest:405`
is unchanged in the range, and no test in the range mentions MRG5-6.

TE6-8 still stands. The existing assertion `began == opticsIntentGeneration.get()` is equally true of
the reverted read-back code.

**Fix.** Add a test where a second door bumps the generation between `setResolvedOptics`' begin and
its return; for example, one queued on the setup lane, or a hook. Assert that the returned value is
the begun generation, and that the VM's rollback is keyed on it. Otherwise move the sub-item to
"later cycle" in the plan instead of `[x]`.

### MRG6-5 — The A1.8 fix has no Engine-level test (Low / High)

**Cite:** `camera/CameraEngine.kt:6045` (`lanes.enterPhoto()`), `:6179` (the new onPhoto catch),
`:6202` (onError guard); `StillSaveLanesTest`.

`StillSaveLanesTest` drives the new class only. Deleting `lanes.enterPhoto()`, the onPhoto `catch`,
or the `claimErrorTerminal()` guard keeps every test green. This is the TE6-1/AGG6-30 pattern the
cycle set out to remove. It also means the two mechanisms are mutually redundant without anything
saying so: onPhoto now catches `Throwable` itself, so the controller's `catch → onError`
(`CameraController.kt:2372`) is reached only if the Engine's catch or finally throws.

**Fix.** Add an Engine test that builds a processed `photoCallback` through reflection, blocks
`ioExecutor`, calls `onPhoto` and then `onError`, and asserts that `onDone` and the producer-terminal
mark do not run until the save task finishes. Also say in the `StillSaveLanes` KDoc that the onError
guard is defence in depth behind the onPhoto catch.

### MRG6-6 — Stale comments left by cross-lane changes (Low / High)

- `ui/CameraViewModel.kt:1062-1065`: "Word for word the engine's capture-time refusal … those fire at
  the shutter, so one user sees all three". A1.7 (`96e36067`) removed the per-press
  `PROCESSED_STILL_UNAVAILABLE_DNG_ONLY`, so only the Ready fold and the caption remain.
- `camera/CameraController.kt:2063-2065`: "ordinary immediate captures keep reading the current
  controller value". `frozen` is now a required parameter, so every capture reads the press-time
  packet. A1.13 also froze the AF inputs.
- `docs/FIELD_CHECKS.md` A9 item 3: "Cycle 6 … replays a refused resume". The implementation does not
  replay anything. It decides `paused` and retires `starting` under one monitor, so a resume is never
  refused in the first place. The plan used the "replay" wording, and the doc copied the plan rather
  than the code.

### MRG6-7 — Open-path changes have no field check (Low / Medium)

A1.3 (route resolve before the paused enumeration), A1.4 (trace owner adoption), and A1.5 (resume
skips a reopen) change which code path opens the camera at cold start and resume. Under
CLAUDE.md ("verify on device … lifecycle races crash the camera") and the AGG6-5 recurrence guard,
they belong in FIELD_CHECKS. Only AGG6-20 is filed (A9 item 3).

**Fix.** Extend A9 with a Home-and-return inside the GL input window. Check three things:

- logcat shows exactly one `onOpened` per foreground and no same-id dual open;
- the preview returns;
- the cold-start trace line appears once with a resume origin.

This check would also catch MRG6-1 on device.

### MRG6-8 — Slop and inaccurate comments in new code (Info / High)

- `CameraEngine.kt` photoCallback: `val finishSequence: () -> Unit = lanes::finishSequence` and two
  siblings exist only to avoid editing call sites. Calling `lanes.finishX()` directly reads more
  plainly.
- `StartupTrace.kt` `adoptForInputReady() = current()` is a rename of `current()` with a nine-line
  KDoc. Calling `current()` at the call site, with a one-line comment there, is enough.
- `CameraEngine.kt` `cleanLateAllocation` comment: "A refused submit still retains the exact identity
  for bounded retry". A refused `RejectedOutputCleanupReservation.submit` means the reservation is
  already terminal: it was consumed by onError's cleanup or cancelled. This call retains nothing, and
  launch recovery owns the REGISTERED row.
- Not this cycle (`git blame`: 2026-07): five consecutive blank lines before `// ---- Video ----` in
  `CameraEngine.kt`. Listed only so it is not attributed to this range.

### MRG6-9 — FrameGap carry-forward does not survive a GlPipeline replacement (Info / Medium)

`DiagnosticTelemetry.kt` `FrameGapAccumulator` KDoc says "a refused terminal summary carries into the
next generation's terminal row". The accumulator is a field of `GlPipeline`. A bounded native wedge
retires that object, which drops the carried counts. Either reword the KDoc to say "next stop of this
pipeline", or move the accumulator to an owner that outlives the GlPipeline.

### MRG6-10 — Structural log-gate scan accepts a gate inside any lambda (Info / Medium)

`tools/check_docs.py` (`a584a588`) accepts
`if (rows.admit { recurringDiagnosticAllowed(…) }) android.util.Log.i(…)` (VendorTagInspector). That
is correct here, because `CapabilityDumpRows.admit` calls the gate inside its share. But the scanner
cannot tell whether the lambda runs at all, so `if (anyHelper { recurringDiagnosticAllowed() })`
would pass. Either allowlist the known admission helpers (`CapabilityDumpRows.admit`, `.admitTruncationNote`) by name,
or document the limit next to `GATE_CALL_NAMES`.

### MRG6-11 — Probe commit flip-flop (Info / High)

`14d8c9bc` made `EncoderProfileLevelProbeTest` fail on a null inventory, and `009305fe` reverted that
choice to a logged verdict. The final state is right: the probe never fails CI and does not claim a
false "no HEVC". The result is two commits for one concern, both typed `fix(test)`. No action beyond
noting it.

## Plan-item verification

Columns: root cause fixed / revert-sensitive test / regression check / notes.

**Lane A1**
- A1.1 AGG6-3 ✅ / ✅ (`RetainedStillDeletionOwnerTest` 40-capture and never-registered cases;
  `CameraEngineStillAdmissionRepublishTest` for PR6-1) / ✅. Registration and terminal are paired in
  `shotSpec`'s catch and in `settleRegisteredStillShot`, so a leaked registration fails closed exactly
  as before. The only widened case is a never-registered id, which correctly admits.
- A1.2 AGG6-20 ✅ / ✅ (monitor-held interleaving test) / ✅. Doc wording: MRG6-6.
- A1.3 AGG6-17 ✅ / ✅ (external-only fake) / ✅.
- A1.4 AGG6-18 ✅ / ✅ (pure owner test). No Engine-level wiring test: removing `adoptForInputReady()`
  at `:2127` is caught only indirectly.
- A1.5 AGG6-19 ✅ for the targeted interleaving / ⚠ the test injects the marker / ❌ new hole,
  MRG6-1.
- A1.6 AGG6-13 ✅ / ✅ (`CameraEngineRawLossReadyTest` first-frame Ready) / ✅. Identity-keyed, and
  the VM's shape latch prevents repeats.
- A1.7 AGG6-27 ✅ / ✅ (`CameraEngineDriveHeadTest`) / ✅. Stale VM comment: MRG6-6.
- A1.8 AGG6-22 ✅ / ❌ Engine wiring untested (MRG6-5) / ✅. Every DNG decision point sets
  `dngLaneDecided`, and an undecided lane after a throw takes bounded cleanup.
- A1.9 AGG6-23 ✅ / ✅ (`CameraEngineDngHeadRefusalTest`, handoff unit tests) / ✅. A deferred settle
  runs exactly once on ACCEPTED and never on a refusal. Timelapse and mid-chain callers keep
  "false → continue yourself".
- A1.10 AGG6-24 ✅ / ✅ (four ordering tests) / ✅. The onReady-throws path no longer double-dispatches
  cleanup (the reservation is single-use). Comment accuracy: MRG6-8.
- A1.11 AGG5-42/43/66 ✅ / ✅ (latched-stop absorption, standby recheck, audio-off status) / ✅.
  `FINISHING_PREVIOUS_CLIP` is already a RESPONSE, so A2.6's reasoning holds for the audio-off path.
- A1.12 AGG5-46 ✅ / ✅ pure (`ExifExposureBiasTest`) / ✅. HAL-AE still prefers the wire answer.
- A1.13 ✅ for AGG5-29, AGG5-28, AGG5-41 (`CameraControllerStillTerminalTest`) and AGG5-39
  (`CameraEngineRecordingPresentationTest`); ❌ MRG5-6 (MRG6-4). The AF inputs are `@Volatile`, so
  reading them on the caller thread is safe.
- A1.14 ✅, compile-enforced. All callers pass explicit values.

**Lane A2**
- A2.1 AGG6-1 ✅ / ✅ (`EncoderInventoryRetryRobolectricTest`) / ✅. Single-flight; retried on
  `onStart`. The Engine's `heifStandIn()` reads `EncoderCaps.isLoaded()`, which is consistent with the
  null contract.
- A2.2 AGG6-2 ✅ / ✅ / ✅. The push can trigger a stabilization-class reopen right after the
  rollback. That is the same cost as an operator pick, and correct.
- A2.3 AGG6-11 ✅ / ✅ / ⚠ MRG6-3 with A2.4.
- A2.4 AGG6-16 ✅ for one recall / ✅ / ⚠ MRG6-3.
- A2.5 AGG6-12 ✅ for zoom hygiene and Engine interaction end / ✅ / ⚠ MRG6-2.
- A2.6 AGG6-7 + AGG6-10 ✅ / ✅ (plate arbitration tests) / ✅. `CAMERA_RECONFIGURING` is out of
  `OPTICS_CONDITION_MESSAGES` and `CAMERA_REOPEN_CONDITION_MESSAGES`.
- A2.7 AGG6-9 ✅ / ✅ (VM Ready-truth test, rail model tests) / ✅. EN+KO `a11y_unavailable` present.
- A2.8 AGG6-15 ✅ / ✅ / ✅. Replays on every REC end path and is cleared on stop.
- A2.9 AGG6-25 ✅ / ✅ (PMA110 + tablet crop + no-inventory fallback) / ✅. Steady state is unchanged
  (`cameraReady → caps`).
- A2.10 AGG6-26 + AGG5-53 ✅ / ✅ / ✅. Cross-lane with A1.7: `dngOnlySubstitution` does not depend on
  the stand-in, and the Ready-fold notice is unaffected. PMA110 stays HEIF.
- A2.11 AGG6-29 ✅ / ✅ (`MomentaryHoldRobolectricTest`) / ✅. onStop uses `releaseAll`.
- A2.12 ✅ / ✅ (`clearProgressStatus` through `StatusPlate.clearProgress`) / ✅.
  `shownStatusExpiresAtMs` is now carried by `recordStatusPlate`. `recallMemorySlot` is internal.

**Lane B**
- B.1 AGG6-6 ✅ / ✅ (`RecorderCodecTeardownTest` drives the extracted owner the recorder calls) / ✅.
  A plain ISE at EOS now waits up to 3 s for the drain thread before it is judged. That is
  acceptable on the recorder executor, and a live thread still quarantines.
- B.2 AGG6-4 ✅ / ✅ (budgets pinned, slices separate) / ✅. MRG6-9 is an Info wording issue.
- B.3 AGG6-21 ✅ / ✅ / ✅. Camera facts come first, and the claim is returned when nothing was
  admitted.
- B.4 AGG6-14 ✅ / ✅ (`JpegExifSpliceTest` with XMP + COM + failed/refused payload) / ✅. A header
  that cannot be walked is refused rather than written verbatim. Dormant on PMA110.
- B.5 AGG6-35 ✅ / ✅ (byte identity) / ✅. Every downstream consumer honours `length`, and the
  passthrough trims defensively.

**Lane C**
- C.1 ✅, with the MRG6-6 and MRG6-7 gaps. C.2 ✅ (re-homed KDocs in `95b4d578` restore the three
  detached by the cherry-picks). C.3 ✅: isolated git, blob-id proof, in-process Kotlin compiler,
  logging-level allowlist, `unverified_inputs` evidence. The repo has no non-regular tracked entries,
  so the new mode check cannot refuse a clean tree. C.4 ✅. C.5 ✅ (MRG6-10 Info). C.6: release lint
  RAN per the orchestrator.

## Cross-lane interactions checked

- A2.10 `heifStandIn` against A1.7 (DNG-only notice): no conflict. The Engine and the VM resolve the
  stand-in from the same inventory truth, and the notice comes only from the Ready fold. One stale
  comment remains (MRG6-6).
- A1.6 RAW-loss on the `handlePreviewReady` Ready against A2.7 `reopenInProgress`: independent. The
  Ready sets `cameraReadyEstablished`, and RAW-loss facts ride the same publication. Both are gated by
  `cameraReadyPublicationGate`.
- A2.6 (`CAMERA_RECONFIGURING` as an EVENT) against A2.7: `reopenInProgress` no longer reads that
  message, so making it an event cannot leave a stuck caption.
- A1.11 `FINISHING_PREVIOUS_CLIP` against A2.6's response class: it is already a response.
- A2.1 nullable inventory against A2.10/Engine `heifStandIn()`: consistent. A failed walk keeps HEIF
  as the stand-in, as before.
- The KDocs detached by the cherry-picks (`recordingClaimRefusalStatus`, `afOverrideForRequest`,
  `settleRouteFold`) were re-homed in `95b4d578`. No other dangling KDoc was found in the changed
  hunks.
