# Architect review — RPL cycle 6

- Agent: architect (AR6). HEAD `30970c9e`. Read-only review. No Gradle run.
- Inputs read: `CLAUDE.md`, `docs/ARCHITECTURE.md` (persistence, threading and status sections),
  `docs/FIELD_CHECKS.md` (A6–A9), the cycle-5 aggregate
  (`archive-rpl-cycle5-2026-10-02/_aggregate.md`), the cycle-5 plan and its later-cycle list, and the
  cycle-5 architect review.
- Scope inventory: the cycle-5 diff `ea7d4374..30970c9e` over `app/src/main` (32 files). I read the
  full diffs of `ui/CameraViewModel.kt`, `camera/CameraEngine.kt`, `camera/CameraController.kt`,
  `ui/ZoomMath.kt`, `camera/RecordingStorageDispatcher.kt`, `MainActivity.kt`,
  `AudioDenialReason.kt` and `ui/MomentaryHold.kt`. I also read in full
  `camera/RetainedStillDeletionOwner.kt`, `camera/CameraStatus.kt` (`StatusPlate` and the message
  classification sets), `camera/DeviceProfile.kt`, `camera/OpticsConstraints.kt`
  (`rawSelectable`), the stabilization helpers in `camera/CaptureCapabilities.kt`, the Engine
  optics-transaction/rollback machinery (`beginOpticsTransaction`, `OpticsSnapshot`,
  `executeOpticsRollbackEffects`), the route-inventory publication, and the `tools/check_docs.py`
  log-door change (`715ccb0a`).
- Angle: duplicated sources of truth, ownership/generation gaps, layering, model-string seams, and
  god-object hazards that produce concrete bugs.
- Known items not re-raised: AGG5-38/40/44/47/48 and the rest of the later-cycle list, plus
  owner-decided items.

Summary: 0 Critical, 0 High, 1 Medium, 1 Low-Medium, 1 Low, 3 Info.

