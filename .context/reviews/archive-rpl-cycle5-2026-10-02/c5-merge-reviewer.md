# RPL cycle 5: merge review (c5-merge-reviewer)

Scope: `main..worktree-agent-a8d4d469e7f2c2e63` (lane A1 + A2, 27 commits up to `c8f838d9`, plus
`ea8273d3` / `5cfd6d34`, which landed while this review ran), lane C on main (`3ced1ffe..main`), and
lane B (`72e9c75f..825fd87b`). `main` is an ancestor of the branch, so the branch tip is the whole
combined result. This was a read-only review. I ran no Gradle, the host gate or any tests. Every
finding comes from reading source and diffs.

## Checked and found sound

- **A1.1/A1.2 cold-start/resume** (`CameraEngine.completeGlInputReady`, resume rebind). The GL re-seed
  and the inventory enqueue now run before the `paused` gate. Gyro, route resolve and reconfigure
  stay behind it. `resumePreviewRebindWanted` is false on the ordinary foreground return, because
  `pause()` never clears `previewReady` and the input Surface persists. The PMA110 resume path is
  therefore unchanged. A GL-input-less bare door now uses the same disposition table as
  selection/caps preflight (`retryBareDoor`).
- **A1.4 DNG invalidation order.** `cancelDngPreCaptureAllocations()` now runs after the monitor block
  that bumps `cameraSessionGeneration` and clears Ready, and before `onCameraReadyChange`. A BURST/AEB
  settle continuation therefore sees `acceptedSessionIsCurrent == false`.
  `CameraEngineDngInvalidationTest` drives the real `invalidateCameraReady` and would fail on the
  old order. The early `releaseDngAdmission()` before the DNG tail handoff is idempotent: the lease
  `release()` returns a boolean, and the `finally` backstop is a no-op.
- **A1.5 per-capture deletion set.** `RetainedStillDeletionOwner.nonDurableLiveDeletions` replaces the
  Engine-lifetime Boolean. The producer-terminal edge republishes admission through
  `markStillProducersTerminal`, and the durability `finally` already republishes. One edge case is
  listed below as MRG5-5.
- **A1.6 buffer-loss/abort.** `stillBufferLossFailsShot(target, listOf(jpeg, raw))` compares the
  request's own reader Surfaces (`CameraController.kt:2031-2032`), not Images. `failStill` is
  done-gated and token-gated, so a late `onCaptureCompleted` cannot double-settle.
- **A2.1 stab/fps request split.** The Engine keeps the stab request. The wire mode and the vendor
  mirror both come from `c.videoStabControlMode(videoStabMode)` (`CameraEngine.kt:2156/2639/4581`),
  so ENHANCED on a route without PREVIEW_STABILIZATION resolves to ON, the same HAL value the old
  narrowed push produced. PMA110 wire values are unchanged.
- **A2.2 status plate.** I traced every `publish`/`expire`/`clearProgress` branch. The invariant
  "a shown PROGRESS never carries a `deferredEvent`" holds: every `show(progress = …)` from a PROGRESS
  incoming passes `event = null`, and only a response creates a `deferredEvent`. `postAtTime` uses
  the same `uptimeMillis` clock as `shownExpiresAtMs`. The re-armed remaining-time timer goes through
  `armStatusTimer(next, timerOwner)` under the status monitor. The rollback family `*_UNCHANGED` ranks
  ERROR, so it still displaces an ERROR-ranked recovery condition and is not dropped.
- **B.4 codec-cleanup classifier.** A skip happens only on `IllegalStateException` (which includes
  CodecException) from a non-release call while that codec's error latch is set. `release()`
  failures, hangs and live drains still quarantine. The new `Log.w` is reserved-class and runs once
  per stop.
- **B.1/B.2 recovery.** A COMPLETE row with SIZE ≤ 0 is now judged on the fstat length (0 → kept,
  INVALID, not re-armed; negative → transient). Nothing destructive was added.
- **C.1 double-charge rule vs new rows.** The new rows are `Log.e("Photo dispatch failed")` in the
  BURST/AEB head path and B's `Log.w` candidate-refusal/codec-skip rows. All are e/w level, which
  `check_docs` counts as `reserved_fault`. The double-charge check only fires on aliased `d`/`i`
  rows next to a recurring gate, and A added none. The VM `android.util.Log.i` sites (605/710/735)
  are C's single-charge conversions and survived the rebase intact.
- **EN/KO.** The only resource change is the removal of `output_switching_single_lens` from both
  locales. Its only remaining reference is a review note. The translatable key sets of `values` and
  `values-ko` are identical.
- **Tests that would fail on revert.** These all drive real Engine/VM code, not only pure seams:
  `CameraEngineDriveHeadTest`, `CameraEngineDngInvalidationTest`,
  `CameraEngineInterruptedColdStartTest`, the four new `CameraViewModelRobolectricTest` cases
  (DNG-only latch, response cover/return, `cameraCondition`, and the stab/fps request), and
  `MainRelativeZoomMultiplierRobolectricTest`.

