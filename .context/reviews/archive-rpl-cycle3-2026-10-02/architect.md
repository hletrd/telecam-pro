# Architect review — RPL cycle 3 (HEAD e3a2bdd4)

Angle: ownership of state across Engine / ViewModel / GL / Controller, duplicated sources of truth,
transaction and rollback design. Prior cycles' ARCH2-1..7 / AGG2-39/40 were read first. ARCH2-1,
ARCH2-3 (recall split) and AGG2-7 are fixed at HEAD (4edd2a14, b565abae, 4d0c5e3c). The findings below
are new, or are carried items with new evidence. Most of them come from the cycle-2 fixes. Those
fixes added per-field "keep newer direct write" rules and a restorable-preflight rollback. The rules
are right one field at a time, but they leave new ways for the Engine packet and the ViewModel packet
to drift apart.

## Findings

### ARCH3-1: A rollback publication is dropped whole when any newer door begins between the setup-thread commit and the main-thread post. The ViewModel then keeps the failed door's optimistic packet for every field the newer door does not republish

- Severity: Medium. Confidence: Medium. Status: Likely (code-confirmed; the timing window needs a device repro).
- Where: `ui/CameraViewModel.kt:933-1011` (`onOpticsRollback`: `if (!engine.isOpticsGenerationCurrent(rollback.generation)) return@post`); `camera/CameraEngine.kt:1009-1086` (the Engine restores its fields under the monitor and only then posts the packet); `camera/CameraEngine.kt:778` (the next door snapshots the Engine's RESTORED fields as its baseline).
- Why: the Engine commits its rollback on `setupExecutor` and the UI mirror is applied later, from a
  `mainHandler.post`. A door that starts on main inside that window (lens tap, TC, mode, DNG, recall)
  bumps `opticsIntentGeneration`. The posted mirror then drops all of its fields:
  `photoFormats.dngRaw`, `pendingPhotoFormatsUntilInventory`, `photoExposureTimeNs`,
  `preTeleUnifiedZoom`, `requestedVideoResolution`, the converter declaration, mode, lens and
  controls. The newer door built its target from the ViewModel's `_state`, which still holds the
  FAILED packet. The Engine's baseline for that door is the restored packet. The two sides now
  disagree, and nothing republishes the fields the newer door did not touch.
- Failure scenario: Photo, DNG off, logical camera. The operator turns DNG on (route-flip door A: the
  ViewModel publishes `dngRaw=true` and lens-local zoom). A's preflight fails, and the Engine
  restores `rawWanted=false` and unified zoom. Before the post runs, the operator taps 3× (door B).
  The ViewModel computes B from `dngRaw=true` (standalone, lens-local `3/opticalBase`). The Engine
  computes from `rawWanted=false` (logical, unified). The posted rollback is discarded. The UI now
  shows DNG on while the Engine wants a logical session, which is the AGG-4 shape again.
  `setRawWanted`'s change gate (`rawWanted == enabled → return`, `CameraEngine.kt:4080`) does not
  repair this: the next DNG-off tap remaps the ViewModel's zoom scale while the Engine ignores the
  packet. Settings persist the hybrid on the next `scheduleSettingsSave`.
- Fix: when a rollback post is stale, do not drop it whole. Split the publication into (a)
  generation-owned visible optics, which a newer door legitimately replaces, and (b) Engine-truth
  mirrors, which must always converge. For (b), read the live Engine values on main, as
  `requestedVideoResolution = engine.currentRequestedVideoSize()` already does: `rawWanted`,
  `photoExposureTimeNs`, `preTeleUnifiedZoom`, and the declaration. Alternatively, have every door's
  VM-side target computation read those route inputs from the Engine and not from `_state`.
  Host-testable: a Robolectric test that commits a rollback, queues a second door before draining
  main, and asserts `state.photoFormats.dngRaw == engine.rawWanted` afterwards.

### ARCH3-2: The rollback mirror reads `requestedVideoSize` live but `rawWanted` from the frozen packet. A direct DNG write in the post window is reverted in the ViewModel only

- Severity: Low. Confidence: Medium. Status: Confirmed (code).
- Where: `ui/CameraViewModel.kt:960` (live `engine.currentRequestedVideoSize()`) vs `:985-990` (`rollback.rawWanted` from the packet); `camera/CameraEngine.kt:4099-4108` (direct, uncounted-generation DNG write on TELE, FRONT, or before start).
- Why: AGG2-7 fixed this exact race for the video size: "a pick made on this main queue after the
  rollback committed is a newer direct write the engine kept, and the packet would revert only the
  mirror". DNG has the same kind of direct write: since 5e4cc454, any DNG toggle with TELE on is a
  direct write that does not bump the generation. The rollback post is still current, so it
  overwrites the ViewModel's `dngRaw` with the packet's older value.
- Failure scenario: TELE on, door A (for example, a mode flip) fails. While the post is queued, the
  operator taps DNG on, and the Engine keeps `rawWanted=true`. The post then sets the UI to DNG off
  and persists it. Captures follow the UI's formats (no DNG). Later the operator turns TC off. The
  Engine resolves `resolveNonTeleId` with `rawWanted=true` (standalone main, lens-local). The
  ViewModel's TC-off zoom target uses `dngRaw=false` (unified), so the operator sees a wrong scale
  and has lost seamless zoom for a DNG they do not appear to have selected.
- Fix: mirror `rawWanted` the same way as the video size. Add an
  `engine.currentRawWanted()` accessor and read it on main. Test: commit a rollback, perform a TELE
  DNG toggle before draining main, and assert that the UI equals the Engine.

### ARCH3-3: The "direct write since baseline" counters cannot tell who wrote last. With a stacked baseline, a FAILED transaction's own write survives rollback as if it were the operator's direct write

- Severity: Low-Medium. Confidence: High (code). Status: Confirmed (code).
- Where: `camera/CameraEngine.kt:778` (`selectRollbackBaseline` reuses `opticsRollbackBaseline` while Not-Ready); `:1043-1047` (`keepNewerDirectWrite` for `requestedVideoSize`); `:1055-1063` and `:8504-8525` (`rollbackRawWanted`); `:2834-2836` (`setResolvedOptics` writes `rawWanted` and `requestedVideoSize` inside its transaction, uncounted).
- Why: the rule compares the CURRENT counter with the BASELINE's counter. When door B begins while
  door A is still Not-Ready, B inherits A's baseline. A direct write between A and B moves the
  counter. B then overwrites the same field transactionally without counting. On B's rollback,
  `directWriteSinceBaseline` is true, so `current` is kept, but `current` is B's write, not the
  operator's. The comment at `:1040-1042` ("only the transaction's own write (a recall's
  recalledVideoSize) rolls back with it") does not hold in this case.
- Failure scenario: a mode-flip door A is in flight. The operator picks 1080p in the picker
  (counted). The operator then recalls an MR bank with 4K and DNG on (door B). B fails with
  `CAMERA_UNAVAILABLE_RECALL_UNCHANGED`. The Engine keeps `requestedVideoSize = 4K`. The ViewModel
  mirrors and persists it. The status says the recall was unchanged, but the bank's size (and, when
  the restored route is TELE/FRONT so the keep rule allows it, its DNG intent) leaked into the live
  and persisted request. The 1080p pick is lost. A secondary issue: `applyVideoSize`
  (`:3793-3805`) writes `videoSize`/`previewStreamSize` outside the transaction, and rollback restores
  them unconditionally (`:1038-1039`, `:1048`). After a failed door, the kept request and the
  delivered stream size therefore differ, and nothing triggers convergence until the next reopen.