The model-string sweep is clean. `Build.MODEL` reaches behaviour only through `DeviceProfile.resolve`
(Engine, plus the Controller's own copy, which the Engine overrides to GENERIC on EXTERNAL) and
`detectPhone`. The EXIF make/model use is a label, not a branch. The cycle-5 fixes I traced hold up:
the A1.1 split of `completeGlInputReady`, the A1.4 cancel order, the A1.5/M.5 per-capture latch, the
AGG5-39 stale presentation, and the AGG5-22 move of provenance into the VM.

The new defects all come from one cause. AGG5-4 and MRG5-8 added two more VM-private "operator
request" mirrors: stabilization and frame rate. The Engine's optics rollback does not know about
them. So the VM, the Engine and the UI now hold three copies of one fact, and they diverge on the
rollback path.

## Findings

| ID | Severity | Confidence | Status | Summary | Primary cite |
|---|---|---|---|---|---|
| AR6-1 | Medium | High | Confirmed (code path); visible symptom needs device validation | A failed MR recall rolls back the VM's stab/fps REQUEST only. The Engine keeps the recalled stab request on the wire, and the rollback's own caps reconcile then shows the prior mode. The OSD reads "Active" while clips record with stabilization OFF, and re-picking Active is a no-op | `ui/CameraViewModel.kt:1170-1177, 3431-3436, 3515`; `camera/CameraEngine.kt:658-687, 1298, 1306, 2182-2189` |
| AR6-2 | Low-Medium | High | Confirmed (code path) | The recall rollback restore is keyed on the recall's own begin generation. A newer door that supersedes the recall and then fails restores the PRE-recall Engine baseline but drops the VM restore record. The AGG5-23/24 and MRG5-8 symptoms come back through supersession | `ui/CameraViewModel.kt:1153-1154, 1699-1708`; `camera/CameraEngine.kt:799-805` |
| AR6-3 | Low | Medium | Likely; needs manual validation (topology change) | The A2.9 route-inventory fold clears glide state only, and does so asynchronously. It never cancels the 40 ms throttled `pendingControls` packet, so a trailing packet holding the rear zoom lands on the lens-local route. The posted invalidation can also clear a newer door's glide state | `ui/CameraViewModel.kt:876-892, 328-332`; `ui/CameraViewModel.kt:4849-4868` |
| AR6-4 | Info | High | Confirmed | After AGG5-9, about 46 debug rows call raw `android.util.Log` behind a caller-side gate. The quota check accepts any gate token within the previous 1,200 characters, which is a lexical anchor. That contradicts CLAUDE.md's "only a real bounded facade/guard" | `tools/check_docs.py` `debug_log_classification_inventory`; CLAUDE.md log-quota bullet |
| AR6-5 | Info | High | Confirmed | Tested-but-unused seams: `StatusPlate.clearProgress()` is pinned by tests, but production clears through a hand-written copy in `clearProgressStatus`. `recallMemorySlot` and `lastAppliedRecall` are now test-only public API | `camera/CameraStatus.kt:486-493`; `ui/CameraViewModel.kt:2081-2108, 3978-3990` |
| AR6-6 | Info | High | Confirmed | God-object growth: Engine 9142→9365 (+223) and VM 4877→5202 (+325). The VM now holds five hand-synchronised request mirrors, each with its own rollback rule | `ui/CameraViewModel.kt:113-169` |

---

### AR6-1 (Medium): stab/fps recall rollback leaves the VM request, the Engine request/wire and the display as three different values

- Severity: Medium. Confidence: High. Status: confirmed from code. The OSD/clip mismatch needs a
  device check.
- Cites:
  - `ui/CameraViewModel.kt:1728` and `:1748`. The recall pushes `engine.setVideoStabMode(e.videoStabMode)`
    and `engine.setVideoFrameRate(safeFrameRate)`. These are Engine fields **outside** the optics
    transaction.
  - `camera/CameraEngine.kt:658-687`. `OpticsSnapshot` has no `videoStabMode` and no
    `videoFrameRate`, so the Engine's rollback never restores either. `camera/CameraEngine.kt:1802`
    holds the Engine's own stab REQUEST. Since AGG5-4 the comment at `ui/CameraViewModel.kt:3431-3434`
    says "the Engine keeps the request too".
  - `ui/CameraViewModel.kt:1170-1177` (MRG5-8). The rollback puts only the VM mirrors
    `requestedVideoStabMode` / `requestedVideoFrameRate` back to the prior values. It neither pushes
    them to the Engine nor touches the displayed `videoStabMode` / `videoFrameRate`.
  - `camera/CameraEngine.kt:1297-1298, 1306`. `executeOpticsRollbackEffects` posts
    `onOpticsRollback`, then `onCapsReady`, then calls `applyStabilization()`. That last call
    re-pushes the Engine's still-recalled stab mode to the wire.
  - `ui/CameraViewModel.kt:3431-3436, 3466-3471`. `reconcileZoomToCaps` sets the DISPLAY to
    `normalize(requestedVideoStabMode)`, the prior value. AGG5-4 deliberately removed the Engine push.
  - `ui/CameraViewModel.kt:3515`. `onVideoStabMode(mode)` returns early when
    `mode == requestedVideoStabMode && normalized == current.videoStabMode`.
- Why it is a problem: the request/display split from AGG5-4 is correct only while the VM request
  and the Engine request are the same value. MRG5-8 added a rollback leg that edits one copy. It has
  no matching Engine write, and the Engine transaction has no field for it. Frame rate converges by
  accident: `reconcileFrameRate` pushes the prior rate through `applyVideoFrameRate`. Stabilization
  does not converge, because the AGG5-4 caps reconcile is display-only by design.
- Failure scenario (PMA110, TELE video, operator request Active/ENHANCED):
  1. Recall an MR bank whose stabilization is OFF. The optimistic apply writes display OFF, Engine
     OFF and VM request OFF.
  2. The recall's route fails asynchronously, for example `CAMERA_UNAVAILABLE_RECALL_UNCHANGED` when
     the target lens is busy. Then `onOpticsRollback` sets the VM request back to ENHANCED. The
     rollback's `onCapsReady` sets the display to ENHANCED ("Active"). The Engine still holds OFF,
     and `applyStabilization()` keeps CONTROL_VIDEO_STABILIZATION_MODE = OFF on the wire.
  3. The OSD/Fn says Active, but every 300 mm clip records without the OIS+EIS profile. Persisted
     settings say ENHANCED, so only a relaunch fixes it.
  4. The operator re-selects Active. Line 3515 sees `mode == request` and `normalized == display`,
     and returns. The Engine is never told, so the obvious in-app remedy does nothing.

  The reverse case (prior OFF, bank ENHANCED) shows "Off" while it records with stabilization, and
  it also triggers a session reconfigure on the next real change.
- Suggested fix, pick one owner:
  - (a) Make the Engine own both requests. Add `videoStabMode` and `videoFrameRate` to
    `OpticsSnapshot`, restore them in `commitOpticsRollbackLocked`, and publish them in
    `OpticsRollbackPublication`. The VM rollback leg then reads `engine.currentVideoStabRequest()`,
    the same way `requestedVideoResolution = engine.currentRequestedVideoSize()` already does at
    `ui/CameraViewModel.kt:1109`. Delete the VM-only revert.
  - (b) Minimal fix: in the MRG5-8 leg, when the VM request is reverted, also call
    `engine.setVideoStabMode(restore.priorStabMode)` and `applyVideoFrameRate(...)`.
  - Also drop the early return at 3515, or compare against the Engine's request instead of the VM
    mirror.
  - Robolectric test: a recall with OFF over ENHANCED, an async rollback, then assert that the
    display, `engine` request and controller wire mode all read ENHANCED. A second test should
    re-select Active after the rollback and assert that the Engine receives it.
- PMA110 behaviour change: only on the failed-recall path, where it becomes correct.

### AR6-2 (Low-Medium): a superseded recall loses its VM rollback record

- Severity: Low-Medium. Confidence: High. Status: confirmed from code.
- Cites:
  - `camera/CameraEngine.kt:799-805`. `beginOpticsTransaction` selects the baseline with
    `selectRollbackBaseline(cameraReady, …, opticsRollbackBaseline)`. While a recall (generation N)
    is still un-Ready, a newer door N+1 inherits the PRE-recall Ready baseline. The comment there
    says "Both must roll back to the last Ready state". The superseded recall N itself never rolls
    back.
  - `ui/CameraViewModel.kt:1699-1708`. The VM records `RecallRollbackRestore(generation = N, …)`.
  - `ui/CameraViewModel.kt:1153-1154`. On rollback it checks
    `recallRollbackRestore?.takeIf { it.generation == rollback.generation }` and then sets
    `recallRollbackRestore = null`. A rollback of N+1 does not match N, so the record is thrown away.
- Why it is a problem: the restore record exists because the recall's VM-only state belongs to the
  same transaction as the Engine optics it armed. That state is the pre-inventory
  codec/transfer/formats (AGG5-23), the cancelled-hold AE lock (AGG5-24) and the stab/fps requests
  (MRG5-8). MRG5-6 correctly stopped keying the record on a re-read generation. But the Engine's
  rollback scope is "everything since the last Ready", and the record's scope is "exactly
  generation N". Those two scopes differ whenever a recall is superseded.
- Failure scenario:
  1. The operator holds AEL (momentary), so the live `aeLock` is true and the snapshot is false.
  2. Recall MR2. The recall cancels the hold, records `aeLockPrior = false`, and starts generation N.
  3. Before Ready, tap the 10× preset (N+1). The 10× lens is unavailable, so N+1 rolls back to the
     pre-recall baseline. That baseline's controls carry the HELD `aeLock = true`.
  4. The VM drops the N record, and the rollback's `scheduleSettingsSave()` persists a latched AE
     lock. This is the AGG5-24 symptom. The same path leaves a pre-inventory AVC / S-Log3 /
     JPEG-only bank armed and persisted (AGG5-23), and leaves the bank's stab/fps request persisted
     (MRG5-8).
- Suggested fix:
  - Keep restore records in a small list keyed by begin generation. Clear them when an owned Ready
    commit of generation ≥ g arrives, because the recall is accepted at that point.
  - On a rollback, apply every record with `g ≤ rollback.generation`, newest first, because the
    Engine baseline predates all of them.
  - Alternatively, have the rollback publication carry the baseline's own generation and restore
    every record whose g is greater than it.
  - Test: recall, then a superseding failing door, then assert the AE lock, the pending codec and
    the stab/fps requests are back at their pre-recall values.

### AR6-3 (Low): the route-inventory fold (A2.9) skips the pending-controls half of the remap hygiene

- Severity: Low. Confidence: Medium. Status: likely. Needs a topology-change run (EXTERNAL
  plug/unplug, or a front-only device) to see it.
- Cites:
  - `ui/CameraViewModel.kt:876-892`. The fold resets `controls.zoomRatio = 1` (via
    `cameraRoutePublishedState`, `:4862-4866`) on the setup thread. It then calls
    `mainHandler.post { invalidateOpticsDerivedState() }`.
  - `ui/CameraViewModel.kt:328-332`. `applyControlsRunnable` pushes `pendingControls` WHOLESALE
    through `engine.setControls`, including whatever zoom it captured.
  - By contrast, every VM optics door and the rollback leg (`ui/CameraViewModel.kt:1091`) call
    `cancelPendingControls()` before re-scaling zoom.
- Why it is a problem: the zoom scale changes, but the 40 ms trailing packet captured in the old
  (rear, unified) scale is not cancelled. When it fires, it writes the old ratio to the Engine on
  the lens-local route. The VM state then reads 1× while the wire carries the stale ratio, or its
  caps-clamped value. There is a second effect: the invalidation runs at some later point on main,
  so it can also cancel a glide or trailing flush that a NEWER main-thread door has already
  started.
- Suggested fix: run the fold's main half (`cancelPendingControls()` plus
  `invalidateOpticsDerivedState()`) in one main-thread task. Better still, fold the zoom reset into
  that same main task, so that the state rewrite and the hygiene are atomic with respect to main
  input. Test: schedule a throttled controls apply, deliver a route-changed inventory, and assert
  that `engine.setControls` never receives the old zoom.

