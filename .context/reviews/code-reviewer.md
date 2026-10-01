# RPL cycle 3 — code-reviewer (CR3-) — HEAD e3a2bdd4

Scope: logic bugs in camera/, capture/, storage/, video/, ui/ (ViewModel reducers, settings restore,
zoom scale conversion, capability normalization), with priority on regressions introduced by the
39 cycle-2 fix commits (c2892dda..e3a2bdd4). Read-only; no Gradle run. Every finding below was
traced in code; none is device-verified. Owner decisions in CLAUDE.md are not relitigated.

Summary: 4 findings (0 Critical, 0 High, 2 Medium, 2 Low). Two are new side effects of cycle-2
commit b5c57e8a (Ready restored after a reconfigure preflight failure). One re-reports a cycle-2
lane note with new evidence that it reaches every standalone route, not only TELE.

---

## CR3-1 — Leaving FRONT re-zooms every standalone rear route through a value-picked divisor (Video UW/MAIN/3×, DNG Photo), not only TELE

- Severity: Medium. Confidence: High. Status: Confirmed in code. The device symptom is predicted, not measured.
- Location: `camera/CameraState.kt:606-614` (`rearReturnZoom`). Callers: `camera/CameraEngine.kt:3985-3996`
  (`setFrontCamera`, leaving branch) and `ui/CameraViewModel.kt:2993-3007` (`onToggleFrontCamera`
  mirror).
- Relation to earlier cycles: the cycle-2 plan's lane notes carry "`rearReturnZoom` leaving FRONT
  with a lens-local zoom past 3.33 uses the value-picked divisor" as a later-cycle item, framed as
  the TELE case of AGG2-5. **New evidence:** the defect is not specific to TELE. It hits any
  standalone route whose lens-local zoom sits past the next optical preset, and that includes plain
  Video at 2× on the ultrawide.
- Why: entering FRONT snapshots canonical framing with a **lens-based** base:
  `preFrontRearUnifiedZoom = unifiedZoomOf(lens = lensChoice, zoomRatio, standaloneRoute, optical)`,
  which is `opticalBaseFor(lens.zoomPreset) × local`. Leaving FRONT converts back with
  `localZoomOf(unified, optical)`, whose base comes from the **ratio**: `opticalBaseFor(unified)`.
  But `lensChoice` is left unchanged, and on a standalone route `resolveNonTeleId(lensChoice)` reopens
  that same lens. So the two conversions use different divisors whenever `unified` crosses into a
  higher optical band than the lens. AGG2-5 fixed exactly this asymmetry for the settings-save path
  (`retainedRearWireZoom`, which divides by the LENS base), but it did not fix the live return path.
- Failure scenarios on PMA110 (all optical presets present):
  - Video, ULTRAWIDE at local 2.0: unified = 0.6×2 = 1.2. On return, `localZoomOf(1.2)` uses the
    MAIN base, so the zoom is 1.2 on the **ultrawide** lens. Framing goes from 1.2× to 0.72×.
  - Video, MAIN at local 4.0: unified = 4.0. The return uses the 3× base, so the zoom is 1.33 on
    **MAIN**. Framing goes from 4× to 1.33×.
  - Video, the 3× lens at local 4.0: unified = 12. The return uses the 10× base, so the zoom is 1.2
    on the **70 mm** lens. Framing goes from 12× to 3.6×.
  - Photo with DNG (standalone on PMA110): the same three cases apply.
  - TC on before FRONT: TC is forced off and `lensChoice` stays TELE3X, so this is the case the
    lane note already named.
  - In every case the OSD focal/zoom readout follows the wrong ratio, and the next settings save
    (taken after the return, now on BACK) persists it. The same trip without the selfie flip keeps
    the framing, and a save made while FRONT persists the correct lens-based value
    (`retainedRearWireZoom`). As a result, "kill while FRONT" restores 4× while "flip back"
    shows 1.33×.