- Fix: replace the counters with a last-writer stamp per field: `DIRECT` or the transaction
  generation that wrote it. Stamp it in every direct setter and in each `beginOpticsTransaction`
  publish lambda that assigns the field. Rollback keeps `current` only when the stamp is `DIRECT`
  and was written after the baseline. A pure helper can be host-tested: (A begin, direct write,
  B transactional write, B rollback) restores the direct value.

### ARCH3-4: The restorable-preflight rollback (b5c57e8a) restores Ready but not the side effects of `invalidateCameraReady()`. The tap-AF hold is released in Engine/UI while the wire keeps it, and a pending DNG shot is cancelled for a camera that never changed

- Severity: Low. Confidence: Medium. Status: Likely (code). The wire state needs a device check.
- Where: `camera/CameraEngine.kt:548-565` (`invalidateCameraReady`: `cancelDngPreCaptureAllocations()`, then `retireTapFocusLocked(rebuildPreview = false)`); `:973-984` / `:8531-8549` (the rollback re-accepts the same controller); `camera/CameraController.kt:1986-1995` (`clearMeteringPoint(false)` sets `tapResetPending` and leaves the cached repeating builder with `AF_MODE_AUTO` + regions); `:566`, `:1725` (the reset is applied only at the next fast-path or full rebuild).
- Why: AGG2-4's fix treats the door's own invalidation as "retired no camera" and re-accepts the
  outgoing controller. The invalidation did more than bump the generation, though. It retired the
  tap owner without rebuilding the preview and cancelled every DNG pre-capture allocation. Neither
  is undone. The new rule's premise that "nothing touched the session" is only half true.