### AR6-4 (Info): log-quota enforcement became lexical when gated rows moved to raw `android.util.Log`

- Cites: `715ccb0a`; `tools/check_docs.py` `debug_log_classification_inventory` (`RECURRING_GATES`,
  with the `before` window of 1,200 characters); 46 `android.util.Log.[di](` sites under
  `app/src/main/kotlin`.
- Why: before AGG5-9, a gated row still went through the bounded `DiagnosticLog` facade, so a wrong
  or missing guard was still bounded. To stop the double charge, these rows now bypass the facade.
  The gate classifies a raw call as "recurring_budgeted" if a gate token appears anywhere in the
  previous 1,200 characters, even when that token guards a different statement. Today all 46 sites
  are genuinely guarded; I checked each against its preceding five lines. But a raw
  `android.util.Log.i` placed after an unrelated guarded block in the same function would pass the
  check. That would be an unbounded producer, which is exactly the ColorOS-quota class CLAUDE.md
  says the inventory now excludes ("severity or an anchor string no longer pretends…").
- Suggested fix: add one pre-admitted door, for example
  `DiagnosticLog.admittedInfo(tag, msg, admission)`, that takes the admission token the gate
  returned. Then reject raw `android.util.Log.[di]` in `app/src/main` outright. A cheaper option is
  to require that the gate token sits in the condition of the immediately enclosing `if`.