- Fix (host-testable): make `rearReturnZoom` the exact inverse of the entry snapshot. Divide by
  `opticalBaseFor(lensPreset, optical)`, which is the same rule as `retainedRearWireZoom`, and pass
  the lens from both callers. Alternatively, when the target is standalone, re-band the lens with
  `LensChoice.forZoom(unified)` together with `localZoomOf` in BOTH Engine and VM. That is the pair
  `resolveTeleZoomTransition` and `remapRouteScaleOptics` already use. Add a round-trip test over
  {UW 2.0, MAIN 4.0, TELE3X 4.0} × {Video, DNG Photo}: entry snapshot, then return, must reproduce
  the same (lens, local).

## CR3-2 — b5c57e8a restores Ready on the OLD session after a preflight failure of a "mutate-then-reopen" door, so REC runs with a video size the camera stream was never configured for

- Severity: Medium. Confidence: Medium. Status: Likely, because it needs a transient
  `selectCurrentLens()`/caps-read failure, which is exactly the service hiccup AGG2-4 targets.
- Location: `camera/CameraEngine.kt:3792-3805` (`applyVideoSize` mutates `videoSize`,
  `previewStreamSize`, the GL preview size and the aspect, THEN calls the bare `reopenForSession()`),
  `:3395-3397` + `:950-956` (`currentOpticsReconfiguration` snapshots `before` AFTER those
  mutations, at the current generation), `:4202` / `:4236-4262` (preflight failure →
  `rollbackOpticsAfterPreflight`), `:8525-8538` (`rollbackRestorableSessionGeneration`), and
  `:1039-1049` (rollback writes back `videoSize`/`previewStreamSize` from that post-mutation
  `before`).
- Why: the cycle-2 fix makes a rollback restorable when the session generation equals the one the
  failing door's own invalidation produced. That is sound only if `transaction.before` describes
  the session the old controller is really streaming. Transactions begun by
  `beginOpticsTransaction` do describe it. The bare `reopenForSession()` callers do not:
  `setVideoResolution → applyVideoSize`, `setVideoStabMode`, `setAspectRatio`/`setHiResStill` (when
  hi-res flips), and `setVideoFrameRate` (high-speed boundary). Each of them mutates first and
  snapshots afterwards. So `before.ready == true`, `before.readyController === controller`, and the
  "restore" writes the NEW `videoSize` and `previewStreamSize` back over an old controller whose
  Camera2 stream is still the OLD size. Before b5c57e8a this path stayed Not-Ready, so capture and
  REC were refused. Now it publishes Ready.
- Failure scenario: Video at 1080p. The user picks 4K. `applyVideoSize` sets
  `videoSize = previewStreamSize = 3840×2160`, calls `gl.setCameraPreviewSize(4K)` and emits a 4K
  aspect. The reopen's preflight then fails (`selectCurrentLens()` null, or a caps read throws). The
  rollback restores Ready on the 1080p session with status "camera unchanged". The REC admission
  packet freezes `videoSize = 4K` against a 1080p producer. The encoder therefore records a 4K file
  upscaled from the 1080p stream, or a mis-shaped frame for an Open Gate size. The preview
  SurfaceTexture default size no longer matches the producer either, which the code's own comment
  at `:3800-3803` names as a broken stream contract. The UI already shows 4K selected, and the size
  is persisted. Nothing re-converges until another door reopens.
- Fix:
  - Preferred: give the bare reopen callers a real pre-mutation baseline. Run their mutation inside
    `beginOpticsTransaction`, as `setVideoMode` does, so `before` is the streaming session's state.
  - Minimum: keep the AGG2-4 Ready restore only for transactions whose `before` was snapshotted
    before mutation. For example, pass `preflightSessionGeneration = null` from
    `reopenForSession(expectedController, currentOpticsReconfiguration())` paths. Or restore Ready
    only when the restored `previewStreamSize`/`videoSize` equal the installed controller's
    configured stream size.
  - Host test: `applyVideoSize` followed by an injected preflight failure must not publish Ready
    with a `videoSize` different from the accepted stream.

## CR3-3 — Preflight-failure rollback restores Ready while the wire keeps the retired tap-AF override

- Severity: Low. Confidence: Medium. Status: Likely, with the same transient preflight-failure
  trigger as CR3-2.
- Location: `camera/CameraEngine.kt:548-565` (`invalidateCameraReady` →
  `retireTapFocusLocked(rebuildPreview = false)`), `:523-537`, `camera/CameraController.kt:1986-1995`
  (`clearMeteringPoint(rebuildPreview = false)` sets `touchAfActive = false`,
  `tapResetPending = true` and does not rebuild), and `:566`, `:1725` (only the NEXT fast-path call
  turns `tapResetPending` into a `startPreview()`).