- Failure scenario: the operator taps to focus (AF_MODE_AUTO hold), then taps a lens whose
  characteristics read fails (`CAMERA_UNAVAILABLE_CAMERA_UNCHANGED`). Ready comes back. The UI
  reticle and the Engine say AF-C. The HAL request still holds AF_MODE_AUTO at the tapped distance
  until some control or zoom write runs the deferred reset, so focus does not follow the subject. A
  DNG shot whose pre-allocation was in flight is also refused, even though the camera "remained
  unchanged".
- Fix: in `rollbackOpticsAfterPreflight`'s restore branch, call `clearMeteringPoint(rebuildPreview =
  true)` on the restored controller (or rebuild once from the effects packet) so the wire matches
  the retired tap. Alternatively, delay tap retirement and DNG cancellation until after preflight
  succeeds, just before the outgoing controller is closed. The second option is cleaner: the
  invalidation would then really "retire no camera".

### ARCH3-5: (carried AGG2-40 / ARCH2-5, new evidence) The cycle-2 fix moved one ViewModel site to the Engine's RAW law, and eight sites still read the `CameraUiState` copy that the same fix documents as wrong. Two ViewModel doors can now answer the route question differently

- Severity: Low-Medium. Confidence: Medium. Status: Confirmed (code).
- Where: `ui/CameraViewModel.kt:2490` (DNG door: `engine.rawForcesStandalone`) and `:1346`, `:1404`, `:2785` (Engine) vs `:2273`, `:2402`, `:2604`, `:2854`, `:2890`, `:2972`, `:3001`, `:3126` (state copy); `ui/ZoomMath.kt:448-469` (KDoc: the state copy "defaults to the PMA110 answer until the first route inventory"); `camera/CameraState.kt:1677` (default `true`); `ui/CameraViewModel.kt:776-784` (the copy refreshes only on route-inventory publication).
- Why: the ViewModel now has two answers to "is PHOTO on a standalone route?" inside one class. On a
  GENERIC device before the first inventory, and on every EXTERNAL↔BACK transition where
  `activeDeviceProfile()` changes without an inventory publication, the DNG door (Engine law, no
  flip) and the lens, TC, front-return, zoom-band, and caps-reconcile sites (copy, flip) compute
  opposite zoom scales for the same state. The Engine has a single helper (`lensBandFollowsZoom`);
  the ViewModel still inlines `standaloneRouteWanted(... s.rawForcesStandalone)` in eight places.
- Failure scenario (generic device, DNG on, before the inventory lands): the DNG door correctly sees
  no route flip and keeps unified zoom. A following `applyZoomRatio` (`:2273`) asks the copy, reads
  "standalone", and stops updating the lens band, so the rail stays on the old band while the
  logical camera zooms across lenses.
- Fix: delete `CameraUiState.rawForcesStandalone`. Add one ViewModel helper,
  `photoStandaloneRoute(state) = standaloneRouteWanted(state.mode == VIDEO, state.photoFormats.dngRaw,
  engine.rawForcesStandalone)`, and use it at all nine sites. Pin the helper with a test that has the
  law false and DNG on.

## Final sweep

- **Root pattern:** each fix this cycle added a field-specific reconciliation rule (keep-newer
  counter, route-aware keep rule, live-read mirror, preflight-restorable generation). The rules are
  correct one at a time, but they disagree with each other: video size is mirrored live and DNG from
  the packet; DNG has a route-aware keep rule and video size a plain one; rollback publication is
  generation-gated while direct writes are not. ARCH3-1 through ARCH3-3 come from that mismatch. A
  single `RouteInputs` value (DNG, requested size, pre-TELE zoom, hidden Photo exposure,
  declaration) would remove the class. It would be owned by the Engine, carry a per-field writer
  stamp, and be mirrored to the ViewModel by live read on every publication.
- GL re-seed: `RendererAssists.replayAll` plus the `transfer`/`previewDigitalGain` re-seed in the
  `gl.start` callback (`CameraEngine.kt:1893-1897`) cover every non-route GL setter. Route GL state
  (rotation, sensor orientation, mirror, preview size, zoom target) is re-pushed by the reopen that
  follows GL input. No gap found.
- Lock design: `OpticsCommitGate` uses the Engine monitor (`CameraEngine.kt:464`), so begin/commit
  and `@Synchronized` setters share one lock and there is no lock-order inversion.
  `retireTapFocusLocked` calls the controller under the monitor, but only through non-blocking
  `postToCamera`.
- RAW reader planning is capability-driven (`sessionAttemptPlan(supportsRaw, ...)`), not
  `rawWanted`-driven, so the recall fast path (`resolvedOpticsRequiresReconfigure` without a DNG
  term) cannot leave a session that lacks a reader `rawWanted` needs. Checked; not a finding.
- AGG2-39 (recall = one transaction plus ~25 trailing setters, partial rollback) is still open at
  `ui/CameraViewModel.kt:1497-1520` (aspect, hi-res, open gate, and fps trailing setters). There is no
  new evidence beyond ARCH2-2, so it is not re-reported.

## Files examined

- Engine/transactions: `camera/CameraEngine.kt` (optics snapshot/transaction/rollback 600-1200,
  GL start 1855-1910, setVideoMode/setResolvedOptics 2660-2960, setVideoResolution/applyVideoSize
  3755-3810, reopenForSession 3395-3437, setRawWanted/reconfigureCamera 4068-4260, DNG pre-capture
  4941, pure helpers 8244-8550), `camera/OpticsConstraints.kt`, `camera/CameraState.kt`
  (`standaloneRouteWanted`, UI state defaults), `camera/DeviceProfile.kt` (`deviceProfileForRoute`).
- Controller: `camera/CameraController.kt` (metering/tap reset 1986-1995, fast paths 566/1725,
  `applyAfOverrides` 1764, `sessionAttemptPlan` 2725).
- GL ownership: `camera/RendererConfig.kt`, `camera/RendererAssists.kt`, `gl/GlPipeline.kt` setter
  surface.
- ViewModel: `ui/CameraViewModel.kt` (route inventory 770-800, rollback mirror 925-1011,
  applyLoaded 1340-1560, zoom/mode/DNG/TC/front/caps doors 2260-3130), `ui/ZoomMath.kt`.
- History: commits c2892dda..e3a2bdd4 (4edd2a14, b565abae, b5c57e8a, 5e4cc454, 4d0c5e3c, c6caab59,
  ae359201), `.context/reviews/archive-rpl-cycle2-2026-10-02/{_aggregate,architect}.md`,
  `docs/plans/2026-10-02-rpl-cycle2.md`.
