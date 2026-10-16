# FD Code Reviewer — RPL cycle 2

Scope: `git diff ba5b16e7..HEAD -- app/src/main` (20 files), with emphasis on the DNG route-scale
door (`CameraEngine.setRawWanted`, `remapRouteScaleOptics`, `restoredOptics(photoStandalone)`),
the DNG rollback (`rawWantedDirectWrites`), the exposure handoff (`exposureModeHandoff`,
`withShutterModeTakingOwnership`), token-scoped recorder worker admission, the tri-state
finalized-video probe, identity-read warning gating, prior-family delete tracking, and
SettingsStore bounds. Read-only pass; ~30 tool calls.

## High-confidence findings

No high-confidence findings.

Checked and found sound:
- `rollbackOptics` only restores `rawWanted` while `rawWantedDirectWrites` matches the baseline, and
  `rollbackOpticsState` already refuses a superseded generation, so a newer DNG door cannot be
  reverted by an older failed transaction. The VM mirrors `rollback.rawWanted` into `photoFormats`.
- `lensBandFollowsZoom` is applied at all three band re-derivation sites with the correct
  video-flag argument (`enabled` / `enabledVideo` are the mode flags at those sites).
- `RecorderQuarantineAdmissionGate`: reverting owner-keyed admission to "any pending token blocks"
  matches pre-0ab5c1ba behavior; the only owner-keyed GL caller is `GlPipeline.start` EGL init, not
  the per-frame encoder swap, so REC publication cannot deadlock. Worker doors go by exact token and
  the token is released only after strict finalization.
- `RETAINED_VALIDATION_UNAVAILABLE` is mapped to `RETAINED_PENDING` in
  `recordingStorageTerminalDisposition`; `classifyFinalizedVideoTrack` only returns INDETERMINATE
  on open failure (`openReadableParcelFd` throws rather than returning null).
- Composable parameter reorders (TopBar/ShutterRow/ShutterButton) cannot silently mis-bind: the
  moved parameters have types that would fail compilation if passed positionally.
- `exposureModeHandoff` reads `autoExposure` of the OUTGOING controls inside the update lambda, and
  `refreshProgramAppSide(seedFromLive = outgoingHalAe)` captures the flag before the update.

## Below-threshold note (not a confirmed bug)

### FD2-N1 — MR recall that flips DNG runs two optics transactions; a failure of the second rolls back to a mixed-scale packet
- Files: `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1429` (`setResolvedOptics`)
  then `:1492` (`engine.setRawWanted(safeFormats.dngRaw)` with no resolved optics);
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4045` (transaction lambda) and
  `:1011` (rollback).
- Scenario: live PHOTO/BACK MR recall whose bank's DNG differs from the current selection on a
  RAW-law device (PMA110). `setResolvedOptics` publishes T1 with `controls.zoomRatio` already in the
  TARGET scale (`restoredRouteStandalone` uses the recalled `dngRaw`). `setRawWanted` then flips the
  route and begins T2, whose baseline snapshot is T1's packet (target-scale zoom) with the OLD
  `rawWanted`. If T2's reopen fails, rollback restores that baseline: logical route (old DNG) carrying
  a lens-local ratio (e.g. 3x lens at local 1.0 lands the logical camera at unified 1.0), or the
  reverse. Failure path only; the operator does get a failure status.
- Fix: carry `rawWanted` inside the `setResolvedOptics` packet (one transaction, like the DNG door's
  own `resolvedLens/resolvedControls`), or have the recall call `setRawWanted` BEFORE
  `setResolvedOptics` so T1's baseline already reflects the DNG route.
- Confidence: 55 (logic traced; reopen-failure trigger not reproduced).