- Why: before b5c57e8a, an invalidated-then-rolled-back session stayed Not-Ready, so the stale
  repeating request did not matter. Now `rollbackOpticsAfterPreflight` re-accepts the same
  controller. Its cached repeating request still carries the tap's `CONTROL_AF_MODE_AUTO` hold (or
  the AF-lock `AF_MODE_OFF` plus frozen distance) and the metering regions. Meanwhile the Engine has
  retired the tap owner, and the VM's rollback leg calls `clearTapFocusUi()`.
- Failure scenario: the user taps to focus at 10 m, then switches lens, and that door's preflight
  fails. The UI comes back Ready with no reticle and AF-C shown, but the lens stays parked at 10 m
  and does not track until some unrelated control change happens to trigger the pending rebuild. A
  photographer re-framing on a near subject shoots out of focus under an AF-C indicator.
- Fix: in the restorable branch of `commitOpticsRollbackLocked`, or in
  `executeOpticsRollbackEffects` when the session is restored, queue one
  `controller.startPreview()` (camera handler, outside the monitor) so `tapResetPending` is consumed
  immediately. Alternatively, retire tap focus with `rebuildPreview = true` once rollback has decided
  to keep the controller. Host test: a restored-Ready rollback leaves no pending tap reset on the
  restored controller.

## CR3-4 — MR recall's trailing session setters issue a second same-generation reopen behind the recall's own reconfigure

- Severity: Low. Confidence: Medium. Status: Likely. This is a perf and visual issue; the end state
  converges.
- Location: `ui/CameraViewModel.kt:1500-1522` (after `setResolvedOptics` returns: the trailing
  `engine.setVideoStabMode`, `setAspectRatio`, `setHiResStill`, `setVideoFrameRate`, `setOpenGate`),
  and `camera/CameraEngine.kt:2028-2041`, `:3683-3693`, `:3748-3754`, `:3366-3370`, `:3395-3426`.
- Why: each of those setters calls the bare `reopenForSession()` when its resolved value changes.
  That captures `currentOpticsReconfiguration()` at the recall's own generation, because no new
  transaction is begun. It invalidates again and enqueues a second `reconfigureCamera` on
  `setupExecutor` behind the recall task. `sessionReopenMayProceed` passes, since the generation is
  still current. The camera is therefore closed and reopened twice for one recall: the recall's
  reconfigure reaches Ready, then the trailing reopen tears it down again. This is the
  "transaction + trailing setters" shape AGG2-39 describes at the architecture level. This finding
  adds the concrete runtime effect: a doubled black dip and a doubled ~0.5 s bring-up on every recall
  that changes stabilization class, hi-res admission, or the high-speed boundary.
- Fix: fold `videoStabMode` and `aspectRatio`/`hiResStill` (the session-shaping inputs) into the
  `setResolvedOptics` packet, as AGG2-2 did for `rawWanted`. Alternatively, have the bare reopen
  skip when the current generation is already an in-flight optics transaction that has not reached
  its terminal commit. The pending reconfigure will configure from the updated fields anyway.

---

## Checked and found sound (no finding)

- `StillContinuationHandoff` (AGG2-3): settling exactly once, synchronously or asynchronously,
  across BURST/AEB/timelapse; recursion is bounded by `BURST_COUNT` or the bracket length.
- `rollbackRawWanted` / `dngIntentChangesRearRoute` (AGG2-1/AGG2-10): checked direct writes under
  TC, FRONT and Video against restored routes. The TELE DNG no-reopen path stays consistent with the
  VM's `dngDoorRemapsZoomScale`.
- AGG2-4 Ready-restore for the camera-fault recovery reopen: `opticsRollbackBaseline` is null after
  every Ready commit, and the fault leaves `cameraReady = false`, so `before.ready` is false and a
  faulted controller is never re-published Ready.
- `AutoExposure.driveProgram` clamp-residual carry (AGG2-16): the sign and both clamp branches are
  correct; inside the span the residual is 0.