## Findings

### MRG5-1: the RAW-loss Ready announcement latches even when the plate drops it (A1 × A2)
- **Severity:** Low-Medium. **Confidence:** High.
- **Where:** `CameraEngine.kt` around line 885 (`rawLossAnnouncementAtReady(...).also { rawLossAnnouncedShape = it.announcedShape }`)
  and line 916 (`onStatus?.invoke(RAW_UNAVAILABLE)`). The interacting change is `CameraStatus.kt`
  (RAW_UNAVAILABLE is now WARNING, ambient, and not in `RESPONSE_MESSAGES`).
- **What:** A1 latches the per-shape edge at the moment it emits. A2's arbiter drops an ambient
  WARNING under an unexpired ERROR or RETAINED event. That case is likely on this path, because a
  drop-RAW rung often follows a failed reopen whose ERROR is still on screen. The notice is then lost
  for that session shape and never comes back. This is the defect class TE5-10 fixed for the DNG-only
  notice in the same cycle: `readyDngOnlyAnnounced` now latches only when `publishStatus` returns
  true. The Output caption ("RAW unavailable") still carries the truth, so nothing is fully silent.
- **Fix:** Move the announcement to the VM's Ready fold, next to the DNG-only notice, and latch it on
  `publishStatus(...) == true`. Alternatively, have the Engine reset `rawLossAnnouncedShape` when the
  VM reports the status was dropped. Either way, add the TE5-10-style test: dropped under a retained
  take, announced by the next Ready of the same shape.

### MRG5-2: a stale comment says every shot raises RAW_UNAVAILABLE
- **Severity:** Low (comment drift). **Confidence:** High.
- **Where:** `ui/controls/PhotoFormatChips.kt:111-112`: "every shot raises RAW_UNAVAILABLE meanwhile".
- **What:** A2's caption rationale quotes behaviour that A1's AGG5-10 removed
  (`CameraEngine.kt:5214`: "No per-press RAW_UNAVAILABLE"). The two lanes rebased cleanly but now
  contradict each other in prose. Per CLAUDE.md, comments are load-bearing in this repo.
- **Fix:** Reword it, for example: "…nothing is switching; a drop-RAW rung is announced once per
  session shape at Ready (AGG5-10). Say what is true."

### MRG5-3: FIELD_CHECKS A9 under-describes what shipped
- **Severity:** Low (doc drift). **Confidence:** Medium-High.
- **Where:** `docs/FIELD_CHECKS.md:252-256` (lane C) vs `CameraEngine.kt` `resumePreviewRebindWanted`
  (`!inputSurfacePresent || !previewReady`) and the comment at line 7844 (AGG5-1 **and AGG5-26**).
- **What:** A9 says resume "re-binds the live preview surface on resume when the GL owner has no
  input surface yet". The shipped predicate also re-binds after a preview-recovery rebind that a pause
  dropped (`previewReady == false`). That is a second, independent trigger, and the device run does
  not exercise it. A9 also omits that a terminally exhausted preview (`previewReady` stays false) now
  re-binds on every resume. That is arguably desirable, but it is new device behaviour.
- **Fix:** Name both triggers in A9 and add a step or note for the preview-recovery interleaving. If
  it cannot be provoked on PMA110, mark that half host-only, as F7/F8 do.

### MRG5-4: the standalone display divisor change moves PMA110 readouts, with no recorded justification
- **Severity:** Low-Medium (PMA110 byte-identity). **Confidence:** Medium.
- **Where:** `ui/ZoomMath.kt` `zoomDisplayMultiplier` `else` arm (`measuredMainEquivMm` from
  `lensInventory.presetEquivMm[MAIN]`) and `mainRelativeZoomMultiplier`.
- **What:** AGG5-49 (logical route 1:1) is justified and intentional on PMA110: "3.1×" becomes "3.0×".
  The second half, which divides standalone routes by the measured main instead of the nominal
  23 mm, is justified only by a tablet finding (CT5-6). It shifts every PMA110 standalone readout:
  Video on all lenses, and DNG Photo. That covers the pill, the Fn ZOOM value, the Quick Zoom ruler
  and the Shoot-tab slider, by 23/23.4 ≈ −1.7% if the main standalone lens measures like the logical
  one (23.4 mm). At the 10× lens the result depends on that lens's measured equivalent. Around
  230 mm it now reads "9.8×", while the rail band and `unifiedZoomOf` (nominal preset 10.0) call the
  same framing 10×. Before this change it read "10.0×". No FIELD_CHECKS entry or plan line records
  this PMA110 delta. The tests pin 69.4/23.4 but not the 10× position.
- **Fix:** Pick one:
  - Record the delta and add a one-line device read of the Video 10× pill to FIELD_CHECKS.
  - Apply the focal-caption honesty rule here as well: keep the nominal divisor while the measured
    main is within 10% of 23 mm. PMA110 then stays byte-identical, and a 26 mm tablet main still
    reads 1.0×.