### AR6-5 (Info): tested seams that production does not call

- `camera/CameraStatus.kt:486-493`. `StatusPlate.clearProgress()` is exercised by
  `StatusPlateArbitrationTest` (`:56, :151-152, :162`). Production `clearProgressStatus`
  (`ui/CameraViewModel.kt:2081-2108`) re-implements it inline. The copy differs from the pure version
  when a PROGRESS condition is shown: the pure version also clears `deferredEvent`, and the copy does
  not. That state looks unreachable today, but the copy is what ships, and the tests prove the other
  one. Route production through `currentStatusPlate().clearProgress()` followed by
  `recordStatusPlate`.
- `ui/CameraViewModel.kt:3978-3990`. After AGG5-22, `recallMemorySlot` and the `lastAppliedRecall`
  field have no production caller; only `OpticsRecallTransactionRobolectricTest` uses them. Make
  them `internal` with `@VisibleForTesting`, or have tests drive `onRecallMemorySlot` and read the
  state.

### AR6-6 (Info): god-object growth and request mirrors

- Line counts `ea7d4374` → `30970c9e`: `camera/CameraEngine.kt` 9142→9365, `ui/CameraViewModel.kt`
  4877→5202.
- The VM now keeps five operator-request mirrors beside the displayed values:
  - `requestedVideoResolution`
  - `requestedVideoStabMode`
  - `requestedVideoFrameRate`
  - `pendingCodec/Transfer/PhotoFormatsUntilInventory`
  - `punchInBeforeAuto` / `momentaryPunchIn` / `momentaryAeLock`

  Each has its own rollback rule. One of them is re-read from the Engine
  (`requestedVideoResolution`), two are VM-only reverts, and one is a field-by-field compare. AR6-1
  and AR6-2 are what that inconsistency produces.
- Direction for a later cycle: one immutable `OperatorRequest` packet owned by the Engine's optics
  snapshot and published with every rollback/Ready. The VM would read it rather than mirror it.
  `currentExtras` then persists `engine.request` and nothing VM-private.