- `withIsoTakingOwnership` / `withShutterNsTakingOwnership` / `withShutterAngleTakingOwnership`
  (AGG2-17): seed only from HAL-AE P, as intended.
- `PhotoFormats.withEdit` pre-inventory merge (AGG2-8): the chip-enable guards prevent an empty
  request, and normalization keeps DNG.
- Launch recovery `exhaustedCursor` (AGG2-21): progress is guaranteed and `hasMore` stays valid.
  The DISCARD and preflight stages are unaffected.
- `classifyFinalizedVideoTrack` → INDETERMINATE on an extractor throw (AGG2-18). The `retireStartupDeadline`
  `shutdown()` does purge the cancelled delayed task (STPE `onShutdown` removes cancelled tasks).
- `DngWriteResult.Failed` now carries the publication and retains through
  `retainCompletedDngForRecovery`; `finishDng` still runs from the callback's `finally`.
- `SettingsStore` load bounds: `jpegQuality` is unbounded at load but is clamped 1..100 at both use
  sites (`CameraEngine.kt:5516`, `ManualControls.kt:686`).
- `remapRouteScaleOptics`, `remapModeOptics`, `resolveTeleZoomTransition`, `restoredOptics`: every
  one pairs `forZoom(unified)` with `localZoomOf(unified)` (or the lens base with `unifiedZoomOf`),
  so they round-trip. `rearReturnZoom` is the one function that mixes the two (CR3-1).
- ZSL ring feed is gated on `zslStreamingActive()` (`CameraController.kt:2230`), so the AGG2-20 flush
  cannot be re-filled after the edge.

## Files examined

- Engine/controller: `camera/CameraEngine.kt` (optics transactions, rollback, preflight, setRawWanted,
  setResolvedOptics, setLens, setFrontCamera, setVideoResolution/applyVideoSize, bare reopen callers,
  drive chains, DNG callback, fault recovery), `camera/CameraController.kt` (metering, rawChars
  accessor, setPinAutoFps/ZSL, clearMeteringPoint, AF overrides, capturePhoto),
  `camera/DngPreCaptureAllocation.kt`, `camera/CameraState.kt` (zoom helpers, LensChoice, LensInventory,
  rearReturnZoom, PhotoFormats), `camera/ManualControls.kt` (handoff/ownership, AEB),
  `camera/AutoExposure.kt`, `camera/CaptureCapabilities.kt` (exposure ceiling), `camera/DeviceProfile.kt`,
  `camera/StandbyAudioController.kt` (cycle-2 diff), `camera/DiagnosticTelemetry.kt` (cycle-2 diff),
  `camera/LaunchMediaRecoveryCoordinator.kt`.
- Capture/storage/video: `capture/StillCapturePipeline.kt` (processed save, saveDng),
  `storage/SettingsStore.kt` (load/save/bounds), `storage/MediaStoreWriter.kt` (cycle-2 diff: warning
  gate, interrupt-preserving backoff, recovery cursor, finalized-video probe),
  `video/VideoRecorder.kt` (cycle-2 diff).
- UI: `ui/CameraViewModel.kt` (Ready/rollback mirrors, applyLoaded, recall, onSetPhotoFormats,
  encoder inventory, zoom inputs, FRONT door, countdown, onStop/onCleared), `ui/ZoomMath.kt`,
  `ui/ZoomGlideState.kt`, `ui/CaptureOutputTracker.kt` (record/seed), `ui/controls/ControlCycles.kt`,
  `ui/controls/ProControls.kt` (PhotoFormatToggles), `ui/controls/ProSheet.kt` (zoom slider guard),
  `MainActivity.kt` (recall/audio-denial path, key routing overview), `CameraPermissionPolicy.kt`.
- Context: `CLAUDE.md`, `.context/reviews/archive-rpl-cycle2-2026-10-02/_aggregate.md`,
  `docs/plans/2026-10-02-rpl-cycle2.md`, `git log`/`git diff c2892dda~1..HEAD`.
- Not examined in depth this pass: `gl/*` beyond the cycle-2 diff, `ui/CameraScreen.kt`,
  `ui/review/MediaReview.kt`, `ui/overlays/*`, `video/VideoRecorder.kt` outside the diff, and
  `storage/PendingDiscardJournal.kt`. Those are covered by other lanes (perf, fd, designer).