### MRG5-5: a failed delete marker can still close admission for the process
- **Severity:** Low. **Confidence:** Medium.
- **Where:** `RetainedStillDeletionOwner.kt` `completeDeletionDurability` (`else if (captureId !in producerTerminalCaptures)`)
  and `markCaptureProducersTerminal` (adds to `producerTerminalCaptures` only when the id is in
  `familiesByCapture`).
- **What:** Take a tombstoned id whose family entry was already evicted from the bounded registry.
  Its producer-terminal edge arrives **before** a failed durability completion. That edge removes the
  id from `nonDurableLiveDeletions` but never records it as producer-terminal. The later
  `durable = false` completion then adds the id, and no further edge for it will ever come. Still
  admission stays closed until process death, which is the pre-AGG5-2 behaviour in a narrower window.
- **Fix:** Record producer-terminality for every id in a small bounded set, independent of
  `familiesByCapture`, or consult `tombstones`. Add the "terminal edge first, then failed marker"
  ordering to `RetainedStillDeletionOwnerTest`, which covers the other two orders today.

### MRG5-6: the recall rollback restore is keyed by a re-read generation, not by the transaction it began
- **Severity:** Low. **Confidence:** Medium.
- **Where:** `CameraViewModel.kt` around line 1651:
  `RecallRollbackRestore(generation = engine.currentOpticsGeneration(), …)`, read after
  `engine.setResolvedOptics(...)` returns (around line 1596).
- **What:** The key is whatever `opticsIntentGeneration` holds a few statements later. Any intent
  bump in between by a non-main door makes the rollback's `generation` mismatch. AGG5-23/24
  (pending-inventory request and AE-lock prior) would then be silently skipped. Today the doors are
  main-confined, so this is latent. The KDoc on `currentOpticsGeneration` already admits it is a
  "read right after".
- **Fix:** Have `setResolvedOptics` return the generation it began (or a small result type) and key
  on that.

### MRG5-7: RAW-loss wiring has no revert-sensitive test
- **Severity:** Low. **Confidence:** High.
- **Where:** `RawLossAnnouncementTest` covers only the pure `rawLossAnnouncementAtReady` table and
  the WARNING severity.
- **What:** Two reverts would leave the suite green: deleting the `commitOpticsReady` call and
  `onStatus` emission, and re-adding the removed per-press `RAW_UNAVAILABLE` branch in
  `capturePhoto`. This is the TE5-13 pattern (a pure table that is tested while its call site is
  not), which this cycle fixed for `recallRequiresReconfigure`.
- **Fix:** Add one Robolectric engine test in the style of `CameraEngineDriveHeadTest`. A press with
  `PhotoFormats(dngRaw = true)` on an accepted RAW-less FRONT session emits no `RAW_UNAVAILABLE`. A
  Ready commit of a RAW-capable RAW-less shape emits it exactly once, and a second Ready of the same
  shape emits nothing. Folding this into MRG5-1's fix would cover both.

### MRG5-8: async recall rollback leaves the bank's stab/fps in the persisted request (pre-existing, now more durable)
- **Severity:** Info. **Confidence:** Medium.
- **Where:** `CameraViewModel.kt` recall path (`requestedVideoStabMode = e.videoStabMode;
  requestedVideoFrameRate = safeFrameRate`) and the rollback fold (around lines 1060-1140), which
  restores neither field.
- **What:** Before this cycle, a rolled-back recall already left the bank's `videoStabMode` and
  `videoFrameRate` in state, so this is not new. With AGG5-4 those values now feed `currentExtras` as
  the operator's REQUEST, which caps narrowing never corrects. That is the AGG4-11 "bank the
  operator was told did not load" shape through one more field pair. Not a merge blocker.
- **Fix (optional):** Add the two prior request values to `RecallRollbackRestore` and restore them
  under the same "still holds the recall's value" rule used for the pending-inventory trio.

## Notes for the verifier

- The branch moved during review (`ea8273d3`, `5cfd6d34`, coverage re-cite). Run the host gate on the
  final tip, not on `c8f838d9`.
- The AGG5-10 Ready-time `RAW_UNAVAILABLE` partly reintroduces a status the owner removed on
  2026-07-31 as "too noisy" (`CameraViewModel.kt:1009`). The trigger is narrower (a drop-RAW rung on
  a RAW-capable route, once per shape) and it replaces a per-press ERROR, so the net noise is lower.
  It is still worth one line in the cycle notes for the owner.

## Verdict

**APPROVE-WITH-FIXES.** No correctness regression or broken invariant was found in the cold-start,
DNG-ordering, deletion, capture-terminal, video-request, recovery, recorder-cleanup or
log-charging changes. The lanes interact correctly in code; MRG5-1 and MRG5-2 are the two real
cross-lane semantic seams. Fix before merge or record as accepted: MRG5-1 (with MRG5-7's test),
MRG5-2, and MRG5-4 (fix it, or record the PMA110 delta). MRG5-3, MRG5-5 and MRG5-6 can follow in the
next cycle.
